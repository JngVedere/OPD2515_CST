package dev.padhelper.fastcts;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Pure settings-status and last-attempt policy (no Android types; Test-NativeBar.ps1 runs it with only the JDK).
 * Readiness says what is configured and listening. Attempt records say how far this app's own request got:
 * a Gemini showSession acceptance or a MiCTS/app startActivity handoff is never reported as a visible window.
 * One record per pathway (newest only), main thread only: id, start/update wall time, outcome, input, detail.
 * Callers store only fixed app strings and exception class names: no log lines, screen text or app content.
 */
final class StatusPolicy {
    static final int PENDING = 0, CANCELLED = 1, FAILED = 2, HANDED_OFF = 3, ACCEPTED = 4;
    private static final String[] OUTCOMES = {"진행 중", "취소됨", "실패",
        "실행 요청 전달됨 (화면 표시 확인 불가)", "요청 수락됨 (창 표시 확인 불가)"};
    /** A step with no later result after this long was interrupted (blocked launch, killed process). */
    static final long INTERRUPTED = 10000;

    private StatusPolicy() {}

    /**
     * Selected voice interaction service: not Google, Google but its service component is missing/disabled, or Google
     * installed and enabled. Configuration only: whether the system binding is live cannot be seen without dumpsys.
     */
    static final int GOOGLE_NONE = 0, GOOGLE_UNAVAILABLE = 1, GOOGLE_READY = 2;
    private static final String CONFIG_ONLY = " · Google 설정 확인됨, 실제 연결 상태는 확인 불가";

    private static String googleProblem(int google) {
        return google == GOOGLE_NONE ? "기본 어시스턴트를 Google로 선택해야 합니다"
            : "선택된 Google 음성 서비스가 설치되지 않았거나 사용 중지됨 (Google 앱 확인)";
    }

    /**
     * ColorOS "제스처 안내 막대 숨기기" as observed read-only in secure navbar_gesture_visibility (OPD2515: 1 hidden, 0 shown).
     * Missing, unreadable or any other value is unknown, never assumed shown.
     * GESTURE_TASKBAR: system enable_launcher_taskbar is exactly "1"; the launcher's taskbar bar reports the hold itself
     * (observed even with the gesture bar hidden), so the hide setting is not an error. Any other taskbar value keeps the old rules.
     */
    static final int GESTURE_SHOWN = 0, GESTURE_HIDDEN = 1, GESTURE_UNKNOWN = 2, GESTURE_TASKBAR = 3, GESTURE_BUTTONS = 4;

    /** nav: OEM hide_navigationbar_enable; exactly "0" is 3-button navigation (home long press), where the gesture bar does not exist. */
    static int gestureBar(String value, String taskbar, String nav) {
        return "0".equals(nav) ? GESTURE_BUTTONS : gestureBar(value, taskbar);
    }

    static int gestureBar(String value, String taskbar) {
        if ("1".equals(taskbar)) return GESTURE_TASKBAR;
        return "0".equals(value) ? GESTURE_SHOWN : "1".equals(value) ? GESTURE_HIDDEN : GESTURE_UNKNOWN;
    }

    /** Bottom trigger (touch area or ColorOS bar) readiness, first failing prerequisite first. The gesture bar only matters natively. */
    static String bar(boolean approved, boolean enabled, boolean live, boolean nativeMode, boolean secureWrite, boolean breenoOff,
            boolean logsPermitted, boolean logActive, boolean logWaiting, boolean ready, boolean cts, int google, int gestureBar) {
        if (!approved) return "접근성 설정 필요 · 접근성 설정에서 Circle-to-Search Trigger를 켜주세요";
        if (!enabled) return "꺼짐";
        if (!live) return "서비스 연결 대기 · 접근성 승인됨, 계속되면 접근성에서 껐다 다시 켜주세요";
        boolean buttons = gestureBar == GESTURE_BUTTONS;
        String where = nativeMode ? (buttons ? "3버튼 홈 버튼" : "ColorOS 기본 바") : "터치 영역";
        if (nativeMode) {
            if (gestureBar == GESTURE_HIDDEN)
                return "오류 · 제스처 안내 막대 숨기기가 켜져 있습니다. 설정 → 시스템 및 업데이트 → 시스템 탐색에서 끄세요";
            if (!secureWrite) return "보안 설정 쓰기 권한 필요 · " + where;
            if (!breenoOff) return "Breeno 비활성화 필요 · " + where;
            if (!logsPermitted) return "기기 로그 권한 필요 · " + where;
            if (!logActive) return (logWaiting ? "기기 로그 확인 창에서 허용 대기 · " : "기기 로그 승인 필요 · ") + where;
            if (!ready) return "제스처 설정 확인 필요 · " + where;
        } else if (!ready) return "터치 영역 연결 대기 · 잠금 해제 후 다시 확인";
        if (cts && google != GOOGLE_READY) return where + " 감지 중 · 서클 투 서치: " + googleProblem(google);
        if (nativeMode && gestureBar != GESTURE_SHOWN && gestureBar != GESTURE_TASKBAR && !buttons)
            return where + " 감지 중 · 제스처 안내 막대 표시 여부 확인 불가 (설정 → 시스템 및 업데이트 → 시스템 탐색에서 숨기기가 꺼져 있는지 확인)";
        return "사용 중 · " + where + " 감지 중" + (cts ? " · Google 설정됨, 실제 연결 상태는 확인 불가" : "")
            + (nativeMode && gestureBar == GESTURE_TASKBAR ? " · 작업 표시줄 기준" : "")
            + (!nativeMode && buttons ? " · 3버튼 탐색에서 홈 화면의 최근 앱 버튼을 가리면 터치 영역에서 위치를 옮기세요" : "");
    }

    /** Power long press -> small Gemini readiness. Breeno is not a prerequisite here; WSS only enables the per-press rebind. */
    static String power(boolean powerOn, boolean approved, boolean live, boolean logsPermitted, boolean logActive,
            boolean logWaiting, int google, boolean secureWrite) {
        if (!powerOn) return "꺼짐";
        if (!approved) return "접근성 설정 필요";
        if (!live) return "접근성 서비스 연결 대기";
        if (!logsPermitted) return "기기 로그 권한 필요 · 최초 ADB 설정 안내 참고";
        if (!logActive) return logWaiting ? "기기 로그 확인 창에서 허용 대기" : "기기 로그 승인 필요 · 기기 로그 접근 승인 요청을 눌러주세요";
        if (google != GOOGLE_READY) return "Gemini 요청 불가 · " + googleProblem(google);
        return "감지 중 · 길게 누르면 Gemini Assistant 요청" + CONFIG_ONLY
            + (secureWrite ? "" : " (보안 설정 쓰기 권한 없음: Google 재연결 생략)");
    }

    /** Why LogTrigger.powerLaunch would refuse (same conditions), or null when it allows the request. */
    static String powerBlocked(boolean power, boolean granted, boolean interactive, boolean locked, boolean lockedAllowed,
            boolean active, long age) {
        if (!power) return "전원 버튼 옵션 꺼짐";
        if (!granted) return "접근성 승인 해제됨";
        if (!interactive) return "화면 꺼짐 (앱은 화면을 깨우지 않음)";
        if (locked && !lockedAllowed) return "잠금 상태 (잠금 화면 호출 옵션 꺼짐 또는 요청 중 변경)";
        if (!active) return "기기 로그 연결 끊김";
        if (age < 0 || age > LogTrigger.POWER_LAUNCH_AGE) return "요청 시간 초과 (" + age + "ms)";
        return null;
    }

    /** ApplicationExitInfo.REASON_OTHER ("OTHER KILLS BY SYSTEM"). */
    static final int EXIT_OTHER = 13;

    /**
     * The newest exit was ColorOS's cleanup kill (device: recents "모두 닫기" records reason 13 and, as the app reads it,
     * description "[UNKNOWN] o-kill(2)" unless the recents card is locked) and it happened after the user last said the
     * card is locked.
     */
    static boolean cleanupKill(int reason, String description, long at, long lockedAt) {
        return reason == EXIT_OTHER && description != null && description.contains("o-kill") && at > lockedAt;
    }

    /** Starts the newest record for one pathway; any older record (and its late callbacks) is superseded. */
    static String begin(String stored, long now, int outcome, String source, String detail) {
        Record old = parse(stored);
        return encode(old == null ? 1 : old.id + 1, now, now, outcome, source, detail);
    }

    /** Next state, or null if this callback is stale: another attempt started, or this one already has a result. */
    static String update(String stored, long id, long now, int outcome, String detail) {
        Record current = parse(stored);
        if (current == null || current.id != id || current.outcome != PENDING) return null;
        return encode(id, current.started, now, outcome, current.source, detail);
    }

    static long id(String stored) { Record record = parse(stored); return record == null ? 0 : record.id; }

    /** One short status line. */
    static String summary(String stored, long now) {
        Record record = parse(stored);
        if (record == null) return "기록 없음";
        return time("HH:mm:ss", record.started) + " · " + result(record, now);
    }

    /** Read-only detail dialog text. */
    static String detail(String stored, long now) {
        Record record = parse(stored);
        if (record == null) return "기록 없음";
        return "시각  " + time("yyyy-MM-dd HH:mm:ss", record.started)
            + "\n입력  " + record.source
            + "\n결과  " + result(record, now)
            + "\n내용  " + record.detail + (interrupted(record, now) ? " · 이후 결과가 기록되지 않음 (실행 차단 또는 앱 종료 가능)" : "");
    }

    private static boolean interrupted(Record record, long now) {
        return record.outcome == PENDING && Math.abs(now - record.updated) > INTERRUPTED;
    }

    private static String result(Record record, long now) {
        return interrupted(record, now) ? "결과 없음 (중단됨)" : OUTCOMES[record.outcome];
    }

    private static String time(String pattern, long millis) { return new SimpleDateFormat(pattern).format(new Date(millis)); }

    private static final class Record {
        final long id, started, updated;
        final int outcome;
        final String source, detail;
        Record(long id, long started, long updated, int outcome, String source, String detail) {
            this.id = id; this.started = started; this.updated = updated;
            this.outcome = outcome; this.source = source; this.detail = detail;
        }
    }

    private static String clean(String value) { return value == null ? "" : value.replace('\t', ' ').replace('\n', ' '); }

    private static String encode(long id, long started, long updated, int outcome, String source, String detail) {
        return id + "\t" + started + "\t" + updated + "\t" + outcome + "\t" + clean(source) + "\t" + clean(detail);
    }

    private static Record parse(String stored) {
        if (stored == null) return null;
        String[] f = stored.split("\t", -1);
        if (f.length != 6) return null;
        try {
            int outcome = Integer.parseInt(f[3]);
            long id = Long.parseLong(f[0]);
            if (id <= 0 || outcome < PENDING || outcome > ACCEPTED) return null;
            return new Record(id, Long.parseLong(f[1]), Long.parseLong(f[2]), outcome, f[4], f[5]);
        } catch (NumberFormatException invalid) { return null; }
    }

    /** Optional update notification: one GitHub check per 24 h (Alerts). */
    static final long UPDATE_INTERVAL = 24L * 60 * 60 * 1000;

    /** Due when no check ran yet, the clock went backwards (never stuck in the future), or a full interval has passed. */
    static boolean updateCheckDue(long now, long lastCheck) {
        return lastCheck <= 0 || now < lastCheck || now - lastCheck >= UPDATE_INTERVAL;
    }
}
