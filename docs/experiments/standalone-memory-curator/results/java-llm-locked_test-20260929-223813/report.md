# Memory Curator LLM experiment

Dataset: `memory-curator-value-300.jsonl` (SHA-256 `074f57e199a7d271464246dd62e8208a00dcf6c3e069fe670357306b4be28b5f`)

This report is generated from real calls to the production Java KnowledgeGraphService, MemoryCuratorService, MemoryCorpusCompactionService, and SqliteMemoryService. Fixture output is not used as experiment data. Primary effects compare compacted G2C with G1; append-only G2A is an ablation built from the same accepted curator output.

```json
{
  "dataset_version" : "memory-curator-value-v1",
  "selected_timelines" : 1,
  "selected_turns" : 40,
  "selected_queries" : 10,
  "answer_queries" : 1,
  "answer_calls_expected" : 2,
  "fact_quality" : {
    "true_positive" : 3,
    "false_positive" : 5,
    "false_negative" : 5,
    "precision" : 0.375,
    "recall" : 0.375,
    "f1" : 0.375,
    "stored_fact_rows" : 8,
    "semantic_duplicate_rows" : 0,
    "duplicate_rate" : 0.0,
    "by_timeline" : [ {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-011",
      "expected" : 8,
      "stored" : 8,
      "true_positive" : 3,
      "duplicate_rows" : 0
    } ]
  },
  "profile_quality" : {
    "fully_correct_timelines" : 0,
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
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      }
    } ],
    "expected_entries" : 4,
    "written_entries" : 3,
    "precision" : 1.0,
    "recall" : 0.75,
    "expected_entries_correct" : 3
  },
  "retention" : {
    "retention_at_1" : 0.75,
    "retention_at_3" : 0.75,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 8,
    "eligible_facts_at_3" : 8,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "actual_g1_retrieval_corpus" : {
      "estimated_tokens" : 1689,
      "unit_count" : 89,
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 30,
        "ordinary_long_term_memory" : 35
      },
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 593,
        "knowledge_graph_relation" : 333,
        "ordinary_long_term_memory" : 763
      }
    },
    "actual_g2a_append_retrieval_corpus" : {
      "estimated_tokens" : 2143,
      "unit_count" : 104,
      "unit_count_by_memory_type" : {
        "curated_fact" : 8,
        "curated_insight" : 1,
        "current_profile" : 3,
        "growth" : 2,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 30,
        "ordinary_long_term_memory" : 35,
        "working_memory" : 1
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 170,
        "curated_insight" : 58,
        "current_profile" : 44,
        "growth" : 135,
        "knowledge_graph_entity" : 593,
        "knowledge_graph_relation" : 333,
        "ordinary_long_term_memory" : 763,
        "working_memory" : 47
      }
    },
    "actual_g2c_compacted_retrieval_corpus" : {
      "estimated_tokens" : 1649,
      "unit_count" : 88,
      "unit_count_by_memory_type" : {
        "curated_fact" : 5,
        "curated_growth" : 2,
        "curated_insight" : 1,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 30,
        "ordinary_long_term_memory" : 26
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 65,
        "curated_growth" : 93,
        "curated_insight" : 37,
        "knowledge_graph_entity" : 562,
        "knowledge_graph_relation" : 333,
        "ordinary_long_term_memory" : 559
      }
    },
    "actual_g2a_token_delta_vs_g1" : 0.2687981053878034,
    "actual_g2c_token_delta_vs_g1" : -0.023682652457075192,
    "actual_g2c_system_compression_rate" : 0.023682652457075237,
    "actual_g2c_token_delta_vs_g2a" : -0.23051796546896874,
    "g2c_compaction_action_counts" : {
      "KEEP" : 98,
      "RETIRE" : 7
    },
    "g2c_logged_net_token_reduction" : -1573,
    "raw_turn_text_tokens_estimated" : 877,
    "curated_fact_projection_tokens_estimated" : 146,
    "curated_fact_projection_compression_estimate" : 0.8335233751425314,
    "raw_turn_text_characters" : 877,
    "curated_fact_projection_characters" : 309,
    "projection_note" : "G2A is the append-only ablation. G2C counts active, searchable, default-scope memory_retrieval_unit rows once; original long_term_memory rows remain stored but stop consuming serving tokens after compaction.",
    "estimator" : "CJK code point=1, ASCII alphanumeric runs=ceil(length/4), punctuation=1"
  },
  "temporal" : {
    "exact_time_expected" : 0,
    "exact_time_correct" : 0,
    "exact_time_accuracy" : null,
    "ambiguous_time_expected" : 0,
    "ambiguous_time_correct" : 0,
    "ambiguous_time_accuracy" : null
  },
  "source_evidence" : {
    "stored_facts" : 8,
    "valid_source_turns" : 8,
    "source_completeness" : 1.0,
    "verbatim_evidence" : 8,
    "verbatim_evidence_rate" : 1.0,
    "active_retrieval_units" : 91,
    "retrieval_unit_source_traceability" : 20,
    "retrieval_unit_source_traceability_rate" : 0.21978021978021978
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
      "delta_recall_at_5_g2c_minus_g1" : -0.4444444444444445,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "cluster_count" : 1,
        "upper" : -0.4444444444444445,
        "lower" : -0.4444444444444445,
        "resamples" : 10000
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : -0.2777777777777777,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "cluster_count" : 1,
        "upper" : -0.2777777777777777,
        "lower" : -0.2777777777777777,
        "resamples" : 10000
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.0,
      "primary_metrics" : [ "Delta Recall@5", "Delta recall at estimated 512 tokens" ]
    },
    "query_count" : 10,
    "by_group" : {
      "G1" : {
        "recall_at_1" : 0.5555555555555556,
        "recall_at_3" : 0.7222222222222222,
        "recall_at_5" : 0.8333333333333334,
        "recall_at_10" : 0.8888888888888888,
        "mrr" : 0.6574074074074073,
        "ndcg_at_10" : 0.6705525604445721,
        "recall_at_256_estimated_tokens" : 0.8888888888888888,
        "recall_at_512_estimated_tokens" : 0.8888888888888888,
        "recall_at_1024_estimated_tokens" : 0.8888888888888888
      },
      "G2A" : {
        "recall_at_1" : 0.5555555555555556,
        "recall_at_3" : 0.8333333333333334,
        "recall_at_5" : 0.8333333333333334,
        "recall_at_10" : 0.8888888888888888,
        "mrr" : 0.6666666666666666,
        "ndcg_at_10" : 0.6782551651030839,
        "recall_at_256_estimated_tokens" : 0.8888888888888888,
        "recall_at_512_estimated_tokens" : 0.8888888888888888,
        "recall_at_1024_estimated_tokens" : 0.8888888888888888
      },
      "G2C" : {
        "recall_at_1" : 0.1111111111111111,
        "recall_at_3" : 0.3888888888888889,
        "recall_at_5" : 0.3888888888888889,
        "recall_at_10" : 0.6111111111111112,
        "mrr" : 0.29012345679012347,
        "ndcg_at_10" : 0.35277997150866697,
        "recall_at_256_estimated_tokens" : 0.6111111111111112,
        "recall_at_512_estimated_tokens" : 0.6111111111111112,
        "recall_at_1024_estimated_tokens" : 0.6111111111111112
      }
    }
  },
  "end_to_end" : {
    "answers" : 2,
    "paired" : {
      "primary_comparison" : "G2C minus G1",
      "g2c_minus_g1_accuracy" : 0.0,
      "mcnemar_g1_only_correct" : 0,
      "mcnemar_g2_only_correct" : 0,
      "mcnemar_p_value_approx" : 1.0
    },
    "by_group" : {
      "G1" : {
        "correct" : 1,
        "total" : 1,
        "accuracy" : 1.0,
        "abstention_correct" : 0,
        "abstention_total" : 0,
        "abstention_accuracy" : null,
        "mean_context_token_estimate" : 220,
        "mean_prompt_tokens_reported" : 270.0,
        "mean_latency_ms" : 747
      },
      "G2C" : {
        "correct" : 1,
        "total" : 1,
        "accuracy" : 1.0,
        "abstention_correct" : 0,
        "abstention_total" : 0,
        "abstention_accuracy" : null,
        "mean_context_token_estimate" : 179,
        "mean_prompt_tokens_reported" : 243.0,
        "mean_latency_ms" : 1400
      }
    }
  },
  "model_calls" : {
    "completion_tokens_reported" : 49441,
    "calls" : 47,
    "calls_with_token_usage" : 47,
    "total_tokens_reported" : 79604,
    "prompt_tokens_reported" : 30163,
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 40,
        "failures" : 0,
        "latency_ms" : {
          "p50_ms" : 2186,
          "p95_ms" : 5690,
          "count" : 40,
          "p99_ms" : 6467,
          "mean_ms" : 2588.025
        },
        "prompt_tokens_reported" : 18824,
        "completion_tokens_reported" : 21822,
        "total_tokens_reported" : 40646,
        "calls_with_token_usage" : 40
      },
      "curator" : {
        "calls" : 5,
        "failures" : 0,
        "latency_ms" : {
          "p50_ms" : 28166,
          "p95_ms" : 40303,
          "count" : 5,
          "p99_ms" : 40303,
          "mean_ms" : 23891.0
        },
        "prompt_tokens_reported" : 10826,
        "completion_tokens_reported" : 27464,
        "total_tokens_reported" : 38290,
        "calls_with_token_usage" : 5
      },
      "answer" : {
        "calls" : 2,
        "failures" : 0,
        "latency_ms" : {
          "p50_ms" : 747,
          "p95_ms" : 1400,
          "count" : 2,
          "p99_ms" : 1400,
          "mean_ms" : 1073.5
        },
        "prompt_tokens_reported" : 513,
        "completion_tokens_reported" : 155,
        "total_tokens_reported" : 668,
        "calls_with_token_usage" : 2
      }
    }
  },
  "system_performance" : {
    "experiment_wall_ms" : 239298,
    "timeline_count" : 1,
    "baseline_ingestion_per_timeline_ms" : {
      "p50_ms" : 108420,
      "p95_ms" : 108420,
      "count" : 1,
      "p99_ms" : 108420,
      "mean_ms" : 108420.0
    },
    "curator_ingestion_per_timeline_ms" : {
      "p50_ms" : 122515,
      "p95_ms" : 122515,
      "count" : 1,
      "p99_ms" : 122515,
      "mean_ms" : 122515.0
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "p50_ms" : 3194,
      "p95_ms" : 3194,
      "count" : 1,
      "p99_ms" : 3194,
      "mean_ms" : 3194.0
    },
    "query_embedding_latency_ms" : {
      "p50_ms" : 64,
      "p95_ms" : 89,
      "count" : 10,
      "p99_ms" : 89,
      "mean_ms" : 65.4
    },
    "g1_production_retrieval_search_latency_ms" : {
      "p50_ms" : 6,
      "p95_ms" : 42,
      "count" : 10,
      "p99_ms" : 42,
      "mean_ms" : 10.7
    },
    "g2a_append_retrieval_search_latency_ms" : {
      "p50_ms" : 6,
      "p95_ms" : 32,
      "count" : 10,
      "p99_ms" : 32,
      "mean_ms" : 9.0
    },
    "g2c_compaction_retrieval_search_latency_ms" : {
      "p50_ms" : 13,
      "p95_ms" : 44,
      "count" : 10,
      "p99_ms" : 44,
      "mean_ms" : 16.5
    },
    "g1_indexed_corpus" : {
      "estimated_tokens" : 1689,
      "unit_count" : 89,
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 30,
        "ordinary_long_term_memory" : 35
      },
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 593,
        "knowledge_graph_relation" : 333,
        "ordinary_long_term_memory" : 763
      }
    },
    "g2a_indexed_corpus" : {
      "estimated_tokens" : 2143,
      "unit_count" : 104,
      "unit_count_by_memory_type" : {
        "curated_fact" : 8,
        "curated_insight" : 1,
        "current_profile" : 3,
        "growth" : 2,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 30,
        "ordinary_long_term_memory" : 35,
        "working_memory" : 1
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 170,
        "curated_insight" : 58,
        "current_profile" : 44,
        "growth" : 135,
        "knowledge_graph_entity" : 593,
        "knowledge_graph_relation" : 333,
        "ordinary_long_term_memory" : 763,
        "working_memory" : 47
      }
    },
    "g2c_indexed_corpus" : {
      "estimated_tokens" : 1649,
      "unit_count" : 88,
      "unit_count_by_memory_type" : {
        "curated_fact" : 5,
        "curated_growth" : 2,
        "curated_insight" : 1,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 30,
        "ordinary_long_term_memory" : 26
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 65,
        "curated_growth" : 93,
        "curated_insight" : 37,
        "knowledge_graph_entity" : 562,
        "knowledge_graph_relation" : 333,
        "ordinary_long_term_memory" : 559
      }
    },
    "g1_sqlite_footprint_bytes" : {
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "main_db_bytes" : 757760,
      "total_observed_bytes" : 4931160,
      "wal_bytes" : 4140632,
      "shm_bytes" : 32768
    },
    "g2_sqlite_footprint_bytes" : {
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "main_db_bytes" : 1691648,
      "total_observed_bytes" : 5856808,
      "wal_bytes" : 4132392,
      "shm_bytes" : 32768
    },
    "llm_calls_by_stage" : {
      "completion_tokens_reported" : 49441,
      "calls" : 47,
      "calls_with_token_usage" : 47,
      "total_tokens_reported" : 79604,
      "prompt_tokens_reported" : 30163,
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 40,
          "failures" : 0,
          "latency_ms" : {
            "p50_ms" : 2186,
            "p95_ms" : 5690,
            "count" : 40,
            "p99_ms" : 6467,
            "mean_ms" : 2588.025
          },
          "prompt_tokens_reported" : 18824,
          "completion_tokens_reported" : 21822,
          "total_tokens_reported" : 40646,
          "calls_with_token_usage" : 40
        },
        "curator" : {
          "calls" : 5,
          "failures" : 0,
          "latency_ms" : {
            "p50_ms" : 28166,
            "p95_ms" : 40303,
            "count" : 5,
            "p99_ms" : 40303,
            "mean_ms" : 23891.0
          },
          "prompt_tokens_reported" : 10826,
          "completion_tokens_reported" : 27464,
          "total_tokens_reported" : 38290,
          "calls_with_token_usage" : 5
        },
        "answer" : {
          "calls" : 2,
          "failures" : 0,
          "latency_ms" : {
            "p50_ms" : 747,
            "p95_ms" : 1400,
            "count" : 2,
            "p99_ms" : 1400,
            "mean_ms" : 1073.5
          },
          "prompt_tokens_reported" : 513,
          "completion_tokens_reported" : 155,
          "total_tokens_reported" : 668,
          "calls_with_token_usage" : 2
        }
      }
    },
    "baseline_turns_per_second_sum_timeline_time" : 0.3689356207341819
  },
  "failures" : {
    "by_reason" : { },
    "count" : 0
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory baseline. G2A and G2C reuse the same accepted real MemoryCuratorService output so the ablation isolates append-only versus compacted serving behavior.", "G1 and G2A use the production SqliteMemoryService hybrid retriever. G2C uses production memory_retrieval_unit ranking and consults the legacy long_term_memory retriever only when the compacted corpus returns no candidate.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run.", "Formal paired effects compare G2C with G1. G2A is reported as an append-only ablation." ]
}
```
