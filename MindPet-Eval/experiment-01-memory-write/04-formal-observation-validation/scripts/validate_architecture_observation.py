"""Fail-closed validation for the Experiment 1 architecture-validation run."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
EXPERIMENT_ROOT = SCRIPT_DIR.parent.parent
SHARED_SCRIPTS = EXPERIMENT_ROOT / "scripts"
sys.path.insert(0, str(SHARED_SCRIPTS))

from evaluate_e2e_pilot import require_write_trace  # noqa: E402


PURPOSE = "ARCHITECTURE_VALIDATION_ONLY"
EXPECTED_SAMPLE_COUNT = 12
SPECIAL_IDS = ("p012", "p017", "p019", "p024")
TABLES = (
    "long_term_memory", "kg_entity", "kg_relation", "kg_evidence", "kg_turn_ingest"
)


class ValidationFailure(RuntimeError):
    pass


def require_key(value: dict[str, Any], key: str, context: str) -> Any:
    if key not in value:
        raise ValidationFailure(f"{context}: required field {key} is missing")
    return value[key]


def load_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ValidationFailure(f"required artifact is missing: {path}")
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValidationFailure(f"artifact must be a JSON object: {path}")
    return value


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise ValidationFailure(f"required artifact is missing: {path}")
    rows = [
        json.loads(line)
        for line in path.read_text(encoding="utf-8-sig").splitlines()
        if line.strip()
    ]
    if any(not isinstance(row, dict) for row in rows):
        raise ValidationFailure(f"artifact must contain JSON objects: {path}")
    return rows


def snapshot_rows(snapshot: dict[str, Any], table: str) -> list[dict[str, Any]]:
    tables = require_key(snapshot, "tables", "snapshot")
    if not isinstance(tables, dict):
        raise ValidationFailure("snapshot.tables must be an object")
    table_value = require_key(tables, table, "snapshot.tables")
    if not isinstance(table_value, dict):
        raise ValidationFailure(f"snapshot.tables.{table} must be an object")
    rows = require_key(table_value, "rows", f"snapshot.tables.{table}")
    count = require_key(table_value, "count", f"snapshot.tables.{table}")
    if not isinstance(rows, list) or count != len(rows):
        raise ValidationFailure(f"snapshot.tables.{table} count/rows mismatch")
    return rows


def ids(rows: list[dict[str, Any]], key: str, context: str) -> set[str]:
    result: set[str] = set()
    for row in rows:
        value = require_key(row, key, context)
        result.add(str(value))
    return result


def require_ids_exist(
    sample_id: str, mapped: list[Any], available: set[str], label: str
) -> None:
    if not isinstance(mapped, list):
        raise ValidationFailure(f"{sample_id}: {label} must be an array")
    missing = [str(value) for value in mapped if str(value) not in available]
    if missing:
        raise ValidationFailure(f"{sample_id}: {label} absent from snapshot: {missing}")


def write_reports(run_dir: Path, report: dict[str, Any]) -> None:
    validation_dir = run_dir / "validation"
    validation_dir.mkdir(parents=True, exist_ok=False)
    (validation_dir / "observation_contract_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    diagnostics = report["diagnostics"]
    lines = [
        "# Experiment 1 Architecture Validation",
        "",
        "- Status: PASS",
        f"- Purpose: {PURPOSE}",
        "- Result classification: NOT_FORMAL_RESULT",
        f"- Samples: {report['sample_count']}",
        "",
        "## Observation diagnostics",
        "",
    ]
    lines.extend(f"- {key}: {value}" for key, value in diagnostics.items())
    lines.extend(["", "## Required business sanity observations", ""])
    for sample_id in SPECIAL_IDS:
        value = report["business_sanity"][sample_id]
        lines.append(
            f"- {sample_id}: shouldRemember={value['shouldRemember']}, "
            f"ltmPersisted={value['ltmPersisted']}, "
            f"KG entity/relation/evidence={value['entityCount']}/"
            f"{value['relationCount']}/{value['evidenceCount']}"
        )
    (validation_dir / "observation_contract_report.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )


def validate(dataset: Path, run_dir: Path) -> dict[str, Any]:
    dataset_rows = load_jsonl(dataset)
    if len(dataset_rows) != EXPECTED_SAMPLE_COUNT:
        raise ValidationFailure("architecture dataset must contain exactly 12 samples")
    sample_ids = [require_key(row, "sample_id", "dataset row") for row in dataset_rows]
    if len(set(sample_ids)) != EXPECTED_SAMPLE_COUNT:
        raise ValidationFailure("architecture dataset sample IDs must be unique")

    raw_dir = run_dir / "raw"
    ingest_rows = load_jsonl(raw_dir / "ingest_results.jsonl")
    mappings = load_jsonl(raw_dir / "sample_mapping.jsonl")
    before = load_json(raw_dir / "snapshot_before.json")
    after = load_json(raw_dir / "snapshot_after.json")
    manifest = load_json(raw_dir / "run_manifest.json")

    if require_key(manifest, "run_purpose", "manifest") != PURPOSE:
        raise ValidationFailure("manifest does not identify architecture validation")
    if require_key(manifest, "formal_result", "manifest") is not False:
        raise ValidationFailure("manifest must mark the run as non-formal")
    if require_key(manifest, "result_label", "manifest") != "NOT_FORMAL_RESULT":
        raise ValidationFailure("manifest result label must be NOT_FORMAL_RESULT")
    if require_key(manifest, "sample_ids", "manifest") != sample_ids:
        raise ValidationFailure("manifest sample IDs do not match the dataset")
    if len(ingest_rows) != EXPECTED_SAMPLE_COUNT or len(mappings) != EXPECTED_SAMPLE_COUNT:
        raise ValidationFailure("raw ingest/mapping row count must equal 12")

    for table in TABLES:
        if snapshot_rows(before, table):
            raise ValidationFailure(f"snapshot_before.{table} is not empty")

    after_rows = {table: snapshot_rows(after, table) for table in TABLES}
    available = {
        "longTermMemoryRowIds": ids(after_rows["long_term_memory"], "id", "long_term_memory row"),
        "kgEntityRowIds": ids(after_rows["kg_entity"], "id", "kg_entity row"),
        "kgRelationRowIds": ids(after_rows["kg_relation"], "id", "kg_relation row"),
        "kgEvidenceRowIds": ids(after_rows["kg_evidence"], "id", "kg_evidence row"),
    }
    turn_rows = {
        str(require_key(row, "turn_hash", "kg_turn_ingest row")): row
        for row in after_rows["kg_turn_ingest"]
    }
    ltm_by_id = {str(row["id"]): row for row in after_rows["long_term_memory"]}
    evidence_by_id = {str(row["id"]): row for row in after_rows["kg_evidence"]}

    ingest_by_id: dict[str, dict[str, Any]] = {}
    for record in ingest_rows:
        sample_id = require_key(record, "sample_id", "ingest record")
        if sample_id in ingest_by_id:
            raise ValidationFailure(f"duplicate ingest record for {sample_id}")
        ingest_by_id[sample_id] = record
    mapping_by_id = {
        require_key(row, "sample_id", "mapping row"): row for row in mappings
    }
    if set(ingest_by_id) != set(sample_ids) or set(mapping_by_id) != set(sample_ids):
        raise ValidationFailure("raw artifact sample IDs do not match the dataset")

    traces: list[dict[str, Any]] = []
    sanity: dict[str, Any] = {}
    for sample_id in sample_ids:
        record = ingest_by_id[sample_id]
        if require_key(record, "http_status", sample_id) != 200:
            raise ValidationFailure(f"{sample_id}: HTTP status is not 200")
        response = require_key(record, "response", sample_id)
        if not isinstance(response, dict):
            raise ValidationFailure(f"{sample_id}: response must be an object")
        required_response = {
            "shouldRemember", "importance", "confidence", "ltmAttempted", "ltmPersisted",
            "entityRowsCreatedOrUpdated", "relationRowsCreatedOrUpdated",
            "evidenceRowsCreated", "sessionId", "turnHash", "rows", "writeTrace",
        }
        missing = sorted(required_response - response.keys())
        if missing:
            raise ValidationFailure(f"{sample_id}: response missing required fields {missing}")
        try:
            trace = require_write_trace(response, sample_id)
        except ValueError as exc:
            raise ValidationFailure(str(exc)) from exc
        traces.append(trace)

        provenance = trace["provenance"]
        mapping = mapping_by_id[sample_id]
        if require_key(mapping, "session_id", sample_id) != response["sessionId"]:
            raise ValidationFailure(f"{sample_id}: mapping sessionId mismatch")
        if require_key(mapping, "turn_hash", sample_id) != response["turnHash"]:
            raise ValidationFailure(f"{sample_id}: mapping turnHash mismatch")
        mapping_fields = {
            "long_term_memory_ids": "longTermMemoryRowIds",
            "entity_ids": "kgEntityRowIds",
            "relation_ids": "kgRelationRowIds",
            "evidence_ids": "kgEvidenceRowIds",
        }
        for mapping_field, trace_field in mapping_fields.items():
            if require_key(mapping, mapping_field, sample_id) != provenance[trace_field]:
                raise ValidationFailure(f"{sample_id}: mapping/provenance {trace_field} mismatch")
            require_ids_exist(sample_id, provenance[trace_field], available[trace_field], trace_field)

        if trace["decision"]["ltmPersisted"] != bool(provenance["longTermMemoryRowIds"]):
            raise ValidationFailure(f"{sample_id}: ltmPersisted/snapshot row mismatch")
        if trace["kgFilter"]["persistedEntityCount"] != len(provenance["kgEntityRowIds"]):
            raise ValidationFailure(f"{sample_id}: persisted entity count mismatch")
        if trace["kgFilter"]["persistedRelationCount"] != len(provenance["kgRelationRowIds"]):
            raise ValidationFailure(f"{sample_id}: persisted relation count mismatch")
        if trace["kgFilter"]["evidenceCount"] != len(provenance["kgEvidenceRowIds"]):
            raise ValidationFailure(f"{sample_id}: evidence count mismatch")

        session_id = response["sessionId"]
        turn_hash = response["turnHash"]
        turn_row = turn_rows.get(str(turn_hash))
        if turn_row is None or require_key(turn_row, "session_id", sample_id) != session_id:
            raise ValidationFailure(f"{sample_id}: turn ingest provenance missing from snapshot")
        for row_id in provenance["longTermMemoryRowIds"]:
            if require_key(ltm_by_id[str(row_id)], "session_id", sample_id) != session_id:
                raise ValidationFailure(f"{sample_id}: LTM snapshot session mismatch")
        for row_id in provenance["kgEvidenceRowIds"]:
            evidence = evidence_by_id[str(row_id)]
            if (require_key(evidence, "session_id", sample_id) != session_id
                    or require_key(evidence, "turn_hash", sample_id) != turn_hash):
                raise ValidationFailure(f"{sample_id}: evidence snapshot provenance mismatch")

        if sample_id in SPECIAL_IDS:
            sanity[sample_id] = {
                "shouldRemember": response["shouldRemember"],
                "ltmPersisted": response["ltmPersisted"],
                "entityCount": trace["kgFilter"]["persistedEntityCount"],
                "relationCount": trace["kgFilter"]["persistedRelationCount"],
                "evidenceCount": trace["kgFilter"]["evidenceCount"],
            }

    diagnostics = {
        "memoryObjectPresent": sum(trace["parse"]["memoryObjectPresent"] is True for trace in traces),
        "memoryObjectMissing": sum(trace["parse"]["memoryObjectPresent"] is False for trace in traces),
        "importanceFallbackUsed": sum(trace["parse"]["importanceFallbackUsed"] is True for trace in traces),
        "confidenceFallbackUsed": sum(trace["parse"]["confidenceFallbackUsed"] is True for trace in traces),
        "importanceClamped": sum(trace["parse"]["importanceClamped"] is True for trace in traces),
        "confidenceClamped": sum(trace["parse"]["confidenceClamped"] is True for trace in traces),
        "parseFailure": sum(trace["parse"]["parseFailure"] is True for trace in traces),
    }
    if diagnostics["memoryObjectPresent"] == 0:
        raise ValidationFailure("memoryObjectPresent is false for all samples; interface drift detected")
    if set(sanity) != set(SPECIAL_IDS):
        raise ValidationFailure("required business sanity samples are incomplete")

    return {
        "status": "PASS",
        "purpose": PURPOSE,
        "formal_result": False,
        "result_label": "NOT_FORMAL_RESULT",
        "sample_count": EXPECTED_SAMPLE_COUNT,
        "sample_ids": sample_ids,
        "category_coverage": dict(Counter(row["category"] for row in dataset_rows)),
        "difficulty_coverage": dict(Counter(row["difficulty"] for row in dataset_rows)),
        "checks": {
            "write_trace_sections_and_fields": "PASS",
            "top_level_trace_consistency": "PASS",
            "response_mapping_consistency": "PASS",
            "snapshot_row_provenance": "PASS",
            "snapshot_before_empty": "PASS",
        },
        "diagnostics": diagnostics,
        "business_sanity": sanity,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--run-dir", required=True, type=Path)
    args = parser.parse_args()
    report = validate(args.dataset, args.run_dir)
    write_reports(args.run_dir, report)
    print(f"architecture observation validation passed: {args.run_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
