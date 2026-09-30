param(
    [string]$Dataset = (Join-Path $PSScriptRoot "data\memory-curator-value-300-v2.jsonl"),
    [ValidateSet("all", "development", "validation", "locked_test")]
    [string]$Split = "locked_test",
    [switch]$ChallengeOnly,
    [int]$Limit = 0,
    [int]$AnswerLimit = 600,
    [ValidateSet(256,512,1024)]
    [int]$AnswerBudget = 512,
    [ValidateRange(1, 16)]
    [int]$Concurrency = 2,
    [ValidateSet("strict", "relaxed")]
    [string]$GateProfile = "strict",
    [switch]$ResumeExisting,
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"
if ($Limit -le 0) {
    throw "Pass -Limit N to bound real LLM usage. Use -Limit 180 to run the complete locked_test split."
}
if ($AnswerLimit -lt 0) { throw "-AnswerLimit must be nonnegative." }

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$moduleRoot = Join-Path $repositoryRoot "MindPet-java"
$sourcePath = Join-Path $PSScriptRoot "java\MemoryCuratorExperimentRunner.java"
$datasetPath = (Resolve-Path $Dataset).Path

$llmConfigPath = Join-Path $moduleRoot "llm-dynamic-config.json"
$experimentConfigPath = Join-Path $PSScriptRoot "llm-experiment.local.json"
$experimentConfig = $null
if (Test-Path -LiteralPath $experimentConfigPath -PathType Leaf) {
    try {
        $experimentConfig = Get-Content -LiteralPath $experimentConfigPath -Raw | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "Could not parse the local experiment LLM config. Config contents were not displayed."
    }
}

$environmentApiKey = [Environment]::GetEnvironmentVariable("MINDPET_EXPERIMENT_LLM_API_KEY")
$environmentBaseUrl = [Environment]::GetEnvironmentVariable("MINDPET_EXPERIMENT_LLM_BASE_URL")
$environmentModel = [Environment]::GetEnvironmentVariable("MINDPET_EXPERIMENT_LLM_MODEL")
$experimentApiKey = if ($null -ne $experimentConfig) { [string]$experimentConfig.apiKey } else { "" }
$experimentBaseUrl = if ($null -ne $experimentConfig) { [string]$experimentConfig.baseUrl } else { "" }
$experimentModel = if ($null -ne $experimentConfig) { [string]$experimentConfig.model } else { "" }
$apiKey = if (-not [string]::IsNullOrWhiteSpace($environmentApiKey)) { $environmentApiKey }
    elseif (-not [string]::IsNullOrWhiteSpace($experimentApiKey)) { $experimentApiKey }
    else { "" }
$baseUrl = if (-not [string]::IsNullOrWhiteSpace($environmentBaseUrl)) { $environmentBaseUrl }
    elseif (-not [string]::IsNullOrWhiteSpace($experimentBaseUrl)) { $experimentBaseUrl }
    else { "" }
$modelName = if (-not [string]::IsNullOrWhiteSpace($environmentModel)) { $environmentModel }
    elseif (-not [string]::IsNullOrWhiteSpace($experimentModel)) { $experimentModel }
    else { "" }

# Load the application config only to fill fields the local experiment config and
# explicit environment overrides did not provide. Never display these values.
if ([string]::IsNullOrWhiteSpace($apiKey) -or
    [string]::IsNullOrWhiteSpace($baseUrl) -or
    [string]::IsNullOrWhiteSpace($modelName)) {
    if (-not (Test-Path -LiteralPath $llmConfigPath -PathType Leaf)) {
        throw "The runner needs an LLM config at $llmConfigPath. Config values are never printed."
    }
    try {
        $llmConfig = Get-Content -LiteralPath $llmConfigPath -Raw | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "Could not parse the dynamic LLM config. Config contents were not displayed."
    }
    if ([string]::IsNullOrWhiteSpace($apiKey)) { $apiKey = [string]$llmConfig.apiKey }
    if ([string]::IsNullOrWhiteSpace($baseUrl)) { $baseUrl = [string]$llmConfig.baseUrl }
    if ([string]::IsNullOrWhiteSpace($modelName)) { $modelName = [string]$llmConfig.model }
    $llmConfig = $null
}
if ([string]::IsNullOrWhiteSpace($apiKey) -or
    [string]::IsNullOrWhiteSpace($baseUrl) -or
    [string]::IsNullOrWhiteSpace($modelName)) {
    throw "The selected LLM configuration has an empty API key, base URL, or model. Values were not displayed."
}

$apiKeySource = if (-not [string]::IsNullOrWhiteSpace($environmentApiKey)) { "experiment environment override" }
    elseif (-not [string]::IsNullOrWhiteSpace($experimentApiKey)) { "local experiment-only config" }
    else { "application dynamic config fallback" }
$baseUrlSource = if (-not [string]::IsNullOrWhiteSpace($environmentBaseUrl)) { "experiment environment override" }
    elseif (-not [string]::IsNullOrWhiteSpace($experimentBaseUrl)) { "local experiment-only config" }
    else { "application dynamic config fallback" }
$modelSource = if (-not [string]::IsNullOrWhiteSpace($environmentModel)) { "experiment environment override" }
    elseif (-not [string]::IsNullOrWhiteSpace($experimentModel)) { "local experiment-only config" }
    else { "application dynamic config fallback" }
$configurationSource = "PowerShell experiment wrapper; apiKey=$apiKeySource; baseUrl=$baseUrlSource; model=$modelSource"
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

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDir = Join-Path $PSScriptRoot "results\java-llm-$Split-$stamp"
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
$evaluationSource = Join-Path $PSScriptRoot "java\FrozenMemoryEvaluation.java"
& javac -encoding UTF-8 -cp $projectClasspath -d $classDirectory $sourcePath $evaluationSource
if ($LASTEXITCODE -ne 0) { throw "Could not compile the external Java LLM experiment runner." }

$runnerClasspath = "$classDirectory;$projectClasspath"
$javaArgs = @("-cp", $runnerClasspath, "experiment.MemoryCuratorExperimentRunner",
    "--dataset", $datasetPath, "--output-dir", $OutputDir,
    "--split", $Split, "--limit", "$Limit",
    "--answer-limit", "$AnswerLimit", "--concurrency", "$Concurrency",
    "--answer-budget", "$AnswerBudget",
    "--gate-profile", $GateProfile)
if ($ChallengeOnly) { $javaArgs += @("--challenge-only", "true") }
if ($ResumeExisting) { $javaArgs += @("--resume-existing", "true") }

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
if ($javaExitCode -ne 0) { throw "The Java LLM Memory Curator experiment failed with exit code $javaExitCode." }
