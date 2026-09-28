# MindPet Extraction Prompt V2 Design

## 1. Problem statement

The frozen E2E Pilot v1 showed that long-term-memory decisions and Knowledge Graph extraction were not consistently separated:

- p017 correctly avoided LTM but missed the explicit project-review event.
- p024 correctly avoided LTM but missed the explicit parcel-pickup event and `plans` relation.
- p019 correctly extracted the rehabilitation event and relation but incorrectly persisted a six-week bounded schedule to LTM.

The production parser independently handles `memory`, `entities`, and `relations`; it does not clear KG candidates when `memory` is missing. The leading hypothesis is therefore Prompt-level coupling rather than parser or persistence coupling.

## 2. Production Prompt location and usage

- File: `MindPet-java/src/main/java/service/KnowledgeGraphService.java`
- Class: `service.KnowledgeGraphService`
- Constant: `EXTRACTION_PROMPT`
- Production caller: `callExtractionModel(String userMessage, String assistantMessage)`
- Injection point: `chatClientFactory.build().prompt().system(EXTRACTION_PROMPT).user(data)`
- Model selection: the resulting request is passed through `chatClientFactory.applyCurrentModel(spec)`, so this is the actual system Prompt used by the configured production extraction model, including `deepseek-flash` in the frozen Pilot.

## 3. V1 failure evidence

The original Prompt described one “durable knowledge graph”, instructed the model to exclude temporary requests, and concluded:

```text
If nothing is durable, return {"worthRemembering":false,"entities":[],"relations":[]}.
```

That language allows the model to infer:

```text
not durable enough for LTM => no entities or relations
```

The Pilot evidence is consistent with that interpretation for p017 and p024. For p019, the Prompt did not explain that a schedule with an explicit short-term end condition should remain LTM-negative even when it repeats for several weeks.

## 4. Root cause

The V1 Prompt combined two decisions that production code already treats separately:

1. whether user information should be durable long-term memory;
2. whether the utterance contains explicit entities and relations representable by the current KG schema.

The parser and KG persistence path are not gated by `worthRemembering`. The coupling was linguistic in the Prompt.

## 5. V2 design goals

1. Name Task A (Long-Term Memory Decision) and Task B (Knowledge Graph Extraction) explicitly.
2. State that the two tasks are independent.
3. Preserve the existing JSON shape and all parser field names.
4. Keep the current 11 entity types and 11 predicates unchanged.
5. Treat explicit short-term end conditions as evidence against durable LTM, even when the plan repeats during that period.
6. Continue extracting representable events and plans when LTM is false.
7. Constrain `related_to` to cases where no more specific existing predicate applies.
8. Avoid sample-specific wording or hard-coded Pilot answers.

## 6. Exact behavioral changes

### 6.1 Task A — Long-Term Memory Decision

V2 positively identifies stable personal facts, persistent preferences, long-term goals, stable habits, durable work/study context, likely future utility, recurrence, and explicit durable confirmation.

V2 explicitly lowers LTM propensity for:

- ordinary small talk;
- one-off tasks and events;
- temporary state or emotion;
- uncertain claims and tool results;
- schedules limited to a day, the current week, several days/weeks, or a temporary project;
- plans with an explicit short-term end condition.

The recurring-period rule is general: repetition during a bounded short interval does not by itself make the information durable.

### 6.2 Task B — Knowledge Graph Extraction

V2 requires the model to inspect the utterance for KG candidates independently of Task A. A false `worthRemembering` or false `memory.shouldRemember` no longer implies empty KG arrays.

The Prompt explicitly permits current-schema KG output for an upcoming event, appointment, review, pickup, meeting, temporary schedule, short-term plan, or time-bounded activity.

The existing schema receives two boundary explanations:

- `event`: a specific event that happened or will happen;
- `plans`: an explicit user plan for an event or activity.

### 6.3 Empty-output condition

V2 only permits entities and relations to be simultaneously empty when Task B independently finds no valid structured fact. When Task A is negative, `memory` may remain omitted because the existing parser supports that shape.

### 6.4 Predicate specificity

`related_to` remains available but is no longer a default fallback. The Prompt tells the model to use it only when no more specific allowed predicate applies.

## 7. Explicit non-changes

This controlled experiment does not change:

- JSON field names or object layout;
- parser or validation code;
- missing-memory fallback;
- importance threshold `0.35`;
- confidence threshold `0.45`;
- `shouldPersistMemory()`;
- 11 entity types;
- 11 predicates;
- database schema;
- KG/LTM persistence;
- retention or prune behavior;
- E2E API semantics;
- Evaluator v1;
- Ground Truth or frozen V1 results.

## 8. Risks

1. More short-lived entities and relations may increase KG noise and graph size.
2. The current schema has no temporal validity field, so short-term events cannot express expiry directly.
3. The broader KG instruction may reduce precision if the model over-extracts incidental entities.
4. Prompt changes can alter importance and ShouldRemember distributions beyond the three boundary cases.
5. Constraining `related_to` may improve precision but reduce relation recall if no specific predicate fits.
6. This change does not solve type ambiguity such as trait/habit/occupation; those remain future Schema V2 questions.

## 9. Validation plan

### Static/unit phase

- Compile the Java project.
- Run the complete offline Maven test suite.
- Verify Prompt contract tests for stable preference, one-off task, short-term event, bounded multi-week schedule, pure small talk, JSON/schema compatibility, and `related_to` specificity.
- Audit the diff to ensure only the Prompt, its tests, and evaluation documentation changed.

### Controlled V2 regression phase (not run in this implementation stage)

- Run the same frozen 30 samples exactly once under the same model/database/evaluator conditions.
- Report V1 and V2 separately; never overwrite V1.
- Recompute ShouldRemember, importance, LTM persistence, Strict KG, and Normalized Diagnostic v1 metrics.
- Inspect p017, p019, and p024 explicitly.
- Compare fallback, entity/relation volume, generic `related_to`, duplicate, and prune diagnostics.
- Do not enter the 600-sample experiment until the 30-sample regression is reviewed.

## 10. Prompt hashes

Hashes are calculated from the reconstructed Java text-block runtime value, normalized to LF, using the same extraction function as `MindPet-Eval/scripts/run_importance_accuracy.py`.

| Version | SHA-256 |
|---|---|
| V1 | `a2f27c59eb39499dc6682bb7e927afc0e19f87013559c0aeacf3c2ef8cb002c9` |
| V2 | `cafa86f6e08703a60f236f4f19b371c3a79df917e74133aacce2377ee06e627e` |

## 11. Experiment status

- Implementation: Prompt-only production change plus offline contract tests and this design record.
- Full offline test suite: 130 tests, 130 passed, 0 failed, 0 errors, 0 skipped; Maven build succeeded.
- Static production diff: only the `EXTRACTION_PROMPT` text changed inside `KnowledgeGraphService.java`.
- AI inference: not run.
- E2E database writes: none.
- Formal V2 30-sample regression: not run.
