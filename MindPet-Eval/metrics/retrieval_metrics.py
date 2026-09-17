"""Metric contracts only. These functions cannot yet produce experiment scores.

Future binary-relevance implementation must validate k, unique result ids, and
nonempty ground truth; include hand-calculated unit tests before real runs.
"""

from collections.abc import Collection, Sequence


def precision_at_k(
    retrieved: Sequence[str], relevant: Collection[str], k: int
) -> float:
    """Planned: number of relevant ids in retrieved[:k] divided by k."""
    raise NotImplementedError("Precision@K awaits implementation and unit tests")


def recall_at_k(
    retrieved: Sequence[str], relevant: Collection[str], k: int
) -> float:
    """Planned: hits in retrieved[:k] divided by number of unique relevant ids."""
    raise NotImplementedError("Recall@K awaits implementation and unit tests")


def reciprocal_rank(retrieved: Sequence[str], relevant: Collection[str]) -> float:
    """Planned: 1 / rank of first relevant result; zero when no hit is returned."""
    raise NotImplementedError("Reciprocal rank awaits implementation and unit tests")


def mean_reciprocal_rank(
    retrieved_lists: Sequence[Sequence[str]],
    relevant_sets: Sequence[Collection[str]],
) -> float:
    """Planned: mean reciprocal rank over a complete, successful query set."""
    raise NotImplementedError("MRR awaits implementation and unit tests")


def ndcg_at_k(
    retrieved: Sequence[str], relevant: Collection[str], k: int
) -> float:
    """Planned binary DCG / IDCG; discount at rank i is log2(i + 1)."""
    raise NotImplementedError("nDCG@K awaits implementation and unit tests")
