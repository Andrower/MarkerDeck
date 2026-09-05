param(
  [string]$Version = "1.7.0",
  [string]$ArtifactDirectory
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
if (-not $ArtifactDirectory) {
  $ArtifactDirectory = Join-Path $root "artifacts"
}
$ArtifactDirectory = [System.IO.Path]::GetFullPath($ArtifactDirectory)
$packageName = "markerdeck-windows-x64-v$Version"
$stagingRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("markerdeck-windows-" + [guid]::NewGuid().ToString("N"))
$packageDirectory = Join-Path $stagingRoot $packageName
$archive = Join-Path $ArtifactDirectory "$packageName.zip"

try {
  New-Item -ItemType Directory -Force (Join-Path $packageDirectory "app"), (Join-Path $packageDirectory "runtime\node-windows"), $ArtifactDirectory | Out-Null
  Copy-Item -Path (Join-Path $root "src\*") -Destination (Join-Path $packageDirectory "app") -Recurse -Force
  $bonjourPackage = Join-Path $root "node_modules\bonjour-service\package.json"
  if (-not (Test-Path $bonjourPackage -PathType Leaf)) {
    throw "bonjour-service is missing. Run npm ci --omit=dev before packaging."
  }
  $portableNodeModules = Join-Path $packageDirectory "app\node_modules"
  New-Item -ItemType Directory -Force $portableNodeModules | Out-Null
  Copy-Item -Path (Join-Path $root "node_modules\*") -Destination $portableNodeModules -Recurse -Force
  Copy-Item -Path (Join-Path $root "platform\windows\start-markerdeck-server.bat") -Destination $packageDirectory -Force
  Copy-Item -Path (Join-Path $root "docs\windows.md") -Destination (Join-Path $packageDirectory "README.md") -Force

  $nodeSource = $env:NODE_WINDOWS_BIN
  if (-not $nodeSource) {
    $bundledNode = Join-Path $root "runtime\windows\node-windows\node.exe"
    if (Test-Path $bundledNode) {
      $nodeSource = $bundledNode
    } else {
      $nodeSource = (Get-Command node -ErrorAction Stop).Source
    }
  }
  Copy-Item -Path $nodeSource -Destination (Join-Path $packageDirectory "runtime\node-windows\node.exe") -Force

  $ffmpegDestination = Join-Path $packageDirectory "runtime\ffmpeg-windows"
  $preparedFfmpeg = Join-Path $root "runtime\windows\ffmpeg-windows"
  if (Test-Path (Join-Path $preparedFfmpeg "ffmpeg.exe")) {
    Copy-Item -Path $preparedFfmpeg -Destination (Split-Path $ffmpegDestination) -Recurse -Force
  } else {
    & (Join-Path $root "platform\windows\prepare-ffmpeg.ps1") -Destination $ffmpegDestination
  }

  if (Test-Path $archive) {
    Remove-Item -Path $archive -Force
  }
  Compress-Archive -Path $packageDirectory -DestinationPath $archive -CompressionLevel Optimal
  Write-Host $archive
} finally {
  if (Test-Path $stagingRoot) {
    Remove-Item -Path $stagingRoot -Recurse -Force
  }
}
