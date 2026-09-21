"""Analyze RRF versus MindPet Full rankings from an existing raw run only."""

from __future__ import annotations

import argparse
import csv
import json
import math
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
)


MODES = ["keyword_only", "vector_only", "rrf", "mindpet_full"]
QUERY_TYPES = [
    "exact_keyword", "semantic_paraphrase", "hybrid", "multi_candidate",
    "temporal_importance",
]
MISSING_RANK = 11
RRF_MAX = 2.0 / 61.0
RETRIEVAL_MAX = 0.5 * RRF_MAX
METADATA_MAX = 0.2 + 0.2 + 0.05 + 0.05


class AnalysisFailure(RuntimeError):
    """Raised when existing raw data is incomplete or inconsistent."""


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
        rows = [json.loads(line) for line in lines if line.strip()]
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AnalysisFailure(f"cannot read {path}: {exc}") from exc
    if len(lines) != len(rows) or not all(isinstance(row, dict) for row in rows):
        raise AnalysisFailure(f"invalid JSONL records in {path}")
    return rows


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


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        temporary.write_text(content, encoding="utf-8", newline="\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def result_ranks(record: dict[str, Any], relevant_ids: list[str]) -> dict[str, int]:
    observed = {
        result["benchmark_memory_id"]: int(result["rank"])
        for result in record["results"]
        if result["benchmark_memory_id"] in relevant_ids
    }
    return {memory_id: observed.get(memory_id, MISSING_RANK) for memory_id in relevant_ids}


def first_relevant_id(ranks: dict[str, int]) -> str:
    return min(ranks, key=lambda memory_id: (ranks[memory_id], memory_id))


def contribution(result: dict[str, Any]) -> dict[str, float | str]:
    required = [
        "rrfScore", "timeScore", "importanceContribution", "confidenceContribution",
        "highImportanceBonus",
    ]
    for field in required:
        if not isinstance(result.get(field), (int, float)) or isinstance(result.get(field), bool):
            raise AnalysisFailure(
                f"{result.get('benchmark_memory_id')}: Full result lacks numeric {field}"
            )
    retrieval = 0.5 * float(result["rrfScore"])
    metadata = (
        0.2 * float(result["timeScore"])
        + float(result["importanceContribution"])
        + float(result["confidenceContribution"])
        + float(result["highImportanceBonus"])
    )
    ratio: float | str = "INF" if retrieval == 0.0 else metadata / retrieval
    return {
        "retrievalContribution": retrieval,
        "metadataContribution": metadata,
        "metadata_to_retrieval_ratio": ratio,
    }


def full_fields(prefix: str, result: dict[str, Any] | None) -> dict[str, Any]:
    fields = [
        "benchmark_memory_id", "content", "rank", "rrfScore", "timeScore", "importance",
        "importanceContribution", "confidence", "confidenceContribution",
        "highImportanceBonus", "finalScore",
    ]
    if result is None:
        values = {f"{prefix}_{field}": "" for field in fields}
        values.update({
            f"{prefix}_retrievalContribution": "",
            f"{prefix}_metadataContribution": "",
            f"{prefix}_metadata_to_retrieval_ratio": "",
        })
        return values
    values = {f"{prefix}_{field}": result.get(field) for field in fields}
    values.update({f"{prefix}_{key}": value for key, value in contribution(result).items()})
    return values


def metric_summary(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for mode in MODES:
        records = [
            record for record in raw
            if record["mode"] == mode and record["relevant_memory_ids"]
        ]
        p1: list[float] = []
        r10: list[float] = []
        rr: list[float] = []
        ndcg10: list[float] = []
        for record in records:
            retrieved = [result["benchmark_memory_id"] for result in record["results"]]
            relevant = record["relevant_memory_ids"]
            p1.append(precision_at_k(retrieved, relevant, 1))
            r10.append(recall_at_k(retrieved, relevant, 10))
            rr.append(reciprocal_rank(retrieved, relevant, 10))
            ndcg10.append(ndcg_at_k(retrieved, relevant, 10))
        rows.append({
            "mode": mode,
            "P@1": mean(p1),
            "R@10": mean(r10),
            "MRR@10": mean(rr),
            "nDCG@10": mean(ndcg10),
        })
    return rows


def average_contributions(results: list[dict[str, Any]]) -> dict[str, Any]:
    if not results:
        return {"count": 0, "retrieval": None, "metadata": None, "finite_ratio": None, "inf": 0}
    parts = [contribution(result) for result in results]
    finite_ratios = [
        float(part["metadata_to_retrieval_ratio"])
        for part in parts if part["metadata_to_retrieval_ratio"] != "INF"
    ]
    return {
        "count": len(results),
        "retrieval": mean(float(part["retrievalContribution"]) for part in parts),
        "metadata": mean(float(part["metadataContribution"]) for part in parts),
        "finite_ratio": mean(finite_ratios) if finite_ratios else None,
        "inf": len(parts) - len(finite_ratios),
    }


def format_number(value: Any) -> str:
    if value is None:
        return "N/A"
    if isinstance(value, str):
        return value
    return f"{float(value):.10f}"


def markdown_table(rows: list[dict[str, Any]], columns: list[str]) -> list[str]:
    lines = ["| " + " | ".join(columns) + " |", "|" + "|".join(["---"] * len(columns)) + "|"]
    for row in rows:
        lines.append("| " + " | ".join(str(row[column]) for column in columns) + " |")
    return lines


def analyze(args: argparse.Namespace) -> dict[str, Any]:
    raw = read_jsonl(args.raw)
    queries = read_jsonl(args.queries)
    if len(raw) != 160 or len(queries) != 40:
        raise AnalysisFailure(f"expected raw=160 and queries=40, got {len(raw)} and {len(queries)}")
    index = {(row["query_id"], row["mode"]): row for row in raw}
    if len(index) != 160:
        raise AnalysisFailure("raw query/mode combinations are not unique")

    query_rows: list[dict[str, Any]] = []
    degraded_rows: list[dict[str, Any]] = []
    degraded_details: list[dict[str, Any]] = []
    wrong_upper_results: list[dict[str, Any]] = []
    degraded_relevant_results: list[dict[str, Any]] = []
    classification_counts: Counter[str] = Counter()
    type_stats: dict[str, dict[str, Any]] = {
        query_type: {"query_count": 0, "improved": 0, "unchanged": 0, "degraded": 0, "deltas": []}
        for query_type in QUERY_TYPES
    }
    paired_comparisons = 0
    lower_retrieval_higher_metadata = 0

    for query in queries:
        relevant_ids = query["relevant_memory_ids"]
        if not relevant_ids:
            continue
        rrf_record = index[(query["query_id"], "rrf")]
        full_record = index[(query["query_id"], "mindpet_full")]
        rrf_ranks = result_ranks(rrf_record, relevant_ids)
        full_ranks = result_ranks(full_record, relevant_ids)
        rrf_first_id = first_relevant_id(rrf_ranks)
        full_first_id = first_relevant_id(full_ranks)
        rrf_first_rank = rrf_ranks[rrf_first_id]
        full_first_rank = full_ranks[full_first_id]
        delta = full_first_rank - rrf_first_rank
        classification = "improved" if delta < 0 else "unchanged" if delta == 0 else "degraded"
        classification_counts[classification] += 1
        stats = type_stats[query["query_type"]]
        stats["query_count"] += 1
        stats[classification] += 1
        stats["deltas"].append(delta)

        query_rows.append({
            "query_id": query["query_id"],
            "query": query["query"],
            "query_type": query["query_type"],
            "relevant_memory_ids": json.dumps(relevant_ids, ensure_ascii=False),
            "rrf_first_relevant_memory_id": rrf_first_id,
            "rrf_first_relevant_rank": rrf_first_rank,
            "rrf_relevant_ranks": json.dumps(rrf_ranks, ensure_ascii=False, sort_keys=True),
            "full_first_relevant_memory_id": full_first_id,
            "full_first_relevant_rank": full_first_rank,
            "full_relevant_ranks": json.dumps(full_ranks, ensure_ascii=False, sort_keys=True),
            "rank_delta": delta,
            "classification": classification,
        })

        if classification != "degraded":
            continue
        relevant_set = set(relevant_ids)
        full_relevant = [
            result for result in full_record["results"]
            if result["benchmark_memory_id"] in relevant_set
        ]
        degraded_relevant_results.extend(full_relevant)
        full_reference = min(full_relevant, key=lambda result: result["rank"]) if full_relevant else None
        rrf_reference = next(
            result for result in rrf_record["results"]
            if result["benchmark_memory_id"] == rrf_first_id
        )
        wrong_above = [
            result for result in full_record["results"]
            if result["rank"] < full_first_rank
            and result["benchmark_memory_id"] not in relevant_set
        ]
        wrong_upper_results.extend(wrong_above)
        degraded_details.append({
            "query": query,
            "rrf_first_rank": rrf_first_rank,
            "full_first_rank": full_first_rank,
            "rank_delta": delta,
            "rrf_reference": rrf_reference,
            "full_reference": full_reference,
            "wrong_above": wrong_above,
        })
        for wrong in wrong_above:
            row = {
                "query_id": query["query_id"],
                "query": query["query"],
                "query_type": query["query_type"],
                "relevant_memory_ids": json.dumps(relevant_ids, ensure_ascii=False),
                "rrf_first_relevant_rank": rrf_first_rank,
                "full_first_relevant_rank": full_first_rank,
                "rank_delta": delta,
                "relevant_in_full_top10": full_reference is not None,
                "rrf_reference_memory_id": rrf_reference["benchmark_memory_id"],
                "rrf_reference_content": rrf_reference["content"],
                "rrf_reference_rank": rrf_reference["rank"],
                "rrf_reference_rrfScore": rrf_reference.get("rrfScore"),
            }
            row.update(full_fields("error", wrong))
            row.update(full_fields("relevant", full_reference))
            degraded_rows.append(row)
            if full_reference is not None:
                paired_comparisons += 1
                wrong_part = contribution(wrong)
                relevant_part = contribution(full_reference)
                if (
                    float(wrong_part["retrievalContribution"])
                    < float(relevant_part["retrievalContribution"])
                    and float(wrong_part["metadataContribution"])
                    > float(relevant_part["metadataContribution"])
                    and float(wrong["finalScore"]) > float(full_reference["finalScore"])
                ):
                    lower_retrieval_higher_metadata += 1

    if len(query_rows) != 36:
        raise AnalysisFailure(f"core query count={len(query_rows)}, expected 36")

    query_fields = [
        "query_id", "query", "query_type", "relevant_memory_ids",
        "rrf_first_relevant_memory_id", "rrf_first_relevant_rank", "rrf_relevant_ranks",
        "full_first_relevant_memory_id", "full_first_relevant_rank", "full_relevant_ranks",
        "rank_delta", "classification",
    ]
    degraded_fields = [
        "query_id", "query", "query_type", "relevant_memory_ids",
        "rrf_first_relevant_rank", "full_first_relevant_rank", "rank_delta",
        "relevant_in_full_top10", "rrf_reference_memory_id", "rrf_reference_content",
        "rrf_reference_rank", "rrf_reference_rrfScore",
    ]
    candidate_fields = [
        "benchmark_memory_id", "content", "rank", "rrfScore", "timeScore", "importance",
        "importanceContribution", "confidence", "confidenceContribution",
        "highImportanceBonus", "finalScore", "retrievalContribution",
        "metadataContribution", "metadata_to_retrieval_ratio",
    ]
    degraded_fields.extend(f"error_{field}" for field in candidate_fields)
    degraded_fields.extend(f"relevant_{field}" for field in candidate_fields)
    write_csv(args.query_output, query_fields, query_rows)
    write_csv(args.degraded_output, degraded_fields, degraded_rows)

    wrong_stats = average_contributions(wrong_upper_results)
    relevant_stats = average_contributions(degraded_relevant_results)
    type_rows = [
        {
            "query_type": query_type,
            "query_count": type_stats[query_type]["query_count"],
            "improved": type_stats[query_type]["improved"],
            "unchanged": type_stats[query_type]["unchanged"],
            "degraded": type_stats[query_type]["degraded"],
            "avg_rank_delta": f"{mean(type_stats[query_type]['deltas']):.6f}",
        }
        for query_type in QUERY_TYPES
    ]
    full_ranks = [row["full_first_relevant_rank"] for row in query_rows]
    summary_metrics = metric_summary(raw)
    typical = sorted(
        degraded_details,
        key=lambda item: (-item["rank_delta"], item["query"]["query_id"]),
    )[:8]

    report: list[str] = [
        "# MindPet Full Rerank Query-level Error Analysis",
        "",
        "本报告只分析既有 `retrieval_ablation_raw.jsonl` 和 `queries.jsonl`，没有重新请求 API、运行实验或修改算法。Top10 未出现 relevant 时，error analysis rank 记为 11；正式 MRR 定义不变。",
        "",
        "## 1. 第一轮结果摘要",
        "",
    ]
    metric_table = [
        {
            "mode": row["mode"],
            "P@1": f"{row['P@1']:.6f}",
            "R@10": f"{row['R@10']:.6f}",
            "MRR@10": f"{row['MRR@10']:.6f}",
            "nDCG@10": f"{row['nDCG@10']:.6f}",
        }
        for row in summary_metrics
    ]
    report.extend(markdown_table(metric_table, ["mode", "P@1", "R@10", "MRR@10", "nDCG@10"]))
    report.extend([
        "", "## 2. RRF vs Full Rank Delta", "",
        f"- improved：{classification_counts['improved']} / 36 ({classification_counts['improved']/36:.2%})",
        f"- unchanged：{classification_counts['unchanged']} / 36 ({classification_counts['unchanged']/36:.2%})",
        f"- degraded：{classification_counts['degraded']} / 36 ({classification_counts['degraded']/36:.2%})",
        f"- 平均 rank delta（Full - RRF）：{mean(row['rank_delta'] for row in query_rows):.6f}",
        "", "## 3. 各 Query Type Degradation", "",
    ])
    report.extend(markdown_table(type_rows, ["query_type", "query_count", "improved", "unchanged", "degraded", "avg_rank_delta"]))
    report.extend([
        "", "## 4. Retrieval 与 Metadata 贡献", "",
        "Full 分解：`retrievalContribution = 0.5 × rrfScore`；`metadataContribution = 0.2 × timeScore + importanceContribution + confidenceContribution + highImportanceBonus`。importanceContribution 已是 `importance × 0.2`，未重复相乘。",
        "",
        "| 候选组 | 数量 | 平均 retrievalContribution | 平均 metadataContribution | 平均有限 ratio | INF 数量 |",
        "|---|---:|---:|---:|---:|---:|",
        f"| degraded 中错误上位 memory | {wrong_stats['count']} | {format_number(wrong_stats['retrieval'])} | {format_number(wrong_stats['metadata'])} | {format_number(wrong_stats['finite_ratio'])} | {wrong_stats['inf']} |",
        f"| degraded 中 Top10 relevant memory | {relevant_stats['count']} | {format_number(relevant_stats['retrieval'])} | {format_number(relevant_stats['metadata'])} | {format_number(relevant_stats['finite_ratio'])} | {relevant_stats['inf']} |",
        "",
        f"在可直接成对比较的 {paired_comparisons} 个“错误上位 memory / 首个 Full relevant memory”组合中，有 {lower_retrieval_higher_metadata} 个组合同时满足：错误 memory 的 retrievalContribution 更低、metadataContribution 更高，但 finalScore 更高。",
        "", "## 5. 典型失败案例", "",
        "| query | type | relevant | RRF first rank | Full first rank | delta | Full rank 1 error |",
        "|---|---|---|---:|---:|---:|---|",
    ])
    for item in typical:
        query = item["query"]
        first_wrong = min(item["wrong_above"], key=lambda result: result["rank"])
        report.append(
            f"| {query['query_id']} {query['query']} | {query['query_type']} | "
            f"{', '.join(query['relevant_memory_ids'])} | {item['rrf_first_rank']} | "
            f"{item['full_first_rank']} | {item['rank_delta']} | "
            f"{first_wrong['benchmark_memory_id']} {first_wrong['content']} |"
        )
    report.extend([
        "", "Top10 外 relevant 的 Full 分数不在 raw 中，因此 q016、q028 的正确 memory Full 贡献字段保留为空，不做推断。完整逐候选字段见 degraded cases CSV。",
        "", "## 6. RRF / Full Score Scale", "",
        f"- `RRF_max = 2/61 = {RRF_MAX:.10f}`",
        f"- `0.5 × RRF_max = {RETRIEVAL_MAX:.10f}`",
        "- `0.2 × timeScore ≤ 0.2`",
        "- `importanceContribution ≤ 0.2`",
        "- `confidenceContribution ≤ 0.05`",
        "- `highImportanceBonus ≤ 0.05`",
        f"- metadata theoretical max = {METADATA_MAX:.2f}",
        f"- metadata_max / retrieval_max = {METADATA_MAX / RETRIEVAL_MAX:.1f}",
        "", "该尺度比较只描述当前公式的数值范围，不提出或实施权重修改。",
        "", "## 7. Top10 Recall 与 MRR 差异", "",
        f"- 至少一个 relevant 位于 Full Top10：{sum(rank <= 10 for rank in full_ranks)} / 36",
        f"- Full Top10 内没有 relevant：{sum(rank == 11 for rank in full_ranks)} / 36",
        f"- 首个 relevant rank > 1：{sum(rank > 1 for rank in full_ranks)} / 36",
        f"- 首个 relevant rank > 3：{sum(rank > 3 for rank in full_ranks)} / 36",
        f"- 首个 relevant rank > 5：{sum(rank > 5 for rank in full_ranks)} / 36",
        "- Full R@10 为 0.916667，而 MRR@10 为 0.423876。多数查询仍在 Top10 找到至少一个相关项，但正确项经常被移动到较后位置；多 relevant query 的 R@10 还会受是否召回全部 relevant 影响。",
        "", "## 8. 当前数据可以支持的结论", "",
        "- RRF 的候选相关性信号总体正常：36 条有答案查询中，RRF R@10 为 1.0、MRR@10 为 0.921296。",
        f"- Full 相对 RRF 有 {classification_counts['degraded']} 条退化、{classification_counts['improved']} 条改善，主要差异发生在 rerank 顺序而非候选完全缺失。",
        "- 在本轮 raw 的 degraded cases 中，错误上位候选平均 retrievalContribution 低于 relevant 候选，但平均 metadataContribution 更高；finalScore 的确定性分解显示 metadata 项足以覆盖 retrieval 差距。",
        "- 因此，本轮数据支持“当前 Full 公式中的相关性贡献与 metadata 贡献存在显著量级失衡，并导致排序退化”这一针对本 Benchmark 和本次运行的结论。",
        "", "## 9. 当前数据不能支持的结论", "",
        "- 不能据此确定新的最优权重、归一化方法或通用阈值；本阶段没有做任何反事实调参实验。",
        "- 不能外推到其他用户、语言、数据规模或真实生产分布。",
        "- 不能把 Top10 外 relevant 的 Full 分数补算或猜测；API raw 没有提供这些候选的 rerank 字段。",
        "- 不能断言 time、importance、confidence 或 bonus 中某一个单项独立造成全部退化；当前只验证了它们合计后的尺度和排序效应。",
        "",
    ])
    write_text(args.report, "\n".join(report))

    return {
        "core_queries": len(query_rows),
        "classification": dict(classification_counts),
        "average_rank_delta": mean(row["rank_delta"] for row in query_rows),
        "full_rank_gt_1": sum(rank > 1 for rank in full_ranks),
        "full_rank_gt_3": sum(rank > 3 for rank in full_ranks),
        "full_rank_gt_5": sum(rank > 5 for rank in full_ranks),
        "full_top10_hit_queries": sum(rank <= 10 for rank in full_ranks),
        "wrong_upper": wrong_stats,
        "degraded_relevant": relevant_stats,
        "paired_comparisons": paired_comparisons,
        "lower_retrieval_higher_metadata": lower_retrieval_higher_metadata,
        "type_stats": type_rows,
        "theoretical": {
            "rrf_max": RRF_MAX,
            "retrieval_max": RETRIEVAL_MAX,
            "metadata_max": METADATA_MAX,
            "ratio": METADATA_MAX / RETRIEVAL_MAX,
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--raw", type=Path,
        default=EVAL_ROOT / "results/raw/retrieval_ablation_raw.jsonl",
    )
    parser.add_argument(
        "--queries", type=Path,
        default=EVAL_ROOT / "datasets/retrieval/queries.jsonl",
    )
    parser.add_argument(
        "--query-output", type=Path,
        default=EVAL_ROOT / "results/tables/full_rerank_error_analysis.csv",
    )
    parser.add_argument(
        "--degraded-output", type=Path,
        default=EVAL_ROOT / "results/tables/full_rerank_degraded_cases.csv",
    )
    parser.add_argument(
        "--report", type=Path,
        default=EVAL_ROOT / "reports/full-rerank-error-analysis.md",
    )
    return parser.parse_args()


def main() -> None:
    try:
        result = analyze(parse_args())
    except AnalysisFailure as exc:
        print(f"ANALYSIS FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    except Exception as exc:
        print(f"ANALYSIS FAILED: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    print("ANALYSIS PASSED")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
