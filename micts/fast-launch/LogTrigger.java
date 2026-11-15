package dev.padhelper.fastcts;

import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/**
 * Pure logd packet policy (no Android types). Only structural results leave this class:
 * a trigger kind, the writer UID and an entry time. Raw messages are never stored.
 */
final class LogTrigger {
    static final int NONE = 0, BAR = 1, POWER = 2;
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
    private static final int WARN = 5;
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
    static int classify(byte[] p, int n, int systemUiUid, int user) {
        if (!valid(p, n) || i32(p, 24) != SYSTEM_UID || i32(p, 20) != SYSTEM_LOG) return NONE;
        int hdr = u16(p, 2), end = hdr + u16(p, 0), tag = hdr + 1, tagEnd = zero(p, tag, end);
        if (tagEnd >= end) return NONE;
        int message = tagEnd + 1, messageEnd = zero(p, message, end);
        if (same(p, tag, tagEnd, POWER_TAG)) {
            int after = message + POWER_TEXT.length;
            return messageEnd >= after && same(p, message, after, POWER_TEXT)
                && (after == messageEnd || p[after] == ',') ? POWER : NONE;
        }
        if (p[hdr] != WARN || !same(p, tag, tagEnd, BAR_TAG)) return NONE;
        return bar(new String(p, message, messageEnd - message, StandardCharsets.UTF_8), systemUiUid, user) ? BAR : NONE;
    }

    /** ColorOS bar hold: the disabled Breeno service start requested by SystemUI (power uses caller 1000). */
    static boolean bar(String message, int systemUiUid, int user) {
        if (message == null || message.length() > 4096 || systemUiUid <= SYSTEM_UID || user < 0) return false;
        if (!message.startsWith("Unable to start service Intent { ")
            || !message.contains("act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST ")
            || !message.contains("cmp=com.heytap.speechassist/.core.SpeechService ")) return false;
        if (message.indexOf("mCallingUid=") != message.lastIndexOf("mCallingUid=")
            || message.indexOf(" U=") != message.lastIndexOf(" U=")) return false;
        Matcher origin = ORIGIN.matcher(message);
        try {
            return origin.find() && Integer.parseInt(origin.group(1)) == systemUiUid && Integer.parseInt(origin.group(2)) == user;
        } catch (NumberFormatException invalid) { return false; }
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
    private static final String[] KINDS = {"NONE", "BAR", "POWER"};

    /** Freshness, replay and duplicate-marker gate for one log session. Reader thread only. */
    static final class Gate {
        private final long watermark;
        private final long[] last = new long[3];
        private final boolean[] seen = new boolean[3];
        Gate(long watermark) { this.watermark = watermark; }

        boolean accept(int kind, long entry, long now) { return check(kind, entry, now) == ACCEPT; }

        int check(int kind, long entry, long now) {
            if (kind != BAR && kind != POWER) return NOT_TRIGGER;
            if (entry < watermark) return BEFORE_SESSION;
            if (now < entry) return FUTURE;
            if (now - entry > MAX_AGE) return STALE;
            if (seen[kind] && entry - last[kind] < DEBOUNCE) return DUPLICATE;
            seen[kind] = true; last[kind] = entry;
            return ACCEPT;
        }
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
        return NONE;
    }

    /** Header fields and policy results only: no tag, message or other payload text. */
    static String describe(byte[] p, int n, int candidate, int kind, int outcome, long now) {
        if (!valid(p, n)) return "candidate=" + KINDS[candidate] + " malformed";
        long entry = millis(p);
        return "candidate=" + KINDS[candidate] + " writer=" + i32(p, 24) + " lid=" + i32(p, 20) + " prio=" + (p[u16(p, 2)] & 0xff)
            + " hdr=" + u16(p, 2) + " age=" + (now - entry) + "ms class=" + KINDS[kind] + " gate=" + OUTCOMES[outcome];
    }

    private static final byte[] POWER_PREFIX = ascii("PowerKey:"), BAR_ACTION = ascii("ACTIVATE_SPEECH_ASSIST");

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
}
