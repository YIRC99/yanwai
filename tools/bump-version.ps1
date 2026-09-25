param([ValidateSet('patch', 'minor', 'major')][string]$Part = 'patch')
$ErrorActionPreference = 'Stop'
$versionFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'version.properties'
$values = ConvertFrom-StringData (Get-Content -Raw -LiteralPath $versionFile)
$current = [version]$values.versionName
$next = switch ($Part) {
    'major' { '{0}.0.0' -f ($current.Major + 1) }
    'minor' { '{0}.{1}.0' -f $current.Major, ($current.Minor + 1) }
    'patch' { '{0}.{1}.{2}' -f $current.Major, $current.Minor, ($current.Build + 1) }
}
$code = [int]$values.versionCode + 1
[IO.File]::WriteAllLines($versionFile, @("versionName=$next", "versionCode=$code"), [Text.UTF8Encoding]::new($false))
Write-Output "Version updated: $next ($code). Stage version.properties with the change."
