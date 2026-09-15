# v0.7 public Muse parity validation

## Status

This validation is a reproducibility gate for the already-frozen Android implementation. It does not change the v0.7 operator and it does not tune parameters from the public-data result.

## Frozen Android reference

- Repository: `Ysopking/MeegRead-Medical`
- Commit: `4d39eeae6e60b3ecedc53d8d567d857d5017fdcc`
- `CouplingFieldV07Engine.kt` Git blob: `be472fb4939d723dc4133f3652a8deabee470a04`
- `SignalAnalysisEngine.kt` Git blob: `e6f25721ad18be07666bf38de4921de6ee914a21`
- Engine version: `coupling-field-v0.7-adapter-1.0`

The exact current implementation uses the final power-of-two analysis window with default size 2048 samples, a 1–40 Hz app-side bandpass at 256 Hz, z-normalization, a truncated odd Hilbert kernel with radius 31, eight-bin normalized mutual information, PLV×NMI bundle weights, deterministic strongest-bundle pairwise coarse graining with phase transport, and a ridge VAR(1) spectral-radius estimate for `chi_dyn`.

This is deliberately the code-defined v0.7 operator. No alpha-only 8–12 Hz substitution is introduced in this validation.

## Frozen public dataset

- Repository: `itayinbarr/web-muse`
- Path: `assets/resting-state.csv`
- Commit: `109e9a199efc7900810f7d2cad6d6852f0d1f324`
- Git blob: `a5931f37244054fbf7997999505730c989069f72`
- Channels: TP9, AF7, AF8, TP10
- Frozen sampling rate: 256 Hz

The upstream repository describes this file as a real pre-recorded Muse resting-state EEG recording and documents the Muse stream at 256 Hz. The validation workflow downloads the file from the pinned commit and rejects it if its Git blob hash differs.

## Independent oracle

`tools/validation/v07_reference.py` is a standard-library-only Python implementation of the published/frozen numerical operations. It neither imports nor invokes Android/Kotlin code. It exports:

- `scales.csv` — one row per recursive scale,
- `pairs.csv` — pair-level phase, PLV, NMI and bundle values,
- `manifest.json` — source pins, data hashes, configuration and guardrails.

The dedicated JUnit parity test reconstructs the same four-channel recording and compares the Android result against the Python oracle at scale level and pair level.

## Expected topology

With four sensor-space nodes the recursive node counts are expected to be:

`4 -> 2 -> 1`

The terminal one-node scale is retained by the current engine and therefore has a `ScaleResult`; it simply has no pair rows.

## Interpretation boundary

The public Muse run is `SENSOR_SPACE_PROXY` only. It can test numerical transport, recursive execution and independent Kotlin-versus-Python parity. It cannot by itself establish source-space invariance, a clinical biomarker, causal neurophysiology, physical power, or a calibrated load/capacity ratio.

`Q = P(1-Gamma)` remains a normalized coupling-loss proxy. `chi_dyn` remains the VAR(1) spectral-radius stability coordinate implemented by the frozen adapter.

## No-result-tuning rule

If this public run fails, the recorded failure is the result for v0.7. Any subsequent change to filtering, Hilbert construction, NMI bins, VAR regularization, coarse-graining, window selection, channel selection or tolerance that changes the scientific operator requires a new version and a new preregistration. The existing v0.7 result must not be overwritten.
