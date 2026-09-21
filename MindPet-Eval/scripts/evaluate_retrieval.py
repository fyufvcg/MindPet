"""Validate a complete raw ablation run and calculate reproducible CSV metrics."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
from statistics import mean
from typing import Any


EVAL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EVAL_ROOT))

from metrics.retrieval_metrics import (  # noqa: E402
    ndcg_at_k,
    precision_at_k,
    recall_at_k,
    reciprocal_rank,
    self_test,
)


MODES = ["keyword_only", "vector_only", "rrf", "mindpet_full"]
QUERY_TYPES = [
    "exact_keyword", "semantic_paraphrase", "hybrid", "multi_candidate",
    "temporal_importance",
]
DIFFICULTIES = ["easy", "medium", "hard"]
KS = [1, 3, 5, 10]
METRIC_COLUMNS = [
    *[f"Precision@{k}" for k in KS],
    *[f"Recall@{k}" for k in KS],
    "MRR@10",
    *[f"nDCG@{k}" for k in KS],
]


class EvaluationFailure(RuntimeError):
    """Raised when raw data cannot be published as a formal experiment."""


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EvaluationFailure(f"cannot read JSON {path}: {exc}") from exc


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
        records = [json.loads(line) for line in lines if line.strip()]
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EvaluationFailure(f"cannot read JSONL {path}: {exc}") from exc
    if len(records) != len(lines) or not all(isinstance(row, dict) for row in records):
        raise EvaluationFailure(f"invalid/blank JSONL record in {path}")
    return records


def write_csv(path: Path, fieldnames: list[str], rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        with temporary.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        temporary.write_text(text, encoding="utf-8", newline="\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def metric_row(records: list[dict[str, Any]]) -> dict[str, float]:
    if not records:
        raise EvaluationFailure("cannot aggregate an empty record slice")
    values: dict[str, list[float]] = defaultdict(list)
    for record in records:
        relevant = record["relevant_memory_ids"]
        if not relevant:
            raise EvaluationFailure("no_answer record reached core ranking metrics")
        retrieved = [result["benchmark_memory_id"] for result in record["results"]]
        for k in KS:
            values[f"Precision@{k}"].append(precision_at_k(retrieved, relevant, k))
            values[f"Recall@{k}"].append(recall_at_k(retrieved, relevant, k))
            values[f"nDCG@{k}"].append(ndcg_at_k(retrieved, relevant, k))
        values["MRR@10"].append(reciprocal_rank(retrieved, relevant, 10))
    return {column: mean(values[column]) for column in METRIC_COLUMNS}


def formatted_row(prefix: dict[str, Any], metrics: dict[str, float]) -> dict[str, Any]:
    return {**prefix, **{key: f"{value:.12g}" for key, value in metrics.items()}}


def validate_run(
    raw: list[dict[str, Any]], queries: list[dict[str, Any]], id_map: dict[str, int],
    manifest: dict[str, Any], before: dict[str, Any], after: dict[str, Any],
) -> dict[str, Any]:
    if len(raw) != 160:
        raise EvaluationFailure(f"raw rows={len(raw)}, expected 160")
    expected_query_ids = [f"q{index:03d}" for index in range(1, 41)]
    if len(queries) != 40 or [row.get("query_id") for row in queries] != expected_query_ids:
        raise EvaluationFailure("queries are not exactly q001..q040")
    query_by_id = {row["query_id"]: row for row in queries}
    expected_pairs = {(query_id, mode) for query_id in expected_query_ids for mode in MODES}
    seen_pairs: set[tuple[str, str]] = set()
    mode_counts: Counter[str] = Counter()
    reverse_map = {str(value): key for key, value in id_map.items()}

    for index, record in enumerate(raw, start=1):
        query_id = record.get("query_id")
        mode = record.get("mode")
        pair = (query_id, mode)
        if pair not in expected_pairs or pair in seen_pairs:
            raise EvaluationFailure(f"raw row {index}: missing/duplicate query-mode pair {pair}")
        seen_pairs.add(pair)
        mode_counts[mode] += 1
        expected = query_by_id[query_id]
        for field in ("query", "query_type", "difficulty", "relevant_memory_ids"):
            if record.get(field) != expected.get(field):
                raise EvaluationFailure(f"{query_id} {mode}: ground truth field changed: {field}")
        if record.get("http_status") != 200 or record.get("status") != "OK" or record.get("topK") != 10:
            raise EvaluationFailure(f"{query_id} {mode}: unsuccessful raw response")
        results = record.get("results")
        if not isinstance(results, list) or len(results) > 10:
            raise EvaluationFailure(f"{query_id} {mode}: invalid results length")
        if [row.get("rank") for row in results] != list(range(1, len(results) + 1)):
            raise EvaluationFailure(f"{query_id} {mode}: ranks are not consecutive")
        result_ids = [row.get("benchmark_memory_id") for row in results]
        if len(result_ids) != len(set(result_ids)):
            raise EvaluationFailure(f"{query_id} {mode}: duplicate benchmark result")
        for result in results:
            benchmark_id = result.get("benchmark_memory_id")
            database_id = str(result.get("db_memory_id"))
            if benchmark_id not in id_map or reverse_map.get(database_id) != benchmark_id:
                raise EvaluationFailure(f"{query_id} {mode}: invalid DB/benchmark mapping")

    if seen_pairs != expected_pairs or mode_counts != Counter({mode: 40 for mode in MODES}):
        raise EvaluationFailure("raw data is not an exact 40 x 4 Cartesian product")
    no_answer_count = sum(row["query_type"] == "no_answer" for row in queries)
    core_query_count = len(queries) - no_answer_count
    if no_answer_count != 4 or core_query_count != 36:
        raise EvaluationFailure(
            f"query split invalid: no_answer={no_answer_count}, core={core_query_count}"
        )
    if before.get("database") != "mindpet_eval" or after.get("database") != "mindpet_eval":
        raise EvaluationFailure("state snapshots are not from mindpet_eval")
    if before.get("rows") != after.get("rows") or len(before.get("rows", [])) != 120:
        raise EvaluationFailure("before/after memory state differs")
    if manifest.get("successful_requests") != 160 or manifest.get("failed_requests") != 0:
        raise EvaluationFailure("run manifest request counts are invalid")
    return {
        "raw_rows": len(raw),
        "mode_counts": dict(mode_counts),
        "no_answer_count": no_answer_count,
        "core_query_count": core_query_count,
        "access_count_changes": 0,
        "last_accessed_changes": 0,
    }


def no_answer_rows(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for mode in MODES:
        records = [
            record for record in raw
            if record["mode"] == mode and record["query_type"] == "no_answer"
        ]
        row: dict[str, Any] = {"mode": mode, "no_answer_query_count": len(records)}
        for k in KS:
            returned = [min(len(record["results"]), k) for record in records]
            empty_count = sum(count == 0 for count in returned)
            row[f"empty_result_count@{k}"] = empty_count
            row[f"empty_result_rate@{k}"] = f"{empty_count / len(records):.12g}"
            row[f"avg_returned_count@{k}"] = f"{mean(returned):.12g}"
        rows.append(row)
    return rows


def markdown_table(rows: list[dict[str, Any]], columns: list[str]) -> list[str]:
    lines = ["| " + " | ".join(columns) + " |", "|" + "|".join(["---"] * len(columns)) + "|"]
    for row in rows:
        lines.append("| " + " | ".join(str(row[column]) for column in columns) + " |")
    return lines


def build_report(
    manifest: dict[str, Any], overall: list[dict[str, Any]], by_type: list[dict[str, Any]],
    diagnostics: list[dict[str, Any]], raw: list[dict[str, Any]], validation: dict[str, Any],
) -> str:
    lines = [
        "# MindPet Retrieval Ablation Run",
        "",
        "## 1. 环境与 Benchmark",
        "",
        f"- Git commit：`{manifest['git_commit']}`",
        f"- 运行时间：`{manifest['run_started_at']}` 至 `{manifest['run_finished_at']}`",
        f"- 数据库：`{manifest['database']}`；用户：`{manifest['user_id']}`",
        f"- Benchmark：{manifest['memory_count']} memories / {manifest['query_count']} queries",
        f"- Embedding：`{manifest['embedding_model']}`，{manifest['embedding_dimension']} 维",
        f"- API：`{manifest['java_api_url']}`",
        "",
        "## 2. 模式与指标定义",
        "",
        "比较 keyword_only、vector_only、rrf、mindpet_full。每个 query/mode 只请求一次 Top10，K=1/3/5/10 均由同一排名切片得到。主排名指标仅统计 36 条有答案查询。Precision@K 的分母固定为 K；Recall@K 以 ground truth 数量为分母；MRR@10 取前十首个相关结果的倒数排名；nDCG 使用二元相关性。",
        "",
        "## 3. 总体结果",
        "",
    ]
    lines.extend(markdown_table(overall, ["mode", "Precision@1", "Recall@1", "Recall@3", "Recall@5", "Recall@10", "MRR@10", "nDCG@10"]))
    lines.extend(["", "## 4. Query Type 切片", ""])
    lines.extend(markdown_table(by_type, ["query_type", "mode", "query_count", "Precision@1", "Recall@10", "MRR@10", "nDCG@10"]))
    lines.extend(["", "## 5. No-answer 诊断", "", "当前系统没有正式拒答阈值；empty result 仅作诊断，不能解释为 no-answer accuracy。", ""])
    lines.extend(markdown_table(diagnostics, ["mode", "no_answer_query_count", "empty_result_count@1", "empty_result_rate@1", "avg_returned_count@10"]))

    misses: list[str] = []
    for mode in MODES:
        missed = [
            record["query_id"] for record in raw
            if record["mode"] == mode and record["query_type"] != "no_answer"
            and not (set(record["relevant_memory_ids"]) & {r["benchmark_memory_id"] for r in record["results"][:10]})
        ]
        misses.append(f"- `{mode}`：{', '.join(missed) if missed else '无 Top10 miss'}")
    lines.extend([
        "", "## 6. 只读与一致性验证", "",
        f"- Raw：{validation['raw_rows']} 行；每种模式 40 条；有答案查询 36 条；no-answer 4 条。",
        "- 所有 HTTP 状态为 200、响应 status 为 OK、DB ID 均可映射到 Benchmark ID。",
        "- access_count 变化 0 条；last_accessed 变化 0 条。",
        "", "## 7. 异常与未命中查询", "",
        "HTTP/JSON/映射/一致性失败：无。以下仅列出有答案查询的 Top10 未命中，不等同于运行错误：",
        *misses,
        "", "## 8. 已知限制", "",
        "- 单用户、中文、120 条人工 Benchmark，不能代表真实用户总体分布。",
        "- 相关性为二元标注，尚无多标注者一致性或分级 gain。",
        "- No-answer 没有拒答阈值，因此只能报告返回数量诊断。",
        "- 本报告如实记录第一轮固定算法结果，不包含调参、删样本或图表。",
        "",
    ])
    return "\n".join(lines)


def evaluate(args: argparse.Namespace) -> dict[str, Any]:
    self_test()
    raw = read_jsonl(args.raw)
    queries = read_jsonl(args.queries)
    manifest = read_json(args.manifest)
    before = read_json(args.before_state)
    after = read_json(args.after_state)
    raw_map = read_json(args.memory_map)
    id_map = {str(key): int(value) for key, value in raw_map.items()}
    validation = validate_run(raw, queries, id_map, manifest, before, after)

    core = [record for record in raw if record["query_type"] != "no_answer"]
    overall = [
        formatted_row({"mode": mode}, metric_row([r for r in core if r["mode"] == mode]))
        for mode in MODES
    ]
    by_type: list[dict[str, Any]] = []
    for query_type in QUERY_TYPES:
        for mode in MODES:
            records = [r for r in core if r["query_type"] == query_type and r["mode"] == mode]
            by_type.append(
                formatted_row(
                    {"query_type": query_type, "mode": mode, "query_count": len(records)},
                    metric_row(records),
                )
            )
    by_difficulty: list[dict[str, Any]] = []
    for difficulty in DIFFICULTIES:
        for mode in MODES:
            records = [r for r in core if r["difficulty"] == difficulty and r["mode"] == mode]
            if records:
                by_difficulty.append(
                    formatted_row(
                        {"difficulty": difficulty, "mode": mode, "query_count": len(records)},
                        metric_row(records),
                    )
                )
    diagnostics = no_answer_rows(raw)

    write_csv(args.metrics, ["mode", *METRIC_COLUMNS], overall)
    write_csv(args.by_type, ["query_type", "mode", "query_count", *METRIC_COLUMNS], by_type)
    write_csv(args.by_difficulty, ["difficulty", "mode", "query_count", *METRIC_COLUMNS], by_difficulty)
    diagnostic_columns = ["mode", "no_answer_query_count"] + [
        name for k in KS for name in (
            f"empty_result_count@{k}", f"empty_result_rate@{k}", f"avg_returned_count@{k}"
        )
    ]
    write_csv(args.no_answer, diagnostic_columns, diagnostics)
    write_text(args.report, build_report(manifest, overall, by_type, diagnostics, raw, validation))
    return {
        "validation": validation,
        "overall": overall,
        "no_answer": diagnostics,
        "outputs": [
            str(args.metrics), str(args.by_type), str(args.by_difficulty),
            str(args.no_answer), str(args.report),
        ],
    }


def parse_args() -> argparse.Namespace:
    raw_dir = EVAL_ROOT / "results/raw"
    table_dir = EVAL_ROOT / "results/tables"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, default=raw_dir / "retrieval_ablation_raw.jsonl")
    parser.add_argument("--queries", type=Path, default=EVAL_ROOT / "datasets/retrieval/queries.jsonl")
    parser.add_argument("--memory-map", type=Path, default=EVAL_ROOT / "results/memory_id_map.json")
    parser.add_argument("--manifest", type=Path, default=raw_dir / "run_manifest.json")
    parser.add_argument("--before-state", type=Path, default=raw_dir / "retrieval_state_before.json")
    parser.add_argument("--after-state", type=Path, default=raw_dir / "retrieval_state_after.json")
    parser.add_argument("--metrics", type=Path, default=table_dir / "retrieval_metrics.csv")
    parser.add_argument("--by-type", type=Path, default=table_dir / "retrieval_metrics_by_type.csv")
    parser.add_argument("--by-difficulty", type=Path, default=table_dir / "retrieval_metrics_by_difficulty.csv")
    parser.add_argument("--no-answer", type=Path, default=table_dir / "no_answer_diagnostics.csv")
    parser.add_argument("--report", type=Path, default=EVAL_ROOT / "reports/retrieval-ablation-run.md")
    return parser.parse_args()


def main() -> None:
    try:
        result = evaluate(parse_args())
    except EvaluationFailure as exc:
        print(f"EVALUATION FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    except Exception as exc:
        print(f"EVALUATION FAILED: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    print("EVALUATION PASSED")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
