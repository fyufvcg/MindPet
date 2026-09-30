"""Evaluate a completed E2E memory Pilot against confirmed human ground truth."""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from pathlib import Path
from typing import Any

from e2e_db import write_json


TRACE_FIELDS = {
    "decision": {
        "rawWorthRemembering", "rawMemoryShouldRemember", "combinedShouldRemember",
        "importance", "confidence", "importanceThreshold", "confidenceThreshold",
        "importanceGatePassed", "confidenceGatePassed", "ltmAttempted",
        "ltmPersisted", "ltmFailureReason",
    },
    "parse": {
        "memoryObjectPresent", "importanceFallbackUsed", "confidenceFallbackUsed",
        "importanceClamped", "confidenceClamped", "parseFailure", "parseFailureReason",
    },
    "kgFilter": {
        "rawEntityCount", "rawRelationCount", "normalizedEntityCount",
        "normalizedRelationCount", "sensitivityRejectedEntityCount",
        "predicateWhitelistRejectedCount", "relationConfidenceRejectedCount",
        "persistedEntityCount", "persistedRelationCount", "evidenceCount",
    },
    "temporal": {
        "eventDate", "eventAt", "eventTimezone", "eventPrecision",
        "referenceTimestamp", "referenceTimezone",
    },
    "provenance": {
        "sampleId", "turnHash", "sessionId", "sourceUserMessageId",
        "sourceAssistantMessageId", "kgEntityRowIds", "kgRelationRowIds",
        "kgEvidenceRowIds", "longTermMemoryRowIds",
    },
}


def require_write_trace(response: dict[str, Any], sample_id: str) -> dict[str, Any]:
    trace = response.get("writeTrace")
    if not isinstance(trace, dict):
        raise ValueError(f"{sample_id}: evaluation contract failure: writeTrace missing")
    for section, required in TRACE_FIELDS.items():
        value = trace.get(section)
        if not isinstance(value, dict):
            raise ValueError(f"{sample_id}: evaluation contract failure: writeTrace.{section} missing")
        missing = sorted(required - value.keys())
        if missing:
            raise ValueError(
                f"{sample_id}: evaluation contract failure: writeTrace.{section} missing {missing}"
            )

    decision = trace["decision"]
    kg_filter = trace["kgFilter"]
    provenance = trace["provenance"]
    expected = {
        "combinedShouldRemember": response.get("shouldRemember"),
        "importance": response.get("importance"),
        "confidence": response.get("confidence"),
        "ltmAttempted": response.get("ltmAttempted"),
        "ltmPersisted": response.get("ltmPersisted"),
    }
    for field, value in expected.items():
        if decision[field] != value:
            raise ValueError(f"{sample_id}: evaluation contract failure: decision.{field} mismatch")
    count_fields = {
        "persistedEntityCount": response.get("entityRowsCreatedOrUpdated"),
        "persistedRelationCount": response.get("relationRowsCreatedOrUpdated"),
        "evidenceCount": response.get("evidenceRowsCreated"),
    }
    for field, value in count_fields.items():
        if kg_filter[field] != value:
            raise ValueError(f"{sample_id}: evaluation contract failure: kgFilter.{field} mismatch")
    if (provenance["sampleId"] != sample_id
            or provenance["turnHash"] != response.get("turnHash")
            or provenance["sessionId"] != response.get("sessionId")):
        raise ValueError(f"{sample_id}: evaluation contract failure: provenance mismatch")
    rows = response.get("rows")
    if not isinstance(rows, dict):
        raise ValueError(f"{sample_id}: evaluation contract failure: rows missing")
    row_mappings = {
        "kgEntityRowIds": "entityIds",
        "kgRelationRowIds": "relationIds",
        "kgEvidenceRowIds": "evidenceIds",
        "longTermMemoryRowIds": "longTermMemoryIds",
    }
    for trace_field, response_field in row_mappings.items():
        if provenance[trace_field] != rows.get(response_field):
            raise ValueError(
                f"{sample_id}: evaluation contract failure: provenance.{trace_field} mismatch"
            )
    return trace


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def normalize(value: str | None) -> str:
    return " ".join((value or "").strip().lower().split())


def classification(truth: list[bool], predicted: list[bool]) -> dict[str, Any]:
    tp = sum(t and p for t, p in zip(truth, predicted))
    fp = sum(not t and p for t, p in zip(truth, predicted))
    fn = sum(t and not p for t, p in zip(truth, predicted))
    tn = sum(not t and not p for t, p in zip(truth, predicted))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {
        "accuracy": (tp + tn) / len(truth) if truth else 0.0,
        "precision": precision,
        "recall": recall,
        "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
    }


def ranks(values: list[float]) -> list[float]:
    order = sorted(range(len(values)), key=values.__getitem__)
    output = [0.0] * len(values)
    index = 0
    while index < len(order):
        end = index + 1
        while end < len(order) and values[order[end]] == values[order[index]]:
            end += 1
        average = (index + 1 + end) / 2.0
        for position in range(index, end):
            output[order[position]] = average
        index = end
    return output


def pearson(left: list[float], right: list[float]) -> float:
    if len(left) < 2:
        return 0.0
    left_mean = sum(left) / len(left)
    right_mean = sum(right) / len(right)
    numerator = sum((x - left_mean) * (y - right_mean) for x, y in zip(left, right))
    left_sum = sum((x - left_mean) ** 2 for x in left)
    right_sum = sum((y - right_mean) ** 2 for y in right)
    denominator = math.sqrt(left_sum * right_sum)
    return numerator / denominator if denominator else 0.0


def continuous(truth: list[float], predicted: list[float]) -> dict[str, Any]:
    errors = [p - t for t, p in zip(truth, predicted)]
    return {
        "n": len(errors),
        "mae": sum(abs(value) for value in errors) / len(errors) if errors else None,
        "rmse": math.sqrt(sum(value * value for value in errors) / len(errors)) if errors else None,
        "spearman": pearson(ranks(truth), ranks(predicted)) if errors else None,
    }


def micro_sets(expected: list[set[Any]], actual: list[set[Any]]) -> dict[str, Any]:
    tp = sum(len(left & right) for left, right in zip(expected, actual))
    fp = sum(len(right - left) for left, right in zip(expected, actual))
    fn = sum(len(left - right) for left, right in zip(expected, actual))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {
        "precision": precision,
        "recall": recall,
        "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
        "tp": tp, "fp": fp, "fn": fn,
    }


def expected_sets(sample: dict[str, Any]) -> tuple[set[tuple[str, str]], set[tuple[Any, str, Any]]]:
    entities = {
        (normalize(entity["name"]), entity["type"])
        for entity in sample["expected_entities"]
    }
    by_key = {
        entity["entity_key"]: (normalize(entity["name"]), entity["type"])
        for entity in sample["expected_entities"]
    }
    relations = set()
    for relation in sample["expected_relations"]:
        source = "user" if relation["source_entity_key"] == "user" else by_key[relation["source_entity_key"]]
        target = "user" if relation["target_entity_key"] == "user" else by_key[relation["target_entity_key"]]
        relations.add((source, relation["predicate"], target))
    return entities, relations


def actual_sets(mapping: dict[str, Any]) -> tuple[set[tuple[str, str]], set[tuple[Any, str, Any]]]:
    entities = {
        (normalize(entity.get("normalizedName")), entity.get("entityType"))
        for entity in mapping.get("entities", [])
    }
    relations: set[tuple[Any, str, Any]] = set()
    for relation in mapping.get("relations", []):
        source_name = normalize(relation.get("sourceName"))
        target_name = normalize(relation.get("targetName"))
        source: Any = "user" if source_name == "user" else (source_name, relation.get("sourceType"))
        target: Any = "user" if target_name == "user" else (target_name, relation.get("targetType"))
        relations.add((source, relation.get("predicate"), target))
    return entities, relations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--run-dir", required=True, type=Path)
    args = parser.parse_args()
    samples = load_jsonl(args.dataset)
    records = load_jsonl(args.run_dir / "raw" / "ingest_results.jsonl")
    mappings = load_jsonl(args.run_dir / "raw" / "sample_mapping.jsonl")
    if len(samples) != 30 or len(records) != 30 or len(mappings) != 30:
        raise ValueError("formal Pilot evaluation requires exactly 30 samples/results/mappings")
    if [row["sample_id"] for row in samples] != [row["sample_id"] for row in records]:
        raise ValueError("raw result order does not match Ground Truth")

    responses = [row["response"] for row in records]
    traces = [
        require_write_trace(response, sample["sample_id"])
        for sample, response in zip(samples, responses)
    ]
    mapping_by_id = {row["sample_id"]: row for row in mappings}
    should_metrics = classification(
        [row["human_should_remember"] for row in samples],
        [response.get("shouldRemember") is True for response in responses],
    )
    ltm_metrics = classification(
        [row["human_should_remember"] for row in samples],
        [response.get("ltmPersisted") is True for response in responses],
    )
    paired = [
        (float(sample["human_importance"]), float(response["importance"]))
        for sample, response in zip(samples, responses)
        if isinstance(response.get("importance"), (int, float))
    ]
    importance_metrics = continuous(
        [left for left, _ in paired], [right for _, right in paired]
    )

    expected_entities: list[set[Any]] = []
    expected_relations: list[set[Any]] = []
    actual_entities: list[set[Any]] = []
    actual_relations: list[set[Any]] = []
    for sample in samples:
        expected_entity_set, expected_relation_set = expected_sets(sample)
        actual_entity_set, actual_relation_set = actual_sets(
            mapping_by_id[sample["sample_id"]]
        )
        expected_entities.append(expected_entity_set)
        expected_relations.append(expected_relation_set)
        actual_entities.append(actual_entity_set)
        actual_relations.append(actual_relation_set)

    kg_positive = [
        index for index, sample in enumerate(samples)
        if sample["expected_entities"] or sample["expected_relations"]
    ]
    evidence_covered = sum(responses[index].get("evidenceRowsCreated", 0) > 0 for index in kg_positive)
    statuses = Counter(response.get("status", "FAILED") for response in responses)
    diagnostics = {
        "parse_failure": sum(trace["parse"]["parseFailure"] for trace in traces),
        "memory_object_missing": sum(not trace["parse"]["memoryObjectPresent"] for trace in traces),
        "importance_fallback": sum(trace["parse"]["importanceFallbackUsed"] for trace in traces),
        "confidence_fallback": sum(trace["parse"]["confidenceFallbackUsed"] for trace in traces),
        "importance_clamp": sum(trace["parse"]["importanceClamped"] for trace in traces),
        "confidence_clamp": sum(trace["parse"]["confidenceClamped"] for trace in traces),
        "ltm_failure": sum(response.get("status") == "KG_ONLY_PARTIAL_SUCCESS" or response.get("errorStage") == "LTM_PERSISTENCE" for response in responses),
        "kg_failure": sum(response.get("status") == "FAILED" and response.get("errorStage") in {"KG_PERSISTENCE", "COMPLETION"} for response in responses),
        "duplicate": sum(response.get("duplicate", False) for response in responses),
        "prune": sum(response.get("pruneOccurred", False) for response in responses),
    }
    special = {}
    for sample_id in ("p017", "p019", "p024"):
        index = next(i for i, sample in enumerate(samples) if sample["sample_id"] == sample_id)
        response = responses[index]
        special[sample_id] = {
            "status": response.get("status"),
            "shouldRemember": response.get("shouldRemember"),
            "importance": response.get("importance"),
            "ltmPersisted": response.get("ltmPersisted"),
            "entityRows": response.get("entityRowsCreatedOrUpdated"),
            "relationRows": response.get("relationRowsCreatedOrUpdated"),
            "evidenceRows": response.get("evidenceRowsCreated"),
            "turnIngestRecorded": response.get("turnIngestRecorded"),
        }

    metrics = {
        "formal_requests": len(records),
        "success": sum(row["http_status"] == 200 and row["response"].get("status") != "FAILED" for row in records),
        "failed": sum(row["http_status"] != 200 or row["response"].get("status") == "FAILED" for row in records),
        "pipeline_status": dict(statuses),
        "should_remember": should_metrics,
        "importance": importance_metrics,
        "ltm_persistence": ltm_metrics,
        "entity": micro_sets(expected_entities, actual_entities),
        "relation": micro_sets(expected_relations, actual_relations),
        "evidence_coverage": {
            "covered": evidence_covered,
            "expected_kg_positive": len(kg_positive),
            "rate": evidence_covered / len(kg_positive) if kg_positive else 0.0,
        },
        "turn_ingest": {
            "success": sum(response.get("turnIngestRecorded") is True for response in responses),
            "total": len(responses),
        },
        "diagnostics": diagnostics,
        "special_cases": special,
    }
    write_json(args.run_dir / "metrics.json", metrics)
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
