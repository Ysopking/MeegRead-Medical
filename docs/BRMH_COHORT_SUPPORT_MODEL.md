# BRMH Cohort Support Model Card

## Source

- File: `EEG.machinelearing_data_BRMH.csv`
- SHA-256: `88c71df61fbc6d07c8e8f83b9b19c00c9200c664a9ede4a54207cb2d72a4ec09`
- Rows / subjects: 945
- Columns: 1,149
- Main cohort label: `main.disorder`
- Specific label: `specific.disorder`

## Actual cohort counts in the uploaded CSV

| Main cohort | N |
|---|---:|
| Mood disorder | 266 |
| Addictive disorder | 186 |
| Trauma and stress related disorder | 128 |
| Schizophrenia | 117 |
| Anxiety disorder | 107 |
| Healthy control | 95 |
| Obsessive compulsive disorder | 46 |

Specific labels:

| Specific label | N |
|---|---:|
| Depressive disorder | 199 |
| Schizophrenia | 117 |
| Healthy control | 95 |
| Alcohol use disorder | 93 |
| Behavioral addiction disorder | 93 |
| Bipolar disorder | 67 |
| Panic disorder | 59 |
| Posttraumatic stress disorder | 52 |
| Social anxiety disorder | 48 |
| Obsessive compulsitve disorder | 46 |
| Acute stress disorder | 38 |
| Adjustment disorder | 38 |

These counts are taken from the uploaded CSV itself and therefore supersede inconsistent cohort tables in older MMSI documents for this specific dataset version.

## Frozen v1 model

`brmh-4ch-logreg-v1.0` uses 20 absolute band-power features from the exact BRMH electrodes F7, F8, T3 and T4:

- delta: 0.5–4 Hz
- theta: 4–8 Hz
- alpha: 8–13 Hz
- beta: 13–30 Hz
- high-beta: 20–30 Hz

Features are transformed with `log1p`, standardized using the uploaded BRMH training cohort, and evaluated by balanced multinomial logistic regression. Class weights are balanced.

The Android implementation deliberately does **not** silently substitute AF7/AF8/TP9/TP10 for F7/F8/T3/T4 because those are different electrode locations.

## 5-fold cross-validation on the uploaded BRMH cohort

| Metric | Result |
|---|---:|
| Accuracy | 17.7% |
| Balanced accuracy | 20.4% |
| Macro-F1 | 17.4% |
| Top-3 accuracy | 53.8% |

One-vs-rest ROC-AUC by main cohort:

| Cohort | AUC |
|---|---:|
| Healthy control | 0.645 |
| Obsessive compulsive disorder | 0.644 |
| Addictive disorder | 0.615 |
| Anxiety disorder | 0.569 |
| Mood disorder | 0.568 |
| Schizophrenia | 0.567 |
| Trauma and stress related disorder | 0.529 |

## Intended use

This model is a **research-only cohort assignment aid**. The app reports a ranked top-3 cohort-support score and the diagnostic subgroups represented in the dataset. It is not a psychiatric diagnosis and is not a replacement for ICD-based clinical assessment, structured diagnostic interview, psychopathological examination, differential diagnosis, or clinician judgment.

The observed cross-validation performance is insufficient for autonomous diagnosis. The scores should only be used as exploratory EEG cohort similarity information and as a basis for future prospective validation.

## Important limitations

- The dataset contains precomputed band-power/coherence features rather than the raw recordings used by the Android app.
- Absolute power is sensitive to acquisition units, preprocessing, reference montage and hardware.
- F7/F8/T3/T4 are required for the frozen model; AF7/AF8/TP9/TP10 are not equivalent.
- The model has not been externally validated on an independent clinical cohort.
- The dataset labels are treated as supplied; this model card does not establish how each source diagnosis was clinically adjudicated.
- Top-1 discrimination is weak. The UI therefore exposes top-3 cohort support and its validation metrics rather than presenting a definitive label.
