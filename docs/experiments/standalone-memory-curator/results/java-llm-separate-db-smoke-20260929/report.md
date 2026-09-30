# Memory Curator LLM experiment

Dataset: `memory-curator-value-300.jsonl` (SHA-256 `074f57e199a7d271464246dd62e8208a00dcf6c3e069fe670357306b4be28b5f`)

This report is generated from real calls to the production Java KnowledgeGraphService, MemoryCuratorService, and SqliteMemoryService. Fixture output is not used as experiment data.

```json
{
  "dataset_version" : "memory-curator-value-v1",
  "selected_timelines" : 1,
  "selected_turns" : 40,
  "selected_queries" : 10,
  "answer_queries" : 10,
  "answer_calls_expected" : 20,
  "fact_quality" : {
    "true_positive" : 5,
    "false_positive" : 5,
    "false_negative" : 3,
    "precision" : 0.5,
    "recall" : 0.625,
    "f1" : 0.5555555555555556,
    "stored_fact_rows" : 10,
    "semantic_duplicate_rows" : 0,
    "duplicate_rate" : 0.0,
    "by_timeline" : [ {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-011",
      "expected" : 8,
      "stored" : 10,
      "true_positive" : 5,
      "duplicate_rows" : 0
    } ]
  },
  "profile_quality" : {
    "precision" : 1.0,
    "recall" : 0.75,
    "expected_entries_correct" : 3,
    "fully_correct_timelines" : 0,
    "by_timeline" : [ {
      "correct" : 3,
      "actual" : {
        "current_location" : "苏州",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "expected_count" : 4,
      "timeline_id" : "timeline-011",
      "expected" : {
        "home_location" : "合肥",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      }
    } ],
    "expected_entries" : 4,
    "written_entries" : 3
  },
  "retention" : {
    "retention_at_1" : 0.75,
    "retention_at_3" : 0.875,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 8,
    "eligible_facts_at_3" : 8,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "actual_g1_retrieval_corpus" : {
      "estimated_tokens" : 1669,
      "unit_count" : 87,
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 21,
        "knowledge_graph_relation" : 29,
        "ordinary_long_term_memory" : 37
      },
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 531,
        "knowledge_graph_relation" : 332,
        "ordinary_long_term_memory" : 806
      }
    },
    "actual_g2_retrieval_corpus" : {
      "estimated_tokens" : 2331,
      "unit_count" : 107,
      "unit_count_by_memory_type" : {
        "curated_fact" : 10,
        "curated_insight" : 3,
        "current_profile" : 3,
        "growth" : 3,
        "knowledge_graph_entity" : 21,
        "knowledge_graph_relation" : 29,
        "ordinary_long_term_memory" : 37,
        "working_memory" : 1
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 201,
        "curated_insight" : 216,
        "current_profile" : 44,
        "growth" : 154,
        "knowledge_graph_entity" : 531,
        "knowledge_graph_relation" : 332,
        "ordinary_long_term_memory" : 806,
        "working_memory" : 47
      }
    },
    "actual_g2_token_delta_vs_g1" : 0.396644697423607,
    "actual_system_compression_rate" : -0.39664469742360686,
    "raw_turn_text_tokens_estimated" : 877,
    "curated_fact_projection_tokens_estimated" : 171,
    "curated_fact_projection_compression_estimate" : 0.8050171037628278,
    "raw_turn_text_characters" : 877,
    "curated_fact_projection_characters" : 370,
    "projection_note" : "The fact-only projection is not the full G2 system corpus; G2 also retains copied G1 units and searchable profile, insight, growth, and working-memory units.",
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
    "verbatim_evidence_rate" : 1.0,
    "stored_facts" : 10,
    "source_completeness" : 1.0,
    "valid_source_turns" : 10,
    "verbatim_evidence" : 10
  },
  "sensitive_content" : {
    "synthetic_sensitive_cases" : 0,
    "fully_blocked" : 0,
    "blocking_rate" : null,
    "by_timeline" : [ ],
    "scanned_tables" : [ "long_term_memory", "memory_fact", "user_profile_current", "user_insight", "llm_growth", "kg_entity", "kg_relation", "kg_evidence" ]
  },
  "retrieval" : {
    "query_count" : 10,
    "by_group" : {
      "G1" : {
        "recall_at_1" : 0.5555555555555556,
        "recall_at_3" : 0.6111111111111112,
        "recall_at_5" : 0.8333333333333334,
        "recall_at_10" : 0.8888888888888888,
        "mrr" : 0.6611111111111111,
        "ndcg_at_10" : 0.6706596446806777,
        "recall_at_256_estimated_tokens" : 0.8888888888888888,
        "recall_at_512_estimated_tokens" : 0.8888888888888888,
        "recall_at_1024_estimated_tokens" : 0.8888888888888888
      },
      "G2" : {
        "recall_at_1" : 0.5555555555555556,
        "recall_at_3" : 0.6111111111111112,
        "recall_at_5" : 0.8888888888888888,
        "recall_at_10" : 0.8888888888888888,
        "mrr" : 0.6611111111111111,
        "ndcg_at_10" : 0.6727474531196923,
        "recall_at_256_estimated_tokens" : 0.8888888888888888,
        "recall_at_512_estimated_tokens" : 0.8888888888888888,
        "recall_at_1024_estimated_tokens" : 0.8888888888888888
      }
    },
    "token_budget_method" : "estimated; see run-config.json",
    "paired_effects" : {
      "delta_recall_at_5_g2_minus_g1" : 0.05555555555555547,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "lower" : 0.05555555555555547,
        "resamples" : 10000,
        "cluster_count" : 1,
        "upper" : 0.05555555555555547
      },
      "delta_recall_at_512_estimated_tokens_g2_minus_g1" : 0.0,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "lower" : 0.0,
        "resamples" : 10000,
        "cluster_count" : 1,
        "upper" : 0.0
      },
      "primary_metrics" : [ "Delta Recall@5", "Delta recall at estimated 512 tokens" ]
    }
  },
  "end_to_end" : {
    "by_group" : {
      "G1" : {
        "correct" : 8,
        "total" : 10,
        "accuracy" : 0.8,
        "abstention_correct" : 1,
        "abstention_total" : 1,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 224,
        "mean_prompt_tokens_reported" : 265.1,
        "mean_latency_ms" : 989
      },
      "G2" : {
        "correct" : 9,
        "total" : 10,
        "accuracy" : 0.9,
        "abstention_correct" : 1,
        "abstention_total" : 1,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 232,
        "mean_prompt_tokens_reported" : 271.1,
        "mean_latency_ms" : 959
      }
    },
    "answers" : 20,
    "paired" : {
      "g2_minus_g1_accuracy" : 0.1,
      "mcnemar_g1_only_correct" : 1,
      "mcnemar_g2_only_correct" : 2,
      "mcnemar_p_value_approx" : 1.0000000300000005
    }
  },
  "model_calls" : {
    "total_tokens_reported" : 62756,
    "prompt_tokens_reported" : 29963,
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 40,
        "failures" : 0,
        "latency_ms" : {
          "p99_ms" : 5690,
          "mean_ms" : 2550.6,
          "p50_ms" : 2081,
          "p95_ms" : 4732,
          "count" : 40
        },
        "prompt_tokens_reported" : 18824,
        "completion_tokens_reported" : 20776,
        "total_tokens_reported" : 39600,
        "calls_with_token_usage" : 40
      },
      "curator" : {
        "calls" : 4,
        "failures" : 0,
        "latency_ms" : {
          "p99_ms" : 16192,
          "mean_ms" : 11259.5,
          "p50_ms" : 7185,
          "p95_ms" : 16192,
          "count" : 4
        },
        "prompt_tokens_reported" : 5777,
        "completion_tokens_reported" : 10288,
        "total_tokens_reported" : 16065,
        "calls_with_token_usage" : 4
      },
      "answer" : {
        "calls" : 20,
        "failures" : 0,
        "latency_ms" : {
          "p99_ms" : 1605,
          "mean_ms" : 974.45,
          "p50_ms" : 918,
          "p95_ms" : 1354,
          "count" : 20
        },
        "prompt_tokens_reported" : 5362,
        "completion_tokens_reported" : 1729,
        "total_tokens_reported" : 7091,
        "calls_with_token_usage" : 20
      }
    },
    "completion_tokens_reported" : 32793,
    "calls" : 64,
    "calls_with_token_usage" : 64
  },
  "system_performance" : {
    "experiment_wall_ms" : 177572,
    "timeline_count" : 1,
    "baseline_ingestion_per_timeline_ms" : {
      "p99_ms" : 106501,
      "mean_ms" : 106501.0,
      "p50_ms" : 106501,
      "p95_ms" : 106501,
      "count" : 1
    },
    "curator_ingestion_per_timeline_ms" : {
      "p99_ms" : 45770,
      "mean_ms" : 45770.0,
      "p50_ms" : 45770,
      "p95_ms" : 45770,
      "count" : 1
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "p99_ms" : 20186,
      "mean_ms" : 20186.0,
      "p50_ms" : 20186,
      "p95_ms" : 20186,
      "count" : 1
    },
    "query_embedding_latency_ms" : {
      "p99_ms" : 66,
      "mean_ms" : 51.4,
      "p50_ms" : 50,
      "p95_ms" : 66,
      "count" : 10
    },
    "g1_production_retrieval_search_latency_ms" : {
      "p99_ms" : 42,
      "mean_ms" : 8.2,
      "p50_ms" : 4,
      "p95_ms" : 42,
      "count" : 10
    },
    "g2_production_retrieval_search_latency_ms" : {
      "p99_ms" : 19,
      "mean_ms" : 6.4,
      "p50_ms" : 4,
      "p95_ms" : 19,
      "count" : 10
    },
    "g1_indexed_corpus" : {
      "estimated_tokens" : 1669,
      "unit_count" : 87,
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 21,
        "knowledge_graph_relation" : 29,
        "ordinary_long_term_memory" : 37
      },
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 531,
        "knowledge_graph_relation" : 332,
        "ordinary_long_term_memory" : 806
      }
    },
    "g2_indexed_corpus" : {
      "estimated_tokens" : 2331,
      "unit_count" : 107,
      "unit_count_by_memory_type" : {
        "curated_fact" : 10,
        "curated_insight" : 3,
        "current_profile" : 3,
        "growth" : 3,
        "knowledge_graph_entity" : 21,
        "knowledge_graph_relation" : 29,
        "ordinary_long_term_memory" : 37,
        "working_memory" : 1
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 201,
        "curated_insight" : 216,
        "current_profile" : 44,
        "growth" : 154,
        "knowledge_graph_entity" : 531,
        "knowledge_graph_relation" : 332,
        "ordinary_long_term_memory" : 806,
        "working_memory" : 47
      }
    },
    "g1_sqlite_footprint_bytes" : {
      "total_observed_bytes" : 4881984,
      "wal_bytes" : 4136512,
      "shm_bytes" : 32768,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "main_db_bytes" : 712704
    },
    "g2_sqlite_footprint_bytes" : {
      "total_observed_bytes" : 4922920,
      "wal_bytes" : 4132392,
      "shm_bytes" : 32768,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "main_db_bytes" : 757760
    },
    "llm_calls_by_stage" : {
      "total_tokens_reported" : 62756,
      "prompt_tokens_reported" : 29963,
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 40,
          "failures" : 0,
          "latency_ms" : {
            "p99_ms" : 5690,
            "mean_ms" : 2550.6,
            "p50_ms" : 2081,
            "p95_ms" : 4732,
            "count" : 40
          },
          "prompt_tokens_reported" : 18824,
          "completion_tokens_reported" : 20776,
          "total_tokens_reported" : 39600,
          "calls_with_token_usage" : 40
        },
        "curator" : {
          "calls" : 4,
          "failures" : 0,
          "latency_ms" : {
            "p99_ms" : 16192,
            "mean_ms" : 11259.5,
            "p50_ms" : 7185,
            "p95_ms" : 16192,
            "count" : 4
          },
          "prompt_tokens_reported" : 5777,
          "completion_tokens_reported" : 10288,
          "total_tokens_reported" : 16065,
          "calls_with_token_usage" : 4
        },
        "answer" : {
          "calls" : 20,
          "failures" : 0,
          "latency_ms" : {
            "p99_ms" : 1605,
            "mean_ms" : 974.45,
            "p50_ms" : 918,
            "p95_ms" : 1354,
            "count" : 20
          },
          "prompt_tokens_reported" : 5362,
          "completion_tokens_reported" : 1729,
          "total_tokens_reported" : 7091,
          "calls_with_token_usage" : 20
        }
      },
      "completion_tokens_reported" : 32793,
      "calls" : 64,
      "calls_with_token_usage" : 64
    },
    "baseline_turns_per_second_sum_timeline_time" : 0.37558332785607645
  },
  "failures" : {
    "by_reason" : { },
    "count" : 0
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory path in its own SQLite database; G2 copies that state into a separate database and adds real MemoryCuratorService output.", "Both retrieval groups use the same production SqliteMemoryService hybrid retriever, same embedding provider, query, and top-k settings.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run." ]
}
```
