# Compiles the production event classifier and timing policy with only the JDK.
# No SDK, ADB, device, or Binder runtime; transport also requires device tests.
$ErrorActionPreference = 'Stop'
$qa = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'qa'))
$folder = [IO.Path]::GetFullPath((Join-Path $qa ('native-bar-test-'+[guid]::NewGuid().ToString('N'))))
try {
    [void][IO.Directory]::CreateDirectory($folder)
    $harness = Join-Path $folder 'NativeBarHarness.java'
    $source = @'
package dev.padhelper.fastcts;


import java.util.Locale;

public final class NativeBarHarness {
    private static int checks, failures;
    private static final long TIME = 1763160000000L;
    private static final int SYSTEM_UI = 10567;
    private static final String ACTION = "act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST ";
    private static final String COMPONENT = "cmp=com.heytap.speechassist/.core.SpeechService ";
    private static void check(boolean result, String description) {
        checks++;
        if (!result) { failures++; System.err.println("FAIL: " + description); }
    }
    private static String message(int uid, int user) {
        return "Unable to start service Intent { " + ACTION + COMPONENT + "mCallingUid=" + uid + " } U=" + user + ": not found";
    }
    private static String record(String stamp, String message) { return stamp + "  1234  5678 W ActivityManager: " + message; }
    private static String event(long time, int uid, int user) {
        return record(String.format(Locale.ROOT,"%d.%03d",time/1000,time%1000),message(uid,user));
    }
    private static boolean accepted(String line) { return NativeBarProtocol.isGesture(line,SYSTEM_UI,0,TIME,TIME+500); }
    public static void main(String[] args) throws Exception {
        String good = event(TIME,SYSTEM_UI,0);
        check(accepted(good),"Exact SystemUI failed Breeno service event is accepted");
        check(accepted("  " + good),"Leading logcat whitespace is accepted");
        check(accepted(good.replace("ActivityManager:","ActivityManager  :")),"Logcat tag padding is accepted");
        check(NativeBarProtocol.isGesture(good,SYSTEM_UI,0,TIME,TIME),"An event at the capture watermark is accepted");
        check(NativeBarProtocol.isGesture(good,SYSTEM_UI,0,TIME,TIME+1500),"The 1500ms event age boundary is accepted");
        check(!NativeBarProtocol.isGesture(good,SYSTEM_UI,0,TIME,TIME+1501),"Stale events beyond the age window are rejected");
        check(!NativeBarProtocol.isGesture(good,SYSTEM_UI,0,TIME+1,TIME+500),"Replayed log buffer events before the watermark are rejected");
        check(!NativeBarProtocol.isGesture(event(TIME+1000,SYSTEM_UI,0),SYSTEM_UI,0,TIME,TIME+500),"Future events are rejected");
        check(!NativeBarProtocol.isGesture(good,SYSTEM_UI,0,TIME+1000,TIME+500),"A watermark beyond now cannot authorize an old event");
        for (int uid : new int[]{0,1000,1001,10000,SYSTEM_UI+1})
            check(!accepted(event(TIME,uid,0)),"Caller UID "+uid+" cannot impersonate SystemUI, including power-button caller1000");
        for (int uid : new int[]{-1,0,999,1000})
            check(!NativeBarProtocol.isGesture(good,uid,0,TIME,TIME+500),"Invalid configured SystemUI UID "+uid+" is rejected");
        check(!accepted(event(TIME,SYSTEM_UI,10)),"Other target user is rejected");
        check(!NativeBarProtocol.isGesture(good,SYSTEM_UI,-1,TIME,TIME+500),"Invalid configured user cannot match the event");
        check(NativeBarProtocol.isGesture(event(TIME,1010567,10),1010567,10,TIME,TIME+500),"An explicitly matching secondary-user SystemUI event is supported");
        check(!accepted(good.replace(" W ActivityManager:"," I ActivityManager:")),"Wrong log level is rejected");
        check(!accepted(good.replace(" W ActivityManager:"," W ActivityTaskManager:")),"Wrong system tag is rejected");
        check(!accepted(good.replace("1234  5678 W","1000 1234 5678 W")),"Unexpected log header fields are rejected");
        check(!accepted(good.replace(ACTION,"")),"Missing action is rejected");
        check(!accepted(good.replace(COMPONENT,"")),"Missing component is rejected");
        check(!accepted(good.replace("ACTIVATE_SPEECH_ASSIST ","ACTIVATE_SPEECH_ASSIST_EVIL ")),"Action prefixes cannot match");
        check(!accepted(good.replace(".core.SpeechService ",".core.SpeechServiceEvil ")),"Component prefixes cannot match");
        check(!accepted(good.replace("cmp=com.heytap.speechassist/","cmp=example.untrusted/")),"Other component package is rejected");
        check(!accepted(good.replace("Unable to start service Intent { ","Starting service Intent { ")),"Different service message is rejected");
        check(!accepted(good.replace("not found","permission denied")),"Other service failures are rejected");
        check(!accepted(good.replace(" } U=0: not found", " U=0: not found")),"Missing intent close delimiter is rejected");
        check(!accepted(good.replace("mCallingUid=10567", "mCallingUid=10567.0")),"Malformed caller numeric suffix is rejected");
        check(!accepted(good.replace("mCallingUid=10567", "mCallingUid=999999999999999999999")),"Caller integer overflow is rejected");
        check(!accepted(good.replace("U=0:", "U=999999999999999999999:")),"User integer overflow is rejected");
        check(!accepted(good.replace("mCallingUid=10567", "caller=10567")),"Missing authoritative caller field is rejected");
        check(!accepted(good.replace("U=0:", "U=0;")),"Malformed user delimiter is rejected");
        check(!accepted(good.replace(ACTION,ACTION+"mCallingUid=10567 ").replace("mCallingUid=10567 }","mCallingUid=10000 }")),"An embedded caller cannot spoof a different authoritative caller");
        check(!accepted(good.replace(COMPONENT,COMPONENT+"U=0: ").replace("} U=0:","} U=10:")),"An embedded user cannot spoof a different authoritative target user");
        check(!accepted(good.replace(COMPONENT,COMPONENT+"mCallingUid=10567 ")),"Duplicate caller fields are rejected even if equal");
        check(!accepted(good.replace(COMPONENT,COMPONENT+"U=0: ")),"Duplicate user fields are rejected even if equal");
        check(!accepted(good+" trailing text"),"Unrecognized text after the authoritative origin is rejected");
        for (String stamp : new String[]{"1234.000","-1763160000.000","1763160000","NaN.000","1763160000.NaN"})
            check(!accepted(record(stamp,message(SYSTEM_UI,0))),"Malformed epoch timestamp "+stamp+" is rejected");
        String overflow = record("999999999999999999999999999999999999999999.000",message(SYSTEM_UI,0));
        check(!accepted(overflow),"Timestamp overflow is rejected with a normal clock");
        check(!NativeBarProtocol.isGesture(overflow,SYSTEM_UI,0,TIME,Long.MAX_VALUE),"Nonrepresentable epoch timestamps are rejected rather than saturated");
        check(!accepted(null),"Null log records are rejected");
        check(!accepted(""),"Empty log records are rejected");
        check(accepted(good+" ".repeat(4096-good.length())),"The bounded 4096-character log line is accepted");
        check(!accepted(good+" ".repeat(4097-good.length())),"Oversized log records are rejected");

        check(NativeBarProtocol.freshHold(0,0),"Zero monotonic time is valid");
        check(NativeBarProtocol.freshHold(10,1510),"Hold age 1500ms is accepted");
        check(!NativeBarProtocol.freshHold(10,1511),"Hold age1501ms is rejected");
        check(!NativeBarProtocol.freshHold(11,10),"Future hold is rejected");
        check(!NativeBarProtocol.freshHold(-1,10),"Negative hold time is rejected");
        check(!NativeBarProtocol.freshHold(0,-1),"Negative current time is rejected");
        check(!NativeBarProtocol.freshHold(0,Long.MAX_VALUE),"Huge hold age does not wrap into a fresh event");
        check(NativeBarProtocol.freshHold(Long.MAX_VALUE,Long.MAX_VALUE),"Equal nonnegative long values do not overflow age calculation");

        String observed = "1763160000.000  1501  2345 W ActivityManager: Unable to start service Intent { act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST xflg=0x4 cmp=com.heytap.speechassist/.core.SpeechService (has extras) mCallingUid=10160 } U=0: not found";
        check(NativeBarProtocol.isGesture(observed,10160,0,TIME,TIME+500),"Observed OPD2515 Android 17 SystemUI long-hold record is accepted");
        check(!NativeBarProtocol.isGesture(observed.replace("mCallingUid=10160","mCallingUid=1000"),10160,0,TIME,TIME+500),"The same intent from the power-key caller1000 is rejected");
        check(!NativeBarProtocol.isGesture(observed,SYSTEM_UI,0,TIME,TIME+500),"Observed caller10160 only matches when it is the resolved SystemUI UID");

        check(NativeBarProtocol.VERSION==3,"Binder protocol version remains explicit");
        check(NativeBarProtocol.newer(1,0) && !NativeBarProtocol.newer(5,5) && !NativeBarProtocol.newer(4,5),"Control epochs must strictly increase");
        check(NativeBarProtocol.DETACH!=NativeBarProtocol.QUIT && NativeBarProtocol.RUN!=NativeBarProtocol.PAUSE,"Transient detach stays distinct from explicit quit");
        if (failures!=0) throw new AssertionError(failures+" failures in "+checks+" native-bar protocol checks");
        System.out.println("PASS: "+checks+" native-bar protocol checks; actual production parser and timing policy, no SDK or device used.");
    }
}
'@
    [IO.File]::WriteAllText($harness,$source,(New-Object Text.UTF8Encoding($false)))
    $protocol = Join-Path $PSScriptRoot 'micts/fast-launch/NativeBarProtocol.java'
    & javac.exe --release 17 -encoding UTF-8 -d $folder $protocol $harness
    if ($LASTEXITCODE) { throw 'Native-bar protocol test compilation failed' }
    & java.exe -cp $folder dev.padhelper.fastcts.NativeBarHarness
    if ($LASTEXITCODE) { throw 'Native-bar protocol regression failed' }
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
