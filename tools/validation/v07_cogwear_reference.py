#!/usr/bin/env python3
"""Second public-data adapter for the frozen independent v0.7 Python oracle.

The numerical operator lives unchanged in v07_reference.py. This file only implements the
preregistered CogWear CSV mapping and source-specific reproducibility manifest.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
from typing import Dict, List, Sequence, Tuple

import v07_reference as core

ADAPTER_VERSION = "python-v07-cogwear-adapter-1.0"
RAW_COLUMNS_ZERO_BASED = {
    "TP9": 21,
    "AF7": 22,
    "AF8": 23,
    "TP10": 24,
}
SOURCE_DATASET = "CogWear: Can we detect cognitive effort with consumer-grade wearables?"
SOURCE_VERSION = "1.0.0"
SOURCE_DOI = "10.13026/5f6t-b637"
SOURCE_PATH = "pilot/4/baseline/muse_eeg.csv"


def parse_raw_cell(value: str) -> float:
    text = value.strip()
    if not text:
        return float("nan")
    try:
        return float(text)
    except ValueError:
        return float("nan")


def load_cogwear_muse(path: str) -> Tuple[Dict[str, List[float]], int]:
    channels: Dict[str, List[float]] = {name: [] for name in RAW_COLUMNS_ZERO_BASED}
    row_count = 0
    with open(path, "r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle)
        try:
            header = next(reader)
        except StopIteration as exc:
            raise ValueError("CogWear CSV is empty") from exc
        if len(header) < 25:
            raise ValueError(f"CogWear header has {len(header)} columns; need at least 25")
        for csv_row_number, row in enumerate(reader, start=2):
            if not row or not any(cell.strip() for cell in row):
                continue
            if len(row) < 25:
                raise ValueError(f"Non-empty CogWear row {csv_row_number} has only {len(row)} columns")
            row_count += 1
            for channel, index in RAW_COLUMNS_ZERO_BASED.items():
                channels[channel].append(parse_raw_cell(row[index]))
    return channels, row_count


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


def sha256_text_file(path: str) -> str:
    return core.sha256_file(path)


def write_manifest(
    out_dir: str,
    input_path: str,
    expected_source_sha256: str,
    source_channels: List[str],
    row_count: int,
    window: int,
    finite_fraction: float,
    scales: Sequence[core.ScaleResult],
    app_base_commit: str,
    engine_blob_sha1: str,
    signal_engine_blob_sha1: str,
    algorithm_fingerprint_sha256: str,
) -> str:
    input_sha256 = core.sha256_file(input_path)
    if expected_source_sha256 and input_sha256.lower() != expected_source_sha256.lower():
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
            "condition": "pilot participant 4 baseline",
            "documented_sample_rate_hz": 256.0,
            "sha256_from_versioned_physionet_manifest": expected_source_sha256,
        },
        "input_file": os.path.basename(input_path),
        "input_sha256": input_sha256,
        "input_rows_nonempty": row_count,
        "raw_columns_zero_based": RAW_COLUMNS_ZERO_BASED,
        "app_repository": "Ysopking/MeegRead-Medical",
        "app_validation_base_commit": app_base_commit,
        "engine_blob_sha1_expected": engine_blob_sha1,
        "signal_engine_blob_sha1_expected": signal_engine_blob_sha1,
        "algorithm_fingerprint_sha256_expected": algorithm_fingerprint_sha256,
        "sample_rate_hz_frozen": 256.0,
        "requested_window_samples": core.DEFAULT_WINDOW_SAMPLES,
        "analysis_window_samples": window,
        "window_policy": "tail; floorPowerOfTwo(min(requestedWindowSamples, commonLength))",
        "source_channels_sorted": source_channels,
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
            "No parameter tuning from this second public-data result",
            "Q is a normalized coupling-loss proxy, not physical power",
            "chi_dyn is VAR(1) spectral radius, not calibrated L/C",
            "4->2->1 transport is not by itself a strong scale-invariance test",
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
    parser.add_argument("--algorithm-fingerprint-sha256", required=True)
    args = parser.parse_args()

    channels, row_count = load_cogwear_muse(args.input)
    if row_count < core.MIN_SAMPLES:
        raise SystemExit(f"CogWear source too short: {row_count} non-empty rows")
    source_channels, window, finite_fraction, scales = core.analyze(
        channels,
        256.0,
        core.DEFAULT_WINDOW_SAMPLES,
        core.DEFAULT_MAX_SCALES,
    )
    if source_channels != ["AF7", "AF8", "TP10", "TP9"]:
        raise SystemExit(f"Unexpected sorted channel labels: {source_channels}")
    if [scale.nodeCount for scale in scales] != [4, 2, 1]:
        raise SystemExit(f"Unexpected recursive node counts: {[scale.nodeCount for scale in scales]}")

    write_scale_and_pair_outputs(args.out_dir, scales)
    manifest_path = write_manifest(
        args.out_dir,
        args.input,
        args.expected_source_sha256,
        source_channels,
        row_count,
        window,
        finite_fraction,
        scales,
        args.app_base_commit,
        args.engine_blob_sha1,
        args.signal_engine_blob_sha1,
        args.algorithm_fingerprint_sha256,
    )
    print(json.dumps({
        "adapter_version": ADAPTER_VERSION,
        "oracle_version": core.ORACLE_VERSION,
        "rows": row_count,
        "input_sha256": core.sha256_file(args.input),
        "source_channels": source_channels,
        "finite_input_fraction": finite_fraction,
        "window": window,
        "scale_node_counts": [scale.nodeCount for scale in scales],
        "manifest": manifest_path,
    }, indent=2))


if __name__ == "__main__":
    main()
