# v0.7 preprocessed source-space input contract

This contract adds input plumbing around the frozen `coupling-field-v0.7-adapter-1.0` engine. It does **not** change the scientific operator and it does not perform EEG/MEG source reconstruction inside MeegRead Medical.

## Scope

A source-space input is a set of already reconstructed and preprocessed node/parcel time series produced by an external pipeline. The app only imports those time series, selects the explicitly declared source nodes, creates an internal compatibility view for the frozen v0.7 engine, and runs the same operator that is used for sensor-space input.

The original imported `MeegRecording` remains unchanged. In particular, parcel channels may truthfully use `type: "OTHER"`. `CouplingFieldV07InputAdapter` temporarily exposes selected nodes as an accepted EEG channel type only inside the call boundary to the frozen engine. This is a software compatibility mapping, not a statement that the parcels are scalp EEG sensors.

## Required declarations

The JSON recording format already supported by `JsonRecordingParser` is used. The recording must declare source space through one of the existing space metadata fields, for example:

```json
"metadata": {
  "analysis_space": "source-space"
}
```

It must then declare source nodes in exactly one of two ways:

1. `"source_nodes_all_channels": "true"` — every imported channel is a source node; or
2. `"source_nodes": "ParcelA,ParcelB,ParcelC,ParcelD"` — only the named channels are source nodes.

Supplying both declarations, neither declaration, fewer than two source nodes, or a `source_nodes` name that is absent from the file is rejected. The adapter does not guess which channels are parcels and does not silently include auxiliary channels.

## Example

```json
{
  "schema": "meegread.medical.recording.v1",
  "name": "subject01_source_parcels",
  "modality": "UNKNOWN",
  "sampleRateHz": 200.0,
  "metadata": {
    "analysis_space": "source-space",
    "source_node_kind": "parcel",
    "source_atlas": "external-atlas-name",
    "source_pipeline": "external-preprocessing-pipeline",
    "source_nodes": "lh.precuneus,rh.precuneus,lh.mPFC,rh.mPFC"
  },
  "events": [],
  "channels": [
    {
      "name": "lh.precuneus",
      "type": "OTHER",
      "unit": "a.u.",
      "samples": [0.01, 0.02, 0.01, -0.01]
    },
    {
      "name": "rh.precuneus",
      "type": "OTHER",
      "unit": "a.u.",
      "samples": [0.00, 0.01, 0.02, 0.01]
    }
  ]
}
```

The abbreviated example above shows the schema only; a real v0.7 run still requires at least the engine minimum number of samples per selected node.

## Reproducibility

`CouplingFieldV07Audit` records:

- frozen engine and signal-engine blob identities;
- the unchanged v0.7 algorithm fingerprint;
- `CouplingFieldV07InputAdapter.VERSION`;
- whether the run used `DIRECT_SENSOR_OR_MEG` or `PREPROCESSED_SOURCE_NODES`;
- the selected source-node names;
- sample rate, requested and actual window, max scale count, and all source channels;
- every scale metric and pair metric.

The per-run configuration SHA-256 therefore changes when the source-node selection or input profile changes, while the v0.7 algorithm fingerprint remains unchanged.

## Interpretation boundary

`SOURCE_SPACE` means only that the imported recording explicitly declares externally reconstructed source-node time series and passes the contract above. It is not evidence that the inverse method, forward model, atlas, co-registration, artifact handling, or preprocessing pipeline was scientifically valid. Those upstream choices must be documented and validated outside this adapter.

The v0.7 outputs remain research observables. They are not a diagnosis, biomarker, therapy recommendation, calibrated physical power measurement, or proof of the Weltformel.
