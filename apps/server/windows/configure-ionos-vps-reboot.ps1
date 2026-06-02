param(
    [string]$ConfigDir = "$env:APPDATA\AdFreePodcastPlayer\ionos",
    [string]$ApiBase = 'https://cloudpanel-api.ionos.com/v1',
    [string]$ServerId = '',
    [string]$ServerName = '',
    [switch]$CreateDesktopShortcut
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

function Get-ServerIps {
    param([object]$Server)

    $ips = @()
    if ($Server.ips) {
        foreach ($ip in $Server.ips) {
            if ($ip.ip) { $ips += [string]$ip.ip }
        }
    }
    ($ips -join ', ')
}

function New-RebootShortcut {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string]$ShortcutPath
    )

    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($ShortcutPath)
    $shortcut.TargetPath = 'powershell.exe'
    $shortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptPath`""
    $shortcut.WorkingDirectory = Split-Path -Parent $ScriptPath
    $shortcut.IconLocation = "$env:WINDIR\System32\shell32.dll,238"
    $shortcut.Description = 'Hardware reboot the Ad Free Podcast Player VPS through the IONOS API'
    $shortcut.Save()
}

New-Item -ItemType Directory -Path $ConfigDir -Force | Out-Null
$tokenPath = Join-Path $ConfigDir 'ionos-api-token.xml'
$configPath = Join-Path $ConfigDir 'vps-reboot.json'

Write-Host ''
Write-Host 'IONOS VPS reboot setup'
Write-Host '----------------------'
Write-Host 'Paste the IONOS Cloud Panel API token for a user with server power permissions.'
Write-Host 'The token will be encrypted for this Windows user on this machine.'
Write-Host ''

$secureToken = Read-Host -Prompt 'IONOS API token' -AsSecureString
$secureToken | Export-Clixml -Path $tokenPath
$plainToken = ConvertFrom-SecureStringToPlainText -Secure $secureToken

try {
    $servers = @(Invoke-IonosApi -Token $plainToken -Uri "$ApiBase/servers")
} finally {
    $plainToken = $null
}

if ($servers.Count -eq 0) {
    throw 'The IONOS API returned no servers for this token.'
}

Write-Host ''
Write-Host 'Available IONOS servers:'
for ($i = 0; $i -lt $servers.Count; $i += 1) {
    $server = $servers[$i]
    $state = if ($server.status -and $server.status.state) { $server.status.state } else { 'unknown' }
    $ips = Get-ServerIps -Server $server
    Write-Host ("[{0}] {1}  id={2}  state={3}  ips={4}" -f ($i + 1), $server.name, $server.id, $state, $ips)
}

$selected = $null
if ($ServerId -ne '') {
    $selected = $servers | Where-Object { $_.id -eq $ServerId } | Select-Object -First 1
    if (-not $selected) {
        throw "Server id was not found: $ServerId"
    }
} elseif ($ServerName -ne '') {
    $selected = $servers | Where-Object { $_.name -eq $ServerName } | Select-Object -First 1
    if (-not $selected) {
        throw "Server name was not found: $ServerName"
    }
} else {
    $choice = Read-Host -Prompt 'Choose server number'
    $index = 0
    if (-not [int]::TryParse($choice, [ref]$index) -or $index -lt 1 -or $index -gt $servers.Count) {
        throw "Invalid server selection: $choice"
    }
    $selected = $servers[$index - 1]
}

$config = [ordered]@{
    api_base = $ApiBase
    server_id = [string]$selected.id
    server_name = [string]$selected.name
    cloudpanel_id = if ($selected.cloudpanel_id) { [string]$selected.cloudpanel_id } else { '' }
    default_method = 'HARDWARE'
    configured_at = (Get-Date).ToUniversalTime().ToString('o')
}

$config | ConvertTo-Json -Depth 6 | Set-Content -Path $configPath -Encoding UTF8

Write-Host ''
Write-Host "Saved config: $configPath"
Write-Host "Saved encrypted token: $tokenPath"

if ($CreateDesktopShortcut) {
    $rebootScript = Join-Path $PSScriptRoot 'reboot-ionos-vps.ps1'
    $shortcutPath = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Reboot AdFree VPS.lnk'
    New-RebootShortcut -ScriptPath $rebootScript -ShortcutPath $shortcutPath
    Write-Host "Created shortcut: $shortcutPath"
}

Write-Host ''
Write-Host 'Setup complete. Run reboot-ionos-vps.ps1 to trigger a hardware reboot.'
