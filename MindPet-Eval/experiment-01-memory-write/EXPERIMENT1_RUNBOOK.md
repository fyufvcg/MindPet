# Experiment 1 Runbook

## 1. Research question

Experiment 1 measures whether the production completed-turn memory-write path makes the correct long-term-memory decision and produces the expected knowledge graph. Prompt V2 changes only `KnowledgeGraphService.EXTRACTION_PROMPT`; parser, thresholds, fallback, schema, persistence, SQLite infrastructure, dataset, and evaluator remain controlled.

## 2. Existing evidence

- H3-A is the completed 100-sample preliminary importance/ShouldRemember benchmark.
- The old 30-sample Pilot is a real end-to-end run made against the earlier PostgreSQL storage path.
- Prompt V1 is the historical extraction prompt, SHA-256 `a2f27c59eb39499dc6682bb7e927afc0e19f87013559c0aeacf3c2ef8cb002c9`.
- Prompt V2 separates the long-term-memory decision from KG extraction and handles bounded short-term schedules, SHA-256 `cafa86f6e08703a60f236f4f19b371c3a79df917e74133aacce2377ee06e627e`.

The old PostgreSQL Pilot and a new SQLite V2 run do not isolate the Prompt: both Prompt and persistence environment would differ. Therefore V1 must be rerun on the current SQLite Evaluation infrastructure before comparing it with V2.

## 3. Frozen branches and controls

| Variant | Branch | Prompt SHA-256 |
|---|---|---|
| V1 | `experiment/e1-prompt-v1` | `a2f27c59eb39499dc6682bb7e927afc0e19f87013559c0aeacf3c2ef8cb002c9` |
| V2 | `experiment/mindpet-evaluation` | `cafa86f6e08703a60f236f4f19b371c3a79df917e74133aacce2377ee06e627e` |

The launcher fails closed unless the current branch, clean working tree, Prompt hash, fixed DeepSeek provider/model/endpoint, inference controls, and new SQLite path satisfy the variant contract. It never changes branches automatically. Both variants are fixed to provider `deepseek`, model `deepseek-flash`, endpoint identifier `deepseek@api.deepseek.com`, and temperature `0.8`; Ark endpoints and `ep-...` models are rejected.

The launcher verifies the non-secret provider, model, and base URL metadata in the active Electron configuration at `%APPDATA%\mindpet\system_llm_config.json`. Electron keeps the normal-chat credential in its encrypted secret store, which this standalone launcher does not decrypt. Before an Execute run, supply the same DeepSeek credential only through the current process environment:

```powershell
$env:MINDPET_LLM_API_KEY = '<set securely for this shell>'
```

Do not echo this value or place it in Git, a manifest, or a result directory. The launcher maps it internally to the Java properties used by `llm.api.key` and Spring AI, but records only the credential source name. Preflight does not require a real credential because it never calls ingest or an LLM.

## 4. V1 no-AI preflight

```powershell
Set-Location D:\MindPet-exp
git switch experiment/e1-prompt-v1
git status --porcelain
& .\MindPet-Eval\experiment-01-memory-write\scripts\run_experiment1_pilot30.ps1 `
  -Variant v1 `
  -Mode Preflight `
  -ModelId 'deepseek-flash'
```

Preflight never calls `/api/eval/memory/ingest`. It checks token rejection, fixed user, Prompt hash, model, canonical database path, empty tables, reset, and post-reset emptiness, then stops the backend.

## 5. V1 formal 30-sample run

Run this only after the V1 preflight succeeds and `MINDPET_LLM_API_KEY` is set in the current process:

```powershell
Set-Location D:\MindPet-exp
git switch experiment/e1-prompt-v1
& .\MindPet-Eval\experiment-01-memory-write\scripts\run_experiment1_pilot30.ps1 `
  -Variant v1 `
  -Mode Execute `
  -ModelId 'deepseek-flash'
```

The launcher runs the frozen 30 samples serially, with runner-level fail-fast/no-retry behavior, then runs snapshot verification, evaluation, and error analysis. It never starts V2 automatically.

## 6. V2 no-AI preflight

```powershell
Set-Location D:\MindPet-exp
git switch experiment/mindpet-evaluation
git status --porcelain
& .\MindPet-Eval\experiment-01-memory-write\scripts\run_experiment1_pilot30.ps1 `
  -Variant v2 `
  -Mode Preflight `
  -ModelId 'deepseek-flash'
```

## 7. V2 formal 30-sample run

Use exactly the same model and inference parameters as V1:

```powershell
Set-Location D:\MindPet-exp
git switch experiment/mindpet-evaluation
& .\MindPet-Eval\experiment-01-memory-write\scripts\run_experiment1_pilot30.ps1 `
  -Variant v2 `
  -Mode Execute `
  -ModelId 'deepseek-flash'
```

## 8. Results

Formal results are written below:

```text
03-prompt-v2/pilot30-sqlite/
├─ v1/<run-id>/
└─ v2/<run-id>/
```

Each completed run contains raw ingest results, manifest, sample-to-row mapping, before/after snapshots, database isolation verification, `metrics.json`, analysis tables, and `error-analysis.md`. Evaluation SQLite files live separately under `D:\MindPet-eval-data\experiment-01\v1-<run-id>` or `v2-<run-id>` and are never reused.

## 9. Compare V1 and V2

```powershell
python .\MindPet-Eval\experiment-01-memory-write\scripts\compare_e1_prompt_v1_v2.py `
  --v1-run '<absolute-v1-run-directory>' `
  --v2-run '<absolute-v2-run-directory>' `
  --output-dir '<new-absolute-comparison-directory>'
```

The comparator first validates controls. It marks the comparison `INVALID` and forbids an improvement conclusion if dataset/schema hashes, provider, model, endpoint identifier, credential source, temperature, timeouts, retry policy, fixed user, runner hash, Evaluation infrastructure version, sample count, or sample IDs differ. Prompt hashes must be the frozen V1/V2 hashes. The two SQLite paths must be different.

Outputs are `comparison_summary.json`, `comparison_metrics.csv`, `comparison_cases.csv`, `comparison_special_cases.csv`, and `comparison_report.md`. Strict metrics are formal; normalized Entity/Relation metrics are diagnostic only. The report includes p017, p019, p024 and every improved/same/degraded sample.

## 10. Production database safety

- Never copy, open, inspect, or reuse a production SQLite database.
- Every invocation creates a new randomized Evaluation root and requires the DB file not to exist.
- The launcher compares canonical paths against `C:\Users\Lenovo\.mindpet\mindpet.db`, configured `MINDPET_DATA_DIR`, Electron `USER_DATA_PATH`/`APPDATA` candidates, and the repository development-data candidate without opening them.
- Evaluation is loopback-only, uses a high-entropy temporary token, and clears token-related environment variables in `finally`.
- Preflight cannot call ingest. Execute cannot begin until all guards and the no-AI preflight pass.
- Preflight databases must never be reused for formal V1 or V2 runs.

## 11. Gate before 600 samples

Do not begin the 600-sample experiment until both SQLite Pilot runs are complete, the comparison is `VALID`, the exact model/inference configuration is frozen, no unexplained pipeline failures remain, special samples are reviewed, the 600-sample annotation protocol has independent review, and its dataset plus Ground Truth are frozen. The 600-sample scaffold does not authorize generating labels or running the experiment.
