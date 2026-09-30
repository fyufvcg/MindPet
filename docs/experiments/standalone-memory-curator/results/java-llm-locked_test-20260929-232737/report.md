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
    "false_positive" : 6,
    "false_negative" : 2,
    "precision" : 0.5,
    "recall" : 0.75,
    "f1" : 0.6,
    "stored_fact_rows" : 12,
    "semantic_duplicate_rows" : 0,
    "duplicate_rate" : 0.0,
    "by_timeline" : [ {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-011",
      "expected" : 8,
      "stored" : 12,
      "true_positive" : 6,
      "duplicate_rows" : 0
    } ]
  },
  "profile_quality" : {
    "by_timeline" : [ {
      "actual" : {
        "current_location" : "苏州",
        "current_project" : "校园服务平台",
        "occupation_current" : "交互设计师"
      },
      "correct" : 3,
      "expected" : {
        "home_location" : "合肥",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "timeline_id" : "timeline-011",
      "expected_count" : 4
    } ],
    "fully_correct_timelines" : 0,
    "expected_entries_correct" : 3,
    "recall" : 0.75,
    "precision" : 1.0,
    "written_entries" : 3,
    "expected_entries" : 4
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
      "estimated_tokens" : 1763,
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 619,
        "knowledge_graph_relation" : 361,
        "ordinary_long_term_memory" : 783
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 36
      },
      "unit_count" : 92
    },
    "actual_g2a_append_retrieval_corpus" : {
      "estimated_tokens" : 2384,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 248,
        "curated_insight" : 141,
        "current_profile" : 44,
        "growth" : 130,
        "knowledge_graph_entity" : 619,
        "knowledge_graph_relation" : 361,
        "ordinary_long_term_memory" : 783,
        "working_memory" : 58
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 12,
        "curated_insight" : 2,
        "current_profile" : 3,
        "growth" : 2,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 36,
        "working_memory" : 1
      },
      "unit_count" : 112
    },
    "actual_g2c_compacted_retrieval_corpus" : {
      "estimated_tokens" : 462,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 111,
        "curated_growth" : 93,
        "curated_insight" : 100,
        "ordinary_long_term_memory" : 158
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 9,
        "curated_growth" : 2,
        "curated_insight" : 2,
        "ordinary_long_term_memory" : 7
      },
      "unit_count" : 20
    },
    "actual_g2a_token_delta_vs_g1" : 0.35224049914917754,
    "actual_g2c_token_delta_vs_g1" : -0.7379466817923993,
    "actual_g2c_system_compression_rate" : 0.7379466817923993,
    "actual_g2c_token_delta_vs_g2a" : -0.8062080536912751,
    "g2c_compaction_action_counts" : {
      "KEEP" : 16,
      "MERGE" : 11,
      "RETIRE" : 1
    },
    "g2c_logged_net_token_reduction" : -227,
    "raw_turn_text_tokens_estimated" : 877,
    "curated_fact_projection_tokens_estimated" : 212,
    "curated_fact_projection_compression_estimate" : 0.758266818700114,
    "raw_turn_text_characters" : 877,
    "curated_fact_projection_characters" : 472,
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
    "stored_facts" : 12,
    "valid_source_turns" : 12,
    "source_completeness" : 1.0,
    "verbatim_evidence" : 12,
    "verbatim_evidence_rate" : 1.0,
    "active_retrieval_units" : 23,
    "retrieval_unit_source_traceability" : 23,
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
    "by_group" : {
      "G1" : {
        "recall_at_1" : 0.6111111111111112,
        "recall_at_3" : 0.7222222222222222,
        "recall_at_5" : 0.7222222222222222,
        "recall_at_10" : 1.0,
        "mrr" : 0.7592592592592592,
        "ndcg_at_10" : 0.7526689901007672,
        "recall_at_256_estimated_tokens" : 1.0,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0
      },
      "G2A" : {
        "recall_at_1" : 0.6111111111111112,
        "recall_at_3" : 0.7222222222222222,
        "recall_at_5" : 0.7222222222222222,
        "recall_at_10" : 1.0,
        "mrr" : 0.7592592592592592,
        "ndcg_at_10" : 0.7526689901007672,
        "recall_at_256_estimated_tokens" : 1.0,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0
      },
      "G2C" : {
        "recall_at_1" : 0.3888888888888889,
        "recall_at_3" : 0.6666666666666666,
        "recall_at_5" : 0.7222222222222222,
        "recall_at_10" : 1.0,
        "mrr" : 0.6388888888888888,
        "ndcg_at_10" : 0.7055870874338038,
        "recall_at_256_estimated_tokens" : 1.0,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0
      }
    },
    "query_count" : 10,
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : 0.0,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : 0.0,
        "upper" : 0.0,
        "cluster_count" : 1
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : 0.0,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : 0.0,
        "upper" : 0.0,
        "cluster_count" : 1
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.0,
      "primary_metrics" : [ "Delta Recall@5", "Delta recall at estimated 512 tokens" ]
    }
  },
  "end_to_end" : {
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
    },
    "paired" : {
      "primary_comparison" : "G2C minus G1",
      "g2c_minus_g1_accuracy" : null,
      "mcnemar_g1_only_correct" : 0,
      "mcnemar_g2_only_correct" : 0,
      "mcnemar_p_value_approx" : 1.0
    },
    "answers" : 0
  },
  "model_calls" : {
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 40,
        "failures" : 0,
        "latency_ms" : {
          "p50_ms" : 1980,
          "mean_ms" : 2484.475,
          "p99_ms" : 7248,
          "count" : 40,
          "p95_ms" : 4990
        },
        "prompt_tokens_reported" : 18824,
        "completion_tokens_reported" : 21068,
        "total_tokens_reported" : 39892,
        "calls_with_token_usage" : 40
      },
      "curator" : {
        "calls" : 4,
        "failures" : 0,
        "latency_ms" : {
          "p50_ms" : 18727,
          "mean_ms" : 35732.0,
          "p99_ms" : 67377,
          "count" : 4,
          "p95_ms" : 67377
        },
        "prompt_tokens_reported" : 13053,
        "completion_tokens_reported" : 35574,
        "total_tokens_reported" : 48627,
        "calls_with_token_usage" : 4
      }
    },
    "prompt_tokens_reported" : 31877,
    "total_tokens_reported" : 88519,
    "calls_with_token_usage" : 44,
    "calls" : 44,
    "completion_tokens_reported" : 56642
  },
  "system_performance" : {
    "experiment_wall_ms" : 255436,
    "timeline_count" : 1,
    "baseline_ingestion_per_timeline_ms" : {
      "p50_ms" : 104178,
      "mean_ms" : 104178.0,
      "p99_ms" : 104178,
      "count" : 1,
      "p95_ms" : 104178
    },
    "curator_ingestion_per_timeline_ms" : {
      "p50_ms" : 145334,
      "mean_ms" : 145334.0,
      "p99_ms" : 145334,
      "count" : 1,
      "p95_ms" : 145334
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "p50_ms" : 717,
      "mean_ms" : 717.0,
      "p99_ms" : 717,
      "count" : 1,
      "p95_ms" : 717
    },
    "query_embedding_latency_ms" : {
      "p50_ms" : 49,
      "mean_ms" : 47.9,
      "p99_ms" : 61,
      "count" : 10,
      "p95_ms" : 61
    },
    "g1_production_retrieval_search_latency_ms" : {
      "p50_ms" : 3,
      "mean_ms" : 5.7,
      "p99_ms" : 24,
      "count" : 10,
      "p95_ms" : 24
    },
    "g2a_append_retrieval_search_latency_ms" : {
      "p50_ms" : 4,
      "mean_ms" : 4.8,
      "p99_ms" : 10,
      "count" : 10,
      "p95_ms" : 10
    },
    "g2c_compaction_retrieval_search_latency_ms" : {
      "p50_ms" : 8,
      "mean_ms" : 10.5,
      "p99_ms" : 23,
      "count" : 10,
      "p95_ms" : 23
    },
    "g1_indexed_corpus" : {
      "estimated_tokens" : 1763,
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 619,
        "knowledge_graph_relation" : 361,
        "ordinary_long_term_memory" : 783
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 36
      },
      "unit_count" : 92
    },
    "g2a_indexed_corpus" : {
      "estimated_tokens" : 2384,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 248,
        "curated_insight" : 141,
        "current_profile" : 44,
        "growth" : 130,
        "knowledge_graph_entity" : 619,
        "knowledge_graph_relation" : 361,
        "ordinary_long_term_memory" : 783,
        "working_memory" : 58
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 12,
        "curated_insight" : 2,
        "current_profile" : 3,
        "growth" : 2,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 32,
        "ordinary_long_term_memory" : 36,
        "working_memory" : 1
      },
      "unit_count" : 112
    },
    "g2c_indexed_corpus" : {
      "estimated_tokens" : 462,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 111,
        "curated_growth" : 93,
        "curated_insight" : 100,
        "ordinary_long_term_memory" : 158
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 9,
        "curated_growth" : 2,
        "curated_insight" : 2,
        "ordinary_long_term_memory" : 7
      },
      "unit_count" : 20
    },
    "g1_sqlite_footprint_bytes" : {
      "main_db_bytes" : 749568,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768,
      "wal_bytes" : 4144752,
      "total_observed_bytes" : 4927088
    },
    "g2_sqlite_footprint_bytes" : {
      "main_db_bytes" : 1974272,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768,
      "wal_bytes" : 4342512,
      "total_observed_bytes" : 6349552
    },
    "llm_calls_by_stage" : {
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 40,
          "failures" : 0,
          "latency_ms" : {
            "p50_ms" : 1980,
            "mean_ms" : 2484.475,
            "p99_ms" : 7248,
            "count" : 40,
            "p95_ms" : 4990
          },
          "prompt_tokens_reported" : 18824,
          "completion_tokens_reported" : 21068,
          "total_tokens_reported" : 39892,
          "calls_with_token_usage" : 40
        },
        "curator" : {
          "calls" : 4,
          "failures" : 0,
          "latency_ms" : {
            "p50_ms" : 18727,
            "mean_ms" : 35732.0,
            "p99_ms" : 67377,
            "count" : 4,
            "p95_ms" : 67377
          },
          "prompt_tokens_reported" : 13053,
          "completion_tokens_reported" : 35574,
          "total_tokens_reported" : 48627,
          "calls_with_token_usage" : 4
        }
      },
      "prompt_tokens_reported" : 31877,
      "total_tokens_reported" : 88519,
      "calls_with_token_usage" : 44,
      "calls" : 44,
      "completion_tokens_reported" : 56642
    },
    "baseline_turns_per_second_sum_timeline_time" : 0.38395822534508245
  },
  "failures" : {
    "count" : 0,
    "by_reason" : { }
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory baseline. G2A and G2C reuse the same accepted real MemoryCuratorService output so the ablation isolates append-only versus compacted serving behavior.", "G1 and G2A use the production SqliteMemoryService hybrid retriever. G2C uses production memory_retrieval_unit ranking and consults the legacy long_term_memory retriever only when the compacted corpus returns no candidate.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run.", "Formal paired effects compare G2C with G1. G2A is reported as an append-only ablation." ]
}
```
