"""Run the confirmed 30-sample E2E pilot serially with no retries."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from e2e_db import (
    commit_and_require_idle,
    DatabaseConfig,
    EVAL_USER,
    require_eval_user_empty,
    require_snapshot,
    reset_eval_user,
    snapshot,
    verify_isolation,
    write_json,
)


SCRIPT_DIR = Path(__file__).resolve().parent
EXPERIMENT_ROOT = SCRIPT_DIR.parent
REPOSITORY_ROOT = EXPERIMENT_ROOT.parent.parent
DEFAULT_DATASET = EXPERIMENT_ROOT / "02-pilot-v1" / "datasets" / "pilot_30.jsonl"
DEFAULT_RESULTS_ROOT = EXPERIMENT_ROOT / "03-prompt-v2" / "pilot30-sqlite"
SQLITE_SCHEMA = REPOSITORY_ROOT / "MindPet-java" / "src" / "main" / "resources" / "db" / "sqlite-schema.sql"
PROMPT_HASHES = {
    "v1": "a2f27c59eb39499dc6682bb7e927afc0e19f87013559c0aeacf3c2ef8cb002c9",
    "v2": "cafa86f6e08703a60f236f4f19b371c3a79df917e74133aacce2377ee06e627e",
}
PLACEHOLDER_MODEL = re.compile(r"(?i)(<[^>]*>|your[-_ ]?model|placeholder|change[-_ ]?me)")

EXPECTED_CATEGORIES = {
    "stable_fact", "long_term_preference", "long_term_goal",
    "temporary_state", "one_off_information", "small_talk",
}
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")


class RunFailure(RuntimeError):
    pass


def prepare_database_before_requests(
    config: DatabaseConfig,
) -> tuple[dict[str, int], dict[str, Any]]:
    """Reset and verify in a short transaction that is closed before HTTP ingest."""
    with config.connect() as connection:
        deleted = reset_eval_user(connection)
        commit_and_require_idle(connection)
        before = snapshot(connection)
        require_eval_user_empty(before)
        commit_and_require_idle(connection)
    return deleted, before


def take_database_snapshot(config: DatabaseConfig) -> dict[str, Any]:
    """Read one snapshot without keeping its transaction open across API calls."""
    with config.connect() as connection:
        state = snapshot(connection)
        commit_and_require_idle(connection)
    return state


def read_dataset(path: Path) -> list[dict[str, Any]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    rows = [json.loads(line) for line in lines if line.strip()]
    expected_ids = [f"p{index:03d}" for index in range(1, 31)]
    if len(rows) != 30 or [row.get("sample_id") for row in rows] != expected_ids:
        raise RunFailure("pilot dataset must contain exactly p001..p030 in order")
    categories = Counter(row.get("category") for row in rows)
    if set(categories) != EXPECTED_CATEGORIES or any(value != 5 for value in categories.values()):
        raise RunFailure("pilot dataset must contain exactly five samples in each category")
    required = {
        "sample_id", "user_message", "assistant_context", "category", "difficulty",
        "human_should_remember", "human_importance", "expected_entities",
        "expected_relations", "annotation_reason", "review_status",
    }
    for row in rows:
        sample_id = row["sample_id"]
        if not required.issubset(row):
            raise RunFailure(f"{sample_id}: missing required field")
        if not isinstance(row["user_message"], str) or not row["user_message"].strip():
            raise RunFailure(f"{sample_id}: user_message must be nonblank")
        if not isinstance(row["assistant_context"], str):
            raise RunFailure(f"{sample_id}: assistant_context must be text")
        if row["difficulty"] not in {"easy", "medium", "hard"}:
            raise RunFailure(f"{sample_id}: unsupported difficulty")
        if row["review_status"] != "confirmed":
            raise RunFailure(f"{sample_id}: human ground truth is not confirmed")
        if not isinstance(row["human_should_remember"], bool):
            raise RunFailure(f"{sample_id}: human_should_remember is not confirmed")
        if row["human_importance"] not in {0.1, 0.3, 0.5, 0.7, 0.9}:
            raise RunFailure(f"{sample_id}: human_importance is not confirmed")
        if not isinstance(row["expected_entities"], list) or not isinstance(row["expected_relations"], list):
            raise RunFailure(f"{sample_id}: KG ground truth must be arrays")
        if not isinstance(row["annotation_reason"], str) or not row["annotation_reason"].strip():
            raise RunFailure(f"{sample_id}: annotation_reason is not confirmed")
    return rows


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


def git_commit() -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=REPOSITORY_ROOT,
        check=True, text=True, capture_output=True,
    ).stdout.strip()


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def configured_evaluation_path() -> Path:
    raw_path = os.environ.get("APP_EVAL_E2E_MEMORY_SQLITE_PATH", "")
    raw_root = os.environ.get("APP_EVAL_E2E_MEMORY_ALLOWED_ROOT", "")
    if not raw_path or not raw_root:
        raise RunFailure(
            "APP_EVAL_E2E_MEMORY_SQLITE_PATH and APP_EVAL_E2E_MEMORY_ALLOWED_ROOT are required"
        )
    path = Path(raw_path).resolve(strict=False)
    root = Path(raw_root).resolve(strict=False)
    if path == root or root not in path.parents:
        raise RunFailure("evaluation SQLite path must be below the configured allowed root")
    if path == (Path.home() / ".mindpet" / "mindpet.db").resolve(strict=False):
        raise RunFailure("default production SQLite path is forbidden")
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--results-root", type=Path, default=DEFAULT_RESULTS_ROOT)
    parser.add_argument("--base-url", default="http://127.0.0.1:8082")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--expected-prompt-variant", required=True, choices=sorted(PROMPT_HASHES))
    parser.add_argument("--endpoint-config-id", required=True)
    parser.add_argument("--expected-model-id", required=True)
    parser.add_argument("--temperature", required=True, type=float)
    parser.add_argument("--llm-connect-timeout", required=True)
    parser.add_argument("--llm-read-timeout", required=True)
    parser.add_argument("--spring-ai-retry-max-attempts", required=True, type=int)
    parser.add_argument("--spring-ai-retry-backoff-initial", required=True)
    parser.add_argument("--spring-ai-retry-backoff-max", required=True)
    parser.add_argument("--evaluation-infrastructure-commit", required=True)
    parser.add_argument("--timeout", type=float, default=240.0)
    parser.add_argument("--occurred-at-base", default="2026-10-01T00:00:00+00:00")
    args = parser.parse_args()
    if not SAFE_ID.fullmatch(args.run_id):
        raise RunFailure("run-id does not match the safe identifier policy")

    token = os.environ.get("APP_EVAL_E2E_MEMORY_TOKEN", "")
    if not token:
        raise RunFailure("APP_EVAL_E2E_MEMORY_TOKEN is required")
    if os.environ.get("APP_EVAL_E2E_MEMORY_ENABLED", "").lower() != "true":
        raise RunFailure("APP_EVAL_E2E_MEMORY_ENABLED must be true")
    if not args.endpoint_config_id.strip():
        raise RunFailure("endpoint-config-id must be nonblank")
    if not args.expected_model_id.strip() or PLACEHOLDER_MODEL.search(args.expected_model_id):
        raise RunFailure("expected-model-id must be explicit and cannot be a placeholder")
    if args.spring_ai_retry_max_attempts < 1:
        raise RunFailure("spring-ai-retry-max-attempts must be at least 1")
    database_path = configured_evaluation_path()
    rows = read_dataset(args.dataset)
    occurred_base = datetime.fromisoformat(args.occurred_at_base)
    if occurred_base.tzinfo is None:
        raise RunFailure("occurred-at-base must include a timezone")

    run_dir = args.results_root / args.expected_prompt_variant / args.run_id
    if run_dir.exists():
        raise RunFailure(f"refusing to overwrite existing run directory: {run_dir}")
    raw_dir = run_dir / "raw"
    raw_dir.mkdir(parents=True)
    raw_path = raw_dir / "ingest_results.jsonl"
    mapping_path = raw_dir / "sample_mapping.jsonl"
    manifest_path = raw_dir / "run_manifest.json"

    manifest: dict[str, Any] = {
        "status": "RUNNING",
        "run_id": args.run_id,
        "dataset": str(args.dataset),
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "dataset_sha256": sha256_file(args.dataset),
        "git_commit": git_commit(),
        "prompt_sha256": None,
        "expected_prompt_variant": args.expected_prompt_variant,
        "model": None,
        "expected_model": args.expected_model_id,
        "endpoint_config_identifier": args.endpoint_config_id,
        "temperature": args.temperature,
        "llm_connect_timeout": args.llm_connect_timeout,
        "llm_read_timeout": args.llm_read_timeout,
        "spring_ai_retry": {
            "max_attempts": args.spring_ai_retry_max_attempts,
            "backoff_initial": args.spring_ai_retry_backoff_initial,
            "backoff_max": args.spring_ai_retry_backoff_max,
        },
        "runner_http_timeout_seconds": args.timeout,
        "sqlite_canonical_absolute_path": str(database_path),
        "sqlite_schema_sha256": sha256_file(SQLITE_SCHEMA),
        "fixed_eval_user": EVAL_USER,
        "evaluation_infrastructure_commit": args.evaluation_infrastructure_commit,
        "runner_commit": git_commit(),
        "runner_sha256": sha256_file(Path(__file__)),
        "evaluation_enabled": True,
        "request_policy": "serial, exactly once, fail-fast, no retry",
        "started_at": datetime.now(timezone.utc).isoformat(),
        "attempted": 0,
        "completed": 0,
        "sample_count": len(rows),
        "sample_ids": [row["sample_id"] for row in rows],
    }
    write_json(manifest_path, manifest)

    config = DatabaseConfig(args.base_url, token, database_path, args.timeout)
    deleted, before = prepare_database_before_requests(config)

    http_status, api_snapshot = api_request(
        "GET", args.base_url.rstrip("/") + "/api/eval/memory/snapshot",
        token, None, args.timeout,
    )
    if http_status != 200 or api_snapshot.get("status") != "OK":
        raise RunFailure("E2E API preflight failed")
    require_snapshot(api_snapshot, database_path)
    expected_prompt_hash = PROMPT_HASHES[args.expected_prompt_variant]
    if api_snapshot.get("promptSha256") != expected_prompt_hash:
        raise RunFailure(
            f"prompt hash mismatch: {api_snapshot.get('promptSha256')} != {expected_prompt_hash}"
        )
    manifest["prompt_sha256"] = api_snapshot["promptSha256"]
    manifest["model"] = api_snapshot.get("model", "")
    if manifest["model"] != args.expected_model_id:
        manifest["status"] = "FAILED"
        manifest["failure_type"] = "MODEL_MISMATCH"
        manifest["failure_message"] = "snapshot model does not match expected-model-id"
        manifest["finished_at"] = datetime.now(timezone.utc).isoformat()
        write_json(manifest_path, manifest)
        raise RunFailure("snapshot model does not match expected-model-id")
    manifest["sqlite_canonical_absolute_path"] = api_snapshot["databasePath"]

    write_json(raw_dir / "snapshot_before.json", before)
    manifest["reset_deleted"] = deleted
    write_json(manifest_path, manifest)

    try:
        with raw_path.open("w", encoding="utf-8", newline="\n") as raw_file, \
                mapping_path.open("w", encoding="utf-8", newline="\n") as mapping_file:
            for index, sample in enumerate(rows):
                body = {
                    "sampleId": sample["sample_id"],
                    "runId": args.run_id,
                    "userMessage": sample["user_message"],
                    "assistantContext": sample["assistant_context"],
                    "emotion": "neutral",
                    "occurredAt": (occurred_base + timedelta(seconds=index)).isoformat().replace("+00:00", "Z"),
                }
                status, payload = api_request(
                    "POST", args.base_url.rstrip("/") + "/api/eval/memory/ingest",
                    token, body, args.timeout,
                )
                record = {
                    "sample_id": sample["sample_id"],
                    "http_status": status,
                    "response": payload,
                }
                raw_file.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                raw_file.flush()
                manifest["attempted"] += 1
                write_json(manifest_path, manifest)

                if status != 200 or payload.get("status") not in {"FULL_SUCCESS", "NO_PERSIST"}:
                    raise RunFailure(
                        f"{sample['sample_id']}: API/persistence failure; fail-fast with no retry"
                    )
                mapping = {
                    "sample_id": sample["sample_id"],
                    "run_id": args.run_id,
                    "user_id": payload.get("userId"),
                    "session_id": payload.get("sessionId"),
                    "turn_hash": payload.get("turnHash"),
                    "long_term_memory_ids": payload.get("rows", {}).get("longTermMemoryIds", []),
                    "entity_ids": payload.get("rows", {}).get("entityIds", []),
                    "relation_ids": payload.get("rows", {}).get("relationIds", []),
                    "evidence_ids": payload.get("rows", {}).get("evidenceIds", []),
                    "entities": payload.get("entities", []),
                    "relations": payload.get("relations", []),
                    "ltm_rows_before": payload.get("ltmRowsBefore"),
                    "ltm_rows_after": payload.get("ltmRowsAfter"),
                    "prune_occurred": payload.get("pruneOccurred"),
                    "prune_deleted_estimate": payload.get("pruneDeletedEstimate"),
                }
                mapping_file.write(json.dumps(mapping, ensure_ascii=False, separators=(",", ":")) + "\n")
                mapping_file.flush()
                manifest["completed"] += 1
                write_json(manifest_path, manifest)

        after = take_database_snapshot(config)
        write_json(raw_dir / "snapshot_after.json", after)
        verification = verify_isolation(before, after)
        write_json(raw_dir / "database_verification.json", verification)
        manifest["status"] = "COMPLETED"
    except Exception as exc:
        after = take_database_snapshot(config)
        write_json(raw_dir / "snapshot_failure.json", after)
        manifest["status"] = "FAILED"
        manifest["failure_type"] = type(exc).__name__
        manifest["failure_message"] = str(exc)
        raise
    finally:
        manifest["finished_at"] = datetime.now(timezone.utc).isoformat()
        write_json(manifest_path, manifest)

    print(f"pilot completed: {run_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
