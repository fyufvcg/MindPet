[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Preflight', 'Smoke', 'Execute')]
    [string]$Mode,

    [Parameter(Mandatory = $true)]
    [string]$ModelId,

    [string]$ConfigPath = 'D:\MindPet-local-config\application.yml',
    [string]$DatasetPath,
    [string]$EvalRoot = 'D:\MindPet-eval-data\experiment-01-formal',
    [switch]$Resume,
    [string]$RunDir,
    [int]$StartAt = 1,
    [Nullable[int]]$Limit,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'run_experiment1_formal_2000.py'
if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) {
    throw 'Experiment 1 formal runner is missing.'
}
if ($ModelId -ne 'deepseek-flash') {
    throw 'Experiment 1 formal guard failed: ModelId must be deepseek-flash.'
}
if ($Mode -eq 'Smoke' -and ($Limit -ne 10)) {
    throw 'Experiment 1 formal guard failed: Smoke requires -Limit 10.'
}
if ($Resume -and [string]::IsNullOrWhiteSpace($RunDir)) {
    throw 'Experiment 1 formal guard failed: -Resume requires -RunDir.'
}

$arguments = @(
    $runner,
    '--mode', $Mode,
    '--model-id', $ModelId,
    '--config', $ConfigPath,
    '--eval-root', $EvalRoot,
    '--start-at', [string]$StartAt
)
if (-not [string]::IsNullOrWhiteSpace($DatasetPath)) {
    $arguments += @('--dataset', $DatasetPath)
}
if ($null -ne $Limit) {
    $arguments += @('--limit', [string]$Limit)
}
if ($Resume) {
    $arguments += @('--resume', '--run-dir', $RunDir)
}
if ($SkipBuild) {
    $arguments += '--skip-build'
}

& python @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Experiment 1 formal runner failed with exit code $LASTEXITCODE."
}
