# Knowledge Graph Evaluation Matching Rules v1

## 1. Scope and metric status

This document freezes the two-level Knowledge Graph evaluation policy used for the completed 30-sample E2E Memory Pilot.

- **Formal metric:** Strict Exact Match. Its values, names, and files are immutable.
- **Diagnostic metric:** Normalized Diagnostic v1. It explains lexical representation differences and must never replace or overwrite the formal metric.
- Both levels use one-to-one maximum matching. One expected item cannot match multiple actual items, and one actual item cannot match multiple expected items.
- These rules are global. They do not inspect `sample_id` and contain no benchmark-answer special cases.

The permanently retained formal results are:

| Target | Precision | Recall | F1 | TP / FP / FN |
|---|---:|---:|---:|---:|
| Entity, Strict Exact Match | 0.294118 | 0.357143 | 0.322581 | 5 / 12 / 9 |
| Relation, Strict Exact Match | 0.210526 | 0.333333 | 0.258065 | 4 / 15 / 8 |

## 2. Entity-name canonicalization

Normalized Diagnostic v1 applies the following pipeline in this exact order:

1. Remove leading and trailing whitespace (`trim`).
2. Apply Unicode NFKC normalization. This unifies compatible Unicode forms and full-width/half-width variants.
3. Apply Unicode `casefold`; therefore Latin-letter case differences do not matter.
4. Remove all Unicode whitespace inside the name.
5. Remove only this frozen set of common presentation punctuation:

   ```text
   ， , 。 ; ； : ： 、 ! ? ！ ？ " ' “ ” ‘ ’ ( ) （ ） [ ] 【 】 { } < > 《 》
   ```

6. Preserve technical symbols not listed above, including `+`, `#`, `.`, `-`, `_`, and `/`.
7. Do not perform Simplified/Traditional Chinese conversion, word segmentation, stemming, translation, or LLM/embedding similarity.

After canonicalization, two names are a Normalized Diagnostic v1 name match when one of these public rules succeeds:

1. Canonical names are identical; or
2. both canonical names have at least four characters and one is a substring of the other; or
3. both canonical names have at least four characters and Python `SequenceMatcher(..., autojunk=False).ratio()` is at least `0.50`.

The containment and similarity rules are deliberately diagnostic and permissive. They expose how much the formal score is affected by lexical representation, but they do not assert ontological identity and cannot change the formal score.

## 3. Alias and synonym policy

- v1 has **no manually curated alias table**.
- No alias may be added because it helps a particular Pilot sample.
- Free-form semantic plausibility, reviewer intuition, LLM judgment, and embedding similarity are not accepted as matches.
- A future alias registry must be versioned separately, documented before evaluation, applied globally, and must produce a new evaluation-rule version.

Thus v1 can recognize surface-level lexical overlap through its fixed string rules, but it does not automatically treat arbitrary synonyms as equivalent.

## 4. Entity matching

### 4.1 Strict Exact Match

An Entity TP requires:

- exact normalized stored name under the existing strict evaluator; and
- exact production entity `type`.

This is category A: **exact name + exact type**.

### 4.2 Normalized Diagnostic v1

An Entity diagnostic TP requires:

- the v1 name rule in section 2; and
- exact production entity `type`.

This is category B: **normalized name + exact type**.

### 4.3 Non-matches

- A plausible name with a different entity type is category C (`wrong_type`) and is never a TP.
- An extracted entity that is reasonable but absent from Ground Truth is category D (`plausible_but_not_in_ground_truth`) and remains an FP.
- Over-split and over-merged extra nodes are not silently merged. One-to-one matching prevents a single Ground Truth entity from absorbing several actual nodes.

## 5. Relation matching

A relation is represented as `(source, predicate, target)`.

### 5.1 Strict Exact Match

All three components must match exactly under the existing strict evaluator. Direction is significant.

### 5.2 Normalized Diagnostic v1

A Relation diagnostic TP requires all of the following:

1. Source endpoints match.
2. Predicate strings are identical after trim/NFKC/casefold.
3. Target endpoints match.
4. Direction is unchanged.

Entity endpoints use the Entity Normalized Diagnostic v1 rule and therefore require both a v1 name match and exact entity type.

The reserved endpoint `user` matches only the reserved endpoint `user` after trim/NFKC/casefold. It never matches a generated entity named “user” or “用户”.

Different predicates are not treated as equivalent. v1 defines no predicate-equivalence map. For example, `works_on` cannot match `experienced`, and `related_to` cannot match a more specific predicate. A reversed edge is recorded as `reversed_direction`, not as a TP.

## 6. Offline Pilot recalculation

Only the confirmed 30-row Ground Truth and the archived 30-row formal Pilot output were used. No API request or model inference was performed.

| Target | Rule | Precision | Recall | F1 | TP / FP / FN |
|---|---|---:|---:|---:|---:|
| Entity | Strict | 0.294118 | 0.357143 | 0.322581 | 5 / 12 / 9 |
| Entity | Normalized Diagnostic v1 | 0.588235 | 0.714286 | 0.645161 | 10 / 7 / 4 |
| Relation | Strict | 0.210526 | 0.333333 | 0.258065 | 4 / 15 / 8 |
| Relation | Normalized Diagnostic v1 | 0.421053 | 0.666667 | 0.516129 | 8 / 11 / 4 |

Normalized Diagnostic v1 adds five Entity TPs:

- one shared-core lexical variant (`后端工程` / `后端开发`);
- one longer same-type lexical paraphrase accepted by the fixed similarity threshold;
- two same-type descriptor expansions accepted by containment;
- one compound project name accepted by containment.

| Sample | Expected | Actual | v1 reason |
|---|---|---|---|
| p001 | 后端工程 / topic | 后端开发 / topic | fixed lexical-similarity threshold |
| p007 | 完整示例优先的技术阅读方式 / preference | 示例优先的技术文档阅读习惯 / preference | fixed lexical-similarity threshold |
| p009 | 深色主题 / preference | 深色主题偏好 / preference | canonical containment |
| p012 | MindPet Agent 和记忆评测 / project | MindPet / project | canonical containment |
| p014 | 全程马拉松 / goal | 全程马拉松目标 / goal | canonical containment |

It adds four Relation TPs. In all four, predicate and direction were already identical; only the entity endpoint changed from a strict non-match to a v1 normalized match. The relation with `works_on` versus `experienced` remains an error.

| Sample | Predicate | Added-match reason |
|---|---|---|
| p007 | prefers | target endpoint normalized by the fixed lexical rule |
| p009 | prefers | target endpoint normalized by containment |
| p012 | works_on | target endpoint normalized by containment |
| p014 | plans | target endpoint normalized by containment |

## 7. Fallback classification v1

`memoryObjectPresent=false` is not automatically a bug. The following deterministic classification uses only the confirmed human memory decision and expected KG structure:

- **likely_expected_no_memory_object:** `human_should_remember=false` and expected entities/relations are both empty.
- **suspicious_missing_memory_object:** `human_should_remember=false`, but expected entities or relations are non-empty. Missing an LTM memory object can be valid, while loss of independently expected KG structure needs investigation.
- **clearly_problematic:** `human_should_remember=true` but the memory object is missing.

Applied to the 18 fallback cases:

| Class | Count | Sample IDs |
|---|---:|---|
| likely_expected_no_memory_object | 16 | p003, p005, p008, p015, p016, p018, p020, p021, p022, p023, p025, p026, p027, p028, p029, p030 |
| suspicious_missing_memory_object | 2 | p017, p024 |
| clearly_problematic | 0 | — |

This classification concerns the missing memory object. It does not erase separate KG extraction errors.

## 8. Frozen boundary cases

- `p017`: LTM-negative / KG-positive. Memory decision is correct; expected `项目评审/event` is missing.
- `p019`: LTM-negative / KG-positive. KG is correct; temporary rehabilitation scheduling is incorrectly persisted as LTM.
- `p024`: LTM-negative / KG-positive. Memory decision is correct; expected `取快递/event` and `user plans event` are missing.

These samples remain fixed regression cases for separating memory decisions from KG extraction.

## 9. Interpretation and versioning

- Strict Exact Match remains the only formal Pilot metric.
- Normalized Diagnostic v1 quantifies matching sensitivity and is always reported with its version.
- A rule, threshold, alias registry, type policy, endpoint policy, or predicate-equivalence change requires a new version and a full offline recomputation. Historical Strict and v1 outputs must remain available.
- v1 does not justify changing Ground Truth, production Prompt, parser, fallback, threshold, schema, or persistence logic.
