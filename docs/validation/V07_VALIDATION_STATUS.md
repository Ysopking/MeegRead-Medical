# v0.7 Validation Status

This status file records validation progress only. It does not redefine the frozen v0.7 operator, datasets, null models, endpoints, thresholds or parity tolerances.

## Current frozen head

- PR: #12
- Head: `13dd30997ae1a198f343bdc58abdd0fb449ac254`
- Guardrail: `SENSOR_SPACE_PROXY`; research/implementation/statistical validation only; no diagnostic, biomarker, source-localization, causal, clinical or universality claim.

## Completed gates on the current head

- Android CI: success
- Public Muse parity: success
- CogWear parity: success
- EEGMMIDB 64-channel scale parity: success
- EEGMMIDB multi-subject null rerun: in progress
- SRM independent-dataset dual-null replication: in progress

## Fresh current-head confirmations

### EEGMMIDB S005

The full current-head subject job completed successfully, including frozen-identity checks, Python reference/null generation and Kotlin↔Python observed parity.

- source channels: 64
- scales: `64 -> 32 -> 16 -> 8 -> 4 -> 2 -> 1`
- observed mean-PLV slope: `0.037269907012164497`
- circular-shift null median: `-0.0037258133591137593`
- primary empirical p: `0.02`
- observed kappa slope: `0.009002451316844386`
- secondary kappa empirical p: `0.02`
- observed logQ slope: `-0.5057594243017209`
- secondary logQ empirical p: `1.0`
- parity/build job: success

### SRM sub-003

The repaired current-head job completed successfully through full Kotlin↔Python parity. The scientific dual-null outcome remains negative and unchanged by the CI-memory-only repair.

- source channels: 64
- scales: `64 -> 32 -> 16 -> 8 -> 4 -> 2 -> 1`
- observed mean-PLV slope: `-0.008051105424116129`
- circular-shift null median: `0.0261457423965063`
- Fourier-phase-randomization null median: `0.0284427497466726`
- `p_shift = 1.0`
- `p_phase = 1.0`
- `dual_null_success = false`
- parity/build job: success

## Merge rule

PR #12 remains unmerged until all preregistered current-head subject jobs and their Kotlin↔Python parity gates have completed successfully. Scientific negative null outcomes are retained as results and are not converted into CI failures or tuned away.
