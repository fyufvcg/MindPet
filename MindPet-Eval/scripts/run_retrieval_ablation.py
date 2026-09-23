"""Run one read-only MindPet retrieval ablation over the frozen 40-query benchmark."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_MODES = ["keyword_only", "vector_only", "rrf", "mindpet_full"]
SUPPORTED_MODES = [*DEFAULT_MODES, "mindpet_full_rrf_norm",
    "mindpet_rrf_norm_only", "mindpet_rrf_norm_time",
    "mindpet_rrf_norm_importance", "mindpet_rrf_norm_importance_bonus"]
TOP_K = 10
USER_ID = "eval_test_user"
DATABASE = "mindpet_eval"
EXPECTED_MEMORY_COUNT = 120
EXPECTED_QUERY_COUNT = 40
RESULT_FIELDS = [
    "vectorRank", "keywordRank", "keywordScore", "distance", "rrfScore",
    "rrfNormalized",
    "importance", "confidence", "layer", "emotion", "createdAt", "timeScore",
    "importanceContribution", "confidenceContribution", "highImportanceBonus",
    "finalScore",
]


class RunFailure(RuntimeError):
    """Raised when a run cannot be treated as a complete formal experiment."""


def now_utc() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
        records = [json.loads(line) for line in lines if line.strip()]
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RunFailure(f"cannot read JSONL {path}: {exc}") from exc
    if len(records) != len(lines) or not all(isinstance(row, dict) for row in records):
        raise RunFailure(f"invalid or blank JSONL record in {path}")
    return records


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        temporary.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as handle:
            for record in records:
                handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def import_psycopg() -> Any:
    try:
        import psycopg  # type: ignore
    except ImportError as exc:
        raise RunFailure("psycopg is required by MindPet-Eval/requirements.txt") from exc
    return psycopg


def connect_database(args: argparse.Namespace) -> Any:
    if args.database != DATABASE:
        raise RunFailure(f"database must be exactly {DATABASE}")
    password = os.environ.get("MINDPET_EVAL_DB_PASSWORD")
    user = os.environ.get("MINDPET_EVAL_DB_USER")
    if not user or not password:
        raise RunFailure("MINDPET_EVAL_DB_USER and MINDPET_EVAL_DB_PASSWORD are required")
    psycopg = import_psycopg()
    conn = psycopg.connect(
        host=args.db_host,
        port=args.db_port,
        dbname=args.database,
        user=user,
        password=password,
        connect_timeout=args.connect_timeout,
    )
    with conn.cursor() as cursor:
        cursor.execute("SELECT current_database()")
        actual = cursor.fetchone()[0]
    if actual != DATABASE:
        conn.close()
        raise RunFailure(f"SAFETY STOP: current_database()={actual!r}, expected {DATABASE!r}")
    return conn


def fetch_state(conn: Any, id_map: dict[str, int]) -> tuple[dict[str, Any], str]:
    with conn.cursor() as cursor:
        cursor.execute(
            "SELECT id, access_count, last_accessed FROM public.long_term_memory "
            "WHERE user_id=%s ORDER BY id",
            (USER_ID,),
        )
        rows = cursor.fetchall()
        cursor.execute(
            "SELECT COUNT(DISTINCT metadata->>'benchmark_base_time'), "
            "MIN(metadata->>'benchmark_base_time') FROM public.long_term_memory "
            "WHERE user_id=%s",
            (USER_ID,),
        )
        base_count, benchmark_base_time = cursor.fetchone()
    if len(rows) != EXPECTED_MEMORY_COUNT:
        raise RunFailure(f"database memory count={len(rows)}, expected 120")
    database_ids = {int(row[0]) for row in rows}
    if database_ids != set(id_map.values()):
        raise RunFailure("database ids do not exactly match memory_id_map.json")
    if base_count != 1 or not benchmark_base_time:
        raise RunFailure("benchmark_base_time is missing or inconsistent in metadata")
    reverse_map = {int(value): key for key, value in id_map.items()}
    state = {
        "database": DATABASE,
        "user_id": USER_ID,
        "captured_at": now_utc(),
        "rows": [
            {
                "id": int(memory_id),
                "benchmark_memory_id": reverse_map[int(memory_id)],
                "access_count": int(access_count or 0),
                "last_accessed": last_accessed.isoformat() if last_accessed else None,
            }
            for memory_id, access_count, last_accessed in rows
        ],
    }
    return state, benchmark_base_time


def comparable_state(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    return snapshot["rows"]


def post_search(
    url: str, token: str, query: dict[str, Any], mode: str, timeout: float
) -> tuple[int, dict[str, Any]]:
    body = json.dumps(
        {"query": query["query"], "mode": mode, "topK": TOP_K, "userId": USER_ID},
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "X-MindPet-Eval-Token": token,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read().decode("utf-8"))
        except Exception:
            payload = {"status": "FAILED", "code": "NON_JSON_HTTP_ERROR"}
        raise RunFailure(
            f"{query['query_id']} {mode}: HTTP {exc.code}, code={payload.get('code')}"
        ) from exc
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RunFailure(f"{query['query_id']} {mode}: request/JSON failure: {exc}") from exc
    if not isinstance(payload, dict):
        raise RunFailure(f"{query['query_id']} {mode}: response is not an object")
    return status, payload


def normalize_response(
    query: dict[str, Any], mode: str, http_status: int, payload: dict[str, Any],
    reverse_map: dict[str, str],
) -> dict[str, Any]:
    if http_status != 200 or payload.get("status") != "OK":
        raise RunFailure(
            f"{query['query_id']} {mode}: HTTP={http_status}, status={payload.get('status')}, "
            f"code={payload.get('code')}"
        )
    if payload.get("mode") != mode or payload.get("topK") != TOP_K:
        raise RunFailure(f"{query['query_id']} {mode}: response mode/topK mismatch")
    api_results = payload.get("results")
    if not isinstance(api_results, list) or len(api_results) > TOP_K:
        raise RunFailure(f"{query['query_id']} {mode}: invalid result list")
    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for rank, result in enumerate(api_results, start=1):
        if not isinstance(result, dict):
            raise RunFailure(f"{query['query_id']} {mode}: result {rank} is not an object")
        database_id = str(result.get("memoryId"))
        benchmark_id = reverse_map.get(database_id)
        if benchmark_id is None:
            raise RunFailure(
                f"{query['query_id']} {mode}: DB id {database_id!r} has no benchmark mapping"
            )
        if benchmark_id in seen:
            raise RunFailure(f"{query['query_id']} {mode}: duplicate result {benchmark_id}")
        seen.add(benchmark_id)
        entry = {
            "rank": rank,
            "benchmark_memory_id": benchmark_id,
            "memoryId": database_id,
            "db_memory_id": database_id,
            "content": result.get("content"),
        }
        entry.update({field: result.get(field) for field in RESULT_FIELDS})
        normalized.append(entry)
    return {
        "query_id": query["query_id"],
        "query": query["query"],
        "query_type": query["query_type"],
        "difficulty": query["difficulty"],
        "relevant_memory_ids": query["relevant_memory_ids"],
        "mode": mode,
        "topK": TOP_K,
        "http_status": http_status,
        "status": payload["status"],
        "results": normalized,
    }


def validate_inputs(queries: list[dict[str, Any]], id_map: dict[str, int]) -> None:
    expected_query_ids = [f"q{index:03d}" for index in range(1, 41)]
    if len(queries) != EXPECTED_QUERY_COUNT or [q.get("query_id") for q in queries] != expected_query_ids:
        raise RunFailure("queries must be exactly q001..q040")
    if len(id_map) != EXPECTED_MEMORY_COUNT:
        raise RunFailure(f"memory_id_map count={len(id_map)}, expected 120")
    expected_memory_ids = {f"m{index:03d}" for index in range(1, 121)}
    if set(id_map) != expected_memory_ids or len(set(id_map.values())) != EXPECTED_MEMORY_COUNT:
        raise RunFailure("memory_id_map must uniquely map m001..m120")
    if sum(q.get("query_type") == "no_answer" for q in queries) != 4:
        raise RunFailure("expected exactly four no_answer queries")


def git_commit(project_root: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(project_root), "rev-parse", "HEAD"],
            text=True,
            encoding="utf-8",
        ).strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise RunFailure(f"cannot determine git commit: {exc}") from exc


def ensure_outputs_available(paths: list[Path], overwrite: bool) -> None:
    existing = [str(path) for path in paths if path.exists()]
    if existing and not overwrite:
        raise RunFailure(f"output files already exist; use --overwrite: {existing}")


def run(args: argparse.Namespace) -> dict[str, Any]:
    token = os.environ.get("MINDPET_EVAL_API_TOKEN")
    if not token:
        raise RunFailure("MINDPET_EVAL_API_TOKEN is required")
    queries = read_jsonl(args.queries)
    try:
        raw_map = json.loads(args.memory_map.read_text(encoding="utf-8"))
        id_map = {str(key): int(value) for key, value in raw_map.items()}
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError) as exc:
        raise RunFailure(f"cannot read memory map: {exc}") from exc
    validate_inputs(queries, id_map)
    modes = list(args.modes)
    if not modes or len(modes) != len(set(modes)) or any(mode not in SUPPORTED_MODES for mode in modes):
        raise RunFailure(f"modes must be unique supported values: {SUPPORTED_MODES}")
    expected_request_count = EXPECTED_QUERY_COUNT * len(modes)
    reverse_map = {str(value): key for key, value in id_map.items()}

    output_paths = [args.raw_output, args.manifest, args.before_state, args.after_state]
    ensure_outputs_available(output_paths, args.overwrite)
    project_root = Path(__file__).resolve().parents[2]
    commit = git_commit(project_root)

    conn = connect_database(args)
    try:
        before, benchmark_base_time = fetch_state(conn, id_map)
        write_json(args.before_state, before)
        run_started_at = now_utc()
        records: list[dict[str, Any]] = []
        for mode in modes:
            for index, query in enumerate(queries, start=1):
                http_status, payload = post_search(
                    args.api_url, token, query, mode, args.http_timeout
                )
                records.append(
                    normalize_response(query, mode, http_status, payload, reverse_map)
                )
                if index == 1 or index % 10 == 0 or index == len(queries):
                    print(
                        f"request progress: mode={mode} query={index}/40 "
                        f"total={len(records)}/{expected_request_count}", flush=True
                    )

        if len(records) != expected_request_count:
            raise RunFailure(
                f"raw record count={len(records)}, expected {expected_request_count}"
            )
        mode_counts = Counter(record["mode"] for record in records)
        if mode_counts != Counter({mode: 40 for mode in modes}):
            raise RunFailure(f"mode counts are invalid: {dict(mode_counts)}")

        after, after_base_time = fetch_state(conn, id_map)
        write_json(args.after_state, after)
        if after_base_time != benchmark_base_time:
            raise RunFailure("benchmark_base_time changed during the run")
        if comparable_state(before) != comparable_state(after):
            raise RunFailure("access_count or last_accessed changed during read-only experiment")

        write_jsonl(args.raw_output, records)
        run_finished_at = now_utc()
        manifest = {
            "experiment": args.experiment_name,
            "experiment_name": args.experiment_name,
            "git_commit": commit,
            "benchmark_version": args.benchmark_version,
            "start_time": run_started_at,
            "end_time": run_finished_at,
            "run_started_at": run_started_at,
            "run_finished_at": run_finished_at,
            "benchmark_base_time": benchmark_base_time,
            "memory_count": EXPECTED_MEMORY_COUNT,
            "query_count": EXPECTED_QUERY_COUNT,
            "memories": EXPECTED_MEMORY_COUNT,
            "queries": EXPECTED_QUERY_COUNT,
            "modes": modes,
            "mode_count": len(modes),
            "formal_requests": expected_request_count,
            "topK": TOP_K,
            "topK_requested": TOP_K,
            "metric_K": [1, 3, 5, 10],
            "embedding_model": "bge-m3",
            "embedding_dimension": 1024,
            "database": DATABASE,
            "user": USER_ID,
            "user_id": USER_ID,
            "java_api_url": args.api_url,
            "successful_requests": len(records),
            "success_count": len(records),
            "failed_requests": 0,
            "failure_count": 0,
            "read_only_state_verified": True,
        }
        write_json(args.manifest, manifest)
        return manifest
    finally:
        conn.close()


def parse_args() -> argparse.Namespace:
    eval_root = Path(__file__).resolve().parents[1]
    raw_dir = eval_root / "results" / "raw"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queries", type=Path, default=eval_root / "datasets/retrieval/queries.jsonl")
    parser.add_argument("--memory-map", type=Path, default=eval_root / "results/memory_id_map.json")
    parser.add_argument("--raw-output", type=Path, default=raw_dir / "retrieval_ablation_raw.jsonl")
    parser.add_argument("--manifest", type=Path, default=raw_dir / "run_manifest.json")
    parser.add_argument("--before-state", type=Path, default=raw_dir / "retrieval_state_before.json")
    parser.add_argument("--after-state", type=Path, default=raw_dir / "retrieval_state_after.json")
    parser.add_argument("--api-url", default="http://127.0.0.1:8081/api/eval/memory/search")
    parser.add_argument("--http-timeout", type=float, default=120.0)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--database", default=DATABASE)
    parser.add_argument("--modes", nargs="+", default=DEFAULT_MODES)
    parser.add_argument("--experiment-name", default="mindpet_retrieval_ablation_v1")
    parser.add_argument("--benchmark-version", default="Retrieval Benchmark v1")
    parser.add_argument("--connect-timeout", type=int, default=10)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main() -> None:
    try:
        manifest = run(parse_args())
    except RunFailure as exc:
        print(f"RUN FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    except Exception as exc:
        print(f"RUN FAILED: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    print("RUN PASSED")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
