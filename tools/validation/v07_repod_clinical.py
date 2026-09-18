#!/usr/bin/env python3
"""Preregistered subject-level v0.7 analysis for RepOD schizophrenia/control EEG.

This adapter performs source decoding and deterministic multi-window aggregation only.
All v0.7 numerical operations remain in v07_reference.py unchanged.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import statistics
import struct
from dataclasses import dataclass
from typing import BinaryIO, Dict, List, Sequence, Tuple

import v07_reference as core

ADAPTER_VERSION = "python-v07-repod-clinical-adapter-1.0"
EXPECTED_SAMPLE_RATE_HZ = 250.0
WINDOW_SAMPLES = 2048
WINDOWS_PER_SUBJECT = 12
EXPECTED_SCALE_COUNTS = [19, 10, 5, 3, 2, 1]
CHANNEL_ORDER = [
    "Fp1", "Fp2", "F7", "F3", "Fz", "F4", "F8", "T3", "C3", "Cz",
    "C4", "T4", "T5", "P3", "Pz", "P4", "T6", "O1", "O2",
]
CANONICAL = {name.lower(): name for name in CHANNEL_ORDER}


@dataclass(frozen=True)
class EdfSignalHeader:
    label: str
    unit: str
    physical_min: float
    physical_max: float
    digital_min: float
    digital_max: float
    samples_per_record: int


def read_exact(handle: BinaryIO, size: int) -> bytes:
    data = handle.read(size)
    if len(data) != size:
        raise EOFError(f"Expected {size} bytes, got {len(data)}")
    return data


def read_ascii(handle: BinaryIO, size: int) -> str:
    return read_exact(handle, size).decode("ascii", errors="strict")


def read_fields(handle: BinaryIO, count: int, width: int) -> List[str]:
    return [read_ascii(handle, width) for _ in range(count)]


def parse_float(text: str, fallback: float) -> float:
    try:
        return float(text.strip()) if text.strip() else fallback
    except ValueError:
        return fallback


def parse_int(text: str, fallback: int) -> int:
    try:
        return int(text.strip()) if text.strip() else fallback
    except ValueError:
        return fallback


def scale_digital(value: int, header: EdfSignalHeader) -> float:
    digital_range = header.digital_max - header.digital_min
    if digital_range == 0.0:
        return float(value)
    return (
        (float(value) - header.digital_min)
        * (header.physical_max - header.physical_min)
        / digital_range
        + header.physical_min
    )


def canonicalize_label(label: str) -> str | None:
    text = label.strip()
    lower = text.lower()
    for prefix in ("eeg ", "eeg-"):
        if lower.startswith(prefix):
            text = text[len(prefix):].strip()
            lower = text.lower()
    for suffix in ("-ref", " ref", "-le"):
        if lower.endswith(suffix):
            text = text[: -len(suffix)].strip()
            lower = text.lower()
    return CANONICAL.get(lower)


def load_edf(path: str) -> Tuple[Dict[str, List[float]], float, int, int, List[str]]:
    channels: Dict[str, List[float]] = {}
    excluded_annotations: List[str] = []
    with open(path, "rb") as handle:
        version = read_ascii(handle, 8).strip()
        read_ascii(handle, 80)
        read_ascii(handle, 80)
        read_ascii(handle, 8)
        read_ascii(handle, 8)
        header_bytes = parse_int(read_ascii(handle, 8), 256)
        read_ascii(handle, 44)
        declared_records = parse_int(read_ascii(handle, 8), -1)
        record_duration = parse_float(read_ascii(handle, 8), 1.0)
        signal_count = parse_int(read_ascii(handle, 4), -1)
        if not version:
            raise ValueError("Missing EDF version")
        if record_duration <= 0.0 or not math.isfinite(record_duration):
            raise ValueError(f"Invalid record duration: {record_duration}")
        if signal_count < 1 or signal_count > 4096:
            raise ValueError(f"Invalid signal count: {signal_count}")

        labels = read_fields(handle, signal_count, 16)
        read_fields(handle, signal_count, 80)
        units = read_fields(handle, signal_count, 8)
        physical_min = [parse_float(x, -1.0) for x in read_fields(handle, signal_count, 8)]
        physical_max = [parse_float(x, 1.0) for x in read_fields(handle, signal_count, 8)]
        digital_min = [parse_float(x, -32768.0) for x in read_fields(handle, signal_count, 8)]
        digital_max = [parse_float(x, 32767.0) for x in read_fields(handle, signal_count, 8)]
        read_fields(handle, signal_count, 80)
        samples_per_record = [parse_int(x, 0) for x in read_fields(handle, signal_count, 8)]
        read_fields(handle, signal_count, 32)

        consumed = 256 + signal_count * 256
        if header_bytes < consumed:
            raise ValueError(f"Header bytes {header_bytes} smaller than EDF standard header {consumed}")
        if header_bytes > consumed:
            read_exact(handle, header_bytes - consumed)

        headers = [
            EdfSignalHeader(
                label=labels[i].strip() or f"CH{i + 1}",
                unit=units[i].strip() or "a.u.",
                physical_min=physical_min[i],
                physical_max=physical_max[i],
                digital_min=digital_min[i],
                digital_max=digital_max[i],
                samples_per_record=samples_per_record[i],
            )
            for i in range(signal_count)
        ]
        annotation_indices = {i for i, h in enumerate(headers) if "annotations" in h.label.lower()}
        excluded_annotations = [headers[i].label for i in sorted(annotation_indices)]

        index_to_name: Dict[int, str] = {}
        for i, header in enumerate(headers):
            if i in annotation_indices:
                continue
            name = canonicalize_label(header.label)
            if name is None:
                raise ValueError(f"Unexpected non-annotation channel label: {header.label!r}")
            if name in channels:
                raise ValueError(f"Duplicate canonical EEG channel: {name}")
            channels[name] = []
            index_to_name[i] = name

        if set(channels) != set(CHANNEL_ORDER):
            missing = sorted(set(CHANNEL_ORDER) - set(channels))
            extra = sorted(set(channels) - set(CHANNEL_ORDER))
            raise ValueError(f"Channel mismatch; missing={missing}, extra={extra}")

        rates = {}
        for i, name in index_to_name.items():
            rate = headers[i].samples_per_record / record_duration
            rates[name] = rate
            if abs(rate - EXPECTED_SAMPLE_RATE_HZ) > 1e-12:
                raise ValueError(f"Unexpected sample rate for {name}: {rate}")

        records_read = 0
        while declared_records < 0 or records_read < declared_records:
            started = False
            try:
                for i, header in enumerate(headers):
                    raw = read_exact(handle, header.samples_per_record * 2)
                    started = True
                    name = index_to_name.get(i)
                    if name is None:
                        continue
                    values = channels[name]
                    for (digital,) in struct.iter_unpack("<h", raw):
                        values.append(scale_digital(digital, header))
            except EOFError:
                if declared_records >= 0:
                    raise
                if started:
                    raise ValueError("Truncated final EDF record")
                break
            records_read += 1

        if declared_records >= 0 and records_read != declared_records:
            raise ValueError(f"Read {records_read} records but header declares {declared_records}")

    lengths = {name: len(values) for name, values in channels.items()}
    if len(set(lengths.values())) != 1:
        raise ValueError(f"Non-uniform channel lengths: {lengths}")
    n = next(iter(lengths.values()))
    if n < WINDOW_SAMPLES:
        raise ValueError(f"Recording too short: {n} samples")
    return {name: channels[name] for name in CHANNEL_ORDER}, EXPECTED_SAMPLE_RATE_HZ, records_read, signal_count, excluded_annotations


def ols_slope(values: Sequence[float]) -> float:
    if len(values) < 2 or any(not math.isfinite(v) for v in values):
        raise ValueError(f"Invalid values for slope: {values}")
    x_mean = (len(values) - 1) / 2.0
    y_mean = sum(values) / len(values)
    numerator = sum((i - x_mean) * (v - y_mean) for i, v in enumerate(values))
    denominator = sum((i - x_mean) ** 2 for i in range(len(values)))
    return numerator / denominator


def window_starts(n: int) -> List[int]:
    if n < WINDOW_SAMPLES:
        raise ValueError("Recording shorter than frozen window")
    if WINDOWS_PER_SUBJECT == 1:
        return [n - WINDOW_SAMPLES]
    return [math.floor(k * (n - WINDOW_SAMPLES) / (WINDOWS_PER_SUBJECT - 1)) for k in range(WINDOWS_PER_SUBJECT)]


def write_parity_outputs(out_dir: str, scales: Sequence[core.ScaleResult]) -> None:
    parity_dir = os.path.join(out_dir, "parity_window")
    os.makedirs(parity_dir, exist_ok=True)
    with open(os.path.join(parity_dir, "scales.csv"), "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["level","nodeCount","kappa","directionCoherence","gamma","flowMagnitudeP","couplingLossQ","chiDyn","stabilityReserve","meanPhaseLocking"])
        for scale in scales:
            writer.writerow([
                scale.level, scale.nodeCount, repr(scale.kappa), repr(scale.directionCoherence), repr(scale.gamma),
                repr(scale.flowMagnitudeP), repr(scale.couplingLossQ), repr(scale.chiDyn),
                repr(scale.stabilityReserve), repr(scale.meanPhaseLocking),
            ])
    with open(os.path.join(parity_dir, "pairs.csv"), "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["level","index","nodeA","nodeB","phaseTransportRad","phaseLocking","informationOverlap","bundleWeight"])
        for scale in scales:
            for index, pair in enumerate(scale.pairs):
                writer.writerow([
                    scale.level, index, pair.nodeA, pair.nodeB, repr(pair.phaseTransportRad),
                    repr(pair.phaseLocking), repr(pair.informationOverlap), repr(pair.bundleWeight),
                ])


def analyze_subject(input_path: str, subject: str, group: str, source_metadata_path: str, out_dir: str) -> dict:
    channels, sample_rate_hz, records_read, signal_count, excluded = load_edf(input_path)
    n = len(next(iter(channels.values())))
    starts = window_starts(n)
    rows = []
    parity_scales = None

    for window_index, start in enumerate(starts):
        sliced = {
            name: [v if math.isfinite(v) else 0.0 for v in values[start:start + WINDOW_SAMPLES]]
            for name, values in channels.items()
        }
        source_channels, window, finite_fraction, scales = core.analyze(
            sliced,
            sample_rate_hz,
            WINDOW_SAMPLES,
            core.DEFAULT_MAX_SCALES,
        )
        counts = [scale.nodeCount for scale in scales]
        if counts != EXPECTED_SCALE_COUNTS:
            raise ValueError(f"Unexpected scale counts for {subject} window {window_index}: {counts}")
        if window != WINDOW_SAMPLES:
            raise ValueError(f"Unexpected analysis window for {subject}: {window}")
        nonterminal = scales[:-1]
        q_values = [scale.couplingLossQ for scale in nonterminal]
        if any((not math.isfinite(v)) or v <= 0.0 for v in q_values):
            raise ValueError(f"Non-positive/non-finite Q for {subject} window {window_index}: {q_values}")
        rows.append({
            "subject": subject,
            "group": group,
            "window_index": window_index,
            "start_sample": start,
            "end_sample_exclusive": start + WINDOW_SAMPLES,
            "finite_input_fraction": finite_fraction,
            "mean_plv_slope": ols_slope([scale.meanPhaseLocking for scale in nonterminal]),
            "kappa_slope": ols_slope([scale.kappa for scale in nonterminal]),
            "log_q_slope": ols_slope([math.log(scale.couplingLossQ) for scale in nonterminal]),
            "chi_dyn_slope": ols_slope([scale.chiDyn for scale in nonterminal]),
            "stability_slope": ols_slope([scale.stabilityReserve for scale in nonterminal]),
        })
        if window_index == WINDOWS_PER_SUBJECT - 1:
            parity_scales = scales

    if parity_scales is None:
        raise AssertionError("Tail parity window not generated")
    os.makedirs(out_dir, exist_ok=True)
    window_path = os.path.join(out_dir, "window_metrics.csv")
    with open(window_path, "w", encoding="utf-8", newline="") as handle:
        fieldnames = list(rows[0].keys())
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    write_parity_outputs(out_dir, parity_scales)

    with open(source_metadata_path, "r", encoding="utf-8") as handle:
        source_metadata = json.load(handle)
    summary = {
        "adapter_version": ADAPTER_VERSION,
        "oracle_version": core.ORACLE_VERSION,
        "subject": subject,
        "group": group,
        "input_filename": os.path.basename(input_path),
        "source_metadata": source_metadata,
        "sample_rate_hz": sample_rate_hz,
        "channel_count": len(channels),
        "channel_order": CHANNEL_ORDER,
        "recording_samples": n,
        "records_read": records_read,
        "edf_declared_signal_count": signal_count,
        "excluded_annotation_labels": excluded,
        "window_samples": WINDOW_SAMPLES,
        "window_starts": starts,
        "scale_node_counts": EXPECTED_SCALE_COUNTS,
        "primary_subject_score_median_mean_plv_slope": statistics.median(row["mean_plv_slope"] for row in rows),
        "secondary_subject_scores": {
            "median_kappa_slope": statistics.median(row["kappa_slope"] for row in rows),
            "median_log_q_slope": statistics.median(row["log_q_slope"] for row in rows),
            "median_chi_dyn_slope": statistics.median(row["chi_dyn_slope"] for row in rows),
            "median_stability_slope": statistics.median(row["stability_slope"] for row in rows),
        },
        "parity_window_index": WINDOWS_PER_SUBJECT - 1,
        "parity_window_is_tail": starts[-1] == n - WINDOW_SAMPLES,
        "outputs": {
            "window_metrics.csv": core.sha256_file(window_path),
            "parity_window/scales.csv": core.sha256_file(os.path.join(out_dir, "parity_window", "scales.csv")),
            "parity_window/pairs.csv": core.sha256_file(os.path.join(out_dir, "parity_window", "pairs.csv")),
        },
    }
    summary_path = os.path.join(out_dir, "subject_summary.json")
    with open(summary_path, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--subject", required=True)
    parser.add_argument("--group", choices=["healthy", "schizophrenia"], required=True)
    parser.add_argument("--source-metadata", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    expected_prefix = "h" if args.group == "healthy" else "s"
    if not args.subject.lower().startswith(expected_prefix):
        raise SystemExit(f"Subject/group mismatch: {args.subject} vs {args.group}")
    allowed = {f"{expected_prefix}{i:02d}" for i in range(1, 15)}
    if args.subject.lower() not in allowed:
        raise SystemExit(f"Subject outside frozen cohort: {args.subject}")
    summary = analyze_subject(args.input, args.subject.lower(), args.group, args.source_metadata, args.out_dir)
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
