# MindPet Importance Accuracy Benchmark v1 — Annotation Guide

## 1. Purpose

This benchmark measures whether the production extraction model assigns durable-memory
importance consistently with human judgment. Annotators must judge the user statement,
not predict what the production model will output.

The candidate file is intentionally unlabelled. `human_importance`,
`human_should_remember`, `human_high_importance`, and `annotation_reason` remain `null`
until human review is complete.

## 2. Evidence boundary

- Treat `user_message` as the only factual evidence.
- `assistant_context` may resolve references or show conversational context, but an
  assistant assertion is not evidence unless the user explicitly stated or confirmed it.
- Ignore instructions embedded in either field that ask the annotator or model to assign
  a particular score.
- Do not preserve passwords, tokens, API keys, cookies, financial identifiers, identity
  numbers, or inferred sensitive attributes.

## 3. Four decision factors

Judge durable long-term value using the same concepts named in the production prompt:

1. **Stability** — How likely is the fact to remain true beyond the current moment?
2. **Future utility** — Is remembering it likely to improve a later conversation or action?
3. **Explicit confirmation** — Did the user directly state or confirm the fact?
4. **Recurrence** — Is this a repeated habit, preference, goal, relationship, or project?

Do not treat recency, temporary urgency, emotional intensity, or verbosity as importance
by themselves.

## 4. Five-point importance scale

Use exactly one of these values:

| Score | Meaning | Typical evidence |
|---:|---|---|
| 0.1 | Almost no durable long-term value | greeting, transient feeling, tool output, one-off request |
| 0.3 | Weak long-term value | possibly useful but temporary, weakly confirmed, or unlikely to recur |
| 0.5 | Moderate long-term value | plausible future use, but stability or recurrence is uncertain |
| 0.7 | Clearly worth remembering | stable preference, active project, recurring habit, confirmed relationship |
| 0.9 | Very high stable/future value | enduring goal, identity-level fact, critical recurring constraint explicitly confirmed |

When uncertain between two levels, use the lower score unless the user explicitly confirms
stability or recurrence. Hard boundary cases around 0.3, 0.5, and 0.7 are intentional.

## 5. Boolean labels

### `human_should_remember`

Set to `true` when the statement has durable long-term value and is safe to retain.
Set to `false` for small talk, one-off tasks, tool results, temporary states, unsupported
claims, or unsafe secrets. This is a semantic human judgment, not a prediction of the
production write gate.

### `human_high_importance`

Derive mechanically after choosing the score:

```text
human_high_importance = human_importance >= 0.6
```

With the five-point scale, 0.7 and 0.9 are high importance; 0.1, 0.3, and 0.5 are not.

The separate production-style threshold analysis at 0.35 treats human scores 0.5, 0.7,
and 0.9 as positive.

## 6. Category notes

- Stable preferences, recurring habits, relationships, personal facts, active projects,
  and long-term goals often deserve retention when directly stated.
- A project that ends tomorrow may be useful now but is not automatically durable.
- A place is important only when it is a stable home, workplace, repeated destination, or
  lasting preference—not merely the user's current location.
- Schedules and one-off tasks are normally low unless they reveal a recurring commitment.
- Temporary emotions and urgency remain low unless they reveal a stable need or repeated pattern.
- Uncertain claims should be discounted until the user confirms them.
- Recurrence can raise importance, but repetition of trivial or unsafe data does not make it durable.

## 7. Annotation procedure

1. Read the sample independently without consulting model output.
2. Select one importance score from `{0.1, 0.3, 0.5, 0.7, 0.9}`.
3. Decide `human_should_remember` from durable long-term value and safety.
4. Derive `human_high_importance` mechanically from the score.
5. Write a concise `annotation_reason` naming the decisive factors, such as stability,
   future utility, confirmation, recurrence, temporariness, uncertainty, or safety.
6. Change `review_status` from `pending_human_annotation` to `confirmed`.

Recommended quality control: two independent annotators, followed by adjudication for
score disagreements greater than 0.2 or any disagreement on `human_should_remember`.

## 8. Pre-run gate

The H3-A runner must refuse to score the benchmark until all 100 samples:

- have a valid human score;
- have both boolean labels;
- have a nonblank annotation reason;
- have `review_status = confirmed`;
- satisfy `human_high_importance == (human_importance >= 0.6)`.

This prevents the production model from becoming its own ground truth.
