"""Strict offline validation for the MindPet retrieval benchmark JSONL files."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any


EXPECTED_QUERY_TYPES = {
    "exact_keyword": 8,
    "semantic_paraphrase": 10,
    "hybrid": 8,
    "multi_candidate": 6,
    "temporal_importance": 4,
    "no_answer": 4,
}
EXPECTED_CATEGORIES = {
    "sport", "food", "study", "work", "software_tools", "schedule_time",
    "relationships", "places", "devices_environment", "projects",
    "personal_facts", "entertainment_hobbies",
}
ALLOWED_EMOTIONS = {"positive", "neutral", "negative"}
ALLOWED_DIFFICULTIES = {"easy", "medium", "hard"}
NO_ANSWER_ABSENT_TERMS = {"乐器", "宠物", "汽车品牌", "滑雪场"}


class ValidationError(Exception):
    """Raised when one or more benchmark invariants are violated."""


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as exc:
        raise ValidationError(f"{path} is not valid UTF-8: {exc}") from exc
    except OSError as exc:
        raise ValidationError(f"cannot read {path}: {exc}") from exc

    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            raise ValidationError(f"{path}:{line_number}: blank JSONL line")
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValidationError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
        if not isinstance(record, dict):
            raise ValidationError(f"{path}:{line_number}: record must be an object")
        records.append(record)
    return records


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def is_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def validate_memories(memories: list[dict[str, Any]], errors: list[str]) -> set[str]:
    require(len(memories) == 120, f"expected 120 memories, got {len(memories)}", errors)
    ids = [row.get("memory_id") for row in memories]
    require(len(ids) == len(set(ids)), "memory_id values must be unique", errors)
    expected_ids = [f"m{index:03d}" for index in range(1, 121)]
    require(ids == expected_ids, "memory_id values/order must be m001..m120", errors)

    categories: Counter[str] = Counter()
    all_content = "\n".join(str(row.get("content", "")) for row in memories)
    required = {
        "memory_id", "user_id", "content", "importance", "confidence",
        "layer", "emotion", "created_at_offset_hours", "category", "tags",
    }
    for index, row in enumerate(memories, start=1):
        label = f"memory line {index}"
        require(required <= row.keys(), f"{label}: missing fields {sorted(required - row.keys())}", errors)
        require(row.get("user_id") == "eval_test_user", f"{label}: invalid user_id", errors)
        require(isinstance(row.get("content"), str) and bool(row["content"].strip()), f"{label}: empty content", errors)
        importance = row.get("importance")
        confidence = row.get("confidence")
        offset = row.get("created_at_offset_hours")
        require(is_number(importance) and 0.0 <= importance <= 1.0, f"{label}: importance outside [0,1]", errors)
        require(is_number(confidence) and 0.0 <= confidence <= 1.0, f"{label}: confidence outside [0,1]", errors)
        require(row.get("layer") in {1, 2, 3}, f"{label}: layer must be 1, 2, or 3", errors)
        require(row.get("emotion") in ALLOWED_EMOTIONS, f"{label}: invalid emotion", errors)
        require(is_number(offset) and offset >= 0, f"{label}: offset must be non-negative", errors)
        category = row.get("category")
        require(category in EXPECTED_CATEGORIES, f"{label}: invalid category {category!r}", errors)
        if isinstance(category, str):
            categories[category] += 1
        tags = row.get("tags")
        require(
            isinstance(tags, list) and len(tags) >= 2
            and all(isinstance(tag, str) and tag.strip() for tag in tags),
            f"{label}: tags must contain at least two non-empty strings",
            errors,
        )

    require(set(categories) == EXPECTED_CATEGORIES, "memory category set is incomplete", errors)
    require(all(categories[category] == 10 for category in EXPECTED_CATEGORIES), f"each category must have 10 memories: {dict(categories)}", errors)
    for term in NO_ANSWER_ABSENT_TERMS:
        require(term not in all_content, f"no-answer topic unexpectedly occurs in memories: {term}", errors)
    return {item for item in ids if isinstance(item, str)}


def validate_queries(queries: list[dict[str, Any]], memory_ids: set[str], errors: list[str]) -> None:
    require(len(queries) == 40, f"expected 40 queries, got {len(queries)}", errors)
    ids = [row.get("query_id") for row in queries]
    require(len(ids) == len(set(ids)), "query_id values must be unique", errors)
    expected_ids = [f"q{index:03d}" for index in range(1, 41)]
    require(ids == expected_ids, "query_id values/order must be q001..q040", errors)

    type_counts: Counter[str] = Counter()
    multi_relevant_count = 0
    invalid_references: list[str] = []
    required = {"query_id", "query", "relevant_memory_ids", "query_type", "difficulty", "notes"}
    for index, row in enumerate(queries, start=1):
        label = f"query line {index}"
        require(required <= row.keys(), f"{label}: missing fields {sorted(required - row.keys())}", errors)
        require(isinstance(row.get("query"), str) and bool(row["query"].strip()), f"{label}: empty query", errors)
        query_type = row.get("query_type")
        require(query_type in EXPECTED_QUERY_TYPES, f"{label}: invalid query_type {query_type!r}", errors)
        if isinstance(query_type, str):
            type_counts[query_type] += 1
        require(row.get("difficulty") in ALLOWED_DIFFICULTIES, f"{label}: invalid difficulty", errors)
        require(isinstance(row.get("notes"), str) and bool(row["notes"].strip()), f"{label}: empty notes", errors)

        refs = row.get("relevant_memory_ids")
        require(isinstance(refs, list), f"{label}: relevant_memory_ids must be a list", errors)
        if not isinstance(refs, list):
            continue
        require(len(refs) == len(set(refs)), f"{label}: duplicate relevant memory id", errors)
        if query_type == "no_answer":
            require(len(refs) == 0, f"{label}: no_answer must have no relevant ids", errors)
        else:
            require(len(refs) > 0, f"{label}: non-no_answer query needs relevant ids", errors)
        if len(refs) > 1:
            multi_relevant_count += 1
        for ref in refs:
            if not isinstance(ref, str) or not re.fullmatch(r"m\d{3}", ref) or ref not in memory_ids:
                invalid_references.append(f"{row.get('query_id')}->{ref!r}")

    require(dict(type_counts) == EXPECTED_QUERY_TYPES, f"wrong query type distribution: {dict(type_counts)}", errors)
    require(multi_relevant_count >= 4, f"expected at least 4 multi-relevant queries, got {multi_relevant_count}", errors)
    require(not invalid_references, f"invalid memory references: {invalid_references}", errors)


def main() -> None:
    default_dir = Path(__file__).resolve().parents[1] / "datasets" / "retrieval"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", type=Path, default=default_dir)
    args = parser.parse_args()

    try:
        memories = read_jsonl(args.dataset_dir / "memories.jsonl")
        queries = read_jsonl(args.dataset_dir / "queries.jsonl")
        errors: list[str] = []
        memory_ids = validate_memories(memories, errors)
        validate_queries(queries, memory_ids, errors)
        if errors:
            raise ValidationError("\n".join(f"- {error}" for error in errors))
    except ValidationError as exc:
        parser.exit(1, f"VALIDATION FAILED\n{exc}\n")

    category_counts = Counter(row["category"] for row in memories)
    query_type_counts = Counter(row["query_type"] for row in queries)
    multi_relevant = sum(len(row["relevant_memory_ids"]) > 1 for row in queries)
    print("VALIDATION PASSED")
    print(f"memories={len(memories)} queries={len(queries)} invalid_references=0")
    print(f"categories={dict(sorted(category_counts.items()))}")
    print(f"query_types={dict(query_type_counts)}")
    print(f"multi_relevant_queries={multi_relevant}")


if __name__ == "__main__":
    main()
