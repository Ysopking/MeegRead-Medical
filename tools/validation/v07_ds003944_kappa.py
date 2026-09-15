#!/usr/bin/env python3
"""Preregistered independent ds003944 confirmation of the RepOD kappa-scale hypothesis.

BrainVision decoding and fixed 19-sensor matching are source adapters only. All v0.7 numerical
operations remain in tools/validation/v07_reference.py unchanged.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import statistics
from pathlib import Path
from typing import Dict, List, Sequence

import mne
import v07_reference as core

ADAPTER_VERSION = "python-v07-ds003944-kappa-confirmatory-1.0"
EXPECTED_SAMPLE_RATE_HZ = 1000.0
WINDOW_SAMPLES = 2048
WINDOWS_PER_SUBJECT = 12
EXPECTED_SCALE_COUNTS = [19, 10, 5, 3, 2, 1]
CHANNEL_MAPPING = [
    ("Fp1", "FP1"), ("Fp2", "FP2"), ("F7", "F7"), ("F3", "F3"), ("Fz", "Fz"),
    ("F4", "F4"), ("F8", "F8"), ("T3", "T7"), ("C3", "C3"), ("Cz", "Cz"),
    ("C4", "C4"), ("T4", "T8"), ("T5", "P7"), ("P3", "P3"), ("Pz", "Pz"),
    ("P4", "P4"), ("T6", "P8"), ("O1", "O1"), ("O2", "O2"),
]
CANONICAL_ORDER = [x[0] for x in CHANNEL_MAPPING]
SOURCE_ORDER = [x[1] for x in CHANNEL_MAPPING]


def ols_slope(values: Sequence[float]) -> float:
    if len(values) < 2 or any(not math.isfinite(v) for v in values):
        raise ValueError(f"Invalid slope values: {values}")
    xm = (len(values) - 1) / 2.0
    ym = sum(values) / len(values)
    num = sum((i - xm) * (v - ym) for i, v in enumerate(values))
    den = sum((i - xm) ** 2 for i in range(len(values)))
    return num / den


def window_starts(n: int) -> List[int]:
    if n < WINDOW_SAMPLES:
        raise ValueError(f"Recording too short: {n}")
    return [math.floor(k * (n - WINDOW_SAMPLES) / (WINDOWS_PER_SUBJECT - 1)) for k in range(WINDOWS_PER_SUBJECT)]


def load_fixed_channels(vhdr_path: str) -> tuple[Dict[str, List[float]], int]:
    raw = mne.io.read_raw_brainvision(vhdr_path, preload=False, verbose="ERROR")
    sfreq = float(raw.info["sfreq"])
    if abs(sfreq - EXPECTED_SAMPLE_RATE_HZ) > 1e-12:
        raise ValueError(f"Unexpected sample rate: {sfreq}")
    missing = [name for name in SOURCE_ORDER if name not in raw.ch_names]
    if missing:
        raise ValueError(f"Missing fixed RepOD-matched channels: {missing}; available={raw.ch_names}")
    raw.pick(SOURCE_ORDER)
    raw.load_data(verbose="ERROR")
    data = raw.get_data()
    if data.shape[0] != 19:
        raise ValueError(f"Expected 19 selected channels, got {data.shape}")
    channels = {
        canonical: data[index].astype(float, copy=False).tolist()
        for index, (canonical, _source) in enumerate(CHANNEL_MAPPING)
    }
    lengths = {len(v) for v in channels.values()}
    if len(lengths) != 1:
        raise ValueError(f"Nonuniform selected channel lengths: {sorted(lengths)}")
    return channels, next(iter(lengths))


def write_parity_outputs(out_dir: Path, scales: Sequence[core.ScaleResult], window_channels: Dict[str, List[float]]) -> None:
    parity = out_dir / "parity_window"
    parity.mkdir(parents=True, exist_ok=True)
    with (parity / "input.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["sample", *CANONICAL_ORDER])
        for i in range(WINDOW_SAMPLES):
            writer.writerow([i, *[repr(window_channels[name][i]) for name in CANONICAL_ORDER]])
    with (parity / "scales.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["level","nodeCount","kappa","directionCoherence","gamma","flowMagnitudeP","couplingLossQ","chiDyn","stabilityReserve","meanPhaseLocking"])
        for scale in scales:
            writer.writerow([
                scale.level, scale.nodeCount, repr(scale.kappa), repr(scale.directionCoherence), repr(scale.gamma),
                repr(scale.flowMagnitudeP), repr(scale.couplingLossQ), repr(scale.chiDyn),
                repr(scale.stabilityReserve), repr(scale.meanPhaseLocking),
            ])
    with (parity / "pairs.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["level","index","nodeA","nodeB","phaseTransportRad","phaseLocking","informationOverlap","bundleWeight"])
        for scale in scales:
            for index, pair in enumerate(scale.pairs):
                writer.writerow([
                    scale.level, index, pair.nodeA, pair.nodeB, repr(pair.phaseTransportRad),
                    repr(pair.phaseLocking), repr(pair.informationOverlap), repr(pair.bundleWeight),
                ])


def analyze_subject(vhdr_path: str, subject: str, group: str, source_metadata_path: str, out_dir: str) -> dict:
    channels, n = load_fixed_channels(vhdr_path)
    starts = window_starts(n)
    rows = []
    parity_scales = None
    parity_input = None
    for wi, start in enumerate(starts):
        sliced = {
            name: [v if math.isfinite(v) else 0.0 for v in values[start:start + WINDOW_SAMPLES]]
            for name, values in channels.items()
        }
        _source_names, window, finite_fraction, scales = core.analyze(
            sliced, EXPECTED_SAMPLE_RATE_HZ, WINDOW_SAMPLES, core.DEFAULT_MAX_SCALES
        )
        counts = [s.nodeCount for s in scales]
        if counts != EXPECTED_SCALE_COUNTS:
            raise ValueError(f"Unexpected scale counts for {subject}/window{wi}: {counts}")
        if window != WINDOW_SAMPLES:
            raise ValueError(f"Unexpected operator window: {window}")
        nonterminal = scales[:-1]
        q = [s.couplingLossQ for s in nonterminal]
        if any((not math.isfinite(v)) or v <= 0.0 for v in q):
            raise ValueError(f"Invalid Q for {subject}/window{wi}: {q}")
        rows.append({
            "subject": subject,
            "group": group,
            "window_index": wi,
            "start_sample": start,
            "end_sample_exclusive": start + WINDOW_SAMPLES,
            "finite_input_fraction": finite_fraction,
            "kappa_slope": ols_slope([s.kappa for s in nonterminal]),
            "mean_plv_slope": ols_slope([s.meanPhaseLocking for s in nonterminal]),
            "log_q_slope": ols_slope([math.log(s.couplingLossQ) for s in nonterminal]),
            "chi_dyn_slope": ols_slope([s.chiDyn for s in nonterminal]),
            "stability_slope": ols_slope([s.stabilityReserve for s in nonterminal]),
        })
        if wi == WINDOWS_PER_SUBJECT - 1:
            parity_scales = scales
            parity_input = sliced
    if parity_scales is None or parity_input is None:
        raise AssertionError("Parity window missing")

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    metrics_path = out / "window_metrics.csv"
    with metrics_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    write_parity_outputs(out, parity_scales, parity_input)

    with open(source_metadata_path, "r", encoding="utf-8") as f:
        source_metadata = json.load(f)
    expected_group = "control" if source_metadata["group"] == "Control" else "psychosis"
    if group != expected_group:
        raise ValueError(f"Group mismatch: argument={group}, metadata={source_metadata['group']}")

    summary = {
        "adapter_version": ADAPTER_VERSION,
        "oracle_version": core.ORACLE_VERSION,
        "subject": subject,
        "group": group,
        "source_metadata": source_metadata,
        "sample_rate_hz": EXPECTED_SAMPLE_RATE_HZ,
        "selected_channel_count": 19,
        "canonical_channel_order": CANONICAL_ORDER,
        "source_channel_order": SOURCE_ORDER,
        "recording_samples": n,
        "window_samples": WINDOW_SAMPLES,
        "window_starts": starts,
        "scale_node_counts": EXPECTED_SCALE_COUNTS,
        "primary_subject_score_median_kappa_slope": statistics.median(r["kappa_slope"] for r in rows),
        "secondary_subject_scores": {
            "median_mean_plv_slope": statistics.median(r["mean_plv_slope"] for r in rows),
            "median_log_q_slope": statistics.median(r["log_q_slope"] for r in rows),
            "median_chi_dyn_slope": statistics.median(r["chi_dyn_slope"] for r in rows),
            "median_stability_slope": statistics.median(r["stability_slope"] for r in rows),
        },
        "parity_window_index": WINDOWS_PER_SUBJECT - 1,
        "parity_window_is_tail": starts[-1] == n - WINDOW_SAMPLES,
        "outputs": {
            "window_metrics.csv": core.sha256_file(str(metrics_path)),
            "parity_window/input.csv": core.sha256_file(str(out / "parity_window" / "input.csv")),
            "parity_window/scales.csv": core.sha256_file(str(out / "parity_window" / "scales.csv")),
            "parity_window/pairs.csv": core.sha256_file(str(out / "parity_window" / "pairs.csv")),
        },
    }
    with (out / "subject_summary.json").open("w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, sort_keys=True)
        f.write("\n")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vhdr", required=True)
    parser.add_argument("--subject", required=True)
    parser.add_argument("--group", choices=["control", "psychosis"], required=True)
    parser.add_argument("--source-metadata", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    result = analyze_subject(args.vhdr, args.subject, args.group, args.source_metadata, args.out_dir)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
