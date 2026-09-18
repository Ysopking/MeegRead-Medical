#!/usr/bin/env python3
"""WF02 independent CogWear thermodynamic-load oracle and paired inference.

Scientific hypothesis, cohort, windows, endpoint and test are frozen in
WF02_COGWEAR_LOAD_PREREG.json. This file implements transport/parity only.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
from scipy.stats import wilcoxon

VERSION = "wf02-cogwear-load-oracle-1.0.0"
VALIDATION_ID = "wf02-cogwear-pilot-stroop-vs-baseline-load-confirmatory"
FS = 256.0
MATCHED_SAMPLES = 46080
OMEGA = 5800.0
RECOVERY_TAU = 300.0
EPS = 1e-12
COLUMNS = {"TP9": 21, "AF7": 22, "AF8": 23, "TP10": 24}
POINT_FIELDS = [
    "timeSeconds","gradE","faa","hSigma","eFlow","pressureProxy","frictionRate",
    "wRaw","wBounded","omegaRatio","flowGate","loadDriveRate","flowReliefRate",
    "recoveryRate","netLoadRate"
]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def parse_cell(value: str) -> float:
    text = value.strip()
    if not text:
        return float("nan")
    try:
        return float(text)
    except ValueError:
        return float("nan")


def load_cogwear(path: Path) -> Dict[str, np.ndarray]:
    channels: Dict[str, List[float]] = {name: [] for name in COLUMNS}
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle)
        try:
            header = next(reader)
        except StopIteration as exc:
            raise ValueError("CogWear CSV is empty") from exc
        if len(header) < 25:
            raise ValueError(f"CogWear header has {len(header)} columns; need at least 25")
        for row_number, row in enumerate(reader, start=2):
            if not row or not any(cell.strip() for cell in row):
                continue
            if len(row) < 25:
                raise ValueError(f"Non-empty CogWear row {row_number} has only {len(row)} columns")
            for channel, index in COLUMNS.items():
                channels[channel].append(parse_cell(row[index]))
    if any(len(values) < MATCHED_SAMPLES for values in channels.values()):
        sizes = {k: len(v) for k, v in channels.items()}
        raise ValueError(f"CogWear condition cannot supply frozen 180 s window: {sizes}")
    return {
        name: np.asarray(values[:MATCHED_SAMPLES], dtype=np.float64)
        for name, values in channels.items()
    }


def segment_starts(total: int, segment: int, hop: int) -> List[int]:
    if total <= segment:
        return [0]
    out: List[int] = []
    start = 0
    while start + segment <= total:
        out.append(start)
        start += hop
    if out[-1] != total - segment:
        out.append(total - segment)
    return list(dict.fromkeys(out))


def welch_psd(samples: np.ndarray, sample_rate_hz: float, requested_segment_size: int = 512) -> Tuple[np.ndarray, np.ndarray]:
    clean = samples[np.isfinite(samples)]
    if clean.size < 8 or sample_rate_hz <= 0.0:
        return np.empty(0), np.empty(0)
    cap = min(max(requested_segment_size, 8), clean.size)
    segment_size = 1 << int(math.floor(math.log2(cap)))
    segment_size = max(segment_size, 8)
    hop = max(segment_size // 2, 1)
    starts = segment_starts(clean.size, segment_size, hop)
    idx = np.arange(segment_size, dtype=np.float64)
    window = 0.5 - 0.5 * np.cos(2.0 * np.pi * idx / (segment_size - 1))
    window_power = max(float(np.sum(window * window)), 1e-30)
    bins = segment_size // 2 + 1
    accumulated = np.zeros(bins, dtype=np.float64)
    for start in starts:
        chunk = clean[start:start + segment_size]
        mean = float(np.sum(chunk) / segment_size)
        transformed = np.fft.rfft((chunk - mean) * window)
        power = (transformed.real * transformed.real + transformed.imag * transformed.imag) / (sample_rate_hz * window_power)
        if bins > 2:
            power[1:-1] *= 2.0
        accumulated += power
    freqs = np.arange(bins, dtype=np.float64) * sample_rate_hz / segment_size
    return freqs, accumulated / max(len(starts), 1)


def power(samples: np.ndarray, lo: float, hi: float) -> float:
    freqs, psd = welch_psd(samples, FS)
    if psd.size < 2:
        return 0.0
    df = freqs[1] - freqs[0]
    return float(np.sum(psd[(freqs >= lo) & (freqs < hi)]) * df)


def usable(values: np.ndarray) -> bool:
    finite = values[np.isfinite(values)]
    if values.size < 32:
        return False
    if finite.size / values.size < 0.995:
        return False
    if finite.size == 0:
        return False
    return float(np.max(finite) - np.min(finite)) > EPS


def safe_nonnegative(value: float) -> float:
    return max(0.0, value) if math.isfinite(value) else 0.0


def analyze(channels: Dict[str, np.ndarray]) -> dict:
    if any(not usable(channels[name]) for name in ("AF7","AF8","TP9","TP10")):
        raise ValueError("Frozen load-engine finite/range analyzability rule failed")
    available = min(channels[name].size for name in ("AF7","AF8","TP9","TP10"))
    window = min(max(int(4.0 * FS), 32), available)
    hop = max(int(1.0 * FS), 1)
    starts = segment_starts(available, window, hop)

    raw_load = 0.0
    first_breach = None
    points: List[dict] = []
    for index, start in enumerate(starts):
        end = start + window
        af7 = channels["AF7"][start:end]
        af8 = channels["AF8"][start:end]
        tp9 = channels["TP9"][start:end]
        tp10 = channels["TP10"][start:end]

        a7 = power(af7, 8.0, 13.0)
        a8 = power(af8, 8.0, 13.0)
        alpha = (a7 + a8) / 2.0
        theta = (power(tp9, 4.0, 8.0) + power(tp10, 4.0, 8.0)) / 2.0
        high_beta = (power(tp9, 20.0, 30.0) + power(tp10, 20.0, 30.0)) / 2.0
        mid_beta = (power(af7, 13.0, 20.0) + power(af8, 13.0, 20.0)) / 2.0

        grad_e = (high_beta + theta) / (alpha + EPS)
        faa = math.log(a8 + EPS) - math.log(a7 + EPS)
        h = 1.0 / (1.0 + math.exp(-2.0 * faa))
        flow = (mid_beta * alpha) / (high_beta + EPS) * h
        pressure = grad_e * (1.0 - h)
        friction = abs(grad_e - pressure)
        dt = hop / FS if index == 0 else (start - starts[index - 1]) / FS

        current = safe_nonnegative(raw_load)
        drive = safe_nonnegative(friction)
        safe_flow = safe_nonnegative(flow)
        safe_dt = safe_nonnegative(dt)
        gate = safe_flow / (1.0 + safe_flow)
        flow_relief = drive * gate
        recovery = (current / RECOVERY_TAU) * gate
        net_rate = drive - flow_relief - recovery
        raw_load = max(0.0, current + net_rate * safe_dt)
        bounded = OMEGA * math.tanh(max(raw_load, 0.0) / OMEGA)
        t = (start + window / 2.0) / FS
        if first_breach is None and raw_load >= OMEGA:
            first_breach = t

        points.append({
            "timeSeconds": t,
            "gradE": grad_e,
            "faa": faa,
            "hSigma": h,
            "eFlow": flow,
            "pressureProxy": pressure,
            "frictionRate": friction,
            "wRaw": raw_load,
            "wBounded": bounded,
            "omegaRatio": raw_load / OMEGA,
            "flowGate": gate,
            "loadDriveRate": drive,
            "flowReliefRate": flow_relief,
            "recoveryRate": recovery,
            "netLoadRate": net_rate,
        })

    tail = [p for p in points if p["timeSeconds"] >= 120.0]
    if not tail:
        raise ValueError("No points in preregistered last-minute interval")
    summary = {
        "omegaCrit": OMEGA,
        "omegaBreach": any(p["wRaw"] >= OMEGA for p in points),
        "firstBreachTimeSeconds": first_breach,
        "finalRawLoad": points[-1]["wRaw"],
        "finalBoundedLoad": points[-1]["wBounded"],
        "maxRawLoad": max(p["wRaw"] for p in points),
        "meanFaa": float(np.mean([p["faa"] for p in points])),
        "sustainedLastMinuteRawLoad": float(np.median([p["wRaw"] for p in tail])),
        "lastMinuteMedianPressureProxy": float(np.median([p["pressureProxy"] for p in tail])),
        "lastMinuteMedianFrictionRate": float(np.median([p["frictionRate"] for p in tail])),
        "lastMinuteMedianEFlow": float(np.median([p["eFlow"] for p in tail])),
        "finalOmegaRatio": points[-1]["omegaRatio"],
        "sourceChannels": {"AF7":"AF7","AF8":"AF8","TP9":"TP9","TP10":"TP10"},
        "pointCount": len(points),
    }
    return {"points": points, "summary": summary}


def write_points(path: Path, points: List[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=POINT_FIELDS)
        writer.writeheader()
        for point in points:
            writer.writerow(point)


def subject_command(args) -> None:
    baseline_path = Path(args.baseline)
    load_path = Path(args.cognitive_load)
    if sha256_file(baseline_path) != args.baseline_sha256:
        raise ValueError("baseline SHA-256 mismatch")
    if sha256_file(load_path) != args.cognitive_load_sha256:
        raise ValueError("cognitive_load SHA-256 mismatch")

    baseline = analyze(load_cogwear(baseline_path))
    cognitive = analyze(load_cogwear(load_path))
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    write_points(out / "baseline_points.csv", baseline["points"])
    write_points(out / "cognitive_load_points.csv", cognitive["points"])
    result = {
        "version": VERSION,
        "validation_id": VALIDATION_ID,
        "participant": args.participant,
        "sample_rate_hz": FS,
        "matched_samples": MATCHED_SAMPLES,
        "baseline_sha256": args.baseline_sha256,
        "cognitive_load_sha256": args.cognitive_load_sha256,
        "baseline": baseline["summary"],
        "cognitive_load": cognitive["summary"],
        "delta_sustained_W": cognitive["summary"]["sustainedLastMinuteRawLoad"] - baseline["summary"]["sustainedLastMinuteRawLoad"],
    }
    (out / "subject.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({
        "participant": args.participant,
        "status": "ANALYZABLE",
        "point_count_each": baseline["summary"]["pointCount"],
        "parity_reference_dir": str(out),
    }, sort_keys=True))


def paired_summary(rows: List[dict], key: str) -> dict:
    b = np.asarray([r["baseline"][key] for r in rows], dtype=float)
    c = np.asarray([r["cognitive_load"][key] for r in rows], dtype=float)
    test = wilcoxon(c, b, alternative="two-sided", zero_method="wilcox", correction=False, method="approx")
    return {
        "median_baseline": float(np.median(b)),
        "median_cognitive_load": float(np.median(c)),
        "median_delta": float(np.median(c - b)),
        "wilcoxon_statistic": float(test.statistic),
        "p_two_sided": float(test.pvalue),
    }


def aggregate_command(args) -> None:
    root = Path(args.root)
    expected = [str(i) for i in range(11)]

    status_files = sorted(root.rglob("status.json"))
    statuses = [json.loads(p.read_text(encoding="utf-8")) for p in status_files]
    status_by_participant = {str(s["participant"]): s for s in statuses}
    if sorted(status_by_participant, key=int) != expected:
        raise ValueError(
            "Expected one frozen analyzability status for every preregistered pilot participant 0..10; "
            f"found {sorted(status_by_participant, key=int)}"
        )

    files = sorted(root.rglob("subject.json"))
    rows = [json.loads(p.read_text(encoding="utf-8")) for p in files]
    row_by_participant = {str(r["participant"]): r for r in rows}
    analyzable_ids = sorted(
        [pid for pid, s in status_by_participant.items() if bool(s.get("analyzable"))],
        key=int,
    )
    if sorted(row_by_participant, key=int) != analyzable_ids:
        raise ValueError(
            "ANALYZABLE status and subject.json set disagree: "
            f"statuses={analyzable_ids}, subject_rows={sorted(row_by_participant, key=int)}"
        )
    rows = [row_by_participant[pid] for pid in analyzable_ids]
    if len(rows) < 2:
        raise ValueError(f"Too few analyzable preregistered pairs for paired inference: {len(rows)}")

    baseline = np.asarray([r["baseline"]["sustainedLastMinuteRawLoad"] for r in rows], dtype=float)
    cognitive = np.asarray([r["cognitive_load"]["sustainedLastMinuteRawLoad"] for r in rows], dtype=float)
    delta = cognitive - baseline
    primary = wilcoxon(cognitive, baseline, alternative="greater", zero_method="wilcox", correction=False, method="approx")
    p = float(primary.pvalue)
    median_delta = float(np.median(delta))
    supported = bool(p <= 0.05 and median_delta > 0.0)

    secondaries = {
        key: paired_summary(rows, key)
        for key in [
            "finalRawLoad","maxRawLoad","finalBoundedLoad",
            "lastMinuteMedianPressureProxy","lastMinuteMedianFrictionRate",
            "lastMinuteMedianEFlow","meanFaa","finalOmegaRatio"
        ]
    }
    not_analyzable = [
        {
            "participant": pid,
            "reason": str(status_by_participant[pid].get("reason", "unspecified")),
        }
        for pid in expected
        if not bool(status_by_participant[pid].get("analyzable"))
    ]
    summary = {
        "validation_id": VALIDATION_ID,
        "n_preregistered": len(expected),
        "n_analyzable": len(rows),
        "analyzable_participants": analyzable_ids,
        "not_analyzable": not_analyzable,
        "alpha": 0.05,
        "primary_endpoint": "median wRaw across points with timeSeconds >= 120 s in matched 180 s recordings",
        "primary": {
            "median_baseline": float(np.median(baseline)),
            "median_cognitive_load": float(np.median(cognitive)),
            "median_delta": median_delta,
            "wilcoxon_statistic": float(primary.statistic),
            "p_one_sided": p,
            "primary_supported": supported,
        },
        "secondary_predeclared": secondaries,
        "omega_breach_descriptive": {
            "baseline_count": int(sum(bool(r["baseline"]["omegaBreach"]) for r in rows)),
            "cognitive_load_count": int(sum(bool(r["cognitive_load"]["omegaBreach"]) for r in rows)),
            "note": "Descriptive only; WF02 does not validate OMEGA_KRIT=5800."
        },
        "guardrail": (
            "Primary inference uses every preregistered participant pair that passes the frozen source-identity "
            "and analyzability gates. NOT_ANALYZABLE participants remain explicit and are never replaced. "
            "WF02 tests one paired cognitive-load prediction only; pressureProxy and OMEGA_KRIT remain "
            "unvalidated research proxy/parameter."
        ),
    }
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "group.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    with (out / "subjects.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["participant","status","reason","baseline_sustained_W","cognitive_load_sustained_W","delta_sustained_W"])
        for pid in expected:
            status = status_by_participant[pid]
            if bool(status.get("analyzable")):
                r = row_by_participant[pid]
                writer.writerow([
                    pid,
                    "ANALYZABLE",
                    "",
                    r["baseline"]["sustainedLastMinuteRawLoad"],
                    r["cognitive_load"]["sustainedLastMinuteRawLoad"],
                    r["delta_sustained_W"],
                ])
            else:
                writer.writerow([pid, "NOT_ANALYZABLE", status.get("reason", "unspecified"), "", "", ""])
    print(json.dumps(summary, sort_keys=True))


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("subject")
    sp.add_argument("--baseline", required=True)
    sp.add_argument("--cognitive-load", required=True)
    sp.add_argument("--baseline-sha256", required=True)
    sp.add_argument("--cognitive-load-sha256", required=True)
    sp.add_argument("--participant", required=True)
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
