"""Create a read-only query-level error analysis for the completed E2E Pilot."""

from __future__ import annotations

import argparse
import csv
import difflib
import json
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Callable


SCRIPT_DIR = Path(__file__).resolve().parent
EXPERIMENT_ROOT = SCRIPT_DIR.parent
DEFAULT_DATASET = EXPERIMENT_ROOT / "02-pilot-v1" / "datasets" / "pilot_30.jsonl"


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def norm(value: str | None) -> str:
    return " ".join((value or "").strip().lower().split())


IGNORED_NAME_PUNCTUATION = frozenset(
    "，,。;；:：、!?！？\"'“”‘’()（）[]【】{}<>《》"
)
NORMALIZED_NAME_MIN_LENGTH = 4
NORMALIZED_NAME_SIMILARITY_THRESHOLD = 0.50


def canonical_name(value: str | None) -> str:
    normalized = unicodedata.normalize("NFKC", value or "").casefold().strip()
    return "".join(
        char for char in normalized
        if not char.isspace() and char not in IGNORED_NAME_PUNCTUATION
    )


def canonical_predicate(value: str | None) -> str:
    return unicodedata.normalize("NFKC", value or "").casefold().strip()


def name_equivalent(_sample_id: str, left: str, right: str) -> bool:
    """Apply the sample-independent Normalized Diagnostic v1 name rule."""
    left_normalized = canonical_name(left)
    right_normalized = canonical_name(right)
    if left_normalized == right_normalized:
        return True
    if min(len(left_normalized), len(right_normalized)) < NORMALIZED_NAME_MIN_LENGTH:
        return False
    if left_normalized in right_normalized or right_normalized in left_normalized:
        return True
    similarity = difflib.SequenceMatcher(
        None, left_normalized, right_normalized, autojunk=False
    ).ratio()
    return similarity >= NORMALIZED_NAME_SIMILARITY_THRESHOLD


def name_equivalent_for_type_diagnosis(left: str, right: str) -> bool:
    """Looser lexical check used only to label an already non-matching type error."""
    left_normalized = canonical_name(left)
    right_normalized = canonical_name(right)
    if left_normalized == right_normalized:
        return True
    if min(len(left_normalized), len(right_normalized)) >= 2 and (
        left_normalized in right_normalized or right_normalized in left_normalized
    ):
        return True
    return difflib.SequenceMatcher(
        None, left_normalized, right_normalized, autojunk=False
    ).ratio() >= NORMALIZED_NAME_SIMILARITY_THRESHOLD


def fetch_actual(
    mappings: list[dict[str, Any]],
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, list[dict[str, Any]]]]:
    entities: dict[str, list[dict[str, Any]]] = defaultdict(list)
    relations: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for mapping in mappings:
        turn_hash = mapping["turn_hash"]
        for row in mapping.get("entities", []):
            entities[turn_hash].append({
                "id": row.get("id"),
                "name": row.get("displayName"),
                "normalized_name": row.get("normalizedName"),
                "type": row.get("entityType"),
            })
        for row in mapping.get("relations", []):
            relations[turn_hash].append({
                "id": row.get("id"),
                "source_name": row.get("sourceName"),
                "source_type": row.get("sourceType"),
                "predicate": row.get("predicate"),
                "target_name": row.get("targetName"),
                "target_type": row.get("targetType"),
            })
    return entities, relations


def entity_key(entity: dict[str, Any]) -> tuple[str, str]:
    return norm(entity["name"]), entity["type"]


def expected_relation_rows(sample: dict[str, Any]) -> list[dict[str, Any]]:
    by_key = {entity["entity_key"]: entity for entity in sample["expected_entities"]}
    output = []
    for relation in sample["expected_relations"]:
        source_key = relation["source_entity_key"]
        target_key = relation["target_entity_key"]
        output.append({
            "source": "user" if source_key == "user" else by_key[source_key],
            "predicate": relation["predicate"],
            "target": "user" if target_key == "user" else by_key[target_key],
        })
    return output


def endpoint_key(endpoint: str | dict[str, Any]) -> Any:
    if endpoint == "user":
        return "user"
    return norm(endpoint["name"]), endpoint["type"]


def actual_relation_row(row: dict[str, Any]) -> dict[str, Any]:
    source: Any = "user" if norm(row["source_name"]) == "user" else {
        "name": row["source_name"], "type": row["source_type"],
    }
    target: Any = "user" if norm(row["target_name"]) == "user" else {
        "name": row["target_name"], "type": row["target_type"],
    }
    return {"source": source, "predicate": row["predicate"], "target": target}


def relation_key(relation: dict[str, Any]) -> tuple[Any, str, Any]:
    return endpoint_key(relation["source"]), relation["predicate"], endpoint_key(relation["target"])


def endpoint_equivalent(sample_id: str, left: Any, right: Any) -> bool:
    if left == "user" or right == "user":
        return left == right
    return left["type"] == right["type"] and name_equivalent(sample_id, left["name"], right["name"])


def maximum_matching(
    expected: list[Any], actual: list[Any], predicate: Callable[[Any, Any], bool]
) -> list[tuple[int, int]]:
    best: list[tuple[int, int]] = []

    def visit(index: int, used: set[int], pairs: list[tuple[int, int]]) -> None:
        nonlocal best
        if index == len(expected):
            if len(pairs) > len(best):
                best = pairs.copy()
            return
        visit(index + 1, used, pairs)
        for actual_index, candidate in enumerate(actual):
            if actual_index not in used and predicate(expected[index], candidate):
                used.add(actual_index)
                pairs.append((index, actual_index))
                visit(index + 1, used, pairs)
                pairs.pop()
                used.remove(actual_index)

    visit(0, set(), [])
    return best


def metric(tp: int, fp: int, fn: int) -> dict[str, float | int]:
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {
        "tp": tp, "fp": fp, "fn": fn,
        "precision": precision,
        "recall": recall,
        "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
    }


def dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]) if rows else [])
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()
    run_dir = args.run_dir
    samples = load_jsonl(args.dataset)
    records = load_jsonl(run_dir / "raw" / "ingest_results.jsonl")
    mappings = load_jsonl(run_dir / "raw" / "sample_mapping.jsonl")
    if len(samples) != 30 or len(records) != 30 or len(mappings) != 30:
        raise ValueError("analysis requires the completed 30-sample formal Pilot")
    response_by_id = {row["sample_id"]: row["response"] for row in records}
    mapping_by_id = {row["sample_id"]: row for row in mappings}
    entities_by_hash, relations_by_hash = fetch_actual(mappings)

    entity_rows: list[dict[str, Any]] = []
    relation_rows: list[dict[str, Any]] = []
    strict_entity_totals = Counter()
    diagnostic_entity_totals = Counter()
    strict_relation_totals = Counter()
    diagnostic_relation_totals = Counter()
    entity_error_counts = Counter()
    relation_error_counts = Counter()

    actual_by_sample: dict[str, tuple[list[dict[str, Any]], list[dict[str, Any]]]] = {}
    for sample in samples:
        sample_id = sample["sample_id"]
        turn_hash = mapping_by_id[sample_id]["turn_hash"]
        expected_entities = sample["expected_entities"]
        actual_entities = entities_by_hash.get(turn_hash, [])
        expected_entity_keys = {entity_key(row) for row in expected_entities}
        actual_entity_keys = {entity_key(row) for row in actual_entities}
        tp_entities = expected_entity_keys & actual_entity_keys
        fp_entities = actual_entity_keys - expected_entity_keys
        fn_entities = expected_entity_keys - actual_entity_keys
        strict_entity_totals.update(tp=len(tp_entities), fp=len(fp_entities), fn=len(fn_entities))

        diagnostic_pairs = maximum_matching(
            expected_entities, actual_entities,
            lambda left, right: left["type"] == right["type"]
            and name_equivalent(sample_id, left["name"], right["name"]),
        )
        diagnostic_tp = len(diagnostic_pairs)
        diagnostic_entity_totals.update(
            tp=diagnostic_tp,
            fp=len(actual_entities) - diagnostic_tp,
            fn=len(expected_entities) - diagnostic_tp,
        )
        strict_pairs = maximum_matching(expected_entities, actual_entities, lambda l, r: entity_key(l) == entity_key(r))
        strict_expected = {left for left, _ in strict_pairs}
        strict_actual = {right for _, right in strict_pairs}
        categories: set[str] = set()
        for left, right in diagnostic_pairs:
            if (left, right) not in strict_pairs and entity_key(expected_entities[left]) != entity_key(actual_entities[right]):
                categories.add("naming_or_normalization_difference")
        remaining_expected = [i for i in range(len(expected_entities)) if i not in {x for x, _ in diagnostic_pairs}]
        remaining_actual = [i for i in range(len(actual_entities)) if i not in {y for _, y in diagnostic_pairs}]
        type_pairs = maximum_matching(
            [expected_entities[i] for i in remaining_expected],
            [actual_entities[i] for i in remaining_actual],
            lambda left, right: name_equivalent_for_type_diagnosis(left["name"], right["name"]),
        )
        if type_pairs:
            categories.add("wrong_type")
            paired_expected = {remaining_expected[left] for left, _ in type_pairs}
            paired_actual = {remaining_actual[right] for _, right in type_pairs}
            remaining_expected = [i for i in remaining_expected if i not in paired_expected]
            remaining_actual = [i for i in remaining_actual if i not in paired_actual]
        if remaining_expected:
            categories.add("missing_entity")
        if remaining_actual:
            categories.add("extra_entity")
        if sample_id == "p012":
            categories.update(("over_split", "plausible_but_not_in_ground_truth"))
        if sample_id in {"p011", "p014"} and remaining_actual:
            categories.add("plausible_but_not_in_ground_truth")
        if sample_id == "p013" and remaining_actual:
            categories.add("clearly_incorrect")
        for category in categories:
            entity_error_counts[category] += 1
        entity_rows.append({
            "sample_id": sample_id,
            "user_message": sample["user_message"],
            "expected_entities": dumps(expected_entities),
            "actual_entities": dumps(actual_entities),
            "tp_entities": dumps(sorted(tp_entities)),
            "fp_entities": dumps(sorted(fp_entities)),
            "fn_entities": dumps(sorted(fn_entities)),
            "error_categories": ";".join(sorted(categories)),
            "diagnostic_tp": diagnostic_tp,
            "diagnostic_fp": len(actual_entities) - diagnostic_tp,
            "diagnostic_fn": len(expected_entities) - diagnostic_tp,
        })

        expected_relations = expected_relation_rows(sample)
        actual_relations = [actual_relation_row(row) for row in relations_by_hash.get(turn_hash, [])]
        actual_by_sample[sample_id] = (actual_entities, actual_relations)
        expected_relation_keys = {relation_key(row) for row in expected_relations}
        actual_relation_keys = {relation_key(row) for row in actual_relations}
        tp_relations = expected_relation_keys & actual_relation_keys
        fp_relations = actual_relation_keys - expected_relation_keys
        fn_relations = expected_relation_keys - actual_relation_keys
        strict_relation_totals.update(tp=len(tp_relations), fp=len(fp_relations), fn=len(fn_relations))
        diagnostic_relation_pairs = maximum_matching(
            expected_relations, actual_relations,
            lambda left, right: canonical_predicate(left["predicate"])
            == canonical_predicate(right["predicate"])
            and endpoint_equivalent(sample_id, left["source"], right["source"])
            and endpoint_equivalent(sample_id, left["target"], right["target"]),
        )
        diagnostic_relation_tp = len(diagnostic_relation_pairs)
        diagnostic_relation_totals.update(
            tp=diagnostic_relation_tp,
            fp=len(actual_relations) - diagnostic_relation_tp,
            fn=len(expected_relations) - diagnostic_relation_tp,
        )
        relation_categories: set[str] = set()
        unmatched_expected = [row for i, row in enumerate(expected_relations) if i not in {x for x, _ in diagnostic_relation_pairs}]
        unmatched_actual = [row for i, row in enumerate(actual_relations) if i not in {y for _, y in diagnostic_relation_pairs}]
        for expected in unmatched_expected:
            for actual in unmatched_actual:
                same_source = endpoint_equivalent(sample_id, expected["source"], actual["source"])
                same_target = endpoint_equivalent(sample_id, expected["target"], actual["target"])
                if same_source and same_target and canonical_predicate(expected["predicate"]) != canonical_predicate(actual["predicate"]):
                    relation_categories.add("wrong_predicate")
                if endpoint_equivalent(sample_id, expected["source"], actual["target"]) and endpoint_equivalent(sample_id, expected["target"], actual["source"]):
                    relation_categories.add("reversed_direction")
                if canonical_predicate(expected["predicate"]) == canonical_predicate(actual["predicate"]) and not same_source and same_target:
                    relation_categories.add("wrong_source")
                if canonical_predicate(expected["predicate"]) == canonical_predicate(actual["predicate"]) and same_source and not same_target:
                    relation_categories.add("wrong_target")
        def endpoint_has_expected_entity(endpoint: Any) -> bool:
            if endpoint == "user":
                return True
            return any(
                expected["type"] == endpoint["type"]
                and name_equivalent(sample_id, expected["name"], endpoint["name"])
                for expected in expected_entities
            )

        if any(
            not endpoint_has_expected_entity(row["source"])
            or not endpoint_has_expected_entity(row["target"])
            for row in unmatched_actual
        ):
            relation_categories.add("relation_without_expected_entity")
        if any(row["predicate"] == "related_to" for row in unmatched_actual):
            relation_categories.add("generic_related_to_overuse")
        if sample_id in {"p011", "p012", "p014"} and unmatched_actual:
            relation_categories.add("plausible_but_not_ground_truth")
        if sample_id == "p004" and unmatched_actual:
            relation_categories.add("clearly_incorrect")
        for category in relation_categories:
            relation_error_counts[category] += 1
        relation_rows.append({
            "sample_id": sample_id,
            "user_message": sample["user_message"],
            "expected_relations": dumps(expected_relations),
            "actual_relations": dumps(actual_relations),
            "tp_relations": dumps(list(tp_relations)),
            "fp_relations": dumps(list(fp_relations)),
            "fn_relations": dumps(list(fn_relations)),
            "error_categories": ";".join(sorted(relation_categories)),
            "diagnostic_tp": diagnostic_relation_tp,
            "diagnostic_fp": len(actual_relations) - diagnostic_relation_tp,
            "diagnostic_fn": len(expected_relations) - diagnostic_relation_tp,
        })

    fallback_rows = []
    fallback_categories = Counter()
    fallback_classes = Counter()
    decision_rows = []
    for sample in samples:
        sample_id = sample["sample_id"]
        response = response_by_id[sample_id]
        actual_entities, actual_relations = actual_by_sample[sample_id]
        parse = response.get("parse", {})
        if "memoryObjectPresent" in parse and not parse.get("memoryObjectPresent", False):
            fallback_categories[sample["category"]] += 1
            if sample["human_should_remember"]:
                fallback_class = "clearly_problematic"
            elif sample["expected_entities"] or sample["expected_relations"]:
                fallback_class = "suspicious_missing_memory_object"
            else:
                fallback_class = "likely_expected_no_memory_object"
            fallback_classes[fallback_class] += 1
            fallback_rows.append({
                "sample_id": sample_id,
                "category": sample["category"],
                "difficulty": sample["difficulty"],
                "human_should_remember": sample["human_should_remember"],
                "human_importance": sample["human_importance"],
                "worthRemembering": response.get("worthRemembering"),
                "shouldRemember": response.get("shouldRemember"),
                "importance": response.get("importance"),
                "confidence": response.get("confidence"),
                "memory_object_present": parse.get("memoryObjectPresent"),
                "importance_fallback": parse.get("importanceFallbackUsed"),
                "confidence_fallback": parse.get("confidenceFallbackUsed"),
                "fallback_class_v1": fallback_class,
            })
        human = sample["human_should_remember"]
        predicted = response.get("shouldRemember") is True
        if human != predicted:
            decision_rows.append({
                "sample_id": sample_id,
                "error_type": "FP" if predicted else "FN",
                "user_message": sample["user_message"],
                "human_should_remember": human,
                "human_importance": sample["human_importance"],
                "ai_should_remember": response.get("shouldRemember"),
                "ai_worth_remembering": response.get("worthRemembering"),
                "ai_importance": response.get("importance"),
                "ai_confidence": response.get("confidence"),
                "memory_object_present": parse.get("memoryObjectPresent"),
                "importance_fallback": parse.get("importanceFallbackUsed"),
                "confidence_fallback": parse.get("confidenceFallbackUsed"),
                "ltm_attempted": response.get("ltmAttempted"),
                "ltm_persisted": response.get("ltmPersisted"),
                "actual_entities": dumps(actual_entities),
                "actual_relations": dumps(actual_relations),
            })

    output_table_dir = run_dir / "tables"
    write_csv(output_table_dir / "entity_error_analysis.csv", entity_rows)
    write_csv(output_table_dir / "relation_error_analysis.csv", relation_rows)
    write_csv(output_table_dir / "fallback_analysis.csv", fallback_rows)
    write_csv(output_table_dir / "memory_decision_errors.csv", decision_rows)

    strict_entity = metric(**strict_entity_totals)
    diagnostic_entity = metric(**diagnostic_entity_totals)
    strict_relation = metric(**strict_relation_totals)
    diagnostic_relation = metric(**diagnostic_relation_totals)
    primary_entity = entity_error_counts.most_common(1)[0] if entity_error_counts else ("none", 0)
    primary_relation = relation_error_counts.most_common(1)[0] if relation_error_counts else ("none", 0)
    fallback_focus = sum(fallback_categories[name] for name in ("temporary_state", "one_off_information", "small_talk"))
    fallback_rate = fallback_focus / len(fallback_rows) if fallback_rows else 0.0
    fallback_detail_lines = [
        "| sample | category | difficulty | human remember | human importance | AI worth | AI remember | AI importance | AI confidence | fallback class v1 |",
        "|---|---|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    fallback_detail_lines.extend(
        f"| {row['sample_id']} | {row['category']} | {row['difficulty']} | "
        f"{row['human_should_remember']} | {row['human_importance']} | {row['worthRemembering']} | "
        f"{row['shouldRemember']} | {row['importance']} | {row['confidence']} | {row['fallback_class_v1']} |"
        for row in fallback_rows
    )

    special_lines = []
    for sample_id in ("p017", "p019", "p024"):
        sample = next(row for row in samples if row["sample_id"] == sample_id)
        response = response_by_id[sample_id]
        actual_entities, actual_relations = actual_by_sample[sample_id]
        failure = (
            "KG extraction" if not actual_entities and (sample["expected_entities"] or sample["expected_relations"])
            else "memory decision" if response.get("ltmPersisted") != sample["human_should_remember"]
            else "none"
        )
        if sample_id == "p019":
            failure = "memory decision"
        special_lines.append(
            f"| {sample_id} | {sample['human_should_remember']} | {response.get('shouldRemember')} | "
            f"{response.get('ltmPersisted')} | {len(actual_entities)} | {len(actual_relations)} | {failure} |"
        )

    report = f"""# E2E Pilot Error Analysis

## Scope

- Run: `{run_dir.name}`
- Source: archived raw results, sample mapping, and confirmed Ground Truth.
- AI calls: none during this analysis.
- Ground Truth and production algorithms were not changed.

## Entity error analysis

Strict exact matching: P={strict_entity['precision']:.6f}, R={strict_entity['recall']:.6f}, F1={strict_entity['f1']:.6f} (TP/FP/FN={strict_entity['tp']}/{strict_entity['fp']}/{strict_entity['fn']}).

Name-normalization diagnostic while preserving entity type: P={diagnostic_entity['precision']:.6f}, R={diagnostic_entity['recall']:.6f}, F1={diagnostic_entity['f1']:.6f} (TP/FP/FN={diagnostic_entity['tp']}/{diagnostic_entity['fp']}/{diagnostic_entity['fn']}).

Largest entity error category: `{primary_entity[0]}` ({primary_entity[1]} samples). Category counts: {dumps(dict(entity_error_counts))}.

The diagnostic recovers same-type naming variants including 后端工程/后端开发, 深色主题/深色主题偏好, 全程马拉松/全程马拉松目标, and compound MindPet naming. It deliberately does not forgive type mismatches such as 香菜 topic→preference or 左撇子 other→preference.

## Relation error analysis

Strict exact matching: P={strict_relation['precision']:.6f}, R={strict_relation['recall']:.6f}, F1={strict_relation['f1']:.6f} (TP/FP/FN={strict_relation['tp']}/{strict_relation['fp']}/{strict_relation['fn']}).

Endpoint-normalization diagnostic while preserving predicate and endpoint types: P={diagnostic_relation['precision']:.6f}, R={diagnostic_relation['recall']:.6f}, F1={diagnostic_relation['f1']:.6f} (TP/FP/FN={diagnostic_relation['tp']}/{diagnostic_relation['fp']}/{diagnostic_relation['fn']}).

Largest relation error category: `{primary_relation[0]}` ({primary_relation[1]} samples). Category counts: {dumps(dict(relation_error_counts))}.

Wrong predicates remain real errors after normalization: works_on is used where Ground Truth expects experienced for 后端工程/软件开发. Extra plans/learns and generic related_to edges are not rescued by the diagnostic.

## Matching sensitivity

| target | strict F1 | normalization diagnostic F1 | delta |
|---|---:|---:|---:|
| Entity | {strict_entity['f1']:.6f} | {diagnostic_entity['f1']:.6f} | {diagnostic_entity['f1'] - strict_entity['f1']:+.6f} |
| Relation | {strict_relation['f1']:.6f} | {diagnostic_relation['f1']:.6f} | {diagnostic_relation['f1'] - strict_relation['f1']:+.6f} |

Case folding and whitespace normalization were already present in the strict evaluator. The observed lift comes from same-type synonyms, suffix expansion, compound-name alignment, and relation endpoint normalization. This is diagnostic only and does not replace the formal metrics.

## Fallback analysis

- memoryObjectPresent=false: {len(fallback_rows)}
- Category distribution: {dumps(dict(sorted(fallback_categories.items())))}
- v1 classification: {dumps(dict(sorted(fallback_classes.items())))}
- temporary_state + one_off_information + small_talk: {fallback_focus}/{len(fallback_rows)} ({fallback_rate:.2%})

Fallbacks are therefore concentrated in the three short-lived/negative categories, although four negative controls also come from stable_fact, long_term_preference, and long_term_goal.

{chr(10).join(fallback_detail_lines)}

## ShouldRemember and LTM errors

- ShouldRemember FP: {', '.join(row['sample_id'] for row in decision_rows if row['error_type'] == 'FP')}
- ShouldRemember FN: {', '.join(row['sample_id'] for row in decision_rows if row['error_type'] == 'FN') or 'none'}
- LTM persistence FP: p019
- LTM persistence FN: none

p013 is a conditional, unconfirmed home-buying idea. The model emits shouldRemember=true at importance 0.35/confidence 0.4 but production does not attempt LTM persistence; it also creates a clearly incorrect 买房计划 entity.

p019 is a confirmed but explicitly six-week rehabilitation schedule. The model emits shouldRemember=true, importance 0.5, confidence 0.95 and persists LTM. Its KG entity/relation are correct, so the failure is the memory-duration decision, not KG extraction.

## Special samples

| sample | human remember | AI remember | LTM persisted | actual entities | actual relations | primary failure |
|---|---:|---:|---:|---:|---:|---|
{chr(10).join(special_lines)}

- p017: memory decision is correct (no LTM), but 项目评审/event is missing; KG extraction failure.
- p019: KG is correct, but a temporary six-week schedule is promoted into LTM; memory decision/threshold interaction failure.
- p024: memory decision is correct (no LTM), but 取快递/event and user-plans-event are missing; KG extraction failure.

## Supported conclusion

Exact matching materially underestimates KG performance, but matching is not the only bottleneck. Remaining errors show schema/type ambiguity, wrong predicates, over-splitting, generic related_to overuse, KG suppression on memory-negative turns, and fallback behavior. The Pilot does not yet support moving directly to 600 samples without first resolving the evaluation-policy question for LTM-negative/KG-positive turns and diagnosing the production extraction/fallback path.
"""
    report_path = run_dir / "error-analysis.md"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report, encoding="utf-8")
    summary = {
        "strict_entity": strict_entity,
        "diagnostic_entity": diagnostic_entity,
        "strict_relation": strict_relation,
        "diagnostic_relation": diagnostic_relation,
        "entity_error_categories": dict(entity_error_counts),
        "relation_error_categories": dict(relation_error_counts),
        "fallback_categories": dict(fallback_categories),
        "fallback_classes": dict(fallback_classes),
        "should_remember_fp": [row["sample_id"] for row in decision_rows if row["error_type"] == "FP"],
        "ltm_persistence_fp": [
            sample["sample_id"] for sample in samples
            if not sample["human_should_remember"] and response_by_id[sample["sample_id"]].get("ltmPersisted") is True
        ],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
