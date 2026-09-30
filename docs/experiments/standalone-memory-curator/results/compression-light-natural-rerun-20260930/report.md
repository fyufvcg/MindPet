# Memory Curator LLM experiment

Dataset: `compression-normal.jsonl` (SHA-256 `dd326731da133d7e5596f298a583302f49da191ad961b9d35a529c9d57d82cd6`)

This report is generated from real calls to the production Java KnowledgeGraphService, MemoryCuratorService, MemoryCorpusCompactionService, and SqliteMemoryService. Fixture output is not used as experiment data. Primary effects compare compacted G2C with G1; append-only G2A is an ablation built from the same accepted curator output.

Acceptance: **NOT PASSED**. Per-timeline gates, information retention and integrity are shown below; missing answers never pass. A single light timeline provides diagnostic evidence without a statistical significance claim.

## Failed answer cases

- timeline-901 / timeline-901-q01 / G1: state_confusion; response: 我不知道。记忆中同时有“目前住在苏州”和“目前住在杭州”的记录，无法确定哪条是最新的。
- timeline-901 / timeline-901-q01 / G2C: state_confusion; response: 记忆中有冲突：苏州和杭州都被记为目前居住地，因此无法确定你现在住在哪座城市。
- timeline-901 / timeline-901-q04 / G1: missing_retrieved_evidence; response: 目前没有检索到相关记忆，我不知道用户希望回复采用哪种形式。
- timeline-901 / timeline-901-q04 / G2C: answer_rule_failure; response: 用户喜欢简洁、按步骤组织的回答。
- timeline-901 / timeline-901-q05 / G1: missing_retrieved_evidence; response: 我不知道；目前没有关于你是否计划继续参加摄影课程的记忆。

```json
{
  "dataset_version" : [ "memory-curator-compression-v3" ],
  "selected_timelines" : 1,
  "selected_turns" : 40,
  "selected_queries" : 10,
  "curator_repair_calls" : 0,
  "answer_queries" : 6,
  "answer_calls_expected" : 12,
  "fact_quality" : {
    "true_positive" : 3,
    "false_positive" : 4,
    "false_negative" : 5,
    "precision" : 0.42857142857142855,
    "recall" : 0.375,
    "f1" : 0.39999999999999997,
    "stored_fact_rows" : 7,
    "exact_match_duplicate_rows" : 0,
    "exact_match_duplicate_rate" : 0.0,
    "semantic_duplicate_rows" : 0,
    "semantic_duplicate_rate" : 0.0,
    "distinct_state_interval_rows" : 0,
    "redundant_semantic_rows" : 0,
    "redundant_semantic_rate" : 0.0,
    "semantic_unique_fact_quality" : {
      "matching_policy" : "predicate/value/scope/assertion; temporal fields are evaluated separately",
      "precision" : 0.5714285714285714,
      "semantic_duplicate_rate" : 0.0,
      "false_negative" : 4,
      "true_positive" : 4,
      "false_positive" : 3,
      "recall" : 0.5,
      "f1" : 0.5333333333333333,
      "semantic_duplicate_rows" : 0
    },
    "strict_row_matching_policy" : "predicate/value/scope/assertion plus expected time status and resolved start date; non-overlapping state intervals are valid repeated rows; recall uses unique matched gold; unmatched rows are not necessarily hallucinations",
    "semantic_repetition_policy" : "semantic_duplicate_rows counts repeated meanings including legitimate state returns; distinct_state_interval_rows and redundant_semantic_rows separate them; intervals lacking provable non-overlap remain redundant",
    "by_timeline" : [ {
      "primary_scenario" : "compression_comparison",
      "timeline_id" : "timeline-901",
      "expected" : 8,
      "stored" : 7,
      "true_positive" : 3,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 4,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    } ]
  },
  "g1_kg_fact_quality" : {
    "precision" : 0.2222222222222222,
    "recall" : 0.25,
    "by_timeline" : [ {
      "unmatched_kg_relations" : 7,
      "timeline_id" : "timeline-901",
      "matched_gold" : 2,
      "duplicate_relations" : 0
    } ],
    "method" : "Conservative KG relation projection by visible text; unsupported relations remain unmatched. No source-turn label inheritance."
  },
  "profile_quality" : {
    "fully_correct_timelines" : 1,
    "expected_entries_correct" : 3,
    "recall" : 1.0,
    "precision" : 1.0,
    "written_entries" : 3,
    "expected_entries" : 3,
    "by_timeline" : [ {
      "actual" : {
        "current_location" : "苏州",
        "occupation_current" : "数据分析师",
        "home_location" : "南京"
      },
      "correct" : 3,
      "expected" : {
        "home_location" : "南京",
        "current_location" : "苏州",
        "occupation_current" : "数据分析师"
      },
      "timeline_id" : "timeline-901",
      "expected_count" : 3
    } ]
  },
  "retention" : {
    "retention_at_1" : 0.6,
    "retention_at_3" : 0.6,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 5,
    "eligible_facts_at_3" : 5,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "actual_g1_retrieval_corpus" : {
      "estimated_tokens" : 549,
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 92,
        "knowledge_graph_relation" : 94,
        "ordinary_long_term_memory" : 363
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 6,
        "knowledge_graph_relation" : 9,
        "ordinary_long_term_memory" : 12
      },
      "unit_count" : 27
    },
    "actual_g2a_append_retrieval_corpus" : {
      "estimated_tokens" : 794,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 141,
        "current_profile" : 39,
        "knowledge_graph_entity" : 92,
        "knowledge_graph_relation" : 94,
        "ordinary_long_term_memory" : 363,
        "working_memory" : 65
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 7,
        "current_profile" : 3,
        "knowledge_graph_entity" : 6,
        "knowledge_graph_relation" : 9,
        "ordinary_long_term_memory" : 12,
        "working_memory" : 1
      },
      "unit_count" : 38
    },
    "actual_g2c_compacted_retrieval_corpus" : {
      "estimated_tokens" : 560,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 81,
        "curated_knowledge_graph_entity" : 92,
        "curated_knowledge_graph_relation" : 94,
        "ordinary_long_term_memory" : 293
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 7,
        "curated_knowledge_graph_entity" : 6,
        "curated_knowledge_graph_relation" : 9,
        "ordinary_long_term_memory" : 6
      },
      "unit_count" : 28
    },
    "actual_g2a_token_delta_vs_g1" : 0.44626593806921677,
    "actual_g2c_token_delta_vs_g1" : 0.020036429872495445,
    "actual_g2c_system_compression_rate" : -0.020036429872495543,
    "actual_g2c_token_delta_vs_g2a" : -0.2947103274559194,
    "g2c_compaction_action_counts" : {
      "KEEP" : 6,
      "MERGE" : 12,
      "RESOLVE" : 14,
      "SUPERSEDE" : 3
    },
    "g2c_logged_net_token_reduction" : -11,
    "raw_turn_text_tokens_estimated" : 594,
    "curated_fact_projection_tokens_estimated" : 119,
    "curated_fact_projection_compression_estimate" : 0.7996632996632996,
    "raw_turn_text_characters" : 620,
    "curated_fact_projection_characters" : 248,
    "curator_eligible_compression_rate" : -0.030303030303030276,
    "information_retention_rate" : 1.0,
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
    "stored_facts" : 7,
    "valid_source_turns" : 7,
    "source_completeness" : 1.0,
    "verbatim_evidence" : 7,
    "verbatim_evidence_rate" : 1.0,
    "active_retrieval_units" : 28,
    "retrieval_unit_source_traceability" : 28,
    "retrieval_unit_source_traceability_rate" : 1.0
  },
  "integrity" : {
    "by_timeline" : [ {
      "timeline_id" : "timeline-901",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 28,
      "pass" : false
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
        "name" : "curator_runs",
        "allocated_bytes" : 4096,
        "payload_bytes" : 570
      }, {
        "name" : "curator_state",
        "allocated_bytes" : 4096,
        "payload_bytes" : 258
      }, {
        "name" : "curator_turns",
        "allocated_bytes" : 16384,
        "payload_bytes" : 8376
      }, {
        "name" : "emotion_history",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_conversation_memory_recent",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_curator_turns_pending",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1919
      }, {
        "name" : "idx_curator_turns_user_completed",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1981
      }, {
        "name" : "idx_curator_turns_user_seq",
        "allocated_bytes" : 4096,
        "payload_bytes" : 838
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
        "allocated_bytes" : 4096,
        "payload_bytes" : 2572
      }, {
        "name" : "idx_ltm_user_importance",
        "allocated_bytes" : 4096,
        "payload_bytes" : 3157
      }, {
        "name" : "idx_ltm_user_searchable",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2637
      }, {
        "name" : "idx_memory_compaction_batch_user_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 92
      }, {
        "name" : "idx_memory_compaction_log_user_batch",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1067
      }, {
        "name" : "idx_memory_fact_idempotency",
        "allocated_bytes" : 4096,
        "payload_bytes" : 610
      }, {
        "name" : "idx_memory_fact_user_predicate_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 294
      }, {
        "name" : "idx_memory_fact_user_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 279
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
        "payload_bytes" : 685
      }, {
        "name" : "idx_memory_retrieval_source_turn",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1943
      }, {
        "name" : "idx_memory_retrieval_user_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1206
      }, {
        "name" : "idx_profile_current_user_updated",
        "allocated_bytes" : 4096,
        "payload_bytes" : 116
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
        "allocated_bytes" : 4096,
        "payload_bytes" : 1754
      }, {
        "name" : "idx_sessions_user_updated",
        "allocated_bytes" : 4096,
        "payload_bytes" : 199
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
        "allocated_bytes" : 307200,
        "payload_bytes" : 278893
      }, {
        "name" : "memory_compaction_batch",
        "allocated_bytes" : 4096,
        "payload_bytes" : 210
      }, {
        "name" : "memory_compaction_blob",
        "allocated_bytes" : 53248,
        "payload_bytes" : 41650
      }, {
        "name" : "memory_compaction_log",
        "allocated_bytes" : 16384,
        "payload_bytes" : 8115
      }, {
        "name" : "memory_compaction_plan",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2041
      }, {
        "name" : "memory_compaction_snapshot",
        "allocated_bytes" : 28672,
        "payload_bytes" : 22704
      }, {
        "name" : "memory_corpus_migration",
        "allocated_bytes" : 4096,
        "payload_bytes" : 39
      }, {
        "name" : "memory_fact",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1861
      }, {
        "name" : "memory_gallery",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_retrieval_source",
        "allocated_bytes" : 20480,
        "payload_bytes" : 14193
      }, {
        "name" : "memory_retrieval_unit",
        "allocated_bytes" : 163840,
        "payload_bytes" : 148380
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
        "allocated_bytes" : 20480,
        "payload_bytes" : 13057
      }, {
        "name" : "sessions",
        "allocated_bytes" : 4096,
        "payload_bytes" : 415
      }, {
        "name" : "sqlite_autoindex_curator_state_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 18
      }, {
        "name" : "sqlite_autoindex_curator_turns_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1599
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
        "payload_bytes" : 62
      }, {
        "name" : "sqlite_autoindex_memory_compaction_blob_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 690
      }, {
        "name" : "sqlite_autoindex_memory_compaction_plan_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1101
      }, {
        "name" : "sqlite_autoindex_memory_compaction_snapshot_1",
        "allocated_bytes" : 16384,
        "payload_bytes" : 9618
      }, {
        "name" : "sqlite_autoindex_memory_corpus_migration_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 18
      }, {
        "name" : "sqlite_autoindex_memory_fact_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 610
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
        "allocated_bytes" : 12288,
        "payload_bytes" : 5323
      }, {
        "name" : "sqlite_autoindex_memory_retrieval_unit_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1343
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
        "allocated_bytes" : 4096,
        "payload_bytes" : 2519
      }, {
        "name" : "sqlite_autoindex_sessions_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 209
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
        "payload_bytes" : 106
      }, {
        "name" : "sqlite_schema",
        "allocated_bytes" : 24576,
        "payload_bytes" : 18235
      }, {
        "name" : "sqlite_sequence",
        "allocated_bytes" : 4096,
        "payload_bytes" : 93
      }, {
        "name" : "uq_memory_retrieval_active_key",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2552
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
        "payload_bytes" : 259
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
        "allocated_bytes" : 4096,
        "payload_bytes" : 327
      }, {
        "name" : "idx_kg_evidence_user_created",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1013
      }, {
        "name" : "idx_kg_relation_user_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 503
      }, {
        "name" : "idx_kg_relation_user_target",
        "allocated_bytes" : 4096,
        "payload_bytes" : 503
      }, {
        "name" : "idx_llm_growth_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_ltm_user_created",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1052
      }, {
        "name" : "idx_ltm_user_importance",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1295
      }, {
        "name" : "idx_ltm_user_searchable",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1079
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
        "allocated_bytes" : 4096,
        "payload_bytes" : 1754
      }, {
        "name" : "idx_sessions_user_updated",
        "allocated_bytes" : 4096,
        "payload_bytes" : 199
      }, {
        "name" : "kg_entity",
        "allocated_bytes" : 28672,
        "payload_bytes" : 25730
      }, {
        "name" : "kg_evidence",
        "allocated_bytes" : 12288,
        "payload_bytes" : 6652
      }, {
        "name" : "kg_relation",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1719
      }, {
        "name" : "kg_turn_ingest",
        "allocated_bytes" : 12288,
        "payload_bytes" : 5122
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
        "allocated_bytes" : 131072,
        "payload_bytes" : 115847
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
        "allocated_bytes" : 20480,
        "payload_bytes" : 13057
      }, {
        "name" : "sessions",
        "allocated_bytes" : 4096,
        "payload_bytes" : 415
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
        "allocated_bytes" : 4096,
        "payload_bytes" : 279
      }, {
        "name" : "sqlite_autoindex_kg_entity_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 262
      }, {
        "name" : "sqlite_autoindex_kg_evidence_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2287
      }, {
        "name" : "sqlite_autoindex_kg_evidence_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2287
      }, {
        "name" : "sqlite_autoindex_kg_relation_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 359
      }, {
        "name" : "sqlite_autoindex_kg_relation_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 926
      }, {
        "name" : "sqlite_autoindex_kg_turn_ingest_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2759
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
        "allocated_bytes" : 4096,
        "payload_bytes" : 2519
      }, {
        "name" : "sqlite_autoindex_sessions_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 209
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
        "allocated_bytes" : 24576,
        "payload_bytes" : 18235
      }, {
        "name" : "sqlite_sequence",
        "allocated_bytes" : 4096,
        "payload_bytes" : 35
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
    "token_budget_method" : "estimated; see run-config.json",
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.3333333333333333,
        "recall_at_10" : 0.4444444444444444,
        "precision_at_5" : 0.16,
        "precision_at_10" : 0.13999999999999999,
        "mrr" : 0.2074074074074074,
        "ndcg_at_10" : 0.2637766386571135,
        "recall_at_256_estimated_tokens" : 0.4444444444444444,
        "recall_at_512_estimated_tokens" : 0.4444444444444444,
        "recall_at_1024_estimated_tokens" : 0.4444444444444444,
        "mean_query_context_tokens" : 54.3,
        "mean_duplicate_top10_occupancy" : 1.8
      },
      "G2A" : {
        "recall_at_5" : 0.4444444444444444,
        "recall_at_10" : 0.5555555555555556,
        "precision_at_5" : 0.2,
        "precision_at_10" : 0.16,
        "mrr" : 0.31851851851851853,
        "ndcg_at_10" : 0.3748877497682246,
        "recall_at_256_estimated_tokens" : 0.5555555555555556,
        "recall_at_512_estimated_tokens" : 0.5555555555555556,
        "recall_at_1024_estimated_tokens" : 0.5555555555555556,
        "mean_query_context_tokens" : 89.6,
        "mean_duplicate_top10_occupancy" : 2.9
      },
      "G2C" : {
        "recall_at_5" : 0.7777777777777778,
        "recall_at_10" : 0.7777777777777778,
        "precision_at_5" : 0.16,
        "precision_at_10" : 0.08,
        "mrr" : 0.6944444444444444,
        "ndcg_at_10" : 0.7145196175637103,
        "recall_at_256_estimated_tokens" : 0.7777777777777778,
        "recall_at_512_estimated_tokens" : 0.7777777777777778,
        "recall_at_1024_estimated_tokens" : 0.7777777777777778,
        "mean_query_context_tokens" : 132.2,
        "mean_duplicate_top10_occupancy" : 2.8
      }
    },
    "query_count" : 10,
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : 0.4444444444444445,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 1,
        "reason" : "At least two independent timelines are required; light single-timeline runs are diagnostic."
      },
      "delta_recall_at_10_g2c_minus_g1" : 0.33333333333333337,
      "delta_recall_at_10_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 1,
        "reason" : "At least two independent timelines are required; light single-timeline runs are diagnostic."
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : 0.33333333333333337,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 1,
        "reason" : "At least two independent timelines are required; light single-timeline runs are diagnostic."
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : 0.1111111111111111,
      "g2a_ablation_delta_recall_at_10_vs_g1" : 0.11111111111111116,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.11111111111111116,
      "primary_metrics" : [ "Delta Recall@5", "Delta Recall@10", "Delta recall at estimated 512 tokens" ]
    }
  },
  "shared_ranker_retrieval" : {
    "token_budget_method" : "estimated; see run-config.json",
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.1111111111111111,
        "recall_at_10" : 0.1111111111111111,
        "precision_at_5" : 0.12,
        "precision_at_10" : 0.11000000000000001,
        "mrr" : 0.1111111111111111,
        "ndcg_at_10" : 0.1111111111111111,
        "recall_at_256_estimated_tokens" : 0.1111111111111111,
        "recall_at_512_estimated_tokens" : 0.1111111111111111,
        "recall_at_1024_estimated_tokens" : 0.1111111111111111,
        "mean_query_context_tokens" : 5.9,
        "mean_duplicate_top10_occupancy" : 0.3
      },
      "G2A" : {
        "recall_at_5" : 0.1111111111111111,
        "recall_at_10" : 0.1111111111111111,
        "precision_at_5" : 0.12,
        "precision_at_10" : 0.11000000000000001,
        "mrr" : 0.1111111111111111,
        "ndcg_at_10" : 0.1111111111111111,
        "recall_at_256_estimated_tokens" : 0.1111111111111111,
        "recall_at_512_estimated_tokens" : 0.1111111111111111,
        "recall_at_1024_estimated_tokens" : 0.1111111111111111,
        "mean_query_context_tokens" : 16.1,
        "mean_duplicate_top10_occupancy" : 0.3
      },
      "G2C" : {
        "recall_at_5" : 0.1111111111111111,
        "recall_at_10" : 0.1111111111111111,
        "precision_at_5" : 0.12,
        "precision_at_10" : 0.11000000000000001,
        "mrr" : 0.1111111111111111,
        "ndcg_at_10" : 0.1111111111111111,
        "recall_at_256_estimated_tokens" : 0.1111111111111111,
        "recall_at_512_estimated_tokens" : 0.1111111111111111,
        "recall_at_1024_estimated_tokens" : 0.1111111111111111,
        "mean_query_context_tokens" : 7.7,
        "mean_duplicate_top10_occupancy" : 0.2
      }
    },
    "query_count" : 10,
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : 0.0,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 1,
        "reason" : "At least two independent timelines are required; light single-timeline runs are diagnostic."
      },
      "delta_recall_at_10_g2c_minus_g1" : 0.0,
      "delta_recall_at_10_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 1,
        "reason" : "At least two independent timelines are required; light single-timeline runs are diagnostic."
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : 0.0,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 1,
        "reason" : "At least two independent timelines are required; light single-timeline runs are diagnostic."
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_10_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.0,
      "primary_metrics" : [ "Delta Recall@5", "Delta Recall@10", "Delta recall at estimated 512 tokens" ]
    }
  },
  "end_to_end" : {
    "by_group" : {
      "G1" : {
        "correct" : 3,
        "total" : 6,
        "accuracy" : 0.5,
        "abstention_correct" : 1,
        "abstention_total" : 1,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 68,
        "state_confusion_rate" : 0.16666666666666666,
        "evidence_support_rate" : 0.6666666666666666,
        "mean_prompt_tokens_reported" : 157.5,
        "mean_latency_ms" : 1514
      },
      "G2C" : {
        "correct" : 4,
        "total" : 6,
        "accuracy" : 0.6666666666666666,
        "abstention_correct" : 1,
        "abstention_total" : 1,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 135,
        "state_confusion_rate" : 0.16666666666666666,
        "evidence_support_rate" : 0.8333333333333334,
        "mean_prompt_tokens_reported" : 220.16666666666666,
        "mean_latency_ms" : 1570
      }
    },
    "paired" : {
      "primary_comparison" : "G2C minus G1",
      "g2c_minus_g1_accuracy" : 0.16666666666666666,
      "mcnemar_g1_only_correct" : 0,
      "mcnemar_g2_only_correct" : 1,
      "mcnemar_p_value_approx" : null,
      "inference_status" : "unavailable_single_timeline_light_run"
    },
    "answers" : 12
  },
  "answer_failure_cases" : [ {
    "timeline_id" : "timeline-901",
    "query_id" : "timeline-901-q01",
    "group" : "G1",
    "query_type" : "memory",
    "question" : "现在的居住地是哪座城市？",
    "expected_answer" : "苏州",
    "should_abstain" : false,
    "response" : "我不知道。记忆中同时有“目前住在苏州”和“目前住在杭州”的记录，无法确定哪条是最新的。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "7", "25", "14", "10", "1", "4", "23", "5", "15", "2" ],
    "context_token_estimate" : 135,
    "elapsed_ms" : 1470,
    "error" : "",
    "prompt_tokens" : 222,
    "completion_tokens" : 35,
    "total_tokens" : 257
  }, {
    "timeline_id" : "timeline-901",
    "query_id" : "timeline-901-q01",
    "group" : "G2C",
    "query_type" : "memory",
    "question" : "现在的居住地是哪座城市？",
    "expected_answer" : "苏州",
    "should_abstain" : false,
    "response" : "记忆中有冲突：苏州和杭州都被记为目前居住地，因此无法确定你现在住在哪座城市。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "5523b2ff-8a26-377c-a77d-29ed107d098c", "46050ffa-59a7-3465-9a6e-c9f907ba366c", "baseline-knowledge_graph_entity-8", "baseline-knowledge_graph_entity-28", "40b5f76f-ebad-3699-af8e-414bf207c207", "baseline-knowledge_graph_entity-2", "baseline-knowledge_graph_relation-3", "baseline-knowledge_graph_relation-29", "baseline-knowledge_graph_entity-14", "baseline-knowledge_graph_relation-9" ],
    "context_token_estimate" : 107,
    "elapsed_ms" : 2218,
    "error" : "",
    "prompt_tokens" : 203,
    "completion_tokens" : 147,
    "total_tokens" : 350
  }, {
    "timeline_id" : "timeline-901",
    "query_id" : "timeline-901-q04",
    "group" : "G1",
    "query_type" : "memory",
    "question" : "用户希望回复采用哪种形式？",
    "expected_answer" : "简洁步骤",
    "should_abstain" : false,
    "response" : "目前没有检索到相关记忆，我不知道用户希望回复采用哪种形式。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : false,
    "state_confusion" : false,
    "error_type" : "missing_retrieved_evidence",
    "source_unit_ids" : [ ],
    "context_token_estimate" : 0,
    "elapsed_ms" : 1170,
    "error" : "",
    "prompt_tokens" : 94,
    "completion_tokens" : 49,
    "total_tokens" : 143
  }, {
    "timeline_id" : "timeline-901",
    "query_id" : "timeline-901-q04",
    "group" : "G2C",
    "query_type" : "memory",
    "question" : "用户希望回复采用哪种形式？",
    "expected_answer" : "简洁步骤",
    "should_abstain" : false,
    "response" : "用户喜欢简洁、按步骤组织的回答。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "6aea5728-1f7d-3e1f-a3a7-084dec015bc7", "baseline-knowledge_graph_relation-21", "461daf1f-e6b9-3df5-80b8-db88ed7304c7", "baseline-knowledge_graph_entity-20", "40b5f76f-ebad-3699-af8e-414bf207c207", "46050ffa-59a7-3465-9a6e-c9f907ba366c", "6a0e1f59-4055-324b-9733-735c00d93905", "baseline-knowledge_graph_relation-52", "baseline-knowledge_graph_relation-34", "5462dad0-8abf-36d9-9879-5fc1ee958978" ],
    "context_token_estimate" : 228,
    "elapsed_ms" : 970,
    "error" : "",
    "prompt_tokens" : 287,
    "completion_tokens" : 15,
    "total_tokens" : 302
  }, {
    "timeline_id" : "timeline-901",
    "query_id" : "timeline-901-q05",
    "group" : "G1",
    "query_type" : "memory",
    "question" : "下一步还会参加摄影课程吗？",
    "expected_answer" : "取消",
    "should_abstain" : false,
    "response" : "我不知道；目前没有关于你是否计划继续参加摄影课程的记忆。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : false,
    "state_confusion" : false,
    "error_type" : "missing_retrieved_evidence",
    "source_unit_ids" : [ ],
    "context_token_estimate" : 0,
    "elapsed_ms" : 1027,
    "error" : "",
    "prompt_tokens" : 95,
    "completion_tokens" : 21,
    "total_tokens" : 116
  } ],
  "compaction_acceptance" : {
    "by_timeline" : [ {
      "timeline_id" : "timeline-901",
      "sample_profile" : "normal",
      "system_compression_rate" : -0.020036429872495543,
      "oracle_upper_rate" : 0.04735883424408016,
      "oracle_efficiency" : -0.42307692307692496,
      "curator_eligible_compression_rate" : -0.030303030303030276,
      "curator_eligible_oracle_upper_rate" : 0.07162534435261703,
      "curator_eligible_oracle_efficiency" : -0.423076923076923,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.5,
      "g2c_answer_accuracy" : 0.6666666666666666,
      "query_context_compression_rate" : -1.4346224677716388,
      "compression_pass" : false,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : false,
      "recall_pass" : true,
      "duplicate_top10_pass" : false,
      "status" : "measured",
      "performance_gates_pass" : false
    } ],
    "oracle_method" : "Conservative feasible corpus estimate using covered clauses, preserving unmatched raw and identical KG; not an exact minimum-cover upper bound or deletion target."
  },
  "model_calls" : {
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 40,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "p50_ms" : 1722,
          "mean_ms" : 2209.75,
          "p99_ms" : 6467,
          "count" : 40,
          "p95_ms" : 4410
        },
        "prompt_tokens_reported" : 17533,
        "completion_tokens_reported" : 5210,
        "total_tokens_reported" : 22743,
        "calls_with_token_usage" : 40
      },
      "curator" : {
        "calls" : 3,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "p50_ms" : 8647,
          "mean_ms" : 8645.333333333334,
          "p99_ms" : 8711,
          "count" : 3,
          "p95_ms" : 8711
        },
        "prompt_tokens_reported" : 7907,
        "completion_tokens_reported" : 3785,
        "total_tokens_reported" : 11692,
        "calls_with_token_usage" : 3
      },
      "answer" : {
        "calls" : 12,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "p50_ms" : 1336,
          "mean_ms" : 1542.25,
          "p99_ms" : 2458,
          "count" : 12,
          "p95_ms" : 2458
        },
        "prompt_tokens_reported" : 2266,
        "completion_tokens_reported" : 803,
        "total_tokens_reported" : 3069,
        "calls_with_token_usage" : 12
      }
    },
    "prompt_tokens_reported" : 27706,
    "total_tokens_reported" : 37504,
    "calls_with_token_usage" : 55,
    "calls" : 55,
    "completion_tokens_reported" : 9798
  },
  "system_performance" : {
    "experiment_wall_ms" : 151065,
    "timeline_count" : 1,
    "baseline_ingestion_per_timeline_ms" : {
      "p50_ms" : 89956,
      "mean_ms" : 89956.0,
      "p99_ms" : 89956,
      "count" : 1,
      "p95_ms" : 89956
    },
    "curator_ingestion_per_timeline_ms" : {
      "p50_ms" : 39714,
      "mean_ms" : 39714.0,
      "p99_ms" : 39714,
      "count" : 1,
      "p95_ms" : 39714
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "p50_ms" : 19303,
      "mean_ms" : 19303.0,
      "p99_ms" : 19303,
      "count" : 1,
      "p95_ms" : 19303
    },
    "query_embedding_latency_ms" : {
      "p50_ms" : 50,
      "mean_ms" : 51.3,
      "p99_ms" : 70,
      "count" : 10,
      "p95_ms" : 70
    },
    "g1_production_retrieval_search_latency_ms" : {
      "p50_ms" : 1,
      "mean_ms" : 3.8,
      "p99_ms" : 23,
      "count" : 10,
      "p95_ms" : 23
    },
    "g2a_append_retrieval_search_latency_ms" : {
      "p50_ms" : 2,
      "mean_ms" : 2.4,
      "p99_ms" : 7,
      "count" : 10,
      "p95_ms" : 7
    },
    "g2c_compaction_retrieval_search_latency_ms" : {
      "p50_ms" : 15,
      "mean_ms" : 17.0,
      "p99_ms" : 29,
      "count" : 10,
      "p95_ms" : 29
    },
    "g1_indexed_corpus" : {
      "estimated_tokens" : 549,
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 92,
        "knowledge_graph_relation" : 94,
        "ordinary_long_term_memory" : 363
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 6,
        "knowledge_graph_relation" : 9,
        "ordinary_long_term_memory" : 12
      },
      "unit_count" : 27
    },
    "g2a_indexed_corpus" : {
      "estimated_tokens" : 794,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 141,
        "current_profile" : 39,
        "knowledge_graph_entity" : 92,
        "knowledge_graph_relation" : 94,
        "ordinary_long_term_memory" : 363,
        "working_memory" : 65
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 7,
        "current_profile" : 3,
        "knowledge_graph_entity" : 6,
        "knowledge_graph_relation" : 9,
        "ordinary_long_term_memory" : 12,
        "working_memory" : 1
      },
      "unit_count" : 38
    },
    "g2c_indexed_corpus" : {
      "estimated_tokens" : 560,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 81,
        "curated_knowledge_graph_entity" : 92,
        "curated_knowledge_graph_relation" : 94,
        "ordinary_long_term_memory" : 293
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 7,
        "curated_knowledge_graph_entity" : 6,
        "curated_knowledge_graph_relation" : 9,
        "ordinary_long_term_memory" : 6
      },
      "unit_count" : 28
    },
    "g1_sqlite_footprint_bytes" : {
      "main_db_bytes" : 577536,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768,
      "wal_bytes" : 4128272,
      "total_observed_bytes" : 4738576
    },
    "g2_sqlite_footprint_bytes" : {
      "main_db_bytes" : 974848,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768,
      "wal_bytes" : 4144752,
      "total_observed_bytes" : 5152368
    },
    "llm_calls_by_stage" : {
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 40,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "p50_ms" : 1722,
            "mean_ms" : 2209.75,
            "p99_ms" : 6467,
            "count" : 40,
            "p95_ms" : 4410
          },
          "prompt_tokens_reported" : 17533,
          "completion_tokens_reported" : 5210,
          "total_tokens_reported" : 22743,
          "calls_with_token_usage" : 40
        },
        "curator" : {
          "calls" : 3,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "p50_ms" : 8647,
            "mean_ms" : 8645.333333333334,
            "p99_ms" : 8711,
            "count" : 3,
            "p95_ms" : 8711
          },
          "prompt_tokens_reported" : 7907,
          "completion_tokens_reported" : 3785,
          "total_tokens_reported" : 11692,
          "calls_with_token_usage" : 3
        },
        "answer" : {
          "calls" : 12,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "p50_ms" : 1336,
            "mean_ms" : 1542.25,
            "p99_ms" : 2458,
            "count" : 12,
            "p95_ms" : 2458
          },
          "prompt_tokens_reported" : 2266,
          "completion_tokens_reported" : 803,
          "total_tokens_reported" : 3069,
          "calls_with_token_usage" : 12
        }
      },
      "prompt_tokens_reported" : 27706,
      "total_tokens_reported" : 37504,
      "calls_with_token_usage" : 55,
      "calls" : 55,
      "completion_tokens_reported" : 9798
    },
    "baseline_turns_per_second_sum_timeline_time" : 0.44466183467472986
  },
  "failures" : {
    "count" : 0,
    "by_reason" : { }
  },
  "acceptance_pass" : false,
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory baseline. G2A and G2C reuse the same accepted real MemoryCuratorService output so the ablation isolates append-only versus compacted serving behavior.", "G1, G2A, and G2C all retain the same traceable KG entities and relations for retrieval; the G2C KG sidecar is unchanged by compaction and counted in its serving tokens.", "G1 and G2A use the production SqliteMemoryService hybrid retriever. G2C merges relevant unmapped raw candidates with production corpus ranking, with an explicit threshold and semantic de-duplication. A frozen shared text ranker provides corpus-only retrieval diagnostics.", "Retrieval reports Recall@5 and Recall@10; Recall@1 and Recall@3 are not reported.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run.", "Formal paired effects compare G2C with G1. G2A is reported as an append-only ablation." ]
}
```
