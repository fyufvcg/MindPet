# Memory Curator LLM experiment

Dataset: `compression-redundant.jsonl` (SHA-256 `6815d0b32855b7c4fc203e49a4294c1a6128d7dc73903a8f823112a3e49158fd`)

This report is generated from real calls to the production Java KnowledgeGraphService, MemoryCuratorService, MemoryCorpusCompactionService, and SqliteMemoryService. Fixture output is not used as experiment data. Primary effects compare compacted G2C with G1; append-only G2A is an ablation built from the same accepted curator output.

Acceptance: **NOT PASSED**. Per-timeline gates, information retention and integrity are shown below; missing answers never pass. A single light timeline provides diagnostic evidence without a statistical significance claim.

## Failed answer cases

- timeline-902 / timeline-902-q01 / G1: state_confusion; response: 你现在住在苏州。
- timeline-902 / timeline-902-q01 / G2C: state_confusion; response: 记忆中有冲突：杭州和苏州都被记为现居城市，因此无法确定你现在住在哪座城市。
- timeline-902 / timeline-902-q02 / G1: answer_rule_failure; response: 最近一次搬家前住在杭州。
- timeline-902 / timeline-902-q02 / G2C: answer_rule_failure; response: 不知道。记忆只提到已搬回杭州，没有说明搬家前住在哪座城市。
- timeline-902 / timeline-902-q04 / G1: answer_rule_failure; response: 用户希望回复采用简洁的步骤形式。
- timeline-902 / timeline-902-q04 / G2C: answer_rule_failure; response: 用户偏好简洁、按步骤组织的回答。
- timeline-902 / timeline-902-q05 / G1: state_confusion; response: 记忆里既有“正在考虑”，也有“已计划参加”的记录；但没有信息确认你现在是否仍会参加。
- timeline-902 / timeline-902-q05 / G2C: state_confusion; response: 目前没有确定会参加：之前的计划已取消，摄影课程现在只是考虑选项。之后是否参加还不知道。

```json
{
  "dataset_version" : [ "memory-curator-compression-v3" ],
  "selected_timelines" : 1,
  "selected_turns" : 40,
  "selected_queries" : 10,
  "curator_repair_calls" : 1,
  "answer_queries" : 6,
  "answer_calls_expected" : 12,
  "fact_quality" : {
    "true_positive" : 5,
    "false_positive" : 6,
    "false_negative" : 3,
    "precision" : 0.45454545454545453,
    "recall" : 0.625,
    "f1" : 0.5263157894736842,
    "stored_fact_rows" : 11,
    "exact_match_duplicate_rows" : 0,
    "exact_match_duplicate_rate" : 0.0,
    "semantic_duplicate_rows" : 0,
    "semantic_duplicate_rate" : 0.0,
    "distinct_state_interval_rows" : 0,
    "redundant_semantic_rows" : 0,
    "redundant_semantic_rate" : 0.0,
    "semantic_unique_fact_quality" : {
      "false_positive" : 6,
      "recall" : 0.625,
      "f1" : 0.5263157894736842,
      "semantic_duplicate_rows" : 0,
      "matching_policy" : "predicate/value/scope/assertion; temporal fields are evaluated separately",
      "precision" : 0.45454545454545453,
      "semantic_duplicate_rate" : 0.0,
      "false_negative" : 3,
      "true_positive" : 5
    },
    "strict_row_matching_policy" : "predicate/value/scope/assertion plus expected time status and resolved start date; non-overlapping state intervals are valid repeated rows; recall uses unique matched gold; unmatched rows are not necessarily hallucinations",
    "semantic_repetition_policy" : "semantic_duplicate_rows counts repeated meanings including legitimate state returns; distinct_state_interval_rows and redundant_semantic_rows separate them; intervals lacking provable non-overlap remain redundant",
    "by_timeline" : [ {
      "primary_scenario" : "compression_comparison",
      "timeline_id" : "timeline-902",
      "expected" : 8,
      "stored" : 11,
      "true_positive" : 5,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 5,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    } ]
  },
  "g1_kg_fact_quality" : {
    "precision" : 0.18181818181818182,
    "recall" : 0.25,
    "by_timeline" : [ {
      "duplicate_relations" : 0,
      "unmatched_kg_relations" : 9,
      "timeline_id" : "timeline-902",
      "matched_gold" : 2
    } ],
    "method" : "Conservative KG relation projection by visible text; unsupported relations remain unmatched. No source-turn label inheritance."
  },
  "profile_quality" : {
    "written_entries" : 3,
    "expected_entries" : 3,
    "by_timeline" : [ {
      "expected_count" : 3,
      "actual" : {
        "home_location" : "南京",
        "current_location" : "苏州",
        "occupation_current" : "数据分析师"
      },
      "correct" : 2,
      "expected" : {
        "home_location" : "南京",
        "current_location" : "杭州",
        "occupation_current" : "数据分析师"
      },
      "timeline_id" : "timeline-902"
    } ],
    "fully_correct_timelines" : 0,
    "expected_entries_correct" : 2,
    "recall" : 0.6666666666666666,
    "precision" : 0.6666666666666666
  },
  "retention" : {
    "retention_at_1" : 0.8,
    "retention_at_3" : 0.8,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 5,
    "eligible_facts_at_3" : 5,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "actual_g1_retrieval_corpus" : {
      "unit_count" : 36,
      "estimated_tokens" : 647,
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 182,
        "knowledge_graph_relation" : 130,
        "ordinary_long_term_memory" : 335
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 11,
        "knowledge_graph_relation" : 13,
        "ordinary_long_term_memory" : 12
      }
    },
    "actual_g2a_append_retrieval_corpus" : {
      "unit_count" : 51,
      "estimated_tokens" : 963,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 212,
        "current_profile" : 39,
        "knowledge_graph_entity" : 182,
        "knowledge_graph_relation" : 130,
        "ordinary_long_term_memory" : 335,
        "working_memory" : 65
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 11,
        "current_profile" : 3,
        "knowledge_graph_entity" : 11,
        "knowledge_graph_relation" : 13,
        "ordinary_long_term_memory" : 12,
        "working_memory" : 1
      }
    },
    "actual_g2c_compacted_retrieval_corpus" : {
      "unit_count" : 40,
      "estimated_tokens" : 692,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 126,
        "curated_knowledge_graph_entity" : 182,
        "curated_knowledge_graph_relation" : 130,
        "ordinary_long_term_memory" : 254
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 11,
        "curated_knowledge_graph_entity" : 11,
        "curated_knowledge_graph_relation" : 13,
        "ordinary_long_term_memory" : 5
      }
    },
    "actual_g2a_token_delta_vs_g1" : 0.4884080370942813,
    "actual_g2c_token_delta_vs_g1" : 0.0695517774343122,
    "actual_g2c_system_compression_rate" : -0.06955177743431218,
    "actual_g2c_token_delta_vs_g2a" : -0.2814122533748702,
    "g2c_compaction_action_counts" : {
      "KEEP" : 10,
      "MERGE" : 21,
      "RESOLVE" : 27,
      "SUPERSEDE" : 4
    },
    "g2c_logged_net_token_reduction" : -45,
    "raw_turn_text_tokens_estimated" : 566,
    "curated_fact_projection_tokens_estimated" : 178,
    "curated_fact_projection_compression_estimate" : 0.6855123674911661,
    "raw_turn_text_characters" : 588,
    "curated_fact_projection_characters" : 395,
    "curator_eligible_compression_rate" : -0.13432835820895517,
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
    "stored_facts" : 11,
    "valid_source_turns" : 11,
    "source_completeness" : 1.0,
    "verbatim_evidence" : 11,
    "verbatim_evidence_rate" : 1.0,
    "active_retrieval_units" : 40,
    "retrieval_unit_source_traceability" : 40,
    "retrieval_unit_source_traceability_rate" : 1.0
  },
  "integrity" : {
    "pass" : false,
    "by_timeline" : [ {
      "timeline_id" : "timeline-902",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 31,
      "pass" : false
    } ]
  },
  "physical_storage_by_table" : {
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
        "payload_bytes" : 567
      }, {
        "name" : "idx_kg_evidence_user_created",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2183
      }, {
        "name" : "idx_kg_relation_user_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 727
      }, {
        "name" : "idx_kg_relation_user_target",
        "allocated_bytes" : 4096,
        "payload_bytes" : 727
      }, {
        "name" : "idx_llm_growth_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_ltm_user_created",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1403
      }, {
        "name" : "idx_ltm_user_importance",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1727
      }, {
        "name" : "idx_ltm_user_searchable",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1439
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
        "allocated_bytes" : 57344,
        "payload_bytes" : 47110
      }, {
        "name" : "kg_evidence",
        "allocated_bytes" : 24576,
        "payload_bytes" : 16444
      }, {
        "name" : "kg_relation",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2486
      }, {
        "name" : "kg_turn_ingest",
        "allocated_bytes" : 12288,
        "payload_bytes" : 5130
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
        "allocated_bytes" : 172032,
        "payload_bytes" : 154122
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
        "payload_bytes" : 12897
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
        "payload_bytes" : 479
      }, {
        "name" : "sqlite_autoindex_kg_entity_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 453
      }, {
        "name" : "sqlite_autoindex_kg_evidence_1",
        "allocated_bytes" : 12288,
        "payload_bytes" : 4927
      }, {
        "name" : "sqlite_autoindex_kg_evidence_2",
        "allocated_bytes" : 12288,
        "payload_bytes" : 4927
      }, {
        "name" : "sqlite_autoindex_kg_relation_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 519
      }, {
        "name" : "sqlite_autoindex_kg_relation_2",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1336
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
    },
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
        "payload_bytes" : 8299
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
        "payload_bytes" : 1984
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
        "payload_bytes" : 3443
      }, {
        "name" : "idx_ltm_user_importance",
        "allocated_bytes" : 12288,
        "payload_bytes" : 4226
      }, {
        "name" : "idx_ltm_user_searchable",
        "allocated_bytes" : 4096,
        "payload_bytes" : 3530
      }, {
        "name" : "idx_memory_compaction_batch_user_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 92
      }, {
        "name" : "idx_memory_compaction_log_user_batch",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1688
      }, {
        "name" : "idx_memory_fact_idempotency",
        "allocated_bytes" : 4096,
        "payload_bytes" : 923
      }, {
        "name" : "idx_memory_fact_user_predicate_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 459
      }, {
        "name" : "idx_memory_fact_user_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 439
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
        "payload_bytes" : 949
      }, {
        "name" : "idx_memory_retrieval_source_turn",
        "allocated_bytes" : 4096,
        "payload_bytes" : 3322
      }, {
        "name" : "idx_memory_retrieval_user_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1657
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
        "allocated_bytes" : 405504,
        "payload_bytes" : 372593
      }, {
        "name" : "memory_compaction_batch",
        "allocated_bytes" : 4096,
        "payload_bytes" : 210
      }, {
        "name" : "memory_compaction_blob",
        "allocated_bytes" : 61440,
        "payload_bytes" : 49980
      }, {
        "name" : "memory_compaction_log",
        "allocated_bytes" : 20480,
        "payload_bytes" : 13719
      }, {
        "name" : "memory_compaction_plan",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1894
      }, {
        "name" : "memory_compaction_snapshot",
        "allocated_bytes" : 36864,
        "payload_bytes" : 29350
      }, {
        "name" : "memory_corpus_migration",
        "allocated_bytes" : 4096,
        "payload_bytes" : 39
      }, {
        "name" : "memory_fact",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2863
      }, {
        "name" : "memory_gallery",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "memory_retrieval_source",
        "allocated_bytes" : 36864,
        "payload_bytes" : 27744
      }, {
        "name" : "memory_retrieval_unit",
        "allocated_bytes" : 221184,
        "payload_bytes" : 204541
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
        "payload_bytes" : 12897
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
        "payload_bytes" : 828
      }, {
        "name" : "sqlite_autoindex_memory_compaction_plan_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 985
      }, {
        "name" : "sqlite_autoindex_memory_compaction_snapshot_1",
        "allocated_bytes" : 20480,
        "payload_bytes" : 13344
      }, {
        "name" : "sqlite_autoindex_memory_corpus_migration_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 18
      }, {
        "name" : "sqlite_autoindex_memory_fact_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 923
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
        "allocated_bytes" : 16384,
        "payload_bytes" : 9283
      }, {
        "name" : "sqlite_autoindex_memory_retrieval_unit_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 1853
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
        "payload_bytes" : 3312
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
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : -0.11111111111111105,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 1,
        "reason" : "At least two independent timelines are required; light single-timeline runs are diagnostic."
      },
      "delta_recall_at_10_g2c_minus_g1" : 0.11111111111111116,
      "delta_recall_at_10_user_cluster_bootstrap_95_ci" : {
        "lower" : null,
        "upper" : null,
        "resamples" : 0,
        "cluster_count" : 1,
        "reason" : "At least two independent timelines are required; light single-timeline runs are diagnostic."
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : 0.11111111111111116,
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
    },
    "token_budget_method" : "estimated; see run-config.json",
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.8888888888888888,
        "recall_at_10" : 0.8888888888888888,
        "precision_at_5" : 0.16,
        "precision_at_10" : 0.08,
        "mrr" : 0.7592592592592592,
        "ndcg_at_10" : 0.749341882926324,
        "recall_at_256_estimated_tokens" : 0.8888888888888888,
        "recall_at_512_estimated_tokens" : 0.8888888888888888,
        "recall_at_1024_estimated_tokens" : 0.8888888888888888,
        "mean_query_context_tokens" : 283.7,
        "mean_duplicate_top10_occupancy" : 6.6
      },
      "G2A" : {
        "recall_at_5" : 0.8888888888888888,
        "recall_at_10" : 0.8888888888888888,
        "precision_at_5" : 0.16,
        "precision_at_10" : 0.08,
        "mrr" : 0.8148148148148148,
        "ndcg_at_10" : 0.7903496880850509,
        "recall_at_256_estimated_tokens" : 0.8888888888888888,
        "recall_at_512_estimated_tokens" : 0.8888888888888888,
        "recall_at_1024_estimated_tokens" : 0.8888888888888888,
        "mean_query_context_tokens" : 284.2,
        "mean_duplicate_top10_occupancy" : 6.3
      },
      "G2C" : {
        "recall_at_5" : 0.7777777777777778,
        "recall_at_10" : 1.0,
        "precision_at_5" : 0.14,
        "precision_at_10" : 0.09,
        "mrr" : 0.5962962962962962,
        "ndcg_at_10" : 0.6492604586430557,
        "recall_at_256_estimated_tokens" : 1.0,
        "recall_at_512_estimated_tokens" : 1.0,
        "recall_at_1024_estimated_tokens" : 1.0,
        "mean_query_context_tokens" : 189.9,
        "mean_duplicate_top10_occupancy" : 3.1
      }
    },
    "query_count" : 10
  },
  "shared_ranker_retrieval" : {
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
      "g2a_ablation_delta_recall_at_10_vs_g1" : -0.1111111111111111,
      "g2a_ablation_delta_recall_at_512_vs_g1" : -0.1111111111111111,
      "primary_metrics" : [ "Delta Recall@5", "Delta Recall@10", "Delta recall at estimated 512 tokens" ]
    },
    "token_budget_method" : "estimated; see run-config.json",
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.1111111111111111,
        "recall_at_10" : 0.2222222222222222,
        "precision_at_5" : 0.12,
        "precision_at_10" : 0.12,
        "mrr" : 0.125,
        "ndcg_at_10" : 0.1461627640873032,
        "recall_at_256_estimated_tokens" : 0.2222222222222222,
        "recall_at_512_estimated_tokens" : 0.2222222222222222,
        "recall_at_1024_estimated_tokens" : 0.2222222222222222,
        "mean_query_context_tokens" : 28.1,
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
        "mean_query_context_tokens" : 34.2,
        "mean_duplicate_top10_occupancy" : 0.3
      },
      "G2C" : {
        "recall_at_5" : 0.1111111111111111,
        "recall_at_10" : 0.2222222222222222,
        "precision_at_5" : 0.12,
        "precision_at_10" : 0.12,
        "mrr" : 0.125,
        "ndcg_at_10" : 0.1461627640873032,
        "recall_at_256_estimated_tokens" : 0.2222222222222222,
        "recall_at_512_estimated_tokens" : 0.2222222222222222,
        "recall_at_1024_estimated_tokens" : 0.2222222222222222,
        "mean_query_context_tokens" : 29.9,
        "mean_duplicate_top10_occupancy" : 0.1
      }
    },
    "query_count" : 10
  },
  "end_to_end" : {
    "answers" : 12,
    "by_group" : {
      "G1" : {
        "correct" : 2,
        "total" : 6,
        "accuracy" : 0.3333333333333333,
        "abstention_correct" : 1,
        "abstention_total" : 1,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 279,
        "state_confusion_rate" : 0.3333333333333333,
        "evidence_support_rate" : 0.8333333333333334,
        "mean_prompt_tokens_reported" : 317.3333333333333,
        "mean_latency_ms" : 2033
      },
      "G2C" : {
        "correct" : 2,
        "total" : 6,
        "accuracy" : 0.3333333333333333,
        "abstention_correct" : 1,
        "abstention_total" : 1,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 156,
        "state_confusion_rate" : 0.3333333333333333,
        "evidence_support_rate" : 1.0,
        "mean_prompt_tokens_reported" : 233.0,
        "mean_latency_ms" : 2223
      }
    },
    "paired" : {
      "primary_comparison" : "G2C minus G1",
      "g2c_minus_g1_accuracy" : 0.0,
      "mcnemar_g1_only_correct" : 0,
      "mcnemar_g2_only_correct" : 0,
      "mcnemar_p_value_approx" : null,
      "inference_status" : "unavailable_single_timeline_light_run"
    }
  },
  "answer_failure_cases" : [ {
    "timeline_id" : "timeline-902",
    "query_id" : "timeline-902-q01",
    "group" : "G1",
    "query_type" : "memory",
    "question" : "现在的居住地是哪座城市？",
    "expected_answer" : "杭州",
    "should_abstain" : false,
    "response" : "你现在住在苏州。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "13", "36", "25", "17", "7", "29", "14", "4", "28", "1" ],
    "context_token_estimate" : 314,
    "elapsed_ms" : 2032,
    "error" : "",
    "prompt_tokens" : 343,
    "completion_tokens" : 105,
    "total_tokens" : 448
  }, {
    "timeline_id" : "timeline-902",
    "query_id" : "timeline-902-q01",
    "group" : "G2C",
    "query_type" : "memory",
    "question" : "现在的居住地是哪座城市？",
    "expected_answer" : "杭州",
    "should_abstain" : false,
    "response" : "记忆中有冲突：杭州和苏州都被记为现居城市，因此无法确定你现在住在哪座城市。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "4376c44d-15fd-38a6-964b-6605778d690c", "6cd14fbd-f7d1-3162-8fcc-996f9f03869f", "dcc60adc-22aa-3bd3-ba2e-92d67f53a351", "baseline-knowledge_graph_entity-8", "e2bed7d4-2150-3039-8710-5181416916cb", "cbd3858f-8ebb-3113-89ba-467c8d689ff5", "baseline-knowledge_graph_entity-58", "06a49a55-0713-3991-8409-b58d4a27ead4", "baseline-knowledge_graph_entity-28", "e4a06e49-09d8-3086-9160-0efdc18fa7d7" ],
    "context_token_estimate" : 153,
    "elapsed_ms" : 1806,
    "error" : "",
    "prompt_tokens" : 232,
    "completion_tokens" : 121,
    "total_tokens" : 353
  }, {
    "timeline_id" : "timeline-902",
    "query_id" : "timeline-902-q02",
    "group" : "G1",
    "query_type" : "memory",
    "question" : "最近一次搬家前住在哪座城市？",
    "expected_answer" : "苏州",
    "should_abstain" : false,
    "response" : "最近一次搬家前住在杭州。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "13", "36", "25", "17", "7", "10", "29", "14", "4", "28" ],
    "context_token_estimate" : 317,
    "elapsed_ms" : 1991,
    "error" : "",
    "prompt_tokens" : 346,
    "completion_tokens" : 66,
    "total_tokens" : 412
  }, {
    "timeline_id" : "timeline-902",
    "query_id" : "timeline-902-q02",
    "group" : "G2C",
    "query_type" : "memory",
    "question" : "最近一次搬家前住在哪座城市？",
    "expected_answer" : "苏州",
    "should_abstain" : false,
    "response" : "不知道。记忆只提到已搬回杭州，没有说明搬家前住在哪座城市。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "79bcdcc7-cace-3b49-9cc3-31c3517993e3", "6cd14fbd-f7d1-3162-8fcc-996f9f03869f", "dcc60adc-22aa-3bd3-ba2e-92d67f53a351", "baseline-knowledge_graph_entity-28", "baseline-knowledge_graph_entity-8", "06a49a55-0713-3991-8409-b58d4a27ead4", "e2bed7d4-2150-3039-8710-5181416916cb", "e4a06e49-09d8-3086-9160-0efdc18fa7d7", "4376c44d-15fd-38a6-964b-6605778d690c", "cbd3858f-8ebb-3113-89ba-467c8d689ff5" ],
    "context_token_estimate" : 151,
    "elapsed_ms" : 2973,
    "error" : "",
    "prompt_tokens" : 230,
    "completion_tokens" : 135,
    "total_tokens" : 365
  }, {
    "timeline_id" : "timeline-902",
    "query_id" : "timeline-902-q04",
    "group" : "G1",
    "query_type" : "memory",
    "question" : "用户希望回复采用哪种形式？",
    "expected_answer" : "简洁步骤",
    "should_abstain" : false,
    "response" : "用户希望回复采用简洁的步骤形式。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "17", "25", "10", "7", "28", "18", "11", "2", "8", "19" ],
    "context_token_estimate" : 264,
    "elapsed_ms" : 1464,
    "error" : "",
    "prompt_tokens" : 306,
    "completion_tokens" : 14,
    "total_tokens" : 320
  }, {
    "timeline_id" : "timeline-902",
    "query_id" : "timeline-902-q04",
    "group" : "G2C",
    "query_type" : "memory",
    "question" : "用户希望回复采用哪种形式？",
    "expected_answer" : "简洁步骤",
    "should_abstain" : false,
    "response" : "用户偏好简洁、按步骤组织的回答。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "baseline-knowledge_graph_entity-34", "52128b4e-6314-3d34-ad23-fd5191b1ad90", "baseline-knowledge_graph_relation-21", "baseline-knowledge_graph_entity-20", "baseline-knowledge_graph_relation-37", "dcc60adc-22aa-3bd3-ba2e-92d67f53a351", "e4a06e49-09d8-3086-9160-0efdc18fa7d7", "6cd14fbd-f7d1-3162-8fcc-996f9f03869f", "f5a41f9d-3a43-35df-b5b6-fa9db16f6bfa", "06a49a55-0713-3991-8409-b58d4a27ead4" ],
    "context_token_estimate" : 163,
    "elapsed_ms" : 891,
    "error" : "",
    "prompt_tokens" : 246,
    "completion_tokens" : 16,
    "total_tokens" : 262
  }, {
    "timeline_id" : "timeline-902",
    "query_id" : "timeline-902-q05",
    "group" : "G1",
    "query_type" : "memory",
    "question" : "下一步还会参加摄影课程吗？",
    "expected_answer" : "取消",
    "should_abstain" : false,
    "response" : "记忆里既有“正在考虑”，也有“已计划参加”的记录；但没有信息确认你现在是否仍会参加。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : false,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "17", "25", "10", "7", "29", "14", "28", "4", "19", "23" ],
    "context_token_estimate" : 253,
    "elapsed_ms" : 2322,
    "error" : "",
    "prompt_tokens" : 300,
    "completion_tokens" : 131,
    "total_tokens" : 431
  }, {
    "timeline_id" : "timeline-902",
    "query_id" : "timeline-902-q05",
    "group" : "G2C",
    "query_type" : "memory",
    "question" : "下一步还会参加摄影课程吗？",
    "expected_answer" : "取消",
    "should_abstain" : false,
    "response" : "目前没有确定会参加：之前的计划已取消，摄影课程现在只是考虑选项。之后是否参加还不知道。",
    "correct_by_frozen_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "e1685767-8a4c-3563-9745-d9d570157c29", "414f9f4d-fd84-39a4-b3b4-eb78732da826", "baseline-knowledge_graph_entity-44", "baseline-knowledge_graph_entity-35", "32628427-0849-3a52-95d8-790be2a4f06c", "baseline-knowledge_graph_entity-67", "baseline-knowledge_graph_relation-36", "baseline-knowledge_graph_relation-45", "f5a41f9d-3a43-35df-b5b6-fa9db16f6bfa", "baseline-knowledge_graph_relation-68" ],
    "context_token_estimate" : 242,
    "elapsed_ms" : 3475,
    "error" : "",
    "prompt_tokens" : 290,
    "completion_tokens" : 115,
    "total_tokens" : 405
  } ],
  "compaction_acceptance" : {
    "oracle_method" : "Conservative feasible corpus estimate using covered clauses, preserving unmatched raw and identical KG; not an exact minimum-cover upper bound or deletion target.",
    "by_timeline" : [ {
      "timeline_id" : "timeline-902",
      "sample_profile" : "redundant",
      "system_compression_rate" : -0.06955177743431218,
      "oracle_upper_rate" : 0.37867078825347755,
      "oracle_efficiency" : -0.18367346938775503,
      "curator_eligible_compression_rate" : -0.13432835820895517,
      "curator_eligible_oracle_upper_rate" : 0.7313432835820896,
      "curator_eligible_oracle_efficiency" : -0.18367346938775503,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.3333333333333333,
      "g2c_answer_accuracy" : 0.3333333333333333,
      "query_context_compression_rate" : 0.33063094818470207,
      "compression_pass" : false,
      "answer_pass" : false,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : false,
      "duplicate_top10_pass" : false,
      "status" : "measured",
      "performance_gates_pass" : false
    } ]
  },
  "model_calls" : {
    "calls" : 56,
    "completion_tokens_reported" : 14644,
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 40,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "p95_ms" : 4573,
          "p50_ms" : 1900,
          "mean_ms" : 2291.275,
          "p99_ms" : 6921,
          "count" : 40
        },
        "prompt_tokens_reported" : 17500,
        "completion_tokens_reported" : 6900,
        "total_tokens_reported" : 24400,
        "calls_with_token_usage" : 40
      },
      "curator" : {
        "calls" : 4,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "p95_ms" : 16657,
          "p50_ms" : 9152,
          "mean_ms" : 11036.0,
          "p99_ms" : 16657,
          "count" : 4
        },
        "prompt_tokens_reported" : 11106,
        "completion_tokens_reported" : 6979,
        "total_tokens_reported" : 18085,
        "calls_with_token_usage" : 4
      },
      "answer" : {
        "calls" : 12,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "p95_ms" : 3475,
          "p50_ms" : 1991,
          "mean_ms" : 2128.5,
          "p99_ms" : 3475,
          "count" : 12
        },
        "prompt_tokens_reported" : 3302,
        "completion_tokens_reported" : 765,
        "total_tokens_reported" : 4067,
        "calls_with_token_usage" : 12
      }
    },
    "prompt_tokens_reported" : 31908,
    "total_tokens_reported" : 46552,
    "calls_with_token_usage" : 56
  },
  "system_performance" : {
    "experiment_wall_ms" : 183416,
    "timeline_count" : 1,
    "baseline_ingestion_per_timeline_ms" : {
      "p95_ms" : 93445,
      "p50_ms" : 93445,
      "mean_ms" : 93445.0,
      "p99_ms" : 93445,
      "count" : 1
    },
    "curator_ingestion_per_timeline_ms" : {
      "p95_ms" : 61222,
      "p50_ms" : 61222,
      "mean_ms" : 61222.0,
      "p99_ms" : 61222,
      "count" : 1
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "p95_ms" : 26428,
      "p50_ms" : 26428,
      "mean_ms" : 26428.0,
      "p99_ms" : 26428,
      "count" : 1
    },
    "query_embedding_latency_ms" : {
      "p95_ms" : 57,
      "p50_ms" : 50,
      "mean_ms" : 50.0,
      "p99_ms" : 57,
      "count" : 10
    },
    "g1_production_retrieval_search_latency_ms" : {
      "p95_ms" : 15,
      "p50_ms" : 2,
      "mean_ms" : 3.7,
      "p99_ms" : 15,
      "count" : 10
    },
    "g2a_append_retrieval_search_latency_ms" : {
      "p95_ms" : 7,
      "p50_ms" : 3,
      "mean_ms" : 3.3,
      "p99_ms" : 7,
      "count" : 10
    },
    "g2c_compaction_retrieval_search_latency_ms" : {
      "p95_ms" : 44,
      "p50_ms" : 24,
      "mean_ms" : 25.5,
      "p99_ms" : 44,
      "count" : 10
    },
    "g1_indexed_corpus" : {
      "unit_count" : 36,
      "estimated_tokens" : 647,
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 182,
        "knowledge_graph_relation" : 130,
        "ordinary_long_term_memory" : 335
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 11,
        "knowledge_graph_relation" : 13,
        "ordinary_long_term_memory" : 12
      }
    },
    "g2a_indexed_corpus" : {
      "unit_count" : 51,
      "estimated_tokens" : 963,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 212,
        "current_profile" : 39,
        "knowledge_graph_entity" : 182,
        "knowledge_graph_relation" : 130,
        "ordinary_long_term_memory" : 335,
        "working_memory" : 65
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 11,
        "current_profile" : 3,
        "knowledge_graph_entity" : 11,
        "knowledge_graph_relation" : 13,
        "ordinary_long_term_memory" : 12,
        "working_memory" : 1
      }
    },
    "g2c_indexed_corpus" : {
      "unit_count" : 40,
      "estimated_tokens" : 692,
      "estimated_tokens_by_memory_type" : {
        "curated_fact" : 126,
        "curated_knowledge_graph_entity" : 182,
        "curated_knowledge_graph_relation" : 130,
        "ordinary_long_term_memory" : 254
      },
      "unit_count_by_memory_type" : {
        "curated_fact" : 11,
        "curated_knowledge_graph_entity" : 11,
        "curated_knowledge_graph_relation" : 13,
        "ordinary_long_term_memory" : 5
      }
    },
    "g1_sqlite_footprint_bytes" : {
      "main_db_bytes" : 577536,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768,
      "wal_bytes" : 4120032,
      "total_observed_bytes" : 4730336
    },
    "g2_sqlite_footprint_bytes" : {
      "main_db_bytes" : 1085440,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768,
      "wal_bytes" : 4128272,
      "total_observed_bytes" : 5246480
    },
    "llm_calls_by_stage" : {
      "calls" : 56,
      "completion_tokens_reported" : 14644,
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 40,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "p95_ms" : 4573,
            "p50_ms" : 1900,
            "mean_ms" : 2291.275,
            "p99_ms" : 6921,
            "count" : 40
          },
          "prompt_tokens_reported" : 17500,
          "completion_tokens_reported" : 6900,
          "total_tokens_reported" : 24400,
          "calls_with_token_usage" : 40
        },
        "curator" : {
          "calls" : 4,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "p95_ms" : 16657,
            "p50_ms" : 9152,
            "mean_ms" : 11036.0,
            "p99_ms" : 16657,
            "count" : 4
          },
          "prompt_tokens_reported" : 11106,
          "completion_tokens_reported" : 6979,
          "total_tokens_reported" : 18085,
          "calls_with_token_usage" : 4
        },
        "answer" : {
          "calls" : 12,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "p95_ms" : 3475,
            "p50_ms" : 1991,
            "mean_ms" : 2128.5,
            "p99_ms" : 3475,
            "count" : 12
          },
          "prompt_tokens_reported" : 3302,
          "completion_tokens_reported" : 765,
          "total_tokens_reported" : 4067,
          "calls_with_token_usage" : 12
        }
      },
      "prompt_tokens_reported" : 31908,
      "total_tokens_reported" : 46552,
      "calls_with_token_usage" : 56
    },
    "baseline_turns_per_second_sum_timeline_time" : 0.42805928621114026
  },
  "failures" : {
    "count" : 0,
    "by_reason" : { }
  },
  "acceptance_pass" : false,
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory baseline. G2A and G2C reuse the same accepted real MemoryCuratorService output so the ablation isolates append-only versus compacted serving behavior.", "G1, G2A, and G2C all retain the same traceable KG entities and relations for retrieval; the G2C KG sidecar is unchanged by compaction and counted in its serving tokens.", "G1 and G2A use the production SqliteMemoryService hybrid retriever. G2C merges relevant unmapped raw candidates with production corpus ranking, with an explicit threshold and semantic de-duplication. A frozen shared text ranker provides corpus-only retrieval diagnostics.", "Retrieval reports Recall@5 and Recall@10; Recall@1 and Recall@3 are not reported.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness is determined by frozen, per-query string and abstention rules; no model-generated gold labels are used.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run.", "Formal paired effects compare G2C with G1. G2A is reported as an append-only ablation." ]
}
```
