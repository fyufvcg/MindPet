# H2 Metadata Reranking Ablation

## Scope and validation

- Git commit: `331a379cd788f2e58e61444f7068f3c29cc9badc`; database: `mindpet_eval`; user: `eval_test_user`.
- Benchmark: Retrieval Benchmark v1, 120 memories, 40 queries, bge-m3 1024 dims.
- Formal requests: 240; HTTP 200/status OK: 240/240; answerable: 36; no-answer: 4.
- Before/after: 120/120; access_count changes: 0; last_accessed changes: 0.
- Sanity: all 40 RRF and normalized-only benchmark-ID ranking sequences match exactly.
- This is a reranking-level ablation; importance remains in upstream retention, layer, and candidate selection.

## Overall ranking metrics (36 answerable queries)

| mode | Precision@1 | Recall@10 | MRR@10 | nDCG@10 |
|---|---|---|---|---|
| rrf | 0.583333333333 | 0.597222222222 | 0.597222222222 | 0.590113248509 |
| mindpet_rrf_norm_only | 0.583333333333 | 0.597222222222 | 0.597222222222 | 0.590113248509 |
| mindpet_rrf_norm_time | 0.555555555556 | 0.597222222222 | 0.578703703704 | 0.575730399598 |
| mindpet_rrf_norm_importance | 0.527777777778 | 0.597222222222 | 0.564814814815 | 0.567214466365 |
| mindpet_rrf_norm_importance_bonus | 0.527777777778 | 0.597222222222 | 0.564814814815 | 0.567214466365 |
| mindpet_full_rrf_norm | 0.527777777778 | 0.597222222222 | 0.564814814815 | 0.567214466365 |

## Pairwise effects

| comparison | improved | unchanged | degraded | avg rank delta | ΔP@1 | ΔMRR@10 | ΔnDCG@10 | full Top10 sequences changed / 40 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rrf → mindpet_rrf_norm_only | 0 | 36 | 0 | +0.000000 | +0.000000 | +0.000000 | +0.000000 | 0 |
| mindpet_rrf_norm_only → mindpet_rrf_norm_time | 0 | 34 | 2 | +0.055556 | -0.027778 | -0.018519 | -0.014383 | 40 |
| mindpet_rrf_norm_only → mindpet_rrf_norm_importance | 0 | 33 | 3 | +0.083333 | -0.055556 | -0.032407 | -0.022899 | 40 |
| mindpet_rrf_norm_importance → mindpet_rrf_norm_importance_bonus | 0 | 36 | 0 | +0.000000 | +0.000000 | +0.000000 | +0.000000 | 15 |
| mindpet_rrf_norm_only → mindpet_full_rrf_norm | 0 | 33 | 3 | +0.083333 | -0.055556 | -0.032407 | -0.022899 | 40 |

## Query-type metrics

| query_type | mode | query_count | Precision@1 | MRR@10 | nDCG@10 |
|---|---|---|---|---|---|
| exact_keyword | rrf | 8 | 0.625 | 0.625 | 0.625 |
| exact_keyword | mindpet_rrf_norm_only | 8 | 0.625 | 0.625 | 0.625 |
| exact_keyword | mindpet_rrf_norm_time | 8 | 0.625 | 0.625 | 0.625 |
| exact_keyword | mindpet_rrf_norm_importance | 8 | 0.625 | 0.625 | 0.625 |
| exact_keyword | mindpet_rrf_norm_importance_bonus | 8 | 0.625 | 0.625 | 0.625 |
| exact_keyword | mindpet_full_rrf_norm | 8 | 0.625 | 0.625 | 0.625 |
| semantic_paraphrase | rrf | 10 | 0.5 | 0.5 | 0.5 |
| semantic_paraphrase | mindpet_rrf_norm_only | 10 | 0.5 | 0.5 | 0.5 |
| semantic_paraphrase | mindpet_rrf_norm_time | 10 | 0.5 | 0.5 | 0.5 |
| semantic_paraphrase | mindpet_rrf_norm_importance | 10 | 0.5 | 0.5 | 0.5 |
| semantic_paraphrase | mindpet_rrf_norm_importance_bonus | 10 | 0.5 | 0.5 | 0.5 |
| semantic_paraphrase | mindpet_full_rrf_norm | 10 | 0.5 | 0.5 | 0.5 |
| hybrid | rrf | 8 | 0.25 | 0.3125 | 0.328866219196 |
| hybrid | mindpet_rrf_norm_only | 8 | 0.25 | 0.3125 | 0.328866219196 |
| hybrid | mindpet_rrf_norm_time | 8 | 0.25 | 0.291666666667 | 0.3125 |
| hybrid | mindpet_rrf_norm_importance | 8 | 0.25 | 0.291666666667 | 0.3125 |
| hybrid | mindpet_rrf_norm_importance_bonus | 8 | 0.25 | 0.291666666667 | 0.3125 |
| hybrid | mindpet_full_rrf_norm | 8 | 0.25 | 0.291666666667 | 0.3125 |
| multi_candidate | rrf | 6 | 0.833333333333 | 0.833333333333 | 0.768857865461 |
| multi_candidate | mindpet_rrf_norm_only | 6 | 0.833333333333 | 0.833333333333 | 0.768857865461 |
| multi_candidate | mindpet_rrf_norm_time | 6 | 0.666666666667 | 0.75 | 0.704382397588 |
| multi_candidate | mindpet_rrf_norm_importance | 6 | 0.5 | 0.666666666667 | 0.653286798191 |
| multi_candidate | mindpet_rrf_norm_importance_bonus | 6 | 0.5 | 0.666666666667 | 0.653286798191 |
| multi_candidate | mindpet_full_rrf_norm | 6 | 0.5 | 0.666666666667 | 0.653286798191 |
| temporal_importance | rrf | 4 | 1 | 1 | 1 |
| temporal_importance | mindpet_rrf_norm_only | 4 | 1 | 1 | 1 |
| temporal_importance | mindpet_rrf_norm_time | 4 | 1 | 1 | 1 |
| temporal_importance | mindpet_rrf_norm_importance | 4 | 1 | 1 | 1 |
| temporal_importance | mindpet_rrf_norm_importance_bonus | 4 | 1 | 1 | 1 |
| temporal_importance | mindpet_full_rrf_norm | 4 | 1 | 1 | 1 |

## No-answer diagnostics

No reject threshold exists; returned-count/empty-result statistics are diagnostic, not no-answer accuracy.

| mode | no_answer_query_count | empty_result_count@10 | empty_result_rate@10 | avg_returned_count@10 |
|---|---|---|---|---|
| rrf | 4 | 0 | 0 | 10 |
| mindpet_rrf_norm_only | 4 | 0 | 0 | 10 |
| mindpet_rrf_norm_time | 4 | 0 | 0 | 10 |
| mindpet_rrf_norm_importance | 4 | 0 | 0 | 10 |
| mindpet_rrf_norm_importance_bonus | 4 | 0 | 0 | 10 |
| mindpet_full_rrf_norm | 4 | 0 | 0 | 10 |

## Contribution-level examples

Each row shows a real result's debug components. Values not in a mode's final formula are observations, not contributions to that score.

| comparison | query_id | classification | role | benchmark_memory_id | rank | rrfNormalized | timeScore | importanceContribution | highImportanceBonus | confidenceContribution | finalScore |
|---|---|---|---|---|---|---|---|---|---|---|---|
| norm_only_vs_time | q025 | degraded | correct | m094 | 3 | 0.9841269841269842 | 0.6751911862346421 | 0.17200000000000001 | 0.05 | 0.0495 | 0.6271017293104205 |
| norm_only_vs_time | q025 | degraded | wrong_above | m093 | 2 | 0.9760624679979518 | 0.6978845869444864 | 0.18000000000000002 | 0.05 | 0.05 | 0.6276081513878732 |
| norm_only_vs_time | q030 | degraded | correct | m082 | 2 | 1.0 | 0.5537138901438793 | 0.13 | 0.05 | 0.0485 | 0.6107427780287759 |
| norm_only_vs_time | q030 | degraded | wrong_above | m094 | 1 | 0.961166253101737 | 0.6751907320772707 | 0.17200000000000001 | 0.05 | 0.0495 | 0.6156212729663226 |
| norm_only_vs_importance | q025 | degraded | correct | m094 | 3 | 0.9841269841269842 | 0.6751874475830527 | 0.17200000000000001 | 0.05 | 0.0495 | 0.6640634920634921 |
| norm_only_vs_importance | q025 | degraded | wrong_above | m093 | 2 | 0.9760624679979518 | 0.6978807226355714 | 0.18000000000000002 | 0.05 | 0.05 | 0.6680312339989759 |
| norm_only_vs_importance | q029 | degraded | correct | m074 | 2 | 1.0 | 0.6751870585288734 | 0.16000000000000003 | 0.05 | 0.0495 | 0.66 |
| norm_only_vs_importance | q029 | degraded | wrong_above | m001 | 1 | 0.9606894841269841 | 0.6751870585288734 | 0.18000000000000002 | 0.05 | 0.05 | 0.660344742063492 |
| norm_only_vs_full_v2 | q025 | degraded | correct | m094 | 3 | 0.9841269841269842 | 0.6751797905420102 | 0.17200000000000001 | 0.05 | 0.0495 | 0.8985994501718942 |
| norm_only_vs_full_v2 | q025 | degraded | wrong_above | m093 | 2 | 0.9760624679979518 | 0.6978728082388285 | 0.18000000000000002 | 0.05 | 0.05 | 0.9076057956467417 |
| norm_only_vs_full_v2 | q029 | degraded | correct | m074 | 2 | 1.0 | 0.6751794154422307 | 0.16000000000000003 | 0.05 | 0.0495 | 0.8945358830884462 |
| norm_only_vs_full_v2 | q029 | degraded | wrong_above | m001 | 1 | 0.9606894841269841 | 0.6751794154422307 | 0.18000000000000002 | 0.05 | 0.05 | 0.8953806251519383 |

## Cross-run baseline drift

- H1 RRF MRR@10: 0.921296; H2 RRF MRR@10: 0.597222.
- RRF Top10 ranking sequence changed in 39/40 queries between H1 and H2.
- The two runs were about 21.6 hours apart. A read-only replay of the production SQL retention expression at both manifest start times (PostgreSQL session `Asia/Shanghai`) found 94/120 eligible memories at H1 time versus 74/120 at H2 time. Twenty benchmark memories crossed below the unchanged `0.1` threshold; their ground-truth IDs occur in 15 answerable queries. For example, `m049` (q005) fell from retention score above the threshold to `0.0905` at H2 time. This is consistent with the observed candidate loss, without establishing that time drift was its only cause.
- The before/after H2 snapshots and H1/H2 snapshot business fields are identical for all 120 IDs (`access_count` and `last_accessed`); the moving `NOW()` input, not a benchmark rewrite, changes retention eligibility.
- H1 and H2 are not directly comparable as a fixed candidate-pool experiment: the existing retention SQL uses NOW(), while benchmark timestamps are fixed.
- Interpret only the six same-window H2 comparisons as metadata reranking effects; do not attribute the cross-run RRF drop to metadata.

## Interpretation and limitations

- mindpet_rrf_norm_only → mindpet_rrf_norm_time: net negative on MRR@10 (-0.018519); improved=0, degraded=2.
- mindpet_rrf_norm_only → mindpet_rrf_norm_importance: net negative on MRR@10 (-0.032407); improved=0, degraded=3.
- mindpet_rrf_norm_importance → mindpet_rrf_norm_importance_bonus: no net change on MRR@10 (+0.000000); improved=0, degraded=0.
- mindpet_rrf_norm_only → mindpet_full_rrf_norm: net negative on MRR@10 (-0.032407); improved=0, degraded=3.
- Time example: on q030, relevant `m082` had stronger normalized retrieval (`1.000000` vs wrong `m094` at `0.961166`), but the wrong memory's higher `timeScore` (`0.675191` vs `0.553714`) reversed their final ordering; relevant rank moved 1 → 2.
- Direct-importance example: on q029, relevant `m074` had normalized retrieval `1.000000`, versus wrong `m001` at `0.960689`; `importanceContribution` was `0.160000` versus `0.180000`, making the wrong candidate's final score `0.660345` versus `0.660000` and moving relevant rank 1 → 2.
- The bonus changed full Top10 sequences in 15/40 queries, but changed no first relevant rank among the 36 answerable queries. For example, in q001, irrelevant m065/m025 rose above irrelevant m115; relevant m001 stayed rank 1. Thus this run shows no relevance gain or loss from the bonus, not no ranking effect.
- No time, direct-importance, or bonus improvement of first relevant rank was observed in this run; do not invent an improvement case.
- These within-benchmark comparisons do not isolate upstream importance effects or establish AI-generated importance accuracy.
- The direct-importance harm motivates a separate reliability study, but these labels do not measure whether AI assigned importance accurately; that hypothesis remains untested.
- Benchmark v1 was used during development and error discovery, so this is not independent generalization evidence.
- No Java retrieval algorithm, benchmark, database memory, or score weight was changed during the formal run.
