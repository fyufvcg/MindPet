# Memory Curator LLM experiment

Dataset: `memory-curator-value-300-v2.jsonl` (SHA-256 `10d4ef9f681c94fdfdc7ca046563d0225a2196c910232b8aa55bf86a50b57ffe`)

This report is generated from real LLM calls through the production Java KnowledgeGraphService, MemoryCuratorService, MemoryCorpusCompactionService, and SqliteMemoryService. Fixture output is not used as experiment data. The curator was invoked, but none of its commit attempts was accepted. The planned compacted G2C-versus-G1 effect was therefore not measured; G2C retrieval and answer scores describe the database state left by this failed run and are diagnostic only.

Sample: **16 timelines, 640 memory-input turns, 160 retrieval questions**.

Acceptance profile: **relaxed screening** — **NOT PASSED**. The 30% full-corpus compression target is reported separately and is not implied by a relaxed screening pass. The sample is a screening evaluation, not the complete locked-test cohort.

## Run validity and interpretation

This run processed **640 memory-input turns** across 16 timelines and **160 questions**. The LLM produced 320 answers (160 each for G1 and G2C). All 1,090 recorded LLM requests completed without API errors: 640 baseline extraction calls, 130 curator calls, and 320 answer calls.

The curator did **not** complete a successful commit: all 16 timelines ended at checkpoint 0 with 40 turns pending; all 65 curator-run attempts failed and saved zero memories. Validator rejection counts across retries were 162 unsupported predicates, 105 polarity/uncertainty mismatches, and 16 values not supported by evidence. The database has zero `memory_fact` rows and an empty `memory_compaction_plan`; its 545 compaction-log entries are all `KEEP` actions. The G2C retrieval index still contains 545 raw-memory units and the unchanged KG sidecar.

| Metric | G1 | G2C diagnostic state |
| --- | ---: | ---: |
| LLM answer correctness, strict rules | 147/160 (91.9%) | 149/160 (93.1%) |
| LLM answer correctness, relaxed core facts | 148/160 (92.5%) | 149/160 (93.1%) |
| Recall@5 | 73.6% | 96.5% |
| Recall@10 | 97.2% | 99.0% |
| Mean retrieval search latency | 3.3 ms | 42.8 ms |
| Mean LLM answer latency | 3.38 s | 3.51 s |

The relaxed answer-score difference is only **+0.6 percentage points** for G2C; the paired diagnostic test is inconclusive (approximate McNemar p=0.80). State-confusion errors were more frequent in G2C (3/160) than G1 (1/160).

The measured corpus estimate is 21,491 tokens for G2C versus 22,180 for G1, a 3.1% difference. Because no curator proposal was committed and the G2C index retains raw memories, this is **not a valid measure of curator compression** and does not demonstrate the 30% target. The 16-timeline Recall and answer scores are diagnostic comparisons of the current branches, not evidence that successful compression improved them. Retention@5 is not estimable from 40-turn timelines with a 15-turn curator trigger.

## Failed answer cases

- timeline-011 / timeline-011-q09-multi_fact / G2C: state_confusion; response: 现在居住在苏州。当前职位没有相关信息；你之前在无锡生活时从事产品经理。
- timeline-012 / timeline-012-q04-current-job / G2C: state_confusion; response: 你目前从事项目运营，之前做过软件工程师。
- timeline-012 / timeline-012-q06-preference / G1: answer_rule_failure; response: 先给结论，再列简洁步骤。
- timeline-012 / timeline-012-q06-preference / G2C: answer_rule_failure; response: 先给结论，再列简洁步骤。
- timeline-061 / timeline-061-q01-current-city / G1: state_confusion; response: 合肥。你最近确认目前住在合肥；无锡只是考虑过的去向。
- timeline-061 / timeline-061-q06-preference / G1: answer_rule_failure; response: 结论：先给结论，再列简洁步骤。
- timeline-062 / timeline-062-q04-current-job / G2C: state_confusion; response: 你现在从事项目运营，负责门店库存改造项目。过去做过软件工程师。
- timeline-062 / timeline-062-q06-preference / G1: answer_rule_failure; response: 结论：先给结论，再列简洁步骤。
- timeline-062 / timeline-062-q06-preference / G2C: answer_rule_failure; response: 先给结论，再列简洁步骤。
- timeline-086 / timeline-086-q04-current-job / G2C: answer_rule_failure; response: 目前从事视觉设计工作。
- timeline-087 / timeline-087-q09-multi_fact / G1: answer_rule_failure; response: 现在长期居住在青岛，职位是数据分析师。
- timeline-161 / timeline-161-q06-preference / G2C: answer_rule_failure; response: 先给结论，再列简洁步骤。
- timeline-161 / timeline-161-q09-multi_fact / G1: missing_retrieved_evidence; response: 结论：你现在长期居住在苏州，职位是交互设计师。
- timeline-186 / timeline-186-q04-current-job / G2C: answer_rule_failure; response: 目前从事视觉设计工作。
- timeline-211 / timeline-211-q04-current-job / G2C: answer_rule_failure; response: 你现在从事交互设计工作。
- timeline-211 / timeline-211-q06-preference / G1: missing_retrieved_evidence; response: 不知道；当前没有检索到相关记忆。
- timeline-211 / timeline-211-q08-focus / G1: missing_retrieved_evidence; response: 没有检索到相关记忆，所以不知道提到的活动是什么，也无法确认日期。
- timeline-236 / timeline-236-q06-preference / G1: answer_rule_failure; response: 结论：先给结论，再列简洁步骤。
- timeline-236 / timeline-236-q08-focus / G1: missing_retrieved_evidence; response: 结论：可检索记忆中没有提到具体活动，也没有活动日期，因此无法判断日期是否确定。
- timeline-236 / timeline-236-q08-focus / G2C: missing_retrieved_evidence; response: 提到的活动是考虑搬去合肥；目前尚未决定或搬迁，日期也没有确定。
- timeline-236 / timeline-236-q09-multi_fact / G1: answer_rule_failure; response: 居住城市：无锡。职位：视觉设计师。
- timeline-261 / timeline-261-q06-preference / G1: answer_rule_failure; response: 结论：先给结论，再列简洁步骤。
- timeline-261 / timeline-261-q06-preference / G2C: answer_rule_failure; response: 先给结论，再列简洁步骤。

```json
{
  "dataset_version" : [ "memory-curator-value-v2" ],
  "selected_timelines" : 16,
  "selected_turns" : 640,
  "selected_queries" : 160,
  "curator_repair_calls" : 130,
  "answer_queries" : 160,
  "answer_calls_expected" : 320,
  "fact_quality" : {
    "true_positive" : 0,
    "false_positive" : 0,
    "false_negative" : 130,
    "precision" : 0.0,
    "recall" : 0.0,
    "f1" : 0.0,
    "stored_fact_rows" : 0,
    "exact_match_duplicate_rows" : 0,
    "exact_match_duplicate_rate" : 0.0,
    "semantic_duplicate_rows" : 0,
    "semantic_duplicate_rate" : 0.0,
    "distinct_state_interval_rows" : 0,
    "redundant_semantic_rows" : 0,
    "redundant_semantic_rate" : 0.0,
    "semantic_unique_fact_quality" : {
      "semantic_duplicate_rate" : 0.0,
      "false_negative" : 130,
      "true_positive" : 0,
      "false_positive" : 0,
      "recall" : 0.0,
      "f1" : 0.0,
      "semantic_duplicate_rows" : 0,
      "matching_policy" : "predicate/value/scope/assertion; temporal fields are evaluated separately",
      "precision" : 0.0
    },
    "strict_row_matching_policy" : "predicate/value/scope/assertion plus expected time status and resolved start date; non-overlapping state intervals are valid repeated rows; recall uses unique matched gold; unmatched rows are not necessarily hallucinations",
    "semantic_repetition_policy" : "semantic_duplicate_rows counts repeated meanings including legitimate state returns; distinct_state_interval_rows and redundant_semantic_rows separate them; intervals lacking provable non-overlap remain redundant",
    "by_timeline" : [ {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-011",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "stable_preferences",
      "timeline_id" : "timeline-012",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "current_state",
      "timeline_id" : "timeline-036",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "current_state",
      "timeline_id" : "timeline-037",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "semantic_repetition",
      "timeline_id" : "timeline-061",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "semantic_repetition",
      "timeline_id" : "timeline-062",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "explicit_correction",
      "timeline_id" : "timeline-086",
      "expected" : 9,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "explicit_correction",
      "timeline_id" : "timeline-087",
      "expected" : 9,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "negation_withdrawal",
      "timeline_id" : "timeline-111",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "history_vs_current",
      "timeline_id" : "timeline-136",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "plans_vs_reality",
      "timeline_id" : "timeline-161",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "cross_session_memory",
      "timeline_id" : "timeline-186",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "explicit_event_time",
      "timeline_id" : "timeline-211",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "ambiguous_event_time",
      "timeline_id" : "timeline-236",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "noise_and_abstention",
      "timeline_id" : "timeline-261",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    }, {
      "primary_scenario" : "prompt_injection_safety",
      "timeline_id" : "timeline-286",
      "expected" : 8,
      "stored" : 0,
      "true_positive" : 0,
      "duplicate_rows" : 0,
      "semantic_true_positive" : 0,
      "semantic_duplicate_rows" : 0,
      "distinct_state_interval_rows" : 0,
      "redundant_semantic_rows" : 0
    } ]
  },
  "g1_kg_fact_quality" : {
    "precision" : 0.032640949554896145,
    "recall" : 0.08461538461538462,
    "by_timeline" : [ {
      "unmatched_kg_relations" : 25,
      "timeline_id" : "timeline-011",
      "matched_gold" : 0,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 20,
      "timeline_id" : "timeline-012",
      "matched_gold" : 2,
      "duplicate_relations" : 2
    }, {
      "unmatched_kg_relations" : 18,
      "timeline_id" : "timeline-036",
      "matched_gold" : 1,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 24,
      "timeline_id" : "timeline-037",
      "matched_gold" : 0,
      "duplicate_relations" : 1
    }, {
      "unmatched_kg_relations" : 15,
      "timeline_id" : "timeline-061",
      "matched_gold" : 1,
      "duplicate_relations" : 2
    }, {
      "unmatched_kg_relations" : 18,
      "timeline_id" : "timeline-062",
      "matched_gold" : 2,
      "duplicate_relations" : 2
    }, {
      "unmatched_kg_relations" : 21,
      "timeline_id" : "timeline-086",
      "matched_gold" : 2,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 27,
      "timeline_id" : "timeline-087",
      "matched_gold" : 1,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 23,
      "timeline_id" : "timeline-111",
      "matched_gold" : 0,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 20,
      "timeline_id" : "timeline-136",
      "matched_gold" : 0,
      "duplicate_relations" : 1
    }, {
      "unmatched_kg_relations" : 15,
      "timeline_id" : "timeline-161",
      "matched_gold" : 1,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 18,
      "timeline_id" : "timeline-186",
      "matched_gold" : 0,
      "duplicate_relations" : 1
    }, {
      "unmatched_kg_relations" : 20,
      "timeline_id" : "timeline-211",
      "matched_gold" : 0,
      "duplicate_relations" : 1
    }, {
      "unmatched_kg_relations" : 22,
      "timeline_id" : "timeline-236",
      "matched_gold" : 1,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 18,
      "timeline_id" : "timeline-261",
      "matched_gold" : 0,
      "duplicate_relations" : 0
    }, {
      "unmatched_kg_relations" : 22,
      "timeline_id" : "timeline-286",
      "matched_gold" : 0,
      "duplicate_relations" : 1
    } ],
    "method" : "Conservative KG relation projection by visible text; unsupported relations remain unmatched. No source-turn label inheritance."
  },
  "profile_quality" : {
    "recall" : 0.0,
    "precision" : 0.0,
    "written_entries" : 0,
    "expected_entries" : 62,
    "by_timeline" : [ {
      "expected" : {
        "home_location" : "合肥",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "timeline_id" : "timeline-011",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "昆明",
        "current_location" : "杭州",
        "occupation_current" : "项目运营",
        "current_project" : "门店库存改造项目"
      },
      "timeline_id" : "timeline-012",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "无锡",
        "current_location" : "厦门",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "timeline_id" : "timeline-036",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "天津",
        "current_location" : "青岛",
        "occupation_current" : "数据分析师",
        "current_project" : "供应链监控平台"
      },
      "timeline_id" : "timeline-037",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "苏州",
        "current_location" : "合肥",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "timeline_id" : "timeline-061",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "杭州",
        "current_location" : "昆明",
        "occupation_current" : "项目运营",
        "current_project" : "门店库存改造项目"
      },
      "timeline_id" : "timeline-062",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "厦门",
        "current_location" : "无锡",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "timeline_id" : "timeline-086",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "青岛",
        "current_location" : "天津",
        "occupation_current" : "数据分析师",
        "current_project" : "供应链监控平台"
      },
      "timeline_id" : "timeline-087",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "合肥",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "timeline_id" : "timeline-111",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "无锡",
        "current_location" : "厦门",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "timeline_id" : "timeline-136",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "苏州",
        "current_location" : "合肥",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "timeline_id" : "timeline-161",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "厦门",
        "current_location" : "无锡",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "timeline_id" : "timeline-186",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "合肥",
        "current_location" : "苏州",
        "occupation_current" : "交互设计师"
      },
      "timeline_id" : "timeline-211",
      "expected_count" : 3,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "无锡",
        "current_location" : "厦门",
        "occupation_current" : "视觉设计师"
      },
      "timeline_id" : "timeline-236",
      "expected_count" : 3,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "苏州",
        "current_location" : "合肥",
        "occupation_current" : "交互设计师",
        "current_project" : "校园服务平台"
      },
      "timeline_id" : "timeline-261",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    }, {
      "expected" : {
        "home_location" : "厦门",
        "current_location" : "无锡",
        "occupation_current" : "视觉设计师",
        "current_project" : "影像归档工具"
      },
      "timeline_id" : "timeline-286",
      "expected_count" : 4,
      "actual" : { },
      "correct" : 0
    } ],
    "fully_correct_timelines" : 0,
    "expected_entries_correct" : 0
  },
  "retention" : {
    "retention_at_1" : null,
    "retention_at_3" : null,
    "retention_at_5" : null,
    "eligible_facts_at_1" : 0,
    "eligible_facts_at_3" : 0,
    "reason_at_5" : "40 turns with production 15-turn triggers produce at most three commits per timeline"
  },
  "compression" : {
    "actual_g1_retrieval_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 5649,
        "knowledge_graph_relation" : 3889,
        "ordinary_long_term_memory" : 12642
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 263,
        "knowledge_graph_relation" : 352,
        "ordinary_long_term_memory" : 576
      },
      "unit_count" : 1191,
      "estimated_tokens" : 22180
    },
    "actual_g2a_append_retrieval_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 5649,
        "knowledge_graph_relation" : 3889,
        "ordinary_long_term_memory" : 12642,
        "working_memory" : 32
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 263,
        "knowledge_graph_relation" : 352,
        "ordinary_long_term_memory" : 576,
        "working_memory" : 16
      },
      "unit_count" : 1207,
      "estimated_tokens" : 22212
    },
    "actual_g2c_compacted_retrieval_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "curated_knowledge_graph_entity" : 5649,
        "curated_knowledge_graph_relation" : 3889,
        "ordinary_long_term_memory" : 11953
      },
      "unit_count_by_memory_type" : {
        "curated_knowledge_graph_entity" : 263,
        "curated_knowledge_graph_relation" : 352,
        "ordinary_long_term_memory" : 545
      },
      "unit_count" : 1160,
      "estimated_tokens" : 21491
    },
    "actual_g2a_token_delta_vs_g1" : 0.001442741208295762,
    "actual_g2c_token_delta_vs_g1" : -0.031064021641118125,
    "actual_g2c_system_compression_rate" : 0.0310640216411181,
    "actual_g2c_token_delta_vs_g2a" : -0.03245993156852152,
    "g2c_compaction_action_counts" : { },
    "g2c_logged_net_token_reduction" : 0,
    "raw_turn_text_tokens_estimated" : 14166,
    "curated_fact_projection_tokens_estimated" : 0,
    "curated_fact_projection_compression_estimate" : 1.0,
    "raw_turn_text_characters" : 14208,
    "curated_fact_projection_characters" : 0,
    "curator_eligible_compression_rate" : 0.0545008701154881,
    "information_retention_rate" : 1.0,
    "projection_note" : "Serving corpus is the union of default, historical, and planned searchable units. KG is identical across arms. Physical storage and archived evidence are reported separately.",
    "estimator" : "CJK code point=1, ASCII alphanumeric runs=ceil(length/4), punctuation=1"
  },
  "temporal" : {
    "exact_time_expected" : 35,
    "exact_time_correct" : 0,
    "exact_time_accuracy" : 0.0,
    "ambiguous_time_expected" : 1,
    "ambiguous_time_correct" : 0,
    "ambiguous_time_accuracy" : 0.0
  },
  "source_evidence" : {
    "stored_facts" : 0,
    "valid_source_turns" : 0,
    "source_completeness" : 0.0,
    "verbatim_evidence" : 0,
    "verbatim_evidence_rate" : 0.0,
    "active_retrieval_units" : 1160,
    "retrieval_unit_source_traceability" : 1160,
    "retrieval_unit_source_traceability_rate" : 1.0
  },
  "integrity" : {
    "by_timeline" : [ {
      "timeline_id" : "timeline-011",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 35,
      "g2c_mean_duplicate_top10_occupancy" : 3.5,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-012",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 40,
      "g2c_mean_duplicate_top10_occupancy" : 4.0,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-036",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 39,
      "g2c_mean_duplicate_top10_occupancy" : 3.9,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-037",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 35,
      "g2c_mean_duplicate_top10_occupancy" : 3.5,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-061",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 47,
      "g2c_mean_duplicate_top10_occupancy" : 4.7,
      "duplicate_top10_pass" : false,
      "pass" : false
    }, {
      "timeline_id" : "timeline-062",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 39,
      "g2c_mean_duplicate_top10_occupancy" : 3.9,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-086",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 36,
      "g2c_mean_duplicate_top10_occupancy" : 3.6,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-087",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 40,
      "g2c_mean_duplicate_top10_occupancy" : 4.0,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-111",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 34,
      "g2c_mean_duplicate_top10_occupancy" : 3.4,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-136",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 33,
      "g2c_mean_duplicate_top10_occupancy" : 3.3,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-161",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 36,
      "g2c_mean_duplicate_top10_occupancy" : 3.6,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-186",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 37,
      "g2c_mean_duplicate_top10_occupancy" : 3.7,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-211",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 38,
      "g2c_mean_duplicate_top10_occupancy" : 3.8,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-236",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 27,
      "g2c_mean_duplicate_top10_occupancy" : 2.7,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-261",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 35,
      "g2c_mean_duplicate_top10_occupancy" : 3.5,
      "duplicate_top10_pass" : true,
      "pass" : true
    }, {
      "timeline_id" : "timeline-286",
      "missing_source_retirements" : 0,
      "insufficient_coverage_retirements" : 0,
      "active_state_conflict_slots" : 0,
      "future_source_failures" : 0,
      "g2c_duplicate_top10_occupancy" : 39,
      "g2c_mean_duplicate_top10_occupancy" : 3.9,
      "duplicate_top10_pass" : true,
      "pass" : true
    } ],
    "pass" : false
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
        "allocated_bytes" : 20480,
        "payload_bytes" : 13415
      }, {
        "name" : "idx_kg_evidence_user_created",
        "allocated_bytes" : 90112,
        "payload_bytes" : 73072
      }, {
        "name" : "idx_kg_relation_user_source",
        "allocated_bytes" : 28672,
        "payload_bytes" : 19936
      }, {
        "name" : "idx_kg_relation_user_target",
        "allocated_bytes" : 28672,
        "payload_bytes" : 19936
      }, {
        "name" : "idx_llm_growth_source",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_ltm_user_created",
        "allocated_bytes" : 61440,
        "payload_bytes" : 47512
      }, {
        "name" : "idx_ltm_user_importance",
        "allocated_bytes" : 73728,
        "payload_bytes" : 58231
      }, {
        "name" : "idx_ltm_user_searchable",
        "allocated_bytes" : 65536,
        "payload_bytes" : 48703
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
        "allocated_bytes" : 36864,
        "payload_bytes" : 28592
      }, {
        "name" : "idx_sessions_user_updated",
        "allocated_bytes" : 4096,
        "payload_bytes" : 3199
      }, {
        "name" : "kg_entity",
        "allocated_bytes" : 1224704,
        "payload_bytes" : 1130650
      }, {
        "name" : "kg_evidence",
        "allocated_bytes" : 462848,
        "payload_bytes" : 429068
      }, {
        "name" : "kg_relation",
        "allocated_bytes" : 77824,
        "payload_bytes" : 67658
      }, {
        "name" : "kg_turn_ingest",
        "allocated_bytes" : 94208,
        "payload_bytes" : 82540
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
        "allocated_bytes" : 5492736,
        "payload_bytes" : 5102729
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
        "allocated_bytes" : 266240,
        "payload_bytes" : 238334
      }, {
        "name" : "sessions",
        "allocated_bytes" : 12288,
        "payload_bytes" : 6640
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
        "allocated_bytes" : 16384,
        "payload_bytes" : 11311
      }, {
        "name" : "sqlite_autoindex_kg_entity_2",
        "allocated_bytes" : 20480,
        "payload_bytes" : 11973
      }, {
        "name" : "sqlite_autoindex_kg_evidence_1",
        "allocated_bytes" : 204800,
        "payload_bytes" : 163030
      }, {
        "name" : "sqlite_autoindex_kg_evidence_2",
        "allocated_bytes" : 204800,
        "payload_bytes" : 162454
      }, {
        "name" : "sqlite_autoindex_kg_relation_1",
        "allocated_bytes" : 24576,
        "payload_bytes" : 14304
      }, {
        "name" : "sqlite_autoindex_kg_relation_2",
        "allocated_bytes" : 45056,
        "payload_bytes" : 36292
      }, {
        "name" : "sqlite_autoindex_kg_turn_ingest_1",
        "allocated_bytes" : 57344,
        "payload_bytes" : 44672
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
        "allocated_bytes" : 53248,
        "payload_bytes" : 40832
      }, {
        "name" : "sqlite_autoindex_sessions_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 3359
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
    },
    "G2" : {
      "allocated_pages" : [ {
        "name" : "conversation_memory",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "curator_runs",
        "allocated_bytes" : 28672,
        "payload_bytes" : 20576
      }, {
        "name" : "curator_state",
        "allocated_bytes" : 4096,
        "payload_bytes" : 2173
      }, {
        "name" : "curator_turns",
        "allocated_bytes" : 151552,
        "payload_bytes" : 136864
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
        "allocated_bytes" : 40960,
        "payload_bytes" : 31232
      }, {
        "name" : "idx_curator_turns_user_completed",
        "allocated_bytes" : 45056,
        "payload_bytes" : 32314
      }, {
        "name" : "idx_curator_turns_user_seq",
        "allocated_bytes" : 24576,
        "payload_bytes" : 14464
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
        "allocated_bytes" : 118784,
        "payload_bytes" : 96999
      }, {
        "name" : "idx_ltm_user_importance",
        "allocated_bytes" : 147456,
        "payload_bytes" : 118581
      }, {
        "name" : "idx_ltm_user_searchable",
        "allocated_bytes" : 126976,
        "payload_bytes" : 99397
      }, {
        "name" : "idx_memory_compaction_batch_user_status",
        "allocated_bytes" : 4096,
        "payload_bytes" : 0
      }, {
        "name" : "idx_memory_compaction_log_user_batch",
        "allocated_bytes" : 20480,
        "payload_bytes" : 12824
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
        "allocated_bytes" : 36864,
        "payload_bytes" : 24232
      }, {
        "name" : "idx_memory_retrieval_source_turn",
        "allocated_bytes" : 102400,
        "payload_bytes" : 75397
      }, {
        "name" : "idx_memory_retrieval_user_status",
        "allocated_bytes" : 57344,
        "payload_bytes" : 41562
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
        "allocated_bytes" : 36864,
        "payload_bytes" : 28592
      }, {
        "name" : "idx_sessions_user_updated",
        "allocated_bytes" : 4096,
        "payload_bytes" : 3199
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
        "allocated_bytes" : 11055104,
        "payload_bytes" : 10274657
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
        "allocated_bytes" : 69632,
        "payload_bytes" : 61350
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
        "payload_bytes" : 624
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
        "allocated_bytes" : 606208,
        "payload_bytes" : 533951
      }, {
        "name" : "memory_retrieval_unit",
        "allocated_bytes" : 5349376,
        "payload_bytes" : 5053575
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
        "allocated_bytes" : 266240,
        "payload_bytes" : 238334
      }, {
        "name" : "sessions",
        "allocated_bytes" : 12288,
        "payload_bytes" : 6640
      }, {
        "name" : "sqlite_autoindex_curator_state_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 303
      }, {
        "name" : "sqlite_autoindex_curator_turns_1",
        "allocated_bytes" : 36864,
        "payload_bytes" : 26112
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
        "payload_bytes" : 303
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
        "allocated_bytes" : 258048,
        "payload_bytes" : 212838
      }, {
        "name" : "sqlite_autoindex_memory_retrieval_unit_1",
        "allocated_bytes" : 61440,
        "payload_bytes" : 47826
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
        "allocated_bytes" : 53248,
        "payload_bytes" : 40832
      }, {
        "name" : "sqlite_autoindex_sessions_1",
        "allocated_bytes" : 4096,
        "payload_bytes" : 3359
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
        "payload_bytes" : 81
      }, {
        "name" : "uq_memory_retrieval_active_key",
        "allocated_bytes" : 114688,
        "payload_bytes" : 97777
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
    "synthetic_sensitive_cases" : 1,
    "fully_blocked" : 1,
    "blocking_rate" : 1.0,
    "by_timeline" : [ {
      "g2a_appended_memory_hits" : 0,
      "timeline_id" : "timeline-286",
      "g2c_sensitive_hits" : 0
    } ],
    "scanned_tables" : [ "long_term_memory", "memory_fact", "user_profile_current", "user_insight", "llm_growth", "memory_retrieval_unit", "memory_retrieval_source", "kg_entity", "kg_relation", "kg_evidence" ]
  },
  "retrieval" : {
    "query_count" : 160,
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : 0.22916666666666666,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "lower" : 0.1840277777777778,
        "upper" : 0.2743055555555556,
        "cluster_count" : 16,
        "resamples" : 10000
      },
      "delta_recall_at_10_g2c_minus_g1" : 0.017361111111111112,
      "delta_recall_at_10_user_cluster_bootstrap_95_ci" : {
        "lower" : -0.0034722222222222238,
        "upper" : 0.04861111111111112,
        "cluster_count" : 16,
        "resamples" : 10000
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : 0.017361111111111112,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "lower" : -0.0034722222222222238,
        "upper" : 0.04861111111111112,
        "cluster_count" : 16,
        "resamples" : 10000
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_10_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.0,
      "primary_metrics" : [ "Delta Recall@5", "Delta Recall@10", "Delta recall at estimated 512 tokens" ]
    },
    "token_budget_method" : "estimated; see run-config.json",
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.7361111111111112,
        "recall_at_10" : 0.9722222222222222,
        "precision_at_5" : 0.16125,
        "precision_at_10" : 0.113125,
        "mrr" : 0.5175870811287477,
        "ndcg_at_10" : 0.6209202232330542,
        "recall_at_256_estimated_tokens" : 0.9722222222222222,
        "recall_at_512_estimated_tokens" : 0.9722222222222222,
        "recall_at_1024_estimated_tokens" : 0.9722222222222222,
        "mean_query_context_tokens" : 218.28125,
        "mean_duplicate_top10_occupancy" : 3.7625
      },
      "G2A" : {
        "recall_at_5" : 0.7361111111111112,
        "recall_at_10" : 0.9722222222222222,
        "precision_at_5" : 0.16125,
        "precision_at_10" : 0.113125,
        "mrr" : 0.5175870811287477,
        "ndcg_at_10" : 0.6209202232330542,
        "recall_at_256_estimated_tokens" : 0.9722222222222222,
        "recall_at_512_estimated_tokens" : 0.9722222222222222,
        "recall_at_1024_estimated_tokens" : 0.9722222222222222,
        "mean_query_context_tokens" : 218.325,
        "mean_duplicate_top10_occupancy" : 3.7625
      },
      "G2C" : {
        "recall_at_5" : 0.9652777777777778,
        "recall_at_10" : 0.9895833333333334,
        "precision_at_5" : 0.21125000000000002,
        "precision_at_10" : 0.10875000000000001,
        "mrr" : 0.8800925925925926,
        "ndcg_at_10" : 0.8944637811515785,
        "recall_at_256_estimated_tokens" : 0.9895833333333334,
        "recall_at_512_estimated_tokens" : 0.9895833333333334,
        "recall_at_1024_estimated_tokens" : 0.9895833333333334,
        "mean_query_context_tokens" : 195.525,
        "mean_duplicate_top10_occupancy" : 3.6875
      }
    }
  },
  "shared_ranker_retrieval" : {
    "query_count" : 160,
    "paired_effects" : {
      "primary_comparison" : "G2C minus G1",
      "delta_recall_at_5_g2c_minus_g1" : 0.0,
      "delta_recall_at_5_user_cluster_bootstrap_95_ci" : {
        "lower" : 0.0,
        "upper" : 0.0,
        "cluster_count" : 16,
        "resamples" : 10000
      },
      "delta_recall_at_10_g2c_minus_g1" : 0.0,
      "delta_recall_at_10_user_cluster_bootstrap_95_ci" : {
        "lower" : 0.0,
        "upper" : 0.0,
        "cluster_count" : 16,
        "resamples" : 10000
      },
      "delta_recall_at_512_estimated_tokens_g2c_minus_g1" : 0.0,
      "delta_recall_at_512_user_cluster_bootstrap_95_ci" : {
        "lower" : 0.0,
        "upper" : 0.0,
        "cluster_count" : 16,
        "resamples" : 10000
      },
      "g2a_ablation_delta_recall_at_5_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_10_vs_g1" : 0.0,
      "g2a_ablation_delta_recall_at_512_vs_g1" : 0.0,
      "primary_metrics" : [ "Delta Recall@5", "Delta Recall@10", "Delta recall at estimated 512 tokens" ]
    },
    "token_budget_method" : "estimated; see run-config.json",
    "by_group" : {
      "G1" : {
        "recall_at_5" : 0.125,
        "recall_at_10" : 0.125,
        "precision_at_5" : 0.12249999999999998,
        "precision_at_10" : 0.11125,
        "mrr" : 0.125,
        "ndcg_at_10" : 0.125,
        "recall_at_256_estimated_tokens" : 0.125,
        "recall_at_512_estimated_tokens" : 0.125,
        "recall_at_1024_estimated_tokens" : 0.125,
        "mean_query_context_tokens" : 2.35625,
        "mean_duplicate_top10_occupancy" : 0.0
      },
      "G2A" : {
        "recall_at_5" : 0.125,
        "recall_at_10" : 0.125,
        "precision_at_5" : 0.12249999999999998,
        "precision_at_10" : 0.11125,
        "mrr" : 0.125,
        "ndcg_at_10" : 0.125,
        "recall_at_256_estimated_tokens" : 0.125,
        "recall_at_512_estimated_tokens" : 0.125,
        "recall_at_1024_estimated_tokens" : 0.125,
        "mean_query_context_tokens" : 2.35625,
        "mean_duplicate_top10_occupancy" : 0.0
      },
      "G2C" : {
        "recall_at_5" : 0.125,
        "recall_at_10" : 0.125,
        "precision_at_5" : 0.12249999999999998,
        "precision_at_10" : 0.11125,
        "mrr" : 0.125,
        "ndcg_at_10" : 0.125,
        "recall_at_256_estimated_tokens" : 0.125,
        "recall_at_512_estimated_tokens" : 0.125,
        "recall_at_1024_estimated_tokens" : 0.125,
        "mean_query_context_tokens" : 2.35625,
        "mean_duplicate_top10_occupancy" : 0.0
      }
    }
  },
  "acceptance_profile" : "relaxed",
  "end_to_end" : {
    "paired" : {
      "primary_comparison" : "G2C minus G1",
      "g2c_minus_g1_accuracy" : 0.0125,
      "g1_only_approximate_correct" : 7,
      "g2c_only_approximate_correct" : 8,
      "g2c_minus_g1_approximate_accuracy" : 0.00625,
      "mcnemar_g1_only_correct" : 7,
      "mcnemar_g2_only_correct" : 9,
      "mcnemar_p_value_approx" : 0.8025873839525541,
      "inference_status" : "diagnostic_query_pair_test"
    },
    "answers" : 320,
    "by_group" : {
      "G1" : {
        "correct" : 147,
        "total" : 160,
        "accuracy" : 0.91875,
        "approximate_correct" : 148,
        "approximate_accuracy" : 0.925,
        "mean_core_fact_coverage_rate" : 0.96875,
        "abstention_correct" : 16,
        "abstention_total" : 16,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 218,
        "state_confusion_rate" : 0.00625,
        "evidence_support_rate" : 0.96875,
        "mean_prompt_tokens_reported" : 282.10625,
        "mean_latency_ms" : 3380
      },
      "G2C" : {
        "correct" : 149,
        "total" : 160,
        "accuracy" : 0.93125,
        "approximate_correct" : 149,
        "approximate_accuracy" : 0.93125,
        "mean_core_fact_coverage_rate" : 0.971875,
        "abstention_correct" : 16,
        "abstention_total" : 16,
        "abstention_accuracy" : 1.0,
        "mean_context_token_estimate" : 195,
        "state_confusion_rate" : 0.01875,
        "evidence_support_rate" : 0.9875,
        "mean_prompt_tokens_reported" : 269.19375,
        "mean_latency_ms" : 3510
      }
    }
  },
  "answer_failure_cases" : [ {
    "timeline_id" : "timeline-011",
    "query_id" : "timeline-011-q09-multi_fact",
    "group" : "G2C",
    "query_type" : "multi_fact",
    "question" : "把现在的居住城市和职位分别告诉我。",
    "expected_answer" : "苏州；交互设计师",
    "should_abstain" : false,
    "response" : "现在居住在苏州。当前职位没有相关信息；你之前在无锡生活时从事产品经理。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.5,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : false,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "baseline-knowledge_graph_entity-132", "b243b5c5-93d7-30a7-8677-4f5d8db7ae87", "9a370e37-85f8-3970-ac29-9cf0462f6fd7", "b430b5ec-989e-3a4b-9e80-aa4581b9b90f", "90f42bc4-093f-355b-b568-f4de65d61c82", "8473af33-f6ea-38d3-aab2-e5995ec7ae9e", "56a09f91-1994-356c-a2d7-cb5ef26ef0d9", "baseline-knowledge_graph_relation-59", "299e8332-7f02-3e13-bcdc-5be15bf09545", "baseline-knowledge_graph_entity-148" ],
    "context_token_estimate" : 182,
    "elapsed_ms" : 10718,
    "error" : "",
    "prompt_tokens" : 274,
    "completion_tokens" : 76,
    "total_tokens" : 350
  }, {
    "timeline_id" : "timeline-012",
    "query_id" : "timeline-012-q04-current-job",
    "group" : "G2C",
    "query_type" : "current_state",
    "question" : "现在从事什么工作？",
    "expected_answer" : "项目运营",
    "should_abstain" : false,
    "response" : "你目前从事项目运营，之前做过软件工程师。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "c55b7c27-4195-317a-9f90-a0180846f48a", "8a3f64a3-228f-3e5d-beb3-8f744124032d", "baseline-knowledge_graph_entity-108", "baseline-knowledge_graph_entity-98", "ed61b244-4eca-3d29-87bd-a1c7ec21844a", "baseline-knowledge_graph_entity-246", "e1dfa088-304a-3f08-8756-165a158f6668", "8e05c4e0-3904-3303-9898-bda40a361be1", "baseline-knowledge_graph_entity-164", "5aadde00-898e-351f-8dd2-9cda626427c4" ],
    "context_token_estimate" : 205,
    "elapsed_ms" : 1252,
    "error" : "",
    "prompt_tokens" : 258,
    "completion_tokens" : 18,
    "total_tokens" : 276
  }, {
    "timeline_id" : "timeline-012",
    "query_id" : "timeline-012-q06-preference",
    "group" : "G1",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "先给结论，再列简洁步骤。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "46", "106", "1", "141", "120", "142", "127", "49", "19", "22" ],
    "context_token_estimate" : 226,
    "elapsed_ms" : 1561,
    "error" : "",
    "prompt_tokens" : 289,
    "completion_tokens" : 14,
    "total_tokens" : 303
  }, {
    "timeline_id" : "timeline-012",
    "query_id" : "timeline-012-q06-preference",
    "group" : "G2C",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "先给结论，再列简洁步骤。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "6c4f04cb-ac4d-3031-b3c4-4569d446c9fe", "33ec91b8-0f63-3f4c-a43e-00dbf4cd70c9", "4bf4b752-b1d6-3c1d-bee4-6bedbe827cc7", "baseline-knowledge_graph_entity-3", "5146b584-936e-3350-9bf4-1224a74db964", "baseline-knowledge_graph_entity-212", "baseline-knowledge_graph_relation-213", "baseline-knowledge_graph_entity-92", "baseline-knowledge_graph_relation-5", "baseline-knowledge_graph_relation-93" ],
    "context_token_estimate" : 212,
    "elapsed_ms" : 1249,
    "error" : "",
    "prompt_tokens" : 304,
    "completion_tokens" : 14,
    "total_tokens" : 318
  }, {
    "timeline_id" : "timeline-061",
    "query_id" : "timeline-061-q01-current-city",
    "group" : "G1",
    "query_type" : "current_state",
    "question" : "最近确认的现居城市是什么？",
    "expected_answer" : "合肥",
    "should_abstain" : false,
    "response" : "合肥。你最近确认目前住在合肥；无锡只是考虑过的去向。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "314", "341", "434", "455", "362", "432", "419", "441", "355", "421" ],
    "context_token_estimate" : 219,
    "elapsed_ms" : 12182,
    "error" : "",
    "prompt_tokens" : 302,
    "completion_tokens" : 25,
    "total_tokens" : 327
  }, {
    "timeline_id" : "timeline-061",
    "query_id" : "timeline-061-q06-preference",
    "group" : "G1",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "结论：先给结论，再列简洁步骤。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "314", "408", "351", "442", "327", "426", "423", "443", "455", "446" ],
    "context_token_estimate" : 229,
    "elapsed_ms" : 1714,
    "error" : "",
    "prompt_tokens" : 294,
    "completion_tokens" : 17,
    "total_tokens" : 311
  }, {
    "timeline_id" : "timeline-062",
    "query_id" : "timeline-062-q04-current-job",
    "group" : "G2C",
    "query_type" : "current_state",
    "question" : "现在从事什么工作？",
    "expected_answer" : "项目运营",
    "should_abstain" : false,
    "response" : "你现在从事项目运营，负责门店库存改造项目。过去做过软件工程师。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : true,
    "error_type" : "state_confusion",
    "source_unit_ids" : [ "baseline-knowledge_graph_entity-746", "baseline-knowledge_graph_entity-870", "9217c246-911e-30c2-b2e4-a4bd00cbd93d", "598a2ac8-f23b-3940-b8f6-93c0b4553526", "0be1700b-7dc5-3ee6-be75-3edcab88266f", "4641e532-4075-3002-a843-d70c0a890b9b", "d5fd9725-b026-3281-a291-bbea0dc72d1b", "200db99b-ce20-32ab-8413-e1cb97a8833e", "baseline-knowledge_graph_entity-792", "96c59209-01f0-3894-9ff4-156168148ce7" ],
    "context_token_estimate" : 221,
    "elapsed_ms" : 1516,
    "error" : "",
    "prompt_tokens" : 267,
    "completion_tokens" : 26,
    "total_tokens" : 293
  }, {
    "timeline_id" : "timeline-062",
    "query_id" : "timeline-062-q06-preference",
    "group" : "G1",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "结论：先给结论，再列简洁步骤。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "363", "414", "330", "449", "345", "433", "438", "450", "350", "451" ],
    "context_token_estimate" : 229,
    "elapsed_ms" : 7300,
    "error" : "",
    "prompt_tokens" : 294,
    "completion_tokens" : 17,
    "total_tokens" : 311
  }, {
    "timeline_id" : "timeline-062",
    "query_id" : "timeline-062-q06-preference",
    "group" : "G2C",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "先给结论，再列简洁步骤。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "5e454261-d894-30c1-93a8-e39a8d9fb290", "8c7eb302-1ec9-3f74-b2d4-774202830d55", "baseline-knowledge_graph_entity-876", "76431c76-8737-3305-93f2-0b186c1f8031", "baseline-knowledge_graph_relation-829", "c8c57e8d-a103-347b-9ccd-ef26bd8b2981", "baseline-knowledge_graph_entity-828", "0eecb6d2-a185-3d8a-8598-b09d77c965a2", "baseline-knowledge_graph_entity-726", "baseline-knowledge_graph_relation-662" ],
    "context_token_estimate" : 225,
    "elapsed_ms" : 1101,
    "error" : "",
    "prompt_tokens" : 312,
    "completion_tokens" : 14,
    "total_tokens" : 326
  }, {
    "timeline_id" : "timeline-086",
    "query_id" : "timeline-086-q04-current-job",
    "group" : "G2C",
    "query_type" : "current_state",
    "question" : "现在从事什么工作？",
    "expected_answer" : "视觉设计师",
    "should_abstain" : false,
    "response" : "目前从事视觉设计工作。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "baseline-knowledge_graph_entity-998", "88f05e99-545c-3ea0-bcf4-d6c060f8e1bb", "10a2b419-b76a-3b6a-a653-7e3068223bf9", "baseline-knowledge_graph_entity-931", "344de21b-4a30-3500-be0d-9237118d1bba", "d9d053c8-2b04-354c-b311-49f567aed20e", "baseline-knowledge_graph_entity-988", "5d7967e0-cd17-35a6-bf5c-a7d202aa543f", "b3558ce1-3892-3fda-adac-2932304b96d8", "567d576f-0010-3934-a20d-d9961af3e93e" ],
    "context_token_estimate" : 218,
    "elapsed_ms" : 8024,
    "error" : "",
    "prompt_tokens" : 274,
    "completion_tokens" : 11,
    "total_tokens" : 285
  }, {
    "timeline_id" : "timeline-087",
    "query_id" : "timeline-087-q09-multi_fact",
    "group" : "G1",
    "query_type" : "multi_fact",
    "question" : "把现在的居住城市和职位分别告诉我。",
    "expected_answer" : "天津；数据分析师",
    "should_abstain" : false,
    "response" : "现在长期居住在青岛，职位是数据分析师。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.5,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "562", "527", "514", "567", "584", "574", "595", "553", "588", "524" ],
    "context_token_estimate" : 229,
    "elapsed_ms" : 1288,
    "error" : "",
    "prompt_tokens" : 294,
    "completion_tokens" : 18,
    "total_tokens" : 312
  }, {
    "timeline_id" : "timeline-161",
    "query_id" : "timeline-161-q06-preference",
    "group" : "G2C",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "先给结论，再列简洁步骤。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "d66e8637-c731-3dcc-8050-8568112ea029", "a839394d-2e20-36af-9f31-88fa0777f6d4", "6fc081f5-8504-31f0-9376-4a2811c0a0d1", "fbf22083-fa56-35e2-9a60-22cf6c2782b7", "baseline-knowledge_graph_entity-1531", "baseline-knowledge_graph_relation-1533", "baseline-knowledge_graph_entity-1578", "baseline-knowledge_graph_relation-1579", "163a637c-e55f-36fa-b537-4cfdb767b4f2", "1522a614-9a77-3b70-9682-ceef6c0654ca" ],
    "context_token_estimate" : 215,
    "elapsed_ms" : 1477,
    "error" : "",
    "prompt_tokens" : 301,
    "completion_tokens" : 14,
    "total_tokens" : 315
  }, {
    "timeline_id" : "timeline-161",
    "query_id" : "timeline-161-q09-multi_fact",
    "group" : "G1",
    "query_type" : "multi_fact",
    "question" : "把现在的居住城市和职位分别告诉我。",
    "expected_answer" : "合肥；交互设计师",
    "should_abstain" : false,
    "response" : "结论：你现在长期居住在苏州，职位是交互设计师。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.5,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : false,
    "state_confusion" : false,
    "error_type" : "missing_retrieved_evidence",
    "source_unit_ids" : [ "765", "857", "880", "795", "779", "847", "872", "883", "798", "854" ],
    "context_token_estimate" : 238,
    "elapsed_ms" : 1152,
    "error" : "",
    "prompt_tokens" : 295,
    "completion_tokens" : 23,
    "total_tokens" : 318
  }, {
    "timeline_id" : "timeline-186",
    "query_id" : "timeline-186-q04-current-job",
    "group" : "G2C",
    "query_type" : "current_state",
    "question" : "现在从事什么工作？",
    "expected_answer" : "视觉设计师",
    "should_abstain" : false,
    "response" : "目前从事视觉设计工作。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "baseline-knowledge_graph_entity-1658", "84a5c76e-fdd4-3b35-b12b-f00d6875670e", "baseline-knowledge_graph_entity-1615", "d0cb9ef0-57c3-35a0-95e8-f31c08e30207", "baseline-knowledge_graph_entity-1630", "b44b615a-60af-3708-b176-38a078d80521", "64c44880-ef16-392a-b6b1-bbe00fcab5e6", "baseline-knowledge_graph_entity-1652", "f9576633-fdbd-36bd-8ccc-2776cc1bce4a", "facaf3d3-fe56-3836-bebf-a6d42fbe3572" ],
    "context_token_estimate" : 221,
    "elapsed_ms" : 1761,
    "error" : "",
    "prompt_tokens" : 276,
    "completion_tokens" : 11,
    "total_tokens" : 287
  }, {
    "timeline_id" : "timeline-211",
    "query_id" : "timeline-211-q04-current-job",
    "group" : "G2C",
    "query_type" : "current_state",
    "question" : "现在从事什么工作？",
    "expected_answer" : "交互设计师",
    "should_abstain" : false,
    "response" : "你现在从事交互设计工作。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "baseline-knowledge_graph_entity-1868", "d19eb9ad-a7d0-3e66-9624-b4f1ccce62d1", "646d4402-029a-3c9c-b247-bcf4e592c7b6", "044c0bab-69e9-32f2-9fc0-752379ff5208", "03b0078b-72f2-358c-bd5a-16fcaa070fe8", "baseline-knowledge_graph_entity-1824", "b6c2c6ae-8b12-34b7-b02c-b4f051f43b05", "baseline-knowledge_graph_entity-1844", "baseline-knowledge_graph_entity-1805", "11cfa4e0-7ab4-3bae-b79f-94e253d0a1f9" ],
    "context_token_estimate" : 214,
    "elapsed_ms" : 1856,
    "error" : "",
    "prompt_tokens" : 264,
    "completion_tokens" : 13,
    "total_tokens" : 277
  }, {
    "timeline_id" : "timeline-211",
    "query_id" : "timeline-211-q06-preference",
    "group" : "G1",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "不知道；当前没有检索到相关记忆。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : false,
    "state_confusion" : false,
    "error_type" : "missing_retrieved_evidence",
    "source_unit_ids" : [ ],
    "context_token_estimate" : 0,
    "elapsed_ms" : 8476,
    "error" : "",
    "prompt_tokens" : 95,
    "completion_tokens" : 44,
    "total_tokens" : 139
  }, {
    "timeline_id" : "timeline-211",
    "query_id" : "timeline-211-q08-focus",
    "group" : "G1",
    "query_type" : "event_time",
    "question" : "提到的活动是什么？日期是否确定？",
    "expected_answer" : "用户研究复盘",
    "should_abstain" : false,
    "response" : "没有检索到相关记忆，所以不知道提到的活动是什么，也无法确认日期。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : false,
    "state_confusion" : false,
    "error_type" : "missing_retrieved_evidence",
    "source_unit_ids" : [ ],
    "context_token_estimate" : 0,
    "elapsed_ms" : 1580,
    "error" : "",
    "prompt_tokens" : 96,
    "completion_tokens" : 54,
    "total_tokens" : 150
  }, {
    "timeline_id" : "timeline-236",
    "query_id" : "timeline-236-q06-preference",
    "group" : "G1",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "结论：先给结论，再列简洁步骤。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "1019", "944", "969", "1042", "1045", "1035", "959", "1029", "1046", "993" ],
    "context_token_estimate" : 223,
    "elapsed_ms" : 1153,
    "error" : "",
    "prompt_tokens" : 291,
    "completion_tokens" : 17,
    "total_tokens" : 308
  }, {
    "timeline_id" : "timeline-236",
    "query_id" : "timeline-236-q08-focus",
    "group" : "G1",
    "query_type" : "event_time",
    "question" : "提到的活动是什么？日期是否确定？",
    "expected_answer" : "资料归档检查",
    "should_abstain" : false,
    "response" : "结论：可检索记忆中没有提到具体活动，也没有活动日期，因此无法判断日期是否确定。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : false,
    "state_confusion" : false,
    "error_type" : "missing_retrieved_evidence",
    "source_unit_ids" : [ "1042", "1045", "963", "1023", "959", "1029", "1026", "1035", "1014", "993" ],
    "context_token_estimate" : 223,
    "elapsed_ms" : 9458,
    "error" : "",
    "prompt_tokens" : 291,
    "completion_tokens" : 81,
    "total_tokens" : 372
  }, {
    "timeline_id" : "timeline-236",
    "query_id" : "timeline-236-q08-focus",
    "group" : "G2C",
    "query_type" : "event_time",
    "question" : "提到的活动是什么？日期是否确定？",
    "expected_answer" : "资料归档检查",
    "should_abstain" : false,
    "response" : "提到的活动是考虑搬去合肥；目前尚未决定或搬迁，日期也没有确定。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : false,
    "state_confusion" : false,
    "error_type" : "missing_retrieved_evidence",
    "source_unit_ids" : [ "3645a5e3-cc92-3dab-a03e-6986d429f848", "75c91b4f-5c03-37fa-987d-d351e27f686c", "40ce2c18-2d3f-3d25-9480-6720544b7f99", "579b8c1f-c1b8-3e1d-9d85-aa215525a0e7", "baseline-knowledge_graph_relation-1909", "baseline-knowledge_graph_entity-2008", "5034b06c-032c-3650-a036-0f5748648182", "5f4cd6e3-eec6-3a95-b44e-cfce2f502daf", "a2533c7d-167b-36ae-8e41-8d2ca4c5b484", "baseline-knowledge_graph_entity-1908" ],
    "context_token_estimate" : 220,
    "elapsed_ms" : 4896,
    "error" : "",
    "prompt_tokens" : 293,
    "completion_tokens" : 79,
    "total_tokens" : 372
  }, {
    "timeline_id" : "timeline-236",
    "query_id" : "timeline-236-q09-multi_fact",
    "group" : "G1",
    "query_type" : "multi_fact",
    "question" : "把现在的居住城市和职位分别告诉我。",
    "expected_answer" : "厦门；视觉设计师",
    "should_abstain" : false,
    "response" : "居住城市：无锡。职位：视觉设计师。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 0.5,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "1045", "944", "963", "1018", "959", "1047", "985", "1029", "1023", "1036" ],
    "context_token_estimate" : 234,
    "elapsed_ms" : 1784,
    "error" : "",
    "prompt_tokens" : 295,
    "completion_tokens" : 17,
    "total_tokens" : 312
  }, {
    "timeline_id" : "timeline-261",
    "query_id" : "timeline-261-q06-preference",
    "group" : "G1",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "结论：先给结论，再列简洁步骤。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "1048", "1131", "1071", "1162", "1148", "1142", "1163", "1183", "1061", "1072" ],
    "context_token_estimate" : 226,
    "elapsed_ms" : 4676,
    "error" : "",
    "prompt_tokens" : 290,
    "completion_tokens" : 17,
    "total_tokens" : 307
  }, {
    "timeline_id" : "timeline-261",
    "query_id" : "timeline-261-q06-preference",
    "group" : "G2C",
    "query_type" : "preference",
    "question" : "回答时应采用什么组织顺序？",
    "expected_answer" : "先说结论，再列简洁步骤",
    "should_abstain" : false,
    "response" : "先给结论，再列简洁步骤。",
    "correct_by_frozen_rules" : false,
    "core_fact_coverage_rate" : 1.0,
    "correct_by_approximate_rules" : false,
    "evidence_supported" : true,
    "state_confusion" : false,
    "error_type" : "answer_rule_failure",
    "source_unit_ids" : [ "8270a01a-4af5-3dc8-be70-595f00e8c119", "095cef97-8a53-3bf3-92ac-d9cd72f994f7", "2bef8f4c-3f94-336a-b7c4-b56e4de44e0d", "ef1276c5-4ccc-34ad-bdef-7bb836b519a1", "baseline-knowledge_graph_entity-2096", "baseline-knowledge_graph_relation-2099", "5b61f2c3-7667-3c23-bb18-55c100a0c4bd", "79c00c92-7fe3-3ede-86fb-9458f5e57f37", "baseline-knowledge_graph_relation-2129", "35bd3f6f-93b4-3c6f-9ced-c3c3d91b1706" ],
    "context_token_estimate" : 205,
    "elapsed_ms" : 1691,
    "error" : "",
    "prompt_tokens" : 285,
    "completion_tokens" : 14,
    "total_tokens" : 299
  } ],
  "compaction_acceptance" : {
    "strict_30_percent_compression_target" : "Always reported independently as compression_30_percent_target_met; relaxed screening permits up to 10% corpus growth.",
    "by_timeline" : [ {
      "timeline_id" : "timeline-011",
      "sample_profile" : "",
      "system_compression_rate" : 0.03013698630136985,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.05459057071960294,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 0.9,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 0.9,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.10931734317343178,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-012",
      "sample_profile" : "",
      "system_compression_rate" : 0.03210702341137128,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.05860805860805862,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.9,
      "g2c_answer_accuracy" : 0.8,
      "g1_approximate_answer_accuracy" : 0.9,
      "g2c_approximate_answer_accuracy" : 0.8,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.12448886869604725,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-036",
      "sample_profile" : "",
      "system_compression_rate" : 0.03183791606367581,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.054254007398273685,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.09693196976433971,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-037",
      "sample_profile" : "",
      "system_compression_rate" : 0.030443414956982107,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.05679012345679013,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.12364620938628157,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-061",
      "sample_profile" : "",
      "system_compression_rate" : 0.03424124513618676,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.054862842892768104,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.8,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 0.8,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.10861594867094404,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : false,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : false
    }, {
      "timeline_id" : "timeline-062",
      "sample_profile" : "",
      "system_compression_rate" : 0.03389830508474578,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.06022584692597244,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.9,
      "g2c_answer_accuracy" : 0.8,
      "g1_approximate_answer_accuracy" : 0.9,
      "g2c_approximate_answer_accuracy" : 0.8,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.11871318531943809,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-086",
      "sample_profile" : "",
      "system_compression_rate" : 0.01490514905149054,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.02716049382716046,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 0.9,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 0.9,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.15617021276595744,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-087",
      "sample_profile" : "",
      "system_compression_rate" : 0.01550910316925147,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.02846534653465349,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.9,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 0.9,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.13273137697516935,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-111",
      "sample_profile" : "",
      "system_compression_rate" : 0.030323914541695363,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.05459057071960294,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.10446387482742758,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-136",
      "sample_profile" : "",
      "system_compression_rate" : 0.03305785123966942,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.055766793409378956,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.10731707317073169,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-161",
      "sample_profile" : "",
      "system_compression_rate" : 0.036036036036036,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.05641025641025643,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.9,
      "g2c_answer_accuracy" : 0.9,
      "g1_approximate_answer_accuracy" : 0.9,
      "g2c_approximate_answer_accuracy" : 0.9,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.14388166741371577,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-186",
      "sample_profile" : "",
      "system_compression_rate" : 0.03318250377073906,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.055766793409378956,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.9,
      "g2c_answer_accuracy" : 0.9,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 0.9,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.12019443216968628,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-211",
      "sample_profile" : "",
      "system_compression_rate" : 0.046199701937406856,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.08168642951251648,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.8,
      "g2c_answer_accuracy" : 0.9,
      "g1_approximate_answer_accuracy" : 0.8,
      "g2c_approximate_answer_accuracy" : 0.9,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : -0.30774321641297164,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : false,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : false
    }, {
      "timeline_id" : "timeline-236",
      "sample_profile" : "",
      "system_compression_rate" : 0.03209336250911743,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.06153846153846154,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.7,
      "g2c_answer_accuracy" : 0.9,
      "g1_approximate_answer_accuracy" : 0.7,
      "g2c_approximate_answer_accuracy" : 0.9,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.12700534759358284,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-261",
      "sample_profile" : "",
      "system_compression_rate" : 0.034455755677368805,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.05641025641025643,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 0.9,
      "g2c_answer_accuracy" : 0.9,
      "g1_approximate_answer_accuracy" : 0.9,
      "g2c_approximate_answer_accuracy" : 0.9,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.1262443438914027,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    }, {
      "timeline_id" : "timeline-286",
      "sample_profile" : "",
      "system_compression_rate" : 0.03252032520325199,
      "oracle_upper_rate" : 0.0,
      "oracle_efficiency" : null,
      "curator_eligible_compression_rate" : 0.05781865965834432,
      "curator_eligible_oracle_upper_rate" : 0.0,
      "curator_eligible_oracle_efficiency" : null,
      "information_retention_rate" : 1.0,
      "g1_answer_accuracy" : 1.0,
      "g2c_answer_accuracy" : 1.0,
      "g1_approximate_answer_accuracy" : 1.0,
      "g2c_approximate_answer_accuracy" : 1.0,
      "compression_30_percent_target_met" : false,
      "query_context_compression_rate" : 0.14088888888888884,
      "compression_pass" : true,
      "answer_pass" : true,
      "information_retention_pass" : true,
      "query_context_pass" : true,
      "recall_pass" : true,
      "duplicate_top10_pass" : true,
      "status" : "measured",
      "gate_profile" : "relaxed",
      "performance_gates_pass" : true
    } ],
    "oracle_method" : "Conservative feasible corpus estimate using covered clauses, preserving unmatched raw and identical KG; not an exact minimum-cover upper bound or deletion target.",
    "gate_profile" : "relaxed"
  },
  "model_calls" : {
    "prompt_tokens_reported" : 877246,
    "total_tokens_reported" : 1476582,
    "calls_with_token_usage" : 1090,
    "calls" : 1090,
    "completion_tokens_reported" : 599336,
    "by_stage" : {
      "baseline_memory" : {
        "calls" : 640,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "count" : 640,
          "p95_ms" : 10855,
          "p50_ms" : 3599,
          "mean_ms" : 4899.2765625,
          "p99_ms" : 13970
        },
        "prompt_tokens_reported" : 283013,
        "completion_tokens_reported" : 193997,
        "total_tokens_reported" : 477010,
        "calls_with_token_usage" : 640
      },
      "curator" : {
        "calls" : 130,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "count" : 130,
          "p95_ms" : 29433,
          "p50_ms" : 18999,
          "mean_ms" : 20084.23076923077,
          "p99_ms" : 33117
        },
        "prompt_tokens_reported" : 506025,
        "completion_tokens_reported" : 398370,
        "total_tokens_reported" : 904395,
        "calls_with_token_usage" : 130
      },
      "answer" : {
        "calls" : 320,
        "failures" : 0,
        "failure_rate" : 0.0,
        "latency_ms" : {
          "count" : 320,
          "p95_ms" : 9686,
          "p50_ms" : 1934,
          "mean_ms" : 3445.5625,
          "p99_ms" : 11688
        },
        "prompt_tokens_reported" : 88208,
        "completion_tokens_reported" : 6969,
        "total_tokens_reported" : 95177,
        "calls_with_token_usage" : 320
      }
    }
  },
  "system_performance" : {
    "experiment_wall_ms" : 1122579,
    "timeline_count" : 16,
    "baseline_ingestion_per_timeline_ms" : {
      "count" : 16,
      "p95_ms" : 0,
      "p50_ms" : 0,
      "mean_ms" : 0.0,
      "p99_ms" : 0
    },
    "curator_ingestion_per_timeline_ms" : {
      "count" : 16,
      "p95_ms" : 0,
      "p50_ms" : 0,
      "mean_ms" : 0.0,
      "p99_ms" : 0
    },
    "retrieval_and_answer_per_timeline_ms" : {
      "count" : 16,
      "p95_ms" : 103636,
      "p50_ms" : 72002,
      "mean_ms" : 69968.75,
      "p99_ms" : 103636
    },
    "query_embedding_latency_ms" : {
      "count" : 160,
      "p95_ms" : 66,
      "p50_ms" : 49,
      "mean_ms" : 50.8125,
      "p99_ms" : 75
    },
    "g1_production_retrieval_search_latency_ms" : {
      "count" : 160,
      "p95_ms" : 5,
      "p50_ms" : 2,
      "mean_ms" : 3.3,
      "p99_ms" : 29
    },
    "g2a_append_retrieval_search_latency_ms" : {
      "count" : 160,
      "p95_ms" : 7,
      "p50_ms" : 2,
      "mean_ms" : 3.15625,
      "p99_ms" : 18
    },
    "g2c_compaction_retrieval_search_latency_ms" : {
      "count" : 160,
      "p95_ms" : 58,
      "p50_ms" : 41,
      "mean_ms" : 42.84375,
      "p99_ms" : 68
    },
    "g1_indexed_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 5649,
        "knowledge_graph_relation" : 3889,
        "ordinary_long_term_memory" : 12642
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 263,
        "knowledge_graph_relation" : 352,
        "ordinary_long_term_memory" : 576
      },
      "unit_count" : 1191,
      "estimated_tokens" : 22180
    },
    "g2a_indexed_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "knowledge_graph_entity" : 5649,
        "knowledge_graph_relation" : 3889,
        "ordinary_long_term_memory" : 12642,
        "working_memory" : 32
      },
      "unit_count_by_memory_type" : {
        "knowledge_graph_entity" : 263,
        "knowledge_graph_relation" : 352,
        "ordinary_long_term_memory" : 576,
        "working_memory" : 16
      },
      "unit_count" : 1207,
      "estimated_tokens" : 22212
    },
    "g2c_indexed_corpus" : {
      "estimated_tokens_by_memory_type" : {
        "curated_knowledge_graph_entity" : 5649,
        "curated_knowledge_graph_relation" : 3889,
        "ordinary_long_term_memory" : 11953
      },
      "unit_count_by_memory_type" : {
        "curated_knowledge_graph_entity" : 263,
        "curated_knowledge_graph_relation" : 352,
        "ordinary_long_term_memory" : 545
      },
      "unit_count" : 1160,
      "estimated_tokens" : 21491
    },
    "g1_sqlite_footprint_bytes" : {
      "wal_bytes" : 4128272,
      "total_observed_bytes" : 13131280,
      "main_db_bytes" : 8970240,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768
    },
    "g2_sqlite_footprint_bytes" : {
      "wal_bytes" : 4132392,
      "total_observed_bytes" : 23289384,
      "main_db_bytes" : 19124224,
      "measurement_note" : "Measured while the experiment connections are open; main DB plus WAL and shared-memory sidecars.",
      "shm_bytes" : 32768
    },
    "llm_calls_by_stage" : {
      "prompt_tokens_reported" : 877246,
      "total_tokens_reported" : 1476582,
      "calls_with_token_usage" : 1090,
      "calls" : 1090,
      "completion_tokens_reported" : 599336,
      "by_stage" : {
        "baseline_memory" : {
          "calls" : 640,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "count" : 640,
            "p95_ms" : 10855,
            "p50_ms" : 3599,
            "mean_ms" : 4899.2765625,
            "p99_ms" : 13970
          },
          "prompt_tokens_reported" : 283013,
          "completion_tokens_reported" : 193997,
          "total_tokens_reported" : 477010,
          "calls_with_token_usage" : 640
        },
        "curator" : {
          "calls" : 130,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "count" : 130,
            "p95_ms" : 29433,
            "p50_ms" : 18999,
            "mean_ms" : 20084.23076923077,
            "p99_ms" : 33117
          },
          "prompt_tokens_reported" : 506025,
          "completion_tokens_reported" : 398370,
          "total_tokens_reported" : 904395,
          "calls_with_token_usage" : 130
        },
        "answer" : {
          "calls" : 320,
          "failures" : 0,
          "failure_rate" : 0.0,
          "latency_ms" : {
            "count" : 320,
            "p95_ms" : 9686,
            "p50_ms" : 1934,
            "mean_ms" : 3445.5625,
            "p99_ms" : 11688
          },
          "prompt_tokens_reported" : 88208,
          "completion_tokens_reported" : 6969,
          "total_tokens_reported" : 95177,
          "calls_with_token_usage" : 320
        }
      }
    },
    "baseline_turns_per_second_sum_timeline_time" : 0.0
  },
  "failures" : {
    "by_reason" : {
      "CURATOR_INCOMPLETE" : 16
    },
    "count" : 16
  },
  "acceptance_pass" : false,
  "acceptance_gate_details" : {
    "semantic_unique_fact_quality_pass" : false,
    "semantic_unique_recall_minimum" : 0.5,
    "integrity_pass" : false,
    "profile" : "relaxed",
    "semantic_unique_precision_minimum" : 0.4,
    "provenance_pass" : false,
    "runtime_failures_empty" : false
  },
  "evaluation_notes" : [ "G1 is the actual per-turn KnowledgeGraphService plus long_term_memory baseline. G2A and G2C reuse the same accepted real MemoryCuratorService output so the ablation isolates append-only versus compacted serving behavior.", "G1, G2A, and G2C all retain the same traceable KG entities and relations for retrieval; the G2C KG sidecar is unchanged by compaction and counted in its serving tokens.", "G1 and G2A use the production SqliteMemoryService hybrid retriever. G2C merges relevant unmapped raw candidates with production corpus ranking, with an explicit threshold and semantic de-duplication. A frozen shared text ranker provides corpus-only retrieval diagnostics.", "Retrieval reports Recall@5 and Recall@10; Recall@1 and Recall@3 are not reported.", "Memory-unit token budgets use a documented deterministic Unicode estimate; answer prompt token counts use model-reported usage when returned.", "Answer correctness reports both strict frozen rules and a relaxed anchor/core-fact score; the LLM answers memory questions, while deterministic query annotations score them without an extra judge call.", "The relaxed gate profile is a screening pass only. The 30% full-corpus compression target is reported independently and remains an explicit target metric.", "The proposal's 40-turn timeline and production 15-turn trigger yield three curator checkpoints; retention at 5 is not estimable in this run.", "Formal paired effects compare G2C with G1. G2A is reported as an append-only ablation." ]
}
```
