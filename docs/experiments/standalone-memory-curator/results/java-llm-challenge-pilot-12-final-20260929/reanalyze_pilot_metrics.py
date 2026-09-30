import json
import random
import sqlite3
import statistics
from collections import Counter, defaultdict
from pathlib import Path

RUN_DIR = Path(__file__).resolve().parent
EXPERIMENT_DIR = RUN_DIR.parents[1]
DATASET = EXPERIMENT_DIR / "data" / "memory-curator-value-300.jsonl"
DB = RUN_DIR / "memory-curator-value-experiment.sqlite"


def read_jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def norm(value):
    return " ".join(str(value or "").strip().lower().split())


def canonical_predicate(predicate, scope):
    value = norm(predicate)
    if norm(scope) == "historical" and value in {"current_location", "home_location"}:
        return "historical_location"
    return value


def canonical_assertion(assertion):
    value = norm(assertion)
    return "confirmed" if value == "observed" else value


def bootstrap(values, seed=7319, draws=10_000):
    if not values:
        return {"lower": None, "upper": None, "resamples": 0, "cluster_count": 0}
    rng = random.Random(seed)
    means = sorted(statistics.mean(rng.choice(values) for _ in values) for _ in range(draws))
    return {"lower": means[249], "upper": means[9749], "resamples": draws, "cluster_count": len(values)}


def mean_metric(rows, group, key):
    values = [row[group].get(key) for row in rows]
    values = [value for value in values if isinstance(value, (int, float))]
    return statistics.mean(values) if values else None


def paired_deltas(rows, metric, budget=None):
    per_group = {"g1": defaultdict(list), "g2": defaultdict(list)}
    for row in rows:
        for group in per_group:
            score = row[group]
            value = score.get(metric) if budget is None else score.get("fixed_token_recall", {}).get(budget)
            if isinstance(value, (int, float)):
                per_group[group][row["timeline_id"]].append(float(value))
    ids = sorted(set(per_group["g1"]) & set(per_group["g2"]))
    values = [statistics.mean(per_group["g2"][uid]) - statistics.mean(per_group["g1"][uid]) for uid in ids]
    return statistics.mean(values) if values else None, bootstrap(values)


def fact_quality(dataset_by_id, sample_ids):
    connection = sqlite3.connect(f"file:{DB.as_posix()}?mode=ro", uri=True, timeout=5)
    total = Counter()
    per_user = []
    by_scenario = defaultdict(Counter)
    for timeline_id in sample_ids:
        sample = dataset_by_id[timeline_id]
        actual = connection.execute(
            "SELECT predicate,value_text,scope,assertion,normalized_start,time_status FROM memory_fact WHERE user_id=? ORDER BY id",
            ("g2-" + timeline_id,),
        ).fetchall()
        expected = [fact for fact in sample["facts"] if fact.get("should_store", True)]
        matched = set()
        tp = fp = 0
        for row in actual:
            predicate, value, scope, assertion, start, time_status = row
            match = None
            for fact in expected:
                if canonical_predicate(predicate, scope) != canonical_predicate(fact["predicate"], fact["scope"]):
                    continue
                if norm(value) != norm(fact["normalized_value"]):
                    continue
                if norm(scope) != norm(fact["scope"]):
                    continue
                if canonical_assertion(assertion) != canonical_assertion(fact["assertion"]):
                    continue
                expected_status = str(fact.get("time_status") or "")
                actual_status = str(time_status or "")
                if expected_status and expected_status != actual_status:
                    continue
                if expected_status == "resolved" and str(fact.get("valid_from") or "")[:10] != str(start or "")[:10]:
                    continue
                if expected_status == "ambiguous" and str(start or ""):
                    continue
                match = fact["gold_fact_id"]
                break
            if match is None:
                fp += 1
            elif match not in matched:
                matched.add(match)
                tp += 1
            else:
                fp += 1
        fn = sum(1 for fact in expected if fact["gold_fact_id"] not in matched)
        total.update(tp=tp, fp=fp, fn=fn)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_user.append({"timeline_id": timeline_id, "precision": precision, "recall": recall, "f1": f1,
                         "tp": tp, "fp": fp, "fn": fn, "expected": len(expected), "stored": len(actual)})
        by_scenario[sample["primary_scenario"]].update(tp=tp, fp=fp, fn=fn)
    connection.close()
    micro_precision = total["tp"] / (total["tp"] + total["fp"]) if total["tp"] + total["fp"] else 0.0
    micro_recall = total["tp"] / (total["tp"] + total["fn"]) if total["tp"] + total["fn"] else 0.0
    micro_f1 = 2 * micro_precision * micro_recall / (micro_precision + micro_recall) if micro_precision + micro_recall else 0.0
    macro = {key: statistics.mean(row[key] for row in per_user) for key in ("precision", "recall", "f1")}
    ci = {key: bootstrap([row[key] for row in per_user]) for key in ("precision", "recall", "f1")}
    return {"micro": {"true_positive": total["tp"], "false_positive": total["fp"], "false_negative": total["fn"],
                       "precision": micro_precision, "recall": micro_recall, "f1": micro_f1},
            "macro_by_timeline": macro, "macro_user_cluster_bootstrap_95_ci": ci,
            "per_scenario": {scenario: dict(counts) for scenario, counts in by_scenario.items()},
            "timeline_count": len(sample_ids)}


def main():
    dataset_by_id = {row["timeline_id"]: row for row in read_jsonl(DATASET)}
    retrieval_rows = read_jsonl(RUN_DIR / "retrieval-results.jsonl")
    answers = read_jsonl(RUN_DIR / "qa-responses.jsonl")
    config = json.loads((RUN_DIR / "run-config.json").read_text(encoding="utf-8"))
    old_metrics = json.loads((RUN_DIR / "metrics.json").read_text(encoding="utf-8"))
    sample_ids = config["sample_ids"]
    retrieval = {}
    for output_group, source_group in (("G1", "g1"), ("G2", "g2")):
        retrieval[output_group] = {
            key: mean_metric(retrieval_rows, source_group, key)
            for key in ("recall_at_1", "recall_at_3", "recall_at_5", "recall_at_10", "mrr", "ndcg_at_10")
        }
        retrieval[output_group].update({
            "recall_at_256_estimated_tokens": statistics.mean(
                row[source_group]["fixed_token_recall"]["256"] for row in retrieval_rows
                if isinstance(row[source_group].get("fixed_token_recall", {}).get("256"), (int, float))),
            "recall_at_512_estimated_tokens": statistics.mean(
                row[source_group]["fixed_token_recall"]["512"] for row in retrieval_rows
                if isinstance(row[source_group].get("fixed_token_recall", {}).get("512"), (int, float))),
            "recall_at_1024_estimated_tokens": statistics.mean(
                row[source_group]["fixed_token_recall"]["1024"] for row in retrieval_rows
                if isinstance(row[source_group].get("fixed_token_recall", {}).get("1024"), (int, float))),
        })
    delta_r5, ci_r5 = paired_deltas(retrieval_rows, "recall_at_5")
    delta_512, ci_512 = paired_deltas(retrieval_rows, "", "512")
    retrieval_result = {"query_count": len(retrieval_rows), "by_group": retrieval,
                        "delta_recall_at_5_g2_minus_g1": delta_r5,
                        "delta_recall_at_5_user_cluster_bootstrap_95_ci": ci_r5,
                        "delta_recall_at_512_estimated_tokens_g2_minus_g1": delta_512,
                        "delta_recall_at_512_user_cluster_bootstrap_95_ci": ci_512}

    qa_by_group = {}
    per_user_answers = defaultdict(lambda: {"G1": [], "G2": []})
    for group in ("G1", "G2"):
        selected = [row for row in answers if row["group"] == group]
        correct = sum(row.get("correct_by_frozen_rules") is True for row in selected)
        abstentions = [row for row in selected if row.get("should_abstain")]
        abstain_correct = sum(row.get("correct_by_frozen_rules") is True for row in abstentions)
        qa_by_group[group] = {"correct": correct, "total": len(selected), "accuracy": correct / len(selected) if selected else None,
                              "abstention_correct": abstain_correct, "abstention_total": len(abstentions),
                              "abstention_accuracy": abstain_correct / len(abstentions) if abstentions else None,
                              "mean_context_token_estimate": statistics.mean(row["context_token_estimate"] for row in selected) if selected else None}
    for row in answers:
        per_user_answers[row["timeline_id"]][row["group"]].append(1.0 if row.get("correct_by_frozen_rules") is True else 0.0)
    answer_deltas = [statistics.mean(values["G2"]) - statistics.mean(values["G1"])
                     for values in per_user_answers.values() if values["G1"] and values["G2"]]
    qa_result = {"scoring": "frozen deterministic string/state/abstention rules; no LLM judge",
                 "by_group": qa_by_group,
                 "delta_accuracy_g2_minus_g1": statistics.mean(answer_deltas) if answer_deltas else None,
                 "delta_accuracy_user_cluster_bootstrap_95_ci": bootstrap(answer_deltas)}

    units = {}
    for filename, label in (("memory-units-before.jsonl", "G1"), ("memory-units-after.jsonl", "G2")):
        rows = read_jsonl(RUN_DIR / filename)
        units[label] = {"unit_count": len(rows), "estimated_tokens": sum(row.get("estimated_tokens", 0) for row in rows)}
    raw_failures = read_jsonl(RUN_DIR / "failures.jsonl")
    reasons = Counter(row.get("reason_code", "unknown") for row in raw_failures)
    usage = old_metrics["model_calls"]
    result = {
        "result_type": "exploratory_real_llm_pilot_reanalysis",
        "official_locked_test_result": False,
        "dataset_sha256": config.get("dataset_sha256"),
        "model": config.get("model"),
        "temperature": 0.0,
        "timeline_count": len(sample_ids),
        "scenario_count": len(set(dataset_by_id[uid]["primary_scenario"] for uid in sample_ids)),
        "turn_count": sum(len(dataset_by_id[uid]["turns"]) for uid in sample_ids),
        "query_count": len(retrieval_rows),
        "answer_query_count": len({row["query_id"] for row in answers}),
        "fact_quality": fact_quality(dataset_by_id, sample_ids),
        "profile_quality": {
            key: old_metrics["profile_quality"].get(key)
            for key in ("precision", "recall", "expected_entries_correct", "expected_entries",
                        "written_entries", "fully_correct_timelines")
        },
        "paired_retrieval": retrieval_result,
        "paired_answers": qa_result,
        "search_corpus": {"G1": units["G1"], "G2": units["G2"],
                           "g2_token_delta_fraction_vs_g1": units["G2"]["estimated_tokens"] / units["G1"]["estimated_tokens"] - 1},
        "safety": old_metrics.get("sensitive_content"),
        "source_evidence": old_metrics.get("source_evidence"),
        "temporal": old_metrics.get("temporal"),
        "model_calls": usage,
        "incomplete_curator_timelines": {"count": len(raw_failures), "by_reason": dict(reasons)},
        "limits": [
            "Only 12 language-challenge timelines were sampled, one per scenario; this is an exploratory pilot, not the locked-test result.",
            "This run predates the separate-database runner refactor. G1 and G2 used distinct user namespaces in one isolated SQLite file.",
            "The original metrics.json has a G1/G2 casing bug in retrieval aggregation and treated observed/confirmed plus historical location slot names as exact mismatches; corrected values above are recomputed from the raw retrieval files and database.",
            "Answer scores use frozen deterministic rules and have no blind human adjudication.",
            "The subsequent 60-timeline run stopped during baseline ingestion after the provider returned HTTP 402 Insufficient Balance."
        ]
    }
    destination = RUN_DIR / "pilot-reanalysis.json"
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"path": str(destination), "retrieval": retrieval_result,
                      "answers": qa_result["by_group"], "facts": result["fact_quality"]["micro"],
                      "failures": result["incomplete_curator_timelines"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
