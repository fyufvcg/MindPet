"""Calculate H3-A importance accuracy metrics from human-confirmed labels and AI scores."""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path
from statistics import mean
from typing import Any


class EvaluationFailure(RuntimeError):
    pass


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    rows = [json.loads(line) for line in lines if line.strip()]
    if len(rows) != len(lines) or len(rows) != 100:
        raise EvaluationFailure("raw input must contain exactly 100 JSON objects")
    return rows


def average_ranks(values: list[float]) -> list[float]:
    order = sorted(range(len(values)), key=values.__getitem__)
    ranks = [0.0] * len(values)
    start = 0
    while start < len(order):
        end = start + 1
        while end < len(order) and values[order[end]] == values[order[start]]:
            end += 1
        rank = (start + 1 + end) / 2.0
        for position in range(start, end):
            ranks[order[position]] = rank
        start = end
    return ranks


def pearson(left: list[float], right: list[float]) -> float:
    if len(left) != len(right) or len(left) < 2:
        raise EvaluationFailure("correlation requires paired values")
    left_mean = mean(left)
    right_mean = mean(right)
    numerator = sum((x - left_mean) * (y - right_mean) for x, y in zip(left, right))
    denominator = math.sqrt(
        sum((x - left_mean) ** 2 for x in left)
        * sum((y - right_mean) ** 2 for y in right)
    )
    if denominator == 0:
        raise EvaluationFailure("correlation is undefined for a constant vector")
    return numerator / denominator


def classification(actual: list[bool], predicted: list[bool]) -> dict[str, float | int]:
    if len(actual) != len(predicted) or not actual:
        raise EvaluationFailure("classification inputs must be paired and non-empty")
    tp = sum(a and p for a, p in zip(actual, predicted))
    fp = sum(not a and p for a, p in zip(actual, predicted))
    tn = sum(not a and not p for a, p in zip(actual, predicted))
    fn = sum(a and not p for a, p in zip(actual, predicted))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "accuracy": (tp + tn) / len(actual), "precision": precision,
        "recall": recall, "f1": f1, "tp": tp, "fp": fp, "tn": tn, "fn": fn,
    }


def validate(rows: list[dict[str, Any]]) -> None:
    expected_ids = {f"i{index:03d}" for index in range(1, 101)}
    if {row.get("sample_id") for row in rows} != expected_ids:
        raise EvaluationFailure("sample IDs are incomplete or duplicated")
    for row in rows:
        sample_id = row["sample_id"]
        for field in ("human_importance", "ai_importance", "ai_confidence"):
            value = row.get(field)
            if not isinstance(value, (int, float)) or not 0.0 <= value <= 1.0:
                raise EvaluationFailure(f"{sample_id}: invalid {field}")
        for field in ("human_should_remember", "human_high_importance",
                      "ai_should_remember", "ai_high_importance"):
            if not isinstance(row.get(field), bool):
                raise EvaluationFailure(f"{sample_id}: invalid {field}")
        if row["human_high_importance"] != (row["human_importance"] >= 0.6):
            raise EvaluationFailure(f"{sample_id}: inconsistent human high label")
        if row["ai_high_importance"] != (row["ai_importance"] >= 0.6):
            raise EvaluationFailure(f"{sample_id}: inconsistent AI high label")
        if row.get("review_status") != "confirmed":
            raise EvaluationFailure(f"{sample_id}: ground truth is not confirmed")
        if row.get("http_status") != 200 or row.get("api_status") != "OK":
            raise EvaluationFailure(f"{sample_id}: unsuccessful API response")


def group_rows(rows: list[dict[str, Any]], field: str) -> list[dict[str, Any]]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[str(row[field])].append(row)
    output = []
    for key in sorted(groups):
        items = groups[key]
        output.append({
            field: key,
            "count": len(items),
            "mae": mean(abs(row["ai_importance"] - row["human_importance"])
                        for row in items),
            "mean_ai_importance": mean(row["ai_importance"] for row in items),
            "mean_human_importance": mean(row["human_importance"] for row in items),
            "mean_signed_error": mean(row["ai_importance"] - row["human_importance"]
                                      for row in items),
        })
    return output


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def evaluate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    validate(rows)
    human = [float(row["human_importance"]) for row in rows]
    ai = [float(row["ai_importance"]) for row in rows]
    errors = [predicted - actual for actual, predicted in zip(human, ai)]
    threshold_035 = classification(
        [value >= 0.35 for value in human], [value >= 0.35 for value in ai])
    threshold_06 = classification(
        [value >= 0.6 for value in human], [value >= 0.6 for value in ai])
    should_remember = classification(
        [row["human_should_remember"] for row in rows],
        [row["ai_should_remember"] for row in rows])
    return {
        "samples": len(rows),
        "mae": mean(abs(error) for error in errors),
        "rmse": math.sqrt(mean(error * error for error in errors)),
        "spearman": pearson(average_ranks(human), average_ranks(ai)),
        "pearson": pearson(human, ai),
        "importance_threshold_0_35": threshold_035,
        "high_importance_threshold_0_6": threshold_06,
        "should_remember": should_remember,
    }


def self_test() -> None:
    assert average_ranks([1.0, 2.0, 2.0, 4.0]) == [1.0, 2.5, 2.5, 4.0]
    assert math.isclose(pearson([1, 2, 3], [2, 4, 6]), 1.0)
    result = classification([True, True, False, False], [True, False, True, False])
    assert result == {
        "accuracy": 0.5, "precision": 0.5, "recall": 0.5, "f1": 0.5,
        "tp": 1, "fp": 1, "tn": 1, "fn": 1,
    }


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path,
                        default=root / "results/importance_h3a/raw/importance_scores.jsonl")
    parser.add_argument("--tables", type=Path,
                        default=root / "results/importance_h3a/tables")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    self_test()
    if args.self_test:
        print("importance metric self-test passed")
        return
    rows = read_jsonl(args.raw)
    metrics = evaluate(rows)
    args.tables.mkdir(parents=True, exist_ok=True)
    (args.tables / "importance_metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_csv(args.tables / "metrics_by_category.csv", group_rows(rows, "category"))
    write_csv(args.tables / "metrics_by_difficulty.csv", group_rows(rows, "difficulty"))
    error_rows = []
    for row in sorted(rows,
                      key=lambda item: abs(item["ai_importance"] - item["human_importance"]),
                      reverse=True)[:20]:
        error_rows.append({
            "sample_id": row["sample_id"], "message": row["user_message"],
            "category": row["category"], "human_importance": row["human_importance"],
            "ai_importance": row["ai_importance"],
            "absolute_error": abs(row["ai_importance"] - row["human_importance"]),
            "human_should_remember": row["human_should_remember"],
            "ai_should_remember": row["ai_should_remember"],
            "human_high_importance": row["human_high_importance"],
            "ai_high_importance": row["ai_high_importance"],
            "confidence": row["ai_confidence"],
            "error_type": (
                "false_high_importance" if row["ai_high_importance"]
                and not row["human_high_importance"] else
                "false_low_importance" if not row["ai_high_importance"]
                and row["human_high_importance"] else "score_error"
            ),
        })
    write_csv(args.tables / "top20_absolute_errors.csv", error_rows)
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
