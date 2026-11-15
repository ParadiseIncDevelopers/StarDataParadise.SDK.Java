param([string]$Gradle = 'gradle')

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot 'out/gradle-home'
    & $Gradle --offline --project-cache-dir out/gradle-project build
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
