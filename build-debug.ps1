$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$jdk = Get-ChildItem -Directory (Join-Path $repoRoot ".tools\jdk") | Select-Object -First 1

if (-not $jdk) {
    throw "Local JDK was not found under $repoRoot\.tools\jdk"
}

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $repoRoot ".tools\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$($env:JAVA_HOME)\bin;$repoRoot\.tools\gradle-8.10.2\bin;$($env:ANDROID_HOME)\platform-tools;$env:Path"

Push-Location $PSScriptRoot
try {
    gradle assembleDebug
} finally {
    Pop-Location
}
