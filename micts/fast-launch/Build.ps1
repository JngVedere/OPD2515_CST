param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
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
Run (Join-Path $tools 'aapt2.exe') @('compile','--dir',"$PSScriptRoot/res",'-o',"$build/resources.zip")
Run (Join-Path $tools 'aapt2.exe') @('link','-I',$androidJar,'--manifest',"$PSScriptRoot/AndroidManifest.xml",'--min-sdk-version','30','--target-sdk-version','36','--version-code',[string]$version.VersionCode,'--version-name',[string]$version.Version,'-o',"$build/unsigned.apk","$build/resources.zip")
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter *.java -File | Select-Object -ExpandProperty FullName)
Run 'javac.exe' (@('--release','17','-encoding','UTF-8','-classpath',$androidJar,'-d',"$build/classes") + $sources)
$classes = @(Get-ChildItem -LiteralPath "$build/classes" -Filter *.class -File -Recurse | Select-Object -ExpandProperty FullName)
Run 'java.exe' (@('-cp',"$tools/lib/d8.jar",'com.android.tools.r8.D8','--lib',$androidJar,'--min-api','30','--output',"$build/dex") + $classes)
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
$allowed = @('android.permission.RECEIVE_BOOT_COMPLETED','android.permission.WRITE_SECURE_SETTINGS')
foreach ($line in $metadata) {
    if ($line -match "^uses-permission(?:-sdk-\d+)?: name='([^']+)'") {
        if ($Matches[1] -notin $allowed) { throw "Unexpected permission: $($Matches[1])" }
    }
}
$outputApk = Join-Path $PSScriptRoot 'MiCTS-Quick.apk'
if (Test-Path -LiteralPath $outputApk) { [IO.File]::Replace($candidate, $outputApk, [NullString]::Value) }
else { [IO.File]::Move($candidate, $outputApk) }
$hash = Get-FileHash -LiteralPath $outputApk -Algorithm SHA256
$release = [ordered]@{
    Schema = 1
    Package = 'dev.padhelper.fastcts'
    Version = [string]$version.Version
    VersionCode = [int]$version.VersionCode
    FileName = 'Bottom-Search-' + $version.Version + '.apk'
    SHA256 = $hash.Hash.ToLowerInvariant()
}
$releasePath = Join-Path $PSScriptRoot '../../App-Release.json'
[IO.File]::WriteAllText($releasePath, ($release | ConvertTo-Json) + "`r`n", (New-Object Text.UTF8Encoding($false)))
$hash
