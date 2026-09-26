# Importance Ground Truth Summary

## Status

Ground Truth is confirmed.

- Source workbook: importance_annotation_A.xlsx
- Samples: 100
- Annotator count: 1
- Required human fields complete: 100
- review_status = confirmed: 100
- review_status = pending_human_annotation: 0
- JSONL write-back: completed
- AI importance scoring: not run

The sole human annotator explicitly completed final review. The workbook and JSONL now use review_status = confirmed for all 100 samples.

Ground Truth was produced by one human annotator.

No inter-annotator agreement was available.

## Validation

- Sample IDs are exactly i001 through i100.
- Sample IDs are unique.
- user_message, assistant_context, category, and difficulty exactly match the source JSONL.
- All human_importance values are in {0.1, 0.3, 0.5, 0.7, 0.9}.
- All human_should_remember values are boolean.
- All annotation_reason values are nonblank.
- human_high_importance was recalculated for analysis as human_importance >= 0.6; the existing Excel value was not trusted.

## Human importance distribution

| Human importance | Samples |
| ---: | ---: |
| 0.1 | 20 |
| 0.3 | 20 |
| 0.5 | 20 |
| 0.7 | 26 |
| 0.9 | 14 |

## Should remember distribution

| Value | Samples |
| --- | ---: |
| true | 52 |
| false | 48 |

## Recalculated high importance distribution

| Value | Samples |
| --- | ---: |
| true | 40 |
| false | 60 |

## Category × mean human importance

| Category | Samples | Mean human importance |
| --- | ---: | ---: |
| active_project | 7 | 0.528571 |
| long_term_goal | 7 | 0.642857 |
| one_off_task | 6 | 0.100000 |
| personal_fact | 7 | 0.528571 |
| place | 7 | 0.557143 |
| recurring_fact | 7 | 0.585714 |
| relationship | 7 | 0.500000 |
| short_term_schedule | 6 | 0.233333 |
| small_talk | 6 | 0.166667 |
| stable_preference | 7 | 0.642857 |
| study_habit | 7 | 0.642857 |
| temporary_state | 6 | 0.200000 |
| tool_habit | 7 | 0.757143 |
| uncertain_claim | 6 | 0.366667 |
| work_habit | 7 | 0.671429 |

## Difficulty × mean human importance

| Difficulty | Samples | Mean human importance |
| --- | ---: | ---: |
| easy | 34 | 0.605882 |
| medium | 33 | 0.518182 |
| hard | 33 | 0.336364 |

## Consistency candidates

The requested deterministic consistency rules found no candidates:

- human_importance <= 0.3 and human_should_remember = true: 0
- human_importance >= 0.7 and human_should_remember = false: 0

No labels were changed.

## Boundary samples

- human_importance = 0.5: 20
  - i003, i009, i011, i014, i018, i024, i030, i033, i039, i042, i045, i053, i054, i057, i062, i068, i091, i093, i095, i098
- human_importance = 0.7: 26
  - i002, i005, i007, i008, i010, i013, i015, i016, i017, i020, i022, i025, i028, i031, i038, i041, i044, i047, i049, i050, i052, i055, i056, i058, i059, i094

## Hard samples

There are 33 difficulty = hard samples:

i003, i006, i009, i012, i015, i018, i021, i024, i027, i030, i033, i036, i039, i042, i045, i048, i051, i054, i057, i060, i063, i066, i069, i072, i075, i078, i081, i084, i087, i090, i093, i096, i099

## Formal-run protection

The H3-A runner remains fail closed. A formal run must be rejected if any row has:

- null or invalid human_importance;
- null or non-boolean human_should_remember;
- inconsistent human_high_importance;
- blank annotation_reason;
- review_status != confirmed.

The evaluator uses the same confirmed status contract.
