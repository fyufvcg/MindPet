"""Calculate and report the formal H3-A importance accuracy metrics."""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path
from statistics import mean
from typing import Any


EXPECTED_COUNT = 100


class EvaluationFailure(RuntimeError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise EvaluationFailure(f"{path}: expected a JSON object")
    return value


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    rows = [json.loads(line) for line in lines if line.strip()]
    if len(rows) != len(lines) or len(rows) != EXPECTED_COUNT:
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


def optional_spearman(left: list[float], right: list[float]) -> float | None:
    if len(left) < 3 or len(set(left)) < 2 or len(set(right)) < 2:
        return None
    return pearson(average_ranks(left), average_ranks(right))


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
        "recall": recall, "f1": f1, "tp": tp, "fp": fp, "fn": fn, "tn": tn,
    }


def validate(rows: list[dict[str, Any]], manifest: dict[str, Any]) -> None:
    expected_ids = [f"i{index:03d}" for index in range(1, EXPECTED_COUNT + 1)]
    if [row.get("sample_id") for row in rows] != expected_ids:
        raise EvaluationFailure("sample IDs are incomplete, duplicated, or out of order")
    required_manifest = {
        "experiment": "importance_h3a", "samples": EXPECTED_COUNT,
        "formal_requests": EXPECTED_COUNT, "success_count": EXPECTED_COUNT,
        "failure_count": 0, "db_write": False, "ground_truth": "confirmed",
    }
    for field, expected in required_manifest.items():
        if manifest.get(field) != expected:
            raise EvaluationFailure(f"manifest {field} is not {expected!r}")
    if not manifest.get("production_model") or not manifest.get("prompt_sha256"):
        raise EvaluationFailure("manifest model or prompt hash is missing")
    if len(str(manifest["prompt_sha256"])) != 64:
        raise EvaluationFailure("manifest prompt hash is invalid")
    for row in rows:
        sample_id = row["sample_id"]
        for field in ("human_importance", "ai_importance", "ai_confidence"):
            value = row.get(field)
            if not isinstance(value, (int, float)) or not 0.0 <= value <= 1.0:
                raise EvaluationFailure(f"{sample_id}: invalid {field}")
        for field in (
            "human_should_remember", "human_high_importance", "ai_should_remember",
            "ai_high_importance", "ai_would_persist", "worthRemembering",
            "parse_success", "memory_object_present", "importance_fallback_used",
            "confidence_fallback_used", "importance_clamped", "confidence_clamped",
        ):
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
        if row.get("model") != manifest["production_model"]:
            raise EvaluationFailure(f"{sample_id}: production model changed")


def numeric_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    human = [float(row["human_importance"]) for row in rows]
    ai = [float(row["ai_importance"]) for row in rows]
    errors = [predicted - actual for actual, predicted in zip(human, ai)]
    return {
        "samples": len(rows),
        "mae": mean(abs(error) for error in errors),
        "rmse": math.sqrt(mean(error * error for error in errors)),
        "spearman": pearson(average_ranks(human), average_ranks(ai)),
        "pearson": pearson(human, ai),
        "mean_human_importance": mean(human),
        "mean_ai_importance": mean(ai),
        "mean_signed_error": mean(errors),
    }


def group_rows(rows: list[dict[str, Any]], field: str) -> list[dict[str, Any]]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[str(row[field])].append(row)
    output = []
    for key in sorted(groups):
        items = groups[key]
        human = [float(row["human_importance"]) for row in items]
        ai = [float(row["ai_importance"]) for row in items]
        errors = [predicted - actual for actual, predicted in zip(human, ai)]
        row = {
            field: key,
            "count": len(items),
            "human_importance_mean": mean(human),
            "ai_importance_mean": mean(ai),
            "mae": mean(abs(error) for error in errors),
            "mean_signed_error": mean(errors),
        }
        if field == "difficulty":
            row["rmse"] = math.sqrt(mean(error * error for error in errors))
            row["spearman"] = optional_spearman(human, ai)
        output.append(row)
    return output


def error_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    output = []
    ordered = sorted(
        rows,
        key=lambda row: (-abs(row["ai_importance"] - row["human_importance"]), row["sample_id"]),
    )[:20]
    for row in ordered:
        signed_error = row["ai_importance"] - row["human_importance"]
        output.append({
            "sample_id": row["sample_id"],
            "user_message": row["user_message"],
            "category": row["category"],
            "difficulty": row["difficulty"],
            "human_importance": row["human_importance"],
            "ai_importance": row["ai_importance"],
            "absolute_error": abs(signed_error),
            "signed_error": signed_error,
            "human_should_remember": row["human_should_remember"],
            "ai_should_remember": row["ai_should_remember"],
            "human_high_importance": row["human_high_importance"],
            "ai_high_importance": row["ai_high_importance"],
            "ai_confidence": row["ai_confidence"],
            "annotation_reason": row["annotation_reason"],
            "false_high": row["ai_high_importance"] and not row["human_high_importance"],
            "false_low": not row["ai_high_importance"] and row["human_high_importance"],
            "shouldRemember_FP": row["ai_should_remember"] and not row["human_should_remember"],
            "shouldRemember_FN": not row["ai_should_remember"] and row["human_should_remember"],
        })
    return output


def diagnostics(rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    fields = (
        "parse_success", "memory_object_present", "importance_fallback_used",
        "confidence_fallback_used", "importance_clamped", "confidence_clamped",
    )
    output = {}
    for field in fields:
        if field in ("parse_success", "memory_object_present"):
            ids = [row["sample_id"] for row in rows if not row[field]]
        else:
            ids = [row["sample_id"] for row in rows if row[field]]
        output[field] = {"count": len(ids), "sample_ids": ids}
    return output


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        raise EvaluationFailure(f"refusing to write empty table: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        with temporary.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def fmt(value: float | int | None) -> str:
    if value is None:
        return "N/A"
    if isinstance(value, int):
        return str(value)
    return f"{value:.6f}"


def classification_table(name: str, threshold: str, metrics: dict[str, Any]) -> dict[str, Any]:
    return {"metric": name, "threshold": threshold, **metrics}


def render_report(
    manifest: dict[str, Any], metrics: dict[str, Any], threshold_035: dict[str, Any],
    threshold_060: dict[str, Any], should_remember: dict[str, Any],
    would_persist: dict[str, Any], categories: list[dict[str, Any]],
    difficulties: list[dict[str, Any]], top20: list[dict[str, Any]],
    fallback: dict[str, dict[str, Any]], rows: list[dict[str, Any]],
) -> str:
    false_high = threshold_060["fp"]
    false_low = threshold_060["fn"]
    category_lines = "\n".join(
        f"| {row['category']} | {row['count']} | {fmt(row['human_importance_mean'])} | "
        f"{fmt(row['ai_importance_mean'])} | {fmt(row['mae'])} | {fmt(row['mean_signed_error'])} |"
        for row in categories
    )
    difficulty_lines = "\n".join(
        f"| {row['difficulty']} | {row['count']} | {fmt(row['mae'])} | {fmt(row['rmse'])} | "
        f"{fmt(row['spearman'])} | {fmt(row['human_importance_mean'])} | {fmt(row['ai_importance_mean'])} |"
        for row in difficulties
    )
    top_lines = "\n".join(
        f"| {row['sample_id']} | {row['category']} | {row['difficulty']} | "
        f"{fmt(row['human_importance'])} | {fmt(row['ai_importance'])} | "
        f"{fmt(row['absolute_error'])} | {fmt(row['signed_error'])} |"
        for row in top20
    )
    diag_lines = []
    labels = {
        "parse_success": "parse failures", "memory_object_present": "memory object missing",
        "importance_fallback_used": "importance fallback", "confidence_fallback_used": "confidence fallback",
        "importance_clamped": "importance clamp", "confidence_clamped": "confidence clamp",
    }
    for field, label in labels.items():
        item = fallback[field]
        ids = ", ".join(item["sample_ids"]) if item["sample_ids"] else "none"
        diag_lines.append(f"- {label}: {item['count']} ({ids})")
    high = sorted(categories, key=lambda row: row["mean_signed_error"], reverse=True)
    low = sorted(categories, key=lambda row: row["mean_signed_error"])
    fallback_rows = [row for row in rows if row["importance_fallback_used"]]
    direct_rows = [row for row in rows if not row["importance_fallback_used"]]
    fallback_mae = mean(abs(row["ai_importance"] - row["human_importance"])
                        for row in fallback_rows)
    direct_mae = mean(abs(row["ai_importance"] - row["human_importance"])
                      for row in direct_rows)
    direct_human = [float(row["human_importance"]) for row in direct_rows]
    direct_ai = [float(row["ai_importance"]) for row in direct_rows]
    direct_spearman = optional_spearman(direct_human, direct_ai)
    direct_pearson = pearson(direct_human, direct_ai)
    return f"""# H3-A Importance Accuracy Experiment

## 1. Run integrity

- Git commit: `{manifest['git_commit']}`
- Dataset: {manifest['dataset_version']} (`{manifest['dataset_sha256']}`)
- Production model: `{manifest['production_model']}`
- Production prompt SHA-256: `{manifest['prompt_sha256']}`
- Formal requests / success / failure: {manifest['formal_requests']} / {manifest['success_count']} / {manifest['failure_count']}
- Database writes: false
- Ground Truth: 100 confirmed samples, produced by one human annotator.
- No inter-annotator agreement was available.

The Evaluation-only endpoint calls the production extraction prompt, model selection, parser, fallback, clamp and persistence-decision logic, but does not call `persist()`, `appendTurn()`, the knowledge-graph write path, or the curator.

## 2. Importance regression/rank metrics

| samples | MAE | RMSE | Spearman | Pearson | mean human | mean AI | mean signed error |
|---:|---:|---:|---:|---:|---:|---:|---:|
| {metrics['samples']} | {fmt(metrics['mae'])} | {fmt(metrics['rmse'])} | {fmt(metrics['spearman'])} | {fmt(metrics['pearson'])} | {fmt(metrics['mean_human_importance'])} | {fmt(metrics['mean_ai_importance'])} | {fmt(metrics['mean_signed_error'])} |

## 3. Threshold and boolean metrics

| target | accuracy | precision | recall | F1 | TP | FP | FN | TN |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| importance >= 0.35 | {fmt(threshold_035['accuracy'])} | {fmt(threshold_035['precision'])} | {fmt(threshold_035['recall'])} | {fmt(threshold_035['f1'])} | {threshold_035['tp']} | {threshold_035['fp']} | {threshold_035['fn']} | {threshold_035['tn']} |
| importance >= 0.6 | {fmt(threshold_060['accuracy'])} | {fmt(threshold_060['precision'])} | {fmt(threshold_060['recall'])} | {fmt(threshold_060['f1'])} | {threshold_060['tp']} | {threshold_060['fp']} | {threshold_060['fn']} | {threshold_060['tn']} |
| shouldRemember | {fmt(should_remember['accuracy'])} | {fmt(should_remember['precision'])} | {fmt(should_remember['recall'])} | {fmt(should_remember['f1'])} | {should_remember['tp']} | {should_remember['fp']} | {should_remember['fn']} | {should_remember['tn']} |
| wouldPersistMemory (diagnostic) | {fmt(would_persist['accuracy'])} | {fmt(would_persist['precision'])} | {fmt(would_persist['recall'])} | {fmt(would_persist['f1'])} | {would_persist['tp']} | {would_persist['fp']} | {would_persist['fn']} | {would_persist['tn']} |

False High (AI >= 0.6, Human < 0.6): **{false_high}**.
False Low (AI < 0.6, Human >= 0.6): **{false_low}**.

The 0.35 result evaluates the importance threshold alone. Production persistence also depends on `shouldRemember` and confidence, so it is not complete persistence accuracy. Likewise, `human_should_remember` is not a manual reproduction of every Java persistence condition; `wouldPersistMemory` is auxiliary diagnosis only.

## 4. Category slices

| category | count | human mean | AI mean | MAE | signed error (AI-Human) |
|---|---:|---:|---:|---:|---:|
{category_lines}

Largest positive mean signed errors: {', '.join(row['category'] for row in high[:3])}.
Largest negative mean signed errors: {', '.join(row['category'] for row in low[:3])}.

## 5. Difficulty slices

| difficulty | count | MAE | RMSE | Spearman | human mean | AI mean |
|---|---:|---:|---:|---:|---:|---:|
{difficulty_lines}

## 6. Top-20 absolute errors

| sample | category | difficulty | human | AI | absolute error | signed error |
|---|---|---|---:|---:|---:|---:|
{top_lines}

The CSV includes the user message, annotation reason, confidence, threshold flags, and shouldRemember FP/FN flags for every Top-20 case.

## 7. Parse, fallback and clamp diagnostics

{chr(10).join(diag_lines)}

## 8. Interpretation boundaries

- This benchmark measures the current production scorer without prompt, model, parser, fallback, clamp, threshold or retrieval changes.
- Ground Truth was produced by one human annotator; no inter-annotator agreement was available.
- The dataset has 100 curated samples and can reveal benchmark-specific failure patterns, but it does not establish population-wide calibration.
- Confidence is reported as model output; no independent human confidence label exists in this benchmark.

## 9. Result interpretation

- The current production importance-output pipeline shows a material reliability problem on this benchmark: MAE is {fmt(metrics['mae'])}, Pearson is {fmt(metrics['pearson'])}, and the 0.35 threshold produces {threshold_035['fp']} false positives with precision {fmt(threshold_035['precision'])}.
- The strongest issue is the missing-memory fallback path. {len(fallback_rows)}/100 samples use the 0.5 importance fallback; their MAE is {fmt(fallback_mae)}. These are not direct LLM importance values.
- Among the {len(direct_rows)} samples with a direct memory importance, MAE is {fmt(direct_mae)}, Spearman is {fmt(direct_spearman)}, and Pearson is {fmt(direct_pearson)}. The high-importance threshold still has 5 False High and 5 False Low cases overall.
- Therefore the data supports a reliability concern for the **current production importance output as a whole**, especially fallback/calibration and low-value categories. It does not by itself justify the stronger blanket claim that every directly generated LLM importance score is unreliable.
"""


def self_test() -> None:
    assert average_ranks([1.0, 2.0, 2.0, 4.0]) == [1.0, 2.5, 2.5, 4.0]
    assert math.isclose(pearson([1, 2, 3], [2, 4, 6]), 1.0)
    result = classification([True, True, False, False], [True, False, True, False])
    assert result == {
        "accuracy": 0.5, "precision": 0.5, "recall": 0.5, "f1": 0.5,
        "tp": 1, "fp": 1, "fn": 1, "tn": 1,
    }


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path,
                        default=root / "results/importance_h3a/raw/importance_predictions.jsonl")
    parser.add_argument("--manifest", type=Path,
                        default=root / "results/importance_h3a/raw/run_manifest.json")
    parser.add_argument("--tables", type=Path,
                        default=root / "results/importance_h3a/tables")
    parser.add_argument("--report", type=Path,
                        default=root / "reports/importance-accuracy-h3a.md")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    self_test()
    if args.self_test:
        print("importance metric self-test passed")
        return

    rows = read_jsonl(args.raw)
    manifest = read_json(args.manifest)
    validate(rows, manifest)
    metrics = numeric_metrics(rows)
    threshold_035 = classification(
        [row["human_importance"] >= 0.35 for row in rows],
        [row["ai_importance"] >= 0.35 for row in rows],
    )
    threshold_060 = classification(
        [row["human_importance"] >= 0.6 for row in rows],
        [row["ai_importance"] >= 0.6 for row in rows],
    )
    should_remember = classification(
        [row["human_should_remember"] for row in rows],
        [row["ai_should_remember"] for row in rows],
    )
    would_persist = classification(
        [row["human_should_remember"] for row in rows],
        [row["ai_would_persist"] for row in rows],
    )
    categories = group_rows(rows, "category")
    difficulties = group_rows(rows, "difficulty")
    top20 = error_rows(rows)
    fallback = diagnostics(rows)

    write_csv(args.tables / "importance_metrics.csv", [metrics])
    write_csv(args.tables / "threshold_035_metrics.csv", [
        classification_table("importance", ">=0.35", threshold_035)])
    write_csv(args.tables / "threshold_060_metrics.csv", [
        classification_table("high_importance", ">=0.6", threshold_060)])
    write_csv(args.tables / "should_remember_metrics.csv", [
        classification_table("shouldRemember", "boolean", should_remember)])
    write_csv(args.tables / "would_persist_metrics.csv", [
        classification_table("wouldPersistMemory", "boolean", would_persist)])
    write_csv(args.tables / "importance_metrics_by_category.csv", categories)
    write_csv(args.tables / "importance_metrics_by_difficulty.csv", difficulties)
    write_csv(args.tables / "top20_importance_errors.csv", top20)

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        render_report(
            manifest, metrics, threshold_035, threshold_060, should_remember,
            would_persist, categories, difficulties, top20, fallback, rows,
        ),
        encoding="utf-8",
    )
    print(json.dumps({
        "importance": metrics,
        "threshold_035": threshold_035,
        "threshold_060": threshold_060,
        "should_remember": should_remember,
        "would_persist": would_persist,
        "fallback": fallback,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
