$ErrorActionPreference = 'Stop'
$baseUrl = 'http://127.0.0.1:8080'
$databaseCandidates = @()
if ($env:USER_DATA_PATH) {
    $databaseCandidates += Join-Path $env:USER_DATA_PATH 'backend\mindpet.db'
}
if ($env:APPDATA) {
    $databaseCandidates += Join-Path $env:APPDATA 'mindpet\backend\mindpet.db'
}
$databaseCandidates += Join-Path $PSScriptRoot '..\data\backend\mindpet.db'
$database = $databaseCandidates |
    ForEach-Object { [System.IO.Path]::GetFullPath($_) } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Select-Object -First 1

function Check-Api($path, $label) {
    $response = Invoke-RestMethod -Uri ($baseUrl + $path) -TimeoutSec 8
    if ($response.status -ne 'ok') {
        throw "$label returned status '$($response.status)'"
    }
    Write-Host "  PASS  $label"
    return $response
}

Write-Host 'MindPet local SQLite verification'
Write-Host "Database: $database"

if (-not $database) {
    throw 'SQLite database not found. Start the desktop app with XiaoqingDesktop.bat first.'
}
Write-Host '  PASS  SQLite database file exists'

$health = Check-Api '/api/desktop/health' 'backend health'
if ($health.service -ne 'mindpet-desktop-api') {
    throw "Unexpected service response: $($health.service)"
}

$tables = Check-Api '/api/desktop/memory/tables' 'memory tables'
$requiredTables = @('long_term_memory', 'user_profile', 'user_insight', 'llm_growth')
foreach ($table in $requiredTables) {
    if (@($tables.tables.PSObject.Properties.Name) -notcontains $table) {
        throw "Required SQLite table missing from response: $table"
    }
}
Write-Host '  PASS  required memory tables are available'

[void](Check-Api '/api/desktop/sessions' 'session storage')
[void](Check-Api '/api/desktop/knowledge-graph/stats' 'knowledge graph storage')

Write-Host ''
Write-Host 'Local SQLite checks passed. LLM and Embedding connectivity are not tested here.'
