
#!/usr/bin/env bash

pkg="dev.padhelper.fastcts"
user=0
svc="$pkg/$pkg.BarService"

googlePkg="com.google.android.googlequicksearchbox"
speechPkg="com.google.android.tts"

passed=0
failed=0
unknown=0
manualCount=3
localeChanged=0

# ==========================================
# Helper functions
# ==========================================

print_status() {
    local color

    case "$1" in
        PASS)     color=32 ;;
        FAIL)     color=31 ;;
        ERROR)    color=31 ;;
        REQUIRED) color=31 ;;
        UNKNOWN)  color=33 ;;
        MANUAL)   color=33 ;;
        OPTIONAL) color=33 ;;
        *)        color=0 ;;
    esac

    printf '\033[%sm[%s] %s\033[0m\n' "$color" "$1" "$2"
}

print_info() {
    printf '\033[36m%s\033[0m\n' "$1"
}

print_warning() {
    printf '\033[33m%s\033[0m\n' "$1"
}

wait_enter() {
    local answer

    print_warning "$1"
    IFS= read -r answer </dev/tty
}

is_installed() {
    local output

    output=$(adb shell pm list packages --user "$user" "$1" 2>&1) || return 2

    output=${output//$'\r'/}

    grep -Fxq "package:$1" <<< "$output"
}

open_playstore() {
    adb shell am start --user "$user" \
        -a android.intent.action.VIEW \
        -d "'market://details?id=$1'" \
        -p com.android.vending >/dev/null
}

check_speech() {
    is_installed "$speechPkg"

    case $? in
        0) speechInstalled=1 ;;
        1) speechInstalled=0 ;;
        *) speechInstalled=-1 ;;
    esac
}

report() {
    print_status "$2" "$1"

    case "$2" in
        PASS)    passed=$((passed + 1)) ;;
        FAIL)    failed=$((failed + 1)) ;;
        UNKNOWN) unknown=$((unknown + 1)) ;;
    esac
}

check() {
    local name="$1"
    local pattern="$2"
    local output

    shift 2

    output=$("$@" 2>&1)

    if [ "$?" -ne 0 ]; then
        report "$name" UNKNOWN
        return
    fi

    output=${output//$'\r'/}

    grep -Eq "$pattern" <<< "$output"

    case $? in
        0) report "$name" PASS ;;
        1) report "$name" FAIL ;;
        *) report "$name" UNKNOWN ;;
    esac
}

restore_locale() {
    if [ "$localeChanged" -eq 1 ]; then
        adb shell "cmd locale set-app-locales com.android.settings --user $user --locales ''"

        if [ "$?" -ne 0 ]; then
            print_warning "설정 앱의 언어를 복원하지 못했습니다."
        fi

        localeChanged=0
    fi
}

trap restore_locale EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ==========================================
# Check ADB connection
# ==========================================

printf '\n'
print_info "========== ADB Connection Check =========="

if ! command -v adb >/dev/null 2>&1; then
    print_status ERROR "ADB is not installed."

    print_warning "Android Platform Tools를 설치해주세요."
    print_info "https://developer.android.com/tools/releases/platform-tools"

    exit 1
fi

print_status PASS "ADB installed"

adbState=$(adb get-state 2>/dev/null)
adbState=${adbState//$'\r'/}

if [ "$adbState" != "device" ]; then
    print_status ERROR "ADB device is not connected or authorized."

    adb devices

    print_warning "USB 디버깅 및 기기 연결 상태를 확인해주세요."

    exit 1
fi

print_status PASS "ADB device connected"

is_installed "$pkg"
packageStatus=$?

case "$packageStatus" in
    0)
        print_status PASS "OpdCTS installed"
        ;;
    1)
        print_status ERROR "OpdCTS is not installed."
        print_warning "OpdCTS를 먼저 설치해주세요."
        exit 1
        ;;
    *)
        print_status ERROR "Failed to query OpdCTS package."
        exit 1
        ;;
esac

print_info "=========================================="

# ==========================================
# Check prerequisites
# ==========================================

printf '\n'
print_info "========== Prerequisite Check =========="

# Required: Google

while true; do
    is_installed "$googlePkg"
    packageStatus=$?

    case "$packageStatus" in
        0)
            break
            ;;
        1)
            print_status REQUIRED "Google is not installed."

            print_warning "Google Play 스토어에서 Google 앱을 설치해주세요."

            open_playstore "$googlePkg"

            print_info "https://play.google.com/store/apps/details?id=$googlePkg"

            wait_enter "설치가 완료되면 Enter를 눌러주세요." || exit 1
            ;;
        *)
            print_status ERROR "Failed to query installed packages."
            exit 1
            ;;
    esac
done

print_status PASS "Google"

# Optional: Speech Recognition & Synthesis

check_speech

case "$speechInstalled" in
    1)
        print_status PASS "Speech Recognition & Synthesis"
        ;;
    0)
        print_status OPTIONAL "Speech Recognition & Synthesis is not installed."
        ;;
    *)
        print_status UNKNOWN "Speech Recognition & Synthesis"
        ;;
esac

print_info "========================================"

# ==========================================
# Disable system optimization
# ==========================================

localeChanged=1

adb shell cmd locale set-app-locales com.android.settings \
    --user "$user" \
    --locales en-US

adb shell am start -W --user "$user" \
    -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS \
    --es ":settings:fragment_args_key" \
    "safecenter_system_optimization" >/dev/null

wait_enter "Disable system optimization을 켠 뒤 Enter" || exit 1

restore_locale

# ==========================================
# Configure Google Assistant
# ==========================================

adb shell pm disable-user --user "$user" \
    com.heytap.speechassist >/dev/null

adb shell cmd role add-role-holder --user "$user" \
    android.app.role.ASSISTANT "$googlePkg"

adb shell settings put global \
    oplus.speechassist.main.type 2

adb shell settings --user "$user" put secure \
    assist_structure_enabled 1

adb shell settings --user "$user" put secure \
    assist_screenshot_enabled 1

adb shell cmd appops set --user "$user" \
    "$googlePkg" READ_SCREEN_CONTEXT allow

# ==========================================
# Grant permissions
# ==========================================

adb shell pm grant --user "$user" "$pkg" \
    android.permission.WRITE_SECURE_SETTINGS

adb shell pm grant --user "$user" "$pkg" \
    android.permission.POST_NOTIFICATIONS

adb shell cmd appops set --user "$user" "$pkg" \
    ACCESS_RESTRICTED_SETTINGS allow

adb shell cmd appops set --user "$user" "$pkg" \
    POST_PROMOTED_NOTIFICATIONS allow

# ==========================================
# Configure background execution
# ==========================================

adb shell dumpsys deviceidle whitelist "+$pkg" >/dev/null

adb shell cmd appops set --user "$user" "$pkg" \
    RUN_IN_BACKGROUND allow

adb shell cmd appops set --user "$user" "$pkg" \
    RUN_ANY_IN_BACKGROUND allow

adb shell am start -W --user "$user" \
    -n "$pkg/.SetupActivity" >/dev/null

# ==========================================
# Enable accessibility service
# ==========================================

current=$(adb shell settings --user "$user" get secure \
    enabled_accessibility_services)

if [ "$?" -ne 0 ]; then
    print_status ERROR "Failed to read accessibility services."
    exit 1
fi

current=${current//$'\r'/}

services=""

IFS=: read -r -a components <<< "$current"

for component in "${components[@]}"; do
    case "$component" in
        ""|null|"$pkg/.BarService"|"$svc")
            ;;
        *)
            if [ -n "$services" ]; then
                services="$services:$component"
            else
                services="$component"
            fi
            ;;
    esac
done

if [ -n "$services" ]; then
    services="$services:$svc"
else
    services="$svc"
fi

adb shell settings --user "$user" put secure \
    enabled_accessibility_services "$services"

adb shell settings --user "$user" put secure \
    accessibility_enabled 1

# ==========================================
# Configure OpdCTS
# ==========================================

adb shell pm grant --user "$user" "$pkg" \
    android.permission.READ_LOGS

adb shell am start -W --user "$user" \
    -n "$pkg/.SetupCommandActivity" \
    --es setup_mode coloros_log \
    --ez setup_circle true \
    --ez setup_boot_recovery true \
    --ez setup_power true \
    --ez setup_enable true >/dev/null

adb shell am start -W --user "$user" \
    -n "$pkg/.SetupActivity" >/dev/null

# ==========================================
# Log access approval
# ==========================================

wait_enter "기기 로그 접근을 허용한 뒤 Enter (팝업까지 최대 2분 소요)" || exit 1

adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_ASSIST

# ==========================================
# Verify setup results
# ==========================================

printf '\n'
print_info "========== OpdCTS Setup Results =========="

check "Breeno disabled" \
    '^package:com\.heytap\.speechassist$' \
    adb shell pm list packages -d --user "$user"

check "Google Assistant" \
    '^com\.google\.android\.googlequicksearchbox/' \
    adb shell settings --user "$user" get secure assistant

check "Native navigation bar" \
    '^2$' \
    adb shell settings get global oplus.speechassist.main.type

check "Assist structure" \
    '^1$' \
    adb shell settings --user "$user" get secure assist_structure_enabled

check "Assist screenshot" \
    '^1$' \
    adb shell settings --user "$user" get secure assist_screenshot_enabled

check "Assistant screen context" \
    'READ_SCREEN_CONTEXT:[[:space:]]*allow([[:space:];,]|$)' \
    adb shell cmd appops get --user "$user" \
    "$googlePkg" READ_SCREEN_CONTEXT

# Verify Android permissions

for permission in \
    WRITE_SECURE_SETTINGS \
    POST_NOTIFICATIONS \
    READ_LOGS
do
    check "$permission" \
        "android\.permission\.$permission:[[:space:]]*granted=true([[:space:],]|$)" \
        adb shell dumpsys package "$pkg"
done

# Verify AppOps

for op in \
    ACCESS_RESTRICTED_SETTINGS \
    POST_PROMOTED_NOTIFICATIONS \
    RUN_IN_BACKGROUND \
    RUN_ANY_IN_BACKGROUND
do
    check "$op" \
        "$op:[[:space:]]*allow([[:space:];,]|$)" \
        adb shell cmd appops get --user "$user" "$pkg" "$op"
done

# Battery whitelist

check "Battery whitelist" \
    ',dev\.padhelper\.fastcts,' \
    adb shell dumpsys deviceidle whitelist

# Accessibility service

check "Accessibility service" \
    '(^|:)dev\.padhelper\.fastcts/(\.BarService|dev\.padhelper\.fastcts\.BarService)(:|$)' \
    adb shell settings --user "$user" get secure enabled_accessibility_services

# Accessibility enabled

check "Accessibility enabled" \
    '^1$' \
    adb shell settings --user "$user" get secure accessibility_enabled

# ==========================================
# Manual setup items
# ==========================================

print_status MANUAL "Log access approval"
print_status MANUAL "Auto launch"
print_status MANUAL "Keep open lock"

check_speech

case "$speechInstalled" in
    0)
        print_status MANUAL "Install Speech Recognition & Synthesis (Optional)"
        manualCount=$((manualCount + 1))
        ;;
    -1)
        report "Speech Recognition & Synthesis" UNKNOWN
        ;;
esac

# ==========================================
# Display setup results
# ==========================================

print_info "=========================================="

printf '\033[32mPassed: %s\033[0m\n' "$passed"
printf '\033[31mFailed: %s\033[0m\n' "$failed"
printf '\033[33mUnknown: %s\033[0m\n' "$unknown"
printf '\033[33mManual: %s\033[0m\n' "$manualCount"

printf '\n'

if [ "$failed" -eq 0 ] && [ "$unknown" -eq 0 ]; then
    printf '\033[32mAll automated checks passed.\033[0m\n'
else
    print_warning "Some settings require attention."
fi

# ==========================================
# Final instructions
# ==========================================

adb shell am start -W --user "$user" \
    -n "$pkg/.SetupActivity" >/dev/null

printf '\n'

print_warning "남은 안내에 따라 다음 설정을 완료해주세요."

print_warning "1. 자동 실행 (Auto launch)"
print_warning "2. 앱 잠금 (Keep open lock)"

if [ "$speechInstalled" -eq 0 ]; then
    printf '\n'

    print_warning "[선택 사항] Google Play 스토어에서 Speech Recognition & Synthesis 앱을 설치해주세요."

    print_info "https://play.google.com/store/apps/details?id=$speechPkg"
fi
