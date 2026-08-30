$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkPath = 'D:\soft\jdk25-2'
$mavenPath = 'D:\soft\maven\apache-maven-3.8.1\bin\mvn.cmd'

if (Test-Path -LiteralPath $jdkPath) {
    $env:JAVA_HOME = $jdkPath
    $env:Path = "$jdkPath\bin;$env:Path"
}

if (-not (Test-Path -LiteralPath $mavenPath)) {
    $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mavenCommand) {
        throw 'Maven 未找到，请先配置 mvn 或修改 scripts/run-local.ps1 中的 Maven 路径。'
    }
    $mavenPath = $mavenCommand.Source
}

Set-Location -LiteralPath $projectRoot
& $mavenPath spring-boot:run
