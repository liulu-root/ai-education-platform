$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkPath = 'D:\soft\jdk25-2'
$mavenPath = 'D:\soft\maven\apache-maven-3.8.1\bin\mvn.cmd'

if (Test-Path -LiteralPath $jdkPath) {
    $env:JAVA_HOME = $jdkPath
    $env:Path = "$jdkPath\bin;$env:Path"
}

Set-Location -LiteralPath $projectRoot
& $mavenPath test
