# E2E Pilot Error Analysis

## Scope

- Run: `pilot30_20260928_101045`
- Source: archived raw results, sample mapping, confirmed Ground Truth, and read-only E2E database rows.
- AI calls: none during this analysis.
- Ground Truth and production algorithms were not changed.

## Entity error analysis

Strict exact matching: P=0.294118, R=0.357143, F1=0.322581 (TP/FP/FN=5/12/9).

Name-normalization diagnostic while preserving entity type: P=0.588235, R=0.714286, F1=0.645161 (TP/FP/FN=10/7/4).

Largest entity error category: `naming_or_normalization_difference` (5 samples). Category counts: {"naming_or_normalization_difference":5,"wrong_type":2,"extra_entity":4,"plausible_but_not_in_ground_truth":3,"over_split":1,"clearly_incorrect":1,"missing_entity":2}.

The diagnostic recovers same-type naming variants including 后端工程/后端开发, 深色主题/深色主题偏好, 全程马拉松/全程马拉松目标, and compound MindPet naming. It deliberately does not forgive type mismatches such as 香菜 topic→preference or 左撇子 other→preference.

## Relation error analysis

Strict exact matching: P=0.210526, R=0.333333, F1=0.258065 (TP/FP/FN=4/15/8).

Endpoint-normalization diagnostic while preserving predicate and endpoint types: P=0.421053, R=0.666667, F1=0.516129 (TP/FP/FN=8/11/4).

Largest relation error category: `relation_without_expected_entity` (5 samples). Category counts: {"wrong_predicate":2,"clearly_incorrect":1,"relation_without_expected_entity":5,"wrong_target":1,"plausible_but_not_ground_truth":3,"generic_related_to_overuse":2}.

Wrong predicates remain real errors after normalization: works_on is used where Ground Truth expects experienced for 后端工程/软件开发. Extra plans/learns and generic related_to edges are not rescued by the diagnostic.

## Matching sensitivity

| target | strict F1 | normalization diagnostic F1 | delta |
|---|---:|---:|---:|
| Entity | 0.322581 | 0.645161 | +0.322581 |
| Relation | 0.258065 | 0.516129 | +0.258065 |

Case folding and whitespace normalization were already present in the strict evaluator. The observed lift comes from same-type synonyms, suffix expansion, compound-name alignment, and relation endpoint normalization. This is diagnostic only and does not replace the formal metrics.

## Fallback analysis

- memoryObjectPresent=false: 18
- Category distribution: {"long_term_goal":1,"long_term_preference":1,"one_off_information":5,"small_talk":5,"stable_fact":2,"temporary_state":4}
- v1 classification: {"likely_expected_no_memory_object":16,"suspicious_missing_memory_object":2}
- temporary_state + one_off_information + small_talk: 14/18 (77.78%)

Fallbacks are therefore concentrated in the three short-lived/negative categories, although four negative controls also come from stable_fact, long_term_preference, and long_term_goal.

| sample | category | difficulty | human remember | human importance | AI worth | AI remember | AI importance | AI confidence | fallback class v1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---|
| p003 | stable_fact | hard | False | 0.3 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p005 | stable_fact | hard | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p008 | long_term_preference | hard | False | 0.5 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p015 | long_term_goal | hard | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p016 | temporary_state | easy | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p017 | temporary_state | medium | False | 0.3 | False | False | 0.5 | 0.5 | suspicious_missing_memory_object |
| p018 | temporary_state | hard | False | 0.3 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p020 | temporary_state | hard | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p021 | one_off_information | easy | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p022 | one_off_information | medium | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p023 | one_off_information | hard | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p024 | one_off_information | medium | False | 0.1 | False | False | 0.5 | 0.5 | suspicious_missing_memory_object |
| p025 | one_off_information | hard | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p026 | small_talk | easy | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p027 | small_talk | medium | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p028 | small_talk | hard | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p029 | small_talk | medium | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |
| p030 | small_talk | hard | False | 0.1 | False | False | 0.5 | 0.5 | likely_expected_no_memory_object |

## ShouldRemember and LTM errors

- ShouldRemember FP: p013, p019
- ShouldRemember FN: none
- LTM persistence FP: p019
- LTM persistence FN: none

p013 is a conditional, unconfirmed home-buying idea. The model emits shouldRemember=true at importance 0.35/confidence 0.4 but production does not attempt LTM persistence; it also creates a clearly incorrect 买房计划 entity.

p019 is a confirmed but explicitly six-week rehabilitation schedule. The model emits shouldRemember=true, importance 0.5, confidence 0.95 and persists LTM. Its KG entity/relation are correct, so the failure is the memory-duration decision, not KG extraction.

## Special samples

| sample | human remember | AI remember | LTM persisted | actual entities | actual relations | primary failure |
|---|---:|---:|---:|---:|---:|---|
| p017 | False | False | False | 0 | 0 | KG extraction |
| p019 | False | True | True | 1 | 1 | memory decision |
| p024 | False | False | False | 0 | 0 | KG extraction |

- p017: memory decision is correct (no LTM), but 项目评审/event is missing; KG extraction failure.
- p019: KG is correct, but a temporary six-week schedule is promoted into LTM; memory decision/threshold interaction failure.
- p024: memory decision is correct (no LTM), but 取快递/event and user-plans-event are missing; KG extraction failure.

## Supported conclusion

Exact matching materially underestimates KG performance, but matching is not the only bottleneck. Remaining errors show schema/type ambiguity, wrong predicates, over-splitting, generic related_to overuse, KG suppression on memory-negative turns, and fallback behavior. The Pilot does not yet support moving directly to 600 samples without first resolving the evaluation-policy question for LTM-negative/KG-positive turns and diagnosing the production extraction/fallback path.

## Frozen matching policy: Normalized Diagnostic v1

The diagnostic figures above are now governed by the sample-independent rules frozen in `MindPet-Eval/docs/kg-evaluation-matching-rules-v1.md`.

- Strict Exact Match remains the formal metric and is unchanged: Entity P/R/F1 = 0.294118/0.357143/0.322581; Relation P/R/F1 = 0.210526/0.333333/0.258065.
- Normalized Diagnostic v1 applies trim, Unicode NFKC, casefold, whitespace/common-punctuation normalization, exact type, and fixed lexical containment/similarity rules. It contains no `sample_id` conditions or manually curated Pilot aliases.
- Entity Normalized Diagnostic v1 = P/R/F1 0.588235/0.714286/0.645161 (TP/FP/FN 10/7/4), adding five diagnostic TPs.
- Relation Normalized Diagnostic v1 = P/R/F1 0.421053/0.666667/0.516129 (TP/FP/FN 8/11/4), adding four diagnostic TPs through endpoint normalization only.
- Predicate and direction remain exact. Type mismatch never becomes a TP. Plausible extra entities/relations remain FP.

Fallback v1 classification does not equate fallback with a defect:

- `likely_expected_no_memory_object`: 16
- `suspicious_missing_memory_object`: 2 (`p017`, `p024`)
- `clearly_problematic`: 0

The frozen boundary interpretation remains: p017 = memory correct/KG miss; p019 = KG correct/false LTM persistence; p024 = memory correct/KG miss.
