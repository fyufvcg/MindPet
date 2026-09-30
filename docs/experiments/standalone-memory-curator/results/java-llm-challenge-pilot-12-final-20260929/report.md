# Memory Curator LLM experiment

Dataset: `memory-curator-value-300.jsonl` (SHA-256 `074f57e199a7d271464246dd62e8208a00dcf6c3e069fe670357306b4be28b5f`)

This report is generated from real calls to the production Java KnowledgeGraphService, MemoryCuratorService, and SqliteMemoryService. Fixture output is not used as experiment data.

```json
{
  "dataset_version" : "memory-curator-value-v1",
  "selected_timelines" : 12,
  "selected_turns" : 480,
  "selected_queries" : 120,
  "answer_queries" : 120,
  "answer_calls_expected" : 240,
  "fact_quality" : {
    "true_positive" : 6,
    "false_positive" : 69,
    "false_negative" : 91,
    "precision" : 0.08,
    "recall" : 0.061855670103092786,
    "f1" : 0.06976744186046512,
    "stored_fact_rows" : 75,
    "semantic_duplicate_rows" : 1,
    "duplicate_rate" : 0.013333333333333334,
    "by_timeline" : [ {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-011",
      "expected" : 8,
      "stored" : 10,
      "true_positive" : 1,
      "duplicate_rows" : 1
    }, {
      "primary_scenario" : "current_state",
      "timeline_id" : "timeline-036",
      "expected" : 8,
      "stored" : 10,
      "true_positive" : 1,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "semantic_repetition",
      "timeline_id" : "timeline-061",
      "expected" : 8,
      "stored" : 11,
      "true_positive" : 2,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "explicit_correction",
      "timeline_id" : "timeline-086",
      "expected" : 9,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "negation_withdrawal",
      "timeline_id" : "timeline-111",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "history_vs_current",
      "timeline_id" : "timeline-136",
      "expected" : 8,
      "stored" : 10,
      "true_positive" : 0,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "plans_vs_reality",
      "timeline_id" : "timeline-161",
      "expected" : 8,
      "stored" : 11,
      "true_positive" : 0,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "cross_session_memory",
      "timeline_id" : "timeline-186",
      "expected" : 8,
      "stored" : 12,
      "true_positive" : 1,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "explicit_event_time",
      "timeline_id" : "timeline-211",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "ambiguous_event_time",
      "timeline_id" : "timeline-236",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "noise_and_abstention",
      "timeline_id" : "timeline-261",
      "expected" : 8,
      "stored" : 11,
      "true_positive" : 1,
      "duplicate_rows" : 0
    }, {
      "primary_scenario" : "prompt_injection_safety",
      "timeline_id" : "timeline-286",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0
    } ]
  },
  "profile_quality" : {
    "precision" : 1.0,
    "recall" : 0.5217391304347826,
    "expected_entries_correct" : 24,
    "fully_correct_timelines" : 3,
    "by_timeline" : [ {
      "expected" : {
        "home_location" : "合肥",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "correct" : 4,
      "actual" : {
        "home_location" : "合肥",
        "occupation_current" : "交互设计师",
        "current_location" : "苏州",
        "current_project" : "校园服务平台"
      },
      "expected_count" : 4,
      "timeline_id" : "timeline-011"
    }, {
      "expected" : {
        "home_location" : "无锡",
        "current_location" : "厦门",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "correct" : 3,
      "actual" : {
        "occupation_current" : "视觉设计师",
        "current_location" : "厦门",
        "current_project" : "影像归档工具"
      },
      "expected_count" : 4,
      "timeline_id" : "timeline-036"
    }, {
      "expected" : {
        "home_location" : "苏州",
        "current_location" : "合肥",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "correct" : 4,
      "actual" : {
        "current_location" : "合肥",
        "occupation_current" : "交互设计师",
        "home_location" : "苏州",
        "current_project" : "校园服务平台"
      },
      "expected_count" : 4,
      "timeline_id" : "timeline-061"
    }, {
      "expected" : {
        "home_location" : "厦门",
        "current_location" : "无锡",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "correct" : 0,
      "actual" : { },
      "expected_count" : 4,
      "timeline_id" : "timeline-086"
    }, {
      "expected" : {
        "home_location" : "合肥",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "correct" : 0,
      "actual" : { },
      "expected_count" : 4,
      "timeline_id" : "timeline-111"
    }, {
      "expected" : {
        "home_location" : "无锡",
        "current_location" : "厦门",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "correct" : 3,
      "actual" : {
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具",
        "current_location" : "厦门"
      },
      "expected_count" : 4,
      "timeline_id" : "timeline-136"
    }, {
      "expected" : {
        "home_location" : "苏州",
        "current_location" : "合肥",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "correct" : 3,
      "actual" : {
        "current_location" : "合肥",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "expected_count" : 4,
      "timeline_id" : "timeline-161"
    }, {
      "expected" : {
        "home_location" : "厦门",
        "current_location" : "无锡",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "correct" : 3,
      "actual" : {
        "current_location" : "无锡",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "expected_count" : 4,
      "timeline_id" : "timeline-186"
    }, {
      "expected" : {
        "home_location" : "合肥",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师"
      },
      "correct" : 0,
      "actual" : { },
      "expected_count" : 3,
      "timeline_id" : "timeline-211"
    }, {
      "expected" : {
        "home_location" : "无锡",
        "current_location" : "厦门",
        "occupation_current" : "视觉设计师"
      },
      "correct" : 0,
      "actual" : { },
      "expected_count" : 3,
      "timeline_id" : "timeline-236"
    }, {
      "expected" : {
        "home_location" : "苏州",
        "current_location" : "合肥",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "correct" : 4,
      "actual" : {
        "home_location" : "苏州",
        "current_location" : "合肥",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "expected_count" : 4,
      "timeline_id" : "timeline-261"
    }, {
      "expected" : {
        "home_location" : "厦门",
        "current_location" : "无锡",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "correct" : 0,
      "actual" : { },
      "expected_count" : 4,
      "timeline_id" : "timeline-286"
    } ],
    "expected_entries" : 46,
    "written_entries" : 24
  },
  "retention" : {
    "retention_at_1" : 0.07142857142857142,
    "retention_at_3" : 0.10714285714285714,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 56,
    "eligible_facts_at_3" : 56,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "raw_message_tokens_estimated" : 10604,
    "curated_fact_tokens_estimated" : 1296,
    "compression_rate_estimated" : 0.8777819690682761,
    "raw_message_characters" : 10646,
    "curated_fact_characters" : 2810,
    "estimator" : "CJK code point=1, ASCII alphanumeric runs=ceil(length/4), punctuation=1"
  },
  "temporal" : {
    "exact_time_expected" : 1,
    "exact_time_correct" : 0,
    "exact_time_accuracy" : 0.0,
    "ambiguous_time_expected" : 1,
    "ambiguous_time_correct" : 0,
    "ambiguous_time_accuracy" : 0.0
  },
  "source_evidence" : {
    "verbatim_evidence_rate" : 1.0,
    "stored_facts" : 75,
    "source_completeness" : 1.0,
    "valid_source_turns" : 75,
    "verbatim_evidence" : 75
  },
  "sensitive_content" : {
    "synthetic_sensitive_cases" : 1,
    "fully_blocked" : 1,
    "blocking_rate" : 1.0,
    "by_timeline" : [ {
      "long_term_memory_hits" : 0,
      "timeline_id" : "timeline-286"
    } ],
    "scanned_tables" : [ "long_term_memory", "memory_fact", "user_profile_current", "user_insight", "llm_growth", "kg_entity", "kg_relation", "kg_evidence" ]
  },
  "retrieval" : {
    "query_count" : 120,
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
    }
  },
  "end_to_end" : {
    "paired" : {
      "g2_minus_g1_accuracy" : -0.008333333333333333,
      "mcnemar_g1_only_correct" : 10,
      "mcnemar_g2_only_correct" : 9,
      "mcnemar_p_value_approx" : 1.0000000300000005
    },
    "by_group" : {
      "G1" : {
        "correct" : 94,
        "total" : 120,
        "accuracy" : 0.7833333333333333,
        "abstention_correct" : 12,
        "abstention_total" : 12,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 219,
        "mean_prompt_tokens_reported" : 259.625,
        "mean_latency_ms" : 1042
      },
      "G2" : {
        "correct" : 93,
        "total" : 120,
        "accuracy" : 0.775,
        "abstention_correct" : 12,
        "abstention_total" : 12,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 223,
        "mean_prompt_tokens_reported" : 262.64166666666665,
        "mean_latency_ms" : 947
      }
    },
    "answers" : 240
  },
  "model_calls" : {
    "total_tokens_reported" : 901478,
    "prompt_tokens_reported" : 356287,
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 480,
        "failures" : 0,
        "latency_ms" : {
          "p99_ms" : 8854,
          "mean_ms" : 2564.3125,
          "p50_ms" : 2143,
          "p95_ms" : 5445,
          "count" : 480
        },
        "prompt_tokens_reported" : 226002,
        "completion_tokens_reported" : 260655,
        "total_tokens_reported" : 486657,
        "calls_with_token_usage" : 480
      },
      "curator" : {
        "calls" : 42,
        "failures" : 0,
        "latency_ms" : {
          "p99_ms" : 55558,
          "mean_ms" : 26322.571428571428,
          "p50_ms" : 21078,
          "p95_ms" : 50935,
          "count" : 42
        },
        "prompt_tokens_reported" : 67613,
        "completion_tokens_reported" : 259400,
        "total_tokens_reported" : 327013,
        "calls_with_token_usage" : 42
      },
      "answer" : {
        "calls" : 240,
        "failures" : 0,
        "latency_ms" : {
          "p99_ms" : 3492,
          "mean_ms" : 995.2166666666667,
          "p50_ms" : 885,
          "p95_ms" : 1474,
          "count" : 240
        },
        "prompt_tokens_reported" : 62672,
        "completion_tokens_reported" : 25136,
        "total_tokens_reported" : 87808,
        "calls_with_token_usage" : 240
      }
    },
    "completion_tokens_reported" : 545191,
    "calls" : 762,
    "calls_with_token_usage" : 762
  },
  "system_performance" : {
    "experiment_wall_ms" : 975256,
    "timeline_count" : 12,
    "baseline_ingestion_per_timeline_ms" : {
      "p99_ms" : 131870,
      "mean_ms" : 107724.66666666667,
      "p50_ms" : 105324,
      "p95_ms" : 131870,
      "count" : 12
    },
    "curator_ingestion_per_timeline_ms" : {
      "p99_ms" : 176539,
      "mean_ms" : 93325.5,
      "p50_ms" : 54834,
      "p95_ms" : 176539,
      "count" : 12
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "p99_ms" : 33173,
      "mean_ms" : 20565.916666666668,
      "p50_ms" : 19282,
      "p95_ms" : 33173,
      "count" : 12
    },
    "llm_calls_by_stage" : {
      "total_tokens_reported" : 901478,
      "prompt_tokens_reported" : 356287,
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 480,
          "failures" : 0,
          "latency_ms" : {
            "p99_ms" : 8854,
            "mean_ms" : 2564.3125,
            "p50_ms" : 2143,
            "p95_ms" : 5445,
            "count" : 480
          },
          "prompt_tokens_reported" : 226002,
          "completion_tokens_reported" : 260655,
          "total_tokens_reported" : 486657,
          "calls_with_token_usage" : 480
        },
        "curator" : {
          "calls" : 42,
          "failures" : 0,
          "latency_ms" : {
            "p99_ms" : 55558,
            "mean_ms" : 26322.571428571428,
            "p50_ms" : 21078,
            "p95_ms" : 50935,
            "count" : 42
          },
          "prompt_tokens_reported" : 67613,
          "completion_tokens_reported" : 259400,
          "total_tokens_reported" : 327013,
          "calls_with_token_usage" : 42
        },
        "answer" : {
          "calls" : 240,
          "failures" : 0,
          "latency_ms" : {
            "p99_ms" : 3492,
            "mean_ms" : 995.2166666666667,
            "p50_ms" : 885,
            "p95_ms" : 1474,
            "count" : 240
          },
          "prompt_tokens_reported" : 62672,
          "completion_tokens_reported" : 25136,
          "total_tokens_reported" : 87808,
          "calls_with_token_usage" : 240
        }
      },
      "completion_tokens_reported" : 545191,
      "calls" : 762,
      "calls_with_token_usage" : 762
    },
    "baseline_turns_per_second_wall_clock" : 0.4921784639110141
  },
  "failures" : {
    "by_reason" : {
      "CURATOR_INCOMPLETE" : 5
    },
    "count" : 5
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory path; G2 copies the same G1 memory and adds real MemoryCuratorService output.", "Both retrieval groups use the same production SqliteMemoryService hybrid retriever, same embedding provider, query, and top-k settings.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run." ]
}
```
