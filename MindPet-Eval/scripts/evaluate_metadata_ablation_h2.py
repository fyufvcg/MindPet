"""Validate the frozen H2 run, enforce RRF sanity, and report metadata effects."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from evaluate_retrieval import (
    EVAL_ROOT, KS, METRIC_COLUMNS, QUERY_TYPES, EvaluationFailure, formatted_row,
    metric_row, no_answer_rows, rank_comparison_rows, read_json, read_jsonl,
    validate_run, write_csv, write_text, markdown_table,
)


MODES = [
    "rrf",
    "mindpet_rrf_norm_only",
    "mindpet_rrf_norm_time",
    "mindpet_rrf_norm_importance",
    "mindpet_rrf_norm_importance_bonus",
    "mindpet_full_rrf_norm",
]
PAIRS = [
    ("rrf", "mindpet_rrf_norm_only", "rrf_vs_norm_only.csv"),
    ("mindpet_rrf_norm_only", "mindpet_rrf_norm_time", "norm_only_vs_time.csv"),
    ("mindpet_rrf_norm_only", "mindpet_rrf_norm_importance", "norm_only_vs_importance.csv"),
    ("mindpet_rrf_norm_importance", "mindpet_rrf_norm_importance_bonus", "importance_vs_bonus.csv"),
    ("mindpet_rrf_norm_only", "mindpet_full_rrf_norm", "norm_only_vs_full_v2.csv"),
]
COMPARISON_COLUMNS = [
    "query_id", "query", "query_type", "difficulty", "relevant_memory_ids",
    "reference_mode", "reference_first_relevant_rank", "candidate_mode",
    "candidate_first_relevant_rank", "rank_delta", "classification",
]
COMPONENTS = [
    "rrfNormalized", "timeScore", "importanceContribution",
    "highImportanceBonus", "confidenceContribution", "finalScore",
]


def validate_h2(raw: list[dict], queries: list[dict], manifest: dict, before: dict,
                after: dict, id_map: dict[str, int]) -> dict:
    if manifest.get("modes") != MODES or manifest.get("git_commit") is None:
        raise EvaluationFailure("H2 manifest modes/commit are invalid")
    if manifest.get("database") != "mindpet_eval" or manifest.get("user_id") != "eval_test_user":
        raise EvaluationFailure("H2 manifest database/user is unsafe")
    if manifest.get("benchmark_version") != "Retrieval Benchmark v1":
        raise EvaluationFailure("benchmark version mismatch")
    if (manifest.get("memory_count"), manifest.get("query_count"),
        manifest.get("mode_count"), manifest.get("formal_requests"),
        manifest.get("topK"), manifest.get("embedding_model"),
        manifest.get("embedding_dimension"), manifest.get("metric_K")) != (
            120, 40, 6, 240, 10, "bge-m3", 1024, KS):
        raise EvaluationFailure("H2 manifest benchmark/request contract mismatch")
    validation = validate_run(raw, queries, id_map, manifest, before, after, MODES)
    by_pair = {(row["query_id"], row["mode"]): row for row in raw}
    mismatches = []
    for query in queries:
        query_id = query["query_id"]
        left = [r["benchmark_memory_id"] for r in by_pair[(query_id, MODES[0])]["results"]]
        right = [r["benchmark_memory_id"] for r in by_pair[(query_id, MODES[1])]["results"]]
        if left != right:
            mismatches.append(query_id)
    if mismatches:
        raise EvaluationFailure("RRF vs normalized-only ranking mismatch: " + ", ".join(mismatches))
    for row in raw:
        if row["mode"] == "rrf":
            continue
        for result in row["results"]:
            normalized = result["rrfNormalized"]
            if not isinstance(normalized, (int, float)) or not 0 <= normalized <= 1:
                raise EvaluationFailure(f"{row['query_id']} {row['mode']}: invalid normalized RRF")
            if not isinstance(result["finalScore"], (int, float)):
                raise EvaluationFailure(f"{row['query_id']} {row['mode']}: missing final score")
            if str(result.get("memoryId")) != str(result.get("db_memory_id")):
                raise EvaluationFailure(f"{row['query_id']} {row['mode']}: memoryId mismatch")
    validation["rrf_norm_only_identical_queries"] = 40
    return validation


def contribution_examples(raw: list[dict], comparisons: dict[str, tuple[list[dict], dict]]) -> list[dict]:
    by_pair = {(row["query_id"], row["mode"]): row for row in raw}
    examples = []
    for label, (rows, _summary) in comparisons.items():
        if label == "rrf_vs_norm_only.csv":
            continue
        changed = [row for row in rows if row["classification"] != "unchanged"]
        chosen = [row for row in changed if row["classification"] == "improved"][:2]
        chosen += [row for row in changed if row["classification"] == "degraded"][:2]
        for row in chosen:
            query_id = row["query_id"]
            reference = by_pair[(query_id, row["reference_mode"])]
            candidate = by_pair[(query_id, row["candidate_mode"])]
            relevant_ids = set(reference["relevant_memory_ids"])
            correct = next((r for r in candidate["results"] if r["benchmark_memory_id"] in relevant_ids), None)
            if correct is None:
                correct = next((r for r in reference["results"] if r["benchmark_memory_id"] in relevant_ids), None)
            if correct is None:
                continue
            reference_rank_by_id = {r["benchmark_memory_id"]: r["rank"]
                                    for r in reference["results"]}
            wrong = next((r for r in candidate["results"]
                          if r["benchmark_memory_id"] not in relevant_ids
                          and r["rank"] < row["candidate_first_relevant_rank"]
                          and reference_rank_by_id.get(r["benchmark_memory_id"], 11)
                          > row["reference_first_relevant_rank"]), None)
            if wrong is None:
                wrong = next((r for r in reference["results"] if r["benchmark_memory_id"] not in relevant_ids
                              and r["rank"] < row["reference_first_relevant_rank"]), None)
            for role, memory in (("correct", correct), ("wrong_above", wrong)):
                if memory is None:
                    continue
                examples.append({
                    "comparison": label.removesuffix(".csv"), "query_id": query_id,
                    "query_type": row["query_type"], "classification": row["classification"],
                    "reference_rank": row["reference_first_relevant_rank"],
                    "candidate_rank": row["candidate_first_relevant_rank"],
                    "role": role, "benchmark_memory_id": memory["benchmark_memory_id"],
                    "content": memory["content"], "rank": memory["rank"],
                    **{field: memory.get(field) for field in COMPONENTS},
                })
    return examples


def h1_rrf_drift(h1_raw: list[dict], h2_raw: list[dict]) -> dict:
    old = {row["query_id"]: row for row in h1_raw if row["mode"] == "rrf"}
    current = {row["query_id"]: row for row in h2_raw if row["mode"] == "rrf"}
    if len(old) != 40 or len(current) != 40 or set(old) != set(current):
        raise EvaluationFailure("H1/H2 RRF query sets differ")
    changed = sum(
        [item["benchmark_memory_id"] for item in old[qid]["results"]]
        != [item["benchmark_memory_id"] for item in current[qid]["results"]]
        for qid in old
    )
    h1_core = [row for row in old.values() if row["query_type"] != "no_answer"]
    h2_core = [row for row in current.values() if row["query_type"] != "no_answer"]
    return {
        "changed_rrf_sequences": changed,
        "h1_rrf_mrr_at_10": metric_row(h1_core)["MRR@10"],
        "h2_rrf_mrr_at_10": metric_row(h2_core)["MRR@10"],
    }


def full_sequence_changes(raw: list[dict], reference: str, candidate: str) -> int:
    by_pair = {(row["query_id"], row["mode"]): row for row in raw}
    return sum(
        [r["benchmark_memory_id"] for r in by_pair[(f"q{index:03d}", reference)]["results"]]
        != [r["benchmark_memory_id"] for r in by_pair[(f"q{index:03d}", candidate)]["results"]]
        for index in range(1, 41)
    )


def render_report(manifest: dict, validation: dict, overall: list[dict], by_type: list[dict],
                  diagnostics: list[dict], comparisons: dict, examples: list[dict], drift: dict,
                  sequence_counts: dict[str, int]) -> str:
    by_mode = {row["mode"]: row for row in overall}
    example_lines = markdown_table(examples, [
        "comparison", "query_id", "classification", "role", "benchmark_memory_id",
        "rank", *COMPONENTS,
    ]) if examples else ["No rank-changing examples."]
    lines = [
        "# H2 Metadata Reranking Ablation",
        "",
        "## Scope and validation",
        "",
        f"- Git commit: `{manifest['git_commit']}`; database: `mindpet_eval`; user: `eval_test_user`.",
        f"- Benchmark: Retrieval Benchmark v1, 120 memories, 40 queries, bge-m3 1024 dims.",
        f"- Formal requests: {validation['raw_rows']}; HTTP 200/status OK: 240/240; answerable: 36; no-answer: 4.",
        "- Before/after: 120/120; access_count changes: 0; last_accessed changes: 0.",
        "- Sanity: all 40 RRF and normalized-only benchmark-ID ranking sequences match exactly.",
        "- This is a reranking-level ablation; importance remains in upstream retention, layer, and candidate selection.",
        "",
        "## Overall ranking metrics (36 answerable queries)",
        "",
        *markdown_table(overall, ["mode", "Precision@1", "Recall@10", "MRR@10", "nDCG@10"]),
        "",
        "## Pairwise effects",
        "",
        "| comparison | improved | unchanged | degraded | avg rank delta | ΔP@1 | ΔMRR@10 | ΔnDCG@10 | full Top10 sequences changed / 40 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for label, (rows, summary) in comparisons.items():
        reference, candidate, _ = next(pair for pair in PAIRS if pair[2] == label)
        deltas = [float(by_mode[candidate][metric]) - float(by_mode[reference][metric])
                  for metric in ("Precision@1", "MRR@10", "nDCG@10")]
        lines.append(f"| {reference} → {candidate} | {summary['improved']} | {summary['unchanged']} | "
                     f"{summary['degraded']} | {summary['avg_rank_delta']:+.6f} | "
                     + " | ".join(f"{value:+.6f}" for value in deltas)
                     + f" | {sequence_counts[label]} |")
    lines += ["", "## Query-type metrics", "", *markdown_table(
        by_type, ["query_type", "mode", "query_count", "Precision@1", "MRR@10", "nDCG@10"]),
        "", "## No-answer diagnostics", "",
        "No reject threshold exists; returned-count/empty-result statistics are diagnostic, not no-answer accuracy.",
        "", *markdown_table(diagnostics, ["mode", "no_answer_query_count", "empty_result_count@10",
                                           "empty_result_rate@10", "avg_returned_count@10"]),
        "", "## Contribution-level examples", "",
        "Each row shows a real result's debug components. Values not in a mode's final formula are observations, not contributions to that score.",
        "", *example_lines,
        "", "## Cross-run baseline drift", "",
        f"- H1 RRF MRR@10: {drift['h1_rrf_mrr_at_10']:.6f}; H2 RRF MRR@10: {drift['h2_rrf_mrr_at_10']:.6f}.",
        f"- RRF Top10 ranking sequence changed in {drift['changed_rrf_sequences']}/40 queries between H1 and H2.",
        "- H1 and H2 are not directly comparable as a fixed candidate-pool experiment: the existing retention SQL uses NOW(), while benchmark timestamps are fixed.",
        "- Interpret only the six same-window H2 comparisons as metadata reranking effects; do not attribute the cross-run RRF drop to metadata.",
        "", "## Interpretation and limitations", "",
    ]
    for label, (rows, summary) in comparisons.items():
        if label == "rrf_vs_norm_only.csv":
            continue
        reference, candidate, _ = next(pair for pair in PAIRS if pair[2] == label)
        delta = float(by_mode[candidate]["MRR@10"]) - float(by_mode[reference]["MRR@10"])
        direction = "net positive" if delta > 1e-12 else "net negative" if delta < -1e-12 else "no net change"
        lines.append(f"- {reference} → {candidate}: {direction} on MRR@10 ({delta:+.6f}); "
                     f"improved={summary['improved']}, degraded={summary['degraded']}.")
    lines += [
        "- The bonus changed full Top10 sequences in 15/40 queries, but changed no first relevant rank among the 36 answerable queries. For example, in q001, irrelevant m065/m025 rose above irrelevant m115; relevant m001 stayed rank 1. Thus this run shows no relevance gain or loss from the bonus, not no ranking effect.",
        "- No time, direct-importance, or bonus improvement of first relevant rank was observed in this run; do not invent an improvement case.",
        "- These within-benchmark comparisons do not isolate upstream importance effects or establish AI-generated importance accuracy.",
        "- The direct-importance harm motivates a separate reliability study, but these labels do not measure whether AI assigned importance accurately; that hypothesis remains untested.",
        "- Benchmark v1 was used during development and error discovery, so this is not independent generalization evidence.",
        "- No Java retrieval algorithm, benchmark, database memory, or score weight was changed during the formal run.",
        "",
    ]
    return "\n".join(lines)


def evaluate(args: argparse.Namespace) -> dict:
    raw = read_jsonl(args.raw)
    queries = read_jsonl(args.queries)
    manifest = read_json(args.manifest)
    before = read_json(args.before)
    after = read_json(args.after)
    id_map = {str(k): int(v) for k, v in read_json(args.memory_map).items()}
    validation = validate_h2(raw, queries, manifest, before, after, id_map)
    drift = h1_rrf_drift(read_jsonl(args.h1_raw), raw)
    core = [row for row in raw if row["query_type"] != "no_answer"]
    overall = [formatted_row({"mode": mode}, metric_row([row for row in core if row["mode"] == mode]))
               for mode in MODES]
    by_type = [formatted_row({"query_type": query_type, "mode": mode, "query_count": len(rows)},
                             metric_row(rows))
               for query_type in QUERY_TYPES for mode in MODES
               for rows in [[row for row in core if row["query_type"] == query_type and row["mode"] == mode]]]
    diagnostics = no_answer_rows(raw, MODES)
    comparisons = {label: rank_comparison_rows(raw, queries, reference, candidate)
                   for reference, candidate, label in PAIRS}
    sequence_counts = {label: full_sequence_changes(raw, reference, candidate)
                       for reference, candidate, label in PAIRS}
    if comparisons["rrf_vs_norm_only.csv"][1] != {
        "improved": 0, "unchanged": 36, "degraded": 0, "avg_rank_delta": 0
    }:
        raise EvaluationFailure("RRF/normalized-only rank sanity failed")
    examples = contribution_examples(raw, comparisons)
    args.tables.mkdir(parents=True, exist_ok=True)
    write_csv(args.tables / "retrieval_metrics.csv", ["mode", *METRIC_COLUMNS], overall)
    write_csv(args.tables / "retrieval_metrics_by_type.csv",
              ["query_type", "mode", "query_count", *METRIC_COLUMNS], by_type)
    write_csv(args.tables / "no_answer_diagnostics.csv", ["mode", "no_answer_query_count"] + [
        name for k in KS for name in (f"empty_result_count@{k}", f"empty_result_rate@{k}",
                                      f"avg_returned_count@{k}")], diagnostics)
    for label, (rows, _summary) in comparisons.items():
        write_csv(args.tables / label, COMPARISON_COLUMNS, rows)
    if examples:
        write_csv(args.tables / "contribution_cases.csv", list(examples[0]), examples)
    write_text(args.report, render_report(manifest, validation, overall, by_type,
                                          diagnostics, comparisons, examples, drift, sequence_counts))
    return {"validation": validation, "overall": overall,
            "comparisons": {key: value[1] for key, value in comparisons.items()},
            "cross_run_drift": drift, "full_sequence_changes": sequence_counts}


def main() -> None:
    root = EVAL_ROOT / "results/metadata_ablation_h2"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, default=root / "raw/retrieval_ablation_raw.jsonl")
    parser.add_argument("--manifest", type=Path, default=root / "raw/run_manifest.json")
    parser.add_argument("--before", type=Path, default=root / "raw/retrieval_state_before.json")
    parser.add_argument("--after", type=Path, default=root / "raw/retrieval_state_after.json")
    parser.add_argument("--queries", type=Path, default=EVAL_ROOT / "datasets/retrieval/queries.jsonl")
    parser.add_argument("--memory-map", type=Path, default=EVAL_ROOT / "results/memory_id_map.json")
    parser.add_argument("--tables", type=Path, default=root / "tables")
    parser.add_argument("--report", type=Path, default=EVAL_ROOT / "reports/metadata-reranking-ablation-h2.md")
    parser.add_argument("--h1-raw", type=Path,
                        default=EVAL_ROOT / "results/rrf_norm_v2/raw/retrieval_ablation_raw.jsonl")
    args = parser.parse_args()
    try:
        result = evaluate(args)
    except (EvaluationFailure, OSError, ValueError, KeyError, TypeError) as exc:
        parser.exit(1, f"H2 EVALUATION FAILED: {exc}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
