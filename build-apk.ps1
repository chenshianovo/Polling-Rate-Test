param(
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
)

$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskJava = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.build-tools\jdk') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
    Select-Object -First 1
if (-not $taskJava) { throw 'Local Java 17 toolchain is missing from .build-tools\jdk.' }

# These settings affect only this PowerShell process and its child processes.
$env:JAVA_HOME = $taskJava.FullName
$env:ANDROID_HOME = Join-Path $taskRoot '.build-tools\sdk'
$env:ANDROID_USER_HOME = Join-Path $taskRoot '.build-tools\android-home'
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.build-tools\gradle-home'
$env:TEMP = Join-Path $taskRoot '.build-tools\temp'
$env:TMP = $env:TEMP
Push-Location $taskRoot
try {
    & (Join-Path $taskRoot 'gradlew.bat') --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}
