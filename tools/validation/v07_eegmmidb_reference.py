#!/usr/bin/env python3
"""EDF+ source adapter for the preregistered 64-channel v0.7 scale-ladder validation.

All numerical v0.7 operations remain in v07_reference.py unchanged. This module independently
implements only standards-based EDF decoding, physical scaling, annotation exclusion and
source-specific reproducibility output for PhysioNet EEGMMIDB S001R02.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import struct
from dataclasses import dataclass
from typing import BinaryIO, Dict, List, Sequence, Tuple

import v07_reference as core

ADAPTER_VERSION = "python-v07-eegmmidb-edf-adapter-1.0"
SOURCE_DATASET = "EEG Motor Movement/Imagery Dataset"
SOURCE_VERSION = "1.0.0"
SOURCE_DOI = "10.13026/C28G6P"
SOURCE_PATH = "S001/S001R02.edf"
EXPECTED_CHANNEL_COUNT = 64
EXPECTED_SAMPLE_RATE_HZ = 160.0
EXPECTED_SCALE_COUNTS = [64, 32, 16, 8, 4, 2, 1]


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
    stripped = text.strip()
    if not stripped:
        return fallback
    try:
        return float(stripped)
    except ValueError:
        return fallback


def parse_int(text: str, fallback: int) -> int:
    stripped = text.strip()
    if not stripped:
        return fallback
    try:
        return int(stripped)
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


def load_edf(path: str) -> Tuple[Dict[str, List[float]], float, int, int, List[str]]:
    """Decode the EDF data records independently of Android code.

    Returns channels, common sample rate, records read, declared signal count and excluded
    annotation labels. Any heterogeneous non-annotation sample rates are rejected by this
    preregistered validation rather than resampled.
    """
    channels: Dict[str, List[float]] = {}
    excluded_annotations: List[str] = []

    with open(path, "rb") as handle:
        version = read_ascii(handle, 8).strip()
        read_ascii(handle, 80)  # patient identification
        read_ascii(handle, 80)  # recording identification
        read_ascii(handle, 8)   # start date
        read_ascii(handle, 8)   # start time
        header_bytes = parse_int(read_ascii(handle, 8), 256)
        read_ascii(handle, 44)  # reserved
        declared_records = parse_int(read_ascii(handle, 8), -1)
        record_duration = parse_float(read_ascii(handle, 8), 1.0)
        if record_duration <= 0.0 or not math.isfinite(record_duration):
            raise ValueError(f"Invalid EDF record duration: {record_duration}")
        signal_count = parse_int(read_ascii(handle, 4), -1)
        if signal_count < 1 or signal_count > 4096:
            raise ValueError(f"Invalid EDF signal count: {signal_count}")

        labels = read_fields(handle, signal_count, 16)
        read_fields(handle, signal_count, 80)  # transducer
        units = read_fields(handle, signal_count, 8)
        physical_min = [parse_float(x, -1.0) for x in read_fields(handle, signal_count, 8)]
        physical_max = [parse_float(x, 1.0) for x in read_fields(handle, signal_count, 8)]
        digital_min = [parse_float(x, -32768.0) for x in read_fields(handle, signal_count, 8)]
        digital_max = [parse_float(x, 32767.0) for x in read_fields(handle, signal_count, 8)]
        read_fields(handle, signal_count, 80)  # prefiltering
        samples_per_record = [parse_int(x, 0) for x in read_fields(handle, signal_count, 8)]
        read_fields(handle, signal_count, 32)  # signal reserved

        consumed = 256 + signal_count * 256
        if header_bytes < consumed:
            raise ValueError(f"EDF header_bytes={header_bytes} smaller than standard header={consumed}")
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
        annotation_indices = {
            i for i, header in enumerate(headers)
            if "annotations" in header.label.lower()
        }
        excluded_annotations = [headers[i].label for i in sorted(annotation_indices)]
        data_indices = [i for i in range(signal_count) if i not in annotation_indices]
        for i in data_indices:
            if headers[i].label in channels:
                raise ValueError(f"Duplicate EDF signal label: {headers[i].label}")
            channels[headers[i].label] = []

        source_rates = {
            i: headers[i].samples_per_record / record_duration
            for i in data_indices
        }
        if not source_rates:
            raise ValueError("No non-annotation EDF signals")
        rates = list(source_rates.values())
        sample_rate_hz = max(rates)
        if abs(sample_rate_hz - EXPECTED_SAMPLE_RATE_HZ) > 1e-12:
            raise ValueError(f"Unexpected EDF sample rate: {sample_rate_hz}")
        heterogeneous = {
            headers[i].label: rate
            for i, rate in source_rates.items()
            if abs(rate - EXPECTED_SAMPLE_RATE_HZ) > 1e-12
        }
        if heterogeneous:
            raise ValueError(f"Heterogeneous non-annotation EDF rates not allowed by preregistration: {heterogeneous}")

        records_read = 0
        while declared_records < 0 or records_read < declared_records:
            record_started = False
            try:
                for i, header in enumerate(headers):
                    byte_count = header.samples_per_record * 2
                    raw = read_exact(handle, byte_count)
                    record_started = True
                    if i in annotation_indices:
                        continue
                    values = channels[header.label]
                    for (digital,) in struct.iter_unpack("<h", raw):
                        values.append(scale_digital(digital, header))
            except EOFError:
                if declared_records >= 0:
                    raise
                if record_started:
                    raise ValueError("Truncated final EDF record")
                break
            records_read += 1

        if declared_records >= 0 and records_read != declared_records:
            raise ValueError(f"Read {records_read} EDF records; header declares {declared_records}")
        if version not in {"0", "0       ".strip()} and not version:
            raise ValueError("Missing EDF version")

    lengths = {name: len(values) for name, values in channels.items()}
    if len(set(lengths.values())) != 1:
        raise ValueError(f"Non-uniform EEG sample counts: {lengths}")
    if len(channels) != EXPECTED_CHANNEL_COUNT:
        raise ValueError(f"Expected {EXPECTED_CHANNEL_COUNT} non-annotation EEG channels, got {len(channels)}")
    if min(lengths.values(), default=0) <= core.DEFAULT_WINDOW_SAMPLES:
        raise ValueError("EDF source must contain more than the frozen 2048-sample analysis window")

    return channels, sample_rate_hz, records_read, signal_count, excluded_annotations


def write_scale_and_pair_outputs(out_dir: str, scales: Sequence[core.ScaleResult]) -> Tuple[str, str]:
    os.makedirs(out_dir, exist_ok=True)
    scales_path = os.path.join(out_dir, "scales.csv")
    pairs_path = os.path.join(out_dir, "pairs.csv")
    with open(scales_path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "level", "nodeCount", "kappa", "directionCoherence", "gamma", "flowMagnitudeP",
            "couplingLossQ", "chiDyn", "stabilityReserve", "meanPhaseLocking",
        ])
        for scale in scales:
            writer.writerow([
                scale.level, scale.nodeCount, repr(scale.kappa), repr(scale.directionCoherence),
                repr(scale.gamma), repr(scale.flowMagnitudeP), repr(scale.couplingLossQ),
                repr(scale.chiDyn), repr(scale.stabilityReserve), repr(scale.meanPhaseLocking),
            ])
    with open(pairs_path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "level", "index", "nodeA", "nodeB", "phaseTransportRad", "phaseLocking",
            "informationOverlap", "bundleWeight",
        ])
        for scale in scales:
            for index, pair in enumerate(scale.pairs):
                writer.writerow([
                    scale.level, index, pair.nodeA, pair.nodeB, repr(pair.phaseTransportRad),
                    repr(pair.phaseLocking), repr(pair.informationOverlap), repr(pair.bundleWeight),
                ])
    return scales_path, pairs_path


def write_manifest(
    out_dir: str,
    input_path: str,
    expected_source_sha256: str,
    source_channels: List[str],
    records_read: int,
    declared_signal_count: int,
    excluded_annotations: List[str],
    window: int,
    finite_fraction: float,
    scales: Sequence[core.ScaleResult],
    app_base_commit: str,
    engine_blob_sha1: str,
    signal_engine_blob_sha1: str,
    edf_parser_blob_sha1: str,
    algorithm_fingerprint_sha256: str,
) -> str:
    input_sha256 = core.sha256_file(input_path)
    if input_sha256.lower() != expected_source_sha256.lower():
        raise ValueError(
            f"PhysioNet source SHA-256 mismatch: expected {expected_source_sha256}, got {input_sha256}"
        )
    scales_path = os.path.join(out_dir, "scales.csv")
    pairs_path = os.path.join(out_dir, "pairs.csv")
    manifest = {
        "adapter_version": ADAPTER_VERSION,
        "oracle_version": core.ORACLE_VERSION,
        "engine_version_expected": core.ENGINE_VERSION,
        "source": {
            "provider": "PhysioNet",
            "dataset": SOURCE_DATASET,
            "version": SOURCE_VERSION,
            "doi": SOURCE_DOI,
            "path": SOURCE_PATH,
            "condition": "S001 run 02; one-minute baseline, eyes closed",
            "documented_sample_rate_hz": EXPECTED_SAMPLE_RATE_HZ,
            "documented_eeg_channel_count": EXPECTED_CHANNEL_COUNT,
            "sha256_from_versioned_physionet_manifest": expected_source_sha256,
        },
        "input_file": os.path.basename(input_path),
        "input_sha256": input_sha256,
        "edf_records_read": records_read,
        "edf_declared_signal_count_including_annotations": declared_signal_count,
        "edf_excluded_annotation_labels": excluded_annotations,
        "app_repository": "Ysopking/MeegRead-Medical",
        "app_validation_base_commit": app_base_commit,
        "engine_blob_sha1_expected": engine_blob_sha1,
        "signal_engine_blob_sha1_expected": signal_engine_blob_sha1,
        "edf_parser_blob_sha1_expected": edf_parser_blob_sha1,
        "algorithm_fingerprint_sha256_expected": algorithm_fingerprint_sha256,
        "sample_rate_hz_frozen": EXPECTED_SAMPLE_RATE_HZ,
        "requested_window_samples": core.DEFAULT_WINDOW_SAMPLES,
        "analysis_window_samples": window,
        "window_policy": "tail; floorPowerOfTwo(min(requestedWindowSamples, commonLength))",
        "source_channels_sorted": source_channels,
        "source_channel_count": len(source_channels),
        "finite_input_fraction": finite_fraction,
        "scale_node_counts": [scale.nodeCount for scale in scales],
        "operator_parameters": {
            "bandpass_low_hz": 1.0,
            "bandpass_high_hz": 40.0,
            "hilbert_kernel_radius": 31,
            "nmi_bins": 8,
            "var_order": 1,
            "var_ridge": 0.001,
            "gelfand_iterations": 80,
            "max_scales": core.DEFAULT_MAX_SCALES,
        },
        "outputs": {
            "scales.csv": core.sha256_file(scales_path),
            "pairs.csv": core.sha256_file(pairs_path),
        },
        "guardrails": [
            "SENSOR_SPACE_PROXY only",
            "No parameter or channel-selection tuning from this 64-channel result",
            "Q is a normalized coupling-loss proxy, not physical power",
            "chi_dyn is VAR(1) spectral radius, not calibrated L/C",
            "A deep scale ladder in one run is not a universality claim",
        ],
    }
    path = os.path.join(out_dir, "manifest.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")
    return path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--expected-source-sha256", required=True)
    parser.add_argument("--app-base-commit", required=True)
    parser.add_argument("--engine-blob-sha1", required=True)
    parser.add_argument("--signal-engine-blob-sha1", required=True)
    parser.add_argument("--edf-parser-blob-sha1", required=True)
    parser.add_argument("--algorithm-fingerprint-sha256", required=True)
    args = parser.parse_args()

    channels, sample_rate_hz, records_read, signal_count, excluded_annotations = load_edf(args.input)
    source_channels, window, finite_fraction, scales = core.analyze(
        channels,
        sample_rate_hz,
        core.DEFAULT_WINDOW_SAMPLES,
        core.DEFAULT_MAX_SCALES,
    )
    if len(source_channels) != EXPECTED_CHANNEL_COUNT:
        raise SystemExit(f"Unexpected source channel count: {len(source_channels)}")
    if [scale.nodeCount for scale in scales] != EXPECTED_SCALE_COUNTS:
        raise SystemExit(f"Unexpected recursive node counts: {[scale.nodeCount for scale in scales]}")
    if window != core.DEFAULT_WINDOW_SAMPLES:
        raise SystemExit(f"Unexpected analysis window: {window}")

    write_scale_and_pair_outputs(args.out_dir, scales)
    manifest_path = write_manifest(
        args.out_dir,
        args.input,
        args.expected_source_sha256,
        source_channels,
        records_read,
        signal_count,
        excluded_annotations,
        window,
        finite_fraction,
        scales,
        args.app_base_commit,
        args.engine_blob_sha1,
        args.signal_engine_blob_sha1,
        args.edf_parser_blob_sha1,
        args.algorithm_fingerprint_sha256,
    )
    print(json.dumps({
        "adapter_version": ADAPTER_VERSION,
        "oracle_version": core.ORACLE_VERSION,
        "records_read": records_read,
        "input_sha256": core.sha256_file(args.input),
        "source_channel_count": len(source_channels),
        "source_channels": source_channels,
        "finite_input_fraction": finite_fraction,
        "window": window,
        "scale_node_counts": [scale.nodeCount for scale in scales],
        "manifest": manifest_path,
    }, indent=2))


if __name__ == "__main__":
    main()
