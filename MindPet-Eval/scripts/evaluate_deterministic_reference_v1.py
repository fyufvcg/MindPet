"""Validate and summarize the fixed-clock nine-mode deterministic reference run."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from evaluate_retrieval import (
    EVAL_ROOT,
    KS,
    METRIC_COLUMNS,
    QUERY_TYPES,
    EvaluationFailure,
    formatted_row,
    markdown_table,
    metric_row,
    no_answer_rows,
    rank_comparison_rows,
    read_json,
    read_jsonl,
    validate_run,
    write_csv,
    write_text,
)


CANONICAL_AS_OF = "2026-09-21T08:38:06.750458"
MODES = [
    "keyword_only",
    "vector_only",
    "rrf",
    "mindpet_full",
    "mindpet_full_rrf_norm",
    "mindpet_rrf_norm_only",
    "mindpet_rrf_norm_time",
    "mindpet_rrf_norm_importance",
    "mindpet_rrf_norm_importance_bonus",
]
PAIRS = [
    ("mindpet_full", "mindpet_full_rrf_norm", "full_v1_vs_v2.csv"),
    ("mindpet_rrf_norm_only", "mindpet_rrf_norm_time", "norm_only_vs_time.csv"),
    ("mindpet_rrf_norm_only", "mindpet_rrf_norm_importance", "norm_only_vs_importance.csv"),
    ("mindpet_rrf_norm_importance", "mindpet_rrf_norm_importance_bonus", "importance_vs_bonus.csv"),
    ("mindpet_rrf_norm_only", "mindpet_full_rrf_norm", "norm_only_vs_full_v2.csv"),
    ("rrf", "mindpet_full_rrf_norm", "rrf_vs_full_v2.csv"),
]
COMPARISON_COLUMNS = [
    "query_id", "query", "query_type", "difficulty", "relevant_memory_ids",
    "reference_mode", "reference_first_relevant_rank", "candidate_mode",
    "candidate_first_relevant_rank", "rank_delta", "classification",
]
NORMALIZED_MODES = {
    "mindpet_full_rrf_norm",
    "mindpet_rrf_norm_only",
    "mindpet_rrf_norm_time",
    "mindpet_rrf_norm_importance",
    "mindpet_rrf_norm_importance_bonus",
}


def validate_reference(
    raw: list[dict], queries: list[dict], manifest: dict,
    before: dict, after: dict, id_map: dict[str, int],
) -> dict:
    expected_manifest = {
        "experiment": "deterministic_reference_v1",
        "benchmark_version": "Retrieval Benchmark v1",
        "benchmark_base_time": CANONICAL_AS_OF,
        "evaluation_as_of": CANONICAL_AS_OF,
        "clock_mode": "fixed",
        "database": "mindpet_eval",
        "user": "eval_test_user",
        "memories": 120,
        "queries": 40,
        "mode_count": 9,
        "formal_requests": 360,
        "topK": 10,
        "embedding": "bge-m3",
        "embedding_dimension": 1024,
        "success_count": 360,
        "failure_count": 0,
    }
    for key, expected in expected_manifest.items():
        if manifest.get(key) != expected:
            raise EvaluationFailure(
                f"manifest {key}={manifest.get(key)!r}, expected {expected!r}"
            )
    if manifest.get("modes") != MODES or not manifest.get("git_commit"):
        raise EvaluationFailure("manifest modes or git_commit is invalid")
    if not manifest.get("start_time") or not manifest.get("end_time"):
        raise EvaluationFailure("manifest start/end time is missing")

    validation = validate_run(raw, queries, id_map, manifest, before, after, MODES)
    for record in raw:
        query_mode = f"{record.get('query_id')} {record.get('mode')}"
        if record.get("asOf") != CANONICAL_AS_OF:
            raise EvaluationFailure(f"{query_mode}: asOf is not canonical")
        for result in record["results"]:
            if str(result.get("memoryId")) != str(result.get("db_memory_id")):
                raise EvaluationFailure(f"{query_mode}: memoryId/db_memory_id mismatch")
            if record["mode"] in NORMALIZED_MODES:
                value = result.get("rrfNormalized")
                if not isinstance(value, (int, float)) or not 0.0 <= value <= 1.0:
                    raise EvaluationFailure(f"{query_mode}: invalid rrfNormalized")

    by_pair = {(row["query_id"], row["mode"]): row for row in raw}
    mismatches: list[str] = []
    for query in queries:
        query_id = query["query_id"]
        rrf_ids = [
            item["benchmark_memory_id"]
            for item in by_pair[(query_id, "rrf")]["results"]
        ]
        norm_ids = [
            item["benchmark_memory_id"]
            for item in by_pair[(query_id, "mindpet_rrf_norm_only")]["results"]
        ]
        if rrf_ids != norm_ids:
            mismatches.append(query_id)
    if mismatches:
        raise EvaluationFailure(
            "RRF vs Norm Only full-ranking mismatch: " + ", ".join(mismatches)
        )

    validation.update({
        "same_as_of": len(raw),
        "http_200": len(raw),
        "status_ok": len(raw),
        "mapping_pass": len(raw),
        "before_count": len(before["rows"]),
        "after_count": len(after["rows"]),
        "rrf_norm_only_identical": 40,
    })
    return validation


def sequence_change_count(raw: list[dict], reference: str, candidate: str) -> int:
    by_pair = {(row["query_id"], row["mode"]): row for row in raw}
    return sum(
        [item["benchmark_memory_id"] for item in by_pair[(f"q{index:03d}", reference)]["results"]]
        != [item["benchmark_memory_id"] for item in by_pair[(f"q{index:03d}", candidate)]["results"]]
        for index in range(1, 41)
    )


def render_report(
    manifest: dict, validation: dict, overall: list[dict], by_type: list[dict],
    diagnostics: list[dict], comparisons: dict, sequence_changes: dict,
) -> str:
    by_mode = {row["mode"]: row for row in overall}
    lines = [
        "# MindPet Deterministic Reference Run v1",
        "",
        "## 1. Run contract",
        "",
        f"- Git commit: `{manifest['git_commit']}`",
        "- Benchmark: Retrieval Benchmark v1; 120 memories; 40 queries "
        "(36 answerable, 4 no-answer).",
        "- Database/user: `mindpet_eval` / `eval_test_user`.",
        "- Embedding: `bge-m3`, 1024 dimensions.",
        f"- Fixed evaluationAsOf: `{manifest['evaluation_as_of']}`.",
        "- All 360 formal requests used the same explicit evaluationAsOf. "
        "Evaluation retention and timeScore did not use wall-clock time.",
        "- Each query/mode was requested exactly once at Top10; K=1/3/5/10 "
        "are slices of that one ranking.",
        "",
        "## 2. Validation",
        "",
        f"- Raw rows / unique query-mode pairs: {validation['raw_rows']} / 360.",
        f"- HTTP 200 / status OK / same asOf: {validation['http_200']} / "
        f"{validation['status_ok']} / {validation['same_as_of']}.",
        f"- State snapshots: {validation['before_count']} before / "
        f"{validation['after_count']} after.",
        "- access_count changes: 0; last_accessed changes: 0.",
        "- RRF vs Norm Only complete Top10 sequence: 40/40 identical.",
        "- No token, password, or API key is recorded in experiment outputs.",
        "",
        "## 3. Overall metrics — 36 answerable queries",
        "",
        *markdown_table(overall, ["mode", "Precision@1", "Recall@10", "MRR@10", "nDCG@10"]),
        "",
        "## 4. Pairwise first-relevant-rank effects",
        "",
        "Top10 misses use rank 11 only for this error analysis; formal MRR remains unchanged.",
        "",
        "| comparison | improved | unchanged | degraded | avg rank delta | MRR@10 change | Top10 sequences changed / 40 |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for reference, candidate, filename in PAIRS:
        summary = comparisons[filename][1]
        mrr_change = float(by_mode[candidate]["MRR@10"]) - float(by_mode[reference]["MRR@10"])
        lines.append(
            f"| {reference} → {candidate} | {summary['improved']} | "
            f"{summary['unchanged']} | {summary['degraded']} | "
            f"{summary['avg_rank_delta']:+.6f} | {mrr_change:+.6f} | "
            f"{sequence_changes[filename]} |"
        )
    lines.extend([
        "",
        "## 5. Query-type metrics",
        "",
        *markdown_table(by_type, [
            "query_type", "mode", "query_count", "Precision@1", "Recall@10",
            "MRR@10", "nDCG@10",
        ]),
        "",
        "## 6. No-answer diagnostics",
        "",
        "The system has no formal reject threshold. These are returned-count "
        "diagnostics, not no-answer accuracy.",
        "",
        *markdown_table(diagnostics, [
            "mode", "no_answer_query_count", "empty_result_count@1",
            "empty_result_rate@1", "avg_returned_count@1",
            "empty_result_count@10", "empty_result_rate@10", "avg_returned_count@10",
        ]),
        "",
        "## 7. Interpretation boundaries",
        "",
        "- H1 is evaluated by Full V1 → Full Norm V2 metrics and query-level ranks "
        "under the same fixed candidate clock.",
        "- H2 is a reranking-level ablation. Importance still participates upstream "
        "in retention/layer behavior, so this is not a complete removal of importance.",
        "- The time, direct-importance, bonus, and full-metadata effects must be read "
        "from their same-run pairwise comparisons; older wall-clock runs are not used "
        "for absolute metric comparison.",
        "- No Java retrieval algorithm, score weight, benchmark item, ground truth, "
        "embedding, or database memory was changed during the formal experiment.",
        "",
        "## 8. Limitations",
        "",
        "- Single synthetic evaluation user and a 120-memory Chinese benchmark limit generalization.",
        "- Binary relevance labels do not capture graded relevance or annotator agreement.",
        "- Benchmark v1 was used during development, so this is reference evidence, "
        "not an untouched external test set.",
        "- No-answer behavior cannot be judged as accuracy until a rejection threshold exists.",
        "- This run evaluates retrieval/reranking only; it does not measure whether "
        "AI-generated importance labels are intrinsically accurate.",
        "",
    ])
    return "\n".join(lines)


def evaluate(args: argparse.Namespace) -> dict:
    raw = read_jsonl(args.raw)
    queries = read_jsonl(args.queries)
    manifest = read_json(args.manifest)
    before = read_json(args.before)
    after = read_json(args.after)
    id_map = {str(key): int(value) for key, value in read_json(args.memory_map).items()}
    validation = validate_reference(raw, queries, manifest, before, after, id_map)

    core = [row for row in raw if row["query_type"] != "no_answer"]
    overall = [
        formatted_row(
            {"mode": mode},
            metric_row([row for row in core if row["mode"] == mode]),
        )
        for mode in MODES
    ]
    by_type = [
        formatted_row(
            {"query_type": query_type, "mode": mode, "query_count": len(records)},
            metric_row(records),
        )
        for query_type in QUERY_TYPES
        for mode in MODES
        for records in [[
            row for row in core
            if row["query_type"] == query_type and row["mode"] == mode
        ]]
    ]
    diagnostics = no_answer_rows(raw, MODES)
    comparisons = {
        filename: rank_comparison_rows(raw, queries, reference, candidate)
        for reference, candidate, filename in PAIRS
    }
    sequence_changes = {
        filename: sequence_change_count(raw, reference, candidate)
        for reference, candidate, filename in PAIRS
    }

    args.tables.mkdir(parents=True, exist_ok=True)
    write_csv(args.tables / "retrieval_metrics.csv", ["mode", *METRIC_COLUMNS], overall)
    write_csv(
        args.tables / "retrieval_metrics_by_type.csv",
        ["query_type", "mode", "query_count", *METRIC_COLUMNS],
        by_type,
    )
    diagnostic_columns = ["mode", "no_answer_query_count"] + [
        name
        for k in KS
        for name in (
            f"empty_result_count@{k}", f"empty_result_rate@{k}",
            f"avg_returned_count@{k}",
        )
    ]
    write_csv(args.tables / "no_answer_diagnostics.csv", diagnostic_columns, diagnostics)
    for filename, (rows, _summary) in comparisons.items():
        write_csv(args.tables / filename, COMPARISON_COLUMNS, rows)
    write_text(
        args.report,
        render_report(
            manifest, validation, overall, by_type, diagnostics,
            comparisons, sequence_changes,
        ),
    )
    return {
        "validation": validation,
        "overall": overall,
        "comparisons": {key: value[1] for key, value in comparisons.items()},
        "sequence_changes": sequence_changes,
    }


def main() -> None:
    root = EVAL_ROOT / "results/deterministic_reference_v1"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, default=root / "raw/retrieval_ablation_raw.jsonl")
    parser.add_argument("--manifest", type=Path, default=root / "raw/run_manifest.json")
    parser.add_argument("--before", type=Path, default=root / "raw/retrieval_state_before.json")
    parser.add_argument("--after", type=Path, default=root / "raw/retrieval_state_after.json")
    parser.add_argument("--queries", type=Path, default=EVAL_ROOT / "datasets/retrieval/queries.jsonl")
    parser.add_argument("--memory-map", type=Path, default=EVAL_ROOT / "results/memory_id_map.json")
    parser.add_argument("--tables", type=Path, default=root / "tables")
    parser.add_argument("--report", type=Path,
                        default=EVAL_ROOT / "reports/deterministic-reference-v1.md")
    args = parser.parse_args()
    try:
        result = evaluate(args)
    except (EvaluationFailure, OSError, ValueError, KeyError, TypeError) as exc:
        parser.exit(1, f"REFERENCE EVALUATION FAILED: {exc}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
