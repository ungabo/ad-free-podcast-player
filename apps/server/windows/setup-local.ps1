param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path,
    [string]$WampRoot = 'D:\wamp64\www',
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
Copy-Item (Join-Path $apiPublic 'index.php') (Join-Path $apiTarget 'index.php') -Force

if ($DisableUi) {
    New-Item -ItemType File -Force -Path (Join-Path $webTarget '.ui-disabled') | Out-Null
} else {
    Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $webTarget '.ui-disabled')
}

Write-Host "Synced web UI to $webTarget"
Write-Host "Synced API to $apiTarget"
Write-Host "Storage root expected at $storageRoot"
Write-Host "UI enabled: $(-not $DisableUi)"
