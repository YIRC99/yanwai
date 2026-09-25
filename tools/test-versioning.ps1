$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path $repo ('output/version-check-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'tools') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'check-version.ps1') -Destination (Join-Path $fixture 'tools')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'bump-version.ps1') -Destination (Join-Path $fixture 'tools')
function Write-FixtureVersion([string]$name, [int]$code) {
    [IO.File]::WriteAllLines((Join-Path $fixture 'version.properties'), @("versionName=$name", "versionCode=$code"))
}
function Assert-Guard([bool]$pass, [string]$scenario) {
    $details = & powershell -NoProfile -File (Join-Path $fixture 'tools/check-version.ps1') -Staged
    if (($LASTEXITCODE -eq 0) -ne $pass) { throw "$scenario : $details" }
    Write-Output "PASS $scenario"
}
& git -C $fixture init --quiet --initial-branch=main
& git -C $fixture config user.name 'Version test'
& git -C $fixture config user.email 'version-test@example.invalid'
Write-FixtureVersion '1.2.0' 16
& git -C $fixture add .
& git -C $fixture -c core.hooksPath=.test-no-hooks commit --quiet -m baseline
Assert-Guard $false 'unchanged staged version is rejected'
& powershell -NoProfile -File (Join-Path $fixture 'tools/bump-version.ps1') | Out-Null
Assert-Guard $false 'unstaged bump cannot bypass the guard'
& git -C $fixture add version.properties
Assert-Guard $true 'patch increments both version values'
Write-FixtureVersion '1.3.0' 16
& git -C $fixture add version.properties
Assert-Guard $false 'unchanged versionCode is rejected'
Write-FixtureVersion '1.2.0' 17
& git -C $fixture add version.properties
Assert-Guard $false 'unchanged versionName is rejected'
Write-FixtureVersion '1.3.0' 17
& git -C $fixture add version.properties
Assert-Guard $true 'minor update is accepted'
Write-Output 'All 6 version guard checks passed. Fixture remains in ignored output/.'
