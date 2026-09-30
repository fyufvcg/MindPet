import fs from "node:fs/promises";
const artifactToolModule = process.env.ARTIFACT_TOOL_MODULE || "@oai/artifact-tool";
const { FileBlob, SpreadsheetFile } = await import(artifactToolModule);

const [, , workbookPath, jsonlPath, outputPath] = process.argv;
if (!workbookPath || !jsonlPath || !outputPath) {
  throw new Error("usage: validate_exp1_formal_2000_v2_workbook.mjs <workbook.xlsx> <dataset.jsonl> <output.json>");
}

const expectedHeaders = [
  "sample_id", "user_message", "assistant_context", "category", "difficulty",
  "store_decision", "human_should_remember", "human_importance", "memory_type",
  "predicate", "value", "time_status", "profile_slot", "sensitivity", "evidence_turn",
  "expected_entities", "expected_relations", "expected_kg_evidence", "sensitive_case",
  "temporal_case", "scenario_tags", "annotation_reason", "review_priority", "review_status",
  "annotator_1", "annotator_2", "review_notes",
];
const qcHeaders = [
  "sample_id", "category", "difficulty", "review_priority", "user_message",
  "human_should_remember", "human_importance", "expected_kg_evidence", "sensitive_case",
  "temporal_case", "scenario_tags", "review_status", "review_notes",
];
const formulaErrors = new Set(["#REF!", "#VALUE!", "#N/A", "#NAME?", "#DIV/0!"]);
const dataset = (await fs.readFile(jsonlPath, "utf8")).trim().split(/\r?\n/).map((line) => JSON.parse(line));
const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(workbookPath));
const review = workbook.worksheets.getItem("Review");
const summary = workbook.worksheets.getItem("Summary");
const qc = workbook.worksheets.getItem("QC_200");
const reference = workbook.worksheets.getItem("Reference");
const errors = [];

const reviewValues = review.getRange("A1:AA2001").values;
if (reviewValues.length !== 2001) errors.push(`Review rows=${reviewValues.length - 1}`);
if (JSON.stringify(reviewValues[0]) !== JSON.stringify(expectedHeaders)) errors.push("Review headers mismatch");
for (let index = 0; index < dataset.length; index += 1) {
  if (reviewValues[index + 1]?.[0] !== dataset[index].sample_id) errors.push(`Review sample mismatch at ${index + 2}`);
}

const qcValues = qc.getRange("A3:M203").values;
const qcFormulas = qc.getRange("A3:M203").formulas;
if (JSON.stringify(qcValues[0]) !== JSON.stringify(qcHeaders)) errors.push("QC headers mismatch");
const qcIds = qcValues.slice(1).map((row) => row[0]).filter(Boolean);
if (qcIds.length !== 200 || new Set(qcIds).size !== 200) errors.push(`QC IDs=${qcIds.length}/${new Set(qcIds).size}`);
let linkedFormulaCount = 0;
let badFormulaCount = 0;
for (let row = 1; row < qcFormulas.length; row += 1) {
  for (let column = 1; column < qcFormulas[row].length; column += 1) {
    const formula = qcFormulas[row][column] || "";
    if (formula.includes("INDEX(Review!") && formula.includes("MATCH($A")) linkedFormulaCount += 1;
    else badFormulaCount += 1;
  }
}
if (linkedFormulaCount !== 2400 || badFormulaCount !== 0) {
  errors.push(`QC linked formulas=${linkedFormulaCount}, bad=${badFormulaCount}`);
}

const notice = qc.getRange("A1").values[0][0];
if (notice !== "QC_200 is a linked view. Edit review status only in Review.") errors.push("QC notice mismatch");
const summaryValues = summary.getUsedRange().values;
const summaryFormulas = summary.getUsedRange().formulas;
const summaryFormulaText = summaryFormulas.flat().join("\n");
if (!summaryFormulaText.includes("Review!")) errors.push("Summary does not link to Review");
if (summaryValues[5]?.[1] !== 2000) errors.push(`Summary total=${summaryValues[5]?.[1]}`);
if (summaryValues[5]?.[3] !== 200) errors.push(`Summary QC total=${summaryValues[5]?.[3]}`);
const expectedSummary = [
  dataset.filter((row) => row.human_should_remember).length,
  dataset.filter((row) => row.expected_kg_evidence).length,
  dataset.filter((row) => row.sensitive_case).length,
  dataset.filter((row) => row.temporal_case).length,
];
for (let index = 0; index < expectedSummary.length; index += 1) {
  const actual = summaryValues[index + 7]?.[1];
  if (actual !== expectedSummary[index]) errors.push(`Summary boolean metric ${index}=${actual}, expected=${expectedSummary[index]}`);
}
for (let index = 0; index < 6; index += 1) {
  const category = summaryValues[index + 13]?.[0];
  const subset = dataset.filter((row) => row.category === category);
  const expected = [
    subset.length,
    subset.filter((row) => row.human_should_remember).length,
    subset.filter((row) => row.expected_kg_evidence).length,
    subset.filter((row) => row.sensitive_case).length,
    subset.filter((row) => row.temporal_case).length,
  ];
  const actual = summaryValues[index + 13]?.slice(1, 6);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) errors.push(`Summary category mismatch: ${category}`);
}

let formulaErrorCount = 0;
for (const sheet of [review, summary, qc, reference]) {
  for (const row of sheet.getUsedRange().values) {
    for (const value of row) {
      if (formulaErrors.has(String(value))) formulaErrorCount += 1;
    }
  }
}
if (formulaErrorCount) errors.push(`formula errors=${formulaErrorCount}`);

const result = {
  status: errors.length ? "FAIL" : "PASS",
  errors,
  review_row_count: reviewValues.length - 1,
  qc_sample_count: qcIds.length,
  qc_unique_sample_count: new Set(qcIds).size,
  qc_linked_formula_count: linkedFormulaCount,
  qc_bad_formula_count: badFormulaCount,
  summary_links_to_review: summaryFormulaText.includes("Review!"),
  summary_total_count: summaryValues[5]?.[1],
  summary_qc_count: summaryValues[5]?.[3],
  formula_error_count: formulaErrorCount,
};
await fs.writeFile(outputPath, JSON.stringify(result, null, 2) + "\n", "utf8");
if (errors.length) {
  console.error(JSON.stringify(result));
  process.exit(1);
}
console.log(JSON.stringify(result));
