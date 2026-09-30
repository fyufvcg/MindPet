# Memory Curator LLM experiment

Dataset: `memory-curator-value-300.jsonl` (SHA-256 `074f57e199a7d271464246dd62e8208a00dcf6c3e069fe670357306b4be28b5f`)

This report is generated from real calls to the production Java KnowledgeGraphService, MemoryCuratorService, and SqliteMemoryService. Fixture output is not used as experiment data.

```json
{
  "dataset_version" : "memory-curator-value-v1",
  "selected_timelines" : 1,
  "selected_turns" : 40,
  "selected_queries" : 10,
  "answer_queries" : 0,
  "answer_calls_expected" : 0,
  "fact_quality" : {
    "true_positive" : 3,
    "false_positive" : 8,
    "false_negative" : 5,
    "precision" : 0.2727272727272727,
    "recall" : 0.375,
    "f1" : 0.3157894736842105,
    "stored_fact_rows" : 11,
    "semantic_duplicate_rows" : 0,
    "duplicate_rate" : 0.0,
    "by_timeline" : [ {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-018",
      "expected" : 8,
      "stored" : 11,
      "true_positive" : 3,
      "duplicate_rows" : 0
    } ]
  },
  "profile_quality" : {
    "recall" : 1.0,
    "expected_entries_correct" : 4,
    "fully_correct_timelines" : 1,
    "by_timeline" : [ {
      "correct" : 4,
      "actual" : {
        "occupation_current" : "产品经理",
        "current_project" : "阅读计划应用",
        "home_location" : "福州",
        "current_location" : "深圳"
      },
      "expected_count" : 4,
      "timeline_id" : "timeline-018",
      "expected" : {
        "home_location" : "福州",
        "current_location" : "深圳",
        "occupation_current" : "产品经理",
        "current_project" : "阅读计划应用"
      }
    } ],
    "expected_entries" : 4,
    "written_entries" : 4,
    "precision" : 1.0
  },
  "retention" : {
    "retention_at_1" : 0.375,
    "retention_at_3" : 0.375,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 8,
    "eligible_facts_at_3" : 8,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "estimator" : "CJK code point=1, ASCII alphanumeric runs=ceil(length/4), punctuation=1",
    "compression_rate_estimated" : 0.7909407665505226,
    "raw_message_tokens_estimated" : 861,
    "curated_fact_tokens_estimated" : 180,
    "curated_fact_characters" : 394,
    "raw_message_characters" : 861
  },
  "temporal" : {
    "ambiguous_time_expected" : 0,
    "exact_time_expected" : 0,
    "ambiguous_time_accuracy" : 0.0,
    "exact_time_accuracy" : 0.0,
    "ambiguous_time_correct" : 0,
    "exact_time_correct" : 0
  },
  "source_evidence" : {
    "stored_facts" : 11,
    "source_completeness" : 1.0,
    "valid_source_turns" : 11,
    "verbatim_evidence" : 11,
    "verbatim_evidence_rate" : 1.0
  },
  "sensitive_content" : {
    "by_timeline" : [ ],
    "scanned_tables" : [ "long_term_memory", "memory_fact", "user_profile_current", "user_insight", "llm_growth", "kg_entity", "kg_relation", "kg_evidence" ],
    "fully_blocked" : 0,
    "synthetic_sensitive_cases" : 0,
    "blocking_rate" : 0.0
  },
  "retrieval" : {
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
    "token_budget_method" : "estimated; see run-config.json",
    "paired_effects" : {
      "delta_recall_at_5_g2_minus_g1" : 0.0,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "resamples" : 0,
        "upper" : "NaN",
        "lower" : "NaN"
      },
      "delta_recall_at_512_estimated_tokens_g2_minus_g1" : 0.0,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "resamples" : 0,
        "upper" : "NaN",
        "lower" : "NaN"
      },
      "primary_metrics" : [ "Delta Recall@5", "Delta recall at estimated 512 tokens" ]
    },
    "query_count" : 10
  },
  "end_to_end" : {
    "by_group" : {
      "G1" : {
        "accuracy" : 0.0,
        "correct" : 0,
        "mean_context_token_estimate" : 0,
        "mean_latency_ms" : 0,
        "abstention_accuracy" : 0.0,
        "abstention_correct" : 0,
        "abstention_total" : 0,
        "total" : 0,
        "mean_prompt_tokens_reported" : 0.0
      },
      "G2" : {
        "accuracy" : 0.0,
        "correct" : 0,
        "mean_context_token_estimate" : 0,
        "mean_latency_ms" : 0,
        "abstention_accuracy" : 0.0,
        "abstention_correct" : 0,
        "abstention_total" : 0,
        "total" : 0,
        "mean_prompt_tokens_reported" : 0.0
      }
    },
    "answers" : 0,
    "paired" : {
      "g2_minus_g1_accuracy" : 0.0,
      "mcnemar_g1_only_correct" : 0,
      "mcnemar_g2_only_correct" : 0,
      "mcnemar_p_value_approx" : 1.0
    }
  },
  "model_calls" : {
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 40,
        "failures" : 0,
        "latency_ms" : {
          "p99_ms" : 5669,
          "mean_ms" : 2580.975,
          "p50_ms" : 2187,
          "p95_ms" : 5263,
          "count" : 40
        },
        "prompt_tokens_reported" : 0,
        "completion_tokens_reported" : 0,
        "total_tokens_reported" : 0,
        "calls_with_token_usage" : 0
      },
      "curator" : {
        "calls" : 5,
        "failures" : 0,
        "latency_ms" : {
          "p99_ms" : 31356,
          "mean_ms" : 17292.2,
          "p50_ms" : 14509,
          "p95_ms" : 31356,
          "count" : 5
        },
        "prompt_tokens_reported" : 0,
        "completion_tokens_reported" : 0,
        "total_tokens_reported" : 0,
        "calls_with_token_usage" : 0
      }
    },
    "completion_tokens_reported" : 0,
    "calls" : 45,
    "calls_with_token_usage" : 0,
    "total_tokens_reported" : 0,
    "prompt_tokens_reported" : 0
  },
  "system_performance" : {
    "experiment_wall_ms" : 204565,
    "timeline_count" : 1,
    "baseline_ingestion_per_timeline_ms" : {
      "p99_ms" : 108198,
      "mean_ms" : 108198.0,
      "p50_ms" : 108198,
      "p95_ms" : 108198,
      "count" : 1
    },
    "curator_ingestion_per_timeline_ms" : {
      "p99_ms" : 88225,
      "mean_ms" : 88225.0,
      "p50_ms" : 88225,
      "p95_ms" : 88225,
      "count" : 1
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "p99_ms" : 627,
      "mean_ms" : 627.0,
      "p50_ms" : 627,
      "p95_ms" : 627,
      "count" : 1
    },
    "llm_calls_by_stage" : {
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 40,
          "failures" : 0,
          "latency_ms" : {
            "p99_ms" : 5669,
            "mean_ms" : 2580.975,
            "p50_ms" : 2187,
            "p95_ms" : 5263,
            "count" : 40
          },
          "prompt_tokens_reported" : 0,
          "completion_tokens_reported" : 0,
          "total_tokens_reported" : 0,
          "calls_with_token_usage" : 0
        },
        "curator" : {
          "calls" : 5,
          "failures" : 0,
          "latency_ms" : {
            "p99_ms" : 31356,
            "mean_ms" : 17292.2,
            "p50_ms" : 14509,
            "p95_ms" : 31356,
            "count" : 5
          },
          "prompt_tokens_reported" : 0,
          "completion_tokens_reported" : 0,
          "total_tokens_reported" : 0,
          "calls_with_token_usage" : 0
        }
      },
      "completion_tokens_reported" : 0,
      "calls" : 45,
      "calls_with_token_usage" : 0,
      "total_tokens_reported" : 0,
      "prompt_tokens_reported" : 0
    },
    "baseline_turns_per_second_wall_clock" : 0.19553687092122307
  },
  "failures" : {
    "count" : 0,
    "by_reason" : { }
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory path; G2 copies the same G1 memory and adds real MemoryCuratorService output.", "Both retrieval groups use the same production SqliteMemoryService hybrid retriever, same embedding provider, query, and top-k settings.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run." ]
}
```
