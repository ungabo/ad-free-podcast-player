param(
    [Parameter(Mandatory = $true)]
    [string]$RemoteHost,

    [Parameter(Mandatory = $true)]
    [string]$RemoteUser,

    [int]$RemoteSshPort = 22,
    [int]$RemoteBindPort = 8081,
    [string]$RemoteBindHost = '127.0.0.1',
    [int]$LocalHttpPort = 80,
    [string]$IdentityFile = '',
    [switch]$BatchMode,
    [string]$SshExe = "$env:WINDIR\System32\OpenSSH\ssh.exe",
    [int]$PollSeconds = 3,
    [int]$RestartBackoffSeconds = 8
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $SshExe)) {
    throw "ssh.exe not found at $SshExe"
}

function Test-HttpPort {
    param([int]$Port)

    try {
        $listener = New-Object System.Net.Sockets.TcpClient
        $iar = $listener.BeginConnect('127.0.0.1', $Port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(750)
        if ($ok -and $listener.Connected) {
            $listener.EndConnect($iar) | Out-Null
            $listener.Close()
            return $true
        }
        $listener.Close()
        return $false
    } catch {
        return $false
    }
}

$target = "$RemoteUser@$RemoteHost"
$remoteSpec = "$RemoteBindHost`:$RemoteBindPort`:localhost`:$LocalHttpPort"

$sshArgs = @(
    '-N',
    '-T',
    '-p', "$RemoteSshPort",
    '-o', 'ExitOnForwardFailure=yes',
    '-o', 'ServerAliveInterval=30',
    '-o', 'ServerAliveCountMax=3',
    '-o', 'TCPKeepAlive=yes',
    '-R', $remoteSpec
)

if ($BatchMode) {
    $sshArgs += @('-o', 'BatchMode=yes')
}

if ($IdentityFile -ne '') {
    $sshArgs += @('-i', $IdentityFile)
}

$sshArgs += $target

$bridgeProc = $null
$nextRetryAt = Get-Date

while ($true) {
    $wampUp = Test-HttpPort -Port $LocalHttpPort

    if ($wampUp) {
        if ($null -ne $bridgeProc -and $bridgeProc.HasExited) {
            $exitCode = $bridgeProc.ExitCode
            $bridgeProc = $null
            $nextRetryAt = (Get-Date).AddSeconds($RestartBackoffSeconds)
            Write-Host "Bridge process exited with code $exitCode. Next retry at $nextRetryAt."
        }

        if ($null -eq $bridgeProc -and (Get-Date) -ge $nextRetryAt) {
            $bridgeProc = Start-Process -FilePath $SshExe -ArgumentList $sshArgs -WindowStyle Hidden -PassThru
            Write-Host "WAMP detected on localhost:$LocalHttpPort; started bridge process PID $($bridgeProc.Id)."
        }
    } else {
        if ($null -ne $bridgeProc -and -not $bridgeProc.HasExited) {
            Stop-Process -Id $bridgeProc.Id -Force -ErrorAction SilentlyContinue
            Write-Host "WAMP unavailable on localhost:$LocalHttpPort; stopped bridge process PID $($bridgeProc.Id)."
        }
        $bridgeProc = $null
        $nextRetryAt = Get-Date
    }

    Start-Sleep -Seconds $PollSeconds
}
