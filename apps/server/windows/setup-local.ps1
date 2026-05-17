param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path,
    [string]$WampRoot = 'D:\wamp64\www',
    [string]$AdCutForgeRoot = (Join-Path $RepoRoot 'apps\server\adcutforge'),
    [string]$AdCutForgePython = 'C:\Users\Gabe\AppData\Local\Programs\Python\Python311\python.exe',
    [string]$FfmpegBin = $(try { (Get-Command ffmpeg.exe -ErrorAction Stop).Source } catch { 'ffmpeg' }),
    [string]$FfprobeBin = $(try { (Get-Command ffprobe.exe -ErrorAction Stop).Source } catch { 'ffprobe' }),
    [string]$CaBundlePath = 'D:\wamp64\storage\cacert.pem',
    [string]$OpenAiApiKey = $(if (-not [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) { $env:OPENAI_API_KEY } elseif (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'User'))) { [Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'User') } else { [Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'Machine') }),
    [switch]$SkipCaBundleDownload,
    [switch]$DisableUi
)

function Get-DotEnvValue {
    param(
        [string]$Path,
        [string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return ''
    }

    $match = Get-Content -LiteralPath $Path | Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($match)) {
        return ''
    }

    $value = ($match -split '=', 2)[1].Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    return $value
}

$serverEnvPath = Join-Path $PSScriptRoot '..\.env'
if ([string]::IsNullOrWhiteSpace($OpenAiApiKey)) {
    $OpenAiApiKey = Get-DotEnvValue -Path $serverEnvPath -Name 'OPENAI_API_KEY'
}

$webDist = Join-Path $RepoRoot 'apps\web\dist'
$apiPublic = Join-Path $RepoRoot 'apps\server\api\public'
$webTarget = Join-Path $WampRoot 'adfree-web'
$apiTarget = Join-Path $WampRoot 'adfree-api'
$storageRoot = 'D:\wamp64\storage'

if (-not (Test-Path $webDist)) {
    throw "Missing web build output at $webDist. Run the web build first."
}

New-Item -ItemType Directory -Force -Path $webTarget, $apiTarget, $storageRoot | Out-Null

$caBundleDir = Split-Path -Parent $CaBundlePath
if ($caBundleDir) {
    New-Item -ItemType Directory -Force -Path $caBundleDir | Out-Null
}

if (-not $SkipCaBundleDownload -and -not (Test-Path $CaBundlePath)) {
    Write-Host "Downloading Mozilla CA bundle to $CaBundlePath"
    Invoke-WebRequest -Uri 'https://curl.se/ca/cacert.pem' -OutFile $CaBundlePath
}

$caBundleForApache = $CaBundlePath.Replace('\', '/')
$openAiApiKeyLine = ''
if (-not [string]::IsNullOrWhiteSpace($OpenAiApiKey)) {
    $openAiApiKeyLine = "SetEnv OPENAI_API_KEY `"$OpenAiApiKey`"`r`n"
}

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
SetEnv LOCAL_BRIDGE_URL "http://127.0.0.1/adfree-api"
SetEnv LOCAL_ADCUTFORGE_ROOT "$($AdCutForgeRoot.Replace('\', '/'))"
SetEnv LOCAL_ADCUTFORGE_PYTHON "$($AdCutForgePython.Replace('\', '/'))"
SetEnv FFMPEG_BIN "$($FfmpegBin.Replace('\', '/'))"
SetEnv FFPROBE_BIN "$($FfprobeBin.Replace('\', '/'))"
${openAiApiKeyLine}SetEnv OPENAI_MODEL "gpt-5.5"
SetEnv ADFREE_CA_BUNDLE "$caBundleForApache"
SetEnv CURL_CA_BUNDLE "$caBundleForApache"
SetEnv SSL_CERT_FILE "$caBundleForApache"

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
Write-Host "CA bundle expected at $CaBundlePath"
Write-Host "UI enabled: $(-not $DisableUi)"
