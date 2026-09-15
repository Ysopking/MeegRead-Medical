#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import statistics
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Dict, Iterable, List, Sequence, Tuple

import v07_reference as core

VALIDATION_ID = "v07-eegmmidb-r02-s002-s011-multisubject-null"
SUBJECTS = [f"S{i:03d}" for i in range(2, 12)]
EXPECTED_RATE = 160.0
EXPECTED_CHANNELS = 64
EXPECTED_SCALE_COUNTS = [64, 32, 16, 8, 4, 2, 1]
SURROGATES = 49
WINDOW = 2048

@dataclass(frozen=True)
class EdfSignalHeader:
    label: str
    unit: str
    physical_min: float
    physical_max: float
    digital_min: float
    digital_max: float
    samples_per_record: int


def sha256_file(path: str | Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def _read_exact(handle: BinaryIO, size: int) -> bytes:
    data = handle.read(size)
    if len(data) != size:
        raise EOFError(f"Expected {size} bytes, got {len(data)}")
    return data


def _read_ascii(handle: BinaryIO, size: int) -> str:
    return _read_exact(handle, size).decode("ascii", errors="strict")


def _read_fields(handle: BinaryIO, count: int, width: int) -> List[str]:
    return [_read_ascii(handle, width) for _ in range(count)]


def _parse_float(text: str, fallback: float) -> float:
    try:
        return float(text.strip()) if text.strip() else fallback
    except ValueError:
        return fallback


def _parse_int(text: str, fallback: int) -> int:
    try:
        return int(text.strip()) if text.strip() else fallback
    except ValueError:
        return fallback


def _scale_digital(value: int, h: EdfSignalHeader) -> float:
    dr = h.digital_max - h.digital_min
    if dr == 0.0:
        return float(value)
    return (float(value) - h.digital_min) * (h.physical_max - h.physical_min) / dr + h.physical_min


def load_edf(path: str | Path) -> Tuple[Dict[str, List[float]], float, int, List[str]]:
    channels: Dict[str, List[float]] = {}
    excluded: List[str] = []
    with open(path, "rb") as handle:
        _read_ascii(handle, 8)
        _read_ascii(handle, 80)
        _read_ascii(handle, 80)
        _read_ascii(handle, 8)
        _read_ascii(handle, 8)
        header_bytes = _parse_int(_read_ascii(handle, 8), 256)
        _read_ascii(handle, 44)
        declared_records = _parse_int(_read_ascii(handle, 8), -1)
        record_duration = _parse_float(_read_ascii(handle, 8), 1.0)
        signal_count = _parse_int(_read_ascii(handle, 4), -1)
        if not (1 <= signal_count <= 4096):
            raise ValueError(f"Invalid EDF signal count: {signal_count}")
        if not math.isfinite(record_duration) or record_duration <= 0.0:
            raise ValueError(f"Invalid EDF record duration: {record_duration}")
        labels = _read_fields(handle, signal_count, 16)
        _read_fields(handle, signal_count, 80)
        units = _read_fields(handle, signal_count, 8)
        pmin = [_parse_float(x, -1.0) for x in _read_fields(handle, signal_count, 8)]
        pmax = [_parse_float(x, 1.0) for x in _read_fields(handle, signal_count, 8)]
        dmin = [_parse_float(x, -32768.0) for x in _read_fields(handle, signal_count, 8)]
        dmax = [_parse_float(x, 32767.0) for x in _read_fields(handle, signal_count, 8)]
        _read_fields(handle, signal_count, 80)
        spr = [_parse_int(x, 0) for x in _read_fields(handle, signal_count, 8)]
        _read_fields(handle, signal_count, 32)
        consumed = 256 + signal_count * 256
        if header_bytes < consumed:
            raise ValueError(f"EDF header shorter than standard header: {header_bytes} < {consumed}")
        if header_bytes > consumed:
            _read_exact(handle, header_bytes - consumed)
        headers = [EdfSignalHeader(label=labels[i].strip() or f"CH{i+1}", unit=units[i].strip() or "a.u.", physical_min=pmin[i], physical_max=pmax[i], digital_min=dmin[i], digital_max=dmax[i], samples_per_record=spr[i]) for i in range(signal_count)]
        annotations = {i for i, h in enumerate(headers) if "annotations" in h.label.lower()}
        excluded = [headers[i].label for i in sorted(annotations)]
        data_indices = [i for i in range(signal_count) if i not in annotations]
        rates = {i: headers[i].samples_per_record / record_duration for i in data_indices}
        if any(abs(rate - EXPECTED_RATE) > 1e-12 for rate in rates.values()):
            raise ValueError(f"Unexpected non-annotation EDF rates: {rates}")
        for i in data_indices:
            if headers[i].label in channels:
                raise ValueError(f"Duplicate EDF label: {headers[i].label}")
            channels[headers[i].label] = []
        records_read = 0
        while declared_records < 0 or records_read < declared_records:
            started = False
            try:
                for i, h in enumerate(headers):
                    raw = _read_exact(handle, h.samples_per_record * 2)
                    started = True
                    if i in annotations:
                        continue
                    out = channels[h.label]
                    for (digital,) in struct.iter_unpack("<h", raw):
                        out.append(_scale_digital(digital, h))
            except EOFError:
                if declared_records >= 0 or started:
                    raise
                break
            records_read += 1
    if len(channels) != EXPECTED_CHANNELS:
        raise ValueError(f"Expected {EXPECTED_CHANNELS} EEG channels, got {len(channels)}")
    lengths = {len(v) for v in channels.values()}
    if len(lengths) != 1:
        raise ValueError(f"Nonuniform sample counts: {sorted(lengths)}")
    if min(lengths) <= WINDOW:
        raise ValueError("Recording shorter than preregistered tail window")
    return channels, EXPECTED_RATE, records_read, excluded


def ols_slope(values: Sequence[float]) -> float:
    n = len(values)
    if n < 2:
        raise ValueError("Need >=2 values for slope")
    xbar = (n - 1) / 2.0
    ybar = sum(values) / n
    denom = sum((i - xbar) ** 2 for i in range(n))
    return sum((i - xbar) * (values[i] - ybar) for i in range(n)) / denom


def metrics(scales: Sequence[core.ScaleResult]) -> Dict[str, float]:
    nonterminal = [s for s in scales if s.nodeCount > 1]
    if [s.nodeCount for s in scales] != EXPECTED_SCALE_COUNTS or len(nonterminal) != 6:
        raise ValueError(f"Unexpected scale ladder: {[s.nodeCount for s in scales]}")
    plv = [s.meanPhaseLocking for s in nonterminal]
    kappa = [s.kappa for s in nonterminal]
    log_q = [math.log(max(s.couplingLossQ, core.EPS)) for s in nonterminal]
    return {"meanPhaseLocking_slope": ols_slope(plv), "kappa_slope": ols_slope(kappa), "logQ_slope": ols_slope(log_q)}


def deterministic_shift(subject: str, replicate: int, channel: str, length: int = WINDOW) -> int:
    key = f"{VALIDATION_ID}|{subject}|{replicate}|{channel}".encode("utf-8")
    u64 = int.from_bytes(hashlib.sha256(key).digest()[:8], "big", signed=False)
    return 1 + (u64 % (length - 1))


def surrogate_channels(channels: Dict[str, List[float]], subject: str, replicate: int) -> Dict[str, List[float]]:
    out: Dict[str, List[float]] = {}
    for name in sorted(channels):
        tail = channels[name][-WINDOW:]
        clean = [x if math.isfinite(x) else 0.0 for x in tail]
        shift = deterministic_shift(subject, replicate, name, len(clean))
        out[name] = clean[-shift:] + clean[:-shift]
    return out


def empirical_p_greater(observed: float, null: Sequence[float]) -> float:
    return (1 + sum(x >= observed for x in null)) / (len(null) + 1)


def empirical_p_less(observed: float, null: Sequence[float]) -> float:
    return (1 + sum(x <= observed for x in null)) / (len(null) + 1)


def write_observed(out_dir: Path, scales: Sequence[core.ScaleResult]) -> None:
    with open(out_dir / "scales.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["level","nodeCount","kappa","directionCoherence","gamma","flowMagnitudeP","couplingLossQ","chiDyn","stabilityReserve","meanPhaseLocking"])
        for s in scales:
            w.writerow([s.level,s.nodeCount,repr(s.kappa),repr(s.directionCoherence),repr(s.gamma),repr(s.flowMagnitudeP),repr(s.couplingLossQ),repr(s.chiDyn),repr(s.stabilityReserve),repr(s.meanPhaseLocking)])
    with open(out_dir / "pairs.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["level","index","nodeA","nodeB","phaseTransportRad","phaseLocking","informationOverlap","bundleWeight"])
        for s in scales:
            for idx,p in enumerate(s.pairs):
                w.writerow([s.level,idx,p.nodeA,p.nodeB,repr(p.phaseTransportRad),repr(p.phaseLocking),repr(p.informationOverlap),repr(p.bundleWeight)])


def run_subject(args: argparse.Namespace) -> None:
    subject = args.subject
    if subject not in SUBJECTS:
        raise SystemExit(f"Subject {subject} is outside preregistered set {SUBJECTS}")
    if args.surrogates != SURROGATES:
        raise SystemExit(f"Surrogate count is frozen at {SURROGATES}")
    actual_sha = sha256_file(args.input)
    if actual_sha.lower() != args.expected_sha256.lower():
        raise SystemExit(f"SHA-256 mismatch: expected {args.expected_sha256}, got {actual_sha}")
    channels, rate, records_read, annotations = load_edf(args.input)
    source_channels, window, finite_fraction, scales = core.analyze(channels, rate, core.DEFAULT_WINDOW_SAMPLES, core.DEFAULT_MAX_SCALES)
    if len(source_channels) != EXPECTED_CHANNELS or window != WINDOW:
        raise SystemExit("Frozen input invariant failed")
    observed = metrics(scales)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    write_observed(out_dir, scales)
    null_rows: List[Dict[str, float | int]] = []
    for replicate in range(SURROGATES):
        shifted = surrogate_channels(channels, subject, replicate)
        _, swindow, _, sscales = core.analyze(shifted, rate, core.DEFAULT_WINDOW_SAMPLES, core.DEFAULT_MAX_SCALES)
        if swindow != WINDOW:
            raise SystemExit("Surrogate window invariant failed")
        row: Dict[str, float | int] = {"replicate": replicate}
        row.update(metrics(sscales))
        null_rows.append(row)
    with open(out_dir / "surrogates.csv", "w", newline="", encoding="utf-8") as f:
        names = ["replicate","meanPhaseLocking_slope","kappa_slope","logQ_slope"]
        w = csv.DictWriter(f, fieldnames=names)
        w.writeheader(); w.writerows(null_rows)
    null_plv = [float(r["meanPhaseLocking_slope"]) for r in null_rows]
    null_kappa = [float(r["kappa_slope"]) for r in null_rows]
    null_logq = [float(r["logQ_slope"]) for r in null_rows]
    summary = {"validation_id": VALIDATION_ID, "subject": subject, "run": "R02", "input_sha256": actual_sha, "records_read": records_read, "excluded_annotation_labels": annotations, "sample_rate_hz": rate, "source_channel_count": len(source_channels), "analysis_window_samples": window, "finite_input_fraction": finite_fraction, "scale_node_counts": [s.nodeCount for s in scales], "observed": observed, "null": {"surrogates": SURROGATES, "meanPhaseLocking_slope_median": statistics.median(null_plv), "kappa_slope_median": statistics.median(null_kappa), "logQ_slope_median": statistics.median(null_logq)}, "inference": {"primary_empirical_p": empirical_p_greater(observed["meanPhaseLocking_slope"], null_plv), "secondary_kappa_empirical_p": empirical_p_greater(observed["kappa_slope"], null_kappa), "secondary_logQ_empirical_p": empirical_p_less(observed["logQ_slope"], null_logq)}, "guardrail": "SENSOR_SPACE_PROXY; implementation/statistical validation only; no diagnostic, biomarker, source-localization, causal or universality claim"}
    with open(out_dir / "subject_summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, sort_keys=True); f.write("\n")
    print(json.dumps(summary, indent=2, sort_keys=True))


def binomial_tail(k: int, n: int, p: float) -> float:
    return sum(math.comb(n, i) * p**i * (1-p)**(n-i) for i in range(k, n+1))


def run_aggregate(args: argparse.Namespace) -> None:
    root = Path(args.root)
    files = sorted(root.rglob("subject_summary.json"))
    by_subject = {}
    for path in files:
        obj = json.load(open(path, encoding="utf-8"))
        subject = obj["subject"]
        if subject in by_subject:
            raise SystemExit(f"Duplicate summary for {subject}")
        by_subject[subject] = obj
    if sorted(by_subject) != SUBJECTS:
        raise SystemExit(f"Expected exactly {SUBJECTS}, got {sorted(by_subject)}")
    rows = []
    for subject in SUBJECTS:
        obj = by_subject[subject]
        rows.append({"subject": subject, "primary_slope": obj["observed"]["meanPhaseLocking_slope"], "primary_null_median": obj["null"]["meanPhaseLocking_slope_median"], "primary_p": obj["inference"]["primary_empirical_p"], "kappa_slope": obj["observed"]["kappa_slope"], "kappa_p": obj["inference"]["secondary_kappa_empirical_p"], "logQ_slope": obj["observed"]["logQ_slope"], "logQ_p": obj["inference"]["secondary_logQ_empirical_p"]})
    significant = sum(float(r["primary_p"]) <= 0.05 for r in rows)
    group = {"validation_id": VALIDATION_ID, "subjects": SUBJECTS, "n": len(rows), "primary_subjects_p_le_0_05": significant, "primary_group_binomial_tail_p": binomial_tail(significant, len(rows), 0.05), "median_observed_primary_slope": statistics.median(float(r["primary_slope"]) for r in rows), "median_subject_null_primary_slope": statistics.median(float(r["primary_null_median"]) for r in rows), "group_alpha": 0.05, "guardrail": "Confirmatory operator-level replication relative to preregistered circular-shift null only; not a physical-law, clinical, causal or source-space claim"}
    out = Path(args.out_dir); out.mkdir(parents=True, exist_ok=True)
    with open(out / "group_subjects.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
    with open(out / "group_summary.json", "w", encoding="utf-8") as f:
        json.dump(group, f, indent=2, sort_keys=True); f.write("\n")
    print(json.dumps(group, indent=2, sort_keys=True))


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="mode", required=True)
    s = sub.add_parser("subject")
    s.add_argument("--input", required=True)
    s.add_argument("--subject", required=True)
    s.add_argument("--expected-sha256", required=True)
    s.add_argument("--out-dir", required=True)
    s.add_argument("--surrogates", type=int, default=SURROGATES)
    s.set_defaults(func=run_subject)
    a = sub.add_parser("aggregate")
    a.add_argument("--root", required=True)
    a.add_argument("--out-dir", required=True)
    a.set_defaults(func=run_aggregate)
    args = ap.parse_args(); args.func(args)

if __name__ == "__main__":
    main()
