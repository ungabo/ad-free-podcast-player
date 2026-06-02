$ErrorActionPreference = 'Stop'

$desktopRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$repoRoot = Resolve-Path (Join-Path $desktopRoot '..\..')
$outputDir = Join-Path $desktopRoot 'Ad Free Podcast Player-win32-x64'
$distDir = Join-Path $desktopRoot 'dist'
$packagerBin = Join-Path $repoRoot 'node_modules\.bin\electron-packager.cmd'

Set-Location $desktopRoot

function Remove-DirectoryChecked {
  param(
    [Parameter(Mandatory = $true)]
    [string] $Path
  )

  if (-not (Test-Path -LiteralPath $Path)) {
    return
  }

  $resolvedRoot = (Resolve-Path -LiteralPath $desktopRoot).Path
  $resolvedTarget = (Resolve-Path -LiteralPath $Path).Path
  if (-not $resolvedTarget.StartsWith($resolvedRoot)) {
    throw "Refusing to remove path outside desktop workspace: $resolvedTarget"
  }

  try {
    Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
  } catch {
    throw "Could not replace '$Path'. Close any running Ad Free Podcast Player window and try again. $($_.Exception.Message)"
  }
}

Remove-DirectoryChecked -Path $outputDir
Remove-DirectoryChecked -Path $distDir

if (-not (Test-Path -LiteralPath $packagerBin)) {
  throw "electron-packager binary was not found at '$packagerBin'. Run npm install first."
}

& $packagerBin . 'Ad Free Podcast Player' --platform=win32 --arch=x64 --out $distDir --overwrite --asar --ignore=renderer --ignore=dist --ignore='Ad Free Podcast Player-win32-x64'
if ($LASTEXITCODE -ne 0) {
  throw "electron-packager failed with exit code $LASTEXITCODE"
}

$packagedDir = Join-Path $distDir 'Ad Free Podcast Player-win32-x64'
if (-not (Test-Path -LiteralPath $packagedDir)) {
  throw "Packaged app was not created at '$packagedDir'."
}

Move-Item -LiteralPath $packagedDir -Destination $outputDir -ErrorAction Stop
Remove-DirectoryChecked -Path $distDir

Write-Host "Packaged Windows app at $outputDir"
