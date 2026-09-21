"""Deterministic binary-relevance metrics for MindPet retrieval experiments."""

from __future__ import annotations

import math
from collections.abc import Collection, Sequence


def _validate(
    retrieved: Sequence[str], relevant: Collection[str], k: int
) -> tuple[list[str], set[str]]:
    if not isinstance(k, int) or isinstance(k, bool) or k <= 0:
        raise ValueError("k must be a positive integer")
    ranked = list(retrieved)
    if len(ranked) != len(set(ranked)):
        raise ValueError("retrieved ids must be unique")
    relevant_ids = set(relevant)
    if not relevant_ids:
        raise ValueError("ranking metrics require non-empty ground truth")
    return ranked, relevant_ids


def precision_at_k(
    retrieved: Sequence[str], relevant: Collection[str], k: int
) -> float:
    """Relevant hits in the first k ranks divided by k, even if fewer are returned."""
    ranked, relevant_ids = _validate(retrieved, relevant, k)
    return sum(item in relevant_ids for item in ranked[:k]) / k


def recall_at_k(
    retrieved: Sequence[str], relevant: Collection[str], k: int
) -> float:
    """Relevant hits in the first k ranks divided by ground-truth count."""
    ranked, relevant_ids = _validate(retrieved, relevant, k)
    return sum(item in relevant_ids for item in ranked[:k]) / len(relevant_ids)


def reciprocal_rank(
    retrieved: Sequence[str], relevant: Collection[str], k: int = 10
) -> float:
    """1/rank of the first relevant result within k; zero when none is found."""
    ranked, relevant_ids = _validate(retrieved, relevant, k)
    for rank, item in enumerate(ranked[:k], start=1):
        if item in relevant_ids:
            return 1.0 / rank
    return 0.0


def mean_reciprocal_rank(
    retrieved_lists: Sequence[Sequence[str]],
    relevant_sets: Sequence[Collection[str]],
    k: int = 10,
) -> float:
    """Mean reciprocal rank over paired, non-empty query collections."""
    if len(retrieved_lists) != len(relevant_sets) or not retrieved_lists:
        raise ValueError("retrieved_lists and relevant_sets must be non-empty and equal length")
    return sum(
        reciprocal_rank(retrieved, relevant, k)
        for retrieved, relevant in zip(retrieved_lists, relevant_sets, strict=True)
    ) / len(retrieved_lists)


def ndcg_at_k(
    retrieved: Sequence[str], relevant: Collection[str], k: int
) -> float:
    """Binary DCG@k divided by ideal DCG for min(k, relevant_count) hits."""
    ranked, relevant_ids = _validate(retrieved, relevant, k)
    dcg = sum(
        (1.0 / math.log2(rank + 1)) if item in relevant_ids else 0.0
        for rank, item in enumerate(ranked[:k], start=1)
    )
    ideal_hits = min(k, len(relevant_ids))
    idcg = sum(1.0 / math.log2(rank + 1) for rank in range(1, ideal_hits + 1))
    return dcg / idcg


def self_test() -> None:
    """Small hand-calculated checks executed before metric aggregation."""
    ranked = ["m2", "m1", "m3"]
    relevant = {"m1", "m4"}
    assert precision_at_k(ranked, relevant, 1) == 0.0
    assert precision_at_k(ranked, relevant, 3) == 1.0 / 3.0
    assert recall_at_k(ranked, relevant, 3) == 0.5
    assert reciprocal_rank(ranked, relevant, 10) == 0.5
    assert math.isclose(ndcg_at_k(["m1"], {"m1"}, 1), 1.0)
