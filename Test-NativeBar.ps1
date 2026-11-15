# Compiles the production logd packet classifier, health rule, timing gate and approval bound with only the JDK.
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
    private static final int SYSTEM_UI = 10160, APP = 10234, V = 2, I = 4, W = 5;
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
    private static int kind(byte[] p) { return LogTrigger.classify(p, p.length, SYSTEM_UI, 0); }

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

        check(kind(system(W, AM, breeno(SYSTEM_UI, 0))) == LogTrigger.BAR, "Observed SystemUI failed Breeno start is BAR");
        check(kind(system(W, AM, breeno(1000, 0))) == LogTrigger.NONE, "The power-key tail with caller 1000 never triggers the bar");
        check(kind(system(I, AM, breeno(SYSTEM_UI, 0))) == LogTrigger.NONE, "Bar record must be a warning");
        check(kind(system(W, "ActivityTaskManager", breeno(SYSTEM_UI, 0))) == LogTrigger.NONE, "Bar record needs the exact ActivityManager tag");
        check(kind(entry(3, APP, TIME, W, AM, breeno(SYSTEM_UI, 0))) == LogTrigger.NONE, "An app writing a forged ActivityManager line is rejected");
        check(kind(entry(0, 1000, TIME, W, AM, breeno(SYSTEM_UI, 0))) == LogTrigger.NONE, "Bar record outside the system buffer is rejected");
        check(kind(system(W, AM, breeno(SYSTEM_UI, 10))) == LogTrigger.NONE, "Other target user is rejected");
        check(LogTrigger.classify(system(W, AM, breeno(1010160, 10)), system(W, AM, breeno(1010160, 10)).length, 1010160, 10) == LogTrigger.BAR, "Matching secondary-user SystemUI is supported");
        check(LogTrigger.classify(system(W, AM, breeno(SYSTEM_UI, 0)), system(W, AM, breeno(SYSTEM_UI, 0)).length, -1, 0) == LogTrigger.NONE, "Unresolved SystemUI UID never matches");

        String good = breeno(SYSTEM_UI, 0);
        check(LogTrigger.bar(good, SYSTEM_UI, 0), "Bar message parser accepts the observed record");
        for (int uid : new int[]{-1, 0, 999, 1000})
            check(!LogTrigger.bar(good, uid, 0), "Invalid configured SystemUI UID " + uid + " is rejected");
        check(!LogTrigger.bar(good, SYSTEM_UI, -1), "Invalid configured user is rejected");
        check(!LogTrigger.bar(good.replace("act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST ", ""), SYSTEM_UI, 0), "Missing action is rejected");
        check(!LogTrigger.bar(good.replace("ACTIVATE_SPEECH_ASSIST ", "ACTIVATE_SPEECH_ASSIST_EVIL "), SYSTEM_UI, 0), "Action prefixes cannot match");
        check(!LogTrigger.bar(good.replace(".core.SpeechService ", ".core.SpeechServiceEvil "), SYSTEM_UI, 0), "Component prefixes cannot match");
        check(!LogTrigger.bar(good.replace("cmp=com.heytap.speechassist/", "cmp=example.untrusted/"), SYSTEM_UI, 0), "Other component package is rejected");
        check(!LogTrigger.bar(good.replace("Unable to start service", "Starting service"), SYSTEM_UI, 0), "Different service message is rejected");
        check(!LogTrigger.bar(good.replace("not found", "permission denied"), SYSTEM_UI, 0), "Other service failures are rejected");
        check(!LogTrigger.bar(good.replace(" } U=0", " U=0"), SYSTEM_UI, 0), "Missing intent close delimiter is rejected");
        check(!LogTrigger.bar(good.replace("mCallingUid=10160", "mCallingUid=999999999999999999999"), SYSTEM_UI, 0), "Caller integer overflow is rejected");
        check(!LogTrigger.bar(good.replace("U=0:", "U=999999999999999999999:"), SYSTEM_UI, 0), "User integer overflow is rejected");
        check(!LogTrigger.bar(good.replace("(has extras) ", "(has extras) mCallingUid=10160 ").replace("mCallingUid=10160 }", "mCallingUid=10000 }"), SYSTEM_UI, 0), "Embedded caller cannot spoof the authoritative caller");
        check(!LogTrigger.bar(good.replace("(has extras) ", "(has extras) U=0: "), SYSTEM_UI, 0), "Duplicate user fields are rejected");
        check(!LogTrigger.bar(good + " trailing text", SYSTEM_UI, 0), "Text after the authoritative origin is rejected");
        check(!LogTrigger.bar(null, SYSTEM_UI, 0), "Null message is rejected");
        check(LogTrigger.bar(good + " ".repeat(4096 - good.length()), SYSTEM_UI, 0), "4096-character message is accepted");
        check(!LogTrigger.bar(good + " ".repeat(4097 - good.length()), SYSTEM_UI, 0), "Oversized message is rejected");

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

        if (failures != 0) throw new AssertionError(failures + " failures in " + checks + " log trigger checks");
        System.out.println("PASS: " + checks + " log trigger checks; actual production classifier, health rule, timing gate and approval bound, no SDK or device used.");
    }
}
'@
    [IO.File]::WriteAllText($harness,$source,(New-Object Text.UTF8Encoding($false)))
    $policy = Join-Path $PSScriptRoot 'micts/fast-launch/LogTrigger.java'
    & javac.exe --release 17 -encoding UTF-8 -d $folder $policy $harness
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
