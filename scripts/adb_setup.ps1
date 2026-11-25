$pkg = "dev.padhelper.fastcts"
$user = 0
$svc = "$pkg/$pkg.BarService"

adb shell cmd locale set-app-locales com.android.settings --user $user --locales en-US

adb shell am start -W --user $user -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS --es ":settings:fragment_args_key" "safecenter_system_optimization" | Out-Null

Read-Host "Disable system optimization을 켠 뒤 Enter"

adb shell "cmd locale set-app-locales com.android.settings --user $user --locales ''"

adb shell pm disable-user --user $user com.heytap.speechassist | Out-Null
adb shell cmd role add-role-holder --user $user android.app.role.ASSISTANT com.google.android.googlequicksearchbox

adb shell settings put global oplus.speechassist.main.type 2

adb shell settings --user $user put secure assist_structure_enabled 1
adb shell settings --user $user put secure assist_screenshot_enabled 1

adb shell pm grant --user $user $pkg android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant --user $user $pkg android.permission.POST_NOTIFICATIONS

adb shell cmd appops set --user $user $pkg ACCESS_RESTRICTED_SETTINGS allow
adb shell cmd appops set --user $user $pkg POST_PROMOTED_NOTIFICATIONS allow

adb shell dumpsys deviceidle whitelist "+$pkg" | Out-Null
adb shell cmd appops set --user $user $pkg RUN_IN_BACKGROUND allow
adb shell cmd appops set --user $user $pkg RUN_ANY_IN_BACKGROUND allow

adb shell am start -W --user $user -n "$pkg/.SetupActivity" | Out-Null

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

adb shell pm grant --user $user $pkg android.permission.READ_LOGS

adb shell am start -W --user $user `
    -n "$pkg/.SetupCommandActivity" `
    --es setup_mode coloros_log `
    --ez setup_circle true `
    --ez setup_boot_recovery true `
    --ez setup_power true `
    --ez setup_enable true | Out-Null

adb shell am start -W --user $user -n "$pkg/.SetupActivity" | Out-Null

Read-Host "기기 로그 접근을 허용한 뒤 Enter"

adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_ASSIST

# ==========================================
# Verify setup results
# ==========================================

function Read-Adb {
    $output = adb @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ($output | Out-String)
    }
    return ($output | Out-String).Trim()
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

Write-Host "[MANUAL] Log access approval" -ForegroundColor Yellow
Write-Host "[MANUAL] Auto launch / App lock" -ForegroundColor Yellow

$passed = @($results | Where-Object Status -eq "PASS").Count
$failed = @($results | Where-Object Status -eq "FAIL").Count
$unknown = @($results | Where-Object Status -eq "UNKNOWN").Count

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Passed: $passed" -ForegroundColor Green
Write-Host "Failed: $failed" -ForegroundColor Red
Write-Host "Unknown: $unknown" -ForegroundColor Yellow
Write-Host "Manual: 2" -ForegroundColor Yellow

if ($failed -eq 0 -and $unknown -eq 0) {
    Write-Host "`nAll automated checks passed." -ForegroundColor Green
} else {
    Write-Host "`nSome settings require attention." -ForegroundColor Yellow
}

adb shell am start -W --user $user -n "$pkg/.SetupActivity" | Out-Null

Write-Host "`n남은 안내에 따라 '자동 실행'과 '앱 잠금'을 켜주세요." -ForegroundColor Yellow
