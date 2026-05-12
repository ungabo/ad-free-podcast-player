param(
    [Parameter(Mandatory = $true)]
    [string]$RemoteHost,

    [Parameter(Mandatory = $true)]
    [string]$RemoteUser,

    [int]$RemoteSshPort = 22,
    [int]$RemoteBindPort = 8081,
    [string]$RemoteBindHost = '127.0.0.1',
    [int]$LocalHttpPort = 80,
    [string]$WampManagerPath = 'D:\wamp64\wampmanager.exe',
    [string]$SshExe = "$env:WINDIR\System32\OpenSSH\ssh.exe",
    [string]$IdentityFile = '',
    [switch]$BatchMode,
    [switch]$SkipWampStart,
    [switch]$Loop
)

$ErrorActionPreference = 'Stop'

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

function Ensure-WampRunning {
    param([string]$ExePath, [int]$Port)

    if (Test-HttpPort -Port $Port) {
        Write-Host "WAMP already serving localhost:$Port"
        return
    }

    if (-not (Test-Path $ExePath)) {
        throw "WAMP manager not found at $ExePath"
    }

    Write-Host "Starting WAMP manager: $ExePath"
    Start-Process -FilePath $ExePath | Out-Null

    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 750
        if (Test-HttpPort -Port $Port) {
            Write-Host "WAMP is now serving localhost:$Port"
            return
        }
    }

    throw "WAMP did not start listening on localhost:$Port within 45 seconds."
}

function Start-Bridge {
    param(
        [string]$RemoteHostname,
        [string]$User,
        [int]$SshPort,
        [string]$BindHost,
        [int]$BindPort,
        [int]$HttpPort,
        [string]$SshPath,
        [string]$KeyPath
    )

    if (-not (Test-Path $SshPath)) {
        throw "ssh.exe not found at $SshPath"
    }

    $target = "$User@$RemoteHostname"
    $remoteSpec = "$BindHost`:$BindPort`:localhost`:$HttpPort"

    $args = @(
        '-N',
        '-T',
        '-p', "$SshPort",
        '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=30',
        '-o', 'ServerAliveCountMax=3',
        '-o', 'TCPKeepAlive=yes',
        '-R', $remoteSpec
    )

    if ($BatchMode) {
        $args += @('-o', 'BatchMode=yes')
    }

    if ($KeyPath -ne '') {
        $args += @('-i', $KeyPath)
    }

    $args += $target

    Write-Host "Starting reverse tunnel: $target  $remoteSpec"
    & $SshPath @args
    return $LASTEXITCODE
}

if (-not $SkipWampStart) {
    Ensure-WampRunning -ExePath $WampManagerPath -Port $LocalHttpPort
}

if ($Loop) {
    while ($true) {
        $code = Start-Bridge -RemoteHostname $RemoteHost -User $RemoteUser -SshPort $RemoteSshPort -BindHost $RemoteBindHost -BindPort $RemoteBindPort -HttpPort $LocalHttpPort -SshPath $SshExe -KeyPath $IdentityFile -BatchMode:$BatchMode
        Write-Host "Tunnel exited with code $code. Reconnecting in 3 seconds..."
        Start-Sleep -Seconds 3
    }
}

    $exitCode = Start-Bridge -RemoteHostname $RemoteHost -User $RemoteUser -SshPort $RemoteSshPort -BindHost $RemoteBindHost -BindPort $RemoteBindPort -HttpPort $LocalHttpPort -SshPath $SshExe -KeyPath $IdentityFile -BatchMode:$BatchMode
exit $exitCode
