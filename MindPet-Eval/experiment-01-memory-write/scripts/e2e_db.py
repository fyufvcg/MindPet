"""Shared fail-closed database helpers for the E2E memory benchmark."""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass
from typing import Any, Iterable

try:
    import psycopg
except ImportError as exc:  # pragma: no cover - environment preflight
    raise RuntimeError("psycopg is required; install MindPet-Eval/requirements.txt") from exc


REQUIRED_DATABASE = "mindpet_e2e_eval"
EVAL_USER = "e2e_memory_eval_user"
TABLES = (
    ("long_term_memory", "id"),
    ("kg_entity", "id"),
    ("kg_relation", "id"),
    ("kg_evidence", "id"),
    ("kg_turn_ingest", "turn_hash"),
)
DELETE_ORDER = (
    "kg_evidence", "kg_relation", "kg_entity", "kg_turn_ingest", "long_term_memory",
)


class SafetyError(RuntimeError):
    pass


@dataclass(frozen=True)
class DatabaseConfig:
    host: str
    port: int
    name: str
    user: str
    password: str

    @classmethod
    def from_env(cls) -> "DatabaseConfig":
        name = os.environ.get("MINDPET_E2E_DB_NAME", REQUIRED_DATABASE)
        if name != REQUIRED_DATABASE:
            raise SafetyError(f"MINDPET_E2E_DB_NAME must be {REQUIRED_DATABASE}")
        password = os.environ.get("MINDPET_E2E_DB_PASSWORD", "")
        if not password:
            raise SafetyError("MINDPET_E2E_DB_PASSWORD is required")
        return cls(
            host=os.environ.get("MINDPET_E2E_DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("MINDPET_E2E_DB_PORT", "5432")),
            name=name,
            user=os.environ.get("MINDPET_E2E_DB_USER", "mindpet_e2e_runner"),
            password=password,
        )

    def connect(self) -> "psycopg.Connection[Any]":
        connection = psycopg.connect(
            host=self.host,
            port=self.port,
            dbname=self.name,
            user=self.user,
            password=self.password,
            connect_timeout=10,
        )
        require_database(connection)
        return connection


def require_database(connection: "psycopg.Connection[Any]") -> str:
    with connection.cursor() as cursor:
        cursor.execute("SELECT current_database()")
        current = cursor.fetchone()[0]
    if current != REQUIRED_DATABASE:
        raise SafetyError(
            f"refusing E2E operation: current_database()={current!r}, expected {REQUIRED_DATABASE!r}"
        )
    return current


def _digest_rows(
    connection: "psycopg.Connection[Any]",
    table: str,
    order_column: str,
    where_sql: str,
    params: Iterable[Any],
) -> str:
    digest = hashlib.sha256()
    sql = (
        f"SELECT to_jsonb(row_data)::text FROM "
        f"(SELECT * FROM {table} WHERE {where_sql} ORDER BY {order_column}) AS row_data"
    )
    with connection.cursor() as cursor:
        cursor.execute(sql, tuple(params))
        for (payload,) in cursor:
            digest.update(payload.encode("utf-8"))
            digest.update(b"\n")
    return digest.hexdigest()


def snapshot(connection: "psycopg.Connection[Any]") -> dict[str, Any]:
    database = require_database(connection)
    tables: dict[str, Any] = {}
    with connection.cursor() as cursor:
        for table, order_column in TABLES:
            cursor.execute(f"SELECT COUNT(*) FROM {table}")
            total = cursor.fetchone()[0]
            cursor.execute(f"SELECT COUNT(*) FROM {table} WHERE user_id=%s", (EVAL_USER,))
            eval_count = cursor.fetchone()[0]
            other_count = total - eval_count
            tables[table] = {
                "row_count": total,
                "eval_user_row_count": eval_count,
                "other_users_row_count": other_count,
                "eval_user_digest": _digest_rows(
                    connection, table, order_column, "user_id=%s", (EVAL_USER,)
                ),
                "other_users_digest": _digest_rows(
                    connection, table, order_column, "user_id<>%s", (EVAL_USER,)
                ),
            }
    return {"database": database, "user_id": EVAL_USER, "tables": tables}


def reset_eval_user(connection: "psycopg.Connection[Any]") -> dict[str, int]:
    require_database(connection)
    deleted: dict[str, int] = {}
    with connection.transaction():
        with connection.cursor() as cursor:
            for table in DELETE_ORDER:
                cursor.execute(f"DELETE FROM {table} WHERE user_id=%s", (EVAL_USER,))
                deleted[table] = cursor.rowcount
            for table, _ in TABLES:
                cursor.execute(f"SELECT COUNT(*) FROM {table} WHERE user_id=%s", (EVAL_USER,))
                remaining = cursor.fetchone()[0]
                if remaining != 0:
                    raise SafetyError(f"reset incomplete: {table} still has {remaining} evaluation rows")
    return deleted


def verify_isolation(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    if before.get("database") != REQUIRED_DATABASE or after.get("database") != REQUIRED_DATABASE:
        raise SafetyError("snapshot database is not the dedicated E2E database")
    checks: dict[str, bool] = {}
    for table, _ in TABLES:
        left = before["tables"][table]
        right = after["tables"][table]
        unchanged = (
            left["other_users_row_count"] == right["other_users_row_count"]
            and left["other_users_digest"] == right["other_users_digest"]
        )
        checks[table] = unchanged
        if not unchanged:
            raise SafetyError(f"non-evaluation rows changed in {table}")
    return {"database": REQUIRED_DATABASE, "other_users_unchanged": checks, "passed": True}


def write_json(path: Any, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
