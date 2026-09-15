# MeegRead Medical

Android EEG/MEG analysis and diagnostic-support research module.

## Current capabilities
- EDF/BDF, FIFF, CSV/TSV, JSON and MGR import
- BLE EEG acquisition
- Welch PSD, band powers, coherence, filtering and common-average reference
- 2D/3D sensor visualization
- Pseudonymized local session archive
- **Weltformel v0.7 coupling-field adapter** with recursive scale analysis (`Psi`, `A`, `K`, `kappa`, `J`, `Gamma`, `Q`, `chi_dyn`)
- Deterministic pairwise coarse graining with phase transport and recomputation on every scale
- Sensor-space/source-space status is explicit; ordinary imports are treated as sensor-space proxies
- Experimental MMSI thermodynamic-load trajectory using AF7/AF8/TP9/TP10 retained as a legacy comparison layer
- Session-level research history and reproducible algorithm versioning

## v0.7 field integration
The primary research card in the analysis screen now implements the frozen app-side mapping documented in [`docs/WELTFORMEL_V07_INTEGRATION.md`](docs/WELTFORMEL_V07_INTEGRATION.md).

The core observables are:

```text
Gamma_d = kappa_d * c_d
Q_d     = P_d * (1 - Gamma_d)
chi_dyn = rho(A_VAR(1))
```

and the recursive bundle operator is applied repeatedly so that all observables are recomputed at each new scale.

`Q` is a normalized coupling-loss proxy, not watts. `chi_dyn` is a VAR stability coordinate, not the calibrated strong-theory ratio `L/C`. A passive recording does not identify biological capacity, recovery or a clinical collapse threshold.

## Medical/research status
All MMSI/Weltformel-derived quantities in this application are experimental research outputs. They are not established clinical norms, standalone diagnoses, biomarkers, treatment recommendations or proof of a universal field theory. Signal validity and clinical interpretation require professional review and independent validation.

## Build
GitHub Actions runs unit tests, assembles the debug APK, calculates SHA-256 and uploads both files as a workflow artifact.
