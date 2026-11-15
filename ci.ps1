param(
    [string] $Gradle,
    [string] $DependencyDir = $env:SDP_SDK_DEPS,
    [string] $JavaHome = $env:JAVA_HOME,
    [switch] $PublishLocal
)

$ErrorActionPreference = 'Stop'
if (-not $DependencyDir) { throw 'Set SDP_SDK_DEPS or pass -DependencyDir.' }
$resolvedDeps = (Resolve-Path -LiteralPath $DependencyDir).Path
if (-not $Gradle) { $Gradle = Join-Path $PSScriptRoot 'gradlew.bat' }
if ($JavaHome) { $JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path }

$previousGradleHome = $env:GRADLE_USER_HOME
$previousJavaHome = $env:JAVA_HOME
Push-Location $PSScriptRoot
try {
    if ($Gradle -ne (Join-Path $PSScriptRoot 'gradlew.bat')) {
        $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot 'out/gradle-home'
    }
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    $tasks = @('build')
    if ($PublishLocal) { $tasks += 'publishSdkPublicationToSdkArtifactsRepository' }
    $options = @('--offline', '--project-cache-dir', 'out/gradle-project', "-PsdpDependencyDir=$resolvedDeps")
    if ($JavaHome) { $options += "-Dorg.gradle.java.home=$JavaHome" }
    & $Gradle @options @tasks
    exit $LASTEXITCODE
}
finally {
    Pop-Location
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:JAVA_HOME = $previousJavaHome
}
