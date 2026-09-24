# MindPet Deterministic Reference Run v1

## 1. Run contract

- Git commit: `a76c99a0b1d32c89d3cb4827a46d485a5fb1004b`
- Benchmark: Retrieval Benchmark v1; 120 memories; 40 queries (36 answerable, 4 no-answer).
- Database/user: `mindpet_eval` / `eval_test_user`.
- Embedding: `bge-m3`, 1024 dimensions.
- Fixed evaluationAsOf: `2026-09-21T08:38:06.750458`.
- All 360 formal requests used the same explicit evaluationAsOf. Evaluation retention and timeScore did not use wall-clock time.
- Each query/mode was requested exactly once at Top10; K=1/3/5/10 are slices of that one ranking.

## 2. Validation

- Raw rows / unique query-mode pairs: 360 / 360.
- HTTP 200 / status OK / same asOf: 360 / 360 / 360.
- State snapshots: 120 before / 120 after.
- access_count changes: 0; last_accessed changes: 0.
- RRF vs Norm Only complete Top10 sequence: 40/40 identical.
- No token, password, or API key is recorded in experiment outputs.

## 3. Overall metrics — 36 answerable queries

| mode | Precision@1 | Recall@10 | MRR@10 | nDCG@10 |
|---|---|---|---|---|
| keyword_only | 0.75 | 0.972222222222 | 0.847222222222 | 0.870542880652 |
| vector_only | 0.861111111111 | 1 | 0.915277777778 | 0.937836600759 |
| rrf | 0.888888888889 | 1 | 0.935185185185 | 0.947203926482 |
| mindpet_full | 0.222222222222 | 0.916666666667 | 0.423875661376 | 0.540367915782 |
| mindpet_full_rrf_norm | 0.833333333333 | 1 | 0.896296296296 | 0.912666819635 |
| mindpet_rrf_norm_only | 0.888888888889 | 1 | 0.935185185185 | 0.947203926482 |
| mindpet_rrf_norm_time | 0.888888888889 | 1 | 0.934027777778 | 0.945578235617 |
| mindpet_rrf_norm_importance | 0.833333333333 | 1 | 0.902116402116 | 0.919572326242 |
| mindpet_rrf_norm_importance_bonus | 0.833333333333 | 1 | 0.902116402116 | 0.919572326242 |

## 4. Pairwise first-relevant-rank effects

Top10 misses use rank 11 only for this error analysis; formal MRR remains unchanged.

| comparison | improved | unchanged | degraded | avg rank delta | MRR@10 change | Top10 sequences changed / 40 |
|---|---:|---:|---:|---:|---:|---:|
| mindpet_full → mindpet_full_rrf_norm | 25 | 10 | 1 | -2.694444 | +0.472421 | 40 |
| mindpet_rrf_norm_only → mindpet_rrf_norm_time | 1 | 33 | 2 | +0.055556 | -0.001157 | 40 |
| mindpet_rrf_norm_only → mindpet_rrf_norm_importance | 1 | 30 | 5 | +0.111111 | -0.033069 | 40 |
| mindpet_rrf_norm_importance → mindpet_rrf_norm_importance_bonus | 0 | 36 | 0 | +0.000000 | +0.000000 | 11 |
| mindpet_rrf_norm_only → mindpet_full_rrf_norm | 1 | 30 | 5 | +0.222222 | -0.038889 | 40 |
| rrf → mindpet_full_rrf_norm | 1 | 30 | 5 | +0.222222 | -0.038889 | 40 |

## 5. Query-type metrics

| query_type | mode | query_count | Precision@1 | Recall@10 | MRR@10 | nDCG@10 |
|---|---|---|---|---|---|---|
| exact_keyword | keyword_only | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | vector_only | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | rrf | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | mindpet_full | 8 | 0.25 | 1 | 0.427579365079 | 0.561964555643 |
| exact_keyword | mindpet_full_rrf_norm | 8 | 0.875 | 1 | 0.9375 | 0.953866219196 |
| exact_keyword | mindpet_rrf_norm_only | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | mindpet_rrf_norm_time | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | mindpet_rrf_norm_importance | 8 | 0.875 | 1 | 0.9375 | 0.953866219196 |
| exact_keyword | mindpet_rrf_norm_importance_bonus | 8 | 0.875 | 1 | 0.9375 | 0.953866219196 |
| semantic_paraphrase | keyword_only | 10 | 0.5 | 0.9 | 0.683333333333 | 0.739278926071 |
| semantic_paraphrase | vector_only | 10 | 0.7 | 1 | 0.82 | 0.864871231438 |
| semantic_paraphrase | rrf | 10 | 0.8 | 1 | 0.866666666667 | 0.898713694068 |
| semantic_paraphrase | mindpet_full | 10 | 0.3 | 0.9 | 0.469444444444 | 0.57059804351 |
| semantic_paraphrase | mindpet_full_rrf_norm | 10 | 0.7 | 1 | 0.81 | 0.855092433346 |
| semantic_paraphrase | mindpet_rrf_norm_only | 10 | 0.8 | 1 | 0.866666666667 | 0.898713694068 |
| semantic_paraphrase | mindpet_rrf_norm_time | 10 | 0.8 | 1 | 0.8625 | 0.894639463036 |
| semantic_paraphrase | mindpet_rrf_norm_importance | 10 | 0.7 | 1 | 0.814285714286 | 0.859519284048 |
| semantic_paraphrase | mindpet_rrf_norm_importance_bonus | 10 | 0.7 | 1 | 0.814285714286 | 0.859519284048 |
| hybrid | keyword_only | 8 | 0.75 | 1 | 0.833333333333 | 0.875 |
| hybrid | vector_only | 8 | 0.875 | 1 | 0.9375 | 0.953866219196 |
| hybrid | rrf | 8 | 0.75 | 1 | 0.875 | 0.907732438393 |
| hybrid | mindpet_full | 8 | 0.125 | 1 | 0.316666666667 | 0.476460194458 |
| hybrid | mindpet_full_rrf_norm | 8 | 0.875 | 1 | 0.916666666667 | 0.9375 |
| hybrid | mindpet_rrf_norm_only | 8 | 0.75 | 1 | 0.875 | 0.907732438393 |
| hybrid | mindpet_rrf_norm_time | 8 | 0.875 | 1 | 0.9375 | 0.953866219196 |
| hybrid | mindpet_rrf_norm_importance | 8 | 0.875 | 1 | 0.916666666667 | 0.9375 |
| hybrid | mindpet_rrf_norm_importance_bonus | 8 | 0.875 | 1 | 0.916666666667 | 0.9375 |
| multi_candidate | keyword_only | 6 | 0.666666666667 | 1 | 0.833333333333 | 0.824459073792 |
| multi_candidate | vector_only | 6 | 0.833333333333 | 1 | 0.875 | 0.913745926565 |
| multi_candidate | rrf | 6 | 1 | 1 | 1 | 0.975057484256 |
| multi_candidate | mindpet_full | 6 | 0 | 0.666666666667 | 0.310185185185 | 0.396376703432 |
| multi_candidate | mindpet_full_rrf_norm | 6 | 0.833333333333 | 1 | 0.888888888889 | 0.862358569971 |
| multi_candidate | mindpet_rrf_norm_only | 6 | 1 | 1 | 1 | 0.975057484256 |
| multi_candidate | mindpet_rrf_norm_time | 6 | 0.833333333333 | 1 | 0.916666666667 | 0.910582016383 |
| multi_candidate | mindpet_rrf_norm_importance | 6 | 0.833333333333 | 1 | 0.916666666667 | 0.896413525113 |
| multi_candidate | mindpet_rrf_norm_importance_bonus | 6 | 0.833333333333 | 1 | 0.916666666667 | 0.896413525113 |
| temporal_importance | keyword_only | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | vector_only | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | rrf | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | mindpet_full | 4 | 0.5 | 1 | 0.6875 | 0.765401577911 |
| temporal_importance | mindpet_full_rrf_norm | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | mindpet_rrf_norm_only | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | mindpet_rrf_norm_time | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | mindpet_rrf_norm_importance | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | mindpet_rrf_norm_importance_bonus | 4 | 1 | 1 | 1 | 1 |

## 6. No-answer diagnostics

The system has no formal reject threshold. These are returned-count diagnostics, not no-answer accuracy.

| mode | no_answer_query_count | empty_result_count@1 | empty_result_rate@1 | avg_returned_count@1 | empty_result_count@10 | empty_result_rate@10 | avg_returned_count@10 |
|---|---|---|---|---|---|---|---|
| keyword_only | 4 | 1 | 0.25 | 0.75 | 1 | 0.25 | 7.25 |
| vector_only | 4 | 0 | 0 | 1 | 0 | 0 | 10 |
| rrf | 4 | 0 | 0 | 1 | 0 | 0 | 10 |
| mindpet_full | 4 | 0 | 0 | 1 | 0 | 0 | 10 |
| mindpet_full_rrf_norm | 4 | 0 | 0 | 1 | 0 | 0 | 10 |
| mindpet_rrf_norm_only | 4 | 0 | 0 | 1 | 0 | 0 | 10 |
| mindpet_rrf_norm_time | 4 | 0 | 0 | 1 | 0 | 0 | 10 |
| mindpet_rrf_norm_importance | 4 | 0 | 0 | 1 | 0 | 0 | 10 |
| mindpet_rrf_norm_importance_bonus | 4 | 0 | 0 | 1 | 0 | 0 | 10 |

## 7. Interpretation boundaries

- H1 is evaluated by Full V1 → Full Norm V2 metrics and query-level ranks under the same fixed candidate clock.
- H2 is a reranking-level ablation. Importance still participates upstream in retention/layer behavior, so this is not a complete removal of importance.
- The time, direct-importance, bonus, and full-metadata effects must be read from their same-run pairwise comparisons; older wall-clock runs are not used for absolute metric comparison.
- No Java retrieval algorithm, score weight, benchmark item, ground truth, embedding, or database memory was changed during the formal experiment.

## 8. Limitations

- Single synthetic evaluation user and a 120-memory Chinese benchmark limit generalization.
- Binary relevance labels do not capture graded relevance or annotator agreement.
- Benchmark v1 was used during development, so this is reference evidence, not an untouched external test set.
- No-answer behavior cannot be judged as accuracy until a rejection threshold exists.
- This run evaluates retrieval/reranking only; it does not measure whether AI-generated importance labels are intrinsically accurate.
