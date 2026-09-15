#!/usr/bin/env python3
"""Aggregate the frozen RepOD schizophrenia/control v0.7 subject results."""
from __future__ import annotations

import argparse
import csv
import glob
import hashlib
import json
import math
import os
import statistics
from typing import Dict, List, Sequence, Tuple

from scipy.stats import mannwhitneyu

VALIDATION_ID = "v07-repod-schizophrenia-h01-h14-s01-s14"
EXPECTED_HEALTHY = [f"h{i:02d}" for i in range(1, 15)]
EXPECTED_SCHIZOPHRENIA = [f"s{i:02d}" for i in range(1, 15)]
EXPECTED_SUBJECTS = EXPECTED_HEALTHY + EXPECTED_SCHIZOPHRENIA
PERMUTATIONS = 10_000


def load_subjects(root: str) -> List[dict]:
    paths = sorted(glob.glob(os.path.join(root, "**", "subject_summary.json"), recursive=True))
    rows = []
    seen = set()
    for path in paths:
        with open(path, "r", encoding="utf-8") as handle:
            row = json.load(handle)
        subject = str(row.get("subject", "")).lower()
        if subject in seen:
            raise ValueError(f"Duplicate subject summary: {subject}")
        seen.add(subject)
        rows.append(row)
    if seen != set(EXPECTED_SUBJECTS):
        missing = sorted(set(EXPECTED_SUBJECTS) - seen)
        extra = sorted(seen - set(EXPECTED_SUBJECTS))
        raise ValueError(f"Frozen cohort mismatch; missing={missing}, extra={extra}")
    return sorted(rows, key=lambda x: EXPECTED_SUBJECTS.index(str(x["subject"]).lower()))


def exact_mw(sz: Sequence[float], healthy: Sequence[float]) -> dict:
    if len(sz) != 14 or len(healthy) != 14:
        raise ValueError("Exact primary Mann-Whitney test requires 14+14 subjects")
    if any(not math.isfinite(x) for x in list(sz) + list(healthy)):
        raise ValueError("Non-finite subject score")
    result = mannwhitneyu(sz, healthy, alternative="two-sided", method="exact")
    u = float(result.statistic)
    return {
        "u_schizophrenia_vs_healthy": u,
        "p_two_sided_exact": float(result.pvalue),
        "auc_schizophrenia_higher": u / (len(sz) * len(healthy)),
        "median_schizophrenia": float(statistics.median(sz)),
        "median_healthy": float(statistics.median(healthy)),
        "median_difference_schizophrenia_minus_healthy": float(statistics.median(sz) - statistics.median(healthy)),
    }


def predict_by_training_medians(scores: Sequence[float], labels: Sequence[str]) -> List[str]:
    predictions = []
    n = len(scores)
    for held in range(n):
        h = [scores[i] for i in range(n) if i != held and labels[i] == "healthy"]
        s = [scores[i] for i in range(n) if i != held and labels[i] == "schizophrenia"]
        if not h or not s:
            raise ValueError("A leave-one-out fold lost a training class")
        mh = statistics.median(h)
        ms = statistics.median(s)
        dh = abs(scores[held] - mh)
        ds = abs(scores[held] - ms)
        predictions.append("schizophrenia" if ds < dh else "healthy")
    return predictions


def balanced_accuracy(labels: Sequence[str], predictions: Sequence[str]) -> float:
    h_idx = [i for i, x in enumerate(labels) if x == "healthy"]
    s_idx = [i for i, x in enumerate(labels) if x == "schizophrenia"]
    specificity = sum(predictions[i] == "healthy" for i in h_idx) / len(h_idx)
    sensitivity = sum(predictions[i] == "schizophrenia" for i in s_idx) / len(s_idx)
    return 0.5 * (specificity + sensitivity)


def permuted_labels(subjects: Sequence[str], replicate: int) -> List[str]:
    ranked = []
    for subject in subjects:
        token = f"{VALIDATION_ID}|classification-permutation|{replicate}|{subject}".encode("utf-8")
        ranked.append((hashlib.sha256(token).digest(), subject))
    ranked.sort(key=lambda x: (x[0], x[1]))
    schizophrenia = {subject for _, subject in ranked[:14]}
    return ["schizophrenia" if subject in schizophrenia else "healthy" for subject in subjects]


def run_classifier_permutation(subjects: Sequence[str], scores: Sequence[float], labels: Sequence[str]) -> Tuple[dict, List[float]]:
    observed_predictions = predict_by_training_medians(scores, labels)
    observed = balanced_accuracy(labels, observed_predictions)
    null = []
    for r in range(PERMUTATIONS):
        perm_labels = permuted_labels(subjects, r)
        perm_predictions = predict_by_training_medians(scores, perm_labels)
        null.append(balanced_accuracy(perm_labels, perm_predictions))
    p = (1 + sum(x >= observed for x in null)) / (PERMUTATIONS + 1)
    return {
        "balanced_accuracy": observed,
        "empirical_p": p,
        "permutations": PERMUTATIONS,
        "prediction_by_subject": dict(zip(subjects, observed_predictions)),
        "healthy_specificity": sum(
            pred == "healthy" for pred, label in zip(observed_predictions, labels) if label == "healthy"
        ) / 14.0,
        "schizophrenia_sensitivity": sum(
            pred == "schizophrenia" for pred, label in zip(observed_predictions, labels) if label == "schizophrenia"
        ) / 14.0,
    }, null


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-root", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    rows = load_subjects(args.input_root)
    os.makedirs(args.out_dir, exist_ok=True)

    subjects = [str(row["subject"]).lower() for row in rows]
    labels = [str(row["group"]) for row in rows]
    expected_labels = ["healthy"] * 14 + ["schizophrenia"] * 14
    if labels != expected_labels:
        raise ValueError(f"Group labels do not match frozen filename-defined cohort: {labels}")

    primary_scores = [float(row["primary_subject_score_median_mean_plv_slope"]) for row in rows]
    healthy = primary_scores[:14]
    sz = primary_scores[14:]
    primary = exact_mw(sz, healthy)
    classifier, classifier_null = run_classifier_permutation(subjects, primary_scores, labels)

    secondary_keys = [
        "median_kappa_slope",
        "median_log_q_slope",
        "median_chi_dyn_slope",
        "median_stability_slope",
    ]
    secondary = {}
    for key in secondary_keys:
        vals = [float(row["secondary_subject_scores"][key]) for row in rows]
        secondary[key] = exact_mw(vals[14:], vals[:14])

    group_subjects_path = os.path.join(args.out_dir, "group_subjects.csv")
    with open(group_subjects_path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "subject", "group", "primary_median_mean_plv_slope", "median_kappa_slope",
            "median_log_q_slope", "median_chi_dyn_slope", "median_stability_slope",
            "loocv_prediction",
        ])
        for i, row in enumerate(rows):
            sec = row["secondary_subject_scores"]
            writer.writerow([
                subjects[i], labels[i], repr(primary_scores[i]), repr(float(sec["median_kappa_slope"])),
                repr(float(sec["median_log_q_slope"])), repr(float(sec["median_chi_dyn_slope"])),
                repr(float(sec["median_stability_slope"])), classifier["prediction_by_subject"][subjects[i]],
            ])

    null_path = os.path.join(args.out_dir, "classifier_null.csv")
    with open(null_path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["replicate", "balanced_accuracy"])
        for r, value in enumerate(classifier_null):
            writer.writerow([r, repr(value)])

    summary = {
        "validation_id": VALIDATION_ID,
        "n_healthy": 14,
        "n_schizophrenia": 14,
        "primary_endpoint": "subject median of 12 per-window meanPhaseLocking scale slopes",
        "primary_exact_mann_whitney": primary,
        "secondary_predeclared": secondary,
        "secondary_single_feature_loocv": classifier,
        "interpretation_guardrails": [
            "Research group discrimination only; not a diagnostic or biomarker claim.",
            "The person, not the window, is the unit of inference.",
            "No post-result metric or direction selection is permitted.",
        ],
    }
    summary_path = os.path.join(args.out_dir, "group_summary.json")
    with open(summary_path, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
