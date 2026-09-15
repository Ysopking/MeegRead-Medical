# v0.7 deterministic batch validation

`CouplingFieldV07BatchValidator` runs the already frozen v0.7 operator over a caller-supplied list of recordings without changing any scientific parameter. It is intended for reproducible validation cohorts, not clinical classification.

## Frozen behavior

- Input order is preserved exactly and is part of the batch result fingerprint.
- Every recording uses the same requested window and max-scale configuration supplied to the batch run.
- Every successful item is produced through `CouplingFieldV07Audit`, so it carries the frozen algorithm fingerprint, per-run config SHA-256, input profile, operational space, scale metrics and pair metrics.
- A recording that does not satisfy the v0.7 input contract is recorded as `NOT_ANALYZABLE`; it is not silently dropped.
- Unexpected exceptions are recorded as `ERROR` with the exception class; the remaining batch items continue.
- The batch result SHA-256 hashes the ordered statuses, per-run config hashes and every scale metric using canonical hexadecimal floating-point representations. It is therefore a deterministic fingerprint of the produced batch result.

## Export bundle

`V07BatchExportManager.writeZip(...)` writes a ZIP containing:

- `manifest.json` — batch version, algorithm fingerprint, batch result fingerprint, fixed run configuration, counts and item-level status/config identities;
- `scales.csv` — one row per recursive scale, with recording index/name, status, input profile, operational space, config SHA-256, algorithm SHA-256 and all exported scale observables;
- `audits/NNNN_<recording>_v07_audit.json` — the complete deterministic v0.7 audit document for every successful recording.

Raw EEG/MEG/source-node recordings are deliberately not copied into the export bundle. Dataset identity and redistribution remain the responsibility of the validation protocol that supplies the recordings.

## Interpretation

A green batch establishes deterministic software transport for the supplied recordings and configuration. It does not convert the research observables into a diagnosis or validated biomarker and does not establish physical universality. The public Muse Kotlin↔Python parity workflow remains the independent invariant gate for the frozen v0.7 implementation.
