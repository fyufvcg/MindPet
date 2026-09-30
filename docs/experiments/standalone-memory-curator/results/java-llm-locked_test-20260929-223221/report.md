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
    "true_positive" : 6,
    "false_positive" : 2,
    "false_negative" : 2,
    "precision" : 0.75,
    "recall" : 0.75,
    "f1" : 0.75,
    "stored_fact_rows" : 8,
    "semantic_duplicate_rows" : 0,
    "duplicate_rate" : 0.0,
    "by_timeline" : [ {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-011",
      "expected" : 8,
      "stored" : 8,
      "true_positive" : 6,
      "duplicate_rows" : 0
    } ]
  },
  "profile_quality" : {
    "written_entries" : 3,
    "precision" : 1.0,
    "recall" : 0.75,
    "expected_entries_correct" : 3,
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
        "current_project" : "校园服务平台",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师"
      }
    } ],
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
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 34,
        "ordinary_long_term_memory" : 36
      },
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 576,
        "knowledge_graph_relation" : 373,
        "ordinary_long_term_memory" : 784
      },
      "estimated_tokens" : 1733,
      "unit_count" : 94
    },
    "actual_g2a_append_retrieval_corpus" : {
      "unit_count_by_memory_type" : {
        "curated_fact" : 8,
        "curated_insight" : 1,
        "current_profile" : 3,
        "growth" : 1,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 34,
        "ordinary_long_term_memory" : 36,
        "working_memory" : 1
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 170,
        "curated_insight" : 68,
        "current_profile" : 44,
        "growth" : 71,
        "knowledge_graph_entity" : 576,
        "knowledge_graph_relation" : 373,
        "ordinary_long_term_memory" : 784,
        "working_memory" : 47
      },
      "estimated_tokens" : 2133,
      "unit_count" : 108
    },
    "actual_g2c_compacted_retrieval_corpus" : {
      "unit_count_by_memory_type" : {
        "curated_fact" : 5,
        "curated_growth" : 1,
        "curated_insight" : 1,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 31,
        "ordinary_long_term_memory" : 29
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 65,
        "curated_growth" : 50,
        "curated_insight" : 45,
        "knowledge_graph_entity" : 556,
        "knowledge_graph_relation" : 339,
        "ordinary_long_term_memory" : 620
      },
      "estimated_tokens" : 1675,
      "unit_count" : 91
    },
    "actual_g2a_token_delta_vs_g1" : 0.2308136180034622,
    "actual_g2c_token_delta_vs_g1" : -0.03346797461050202,
    "actual_g2c_system_compression_rate" : 0.033467974610501994,
    "actual_g2c_token_delta_vs_g2a" : -0.21472105016408813,
    "g2c_compaction_action_counts" : {
      "KEEP" : 99,
      "RETIRE" : 6
    },
    "g2c_logged_net_token_reduction" : -1584,
    "raw_turn_text_tokens_estimated" : 877,
    "curated_fact_projection_tokens_estimated" : 146,
    "curated_fact_projection_compression_estimate" : 0.8335233751425314,
    "raw_turn_text_characters" : 877,
    "curated_fact_projection_characters" : 319,
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
    "active_retrieval_units" : 94,
    "retrieval_unit_source_traceability" : 18,
    "retrieval_unit_source_traceability_rate" : 0.19148936170212766
  },
  "sensitive_content" : {
    "synthetic_sensitive_cases" : 0,
    "fully_blocked" : 0,
    "blocking_rate" : null,
    "by_timeline" : [ ],
    "scanned_tables" : [ "long_term_memory", "memory_fact", "user_profile_current", "user_insight", "llm_growth", "memory_retrieval_unit", "memory_retrieval_source", "kg_entity", "kg_relation", "kg_evidence" ]
  },
  "retrieval" : {
    "query_count" : 10,
    "by_group" : {
      "G1" : {
        "recall_at_1" : 0.2777777777777778,
        "recall_at_3" : 0.7777777777777778,
        "recall_at_5" : 0.7777777777777778,
        "recall_at_10" : 0.8888888888888888,
        "mrr" : 0.5343915343915344,
        "ndcg_at_10" : 0.6197122532966943,
        "recall_at_256_estimated_tokens" : 0.8888888888888888,
        "recall_at_512_estimated_tokens" : 0.8888888888888888,
        "recall_at_1024_estimated_tokens" : 0.8888888888888888
      },
      "G2A" : {
        "recall_at_1" : 0.2777777777777778,
        "recall_at_3" : 0.7777777777777778,
        "recall_at_5" : 0.7777777777777778,
        "recall_at_10" : 0.8888888888888888,
        "mrr" : 0.5343915343915344,
        "ndcg_at_10" : 0.6197122532966943,
        "recall_at_256_estimated_tokens" : 0.8888888888888888,
        "recall_at_512_estimated_tokens" : 0.8888888888888888,
        "recall_at_1024_estimated_tokens" : 0.8888888888888888
      },
      "G2C" : {
        "recall_at_1" : 0.3333333333333333,
        "recall_at_3" : 0.5555555555555556,
        "recall_at_5" : 0.6666666666666666,
        "recall_at_10" : 0.6666666666666666,
        "mrr" : 0.5037037037037037,
        "ndcg_at_10" : 0.5234676627136967,
        "recall_at_256_estimated_tokens" : 0.6666666666666666,
        "recall_at_512_estimated_tokens" : 0.6666666666666666,
        "recall_at_1024_estimated_tokens" : 0.6666666666666666
      }
    },
    "token_budget_method" : "estimated; see run-config.json",
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : -0.11111111111111116,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "cluster_count" : 1,
        "upper" : -0.11111111111111116,
        "lower" : -0.11111111111111116,
        "resamples" : 10000
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : -0.2222222222222222,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "cluster_count" : 1,
        "upper" : -0.2222222222222222,
        "lower" : -0.2222222222222222,
        "resamples" : 10000
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.0,
      "primary_metrics" : [ "Delta Recall@5", "Delta recall at estimated 512 tokens" ]
    }
  },
  "end_to_end" : {
    "paired" : {
      "primary_comparison" : "G2C minus G1",
      "g2c_minus_g1_accuracy" : 1.0,
      "mcnemar_g1_only_correct" : 0,
      "mcnemar_g2_only_correct" : 1,
      "mcnemar_p_value_approx" : 1.0000000300000005
    },
    "by_group" : {
      "G1" : {
        "correct" : 0,
        "total" : 1,
        "accuracy" : 0.0,
        "abstention_correct" : 0,
        "abstention_total" : 0,
        "abstention_accuracy" : null,
        "mean_context_token_estimate" : 214,
        "mean_prompt_tokens_reported" : 263.0,
        "mean_latency_ms" : 692
      },
      "G2C" : {
        "correct" : 1,
        "total" : 1,
        "accuracy" : 1.0,
        "abstention_correct" : 0,
        "abstention_total" : 0,
        "abstention_accuracy" : null,
        "mean_context_token_estimate" : 165,
        "mean_prompt_tokens_reported" : 234.0,
        "mean_latency_ms" : 1274
      }
    },
    "answers" : 2
  },
  "model_calls" : {
    "calls" : 46,
    "calls_with_token_usage" : 46,
    "total_tokens_reported" : 69315,
    "prompt_tokens_reported" : 27695,
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 40,
        "failures" : 0,
        "latency_ms" : {
          "count" : 40,
          "p99_ms" : 6970,
          "mean_ms" : 2624.925,
          "p50_ms" : 2313,
          "p95_ms" : 5890
        },
        "prompt_tokens_reported" : 18824,
        "completion_tokens_reported" : 20861,
        "total_tokens_reported" : 39685,
        "calls_with_token_usage" : 40
      },
      "curator" : {
        "calls" : 4,
        "failures" : 0,
        "latency_ms" : {
          "count" : 4,
          "p99_ms" : 71039,
          "mean_ms" : 23532.5,
          "p50_ms" : 4980,
          "p95_ms" : 71039
        },
        "prompt_tokens_reported" : 8374,
        "completion_tokens_reported" : 20575,
        "total_tokens_reported" : 28949,
        "calls_with_token_usage" : 4
      },
      "answer" : {
        "calls" : 2,
        "failures" : 0,
        "latency_ms" : {
          "count" : 2,
          "p99_ms" : 1274,
          "mean_ms" : 983.0,
          "p50_ms" : 692,
          "p95_ms" : 1274
        },
        "prompt_tokens_reported" : 497,
        "completion_tokens_reported" : 184,
        "total_tokens_reported" : 681,
        "calls_with_token_usage" : 2
      }
    },
    "completion_tokens_reported" : 41620
  },
  "system_performance" : {
    "experiment_wall_ms" : 212771,
    "timeline_count" : 1,
    "baseline_ingestion_per_timeline_ms" : {
      "count" : 1,
      "p99_ms" : 109584,
      "mean_ms" : 109584.0,
      "p50_ms" : 109584,
      "p95_ms" : 109584
    },
    "curator_ingestion_per_timeline_ms" : {
      "count" : 1,
      "p99_ms" : 95817,
      "mean_ms" : 95817.0,
      "p50_ms" : 95817,
      "p95_ms" : 95817
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "count" : 1,
      "p99_ms" : 2744,
      "mean_ms" : 2744.0,
      "p50_ms" : 2744,
      "p95_ms" : 2744
    },
    "query_embedding_latency_ms" : {
      "count" : 10,
      "p99_ms" : 58,
      "mean_ms" : 51.2,
      "p50_ms" : 49,
      "p95_ms" : 58
    },
    "g1_production_retrieval_search_latency_ms" : {
      "count" : 10,
      "p99_ms" : 29,
      "mean_ms" : 6.7,
      "p50_ms" : 3,
      "p95_ms" : 29
    },
    "g2a_append_retrieval_search_latency_ms" : {
      "count" : 10,
      "p99_ms" : 15,
      "mean_ms" : 5.6,
      "p50_ms" : 4,
      "p95_ms" : 15
    },
    "g2c_compaction_retrieval_search_latency_ms" : {
      "count" : 10,
      "p99_ms" : 27,
      "mean_ms" : 10.9,
      "p50_ms" : 8,
      "p95_ms" : 27
    },
    "g1_indexed_corpus" : {
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 34,
        "ordinary_long_term_memory" : 36
      },
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 576,
        "knowledge_graph_relation" : 373,
        "ordinary_long_term_memory" : 784
      },
      "estimated_tokens" : 1733,
      "unit_count" : 94
    },
    "g2a_indexed_corpus" : {
      "unit_count_by_memory_type" : {
        "curated_fact" : 8,
        "curated_insight" : 1,
        "current_profile" : 3,
        "growth" : 1,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 34,
        "ordinary_long_term_memory" : 36,
        "working_memory" : 1
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 170,
        "curated_insight" : 68,
        "current_profile" : 44,
        "growth" : 71,
        "knowledge_graph_entity" : 576,
        "knowledge_graph_relation" : 373,
        "ordinary_long_term_memory" : 784,
        "working_memory" : 47
      },
      "estimated_tokens" : 2133,
      "unit_count" : 108
    },
    "g2c_indexed_corpus" : {
      "unit_count_by_memory_type" : {
        "curated_fact" : 5,
        "curated_growth" : 1,
        "curated_insight" : 1,
        "knowledge_graph_entity" : 24,
        "knowledge_graph_relation" : 31,
        "ordinary_long_term_memory" : 29
      },
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 65,
        "curated_growth" : 50,
        "curated_insight" : 45,
        "knowledge_graph_entity" : 556,
        "knowledge_graph_relation" : 339,
        "ordinary_long_term_memory" : 620
      },
      "estimated_tokens" : 1675,
      "unit_count" : 91
    },
    "g1_sqlite_footprint_bytes" : {
      "total_observed_bytes" : 4935280,
      "wal_bytes" : 4144752,
      "shm_bytes" : 32768,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "main_db_bytes" : 757760
    },
    "g2_sqlite_footprint_bytes" : {
      "total_observed_bytes" : 5889624,
      "wal_bytes" : 4140632,
      "shm_bytes" : 32768,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "main_db_bytes" : 1716224
    },
    "llm_calls_by_stage" : {
      "calls" : 46,
      "calls_with_token_usage" : 46,
      "total_tokens_reported" : 69315,
      "prompt_tokens_reported" : 27695,
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 40,
          "failures" : 0,
          "latency_ms" : {
            "count" : 40,
            "p99_ms" : 6970,
            "mean_ms" : 2624.925,
            "p50_ms" : 2313,
            "p95_ms" : 5890
          },
          "prompt_tokens_reported" : 18824,
          "completion_tokens_reported" : 20861,
          "total_tokens_reported" : 39685,
          "calls_with_token_usage" : 40
        },
        "curator" : {
          "calls" : 4,
          "failures" : 0,
          "latency_ms" : {
            "count" : 4,
            "p99_ms" : 71039,
            "mean_ms" : 23532.5,
            "p50_ms" : 4980,
            "p95_ms" : 71039
          },
          "prompt_tokens_reported" : 8374,
          "completion_tokens_reported" : 20575,
          "total_tokens_reported" : 28949,
          "calls_with_token_usage" : 4
        },
        "answer" : {
          "calls" : 2,
          "failures" : 0,
          "latency_ms" : {
            "count" : 2,
            "p99_ms" : 1274,
            "mean_ms" : 983.0,
            "p50_ms" : 692,
            "p95_ms" : 1274
          },
          "prompt_tokens_reported" : 497,
          "completion_tokens_reported" : 184,
          "total_tokens_reported" : 681,
          "calls_with_token_usage" : 2
        }
      },
      "completion_tokens_reported" : 41620
    },
    "baseline_turns_per_second_sum_timeline_time" : 0.36501679077237553
  },
  "failures" : {
    "by_reason" : { },
    "count" : 0
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory baseline. G2A and G2C reuse the same accepted real MemoryCuratorService output so the ablation isolates append-only versus compacted serving behavior.", "G1 and G2A use the production SqliteMemoryService hybrid retriever. G2C uses production memory_retrieval_unit ranking and consults the legacy long_term_memory retriever only when the compacted corpus returns no candidate.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run.", "Formal paired effects compare G2C with G1. G2A is reported as an append-only ablation." ]
}
```
