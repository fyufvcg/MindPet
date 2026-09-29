"""Validate controls and compare completed Experiment 1 SQLite V1/V2 Pilot runs."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

import analyze_e2e_pilot_errors as diagnostic
import evaluate_e2e_pilot as formal


SCRIPT_DIR = Path(__file__).resolve().parent
EXPERIMENT_ROOT = SCRIPT_DIR.parent
DEFAULT_DATASET = EXPERIMENT_ROOT / "02-pilot-v1" / "datasets" / "pilot_30.jsonl"
PROMPT_HASHES = {
    "v1": "a2f27c59eb39499dc6682bb7e927afc0e19f87013559c0aeacf3c2ef8cb002c9",
    "v2": "cafa86f6e08703a60f236f4f19b371c3a79df917e74133aacce2377ee06e627e",
}
REQUIRED_PROVIDER = "deepseek"
REQUIRED_MODEL = "deepseek-flash"
REQUIRED_ENDPOINT_IDENTIFIER = "deepseek@api.deepseek.com"
REQUIRED_EMBEDDING_CONTROLS = {
    "embedding_provider": "ollama",
    "embedding_model": "bge-m3",
    "embedding_dimension": 1024,
    "embedding_endpoint_identifier": "ollama@127.0.0.1:11434",
}
REQUIRED_CONTROLS = {
    "temperature": 0.8,
    "llm_connect_timeout": "30s",
    "llm_read_timeout": "120s",
    "spring_ai_retry.max_attempts": 2,
    "spring_ai_retry.backoff_initial": "1000",
    "spring_ai_retry.backoff_max": "5000",
    "runner_http_timeout_seconds": 240.0,
    "request_policy": "serial, exactly once, fail-fast, no retry",
}
PLACEHOLDER = re.compile(r"(?i)(<[^>]*>|your[-_ ]?model|placeholder|change[-_ ]?me)")
SPECIAL_IDS = ("p017", "p019", "p024")


class ComparisonFailure(RuntimeError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ComparisonFailure(f"expected JSON object: {path}")
    return value


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_csv(path: Path, rows: list[dict[str, Any]], fields: list[str] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields or list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def nested(value: dict[str, Any], path: str) -> Any:
    current: Any = value
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    return current


def load_run(path: Path) -> dict[str, Any]:
    root = path.resolve(strict=True)
    records = read_jsonl(root / "raw" / "ingest_results.jsonl")
    mappings = read_jsonl(root / "raw" / "sample_mapping.jsonl")
    return {
        "path": root,
        "manifest": read_json(root / "raw" / "run_manifest.json"),
        "metrics": read_json(root / "metrics.json"),
        "records": records,
        "mappings": mappings,
        "record_by_id": {row["sample_id"]: row["response"] for row in records},
        "mapping_by_id": {row["sample_id"]: row for row in mappings},
        "sample_ids": [row["sample_id"] for row in records],
    }


def validate_controls(v1: dict[str, Any], v2: dict[str, Any], dataset: Path) -> tuple[list[str], dict[str, Any]]:
    issues: list[str] = []
    controls: dict[str, Any] = {}
    for variant, run in (("v1", v1), ("v2", v2)):
        manifest = run["manifest"]
        if manifest.get("status") != "COMPLETED":
            issues.append(f"{variant}: manifest status is not COMPLETED")
        if manifest.get("expected_prompt_variant") != variant:
            issues.append(f"{variant}: expected_prompt_variant mismatch")
        if manifest.get("prompt_sha256") != PROMPT_HASHES[variant]:
            issues.append(f"{variant}: prompt hash mismatch")
        model = str(manifest.get("model") or "")
        if not model or PLACEHOLDER.search(model):
            issues.append(f"{variant}: model is missing or placeholder")
        if manifest.get("provider") != REQUIRED_PROVIDER:
            issues.append(f"{variant}: provider must be {REQUIRED_PROVIDER}")
        if model != REQUIRED_MODEL:
            issues.append(f"{variant}: model must be {REQUIRED_MODEL}")
        if manifest.get("endpoint_config_identifier") != REQUIRED_ENDPOINT_IDENTIFIER:
            issues.append(f"{variant}: endpoint identifier must be {REQUIRED_ENDPOINT_IDENTIFIER}")
        for field, expected in REQUIRED_EMBEDDING_CONTROLS.items():
            if manifest.get(field) != expected:
                issues.append(f"{variant}: {field} does not match the fixed embedding control")
        for field, expected in REQUIRED_CONTROLS.items():
            if nested(manifest, field) != expected:
                issues.append(f"{variant}: {field} does not match the fixed experiment control")
        if len(run["records"]) != 30 or len(run["mappings"]) != 30:
            issues.append(f"{variant}: expected exactly 30 records and mappings")
        if run["sample_ids"] != [row.get("sample_id") for row in run["mappings"]]:
            issues.append(f"{variant}: raw record and mapping order differ")
        if manifest.get("sample_ids") != run["sample_ids"]:
            issues.append(f"{variant}: manifest sample ids differ from raw results")
        if not Path(str(manifest.get("sqlite_canonical_absolute_path", ""))).is_absolute():
            issues.append(f"{variant}: SQLite path is not absolute")

    equal_fields = (
        "dataset_sha256", "sqlite_schema_sha256", "provider", "model",
        "endpoint_config_identifier", "credential_source", "embedding_provider",
        "embedding_model", "embedding_dimension", "embedding_endpoint_identifier",
        "temperature", "llm_connect_timeout", "llm_read_timeout",
        "spring_ai_retry.max_attempts", "spring_ai_retry.backoff_initial",
        "spring_ai_retry.backoff_max", "runner_http_timeout_seconds", "fixed_eval_user",
        "runner_sha256", "evaluation_infrastructure_commit", "sample_count", "sample_ids",
        "request_policy",
    )
    for field in equal_fields:
        left, right = nested(v1["manifest"], field), nested(v2["manifest"], field)
        controls[field] = {"v1": left, "v2": right, "equal": left == right}
        if left is None or right is None:
            issues.append(f"missing required control: {field}")
        elif left != right:
            issues.append(f"control differs: {field}")
    dataset_hash = hashlib.sha256(dataset.read_bytes()).hexdigest()
    if v1["manifest"].get("dataset_sha256") != dataset_hash:
        issues.append("manifest dataset hash does not match the frozen dataset")
    left_db = Path(v1["manifest"].get("sqlite_canonical_absolute_path", "")).resolve(strict=False)
    right_db = Path(v2["manifest"].get("sqlite_canonical_absolute_path", "")).resolve(strict=False)
    controls["sqlite_paths_distinct"] = {"v1": str(left_db), "v2": str(right_db), "equal": left_db == right_db}
    if left_db == right_db:
        issues.append("V1 and V2 must use different SQLite files")
    if v1["sample_ids"] != v2["sample_ids"]:
        issues.append("V1 and V2 sample ids or order differ")
    return issues, controls


def normalized_metrics(samples: list[dict[str, Any]], run: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    entity_totals: Counter[str] = Counter()
    relation_totals: Counter[str] = Counter()
    entities_by_hash, relations_by_hash = diagnostic.fetch_actual(run["mappings"])
    for sample in samples:
        sample_id = sample["sample_id"]
        turn_hash = run["mapping_by_id"][sample_id]["turn_hash"]
        expected_entities = sample["expected_entities"]
        actual_entities = entities_by_hash.get(turn_hash, [])
        entity_pairs = diagnostic.maximum_matching(
            expected_entities, actual_entities,
            lambda expected, actual: expected["type"] == actual["type"]
            and diagnostic.name_equivalent(sample_id, expected["name"], actual["name"]),
        )
        entity_totals.update(tp=len(entity_pairs), fp=len(actual_entities) - len(entity_pairs), fn=len(expected_entities) - len(entity_pairs))
        expected_relations = diagnostic.expected_relation_rows(sample)
        actual_relations = [diagnostic.actual_relation_row(row) for row in relations_by_hash.get(turn_hash, [])]
        relation_pairs = diagnostic.maximum_matching(
            expected_relations, actual_relations,
            lambda expected, actual: diagnostic.canonical_predicate(expected["predicate"])
            == diagnostic.canonical_predicate(actual["predicate"])
            and diagnostic.endpoint_equivalent(sample_id, expected["source"], actual["source"])
            and diagnostic.endpoint_equivalent(sample_id, expected["target"], actual["target"]),
        )
        relation_totals.update(tp=len(relation_pairs), fp=len(actual_relations) - len(relation_pairs), fn=len(expected_relations) - len(relation_pairs))
    return diagnostic.metric(**entity_totals), diagnostic.metric(**relation_totals)


def sample_result(sample: dict[str, Any], run: dict[str, Any]) -> dict[str, Any]:
    sample_id = sample["sample_id"]
    response = run["record_by_id"][sample_id]
    mapping = run["mapping_by_id"][sample_id]
    expected_entities, expected_relations = formal.expected_sets(sample)
    actual_entities, actual_relations = formal.actual_sets(mapping)
    importance = response.get("importance")
    importance_error = abs(float(importance) - float(sample["human_importance"])) if isinstance(importance, (int, float)) else 1.0
    values = {
        "should_remember": response.get("shouldRemember"), "importance": importance,
        "ltm_persisted": response.get("ltmPersisted"),
        "entity_tp": len(expected_entities & actual_entities), "entity_fp": len(actual_entities - expected_entities),
        "entity_fn": len(expected_entities - actual_entities),
        "relation_tp": len(expected_relations & actual_relations), "relation_fp": len(actual_relations - expected_relations),
        "relation_fn": len(expected_relations - actual_relations),
        "evidence_rows": response.get("evidenceRowsCreated"), "turn_ingest_recorded": response.get("turnIngestRecorded"),
        "parse_failure": response.get("errorStage") == "EXTRACTION" or "PARSE" in str(response.get("errorType") or ""),
        "importance_fallback": response.get("parse", {}).get("importanceFallbackUsed", False),
        "ltm_failure": response.get("errorStage") == "LTM_PERSISTENCE",
        "kg_failure": response.get("errorStage") in {"KG_PERSISTENCE", "COMPLETION"},
        "entities": json.dumps(mapping.get("entities", []), ensure_ascii=False, separators=(",", ":")),
        "relations": json.dumps(mapping.get("relations", []), ensure_ascii=False, separators=(",", ":")),
    }
    decision_error = int((response.get("shouldRemember") is True) != sample["human_should_remember"])
    ltm_error = int((response.get("ltmPersisted") is True) != sample["human_should_remember"])
    evidence_error = int(bool(expected_entities or expected_relations) and int(response.get("evidenceRowsCreated") or 0) == 0)
    values["error_score"] = 3 * decision_error + 3 * ltm_error + importance_error + values["entity_fp"] + values["entity_fn"] + values["relation_fp"] + values["relation_fn"] + evidence_error
    return values


def metric_rows(
    left: dict[str, Any], right: dict[str, Any],
    left_normalized: tuple[dict[str, Any], dict[str, Any]],
    right_normalized: tuple[dict[str, Any], dict[str, Any]],
) -> list[dict[str, Any]]:
    definitions = [
        *[("ShouldRemember", name, f"should_remember.{name}", "higher", False)
          for name in ("accuracy", "precision", "recall", "f1")],
        *[("ShouldRemember", name, f"should_remember.{name}", "informational", False)
          for name in ("tp", "fp", "fn", "tn")],
        ("Importance", "mae", "importance.mae", "lower", False),
        ("Importance", "rmse", "importance.rmse", "lower", False),
        ("Importance", "spearman", "importance.spearman", "higher", False),
        *[("LTM persistence", name, f"ltm_persistence.{name}", "higher", False)
          for name in ("accuracy", "precision", "recall", "f1")],
        *[("LTM persistence", name, f"ltm_persistence.{name}", "informational", False)
          for name in ("tp", "fp", "fn", "tn")],
        *[("KG Entity strict", name, f"entity.{name}", "higher", False)
          for name in ("precision", "recall", "f1")],
        *[("KG Relation strict", name, f"relation.{name}", "higher", False)
          for name in ("precision", "recall", "f1")],
        ("Evidence coverage", "rate", "evidence_coverage.rate", "higher", False),
        ("Turn-ingest coverage", "success", "turn_ingest.success", "higher", False),
        *[("Diagnostics", name, f"diagnostics.{name}", "lower", False)
          for name in ("importance_fallback", "parse_failure", "ltm_failure", "kg_failure")],
    ]
    rows: list[dict[str, Any]] = []
    for group, metric, path, direction, diagnostic_only in definitions:
        v1, v2 = nested(left, path), nested(right, path)
        delta = v2 - v1 if isinstance(v1, (int, float)) and isinstance(v2, (int, float)) else None
        rows.append({"group": group, "metric": metric, "v1": v1, "v2": v2,
                     "delta_v2_minus_v1": delta, "direction": direction,
                     "diagnostic_only": diagnostic_only})
    for index, group in ((0, "KG Entity normalized"), (1, "KG Relation normalized")):
        for metric in ("precision", "recall", "f1"):
            v1, v2 = left_normalized[index][metric], right_normalized[index][metric]
            rows.append({"group": group, "metric": metric, "v1": v1, "v2": v2,
                         "delta_v2_minus_v1": v2 - v1, "direction": "higher",
                         "diagnostic_only": True})
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--v1-run", required=True, type=Path)
    parser.add_argument("--v2-run", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    args = parser.parse_args()
    output_dir = args.output_dir.resolve(strict=False)
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ComparisonFailure(f"refusing to overwrite non-empty output directory: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    samples = read_jsonl(args.dataset)
    if [row.get("sample_id") for row in samples] != [f"p{index:03d}" for index in range(1, 31)]:
        raise ComparisonFailure("comparison requires the frozen p001..p030 dataset")
    v1, v2 = load_run(args.v1_run), load_run(args.v2_run)
    issues, controls = validate_controls(v1, v2, args.dataset)
    valid = not issues
    v1_normalized, v2_normalized = normalized_metrics(samples, v1), normalized_metrics(samples, v2)
    metrics = metric_rows(v1["metrics"], v2["metrics"], v1_normalized, v2_normalized)

    cases: list[dict[str, Any]] = []
    result_fields = (
        "should_remember", "importance", "ltm_persisted", "entity_tp", "entity_fp", "entity_fn",
        "relation_tp", "relation_fp", "relation_fn", "evidence_rows", "turn_ingest_recorded",
        "parse_failure", "importance_fallback", "ltm_failure", "kg_failure", "entities", "relations",
    )
    for sample in samples:
        left, right = sample_result(sample, v1), sample_result(sample, v2)
        delta = right["error_score"] - left["error_score"]
        change = "improved" if delta < -1e-12 else "degraded" if delta > 1e-12 else "same"
        row: dict[str, Any] = {
            "sample_id": sample["sample_id"], "category": sample["category"],
            "difficulty": sample["difficulty"], "change": change,
            "v1_error_score": left["error_score"], "v2_error_score": right["error_score"],
        }
        for field in result_fields:
            row[f"v1_{field}"], row[f"v2_{field}"] = left[field], right[field]
        cases.append(row)

    summary = {
        "comparison_status": "VALID" if valid else "INVALID",
        "invalid_reasons": issues, "v1_run": str(v1["path"]), "v2_run": str(v2["path"]),
        "prompt_hashes": PROMPT_HASHES, "controls": controls,
        "case_changes": dict(Counter(row["change"] for row in cases)),
        "strict_metrics": {"v1": v1["metrics"], "v2": v2["metrics"]},
        "normalized_diagnostic_only": {
            "v1": {"entity": v1_normalized[0], "relation": v1_normalized[1]},
            "v2": {"entity": v2_normalized[0], "relation": v2_normalized[1]},
        },
    }
    write_json(output_dir / "comparison_summary.json", summary)
    write_csv(output_dir / "comparison_metrics.csv", metrics)
    write_csv(output_dir / "comparison_cases.csv", cases)
    write_csv(output_dir / "comparison_special_cases.csv",
              [row for row in cases if row["sample_id"] in SPECIAL_IDS], list(cases[0]))

    strict_lines = ["| group | metric | V1 | V2 | delta |", "|---|---|---:|---:|---:|"]
    normalized_lines = strict_lines.copy()
    for row in metrics:
        line = f"| {row['group']} | {row['metric']} | {row['v1']} | {row['v2']} | {row['delta_v2_minus_v1']} |"
        (normalized_lines if row["diagnostic_only"] else strict_lines).append(line)
    special_lines = [
        "| sample | change | V1 remember | V2 remember | V1 importance | V2 importance | V1 LTM | V2 LTM | V1 entities | V2 entities | V1 relations | V2 relations | V1 evidence | V2 evidence |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in cases:
        if row["sample_id"] in SPECIAL_IDS:
            special_lines.append(
                f"| {row['sample_id']} | {row['change']} | {row['v1_should_remember']} | {row['v2_should_remember']} | "
                f"{row['v1_importance']} | {row['v2_importance']} | {row['v1_ltm_persisted']} | {row['v2_ltm_persisted']} | "
                f"{row['v1_entity_tp'] + row['v1_entity_fp']} | {row['v2_entity_tp'] + row['v2_entity_fp']} | "
                f"{row['v1_relation_tp'] + row['v1_relation_fp']} | {row['v2_relation_tp'] + row['v2_relation_fp']} | "
                f"{row['v1_evidence_rows']} | {row['v2_evidence_rows']} |"
            )
    validity_text = (
        "Control variables passed. Metric deltas may be interpreted as a controlled V1/V2 comparison."
        if valid else "Comparison is INVALID. Do not claim that V2 improved or degraded performance.\n\n"
        + "\n".join(f"- {issue}" for issue in issues)
    )
    report = f"""# Experiment 1 Prompt V1 vs V2 Comparison

## Validity

**{summary['comparison_status']}**

{validity_text}

The V1 and V2 SQLite paths are intentionally required to be different. All other listed controls must match.

## Formal strict metrics

{chr(10).join(strict_lines)}

## Normalized matching diagnostics

These metrics are diagnostic only and do not replace the formal strict metrics.

{chr(10).join(normalized_lines)}

## Special cases

{chr(10).join(special_lines)}

## Per-sample changes

- Improved: {', '.join(row['sample_id'] for row in cases if row['change'] == 'improved') or 'none'}
- Same: {', '.join(row['sample_id'] for row in cases if row['change'] == 'same') or 'none'}
- Degraded: {', '.join(row['sample_id'] for row in cases if row['change'] == 'degraded') or 'none'}

Detailed component values and extracted entities/relations are in `comparison_cases.csv`.
"""
    (output_dir / "comparison_report.md").write_text(report, encoding="utf-8")
    print(json.dumps({"comparison_status": summary["comparison_status"], "output_dir": str(output_dir),
                      "invalid_reasons": issues}, ensure_ascii=False, indent=2))
    return 0 if valid else 2


if __name__ == "__main__":
    raise SystemExit(main())
