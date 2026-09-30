[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Preflight', 'Execute')]
    [string]$Mode,

    [string]$ModelId = 'deepseek-flash',
    [string]$ConfigPath = 'D:\MindPet-local-config\application.yml',
    [string]$EvalRootBase = 'D:\MindPet-eval-data\experiment-01-architecture-validation',
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    throw "Architecture validation guard failed: $Message"
}

$validationRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$experimentRoot = [System.IO.Path]::GetFullPath((Join-Path $validationRoot '..'))
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $experimentRoot '..\..'))
$sharedRunner = Join-Path $experimentRoot 'scripts\run_experiment1_pilot30.ps1'
$traceSource = Join-Path $repositoryRoot 'MindPet-java\src\main\java\model\EvaluationWriteTrace.java'
$resultSource = Join-Path $repositoryRoot 'MindPet-java\src\main\java\model\E2eMemoryIngestResult.java'
$dataset = Join-Path $validationRoot 'datasets\architecture_validation_12.jsonl'
$manifest = Join-Path $validationRoot 'datasets\architecture_validation_12_manifest.json'
$sourceDataset = Join-Path $experimentRoot '02-pilot-v1\datasets\pilot_30.jsonl'

foreach ($path in @($sharedRunner, $traceSource, $resultSource, $dataset, $manifest, $sourceDataset)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Fail "required file is missing: $path"
    }
}

$traceText = [System.IO.File]::ReadAllText($traceSource)
$requiredTraceNames = @(
    'DecisionTrace', 'ParseDiagnostics', 'KgFilterTrace', 'TemporalTrace', 'ProvenanceTrace',
    'rawWorthRemembering', 'rawMemoryShouldRemember', 'combinedShouldRemember',
    'importance', 'confidence', 'importanceThreshold', 'confidenceThreshold',
    'importanceGatePassed', 'confidenceGatePassed', 'ltmAttempted', 'ltmPersisted',
    'ltmFailureReason',
    'memoryObjectPresent', 'importanceFallbackUsed', 'confidenceFallbackUsed',
    'importanceClamped', 'confidenceClamped', 'parseFailure', 'parseFailureReason',
    'rawEntityCount', 'rawRelationCount', 'normalizedEntityCount', 'normalizedRelationCount',
    'sensitivityRejectedEntityCount', 'predicateWhitelistRejectedCount',
    'relationConfidenceRejectedCount', 'persistedEntityCount', 'persistedRelationCount',
    'evidenceCount', 'eventDate', 'eventAt', 'eventTimezone', 'eventPrecision',
    'referenceTimestamp', 'referenceTimezone', 'sampleId', 'turnHash', 'sessionId',
    'sourceUserMessageId', 'sourceAssistantMessageId', 'kgEntityRowIds',
    'kgRelationRowIds', 'kgEvidenceRowIds', 'longTermMemoryRowIds'
)
foreach ($name in $requiredTraceNames) {
    if ($traceText -notmatch "\b$([regex]::Escape($name))\b") {
        Fail "writeTrace contract field/type is missing: $name"
    }
}
$resultText = [System.IO.File]::ReadAllText($resultSource)
if ($resultText -notmatch '\bEvaluationWriteTrace\s+writeTrace\b') {
    Fail 'E2eMemoryIngestResult does not expose writeTrace'
}
Write-Host 'Formal writeTrace contract code is present.'

$sourceHash = (Get-FileHash -LiteralPath $sourceDataset -Algorithm SHA256).Hash.ToLowerInvariant()
$manifestValue = Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifestValue.purpose -ne 'ARCHITECTURE_VALIDATION_ONLY' -or
    $manifestValue.formal_result -ne $false) {
    Fail 'dataset manifest must identify a non-formal architecture validation'
}
if ($manifestValue.source_dataset_sha256 -ne $sourceHash) {
    Fail 'dataset manifest source SHA-256 does not match pilot_30.jsonl'
}
$datasetIds = @(Get-Content -LiteralPath $dataset -Encoding UTF8 | ForEach-Object {
    if ($_.Trim()) { ($_ | ConvertFrom-Json).sample_id }
})
$manifestIds = @($manifestValue.selected_sample_ids)
if ($datasetIds.Count -ne 12 -or
    (Compare-Object -ReferenceObject $manifestIds -DifferenceObject $datasetIds -SyncWindow 0)) {
    Fail 'dataset IDs do not match the ordered 12-sample manifest selection'
}
$sourceRows = @{}
Get-Content -LiteralPath $sourceDataset -Encoding UTF8 | ForEach-Object {
    if ($_.Trim()) {
        $sourceRows[($_ | ConvertFrom-Json).sample_id] = $_
    }
}
Get-Content -LiteralPath $dataset -Encoding UTF8 | ForEach-Object {
    if ($_.Trim()) {
        $sampleId = ($_ | ConvertFrom-Json).sample_id
        if (-not $sourceRows.ContainsKey($sampleId) -or $sourceRows[$sampleId] -cne $_) {
            Fail "architecture sample is not an exact source row: $sampleId"
        }
    }
}

$arguments = @{
    Variant = 'arch12'
    Mode = $Mode
    ModelId = $ModelId
    ConfigPath = $ConfigPath
    EvalRootBase = $EvalRootBase
}
if ($SkipBuild) { $arguments.SkipBuild = $true }

& $sharedRunner @arguments
if ($LASTEXITCODE -ne 0) {
    Fail "shared Experiment 1 runner exited with code $LASTEXITCODE"
}
