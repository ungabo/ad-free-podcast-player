param(
    [string]$ConfigDir = "$env:APPDATA\AdFreePodcastPlayer\ionos",
    [ValidateSet('HARDWARE', 'SOFTWARE')]
    [string]$Method = '',
    [switch]$StatusOnly,
    [switch]$Force,
    [switch]$Configure
)

$ErrorActionPreference = 'Stop'

function ConvertFrom-SecureStringToPlainText {
    param([Parameter(Mandatory = $true)][securestring]$Secure)

    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    } finally {
        if ($ptr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        }
    }
}

function Invoke-IonosApi {
    param(
        [Parameter(Mandatory = $true)][string]$Token,
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Method = 'GET',
        [object]$Body = $null
    )

    $headers = @{ 'X-TOKEN' = $Token }
    $args = @{
        Uri = $Uri
        Method = $Method
        Headers = $headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $args.ContentType = 'application/json'
        $args.Body = ($Body | ConvertTo-Json -Depth 6)
    }

    Invoke-RestMethod @args
}

if ($Configure) {
    & (Join-Path $PSScriptRoot 'configure-ionos-vps-reboot.ps1') -ConfigDir $ConfigDir -CreateDesktopShortcut
    exit $LASTEXITCODE
}

$configPath = Join-Path $ConfigDir 'vps-reboot.json'
$tokenPath = Join-Path $ConfigDir 'ionos-api-token.xml'

if (-not (Test-Path -LiteralPath $configPath) -or -not (Test-Path -LiteralPath $tokenPath)) {
    Write-Host 'IONOS reboot is not configured yet.'
    & (Join-Path $PSScriptRoot 'configure-ionos-vps-reboot.ps1') -ConfigDir $ConfigDir -CreateDesktopShortcut
    exit $LASTEXITCODE
}

$config = Get-Content -Path $configPath -Raw | ConvertFrom-Json
if (-not $config.server_id) {
    throw "Missing server_id in $configPath"
}

$apiBase = if ($config.api_base) { [string]$config.api_base } else { 'https://cloudpanel-api.ionos.com/v1' }
$serverId = [string]$config.server_id
$serverName = if ($config.server_name) { [string]$config.server_name } else { $serverId }
$selectedMethod = if ($Method -ne '') { $Method } elseif ($config.default_method) { [string]$config.default_method } else { 'HARDWARE' }

$secureToken = Import-Clixml -Path $tokenPath
$plainToken = ConvertFrom-SecureStringToPlainText -Secure $secureToken

try {
    $status = Invoke-IonosApi -Token $plainToken -Uri "$apiBase/servers/$serverId/status"
    $state = if ($status.state) { [string]$status.state } else { 'unknown' }
    $percent = if ($null -ne $status.percent) { [string]$status.percent } else { '-' }

    Write-Host ''
    Write-Host "IONOS server: $serverName"
    Write-Host "Server id: $serverId"
    Write-Host "Current state: $state ($percent%)"

    if ($StatusOnly) {
        exit 0
    }

    if (-not $Force) {
        Write-Host ''
        Write-Host "This will request a $selectedMethod reboot through IONOS."
        $confirmation = Read-Host -Prompt 'Type REBOOT to continue'
        if ($confirmation -ne 'REBOOT') {
            Write-Host 'Cancelled.'
            exit 0
        }
    }

    $body = @{
        action = 'REBOOT'
        method = $selectedMethod
    }
    $result = Invoke-IonosApi -Token $plainToken -Uri "$apiBase/servers/$serverId/status/action" -Method 'PUT' -Body $body

    Write-Host ''
    Write-Host "Reboot accepted by IONOS for $serverName."
    if ($result.status -and $result.status.state) {
        Write-Host ("New state: {0}" -f $result.status.state)
    }
    Write-Host 'Wait a few minutes, then retry SSH/API checks.'
} finally {
    $plainToken = $null
}
