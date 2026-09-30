import fs from "node:fs/promises";
const artifactToolModule = process.env.ARTIFACT_TOOL_MODULE || "@oai/artifact-tool";
const { SpreadsheetFile, Workbook } = await import(artifactToolModule);

const [, , payloadPath, outputPath, previewDir] = process.argv;
if (!payloadPath || !outputPath || !previewDir) {
  throw new Error("usage: build_exp1_formal_2000_v2_workbook.mjs <payload.json> <output.xlsx> <preview-dir>");
}

const payload = JSON.parse(await fs.readFile(payloadPath, "utf8"));
const workbook = Workbook.create();
const review = workbook.worksheets.add("Review");
const summary = workbook.worksheets.add("Summary");
const qc = workbook.worksheets.add("QC_200");
const reference = workbook.worksheets.add("Reference");
const fontName = "Microsoft YaHei";
const navy = "#16324F";
const blue = "#1F4E78";
const teal = "#0F766E";
const paleBlue = "#EAF2F8";
const paleGold = "#FFF2CC";
const paleRed = "#FCE8E6";
const paleGreen = "#E2F0D9";
const grid = "#D9E2F3";
const jsonFields = new Set(["predicate", "value", "expected_entities", "expected_relations", "scenario_tags"]);

function excelColumn(index) {
  let value = index + 1;
  let output = "";
  while (value > 0) {
    value -= 1;
    output = String.fromCharCode(65 + (value % 26)) + output;
    value = Math.floor(value / 26);
  }
  return output;
}

function workbookValue(field, value) {
  if (jsonFields.has(field)) return JSON.stringify(value);
  if (value === null || value === undefined) return "";
  return value;
}

const reviewValues = [payload.fields];
for (const row of payload.rows) {
  reviewValues.push(payload.fields.map((field) => workbookValue(field, row[field])));
}
review.getRange(`A1:AA${reviewValues.length}`).values = reviewValues;
review.tables.add(`A1:AA${reviewValues.length}`, true, "FormalReviewV2");
review.freezePanes.freezeRows(1);
review.freezePanes.freezeColumns(1);
review.showGridLines = false;
review.getRange("A1:AA1").format = {
  fill: navy,
  font: { name: fontName, bold: true, color: "#FFFFFF", size: 10 },
  wrapText: true,
  verticalAlignment: "center",
  horizontalAlignment: "center",
};
review.getRange(`A2:AA${reviewValues.length}`).format.font = { name: fontName, size: 9 };
review.getRange(`A2:AA${reviewValues.length}`).format.verticalAlignment = "top";
review.getRange(`B2:C${reviewValues.length}`).format.wrapText = true;
review.getRange(`J2:K${reviewValues.length}`).format.wrapText = true;
review.getRange(`P2:Q${reviewValues.length}`).format.wrapText = true;
review.getRange(`U2:V${reviewValues.length}`).format.wrapText = true;
review.getRange(`AA2:AA${reviewValues.length}`).format.wrapText = true;
review.getRange(`H2:H${reviewValues.length}`).format.numberFormat = "0.0";
review.getRange(`X2:X${reviewValues.length}`).dataValidation = {
  rule: { type: "list", values: ["pending_human_review", "confirmed", "adjudicated", "excluded"] },
};
review.getRange(`Y2:Z${reviewValues.length}`).format.fill = paleGold;
review.getRange(`X2:X${reviewValues.length}`).format.fill = paleGold;
review.getRange(`AA2:AA${reviewValues.length}`).format.fill = paleGold;
for (const [column, width] of Object.entries({
  A: 12, B: 42, C: 32, D: 22, E: 11, F: 14, G: 20, H: 12, I: 18,
  J: 24, K: 28, L: 13, M: 18, N: 16, O: 14, P: 46, Q: 52, R: 19,
  S: 15, T: 14, U: 34, V: 42, W: 13, X: 24, Y: 15, Z: 15, AA: 34,
})) {
  review.getRange(`${column}:${column}`).format.columnWidth = width;
}
review.getRange("1:1").format.rowHeight = 32;
review.tabColor = blue;

summary.showGridLines = false;
summary.mergeCells("A1:H1");
summary.getRange("A1").values = [["Experiment 1 Formal 2000 v2 — Review Dashboard"]];
summary.getRange("A1:H1").format = {
  fill: navy,
  font: { name: fontName, bold: true, color: "#FFFFFF", size: 16 },
  verticalAlignment: "center",
};
summary.getRange("A3:H3").merge();
summary.getRange("A3").values = [[
  "FORMAL_CANDIDATE_V2_READY_FOR_HUMAN_QC — Review is the single source of truth. Do not call this final Gold until human QC is complete.",
]];
summary.getRange("A3:H3").format = { fill: paleGold, font: { name: fontName, bold: true, color: "#7F6000" }, wrapText: true };
summary.getRange("A5:D5").values = [["Metric", "Count", "Metric", "Count"]];
summary.getRange("A5:D5").format = { fill: blue, font: { name: fontName, bold: true, color: "#FFFFFF" } };
summary.getRange("A6:A11").values = [["Total samples"], ["Pending review"], ["ShouldRemember=TRUE"], ["KG Evidence=TRUE"], ["Sensitive=TRUE"], ["Temporal=TRUE"]];
summary.getRange("B6:B11").formulas = [[
  "=COUNTA(Review!$A$2:$A$2001)",
], [
  '=COUNTIF(Review!$X$2:$X$2001,"pending_human_review")',
], [
  "=COUNTIF(Review!$G$2:$G$2001,1)",
], [
  "=COUNTIF(Review!$R$2:$R$2001,1)",
], [
  "=COUNTIF(Review!$S$2:$S$2001,1)",
], [
  "=COUNTIF(Review!$T$2:$T$2001,1)",
]];
summary.getRange("C6:C11").values = [["QC sample count"], ["High priority"], ["Medium priority"], ["Low priority"], ["Pilot overlap"], ["Known issue total"]];
summary.getRange("D6:D11").formulas = [[
  "=COUNTA(QC_200!$A$4:$A$203)",
], [
  '=COUNTIF(Review!$W$2:$W$2001,"High")',
], [
  '=COUNTIF(Review!$W$2:$W$2001,"Medium")',
], [
  '=COUNTIF(Review!$W$2:$W$2001,"Low")',
], [
  "=0",
], [
  "=SUM(B31:B36)",
]];

summary.getRange("A13:F13").values = [["Category", "Count", "Remember TRUE", "KG TRUE", "Sensitive", "Temporal"]];
summary.getRange("A13:F13").format = { fill: teal, font: { name: fontName, bold: true, color: "#FFFFFF" } };
const categories = ["stable_fact", "long_term_preference", "long_term_goal", "temporary_state", "one_off_information", "small_talk"];
summary.getRange("A14:A19").values = categories.map((value) => [value]);
for (let row = 14; row <= 19; row += 1) {
  summary.getRange(`B${row}:F${row}`).formulas = [[
    `=COUNTIF(Review!$D$2:$D$2001,$A${row})`,
    `=COUNTIFS(Review!$D$2:$D$2001,$A${row},Review!$G$2:$G$2001,1)`,
    `=COUNTIFS(Review!$D$2:$D$2001,$A${row},Review!$R$2:$R$2001,1)`,
    `=COUNTIFS(Review!$D$2:$D$2001,$A${row},Review!$S$2:$S$2001,1)`,
    `=COUNTIFS(Review!$D$2:$D$2001,$A${row},Review!$T$2:$T$2001,1)`,
  ]];
}

summary.getRange("A21:B21").values = [["Difficulty", "Count"]];
summary.getRange("D21:E21").values = [["Time status", "Count"]];
summary.getRange("A21:B21").format = { fill: blue, font: { name: fontName, bold: true, color: "#FFFFFF" } };
summary.getRange("D21:E21").format = { fill: blue, font: { name: fontName, bold: true, color: "#FFFFFF" } };
summary.getRange("A22:A24").values = [["easy"], ["medium"], ["hard"]];
summary.getRange("B22:B24").formulas = [["=COUNTIF(Review!$E$2:$E$2001,A22)"], ["=COUNTIF(Review!$E$2:$E$2001,A23)"], ["=COUNTIF(Review!$E$2:$E$2001,A24)"]];
summary.getRange("D22:D26").values = [["none"], ["past"], ["current"], ["future"], ["mixed"]];
summary.getRange("E22:E26").formulas = [["=COUNTIF(Review!$L$2:$L$2001,D22)"], ["=COUNTIF(Review!$L$2:$L$2001,D23)"], ["=COUNTIF(Review!$L$2:$L$2001,D24)"], ["=COUNTIF(Review!$L$2:$L$2001,D25)"], ["=COUNTIF(Review!$L$2:$L$2001,D26)"]];

summary.getRange("A29:B29").values = [["Known issue scanner", "Final count"]];
summary.getRange("A29:B29").format = { fill: "#9C0006", font: { name: fontName, bold: true, color: "#FFFFFF" } };
const scannerEntries = Object.entries(payload.metadata.known_issue_scanners);
summary.getRange(`A30:B${29 + scannerEntries.length}`).values = scannerEntries;

summary.getRange("D29:E29").values = [["Frozen artifact", "Value"]];
summary.getRange("D29:E29").format = { fill: teal, font: { name: fontName, bold: true, color: "#FFFFFF" } };
summary.getRange("D30:E34").values = [
  ["Dataset version", payload.metadata.dataset_version],
  ["Dataset SHA-256", payload.metadata.dataset_sha256],
  ["Schema version", payload.metadata.schema_version],
  ["Frozen Prompt HEAD", payload.metadata.frozen_prompt_head],
  ["Prompt SHA-256", payload.metadata.prompt_sha256],
];
summary.getRange("A1:H40").format.font = { name: fontName, size: 10 };
summary.getRange("A1:H40").format.verticalAlignment = "center";
summary.getRange("A1:H40").format.borders = { preset: "inside", style: "thin", color: grid };
summary.getRange("A:A").format.columnWidth = 30;
summary.getRange("B:B").format.columnWidth = 15;
summary.getRange("C:C").format.columnWidth = 28;
summary.getRange("D:D").format.columnWidth = 26;
summary.getRange("E:E").format.columnWidth = 72;
summary.getRange("F:H").format.columnWidth = 14;
summary.getRange("3:3").format.rowHeight = 44;
summary.freezePanes.freezeRows(3);
summary.tabColor = teal;

qc.showGridLines = false;
qc.mergeCells("A1:M1");
qc.getRange("A1").values = [["QC_200 is a linked view. Edit review status only in Review."]];
qc.getRange("A1:M1").format = {
  fill: "#C65911",
  font: { name: fontName, bold: true, color: "#FFFFFF", size: 13 },
  verticalAlignment: "center",
};
const qcFields = [
  "sample_id", "category", "difficulty", "review_priority", "user_message",
  "human_should_remember", "human_importance", "expected_kg_evidence",
  "sensitive_case", "temporal_case", "scenario_tags", "review_status", "review_notes",
];
qc.getRange("A3:M3").values = [qcFields];
qc.getRange("A3:M3").format = {
  fill: navy,
  font: { name: fontName, bold: true, color: "#FFFFFF" },
  wrapText: true,
  verticalAlignment: "center",
};
const reviewColumnByField = Object.fromEntries(payload.fields.map((field, index) => [field, excelColumn(index)]));
qc.getRange("A4:A203").values = payload.qc.map((item) => [item.sample_id]);
const formulas = [];
for (let index = 0; index < payload.qc.length; index += 1) {
  const rowNumber = index + 4;
  formulas.push(qcFields.slice(1).map((field) => {
    const column = reviewColumnByField[field];
    return `=INDEX(Review!$${column}$2:$${column}$2001,MATCH($A${rowNumber},Review!$A$2:$A$2001,0))`;
  }));
}
qc.getRange("B4:M203").formulas = formulas;
qc.tables.add("A3:M203", true, "LinkedQC200");
qc.freezePanes.freezeRows(3);
qc.freezePanes.freezeColumns(1);
qc.getRange("A4:M203").format.font = { name: fontName, size: 9 };
qc.getRange("E4:E203").format.wrapText = true;
qc.getRange("K4:M203").format.wrapText = true;
qc.getRange("A:A").format.columnWidth = 12;
qc.getRange("B:B").format.columnWidth = 22;
qc.getRange("C:D").format.columnWidth = 14;
qc.getRange("E:E").format.columnWidth = 52;
qc.getRange("F:J").format.columnWidth = 18;
qc.getRange("K:K").format.columnWidth = 36;
qc.getRange("L:L").format.columnWidth = 24;
qc.getRange("M:M").format.columnWidth = 36;
qc.getRange("1:1").format.rowHeight = 32;
qc.getRange("3:3").format.rowHeight = 30;
qc.tabColor = "#C65911";

reference.showGridLines = false;
reference.mergeCells("A1:F1");
reference.getRange("A1").values = [["Experiment 1 Formal 2000 v2 — Frozen Schema & Review Guide"]];
reference.getRange("A1:F1").format = { fill: navy, font: { name: fontName, bold: true, color: "#FFFFFF", size: 15 } };
reference.mergeCells("A3:F3");
reference.getRange("A3").values = [[
  "Review is the single source of truth. Human reviewers edit only Review.review_status, annotator_1, annotator_2, and review_notes.",
]];
reference.getRange("A3:F3").format = { fill: paleGold, font: { name: fontName, bold: true, color: "#7F6000" }, wrapText: true };
const schemaRows = [
  ["Field", "Type", "Allowed / rule", "Annotation only", "Production directly observable", "Notes"],
  ["sample_id", "text", "f0001-f2000", "false", "n/a", "Unique formal sample ID"],
  ["user_message", "text", "natural language", "false", "true", "Formal evaluation input"],
  ["assistant_context", "text", "natural language", "false", "true", "Context is not independent evidence"],
  ["category", "enum", Object.keys(payload.metadata.category_counts).join(" / "), "true", "false", "Annotation taxonomy"],
  ["difficulty", "enum", "easy / medium / hard", "true", "false", "Stratification field"],
  ["store_decision", "bool", "equals human_should_remember", "true", "derivable", "Formal storage decision Gold"],
  ["human_should_remember", "bool", "TRUE / FALSE", "true", "derivable", "Core LTM Gold"],
  ["human_importance", "number", "0.1 / 0.3 / 0.5 / 0.7 / 0.9", "true", "derivable", "Human importance Gold"],
  ["memory_type", "enum", "fact / preference / goal / temporary_state / one_off / none", "true", "false", "annotation_only=true"],
  ["predicate", "JSON array", "production predicate whitelist", "true", "derivable", "Flat view of expected_relations order"],
  ["value", "JSON array", "aligned with predicate", "true", "derivable", "Resolved relation target entity name or user"],
  ["time_status", "enum", "none / past / current / future / mixed", "true", "false", "annotation_only=true; not TemporalMemory output"],
  ["profile_slot", "enum", "not_applicable", "true", "false", payload.schema.profile_slot_note],
  ["sensitivity", "enum", "sensitive / non_sensitive", "true", "derivable", "Must match sensitive_case"],
  ["evidence_turn", "text", "equals sample_id", "true", "false", payload.schema.evidence_turn_note],
  ["expected_entities", "JSON array", "production entity types", "true", "derivable", "Canonical KG entity Gold"],
  ["expected_relations", "JSON array", "production predicate whitelist", "true", "derivable", "Canonical KG relation Gold"],
  ["expected_kg_evidence", "bool", "entities or relations non-empty", "true", "derivable", "KG evidence flag"],
  ["sensitive_case", "bool", "TRUE / FALSE", "true", "false", "Grouping label"],
  ["temporal_case", "bool", "TRUE / FALSE", "true", "false", "Grouping label; not time_status"],
  ["scenario_tags", "JSON array", "scenario tags", "true", "false", "Error analysis tags"],
  ["annotation_reason", "text", "concise reason", "true", "false", "Gold rationale"],
  ["review_priority", "enum", "High / Medium / Low", "true", "false", "Deterministic review aid"],
  ["review_status", "enum", "pending_human_review / confirmed / adjudicated / excluded", "true", "false", "Human edit in Review only"],
  ["annotator_1", "text", "blank until human review", "true", "false", "Human edit in Review only"],
  ["annotator_2", "text", "blank until human review", "true", "false", "Human edit in Review only"],
  ["review_notes", "text", "blank until human review", "true", "false", "Human edit in Review only"],
];
reference.getRange(`A6:F${5 + schemaRows.length}`).values = schemaRows;
reference.getRange("A6:F6").format = { fill: blue, font: { name: fontName, bold: true, color: "#FFFFFF" }, wrapText: true };
reference.getRange(`A7:F${5 + schemaRows.length}`).format = { font: { name: fontName, size: 9 }, wrapText: true, verticalAlignment: "top" };
const enumStart = 8 + schemaRows.length;
reference.getRange(`A${enumStart}:F${enumStart}`).values = [["Production entity types", "Production predicates", "Memory type mapping", "Time status", "Profile scope", "Evidence provenance"]];
reference.getRange(`A${enumStart}:F${enumStart}`).format = { fill: teal, font: { name: fontName, bold: true, color: "#FFFFFF" }, wrapText: true };
reference.getRange(`A${enumStart + 1}:F${enumStart + 11}`).values = [
  ["person", "prefers", "stable_fact -> fact", "none", "not_applicable", "sample_id"],
  ["project", "dislikes", "long_term_preference -> preference", "past", "Experiment 1 excludes MemoryCurator/UserProfile", "not a session_message_id"],
  ["technology", "uses", "long_term_goal -> goal", "current", "production_directly_observable=false", "formal annotation provenance key"],
  ["tool", "learns", "temporary_state -> temporary_state", "future", "", ""],
  ["preference", "builds", "one_off_information -> one_off", "mixed", "", ""],
  ["goal", "works_on", "small_talk -> none", "", "", ""],
  ["topic", "plans", "", "", "", ""],
  ["organization", "knows", "", "", "", ""],
  ["place", "experienced", "", "", "", ""],
  ["event", "belongs_to", "", "", "", ""],
  ["other", "related_to", "", "", "", ""],
];
reference.getRange("A:F").format.columnWidth = 32;
reference.getRange("C:C").format.columnWidth = 48;
reference.getRange("F:F").format.columnWidth = 52;
reference.getRange("1:1").format.rowHeight = 32;
reference.getRange("3:3").format.rowHeight = 42;
reference.freezePanes.freezeRows(6);
reference.tabColor = "#7030A0";

workbook.recalculate();
await fs.mkdir(previewDir, { recursive: true });
for (const [sheetName, range] of [
  ["Review", "A1:AA14"],
  ["Summary", "A1:H36"],
  ["QC_200", "A1:M14"],
  ["Reference", "A1:F45"],
]) {
  const preview = await workbook.render({ sheetName, range, scale: 1, format: "png" });
  await fs.writeFile(`${previewDir}/${sheetName}.png`, new Uint8Array(await preview.arrayBuffer()));
}
const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);
