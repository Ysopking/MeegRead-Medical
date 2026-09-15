#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import statistics
from pathlib import Path
from typing import Dict, List, Sequence

import numpy as np
import v07_eegmmidb_multisubject_null as helper

VALIDATION_ID = "v07-srm-ds003775-t1-sub001-sub010-dual-null"
SUBJECTS = [f"sub-{i:03d}" for i in range(1, 11)]
EXPECTED_RATE = 1024.0
EXPECTED_CHANNELS = 64
EXPECTED_SCALE_COUNTS = [64, 32, 16, 8, 4, 2, 1]
SURROGATES = 49
WINDOW = 2048
TWO64 = float(1 << 64)

# Reuse the independently authored EDF reader and frozen v0.7 oracle helpers.
# Its rate/global invariants are overridden here before any data are loaded.
helper.EXPECTED_RATE = EXPECTED_RATE
helper.EXPECTED_CHANNELS = EXPECTED_CHANNELS
helper.EXPECTED_SCALE_COUNTS = EXPECTED_SCALE_COUNTS
helper.WINDOW = WINDOW


def md5_file(path: str | Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def _clean_tail(values: Sequence[float]) -> List[float]:
    tail = list(values[-WINDOW:])
    if len(tail) != WINDOW:
        raise ValueError("Tail length invariant failed")
    return [x if math.isfinite(x) else 0.0 for x in tail]


def deterministic_shift(subject: str, replicate: int, channel: str) -> int:
    key = f"{VALIDATION_ID}|shift|{subject}|{replicate}|{channel}".encode("utf-8")
    u64 = int.from_bytes(hashlib.sha256(key).digest()[:8], "big", signed=False)
    return 1 + (u64 % (WINDOW - 1))


def circular_shift_surrogate(
    channels: Dict[str, List[float]], subject: str, replicate: int
) -> Dict[str, List[float]]:
    out: Dict[str, List[float]] = {}
    for name in sorted(channels):
        clean = _clean_tail(channels[name])
        shift = deterministic_shift(subject, replicate, name)
        out[name] = clean[-shift:] + clean[:-shift]
    return out


def deterministic_phase(subject: str, replicate: int, channel: str, bin_index: int) -> float:
    key = (
        f"{VALIDATION_ID}|phase|{subject}|{replicate}|{channel}|{bin_index}"
    ).encode("utf-8")
    u64 = int.from_bytes(hashlib.sha256(key).digest()[:8], "big", signed=False)
    return 2.0 * math.pi * (u64 / TWO64)


def fourier_phase_surrogate(
    channels: Dict[str, List[float]], subject: str, replicate: int
) -> Dict[str, List[float]]:
    out: Dict[str, List[float]] = {}
    for name in sorted(channels):
        clean = np.asarray(_clean_tail(channels[name]), dtype=np.float64)
        spectrum = np.fft.rfft(clean)
        magnitudes = np.abs(spectrum)
        randomized = spectrum.copy()
        for k in range(1, spectrum.shape[0] - 1):
            theta = deterministic_phase(subject, replicate, name, k)
            randomized[k] = magnitudes[k] * complex(math.cos(theta), math.sin(theta))
        # Preserve DC and Nyquist exactly as preregistered.
        surrogate = np.fft.irfft(randomized, n=WINDOW)
        out[name] = [float(x) for x in surrogate]
    return out


def empirical_p_greater(observed: float, null: Sequence[float]) -> float:
    return (1 + sum(x >= observed for x in null)) / (len(null) + 1)


def run_family(
    channels: Dict[str, List[float]],
    rate: float,
    subject: str,
    family: str,
) -> List[Dict[str, float | int]]:
    rows: List[Dict[str, float | int]] = []
    for replicate in range(SURROGATES):
        if family == "shift":
            surrogate = circular_shift_surrogate(channels, subject, replicate)
        elif family == "phase":
            surrogate = fourier_phase_surrogate(channels, subject, replicate)
        else:
            raise ValueError(f"Unknown surrogate family: {family}")
        _, swindow, _, scales = helper.core.analyze(
            surrogate,
            rate,
            helper.core.DEFAULT_WINDOW_SAMPLES,
            helper.core.DEFAULT_MAX_SCALES,
        )
        if swindow != WINDOW:
            raise SystemExit("Surrogate window invariant failed")
        row: Dict[str, float | int] = {"replicate": replicate}
        row.update(helper.metrics(scales))
        rows.append(row)
    return rows


def write_surrogates(path: Path, rows: Sequence[Dict[str, float | int]]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        names = ["replicate", "meanPhaseLocking_slope", "kappa_slope", "logQ_slope"]
        writer = csv.DictWriter(f, fieldnames=names)
        writer.writeheader()
        writer.writerows(rows)


def run_subject(args: argparse.Namespace) -> None:
    if args.subject not in SUBJECTS:
        raise SystemExit(f"Subject {args.subject} is outside preregistered set {SUBJECTS}")
    if args.surrogates != SURROGATES:
        raise SystemExit(f"Surrogate count is frozen at {SURROGATES}")
    path = Path(args.input)
    if path.stat().st_size != args.expected_size:
        raise SystemExit(
            f"Byte-size mismatch: expected {args.expected_size}, got {path.stat().st_size}"
        )
    actual_md5 = md5_file(path)
    if actual_md5.lower() != args.expected_md5.lower():
        raise SystemExit(f"MD5 mismatch: expected {args.expected_md5}, got {actual_md5}")

    channels, rate, records_read, annotations = helper.load_edf(path)
    source_channels, window, finite_fraction, scales = helper.core.analyze(
        channels,
        rate,
        helper.core.DEFAULT_WINDOW_SAMPLES,
        helper.core.DEFAULT_MAX_SCALES,
    )
    if len(source_channels) != EXPECTED_CHANNELS or window != WINDOW:
        raise SystemExit("Frozen observed-input invariant failed")
    if [s.nodeCount for s in scales] != EXPECTED_SCALE_COUNTS:
        raise SystemExit(f"Unexpected observed scale ladder: {[s.nodeCount for s in scales]}")

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    helper.write_observed(out_dir, scales)
    observed = helper.metrics(scales)

    shift_rows = run_family(channels, rate, args.subject, "shift")
    phase_rows = run_family(channels, rate, args.subject, "phase")
    write_surrogates(out_dir / "shift_surrogates.csv", shift_rows)
    write_surrogates(out_dir / "phase_surrogates.csv", phase_rows)

    shift_primary = [float(x["meanPhaseLocking_slope"]) for x in shift_rows]
    phase_primary = [float(x["meanPhaseLocking_slope"]) for x in phase_rows]
    observed_primary = float(observed["meanPhaseLocking_slope"])
    p_shift = empirical_p_greater(observed_primary, shift_primary)
    p_phase = empirical_p_greater(observed_primary, phase_primary)

    summary = {
        "validation_id": VALIDATION_ID,
        "subject": args.subject,
        "session": "ses-t1",
        "task": "resteyesc",
        "input_size_bytes": path.stat().st_size,
        "input_md5": actual_md5,
        "input_sha256": helper.sha256_file(path),
        "records_read": records_read,
        "excluded_annotation_labels": annotations,
        "sample_rate_hz": rate,
        "source_channel_count": len(source_channels),
        "analysis_window_samples": window,
        "finite_input_fraction": finite_fraction,
        "scale_node_counts": [s.nodeCount for s in scales],
        "observed": observed,
        "nulls": {
            "circular_shift": {
                "surrogates": SURROGATES,
                "primary_slope_median": statistics.median(shift_primary),
            },
            "fourier_phase_randomization": {
                "surrogates": SURROGATES,
                "numpy_version": np.__version__,
                "primary_slope_median": statistics.median(phase_primary),
            },
        },
        "inference": {
            "p_shift": p_shift,
            "p_phase": p_phase,
            "dual_null_success": bool(p_shift <= 0.05 and p_phase <= 0.05),
        },
        "guardrail": (
            "SENSOR_SPACE_PROXY; independent-dataset operator-level validation only; "
            "no diagnostic, biomarker, source-localization, causal, clinical or universality claim"
        ),
    }
    with open(out_dir / "subject_summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, sort_keys=True)
        f.write("\n")
    print(json.dumps(summary, indent=2, sort_keys=True))


def binomial_tail(k: int, n: int, p: float) -> float:
    return sum(math.comb(n, i) * p**i * (1 - p) ** (n - i) for i in range(k, n + 1))


def run_aggregate(args: argparse.Namespace) -> None:
    files = sorted(Path(args.root).rglob("subject_summary.json"))
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
        rows.append({
            "subject": subject,
            "observed_primary_slope": obj["observed"]["meanPhaseLocking_slope"],
            "shift_null_median": obj["nulls"]["circular_shift"]["primary_slope_median"],
            "p_shift": obj["inference"]["p_shift"],
            "phase_null_median": obj["nulls"]["fourier_phase_randomization"]["primary_slope_median"],
            "p_phase": obj["inference"]["p_phase"],
            "dual_null_success": obj["inference"]["dual_null_success"],
        })

    successes = sum(bool(row["dual_null_success"]) for row in rows)
    group = {
        "validation_id": VALIDATION_ID,
        "subjects": SUBJECTS,
        "n": len(rows),
        "dual_null_successes": successes,
        "primary_group_binomial_tail_p": binomial_tail(successes, len(rows), 0.05),
        "median_observed_primary_slope": statistics.median(
            float(row["observed_primary_slope"]) for row in rows
        ),
        "median_shift_null_primary_slope": statistics.median(
            float(row["shift_null_median"]) for row in rows
        ),
        "median_phase_null_primary_slope": statistics.median(
            float(row["phase_null_median"]) for row in rows
        ),
        "group_alpha": 0.05,
        "guardrail": (
            "Independent-dataset dual-null replication of the preregistered v0.7 PLV scale slope; "
            "not a physical-law, clinical, causal, source-space or universality claim"
        ),
    }

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "group_subjects.csv", "w", newline="", encoding="utf-8") as f:
        names = list(rows[0].keys())
        writer = csv.DictWriter(f, fieldnames=names)
        writer.writeheader()
        writer.writerows(rows)
    with open(out / "group_summary.json", "w", encoding="utf-8") as f:
        json.dump(group, f, indent=2, sort_keys=True)
        f.write("\n")
    print(json.dumps(group, indent=2, sort_keys=True))


def main() -> None:
    parser = argparse.ArgumentParser()
    subs = parser.add_subparsers(dest="command", required=True)

    subject = subs.add_parser("subject")
    subject.add_argument("--subject", required=True)
    subject.add_argument("--input", required=True)
    subject.add_argument("--expected-md5", required=True)
    subject.add_argument("--expected-size", required=True, type=int)
    subject.add_argument("--out-dir", required=True)
    subject.add_argument("--surrogates", required=True, type=int)
    subject.set_defaults(func=run_subject)

    aggregate = subs.add_parser("aggregate")
    aggregate.add_argument("--root", required=True)
    aggregate.add_argument("--out-dir", required=True)
    aggregate.set_defaults(func=run_aggregate)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
