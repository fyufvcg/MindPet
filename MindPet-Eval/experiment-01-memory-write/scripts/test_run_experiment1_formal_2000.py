import json
from pathlib import Path
import tempfile
import unittest

import run_experiment1_formal_2000 as runner


class FormalRunnerContractTest(unittest.TestCase):
    def test_frozen_dataset_and_prompt_guards_accept_repository_artifacts(self):
        repository = Path(__file__).resolve().parents[3]
        dataset = repository / "MindPet-Eval" / "datasets" / "e2e_memory" / "annotation" / "exp1_formal_2000_v2.jsonl"
        prompt = repository / "MindPet-java" / "src" / "main" / "java" / "service" / "KnowledgeGraphService.java"
        rows = runner.validate_dataset(dataset)
        self.assertEqual(2000, len(rows))
        self.assertEqual("f0001", rows[0]["sample_id"])
        self.assertEqual("f2000", rows[-1]["sample_id"])
        self.assertEqual(runner.PROMPT_SHA256, runner.prompt_hash(prompt))

    def test_dataset_sha_guard_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dataset.jsonl"
            path.write_text('{"sample_id":"f0001"}\n', encoding="utf-8")
            with self.assertRaises(runner.GuardFailure):
                runner.validate_dataset(path)

    def test_resume_reuses_only_successful_well_formed_snapshot_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = Path(directory) / "snapshots.jsonl"
            rows = [
                {"sample_id": "f0001", "status": "SUCCESS", "response": {"snapshot": {"snapshotSha256": "a" * 64}}},
                {"sample_id": "f0002", "status": "SUCCESS", "response": {"snapshot": {"snapshotSha256": "bad"}}},
                {"sample_id": "f0003", "status": "FAILED", "response": {"snapshot": {"snapshotSha256": "b" * 64}}},
            ]
            checkpoint.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            reusable = runner.successful_checkpoints(checkpoint, extraction=True)
            self.assertEqual(["f0001"], list(reusable))

    def test_variant_contract_is_exactly_the_five_frozen_variants(self):
        self.assertEqual({
            "b0": "B0_CURRENT_FULL",
            "a0": "A0_DIRECT_SAVE_ALL",
            "a1": "A1_LLM_DECISION_ONLY",
            "b1": "B1_NO_PREDICATE_WHITELIST",
            "b2": "B2_NO_LTM_CONFIDENCE_GATE",
        }, runner.VARIANTS)

    def test_each_variant_has_an_independent_sqlite_path(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = runner.variant_database_paths(Path(directory))
            self.assertEqual(5, len(paths))
            self.assertEqual(5, len(set(paths.values())))
            for variant, path in paths.items():
                self.assertEqual(variant, path.parent.name)
                self.assertEqual(f"{variant}.db", path.name)


if __name__ == "__main__":
    unittest.main()
