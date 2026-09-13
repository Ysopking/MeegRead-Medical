# MMSI 19-channel research metric v1.0

This document freezes the deterministic W/Y/Z feature construction used by `ResearchFriction19Engine`.

## Input montage

Exact 10-20 channels: FP1, FP2, F7, F3, FZ, F4, F8, T3, C3, CZ, C4, T4, T5, P3, PZ, P4, T6, O1, O2.

No AF7/F8/TP9/TP10 substitution is performed.

## Bands

- delta: 0.5-4 Hz
- theta: 4-8 Hz
- alpha: 8-12 Hz
- beta: 12-20 Hz
- high-beta: 20-30 Hz
- gamma: 30-45 Hz

## Frozen equations

For one feature epoch, let the six absolute band-power vectors contain 19 values each.

- `D = std(all 6 x 19 band-power cells) / (abs(mean(all cells)) + eps)`
- `Z = mean(alpha) * (1 + mean_alpha_coherence_percent / 100)`
- `Y = (0.5 * mean(beta) + mean(highbeta) + mean(gamma)) * (1 + D)`
- `W_raw = Y / (Z + eps)`
- `W = W_raw / 0.7597781027727232`

The constant `0.7597781027727232` is the raw W median of the 95 healthy-control rows in the uploaded BRMH CSV under this feature-table calculation. It makes the reference healthy-control median equal to 1.0.

Local channel ratios are:

`W_i = (0.5 * beta_i + highbeta_i + gamma_i) / (alpha_i + eps)`

## Alpha coherence

The BRMH reference table is reproduced by averaging the alpha-coherence columns across all 171 unique channel pairs. The raw-signal implementation mirrors this by averaging magnitude-squared coherence over all unique channel pairs and the 8-12 Hz bins.

This differs from a fronto-parietal-only coherence definition. A future algorithm version must use a different version identifier if that definition is changed.

## Research ranges

The UI exposes the following frozen model ranges:

- W <= 1.0: baseline range
- 1.0 < W < 2.30: elevated ratio
- 2.30 <= W < e: high ratio
- W >= e: above research limit

These are research-model ranges. They are not standalone medical thresholds.

## Reproducibility notes

Using the uploaded `EEG.machinelearing_data_BRMH.csv` (N=945), the reported Y and Z cohort table is reproducible when:

1. dispersion D is computed across all 114 absolute-band-power cells per subject;
2. Z uses the mean of all 171 alpha-coherence values per subject;
3. W is normalized by the healthy-control raw median above.

The subgroup p-values in the supplied table match two-sided Mann-Whitney U comparisons against the healthy-control W distribution.

## Scope

This engine is a deterministic research feature extractor and cohort-context metric. It does not by itself establish a diagnosis, treatment indication, emergency status, legal conclusion, regulatory approval, or causal biological mechanism.
