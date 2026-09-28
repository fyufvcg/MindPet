"""Run the formal H3-A benchmark against the production importance scorer."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import textwrap
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


EXPECTED_COUNT = 100
DATASET_VERSION = "Importance Accuracy Benchmark v1"
EXPERIMENT = "importance_h3a"
ALLOWED_SCORES = {0.1, 0.3, 0.5, 0.7, 0.9}
EXPECTED_CATEGORIES = {
    "stable_preference", "active_project", "long_term_goal", "relationship",
    "tool_habit", "study_habit", "work_habit", "place", "personal_fact",
    "short_term_schedule", "one_off_task", "temporary_state", "small_talk",
    "uncertain_claim", "recurring_fact",
}
EXPECTED_DIFFICULTIES = {"easy", "medium", "hard"}


class RunFailure(RuntimeError):
    pass


def now_utc() -> str:
    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def production_prompt(java_source: Path) -> str:
    """Reconstruct the Java text-block value used as EXTRACTION_PROMPT."""
    source = java_source.read_text(encoding="utf-8")
    match = re.search(
        r'private\s+static\s+final\s+String\s+EXTRACTION_PROMPT\s*=\s*"""\r?\n'
        r'(.*?)^[ \t]*""";',
        source,
        flags=re.DOTALL | re.MULTILINE,
    )
    if not match:
        raise RunFailure("cannot locate production EXTRACTION_PROMPT")
    return textwrap.dedent(match.group(1)).replace("\r\n", "\n")


def prompt_sha256(java_source: Path) -> str:
    return hashlib.sha256(production_prompt(java_source).encode("utf-8")).hexdigest()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
        rows = [json.loads(line) for line in lines if line.strip()]
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RunFailure(f"cannot read dataset: {exc}") from exc
    if len(rows) != len(lines) or not all(isinstance(row, dict) for row in rows):
        raise RunFailure("dataset contains a blank or non-object row")
    return rows


def validate_dataset(rows: list[dict[str, Any]], require_human_labels: bool) -> dict[str, Any]:
    expected_ids = [f"i{index:03d}" for index in range(1, EXPECTED_COUNT + 1)]
    if len(rows) != EXPECTED_COUNT or [row.get("sample_id") for row in rows] != expected_ids:
        raise RunFailure("dataset must contain exactly i001..i100 in order")
    required = {
        "sample_id", "user_message", "assistant_context", "category", "difficulty",
        "human_importance", "human_should_remember", "human_high_importance",
        "annotation_reason", "review_status",
    }
    for row in rows:
        sample_id = row["sample_id"]
        if not required.issubset(row):
            raise RunFailure(f"{sample_id}: required fields are missing")
        if not isinstance(row["user_message"], str) or not row["user_message"].strip():
            raise RunFailure(f"{sample_id}: user_message must be nonblank text")
        if not isinstance(row["assistant_context"], str):
            raise RunFailure(f"{sample_id}: assistant_context must be text")
        if row["category"] not in EXPECTED_CATEGORIES:
            raise RunFailure(f"{sample_id}: unsupported category")
        if row["difficulty"] not in EXPECTED_DIFFICULTIES:
            raise RunFailure(f"{sample_id}: unsupported difficulty")
        if require_human_labels:
            importance = row["human_importance"]
            if not isinstance(importance, (int, float)) or float(importance) not in ALLOWED_SCORES:
                raise RunFailure(f"{sample_id}: human_importance is not human-confirmed")
            if not isinstance(row["human_should_remember"], bool):
                raise RunFailure(f"{sample_id}: human_should_remember is missing")
            if not isinstance(row["human_high_importance"], bool):
                raise RunFailure(f"{sample_id}: human_high_importance is missing")
            if row["human_high_importance"] != (float(importance) >= 0.6):
                raise RunFailure(f"{sample_id}: human_high_importance is inconsistent")
            if not isinstance(row["annotation_reason"], str) or not row["annotation_reason"].strip():
                raise RunFailure(f"{sample_id}: annotation_reason is missing")
            if row["review_status"] != "confirmed":
                raise RunFailure(f"{sample_id}: review_status is not confirmed")
        elif any(row[field] is not None for field in (
            "human_importance", "human_should_remember", "human_high_importance",
            "annotation_reason",
        )) or row["review_status"] != "pending_human_annotation":
            raise RunFailure(f"{sample_id}: candidate template contains an unreviewed label")
    return {
        "samples": len(rows),
        "unique_sample_ids": len({row["sample_id"] for row in rows}),
        "confirmed": sum(row["review_status"] == "confirmed" for row in rows),
        "categories": dict(sorted(Counter(row["category"] for row in rows).items())),
        "difficulties": dict(sorted(Counter(row["difficulty"] for row in rows).items())),
        "human_labels": "confirmed" if require_human_labels else "pending",
    }


def request_score(url: str, token: str, sample: dict[str, Any], timeout: float) -> tuple[int | None, dict[str, Any]]:
    body = json.dumps({
        "userMessage": sample["user_message"],
        "assistantContext": sample["assistant_context"],
    }, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json; charset=utf-8",
        "X-MindPet-Eval-Token": token,
    })
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read().decode("utf-8"))
        except Exception:
            payload = {"status": "FAILED", "code": "NON_JSON_HTTP_ERROR"}
        return exc.code, payload
    except Exception as exc:
        return None, {
            "status": "FAILED",
            "code": "REQUEST_ERROR",
            "message": f"{type(exc).__name__}: {exc}",
        }


def base_record(sample: dict[str, Any]) -> dict[str, Any]:
    return {
        "sample_id": sample["sample_id"],
        "user_message": sample["user_message"],
        "assistant_context": sample["assistant_context"],
        "category": sample["category"],
        "difficulty": sample["difficulty"],
        "human_importance": sample["human_importance"],
        "human_should_remember": sample["human_should_remember"],
        "human_high_importance": sample["human_high_importance"],
        "annotation_reason": sample["annotation_reason"],
        "review_status": sample["review_status"],
    }


def normalize_success(sample: dict[str, Any], http_status: int, payload: dict[str, Any]) -> dict[str, Any]:
    required = {
        "status", "model", "worthRemembering", "shouldRemember", "importance",
        "confidence", "highImportance", "wouldPersistMemory", "parse",
    }
    if http_status != 200 or payload.get("status") != "OK" or not required.issubset(payload):
        raise RunFailure("HTTP/API status or response fields are invalid")
    importance = payload["importance"]
    confidence = payload["confidence"]
    parse = payload["parse"]
    parse_required = {
        "succeeded", "memoryObjectPresent", "importanceFallbackUsed",
        "confidenceFallbackUsed", "importanceClamped", "confidenceClamped",
    }
    if not isinstance(importance, (int, float)) or not 0.0 <= importance <= 1.0:
        raise RunFailure("invalid AI importance")
    if not isinstance(confidence, (int, float)) or not 0.0 <= confidence <= 1.0:
        raise RunFailure("invalid AI confidence")
    if not isinstance(parse, dict) or not parse_required.issubset(parse):
        raise RunFailure("parse diagnostics are incomplete")
    if not all(isinstance(parse[field], bool) for field in parse_required):
        raise RunFailure("parse diagnostics are not boolean")
    for field in ("worthRemembering", "shouldRemember", "highImportance", "wouldPersistMemory"):
        if not isinstance(payload[field], bool):
            raise RunFailure(f"invalid {field}")
    if payload["highImportance"] != (importance >= 0.6):
        raise RunFailure("inconsistent AI highImportance")
    if not isinstance(payload["model"], str) or not payload["model"].strip():
        raise RunFailure("production model name is missing")
    return {
        **base_record(sample),
        "ai_importance": float(importance),
        "ai_should_remember": payload["shouldRemember"],
        "ai_confidence": float(confidence),
        "ai_high_importance": payload["highImportance"],
        "ai_would_persist": payload["wouldPersistMemory"],
        "worthRemembering": payload["worthRemembering"],
        "parse_success": parse["succeeded"],
        "memory_object_present": parse["memoryObjectPresent"],
        "importance_fallback_used": parse["importanceFallbackUsed"],
        "confidence_fallback_used": parse["confidenceFallbackUsed"],
        "importance_clamped": parse["importanceClamped"],
        "confidence_clamped": parse["confidenceClamped"],
        "model": payload["model"],
        "http_status": http_status,
        "api_status": payload["status"],
        "error_code": None,
        "error_message": None,
    }


def normalize_attempt(sample: dict[str, Any], http_status: int | None, payload: dict[str, Any]) -> dict[str, Any]:
    try:
        return normalize_success(sample, http_status, payload)  # type: ignore[arg-type]
    except (RunFailure, KeyError, TypeError) as exc:
        return {
            **base_record(sample),
            "ai_importance": None,
            "ai_should_remember": None,
            "ai_confidence": None,
            "ai_high_importance": None,
            "ai_would_persist": None,
            "worthRemembering": None,
            "parse_success": None,
            "memory_object_present": None,
            "importance_fallback_used": None,
            "confidence_fallback_used": None,
            "importance_clamped": None,
            "confidence_clamped": None,
            "model": payload.get("model"),
            "http_status": http_status,
            "api_status": payload.get("status", "FAILED"),
            "error_code": payload.get("code", "INVALID_RESPONSE"),
            "error_message": payload.get("message", str(exc)),
        }


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as handle:
            for row in rows:
                handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def git_commit(project_root: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(project_root), "rev-parse", "HEAD"],
            text=True,
            encoding="utf-8",
        ).strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise RunFailure(f"cannot determine git commit: {exc}") from exc


def self_test(project_root: Path) -> None:
    assert prompt_sha256(
        project_root / "MindPet-java/src/main/java/service/KnowledgeGraphService.java"
    )
    sample = {
        "sample_id": "i001", "user_message": "x", "assistant_context": "",
        "category": "stable_preference", "difficulty": "easy",
        "human_importance": 0.7, "human_should_remember": True,
        "human_high_importance": True, "annotation_reason": "x",
        "review_status": "confirmed",
    }
    payload = {
        "status": "OK", "model": "test-model", "worthRemembering": True,
        "shouldRemember": True, "importance": 0.7, "confidence": 0.9,
        "highImportance": True, "wouldPersistMemory": True,
        "parse": {
            "succeeded": True, "memoryObjectPresent": True,
            "importanceFallbackUsed": False, "confidenceFallbackUsed": False,
            "importanceClamped": False, "confidenceClamped": False,
        },
    }
    record = normalize_attempt(sample, 200, payload)
    assert record["api_status"] == "OK" and record["ai_importance"] == 0.7


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    project_root = root.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=root / "datasets/importance/importance_samples.jsonl")
    parser.add_argument("--api-url", default="http://127.0.0.1:8081/api/eval/importance/score")
    parser.add_argument("--raw-output", type=Path,
                        default=root / "results/importance_h3a/raw/importance_predictions.jsonl")
    parser.add_argument("--manifest", type=Path,
                        default=root / "results/importance_h3a/raw/run_manifest.json")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--validate-template", action="store_true")
    parser.add_argument("--preflight", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test(project_root)
            print("importance runner self-test passed")
            return
        samples = read_jsonl(args.dataset)
        validation = validate_dataset(samples, require_human_labels=not args.validate_template)
        if args.validate_template or args.preflight:
            print(json.dumps(validation, ensure_ascii=False, indent=2))
            return
        token = os.environ.get("MINDPET_EVAL_API_TOKEN")
        if not token:
            raise RunFailure("MINDPET_EVAL_API_TOKEN is required")
        if args.raw_output.exists() or args.manifest.exists():
            raise RunFailure("formal output already exists; refusing to overwrite")
        java_source = project_root / "MindPet-java/src/main/java/service/KnowledgeGraphService.java"
        prompt_hash = prompt_sha256(java_source)
        commit = git_commit(project_root)
        dataset_hash = sha256_file(args.dataset)
        started = now_utc()
        records: list[dict[str, Any]] = []
        for index, sample in enumerate(samples, start=1):
            http_status, payload = request_score(args.api_url, token, sample, args.timeout)
            records.append(normalize_attempt(sample, http_status, payload))
            if index == 1 or index % 10 == 0:
                print(f"importance scoring progress: {index}/100", flush=True)
        if len(records) != EXPECTED_COUNT:
            raise RunFailure("formal result count is not 100")
        success_count = sum(
            record["http_status"] == 200 and record["api_status"] == "OK"
            for record in records
        )
        failure_count = EXPECTED_COUNT - success_count
        models = {record["model"] for record in records if record["model"]}
        production_model = next(iter(models)) if len(models) == 1 else None
        write_jsonl(args.raw_output, records)
        write_json(args.manifest, {
            "experiment": EXPERIMENT,
            "git_commit": commit,
            "dataset_version": DATASET_VERSION,
            "dataset_sha256": dataset_hash,
            "samples": EXPECTED_COUNT,
            "formal_requests": EXPECTED_COUNT,
            "success_count": success_count,
            "failure_count": failure_count,
            "production_model": production_model,
            "prompt_sha256": prompt_hash,
            "start_time": started,
            "end_time": now_utc(),
            "db_write": False,
            "ground_truth": "confirmed",
            "api_url": args.api_url,
        })
        if failure_count:
            raise RunFailure(f"formal run completed with {failure_count} failed requests; no reruns performed")
        print("H3-A RUN PASSED")
    except RunFailure as exc:
        print(f"H3-A RUN FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
