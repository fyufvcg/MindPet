# E2E Memory Pilot Ground Truth Summary

## Freeze status

- Source workbook: `MindPet-Eval/datasets/e2e_memory/annotation/e2e_pilot_annotation_A_reviewed.xlsx`
- Frozen dataset: `MindPet-Eval/datasets/e2e_memory/pilot_30.jsonl`
- Samples: 30
- Confirmed: 30
- Pending: 0
- Ground Truth status: confirmed
- AI scoring: not run
- Database write: not performed

## Label distributions

- shouldRemember=true: 9
- shouldRemember=false: 21
- Importance: 0.1=14, 0.3=4, 0.5=3, 0.7=7, 0.9=2

## Category distribution

- long_term_goal: 5
- long_term_preference: 5
- one_off_information: 5
- small_talk: 5
- stable_fact: 5
- temporary_state: 5

## Knowledge graph Ground Truth

- Expected entity total: 14
- Expected relation total: 12
- ShouldRemember=false with non-empty Expected KG: p017, p019, p024

The E2E Pilot Ground Truth was produced and confirmed by one human annotator.
