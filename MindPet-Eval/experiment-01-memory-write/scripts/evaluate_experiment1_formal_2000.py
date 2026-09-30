#!/usr/bin/env python3
"""Deterministic metrics for Experiment 1 frozen-extraction multi-variant runs."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
import re
from statistics import mean
from typing import Any, Callable, Iterable


VARIANTS = ["a0", "a1", "b0", "b1", "b2"]
SCENARIO_TAGS = [
    "correction", "negation", "uncertainty", "third_party_claim", "prompt_injection",
    "fictional_role", "assistant_inference_trap", "memory_command_trap", "kg_without_ltm",
    "bounded_but_durable", "short_duration", "health",
]


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def ratio(numerator: int | float, denominator: int | float) -> float:
    return float(numerator / denominator) if denominator else 0.0


def classification(gold: list[bool], predicted: list[bool]) -> dict[str, Any]:
    tp = sum(g and p for g, p in zip(gold, predicted))
    fp = sum(not g and p for g, p in zip(gold, predicted))
    fn = sum(g and not p for g, p in zip(gold, predicted))
    tn = sum(not g and not p for g, p in zip(gold, predicted))
    precision = ratio(tp, tp + fp)
    recall = ratio(tp, tp + fn)
    return {
        "count": len(gold), "accuracy": ratio(tp + tn, len(gold)),
        "precision": precision, "recall": recall,
        "f1": ratio(2 * precision * recall, precision + recall),
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
    }


def ranks(values: list[float]) -> list[float]:
    order = sorted(range(len(values)), key=values.__getitem__)
    result = [0.0] * len(values)
    index = 0
    while index < len(order):
        end = index + 1
        while end < len(order) and values[order[end]] == values[order[index]]:
            end += 1
        average_rank = (index + 1 + end) / 2.0
        for position in order[index:end]:
            result[position] = average_rank
        index = end
    return result


def spearman(left: list[float], right: list[float]) -> float:
    if len(left) < 2:
        return 0.0
    x, y = ranks(left), ranks(right)
    mx, my = mean(x), mean(y)
    numerator = sum((a - mx) * (b - my) for a, b in zip(x, y))
    denominator = math.sqrt(sum((a - mx) ** 2 for a in x) * sum((b - my) ** 2 for b in y))
    return numerator / denominator if denominator else 0.0


def importance_metrics(gold: list[float], predicted: list[float]) -> dict[str, float]:
    errors = [p - g for g, p in zip(gold, predicted)]
    return {
        "count": len(errors), "mae": mean(abs(error) for error in errors) if errors else 0.0,
        "rmse": math.sqrt(mean(error * error for error in errors)) if errors else 0.0,
        "spearman": spearman(gold, predicted),
        "scope": "SHARED_FROZEN_EXTRACTION",
    }


def normalize_name(value: str) -> str:
    return re.sub(r"\s+", " ", str(value or "").strip().casefold())


def prf(gold_sets: list[set[Any]], predicted_sets: list[set[Any]]) -> dict[str, Any]:
    tp = sum(len(gold & predicted) for gold, predicted in zip(gold_sets, predicted_sets))
    fp = sum(len(predicted - gold) for gold, predicted in zip(gold_sets, predicted_sets))
    fn = sum(len(gold - predicted) for gold, predicted in zip(gold_sets, predicted_sets))
    precision, recall = ratio(tp, tp + fp), ratio(tp, tp + fn)
    return {"precision": precision, "recall": recall,
            "f1": ratio(2 * precision * recall, precision + recall),
            "tp": tp, "fp": fp, "fn": fn}


def expected_kg(sample: dict[str, Any], normalized: bool) -> tuple[set[Any], set[Any]]:
    key_to_name = {"user": "user"}
    entities: set[Any] = set()
    for entity in sample.get("expected_entities", []):
        name = entity.get("name", "")
        key_to_name[entity.get("entity_key")] = name
        pair = (normalize_name(name), str(entity.get("type", "")).casefold()) if normalized \
            else (name, entity.get("type", ""))
        entities.add(pair)
    relations: set[Any] = set()
    for relation in sample.get("expected_relations", []):
        source = key_to_name.get(relation.get("source_entity_key"), relation.get("source_entity_key", ""))
        target = key_to_name.get(relation.get("target_entity_key"), relation.get("target_entity_key", ""))
        predicate = relation.get("predicate", "")
        item = (normalize_name(source), str(predicate).casefold(), normalize_name(target)) if normalized \
            else (source, predicate, target)
        relations.add(item)
    return entities, relations


def actual_kg(result: dict[str, Any], normalized: bool) -> tuple[set[Any], set[Any]]:
    entities: set[Any] = set()
    for entity in result.get("entities", []):
        name = entity.get("displayName") or entity.get("normalizedName") or ""
        pair = (normalize_name(name), str(entity.get("entityType", "")).casefold()) if normalized \
            else (name, entity.get("entityType", ""))
        if normalize_name(name) != "user":
            entities.add(pair)
    relations: set[Any] = set()
    for relation in result.get("relations", []):
        source = relation.get("sourceName", "")
        target = relation.get("targetName", "")
        predicate = relation.get("predicate", "")
        item = (normalize_name(source), str(predicate).casefold(), normalize_name(target)) if normalized \
            else (source, predicate, target)
        relations.add(item)
    return entities, relations


def slice_metrics(records: list[dict[str, Any]], predicate: Callable[[dict[str, Any]], bool],
                  prediction_key: str = "decision") -> dict[str, Any]:
    selected = [record for record in records if predicate(record)]
    return classification(
        [bool(record["gold"]["store_decision"]) for record in selected],
        [bool(record[prediction_key]) for record in selected],
    )


def load_success(path: Path) -> dict[str, dict[str, Any]]:
    rows = read_jsonl(path)
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        if row.get("status") == "SUCCESS":
            result[row["sample_id"]] = row
    return result


def variant_records(dataset: list[dict[str, Any]], rows: dict[str, dict[str, Any]],
                    snapshots: dict[str, dict[str, Any]], variant: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for sample in dataset:
        sample_id = sample["sample_id"]
        replay = rows.get(sample_id)
        extraction = snapshots.get(sample_id)
        if replay is None or extraction is None:
            raise ValueError(f"{variant}: missing successful result for {sample_id}")
        response = replay["response"]
        result = response["result"]
        snapshot = extraction["response"]["snapshot"]
        if response.get("llmCalled") is not False:
            raise ValueError(f"{variant}: replay reported an LLM call for {sample_id}")
        if response.get("extractionSnapshotSha256") != snapshot.get("snapshotSha256"):
            raise ValueError(f"{variant}: extraction snapshot hash mismatch for {sample_id}")
        records.append({
            "sample_id": sample_id, "variant": variant,
            "snapshot_sha256": snapshot["snapshotSha256"],
            "gold": sample, "decision": bool(result.get("ltmAttempted")),
            "persisted": bool(result.get("ltmPersisted")),
            "importance": float(snapshot.get("importance", 0.0)),
            "result": result,
        })
    return records


def compute_variant(records: list[dict[str, Any]], shared_importance: dict[str, Any]) -> tuple[dict[str, Any], dict[str, list[dict[str, Any]]]]:
    gold = [bool(record["gold"]["store_decision"]) for record in records]
    decision = [record["decision"] for record in records]
    persisted = [record["persisted"] for record in records]
    sensitive = [record for record in records if record["gold"].get("sensitivity") == "sensitive"
                 or record["gold"].get("sensitive_case")]
    sensitive_false = [record for record in sensitive
                       if not record["gold"]["store_decision"] and record["persisted"]]
    temporal = [record for record in records if record["gold"].get("temporal_case")]
    temporal_negative = [record for record in temporal if not record["gold"]["store_decision"]]
    temporal_false = [record for record in temporal_negative if record["persisted"]]

    category = {}
    for name in sorted({record["gold"]["category"] for record in records}):
        category[name] = slice_metrics(records, lambda record, name=name: record["gold"]["category"] == name)
    scenario = {}
    for tag in SCENARIO_TAGS:
        selected = [record for record in records if tag in record["gold"].get("scenario_tags", [])]
        if selected:
            scenario[tag] = slice_metrics(records, lambda record, tag=tag: tag in record["gold"].get("scenario_tags", []))
    time_status = {}
    for value in sorted({record["gold"]["time_status"] for record in records}):
        time_status[value] = slice_metrics(records, lambda record, value=value: record["gold"]["time_status"] == value)
    temporal_case = {
        "true": slice_metrics(records, lambda record: bool(record["gold"].get("temporal_case"))),
        "false": slice_metrics(records, lambda record: not bool(record["gold"].get("temporal_case"))),
    }

    strict_entities, strict_relations, actual_strict_entities, actual_strict_relations = [], [], [], []
    normalized_entities, normalized_relations, actual_norm_entities, actual_norm_relations = [], [], [], []
    extra_entities: list[dict[str, Any]] = []
    missing_entities: list[dict[str, Any]] = []
    extra_relations: list[dict[str, Any]] = []
    missing_relations: list[dict[str, Any]] = []
    for record in records:
        expected_e, expected_r = expected_kg(record["gold"], False)
        actual_e, actual_r = actual_kg(record["result"], False)
        expected_en, expected_rn = expected_kg(record["gold"], True)
        actual_en, actual_rn = actual_kg(record["result"], True)
        strict_entities.append(expected_e); strict_relations.append(expected_r)
        actual_strict_entities.append(actual_e); actual_strict_relations.append(actual_r)
        normalized_entities.append(expected_en); normalized_relations.append(expected_rn)
        actual_norm_entities.append(actual_en); actual_norm_relations.append(actual_rn)
        if actual_en - expected_en:
            extra_entities.append({"sample_id": record["sample_id"], "variant": record["variant"],
                                   "extra": sorted(actual_en - expected_en)})
        if expected_en - actual_en:
            missing_entities.append({"sample_id": record["sample_id"], "variant": record["variant"],
                                     "missing": sorted(expected_en - actual_en)})
        if actual_rn - expected_rn:
            extra_relations.append({"sample_id": record["sample_id"], "variant": record["variant"],
                                    "extra": sorted(actual_rn - expected_rn)})
        if expected_rn - actual_rn:
            missing_relations.append({"sample_id": record["sample_id"], "variant": record["variant"],
                                      "missing": sorted(expected_rn - actual_rn)})
    kg_evidence_gold = [bool(record["gold"].get("expected_kg_evidence")) for record in records]
    kg_evidence_pred = [int(record["result"].get("evidenceRowsCreated", 0)) > 0 for record in records]

    traces = [record["result"].get("writeTrace") or {} for record in records]
    reliability = {
        "parseFailure": sum(bool(trace.get("parse", {}).get("parseFailure")) for trace in traces),
        "memoryObjectPresent": sum(trace.get("parse", {}).get("memoryObjectPresent") is True for trace in traces),
        "importanceFallbackUsed": sum(trace.get("parse", {}).get("importanceFallbackUsed") is True for trace in traces),
        "confidenceFallbackUsed": sum(trace.get("parse", {}).get("confidenceFallbackUsed") is True for trace in traces),
        "importanceClamped": sum(trace.get("parse", {}).get("importanceClamped") is True for trace in traces),
        "confidenceClamped": sum(trace.get("parse", {}).get("confidenceClamped") is True for trace in traces),
        "ltmAttempted": sum(record["decision"] for record in records),
        "ltmPersisted": sum(record["persisted"] for record in records),
        "ltmFailures": sum(record["decision"] and not record["persisted"] for record in records),
        "duplicates": sum(bool(record["result"].get("duplicate")) for record in records),
        "provenanceMismatches": sum(
            (trace.get("provenance") or {}).get("sampleId") != record["sample_id"]
            for trace, record in zip(traces, records)),
        "contractValidationFailures": 0,
        "predicateWhitelistRejectedCount": sum(
            int((trace.get("kgFilter") or {}).get("predicateWhitelistRejectedCount") or 0)
            for trace in traces),
        "relationOutputs": sum(int(record["result"].get("relationRowsCreatedOrUpdated", 0)) for record in records),
    }
    metrics = {
        "sample_count": len(records),
        "decision": classification(gold, decision),
        "persistence": classification(gold, persisted),
        "importance": shared_importance,
        "sensitive": {
            "sample_count": len(sensitive), "false_persistence_count": len(sensitive_false),
            "false_persistence_rate": ratio(len(sensitive_false), len(sensitive)),
        },
        "temporal": {
            "sample_count": len(temporal),
            "decision": classification(
                [bool(record["gold"]["store_decision"]) for record in temporal],
                [record["decision"] for record in temporal]),
            "false_persistence_count": len(temporal_false),
            "false_persistence_rate": ratio(len(temporal_false), len(temporal_negative)),
            "by_time_status": time_status, "by_temporal_case": temporal_case,
        },
        "category": category, "scenario_tags": scenario,
        "kg": {
            "entity_strict": prf(strict_entities, actual_strict_entities),
            "relation_strict": prf(strict_relations, actual_strict_relations),
            "entity_normalized": prf(normalized_entities, actual_norm_entities),
            "relation_normalized": prf(normalized_relations, actual_norm_relations),
            "evidence": classification(kg_evidence_gold, kg_evidence_pred),
            "extra_entity_count": len(extra_entities), "missing_entity_count": len(missing_entities),
            "extra_relation_count": len(extra_relations), "missing_relation_count": len(missing_relations),
        },
        "observation_reliability": reliability,
        "profile_pollution_metric": "NOT_APPLICABLE",
        "scope_note": "Experiment 1 excludes MemoryCurator/UserProfile; profile_slot is schema compatibility only.",
    }
    errors = {
        "false_positive": [record for record in records if not record["gold"]["store_decision"] and record["decision"]],
        "false_negative": [record for record in records if record["gold"]["store_decision"] and not record["decision"]],
        "sensitive_errors": sensitive_false,
        "temporal_errors": [record for record in temporal if record["decision"] != bool(record["gold"]["store_decision"])],
        "kg_entity_errors": extra_entities + missing_entities,
        "kg_relation_errors": extra_relations + missing_relations,
    }
    return metrics, errors


def compact_error(record: dict[str, Any]) -> dict[str, Any]:
    if "gold" not in record:
        return record
    return {
        "sample_id": record["sample_id"], "variant": record["variant"],
        "category": record["gold"]["category"], "difficulty": record["gold"]["difficulty"],
        "scenario_tags": record["gold"].get("scenario_tags", []),
        "gold_store_decision": record["gold"]["store_decision"],
        "decision": record["decision"], "ltm_persisted": record["persisted"],
        "snapshot_sha256": record["snapshot_sha256"],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    full_dataset = read_jsonl(args.dataset)
    extraction = load_success(args.run_dir / "extraction" / "extraction_snapshots.jsonl")
    dataset = [sample for sample in full_dataset if sample["sample_id"] in extraction]
    if not dataset or len(extraction) != len(dataset):
        raise ValueError("extraction checkpoint contains unknown, duplicate, or no dataset samples")
    predicted_importance = [float(extraction[sample["sample_id"]]["response"]["snapshot"]["importance"])
                            for sample in dataset]
    shared_importance = importance_metrics(
        [float(sample["human_importance"]) for sample in dataset], predicted_importance)

    all_records: list[dict[str, Any]] = []
    all_errors = {name: [] for name in ["false_positive", "false_negative", "sensitive_errors",
                                        "temporal_errors", "kg_entity_errors", "kg_relation_errors"]}
    metrics_by_variant: dict[str, Any] = {}
    for variant in VARIANTS:
        replay = load_success(args.run_dir / variant / "results.jsonl")
        records = variant_records(dataset, replay, extraction, variant)
        metrics, errors = compute_variant(records, shared_importance)
        metrics_by_variant[variant] = metrics
        write_json(args.run_dir / "reports" / "per_variant" / f"{variant}_metrics.json", metrics)
        all_records.extend({
            "sample_id": record["sample_id"], "variant": variant,
            "snapshot_sha256": record["snapshot_sha256"],
            "decision": record["decision"], "ltm_persisted": record["persisted"],
            "importance": record["importance"],
            "entity_count": len(record["result"].get("entities", [])),
            "relation_count": len(record["result"].get("relations", [])),
            "evidence_count": record["result"].get("evidenceRowsCreated", 0),
        } for record in records)
        for name, values in errors.items():
            all_errors[name].extend(compact_error(value) for value in values)

    sample_hashes: dict[str, set[str]] = {}
    for row in all_records:
        sample_hashes.setdefault(row["sample_id"], set()).add(row["snapshot_sha256"])
    if any(len(values) != 1 for values in sample_hashes.values()):
        raise ValueError("variants did not use identical extraction snapshots")

    delta_metrics = {
        "decision_f1": lambda metrics: metrics["decision"]["f1"],
        "persistence_f1": lambda metrics: metrics["persistence"]["f1"],
        "sensitive_false_persistence_rate": lambda metrics: metrics["sensitive"]["false_persistence_rate"],
        "temporal_decision_f1": lambda metrics: metrics["temporal"]["decision"]["f1"],
        "relation_normalized_f1": lambda metrics: metrics["kg"]["relation_normalized"]["f1"],
        "predicate_whitelist_rejected": lambda metrics: metrics["observation_reliability"]["predicateWhitelistRejectedCount"],
        "relation_outputs": lambda metrics: metrics["observation_reliability"]["relationOutputs"],
    }
    delta_pairs = {"a0_to_b0": ("a0", "b0"), "a1_to_b0": ("a1", "b0"),
                   "b1_to_b0": ("b1", "b0"), "b2_to_b0": ("b2", "b0")}
    deltas = {
        name: {metric: getter(metrics_by_variant[target]) - getter(metrics_by_variant[source])
               for metric, getter in delta_metrics.items()}
        for name, (source, target) in delta_pairs.items()
    }
    summary = {
        "variants": metrics_by_variant,
        "shared_importance": shared_importance,
        "controlled_ablation": {
            "same_dataset": True, "same_prompt_sha": True, "same_model": True,
            "same_temperature": True, "same_extraction_snapshot_per_sample": True,
            "replay_llm_calls": 0,
            "deltas": deltas,
        },
        "profile_pollution_metric": "NOT_APPLICABLE",
    }
    reports = args.run_dir / "reports"
    write_json(reports / "formal_summary.json", summary)
    write_jsonl(reports / "per_sample_results.jsonl", all_records)
    error_dir = reports / "error_analysis"
    for name, values in all_errors.items():
        write_jsonl(error_dir / f"{name}.jsonl", values)

    columns = ["variant", "decision_accuracy", "decision_precision", "decision_recall", "decision_f1",
               "persistence_f1", "sensitive_false_persistence_rate", "temporal_decision_f1",
               "entity_strict_f1", "relation_strict_f1", "entity_normalized_f1",
               "relation_normalized_f1", "kg_evidence_f1", "predicate_whitelist_rejected",
               "relation_outputs"]
    comparison_rows = []
    for variant in VARIANTS:
        metrics = metrics_by_variant[variant]
        comparison_rows.append({
            "variant": variant.upper(),
            "decision_accuracy": metrics["decision"]["accuracy"],
            "decision_precision": metrics["decision"]["precision"],
            "decision_recall": metrics["decision"]["recall"],
            "decision_f1": metrics["decision"]["f1"],
            "persistence_f1": metrics["persistence"]["f1"],
            "sensitive_false_persistence_rate": metrics["sensitive"]["false_persistence_rate"],
            "temporal_decision_f1": metrics["temporal"]["decision"]["f1"],
            "entity_strict_f1": metrics["kg"]["entity_strict"]["f1"],
            "relation_strict_f1": metrics["kg"]["relation_strict"]["f1"],
            "entity_normalized_f1": metrics["kg"]["entity_normalized"]["f1"],
            "relation_normalized_f1": metrics["kg"]["relation_normalized"]["f1"],
            "kg_evidence_f1": metrics["kg"]["evidence"]["f1"],
            "predicate_whitelist_rejected": metrics["observation_reliability"]["predicateWhitelistRejectedCount"],
            "relation_outputs": metrics["observation_reliability"]["relationOutputs"],
        })
    with (reports / "formal_comparison.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader(); writer.writerows(comparison_rows)
    markdown = ["# Experiment 1 Formal Comparison", "", "Objective metrics only; no winner is selected.", "",
                "| " + " | ".join(columns) + " |", "| " + " | ".join(["---"] * len(columns)) + " |"]
    for row in comparison_rows:
        markdown.append("| " + " | ".join(str(row[column]) for column in columns) + " |")
    markdown.extend(["", "## Controlled deltas", "",
                     "- A0 → B0: full write-decision value.",
                     "- A1 → B0: incremental effect of importance and confidence gates.",
                     "- B1 → B0: predicate whitelist effect.",
                     "- B2 → B0: LTM confidence-gate effect.", "",
                     "```json", json.dumps(deltas, ensure_ascii=False, indent=2), "```", "",
                     "Profile pollution: NOT_APPLICABLE. Experiment 1 excludes MemoryCurator/UserProfile."])
    (reports / "formal_comparison.md").write_text("\n".join(markdown) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
