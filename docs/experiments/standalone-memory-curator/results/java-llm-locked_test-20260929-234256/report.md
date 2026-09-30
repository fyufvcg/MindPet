# Memory Curator LLM experiment

Dataset: `memory-curator-value-300-v2.jsonl` (SHA-256 `10d4ef9f681c94fdfdc7ca046563d0225a2196c910232b8aa55bf86a50b57ffe`)

This report is generated from real calls to the production Java KnowledgeGraphService, MemoryCuratorService, MemoryCorpusCompactionService, and SqliteMemoryService. Fixture output is not used as experiment data. Primary effects compare compacted G2C with G1; append-only G2A is an ablation built from the same accepted curator output.

```json
{
  "dataset_version" : "memory-curator-value-v2",
  "selected_timelines" : 1,
  "selected_turns" : 40,
  "selected_queries" : 10,
  "answer_queries" : 0,
  "answer_calls_expected" : 0,
  "fact_quality" : {
    "true_positive" : 6,
    "false_positive" : 8,
    "false_negative" : 2,
    "precision" : 0.42857142857142855,
    "recall" : 0.75,
    "f1" : 0.5454545454545454,
    "stored_fact_rows" : 14,
    "semantic_duplicate_rows" : 0,
    "duplicate_rate" : 0.0,
    "by_timeline" : [ {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-011",
      "expected" : 8,
      "stored" : 14,
      "true_positive" : 6,
      "duplicate_rows" : 0
    } ]
  },
  "profile_quality" : {
    "by_timeline" : [ {
      "expected_count" : 4,
      "timeline_id" : "timeline-011",
      "expected" : {
        "home_location" : "合肥",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "correct" : 3,
      "actual" : {
        "current_location" : "苏州",
        "current_project" : "校园服务平台",
        "occupation_current" : "交互设计师"
      }
    } ],
    "expected_entries" : 4,
    "written_entries" : 3,
    "precision" : 1.0,
    "recall" : 0.75,
    "expected_entries_correct" : 3,
    "fully_correct_timelines" : 0
  },
  "retention" : {
    "retention_at_1" : 1.0,
    "retention_at_3" : 1.0,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 8,
    "eligible_facts_at_3" : 8,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "actual_g1_retrieval_corpus" : {
      "estimated_tokens" : 1659,
      "unit_count" : 90,
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 21,
        "knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 37
      },
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 505,
        "knowledge_graph_relation" : 348,
        "ordinary_long_term_memory" : 806
      }
    },
    "actual_g2a_append_retrieval_corpus" : {
      "estimated_tokens" : 2471,
      "unit_count" : 115,
      "unit_count_by_memory_type" : {
        "curated_fact" : 14,
        "curated_insight" : 3,
        "current_profile" : 3,
        "growth" : 4,
        "knowledge_graph_entity" : 21,
        "knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 37,
        "working_memory" : 1
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 297,
        "curated_insight" : 169,
        "current_profile" : 44,
        "growth" : 244,
        "knowledge_graph_entity" : 505,
        "knowledge_graph_relation" : 348,
        "ordinary_long_term_memory" : 806,
        "working_memory" : 58
      }
    },
    "actual_g2c_compacted_retrieval_corpus" : {
      "estimated_tokens" : 1430,
      "unit_count" : 79,
      "unit_count_by_memory_type" : {
        "curated_fact" : 11,
        "curated_growth" : 4,
        "curated_insight" : 3,
        "curated_knowledge_graph_entity" : 21,
        "curated_knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 8
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 130,
        "curated_growth" : 161,
        "curated_insight" : 105,
        "curated_knowledge_graph_entity" : 505,
        "curated_knowledge_graph_relation" : 348,
        "ordinary_long_term_memory" : 181
      }
    },
    "actual_g2a_token_delta_vs_g1" : 0.48945147679324896,
    "actual_g2c_token_delta_vs_g1" : -0.13803496081977096,
    "actual_g2c_system_compression_rate" : 0.13803496081977096,
    "actual_g2c_token_delta_vs_g2a" : -0.4212869283690813,
    "g2c_compaction_action_counts" : {
      "KEEP" : 21,
      "MERGE" : 14,
      "RETIRE" : 1
    },
    "g2c_logged_net_token_reduction" : -323,
    "raw_turn_text_tokens_estimated" : 877,
    "curated_fact_projection_tokens_estimated" : 255,
    "curated_fact_projection_compression_estimate" : 0.7092360319270239,
    "raw_turn_text_characters" : 877,
    "curated_fact_projection_characters" : 560,
    "projection_note" : "G2A is the append-only ablation. G2C counts active, searchable, default-scope memory_retrieval_unit rows once; original long_term_memory rows remain stored but stop consuming serving tokens after compaction.",
    "estimator" : "CJK code point=1, ASCII alphanumeric runs=ceil(length/4), punctuation=1"
  },
  "temporal" : {
    "exact_time_expected" : 2,
    "exact_time_correct" : 2,
    "exact_time_accuracy" : 1.0,
    "ambiguous_time_expected" : 0,
    "ambiguous_time_correct" : 0,
    "ambiguous_time_accuracy" : null
  },
  "source_evidence" : {
    "stored_facts" : 14,
    "valid_source_turns" : 14,
    "source_completeness" : 1.0,
    "verbatim_evidence" : 14,
    "verbatim_evidence_rate" : 1.0,
    "active_retrieval_units" : 82,
    "retrieval_unit_source_traceability" : 82,
    "retrieval_unit_source_traceability_rate" : 1.0
  },
  "sensitive_content" : {
    "synthetic_sensitive_cases" : 0,
    "fully_blocked" : 0,
    "blocking_rate" : null,
    "by_timeline" : [ ],
    "scanned_tables" : [ "long_term_memory", "memory_fact", "user_profile_current", "user_insight", "llm_growth", "memory_retrieval_unit", "memory_retrieval_source", "kg_entity", "kg_relation", "kg_evidence" ]
  },
  "retrieval" : {
    "token_budget_method" : "estimated; see run-config.json",
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : 0.11111111111111116,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "cluster_count" : 1,
        "upper" : 0.11111111111111116,
        "lower" : 0.11111111111111116,
        "resamples" : 10000
      },
      "delta_recall_at_10_g2c_minus_g1" : 0.0,
      "delta_recall_at_10_user_cluster_bootstrap_95_ci" : {
        "cluster_count" : 1,
        "upper" : 0.0,
        "lower" : 0.0,
        "resamples" : 10000
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : 0.0,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "cluster_count" : 1,
        "upper" : 0.0,
        "lower" : 0.0,
        "resamples" : 10000
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_10_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.0,
      "primary_metrics" : [ "Delta Recall@5", "Delta Recall@10", "Delta recall at estimated 512 tokens" ]
    },
    "query_count" : 10,
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.7222222222222222,
        "recall_at_10" : 1.0,
        "mrr" : 0.6651234567901234,
        "ndcg_at_10" : 0.6933997593489304,
        "recall_at_256_estimated_tokens" : 1.0,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0
      },
      "G2A" : {
        "recall_at_5" : 0.7222222222222222,
        "recall_at_10" : 1.0,
        "mrr" : 0.6759259259259259,
        "ndcg_at_10" : 0.7027062396876365,
        "recall_at_256_estimated_tokens" : 1.0,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0
      },
      "G2C" : {
        "recall_at_5" : 0.8333333333333334,
        "recall_at_10" : 1.0,
        "mrr" : 0.5833333333333334,
        "ndcg_at_10" : 0.685999408068079,
        "recall_at_256_estimated_tokens" : 1.0,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0
      }
    }
  },
  "end_to_end" : {
    "answers" : 0,
    "paired" : {
      "primary_comparison" : "G2C minus G1",
      "g2c_minus_g1_accuracy" : null,
      "mcnemar_g1_only_correct" : 0,
      "mcnemar_g2_only_correct" : 0,
      "mcnemar_p_value_approx" : 1.0
    },
    "by_group" : {
      "G1" : {
        "correct" : 0,
        "total" : 0,
        "accuracy" : null,
        "abstention_correct" : 0,
        "abstention_total" : 0,
        "abstention_accuracy" : null,
        "mean_context_token_estimate" : 0,
        "mean_prompt_tokens_reported" : null,
        "mean_latency_ms" : 0
      },
      "G2C" : {
        "correct" : 0,
        "total" : 0,
        "accuracy" : null,
        "abstention_correct" : 0,
        "abstention_total" : 0,
        "abstention_accuracy" : null,
        "mean_context_token_estimate" : 0,
        "mean_prompt_tokens_reported" : null,
        "mean_latency_ms" : 0
      }
    }
  },
  "model_calls" : {
    "completion_tokens_reported" : 64328,
    "calls" : 44,
    "calls_with_token_usage" : 44,
    "total_tokens_reported" : 101705,
    "prompt_tokens_reported" : 37377,
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 40,
        "failures" : 0,
        "latency_ms" : {
          "p50_ms" : 2381,
          "p95_ms" : 5106,
          "count" : 40,
          "p99_ms" : 13530,
          "mean_ms" : 2919.975
        },
        "prompt_tokens_reported" : 18824,
        "completion_tokens_reported" : 24633,
        "total_tokens_reported" : 43457,
        "calls_with_token_usage" : 40
      },
      "curator" : {
        "calls" : 4,
        "failures" : 0,
        "latency_ms" : {
          "p50_ms" : 30207,
          "p95_ms" : 54975,
          "count" : 4,
          "p99_ms" : 54975,
          "mean_ms" : 38759.0
        },
        "prompt_tokens_reported" : 18553,
        "completion_tokens_reported" : 39695,
        "total_tokens_reported" : 58248,
        "calls_with_token_usage" : 4
      }
    }
  },
  "system_performance" : {
    "experiment_wall_ms" : 289813,
    "timeline_count" : 1,
    "baseline_ingestion_per_timeline_ms" : {
      "p50_ms" : 122740,
      "p95_ms" : 122740,
      "count" : 1,
      "p99_ms" : 122740,
      "mean_ms" : 122740.0
    },
    "curator_ingestion_per_timeline_ms" : {
      "p50_ms" : 158568,
      "p95_ms" : 158568,
      "count" : 1,
      "p99_ms" : 158568,
      "mean_ms" : 158568.0
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "p50_ms" : 1002,
      "p95_ms" : 1002,
      "count" : 1,
      "p99_ms" : 1002,
      "mean_ms" : 1002.0
    },
    "query_embedding_latency_ms" : {
      "p50_ms" : 63,
      "p95_ms" : 86,
      "count" : 10,
      "p99_ms" : 86,
      "mean_ms" : 64.2
    },
    "g1_production_retrieval_search_latency_ms" : {
      "p50_ms" : 5,
      "p95_ms" : 36,
      "count" : 10,
      "p99_ms" : 36,
      "mean_ms" : 8.6
    },
    "g2a_append_retrieval_search_latency_ms" : {
      "p50_ms" : 5,
      "p95_ms" : 19,
      "count" : 10,
      "p99_ms" : 19,
      "mean_ms" : 7.4
    },
    "g2c_compaction_retrieval_search_latency_ms" : {
      "p50_ms" : 14,
      "p95_ms" : 42,
      "count" : 10,
      "p99_ms" : 42,
      "mean_ms" : 17.3
    },
    "g1_indexed_corpus" : {
      "estimated_tokens" : 1659,
      "unit_count" : 90,
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 21,
        "knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 37
      },
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 505,
        "knowledge_graph_relation" : 348,
        "ordinary_long_term_memory" : 806
      }
    },
    "g2a_indexed_corpus" : {
      "estimated_tokens" : 2471,
      "unit_count" : 115,
      "unit_count_by_memory_type" : {
        "curated_fact" : 14,
        "curated_insight" : 3,
        "current_profile" : 3,
        "growth" : 4,
        "knowledge_graph_entity" : 21,
        "knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 37,
        "working_memory" : 1
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 297,
        "curated_insight" : 169,
        "current_profile" : 44,
        "growth" : 244,
        "knowledge_graph_entity" : 505,
        "knowledge_graph_relation" : 348,
        "ordinary_long_term_memory" : 806,
        "working_memory" : 58
      }
    },
    "g2c_indexed_corpus" : {
      "estimated_tokens" : 1430,
      "unit_count" : 79,
      "unit_count_by_memory_type" : {
        "curated_fact" : 11,
        "curated_growth" : 4,
        "curated_insight" : 3,
        "curated_knowledge_graph_entity" : 21,
        "curated_knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 8
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 130,
        "curated_growth" : 161,
        "curated_insight" : 105,
        "curated_knowledge_graph_entity" : 505,
        "curated_knowledge_graph_relation" : 348,
        "ordinary_long_term_memory" : 181
      }
    },
    "g1_sqlite_footprint_bytes" : {
      "main_db_bytes" : 737280,
      "total_observed_bytes" : 4910680,
      "wal_bytes" : 4140632,
      "shm_bytes" : 32768,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars."
    },
    "g2_sqlite_footprint_bytes" : {
      "main_db_bytes" : 2187264,
      "total_observed_bytes" : 6356544,
      "wal_bytes" : 4136512,
      "shm_bytes" : 32768,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars."
    },
    "llm_calls_by_stage" : {
      "completion_tokens_reported" : 64328,
      "calls" : 44,
      "calls_with_token_usage" : 44,
      "total_tokens_reported" : 101705,
      "prompt_tokens_reported" : 37377,
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 40,
          "failures" : 0,
          "latency_ms" : {
            "p50_ms" : 2381,
            "p95_ms" : 5106,
            "count" : 40,
            "p99_ms" : 13530,
            "mean_ms" : 2919.975
          },
          "prompt_tokens_reported" : 18824,
          "completion_tokens_reported" : 24633,
          "total_tokens_reported" : 43457,
          "calls_with_token_usage" : 40
        },
        "curator" : {
          "calls" : 4,
          "failures" : 0,
          "latency_ms" : {
            "p50_ms" : 30207,
            "p95_ms" : 54975,
            "count" : 4,
            "p99_ms" : 54975,
            "mean_ms" : 38759.0
          },
          "prompt_tokens_reported" : 18553,
          "completion_tokens_reported" : 39695,
          "total_tokens_reported" : 58248,
          "calls_with_token_usage" : 4
        }
      }
    },
    "baseline_turns_per_second_sum_timeline_time" : 0.32589212970506765
  },
  "failures" : {
    "by_reason" : { },
    "count" : 0
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory baseline. G2A and G2C reuse the same accepted real MemoryCuratorService output so the ablation isolates append-only versus compacted serving behavior.", "G1, G2A, and G2C all retain the same traceable KG entities and relations for retrieval; the G2C KG sidecar is unchanged by compaction and counted in its serving tokens.", "G1 and G2A use the production SqliteMemoryService hybrid retriever. G2C uses production memory_retrieval_unit ranking and consults the legacy long_term_memory retriever only when the compacted corpus returns no candidate.", "Retrieval reports Recall@5 and Recall@10; Recall@1 and Recall@3 are not reported.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run.", "Formal paired effects compare G2C with G1. G2A is reported as an append-only ablation." ]
}
```
