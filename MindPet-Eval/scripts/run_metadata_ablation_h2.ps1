param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$evalRoot = Join-Path $projectRoot 'MindPet-Eval'
$outputRoot = Join-Path $evalRoot 'results\metadata_ablation_h2'
$rawRoot = Join-Path $outputRoot 'raw'
$apiUrl = 'http://127.0.0.1:8082/api/eval/memory/search'
$docker = 'C:\Users\Lenovo\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
$jar = Join-Path $projectRoot 'MindPet-java\target\weather-wechat-bot-1.0.0.jar'
$javaProcess = $null

try {
    if (-not (Test-Path -LiteralPath $jar)) { throw 'H2 Java JAR is missing.' }
    if (Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Port 8082 is already in use.'
    }
    $targets = @(
        (Join-Path $rawRoot 'retrieval_ablation_raw.jsonl'),
        (Join-Path $rawRoot 'run_manifest.json'),
        (Join-Path $rawRoot 'retrieval_state_before.json'),
        (Join-Path $rawRoot 'retrieval_state_after.json')
    )
    foreach ($target in $targets) {
        if (Test-Path -LiteralPath $target) { throw "Formal output already exists: $target" }
    }

    $containerEnv = @{}
    & $docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' mindpet-postgres |
        ForEach-Object {
            $parts = $_ -split '=', 2
            if ($parts.Count -eq 2) { $containerEnv[$parts[0]] = $parts[1] }
        }
    if (-not $containerEnv['POSTGRES_USER'] -or -not $containerEnv['POSTGRES_PASSWORD']) {
        throw 'PostgreSQL container credentials are unavailable.'
    }
    $env:PGPASSWORD = $containerEnv['POSTGRES_PASSWORD']
    $sql = "SELECT current_database(), COUNT(*), COUNT(*) FILTER (WHERE embedding IS NULL), COUNT(*) FILTER (WHERE vector_dims(embedding)<>1024), COUNT(DISTINCT metadata->>'benchmark_memory_id') FROM public.long_term_memory WHERE user_id='eval_test_user'"
    $dbCheck = (& $docker exec -e PGPASSWORD mindpet-postgres psql -U $containerEnv['POSTGRES_USER'] -d mindpet_eval -tAc $sql).Trim()
    if ($LASTEXITCODE -ne 0 -or $dbCheck -ne 'mindpet_eval|120|0|0|120') {
        throw "Database safety/integrity check failed: $dbCheck"
    }
    $asOfSql = "SELECT MIN(metadata->>'benchmark_base_time') FROM public.long_term_memory WHERE user_id='eval_test_user' HAVING COUNT(DISTINCT metadata->>'benchmark_base_time')=1"
    $evaluationAsOf = (& $docker exec -e PGPASSWORD mindpet-postgres psql `
        -U $containerEnv['POSTGRES_USER'] -d mindpet_eval -tAc $asOfSql).Trim()
    if (-not $evaluationAsOf) { throw 'Canonical benchmark evaluationAsOf is unavailable.' }
    Write-Output 'DB preflight: mindpet_eval, 120 memories, embeddings complete.'

    $models = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -Method Get -TimeoutSec 10
    if (-not ($models.models | Where-Object name -eq 'bge-m3:latest')) {
        throw 'Ollama bge-m3:latest is unavailable.'
    }

    $tokenBytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($tokenBytes)
    $token = [Convert]::ToHexString($tokenBytes)
    $env:MINDPET_EVAL_API_TOKEN = $token
    $env:MINDPET_EVAL_DB_USER = $containerEnv['POSTGRES_USER']
    $env:MINDPET_EVAL_DB_PASSWORD = $containerEnv['POSTGRES_PASSWORD']
    $env:APP_EVAL_RETRIEVAL_TOKEN = $token
    $env:APP_EVAL_RETRIEVAL_ENABLED = 'true'
    $env:SERVER_PORT = '8082'
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/mindpet_eval'
    $env:SPRING_DATASOURCE_USERNAME = $containerEnv['POSTGRES_USER']
    $env:SPRING_DATASOURCE_PASSWORD = $containerEnv['POSTGRES_PASSWORD']
    $env:SPRING_DATASOURCE_HIKARI_READ_ONLY = 'true'
    $env:APP_MEMORY_REDIS_ENABLED = 'false'
    $env:APP_EMBEDDING_USE_OLLAMA = 'true'
    $env:APP_EMBEDDING_OLLAMA_MODEL = 'bge-m3'
    $env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:' + ((Join-Path $projectRoot 'MindPet-java\src\main\resources\application-template.yml') -replace '\\', '/')

    $logOut = Join-Path $env:TEMP ('mindpet-h2-java-' + [Guid]::NewGuid().ToString('N') + '.out.log')
    $logErr = Join-Path $env:TEMP ('mindpet-h2-java-' + [Guid]::NewGuid().ToString('N') + '.err.log')
    $javaProcess = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar) `
        -WorkingDirectory (Join-Path $projectRoot 'MindPet-java') -WindowStyle Hidden `
        -PassThru -RedirectStandardOutput $logOut -RedirectStandardError $logErr
    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $javaProcess.Refresh()
        if ($javaProcess.HasExited) { throw 'H2 Java instance exited before listening.' }
        if (Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'H2 Java instance did not listen within 90 seconds.' }

    $body = [Text.Encoding]::UTF8.GetBytes((@{
        query = '羽毛球'; mode = 'mindpet_rrf_norm_importance'; topK = 1;
        userId = 'eval_test_user'; asOf = $evaluationAsOf
    } | ConvertTo-Json -Compress))
    $health = Invoke-WebRequest -Uri $apiUrl -Method Post -Body $body `
        -ContentType 'application/json; charset=utf-8' `
        -Headers @{'X-MindPet-Eval-Token' = $token} -TimeoutSec 120
    $healthJson = $health.Content | ConvertFrom-Json
    if ($health.StatusCode -ne 200 -or $healthJson.status -ne 'OK' -or
        $healthJson.mode -ne 'mindpet_rrf_norm_importance') {
        throw 'The single H2 health check did not return HTTP 200 / OK / expected mode.'
    }
    Write-Output 'Single H2 health check: HTTP 200 / OK.'

    $runner = Join-Path $evalRoot 'scripts\run_retrieval_ablation.py'
    $runnerArgs = @(
        '-B', $runner, '--api-url', $apiUrl, '--database', 'mindpet_eval',
        '--modes', 'rrf', 'mindpet_rrf_norm_only', 'mindpet_rrf_norm_time',
        'mindpet_rrf_norm_importance', 'mindpet_rrf_norm_importance_bonus',
        'mindpet_full_rrf_norm',
        '--experiment-name', 'mindpet_metadata_reranking_ablation_h2',
        '--benchmark-version', 'Retrieval Benchmark v1',
        '--raw-output', (Join-Path $rawRoot 'retrieval_ablation_raw.jsonl'),
        '--manifest', (Join-Path $rawRoot 'run_manifest.json'),
        '--before-state', (Join-Path $rawRoot 'retrieval_state_before.json'),
        '--after-state', (Join-Path $rawRoot 'retrieval_state_after.json')
    )
    & python @runnerArgs
    if ($LASTEXITCODE -ne 0) { throw 'Formal runner failed; no evaluation will be published.' }

    & python -B (Join-Path $evalRoot 'scripts\evaluate_metadata_ablation_h2.py')
    if ($LASTEXITCODE -ne 0) { throw 'H2 validation/sanity failed; do not interpret results.' }
    Write-Output 'H2 formal run and sanity validation PASSED.'
}
finally {
    if ($null -ne $javaProcess) {
        $javaProcess.Refresh()
        if (-not $javaProcess.HasExited) {
            Stop-Process -Id $javaProcess.Id -Force
        }
    }
    Remove-Item Env:MINDPET_EVAL_API_TOKEN,Env:APP_EVAL_RETRIEVAL_TOKEN,Env:PGPASSWORD,Env:MINDPET_EVAL_DB_PASSWORD,Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
}
