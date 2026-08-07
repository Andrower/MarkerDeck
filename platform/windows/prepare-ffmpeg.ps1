param(
  [string]$Destination
)

$ErrorActionPreference = "Stop"

if (-not $Destination) {
  $Destination = Join-Path $PSScriptRoot "..\..\runtime\windows\ffmpeg-windows"
}
$Destination = [System.IO.Path]::GetFullPath($Destination)
$binary = Join-Path $Destination "ffmpeg.exe"
if (Test-Path $binary) {
  Write-Host "FFmpeg already prepared: $binary"
  exit 0
}

$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("chroma-ffmpeg-" + [guid]::NewGuid().ToString("N"))
$archive = Join-Path $temporary "ffmpeg.zip"
$expanded = Join-Path $temporary "expanded"
$downloadUrl = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"

function Get-Download([string]$Uri, [string]$OutputFile) {
  & curl.exe --fail --location --retry 5 --retry-all-errors --retry-delay 10 --connect-timeout 30 --output $OutputFile $Uri
  return $LASTEXITCODE -eq 0 -and (Test-Path $OutputFile) -and (Get-Item $OutputFile).Length -gt 1MB
}

try {
  New-Item -ItemType Directory -Force $temporary, $expanded, $Destination | Out-Null
  Write-Host "Downloading FFmpeg release essentials..."
  $downloaded = Get-Download $downloadUrl $archive
  if (-not $downloaded) {
    Write-Host "Primary FFmpeg download is unavailable. Trying the publisher's GitHub Release..."
    if (Test-Path $archive) { Remove-Item $archive -Force }
    $headers = @{ "User-Agent" = "chroma-cross-platform" }
    if ($env:GITHUB_TOKEN) { $headers["Authorization"] = "Bearer $env:GITHUB_TOKEN" }
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/GyanD/codexffmpeg/releases/latest" -Headers $headers
    $asset = $release.assets | Where-Object { $_.name -like "*-essentials_build.zip" } | Select-Object -First 1
    if (-not $asset) { throw "FFmpeg essentials ZIP was not found in the publisher's latest GitHub Release" }
    $downloaded = Get-Download $asset.browser_download_url $archive
  }
  if (-not $downloaded) { throw "Unable to download FFmpeg after retrying both sources" }
  Expand-Archive -Path $archive -DestinationPath $expanded -Force
  $source = Get-ChildItem -Path $expanded -Filter "ffmpeg.exe" -File -Recurse | Select-Object -First 1
  if (-not $source) { throw "ffmpeg.exe was not found in the downloaded archive" }
  Copy-Item -Path $source.FullName -Destination $binary -Force
  Write-Host "FFmpeg prepared: $binary"
} finally {
  if (Test-Path $temporary) { Remove-Item -Path $temporary -Recurse -Force }
}
