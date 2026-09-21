"""Safely import the 120-memory retrieval benchmark into mindpet_eval only.

All embeddings are generated and validated before the database transaction starts.
The transaction only replaces rows belonging to eval_test_user. It never truncates a
table and refuses to run unless SELECT current_database() returns mindpet_eval.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any


ALLOWED_DATABASE = "mindpet_eval"
ALLOWED_USER_ID = "eval_test_user"
SESSION_ID = "eval_benchmark_v1"
BENCHMARK_VERSION = "retrieval_v1"
MODEL = "bge-m3"
EMBEDDING_DIMENSION = 1024
MEMORY_COUNT = 120
QUERY_COUNT = 40


class ImportFailure(RuntimeError):
    """A safety precondition, embedding, database, or verification failure."""


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as exc:
        raise ImportFailure(f"cannot read UTF-8 JSONL {path}: {exc}") from exc
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            raise ImportFailure(f"{path}:{line_number}: blank JSONL line")
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ImportFailure(f"{path}:{line_number}: invalid JSON: {exc}") from exc
        if not isinstance(record, dict):
            raise ImportFailure(f"{path}:{line_number}: record must be an object")
        records.append(record)
    return records


def validate_dataset(memories: list[dict[str, Any]], queries: list[dict[str, Any]]) -> None:
    if len(memories) != MEMORY_COUNT or len(queries) != QUERY_COUNT:
        raise ImportFailure(
            f"dataset count mismatch: memories={len(memories)}, queries={len(queries)}"
        )
    memory_ids = [row.get("memory_id") for row in memories]
    expected = [f"m{index:03d}" for index in range(1, MEMORY_COUNT + 1)]
    if memory_ids != expected or len(set(memory_ids)) != MEMORY_COUNT:
        raise ImportFailure("memory_id values must be unique and ordered m001..m120")
    for row in memories:
        if row.get("user_id") != ALLOWED_USER_ID:
            raise ImportFailure(f"{row.get('memory_id')}: user_id is not {ALLOWED_USER_ID}")
        if not isinstance(row.get("content"), str) or not row["content"].strip():
            raise ImportFailure(f"{row.get('memory_id')}: content is empty")
        offset = row.get("created_at_offset_hours")
        if isinstance(offset, bool) or not isinstance(offset, (int, float)) or offset < 0:
            raise ImportFailure(f"{row.get('memory_id')}: invalid created_at_offset_hours")


def http_json(url: str, payload: dict[str, Any] | None, timeout: float) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method="GET" if payload is None else "POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            parsed = json.loads(response.read().decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, urllib.error.HTTPError) as exc:
        raise ImportFailure(f"Ollama request failed at {url}: {exc}") from exc
    if not isinstance(parsed, dict):
        raise ImportFailure(f"Ollama returned a non-object response at {url}")
    return parsed


def validate_embedding(memory_id: str, raw: object) -> list[float]:
    if not isinstance(raw, list):
        raise ImportFailure(f"{memory_id}: embedding is null or not an array")
    if len(raw) != EMBEDDING_DIMENSION:
        raise ImportFailure(f"{memory_id}: embedding dimension={len(raw)}, expected 1024")
    values: list[float] = []
    for index, value in enumerate(raw):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ImportFailure(f"{memory_id}: embedding[{index}] is not numeric")
        number = float(value)
        if not math.isfinite(number):
            raise ImportFailure(f"{memory_id}: embedding[{index}] is non-finite")
        values.append(number)
    if not any(value != 0.0 for value in values):
        raise ImportFailure(f"{memory_id}: embedding is all zero")
    return values


def generate_embeddings(
    memories: list[dict[str, Any]], ollama_base_url: str, timeout: float
) -> tuple[str, list[list[float]]]:
    version = http_json(f"{ollama_base_url}/api/version", None, timeout).get("version")
    tags = http_json(f"{ollama_base_url}/api/tags", None, timeout).get("models", [])
    model_names = {
        item.get("name") or item.get("model")
        for item in tags
        if isinstance(item, dict)
    }
    if MODEL not in model_names and f"{MODEL}:latest" not in model_names:
        raise ImportFailure(f"Ollama model {MODEL}:latest is not available; reported={sorted(model_names)}")

    embeddings: list[list[float]] = []
    for index, memory in enumerate(memories, start=1):
        response = http_json(
            f"{ollama_base_url}/api/embed",
            {"model": MODEL, "input": memory["content"]},
            timeout,
        )
        batch = response.get("embeddings")
        if not isinstance(batch, list) or len(batch) != 1:
            raise ImportFailure(f"{memory['memory_id']}: expected exactly one Ollama embedding")
        embeddings.append(validate_embedding(memory["memory_id"], batch[0]))
        if index == 1 or index % 10 == 0 or index == len(memories):
            print(f"embedding progress: {index}/{len(memories)}", flush=True)
    return str(version or "unknown"), embeddings


def import_psycopg() -> Any:
    try:
        import psycopg  # type: ignore
    except ImportError as exc:
        raise ImportFailure(
            "psycopg is required; install MindPet-Eval/requirements.txt in the eval environment"
        ) from exc
    return psycopg


def required_columns() -> set[str]:
    return {
        "id", "user_id", "session_id", "content", "role", "embedding",
        "importance", "confidence", "layer", "emotion", "created_at",
        "last_accessed", "access_count",
    }


def verify_database_and_schema(conn: Any, allow_add_metadata: bool) -> tuple[datetime, int, bool]:
    with conn.cursor() as cursor:
        cursor.execute("SELECT current_database()")
        current_database = cursor.fetchone()[0]
        if current_database != ALLOWED_DATABASE:
            raise ImportFailure(
                f"SAFETY STOP: current_database()={current_database!r}, expected {ALLOWED_DATABASE!r}"
            )
        cursor.execute("SELECT to_regclass('public.long_term_memory')")
        if cursor.fetchone()[0] is None:
            raise ImportFailure("public.long_term_memory does not exist in mindpet_eval")
        cursor.execute(
            "SELECT column_name, data_type, udt_name FROM information_schema.columns "
            "WHERE table_schema='public' AND table_name='long_term_memory'"
        )
        columns = {row[0]: (row[1], row[2]) for row in cursor.fetchall()}
        missing = sorted(required_columns() - set(columns))
        if missing:
            raise ImportFailure(f"long_term_memory is missing required columns: {missing}")
        metadata_exists = "metadata" in columns
        if metadata_exists and columns["metadata"][1] not in {"json", "jsonb"}:
            raise ImportFailure(f"metadata column must be json/jsonb, got {columns['metadata']}")
        if not metadata_exists and not allow_add_metadata:
            raise ImportFailure(
                "long_term_memory has no metadata column; rerun only after review with "
                "--allow-add-metadata-column to add JSONB inside the import transaction"
            )
        cursor.execute("SELECT clock_timestamp()::timestamp")
        benchmark_base_time = cursor.fetchone()[0]
        cursor.execute(
            "SELECT COUNT(*) FROM public.long_term_memory WHERE user_id=%s",
            (ALLOWED_USER_ID,),
        )
        old_count = int(cursor.fetchone()[0])
    print(f"current_database={current_database}")
    print(f"existing eval_test_user rows to delete={old_count}")
    return benchmark_base_time, old_count, metadata_exists


def vector_literal(values: list[float]) -> str:
    return "[" + ",".join(format(value, ".17g") for value in values) + "]"


def verify_import(cursor: Any, memories: list[dict[str, Any]], benchmark_base_time: datetime) -> dict[str, Any]:
    cursor.execute(
        "SELECT COUNT(*), "
        "COUNT(*) FILTER (WHERE embedding IS NULL), "
        "COUNT(*) FILTER (WHERE embedding IS NOT NULL AND vector_dims(embedding) <> %s), "
        "COUNT(*) FILTER (WHERE access_count IS DISTINCT FROM 0), "
        "COUNT(*) FILTER (WHERE last_accessed IS DISTINCT FROM created_at), "
        "COUNT(*) FILTER (WHERE embedding IS NOT NULL AND embedding::text ~* '(nan|inf)'), "
        "COUNT(*) FILTER (WHERE embedding IS NOT NULL AND (embedding <#> embedding) = 0) "
        "FROM public.long_term_memory WHERE user_id=%s",
        (EMBEDDING_DIMENSION, ALLOWED_USER_ID),
    )
    total, null_vectors, wrong_dims, wrong_access, wrong_time, nonfinite, all_zero = map(int, cursor.fetchone())
    cursor.execute(
        "SELECT COUNT(*), COUNT(DISTINCT metadata->>'benchmark_memory_id') "
        "FROM public.long_term_memory WHERE user_id=%s "
        "AND metadata ? 'benchmark_memory_id'",
        (ALLOWED_USER_ID,),
    )
    metadata_count, unique_metadata_count = map(int, cursor.fetchone())
    cursor.execute(
        "SELECT metadata->>'benchmark_memory_id', created_at "
        "FROM public.long_term_memory WHERE user_id=%s",
        (ALLOWED_USER_ID,),
    )
    created_by_id = {row[0]: row[1] for row in cursor.fetchall()}
    bad_offsets = 0
    for memory in memories:
        expected = benchmark_base_time - timedelta(hours=float(memory["created_at_offset_hours"]))
        actual = created_by_id.get(memory["memory_id"])
        if actual is None or abs((actual - expected).total_seconds()) > 0.001:
            bad_offsets += 1

    checks = {
        "total": total,
        "null_embeddings": null_vectors,
        "wrong_dimensions": wrong_dims,
        "wrong_access_count": wrong_access,
        "last_accessed_mismatch": wrong_time,
        "nonfinite_embeddings": nonfinite,
        "all_zero_embeddings": all_zero,
        "metadata_count": metadata_count,
        "unique_metadata_count": unique_metadata_count,
        "created_at_offset_mismatch": bad_offsets,
    }
    expected_checks = {
        "total": MEMORY_COUNT,
        "null_embeddings": 0,
        "wrong_dimensions": 0,
        "wrong_access_count": 0,
        "last_accessed_mismatch": 0,
        "nonfinite_embeddings": 0,
        "all_zero_embeddings": 0,
        "metadata_count": MEMORY_COUNT,
        "unique_metadata_count": MEMORY_COUNT,
        "created_at_offset_mismatch": 0,
    }
    if checks != expected_checks:
        raise ImportFailure(f"post-import verification failed: {checks}")
    return checks


def run_import(args: argparse.Namespace) -> dict[str, Any]:
    if args.database != ALLOWED_DATABASE:
        raise ImportFailure(f"database argument must be exactly {ALLOWED_DATABASE}")
    if not args.confirm_delete_eval_user:
        raise ImportFailure("explicit --confirm-delete-eval-user is required")

    memories = load_jsonl(args.memories)
    queries = load_jsonl(args.queries)
    validate_dataset(memories, queries)
    psycopg = import_psycopg()

    password = os.environ.get(args.password_env)
    connection_args = {
        "host": args.host,
        "port": args.port,
        "dbname": args.database,
        "user": args.db_user,
        "connect_timeout": args.connect_timeout,
    }
    if password:
        connection_args["password"] = password

    with psycopg.connect(**connection_args, autocommit=True) as conn:
        benchmark_base_time, old_count, metadata_exists = verify_database_and_schema(
            conn, args.allow_add_metadata_column
        )
        print(f"benchmark_base_time={benchmark_base_time.isoformat()}")

        # All 120 real embeddings must succeed before DELETE or INSERT can begin.
        ollama_version, embeddings = generate_embeddings(
            memories, args.ollama_base_url.rstrip("/"), args.ollama_timeout
        )
        if len(embeddings) != MEMORY_COUNT:
            raise ImportFailure(f"embedding count={len(embeddings)}, expected {MEMORY_COUNT}")

        id_map: dict[str, int] = {}
        samples: dict[str, dict[str, Any]] = {}
        with conn.transaction():
            with conn.cursor() as cursor:
                # The optional schema change is restricted to mindpet_eval by the
                # current_database() hard gate above and rolls back with the import.
                if not metadata_exists:
                    cursor.execute(
                        "ALTER TABLE public.long_term_memory ADD COLUMN metadata JSONB"
                    )
                cursor.execute(
                    "SELECT COUNT(*) FROM public.long_term_memory WHERE user_id=%s",
                    (ALLOWED_USER_ID,),
                )
                transaction_old_count = int(cursor.fetchone()[0])
                if transaction_old_count != old_count:
                    raise ImportFailure(
                        "eval_test_user row count changed after preflight; refusing to delete"
                    )
                print(f"deleting eval_test_user rows={transaction_old_count}")
                cursor.execute(
                    "DELETE FROM public.long_term_memory WHERE user_id=%s",
                    (ALLOWED_USER_ID,),
                )
                if cursor.rowcount != transaction_old_count:
                    raise ImportFailure(
                        f"DELETE affected {cursor.rowcount}, expected {transaction_old_count}"
                    )

                insert_sql = (
                    "INSERT INTO public.long_term_memory "
                    "(user_id, session_id, content, role, embedding, importance, confidence, "
                    "layer, emotion, created_at, last_accessed, access_count, metadata) "
                    "VALUES (%s,%s,%s,%s,%s::vector,%s,%s,%s,%s,%s,%s,0,%s::jsonb) RETURNING id"
                )
                for memory, embedding in zip(memories, embeddings, strict=True):
                    created_at = benchmark_base_time - timedelta(
                        hours=float(memory["created_at_offset_hours"])
                    )
                    metadata = {
                        "benchmark_memory_id": memory["memory_id"],
                        "benchmark_version": BENCHMARK_VERSION,
                        "benchmark_base_time": benchmark_base_time.isoformat(),
                        "created_at_offset_hours": memory["created_at_offset_hours"],
                        "category": memory["category"],
                        "tags": memory["tags"],
                    }
                    cursor.execute(
                        insert_sql,
                        (
                            ALLOWED_USER_ID, SESSION_ID, memory["content"], "user",
                            vector_literal(embedding), memory["importance"], memory["confidence"],
                            memory["layer"], memory["emotion"], created_at, created_at,
                            json.dumps(metadata, ensure_ascii=False),
                        ),
                    )
                    id_map[memory["memory_id"]] = int(cursor.fetchone()[0])

                checks = verify_import(cursor, memories, benchmark_base_time)
                cursor.execute(
                    "SELECT metadata->>'benchmark_memory_id', id, content, importance, layer, "
                    "created_at, last_accessed, vector_dims(embedding) "
                    "FROM public.long_term_memory WHERE user_id=%s "
                    "AND metadata->>'benchmark_memory_id' IN ('m001','m120') "
                    "ORDER BY metadata->>'benchmark_memory_id'",
                    (ALLOWED_USER_ID,),
                )
                for row in cursor.fetchall():
                    samples[row[0]] = {
                        "db_id": int(row[1]), "content": row[2], "importance": row[3],
                        "layer": row[4], "created_at": row[5].isoformat(),
                        "last_accessed": row[6].isoformat(), "vector_dims": int(row[7]),
                    }

        args.map_output.parent.mkdir(parents=True, exist_ok=True)
        temporary_map = args.map_output.with_suffix(args.map_output.suffix + ".tmp")
        try:
            temporary_map.write_text(
                json.dumps(id_map, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            temporary_map.replace(args.map_output)
        finally:
            temporary_map.unlink(missing_ok=True)

    return {
        "dataset": {"memories": len(memories), "queries": len(queries)},
        "embedding": {
            "ollama_version": ollama_version, "model": MODEL,
            "success": len(embeddings), "failed": 0, "dimensions": EMBEDDING_DIMENSION,
        },
        "database": {
            "current_database": ALLOWED_DATABASE, "deleted": old_count,
            "inserted": len(id_map), "final_count": checks["total"],
        },
        "benchmark_base_time": benchmark_base_time.isoformat(),
        "checks": checks,
        "id_map": {"m001": id_map["m001"], "m120": id_map["m120"]},
        "samples": samples,
        "map_output": str(args.map_output.resolve()),
    }


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--memories", type=Path, default=root / "datasets/retrieval/memories.jsonl")
    parser.add_argument("--queries", type=Path, default=root / "datasets/retrieval/queries.jsonl")
    parser.add_argument("--map-output", type=Path, default=root / "results/memory_id_map.json")
    parser.add_argument("--host", default=os.environ.get("MINDPET_EVAL_DB_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("MINDPET_EVAL_DB_PORT", "5432")))
    parser.add_argument("--database", default=ALLOWED_DATABASE)
    parser.add_argument("--db-user", default=os.environ.get("MINDPET_EVAL_DB_USER", "postgres"))
    parser.add_argument("--password-env", default="MINDPET_EVAL_DB_PASSWORD")
    parser.add_argument("--connect-timeout", type=int, default=10)
    parser.add_argument("--ollama-base-url", default="http://127.0.0.1:11434")
    parser.add_argument("--ollama-timeout", type=float, default=120.0)
    parser.add_argument("--confirm-delete-eval-user", action="store_true")
    parser.add_argument("--allow-add-metadata-column", action="store_true")
    return parser.parse_args()


def main() -> None:
    try:
        report = run_import(parse_args())
    except ImportFailure as exc:
        print(f"IMPORT FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    except Exception as exc:
        print(f"IMPORT FAILED: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    print("IMPORT PASSED")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
