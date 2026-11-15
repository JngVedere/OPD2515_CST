package dev.padhelper.fastcts;

import java.util.regex.*;

/** Binder transport constants; transactions carry only epochs and monotonic times, never raw system log lines. */
final class NativeBarProtocol {
    // 3: Binder registration through a DUMP-protected provider (shell could not connect to the abstract socket).
    static final int VERSION = 3;
    static final String AUTHORITY = "dev.padhelper.fastcts.nativebar", REGISTER = "register";
    static final String DESCRIPTOR = AUTHORITY + ".v3";
    static final String KEY_PROTOCOL = "protocol", KEY_VERSION = "version", KEY_CALLBACK = "callback", KEY_EVENTS = "events", KEY_RESULT = "result";
    static final int OK = 0, REJECT = 1, UNAVAILABLE = 2;
    // App -> agent (oneway): RUN/PAUSE carry an epoch; DETACH = transient (reconnect), QUIT = explicit stop.
    static final int RUN = 1, PAUSE = 2, DETACH = 3, QUIT = 4;
    // Agent -> app (oneway): RUNNING/PAUSED carry an epoch; HOLD carries epoch + elapsedRealtime.
    static final int RUNNING = 1, PAUSED = 2, HOLD = 3, READY = 4;
    private static final Pattern RECORD = Pattern.compile("^\\s*(\\d{9,}\\.\\d+)\\s+\\d+\\s+\\d+\\s+W\\s+ActivityManager\\s*:\\s*(.*)$");
    private static final Pattern ORIGIN = Pattern.compile("\\s+mCallingUid=(\\d+)\\s+\\}\\s+U=(\\d+): not found\\s*$");

    static boolean isGesture(String line, int systemUiUid, int user, long watermark, long now) {
        if (line == null || line.length() > 4096 || systemUiUid <= 1000) return false;
        Matcher record = RECORD.matcher(line);
        if (!record.matches()) return false;
        try {
            double millis = Double.parseDouble(record.group(1)) * 1000;
            if (!Double.isFinite(millis) || millis >= Long.MAX_VALUE) return false;
            long time = (long) millis;
            if (time < watermark || now < time || now - time > 1500) return false;
            String message = record.group(2);
            if (!message.startsWith("Unable to start service Intent { ")
                || !message.contains("act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST ")
                || !message.contains("cmp=com.heytap.speechassist/.core.SpeechService ")) return false;
            if (message.indexOf("mCallingUid=") != message.lastIndexOf("mCallingUid=")
                || message.indexOf(" U=") != message.lastIndexOf(" U=")) return false;
            Matcher origin = ORIGIN.matcher(message);
            return origin.find() && Integer.parseInt(origin.group(1)) == systemUiUid
                && Integer.parseInt(origin.group(2)) == user;
        } catch (NumberFormatException invalid) { return false; }
    }

    /** Controls must carry a strictly newer epoch; anything else is a replay. */
    static boolean newer(long epoch, long last) { return epoch > last; }

    static boolean freshHold(long event, long now) { return event >= 0 && now >= event && now - event <= 1500; }
}
