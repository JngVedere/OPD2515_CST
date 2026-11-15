# Offline app-only release tests. No SDK, ADB, network, or real APK required.
$ErrorActionPreference = 'Stop'
$checks = 0
function Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw ('TEST FAILED: ' + $Message) }
    $script:checks++
}
function Save-Json($Value, [string]$Path) {
    [IO.File]::WriteAllText($Path,($Value | ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($true)))
}
$qa = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'qa'))
$fixture = [IO.Path]::GetFullPath((Join-Path $qa ('app-release-test-' + [guid]::NewGuid().ToString('N'))))
function Remove-TestFixture([string]$Path) {
    $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Path).ProviderPath)
    $resolvedQa = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $qa).ProviderPath)
    if (-not $resolved.Equals($fixture, [StringComparison]::OrdinalIgnoreCase) -or
        -not [IO.Path]::GetDirectoryName($resolved).Equals($resolvedQa, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -cnotmatch '^app-release-test-[a-f0-9]{32}$') { throw 'Refusing to delete a path outside this test fixture.' }
    [IO.Directory]::Delete($resolved, $true)
}
try {
$app = Join-Path $fixture 'micts/fast-launch'
[void][IO.Directory]::CreateDirectory($app)
$unrelated = Join-Path $fixture 'cleanup-guard-target'
[void][IO.Directory]::CreateDirectory($unrelated)
$rejectedCleanup = $false
try { Remove-TestFixture $unrelated } catch { $rejectedCleanup = $true }
Check $rejectedCleanup 'Fixture cleanup rejects a directory other than the exact owned GUID path'
Check (Test-Path -LiteralPath $unrelated -PathType Container) 'The cleanup guard leaves the rejected target untouched'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Build-Release.ps1') -Destination (Join-Path $fixture 'Build-Release.ps1')
$apk = Join-Path $app 'MiCTS-Quick.apk'
[IO.File]::WriteAllText($apk,'fixture-apk-v1')
$version = [pscustomobject]@{Version='2.8.0';VersionCode=12}
$versionPath = Join-Path $app 'App-Version.json'
Save-Json $version $versionPath
$release = [pscustomobject]@{Schema=1;Package='dev.padhelper.fastcts';Version=$version.Version;VersionCode=$version.VersionCode;FileName=('Bottom-Search-'+$version.Version+'.apk');SHA256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()}
$metadata = Join-Path $fixture 'App-Release.json'
Save-Json $release $metadata
# A release must work without loading any archived desktop implementation.
[IO.File]::WriteAllText((Join-Path $fixture 'PadHelper.ps1'),'throw "Legacy desktop code must not run"')
$releaseScript = Join-Path $fixture 'Build-Release.ps1'
function Invoke-Release([string]$Script = $releaseScript) {
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Script 2>&1
        [pscustomobject]@{ExitCode=$LASTEXITCODE;Output=($output | Out-String)}
    } finally { $ErrorActionPreference = $previous }
}
$result = Invoke-Release
Check ($result.ExitCode -eq 0) 'Package the app without desktop modules or SDK tools'
$dist = Join-Path $fixture 'dist'
$publishedApk = Join-Path $dist $release.FileName
$publishedChecksum = $publishedApk + '.sha256'
Check (Test-Path -LiteralPath $publishedApk -PathType Leaf) 'Publish the versioned APK'
Check (Test-Path -LiteralPath $publishedChecksum -PathType Leaf) 'Publish a separate SHA-256 file'
Check (@(Get-ChildItem -LiteralPath $dist -Force).Count -eq 2) 'App release contains only APK and checksum, no desktop ZIP or source'
Check ((Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash -eq $release.SHA256) 'Published APK matches pinned metadata'
Check ([IO.File]::ReadAllText($publishedChecksum) -ceq ($release.SHA256+'  '+$release.FileName+"`n")) 'Checksum uses a bare APK name and one LF line ending'
$originalApkHash = (Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash
$originalChecksumHash = (Get-FileHash -LiteralPath $publishedChecksum -Algorithm SHA256).Hash

foreach ($case in @(
    @{Name='wrong hash';Property='SHA256';Value=('0'*64)},
    @{Name='malformed hash';Property='SHA256';Value='not-a-hash'},
    @{Name='wrong version';Property='Version';Value='2.8.1'},
    @{Name='wrong version code';Property='VersionCode';Value=13},
    @{Name='path traversal';Property='FileName';Value='../escaped.apk'},
    @{Name='wrong file name';Property='FileName';Value='Desktop-Helper.zip'},
    @{Name='wrong package';Property='Package';Value='example.untrusted'},
    @{Name='wrong schema';Property='Schema';Value=2}
)) {
    $bad = $release | ConvertTo-Json | ConvertFrom-Json
    $bad.($case.Property) = $case.Value
    Save-Json $bad $metadata
    $result = Invoke-Release
    Check ($result.ExitCode -ne 0) ('Reject '+$case.Name)
    Check ((Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash -eq $originalApkHash) ('Keep prior APK after '+$case.Name)
    Check ((Get-FileHash -LiteralPath $publishedChecksum -Algorithm SHA256).Hash -eq $originalChecksumHash) ('Keep prior checksum after '+$case.Name)
}
Check (-not (Test-Path -LiteralPath (Join-Path $fixture 'escaped.apk'))) 'Malformed file names write nothing outside dist'
Save-Json $release $metadata

# Deterministically simulate a build replacing the source after initial hashing.
$raceDriver = Join-Path $fixture 'Race-Release.ps1'
$raceSource = @'
$ErrorActionPreference = 'Stop'
Import-Module Microsoft.PowerShell.Utility
$global:ReleaseRacePath = Join-Path $PSScriptRoot 'micts/fast-launch/MiCTS-Quick.apk'
$global:ReleaseRaceChanged = $false
function global:Get-FileHash {
    param([string]$LiteralPath, [string]$Algorithm = 'SHA256')
    $result = Microsoft.PowerShell.Utility\Get-FileHash -LiteralPath $LiteralPath -Algorithm $Algorithm
    if (-not $global:ReleaseRaceChanged -and $LiteralPath -eq $global:ReleaseRacePath) {
        $global:ReleaseRaceChanged = $true
        [IO.File]::AppendAllText($LiteralPath,'replacement-after-hashing')
    }
    $result
}
& (Join-Path $PSScriptRoot 'Build-Release.ps1')
'@
[IO.File]::WriteAllText($raceDriver,$raceSource,(New-Object Text.UTF8Encoding($true)))
$result = Invoke-Release $raceDriver
Check ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne $release.SHA256) 'Race test actually replaces source bytes after the initial valid hash'
Check ($result.ExitCode -ne 0 -and $result.Output -match 'Staged app APK does not match pinned release metadata') 'Reject source replacement before publication'
Check ((Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash -eq $originalApkHash) 'Source replacement keeps the prior APK'
Check ((Get-FileHash -LiteralPath $publishedChecksum -Algorithm SHA256).Hash -eq $originalChecksumHash) 'Source replacement keeps the prior checksum'
Check (@(Get-ChildItem -LiteralPath $dist -Force).Count -eq 2) 'Failed staging leaves no temporary or backup files'

# Fail the second replacement after the new APK has been published. Both old
# artifacts must survive the rollback, not just failures before publication.
[IO.File]::WriteAllText($apk,'fixture-apk-v2')
$release.SHA256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Save-Json $release $metadata
$locked = [IO.File]::Open($publishedChecksum,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
try { $result = Invoke-Release } finally { $locked.Dispose() }
Check ($result.ExitCode -ne 0) 'A locked checksum aborts the second publication step'
Check ((Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash -eq $originalApkHash) 'Rollback restores the old APK after partial publication'
Check ((Get-FileHash -LiteralPath $publishedChecksum -Algorithm SHA256).Hash -eq $originalChecksumHash) 'Rollback preserves the old locked checksum'
Check (@(Get-ChildItem -LiteralPath $dist -Force).Count -eq 2) 'Successful rollback cleans temporary and backup files'
$result = Invoke-Release
Check ($result.ExitCode -eq 0) 'Publish both new artifacts after the lock is released'
Check ((Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash -eq $release.SHA256 -and $release.SHA256 -ne $originalApkHash) 'Successful replacement publishes the new APK'
Check ([IO.File]::ReadAllText($publishedChecksum) -ceq ($release.SHA256+'  '+$release.FileName+"`n")) 'Successful replacement publishes the matching checksum with LF'
Check (@(Get-ChildItem -LiteralPath $dist -Force).Count -eq 2) 'Final release still contains only APK and checksum'

} finally {
    if (Test-Path -LiteralPath $fixture) { Remove-TestFixture $fixture }
}
Check (-not (Test-Path -LiteralPath $fixture)) 'The complete fixture is removed after the test run'
Write-Output ('PASS: '+$checks+' app release checks; no SDK, device, or network used.')
