# Copies the user's local Jev credential into an ignored build-time file.
param([string]$SourceEnv = "D:\code_file\jev_demo\.env")
$ErrorActionPreference = "Stop"
$values = @{}
foreach ($line in [IO.File]::ReadAllLines($SourceEnv)) {
    if ($line -match '^\s*([A-Z_0-9]+)\s*=\s*(.*)$') {
        $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}
if (-not $values['TYPESAFE_API_KEY']) { throw 'TYPESAFE_API_KEY is missing in source .env' }
function Escape-Property([string]$value) { $value.Replace('\', '\\').Replace(':', '\:').Replace('=', '\=') }
$model = $values['TYPESAFE_MODEL']
if (-not $model) { $model = 'jev-1.13.0' }
$dest = Join-Path (Split-Path $PSScriptRoot -Parent) 'jev.local.properties'
$lines = @('endpoint=https\://api.typesafe.ai/v1/systemone', ('model=' + (Escape-Property $model)), ('apiKey=' + (Escape-Property $values['TYPESAFE_API_KEY'])))
[IO.File]::WriteAllLines($dest, $lines, [Text.UTF8Encoding]::new($false))
Write-Output 'Jev configuration imported; credential hidden and excluded from Git.'
