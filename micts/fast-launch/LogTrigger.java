package dev.padhelper.fastcts;

import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/**
 * Pure logd packet policy (no Android types). Only structural results leave this class:
 * a trigger kind, the writer UID and an entry time. Raw messages are never stored.
 */
final class LogTrigger {
    /**
     * GOOGLE_DIED: ColorOS reports the Google assistant interactor process killed (e.g. recents "clear all").
     * POWER_SINGLE / POWER_DOUBLE: short and double power presses, seen only while PowerButton settings make ColorOS
     * wait for a second press (a single press while the screen was off is never POWER_SINGLE).
     * HOME: the same Breeno start failure as BAR but requested by system_server (caller 1000): 3-button home long press,
     * and also the power long press's tail, which the Gate drops after a power marker (POWER_TAIL).
     * ANY_KILL: any other UserAwareMgr process kill; only times the Google warm-up after a clear-all sweep (Warm).
     */
    static final int NONE = 0, BAR = 1, POWER = 2, GOOGLE_DIED = 3, POWER_SINGLE = 4, POWER_DOUBLE = 5, HOME = 6, ANY_KILL = 7;
    /** Device: the power long press logs onLongPress, then its caller-1000 Breeno line 2 ms later. */
    static final long POWER_TAIL = 1000;
    /** The double press's own "send base.powerPress" line follows its onMultiPress line in the same millisecond. */
    static final long DOUBLE_PAIR = 300;
    /** One SOCK_SEQPACKET connection to logd: system buffer only, starting from the newest entry. */
    static final String COMMAND = "stream lids=3 tail=1";
    static final int MAX_PACKET = 5120, SYSTEM_UID = 1000, SYSTEM_LOG = 3;
    static final long MAX_AGE = 1500, DEBOUNCE = 1500;
    /** A new session that never yields a system-UID entry was declined or never approved. */
    static final long ACTIVATION_TIMEOUT = 35000;
    /** Automatic first-unlock approval windows per boot; a decline stops further automatic prompts. */
    static final int AUTO_PROMPTS = 3;
    // logger_entry v4: len u16, hdr_size u16, pid, tid, sec, nsec, lid, uid (28 bytes).
    private static final int HEADER = 28;
    private static final byte[] POWER_TAG = ascii("KEYLOG_SinglePowerKeyMonitor"), POWER_TEXT = ascii("PowerKey:onLongPress");
    private static final byte[] BAR_TAG = ascii("ActivityManager");
    /** Observed OPD2515 (system_server, INFO): "process killed: {pkgName='<pkg>', serviceName='', procName=<proc>, pid=..." */
    private static final byte[] KILL_TAG = ascii("UserAwareMgr");
    private static final String KILL_ANY = "process killed: {pkgName='",
        KILL_PREFIX = "process killed: {pkgName='com.google.android.googlequicksearchbox', ",
        KILL_PROC = ", procName=com.google.android.googlequicksearchbox:interactor, ";
    /** Observed OPD2515 (system_server): one line per handled press, after any multi-press wait. */
    private static final byte[] PRESS_TAG = ascii("KEYLOG_PhoneWindowManagerExtImpl");
    private static final Pattern SINGLE = Pattern.compile("PowerKey: send base\\.powerPress, beganFromNonInteractive=false downTime=\\d{1,19}"),
        DOUBLE = Pattern.compile("PowerKey: onMultiPress, down time:\\d{1,19} count:2");
    private static final int INFO = 4, WARN = 5;
    private static final Pattern ORIGIN = Pattern.compile("\\s+mCallingUid=(\\d+)\\s+\\}\\s+U=(\\d+): not found\\s*$");

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static int u16(byte[] p, int at) { return (p[at] & 0xff) | (p[at + 1] & 0xff) << 8; }
    private static int i32(byte[] p, int at) { return u16(p, at) | u16(p, at + 2) << 16; }

    private static boolean valid(byte[] p, int n) {
        if (p == null || n < HEADER || n > p.length || n > MAX_PACKET) return false;
        int len = u16(p, 0), hdr = u16(p, 2);
        return hdr >= HEADER && len >= 3 && hdr + len <= n;
    }

    /** Writer UID recorded by logd, or -1 for a malformed packet. Writer UIDs cannot be chosen by apps. */
    static int uid(byte[] p, int n) { return valid(p, n) ? i32(p, 24) : -1; }

    /** Bytes after the first logd entry in one read (non-zero only if a read returned several entries). */
    static int trailing(byte[] p, int n) { return valid(p, n) ? n - u16(p, 2) - u16(p, 0) : 0; }

    /** Session health proof: an entry written by the system UID. The app's own (or any app's) entries never qualify. */
    static boolean systemEntry(byte[] p, int n) { return uid(p, n) == SYSTEM_UID; }

    /** Entry wall-clock time in milliseconds (logd realtime stamp). */
    static long millis(byte[] p) { return (i32(p, 12) & 0xffffffffL) * 1000 + (i32(p, 16) & 0xffffffffL) / 1_000_000; }

    /** Trusted classification: system_server writer, system buffer, exact tag and message shape. */
    static int classify(byte[] p, int n, int systemUiUid, int launcherUid, int user) {
        if (!valid(p, n) || i32(p, 24) != SYSTEM_UID || i32(p, 20) != SYSTEM_LOG) return NONE;
        int hdr = u16(p, 2), end = hdr + u16(p, 0), tag = hdr + 1, tagEnd = zero(p, tag, end);
        if (tagEnd >= end) return NONE;
        int message = tagEnd + 1, messageEnd = zero(p, message, end);
        if (same(p, tag, tagEnd, POWER_TAG)) {
            int after = message + POWER_TEXT.length;
            if (messageEnd >= after && same(p, message, after, POWER_TEXT) && (after == messageEnd || p[after] == ',')) return POWER;
            return messageEnd - message <= 128
                && DOUBLE.matcher(new String(p, message, messageEnd - message, StandardCharsets.US_ASCII)).matches() ? POWER_DOUBLE : NONE;
        }
        if (same(p, tag, tagEnd, PRESS_TAG)) {
            return messageEnd - message <= 128
                && SINGLE.matcher(new String(p, message, messageEnd - message, StandardCharsets.US_ASCII)).matches() ? POWER_SINGLE : NONE;
        }
        if (same(p, tag, tagEnd, KILL_TAG)) {
            if (p[hdr] != INFO || messageEnd - message > 4096) return NONE;
            String text = new String(p, message, messageEnd - message, StandardCharsets.UTF_8);
            return googleDied(text) ? GOOGLE_DIED : anyKill(text) ? ANY_KILL : NONE;
        }
        if (p[hdr] != WARN || !same(p, tag, tagEnd, BAR_TAG)) return NONE;
        int caller = barCaller(new String(p, message, messageEnd - message, StandardCharsets.UTF_8), user);
        return caller == SYSTEM_UID ? HOME : caller > SYSTEM_UID && (caller == systemUiUid || caller == launcherUid) ? BAR : NONE;
    }

    /**
     * ColorOS bar hold: the disabled Breeno service start requested by SystemUI, or by the verified system launcher when
     * its taskbar draws the bar (power uses caller 1000). Each UID is resolved by the caller; -1 (unresolved) never matches.
     */
    static boolean bar(String message, int systemUiUid, int launcherUid, int user) {
        int caller = barCaller(message, user);
        return caller > SYSTEM_UID && (caller == systemUiUid || caller == launcherUid);
    }

    /** The calling UID of an exact Breeno start failure for this user, else -1. */
    static int barCaller(String message, int user) {
        if (message == null || message.length() > 4096 || user < 0) return -1;
        if (!message.startsWith("Unable to start service Intent { ")
            || !message.contains("act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST ")
            || !message.contains("cmp=com.heytap.speechassist/.core.SpeechService ")) return -1;
        if (message.indexOf("mCallingUid=") != message.lastIndexOf("mCallingUid=")
            || message.indexOf(" U=") != message.lastIndexOf(" U=")) return -1;
        Matcher origin = ORIGIN.matcher(message);
        try {
            if (!origin.find() || Integer.parseInt(origin.group(2)) != user) return -1;
            return Integer.parseInt(origin.group(1));
        } catch (NumberFormatException invalid) { return -1; }
    }

    /** Exactly the Google package's :interactor process (the small-Gemini session host), named once. */
    static boolean googleDied(String message) {
        return message != null && message.startsWith(KILL_PREFIX)
            && message.indexOf(KILL_PROC) > 0 && message.indexOf(KILL_PROC) == message.lastIndexOf(KILL_PROC)
            && message.indexOf("procName=") == message.lastIndexOf("procName=");
    }

    /** Any process kill in the same exact shape: one package and one process name. Nothing of it leaves this class. */
    static boolean anyKill(String message) {
        return message != null && message.startsWith(KILL_ANY) && message.indexOf("', ", KILL_ANY.length()) > KILL_ANY.length()
            && message.indexOf("procName=") > 0 && message.indexOf("procName=") == message.lastIndexOf("procName=");
    }

    /**
     * 일부 앱에서 하단 영역 인식 강제 사용: the app ColorOS resumes. Observed OPD2515 (system_server, system buffer, every app
     * switch including Home): "NFW_setLastResumedActivityUncheckLocked:true r:ActivityRecord{<id> u<user> <pkg>/<class> ...".
     * Only whether that app is in apps leaves this class: SELECTED_APP, OTHER_APP, or -1 for any other entry.
     */
    static final int OTHER_APP = 0, SELECTED_APP = 1;
    private static final byte[] TASK_TAG = ascii("ActivityTaskManager"), RESUMED_PREFIX = ascii("NFW_setLastResumedActivityUncheckLocked:true r:ActivityRecord{");
    private static final Pattern RESUMED = Pattern.compile("NFW_setLastResumedActivityUncheckLocked:true r:ActivityRecord\\{[0-9a-f]{1,16} u(\\d{1,6}) ([A-Za-z0-9_.]{1,255})/");

    static int foreground(byte[] p, int n, int user, java.util.Set<String> apps) {
        if (!valid(p, n) || i32(p, 24) != SYSTEM_UID || i32(p, 20) != SYSTEM_LOG) return -1;
        int hdr = u16(p, 2), end = hdr + u16(p, 0), tag = hdr + 1, tagEnd = zero(p, tag, end);
        if (tagEnd >= end || !same(p, tag, tagEnd, TASK_TAG)) return -1;
        int message = tagEnd + 1, messageEnd = zero(p, message, end);
        if (messageEnd - message > 1024 || !starts(p, message, messageEnd, RESUMED_PREFIX)) return -1;
        return resumed(new String(p, message, messageEnd - message, StandardCharsets.US_ASCII), user, apps);
    }

    static int resumed(String message, int user, java.util.Set<String> apps) {
        Matcher line = RESUMED.matcher(message);
        try { if (!line.lookingAt() || Integer.parseInt(line.group(1)) != user) return -1; }
        catch (NumberFormatException invalid) { return -1; }
        return apps.contains(line.group(2)) ? SELECTED_APP : OTHER_APP;
    }

    private static int zero(byte[] p, int from, int end) {
        for (int i = from; i < end; i++) if (p[i] == 0) return i;
        return end;
    }

    private static boolean same(byte[] p, int from, int to, byte[] expected) {
        if (to - from != expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (p[from + i] != expected[i]) return false;
        return true;
    }

    /** Gate outcomes, for structural diagnostics. */
    static final int ACCEPT = 0, NOT_TRIGGER = 1, BEFORE_SESSION = 2, FUTURE = 3, STALE = 4, DUPLICATE = 5;
    private static final String[] OUTCOMES = {"ACCEPT", "NOT_TRIGGER", "BEFORE_SESSION", "FUTURE", "STALE", "DUPLICATE"};
    private static final String[] KINDS = {"NONE", "BAR", "POWER", "GOOGLE_DIED", "POWER_SINGLE", "POWER_DOUBLE", "HOME", "ANY_KILL"};

    /** Freshness, replay and duplicate-marker gate for one log session. Reader thread only. */
    static final class Gate {
        private final long watermark;
        private final long[] last = new long[8];
        private final boolean[] seen = new boolean[8];
        /** Every power marker, accepted or not (a duplicate onLongPress still has its own Breeno tail). */
        private long lastPower;
        private boolean powerSeen;
        Gate(long watermark) { this.watermark = watermark; }

        boolean accept(int kind, long entry, long now) { return check(kind, entry, now) == ACCEPT; }

        int check(int kind, long entry, long now) {
            if (kind < BAR || kind > ANY_KILL) return NOT_TRIGGER;
            if (kind == POWER) { powerSeen = true; lastPower = entry; }
            if (entry < watermark) return BEFORE_SESSION;
            if (now < entry) return FUTURE;
            if (now - entry > MAX_AGE) return STALE;
            if (kind == ANY_KILL) return ACCEPT; // A sweep is many kills milliseconds apart: each one counts, no debounce.
            if (seen[kind] && entry - last[kind] < DEBOUNCE) return DUPLICATE;
            // A double press also emits the single-press line; it is part of the double, never a screen-off request.
            if (kind == POWER_SINGLE && seen[POWER_DOUBLE] && entry >= last[POWER_DOUBLE] && entry - last[POWER_DOUBLE] < DOUBLE_PAIR)
                return DUPLICATE;
            // The power long press's own caller-1000 Breeno line is part of that press, never a home long press.
            if (kind == HOME && powerSeen && entry >= lastPower && entry - lastPower < POWER_TAIL) return DUPLICATE;
            seen[kind] = true; last[kind] = entry;
            return ACCEPT;
        }
    }

    /**
     * Google warm-up after a recents clear-all (main thread; elapsedRealtime ms). Device, 6 sweeps: the Google :interactor
     * kill comes late in the sweep and the last kill follows within 14 ms (largest gap between kills 52 ms). So the warm-up
     * starts once kills have been quiet for WARM_QUIET, at most WARM_MAX after the Google kill. Within WARM_INTERVAL of the
     * last warm-up, one more Google kill is warmed again (a re-kill right after warming); a further one is not (no loop).
     */
    static final long WARM_QUIET = 150, WARM_MAX = 500, WARM_INTERVAL = 3000;

    /**
     * Reader side: an ANY_KILL can only move a warm-up that is waiting, which happens at most WARM_MAX after a Google kill.
     * Other kills are dropped before they wake the main thread. googleKill: entry time of the last accepted GOOGLE_DIED, or -1.
     */
    static boolean killUseful(long entry, long googleKill) {
        return googleKill >= 0 && entry >= googleKill && entry - googleKill <= WARM_MAX;
    }
    static final class Warm {
        private long deadline = -1, lastWarm = Long.MIN_VALUE / 2;
        private boolean retryUsed;

        /** When to warm (elapsedRealtime), or -1: rate limited. A Google kill while already waiting keeps the first deadline. */
        long googleDied(long now) {
            if (deadline < 0) {
                if (now - lastWarm < WARM_INTERVAL) {
                    if (retryUsed) return -1;
                    retryUsed = true;
                } else retryUsed = false;
                deadline = now + WARM_MAX;
            }
            return Math.min(now + WARM_QUIET, deadline);
        }

        /** Any other kill while waiting restarts the quiet window, never past the deadline; -1 when not waiting. */
        long anyKill(long now) { return deadline < 0 ? -1 : Math.min(now + WARM_QUIET, deadline); }

        /** The warm-up ran. */
        void warmed(long now) { deadline = -1; lastWarm = now; }

        /** The wait ended without a warm-up (option off, power request pending, screen off). */
        void cancel() { deadline = -1; }
    }

    /** Main-thread recheck: a queued trigger delayed past the same age window (e.g. process freeze/thaw) is dropped. */
    static boolean deliverable(long entry, long now) { return now >= entry && now - entry <= MAX_AGE; }

    /**
     * Diagnostics pre-filter only (never a trigger): the known power tag with the "PowerKey:" prefix, or the known
     * ActivityManager tag with the Breeno action, from ANY writer/buffer/priority, so classifier rejections are visible.
     */
    static int candidate(byte[] p, int n) {
        if (!valid(p, n)) return NONE;
        int hdr = u16(p, 2), end = hdr + u16(p, 0), tag = hdr + 1, tagEnd = zero(p, tag, end);
        if (tagEnd >= end) return NONE;
        int message = tagEnd + 1, messageEnd = zero(p, message, end);
        if (same(p, tag, tagEnd, POWER_TAG)) return starts(p, message, messageEnd, POWER_PREFIX) ? POWER : NONE;
        if (same(p, tag, tagEnd, BAR_TAG)) return contains(p, message, messageEnd, BAR_ACTION) ? BAR : NONE;
        if (same(p, tag, tagEnd, KILL_TAG)) return contains(p, message, messageEnd, INTERACTOR) ? GOOGLE_DIED : NONE;
        if (same(p, tag, tagEnd, PRESS_TAG)) return starts(p, message, messageEnd, POWER_PREFIX) ? POWER_SINGLE : NONE;
        return NONE;
    }

    /** Header fields and policy results only: no tag, message or other payload text. */
    static String describe(byte[] p, int n, int candidate, int kind, int outcome, long now) {
        if (!valid(p, n)) return "candidate=" + KINDS[candidate] + " malformed";
        long entry = millis(p);
        return "candidate=" + KINDS[candidate] + " writer=" + i32(p, 24) + " lid=" + i32(p, 20) + " prio=" + (p[u16(p, 2)] & 0xff)
            + " hdr=" + u16(p, 2) + " age=" + (now - entry) + "ms class=" + KINDS[kind] + " gate=" + OUTCOMES[outcome];
    }

    private static final byte[] POWER_PREFIX = ascii("PowerKey:"), BAR_ACTION = ascii("ACTIVATE_SPEECH_ASSIST"),
        INTERACTOR = ascii("googlequicksearchbox:interactor");

    private static boolean starts(byte[] p, int from, int end, byte[] prefix) {
        return end - from >= prefix.length && same(p, from, from + prefix.length, prefix);
    }

    private static boolean contains(byte[] p, int from, int end, byte[] needle) {
        for (int i = from; i + needle.length <= end; i++) if (same(p, i, i + needle.length, needle)) return true;
        return false;
    }

    /** Bounded automatic approval window: never while locked/off, after a decline, or beyond the per-boot limit. */
    static boolean autoPrompt(boolean wanted, boolean permission, boolean live, boolean bridgeShowing,
            boolean interactive, boolean locked, boolean declined, int attempts) {
        return wanted && permission && !live && !bridgeShowing && interactive && !locked && !declined && attempts < AUTO_PROMPTS;
    }

    /** Accepted power press to delayed Gemini request: covers the rebind (350 ms), settle (500 ms) and scheduling slack. */
    static final long POWER_LAUNCH_AGE = 3000;

    /**
     * Screen rule shared by every power gate (press, late finish, final GeminiSession check): the display must be
     * interactive (this app never wakes it; on screen off only the power key's own wake qualifies), and a locked
     * keyguard is allowed only when lockedAllowed = locked-screen option at the press AND still on now.
     */
    static boolean powerScreen(boolean interactive, boolean locked, boolean lockedAllowed) {
        return interactive && (!locked || lockedAllowed);
    }

    /** Circle to Search (CtsSession): interactive and unlocked only, with no locked-screen option. */
    static boolean ctsScreen(boolean interactive, boolean locked) { return interactive && !locked; }

    /** Power gate at the press (age 0) and again before the delayed Gemini request (elapsed-realtime age since the press). */
    static boolean powerLaunch(boolean power, boolean granted, boolean interactive, boolean locked, boolean lockedAllowed,
            boolean active, long age) {
        return power && granted && powerScreen(interactive, locked, lockedAllowed) && active && age >= 0 && age <= POWER_LAUNCH_AGE;
    }
}
