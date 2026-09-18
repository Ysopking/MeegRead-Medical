#!/usr/bin/env python3
"""Aggregate the frozen independent ds003944 kappa confirmation at the PERSON level."""
from __future__ import annotations

import argparse
import csv
import json
import statistics
from pathlib import Path

from scipy.stats import mannwhitneyu

EXPECTED_CONTROL = 28
EXPECTED_PSYCHOSIS = 44
PRIMARY = "primary_subject_score_median_kappa_slope"
SECONDARIES = {
    "mean_plv_slope": "median_mean_plv_slope",
    "log_q_slope": "median_log_q_slope",
    "chi_dyn_slope": "median_chi_dyn_slope",
    "stability_slope": "median_stability_slope",
}


def find_summaries(root: Path) -> list[dict]:
    rows = []
    for path in sorted(root.rglob("subject_summary.json")):
        with path.open("r", encoding="utf-8") as f:
            obj = json.load(f)
        obj["_path"] = str(path)
        rows.append(obj)
    return rows


def primary_test(psychosis: list[float], control: list[float]) -> dict:
    # Frozen implementation choice before ds003944 outcomes: large-sample Mann-Whitney
    # uses SciPy's asymptotic distribution with continuity correction.
    result = mannwhitneyu(psychosis, control, alternative="less", method="asymptotic", use_continuity=True)
    u = float(result.statistic)
    return {
        "alternative": "Psychosis < Control",
        "method": "scipy.mannwhitneyu asymptotic one-sided with continuity correction",
        "u_psychosis_vs_control": u,
        "p_one_sided": float(result.pvalue),
        "auc_psychosis_higher": u / (len(psychosis) * len(control)),
        "control_median": statistics.median(control),
        "psychosis_median": statistics.median(psychosis),
        "psychosis_minus_control_median": statistics.median(psychosis) - statistics.median(control),
    }


def secondary_test(psychosis: list[float], control: list[float]) -> dict:
    result = mannwhitneyu(psychosis, control, alternative="two-sided", method="asymptotic", use_continuity=True)
    u = float(result.statistic)
    return {
        "method": "scipy.mannwhitneyu asymptotic two-sided with continuity correction",
        "u_psychosis_vs_control": u,
        "p_two_sided": float(result.pvalue),
        "auc_psychosis_higher": u / (len(psychosis) * len(control)),
        "control_median": statistics.median(control),
        "psychosis_median": statistics.median(psychosis),
        "psychosis_minus_control_median": statistics.median(psychosis) - statistics.median(control),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-root", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    root = Path(args.input_root)
    out = Path(args.out_dir)
    summaries = find_summaries(root)
    by_subject = {}
    for s in summaries:
        subject = s["subject"]
        if subject in by_subject:
            raise SystemExit(f"Duplicate subject summary: {subject}")
        by_subject[subject] = s
    controls = [s for s in by_subject.values() if s["group"] == "control"]
    psychosis = [s for s in by_subject.values() if s["group"] == "psychosis"]
    if (len(controls), len(psychosis)) != (EXPECTED_CONTROL, EXPECTED_PSYCHOSIS):
        raise SystemExit(f"Frozen cohort mismatch: control={len(controls)} psychosis={len(psychosis)} total={len(by_subject)}")

    c_primary = [float(s[PRIMARY]) for s in controls]
    p_primary = [float(s[PRIMARY]) for s in psychosis]
    primary = primary_test(p_primary, c_primary)

    secondary = {}
    for label, key in SECONDARIES.items():
        c = [float(s["secondary_subject_scores"][key]) for s in controls]
        p = [float(s["secondary_subject_scores"][key]) for s in psychosis]
        secondary[label] = secondary_test(p, c)

    result = {
        "validation_id": "v07-kappa-ds003944-fep-control-nonA-confirmatory",
        "n_control": len(controls),
        "n_psychosis": len(psychosis),
        "primary_endpoint": "subject median of 12 per-window v0.7 kappa scale slopes",
        "alpha": 0.05,
        "primary": primary,
        "primary_supported": primary["p_one_sided"] <= 0.05,
        "secondary_predeclared": secondary,
        "guardrail": "Only the preregistered kappa endpoint is confirmatory. Secondary endpoints remain secondary regardless of their p-values.",
    }
    out.mkdir(parents=True, exist_ok=True)
    with (out / "group_summary.json").open("w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, sort_keys=True)
        f.write("\n")
    with (out / "group_subjects.csv").open("w", encoding="utf-8", newline="") as f:
        fieldnames = ["subject","group","kappa_slope","mean_plv_slope","log_q_slope","chi_dyn_slope","stability_slope"]
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for s in sorted(by_subject.values(), key=lambda x: x["subject"]):
            sec = s["secondary_subject_scores"]
            writer.writerow({
                "subject": s["subject"],
                "group": s["group"],
                "kappa_slope": s[PRIMARY],
                "mean_plv_slope": sec["median_mean_plv_slope"],
                "log_q_slope": sec["median_log_q_slope"],
                "chi_dyn_slope": sec["median_chi_dyn_slope"],
                "stability_slope": sec["median_stability_slope"],
            })
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
