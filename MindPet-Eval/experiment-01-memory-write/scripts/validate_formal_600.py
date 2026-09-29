"""Validate the structure and freeze readiness of an Experiment 1 Formal-600 JSONL file."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any


CATEGORIES = {
    "stable_fact", "long_term_preference", "long_term_goal",
    "temporary_state", "one_off_information", "small_talk",
}
ENTITY_TYPES = {
    "person", "project", "technology", "tool", "preference", "goal", "topic",
    "organization", "place", "event", "other",
}
PREDICATES = {
    "prefers", "dislikes", "uses", "learns", "builds", "works_on", "plans",
    "knows", "experienced", "belongs_to", "related_to",
}
IMPORTANCE_VALUES = {0.1, 0.3, 0.5, 0.7, 0.9}
REQUIRED = {
    "dataset_version", "sample_id", "user_message", "assistant_context", "category",
    "difficulty", "human_should_remember", "human_importance", "expected_entities",
    "expected_relations", "annotation_reason", "annotation_status",
}
SAFE_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{0,63}$")


def fail(sample_id: str, message: str) -> None:
    raise ValueError(f"{sample_id}: {message}")


def validate_row(row: dict[str, Any], expected_id: str, require_frozen: bool) -> None:
    sample_id = str(row.get("sample_id", expected_id))
    if not REQUIRED.issubset(row):
        fail(sample_id, f"missing fields: {sorted(REQUIRED - set(row))}")
    if row["dataset_version"] != "e1-formal-600-v1":
        fail(sample_id, "dataset_version must be e1-formal-600-v1")
    if sample_id != expected_id:
        fail(sample_id, f"expected ordered id {expected_id}")
    if not isinstance(row["user_message"], str) or not row["user_message"].strip():
        fail(sample_id, "user_message must be nonblank text")
    if not isinstance(row["assistant_context"], str):
        fail(sample_id, "assistant_context must be text")
    if row["category"] not in CATEGORIES:
        fail(sample_id, "unsupported category")
    if row["difficulty"] not in {"easy", "medium", "hard"}:
        fail(sample_id, "difficulty must be easy, medium, or hard")
    if not isinstance(row["human_should_remember"], bool):
        fail(sample_id, "human_should_remember must be boolean")
    if row["human_importance"] not in IMPORTANCE_VALUES:
        fail(sample_id, "human_importance is outside the frozen scale")
    if row["annotation_status"] not in {"draft", "reviewed", "confirmed"}:
        fail(sample_id, "unsupported annotation_status")
    if require_frozen and row["annotation_status"] != "confirmed":
        fail(sample_id, "frozen data requires annotation_status=confirmed")
    if not isinstance(row["annotation_reason"], str) or not row["annotation_reason"].strip():
        fail(sample_id, "annotation_reason must be nonblank")
    if not isinstance(row["expected_entities"], list) or not isinstance(row["expected_relations"], list):
        fail(sample_id, "expected_entities and expected_relations must be arrays")

    entity_keys: set[str] = set()
    for entity in row["expected_entities"]:
        if not isinstance(entity, dict) or not {"entity_key", "name", "type"}.issubset(entity):
            fail(sample_id, "invalid expected entity")
        key = entity["entity_key"]
        if not isinstance(key, str) or not SAFE_KEY.fullmatch(key) or key == "user" or key in entity_keys:
            fail(sample_id, "entity_key must be unique, safe, and not user")
        if not isinstance(entity["name"], str) or not entity["name"].strip():
            fail(sample_id, "entity name must be nonblank")
        if entity["type"] not in ENTITY_TYPES:
            fail(sample_id, "unsupported entity type")
        entity_keys.add(key)

    allowed_endpoints = entity_keys | {"user"}
    relation_keys: set[tuple[str, str, str]] = set()
    for relation in row["expected_relations"]:
        required = {"source_entity_key", "target_entity_key", "predicate"}
        if not isinstance(relation, dict) or not required.issubset(relation):
            fail(sample_id, "invalid expected relation")
        source, target = relation["source_entity_key"], relation["target_entity_key"]
        if source not in allowed_endpoints or target not in allowed_endpoints or source == target:
            fail(sample_id, "relation endpoint is missing or self-referential")
        if relation["predicate"] not in PREDICATES:
            fail(sample_id, "unsupported predicate")
        key = source, relation["predicate"], target
        if key in relation_keys:
            fail(sample_id, "duplicate expected relation")
        relation_keys.add(key)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--require-frozen", action="store_true")
    args = parser.parse_args()
    raw = args.dataset.read_bytes()
    lines = raw.decode("utf-8").splitlines()
    if len(lines) != 600 or any(not line.strip() for line in lines):
        raise ValueError("dataset must contain exactly 600 nonblank JSONL records")
    rows = [json.loads(line) for line in lines]
    if not all(isinstance(row, dict) for row in rows):
        raise ValueError("every JSONL record must be an object")
    for index, row in enumerate(rows, 1):
        validate_row(row, f"e1-{index:04d}", args.require_frozen)
    categories = Counter(row["category"] for row in rows)
    if set(categories) != CATEGORIES or any(categories[name] != 100 for name in CATEGORIES):
        raise ValueError(f"expected exactly 100 samples per category; got {dict(categories)}")
    messages = [row["user_message"].strip() for row in rows]
    if len(set(messages)) != len(messages):
        raise ValueError("duplicate user_message detected")
    print(json.dumps({
        "status": "VALID", "samples": len(rows), "categories": dict(sorted(categories.items())),
        "require_frozen": args.require_frozen, "dataset_sha256": hashlib.sha256(raw).hexdigest(),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
