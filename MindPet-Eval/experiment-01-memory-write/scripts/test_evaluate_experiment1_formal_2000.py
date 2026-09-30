import unittest

import evaluate_experiment1_formal_2000 as evaluator


def sample(sample_id, gold, *, sensitive=False, temporal=False, category="stable_fact", tags=None):
    return {
        "sample_id": sample_id, "store_decision": gold, "human_importance": 0.7,
        "category": category, "difficulty": "hard", "sensitivity": "sensitive" if sensitive else "non_sensitive",
        "sensitive_case": sensitive, "temporal_case": temporal, "time_status": "future" if temporal else "none",
        "scenario_tags": tags or [], "expected_entities": [{"entity_key": "e1", "name": "Project A", "type": "project"}],
        "expected_relations": [{"source_entity_key": "user", "predicate": "works_on", "target_entity_key": "e1"}],
        "expected_kg_evidence": True,
    }


def record(item, decision, persisted, predicate="works_on"):
    return {
        "sample_id": item["sample_id"], "variant": "b0", "snapshot_sha256": "a" * 64,
        "gold": item, "decision": decision, "persisted": persisted, "importance": 0.7,
        "result": {
            "duplicate": False, "evidenceRowsCreated": 2, "relationRowsCreatedOrUpdated": 1,
            "entities": [{"displayName": "Project A", "normalizedName": "project a", "entityType": "project"}],
            "relations": [{"sourceName": "user", "targetName": "Project A", "predicate": predicate}],
            "writeTrace": {
                "parse": {"memoryObjectPresent": True, "parseFailure": False,
                          "importanceFallbackUsed": False, "confidenceFallbackUsed": False,
                          "importanceClamped": False, "confidenceClamped": False},
                "kgFilter": {"predicateWhitelistRejectedCount": 0},
                "provenance": {"sampleId": item["sample_id"]},
            },
        },
    }


class FormalMetricsTest(unittest.TestCase):
    def test_classification_counts_and_f1(self):
        metrics = evaluator.classification([True, True, False, False], [True, False, True, False])
        self.assertEqual((1, 1, 1, 1), (metrics["tp"], metrics["fp"], metrics["fn"], metrics["tn"]))
        self.assertEqual(0.5, metrics["accuracy"])
        self.assertEqual(0.5, metrics["f1"])

    def test_sensitive_temporal_scenario_and_kg_metrics(self):
        first = sample("f0001", False, sensitive=True, temporal=True,
                       category="temporary_state", tags=["short_duration"])
        second = sample("f0002", True, tags=["correction"])
        records = [record(first, True, True), record(second, True, True)]
        metrics, errors = evaluator.compute_variant(
            records, {"count": 2, "mae": 0.0, "rmse": 0.0, "spearman": 1.0,
                      "scope": "SHARED_FROZEN_EXTRACTION"})
        self.assertEqual(1, metrics["sensitive"]["false_persistence_count"])
        self.assertEqual(1.0, metrics["temporal"]["false_persistence_rate"])
        self.assertIn("short_duration", metrics["scenario_tags"])
        self.assertEqual(1.0, metrics["kg"]["entity_strict"]["f1"])
        self.assertEqual(1.0, metrics["kg"]["relation_normalized"]["f1"])
        self.assertEqual(1, len(errors["false_positive"]))
        self.assertEqual(1, len(errors["sensitive_errors"]))

    def test_normalized_kg_matching_is_case_and_whitespace_insensitive(self):
        item = sample("f0001", True)
        result = record(item, True, True)
        result["result"]["entities"][0]["displayName"] = "  PROJECT   A "
        result["result"]["relations"][0]["targetName"] = "project a"
        expected_e, expected_r = evaluator.expected_kg(item, True)
        actual_e, actual_r = evaluator.actual_kg(result["result"], True)
        self.assertEqual(expected_e, actual_e)
        self.assertEqual(expected_r, actual_r)

    def test_importance_metrics_are_explicitly_shared(self):
        metrics = evaluator.importance_metrics([0.2, 0.8], [0.3, 0.7])
        self.assertAlmostEqual(0.1, metrics["mae"])
        self.assertEqual("SHARED_FROZEN_EXTRACTION", metrics["scope"])


if __name__ == "__main__":
    unittest.main()
