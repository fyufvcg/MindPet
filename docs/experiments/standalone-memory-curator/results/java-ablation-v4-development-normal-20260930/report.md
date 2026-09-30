# Memory Curator LLM experiment

Dataset: `normal.jsonl` (SHA-256 `a87ef16b7fdace3fbac85ca2b3c10969c1941c9de7f709309b2a80fc027b202b`)

This report is generated from real calls to the production Java KnowledgeGraphService, MemoryCuratorService, MemoryCorpusCompactionService, and SqliteMemoryService. Fixture output is not used as experiment data. Primary effects compare compacted G2C with G1; append-only G2A is an ablation built from the same accepted curator output.

Sample: **4 timelines, 160 memory-input turns, 48 retrieval questions**.

Acceptance profile: **relaxed screening** — **NOT PASSED**. The 30% full-corpus compression target is reported separately and is not implied by a relaxed screening pass. The sample is a screening evaluation, not the complete locked-test cohort.

## Failed answer cases

- timeline-001 / timeline-001-q08 / G1: missing_retrieved_evidence; response: 记忆中记录你已于2025年完成了多期培训，没有提到此前报名的课程被取消。若你指的是其中某一门具体课程，记忆里没有足够信息确认。
- timeline-001 / timeline-001-q08 / G2A: answer_rule_failure; response: 记录显示：课程原计划曾于4月12日取消，但4月26日又安排了参加计划；目前仍未完成。
- timeline-002 / timeline-002-q06 / G2C: missing_retrieved_evidence; response: 搬家前住在北京。

```json
{
  "dataset_version" : [ "memory-curator-ablation-v4" ],
  "selected_timelines" : 4,
  "selected_turns" : 160,
  "selected_queries" : 48,
  "curator_repair_calls" : 12,
  "answer_queries" : 48,
  "answer_calls_expected" : 144,
  "fact_quality" : {
    "true_positive" : 20,
    "false_positive" : 94,
    "false_negative" : 92,
    "precision" : 0.17543859649122806,
    "recall" : 0.17857142857142858,
    "f1" : 0.17699115044247785,
    "stored_fact_rows" : 114,
    "exact_match_duplicate_rows" : 0,
    "exact_match_duplicate_rate" : 0.0,
    "semantic_duplicate_rows" : 0,
    "semantic_duplicate_rate" : 0.0,
    "distinct_state_interval_rows" : 0,
    "redundant_semantic_rows" : 0,
    "redundant_semantic_rate" : 0.0,
    "semantic_unique_fact_quality" : {
      "precision" : 0.17543859649122806,
      "semantic_duplicate_rate" : 0.0,
      "false_negative" : 92,
      "true_positive" : 20,
      "false_positive" : 94,
      "recall" : 0.17857142857142858,
      "f1" : 0.17699115044247785,
      "semantic_duplicate_rows" : 0,
      "matching_policy" : "predicate/value/scope/assertion; temporal fields are evaluated separately"
    },
    "strict_row_matching_policy" : "predicate/value/scope/assertion plus expected time status and resolved start date; non-overlapping state intervals are valid repeated rows; recall uses unique matched gold; unmatched rows are not necessarily hallucinations",
    "semantic_repetition_policy" : "semantic_duplicate_rows counts repeated meanings including legitimate state returns; distinct_state_interval_rows and redundant_semantic_rows separate them; intervals lacking provable non-overlap remain redundant",
    "by_timeline" : [ {
      "primary_scenario" : "memory_ablation",
      "timeline_id" : "timeline-001",
      "expected" : 28,
      "stored" : 30,
      "true_positive" : 9,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 9,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "memory_ablation",
      "timeline_id" : "timeline-002",
      "expected" : 28,
      "stored" : 28,
      "true_positive" : 8,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 8,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "memory_ablation",
      "timeline_id" : "timeline-003",
      "expected" : 28,
      "stored" : 28,
      "true_positive" : 2,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 2,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "memory_ablation",
      "timeline_id" : "timeline-004",
      "expected" : 28,
      "stored" : 28,
      "true_positive" : 1,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 1,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    } ]
  },
  "g1_kg_fact_quality" : {
    "precision" : 0.05263157894736842,
    "recall" : 0.0625,
    "by_timeline" : [ {
      "unmatched_kg_relations" : 30,
      "timeline_id" : "timeline-001",
      "matched_gold" : 1,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 32,
      "timeline_id" : "timeline-002",
      "matched_gold" : 3,
      "duplicate_relations" : 1
    }, {
      "unmatched_kg_relations" : 33,
      "timeline_id" : "timeline-003",
      "matched_gold" : 1,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 31,
      "timeline_id" : "timeline-004",
      "matched_gold" : 2,
      "duplicate_relations" : 1
    } ],
    "method" : "Conservative KG relation projection by visible text; unsupported relations remain unmatched. No source-turn label inheritance."
  },
  "profile_quality" : {
    "fully_correct_timelines" : 1,
    "expected_entries_correct" : 7,
    "recall" : 0.4375,
    "precision" : 1.0,
    "written_entries" : 7,
    "expected_entries" : 16,
    "by_timeline" : [ {
      "actual" : {
        "occupation_current" : "产品经理",
        "current_location" : "广州",
        "current_project" : "校园服务平台"
      },
      "correct" : 3,
      "expected" : {
        "home_location" : "苏州",
        "current_location" : "广州",
        "occupation_current" : "产品经理",
        "current_project" : "校园服务平台"
      },
      "timeline_id" : "timeline-001",
      "expected_count" : 4
    }, {
      "actual" : {
        "home_location" : "杭州",
        "occupation_current" : "软件工程师",
        "current_location" : "北京",
        "current_project" : "门店库存改造项目"
      },
      "correct" : 4,
      "expected" : {
        "home_location" : "杭州",
        "current_location" : "北京",
        "occupation_current" : "软件工程师",
        "current_project" : "门店库存改造项目"
      },
      "timeline_id" : "timeline-002",
      "expected_count" : 4
    }, {
      "actual" : { },
      "correct" : 0,
      "expected" : {
        "home_location" : "成都",
        "current_location" : "合肥",
        "occupation_current" : "中学教师",
        "current_project" : "课程预约系统"
      },
      "timeline_id" : "timeline-003",
      "expected_count" : 4
    }, {
      "actual" : { },
      "correct" : 0,
      "expected" : {
        "home_location" : "西安",
        "current_location" : "昆明",
        "occupation_current" : "交互设计师",
        "current_project" : "客户反馈看板"
      },
      "timeline_id" : "timeline-004",
      "expected_count" : 4
    } ]
  },
  "retention" : {
    "retention_at_1" : 0.26785714285714285,
    "retention_at_3" : 0.2857142857142857,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 56,
    "eligible_facts_at_3" : 56,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "actual_g1_retrieval_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 2145,
        "knowledge_graph_relation" : 1171,
        "ordinary_long_term_memory" : 1551
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 128,
        "knowledge_graph_relation" : 136,
        "ordinary_long_term_memory" : 127
      },
      "unit_count" : 391,
      "estimated_tokens" : 4867
    },
    "actual_g2a_append_retrieval_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 1321,
        "knowledge_graph_entity" : 2145,
        "knowledge_graph_relation" : 1171,
        "ordinary_long_term_memory" : 1551,
        "working_memory" : 100
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 141,
        "knowledge_graph_entity" : 128,
        "knowledge_graph_relation" : 136,
        "ordinary_long_term_memory" : 127,
        "working_memory" : 4
      },
      "unit_count" : 536,
      "estimated_tokens" : 6288
    },
    "actual_g2c_compacted_retrieval_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 1138,
        "curated_knowledge_graph_entity" : 2145,
        "curated_knowledge_graph_relation" : 1171,
        "curated_residual_memory" : 66,
        "ordinary_long_term_memory" : 131
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 114,
        "curated_knowledge_graph_entity" : 128,
        "curated_knowledge_graph_relation" : 136,
        "curated_residual_memory" : 7,
        "ordinary_long_term_memory" : 10
      },
      "unit_count" : 395,
      "estimated_tokens" : 4651
    },
    "actual_g2a_token_delta_vs_g1" : 0.2919663036778303,
    "actual_g2c_token_delta_vs_g1" : -0.04438052188206287,
    "actual_g2c_system_compression_rate" : 0.04438052188206287,
    "g2c_compression_vs_g2a" : 0.26033715012722647,
    "common_kg_tokens" : 3316,
    "memory_layer_compression_vs_g2a" : 0.550807537012113,
    "memory_layer_compression_vs_g1" : 0.13926499032882012,
    "actual_g2c_token_delta_vs_g2a" : -0.26033715012722647,
    "g2c_compaction_action_counts" : {
      "KEEP" : 119,
      "MERGE" : 152,
      "RESIDUAL" : 7,
      "RESOLVE" : 157,
      "SUPERSEDE" : 5
    },
    "g2c_logged_net_token_reduction" : 150,
    "raw_turn_text_tokens_estimated" : 1850,
    "curated_fact_projection_tokens_estimated" : 1689,
    "curated_fact_projection_compression_estimate" : 0.08702702702702703,
    "raw_turn_text_characters" : 2574,
    "curated_fact_projection_characters" : 4836,
    "curator_eligible_compression_rate" : 0.13926499032882012,
    "information_retention_rate" : 0.4380952380952381,
    "projection_note" : "Serving corpus is the union of default, historical, and planned searchable units. KG is identical across arms. Physical storage and archived evidence are reported separately.",
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
    "stored_facts" : 114,
    "valid_source_turns" : 114,
    "source_completeness" : 1.0,
    "verbatim_evidence" : 114,
    "verbatim_evidence_rate" : 1.0,
    "active_retrieval_units" : 395,
    "retrieval_unit_source_traceability" : 395,
    "retrieval_unit_source_traceability_rate" : 1.0
  },
  "integrity" : {
    "by_timeline" : [ {
      "timeline_id" : "timeline-001",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 1,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 20,
      "g2c_mean_duplicate_top10_occupancy" : 1.6666666666666667,
      "duplicate_top10_pass" : true,
      "pass" : false
    }, {
      "timeline_id" : "timeline-002",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 27,
      "g2c_mean_duplicate_top10_occupancy" : 2.25,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-003",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 4,
      "g2c_mean_duplicate_top10_occupancy" : 0.3333333333333333,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-004",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 8,
      "g2c_mean_duplicate_top10_occupancy" : 0.6666666666666666,
      "duplicate_top10_pass" : true,
      "pass" : true
    } ],
    "pass" : false
  },
  "physical_storage_by_table" : {
    "G2" : {
      "allocated_pages" : [ {
        "name" : "conversation_memory",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "curator_accepted_event",
        "allocated_bytes" : 73728,
        "payload_bytes" : 62590
      }, {
        "name" : "curator_proposal_item",
        "allocated_bytes" : 90112,
        "payload_bytes" : 76376
      }, {
        "name" : "curator_runs",
        "allocated_bytes" : 4096,
        "payload_bytes" : 3057
      }, {
        "name" : "curator_state",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1524
      }, {
        "name" : "curator_turns",
        "allocated_bytes" : 45056,
        "payload_bytes" : 33514
      }, {
        "name" : "emotion_history",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_conversation_memory_recent",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_curator_proposal_pending",
        "allocated_bytes" : 12288,
        "payload_bytes" : 4361
      }, {
        "name" : "idx_curator_turns_pending",
        "allocated_bytes" : 12288,
        "payload_bytes" : 7712
      }, {
        "name" : "idx_curator_turns_user_completed",
        "allocated_bytes" : 16384,
        "payload_bytes" : 7990
      }, {
        "name" : "idx_curator_turns_user_seq",
        "allocated_bytes" : 4096,
        "payload_bytes" : 3424
      }, {
        "name" : "idx_emotion_history_recent",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_kg_entity_user_importance",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_kg_evidence_user_created",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_kg_relation_user_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_kg_relation_user_target",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_llm_growth_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_ltm_user_created",
        "allocated_bytes" : 49152,
        "payload_bytes" : 37488
      }, {
        "name" : "idx_ltm_user_importance",
        "allocated_bytes" : 57344,
        "payload_bytes" : 45831
      }, {
        "name" : "idx_ltm_user_searchable",
        "allocated_bytes" : 49152,
        "payload_bytes" : 38415
      }, {
        "name" : "idx_memory_compaction_batch_user_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 374
      }, {
        "name" : "idx_memory_compaction_log_user_batch",
        "allocated_bytes" : 20480,
        "payload_bytes" : 13883
      }, {
        "name" : "idx_memory_fact_idempotency",
        "allocated_bytes" : 16384,
        "payload_bytes" : 11151
      }, {
        "name" : "idx_memory_fact_user_predicate_status",
        "allocated_bytes" : 12288,
        "payload_bytes" : 4551
      }, {
        "name" : "idx_memory_fact_user_source",
        "allocated_bytes" : 12288,
        "payload_bytes" : 4559
      }, {
        "name" : "idx_memory_gallery_user_event",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_gallery_user_session_created",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_retrieval_fact",
        "allocated_bytes" : 16384,
        "payload_bytes" : 10737
      }, {
        "name" : "idx_memory_retrieval_source_turn",
        "allocated_bytes" : 40960,
        "payload_bytes" : 28347
      }, {
        "name" : "idx_memory_retrieval_user_status",
        "allocated_bytes" : 28672,
        "payload_bytes" : 18708
      }, {
        "name" : "idx_profile_current_user_updated",
        "allocated_bytes" : 4096,
        "payload_bytes" : 272
      }, {
        "name" : "idx_rpa_artifacts_run",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_rpa_events_run_seq",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_rpa_runs_workflow_created",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_session_messages_order",
        "allocated_bytes" : 12288,
        "payload_bytes" : 7052
      }, {
        "name" : "idx_sessions_user_updated",
        "allocated_bytes" : 4096,
        "payload_bytes" : 799
      }, {
        "name" : "kg_entity",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "kg_evidence",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "kg_relation",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "kg_turn_ingest",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "llm_growth",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "local_cache",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "long_term_memory",
        "allocated_bytes" : 4276224,
        "payload_bytes" : 3963731
      }, {
        "name" : "memory_compaction_batch",
        "allocated_bytes" : 4096,
        "payload_bytes" : 843
      }, {
        "name" : "memory_compaction_blob",
        "allocated_bytes" : 442368,
        "payload_bytes" : 391510
      }, {
        "name" : "memory_compaction_log",
        "allocated_bytes" : 98304,
        "payload_bytes" : 88217
      }, {
        "name" : "memory_compaction_plan",
        "allocated_bytes" : 28672,
        "payload_bytes" : 21102
      }, {
        "name" : "memory_compaction_snapshot",
        "allocated_bytes" : 282624,
        "payload_bytes" : 253945
      }, {
        "name" : "memory_corpus_migration",
        "allocated_bytes" : 4096,
        "payload_bytes" : 156
      }, {
        "name" : "memory_fact",
        "allocated_bytes" : 40960,
        "payload_bytes" : 32227
      }, {
        "name" : "memory_gallery",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_retrieval_source",
        "allocated_bytes" : 204800,
        "payload_bytes" : 184547
      }, {
        "name" : "memory_retrieval_unit",
        "allocated_bytes" : 2334720,
        "payload_bytes" : 2194800
      }, {
        "name" : "rpa_artifacts",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "rpa_run_events",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "rpa_runs",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "schema_version",
        "allocated_bytes" : 4096,
        "payload_bytes" : 110
      }, {
        "name" : "session_messages",
        "allocated_bytes" : 61440,
        "payload_bytes" : 52186
      }, {
        "name" : "sessions",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1660
      }, {
        "name" : "sqlite_autoindex_curator_accepted_event_1",
        "allocated_bytes" : 12288,
        "payload_bytes" : 4300
      }, {
        "name" : "sqlite_autoindex_curator_proposal_item_1",
        "allocated_bytes" : 12288,
        "payload_bytes" : 4424
      }, {
        "name" : "sqlite_autoindex_curator_state_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 75
      }, {
        "name" : "sqlite_autoindex_curator_turns_1",
        "allocated_bytes" : 12288,
        "payload_bytes" : 6432
      }, {
        "name" : "sqlite_autoindex_kg_entity_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_kg_entity_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_kg_evidence_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_kg_evidence_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_kg_relation_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_kg_relation_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_kg_turn_ingest_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_local_cache_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_compaction_batch_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 254
      }, {
        "name" : "sqlite_autoindex_memory_compaction_blob_1",
        "allocated_bytes" : 12288,
        "payload_bytes" : 6486
      }, {
        "name" : "sqlite_autoindex_memory_compaction_plan_1",
        "allocated_bytes" : 16384,
        "payload_bytes" : 9706
      }, {
        "name" : "sqlite_autoindex_memory_compaction_snapshot_1",
        "allocated_bytes" : 135168,
        "payload_bytes" : 108826
      }, {
        "name" : "sqlite_autoindex_memory_corpus_migration_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 75
      }, {
        "name" : "sqlite_autoindex_memory_fact_1",
        "allocated_bytes" : 16384,
        "payload_bytes" : 11151
      }, {
        "name" : "sqlite_autoindex_memory_gallery_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_gallery_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_retrieval_source_1",
        "allocated_bytes" : 94208,
        "payload_bytes" : 76003
      }, {
        "name" : "sqlite_autoindex_memory_retrieval_unit_1",
        "allocated_bytes" : 28672,
        "payload_bytes" : 20832
      }, {
        "name" : "sqlite_autoindex_rpa_artifacts_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_rpa_run_events_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_rpa_run_events_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_rpa_runs_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_session_messages_1",
        "allocated_bytes" : 16384,
        "payload_bytes" : 10112
      }, {
        "name" : "sqlite_autoindex_sessions_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 839
      }, {
        "name" : "sqlite_autoindex_user_insight_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_user_profile_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_user_profile_current_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 250
      }, {
        "name" : "sqlite_schema",
        "allocated_bytes" : 28672,
        "payload_bytes" : 19360
      }, {
        "name" : "sqlite_sequence",
        "allocated_bytes" : 4096,
        "payload_bytes" : 96
      }, {
        "name" : "uq_memory_retrieval_active_key",
        "allocated_bytes" : 40960,
        "payload_bytes" : 30230
      }, {
        "name" : "user_insight",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "user_profile",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "user_profile_current",
        "allocated_bytes" : 4096,
        "payload_bytes" : 631
      } ],
      "status" : "measured",
      "note" : "Page allocation includes table/index overhead; whole database plus WAL/SHM is reported separately."
    },
    "G1" : {
      "allocated_pages" : [ {
        "name" : "conversation_memory",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "curator_accepted_event",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "curator_proposal_item",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "curator_runs",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "curator_state",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "curator_turns",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "emotion_history",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_conversation_memory_recent",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_curator_proposal_pending",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_curator_turns_pending",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_curator_turns_user_completed",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_curator_turns_user_seq",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_emotion_history_recent",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_kg_entity_user_importance",
        "allocated_bytes" : 12288,
        "payload_bytes" : 6308
      }, {
        "name" : "idx_kg_evidence_user_created",
        "allocated_bytes" : 20480,
        "payload_bytes" : 13232
      }, {
        "name" : "idx_kg_relation_user_source",
        "allocated_bytes" : 12288,
        "payload_bytes" : 7624
      }, {
        "name" : "idx_kg_relation_user_target",
        "allocated_bytes" : 12288,
        "payload_bytes" : 7624
      }, {
        "name" : "idx_llm_growth_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_ltm_user_created",
        "allocated_bytes" : 24576,
        "payload_bytes" : 15512
      }, {
        "name" : "idx_ltm_user_importance",
        "allocated_bytes" : 28672,
        "payload_bytes" : 19031
      }, {
        "name" : "idx_ltm_user_searchable",
        "allocated_bytes" : 24576,
        "payload_bytes" : 15903
      }, {
        "name" : "idx_memory_compaction_batch_user_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_compaction_log_user_batch",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_fact_idempotency",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_fact_user_predicate_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_fact_user_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_gallery_user_event",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_gallery_user_session_created",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_retrieval_fact",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_retrieval_source_turn",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_retrieval_user_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_profile_current_user_updated",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_rpa_artifacts_run",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_rpa_events_run_seq",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_rpa_runs_workflow_created",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_session_messages_order",
        "allocated_bytes" : 12288,
        "payload_bytes" : 7052
      }, {
        "name" : "idx_sessions_user_updated",
        "allocated_bytes" : 4096,
        "payload_bytes" : 799
      }, {
        "name" : "kg_entity",
        "allocated_bytes" : 598016,
        "payload_bytes" : 549592
      }, {
        "name" : "kg_evidence",
        "allocated_bytes" : 81920,
        "payload_bytes" : 72754
      }, {
        "name" : "kg_relation",
        "allocated_bytes" : 32768,
        "payload_bytes" : 26283
      }, {
        "name" : "kg_turn_ingest",
        "allocated_bytes" : 28672,
        "payload_bytes" : 20538
      }, {
        "name" : "llm_growth",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "local_cache",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "long_term_memory",
        "allocated_bytes" : 1806336,
        "payload_bytes" : 1671956
      }, {
        "name" : "memory_compaction_batch",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_compaction_blob",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_compaction_log",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_compaction_plan",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_compaction_snapshot",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_corpus_migration",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_fact",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_gallery",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_retrieval_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_retrieval_unit",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "rpa_artifacts",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "rpa_run_events",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "rpa_runs",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "schema_version",
        "allocated_bytes" : 4096,
        "payload_bytes" : 110
      }, {
        "name" : "session_messages",
        "allocated_bytes" : 61440,
        "payload_bytes" : 52186
      }, {
        "name" : "sessions",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1660
      }, {
        "name" : "sqlite_autoindex_curator_accepted_event_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_curator_proposal_item_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_curator_state_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_curator_turns_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_kg_entity_1",
        "allocated_bytes" : 12288,
        "payload_bytes" : 5284
      }, {
        "name" : "sqlite_autoindex_kg_entity_2",
        "allocated_bytes" : 12288,
        "payload_bytes" : 5307
      }, {
        "name" : "sqlite_autoindex_kg_evidence_1",
        "allocated_bytes" : 36864,
        "payload_bytes" : 29634
      }, {
        "name" : "sqlite_autoindex_kg_evidence_2",
        "allocated_bytes" : 36864,
        "payload_bytes" : 29562
      }, {
        "name" : "sqlite_autoindex_kg_relation_1",
        "allocated_bytes" : 12288,
        "payload_bytes" : 5448
      }, {
        "name" : "sqlite_autoindex_kg_relation_2",
        "allocated_bytes" : 20480,
        "payload_bytes" : 14108
      }, {
        "name" : "sqlite_autoindex_kg_turn_ingest_1",
        "allocated_bytes" : 16384,
        "payload_bytes" : 11072
      }, {
        "name" : "sqlite_autoindex_local_cache_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_compaction_batch_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_compaction_blob_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_compaction_plan_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_compaction_snapshot_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_corpus_migration_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_fact_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_gallery_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_gallery_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_retrieval_source_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_memory_retrieval_unit_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_rpa_artifacts_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_rpa_run_events_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_rpa_run_events_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_rpa_runs_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_session_messages_1",
        "allocated_bytes" : 16384,
        "payload_bytes" : 10112
      }, {
        "name" : "sqlite_autoindex_sessions_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 839
      }, {
        "name" : "sqlite_autoindex_user_insight_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_user_profile_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_autoindex_user_profile_current_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "sqlite_schema",
        "allocated_bytes" : 28672,
        "payload_bytes" : 19360
      }, {
        "name" : "sqlite_sequence",
        "allocated_bytes" : 4096,
        "payload_bytes" : 37
      }, {
        "name" : "uq_memory_retrieval_active_key",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "user_insight",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "user_profile",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "user_profile_current",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      } ],
      "status" : "measured",
      "note" : "Page allocation includes table/index overhead; whole database plus WAL/SHM is reported separately."
    }
  },
  "sensitive_content" : {
    "synthetic_sensitive_cases" : 0,
    "fully_blocked" : 0,
    "blocking_rate" : null,
    "by_timeline" : [ ],
    "scanned_tables" : [ "long_term_memory", "memory_fact", "user_profile_current", "user_insight", "llm_growth", "memory_retrieval_unit", "memory_retrieval_source", "kg_entity", "kg_relation", "kg_evidence" ]
  },
  "retrieval" : {
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.95,
        "recall_at_10" : 0.95,
        "precision_at_5" : 0.19166666666666668,
        "precision_at_10" : 0.09583333333333334,
        "mrr" : 0.8625,
        "ndcg_at_10" : 0.8673858807758835,
        "recall_at_256_estimated_tokens" : 0.95,
        "recall_at_512_estimated_tokens" : 0.95,
        "recall_at_1024_estimated_tokens" : 0.95,
        "mean_query_context_tokens" : 117.125,
        "mean_duplicate_top10_occupancy" : 3.2291666666666665
      },
      "G2A" : {
        "recall_at_5" : 0.95,
        "recall_at_10" : 1.0,
        "precision_at_5" : 0.18333333333333335,
        "precision_at_10" : 0.10000000000000002,
        "mrr" : 0.9125,
        "ndcg_at_10" : 0.9173270991440046,
        "recall_at_256_estimated_tokens" : 1.0,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0,
        "mean_query_context_tokens" : 113.4375,
        "mean_duplicate_top10_occupancy" : 3.2708333333333335
      },
      "G2C" : {
        "recall_at_5" : 0.775,
        "recall_at_10" : 0.8125,
        "precision_at_5" : 0.15,
        "precision_at_10" : 0.08125,
        "mrr" : 0.7291666666666667,
        "ndcg_at_10" : 0.7475764194591933,
        "recall_at_256_estimated_tokens" : 0.8125,
        "recall_at_512_estimated_tokens" : 0.8125,
        "recall_at_1024_estimated_tokens" : 0.8125,
        "mean_query_context_tokens" : 104.72916666666667,
        "mean_duplicate_top10_occupancy" : 1.2291666666666667
      }
    },
    "query_count" : 48,
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : -0.175,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : -0.36249999999999993,
        "upper" : 0.012499999999999983,
        "cluster_count" : 4
      },
      "delta_recall_at_10_g2c_minus_g1" : -0.1375,
      "delta_recall_at_10_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : -0.2875,
        "upper" : 0.024999999999999967,
        "cluster_count" : 4
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : -0.1375,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : -0.2875,
        "upper" : 0.024999999999999967,
        "cluster_count" : 4
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : -2.7755575615628914E-17,
      "g2a_ablation_delta_recall_at_10_vs_g1" : 0.04999999999999999,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.04999999999999999,
      "g2c_minus_g2a_recall_at_5" : -0.17499999999999993,
      "g2c_minus_g2a_recall_at_10" : -0.1875,
      "primary_metrics" : [ "Delta Recall@5", "Delta Recall@10", "Delta recall at estimated 512 tokens" ]
    },
    "token_budget_method" : "estimated; see run-config.json"
  },
  "shared_ranker_retrieval" : {
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.95,
        "recall_at_10" : 0.95,
        "precision_at_5" : 0.19166666666666668,
        "precision_at_10" : 0.09583333333333334,
        "mrr" : 0.8625,
        "ndcg_at_10" : 0.8673858807758835,
        "recall_at_256_estimated_tokens" : 0.95,
        "recall_at_512_estimated_tokens" : 0.95,
        "recall_at_1024_estimated_tokens" : 0.95,
        "mean_query_context_tokens" : 117.125,
        "mean_duplicate_top10_occupancy" : 3.2291666666666665
      },
      "G2A" : {
        "recall_at_5" : 0.95,
        "recall_at_10" : 1.0,
        "precision_at_5" : 0.18333333333333335,
        "precision_at_10" : 0.10000000000000002,
        "mrr" : 0.9125,
        "ndcg_at_10" : 0.9173270991440046,
        "recall_at_256_estimated_tokens" : 1.0,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0,
        "mean_query_context_tokens" : 113.4375,
        "mean_duplicate_top10_occupancy" : 3.2708333333333335
      },
      "G2C" : {
        "recall_at_5" : 0.775,
        "recall_at_10" : 0.8125,
        "precision_at_5" : 0.15,
        "precision_at_10" : 0.08125,
        "mrr" : 0.7291666666666667,
        "ndcg_at_10" : 0.7475764194591933,
        "recall_at_256_estimated_tokens" : 0.8125,
        "recall_at_512_estimated_tokens" : 0.8125,
        "recall_at_1024_estimated_tokens" : 0.8125,
        "mean_query_context_tokens" : 104.72916666666667,
        "mean_duplicate_top10_occupancy" : 1.2291666666666667
      }
    },
    "query_count" : 48,
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : -0.175,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : -0.36249999999999993,
        "upper" : 0.012499999999999983,
        "cluster_count" : 4
      },
      "delta_recall_at_10_g2c_minus_g1" : -0.1375,
      "delta_recall_at_10_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : -0.2875,
        "upper" : 0.024999999999999967,
        "cluster_count" : 4
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : -0.1375,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : -0.2875,
        "upper" : 0.024999999999999967,
        "cluster_count" : 4
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : -2.7755575615628914E-17,
      "g2a_ablation_delta_recall_at_10_vs_g1" : 0.04999999999999999,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.04999999999999999,
      "g2c_minus_g2a_recall_at_5" : -0.17499999999999993,
      "g2c_minus_g2a_recall_at_10" : -0.1875,
      "primary_metrics" : [ "Delta Recall@5", "Delta Recall@10", "Delta recall at estimated 512 tokens" ]
    },
    "token_budget_method" : "estimated; see run-config.json"
  },
  "production_retrieval" : {
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.675,
        "recall_at_10" : 0.95,
        "precision_at_5" : 0.125,
        "precision_at_10" : 0.08958333333333333,
        "mrr" : 0.5709623015873015,
        "ndcg_at_10" : 0.6251515096889659,
        "recall_at_256_estimated_tokens" : 0.9,
        "recall_at_512_estimated_tokens" : 0.95,
        "recall_at_1024_estimated_tokens" : 0.95,
        "mean_query_context_tokens" : 117.54166666666667,
        "mean_duplicate_top10_occupancy" : 3.6666666666666665
      },
      "G2A" : {
        "recall_at_5" : 0.725,
        "recall_at_10" : 1.0,
        "precision_at_5" : 0.1375,
        "precision_at_10" : 0.09375,
        "mrr" : 0.6252678571428572,
        "ndcg_at_10" : 0.6755017074003542,
        "recall_at_256_estimated_tokens" : 0.975,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0,
        "mean_query_context_tokens" : 114.5,
        "mean_duplicate_top10_occupancy" : 3.375
      },
      "G2C" : {
        "recall_at_5" : 0.7125,
        "recall_at_10" : 0.8,
        "precision_at_5" : 0.1416666666666667,
        "precision_at_10" : 0.07916666666666668,
        "mrr" : 0.49419642857142854,
        "ndcg_at_10" : 0.5773957139132463,
        "recall_at_256_estimated_tokens" : 0.7875,
        "recall_at_512_estimated_tokens" : 0.8,
        "recall_at_1024_estimated_tokens" : 0.8,
        "mean_query_context_tokens" : 114.79166666666667,
        "mean_duplicate_top10_occupancy" : 0.8125
      }
    },
    "query_count" : 48,
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : 0.037500000000000006,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : -0.175,
        "upper" : 0.25,
        "cluster_count" : 4
      },
      "delta_recall_at_10_g2c_minus_g1" : -0.15000000000000002,
      "delta_recall_at_10_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : -0.325,
        "upper" : 0.024999999999999967,
        "cluster_count" : 4
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : -0.15000000000000002,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "resamples" : 10000,
        "lower" : -0.325,
        "upper" : 0.024999999999999967,
        "cluster_count" : 4
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : 0.04999999999999996,
      "g2a_ablation_delta_recall_at_10_vs_g1" : 0.04999999999999999,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.04999999999999999,
      "g2c_minus_g2a_recall_at_5" : -0.012499999999999956,
      "g2c_minus_g2a_recall_at_10" : -0.19999999999999996,
      "primary_metrics" : [ "Delta Recall@5", "Delta Recall@10", "Delta recall at estimated 512 tokens" ]
    },
    "token_budget_method" : "estimated; see run-config.json"
  },
  "acceptance_profile" : "relaxed",
  "end_to_end" : {
    "by_query_type" : {
      "current_state" : {
        "G1" : {
          "judge_score" : 0.9583333333333334,
          "total" : 12,
          "core_score" : 1.0
        },
        "G2A" : {
          "judge_score" : 1.0,
          "total" : 12,
          "core_score" : 1.0
        },
        "G2C" : {
          "judge_score" : 1.0,
          "total" : 12,
          "core_score" : 1.0
        }
      },
      "preference" : {
        "G1" : {
          "judge_score" : 0.75,
          "total" : 8,
          "core_score" : 1.0
        },
        "G2A" : {
          "judge_score" : 0.625,
          "total" : 8,
          "core_score" : 1.0
        },
        "G2C" : {
          "judge_score" : 0.75,
          "total" : 8,
          "core_score" : 1.0
        }
      },
      "historical" : {
        "G1" : {
          "judge_score" : 1.0,
          "total" : 4,
          "core_score" : 1.0
        },
        "G2A" : {
          "judge_score" : 1.0,
          "total" : 4,
          "core_score" : 1.0
        },
        "G2C" : {
          "judge_score" : 0.75,
          "total" : 4,
          "core_score" : 0.75
        }
      },
      "plan_vs_reality" : {
        "G1" : {
          "judge_score" : 1.0,
          "total" : 4,
          "core_score" : 1.0
        },
        "G2A" : {
          "judge_score" : 1.0,
          "total" : 4,
          "core_score" : 1.0
        },
        "G2C" : {
          "judge_score" : 1.0,
          "total" : 4,
          "core_score" : 1.0
        }
      },
      "cancelled_or_completed" : {
        "G1" : {
          "judge_score" : 0.5,
          "total" : 4,
          "core_score" : 0.875
        },
        "G2A" : {
          "judge_score" : 0.25,
          "total" : 4,
          "core_score" : 0.875
        },
        "G2C" : {
          "judge_score" : 1.0,
          "total" : 4,
          "core_score" : 1.0
        }
      },
      "multi_fact" : {
        "G1" : {
          "judge_score" : 1.0,
          "total" : 8,
          "core_score" : 1.0
        },
        "G2A" : {
          "judge_score" : 1.0,
          "total" : 8,
          "core_score" : 1.0
        },
        "G2C" : {
          "judge_score" : 0.8125,
          "total" : 8,
          "core_score" : 1.0
        }
      },
      "unsupported_abstention" : {
        "G1" : {
          "judge_score" : 1.0,
          "total" : 8,
          "core_score" : 1.0
        },
        "G2A" : {
          "judge_score" : 1.0,
          "total" : 8,
          "core_score" : 1.0
        },
        "G2C" : {
          "judge_score" : 1.0,
          "total" : 8,
          "core_score" : 1.0
        }
      }
    },
    "by_group" : {
      "G1" : {
        "correct" : 47,
        "total" : 48,
        "accuracy" : 0.9791666666666666,
        "core_full_accuracy" : 0.9791666666666666,
        "mean_core_answer_score" : 0.9895833333333334,
        "judge_scored_answers" : 48,
        "judge_failures" : 0,
        "judge_accuracy" : 0.8958333333333334,
        "judge_mean_score" : 0.90625,
        "approximate_correct" : 47,
        "approximate_accuracy" : 0.9791666666666666,
        "mean_core_fact_coverage_rate" : 0.9895833333333334,
        "abstention_correct" : 8,
        "abstention_total" : 8,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 289,
        "state_confusion_rate" : 0.0,
        "evidence_support_rate" : 0.9583333333333334,
        "mean_prompt_tokens_reported" : 375.0,
        "mean_latency_ms" : 3992
      },
      "G2A" : {
        "correct" : 47,
        "total" : 48,
        "accuracy" : 0.9791666666666666,
        "core_full_accuracy" : 0.9791666666666666,
        "mean_core_answer_score" : 0.9895833333333334,
        "judge_scored_answers" : 48,
        "judge_failures" : 0,
        "judge_accuracy" : 0.875,
        "judge_mean_score" : 0.875,
        "approximate_correct" : 47,
        "approximate_accuracy" : 0.9791666666666666,
        "mean_core_fact_coverage_rate" : 0.9895833333333334,
        "abstention_correct" : 8,
        "abstention_total" : 8,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 286,
        "state_confusion_rate" : 0.0,
        "evidence_support_rate" : 1.0,
        "mean_prompt_tokens_reported" : 372.5625,
        "mean_latency_ms" : 3435
      },
      "G2C" : {
        "correct" : 47,
        "total" : 48,
        "accuracy" : 0.9791666666666666,
        "core_full_accuracy" : 0.9791666666666666,
        "mean_core_answer_score" : 0.9791666666666666,
        "judge_scored_answers" : 48,
        "judge_failures" : 0,
        "judge_accuracy" : 0.8958333333333334,
        "judge_mean_score" : 0.90625,
        "approximate_correct" : 47,
        "approximate_accuracy" : 0.9791666666666666,
        "mean_core_fact_coverage_rate" : 0.9791666666666666,
        "abstention_correct" : 8,
        "abstention_total" : 8,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 281,
        "state_confusion_rate" : 0.0,
        "evidence_support_rate" : 0.8125,
        "mean_prompt_tokens_reported" : 367.1666666666667,
        "mean_latency_ms" : 3505
      }
    },
    "paired" : {
      "primary_comparison" : "G2C minus G1",
      "g2c_minus_g1_accuracy" : 0.0,
      "g1_only_approximate_correct" : 1,
      "g2c_only_approximate_correct" : 1,
      "g2c_minus_g1_approximate_accuracy" : 0.0,
      "mcnemar_g1_only_correct" : 1,
      "mcnemar_g2_only_correct" : 1,
      "mcnemar_p_value_approx" : 0.47950009227675744,
      "inference_status" : "diagnostic_query_pair_test"
    },
    "all_paired_comparisons" : {
      "G2A_minus_G1" : {
        "delta_judge_score" : -0.03125,
        "judge_score_complete_pairs" : 48,
        "delta_core_answer_score" : 0.0,
        "core_answer_score_complete_pairs" : 48
      },
      "G2C_minus_G1" : {
        "delta_judge_score" : 0.0,
        "judge_score_complete_pairs" : 48,
        "delta_core_answer_score" : -0.010416666666666666,
        "core_answer_score_complete_pairs" : 48
      },
      "G2C_minus_G2A" : {
        "delta_judge_score" : 0.03125,
        "judge_score_complete_pairs" : 48,
        "delta_core_answer_score" : -0.010416666666666666,
        "core_answer_score_complete_pairs" : 48
      }
    },
    "answers" : 144
  },
  "answer_failure_cases" : [ {
    "timeline_id" : "timeline-001",
    "query_id" : "timeline-001-q08",
    "group" : "G1",
    "query_type" : "cancelled_or_completed",
    "question" : "此前报名的课程现在取消了还是完成了？",
    "expected_answer" : "第1期数据整理课程；取消",
    "should_abstain" : false,
    "response" : "记忆中记录你已于2025年完成了多期培训，没有提到此前报名的课程被取消。若你指的是其中某一门具体课程，记忆里没有足够信息确认。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.5,
    "correct_by_approximate_rules" : false,
    "core_answer_score" : 0.5,
    "correct_core_full" : false,
    "answer_budget" : 512,
    "evaluation_version" : "memory-eval-v4-core-state",
    "evidence_supported" : false,
    "state_confusion" : false,
    "error_type" : "missing_retrieved_evidence",
    "source_unit_ids" : [ "103", "134", "180", "175", "137", "122", "81", "145", "85", "190" ],
    "context_token_estimate" : 315,
    "context_text" : "[1] 2026-03-15T16:16:00Z 我曾经2025年完成第16期培训。\n[2] 2026-03-29T16:24:00Z 我曾经2025年完成第24期培训。\n[3] 2026-04-26T16:33:00Z 我曾经2025年完成第33期培训。\n[4] 2026-04-12T16:31:00Z 我曾经2025年完成第31期培训。\n[5] 2026-04-12T16:25:00Z 我曾经2025年完成第25期培训。\n[6] 2026-03-29T16:21:00Z 第21期培训：用户于2025年完成了第21期培训；培训主题和主办方未说明。\n[7] 2026-03-15T16:12:00Z 我曾经2025年完成第12期培训。\n[8] 2026-04-12T16:27:00Z 我曾经2025年完成第27期培训。\n[9] 2026-03-15T16:13:00Z 我曾经2025年完成第13期培训。我的朋友住在成都，这不是我的住址。\n[10] 2026-04-26T16:37:00Z 我曾经2025年完成第37期培训。\n",
    "elapsed_ms" : 8075,
    "error" : "",
    "prompt_tokens" : 404,
    "completion_tokens" : 101,
    "total_tokens" : 505,
    "judge_score" : 0.0,
    "judge_correct" : false,
    "judge_reason" : "错误称已于2025年完成多期培训，并未识别课程已取消。",
    "judge_anonymous_id" : "A0",
    "judge_error" : ""
  }, {
    "timeline_id" : "timeline-001",
    "query_id" : "timeline-001-q08",
    "group" : "G2A",
    "query_type" : "cancelled_or_completed",
    "question" : "此前报名的课程现在取消了还是完成了？",
    "expected_answer" : "第1期数据整理课程；取消",
    "should_abstain" : false,
    "response" : "记录显示：课程原计划曾于4月12日取消，但4月26日又安排了参加计划；目前仍未完成。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.5,
    "correct_by_approximate_rules" : false,
    "core_answer_score" : 0.5,
    "correct_core_full" : false,
    "answer_budget" : 512,
    "evaluation_version" : "memory-eval-v4-core-state",
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "808", "794", "820", "245", "271", "377", "208", "362", "270", "352" ],
    "context_token_estimate" : 352,
    "context_text" : "[1] 2026-04-12T16:28:00Z 用户已否定或取消原计划：第1期数据整理课程\n[2] 2026-03-15T16:11:00Z 用户已安排计划（尚未完成）：参加第1期数据整理课程\n[3] 2026-04-26T16:40:00Z 用户已安排计划（尚未完成）：参加第1期数据整理课程；用户当前项目：校园服务平台\n[4] 2026-03-29T16:21:00Z 第21期培训：用户于2025年完成了第21期培训；培训主题和主办方未说明。\n[5] 2026-03-29T16:24:00Z 第24期培训：用户于2025年完成了第24期培训；培训名称和领域未说明。\n[6] 2026-04-26T16:36:00Z 第36期培训：用户于2025年完成了第36期培训；培训主题和主办方未说明。\n[7] 2026-03-15T16:16:00Z 我曾经2025年完成第16期培训。\n[8] 2026-04-26T16:33:00Z 我曾经2025年完成第33期培训。\n[9] 2026-03-29T16:24:00Z 我曾经2025年完成第24期培训。\n[10] 2026-04-12T16:31:00Z 我曾经2025年完成第31期培训。\n",
    "elapsed_ms" : 1859,
    "error" : "",
    "prompt_tokens" : 441,
    "completion_tokens" : 174,
    "total_tokens" : 615,
    "judge_score" : 0.0,
    "judge_correct" : false,
    "judge_reason" : "编造日期和后续安排，且错误称目前未完成。",
    "judge_anonymous_id" : "A1",
    "judge_error" : ""
  }, {
    "timeline_id" : "timeline-002",
    "query_id" : "timeline-002-q06",
    "group" : "G2C",
    "query_type" : "historical",
    "question" : "搬家前住在哪座城市？",
    "expected_answer" : "厦门",
    "should_abstain" : false,
    "response" : "搬家前住在北京。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.0,
    "correct_by_approximate_rules" : false,
    "core_answer_score" : 0.0,
    "correct_core_full" : false,
    "answer_budget" : 512,
    "evaluation_version" : "memory-eval-v4-core-state",
    "evidence_supported" : false,
    "state_confusion" : false,
    "error_type" : "missing_retrieved_evidence",
    "source_unit_ids" : [ "6f14d649-a0f2-314d-8a98-498cdda5b230", "3d82182f-8e18-34d0-8fbe-0942ca7457eb", "522d26a2-b512-3e18-aa9f-24c32b2cd1e8", "efdb8d5c-f4ba-3251-b1ec-753c549f1b85", "9fa4816e-9364-3082-a840-d19fcb514d77", "baseline-knowledge_graph_entity-118", "e9875ce9-378e-3ec4-b772-663928660925", "cff4ab42-b76f-34fa-ba30-1079f0000474", "baseline-knowledge_graph_relation-213", "baseline-knowledge_graph_entity-23" ],
    "context_token_estimate" : 258,
    "context_text" : "[1] 2026-03-16T16:10:00Z 用户过去居住城市：北京\n[2] 2026-03-30T16:18:00Z 用户过去居住城市：福州\n[3] 2026-03-02T16:02:00Z 用户过去居住城市：厦门\n[4] 2026-03-02T16:02:00Z 我以前从事项目运营。\n[5] 2026-03-02T16:02:00Z 用户曾任职位：项目运营\n[6] 2026-04-27T16:40:00Z 北京：用户目前居住的城市。\n[7] 2026-04-27T16:40:00Z 用户现居城市：北京\n[8] 2026-03-30T16:20:00Z 用户可能前往城市：长沙\n[9] 2026-03-30T16:20:00Z User —plans→ 搬去长沙\n[10] 2026-03-02T16:02:00Z Xiamen：The user lived in Xiamen before moving.\n",
    "elapsed_ms" : 3606,
    "error" : "",
    "prompt_tokens" : 344,
    "completion_tokens" : 78,
    "total_tokens" : 422,
    "judge_score" : 0.0,
    "judge_correct" : false,
    "judge_reason" : "搬家前住在厦门，不是北京。",
    "judge_anonymous_id" : "A1",
    "judge_error" : ""
  } ],
  "compaction_acceptance" : {
    "strict_30_percent_compression_target" : "Always reported independently as compression_30_percent_target_met; relaxed screening permits up to 10% corpus growth.",
    "by_timeline" : [ {
      "timeline_id" : "timeline-001",
      "sample_profile" : "normal",
      "system_compression_rate" : 0.005291005291005346,
      "oracle_upper_rate" : 0.025573192239858877,
      "oracle_efficiency" : 0.20689655172414032,
      "curator_eligible_compression_rate" : 0.015957446808510634,
      "curator_eligible_oracle_upper_rate" : 0.0771276595744681,
      "curator_eligible_oracle_efficiency" : 0.20689655172413784,
      "information_retention_rate" : 0.5384615384615384,
      "g1_answer_accuracy" : 0.9166666666666666,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 0.9166666666666666,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.08278388278388282,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : false,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : false
    }, {
      "timeline_id" : "timeline-002",
      "sample_profile" : "normal",
      "system_compression_rate" : 0.0436113236419281,
      "oracle_upper_rate" : 0.030604437643458327,
      "oracle_efficiency" : 1.4249999999999996,
      "curator_eligible_compression_rate" : 0.15000000000000002,
      "curator_eligible_oracle_upper_rate" : 0.10526315789473684,
      "curator_eligible_oracle_efficiency" : 1.4250000000000003,
      "information_retention_rate" : 0.46153846153846156,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 0.9166666666666666,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 0.9166666666666666,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.10446685878962547,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : false,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : false
    }, {
      "timeline_id" : "timeline-003",
      "sample_profile" : "normal",
      "system_compression_rate" : 0.056397306397306446,
      "oracle_upper_rate" : 0.02946127946127941,
      "oracle_efficiency" : 1.9142857142857193,
      "curator_eligible_compression_rate" : 0.17402597402597397,
      "curator_eligible_oracle_upper_rate" : 0.09090909090909094,
      "curator_eligible_oracle_efficiency" : 1.914285714285713,
      "information_retention_rate" : 0.38461538461538464,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.10807017543859643,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : false,
      "query_context_pass" : true,
      "recall_pass" : false,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : false
    }, {
      "timeline_id" : "timeline-004",
      "sample_profile" : "normal",
      "system_compression_rate" : 0.06946688206785134,
      "oracle_upper_rate" : 0.037156704361874016,
      "oracle_efficiency" : 1.8695652173913022,
      "curator_eligible_compression_rate" : 0.20975609756097557,
      "curator_eligible_oracle_upper_rate" : 0.1121951219512195,
      "curator_eligible_oracle_efficiency" : 1.8695652173913042,
      "information_retention_rate" : 0.37037037037037035,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.12673130193905813,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : false,
      "query_context_pass" : true,
      "recall_pass" : false,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : false
    } ],
    "oracle_method" : "Conservative feasible corpus estimate using covered clauses, preserving unmatched raw and identical KG; not an exact minimum-cover upper bound or deletion target.",
    "gate_profile" : "relaxed"
  },
  "model_calls" : {
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 160,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "mean_ms" : 3916.51875,
          "p99_ms" : 11919,
          "count" : 160,
          "p95_ms" : 9504,
          "p50_ms" : 2949
        },
        "prompt_tokens_reported" : 70010,
        "completion_tokens_reported" : 41843,
        "total_tokens_reported" : 111853,
        "calls_with_token_usage" : 160
      },
      "curator" : {
        "calls" : 24,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "mean_ms" : 13792.416666666666,
          "p99_ms" : 24280,
          "count" : 24,
          "p95_ms" : 22414,
          "p50_ms" : 12285
        },
        "prompt_tokens_reported" : 117800,
        "completion_tokens_reported" : 63227,
        "total_tokens_reported" : 181027,
        "calls_with_token_usage" : 24
      },
      "answer" : {
        "calls" : 144,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "mean_ms" : 3644.465277777778,
          "p99_ms" : 11592,
          "count" : 144,
          "p95_ms" : 8947,
          "p50_ms" : 2117
        },
        "prompt_tokens_reported" : 53507,
        "completion_tokens_reported" : 4128,
        "total_tokens_reported" : 57635,
        "calls_with_token_usage" : 144
      },
      "judge" : {
        "calls" : 48,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "mean_ms" : 2097.1041666666665,
          "p99_ms" : 7980,
          "count" : 48,
          "p95_ms" : 3314,
          "p50_ms" : 1715
        },
        "prompt_tokens_reported" : 20241,
        "completion_tokens_reported" : 5881,
        "total_tokens_reported" : 26122,
        "calls_with_token_usage" : 48
      }
    },
    "prompt_tokens_reported" : 261558,
    "total_tokens_reported" : 376637,
    "calls_with_token_usage" : 376,
    "calls" : 376,
    "completion_tokens_reported" : 115079
  },
  "system_performance" : {
    "experiment_wall_ms" : 1306175,
    "timeline_count" : 4,
    "baseline_ingestion_per_timeline_ms" : {
      "mean_ms" : 161242.5,
      "p99_ms" : 188486,
      "count" : 4,
      "p95_ms" : 188486,
      "p50_ms" : 137519
    },
    "curator_ingestion_per_timeline_ms" : {
      "mean_ms" : 137942.25,
      "p99_ms" : 185823,
      "count" : 4,
      "p95_ms" : 185823,
      "p50_ms" : 123985
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "mean_ms" : 158214.0,
      "p99_ms" : 185371,
      "count" : 4,
      "p95_ms" : 185371,
      "p50_ms" : 166771
    },
    "query_embedding_latency_ms" : {
      "mean_ms" : 45.916666666666664,
      "p99_ms" : 67,
      "count" : 48,
      "p95_ms" : 60,
      "p50_ms" : 43
    },
    "g1_production_retrieval_search_latency_ms" : {
      "mean_ms" : 4.041666666666667,
      "p99_ms" : 31,
      "count" : 48,
      "p95_ms" : 8,
      "p50_ms" : 3
    },
    "g2a_append_retrieval_search_latency_ms" : {
      "mean_ms" : 3.7291666666666665,
      "p99_ms" : 17,
      "count" : 48,
      "p95_ms" : 6,
      "p50_ms" : 3
    },
    "g2c_compaction_retrieval_search_latency_ms" : {
      "mean_ms" : 51.166666666666664,
      "p99_ms" : 117,
      "count" : 48,
      "p95_ms" : 71,
      "p50_ms" : 49
    },
    "g1_indexed_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 2145,
        "knowledge_graph_relation" : 1171,
        "ordinary_long_term_memory" : 1551
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 128,
        "knowledge_graph_relation" : 136,
        "ordinary_long_term_memory" : 127
      },
      "unit_count" : 391,
      "estimated_tokens" : 4867
    },
    "g2a_indexed_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 1321,
        "knowledge_graph_entity" : 2145,
        "knowledge_graph_relation" : 1171,
        "ordinary_long_term_memory" : 1551,
        "working_memory" : 100
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 141,
        "knowledge_graph_entity" : 128,
        "knowledge_graph_relation" : 136,
        "ordinary_long_term_memory" : 127,
        "working_memory" : 4
      },
      "unit_count" : 536,
      "estimated_tokens" : 6288
    },
    "g2c_indexed_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 1138,
        "curated_knowledge_graph_entity" : 2145,
        "curated_knowledge_graph_relation" : 1171,
        "curated_residual_memory" : 66,
        "ordinary_long_term_memory" : 131
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 114,
        "curated_knowledge_graph_entity" : 128,
        "curated_knowledge_graph_relation" : 136,
        "curated_residual_memory" : 7,
        "ordinary_long_term_memory" : 10
      },
      "unit_count" : 395,
      "estimated_tokens" : 4651
    },
    "g1_sqlite_footprint_bytes" : {
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768,
      "wal_bytes" : 4132392,
      "total_observed_bytes" : 7421480,
      "main_db_bytes" : 3256320
    },
    "g2_sqlite_footprint_bytes" : {
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768,
      "wal_bytes" : 4461992,
      "total_observed_bytes" : 13501864,
      "main_db_bytes" : 9007104
    },
    "llm_calls_by_stage" : {
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 160,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "mean_ms" : 3916.51875,
            "p99_ms" : 11919,
            "count" : 160,
            "p95_ms" : 9504,
            "p50_ms" : 2949
          },
          "prompt_tokens_reported" : 70010,
          "completion_tokens_reported" : 41843,
          "total_tokens_reported" : 111853,
          "calls_with_token_usage" : 160
        },
        "curator" : {
          "calls" : 24,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "mean_ms" : 13792.416666666666,
            "p99_ms" : 24280,
            "count" : 24,
            "p95_ms" : 22414,
            "p50_ms" : 12285
          },
          "prompt_tokens_reported" : 117800,
          "completion_tokens_reported" : 63227,
          "total_tokens_reported" : 181027,
          "calls_with_token_usage" : 24
        },
        "answer" : {
          "calls" : 144,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "mean_ms" : 3644.465277777778,
            "p99_ms" : 11592,
            "count" : 144,
            "p95_ms" : 8947,
            "p50_ms" : 2117
          },
          "prompt_tokens_reported" : 53507,
          "completion_tokens_reported" : 4128,
          "total_tokens_reported" : 57635,
          "calls_with_token_usage" : 144
        },
        "judge" : {
          "calls" : 48,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "mean_ms" : 2097.1041666666665,
            "p99_ms" : 7980,
            "count" : 48,
            "p95_ms" : 3314,
            "p50_ms" : 1715
          },
          "prompt_tokens_reported" : 20241,
          "completion_tokens_reported" : 5881,
          "total_tokens_reported" : 26122,
          "calls_with_token_usage" : 48
        }
      },
      "prompt_tokens_reported" : 261558,
      "total_tokens_reported" : 376637,
      "calls_with_token_usage" : 376,
      "calls" : 376,
      "completion_tokens_reported" : 115079
    },
    "baseline_turns_per_second_sum_timeline_time" : 0.24807355380870427
  },
  "failures" : {
    "count" : 0,
    "by_reason" : { }
  },
  "acceptance_pass" : false,
  "acceptance_gate_details" : {
    "semantic_unique_precision_minimum" : 0.4,
    "provenance_pass" : true,
    "runtime_failures_empty" : true,
    "semantic_unique_fact_quality_pass" : false,
    "semantic_unique_recall_minimum" : 0.5,
    "integrity_pass" : false,
    "profile" : "relaxed"
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory baseline. G2A and G2C reuse the same accepted real MemoryCuratorService output so the ablation isolates append-only versus compacted serving behavior.", "G1, G2A, and G2C all retain the same traceable KG entities and relations for retrieval; the G2C KG sidecar is unchanged by compaction and counted in its serving tokens.", "G1 and G2A use the production SqliteMemoryService hybrid retriever. G2C merges relevant unmapped raw candidates with production corpus ranking, with an explicit threshold and semantic de-duplication. A frozen shared text ranker provides corpus-only retrieval diagnostics.", "Retrieval reports Recall@5 and Recall@10; Recall@1 and Recall@3 are not reported.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness reports both strict frozen rules and a relaxed anchor/core-fact score; the LLM answers memory questions, while deterministic query annotations score them without an extra judge call.", "The relaxed gate profile is a screening pass only. The 30% full-corpus compression target is reported independently and remains an explicit target metric.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run.", "Formal paired effects compare G2C with G1. G2A is reported as an append-only ablation." ]
}
```
