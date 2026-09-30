#!/usr/bin/env python3
"""Fail-closed formal Experiment 1 orchestrator: one extraction, five DB replays."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import time
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


BRANCH = "experiment/e1-formal-observation"
PROMPT_SHA256 = "1e02c1b13dbb1edfe0984ade5eaa5a3f96ee7da765b9d853abce71f1ddfeb649"
DATASET_SHA256 = "1c80e75e3dbf1e22f8cbeba0e05556b33d8a641b024613244435a1fc12f24ff5"
PROVIDER = "deepseek"
MODEL = "deepseek-flash"
TEMPERATURE = 0.8
ENDPOINT_IDENTIFIER = "deepseek@api.deepseek.com"
BASE_URL = "https://api.deepseek.com/v1"
CHAT_URL = "https://api.deepseek.com/v1/chat/completions"
EMBEDDING_PROVIDER = "ollama"
EMBEDDING_MODEL = "bge-m3"
EMBEDDING_DIMENSION = 1024
EMBEDDING_ENDPOINT = "http://127.0.0.1:11434/api/embed"
EMBEDDING_IDENTIFIER = "ollama@127.0.0.1:11434"
VARIANTS = {
    "b0": "B0_CURRENT_FULL",
    "a0": "A0_DIRECT_SAVE_ALL",
    "a1": "A1_LLM_DECISION_ONLY",
    "b1": "B1_NO_PREDICATE_WHITELIST",
    "b2": "B2_NO_LTM_CONFIDENCE_GATE",
}


class GuardFailure(RuntimeError):
    pass


def fail(message: str) -> None:
    raise GuardFailure(message)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prompt_hash(path: Path) -> str:
    source = path.read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n")
    match = re.search(
        r'private\s+static\s+final\s+String\s+EXTRACTION_PROMPT\s*=\s*"""\n(.*?)^[ \t]*""";',
        source,
        re.MULTILINE | re.DOTALL,
    )
    if not match:
        fail("cannot locate KnowledgeGraphService.EXTRACTION_PROMPT")
    lines = match.group(1).split("\n")
    indents = [len(re.match(r"^[ \t]*", line).group(0)) for line in lines if line.strip()]
    minimum = min(indents)
    prompt = "\n".join(line[minimum:] if len(line) >= minimum else "" for line in lines)
    return hashlib.sha256(prompt.encode("utf-8")).hexdigest()


def run_git(repository: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), *args], text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if completed.returncode:
        fail(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if line.strip():
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError as exc:
                    fail(f"invalid JSONL at line {number}: {exc}")
    return rows


def validate_dataset(path: Path) -> list[dict[str, Any]]:
    if sha256_file(path) != DATASET_SHA256:
        fail("formal dataset SHA-256 mismatch")
    rows = read_jsonl(path)
    expected = [f"f{index:04d}" for index in range(1, 2001)]
    actual = [row.get("sample_id") for row in rows]
    if len(rows) != 2000 or actual != expected or len(set(actual)) != 2000:
        fail("formal dataset must contain exactly f0001..f2000 in order")
    required = {
        "sample_id", "user_message", "assistant_context", "category", "difficulty",
        "store_decision", "human_should_remember", "human_importance", "memory_type",
        "predicate", "value", "time_status", "profile_slot", "sensitivity",
        "evidence_turn", "expected_entities", "expected_relations", "expected_kg_evidence",
        "sensitive_case", "temporal_case", "scenario_tags",
    }
    for row in rows:
        missing = required - row.keys()
        if missing:
            fail(f"formal validator: {row.get('sample_id')} missing {sorted(missing)}")
        if row["evidence_turn"] != row["sample_id"]:
            fail(f"formal validator: evidence_turn mismatch for {row['sample_id']}")
    return rows


def selected_rows(rows: list[dict[str, Any]], start_at: int, limit: int | None) -> list[dict[str, Any]]:
    if start_at < 1 or start_at > len(rows):
        fail("StartAt must be in 1..2000")
    selected = rows[start_at - 1 :]
    if limit is not None:
        if limit < 1:
            fail("Limit must be positive")
        selected = selected[:limit]
    return selected


def http_json(method: str, url: str, token: str | None = None,
              body: dict[str, Any] | None = None, timeout: int = 120) -> tuple[int, Any]:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        request.add_header("X-MindPet-Eval-Token", token)
    try:
        with urlopen(request, timeout=timeout) as response:
            text = response.read().decode("utf-8")
            return response.status, json.loads(text) if text else None
    except HTTPError as exc:
        text = exc.read().decode("utf-8")
        try:
            parsed = json.loads(text) if text else None
        except json.JSONDecodeError:
            parsed = text
        return exc.code, parsed


def ollama_preflight() -> dict[str, Any]:
    try:
        status, tags = http_json("GET", "http://127.0.0.1:11434/api/tags", timeout=5)
    except (OSError, URLError) as exc:
        fail(f"OLLAMA_UNREACHABLE: {type(exc).__name__}: {exc}")
    if status != 200:
        fail(f"OLLAMA_UNREACHABLE: HTTP {status}")
    names = [str(item.get("name") or item.get("model") or "") for item in tags.get("models", [])]
    if not any(name == EMBEDDING_MODEL or name.startswith(EMBEDDING_MODEL + ":") for name in names):
        fail("BGE_M3_MISSING")
    status, result = http_json(
        "POST", EMBEDDING_ENDPOINT,
        body={"model": EMBEDDING_MODEL, "input": "MindPet Experiment 1 formal preflight",
              "truncate": True, "keep_alive": "30m"}, timeout=30,
    )
    if status != 200 or not result.get("embeddings"):
        fail(f"EMBEDDING_REQUEST_FAILED: HTTP {status}")
    dimension = len(result["embeddings"][0])
    if dimension != EMBEDDING_DIMENSION:
        fail(f"EMBEDDING_DIMENSION_MISMATCH: expected 1024, actual {dimension}")
    return {"provider": EMBEDDING_PROVIDER, "model": EMBEDDING_MODEL,
            "dimension": dimension, "endpoint_identifier": EMBEDDING_IDENTIFIER}


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def canonical(path: Path) -> Path:
    return path.expanduser().resolve()


def production_paths(repository: Path) -> set[Path]:
    paths = {canonical(Path.home() / ".mindpet" / "mindpet.db"),
             canonical(repository / "data" / "backend" / "mindpet.db")}
    if os.getenv("MINDPET_DATA_DIR"):
        paths.add(canonical(Path(os.environ["MINDPET_DATA_DIR"]) / "mindpet.db"))
    if os.getenv("USER_DATA_PATH"):
        paths.add(canonical(Path(os.environ["USER_DATA_PATH"]) / "backend" / "mindpet.db"))
    if os.getenv("APPDATA"):
        paths.add(canonical(Path(os.environ["APPDATA"]) / "mindpet" / "backend" / "mindpet.db"))
    return paths


def variant_database_paths(run_dir: Path) -> dict[str, Path]:
    paths = {variant: canonical(run_dir / variant / f"{variant}.db") for variant in VARIANTS}
    if len(set(paths.values())) != len(VARIANTS):
        fail("variant databases must be isolated")
    return paths


class Backend:
    def __init__(self, jar: Path, config: Path, phase_dir: Path, database: Path,
                 allowed_root: Path, credential: str, timeout: int = 30):
        self.phase_dir = phase_dir
        self.database = canonical(database)
        self.token = secrets.token_urlsafe(32)
        self.port = free_port()
        self.base_url = f"http://127.0.0.1:{self.port}"
        self.stdout_path = phase_dir / "backend.stdout.log"
        self.stderr_path = phase_dir / "backend.stderr.log"
        phase_dir.mkdir(parents=True, exist_ok=True)
        (phase_dir / "embedding-config.json").write_text(
            json.dumps({"mode": "OLLAMA", "doubaoApiKey": "", "doubaoEndpoint": "",
                        "doubaoModel": ""}, indent=2), encoding="utf-8")
        environment = os.environ.copy()
        environment.update({
            "APP_EVAL_E2E_MEMORY_ENABLED": "true",
            "APP_EVAL_E2E_MEMORY_TOKEN": self.token,
            "APP_EVAL_E2E_MEMORY_SQLITE_PATH": str(self.database),
            "APP_EVAL_E2E_MEMORY_ALLOWED_ROOT": str(canonical(allowed_root)),
            "APP_STORAGE_SQLITE_PATH": str(self.database),
            "LLM_MODEL": MODEL,
            "MINDPET_LLM_MODEL": MODEL,
            "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL": MODEL,
            "SPRING_AI_OPENAI_CHAT_OPTIONS_TEMPERATURE": str(TEMPERATURE),
            "LLM_API_KEY": credential,
            "MINDPET_LLM_API_KEY": credential,
            "SPRING_AI_OPENAI_API_KEY": credential,
            "LLM_API_URL": CHAT_URL,
            "MINDPET_LLM_CHAT_URL": CHAT_URL,
            "MINDPET_LLM_BASE_URL": BASE_URL,
            "SPRING_AI_OPENAI_BASE_URL": BASE_URL,
            "SPRING_AI_OPENAI_CHAT_COMPLETIONS_PATH": "/chat/completions",
            "SPRING_AI_OPENAI_CONNECT_TIMEOUT": "30s",
            "SPRING_AI_OPENAI_READ_TIMEOUT": "120s",
            "SPRING_AI_RETRY_MAX_ATTEMPTS": "2",
            "SPRING_AI_RETRY_BACKOFF_INITIAL_INTERVAL": "1000",
            "SPRING_AI_RETRY_BACKOFF_MAX_INTERVAL": "5000",
            "APP_EMBEDDING_OLLAMA_ENDPOINT": EMBEDDING_ENDPOINT,
            "APP_EMBEDDING_OLLAMA_MODEL": EMBEDDING_MODEL,
            "APP_EMBEDDING_OLLAMA_KEEP_ALIVE": "30m",
        })
        config_uri = config.as_uri()
        self.stdout = self.stdout_path.open("w", encoding="utf-8")
        self.stderr = self.stderr_path.open("w", encoding="utf-8")
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        self.process = subprocess.Popen(
            ["java", "-jar", str(jar), "--server.address=127.0.0.1",
             f"--server.port={self.port}", f"--spring.config.location={config_uri}",
             f"--app.storage.sqlite.path={self.database}"],
            cwd=phase_dir, env=environment, stdout=self.stdout, stderr=self.stderr,
            creationflags=creationflags,
        )
        try:
            self.wait_ready(timeout)
            self.assert_embedding_provider()
        except Exception:
            self.stop()
            raise

    def wait_ready(self, timeout: int) -> None:
        deadline = time.monotonic() + timeout
        last = "no response"
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                fail(f"backend exited during startup with code {self.process.returncode}")
            try:
                status, _ = http_json("GET", self.base_url + "/api/eval/memory/snapshot", timeout=3)
                last = f"HTTP {status}"
                if status == 401:
                    return
            except Exception as exc:  # readiness reports the final concrete transport error
                last = f"{type(exc).__name__}: {exc}"
            time.sleep(0.5)
        fail(f"backend readiness timeout; expected HTTP 401; last={last}")

    def snapshot(self) -> dict[str, Any]:
        status, body = http_json(
            "GET", self.base_url + "/api/eval/memory/snapshot", self.token, timeout=10)
        if status != 200:
            fail(f"snapshot failed: HTTP {status}")
        return body

    def assert_embedding_provider(self) -> None:
        expected = re.compile(r"Embedding provider.*Ollama/bge-m3.*mode=OLLAMA.*reason=OK_OLLAMA")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            self.stdout.flush()
            text = self.stdout_path.read_text(encoding="utf-8", errors="replace")
            if expected.search(text):
                return
            if self.process.poll() is not None:
                break
            time.sleep(0.25)
        fail("EMBEDDING_BACKEND_PROVIDER_MISMATCH: expected Ollama/bge-m3 OLLAMA-only mode")

    def reset(self) -> dict[str, Any]:
        status, body = http_json(
            "POST", self.base_url + "/api/eval/memory/reset", self.token, {}, timeout=10)
        if status != 200:
            fail(f"reset failed: HTTP {status}")
        return body

    def stop(self) -> None:
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.stdout.close()
        self.stderr.close()
        self.token = ""

    def __enter__(self) -> "Backend":
        return self

    def __exit__(self, *_: object) -> None:
        self.stop()


def assert_empty_snapshot(snapshot: dict[str, Any], database: Path) -> None:
    if snapshot.get("status") != "OK" or snapshot.get("userId") != "e2e_memory_eval_user":
        fail("evaluation snapshot identity mismatch")
    if canonical(Path(snapshot.get("databasePath", ""))) != canonical(database):
        fail("evaluation snapshot database path mismatch")
    if snapshot.get("promptSha256") != PROMPT_SHA256 or snapshot.get("model") != MODEL:
        fail("evaluation snapshot prompt/model mismatch")
    expected = {"long_term_memory", "kg_entity", "kg_relation", "kg_evidence", "kg_turn_ingest"}
    if set(snapshot.get("tables", {})) != expected:
        fail("evaluation snapshot table contract mismatch")
    if any(int(table.get("count", -1)) != 0 for table in snapshot["tables"].values()):
        fail("evaluation database is not empty")


def append_checkpoint(path: Path, row: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def successful_checkpoints(path: Path, extraction: bool = False) -> dict[str, dict[str, Any]]:
    if not path.exists():
        return {}
    completed: dict[str, dict[str, Any]] = {}
    for row in read_jsonl(path):
        if row.get("status") != "SUCCESS":
            continue
        if extraction:
            snapshot = row.get("response", {}).get("snapshot", {})
            digest = snapshot.get("snapshotSha256", "")
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                continue
        completed[row.get("sample_id")] = row
    return completed


def request_payload(sample: dict[str, Any], run_id: str) -> dict[str, Any]:
    return {
        "sampleId": sample["sample_id"], "runId": run_id,
        "userMessage": sample["user_message"],
        "assistantContext": sample["assistant_context"], "emotion": "neutral",
        "occurredAt": "2026-10-01T00:00:00Z",
    }


def extraction_phase(backend: Backend, samples: list[dict[str, Any]], run_id: str,
                     checkpoint: Path, resume: bool) -> dict[str, dict[str, Any]]:
    selected_ids = {sample["sample_id"] for sample in samples}
    existing = successful_checkpoints(checkpoint, extraction=True) if resume else {}
    done = {sample_id: row for sample_id, row in existing.items() if sample_id in selected_ids}
    for sample in samples:
        sample_id = sample["sample_id"]
        if sample_id in done:
            continue
        started = dt.datetime.now(dt.timezone.utc).isoformat()
        try:
            status, body = http_json(
                "POST", backend.base_url + "/api/eval/memory/extract", backend.token,
                request_payload(sample, run_id), timeout=240)
            success = status == 200 and body.get("status") == "SUCCESS"
            row = {"sample_id": sample_id, "status": "SUCCESS" if success else "FAILED",
                   "http_status": status, "started_at_utc": started, "response": body}
        except Exception as exc:
            row = {"sample_id": sample_id, "status": "FAILED", "http_status": None,
                   "started_at_utc": started,
                   "error": {"type": type(exc).__name__, "message": str(exc)}}
        append_checkpoint(checkpoint, row)
        if row["status"] == "SUCCESS":
            done[sample_id] = row
    return done


def replay_phase(backend: Backend, samples: list[dict[str, Any]], run_id: str,
                 checkpoint: Path, extraction: dict[str, dict[str, Any]],
                 variant: str, resume: bool) -> dict[str, dict[str, Any]]:
    selected_ids = {sample["sample_id"] for sample in samples}
    existing = successful_checkpoints(checkpoint) if resume else {}
    done = {sample_id: row for sample_id, row in existing.items() if sample_id in selected_ids}
    for sample in samples:
        sample_id = sample["sample_id"]
        if sample_id in done:
            continue
        extraction_row = extraction.get(sample_id)
        if extraction_row is None:
            append_checkpoint(checkpoint, {"sample_id": sample_id, "status": "FAILED",
                "error": {"type": "MISSING_EXTRACTION", "message": "no successful snapshot"}})
            continue
        payload = request_payload(sample, run_id)
        payload.update({"variant": variant, "snapshot": extraction_row["response"]["snapshot"]})
        try:
            status, body = http_json(
                "POST", backend.base_url + "/api/eval/memory/replay", backend.token,
                payload, timeout=240)
            success = (status == 200 and body.get("status") == "SUCCESS"
                       and body.get("llmCalled") is False
                       and body.get("extractionSnapshotSha256")
                       == payload["snapshot"]["snapshotSha256"]
                       and not body.get("result", {}).get("duplicate", False))
            row = {"sample_id": sample_id, "status": "SUCCESS" if success else "FAILED",
                   "http_status": status, "snapshot_sha256": payload["snapshot"]["snapshotSha256"],
                   "response": body}
        except Exception as exc:
            row = {"sample_id": sample_id, "status": "FAILED", "http_status": None,
                   "snapshot_sha256": payload["snapshot"]["snapshotSha256"],
                   "error": {"type": type(exc).__name__, "message": str(exc)}}
        append_checkpoint(checkpoint, row)
        if row["status"] == "SUCCESS":
            done[sample_id] = row
    return done


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    script = Path(__file__).resolve()
    repository = script.parents[3]
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["Preflight", "Smoke", "Execute"], required=True)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--config", type=Path, default=Path(r"D:\MindPet-local-config\application.yml"))
    parser.add_argument("--dataset", type=Path, default=repository / "MindPet-Eval" / "datasets" /
                        "e2e_memory" / "annotation" / "exp1_formal_2000_v2.jsonl")
    parser.add_argument("--eval-root", type=Path,
                        default=Path(r"D:\MindPet-eval-data\experiment-01-formal"))
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--start-at", type=int, default=1)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--skip-build", action="store_true")
    return parser.parse_args(argv)


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    script = Path(__file__).resolve()
    repository = script.parents[3]
    java_root = repository / "MindPet-java"
    prompt_source = java_root / "src" / "main" / "java" / "service" / "KnowledgeGraphService.java"
    if args.model_id != MODEL:
        fail(f"ModelId must be exactly {MODEL}")
    if run_git(repository, "branch", "--show-current") != BRANCH:
        fail(f"branch must be {BRANCH}")
    if run_git(repository, "status", "--porcelain"):
        fail("working tree must be clean")
    if prompt_hash(prompt_source) != PROMPT_SHA256:
        fail("frozen Prompt SHA-256 mismatch")
    rows = validate_dataset(canonical(args.dataset))
    chosen = selected_rows(rows, args.start_at, args.limit)
    if args.mode == "Smoke" and len(chosen) != 10:
        fail("Smoke mode requires exactly -Limit 10")
    if args.mode == "Execute" and (args.start_at != 1 or args.limit is not None) and not args.resume:
        fail("a partial Execute requires --resume; use Smoke for a fresh limited run")

    config = canonical(args.config)
    if not config.is_file():
        fail("backend configuration file does not exist")
    normal_config_path = Path(os.environ.get("APPDATA", "")) / "mindpet" / "system_llm_config.json"
    if not normal_config_path.is_file():
        fail("normal-chat LLM configuration file does not exist")
    normal = json.loads(normal_config_path.read_text(encoding="utf-8"))
    endpoint_host = re.sub(r"^https?://", "", str(normal.get("baseUrl", ""))).split("/")[0]
    if normal.get("provider") != PROVIDER or normal.get("model") != MODEL or endpoint_host != "api.deepseek.com":
        fail("normal-chat DeepSeek provider/model/endpoint configuration mismatch")
    embedding = ollama_preflight()

    credential = os.getenv("MINDPET_LLM_API_KEY")
    if args.mode == "Preflight":
        credential = credential or ("preflight-no-ai-" + secrets.token_hex(16))
    elif not credential:
        fail("MINDPET_LLM_API_KEY must be set for Smoke/Execute")

    if not args.skip_build:
        completed = subprocess.run(
            ["mvn.cmd", "-q", "-DskipTests", "package", "-f", str(java_root / "pom.xml")],
            cwd=repository, check=False,
        )
        if completed.returncode:
            fail("Maven package failed")
    jars = sorted((java_root / "target").glob("*.jar"), key=lambda path: path.stat().st_mtime,
                  reverse=True)
    jars = [path for path in jars if ".original" not in path.name and not path.name.startswith("original-")]
    if not jars:
        fail("backend jar not found")
    jar = jars[0]

    if args.resume:
        if args.run_dir is None or not args.run_dir.is_dir():
            fail("Resume requires an existing --run-dir")
        run_dir = canonical(args.run_dir)
        run_id = run_dir.name
    else:
        run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(4)
        run_dir = canonical(args.eval_root) / run_id
        if run_dir.exists():
            fail("new formal run directory already exists")
        run_dir.mkdir(parents=True)
    if not canonical(run_dir).is_relative_to(canonical(args.eval_root)):
        fail("run directory must be inside the formal evaluation root")

    for child in ["extraction", "b0", "a0", "a1", "b1", "b2", "reports"]:
        (run_dir / child).mkdir(parents=True, exist_ok=True)
    manifest_path = run_dir / "run_manifest.json"
    extraction_checkpoint = run_dir / "extraction" / "extraction_snapshots.jsonl"
    reusable_rows = successful_checkpoints(extraction_checkpoint, extraction=True) if args.resume else {}
    selected_ids = {row["sample_id"] for row in chosen}
    reusable = sum(sample_id in selected_ids for sample_id in reusable_rows)
    new_inferences = sum(1 for row in chosen if row["sample_id"] not in reusable_rows)
    print(f"samples requiring new inference: {new_inferences}")
    print(f"samples reusable from checkpoint: {reusable}")
    print(f"estimated DeepSeek calls: {new_inferences} (Spring AI max-attempts=2; runner retries=0)")
    if new_inferences > len(chosen):
        fail("estimated DeepSeek calls exceed remaining samples")
    manifest = {
        "status": "PREPARED", "run_id": run_id, "mode": args.mode,
        "branch": BRANCH, "git_commit": run_git(repository, "rev-parse", "HEAD"),
        "prompt_sha256": PROMPT_SHA256, "dataset_sha256": DATASET_SHA256,
        "dataset_count": 2000, "selected_count": len(chosen), "start_at": args.start_at,
        "provider": PROVIDER, "model": MODEL, "endpoint_identifier": ENDPOINT_IDENTIFIER,
        "temperature": TEMPERATURE, "spring_ai_retry_max_attempts": 2,
        "runner_sample_retries": 0, "embedding": embedding,
        "variants": VARIANTS, "single_inference": True,
        "estimated_deepseek_calls": new_inferences,
        "reusable_extractions": reusable, "fixed_eval_user": "e2e_memory_eval_user",
        "profile_pollution_metric": "NOT_APPLICABLE",
        "scope_note": "Experiment 1 excludes MemoryCurator/UserProfile; profile_slot is annotation-only.",
    }
    write_json(manifest_path, manifest)

    preflight_dir = run_dir / "preflight"
    preflight_db = preflight_dir / "preflight.db"
    if preflight_db.exists() and not args.resume:
        fail("preflight database must not exist before startup")
    if canonical(preflight_db) in production_paths(repository):
        fail("production database path rejected")
    with Backend(jar, config, preflight_dir, preflight_db, run_dir, credential) as backend:
        status, _ = http_json("GET", backend.base_url + "/api/eval/memory/snapshot", timeout=5)
        if status != 401:
            fail("no-token evaluation API must return 401")
        status, _ = http_json(
            "GET", backend.base_url + "/api/eval/memory/snapshot", "wrong-" + secrets.token_hex(8),
            timeout=5)
        if status != 401:
            fail("wrong-token evaluation API must return 401")
        assert_empty_snapshot(backend.snapshot(), preflight_db)
        backend.reset()
        assert_empty_snapshot(backend.snapshot(), preflight_db)
    manifest["preflight"] = {"status": "PASSED", "ingest_called": False,
                             "deepseek_called": False}
    if args.mode == "Preflight":
        manifest["status"] = "PREFLIGHT_PASSED"
        write_json(manifest_path, manifest)
        print(str(run_dir))
        return 0

    extraction_db = run_dir / "extraction" / "extraction.db"
    if extraction_db.exists() and not args.resume:
        fail("extraction database must be new")
    with Backend(jar, config, run_dir / "extraction", extraction_db, run_dir, credential) as backend:
        if not args.resume:
            backend.reset()
        extracted = extraction_phase(backend, chosen, run_id, extraction_checkpoint, args.resume)
    if len(extracted) != len(chosen):
        fail("extraction phase incomplete; resume after reviewing checkpoint failures")
    extraction_hashes = {row["response"]["snapshot"]["snapshotSha256"] for row in extracted.values()}
    write_json(run_dir / "extraction_manifest.json", {
        "run_id": run_id, "sample_count": len(chosen), "successful_snapshots": len(extracted),
        "unique_snapshot_count": len(extraction_hashes), "prompt_sha256": PROMPT_SHA256,
        "model": MODEL, "temperature": TEMPERATURE, "deepseek_calls": new_inferences,
        "snapshot_schema": "mindpet-exp1-extraction-v1",
    })

    databases = variant_database_paths(run_dir)
    for directory, variant in VARIANTS.items():
        database = databases[directory]
        checkpoint = run_dir / directory / "results.jsonl"
        if database.exists() and not args.resume:
            fail(f"{directory} database must be new")
        if canonical(database) in production_paths(repository):
            fail(f"{directory} database resolves to a production path")
        with Backend(jar, config, run_dir / directory, database, run_dir, credential) as backend:
            if not args.resume:
                backend.reset()
            completed = replay_phase(
                backend, chosen, run_id, checkpoint, extracted, variant, args.resume)
        if len(completed) != len(chosen):
            fail(f"{variant} replay incomplete")

    evaluator = script.with_name("evaluate_experiment1_formal_2000.py")
    completed = subprocess.run(
        [sys.executable, str(evaluator), "--dataset", str(args.dataset), "--run-dir", str(run_dir)],
        check=False,
    )
    if completed.returncode:
        fail("formal evaluator failed")
    manifest["status"] = "COMPLETED"
    manifest["replay_deepseek_calls"] = 0
    write_json(manifest_path, manifest)
    print(str(run_dir))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GuardFailure as exc:
        print(f"Experiment 1 formal guard failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
