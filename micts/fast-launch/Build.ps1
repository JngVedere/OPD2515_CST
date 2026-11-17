param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk", [switch]$Beta)
$ErrorActionPreference = 'Stop'
$version = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'App-Version.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($version.Version -notmatch '^\d+\.\d+\.\d+(?:[-.A-Za-z0-9]+)?$' -or [int]$version.VersionCode -lt 1) { throw 'Invalid Android app version' }
$tools = Join-Path $Sdk 'build-tools/android-37.0'
$androidJar = Join-Path $Sdk 'platforms/android-37.0/android.jar'
$build = Join-Path $PSScriptRoot 'build'
foreach ($folder in @('classes','dex','keys')) { [void][IO.Directory]::CreateDirectory((Join-Path $build $folder)) }
# Remove obsolete classes from previous versions without touching the signing key.
Get-ChildItem -LiteralPath (Join-Path $build 'classes') -Filter *.class -File -Recurse | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
function Run([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe exited with $LASTEXITCODE" }
}
# Keep the single runtime dependency reproducible; it adds no Android permissions.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dependencyDir = Join-Path $build 'deps'
[void][IO.Directory]::CreateDirectory($dependencyDir)
$dependency = Join-Path $dependencyDir 'hiddenapibypass-6.1.aar'
$hiddenApiJar = Join-Path $dependencyDir 'hiddenapibypass-6.1.jar'
$dependencyHash = 'e3161dd21c97a4540b1698a33f7062aeaa1450008e1e2176070e5380f7a6324c'
$jarHash = '6f50c4d202acb8152901716df3a1f94b2181e33aa0d14c9bdb2579cbc21c0832'
if (-not (Test-Path -LiteralPath $dependency)) {
    $download = $dependency + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 60 -Uri 'https://repo.maven.apache.org/maven2/org/lsposed/hiddenapibypass/hiddenapibypass/6.1/hiddenapibypass-6.1.aar' -OutFile $download
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -ne $dependencyHash) { throw 'HiddenApiBypass download hash mismatch' }
        [IO.File]::Move($download, $dependency)
    } finally {
        if (Test-Path -LiteralPath $download) { Remove-Item -LiteralPath $download }
    }
}
if ((Get-FileHash -LiteralPath $dependency -Algorithm SHA256).Hash.ToLowerInvariant() -ne $dependencyHash) { throw 'Cached HiddenApiBypass hash mismatch' }
$archive = [IO.Compression.ZipFile]::OpenRead($dependency)
try {
    $entry = $archive.GetEntry('classes.jar')
    if ($null -eq $entry) { throw 'HiddenApiBypass classes.jar missing' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $hiddenApiJar, $true)
} finally { $archive.Dispose() }
if ((Get-FileHash -LiteralPath $hiddenApiJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $jarHash) { throw 'HiddenApiBypass classes hash mismatch' }
Run (Join-Path $tools 'aapt2.exe') @('compile','--dir',"$PSScriptRoot/res",'-o',"$build/resources.zip")
Run (Join-Path $tools 'aapt2.exe') @('link','-I',$androidJar,'--manifest',"$PSScriptRoot/AndroidManifest.xml",'--min-sdk-version','30','--target-sdk-version','36','--version-code',[string]$version.VersionCode,'--version-name',[string]$version.Version,'-o',"$build/unsigned.apk","$build/resources.zip")
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter *.java -File | Select-Object -ExpandProperty FullName)
Run 'javac.exe' (@('--release','17','-encoding','UTF-8','-classpath',($androidJar + ';' + $hiddenApiJar),'-d',"$build/classes") + $sources)
$classes = @(Get-ChildItem -LiteralPath "$build/classes" -Filter *.class -File -Recurse | Select-Object -ExpandProperty FullName)
Run 'java.exe' (@('-cp',"$tools/lib/d8.jar",'com.android.tools.r8.D8','--lib',$androidJar,'--min-api','30','--output',"$build/dex",$hiddenApiJar) + $classes)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$build/unsigned.apk",'Update')
try { [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$build/dex/classes.dex",'classes.dex') } finally { $zip.Dispose() }
Run (Join-Path $tools 'zipalign.exe') @('-f','4',"$build/unsigned.apk","$build/aligned.apk")
$key = Join-Path $build 'keys/fast-cts.jks'
if (-not (Test-Path -LiteralPath $key)) {
    Run 'keytool.exe' @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','fast-cts','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Personal Fast MiCTS Launcher')
}
$candidate = Join-Path $build 'verified-candidate.apk'
Run 'java.exe' @('-jar',"$tools/lib/apksigner.jar",'sign','--ks',$key,'--ks-key-alias','fast-cts','--ks-pass','pass:android','--out',$candidate,"$build/aligned.apk")
Run 'java.exe' @('-jar',"$tools/lib/apksigner.jar",'verify','--verbose',$candidate)
Run (Join-Path $tools 'aapt.exe') @('dump','permissions',$candidate)
$metadata = & (Join-Path $tools 'aapt.exe') dump badging $candidate
if ($LASTEXITCODE -ne 0) { throw 'APK metadata inspection failed' }
if (-not ($metadata -match ("^package: name='dev\.padhelper\.fastcts' versionCode='" + [regex]::Escape([string]$version.VersionCode) + "' versionName='" + [regex]::Escape([string]$version.Version) + "'"))) {
    throw 'Signed APK package/version does not match App-Version.json'
}
if ($metadata -match '^launchable-activity:') { throw 'The app must have no launcher icon.' }
# READ_LOGS is granted once over ADB; INTERNET/REQUEST_INSTALL_PACKAGES serve only the manual update check (AppUpdate).
# No foreground-service or overlay permission is allowed.
$allowed = @('android.permission.RECEIVE_BOOT_COMPLETED','android.permission.WRITE_SECURE_SETTINGS','android.permission.READ_LOGS',
    'android.permission.INTERNET','android.permission.REQUEST_INSTALL_PACKAGES')
foreach ($line in $metadata) {
    if ($line -match "^uses-permission(?:-sdk-\d+)?: name='([^']+)'") {
        if ($Matches[1] -notin $allowed) { throw "Unexpected permission: $($Matches[1])" }
    }
}
if (-not ($metadata -match "^uses-permission: name='android\.permission\.READ_LOGS'")) { throw 'The shared log trigger requires READ_LOGS.' }
$outputApk = Join-Path $PSScriptRoot 'MiCTS-Quick.apk'
if (Test-Path -LiteralPath $outputApk) { [IO.File]::Replace($candidate, $outputApk, [NullString]::Value) }
else { [IO.File]::Move($candidate, $outputApk) }
$hash = Get-FileHash -LiteralPath $outputApk -Algorithm SHA256
$release = [ordered]@{
    Schema = 1
    Package = 'dev.padhelper.fastcts'
    Version = [string]$version.Version
    VersionCode = [int]$version.VersionCode
    FileName = 'Circle-to-Search-Trigger-' + $version.Version + '.apk'
    SHA256 = $hash.Hash.ToLowerInvariant()
}
# Beta builds publish their metadata separately; the in-app updater reads it only when "베타 버전도 확인" is on.
$releasePath = Join-Path $PSScriptRoot $(if ($Beta) { '../../App-Release-Beta.json' } else { '../../App-Release.json' })
[IO.File]::WriteAllText($releasePath, ($release | ConvertTo-Json) + "`r`n", (New-Object Text.UTF8Encoding($false)))
$hash
