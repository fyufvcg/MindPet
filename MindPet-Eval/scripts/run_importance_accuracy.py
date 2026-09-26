"""Run H3-A against the opt-in production importance scoring endpoint."""

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


EXPECTED_COUNT = 100
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
        "categories": dict(sorted(Counter(row["category"] for row in rows).items())),
        "difficulties": dict(sorted(Counter(row["difficulty"] for row in rows).items())),
        "human_labels": "confirmed" if require_human_labels else "pending",
    }


def post_score(url: str, token: str, sample: dict[str, Any], timeout: float) -> dict[str, Any]:
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
            payload = json.loads(response.read().decode("utf-8"))
            status = response.status
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read().decode("utf-8"))
        except Exception:
            payload = {"status": "FAILED", "code": "NON_JSON_HTTP_ERROR"}
        raise RunFailure(
            f"{sample['sample_id']}: HTTP {exc.code}, code={payload.get('code')}"
        ) from exc
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RunFailure(f"{sample['sample_id']}: request/JSON failure: {exc}") from exc
    if status != 200 or payload.get("status") != "OK":
        raise RunFailure(f"{sample['sample_id']}: HTTP/status failure")
    return payload


def normalized(sample: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
    required = {
        "model", "worthRemembering", "shouldRemember", "importance", "confidence",
        "highImportance", "wouldPersistMemory", "parse",
    }
    if not required.issubset(payload):
        raise RunFailure(f"{sample['sample_id']}: response fields are incomplete")
    importance = payload["importance"]
    confidence = payload["confidence"]
    if not isinstance(importance, (int, float)) or not 0.0 <= importance <= 1.0:
        raise RunFailure(f"{sample['sample_id']}: invalid AI importance")
    if not isinstance(confidence, (int, float)) or not 0.0 <= confidence <= 1.0:
        raise RunFailure(f"{sample['sample_id']}: invalid AI confidence")
    if payload["highImportance"] != (importance >= 0.6):
        raise RunFailure(f"{sample['sample_id']}: inconsistent AI highImportance")
    if not isinstance(payload["model"], str) or not payload["model"].strip():
        raise RunFailure(f"{sample['sample_id']}: production model name is missing")
    return {
        **sample,
        "http_status": 200,
        "api_status": payload["status"],
        "production_model": payload["model"],
        "worth_remembering": payload["worthRemembering"],
        "ai_should_remember": payload["shouldRemember"],
        "ai_importance": importance,
        "ai_confidence": confidence,
        "ai_high_importance": payload["highImportance"],
        "ai_would_persist_memory": payload["wouldPersistMemory"],
        "parse": payload["parse"],
    }


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8")
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


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path,
                        default=root / "datasets/importance/importance_samples.jsonl")
    parser.add_argument("--api-url",
                        default="http://127.0.0.1:8082/api/eval/importance/score")
    parser.add_argument("--raw-output", type=Path,
                        default=root / "results/importance_h3a/raw/importance_scores.jsonl")
    parser.add_argument("--manifest", type=Path,
                        default=root / "results/importance_h3a/raw/run_manifest.json")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--validate-template", action="store_true")
    args = parser.parse_args()
    try:
        samples = read_jsonl(args.dataset)
        validation = validate_dataset(samples, require_human_labels=not args.validate_template)
        if args.validate_template:
            print(json.dumps(validation, ensure_ascii=False, indent=2))
            return
        token = os.environ.get("MINDPET_EVAL_API_TOKEN")
        if not token:
            raise RunFailure("MINDPET_EVAL_API_TOKEN is required")
        if args.raw_output.exists() or args.manifest.exists():
            raise RunFailure("formal output already exists; refusing to overwrite")
        started = now_utc()
        records = []
        for index, sample in enumerate(samples, start=1):
            records.append(normalized(sample, post_score(
                args.api_url, token, sample, args.timeout)))
            if index == 1 or index % 10 == 0:
                print(f"importance scoring progress: {index}/100", flush=True)
        if len(records) != EXPECTED_COUNT:
            raise RunFailure("formal result count is not 100")
        models = {record["production_model"] for record in records}
        if len(models) != 1:
            raise RunFailure("production model changed during the formal run")
        project_root = Path(__file__).resolve().parents[2]
        try:
            git_commit = subprocess.check_output(
                ["git", "-C", str(project_root), "rev-parse", "HEAD"],
                text=True, encoding="utf-8",
            ).strip()
        except (OSError, subprocess.CalledProcessError) as exc:
            raise RunFailure(f"cannot determine git commit: {exc}") from exc
        write_jsonl(args.raw_output, records)
        write_json(args.manifest, {
            "experiment": "importance_accuracy_benchmark_v1",
            "dataset": "Importance Accuracy Benchmark v1",
            "git_commit": git_commit,
            "production_model": next(iter(models)),
            "samples": EXPECTED_COUNT,
            "formal_requests": EXPECTED_COUNT,
            "success_count": EXPECTED_COUNT,
            "failure_count": 0,
            "api_url": args.api_url,
            "start_time": started,
            "end_time": now_utc(),
            "ground_truth": "confirmed",
            "database_writes": False,
        })
        print("H3-A RUN PASSED")
    except RunFailure as exc:
        print(f"H3-A RUN FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
