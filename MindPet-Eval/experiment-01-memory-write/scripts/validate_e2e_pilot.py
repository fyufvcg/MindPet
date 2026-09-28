"""Validate the 30-sample E2E pilot template or confirmed ground truth."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any


CATEGORIES = {
    "stable_fact", "long_term_preference", "long_term_goal",
    "temporary_state", "one_off_information", "small_talk",
}
ENTITY_TYPES = {
    "person", "project", "technology", "tool", "preference", "goal",
    "topic", "organization", "place", "event", "other",
}
PREDICATES = {
    "prefers", "dislikes", "uses", "learns", "builds", "works_on",
    "plans", "knows", "experienced", "belongs_to", "related_to",
}


def load(path: Path) -> list[dict[str, Any]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if any(not line.strip() for line in lines):
        raise ValueError("blank JSONL lines are not allowed")
    return [json.loads(line) for line in lines]


def validate(rows: list[dict[str, Any]], require_confirmed: bool) -> dict[str, Any]:
    expected_ids = [f"p{index:03d}" for index in range(1, 31)]
    if [row.get("sample_id") for row in rows] != expected_ids:
        raise ValueError("dataset must contain exactly p001..p030 in order")
    categories = Counter(row.get("category") for row in rows)
    if set(categories) != CATEGORIES or any(value != 5 for value in categories.values()):
        raise ValueError("each of the six categories must contain exactly five samples")

    required = {
        "sample_id", "user_message", "assistant_context", "category", "difficulty",
        "human_should_remember", "human_importance", "expected_entities",
        "expected_relations", "annotation_reason", "review_status",
    }
    for row in rows:
        sample_id = row["sample_id"]
        if not required.issubset(row):
            raise ValueError(f"{sample_id}: missing required fields")
        if not isinstance(row["user_message"], str) or not row["user_message"].strip():
            raise ValueError(f"{sample_id}: invalid user_message")
        if not isinstance(row["assistant_context"], str):
            raise ValueError(f"{sample_id}: invalid assistant_context")
        if row["difficulty"] not in {"easy", "medium", "hard"}:
            raise ValueError(f"{sample_id}: invalid difficulty")
        if not isinstance(row["expected_entities"], list) or len(row["expected_entities"]) > 8:
            raise ValueError(f"{sample_id}: expected_entities must be an array with at most 8 entries")
        if not isinstance(row["expected_relations"], list) or len(row["expected_relations"]) > 10:
            raise ValueError(f"{sample_id}: expected_relations must be an array with at most 10 entries")

        keys = set()
        for entity in row["expected_entities"]:
            if not isinstance(entity, dict) or entity.get("type") not in ENTITY_TYPES:
                raise ValueError(f"{sample_id}: invalid expected entity type")
            key = entity.get("entity_key")
            if not isinstance(key, str) or not key or key in keys:
                raise ValueError(f"{sample_id}: invalid or duplicate entity_key")
            keys.add(key)
        for relation in row["expected_relations"]:
            if not isinstance(relation, dict) or relation.get("predicate") not in PREDICATES:
                raise ValueError(f"{sample_id}: invalid expected relation predicate")
            source = relation.get("source_entity_key")
            target = relation.get("target_entity_key")
            if source != "user" and source not in keys:
                raise ValueError(f"{sample_id}: relation source does not reference an entity_key")
            if target != "user" and target not in keys:
                raise ValueError(f"{sample_id}: relation target does not reference an entity_key")

        if require_confirmed:
            if row["review_status"] != "confirmed":
                raise ValueError(f"{sample_id}: ground truth is not confirmed")
            if not isinstance(row["human_should_remember"], bool):
                raise ValueError(f"{sample_id}: human_should_remember is not boolean")
            if row["human_importance"] not in {0.1, 0.3, 0.5, 0.7, 0.9}:
                raise ValueError(f"{sample_id}: human_importance is invalid")
            if not isinstance(row["annotation_reason"], str) or not row["annotation_reason"].strip():
                raise ValueError(f"{sample_id}: annotation_reason is missing")
        elif (
            row["review_status"] != "pending_human_annotation"
            or row["human_should_remember"] is not None
            or row["human_importance"] is not None
            or row["expected_entities"] != []
            or row["expected_relations"] != []
            or row["annotation_reason"] is not None
        ):
            raise ValueError(f"{sample_id}: pending template contains pre-filled ground truth")

    return {
        "samples": len(rows),
        "sample_ids": "p001-p030",
        "categories": dict(sorted(categories.items())),
        "review_status": "confirmed" if require_confirmed else "pending_human_annotation",
        "valid": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--require-confirmed", action="store_true")
    args = parser.parse_args()
    print(json.dumps(validate(load(args.dataset), args.require_confirmed), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
