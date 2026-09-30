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
    "true_positive" : 0,
    "false_positive" : 0,
    "false_negative" : 8,
    "precision" : 0.0,
    "recall" : 0.0,
    "f1" : 0.0,
    "stored_fact_rows" : 0,
    "semantic_duplicate_rows" : 0,
    "duplicate_rate" : 0.0,
    "by_timeline" : [ {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-011",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
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
      "correct" : 0,
      "actual" : { }
    } ],
    "expected_entries" : 4,
    "written_entries" : 0,
    "precision" : 0.0,
    "recall" : 0.0,
    "expected_entries_correct" : 0,
    "fully_correct_timelines" : 0
  },
  "retention" : {
    "retention_at_1" : 0.0,
    "retention_at_3" : 0.0,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 0,
    "eligible_facts_at_3" : 0,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "raw_message_tokens_estimated" : 877,
    "curated_fact_tokens_estimated" : 0,
    "curated_fact_characters" : 0,
    "raw_message_characters" : 877,
    "estimator" : "CJK code point=1, ASCII alphanumeric runs=ceil(length/4), punctuation=1",
    "compression_rate_estimated" : 1.0
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
    "verbatim_evidence_rate" : 0.0,
    "stored_facts" : 0,
    "source_completeness" : 0.0,
    "valid_source_turns" : 0,
    "verbatim_evidence" : 0
  },
  "sensitive_content" : {
    "synthetic_sensitive_cases" : 0,
    "fully_blocked" : 0,
    "blocking_rate" : null,
    "by_timeline" : [ ],
    "scanned_tables" : [ "long_term_memory", "memory_fact", "user_profile_current", "user_insight", "llm_growth", "kg_entity", "kg_relation", "kg_evidence" ]
  },
  "retrieval" : {
    "paired_effects" : {
      "delta_recall_at_5_g2_minus_g1" : 0.0,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 0
      },
      "delta_recall_at_512_estimated_tokens_g2_minus_g1" : 0.0,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 0
      },
      "primary_metrics" : [ "Delta Recall@5", "Delta recall at estimated 512 tokens" ]
    },
    "query_count" : 10,
    "by_group" : {
      "G1" : {
        "recall_at_1" : 0.0,
        "recall_at_3" : 0.0,
        "recall_at_5" : 0.0,
        "recall_at_10" : 0.0,
        "mrr" : 0.0,
        "ndcg_at_10" : 0.0,
        "recall_at_256_estimated_tokens" : 0.0,
        "recall_at_512_estimated_tokens" : 0.0,
        "recall_at_1024_estimated_tokens" : 0.0
      },
      "G2" : {
        "recall_at_1" : 0.0,
        "recall_at_3" : 0.0,
        "recall_at_5" : 0.0,
        "recall_at_10" : 0.0,
        "mrr" : 0.0,
        "ndcg_at_10" : 0.0,
        "recall_at_256_estimated_tokens" : 0.0,
        "recall_at_512_estimated_tokens" : 0.0,
        "recall_at_1024_estimated_tokens" : 0.0
      }
    },
    "token_budget_method" : "estimated; see run-config.json"
  },
  "end_to_end" : {
    "paired" : {
      "g2_minus_g1_accuracy" : 0.0,
      "mcnemar_g1_only_correct" : 0,
      "mcnemar_g2_only_correct" : 0,
      "mcnemar_p_value_approx" : 1.0
    },
    "by_group" : {
      "G1" : {
        "correct" : 8,
        "total" : 10,
        "accuracy" : 0.8,
        "abstention_correct" : 1,
        "abstention_total" : 1,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 222,
        "mean_prompt_tokens_reported" : 261.1,
        "mean_latency_ms" : 866
      },
      "G2" : {
        "correct" : 8,
        "total" : 10,
        "accuracy" : 0.8,
        "abstention_correct" : 1,
        "abstention_total" : 1,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 221,
        "mean_prompt_tokens_reported" : 260.7,
        "mean_latency_ms" : 789
      }
    },
    "answers" : 20
  },
  "model_calls" : {
    "completion_tokens_reported" : 57847,
    "calls" : 64,
    "calls_with_token_usage" : 64,
    "total_tokens_reported" : 89168,
    "prompt_tokens_reported" : 31321,
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 40,
        "failures" : 0,
        "latency_ms" : {
          "p95_ms" : 4751,
          "count" : 40,
          "p99_ms" : 5199,
          "mean_ms" : 2505.425,
          "p50_ms" : 2228
        },
        "prompt_tokens_reported" : 18824,
        "completion_tokens_reported" : 20566,
        "total_tokens_reported" : 39390,
        "calls_with_token_usage" : 40
      },
      "curator" : {
        "calls" : 4,
        "failures" : 0,
        "latency_ms" : {
          "p95_ms" : 64868,
          "count" : 4,
          "p99_ms" : 64868,
          "mean_ms" : 38115.75,
          "p50_ms" : 31611
        },
        "prompt_tokens_reported" : 7279,
        "completion_tokens_reported" : 35833,
        "total_tokens_reported" : 43112,
        "calls_with_token_usage" : 4
      },
      "answer" : {
        "calls" : 20,
        "failures" : 0,
        "latency_ms" : {
          "p95_ms" : 1192,
          "count" : 20,
          "p99_ms" : 1255,
          "mean_ms" : 827.9,
          "p50_ms" : 808
        },
        "prompt_tokens_reported" : 5218,
        "completion_tokens_reported" : 1448,
        "total_tokens_reported" : 6666,
        "calls_with_token_usage" : 20
      }
    }
  },
  "system_performance" : {
    "experiment_wall_ms" : 280207,
    "timeline_count" : 1,
    "baseline_ingestion_per_timeline_ms" : {
      "p95_ms" : 104618,
      "count" : 1,
      "p99_ms" : 104618,
      "mean_ms" : 104618.0,
      "p50_ms" : 104618
    },
    "curator_ingestion_per_timeline_ms" : {
      "p95_ms" : 154444,
      "count" : 1,
      "p99_ms" : 154444,
      "mean_ms" : 154444.0,
      "p50_ms" : 154444
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "p95_ms" : 17234,
      "count" : 1,
      "p99_ms" : 17234,
      "mean_ms" : 17234.0,
      "p50_ms" : 17234
    },
    "llm_calls_by_stage" : {
      "completion_tokens_reported" : 57847,
      "calls" : 64,
      "calls_with_token_usage" : 64,
      "total_tokens_reported" : 89168,
      "prompt_tokens_reported" : 31321,
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 40,
          "failures" : 0,
          "latency_ms" : {
            "p95_ms" : 4751,
            "count" : 40,
            "p99_ms" : 5199,
            "mean_ms" : 2505.425,
            "p50_ms" : 2228
          },
          "prompt_tokens_reported" : 18824,
          "completion_tokens_reported" : 20566,
          "total_tokens_reported" : 39390,
          "calls_with_token_usage" : 40
        },
        "curator" : {
          "calls" : 4,
          "failures" : 0,
          "latency_ms" : {
            "p95_ms" : 64868,
            "count" : 4,
            "p99_ms" : 64868,
            "mean_ms" : 38115.75,
            "p50_ms" : 31611
          },
          "prompt_tokens_reported" : 7279,
          "completion_tokens_reported" : 35833,
          "total_tokens_reported" : 43112,
          "calls_with_token_usage" : 4
        },
        "answer" : {
          "calls" : 20,
          "failures" : 0,
          "latency_ms" : {
            "p95_ms" : 1192,
            "count" : 20,
            "p99_ms" : 1255,
            "mean_ms" : 827.9,
            "p50_ms" : 808
          },
          "prompt_tokens_reported" : 5218,
          "completion_tokens_reported" : 1448,
          "total_tokens_reported" : 6666,
          "calls_with_token_usage" : 20
        }
      }
    },
    "baseline_turns_per_second_wall_clock" : 0.14275160863218977
  },
  "failures" : {
    "by_reason" : {
      "CURATOR_INCOMPLETE" : 1
    },
    "count" : 1
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory path; G2 copies the same G1 memory and adds real MemoryCuratorService output.", "Both retrieval groups use the same production SqliteMemoryService hybrid retriever, same embedding provider, query, and top-k settings.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run." ]
}
```
