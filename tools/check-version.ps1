param([switch]$Staged)
$ErrorActionPreference = 'Stop'
try {
    $repo = Split-Path $PSScriptRoot -Parent
    function Read-GitFile([string]$spec) {
        # Missing files in HEAD/index are expected during initial migration.
        $ErrorActionPreference = 'Continue'
        $content = (& git -C $repo show $spec 2>$null) -join "`n"
        return @{ Found = ($LASTEXITCODE -eq 0); Content = $content }
    }
    function Read-Version([string]$content) {
        $values = ConvertFrom-StringData $content
        if ($values.versionName -notmatch '^\d+\.\d+\.\d+$' -or $values.versionCode -notmatch '^[1-9]\d*$') {
            throw 'Use a three-part versionName and a positive integer versionCode.'
        }
        return @{ Name = [version]$values.versionName; Code = [int]$values.versionCode }
    }
    if ($Staged) {
        $stagedFile = Read-GitFile ':version.properties'
        if (!$stagedFile.Found) { throw 'Stage version.properties before committing.' }
        $raw = $stagedFile.Content
    } else {
        $raw = Get-Content -Raw -LiteralPath (Join-Path $repo 'version.properties')
    }
    # During the initial migration, HEAD still stores version values in Gradle.
    $next = Read-Version $raw
    $priorFile = Read-GitFile 'HEAD:version.properties'
    if ($priorFile.Found) {
        $prior = Read-Version $priorFile.Content
    } else {
        $priorGradle = Read-GitFile 'HEAD:app/build.gradle.kts'
        $gradle = $priorGradle.Content
        if ($priorGradle.Found -and $gradle -match 'versionName\s*=\s*"([0-9.]+)"') {
            $oldName = $Matches[1]
            if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { throw 'Cannot read previous versionCode.' }
            $prior = Read-Version "versionName=$oldName`nversionCode=$($Matches[1])"
        } else { $prior = $null }
    }
    if ($null -ne $prior -and ($next.Name -le $prior.Name -or $next.Code -le $prior.Code)) {
        throw 'Every commit needs a newer versionName and versionCode. Run tools/bump-version.ps1, then stage version.properties.'
    }
    Write-Output "Version check passed: $($next.Name) ($($next.Code))."
} catch {
    Write-Output "Version check failed: $($_.Exception.Message)"
    exit 1
}
