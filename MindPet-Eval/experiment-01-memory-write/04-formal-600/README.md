# Experiment 1 Formal-600 Scaffold

This directory defines the data contract for a future 600-sample Experiment 1 benchmark. It does not contain generated samples or Ground Truth and does not authorize a run.

## Composition

The frozen dataset must contain exactly 600 JSON Lines records: 100 records in each category.

1. `stable_fact`
2. `long_term_preference`
3. `long_term_goal`
4. `temporary_state`
5. `one_off_information`
6. `small_talk`

Sample IDs are exactly `e1-0001` through `e1-0600`, unique and ordered. Categories may be interleaved, but every category count must be exactly 100.

## Record format

```json
{
  "dataset_version": "e1-formal-600-v1",
  "sample_id": "e1-0001",
  "user_message": "...",
  "assistant_context": "...",
  "category": "stable_fact",
  "difficulty": "easy",
  "human_should_remember": true,
  "human_importance": 0.7,
  "expected_entities": [
    {
      "entity_key": "entity_1",
      "name": "...",
      "type": "person|project|technology|tool|preference|goal|topic|organization|place|event|other"
    }
  ],
  "expected_relations": [
    {
      "source_entity_key": "user",
      "target_entity_key": "entity_1",
      "predicate": "prefers|dislikes|uses|learns|builds|works_on|plans|knows|experienced|belongs_to|related_to"
    }
  ],
  "annotation_reason": "...",
  "annotation_status": "draft|reviewed|confirmed"
}
```

`expected_entities` and `expected_relations` use the current production schema only. Do not introduce new entity types or predicates as part of dataset creation. A relation endpoint must be `user` or an `entity_key` declared in the same record.

## Annotation and freeze rules

- Human annotators create labels; scripts must never generate Ground Truth.
- `human_should_remember`, `human_importance`, expected KG, category, and difficulty require independent review.
- Allowed importance values are `0.1`, `0.3`, `0.5`, `0.7`, and `0.9`.
- A run-ready row has `annotation_status: confirmed`. Draft or merely reviewed rows cannot enter a frozen experiment.
- Resolve disagreements before freeze and document the adjudication outside the JSONL record.
- Run the validator with `--require-frozen`; retain its reported SHA-256 in the experiment manifest.
- After freeze, any content change creates a new dataset version and a new SHA-256. Never silently edit a frozen dataset or its Ground Truth.
- V1 and V2 must use the identical frozen bytes and SHA-256.

## Validation

```powershell
python ..\scripts\validate_formal_600.py `
  --dataset '<path-to-formal-600.jsonl>' `
  --require-frozen
```

The validator checks structure and freeze readiness only. It does not judge annotation correctness, generate labels, call AI, or run the experiment.
