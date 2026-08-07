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

try {
  New-Item -ItemType Directory -Force $temporary, $expanded, $Destination | Out-Null
  Write-Host "Downloading FFmpeg release essentials..."
  Invoke-WebRequest -Uri $downloadUrl -OutFile $archive
  Expand-Archive -Path $archive -DestinationPath $expanded -Force
  $source = Get-ChildItem -Path $expanded -Filter "ffmpeg.exe" -File -Recurse | Select-Object -First 1
  if (-not $source) { throw "ffmpeg.exe was not found in the downloaded archive" }
  Copy-Item -Path $source.FullName -Destination $binary -Force
  Write-Host "FFmpeg prepared: $binary"
} finally {
  if (Test-Path $temporary) { Remove-Item -Path $temporary -Recurse -Force }
}
