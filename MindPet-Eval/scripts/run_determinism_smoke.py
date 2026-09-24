"""Run two fixed-clock 5-query x 2-mode passes and compare every ranked result."""

from __future__ import annotations

import argparse
import json
import math
import os
import urllib.request
from pathlib import Path
from typing import Any


QUERY_IDS = ["q001", "q010", "q020", "q030", "q036"]
MODES = ["rrf", "mindpet_full_rrf_norm"]
NUMERIC_FIELDS = ["rrfScore", "rrfNormalized", "timeScore", "finalScore"]


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def request(url: str, token: str, query: dict[str, Any], mode: str, as_of: str) -> dict[str, Any]:
    body = json.dumps({
        "query": query["query"], "mode": mode, "topK": 10,
        "userId": "eval_test_user", "asOf": as_of,
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json; charset=utf-8",
        "X-MindPet-Eval-Token": token,
    })
    with urllib.request.urlopen(req, timeout=120) as response:
        payload = json.loads(response.read().decode("utf-8"))
        if response.status != 200 or payload.get("status") != "OK":
            raise RuntimeError(f"{query['query_id']} {mode}: HTTP/status failure")
    return payload


def normalized(payload: dict[str, Any], reverse_map: dict[str, str]) -> list[dict[str, Any]]:
    results = []
    for item in payload["results"]:
        benchmark_id = reverse_map.get(str(item.get("memoryId")))
        if benchmark_id is None:
            raise RuntimeError(f"unmapped DB memory id: {item.get('memoryId')}")
        results.append({
            "benchmark_memory_id": benchmark_id,
            **{field: item.get(field) for field in NUMERIC_FIELDS},
        })
    return results


def equal_number(left: Any, right: Any) -> bool:
    if left is None or right is None:
        return left is right
    return math.isclose(float(left), float(right), rel_tol=1e-12, abs_tol=1e-12)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-url", default="http://127.0.0.1:8082/api/eval/memory/search")
    parser.add_argument("--as-of", required=True)
    parser.add_argument("--output", type=Path,
                        default=root / "results/determinism_clock_smoke/smoke_result.json")
    args = parser.parse_args()
    token = os.environ.get("MINDPET_EVAL_API_TOKEN")
    if not token:
        raise SystemExit("MINDPET_EVAL_API_TOKEN is required")
    all_queries = {q["query_id"]: q for q in read_jsonl(root / "datasets/retrieval/queries.jsonl")}
    memory_map = json.loads((root / "results/memory_id_map.json").read_text(encoding="utf-8"))
    reverse_map = {str(database_id): benchmark_id for benchmark_id, database_id in memory_map.items()}
    runs: dict[str, dict[str, list[dict[str, Any]]]] = {}
    for run_name in ("A", "B"):
        runs[run_name] = {}
        for query_id in QUERY_IDS:
            for mode in MODES:
                key = f"{query_id}/{mode}"
                runs[run_name][key] = normalized(
                    request(args.api_url, token, all_queries[query_id], mode, args.as_of),
                    reverse_map,
                )
    comparisons = []
    for key in runs["A"]:
        left = runs["A"][key]
        right = runs["B"][key]
        ranking_equal = [r["benchmark_memory_id"] for r in left] == [
            r["benchmark_memory_id"] for r in right
        ]
        numeric_equal = len(left) == len(right) and all(
            equal_number(a[field], b[field])
            for a, b in zip(left, right) for field in NUMERIC_FIELDS
        )
        comparisons.append({"query_mode": key, "ranking_equal": ranking_equal,
                            "numeric_equal": numeric_equal})
    passed = all(row["ranking_equal"] and row["numeric_equal"] for row in comparisons)
    result = {
        "clock_mode": "fixed", "evaluation_as_of": args.as_of,
        "queries": QUERY_IDS, "modes": MODES, "requests_per_run": 10,
        "total_requests": 20, "ranking_matches": sum(r["ranking_equal"] for r in comparisons),
        "numeric_matches": sum(r["numeric_equal"] for r in comparisons),
        "comparisons": comparisons, "passed": passed,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
