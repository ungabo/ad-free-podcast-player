param(
    [Parameter(Mandatory = $true)]
    [string]$RemoteHost,

    [Parameter(Mandatory = $true)]
    [string]$RemoteUser,

    [string]$TaskName = 'AdFree Local Reverse Tunnel',
    [int]$RemoteSshPort = 22,
    [int]$RemoteBindPort = 8081,
    [string]$RemoteBindHost = '127.0.0.1',
    [int]$LocalHttpPort = 80,
    [string]$WampManagerPath = 'D:\wamp64\wampmanager.exe',
    [string]$IdentityFile = '',
    [switch]$BatchMode,
    [switch]$WampTriggered
)

$ErrorActionPreference = 'Stop'

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    $selfArgs = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', '"' + $PSCommandPath + '"',
        '-RemoteHost', '"' + $RemoteHost + '"',
        '-RemoteUser', '"' + $RemoteUser + '"',
        '-TaskName', '"' + $TaskName + '"',
        '-RemoteSshPort', "$RemoteSshPort",
        '-RemoteBindPort', "$RemoteBindPort",
        '-RemoteBindHost', '"' + $RemoteBindHost + '"',
        '-LocalHttpPort', "$LocalHttpPort",
        '-WampManagerPath', '"' + $WampManagerPath + '"'
    )

    if ($IdentityFile -ne '') {
        $selfArgs += @('-IdentityFile', '"' + $IdentityFile + '"')
    }

    if ($BatchMode) {
        $selfArgs += '-BatchMode'
    }

    if ($WampTriggered) {
        $selfArgs += '-WampTriggered'
    }

    Start-Process -Verb RunAs -FilePath 'powershell.exe' -ArgumentList ($selfArgs -join ' ')
    Write-Host 'Re-launched installer with elevation. Complete the admin prompt to finish task registration.'
    exit 0
}

$scriptPath = Join-Path $PSScriptRoot 'run-local-bridge.ps1'
$triggeredScriptPath = Join-Path $PSScriptRoot 'run-wamp-triggered-bridge.ps1'
if (-not (Test-Path $scriptPath)) {
    throw "run-local-bridge.ps1 not found at $scriptPath"
}
if (-not (Test-Path $triggeredScriptPath)) {
    throw "run-wamp-triggered-bridge.ps1 not found at $triggeredScriptPath"
}

$selectedScript = if ($WampTriggered) { $triggeredScriptPath } else { $scriptPath }

$argList = @(
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', '"' + $selectedScript + '"',
    '-RemoteHost', '"' + $RemoteHost + '"',
    '-RemoteUser', '"' + $RemoteUser + '"',
    '-RemoteSshPort', "$RemoteSshPort",
    '-RemoteBindPort', "$RemoteBindPort",
    '-RemoteBindHost', '"' + $RemoteBindHost + '"',
    '-LocalHttpPort', "$LocalHttpPort"
)

if (-not $WampTriggered) {
    $argList += @('-WampManagerPath', '"' + $WampManagerPath + '"', '-Loop')
}

if ($IdentityFile -ne '') {
    $argList += @('-IdentityFile', '"' + $IdentityFile + '"')
}

if ($BatchMode) {
    $argList += '-BatchMode'
}

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ($argList -join ' ')
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Description 'Starts WAMP and maintains reverse SSH tunnel for local adfree API bridge' -Force | Out-Null
Write-Host "Registered scheduled task: $TaskName"
