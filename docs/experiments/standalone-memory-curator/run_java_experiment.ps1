param(
    [string]$Dataset = (Join-Path $PSScriptRoot "data\synthetic-curator-benchmark-1200.jsonl"),
    [ValidateSet("fixture", "llm")]
    [string]$Provider = "fixture",
    [int]$Limit = 0,
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$moduleRoot = Join-Path $repositoryRoot "MindPet-java"
$sourcePath = Join-Path $PSScriptRoot "java\MemoryCuratorExperimentRunner.java"
$datasetPath = (Resolve-Path $Dataset).Path
$llmEnvironment = @{}

if ($Provider -eq "llm") {
    if ($Limit -le 0) {
        throw "LLM mode requires -Limit N so the number of billable requests is bounded."
    }

    $llmConfigPath = Join-Path $moduleRoot "llm-dynamic-config.json"
    if (-not (Test-Path -LiteralPath $llmConfigPath -PathType Leaf)) {
        throw "LLM mode requires the existing application config file: $llmConfigPath"
    }
    try {
        $llmConfig = Get-Content -LiteralPath $llmConfigPath -Raw | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "Could not parse the application dynamic LLM config as JSON. The config contents were not displayed."
    }

    $experimentConfigPath = Join-Path $PSScriptRoot "llm-experiment.local.json"
    $experimentApiKey = ""
    if (Test-Path -LiteralPath $experimentConfigPath -PathType Leaf) {
        try {
            $experimentConfig = Get-Content -LiteralPath $experimentConfigPath -Raw | ConvertFrom-Json -ErrorAction Stop
            $experimentApiKey = [string]$experimentConfig.apiKey
        }
        catch {
            throw "Could not parse the local experiment LLM config. Its contents were not displayed."
        }
    }

    $environmentApiKey = [Environment]::GetEnvironmentVariable("MINDPET_EXPERIMENT_LLM_API_KEY")
    $environmentBaseUrl = [Environment]::GetEnvironmentVariable("MINDPET_EXPERIMENT_LLM_BASE_URL")
    $environmentModel = [Environment]::GetEnvironmentVariable("MINDPET_EXPERIMENT_LLM_MODEL")
    $apiKey = if (-not [string]::IsNullOrWhiteSpace($experimentApiKey)) { $experimentApiKey }
        elseif (-not [string]::IsNullOrWhiteSpace($environmentApiKey)) { $environmentApiKey }
        else { [string]$llmConfig.apiKey }
    $baseUrl = if ([string]::IsNullOrWhiteSpace($environmentBaseUrl)) { [string]$llmConfig.baseUrl } else { $environmentBaseUrl }
    $modelName = if ([string]::IsNullOrWhiteSpace($environmentModel)) { [string]$llmConfig.model } else { $environmentModel }
    if ([string]::IsNullOrWhiteSpace($apiKey) -or
        [string]::IsNullOrWhiteSpace($baseUrl) -or
        [string]::IsNullOrWhiteSpace($modelName)) {
        throw "The application dynamic LLM config must contain non-empty apiKey, baseUrl, and model fields. Values were not displayed."
    }

    $apiKeySource = if (-not [string]::IsNullOrWhiteSpace($experimentApiKey)) { "local experiment-only config" }
        elseif (-not [string]::IsNullOrWhiteSpace($environmentApiKey)) { "experiment environment override" }
        else { "application dynamic config" }
    $baseUrlSource = if ([string]::IsNullOrWhiteSpace($environmentBaseUrl)) { "application dynamic config" } else { "experiment environment override" }
    $modelSource = if ([string]::IsNullOrWhiteSpace($environmentModel)) { "application dynamic config" } else { "experiment environment override" }
    $configurationSource = "external PowerShell runner; apiKey=$apiKeySource; baseUrl=$baseUrlSource; model=$modelSource"
    $llmEnvironment = @{
        "MINDPET_EXPERIMENT_LLM_API_KEY" = $apiKey
        "MINDPET_EXPERIMENT_LLM_BASE_URL" = $baseUrl
        "MINDPET_EXPERIMENT_LLM_MODEL" = $modelName
        "MINDPET_EXPERIMENT_LLM_CONFIG_SOURCE" = $configurationSource
    }
    $llmConfig = $null
    $experimentConfig = $null
    $experimentApiKey = $null
    $apiKey = $null
    $environmentApiKey = $null
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDir = Join-Path $PSScriptRoot "results\java-$Provider-$stamp"
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$classDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "mindpet-memory-curator-experiment-classes"
$classpathFile = Join-Path $moduleRoot "target\memory-curator-experiment-classpath.txt"
New-Item -ItemType Directory -Force -Path $classDirectory | Out-Null

Push-Location $moduleRoot
try {
    & mvn -q -DskipTests compile dependency:build-classpath "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/memory-curator-experiment-classpath.txt"
    if ($LASTEXITCODE -ne 0) { throw "Maven compile/classpath preparation failed with exit code $LASTEXITCODE." }
}
finally {
    Pop-Location
}

$dependencyClasspath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
$projectClasspath = "$moduleRoot\target\classes;$dependencyClasspath"
& javac -encoding UTF-8 -cp $projectClasspath -d $classDirectory $sourcePath
if ($LASTEXITCODE -ne 0) { throw "Could not compile the external Java experiment runner." }

$runnerClasspath = "$classDirectory;$projectClasspath"
$javaArgs = @("-cp", $runnerClasspath, "experiment.MemoryCuratorExperimentRunner",
    "--dataset", $datasetPath, "--output-dir", $OutputDir, "--provider", $Provider)
if ($Limit -gt 0) { $javaArgs += @("--limit", "$Limit") }

$javaExitCode = 0
$previousEnvironment = @{}
try {
    foreach ($name in $llmEnvironment.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable(
            $name, [EnvironmentVariableTarget]::Process)
        [Environment]::SetEnvironmentVariable(
            $name, $llmEnvironment[$name], [EnvironmentVariableTarget]::Process)
    }
    & java @javaArgs
    $javaExitCode = $LASTEXITCODE
}
finally {
    foreach ($name in $llmEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable(
            $name, $previousEnvironment[$name], [EnvironmentVariableTarget]::Process)
    }
}
if ($javaExitCode -ne 0) { throw "The Java Memory Curator experiment failed with exit code $javaExitCode." }
