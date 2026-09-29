# Java Memory Curator experiment

The retained experiment path is the Java runner below. An earlier Python reference simulation was removed because it did not execute MINDPET's production curator. Any metrics recorded from that simulation are historical summaries, not production Memory Curator measurements.

`run_java_experiment.ps1` is the production-logic experiment runner. It lives outside the application source tree, compiles against the current `MindPet-java` classes, creates a new SQLite database under its result directory, and calls the real Java curator services. The default `fixture` mode replays the synthetic proposal through `MemoryCuratorCommitService`, including source/evidence checks, safety filters, temporal normalization, fact merging, profile projection, working-memory/checkpoint updates, and transaction rollback/replay checks. It does not call an LLM.

Previously generated datasets and run outputs have been cleared from the working tree. The result values below remain as historical summaries; the referenced raw result directories are not included. Regenerate the synthetic fixture dataset before running the Java fixture command.

## Run the Java commit experiment

From PowerShell in the repository root:

```powershell
& .\docs\experiments\standalone-memory-curator\run_java_experiment.ps1
```

First generate the 1,200-case synthetic fixture dataset, then run the Java runner:

```powershell
python .\docs\experiments\standalone-memory-curator\generate_synthetic_curator_dataset.py
& .\docs\experiments\standalone-memory-curator\run_java_experiment.ps1
```

This runs all 1,200 generated cases unless `-Limit N` is supplied. Each run gets a fresh result folder and SQLite database. The runner refuses non-synthetic rows and will not overwrite an existing experiment database.

## Run the actual LLM curator on a bounded sample

LLM mode reads `baseUrl` and `model` from `MindPet-java/llm-dynamic-config.json`. For the API key it prefers the local `llm-experiment.local.json` file in this folder, then the `MINDPET_EXPERIMENT_LLM_API_KEY` process environment value, then the application config as a compatibility fallback. The local experiment file is explicitly Git-ignored and takes precedence so repeated experiment runs use the experiment-only key. Optional `MINDPET_EXPERIMENT_LLM_BASE_URL` and `MINDPET_EXPERIMENT_LLM_MODEL` environment values override the application config for that run. The runner passes selected values to Java through temporary environment variables, then restores the previous environment. It never prints the key, adds it to command-line arguments, or writes it into result files. The application config file is not changed.

```powershell
& .\docs\experiments\standalone-memory-curator\run_java_experiment.ps1 -Provider llm -Limit 10
```

LLM mode selects samples evenly across `case_type` when `-Limit` is set. For each sample, it passes up to the latest 15 **user messages** through the production `MemoryCuratorService`; the stored assistant reply is empty, and the runner generates no assistant responses. The fifteenth turn triggers the curator on longer histories; for shorter samples the runner calls the curator's retry entry point after loading the sample. Under normal operation there is one model request per sample; validation repair and network retries can increase the count. The service parses its JSON proposal and commits through the production validation and isolated SQLite services. The external PowerShell wrapper resolves configuration locally, passes it to Java through process environment variables, and restores the prior environment afterward.

Start with ten samples for one case from each of the ten scenario types:

```powershell
& .\docs\experiments\standalone-memory-curator\run_java_experiment.ps1 -Provider llm -Limit 10
```

Then increase to `-Limit 100` for about ten cases per type, review the results, and expand further if quality and cost are acceptable. The runner uses algorithmically generated synthetic text; it does not generate an end-user's responses with a second LLM and does not require real user conversations.

Embedding calls are disabled in both modes; insights and growth entries are still committed with null embeddings. This isolates curator extraction and storage behavior from a separate embedding provider. The application database path is never used. Results are written to `run-config.json`, `metrics.json`, `failures.jsonl`, `stored-memory-snapshots.jsonl`, `applied-proposals.jsonl`, `curator-run-summaries.jsonl`, and `llm-model-responses.jsonl` in the run's output directory. LLM mode records the raw model response, committed memories, and curator rejection summaries, but not the full prompt. Token usage and cost are not currently collected by the Java curator service; check the model provider's usage dashboard. Transaction rollback and replay checks run only in fixture mode, so those metrics are `null` in LLM mode.

## Interpreting results

Fixture mode measures the production Java commit path against known proposal fixtures. It does not evaluate LLM extraction quality. LLM mode evaluates actual extraction plus Java commit behavior for the selected cases, but the sample is small and model-dependent; run a larger, blinded dataset only after reviewing cost and latency. Neither result changes application source code or the application's database.

## Initial 1,200-case Java fixture result before logic fixes

Run: `results/java-fixture-20260929-153907` (1,200 synthetic samples, 19,986 turns; fixture provider, zero LLM calls).

| Metric | Result |
|---|---:|
| Stored fact precision / recall / F1 | 100% / 100% / 100% |
| Current profile precision / recall | 61.8% / 55.3% |
| Exact / ambiguous time handling | 100% / 100% |
| Source completeness | 100% |
| Sensitive-content blocking | 100% |
| Transaction rollback consistency | 100% |
| Same-batch replay consistency | 83.75% (195 of 1,200 changed) |
| Per-sample processing p50 / p95 / p99 | 8 / 21 / 27 ms |

The stored-fact score includes superseded historical rows; current-state quality is measured separately through `user_profile_current`. The synthetic fixtures exposed three application-level issues worth fixing before an LLM evaluation:

1. **Proposal order changes the current profile.** A case containing an older location and a later correction ends with the older location when the proposal lists the later fact first and the older fact second. Resolve conflicting current facts by source-turn time and explicit assertion/confidence policy, never by JSON array order. Test both input orderings and shuffled proposals.
2. **Replaying a superseding proposal can drift.** A superseded row is excluded by the merge lookup, while `INSERT OR IGNORE` can then hit its unique key and return no active row. The projection may remain pointed at a fact whose status changed, and repeated submission is not idempotent. Make merge return a stable existing fact for the same source/evidence and reconcile its status/projection deterministically; add retries of the same batch and fault-injection checks.
3. **Fact scope and profile policy disagree.** A valid `occupation_current` fact with `scope=stable` is stored but not projected, although the fixture expects it in the current profile. Either constrain that predicate to `scope=current` in the curator contract and fixtures, or explicitly allow stable scope for the applicable profile slots.

The profile recall is 315 correct projections out of 570 expected slots (55.3%). The 255 profile mismatches break down into 119 state-conflict cases, 60 negation/correction cases, 50 stable-profile cases, and 26 cross-session cases. Profile precision is 315 correct rows out of 510 rows written (61.8%). Replay drift affected 195 cases: 119 state conflicts, 50 negation/correction cases, and 26 cross-session cases.

These are findings from synthetic fixture proposals through the Java storage path, not measured LLM quality. The earlier Python metrics are reference-pipeline results and must not be presented as Java or Memory Curator results.

## Logic improvement and paired 100-case check

The application logic was updated against the defects listed above. The Java production-service runner then replayed the same 100 generated cases before and after the changes. Both runs used the identical dataset SHA-256 (`60e660ea8b7a5b8107861213596d6751fbf1b26cb529793c46fcccbab1f77d0c`), the same 100 sample IDs (10 cases from each of 10 scenario types), fixture proposals, and separate experiment SQLite databases. `application_database_access` was `false`; the fixture runs made no LLM calls.

| Metric | Before (`logic-improvement-100-before-aligned-20260929`) | After (`logic-improvement-100-final-20260929`) | Change |
|---|---:|---:|---:|
| Stored fact precision / recall / F1 | 100% / 100% / 100% | 100% / 100% / 100% | unchanged |
| Expected profile slots stored correctly | 29 / 45 (64.4%) | 45 / 45 (100%) | +16 correct slots; +35.6 percentage points |
| Profile precision / recall | 64.4% / 64.4% | 100% / 100% | +35.6 percentage points each |
| Same-batch replay consistency | 84 / 100 (84%) | 100 / 100 (100%) | +16 percentage points |
| Transaction rollback consistency | 100 / 100 (100%) | 100 / 100 (100%) | unchanged |
| Exact time / ambiguous-time accuracy | 100% / 100% | 100% / 100% | unchanged |
| Source completeness / sensitive-content blocking | 100% / 100% | 100% / 100% | unchanged |
| Active fact rows / superseded fact rows | 70 / 49 | 70 / 17 | fewer stale superseded rows |
| Processing p50 / p95 / p99 | 8 / 18 / 19 ms | 9 / 18 / 22 ms | p95 unchanged; p50 +1 ms and p99 +3 ms |

The changed logic provides one shared fact ontology for prompt construction, proposal validation, and profile projection; resolves current facts using source event time and turn order; recomputes a profile slot after negation or correction; makes replay of superseded facts idempotent; and retries one repairable proposal error without advancing the checkpoint when a batch is rejected. User timezone configuration is honored, with `Asia/Shanghai` as the default. The generator's `occupation_current` scope was also corrected so the expected fixture matches the curator contract.

The 100-case result supports a clear improvement in deterministic commit, projection, and replay behavior. It does not establish LLM extraction quality: the fixture provider supplies known proposals and bypasses model generation. Its latency figures are storage-path measurements without LLM or embedding calls; this small run is not a production performance benchmark. The unchanged p95 and small p50/p99 changes should be treated as descriptive only.

## Focused real-LLM attempt

The initial `java-llm-logic-improvement-10-20260929` run sent ten samples to `deepseek-flash` through the production curator service. All ten curator runs failed with the provider authorization error (HTTP 401); no active facts were stored. Its 0% recall was an authentication failure, not a valid measure of extraction quality. A later run using an experiment-only credential completed below.

## Java regression tests

After the code changes, `mvn -f MindPet-java/pom.xml test` completed successfully: 16 tests passed, with 0 failures, 0 errors, and 0 skipped. Coverage includes turn/checkpoint handling, fact merge and replay, isolated transaction rollback, temporal normalization, and profile projection. `git diff --check` reported no whitespace errors.

## Fresh 100-case retest with 40 expected profile slots

To check the changes on different inputs, `generate_retest_100_40_profiles.py` creates a new synthetic dataset with 100 samples, ten per scenario type, and exactly 40 expected profile slots across 40 samples. The remaining 60 samples expect no current-profile entry. The dataset has 120 expected facts and 30 expected time results. Its SHA-256 is `4c8bfa126ac2f7921448d5670bc456a7049583dbc2acc33ac33f15e9238009e3`, and it shares no sample IDs with the prior 100-case set.

The Java production-service fixture runner completed all 100 samples with a fresh experiment SQLite database. `application_database_access` was `false`; there were no LLM or embedding calls.

| Metric | Result |
|---|---:|
| Samples completed | 100 / 100 |
| Expected profile slots correctly stored | 40 / 40 |
| Unexpected profile entries in the 60 no-profile cases | 0 |
| Fact precision / recall / F1 | 100% / 100% / 100% |
| Profile precision / recall | 100% / 100% |
| Exact time / ambiguous-time accuracy | 100% / 100% |
| Source completeness / sensitive-content blocking | 100% / 100% |
| Transaction rollback consistency | 100 / 100 |
| Same-batch replay consistency | 100 / 100 |
| Processing p50 / p95 / p99 | 5 / 10 / 17 ms |

All 10 negation cases removed the obsolete current-profile entry, and none of the 10 synthetic credential proposals were stored. This is a fresh-set check of the current deterministic Java commit and projection logic, not a before/after comparison and not an LLM extraction-quality result. Processing timings are descriptive for this fixture run and should not be compared with the previous dataset as a performance gain because the sample composition differs.

Run artifacts are in `results/java-retest-100-profile40-20260929/`; dataset and manifest are in `data/retest-100-expected-profile-40-20260929.jsonl` and `data/retest-100-expected-profile-40-20260929.manifest.json`.

## Real LLM evaluation on the fresh 100-case dataset

The `java-llm-retest-100-profile40-20260929` run used the user's experiment-only credential through a process environment override and called `deepseek-flash` via the production `MemoryCuratorService`. It completed 100 samples and captured 100 model responses. The run used its own SQLite database (`application_database_access=false`); the application config was not changed, and the credential was not written into result artifacts. For later runs, the credential is retained only in the Git-ignored `llm-experiment.local.json`. The dataset remains synthetic.

| Metric | Result |
|---|---:|
| Curator runs completed successfully | 99 / 100 |
| Expected facts matched exactly | 35 / 120 (29.2% recall) |
| Stored fact precision / recall / F1 | 35 / 102 (34.3%) / 29.2% / 31.5% |
| Expected profile entries correctly stored | 8 / 40 (20.0% recall) |
| Profile precision | 8 / 8 (100%) |
| Exact time / ambiguous-time accuracy | 29 / 30 (96.7%) / 19 / 20 (95%) |
| Source completeness / sensitive-content blocking | 100% / 100% |
| Processing p50 / p95 / p99 | 2.62 / 15.11 / 21.18 seconds |
| Mean curator end-to-end latency | 4.94 seconds per sample |

All eight profile entries that were written matched the expected value, but 32 expected profile entries were missed. By profile case type, the model produced 7/10 stable-home values, 1/10 current-occupation values, 0/10 event-time-conflict values, and 0/10 project-correction values. Exact fact matching found 35 true positives, 67 extra facts, and 85 expected facts not recovered. One curator run failed after its proposal was rejected, so the batch-wide extraction result is weak despite valid authentication.

The latency values include real provider calls and Java commit work; the p95 is substantially higher than the median. Token usage and billing cost are not recorded by the current curator service. Rollback and same-batch replay checks are fixture-only and were not run in this LLM mode. This is an actual model-backed test of synthetic conversations, not a test against real user conversation data. These results indicate that improving Java storage alone is insufficient: extraction and profile selection in the curator prompt/model path need further work.

Run artifacts are in `results/java-llm-retest-100-profile40-20260929/`, including the per-sample summaries, failure reasons, and captured model responses. The experiment-only key itself is not present in those files.
