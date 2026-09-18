#!/usr/bin/env python3
"""WF01 independent EEGMAT oracle and preregistered paired inference.

This script is an implementation transport for the already-frozen
WF01_EEGMAT_FRICTION_PREREG.json. It must not change the hypothesis.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
import struct
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
from scipy.stats import wilcoxon

VERSION = "wf01-eegmat-friction-oracle-1.0.0"
FS = 500.0
WINDOW_SECONDS = 60.0
WINDOW_SAMPLES = 30000
HC_RAW_MEDIAN = 0.7597781027727232
EPS = 1e-6
ELECTRODES = [
    "FP1","FP2","F7","F3","FZ","F4","F8",
    "T3","C3","CZ","C4","T4","T5","P3","PZ","P4","T6","O1","O2",
]
BANDS = [
    ("delta", 0.5, 4.0),
    ("theta", 4.0, 8.0),
    ("alpha", 8.0, 12.0),
    ("beta", 12.0, 20.0),
    ("highBeta", 20.0, 30.0),
    ("gamma", 30.0, 45.0),
]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def _ascii(f, n: int) -> str:
    b = f.read(n)
    if len(b) != n:
        raise EOFError("short EDF header")
    return b.decode("ascii", errors="strict")


def _fields(f, count: int, width: int) -> List[str]:
    return [_ascii(f, width) for _ in range(count)]


def read_edf(path: Path) -> Tuple[float, Dict[str, np.ndarray]]:
    """Minimal EDF reader matching EdfBdfParser physical scaling for ordinary EDF."""
    with path.open("rb") as f:
        _version = _ascii(f, 8)
        _subject = _ascii(f, 80)
        _recording = _ascii(f, 80)
        _date = _ascii(f, 8)
        _time = _ascii(f, 8)
        header_bytes = int(_ascii(f, 8).strip() or "256")
        _ascii(f, 44)
        declared_records = int(_ascii(f, 8).strip() or "-1")
        record_duration = float(_ascii(f, 8).strip() or "1")
        signal_count = int(_ascii(f, 4).strip())
        if not 1 <= signal_count <= 4096:
            raise ValueError(f"invalid EDF signal count: {signal_count}")

        labels = [x.strip() or f"CH{i+1}" for i, x in enumerate(_fields(f, signal_count, 16))]
        _fields(f, signal_count, 80)
        units = [x.strip() for x in _fields(f, signal_count, 8)]
        pmin = [float(x.strip() or "-1") for x in _fields(f, signal_count, 8)]
        pmax = [float(x.strip() or "1") for x in _fields(f, signal_count, 8)]
        dmin = [float(x.strip() or "-32768") for x in _fields(f, signal_count, 8)]
        dmax = [float(x.strip() or "32767") for x in _fields(f, signal_count, 8)]
        _fields(f, signal_count, 80)
        spr = [int(x.strip() or "0") for x in _fields(f, signal_count, 8)]
        _fields(f, signal_count, 32)

        consumed = 256 + signal_count * 256
        if header_bytes > consumed:
            f.seek(header_bytes - consumed, 1)

        if declared_records < 0:
            raise ValueError("WF01 requires finite declared EDF record count")
        annotation = {i for i, label in enumerate(labels) if "annotations" in label.lower()}
        chunks: Dict[int, List[np.ndarray]] = {i: [] for i in range(signal_count) if i not in annotation}

        for _ in range(declared_records):
            for i in range(signal_count):
                count = spr[i]
                payload = f.read(2 * count)
                if len(payload) != 2 * count:
                    raise EOFError(f"short EDF data block in {path.name}")
                if i in annotation:
                    continue
                digital = np.frombuffer(payload, dtype="<i2").astype(np.float64)
                denom = dmax[i] - dmin[i]
                if denom == 0.0:
                    physical = digital
                else:
                    physical = (digital - dmin[i]) * (pmax[i] - pmin[i]) / denom + pmin[i]
                chunks[i].append(physical)

        rates = {i: spr[i] / record_duration for i in chunks}
        target_rate = max(rates.values())
        if abs(target_rate - FS) > 1e-12:
            raise ValueError(f"unexpected sample rate: {target_rate}")
        channels: Dict[str, np.ndarray] = {}
        for i, parts in chunks.items():
            rate = rates[i]
            if abs(rate - target_rate) > 1e-12:
                raise ValueError(f"mixed EDF sample rates are outside WF01 contract: {labels[i]}={rate}")
            channels[labels[i]] = np.concatenate(parts) if parts else np.empty(0, dtype=np.float64)
        return target_rate, channels


def resolve(channels: Dict[str, np.ndarray], electrode: str) -> str:
    target = electrode.upper()
    for name in channels:
        upper = name.upper()
        tokens = [x for x in re.split(r"[^A-Z0-9]+", upper) if x]
        if upper == target or target in tokens:
            return name
    raise ValueError(f"missing fixed EEGMAT channel {electrode}; available={list(channels)}")


def matched_window(path: Path) -> Dict[str, np.ndarray]:
    fs, channels = read_edf(path)
    if abs(fs - FS) > 1e-12:
        raise ValueError(f"unexpected sample rate in {path}: {fs}")
    out: Dict[str, np.ndarray] = {}
    for electrode in ELECTRODES:
        source = resolve(channels, electrode)
        values = channels[source]
        if values.size < WINDOW_SAMPLES:
            raise ValueError(f"recording too short for {electrode}: {values.size}")
        selected = values[:WINDOW_SAMPLES].astype(np.float64, copy=False)
        if not np.isfinite(selected).all():
            raise ValueError(f"non-finite samples in fixed window: {path.name}/{electrode}")
        out[electrode] = selected
    return out


def segment_starts(total: int, segment: int, hop: int) -> List[int]:
    if total <= segment:
        return [0]
    starts = []
    s = 0
    while s + segment <= total:
        starts.append(s)
        s += hop
    if starts[-1] != total - segment:
        starts.append(total - segment)
    return list(dict.fromkeys(starts))


def welch_psd(values: np.ndarray, fs: float, requested: int = 512) -> Tuple[np.ndarray, np.ndarray]:
    clean = values[np.isfinite(values)]
    if clean.size < 8 or fs <= 0.0:
        return np.empty(0), np.empty(0)
    cap = min(max(requested, 8), clean.size)
    segment = 1 << int(math.floor(math.log2(cap)))
    segment = max(segment, 8)
    hop = max(segment // 2, 1)
    starts = segment_starts(clean.size, segment, hop)
    idx = np.arange(segment, dtype=np.float64)
    window = 0.5 - 0.5 * np.cos(2.0 * np.pi * idx / (segment - 1))
    window_power = max(float(np.sum(window * window)), 1e-30)
    bins = segment // 2 + 1
    accumulated = np.zeros(bins, dtype=np.float64)
    for start in starts:
        chunk = clean[start:start+segment]
        centered = (chunk - float(np.mean(chunk))) * window
        spectrum = np.fft.rfft(centered)
        power = (spectrum.real * spectrum.real + spectrum.imag * spectrum.imag) / (fs * window_power)
        if bins > 2:
            power[1:-1] *= 2.0
        accumulated += power
    freqs = np.arange(bins, dtype=np.float64) * fs / segment
    return freqs, accumulated / max(len(starts), 1)


def band_power(values: np.ndarray, fs: float, lo: float, hi: float) -> float:
    freqs, psd = welch_psd(values, fs)
    if psd.size < 2:
        return 0.0
    df = freqs[1] - freqs[0]
    return max(0.0, float(np.sum(psd[(freqs >= lo) & (freqs < hi)]) * df))


def coherence(a: np.ndarray, b: np.ndarray, fs: float, requested: int = 512) -> Tuple[np.ndarray, np.ndarray]:
    available = min(a.size, b.size)
    if available < 8 or fs <= 0.0:
        return np.empty(0), np.empty(0)
    aa = np.where(np.isfinite(a[:available]), a[:available], 0.0)
    bb = np.where(np.isfinite(b[:available]), b[:available], 0.0)
    cap = min(max(requested, 8), available)
    segment = 1 << int(math.floor(math.log2(cap)))
    segment = max(segment, 8)
    starts = segment_starts(available, segment, max(segment // 2, 1))
    bins = segment // 2 + 1
    sxx = np.zeros(bins); syy = np.zeros(bins); sxyr = np.zeros(bins); sxyi = np.zeros(bins)
    idx = np.arange(segment, dtype=np.float64)
    window = 0.5 - 0.5 * np.cos(2.0 * np.pi * idx / (segment - 1))
    for start in starts:
        xa = (aa[start:start+segment] - float(np.mean(aa[start:start+segment]))) * window
        xb = (bb[start:start+segment] - float(np.mean(bb[start:start+segment]))) * window
        fa = np.fft.rfft(xa); fb = np.fft.rfft(xb)
        ar, ai, br, bi = fa.real, fa.imag, fb.real, fb.imag
        sxx += ar * ar + ai * ai
        syy += br * br + bi * bi
        sxyr += ar * br + ai * bi
        sxyi += ai * br - ar * bi
    denom = np.maximum(sxx * syy, 1e-30)
    values = np.clip((sxyr * sxyr + sxyi * sxyi) / denom, 0.0, 1.0)
    freqs = np.arange(bins, dtype=np.float64) * fs / segment
    return freqs, values


def analyze_window(channels: Dict[str, np.ndarray]) -> dict:
    powers = np.zeros((6, 19), dtype=np.float64)
    for bi, (_name, lo, hi) in enumerate(BANDS):
        for ci, electrode in enumerate(ELECTRODES):
            powers[bi, ci] = band_power(channels[electrode], FS, lo, hi)

    cells = powers.reshape(-1)
    mu = float(np.mean(cells))
    sigma = math.sqrt(float(np.sum((np.maximum(cells, 0.0) - mu) ** 2)) / 114.0)
    dispersion = sigma / (abs(mu) + EPS)

    coh_values = []
    for i in range(len(ELECTRODES) - 1):
        for j in range(i + 1, len(ELECTRODES)):
            freqs, c = coherence(channels[ELECTRODES[i]], channels[ELECTRODES[j]], FS)
            mask = (freqs >= 8.0) & (freqs < 12.0)
            coh_values.extend(c[mask].tolist())
    alpha_coherence_percent = 0.0 if not coh_values else 100.0 * float(np.mean(np.clip(coh_values, 0.0, 1.0)))

    alpha = powers[2]; beta = powers[3]; high_beta = powers[4]; gamma = powers[5]
    z = float(np.mean(alpha)) * (1.0 + alpha_coherence_percent / 100.0)
    y = (0.5 * float(np.mean(beta)) + float(np.mean(high_beta)) + float(np.mean(gamma))) * (1.0 + dispersion)
    raw = y / (z + EPS)
    normalized = raw / HC_RAW_MEDIAN
    local = {
        electrode: (0.5 * beta[i] + high_beta[i] + gamma[i]) / (alpha[i] + EPS)
        for i, electrode in enumerate(ELECTRODES)
    }
    return {
        "capacityZ": z,
        "loadY": y,
        "dispersionD": dispersion,
        "rawRatio": raw,
        "normalizedRatio": normalized,
        "alphaCoherencePercent": alpha_coherence_percent,
        "localRatios": local,
    }


def write_reference_csv(path: Path, rows: List[Tuple[str, dict]]) -> None:
    fields = [
        "condition","capacityZ","loadY","dispersionD","rawRatio",
        "normalizedRatio","alphaCoherencePercent",
    ] + [f"local_{x}" for x in ELECTRODES]
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for condition, result in rows:
            row = {k: result[k] for k in fields if k in result}
            row["condition"] = condition
            for electrode in ELECTRODES:
                row[f"local_{electrode}"] = result["localRatios"][electrode]
            w.writerow(row)


def subject_command(args) -> None:
    rest = Path(args.rest)
    task = Path(args.task)
    if sha256_file(rest) != args.rest_sha256:
        raise ValueError("rest EDF SHA-256 mismatch")
    if sha256_file(task) != args.task_sha256:
        raise ValueError("task EDF SHA-256 mismatch")

    rest_result = analyze_window(matched_window(rest))
    task_result = analyze_window(matched_window(task))
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)

    summary = {
        "version": VERSION,
        "validation_id": "wf01-eegmat-mental-arithmetic-friction-paired-confirmatory",
        "subject": args.subject,
        "rest_sha256": args.rest_sha256,
        "task_sha256": args.task_sha256,
        "sample_rate_hz": FS,
        "window_seconds": WINDOW_SECONDS,
        "rest": rest_result,
        "arithmetic": task_result,
        "delta_W": task_result["normalizedRatio"] - rest_result["normalizedRatio"],
    }
    (out / "subject.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    write_reference_csv(out / "reference.csv", [("rest", rest_result), ("arithmetic", task_result)])
    print(json.dumps(summary, sort_keys=True))


def paired_secondary(rows: List[dict], key: str) -> dict:
    rest = np.asarray([r["rest"][key] for r in rows], dtype=float)
    task = np.asarray([r["arithmetic"][key] for r in rows], dtype=float)
    result = wilcoxon(task, rest, alternative="two-sided", zero_method="wilcox", correction=False, method="approx")
    delta = task - rest
    return {
        "median_rest": float(np.median(rest)),
        "median_arithmetic": float(np.median(task)),
        "median_delta": float(np.median(delta)),
        "wilcoxon_statistic": float(result.statistic),
        "p_two_sided": float(result.pvalue),
    }


def aggregate_command(args) -> None:
    root = Path(args.root)
    files = sorted(root.rglob("subject.json"))
    rows = [json.loads(p.read_text(encoding="utf-8")) for p in files]
    if len(rows) != 36:
        raise ValueError(f"expected 36 subject results, got {len(rows)}")
    subjects = [r["subject"] for r in rows]
    expected = [f"Subject{i:02d}" for i in range(36)]
    if sorted(subjects) != expected:
        raise ValueError(f"unexpected subject set: {sorted(subjects)}")

    rest = np.asarray([r["rest"]["normalizedRatio"] for r in rows], dtype=float)
    task = np.asarray([r["arithmetic"]["normalizedRatio"] for r in rows], dtype=float)
    delta = task - rest
    test = wilcoxon(task, rest, alternative="greater", zero_method="wilcox", correction=False, method="approx")
    p = float(test.pvalue)
    median_delta = float(np.median(delta))
    primary_supported = bool(p <= 0.05 and median_delta > 0.0)

    secondary = {
        key: paired_secondary(rows, key)
        for key in ["loadY","capacityZ","dispersionD","alphaCoherencePercent"]
    }
    local_secondary = {}
    for electrode in ELECTRODES:
        rr = np.asarray([r["rest"]["localRatios"][electrode] for r in rows], dtype=float)
        tt = np.asarray([r["arithmetic"]["localRatios"][electrode] for r in rows], dtype=float)
        res = wilcoxon(tt, rr, alternative="two-sided", zero_method="wilcox", correction=False, method="approx")
        local_secondary[electrode] = {
            "median_rest": float(np.median(rr)),
            "median_arithmetic": float(np.median(tt)),
            "median_delta": float(np.median(tt - rr)),
            "wilcoxon_statistic": float(res.statistic),
            "p_two_sided_unadjusted": float(res.pvalue),
        }

    summary = {
        "validation_id": "wf01-eegmat-mental-arithmetic-friction-paired-confirmatory",
        "n_subjects": 36,
        "alpha": 0.05,
        "primary_endpoint": "paired ResearchFriction19Engine normalizedRatio W, arithmetic > rest",
        "primary": {
            "median_rest": float(np.median(rest)),
            "median_arithmetic": float(np.median(task)),
            "median_delta": median_delta,
            "wilcoxon_statistic": float(test.statistic),
            "p_one_sided": p,
            "primary_supported": primary_supported,
        },
        "secondary_predeclared": secondary,
        "local_secondary_predeclared": local_secondary,
        "guardrail": "WF01 tests one narrow EEG cognitive-load prediction only; it does not establish OMEGA_KRIT, pressure coupling, diagnosis, universality, or a physical law.",
    }
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "group.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    with (out / "subjects.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["subject","W_rest","W_arithmetic","delta_W"])
        for r in sorted(rows, key=lambda x: x["subject"]):
            w.writerow([r["subject"], r["rest"]["normalizedRatio"], r["arithmetic"]["normalizedRatio"], r["delta_W"]])
    print(json.dumps(summary, sort_keys=True))


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("subject")
    sp.add_argument("--rest", required=True)
    sp.add_argument("--task", required=True)
    sp.add_argument("--rest-sha256", required=True)
    sp.add_argument("--task-sha256", required=True)
    sp.add_argument("--subject", required=True)
    sp.add_argument("--out-dir", required=True)

    ag = sub.add_parser("aggregate")
    ag.add_argument("--root", required=True)
    ag.add_argument("--out-dir", required=True)

    args = ap.parse_args()
    if args.command == "subject":
        subject_command(args)
    else:
        aggregate_command(args)


if __name__ == "__main__":
    main()
