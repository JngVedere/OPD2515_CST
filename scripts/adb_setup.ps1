
$pkg = "dev.padhelper.fastcts"
$user = 0
$svc = "$pkg/$pkg.BarService"

$googlePkg = "com.google.android.googlequicksearchbox"
$speechPkg = "com.google.android.tts"

# ==========================================
# Helper functions
# ==========================================

function Read-Adb {
    $output = adb @args 2>&1

    if ($LASTEXITCODE -ne 0) {
        throw ($output | Out-String)
    }

    return ($output | Out-String).Trim()
}

function Test-PackageInstalled {
    param([string]$PackageName)

    $output = Read-Adb shell pm list packages --user $user $PackageName

    return (($output -split "\r?\n") -contains "package:$PackageName")
}

function Open-PlayStore {
    param([string]$PackageName)

    adb shell am start --user $user `
        -a android.intent.action.VIEW `
        -d "market://details?id=$PackageName" `
        -p com.android.vending | Out-Null
}

function Test-Setup {
    param(
        [string]$Name,
        [scriptblock]$Check
    )

    try {
        if (& $Check) {
            $status = "PASS"
        } else {
            $status = "FAIL"
        }
    } catch {
        $status = "UNKNOWN"
    }

    [PSCustomObject]@{
        Name = $Name
        Status = $status
    }
}

# ==========================================
# Check prerequisites
# ==========================================

Write-Host "`n========== Prerequisite Check ==========" -ForegroundColor Cyan

# Required: Google

while (-not (Test-PackageInstalled $googlePkg)) {
    Write-Host "[REQUIRED] Google is not installed." -ForegroundColor Red
    Write-Host "Google Play 스토어에서 Google 앱을 설치해주세요." -ForegroundColor Yellow

    Open-PlayStore $googlePkg

    Write-Host "https://play.google.com/store/apps/details?id=$googlePkg" -ForegroundColor Cyan
    Write-Host "설치가 완료되면 Enter를 눌러주세요." -ForegroundColor Yellow

    Read-Host | Out-Null
}

Write-Host "[PASS] Google" -ForegroundColor Green

# Optional: Speech Recognition & Synthesis

$speechInstalled = Test-PackageInstalled $speechPkg

if ($speechInstalled) {
    Write-Host "[PASS] Speech Recognition & Synthesis" -ForegroundColor Green
} else {
    Write-Host "[OPTIONAL] Speech Recognition & Synthesis is not installed." -ForegroundColor Yellow
}

Write-Host "========================================" -ForegroundColor Cyan

# ==========================================
# Disable system optimization
# ==========================================

adb shell cmd locale set-app-locales com.android.settings --user $user --locales en-US

try {
    adb shell am start -W --user $user `
        -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS `
        --es ":settings:fragment_args_key" "safecenter_system_optimization" | Out-Null

    Write-Host "Disable system optimization을 켠 뒤 Enter" -ForegroundColor Yellow
    Read-Host | Out-Null

} finally {
    adb shell "cmd locale set-app-locales com.android.settings --user $user --locales ''"
}

# ==========================================
# Configure Google Assistant
# ==========================================

adb shell pm disable-user --user $user com.heytap.speechassist | Out-Null

adb shell cmd role add-role-holder --user $user android.app.role.ASSISTANT $googlePkg

adb shell settings put global oplus.speechassist.main.type 2

adb shell settings --user $user put secure assist_structure_enabled 1
adb shell settings --user $user put secure assist_screenshot_enabled 1

adb shell cmd appops set --user $user $googlePkg READ_SCREEN_CONTEXT allow

# ==========================================
# Grant permissions
# ==========================================

adb shell pm grant --user $user $pkg android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant --user $user $pkg android.permission.POST_NOTIFICATIONS

adb shell cmd appops set --user $user $pkg ACCESS_RESTRICTED_SETTINGS allow
adb shell cmd appops set --user $user $pkg POST_PROMOTED_NOTIFICATIONS allow

# ==========================================
# Configure background execution
# ==========================================

adb shell dumpsys deviceidle whitelist "+$pkg" | Out-Null

adb shell cmd appops set --user $user $pkg RUN_IN_BACKGROUND allow
adb shell cmd appops set --user $user $pkg RUN_ANY_IN_BACKGROUND allow

adb shell am start -W --user $user -n "$pkg/.SetupActivity" | Out-Null

# ==========================================
# Enable accessibility service
# ==========================================

$current = (adb shell settings --user $user get secure enabled_accessibility_services | Out-String).Trim()

$services = @(
    $current -split ":" | Where-Object {
        $_ -and $_ -ne "null" -and
        $_ -ne "$pkg/.BarService" -and
        $_ -ne $svc
    }
)

$services += $svc

adb shell settings --user $user put secure enabled_accessibility_services ($services -join ":")
adb shell settings --user $user put secure accessibility_enabled 1

# ==========================================
# Configure OpdCTS
# ==========================================

adb shell pm grant --user $user $pkg android.permission.READ_LOGS

adb shell am start -W --user $user `
    -n "$pkg/.SetupCommandActivity" `
    --es setup_mode coloros_log `
    --ez setup_circle true `
    --ez setup_boot_recovery true `
    --ez setup_power true `
    --ez setup_enable true | Out-Null

adb shell am start -W --user $user -n "$pkg/.SetupActivity" | Out-Null

# ==========================================
# Log access approval
# ==========================================

Write-Host "기기 로그 접근을 허용한 뒤 Enter ( (팝업까지 최대 2분 소요))" -ForegroundColor Yellow
Read-Host | Out-Null

adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_ASSIST

# ==========================================
# Verify setup results
# ==========================================

$escapedPkg = [regex]::Escape($pkg)

$results = @(

    Test-Setup "Breeno disabled" {
        (Read-Adb shell pm list packages -d --user $user) `
            -split "\r?\n" -contains "package:com.heytap.speechassist"
    }

    Test-Setup "Google Assistant" {
        (Read-Adb shell settings --user $user get secure assistant) `
            -match "^com\.google\.android\.googlequicksearchbox/"
    }

    Test-Setup "Native navigation bar" {
        (Read-Adb shell settings get global oplus.speechassist.main.type) `
            -eq "2"
    }

    Test-Setup "Assist structure" {
        (Read-Adb shell settings --user $user get secure assist_structure_enabled) `
            -eq "1"
    }

    Test-Setup "Assist screenshot" {
        (Read-Adb shell settings --user $user get secure assist_screenshot_enabled) `
            -eq "1"
    }

    Test-Setup "Assistant screen context" {
        (Read-Adb shell cmd appops get --user $user $googlePkg READ_SCREEN_CONTEXT) `
            -match "(?m)\bREAD_SCREEN_CONTEXT:\s*allow\b"
    }

    foreach ($permission in @(
        "WRITE_SECURE_SETTINGS",
        "POST_NOTIFICATIONS",
        "READ_LOGS"
    )) {
        Test-Setup $permission {
            (Read-Adb shell dumpsys package $pkg) `
                -match "android\.permission\.$($permission):\s*granted=true"
        }
    }

    foreach ($op in @(
        "ACCESS_RESTRICTED_SETTINGS",
        "POST_PROMOTED_NOTIFICATIONS",
        "RUN_IN_BACKGROUND",
        "RUN_ANY_IN_BACKGROUND"
    )) {
        Test-Setup $op {
            (Read-Adb shell cmd appops get --user $user $pkg $op) `
                -match "(?m)\b$($op):\s*allow\b"
        }
    }

    Test-Setup "Battery whitelist" {
        (Read-Adb shell dumpsys deviceidle whitelist) `
            -match "(?m),$escapedPkg,"
    }

    Test-Setup "Accessibility service" {
        $enabled = Read-Adb shell settings --user $user get secure enabled_accessibility_services

        ($enabled -split ":") -contains $svc -or
        ($enabled -split ":") -contains "$pkg/.BarService"
    }

    Test-Setup "Accessibility enabled" {
        (Read-Adb shell settings --user $user get secure accessibility_enabled) `
            -eq "1"
    }
)

# ==========================================
# Manual setup items
# ==========================================

$speechInstalled = Test-PackageInstalled $speechPkg

$manualItems = @(
    "Log access approval"
    "Auto launch"
    "Keep open lock"
)

if (-not $speechInstalled) {
    $manualItems += "Install Speech Recognition & Synthesis (Optional)"
}

# ==========================================
# Display setup results
# ==========================================

Write-Host "`n========== OpdCTS Setup Results ==========" -ForegroundColor Cyan

foreach ($result in $results) {
    switch ($result.Status) {
        "PASS" {
            Write-Host "[PASS] $($result.Name)" -ForegroundColor Green
        }
        "FAIL" {
            Write-Host "[FAIL] $($result.Name)" -ForegroundColor Red
        }
        "UNKNOWN" {
            Write-Host "[UNKNOWN] $($result.Name)" -ForegroundColor Yellow
        }
    }
}

foreach ($item in $manualItems) {
    Write-Host "[MANUAL] $item" -ForegroundColor Yellow
}

$passed = @($results | Where-Object Status -eq "PASS").Count
$failed = @($results | Where-Object Status -eq "FAIL").Count
$unknown = @($results | Where-Object Status -eq "UNKNOWN").Count
$manualCount = $manualItems.Count

Write-Host "==========================================" -ForegroundColor Cyan

Write-Host "Passed: $passed" -ForegroundColor Green
Write-Host "Failed: $failed" -ForegroundColor Red
Write-Host "Unknown: $unknown" -ForegroundColor Yellow
Write-Host "Manual: $manualCount" -ForegroundColor Yellow

if ($failed -eq 0 -and $unknown -eq 0) {
    Write-Host "`nAll automated checks passed." -ForegroundColor Green
} else {
    Write-Host "`nSome settings require attention." -ForegroundColor Yellow
}

# ==========================================
# Final instructions
# ==========================================

adb shell am start -W --user $user -n "$pkg/.SetupActivity" | Out-Null

Write-Host "`n남은 안내에 따라 다음 설정을 완료해주세요." -ForegroundColor Yellow

Write-Host "1. 자동 실행 (Auto launch)" -ForegroundColor Yellow
Write-Host "2. 앱 잠금 (Keep open lock)" -ForegroundColor Yellow

if (-not $speechInstalled) {
    Write-Host "`n[선택 사항] Google Play 스토어에서 Speech Recognition & Synthesis 앱을 설치해주세요." -ForegroundColor Yellow
    Write-Host "https://play.google.com/store/apps/details?id=$speechPkg" -ForegroundColor Cyan
}
