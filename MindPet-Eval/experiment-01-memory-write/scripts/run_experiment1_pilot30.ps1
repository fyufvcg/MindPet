[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('v1', 'v2', 'v21')]
    [string]$Variant,

    [Parameter(Mandatory = $true)]
    [ValidateSet('Preflight', 'Execute')]
    [string]$Mode,

    [Parameter(Mandatory = $true)]
    [string]$ModelId,

    [string]$ConfigPath = 'D:\MindPet-local-config\application.yml',
    [string]$EvalRootBase = 'D:\MindPet-eval-data\experiment-01',
    [double]$Temperature = 0.8,
    [int]$RunnerTimeoutSeconds = 240,
    [string]$LlmConnectTimeout = '30s',
    [string]$LlmReadTimeout = '120s',
    [int]$SpringAiRetryMaxAttempts = 2,
    [string]$SpringAiRetryBackoffInitial = '1000',
    [string]$SpringAiRetryBackoffMax = '5000',
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http -ErrorAction Stop

$PromptHashes = @{
    v1 = 'a2f27c59eb39499dc6682bb7e927afc0e19f87013559c0aeacf3c2ef8cb002c9'
    v2 = 'cafa86f6e08703a60f236f4f19b371c3a79df917e74133aacce2377ee06e627e'
    v21 = '1e02c1b13dbb1edfe0984ade5eaa5a3f96ee7da765b9d853abce71f1ddfeb649'
}
$RequiredBranches = @{
    v1 = 'experiment/e1-prompt-v1'
    v2 = 'experiment/mindpet-evaluation'
    v21 = 'experiment/e1-prompt-v2-1'
}
$EvaluationInfrastructureCommit = 'ea1e5547033a8efcfaa58bb1891b8a489a6acd41'
$FixedEvalUser = 'e2e_memory_eval_user'
$TableNames = @('long_term_memory', 'kg_entity', 'kg_relation', 'kg_evidence', 'kg_turn_ingest')
$RequiredProvider = 'deepseek'
$RequiredModel = 'deepseek-flash'
$RequiredEndpointHost = 'api.deepseek.com'
$RequiredEndpointIdentifier = 'deepseek@api.deepseek.com'
$DeepSeekBaseUrl = 'https://api.deepseek.com/v1'
$DeepSeekChatUrl = 'https://api.deepseek.com/v1/chat/completions'
$NormalChatConfigPath = Join-Path $env:APPDATA 'mindpet\system_llm_config.json'
$RequiredRunnerTimeoutSeconds = 240
$RequiredLlmConnectTimeout = '30s'
$RequiredLlmReadTimeout = '120s'
$RequiredRetryMaxAttempts = 2
$RequiredRetryBackoffInitial = '1000'
$RequiredRetryBackoffMax = '5000'
$RequiredEmbeddingProvider = 'ollama'
$RequiredEmbeddingModel = 'bge-m3'
$RequiredEmbeddingDimension = 1024
$RequiredEmbeddingEndpoint = 'http://127.0.0.1:11434/api/embed'
$RequiredEmbeddingTagsEndpoint = 'http://127.0.0.1:11434/api/tags'
$RequiredEmbeddingEndpointIdentifier = 'ollama@127.0.0.1:11434'
$RequiredEmbeddingKeepAlive = '30m'

function Fail([string]$Message) {
    throw "Experiment 1 guard failed: $Message"
}

function Get-CanonicalPath([string]$Path) {
    return [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-PromptHash([string]$Path) {
    $source = [System.IO.File]::ReadAllText($Path).Replace("`r`n", "`n").Replace("`r", "`n")
    $options = [Text.RegularExpressions.RegexOptions]::Singleline -bor
        [Text.RegularExpressions.RegexOptions]::Multiline
    $match = [regex]::Match(
        $source,
        'private\s+static\s+final\s+String\s+EXTRACTION_PROMPT\s*=\s*"""\n(.*?)^[ \t]*""";',
        $options
    )
    if (-not $match.Success) {
        Fail 'cannot locate KnowledgeGraphService.EXTRACTION_PROMPT'
    }
    $lines = $match.Groups[1].Value -split "`n"
    $indents = @(
        $lines |
            Where-Object { $_ -match '\S' } |
            ForEach-Object { ([regex]::Match($_, '^[ \t]*')).Length }
    )
    $minimumIndent = ($indents | Measure-Object -Minimum).Minimum
    $prompt = ($lines | ForEach-Object {
        if ($_.Length -ge $minimumIndent) { $_.Substring($minimumIndent) } else { '' }
    }) -join "`n"
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString(
            $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($prompt))
        )).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Get-YamlScalar([string]$Path, [string]$WantedPath) {
    $stack = @()
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^(\s*)([A-Za-z0-9_.-]+)\s*:\s*(.*)$') { continue }
        $indent = $matches[1].Length
        $key = $matches[2]
        $value = $matches[3].Trim()
        while ($stack.Count -gt 0 -and $stack[-1].Indent -ge $indent) {
            if ($stack.Count -eq 1) { $stack = @() } else { $stack = $stack[0..($stack.Count - 2)] }
        }
        $parts = @()
        if ($stack.Count -gt 0) { $parts += $stack | ForEach-Object { $_.Key } }
        $parts += $key
        $currentPath = $parts -join '.'
        if (-not $value) {
            $stack += [pscustomobject]@{ Indent = $indent; Key = $key }
        }
        elseif ($currentPath -eq $WantedPath) {
            return $value.Trim('"', "'")
        }
    }
    return $null
}

function Get-FreeLoopbackPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function Invoke-EvalRequest(
    [string]$Method,
    [string]$Url,
    [AllowNull()][string]$Token,
    [AllowNull()][string]$JsonBody,
    [int]$TimeoutSeconds
) {
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Url)
    try {
        $request.Headers.Accept.ParseAdd('application/json')
        if ($Token) { $request.Headers.Add('X-MindPet-Eval-Token', $Token) }
        if ($Method -ne 'GET' -and $null -ne $JsonBody) {
            $request.Content = [Net.Http.StringContent]::new(
                $JsonBody, [Text.Encoding]::UTF8, 'application/json'
            )
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $body = $null
        if ($text) {
            try { $body = $text | ConvertFrom-Json } catch { $body = $text }
        }
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $body }
    }
    finally {
        $request.Dispose()
        $client.Dispose()
    }
}

function Assert-EmptySnapshot($Snapshot, [string]$ExpectedDatabasePath, [string]$ExpectedPromptHash) {
    if ($Snapshot.status -ne 'OK') { Fail 'snapshot status is not OK' }
    if ($Snapshot.userId -ne $FixedEvalUser) { Fail 'snapshot user is not the fixed evaluation user' }
    if ((Get-CanonicalPath $Snapshot.databasePath) -ne $ExpectedDatabasePath) {
        Fail 'snapshot database path does not match the new evaluation database'
    }
    if ($Snapshot.promptSha256 -ne $ExpectedPromptHash) { Fail 'snapshot prompt hash mismatch' }
    if ($Snapshot.model -ne $ModelId) { Fail 'snapshot model does not match -ModelId' }
    foreach ($tableName in $TableNames) {
        $property = $Snapshot.tables.PSObject.Properties[$tableName]
        if ($null -eq $property) { Fail "snapshot is missing table $tableName" }
        if ([int]$property.Value.count -ne 0) { Fail "snapshot table $tableName is not empty" }
    }
}

function Invoke-OllamaEmbeddingPreflight {
    try {
        $tagsResult = Invoke-EvalRequest 'GET' $RequiredEmbeddingTagsEndpoint $null $null 5
    }
    catch {
        Fail "OLLAMA_UNREACHABLE: $($_.Exception.GetType().FullName): $($_.Exception.Message)"
    }
    if ($tagsResult.StatusCode -ne 200) {
        Fail "OLLAMA_UNREACHABLE: /api/tags returned HTTP $($tagsResult.StatusCode)"
    }

    $modelNames = @($tagsResult.Body.models | ForEach-Object {
        if ($_.name) { [string]$_.name } elseif ($_.model) { [string]$_.model }
    })
    $modelFound = @($modelNames | Where-Object {
        $_ -eq $RequiredEmbeddingModel -or $_ -like "$RequiredEmbeddingModel`:*"
    }).Count -gt 0
    if (-not $modelFound) {
        Fail "BGE_M3_MISSING: model $RequiredEmbeddingModel is not present in Ollama"
    }

    $embedBody = [ordered]@{
        model = $RequiredEmbeddingModel
        input = 'MindPet Experiment 1 embedding preflight'
        truncate = $true
        keep_alive = $RequiredEmbeddingKeepAlive
    } | ConvertTo-Json -Compress
    try {
        $embedResult = Invoke-EvalRequest 'POST' $RequiredEmbeddingEndpoint $null $embedBody 30
    }
    catch {
        Fail "EMBEDDING_REQUEST_FAILED: $($_.Exception.GetType().FullName): $($_.Exception.Message)"
    }
    if ($embedResult.StatusCode -ne 200) {
        Fail "EMBEDDING_REQUEST_FAILED: /api/embed returned HTTP $($embedResult.StatusCode)"
    }
    $embeddings = @($embedResult.Body.embeddings)
    if ($embeddings.Count -eq 0 -or $null -eq $embeddings[0]) {
        Fail 'EMBEDDING_REQUEST_FAILED: /api/embed response has no embedding'
    }
    $dimension = @($embeddings[0]).Count
    if ($dimension -ne $RequiredEmbeddingDimension) {
        Fail "EMBEDDING_DIMENSION_MISMATCH: expected $RequiredEmbeddingDimension, actual $dimension"
    }
    return [pscustomobject]@{
        Provider = $RequiredEmbeddingProvider
        Model = $RequiredEmbeddingModel
        Dimension = $dimension
        EndpointIdentifier = $RequiredEmbeddingEndpointIdentifier
    }
}

function Write-SafeJson([string]$Path, $Value) {
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding utf8
}

$experimentRoot = Get-CanonicalPath (Join-Path $PSScriptRoot '..')
$repositoryRoot = Get-CanonicalPath (Join-Path $experimentRoot '..\..')
$javaRoot = Join-Path $repositoryRoot 'MindPet-java'
$promptSource = Join-Path $javaRoot 'src\main\java\service\KnowledgeGraphService.java'
$dataset = Join-Path $experimentRoot '02-pilot-v1\datasets\pilot_30.jsonl'
$resultsRoot = Join-Path $experimentRoot '03-prompt-v2\pilot30-sqlite'
$runner = Join-Path $PSScriptRoot 'run_e2e_pilot.py'
$evaluator = Join-Path $PSScriptRoot 'evaluate_e2e_pilot.py'
$analyzer = Join-Path $PSScriptRoot 'analyze_e2e_pilot_errors.py'
$sqliteSchema = Join-Path $javaRoot 'src\main\resources\db\sqlite-schema.sql'
$configCanonical = Get-CanonicalPath $ConfigPath

if (-not (Test-Path -LiteralPath $configCanonical -PathType Leaf)) { Fail 'configuration file does not exist' }
if ($ModelId -ne $RequiredModel) {
    Fail "-ModelId must be exactly $RequiredModel"
}
if ($Temperature -ne 0.8) { Fail 'Temperature must be exactly 0.8' }
if ($RunnerTimeoutSeconds -ne $RequiredRunnerTimeoutSeconds) {
    Fail "RunnerTimeoutSeconds must be exactly $RequiredRunnerTimeoutSeconds"
}
if ($LlmConnectTimeout -ne $RequiredLlmConnectTimeout -or
    $LlmReadTimeout -ne $RequiredLlmReadTimeout) {
    Fail "LLM timeouts must be exactly connect=$RequiredLlmConnectTimeout and read=$RequiredLlmReadTimeout"
}
if ($SpringAiRetryMaxAttempts -ne $RequiredRetryMaxAttempts -or
    $SpringAiRetryBackoffInitial -ne $RequiredRetryBackoffInitial -or
    $SpringAiRetryBackoffMax -ne $RequiredRetryBackoffMax) {
    Fail "retry policy must be exactly $RequiredRetryMaxAttempts/$RequiredRetryBackoffInitial/$RequiredRetryBackoffMax"
}

if (-not (Test-Path -LiteralPath $NormalChatConfigPath -PathType Leaf)) {
    Fail 'normal-chat LLM configuration file does not exist'
}
try {
    $normalChatConfig = Get-Content -LiteralPath $NormalChatConfigPath -Raw | ConvertFrom-Json
    $normalChatEndpoint = [Uri][string]$normalChatConfig.baseUrl
}
catch { Fail 'normal-chat LLM configuration is invalid' }
if ([string]$normalChatConfig.provider -ne $RequiredProvider) {
    Fail "normal-chat provider must be $RequiredProvider"
}
if ([string]$normalChatConfig.model -ne $RequiredModel) {
    Fail "normal-chat model must be $RequiredModel"
}
if ($normalChatEndpoint.Host -ne $RequiredEndpointHost) {
    Fail "normal-chat endpoint host must be $RequiredEndpointHost"
}

$branch = (& git -C $repositoryRoot rev-parse --abbrev-ref HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $branch -ne $RequiredBranches[$Variant]) {
    Fail "variant $Variant requires branch $($RequiredBranches[$Variant]); current branch is $branch"
}
$workingChanges = @(& git -C $repositoryRoot status --porcelain)
if ($LASTEXITCODE -ne 0 -or $workingChanges.Count -ne 0) {
    Fail 'working tree must be clean'
}
$promptHash = Get-PromptHash $promptSource
if ($promptHash -ne $PromptHashes[$Variant]) {
    Fail "prompt hash mismatch for $Variant"
}

$provider = $RequiredProvider
$endpointIdentifier = $RequiredEndpointIdentifier
if ($ModelId -match '^ep-' -or $DeepSeekBaseUrl -match 'volces\.com') {
    Fail 'Ark model/endpoint must not be mixed with the DeepSeek experiment configuration'
}
if ($DeepSeekChatUrl -notmatch '^https://api\.deepseek\.com/' -or
    $DeepSeekBaseUrl -notmatch '^https://api\.deepseek\.com/') {
    Fail 'DeepSeek model must use the required DeepSeek endpoint'
}

$embeddingPreflight = Invoke-OllamaEmbeddingPreflight
Write-Host "Embedding preflight passed: provider=$($embeddingPreflight.Provider) model=$($embeddingPreflight.Model) dimension=$($embeddingPreflight.Dimension) endpoint=$($embeddingPreflight.EndpointIdentifier)"

$credential = [Environment]::GetEnvironmentVariable('MINDPET_LLM_API_KEY', 'Process')
$credentialSource = 'environment:MINDPET_LLM_API_KEY'
if ([string]::IsNullOrWhiteSpace($credential)) {
    if ($Mode -eq 'Execute') {
        Fail 'MINDPET_LLM_API_KEY must be set for Execute mode'
    }
    $credential = 'preflight-no-ai-' + [Guid]::NewGuid().ToString('N')
    $credentialSource = 'preflight-no-ai-sentinel'
}

$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss') + '-' +
    ([Guid]::NewGuid().ToString('N').Substring(0, 8))
$evalBaseCanonical = Get-CanonicalPath $EvalRootBase
$evalRoot = Get-CanonicalPath (Join-Path $evalBaseCanonical "$Variant-$runId")
$databasePath = Get-CanonicalPath (Join-Path $evalRoot 'mindpet-e2e.db')
if (Test-Path -LiteralPath $evalRoot) { Fail 'new evaluation root already exists' }

$productionCandidates = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
[void]$productionCandidates.Add((Get-CanonicalPath (Join-Path $HOME '.mindpet\mindpet.db')))
if ($env:MINDPET_DATA_DIR) {
    [void]$productionCandidates.Add((Get-CanonicalPath (Join-Path $env:MINDPET_DATA_DIR 'mindpet.db')))
}
if ($env:USER_DATA_PATH) {
    [void]$productionCandidates.Add((Get-CanonicalPath (Join-Path $env:USER_DATA_PATH 'backend\mindpet.db')))
}
if ($env:APPDATA) {
    [void]$productionCandidates.Add((Get-CanonicalPath (Join-Path $env:APPDATA 'mindpet\backend\mindpet.db')))
}
[void]$productionCandidates.Add((Get-CanonicalPath (Join-Path $repositoryRoot 'data\backend\mindpet.db')))
if ($productionCandidates.Contains($databasePath)) { Fail 'evaluation database resolves to a production path' }
if (Test-Path -LiteralPath $databasePath) { Fail 'evaluation database must not exist before startup' }

$temperatureText = $Temperature.ToString([Globalization.CultureInfo]::InvariantCulture)
Write-Host "Experiment 1 $Variant $Mode"
Write-Host "Branch: $branch"
Write-Host "Prompt SHA-256: $promptHash"
Write-Host "Model: $ModelId"
Write-Host "Provider: $provider"
Write-Host "Endpoint identifier: $endpointIdentifier"
Write-Host "Temperature: $temperatureText"
Write-Host "LLM timeout: connect=$LlmConnectTimeout read=$LlmReadTimeout"
Write-Host "Retry: Spring AI max-attempts=$SpringAiRetryMaxAttempts, backoff=$SpringAiRetryBackoffInitial..$SpringAiRetryBackoffMax; runner=no retry"
Write-Host "Evaluation SQLite: $databasePath"

if (-not $SkipBuild) {
    & mvn -q -DskipTests package -f (Join-Path $javaRoot 'pom.xml')
    if ($LASTEXITCODE -ne 0) { Fail 'Maven package failed' }
}
$jar = Get-ChildItem -LiteralPath (Join-Path $javaRoot 'target') -Filter '*.jar' -File |
    Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike 'original-*' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $jar) { Fail 'backend jar was not found; run without -SkipBuild' }

New-Item -ItemType Directory -Path $evalRoot | Out-Null
$embeddingRuntimeConfigPath = Join-Path $evalRoot 'embedding-config.json'
Write-SafeJson $embeddingRuntimeConfigPath ([ordered]@{
    mode = 'OLLAMA'
    doubaoApiKey = ''
    doubaoEndpoint = ''
    doubaoModel = ''
})
$tokenBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($tokenBytes)
}
finally {
    $rng.Dispose()
}
$token = [Convert]::ToBase64String($tokenBytes)
$wrongToken = [Guid]::NewGuid().ToString('N')
$port = Get-FreeLoopbackPort
$baseUrl = "http://127.0.0.1:$port"
$stdoutPath = Join-Path $evalRoot 'backend.stdout.log'
$stderrPath = Join-Path $evalRoot 'backend.stderr.log'
$backend = $null
$environmentNames = @(
    'APP_EVAL_E2E_MEMORY_ENABLED', 'APP_EVAL_E2E_MEMORY_TOKEN',
    'APP_EVAL_E2E_MEMORY_SQLITE_PATH', 'APP_EVAL_E2E_MEMORY_ALLOWED_ROOT',
    'APP_STORAGE_SQLITE_PATH', 'LLM_MODEL', 'MINDPET_LLM_MODEL',
    'SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL', 'SPRING_AI_OPENAI_CHAT_OPTIONS_TEMPERATURE',
    'LLM_API_KEY', 'MINDPET_LLM_API_KEY', 'SPRING_AI_OPENAI_API_KEY',
    'LLM_API_URL', 'MINDPET_LLM_CHAT_URL', 'MINDPET_LLM_BASE_URL',
    'SPRING_AI_OPENAI_BASE_URL', 'SPRING_AI_OPENAI_CHAT_COMPLETIONS_PATH',
    'SPRING_AI_OPENAI_CONNECT_TIMEOUT', 'SPRING_AI_OPENAI_READ_TIMEOUT',
    'SPRING_AI_RETRY_MAX_ATTEMPTS', 'SPRING_AI_RETRY_BACKOFF_INITIAL_INTERVAL',
    'SPRING_AI_RETRY_BACKOFF_MAX_INTERVAL',
    'APP_EMBEDDING_OLLAMA_ENDPOINT', 'APP_EMBEDDING_OLLAMA_MODEL',
    'APP_EMBEDDING_OLLAMA_KEEP_ALIVE'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $processEnvironment = @{
        APP_EVAL_E2E_MEMORY_ENABLED = 'true'
        APP_EVAL_E2E_MEMORY_TOKEN = $token
        APP_EVAL_E2E_MEMORY_SQLITE_PATH = $databasePath
        APP_EVAL_E2E_MEMORY_ALLOWED_ROOT = $evalRoot
        APP_STORAGE_SQLITE_PATH = $databasePath
        LLM_MODEL = $ModelId
        MINDPET_LLM_MODEL = $ModelId
        SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL = $ModelId
        SPRING_AI_OPENAI_CHAT_OPTIONS_TEMPERATURE = $temperatureText
        LLM_API_KEY = $credential
        MINDPET_LLM_API_KEY = $credential
        SPRING_AI_OPENAI_API_KEY = $credential
        LLM_API_URL = $DeepSeekChatUrl
        MINDPET_LLM_CHAT_URL = $DeepSeekChatUrl
        MINDPET_LLM_BASE_URL = $DeepSeekBaseUrl
        SPRING_AI_OPENAI_BASE_URL = $DeepSeekBaseUrl
        SPRING_AI_OPENAI_CHAT_COMPLETIONS_PATH = '/chat/completions'
        SPRING_AI_OPENAI_CONNECT_TIMEOUT = $LlmConnectTimeout
        SPRING_AI_OPENAI_READ_TIMEOUT = $LlmReadTimeout
        SPRING_AI_RETRY_MAX_ATTEMPTS = [string]$SpringAiRetryMaxAttempts
        SPRING_AI_RETRY_BACKOFF_INITIAL_INTERVAL = $SpringAiRetryBackoffInitial
        SPRING_AI_RETRY_BACKOFF_MAX_INTERVAL = $SpringAiRetryBackoffMax
        APP_EMBEDDING_OLLAMA_ENDPOINT = $RequiredEmbeddingEndpoint
        APP_EMBEDDING_OLLAMA_MODEL = $RequiredEmbeddingModel
        APP_EMBEDDING_OLLAMA_KEEP_ALIVE = $RequiredEmbeddingKeepAlive
    }
    foreach ($item in $processEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($item.Key, $item.Value, 'Process')
    }

    $configUri = [Uri]::new($configCanonical).AbsoluteUri
    $backend = Start-Process -FilePath 'java' -WindowStyle Hidden -PassThru `
        -WorkingDirectory $evalRoot `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath `
        -ArgumentList @(
            '-jar', $jar.FullName,
            "--server.address=127.0.0.1",
            "--server.port=$port",
            "--spring.config.location=$configUri",
            "--app.storage.sqlite.path=$databasePath"
        )

    $readinessTimeoutSeconds = 30
    $deadline = [DateTime]::UtcNow.AddSeconds($readinessTimeoutSeconds)
    $readinessWatch = [Diagnostics.Stopwatch]::StartNew()
    $noTokenResult = $null
    $lastReadinessStatus = 'none'
    $lastReadinessError = 'none'
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($backend.HasExited) { Fail "backend exited during startup with code $($backend.ExitCode)" }
        try {
            $candidate = Invoke-EvalRequest 'GET' "$baseUrl/api/eval/memory/snapshot" $null $null 3
            $lastReadinessStatus = [string]$candidate.StatusCode
            $lastReadinessError = 'none'
            if ($candidate.StatusCode -eq 401) { $noTokenResult = $candidate; break }
        }
        catch {
            $lastReadinessStatus = 'none'
            $lastReadinessError = "$($_.Exception.GetType().FullName): $($_.Exception.Message)"
        }
        Start-Sleep -Milliseconds 500
    }
    $readinessWatch.Stop()
    if ($null -eq $noTokenResult) {
        Fail "backend did not become ready within $readinessTimeoutSeconds seconds; expected HTTP 401; last HTTP status=$lastReadinessStatus; last error=$lastReadinessError"
    }
    Write-Host "Backend readiness confirmed: HTTP 401 after $([Math]::Round($readinessWatch.Elapsed.TotalSeconds, 3)) seconds."

    $embeddingProviderLog = @(Get-Content -LiteralPath $stdoutPath | Where-Object {
        $_ -match 'Embedding provider.*Ollama/bge-m3.*mode=OLLAMA.*reason=OK_OLLAMA'
    })
    if ($embeddingProviderLog.Count -eq 0) {
        Fail 'EMBEDDING_BACKEND_PROVIDER_MISMATCH: expected Ollama/bge-m3 mode=OLLAMA reason=OK_OLLAMA'
    }
    Write-Host 'Backend embedding provider confirmed: Ollama/bge-m3 (mode=OLLAMA).'

    $wrong = Invoke-EvalRequest 'GET' "$baseUrl/api/eval/memory/snapshot" $wrongToken $null 10
    if ($wrong.StatusCode -ne 401) { Fail 'wrong token was not rejected' }
    $correct = Invoke-EvalRequest 'GET' "$baseUrl/api/eval/memory/snapshot" $token $null 10
    if ($correct.StatusCode -ne 200) { Fail 'correct-token snapshot failed' }
    Assert-EmptySnapshot $correct.Body $databasePath $promptHash

    $reset = Invoke-EvalRequest 'POST' "$baseUrl/api/eval/memory/reset" $token '{}' 10
    if ($reset.StatusCode -ne 200 -or $reset.Body.status -ne 'OK') { Fail 'evaluation reset failed' }
    $afterReset = Invoke-EvalRequest 'GET' "$baseUrl/api/eval/memory/snapshot" $token $null 10
    if ($afterReset.StatusCode -ne 200) { Fail 'post-reset snapshot failed' }
    Assert-EmptySnapshot $afterReset.Body $databasePath $promptHash

    $preflightResult = [ordered]@{
        status = 'PASSED'
        mode = $Mode
        variant = $Variant
        run_id = $runId
        branch = $branch
        git_commit = (& git -C $repositoryRoot rev-parse HEAD).Trim()
        prompt_sha256 = $promptHash
        provider = $provider
        model = $ModelId
        endpoint_identifier = $endpointIdentifier
        endpoint_config_identifier = $endpointIdentifier
        credential_source = $credentialSource
        embedding_provider = $embeddingPreflight.Provider
        embedding_model = $embeddingPreflight.Model
        embedding_dimension = $embeddingPreflight.Dimension
        embedding_endpoint_identifier = $embeddingPreflight.EndpointIdentifier
        temperature = $Temperature
        llm_connect_timeout = $LlmConnectTimeout
        llm_read_timeout = $LlmReadTimeout
        spring_ai_retry = [ordered]@{
            max_attempts = $SpringAiRetryMaxAttempts
            backoff_initial = $SpringAiRetryBackoffInitial
            backoff_max = $SpringAiRetryBackoffMax
        }
        runner_http_timeout_seconds = $RunnerTimeoutSeconds
        sqlite_canonical_absolute_path = $databasePath
        sqlite_schema_sha256 = Get-Sha256 $sqliteSchema
        fixed_eval_user = $FixedEvalUser
        no_token_http_status = $noTokenResult.StatusCode
        wrong_token_http_status = $wrong.StatusCode
        correct_token_http_status = $correct.StatusCode
        reset_http_status = $reset.StatusCode
        post_reset_counts = [ordered]@{}
        ingest_called = $false
        evaluation_infrastructure_commit = $EvaluationInfrastructureCommit
    }
    foreach ($tableName in $TableNames) {
        $preflightResult.post_reset_counts[$tableName] = [int]$afterReset.Body.tables.PSObject.Properties[$tableName].Value.count
    }
    Write-SafeJson (Join-Path $evalRoot 'preflight_result.json') $preflightResult
    Write-Host 'No-AI preflight passed.'

    if ($Mode -eq 'Execute') {
        $runnerArgs = @(
            $runner,
            '--dataset', $dataset,
            '--results-root', $resultsRoot,
            '--base-url', $baseUrl,
            '--run-id', $runId,
            '--expected-prompt-variant', $Variant,
            '--provider', $provider,
            '--endpoint-config-id', $endpointIdentifier,
            '--credential-source', $credentialSource,
            '--expected-model-id', $ModelId,
            '--embedding-provider', $embeddingPreflight.Provider,
            '--embedding-model', $embeddingPreflight.Model,
            '--embedding-dimension', [string]$embeddingPreflight.Dimension,
            '--embedding-endpoint-identifier', $embeddingPreflight.EndpointIdentifier,
            '--temperature', $temperatureText,
            '--llm-connect-timeout', $LlmConnectTimeout,
            '--llm-read-timeout', $LlmReadTimeout,
            '--spring-ai-retry-max-attempts', [string]$SpringAiRetryMaxAttempts,
            '--spring-ai-retry-backoff-initial', $SpringAiRetryBackoffInitial,
            '--spring-ai-retry-backoff-max', $SpringAiRetryBackoffMax,
            '--evaluation-infrastructure-commit', $EvaluationInfrastructureCommit,
            '--timeout', [string]$RunnerTimeoutSeconds
        )
        if ($Variant -eq 'v21') {
            $pythonScriptDirectory = $PSScriptRoot.Replace('\', '\\')
            $runnerBootstrap = "import sys; sys.path.insert(0, r'$pythonScriptDirectory'); import run_e2e_pilot as runner; runner.PROMPT_HASHES['v21']='$($PromptHashes.v21)'; raise SystemExit(runner.main())"
            $v21RunnerArgs = $runnerArgs[1..($runnerArgs.Count - 1)]
            & python -c $runnerBootstrap @v21RunnerArgs
        }
        else {
            & python @runnerArgs
        }
        if ($LASTEXITCODE -ne 0) { Fail '30-sample runner failed' }
        $runDirectory = Join-Path (Join-Path $resultsRoot $Variant) $runId
        & python $evaluator --dataset $dataset --run-dir $runDirectory
        if ($LASTEXITCODE -ne 0) { Fail 'evaluator failed' }
        & python $analyzer --dataset $dataset --run-dir $runDirectory
        if ($LASTEXITCODE -ne 0) { Fail 'error analysis failed' }
        Write-Host "Execute completed: $runDirectory"
    }
}
finally {
    if ($null -ne $backend -and -not $backend.HasExited) {
        Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
        $backend.WaitForExit(10000) | Out-Null
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    $token = $null
    $wrongToken = $null
    $credential = $null
}
