import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))

import validate_architecture_observation as validator


def write_json(path, value):
    path.write_text(json.dumps(value), encoding="utf-8")


def write_jsonl(path, values):
    path.write_text("\n".join(json.dumps(value) for value in values) + "\n", encoding="utf-8")


def empty_table():
    return {"count": 0, "rows": []}


def response(sample_id):
    session_id = f"e2e:test:{sample_id}"
    turn_hash = f"turn-{sample_id}"
    return {
        "status": "NO_PERSIST",
        "turnHash": turn_hash,
        "sessionId": session_id,
        "shouldRemember": False,
        "importance": 0.2,
        "confidence": 0.9,
        "ltmAttempted": False,
        "ltmPersisted": False,
        "entityRowsCreatedOrUpdated": 0,
        "relationRowsCreatedOrUpdated": 0,
        "evidenceRowsCreated": 0,
        "rows": {"longTermMemoryIds": [], "entityIds": [], "relationIds": [], "evidenceIds": []},
        "writeTrace": {
            "decision": {
                "rawWorthRemembering": False, "rawMemoryShouldRemember": False,
                "combinedShouldRemember": False, "importance": 0.2, "confidence": 0.9,
                "importanceThreshold": 0.35, "confidenceThreshold": 0.45,
                "importanceGatePassed": False, "confidenceGatePassed": True,
                "ltmAttempted": False, "ltmPersisted": False, "ltmFailureReason": None,
            },
            "parse": {
                "memoryObjectPresent": True, "importanceFallbackUsed": False,
                "confidenceFallbackUsed": False, "importanceClamped": False,
                "confidenceClamped": False, "parseFailure": False, "parseFailureReason": None,
            },
            "kgFilter": {
                "rawEntityCount": 0, "rawRelationCount": 0, "normalizedEntityCount": 0,
                "normalizedRelationCount": 0, "sensitivityRejectedEntityCount": 0,
                "predicateWhitelistRejectedCount": 0, "relationConfidenceRejectedCount": 0,
                "persistedEntityCount": 0, "persistedRelationCount": 0, "evidenceCount": 0,
            },
            "temporal": {
                "eventDate": None, "eventAt": None, "eventTimezone": "Asia/Shanghai",
                "eventPrecision": "none", "referenceTimestamp": "2026-10-01T00:00:00Z",
                "referenceTimezone": "Asia/Shanghai",
            },
            "provenance": {
                "sampleId": sample_id, "turnHash": turn_hash, "sessionId": session_id,
                "sourceUserMessageId": None, "sourceAssistantMessageId": None,
                "kgEntityRowIds": [], "kgRelationRowIds": [], "kgEvidenceRowIds": [],
                "longTermMemoryRowIds": [],
            },
        },
    }


class ArchitectureObservationValidatorTest(unittest.TestCase):
    def make_fixture(self, root):
        sample_ids = [
            "p001", "p003", "p006", "p008", "p011", "p012",
            "p017", "p019", "p023", "p024", "p026", "p028",
        ]
        dataset = root / "dataset.jsonl"
        run_dir = root / "run"
        raw = run_dir / "raw"
        raw.mkdir(parents=True)
        dataset_rows = [
            {"sample_id": value, "category": "small_talk", "difficulty": "easy"}
            for value in sample_ids
        ]
        responses = [response(value) for value in sample_ids]
        write_jsonl(dataset, dataset_rows)
        write_jsonl(raw / "ingest_results.jsonl", [
            {"sample_id": value, "http_status": 200, "response": item}
            for value, item in zip(sample_ids, responses)
        ])
        write_jsonl(raw / "sample_mapping.jsonl", [
            {
                "sample_id": value, "run_id": "test", "user_id": "e2e_memory_eval_user",
                "session_id": item["sessionId"], "turn_hash": item["turnHash"],
                "long_term_memory_ids": [], "entity_ids": [], "relation_ids": [],
                "evidence_ids": [], "entities": [], "relations": [], "ltm_rows_before": 0,
                "ltm_rows_after": 0, "prune_occurred": False, "prune_deleted_estimate": 0,
            }
            for value, item in zip(sample_ids, responses)
        ])
        write_json(raw / "run_manifest.json", {
            "run_purpose": validator.PURPOSE, "formal_result": False,
            "result_label": "NOT_FORMAL_RESULT", "sample_ids": sample_ids,
        })
        write_json(raw / "snapshot_before.json", {
            "tables": {name: empty_table() for name in validator.TABLES}
        })
        after_tables = {name: empty_table() for name in validator.TABLES}
        after_tables["kg_turn_ingest"] = {
            "count": 12,
            "rows": [
                {"turn_hash": item["turnHash"], "session_id": item["sessionId"]}
                for item in responses
            ],
        }
        write_json(raw / "snapshot_after.json", {"tables": after_tables})
        return dataset, run_dir

    def test_complete_contract_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            dataset, run_dir = self.make_fixture(Path(directory))
            report = validator.validate(dataset, run_dir)
            self.assertEqual("PASS", report["status"])
            self.assertEqual(12, report["diagnostics"]["memoryObjectPresent"])

    def test_missing_trace_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            dataset, run_dir = self.make_fixture(Path(directory))
            path = run_dir / "raw" / "ingest_results.jsonl"
            records = [json.loads(line) for line in path.read_text().splitlines()]
            del records[0]["response"]["writeTrace"]
            write_jsonl(path, records)
            with self.assertRaisesRegex(validator.ValidationFailure, "writeTrace"):
                validator.validate(dataset, run_dir)


if __name__ == "__main__":
    unittest.main()
