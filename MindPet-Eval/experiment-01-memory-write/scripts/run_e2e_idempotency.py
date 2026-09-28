"""Replay three completed pilot samples once and verify that all five tables stay unchanged."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from e2e_db import DatabaseConfig, snapshot, verify_isolation, write_json
from run_e2e_pilot import api_request, read_dataset


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--pilot-run-dir", type=Path, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:8082")
    parser.add_argument("--samples", nargs=3, default=["p001", "p011", "p026"])
    parser.add_argument("--timeout", type=float, default=240.0)
    args = parser.parse_args()
    token = os.environ.get("APP_EVAL_E2E_MEMORY_TOKEN", "")
    if not token:
        raise RuntimeError("APP_EVAL_E2E_MEMORY_TOKEN is required")

    manifest = json.loads(
        (args.pilot_run_dir / "raw" / "run_manifest.json").read_text(encoding="utf-8")
    )
    if manifest.get("status") != "COMPLETED" or manifest.get("completed") != 30:
        raise RuntimeError("idempotency replay requires a completed 30-sample pilot")
    run_id = manifest["run_id"]
    rows = {row["sample_id"]: row for row in read_dataset(args.dataset)}
    first_results = {
        row["sample_id"]: row
        for row in (
            json.loads(line)
            for line in (args.pilot_run_dir / "raw" / "ingest_results.jsonl")
                .read_text(encoding="utf-8").splitlines()
            if line.strip()
        )
    }

    output_dir = args.pilot_run_dir / "idempotency"
    if output_dir.exists():
        raise RuntimeError("refusing to overwrite existing idempotency output")
    output_dir.mkdir(parents=True)

    with DatabaseConfig.from_env().connect() as connection:
        before = snapshot(connection)
        results = []
        for sample_id in args.samples:
            sample = rows[sample_id]
            status, response = api_request(
                "POST", args.base_url.rstrip("/") + "/api/eval/memory/ingest", token,
                {
                    "sampleId": sample_id,
                    "runId": run_id,
                    "userId": "e2e_memory_eval_user",
                    "userMessage": sample["user_message"],
                    "assistantContext": sample["assistant_context"],
                    "emotion": "neutral",
                },
                args.timeout,
            )
            if status != 200 or response.get("status") != "NO_PERSIST" or response.get("duplicate") is not True:
                raise RuntimeError(f"{sample_id}: duplicate replay was not rejected idempotently")
            if any(response.get(field) != 0 for field in (
                "entityRowsCreatedOrUpdated", "relationRowsCreatedOrUpdated", "evidenceRowsCreated"
            )) or response.get("ltmPersisted") is not False:
                raise RuntimeError(f"{sample_id}: duplicate replay reported a write")
            results.append({
                "sample_id": sample_id,
                "first_ingest": first_results[sample_id]["response"],
                "second_ingest": response,
            })
        after = snapshot(connection)

    if before != after:
        raise RuntimeError("five-table snapshot changed during idempotency replay")
    verify_isolation(before, after)
    write_json(output_dir / "snapshot_before.json", before)
    write_json(output_dir / "snapshot_after.json", after)
    write_json(output_dir / "idempotency_results.json", {
        "status": "PASSED", "samples": results,
        "all_five_tables_unchanged": True,
    })
    print(f"idempotency verification passed: {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
