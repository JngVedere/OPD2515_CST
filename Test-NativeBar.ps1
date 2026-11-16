# Compiles the production logd packet classifier, health rule, timing gate, approval bound and settings
# status/last-attempt policy (StatusPolicy) with only the JDK.
# No SDK, ADB, device, socket or Gemini runtime; logd transport and UI still require device tests.
$ErrorActionPreference = 'Stop'
$qa = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'qa'))
$folder = [IO.Path]::GetFullPath((Join-Path $qa ('native-bar-test-'+[guid]::NewGuid().ToString('N'))))
try {
    [void][IO.Directory]::CreateDirectory($folder)
    $harness = Join-Path $folder 'LogTriggerHarness.java'
    $source = @'
package dev.padhelper.fastcts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class LogTriggerHarness {
    private static int checks, failures;
    private static final long TIME = 1763160000000L;
    // LAUNCHER: test fixture for the UID BarService resolves at runtime for the verified com.android.launcher (observed 10156).
    private static final int SYSTEM_UI = 10160, LAUNCHER = 10156, APP = 10234, V = 2, I = 4, W = 5;
    private static final String POWER_TAG = "KEYLOG_SinglePowerKeyMonitor";
    private static final String POWER_TEXT = "PowerKey:onLongPress, underWaterCameraStatus = false";
    private static final String AM = "ActivityManager";
    private static String breeno(int caller, int user) {
        return "Unable to start service Intent { act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST flg=0x10000000 xflg=0x4 cmp=com.heytap.speechassist/.core.SpeechService (has extras) mCallingUid=" + caller + " } U=" + user + ": not found";
    }
    private static void check(boolean result, String description) {
        checks++;
        if (!result) { failures++; System.err.println("FAIL: " + description); }
    }
    private static byte[] raw(int header, int lid, int uid, long millis, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(header + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) payload.length).putShort((short) header).putInt(3176).putInt(3299)
            .putInt((int) (millis / 1000)).putInt((int) (millis % 1000 * 1_000_000)).putInt(lid).putInt(uid);
        b.position(header); b.put(payload);
        return b.array();
    }
    private static byte[] payload(int priority, String tag, String message) {
        byte[] t = tag.getBytes(StandardCharsets.UTF_8), m = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(t.length + m.length + 3);
        b.put((byte) priority).put(t).put((byte) 0).put(m).put((byte) 0);
        return b.array();
    }
    private static byte[] entry(int lid, int uid, long millis, int priority, String tag, String message) {
        return raw(28, lid, uid, millis, payload(priority, tag, message));
    }
    private static byte[] system(int priority, String tag, String message) { return entry(3, 1000, TIME, priority, tag, message); }
    private static int kind(byte[] p) { return LogTrigger.classify(p, p.length, SYSTEM_UI, LAUNCHER, 0); }

    public static void main(String[] args) {
        check(LogTrigger.COMMAND.equals("stream lids=3 tail=1"), "One logd stream reads only the system buffer from the newest entry");

        byte[] power = system(V, POWER_TAG, POWER_TEXT);
        check(kind(power) == LogTrigger.POWER, "Observed OPD2515 power long-press marker is POWER");
        check(kind(system(V, POWER_TAG, "PowerKey:onLongPress")) == LogTrigger.POWER, "Bare power marker message is POWER");
        check(kind(system(I, POWER_TAG, POWER_TEXT)) == LogTrigger.POWER, "Power marker priority is not the trust anchor");
        check(kind(system(V, POWER_TAG, "PowerKey:onLongPressed")) == LogTrigger.NONE, "Power message prefixes cannot match");
        check(kind(system(V, POWER_TAG, "onKeyGesture, eventType = 1 action=1 pressCount=0")) == LogTrigger.NONE, "Other power-key monitor lines are ignored");
        check(kind(system(V, POWER_TAG, " PowerKey:onLongPress")) == LogTrigger.NONE, "Power marker must start the message");
        check(kind(system(V, POWER_TAG + "X", POWER_TEXT)) == LogTrigger.NONE, "Power tag suffixes cannot match");
        check(kind(system(V, "XKEYLOG_SinglePowerKeyMonitor", POWER_TEXT)) == LogTrigger.NONE, "Power tag prefixes cannot match");
        check(kind(system(V, "KEYLOG_SingleKeyGesture", POWER_TEXT)) == LogTrigger.NONE, "Neighbouring KEYLOG tags cannot trigger");
        for (int uid : new int[]{0, 1001, 2000, APP, SYSTEM_UI, 1000 + 100000})
            check(kind(entry(3, uid, TIME, V, POWER_TAG, POWER_TEXT)) == LogTrigger.NONE, "Power marker written by UID " + uid + " is untrusted");
        for (int lid : new int[]{0, 1, 2, 4, 5, 7})
            check(kind(entry(lid, 1000, TIME, V, POWER_TAG, POWER_TEXT)) == LogTrigger.NONE, "Power marker outside the system buffer (lid " + lid + ") is rejected");

        // Observed OPD2515 after recents "clear all": system_server UserAwareMgr INFO line for Google's :interactor.
        String gsa = "com.google.android.googlequicksearchbox", killTag = "UserAwareMgr";
        String killed = "process killed: {pkgName='" + gsa + "', serviceName='', procName=" + gsa + ":interactor, pid=30058, uid=10371, appId=0, owner='null', flags='fg1', renderTid=0, hwuiTids=null, glTids=null, threadInfos=null, mVideoActivityList=[], mStates=0}";
        check(kind(system(I, killTag, killed)) == LogTrigger.GOOGLE_DIED, "Observed Google :interactor kill is GOOGLE_DIED");
        check(kind(system(I, killTag, killed.replace(":interactor", ":search"))) == LogTrigger.NONE, "Other Google processes are ignored");
        check(kind(system(I, killTag, killed.replace("pkgName='" + gsa, "pkgName='com.example.app"))) == LogTrigger.NONE, "Another package naming the interactor is ignored");
        check(kind(system(I, killTag, killed + " procName=x")) == LogTrigger.NONE, "A second procName is rejected");
        check(kind(system(W, killTag, killed)) == LogTrigger.NONE, "Kill line must be INFO");
        check(kind(system(I, killTag + "X", killed)) == LogTrigger.NONE, "Kill tag suffixes cannot match");
        check(kind(entry(3, APP, TIME, I, killTag, killed)) == LogTrigger.NONE, "An app writing a forged kill line is rejected");
        check(kind(entry(0, 1000, TIME, I, killTag, killed)) == LogTrigger.NONE, "Kill line outside the system buffer is rejected");
        byte[] kill = system(I, killTag, killed);
        check(LogTrigger.candidate(kill, kill.length) == LogTrigger.GOOGLE_DIED, "Kill line is a diagnostic candidate");
        LogTrigger.Gate killGate = new LogTrigger.Gate(TIME);
        check(killGate.accept(LogTrigger.GOOGLE_DIED, TIME, TIME + 1) && !killGate.accept(LogTrigger.GOOGLE_DIED, TIME + 5, TIME + 6)
            && killGate.accept(LogTrigger.POWER, TIME + 5, TIME + 6), "GOOGLE_DIED is debounced on its own, never blocking POWER");

        check(kind(system(W, AM, breeno(SYSTEM_UI, 0))) == LogTrigger.BAR, "Observed SystemUI failed Breeno start is BAR");
        check(kind(system(W, AM, breeno(1000, 0))) == LogTrigger.NONE, "The power-key tail with caller 1000 never triggers the bar");
        check(kind(system(I, AM, breeno(SYSTEM_UI, 0))) == LogTrigger.NONE, "Bar record must be a warning");
        check(kind(system(W, "ActivityTaskManager", breeno(SYSTEM_UI, 0))) == LogTrigger.NONE, "Bar record needs the exact ActivityManager tag");
        check(kind(entry(3, APP, TIME, W, AM, breeno(SYSTEM_UI, 0))) == LogTrigger.NONE, "An app writing a forged ActivityManager line is rejected");
        check(kind(entry(0, 1000, TIME, W, AM, breeno(SYSTEM_UI, 0))) == LogTrigger.NONE, "Bar record outside the system buffer is rejected");
        check(kind(system(W, AM, breeno(SYSTEM_UI, 10))) == LogTrigger.NONE, "Other target user is rejected");
        check(LogTrigger.classify(system(W, AM, breeno(1010160, 10)), system(W, AM, breeno(1010160, 10)).length, 1010160, 1010156, 10) == LogTrigger.BAR, "Matching secondary-user SystemUI is supported");
        check(LogTrigger.classify(system(W, AM, breeno(SYSTEM_UI, 0)), system(W, AM, breeno(SYSTEM_UI, 0)).length, -1, LAUNCHER, 0) == LogTrigger.NONE, "Unresolved SystemUI UID never matches");
        check(LogTrigger.classify(system(W, AM, breeno(SYSTEM_UI, 0)), system(W, AM, breeno(SYSTEM_UI, 0)).length, SYSTEM_UI, -1, 0) == LogTrigger.BAR, "SystemUI still matches when no trusted launcher was resolved");

        // Taskbar ON: the verified system launcher (UID resolved at runtime) reports the bar hold. Observed OPD2515 record (no flg field).
        String taskbarLine = "Unable to start service Intent { act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST xflg=0x4 cmp=com.heytap.speechassist/.core.SpeechService (has extras) mCallingUid=10156 } U=0: not found";
        check(kind(system(W, AM, taskbarLine)) == LogTrigger.BAR, "Observed taskbar launcher failed Breeno start is BAR");
        check(kind(system(W, AM, breeno(LAUNCHER, 0))) == LogTrigger.BAR, "Trusted launcher caller is BAR");
        byte[] taskbarPacket = system(W, AM, breeno(LAUNCHER, 0));
        check(LogTrigger.classify(taskbarPacket, taskbarPacket.length, SYSTEM_UI, -1, 0) == LogTrigger.NONE, "Unresolved/untrusted launcher UID never matches");
        check(LogTrigger.classify(taskbarPacket, taskbarPacket.length, -1, -1, 0) == LogTrigger.NONE, "Nothing resolved: no bar caller matches");
        for (int caller : new int[]{APP, 10155, 10157, 0, 1000, 2000})
            check(kind(system(W, AM, breeno(caller, 0))) == LogTrigger.NONE, "Caller UID " + caller + " that is neither SystemUI nor the trusted launcher is rejected");
        for (int writer : new int[]{0, 2000, APP, SYSTEM_UI, LAUNCHER, 1000 + 100000})
            check(kind(entry(3, writer, TIME, W, AM, breeno(LAUNCHER, 0))) == LogTrigger.NONE, "Launcher bar line written by UID " + writer + " is untrusted");
        for (int lid : new int[]{0, 1, 2, 4, 5, 7})
            check(kind(entry(lid, 1000, TIME, W, AM, breeno(LAUNCHER, 0))) == LogTrigger.NONE, "Launcher bar line outside the system buffer (lid " + lid + ") is rejected");
        check(kind(system(W, "ActivityTaskManager", breeno(LAUNCHER, 0))) == LogTrigger.NONE, "Launcher bar line needs the exact ActivityManager tag");
        for (int priority : new int[]{V, 3, I, 6})
            check(kind(system(priority, AM, breeno(LAUNCHER, 0))) == LogTrigger.NONE, "Launcher bar line must be a warning (priority " + priority + ")");
        check(kind(system(W, AM, breeno(LAUNCHER, 10))) == LogTrigger.NONE, "Launcher line for another user is rejected");
        byte[] secondaryTaskbar = system(W, AM, breeno(1010156, 10));
        check(LogTrigger.classify(secondaryTaskbar, secondaryTaskbar.length, 1010160, 1010156, 10) == LogTrigger.BAR, "Matching secondary-user launcher is supported");
        check(LogTrigger.classify(secondaryTaskbar, secondaryTaskbar.length, SYSTEM_UI, LAUNCHER, 0) == LogTrigger.NONE, "Secondary-user launcher does not match the primary user's UIDs");
        byte[] primaryOnSecondary = system(W, AM, breeno(LAUNCHER, 10));
        check(LogTrigger.classify(primaryOnSecondary, primaryOnSecondary.length, 1010160, 1010156, 10) == LogTrigger.NONE, "Primary launcher UID with a secondary-user target is rejected");
        for (int uid : new int[]{-1, 0, 999, 1000})
            check(!LogTrigger.bar(breeno(uid, 0), -1, uid, 0) && !LogTrigger.bar(breeno(uid, 0), uid, uid, 0), "Invalid configured launcher UID " + uid + " never matches");
        String launcherGood = breeno(LAUNCHER, 0);
        check(LogTrigger.bar(launcherGood, -1, LAUNCHER, 0), "Launcher-only configuration accepts the launcher caller");
        check(!LogTrigger.bar(launcherGood.replace("(has extras) ", "(has extras) mCallingUid=10156 ").replace("mCallingUid=10156 }", "mCallingUid=" + APP + " }"), SYSTEM_UI, LAUNCHER, 0), "Embedded launcher caller cannot spoof the authoritative caller");
        check(!LogTrigger.bar(launcherGood.replace("(has extras) ", "(has extras) mCallingUid=" + APP + " "), SYSTEM_UI, LAUNCHER, 0), "Duplicate caller fields are rejected");
        check(!LogTrigger.bar(launcherGood.replace("(has extras) ", "(has extras) U=0: "), SYSTEM_UI, LAUNCHER, 0), "Duplicate user fields are rejected for the launcher");
        check(!LogTrigger.bar(launcherGood.replace("cmp=com.heytap.speechassist/", "cmp=com.android.launcher/"), SYSTEM_UI, LAUNCHER, 0), "Launcher caller with another component is rejected");
        check(!LogTrigger.bar(launcherGood.replace("ACTIVATE_SPEECH_ASSIST ", "ACTIVATE_SPEECH_ASSIST_EVIL "), SYSTEM_UI, LAUNCHER, 0), "Launcher caller with another action is rejected");
        check(!LogTrigger.bar(launcherGood + " trailing text", SYSTEM_UI, LAUNCHER, 0), "Text after the launcher origin is rejected");
        check(!LogTrigger.bar(launcherGood.replace("mCallingUid=10156", "mCallingUid=999999999999999999999"), SYSTEM_UI, LAUNCHER, 0), "Launcher caller overflow is rejected");
        LogTrigger.Gate taskbarGate = new LogTrigger.Gate(TIME);
        int taskbarAccepted = 0;
        for (long at : new long[]{TIME - 1, TIME + 10, TIME + 13, TIME + 1509, TIME + 1510})
            if (taskbarGate.accept(kind(entry(3, 1000, at, W, AM, breeno(LAUNCHER, 0))), at, at + 5)) taskbarAccepted++;
        check(taskbarAccepted == 2, "Launcher bar holds keep watermark, duplicate and 1500ms rules: " + taskbarAccepted);
        check(!new LogTrigger.Gate(TIME).accept(kind(system(W, AM, breeno(LAUNCHER, 0))), TIME, TIME + 1501), "Stale launcher bar entries are rejected");

        String good = breeno(SYSTEM_UI, 0);
        check(LogTrigger.bar(good, SYSTEM_UI, LAUNCHER, 0), "Bar message parser accepts the observed record");
        for (int uid : new int[]{-1, 0, 999, 1000})
            check(!LogTrigger.bar(good, uid, LAUNCHER, 0), "Invalid configured SystemUI UID " + uid + " is rejected");
        check(!LogTrigger.bar(good, SYSTEM_UI, LAUNCHER, -1), "Invalid configured user is rejected");
        check(!LogTrigger.bar(good.replace("act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST ", ""), SYSTEM_UI, LAUNCHER, 0), "Missing action is rejected");
        check(!LogTrigger.bar(good.replace("ACTIVATE_SPEECH_ASSIST ", "ACTIVATE_SPEECH_ASSIST_EVIL "), SYSTEM_UI, LAUNCHER, 0), "Action prefixes cannot match");
        check(!LogTrigger.bar(good.replace(".core.SpeechService ", ".core.SpeechServiceEvil "), SYSTEM_UI, LAUNCHER, 0), "Component prefixes cannot match");
        check(!LogTrigger.bar(good.replace("cmp=com.heytap.speechassist/", "cmp=example.untrusted/"), SYSTEM_UI, LAUNCHER, 0), "Other component package is rejected");
        check(!LogTrigger.bar(good.replace("Unable to start service", "Starting service"), SYSTEM_UI, LAUNCHER, 0), "Different service message is rejected");
        check(!LogTrigger.bar(good.replace("not found", "permission denied"), SYSTEM_UI, LAUNCHER, 0), "Other service failures are rejected");
        check(!LogTrigger.bar(good.replace(" } U=0", " U=0"), SYSTEM_UI, LAUNCHER, 0), "Missing intent close delimiter is rejected");
        check(!LogTrigger.bar(good.replace("mCallingUid=10160", "mCallingUid=999999999999999999999"), SYSTEM_UI, LAUNCHER, 0), "Caller integer overflow is rejected");
        check(!LogTrigger.bar(good.replace("U=0:", "U=999999999999999999999:"), SYSTEM_UI, LAUNCHER, 0), "User integer overflow is rejected");
        check(!LogTrigger.bar(good.replace("(has extras) ", "(has extras) mCallingUid=10160 ").replace("mCallingUid=10160 }", "mCallingUid=10000 }"), SYSTEM_UI, LAUNCHER, 0), "Embedded caller cannot spoof the authoritative caller");
        check(!LogTrigger.bar(good.replace("(has extras) ", "(has extras) U=0: "), SYSTEM_UI, LAUNCHER, 0), "Duplicate user fields are rejected");
        check(!LogTrigger.bar(good + " trailing text", SYSTEM_UI, LAUNCHER, 0), "Text after the authoritative origin is rejected");
        check(!LogTrigger.bar(null, SYSTEM_UI, LAUNCHER, 0), "Null message is rejected");
        check(LogTrigger.bar(good + " ".repeat(4096 - good.length()), SYSTEM_UI, LAUNCHER, 0), "4096-character message is accepted");
        check(!LogTrigger.bar(good + " ".repeat(4097 - good.length()), SYSTEM_UI, LAUNCHER, 0), "Oversized message is rejected");

        check(LogTrigger.uid(power, power.length) == 1000, "Writer UID is read from the logd header");
        check(LogTrigger.millis(power) == TIME, "Entry time is read from the logd header");
        byte[] tail = system(I, "PowerManagerService", "anything");
        check(LogTrigger.systemEntry(tail, tail.length), "Any system-UID entry proves privileged access (tail entry)");
        byte[] heartbeat = entry(0, APP, TIME, I, "PadLog", "HEARTBEAT 1");
        check(!LogTrigger.systemEntry(heartbeat, heartbeat.length), "Own-UID heartbeat never counts as privileged-log health");
        byte[] otherApp = entry(3, SYSTEM_UI, TIME, I, "SystemUi", "x");
        check(!LogTrigger.systemEntry(otherApp, otherApp.length), "Another app UID does not prove system-log access");
        check(!LogTrigger.systemEntry(power, 27), "Truncated packet is not health");
        check(LogTrigger.uid(null, 0) == -1, "Null packet is malformed");
        check(LogTrigger.uid(power, power.length + 1) == -1, "Declared length beyond the buffer is malformed");
        check(LogTrigger.uid(raw(24, 3, 1000, TIME, payload(V, POWER_TAG, POWER_TEXT)), 24 + payload(V, POWER_TAG, POWER_TEXT).length) == -1, "Pre-v4 headers without a writer UID are rejected");
        byte[] wide = raw(32, 3, 1000, TIME, payload(V, POWER_TAG, POWER_TEXT));
        check(kind(wide) == LogTrigger.POWER, "A larger future header keeps the v4 fields");
        byte[] overflow = power.clone(); overflow[0] = (byte) 0xff; overflow[1] = (byte) 0x0f;
        check(kind(overflow) == LogTrigger.NONE && LogTrigger.uid(overflow, overflow.length) == -1, "Payload length beyond the packet is rejected");
        check(kind(raw(28, 3, 1000, TIME, new byte[]{V, 'K', 'E', 'Y'})) == LogTrigger.NONE, "Unterminated tag without message is rejected");
        byte[] big = entry(3, 1000, TIME, V, POWER_TAG, POWER_TEXT + " ".repeat(5200));
        check(kind(big) == LogTrigger.NONE && LogTrigger.uid(big, big.length) == -1, "Packets above logd's entry limit are rejected");

        LogTrigger.Gate gate = new LogTrigger.Gate(TIME);
        check(!gate.accept(LogTrigger.NONE, TIME, TIME), "Non-trigger kinds never pass");
        check(!gate.accept(LogTrigger.POWER, TIME - 1, TIME), "Replayed tail entries before the session watermark are rejected");
        check(!gate.accept(LogTrigger.POWER, TIME + 10, TIME + 5), "Future entries are rejected");
        check(!gate.accept(LogTrigger.POWER, TIME, TIME + 1501), "Stale entries beyond 1500ms are rejected");
        check(gate.accept(LogTrigger.POWER, TIME, TIME + 1500), "The 1500ms age boundary is accepted");
        check(!gate.accept(LogTrigger.POWER, TIME + 3, TIME + 10), "Duplicate power marker 3ms later is suppressed");
        check(!gate.accept(LogTrigger.POWER, TIME, TIME + 10), "Same entry replayed is suppressed");
        check(!gate.accept(LogTrigger.POWER, TIME + 1499, TIME + 1500), "A marker 1499ms later is suppressed");
        check(gate.accept(LogTrigger.BAR, TIME + 1, TIME + 10), "Bar and power duplicate windows are independent");
        check(!gate.accept(LogTrigger.BAR, TIME, TIME + 10), "Out-of-order older bar entries are rejected");
        check(gate.accept(LogTrigger.POWER, TIME + 1500, TIME + 1600), "A new long press 1500ms later is accepted");

        LogTrigger.Gate codes = new LogTrigger.Gate(TIME);
        check(codes.check(LogTrigger.NONE, TIME, TIME) == LogTrigger.NOT_TRIGGER, "Gate reports NOT_TRIGGER");
        check(codes.check(LogTrigger.POWER, TIME - 1, TIME) == LogTrigger.BEFORE_SESSION, "Gate reports BEFORE_SESSION");
        check(codes.check(LogTrigger.POWER, TIME + 5, TIME) == LogTrigger.FUTURE, "Gate reports FUTURE");
        check(codes.check(LogTrigger.POWER, TIME, TIME + 1501) == LogTrigger.STALE, "Gate reports STALE");
        check(codes.check(LogTrigger.POWER, TIME, TIME + 1) == LogTrigger.ACCEPT, "Gate reports ACCEPT");
        check(codes.check(LogTrigger.POWER, TIME + 3, TIME + 4) == LogTrigger.DUPLICATE, "Gate reports DUPLICATE");

        check(LogTrigger.deliverable(TIME, TIME), "Immediate main-thread delivery is allowed");
        check(LogTrigger.deliverable(TIME, TIME + 1500), "Main-thread delivery at the 1500ms boundary is allowed");
        check(!LogTrigger.deliverable(TIME, TIME + 1501), "A trigger queued past 1500ms (freeze/thaw) is dropped");
        check(!LogTrigger.deliverable(TIME, TIME + 60000), "A gesture released after a long freeze never fires");
        check(!LogTrigger.deliverable(TIME + 1, TIME), "A future-stamped queued trigger is dropped");
        check(!LogTrigger.deliverable(TIME, Long.MAX_VALUE), "Huge delivery delay does not wrap into a fresh trigger");
        check(LogTrigger.candidate(power, power.length) == LogTrigger.POWER, "Power marker is a diagnostic candidate");
        byte[] forged = entry(0, APP, TIME, V, POWER_TAG, POWER_TEXT);
        check(LogTrigger.candidate(forged, forged.length) == LogTrigger.POWER && kind(forged) == LogTrigger.NONE, "Untrusted look-alikes are visible as candidates but never classified");
        byte[] gesture = system(V, POWER_TAG, "onKeyGesture, eventType = 1 action=1 pressCount=0");
        check(LogTrigger.candidate(gesture, gesture.length) == LogTrigger.NONE, "Ordinary key-gesture lines are not diagnostic candidates");
        byte[] amOther = system(W, AM, "Slow operation: 120ms");
        check(LogTrigger.candidate(amOther, amOther.length) == LogTrigger.NONE, "Unrelated ActivityManager lines are not candidates");
        byte[] tailLine = system(W, AM, breeno(1000, 0));
        check(LogTrigger.candidate(tailLine, tailLine.length) == LogTrigger.BAR && kind(tailLine) == LogTrigger.NONE, "Power tail is a visible BAR candidate but rejected");
        String described = LogTrigger.describe(power, power.length, LogTrigger.POWER, LogTrigger.POWER, LogTrigger.ACCEPT, TIME + 12);
        check(described.equals("candidate=POWER writer=1000 lid=3 prio=2 hdr=28 age=12ms class=POWER gate=ACCEPT"), "Diagnostics carry header fields and outcomes: " + described);
        String forgedText = LogTrigger.describe(forged, forged.length, LogTrigger.POWER, LogTrigger.NONE, LogTrigger.NOT_TRIGGER, TIME);
        check(!forgedText.contains("KEYLOG") && !forgedText.contains("PowerKey") && !forgedText.contains("underWater"), "Diagnostics never include tag or message text");
        check(LogTrigger.describe(power, 10, LogTrigger.POWER, LogTrigger.NONE, LogTrigger.NOT_TRIGGER, TIME).endsWith("malformed"), "Malformed packets are described structurally");
        check(LogTrigger.trailing(power, power.length) == 0, "A single-entry read has no trailing bytes");
        byte[] two = new byte[power.length + gesture.length];
        System.arraycopy(power, 0, two, 0, power.length); System.arraycopy(gesture, 0, two, power.length, gesture.length);
        check(LogTrigger.trailing(two, two.length) == gesture.length, "A coalesced read is detected by its trailing bytes");

        // Physical-press sequence observed on OPD2515: two identical markers 3ms apart and the caller-1000 tail.
        LogTrigger.Gate observed = new LogTrigger.Gate(TIME);
        int accepted = 0;
        byte[][] sequence = {
            entry(3, 1000, TIME + 511, V, POWER_TAG, POWER_TEXT),
            entry(3, 1000, TIME + 512, W, AM, breeno(1000, 0)),
            entry(3, 1000, TIME + 514, V, POWER_TAG, POWER_TEXT)
        };
        for (byte[] packet : sequence) {
            int k = kind(packet);
            if (k != LogTrigger.NONE && observed.accept(k, LogTrigger.millis(packet), TIME + 600)) accepted++;
        }
        check(accepted == 1, "One physical power long press produces exactly one trigger");

        for (int bits = 0; bits < 128; bits++) {
            boolean expected = bits == 0b0010011;
            check(LogTrigger.autoPrompt((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0,
                (bits & 16) != 0, (bits & 32) != 0, (bits & 64) != 0, 0) == expected, "Automatic approval gate case " + bits);
        }
        check(LogTrigger.autoPrompt(true, true, false, false, true, false, false, LogTrigger.AUTO_PROMPTS - 1), "Last automatic attempt is allowed");
        check(!LogTrigger.autoPrompt(true, true, false, false, true, false, false, LogTrigger.AUTO_PROMPTS), "Automatic approval windows are bounded per boot");
        check(LogTrigger.AUTO_PROMPTS == 3 && LogTrigger.ACTIVATION_TIMEOUT == 35000, "Recovery bounds stay explicit");

        // Power gate (press, late finish): power option, own a11y consent, screen rule, live session, bounded age.
        // Truth table: option(snapshot && current) x interactive x locked; OFF keeps the old unlocked-interactive rule.
        for (int bits = 0; bits < 64; bits++) {
            boolean p = (bits & 1) != 0, g = (bits & 2) != 0, on = (bits & 4) != 0, lk = (bits & 8) != 0, opt = (bits & 16) != 0, a = (bits & 32) != 0;
            boolean expected = p && g && a && on && (!lk || opt);
            check(LogTrigger.powerLaunch(p, g, on, lk, opt, a, 900) == expected, "Power launch gate case " + bits);
            check(LogTrigger.powerScreen(on, lk, opt) == (on && (!lk || opt)), "Power screen rule case " + bits);
        }
        check(LogTrigger.powerLaunch(true, true, true, false, false, true, 900), "Option OFF: unlocked interactive screen still allowed (old behaviour)");
        check(!LogTrigger.powerLaunch(true, true, true, true, false, true, 900), "Option OFF: locked screen stays blocked");
        check(LogTrigger.powerLaunch(true, true, true, true, true, true, 900), "Option ON: locked screen woken by the power key is allowed");
        check(!LogTrigger.powerLaunch(true, true, false, true, true, true, 900) && !LogTrigger.powerLaunch(true, true, false, false, true, true, 900),
            "Option ON: a screen that is still off is never allowed (the app does not wake it)");
        check(!LogTrigger.powerLaunch(false, true, true, true, true, true, 900) && !LogTrigger.powerLaunch(false, true, true, false, true, true, 900),
            "Power option OFF disables the locked-screen sub-option");
        check(!LogTrigger.powerLaunch(true, true, true, true, true, false, 900), "Option ON: no live log session (before first unlock / approval) is never allowed");
        // Snapshot && current: a request pressed with the option OFF is not widened by switching it ON, and vice versa.
        for (boolean snapshot : new boolean[]{false, true})
            for (boolean current : new boolean[]{false, true})
                check(LogTrigger.powerLaunch(true, true, true, true, snapshot && current, true, 900) == (snapshot && current),
                    "Locked request needs the option at press and at launch " + snapshot + "/" + current);
        check(LogTrigger.powerLaunch(true, true, true, false, false, true, LogTrigger.POWER_LAUNCH_AGE), "Power launch at the age boundary is allowed");
        check(!LogTrigger.powerLaunch(true, true, true, false, false, true, LogTrigger.POWER_LAUNCH_AGE + 1), "A request older than the bound (freeze/slow rebind) never opens Gemini");
        check(!LogTrigger.powerLaunch(true, true, true, true, true, true, LogTrigger.POWER_LAUNCH_AGE + 1), "Locked requests share the age bound");
        check(!LogTrigger.powerLaunch(true, true, true, false, false, true, -1), "A negative request age is rejected");
        check(LogTrigger.POWER_LAUNCH_AGE == 3000, "Power launch age bound stays explicit");

        // Settings readiness: "사용 중"/"감지 중" only when every prerequisite of that pathway holds; never a window claim.
        final int NONE = StatusPolicy.GOOGLE_NONE, GONE = StatusPolicy.GOOGLE_UNAVAILABLE, OK = StatusPolicy.GOOGLE_READY;
        final int SHOWN = StatusPolicy.GESTURE_SHOWN, HIDDEN = StatusPolicy.GESTURE_HIDDEN, UNKNOWN = StatusPolicy.GESTURE_UNKNOWN,
            TASKBAR = StatusPolicy.GESTURE_TASKBAR;
        for (int visibility : new int[]{SHOWN, HIDDEN, UNKNOWN, TASKBAR})
        for (int google : new int[]{NONE, GONE, OK})
            for (int bits = 0; bits < 2048; bits++) {
                boolean[] b = new boolean[11];
                for (int i = 0; i < 11; i++) b[i] = (bits & (1 << i)) != 0;
                boolean approved = b[0], enabled = b[1], live = b[2], nat = b[3], wss = b[4], breeno = b[5], logs = b[6],
                    active = b[7], waiting = b[8], ready = b[9], cts = b[10];
                String s = StatusPolicy.bar(approved, enabled, live, nat, wss, breeno, logs, active, waiting, ready, cts, google, visibility);
                boolean ok = approved && enabled && live && ready && (!nat || (wss && breeno && logs && active && (visibility == SHOWN || visibility == TASKBAR))) && (!cts || google == OK);
                check(s.startsWith("사용 중") == ok && (!ok || !cts || s.contains("실제 연결 상태는 확인 불가"))
                    && !s.contains("떴") && !s.contains("열렸") && !s.contains("표시됨"), "Bottom readiness case " + visibility + "/" + google + "/" + bits + ": " + s);
                // Native + on + hidden (taskbar not on) is always the explicit error; taskbar ON never is; the touch area ignores both.
                check(s.startsWith("오류") == (approved && enabled && live && nat && visibility == HIDDEN), "Hidden gesture bar error case " + visibility + "/" + google + "/" + bits + ": " + s);
                check(s.contains("작업 표시줄") == (ok && nat && visibility == TASKBAR), "Taskbar note only on a ready native bar " + visibility + "/" + google + "/" + bits + ": " + s);
                if (!nat) check(s.equals(StatusPolicy.bar(approved, enabled, live, nat, wss, breeno, logs, active, waiting, ready, cts, google, SHOWN)),
                    "Touch area ignores the gesture bar " + visibility + "/" + google + "/" + bits);
            }
        // Taskbar OFF/unknown (anything but exactly "1", incl. unreadable null) keeps the old gesture-bar rules.
        for (String taskbarOff : new String[]{null, "0", "", "2", " 1", "1 ", "true", "01"}) {
            check(StatusPolicy.gestureBar("0", taskbarOff) == SHOWN && StatusPolicy.gestureBar("1", taskbarOff) == HIDDEN,
                "navbar_gesture_visibility: 0 shown, 1 hidden with taskbar " + taskbarOff);
            for (String raw : new String[]{null, "", "2", "-1", " 1", "1 ", "true", "01"})
                check(StatusPolicy.gestureBar(raw, taskbarOff) == UNKNOWN, "Missing/unrecognized navbar_gesture_visibility is unknown: " + raw + "/" + taskbarOff);
        }
        for (String raw : new String[]{"0", "1", null, "2"})
            check(StatusPolicy.gestureBar(raw, "1") == TASKBAR, "Taskbar ON: the launcher bar reports the hold whatever navbar_gesture_visibility is: " + raw);
        String taskbarHidden = StatusPolicy.bar(true, true, true, true, true, true, true, true, false, true, true, OK, StatusPolicy.gestureBar("1", "1"));
        check(taskbarHidden.startsWith("사용 중 · ColorOS 기본 바 감지 중") && taskbarHidden.contains("작업 표시줄") && !taskbarHidden.contains("숨기기"),
            "Taskbar ON + hidden gesture bar is normal, no hide error: " + taskbarHidden);
        check(StatusPolicy.bar(true, true, true, true, true, true, true, true, false, true, true, OK, StatusPolicy.gestureBar("1", "0")).startsWith("오류 · 제스처 안내 막대 숨기기"),
            "Taskbar OFF + hidden gesture bar keeps the hide error");
        check(StatusPolicy.bar(true, true, true, true, true, true, true, true, false, true, true, OK, StatusPolicy.gestureBar(null, null)).contains("제스처 안내 막대 표시 여부 확인 불가"),
            "Unreadable gesture bar and taskbar stay unverified");
        check(StatusPolicy.bar(true, true, true, true, false, true, true, true, false, true, true, OK, TASKBAR).contains("보안 설정 쓰기 권한 필요"),
            "Taskbar ON still requires the native prerequisites");
        String hidden = StatusPolicy.bar(true, true, true, true, true, true, true, true, false, true, true, OK, HIDDEN);
        check(hidden.equals("오류 · 제스처 안내 막대 숨기기가 켜져 있습니다. 설정 → 시스템 및 업데이트 → 시스템 탐색에서 끄세요"), "Hidden gesture bar names the ColorOS setting: " + hidden);
        check(StatusPolicy.bar(true, true, true, true, false, false, false, false, false, false, true, NONE, HIDDEN).startsWith("오류"), "Hidden gesture bar is reported before other native prerequisites");
        check(StatusPolicy.bar(true, false, true, true, true, true, true, true, false, true, true, OK, HIDDEN).equals("꺼짐"), "Hidden gesture bar is no error while the trigger is off");
        String unknown = StatusPolicy.bar(true, true, true, true, true, true, true, true, false, true, true, OK, UNKNOWN);
        check(!unknown.startsWith("사용 중") && !unknown.startsWith("오류") && unknown.contains("확인 불가") && unknown.contains("제스처 안내 막대"), "Unknown gesture bar is unverified, not normal: " + unknown);
        check(StatusPolicy.bar(true, true, true, true, true, true, true, true, false, true, true, OK, SHOWN).startsWith("사용 중 · ColorOS 기본 바 감지 중"), "Shown gesture bar keeps the normal native status");
        for (int visibility : new int[]{SHOWN, HIDDEN, UNKNOWN, TASKBAR})
            check(StatusPolicy.bar(true, true, true, false, true, true, true, true, false, true, true, OK, visibility).startsWith("사용 중 · 터치 영역"), "Touch area stays in use with gesture bar " + visibility);
        check(StatusPolicy.bar(false, true, true, false, true, true, true, true, false, true, true, OK, SHOWN).startsWith("접근성 설정 필요"), "Own accessibility approval is checked first");
        check(StatusPolicy.bar(true, true, false, false, true, true, true, true, false, true, true, OK, SHOWN).contains("서비스 연결 대기 · 접근성 승인됨"), "Approved but not live is reported separately");
        check(StatusPolicy.bar(true, false, false, true, false, false, false, false, false, false, true, NONE, SHOWN).equals("꺼짐"), "Disabled bottom trigger is just off");
        check(StatusPolicy.bar(true, true, true, false, false, false, false, false, false, true, false, NONE, SHOWN).startsWith("사용 중 · 터치 영역"), "Touch area needs no WSS, Breeno, device log or Google for a custom app");
        check(StatusPolicy.bar(true, true, true, true, true, false, true, true, false, true, true, OK, SHOWN).contains("Breeno"), "Breeno is required for the ColorOS bar");
        check(StatusPolicy.bar(true, true, true, true, true, true, false, false, false, false, true, OK, SHOWN).contains("기기 로그 권한 필요"), "Missing READ_LOGS is distinct from session consent");
        check(StatusPolicy.bar(true, true, true, true, true, true, true, false, true, false, true, OK, SHOWN).contains("허용 대기"), "Pending consent dialog is reported");
        check(StatusPolicy.bar(true, true, true, true, true, true, true, false, false, false, true, OK, SHOWN).contains("승인 필요"), "Missing session consent is actionable");
        check(StatusPolicy.bar(true, true, true, false, true, true, true, true, false, true, true, NONE, SHOWN).contains("Google로 선택"), "CTS target needs Google as the assistant");
        check(StatusPolicy.bar(true, true, true, false, true, true, true, true, false, true, true, GONE, SHOWN).contains("사용 중지"), "Selected but missing/disabled Google is distinct");
        for (int google : new int[]{NONE, GONE, OK})
            for (int bits = 0; bits < 128; bits++) {
                boolean[] b = new boolean[7];
                for (int i = 0; i < 7; i++) b[i] = (bits & (1 << i)) != 0;
                String s = StatusPolicy.power(b[0], b[1], b[2], b[3], b[4], b[5], google, b[6]);
                boolean ok = b[0] && b[1] && b[2] && b[3] && b[4] && google == OK;
                check(s.startsWith("감지 중") == ok && (!ok || (s.contains("요청") && s.contains("실제 연결 상태는 확인 불가")))
                    && s.contains("보안 설정 쓰기 권한 없음") == (ok && !b[6])
                    && !s.contains("떴") && !s.contains("열렸") && !s.contains("표시됨"), "Power readiness case " + google + "/" + bits + ": " + s);
            }
        check(StatusPolicy.power(false, true, true, true, true, false, OK, true).equals("꺼짐"), "Power option off");
        check(StatusPolicy.power(true, true, true, true, true, false, NONE, true).contains("Google로 선택")
            && StatusPolicy.power(true, true, true, true, true, false, GONE, true).contains("사용 중지"), "Power needs Google selected and available");
        check(StatusPolicy.power(true, true, true, false, false, false, OK, true).contains("권한 필요")
            && StatusPolicy.power(true, true, true, true, false, false, OK, true).contains("승인 필요"), "Power separates READ_LOGS from session consent");
        for (int bits = 0; bits < 64; bits++)
            for (long age : new long[]{-1, 0, 900, LogTrigger.POWER_LAUNCH_AGE, LogTrigger.POWER_LAUNCH_AGE + 1}) {
                boolean p = (bits & 1) != 0, g = (bits & 2) != 0, on = (bits & 4) != 0, lk = (bits & 8) != 0, opt = (bits & 16) != 0, a = (bits & 32) != 0;
                check((StatusPolicy.powerBlocked(p, g, on, lk, opt, a, age) == null) == LogTrigger.powerLaunch(p, g, on, lk, opt, a, age), "Power cancel reason matches the launch gate " + bits + "/" + age);
            }
        check(StatusPolicy.powerBlocked(true, true, false, true, true, true, 0).contains("화면 꺼짐"), "Screen-off cancel names the screen, not the lock");
        check(StatusPolicy.powerBlocked(true, true, true, true, false, true, 0).contains("잠금"), "Locked with the option off names the lock");

        // Last-attempt records: newest per pathway, stale callbacks never overwrite, no visual-success claim.
        long t = TIME;
        String first = StatusPolicy.begin(null, t, StatusPolicy.PENDING, "전원 버튼", "재연결 중");
        check(StatusPolicy.id(first) == 1 && StatusPolicy.summary(first, t + 100).endsWith("진행 중"), "First attempt starts pending");
        String second = StatusPolicy.begin(first, t + 2000, StatusPolicy.PENDING, "전원 버튼", "재연결 중");
        check(StatusPolicy.id(second) == 2 && second.split("\t", -1).length == 6, "A new attempt replaces the old record (bounded)");
        check(StatusPolicy.update(second, 1, t + 2100, StatusPolicy.ACCEPTED, "old") == null, "A stale callback from the older attempt cannot overwrite the newer one");
        String step = StatusPolicy.update(second, 2, t + 2050, StatusPolicy.PENDING, "Google 준비");
        check(step != null && StatusPolicy.id(step) == 2, "An attempt may record intermediate steps");
        String acceptedRecord = StatusPolicy.update(step, 2, t + 2900, StatusPolicy.ACCEPTED, "세션 요청 수락");
        String acceptedSummary = StatusPolicy.summary(acceptedRecord, t + 3000);
        check(acceptedSummary.contains("수락") && acceptedSummary.contains("확인 불가"), "Gemini acceptance is not a window claim: " + acceptedSummary);
        check(StatusPolicy.update(acceptedRecord, 2, t + 3000, StatusPolicy.CANCELLED, "late") == null, "A late duplicate (e.g. destroy after handoff) cannot rewrite a result");
        check(StatusPolicy.summary(acceptedRecord, t + 600000).equals(acceptedSummary), "Final results never turn into interrupted");
        String handed = StatusPolicy.update(StatusPolicy.begin(null, t, StatusPolicy.PENDING, "터치 영역", "x"), 1, t + 200, StatusPolicy.HANDED_OFF, "MiCTS 실행 요청 전달");
        check(StatusPolicy.summary(handed, t).contains("전달") && StatusPolicy.summary(handed, t).contains("확인 불가"), "MiCTS startActivity is a handoff only");
        String cancelled = StatusPolicy.update(second, 2, t + 2500, StatusPolicy.CANCELLED, "Gemini 요청 취소: 화면 꺼짐");
        check(StatusPolicy.summary(cancelled, t + 2600).endsWith("취소됨") && StatusPolicy.detail(cancelled, t + 2600).contains("화면 꺼짐"), "Cancellation is recorded with its reason");
        for (int outcome = StatusPolicy.PENDING; outcome <= StatusPolicy.ACCEPTED; outcome++) {
            String s = StatusPolicy.detail(StatusPolicy.begin(null, t, outcome, "입력", "내용"), t);
            check(!s.contains("떴") && !s.contains("열렸") && !s.contains("표시됨") && !s.contains("성공"), "Outcome " + outcome + " never claims visual success");
        }
        check(StatusPolicy.summary(second, t + 2000 + StatusPolicy.INTERRUPTED).endsWith("진행 중"), "Pending within the bound is in progress");
        check(StatusPolicy.summary(second, t + 2001 + StatusPolicy.INTERRUPTED).endsWith("결과 없음 (중단됨)"), "Pending with no result is shown as interrupted");
        check(StatusPolicy.summary(second, t - 60000).endsWith("결과 없음 (중단됨)"), "A clock jump back does not keep it in progress forever");
        String detail = StatusPolicy.detail(StatusPolicy.begin(null, t, StatusPolicy.FAILED, "a\tb\nc", "d\te"), t);
        check(detail.contains("입력  a b c") && detail.contains("내용  d e"), "Separators in fields cannot corrupt the record");
        for (String bad : new String[]{null, "", "x", "1\t2\t3\t9\ta\tb", "0\t1\t1\t0\ta\tb", "a\t1\t1\t0\ta\tb", "1\t1\t1\t0\ta"}) {
            check(StatusPolicy.summary(bad, t).equals("기록 없음") && StatusPolicy.id(bad) == 0 && StatusPolicy.update(bad, 1, t, StatusPolicy.FAILED, "x") == null
                && StatusPolicy.id(StatusPolicy.begin(bad, t, StatusPolicy.PENDING, "a", "b")) == 1, "Malformed record is ignored: " + bad);
        }

        if (failures != 0) throw new AssertionError(failures + " failures in " + checks + " log trigger checks");
        System.out.println("PASS: " + checks + " log trigger checks; actual production classifier, health rule, timing gate and approval bound, no SDK or device used.");
    }
}
'@
    [IO.File]::WriteAllText($harness,$source,(New-Object Text.UTF8Encoding($false)))
    $policy = Join-Path $PSScriptRoot 'micts/fast-launch/LogTrigger.java'
    $status = Join-Path $PSScriptRoot 'micts/fast-launch/StatusPolicy.java'
    & javac.exe --release 17 -encoding UTF-8 -d $folder $policy $status $harness
    if ($LASTEXITCODE) { throw 'Log trigger test compilation failed' }
    & java.exe -cp $folder dev.padhelper.fastcts.LogTriggerHarness
    if ($LASTEXITCODE) { throw 'Log trigger regression failed' }
} finally {
    if (Test-Path -LiteralPath $folder) {
        $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $folder).ProviderPath)
        $resolvedQa = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $qa).ProviderPath)
        if (-not $resolved.Equals($folder,[StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetDirectoryName($resolved).Equals($resolvedQa,[StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -cnotmatch '^native-bar-test-[a-f0-9]{32}$') { throw 'Refusing unsafe native-bar test cleanup.' }
        [IO.Directory]::Delete($resolved,$true)
    }
}
