param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path,
    [string]$WampRoot = 'D:\wamp64\www',
    [string]$AdCutForgeRoot = 'C:\Users\Gabe\Documents\Codex\2026-05-08\podcast ad remover',
    [string]$AdCutForgePython = 'C:\Users\Gabe\AppData\Local\Programs\Python\Python311\python.exe',
    [switch]$DisableUi
)

$webDist = Join-Path $RepoRoot 'apps\web\dist'
$apiPublic = Join-Path $RepoRoot 'apps\server\api\public'
$webTarget = Join-Path $WampRoot 'adfree-web'
$apiTarget = Join-Path $WampRoot 'adfree-api'
$storageRoot = 'D:\wamp64\storage'

if (-not (Test-Path $webDist)) {
    throw "Missing web build output at $webDist. Run the web build first."
}

New-Item -ItemType Directory -Force -Path $webTarget, $apiTarget, $storageRoot | Out-Null

Get-ChildItem -Force $webDist | Copy-Item -Destination $webTarget -Recurse -Force
Get-ChildItem -Force $apiPublic | Copy-Item -Destination $apiTarget -Recurse -Force

$apiHtaccess = @"
SetEnv APP_STORAGE "D:/wamp64/storage"
SetEnv DB_PATH "D:/wamp64/storage/jobs.sqlite"
SetEnv QUEUE_DIR "D:/wamp64/storage/queue"
SetEnv INPUT_DIR "D:/wamp64/storage/input"
SetEnv OUTPUT_DIR "D:/wamp64/storage/output"
SetEnv ARTIFACTS_DIR "D:/wamp64/storage/artifacts"
SetEnv WORKER_HEARTBEAT "D:/wamp64/storage/worker-heartbeat.json"
SetEnv WORKER_LOCK_DIR "D:/wamp64/storage/worker.lock"
SetEnv APP_BASE_PATH "/adfree-api"
SetEnv LOCAL_BRIDGE_URL "http://127.0.0.1:8081/adfree-api"
SetEnv LOCAL_ADCUTFORGE_ROOT "$($AdCutForgeRoot.Replace('\', '/'))"
SetEnv LOCAL_ADCUTFORGE_PYTHON "$($AdCutForgePython.Replace('\', '/'))"

RewriteEngine On
RewriteCond %{REQUEST_FILENAME} !-f
RewriteCond %{REQUEST_FILENAME} !-d
RewriteRule ^ index.php [QSA,L]
"@
$apiHtaccess | Set-Content -Path (Join-Path $apiTarget '.htaccess') -Encoding ASCII

if ($DisableUi) {
    New-Item -ItemType File -Force -Path (Join-Path $webTarget '.ui-disabled') | Out-Null
} else {
    Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $webTarget '.ui-disabled')
}

Write-Host "Synced web UI to $webTarget"
Write-Host "Synced API to $apiTarget"
Write-Host "Storage root expected at $storageRoot"
Write-Host "UI enabled: $(-not $DisableUi)"
