$pkg = "dev.padhelper.fastcts"
$user = 0
$svc = "$pkg/$pkg.BarService"

adb shell cmd locale set-app-locales com.android.settings --user $user --locales en-US

adb shell am start -W --user $user -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS --es ":settings:fragment_args_key" "safecenter_system_optimization" | Out-Null

Read-Host "Disable system optimization을 켠 뒤 Enter"

adb shell "cmd locale set-app-locales com.android.settings --user $user --locales ''"

adb shell pm disable-user --user $user com.heytap.speechassist | Out-Null
adb shell cmd role add-role-holder --user $user android.app.role.ASSISTANT com.google.android.googlequicksearchbox

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

Write-Host "`n남은 안내에 따라 '자동 실행'과 '앱 잠금'을 켜주세요." -ForegroundColor Yellow
