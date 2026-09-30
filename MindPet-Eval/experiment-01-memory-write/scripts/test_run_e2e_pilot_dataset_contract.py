import json
import sys
import unittest
from collections import Counter
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parent
EXPERIMENT_ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))

import run_e2e_pilot as runner


class E2eDatasetContractTest(unittest.TestCase):
    def setUp(self):
        self.source = EXPERIMENT_ROOT / "02-pilot-v1" / "datasets" / "pilot_30.jsonl"
        self.subset = (
            EXPERIMENT_ROOT / "04-formal-observation-validation" / "datasets"
            / "architecture_validation_12.jsonl"
        )

    def test_architecture_subset_is_exact_source_selection(self):
        rows = runner.read_dataset(self.subset, expected_sample_count=12)
        source_rows = {
            row["sample_id"]: row
            for row in (
                json.loads(line)
                for line in self.source.read_text(encoding="utf-8").splitlines()
                if line.strip()
            )
        }

        self.assertEqual(
            [
                "p001", "p003", "p006", "p008", "p011", "p012",
                "p017", "p019", "p023", "p024", "p026", "p028",
            ],
            [row["sample_id"] for row in rows],
        )
        self.assertTrue(all(row == source_rows[row["sample_id"]] for row in rows))
        self.assertEqual({2}, set(Counter(row["category"] for row in rows).values()))
        self.assertEqual(
            {"easy": 4, "medium": 4, "hard": 4},
            dict(Counter(row["difficulty"] for row in rows)),
        )

    def test_default_pilot_contract_still_rejects_a_12_sample_dataset(self):
        with self.assertRaisesRegex(runner.RunFailure, "exactly 30"):
            runner.read_dataset(self.subset)


if __name__ == "__main__":
    unittest.main()
