# setup.ps1 - Download TerraPlusMinus compile-time dependency
# Usage: .\scripts\setup.ps1

$ErrorActionPreference = "Stop"

# Read version from pom.xml
$pomXml = Get-Content pom.xml -Raw
if ($pomXml -match '<artifactId>terraplusminus</artifactId>\s*<version>([^<]+)</version>') {
    $tpmVersion = $Matches[1]
} else {
    Write-Error "Could not parse TerraPlusMinus version from pom.xml"
    exit 1
}

$jarName = "terraplusminus-$tpmVersion.jar"
$downloadUrl = "https://github.com/BTE-Germany/TerraPlusMinus/releases/download/v$tpmVersion/$jarName"
$destDir = Join-Path $PSScriptRoot "..\libs"
$destFile = Join-Path $destDir $jarName

# Skip if already exists
if (Test-Path $destFile) {
    Write-Host "Already exists: $jarName" -ForegroundColor Green
    exit 0
}

# Create libs/ directory
New-Item -ItemType Directory -Force -Path $destDir | Out-Null

# Download
Write-Host "Downloading $jarName ..." -ForegroundColor Cyan
try {
    Invoke-WebRequest -Uri $downloadUrl -OutFile $destFile -UseBasicParsing
} catch {
    Write-Error "Download failed: $($_.Exception.Message)"
    Write-Host "Please download manually from: $downloadUrl" -ForegroundColor Yellow
    exit 1
}

$sizeMB = [math]::Round((Get-Item $destFile).Length / 1MB, 1)
Write-Host "Downloaded: $jarName ($sizeMB MB)" -ForegroundColor Green
