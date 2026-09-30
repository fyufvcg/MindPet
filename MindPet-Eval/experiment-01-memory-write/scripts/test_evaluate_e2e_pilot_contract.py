import sys
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))

import evaluate_e2e_pilot as evaluator


class EvaluationWriteTraceContractTest(unittest.TestCase):
    def test_missing_trace_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "writeTrace missing"):
            evaluator.require_write_trace({"shouldRemember": False}, "p001")

    def test_complete_trace_is_accepted(self):
        response = {
            "turnHash": "turn-hash",
            "sessionId": "e2e:run:p001",
            "shouldRemember": False,
            "importance": 0.2,
            "confidence": 0.9,
            "ltmAttempted": False,
            "ltmPersisted": False,
            "entityRowsCreatedOrUpdated": 0,
            "relationRowsCreatedOrUpdated": 0,
            "evidenceRowsCreated": 0,
            "rows": {
                "longTermMemoryIds": [],
                "entityIds": [],
                "relationIds": [],
                "evidenceIds": [],
            },
            "writeTrace": {
                "decision": {
                    "rawWorthRemembering": False,
                    "rawMemoryShouldRemember": False,
                    "combinedShouldRemember": False,
                    "importance": 0.2,
                    "confidence": 0.9,
                    "importanceThreshold": 0.35,
                    "confidenceThreshold": 0.45,
                    "importanceGatePassed": False,
                    "confidenceGatePassed": True,
                    "ltmAttempted": False,
                    "ltmPersisted": False,
                    "ltmFailureReason": None,
                },
                "parse": {
                    "memoryObjectPresent": True,
                    "importanceFallbackUsed": False,
                    "confidenceFallbackUsed": False,
                    "importanceClamped": False,
                    "confidenceClamped": False,
                    "parseFailure": False,
                    "parseFailureReason": None,
                },
                "kgFilter": {
                    "rawEntityCount": 0,
                    "rawRelationCount": 0,
                    "normalizedEntityCount": 0,
                    "normalizedRelationCount": 0,
                    "sensitivityRejectedEntityCount": 0,
                    "predicateWhitelistRejectedCount": 0,
                    "relationConfidenceRejectedCount": 0,
                    "persistedEntityCount": 0,
                    "persistedRelationCount": 0,
                    "evidenceCount": 0,
                },
                "temporal": {
                    "eventDate": None,
                    "eventAt": None,
                    "eventTimezone": "Asia/Shanghai",
                    "eventPrecision": "none",
                    "referenceTimestamp": "2026-10-01T00:00:00Z",
                    "referenceTimezone": "Asia/Shanghai",
                },
                "provenance": {
                    "sampleId": "p001",
                    "turnHash": "turn-hash",
                    "sessionId": "e2e:run:p001",
                    "sourceUserMessageId": None,
                    "sourceAssistantMessageId": None,
                    "kgEntityRowIds": [],
                    "kgRelationRowIds": [],
                    "kgEvidenceRowIds": [],
                    "longTermMemoryRowIds": [],
                },
            },
        }

        trace = evaluator.require_write_trace(response, "p001")

        self.assertTrue(trace["parse"]["memoryObjectPresent"])


if __name__ == "__main__":
    unittest.main()
