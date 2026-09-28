"""Shared HTTP and artifact helpers for the isolated SQLite E2E benchmark."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


EVAL_USER = "e2e_memory_eval_user"
TABLES = (
    "long_term_memory", "kg_entity", "kg_relation", "kg_evidence", "kg_turn_ingest",
)


class SafetyError(RuntimeError):
    pass


def api_request(
    method: str, url: str, token: str, body: dict[str, Any] | None, timeout: float
) -> tuple[int, dict[str, Any]]:
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=payload, method=method, headers={
        "Accept": "application/json",
        "Content-Type": "application/json; charset=utf-8",
        "X-MindPet-Eval-Token": token,
    })
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            parsed = json.loads(exc.read().decode("utf-8"))
        except Exception:
            parsed = {"status": "FAILED", "errorType": "NON_JSON_HTTP_ERROR"}
        return exc.code, parsed


@dataclass(frozen=True)
class DatabaseConfig:
    """HTTP Evaluation API configuration; this object never opens SQLite."""

    base_url: str
    token: str
    sqlite_path: Path
    timeout: float

    @classmethod
    def from_env(cls) -> "DatabaseConfig":
        token = os.environ.get("APP_EVAL_E2E_MEMORY_TOKEN", "")
        raw_path = os.environ.get("APP_EVAL_E2E_MEMORY_SQLITE_PATH", "")
        if not token:
            raise SafetyError("APP_EVAL_E2E_MEMORY_TOKEN is required")
        if not raw_path:
            raise SafetyError("APP_EVAL_E2E_MEMORY_SQLITE_PATH is required")
        return cls(
            base_url=os.environ.get("APP_EVAL_E2E_MEMORY_BASE_URL", "http://127.0.0.1:8082"),
            token=token,
            sqlite_path=Path(raw_path).resolve(strict=False),
            timeout=float(os.environ.get("APP_EVAL_E2E_MEMORY_TIMEOUT", "30")),
        )

    def connect(self) -> "DatabaseConfig":
        return self

    def __enter__(self) -> "DatabaseConfig":
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        return None


def require_snapshot(
    state: dict[str, Any], expected_database_path: Path | None = None
) -> dict[str, Any]:
    if state.get("status") != "OK" or state.get("userId") != EVAL_USER:
        raise SafetyError("snapshot is not bound to the fixed evaluation user")
    raw_path = state.get("databasePath")
    if not isinstance(raw_path, str) or not raw_path:
        raise SafetyError("snapshot does not expose a canonical SQLite path")
    if expected_database_path is not None:
        actual = Path(raw_path).resolve(strict=False)
        expected = expected_database_path.resolve(strict=False)
        if actual != expected:
            raise SafetyError(f"snapshot path mismatch: {actual} != {expected}")
    tables = state.get("tables")
    if not isinstance(tables, dict) or set(tables) != set(TABLES):
        raise SafetyError("snapshot does not contain exactly the five evaluation tables")
    for table in TABLES:
        value = tables[table]
        if not isinstance(value, dict) or not isinstance(value.get("count"), int):
            raise SafetyError(f"invalid snapshot table: {table}")
        if value["count"] != len(value.get("rows", [])):
            raise SafetyError(f"snapshot count mismatch: {table}")
    return state


def snapshot(connection: DatabaseConfig) -> dict[str, Any]:
    status, state = api_request(
        "GET", connection.base_url.rstrip("/") + "/api/eval/memory/snapshot",
        connection.token, None, connection.timeout,
    )
    if status != 200:
        raise SafetyError(f"snapshot failed: HTTP {status} {state.get('errorType')}")
    return require_snapshot(state, connection.sqlite_path)


def reset_eval_user(connection: DatabaseConfig) -> dict[str, int]:
    status, result = api_request(
        "POST", connection.base_url.rstrip("/") + "/api/eval/memory/reset",
        connection.token, {}, connection.timeout,
    )
    if status != 200 or result.get("status") != "OK":
        raise SafetyError(f"reset failed: HTTP {status} {result.get('errorType')}")
    return result.get("deleted", {})


def commit_and_require_idle(connection: DatabaseConfig) -> None:
    del connection


def require_eval_user_empty(state: dict[str, Any]) -> None:
    require_snapshot(state)
    nonempty = {
        table: state["tables"][table]["count"]
        for table in TABLES if state["tables"][table]["count"] != 0
    }
    if nonempty:
        raise SafetyError(f"evaluation user is not empty after reset: {nonempty}")


def verify_isolation(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    require_snapshot(before)
    require_snapshot(after)
    if before["databasePath"] != after["databasePath"]:
        raise SafetyError("snapshot database path changed during the run")
    return {
        "database_path": after["databasePath"],
        "user_id": EVAL_USER,
        "fixed_user_only": True,
        "passed": True,
    }


def write_json(path: Any, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
