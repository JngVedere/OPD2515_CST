param([switch]$BuildApp, [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk", [switch]$Beta)
$ErrorActionPreference = 'Stop'
if ($BuildApp) { & (Join-Path $PSScriptRoot 'micts/fast-launch/Build.ps1') -Sdk $Sdk -Beta:$Beta }

# -Beta: metadata goes to App-Release-Beta.json (publish the GitHub release as a pre-release); stable stays untouched.
$metadataName = if ($Beta) { 'App-Release-Beta.json' } else { 'App-Release.json' }
$release = Get-Content -LiteralPath (Join-Path $PSScriptRoot $metadataName) -Raw -Encoding UTF8 | ConvertFrom-Json
$version = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'micts/fast-launch/App-Version.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($release.Schema -ne 1 -or $release.Package -cne 'dev.padhelper.fastcts') { throw 'Invalid app release schema or package.' }
if ($release.Version -cnotmatch '^\d+\.\d+\.\d+(?:[-.A-Za-z0-9]+)?$' -or [long]$release.VersionCode -lt 1) { throw 'Invalid app release version.' }
if ($release.Version -cne $version.Version -or [long]$release.VersionCode -ne [long]$version.VersionCode) { throw 'App release metadata is stale. Rebuild the Android app first.' }
$expectedName = 'Circle-to-Search-Trigger-' + $release.Version + '.apk'
if ($release.FileName -cne $expectedName -or $release.FileName -cnotmatch '^Circle-to-Search-Trigger-[0-9A-Za-z.-]+\.apk$') { throw 'Invalid app release file name.' }
if ($release.SHA256 -cnotmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid app release SHA-256.' }

$apk = Join-Path $PSScriptRoot 'micts/fast-launch/MiCTS-Quick.apk'
if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne $release.SHA256) { throw 'Signed app APK does not match release metadata.' }
$dist = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'dist'))
[void][IO.Directory]::CreateDirectory($dist)
$token = [guid]::NewGuid().ToString('N')
$artifacts = @(foreach ($name in @($release.FileName, ($release.FileName + '.sha256'))) {
    $target = [IO.Path]::GetFullPath((Join-Path $dist $name))
    if (-not $target.StartsWith($dist + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Release path leaves the distribution folder.' }
    [pscustomobject]@{ Target=$target; Temporary=($target+'.'+$token+'.tmp'); Backup=($target+'.'+$token+'.previous'); Existed=(Test-Path -LiteralPath $target -PathType Leaf) }
})
$published = @()
$cleanupBackups = $false
try {
    Copy-Item -LiteralPath $apk -Destination $artifacts[0].Temporary
    if ((Get-FileHash -LiteralPath $artifacts[0].Temporary -Algorithm SHA256).Hash -ne $release.SHA256) { throw 'Staged app APK does not match pinned release metadata.' }
    $checksum = $release.SHA256.ToLowerInvariant() + '  ' + $release.FileName + "`n"
    [IO.File]::WriteAllText($artifacts[1].Temporary, $checksum, [Text.Encoding]::ASCII)
    foreach ($artifact in $artifacts) {
        if ($artifact.Existed) { [IO.File]::Replace($artifact.Temporary, $artifact.Target, $artifact.Backup) }
        else { [IO.File]::Move($artifact.Temporary, $artifact.Target) }
        $published += $artifact
    }
    $cleanupBackups = $true
} catch {
    $failure = $_
    [array]::Reverse($published)
    try {
        foreach ($artifact in $published) {
            if ($artifact.Existed) { [IO.File]::Replace($artifact.Backup, $artifact.Target, [NullString]::Value) }
            else { Remove-Item -LiteralPath $artifact.Target }
        }
    } catch {
        throw ('Release publication failed: ' + $failure.Exception.Message + ' Rollback also failed: ' + $_.Exception.Message + ' Recovery backups remain in: ' + $dist)
    }
    $cleanupBackups = $true
    throw $failure
} finally {
    foreach ($artifact in $artifacts) {
        if (Test-Path -LiteralPath $artifact.Temporary) { Remove-Item -LiteralPath $artifact.Temporary }
        if ($cleanupBackups -and (Test-Path -LiteralPath $artifact.Backup)) { Remove-Item -LiteralPath $artifact.Backup }
    }
}
[pscustomobject]@{ Path=$artifacts[0].Target; SHA256=$release.SHA256.ToLowerInvariant(); Checksum=$artifacts[1].Target }
