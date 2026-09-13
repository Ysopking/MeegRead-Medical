# MeegRead Medical

Android EEG/MEG analysis and diagnostic-support research module.

## Current capabilities
- EDF/BDF, FIFF, CSV/TSV, JSON and MGR import
- BLE EEG acquisition
- Welch PSD, band powers, coherence, filtering and common-average reference
- 2D/3D sensor visualization
- Pseudonymized local session archive
- Experimental MMSI thermodynamic-load trajectory using AF7/AF8/TP9/TP10
- Session-level research history and reproducible algorithm versioning

## Medical/research status
The MMSI-derived load metric and its Omega model parameter are experimental research outputs, not an established clinical norm or standalone diagnosis. Signal validity and clinical interpretation require professional review and independent validation.

## Build
GitHub Actions runs unit tests, assembles the debug APK, calculates SHA-256 and uploads both files as a workflow artifact.
