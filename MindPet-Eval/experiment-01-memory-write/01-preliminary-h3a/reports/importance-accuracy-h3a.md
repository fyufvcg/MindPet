# H3-A Importance Accuracy Experiment

## 1. Run integrity

- Git commit: `427aa1a1a1f4cc325c12ee620600691a18583abc`
- Dataset: Importance Accuracy Benchmark v1 (`b1ba8c81e86d098733c11ef4f4eced3203a0507fadd38277128e2e09d7375d8d`)
- Production model: `deepseek-flash`
- Production prompt SHA-256: `a2f27c59eb39499dc6682bb7e927afc0e19f87013559c0aeacf3c2ef8cb002c9`
- Formal requests / success / failure: 100 / 100 / 0
- Database writes: false
- Ground Truth: 100 confirmed samples, produced by one human annotator.
- No inter-annotator agreement was available.

The Evaluation-only endpoint calls the production extraction prompt, model selection, parser, fallback, clamp and persistence-decision logic, but does not call `persist()`, `appendTurn()`, the knowledge-graph write path, or the curator.

## 2. Importance regression/rank metrics

| samples | MAE | RMSE | Spearman | Pearson | mean human | mean AI | mean signed error |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 0.182500 | 0.228418 | 0.737179 | 0.580687 | 0.488000 | 0.549500 | 0.061500 |

## 3. Threshold and boolean metrics

| target | accuracy | precision | recall | F1 | TP | FP | FN | TN |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| importance >= 0.35 | 0.630000 | 0.621053 | 0.983333 | 0.761290 | 59 | 36 | 1 | 4 |
| importance >= 0.6 | 0.900000 | 0.875000 | 0.875000 | 0.875000 | 35 | 5 | 5 | 55 |
| shouldRemember | 0.870000 | 0.854545 | 0.903846 | 0.878505 | 47 | 8 | 5 | 40 |
| wouldPersistMemory (diagnostic) | 0.870000 | 0.867925 | 0.884615 | 0.876190 | 46 | 7 | 6 | 41 |

False High (AI >= 0.6, Human < 0.6): **5**.
False Low (AI < 0.6, Human >= 0.6): **5**.

The 0.35 result evaluates the importance threshold alone. Production persistence also depends on `shouldRemember` and confidence, so it is not complete persistence accuracy. Likewise, `human_should_remember` is not a manual reproduction of every Java persistence condition; `wouldPersistMemory` is auxiliary diagnosis only.

## 4. Category slices

| category | count | human mean | AI mean | MAE | signed error (AI-Human) |
|---|---:|---:|---:|---:|---:|
| active_project | 7 | 0.528571 | 0.614286 | 0.114286 | 0.085714 |
| long_term_goal | 7 | 0.642857 | 0.592857 | 0.107143 | -0.050000 |
| one_off_task | 6 | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| personal_fact | 7 | 0.528571 | 0.585714 | 0.114286 | 0.057143 |
| place | 7 | 0.557143 | 0.635714 | 0.164286 | 0.078571 |
| recurring_fact | 7 | 0.585714 | 0.550000 | 0.150000 | -0.035714 |
| relationship | 7 | 0.500000 | 0.550000 | 0.107143 | 0.050000 |
| short_term_schedule | 6 | 0.233333 | 0.508333 | 0.275000 | 0.275000 |
| small_talk | 6 | 0.166667 | 0.500000 | 0.333333 | 0.333333 |
| stable_preference | 7 | 0.642857 | 0.600000 | 0.157143 | -0.042857 |
| study_habit | 7 | 0.642857 | 0.542857 | 0.128571 | -0.100000 |
| temporary_state | 6 | 0.200000 | 0.500000 | 0.300000 | 0.300000 |
| tool_habit | 7 | 0.757143 | 0.607143 | 0.150000 | -0.150000 |
| uncertain_claim | 6 | 0.366667 | 0.308333 | 0.125000 | -0.058333 |
| work_habit | 7 | 0.671429 | 0.585714 | 0.185714 | -0.085714 |

Largest positive mean signed errors: one_off_task, small_talk, temporary_state.
Largest negative mean signed errors: tool_habit, study_habit, work_habit.

## 5. Difficulty slices

| difficulty | count | MAE | RMSE | Spearman | human mean | AI mean |
|---|---:|---:|---:|---:|---:|---:|
| easy | 34 | 0.198529 | 0.243292 | 0.655071 | 0.605882 | 0.619118 |
| hard | 33 | 0.178788 | 0.233874 | 0.084807 | 0.336364 | 0.481818 |
| medium | 33 | 0.169697 | 0.205971 | 0.694982 | 0.518182 | 0.545455 |

## 6. Top-20 absolute errors

| sample | category | difficulty | human | AI | absolute error | signed error |
|---|---|---|---:|---:|---:|---:|
| i006 | stable_preference | hard | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i012 | active_project | hard | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i051 | place | hard | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i063 | personal_fact | hard | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i064 | short_term_schedule | easy | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i065 | short_term_schedule | medium | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i067 | short_term_schedule | easy | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i070 | one_off_task | easy | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i071 | one_off_task | medium | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i072 | one_off_task | hard | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i073 | one_off_task | easy | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i074 | one_off_task | medium | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i075 | one_off_task | hard | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i076 | temporary_state | easy | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i079 | temporary_state | easy | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i081 | temporary_state | hard | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i082 | small_talk | easy | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i084 | small_talk | hard | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i085 | small_talk | easy | 0.100000 | 0.500000 | 0.400000 | 0.400000 |
| i086 | small_talk | medium | 0.100000 | 0.500000 | 0.400000 | 0.400000 |

The CSV includes the user message, annotation reason, confidence, threshold flags, and shouldRemember FP/FN flags for every Top-20 case.

## 7. Parse, fallback and clamp diagnostics

- parse failures: 0 (none)
- memory object missing: 42 (i003, i006, i009, i012, i018, i024, i027, i030, i033, i042, i051, i057, i060, i063, i064, i065, i066, i067, i069, i070, i071, i072, i073, i074, i075, i076, i077, i078, i079, i080, i081, i082, i083, i084, i085, i086, i087, i089, i091, i093, i096, i099)
- importance fallback: 42 (i003, i006, i009, i012, i018, i024, i027, i030, i033, i042, i051, i057, i060, i063, i064, i065, i066, i067, i069, i070, i071, i072, i073, i074, i075, i076, i077, i078, i079, i080, i081, i082, i083, i084, i085, i086, i087, i089, i091, i093, i096, i099)
- confidence fallback: 42 (i003, i006, i009, i012, i018, i024, i027, i030, i033, i042, i051, i057, i060, i063, i064, i065, i066, i067, i069, i070, i071, i072, i073, i074, i075, i076, i077, i078, i079, i080, i081, i082, i083, i084, i085, i086, i087, i089, i091, i093, i096, i099)
- importance clamp: 0 (none)
- confidence clamp: 0 (none)

## 8. Interpretation boundaries

- This benchmark measures the current production scorer without prompt, model, parser, fallback, clamp, threshold or retrieval changes.
- Ground Truth was produced by one human annotator; no inter-annotator agreement was available.
- The dataset has 100 curated samples and can reveal benchmark-specific failure patterns, but it does not establish population-wide calibration.
- Confidence is reported as model output; no independent human confidence label exists in this benchmark.

## 9. Result interpretation

- The current production importance-output pipeline shows a material reliability problem on this benchmark: MAE is 0.182500, Pearson is 0.580687, and the 0.35 threshold produces 36 false positives with precision 0.621053.
- The strongest issue is the missing-memory fallback path. 42/100 samples use the 0.5 importance fallback; their MAE is 0.247619. These are not direct LLM importance values.
- Among the 58 samples with a direct memory importance, MAE is 0.135345, Spearman is 0.614401, and Pearson is 0.692401. The high-importance threshold still has 5 False High and 5 False Low cases overall.
- Therefore the data supports a reliability concern for the **current production importance output as a whole**, especially fallback/calibration and low-value categories. It does not by itself justify the stronger blanket claim that every directly generated LLM importance score is unreliable.
