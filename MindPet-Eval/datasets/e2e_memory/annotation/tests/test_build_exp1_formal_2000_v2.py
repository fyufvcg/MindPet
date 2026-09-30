from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import unittest
import zipfile
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET


ANNOTATION_DIR = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ANNOTATION_DIR / "scripts" / "build_exp1_formal_2000_v2.py"
SPEC = importlib.util.spec_from_file_location("build_exp1_formal_2000_v2", SCRIPT_PATH)
module = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


class FormalDatasetV2Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v1_path = ANNOTATION_DIR / "exp1_formal_2000_v1.jsonl"
        cls.v2_path = ANNOTATION_DIR / "exp1_formal_2000_v2.jsonl"
        cls.workbook_path = ANNOTATION_DIR / "exp1_formal_2000_review_v2.xlsx"
        cls.manifest_path = ANNOTATION_DIR / "exp1_formal_2000_v2_manifest.json"
        cls.v1_rows = module.load_jsonl(cls.v1_path)
        cls.rows = module.load_jsonl(cls.v2_path)
        cls.manifest = json.loads(cls.manifest_path.read_text(encoding="utf-8"))
        pilot_paths = sorted(ANNOTATION_DIR.glob("exp1_annotation_pilot_100*.xlsx"))
        cls.pilot_messages = module.read_pilot_messages(pilot_paths)

    def test_formal_schema_and_constants(self) -> None:
        self.assertEqual(len(self.rows), 2000)
        for row in self.rows:
            self.assertEqual(list(row), module.FORMAL_FIELDS)
            self.assertEqual(row["store_decision"], row["human_should_remember"])
            self.assertEqual(row["profile_slot"], "not_applicable")
            self.assertEqual(row["evidence_turn"], row["sample_id"])
            self.assertIn(row["time_status"], module.TIME_STATUS_VALUES)
            expected_sensitivity = "sensitive" if row["sensitive_case"] else "non_sensitive"
            self.assertEqual(row["sensitivity"], expected_sensitivity)
            self.assertEqual(row["review_status"], "pending_human_review")
            self.assertEqual(row["annotator_1"], "")
            self.assertEqual(row["annotator_2"], "")

    def test_flat_predicate_value(self) -> None:
        for row in self.rows:
            predicate, value = module.relation_flat_view(row)
            self.assertEqual(row["predicate"], predicate)
            self.assertEqual(row["value"], value)

    def test_distributions_uniqueness_and_pilot_isolation(self) -> None:
        self.assertEqual(Counter(row["category"] for row in self.rows), Counter(module.CATEGORY_COUNTS))
        self.assertEqual(Counter(row["difficulty"] for row in self.rows), Counter(module.DIFFICULTY_COUNTS))
        self.assertEqual([row["sample_id"] for row in self.rows], [f"f{i:04d}" for i in range(1, 2001)])
        self.assertEqual(len({row["user_message"] for row in self.rows}), 2000)
        self.assertFalse({row["user_message"] for row in self.rows} & self.pilot_messages)

    def test_known_issue_scanners(self) -> None:
        scanners = module._issue_scanners(self.rows)
        self.assertEqual(scanners, {
            "medical_incompatible_slot": 0,
            "positive_preference_with_dislikes": 0,
            "negative_preference_with_prefers": 0,
            "cross_domain_preference_change": 0,
            "template_slot_incompatibility": 0,
            "known_long_term_goal_grammar_pattern": 0,
            "pure_memory_command_trap_with_kg_evidence": 0,
        })

    def test_medical_slot_safety(self) -> None:
        self.assertFalse(any(module.medical_template_incompatible(row) for row in self.rows))

    def test_preference_polarity_and_domain_pairing(self) -> None:
        changes = [row for row in self.rows if "preference_change" in row["scenario_tags"]]
        self.assertEqual(len(changes), 30)
        for row in changes:
            self.assertNotIn("dislikes", row["predicate"])
            old_domain, new_domain = module.preference_change_domain(row)
            self.assertIsNotNone(old_domain)
            self.assertEqual(old_domain, new_domain)

    def test_typed_slots_and_goal_grammar(self) -> None:
        self.assertFalse(any(module.typed_slot_incompatible(row) for row in self.rows))
        self.assertFalse(any(module.goal_grammar_issue(row) for row in self.rows))

    def test_memory_command_trap_is_empty(self) -> None:
        traps = [row for row in self.rows if module.pure_memory_command_trap(row)]
        self.assertTrue(traps)
        for row in traps:
            self.assertEqual(row["expected_entities"], [])
            self.assertEqual(row["expected_relations"], [])
            self.assertFalse(row["expected_kg_evidence"])
            self.assertEqual(row["predicate"], [])
            self.assertEqual(row["value"], [])
            self.assertFalse(row["store_decision"])

    def test_fail_closed_dataset_validator(self) -> None:
        result = module.validate_rows(self.rows, self.pilot_messages)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["pilot_overlap_count"], 0)

    def test_deterministic_generation(self) -> None:
        first, _ = module.repair_rows(self.v1_rows, self.pilot_messages)
        second, _ = module.repair_rows(self.v1_rows, self.pilot_messages)
        first_bytes = module.canonical_jsonl_bytes(first)
        second_bytes = module.canonical_jsonl_bytes(second)
        self.assertEqual(first_bytes, second_bytes)
        self.assertEqual(hashlib.sha256(first_bytes).hexdigest(), self.manifest["dataset_sha256"])

    def test_qc_deterministic_selection(self) -> None:
        first = module.select_qc(self.rows)
        second = module.select_qc(self.rows)
        self.assertEqual(first, second)
        self.assertEqual(len(first), 200)
        self.assertEqual([item["sample_id"] for item in first], [item["sample_id"] for item in self.manifest["qc_samples"]])
        self.assertEqual(set(row["category"] for row in self.rows), set(item["category"] for item in first))
        self.assertEqual(set(row["difficulty"] for row in self.rows), set(item["difficulty"] for item in first))

    def test_qc_linked_formulas_and_excel_error_scan(self) -> None:
        with zipfile.ZipFile(self.workbook_path) as archive:
            sheet_path = module._xlsx_sheet_path(archive, "QC_200")
            root = ET.fromstring(archive.read(sheet_path))
            ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
            formulas = [node.text or "" for node in root.findall(".//m:f", ns)]
            linked = [formula for formula in formulas if "INDEX(Review!" in formula and "MATCH($A" in formula]
            self.assertEqual(len(linked), 2400)
            xml_text = "\n".join(
                archive.read(name).decode("utf-8", errors="ignore")
                for name in archive.namelist()
                if name.endswith(".xml")
            )
        for error in module.FORMULA_ERRORS:
            self.assertNotIn(f">{error}<", xml_text)

    def test_workbook_counts_and_manifest_validation(self) -> None:
        review_rows = module.xlsx_table_as_dicts(self.workbook_path, "Review")
        qc_rows = module.xlsx_table_as_dicts(self.workbook_path, "QC_200")
        self.assertEqual(len(review_rows), 2000)
        self.assertEqual(len(qc_rows), 200)
        validation = self.manifest["workbook_validation"]
        self.assertEqual(validation["status"], "PASS")
        self.assertEqual(validation["formula_error_count"], 0)
        self.assertEqual(validation["qc_linked_formula_count"], 2400)


if __name__ == "__main__":
    unittest.main()
