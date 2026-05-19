param(
    [string]$RemoteHost = '74.208.203.194',
    [string]$RemoteUser = 'agitated-engelbart_9pw3g4pzt1v',
    [int]$RemoteSshPort = 22,
    [int]$RemoteBindPort = 8081,
    [string]$RemoteBindHost = '127.0.0.1',
    [int]$LocalHttpPort = 80,
    [string]$WampManagerPath = 'D:\wamp64\wampmanager.exe',
    [string]$IdentityFile = "$env:USERPROFILE\.ssh\adfree_hosting_ed25519",
    [string]$TaskName = 'AdFree Local Reverse Tunnel',
    [string]$SshExe = "$env:WINDIR\System32\OpenSSH\ssh.exe"
)

$ErrorActionPreference = 'Stop'

function Write-Step {
    param([string]$Message)
    Write-Host "[AdFree] $Message"
}

function Test-HttpPort {
    param([int]$Port)

    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(750)
        if ($ok -and $client.Connected) {
            $client.EndConnect($iar) | Out-Null
            $client.Close()
            return $true
        }
        $client.Close()
        return $false
    } catch {
        return $false
    }
}

function Start-WampIfNeeded {
    if (Test-HttpPort -Port $LocalHttpPort) {
        Write-Step "WAMP is already serving localhost:$LocalHttpPort."
        return
    }

    if (-not (Test-Path $WampManagerPath)) {
        throw "WAMP manager was not found at $WampManagerPath"
    }

    Write-Step "Starting WAMP..."
    Start-Process -FilePath $WampManagerPath | Out-Null

    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 750
        if (Test-HttpPort -Port $LocalHttpPort) {
            Write-Step "WAMP is serving localhost:$LocalHttpPort."
            return
        }
    }

    throw "WAMP did not start listening on localhost:$LocalHttpPort within 60 seconds."
}

function Test-LocalProcessor {
    $url = "http://127.0.0.1:$LocalHttpPort/adfree-api/api/local/health"
    $health = Invoke-RestMethod -Uri $url -TimeoutSec 15
    if (-not $health.ok) {
        throw "Local bridge health check failed."
    }
    if (-not $health.parakeet_available) {
        throw "Parakeet runtime is not available."
    }
    if (-not $health.openai_key_configured) {
        throw "OPENAI_API_KEY is not configured on the Windows processor."
    }
    Write-Step "Local processor is ready: Parakeet + OpenAI."
}

function Test-RemoteTunnel {
    if (-not (Test-Path $SshExe)) {
        throw "ssh.exe was not found at $SshExe"
    }
    if (-not (Test-Path $IdentityFile)) {
        throw "SSH identity file was not found at $IdentityFile"
    }

    $target = "$RemoteUser@$RemoteHost"
    $remoteHealthUrl = "http://127.0.0.1:$RemoteBindPort/adfree-api/api/local/health"
    $remoteCommand = "curl -fsS --max-time 10 $remoteHealthUrl >/dev/null && echo ok"
    $args = @(
        '-i', $IdentityFile,
        '-p', "$RemoteSshPort",
        '-o', 'BatchMode=yes',
        '-o', 'ConnectTimeout=10',
        $target,
        $remoteCommand
    )

    try {
        $output = & $SshExe @args 2>$null
        return $LASTEXITCODE -eq 0 -and (($output -join "`n").Trim() -eq 'ok')
    } catch {
        return $false
    }
}

function Start-Tunnel {
    if (Test-RemoteTunnel) {
        Write-Step "Tunnel is already online."
        return
    }

    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($task) {
        if ($task.State -eq 'Running') {
            Write-Step "Restarting existing tunnel task..."
            Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
        } else {
            Write-Step "Starting existing tunnel task..."
        }
        Start-ScheduledTask -TaskName $TaskName
        return
    }

    $bridgeScript = Join-Path $PSScriptRoot 'run-wamp-triggered-bridge.ps1'
    if (-not (Test-Path $bridgeScript)) {
        throw "Bridge script was not found at $bridgeScript"
    }

    Write-Step "Starting tunnel controller directly..."
    $argList = @(
        '-NoProfile',
        '-WindowStyle', 'Hidden',
        '-ExecutionPolicy', 'Bypass',
        '-File', "`"$bridgeScript`"",
        '-RemoteHost', $RemoteHost,
        '-RemoteUser', $RemoteUser,
        '-RemoteSshPort', "$RemoteSshPort",
        '-RemoteBindPort', "$RemoteBindPort",
        '-RemoteBindHost', $RemoteBindHost,
        '-LocalHttpPort', "$LocalHttpPort",
        '-IdentityFile', "`"$IdentityFile`"",
        '-BatchMode'
    )
    Start-Process -FilePath 'powershell.exe' -ArgumentList ($argList -join ' ') -WindowStyle Hidden | Out-Null
}

function Wait-ForTunnel {
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        if (Test-RemoteTunnel) {
            Write-Step "Tunnel is online. Remote server can reach this Windows processor."
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Tunnel did not become reachable from the VPS within 45 seconds."
}

try {
    Write-Step "Starting Windows processor bridge..."
    Start-WampIfNeeded
    Test-LocalProcessor
    Start-Tunnel
    Wait-ForTunnel
    Write-Step "Ready. You can close this window."
    Start-Sleep -Seconds 5
} catch {
    Write-Host ""
    Write-Host "[AdFree] Startup failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Press Enter to close this window."
    Read-Host | Out-Null
    exit 1
}
