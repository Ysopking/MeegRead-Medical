# Weltformel v0.7 – Integration in MeegRead Medical

Status: **research-only implementation adapter**. This document freezes the app-side mapping used by `CouplingFieldV07Engine`.

## Purpose

The app now exposes the recursive coupling-field observables introduced in v0.7 without silently converting them into a clinical diagnosis. The implementation keeps the strong-theory quantities separate from what can currently be inferred from ordinary EEG/MEG sensor recordings.

## Frozen observable mapping

| Theory symbol | App observable | Implementation |
|---|---|---|
| `Psi_d` | analytic node signal | 1–40 Hz-compatible band-limited, z-normalized EEG/MEG signal plus truncated Hilbert quadrature |
| `A_ij` | phase transport | circular mean of `phase(i)-phase(j)` |
| `K_ij` | coupling strength | phase-locking value (PLV) |
| `kappa_d` | shared-information factor | mean normalized mutual information between node signals |
| `J_i` | directed flow proxy | mean `amplitude^2 * phase_velocity` |
| `c_d` | direction coherence | `abs(sum J_i) / sum abs(J_i)` |
| `Gamma_d` | coupling quality | `kappa_d * c_d` |
| `P_d` | total flow magnitude proxy | `sum abs(J_i)` |
| `Q_d` | coupling-loss proxy | `P_d * (1 - Gamma_d)` |
| `chi_dyn,d` | dynamical stability coordinate | spectral radius `rho(A_VAR(1))` of a ridge-regularized VAR(1) |
| `B_d` | recursive coarse graining | greedy strongest-pair bundling with phase transport and weight `K_ij * kappa_ij` |

After each coarse-graining step **all observables are recomputed from the new node signals**. The next scale is not assigned the previous scale's values.

## Recursive bundling

For a selected pair `(i,j)` the app computes

```text
Psi_(ij),d+1 = [Psi_i,d + w_ij exp(i A_ij,d) Psi_j,d] / sqrt(1 + w_ij^2)
```

with

```text
w_ij = K_ij * kappa_ij,  0 <= w_ij <= 1.
```

Pairs are chosen deterministically by descending `w_ij`, then the procedure is repeated on the newly formed scale until one node remains or the configured scale limit is reached.

## Stability coordinate

The app fits a multivariate VAR(1)

```text
x_t = A x_(t-1) + e_t
```

and reports

```text
chi_dyn = rho(A).
```

`rho(A)` is estimated by the Gelfand limit using repeated matrix powers. The app displays `1 - chi_dyn` as a local stability reserve.

This is **not** the same quantity as the strong load ratio

```text
chi = L / C.
```

The app does not infer `L`, `C`, recovery `R`, or a biological collapse threshold from a passive recording. Those require a separately calibrated load/recovery protocol.

## Sensor-space versus source-space

The strong v0.7 neurophysiology test is intended for source-space signals. Current ordinary imports are therefore labeled

```text
SENSOR_SPACE_PROXY
```

unless the recording metadata explicitly declares a source-space representation (`space`, `analysis_space`, or `source_space` containing `source`).

This guard prevents sensor-level results from being presented as if deep or cortical generators had already been localized.

## Medical status

The new section is a theory-aligned research surface. In particular:

- `Q` is not watts and is not a physical power measurement.
- `chi_dyn` is not a calibrated clinical overload boundary.
- PLV, mutual information, VAR stability, and recursive scale behavior are descriptive research observables.
- No value is a diagnosis, biomarker, treatment recommendation, or proof of the universal field theory.

## First falsification-oriented test

The next empirical stage should freeze a source-space dataset and test whether the same normalized relation survives recursive scales without per-scale re-fitting. A central predeclared test is the sign of the relation

```text
Q_d(t) ↑  ->  [1 - chi_dyn,d(t + tau)] ↓
```

and whether that relation is preserved for `d=0,1,2,...` under the same operator.

Failure to preserve the relation, or the need for scale-specific free parameters, counts against the strong scale-invariance claim.
