<#
.SYNOPSIS
Build the jar with the four bundled libraries, smoke-test it, and upload it to Maven Central.

.DESCRIPTION
Needs Maven 3.9+, JDK 11+ (for the smoke test), and for a real release gpg with the signing key
plus a "central" server in ~/.m2/settings.xml holding a Central Portal user token.
-DryRun builds and smoke-tests without signing or uploading.

.EXAMPLE
./scripts/release.ps1 -DryRun
./scripts/release.ps1
./scripts/release.ps1 -AutoPublish
#>
param(
    [switch]$DryRun,
    [switch]$SkipFetch,
    [switch]$AutoPublish,
    [string]$Maven = 'mvn'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

function Invoke-Checked {
    # Native tools write progress to stderr; only the exit code decides failure.
    $ErrorActionPreference = 'Continue'
    & $args[0] $args[1..($args.Count - 1)] 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "$($args -join ' ') failed with exit code $LASTEXITCODE" }
}

if (-not $SkipFetch) { & "$PSScriptRoot/fetch-natives.ps1" -Destination natives }

$sign = if ($DryRun) { '-Dgpg.skip' } else { '-Dgpg.skip=false' }
Invoke-Checked $Maven -B -ntp -Prelease $sign clean verify
Invoke-Checked $Maven -B -ntp -q dependency:copy-dependencies -DincludeScope=runtime -DexcludeArtifactIds=json -DoutputDirectory=target/deps

$jar = Get-ChildItem target -Filter 'edifabric-x12-native-*.jar' |
    Where-Object Name -notmatch '-(sources|javadoc)\.jar$' | Select-Object -First 1
$smoke = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
New-Item -ItemType Directory $smoke | Out-Null
try {
    Copy-Item $jar.FullName, (Get-ChildItem target/deps -Filter *.jar).FullName, scripts/SmokeTest.java $smoke
    Push-Location $smoke
    try {
        $classpath = (Get-ChildItem -Filter *.jar).Name -join [IO.Path]::PathSeparator
        Invoke-Checked java -cp $classpath SmokeTest.java
    }
    finally { Pop-Location }
}
finally {
    Remove-Item $smoke -Recurse -Force -ErrorAction SilentlyContinue
}

if ($DryRun) {
    Write-Host "Dry run: built $($jar.Name), nothing signed or uploaded."
    return
}
Invoke-Checked $Maven -B -ntp -Prelease "-Dcentral.autoPublish=$($AutoPublish.IsPresent.ToString().ToLower())" deploy
