param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$jar = Join-Path $Sdk 'platforms/android-37.0/android.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Android SDK platform android-37.0 is required for this developer test.' }
$hiddenApiJar = Join-Path $PSScriptRoot 'micts/fast-launch/build/deps/hiddenapibypass-6.1.jar'
if (-not (Test-Path -LiteralPath $hiddenApiJar)) { throw 'Run micts/fast-launch/Build.ps1 first to prepare the pinned Gemini dependency.' }
if ((Get-FileHash -LiteralPath $hiddenApiJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne '6f50c4d202acb8152901716df3a1f94b2181e33aa0d14c9bdb2579cbc21c0832') { throw 'Unexpected Gemini dependency bytes' }
$folder = Join-Path $PSScriptRoot ('qa/android-recovery-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($folder)
$harness = Join-Path $folder 'RecoveryHarness.java'
$source = @'
package dev.padhelper.fastcts;
public final class RecoveryHarness {
    static int checks;
    static void check(boolean result) {
        if (!result) throw new AssertionError("Check " + checks + " failed");
        checks++;
    }
    public static void main(String[] args) {
        String own = BootRecovery.SERVICE;
        String other = "com.example.reader/.Reader:com.example.second/com.example.second.Service";
        check(BootRecovery.withOwn(null).equals(own));
        check(BootRecovery.withOwn("").equals(own));
        check(BootRecovery.withOwn(other).equals(other + ":" + own));
        check(BootRecovery.withOwn(other + ":").equals(other + ":" + own));
        check(BootRecovery.withOwn(BootRecovery.SHORT_SERVICE).equals(BootRecovery.SHORT_SERVICE));
        check(BootRecovery.withOwn(other + ":" + own).equals(other + ":" + own));
        check(!BootRecovery.containsOwn("evil." + own));
        check(BootRecovery.containsOwn(other + ":" + own));
        check(!BootRecovery.containsOwn(null));
        for (int bits = 0; bits < 64; bits++) {
            check(BootRecovery.shouldRecover((bits & 1) != 0, (bits & 2) != 0,
                (bits & 4) != 0, (bits & 8) != 0, (bits & 16) != 0, (bits & 32) != 0) == (bits == 63));
        }
        check(BarGesture.releaseShouldTrigger(350, false, false));
        check(!BarGesture.releaseShouldTrigger(349, false, false));
        check(!BarGesture.releaseShouldTrigger(1000, true, false));
        check(!BarGesture.releaseShouldTrigger(1000, false, true));
        check(BootRecovery.autoStartMissed(32, 31, 1000, 2000));   // captured last boot only: auto-start blocked
        check(!BootRecovery.autoStartMissed(32, 32, 1000, 2000));  // captured this boot
        check(!BootRecovery.autoStartMissed(32, -2, 3000, 2000));  // installed after this boot: unknown, no warning
        check(!BootRecovery.autoStartMissed(-1, 31, 1000, 2000));  // boot count unreadable
        String sha = "9d3a0258f74a835df591fb0a1b83c99a858e8d5195e323b85ff8b7c2eeb0ad70", pkg = "dev.padhelper.fastcts";
        AppUpdate.Release update = AppUpdate.newer(1, pkg, "0.3.2-patch2", 21, "Circle-to-Search-Trigger-0.3.2-patch2.apk", sha.toUpperCase(), 20);
        check(update != null && update.code == 21 && update.sha256.equals(sha)
            && update.apkUrl().equals("https://github.com/JngVedere/OPD2515_CST/releases/download/v0.3.2-patch2/Circle-to-Search-Trigger-0.3.2-patch2.apk"));
        check(AppUpdate.newer(1, pkg, "0.3.2-patch2", 21, "Circle-to-Search-Trigger-0.3.2-patch2.apk", sha, 21) == null); // same code
        check(AppUpdate.newer(1, pkg, "0.3.2-patch2", 21, "Circle-to-Search-Trigger-0.3.2-patch2.apk", sha, 22) == null); // older
        check(AppUpdate.newer(2, pkg, "0.3.2-patch2", 21, "Circle-to-Search-Trigger-0.3.2-patch2.apk", sha, 20) == null);
        check(AppUpdate.newer(1, "com.example.app", "0.3.2-patch2", 21, "Circle-to-Search-Trigger-0.3.2-patch2.apk", sha, 20) == null);
        check(AppUpdate.newer(1, pkg, "0.3.2-patch2", 21, "Other-0.3.2-patch2.apk", sha, 20) == null);
        check(AppUpdate.newer(1, pkg, "0.3.2-patch2", 21, "Circle-to-Search-Trigger-0.3.2-patch2.apk", sha.substring(1), 20) == null);
        check(AppUpdate.newer(1, pkg, "0.3.2/../x", 21, "Circle-to-Search-Trigger-0.3.2/../x.apk", sha, 20) == null);
        check(AppUpdate.newer(1, pkg, "0.3.2..x", 21, "Circle-to-Search-Trigger-0.3.2..x.apk", sha, 20) == null);
        check(AppUpdate.newer(1, pkg, null, 21, null, sha, 20) == null);
        check(AppUpdate.hex(new byte[]{0, 15, (byte) 255}).equals("000fff"));
        AppUpdate.Release s22 = new AppUpdate.Release("0.3.3", 22, "f", sha), b23 = new AppUpdate.Release("0.3.4-beta1", 23, "f", sha);
        check(AppUpdate.pick(s22, null) == s22 && !s22.beta);                                // beta off or none published
        check(AppUpdate.pick(s22, b23) == b23 && b23.beta);                                  // newer beta wins, marked beta
        check(AppUpdate.pick(null, new AppUpdate.Release("0.3.4-beta1", 23, "f", sha)).beta); // only a beta is newer
        check(AppUpdate.pick(s22, new AppUpdate.Release("0.3.3-beta9", 22, "f", sha)) == s22); // tie: stable wins
        check(AppUpdate.pick(null, null) == null);
        System.out.println("PASS: " + checks + " Android recovery checks; actual app policy and service-list code, no device used.");
    }
}
'@
[IO.File]::WriteAllText($harness,$source,(New-Object Text.UTF8Encoding($false)))
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'micts/fast-launch') -Filter *.java -File | Select-Object -ExpandProperty FullName)
& javac.exe --release 17 -encoding UTF-8 -classpath ($jar + ';' + $hiddenApiJar) -d $folder @sources $harness
if ($LASTEXITCODE) { throw 'Recovery test compilation failed' }
& java.exe -cp ($folder+';'+$jar) dev.padhelper.fastcts.RecoveryHarness
if ($LASTEXITCODE) { throw 'Android recovery regression failed' }

# Exercise the actual effectful recovery/gesture classes against an in-memory
# Android settings boundary. No installed app, ADB, or device settings are used.
$stateFolder = Join-Path $folder 'stateful'
$fixtures = @{}
$fixtures['android/Manifest.java'] = @'
package android;
public final class Manifest {
    public static final class permission { public static final String WRITE_SECURE_SETTINGS = "WSS"; }
}
'@
$fixtures['android/content/pm/PackageManager.java'] = @'
package android.content.pm;
public class PackageManager { public static final int PERMISSION_GRANTED = 0; }
'@
$fixtures['android/content/SharedPreferences.java'] = @'
package android.content;
import java.util.*;
public class SharedPreferences {
    private final Map<String,Object> values = new HashMap<>();
    public boolean failNextCommit;
    public boolean contains(String key) { return values.containsKey(key); }
    public boolean getBoolean(String key, boolean fallback) { return (Boolean)values.getOrDefault(key, fallback); }
    public int getInt(String key, int fallback) { return (Integer)values.getOrDefault(key, fallback); }
    public String getString(String key, String fallback) { return (String)values.getOrDefault(key, fallback); }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        private final Map<String,Object> pending = new HashMap<>();
        public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
        public Editor putInt(String key, int value) { pending.put(key, value); return this; }
        public Editor putString(String key, String value) { pending.put(key, value); return this; }
        public Editor remove(String key) { pending.put(key, null); return this; }
        public boolean commit() {
            if (failNextCommit) { failNextCommit = false; return false; }
            pending.forEach((key,value) -> { if (value == null) values.remove(key); else values.put(key,value); });
            return true;
        }
    }
}
'@
$fixtures['android/content/Context.java'] = @'
package android.content;
import java.util.*;
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final Context root;
    private final boolean deviceProtected;
    private final Map<String,SharedPreferences> credentialPrefs = new HashMap<>();
    private final Map<String,SharedPreferences> devicePrefs = new HashMap<>();
    public final Map<String,String> secure = new HashMap<>(), global = new HashMap<>();
    public int globalWrites, failGlobalWrites, globalWritesFailAt;
    public int permission, bootCount = 4, writes, failIntWrites, failStringWrites, throwReads, throwStringWrites, throwAfterStringWrites;
    public boolean credentialLocked;
    public Runnable beforeWrite;
    public Context() { root = this; deviceProtected = false; }
    private Context(Context root) { this.root = root; deviceProtected = true; }
    public int checkSelfPermission(String permission) { return root.permission; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!deviceProtected && root.credentialLocked) throw new IllegalStateException("Credential storage used before unlock");
        return (deviceProtected ? root.devicePrefs : root.credentialPrefs).computeIfAbsent(name, key -> new SharedPreferences());
    }
    public Context createDeviceProtectedStorageContext() { return new Context(root); }
    public Context getContentResolver() { return root; }
}
'@
$fixtures['android/provider/Settings.java'] = @'
package android.provider;
import android.content.Context;
public class Settings {
    public static final class Secure {
        public static final String ENABLED_ACCESSIBILITY_SERVICES = "services", ACCESSIBILITY_ENABLED = "enabled";
        public static String getString(Context context, String key) {
            if (context.throwReads > 0) { context.throwReads--; throw new SecurityException("read denied"); }
            return context.secure.get(key);
        }
        public static int getInt(Context context, String key, int fallback) {
            String value = getString(context,key); return value == null ? fallback : Integer.parseInt(value);
        }
        public static boolean putString(Context context, String key, String value) {
            context.writes++;
            if (context.beforeWrite != null) context.beforeWrite.run();
            if (context.failStringWrites > 0) { context.failStringWrites--; return false; }
            if (context.throwStringWrites > 0) { context.throwStringWrites--; throw new SecurityException("write denied"); }
            context.secure.put(key,value);
            if (context.throwAfterStringWrites > 0) { context.throwAfterStringWrites--; throw new IllegalStateException("write landed, then failed"); }
            return true;
        }
        public static boolean putInt(Context context, String key, int value) {
            context.writes++;
            if (context.beforeWrite != null) context.beforeWrite.run();
            if (context.failIntWrites > 0) { context.failIntWrites--; return false; }
            context.secure.put(key,Integer.toString(value)); return true;
        }
    }
    public static final class Global {
        public static final String BOOT_COUNT = "boot";
        public static int getInt(Context context, String key, int fallback) { return context.bootCount; }
        // Device: hidden keys are not readable by apps (Android 12+); PowerButton must never depend on a read.
        public static String getString(Context context, String key) { throw new SecurityException("Settings key: <" + key + "> is not readable."); }
        public static boolean putString(Context context, String key, String value) {
            context.globalWrites++;
            if (context.failGlobalWrites > 0) { context.failGlobalWrites--; return false; }
            if (context.globalWrites == context.globalWritesFailAt) return false;
            if (value == null) context.global.remove(key); else context.global.put(key,value);
            return true;
        }
    }
}
'@
$fixtures['android/util/Log.java'] = @'
package android.util;
public class Log {
    public static int i(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
}
'@
$fixtures['dev/padhelper/fastcts/BarService.java'] = @'
package dev.padhelper.fastcts;
import android.content.Context;
public class BarService {
    static boolean enabled(Context context) { return context.getSharedPreferences("bar",0).getBoolean("enabled",false); }
}
'@
$fixtures['dev/padhelper/fastcts/StateHarness.java'] = @'
package dev.padhelper.fastcts;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
public final class StateHarness {
    private static int checks;
    private static final String OTHER = "com.example.reader/.Reader";
    private static void check(boolean result, String description) {
        if (!result) throw new AssertionError(description);
        checks++;
    }
    private static SharedPreferences bar(Context context) { return context.getSharedPreferences("bar",0); }
    private static int master(Context context) { return Settings.Secure.getInt(context,Settings.Secure.ACCESSIBILITY_ENABLED,0); }
    private static int gesture(Context context) { return Settings.Secure.getInt(context,BarGesture.GESTURE_KEY,-1); }
    private static Context approvedBoot() {
        Context context = new Context();
        bar(context).edit().putBoolean("enabled",true).putBoolean(BootRecovery.APPROVED,true)
            .putBoolean(BootRecovery.OPTION,true).commit();
        context.secure.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,OTHER+":"+BootRecovery.SERVICE);
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"1");
        context.credentialLocked = true;
        BootRecovery.captureBeforeUnlock(context);
        context.credentialLocked = false;
        return context;
    }
    public static void main(String[] args) {
        Context context = approvedBoot();
        check(BootRecovery.eligible(context),"Approved same-boot snapshot is eligible");
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"0");
        BootRecovery.restore(context);
        check(master(context)==1,"Retained own entry still repairs disabled master switch");
        check(context.secure.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).equals(OTHER+":"+BootRecovery.SERVICE),"Master repair preserves exact service list");
        check(context.writes==1,"Master-only repair does not rewrite service list");

        context = approvedBoot();
        context.secure.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,OTHER);
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"0");
        BootRecovery.restore(context);
        check(context.secure.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).equals(OTHER+":"+BootRecovery.SERVICE),"Missing service appended after existing service");
        check(master(context)==1,"Missing service and master both repaired");
        check(context.writes==2,"Only two necessary settings written");
        BootRecovery.restore(context);
        check(context.writes==2,"Second restore is idempotent");

        context = approvedBoot();
        bar(context).edit().putBoolean(BootRecovery.OPTION,false).commit();
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"0");
        BootRecovery.restore(context);
        check(master(context)==0 && context.writes==0,"Opt-out prevents all recovery writes");

        context = approvedBoot();
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"0");
        context.credentialLocked = true;
        BootRecovery.captureBeforeUnlock(context);
        check(context.createDeviceProtectedStorageContext().getSharedPreferences("boot",0)
            .getBoolean("was_enabled",false),"Pre-unlock computed master=0 must retain listed service approval");
        context.credentialLocked = false;
        check(BootRecovery.eligible(context),"Listed service stays eligible after first unlock despite pre-unlock master=0");
        BootRecovery.restore(context);
        check(master(context)==1,"Post-unlock recovery repairs computed master switch");

        context = approvedBoot();
        context.secure.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,OTHER);
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"0");
        context.credentialLocked = true;
        BootRecovery.captureBeforeUnlock(context);
        context.credentialLocked = false;
        check(!BootRecovery.eligible(context),"Own entry absent before boot represents user opt-out");
        BootRecovery.restore(context);
        check(master(context)==0 && context.writes==0
            && context.secure.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).equals(OTHER),"Pre-boot service opt-out preserves other services and disables recovery");

        context = approvedBoot();
        context.secure.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,OTHER);
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"0");
        context.failStringWrites = 1;
        BootRecovery.restore(context);
        check(master(context)==0 && context.writes==1,"Failed service-list write cannot enable master");

        context = approvedBoot();
        context.secure.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,OTHER);
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"0");
        context.failIntWrites = 1;
        BootRecovery.restore(context);
        check(master(context)==0 && BootRecovery.containsOwn(context.secure.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)),"Failed master write retains repairable own list entry");
        BootRecovery.restore(context);
        check(master(context)==1 && context.writes==3,"Retry repairs master even though own entry was already restored");

        context = approvedBoot();
        context.permission = -1;
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"0");
        BootRecovery.restore(context);
        check(master(context)==0 && context.writes==0,"Revoked WSS prevents boot writes");
        context = approvedBoot();
        context.bootCount++;
        context.secure.put(Settings.Secure.ACCESSIBILITY_ENABLED,"0");
        BootRecovery.restore(context);
        check(master(context)==0 && context.writes==0,"Old-boot snapshot cannot authorize recovery");

        Context initial = new Context();
        initial.secure.put(BarGesture.GESTURE_KEY,"1");
        initial.beforeWrite = () -> check(bar(initial).getInt(BarGesture.ORIGINAL,-1)==1
            && bar(initial).getInt(BarGesture.APPLIED,-1)==0,"Gesture recovery data committed before system mutation");
        BarGesture.disableBreenoGesture(initial);
        initial.beforeWrite = null;
        check(gesture(initial)==0,"Touch mode disables conflicting OEM gesture");
        BarGesture.disableBreenoGesture(initial);
        check(initial.writes==1,"Already-disabled gesture avoids redundant writes");
        boolean restored = BarGesture.restore(initial);
        check(restored && gesture(initial)==1 && !bar(initial).contains(BarGesture.ORIGINAL),"Explicit stop reports successful original gesture restore");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"0");
        BarGesture.disableBreenoGesture(context);
        check(gesture(context)==0 && context.writes==0 && !bar(context).contains(BarGesture.ORIGINAL),"Original off gesture needs no backup or system change");
        restored = BarGesture.restore(context);
        check(restored && gesture(context)==0 && context.writes==0,"Stopping unmodified gesture succeeds without writes");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"1");
        bar(context).edit().putBoolean("native_bar",true).putInt(BarGesture.ORIGINAL,1)
            .putInt(BarGesture.APPLIED,1).commit();
        BarGesture.disableBreenoGesture(context);
        check(gesture(context)==0 && bar(context).getInt(BarGesture.ORIGINAL,-1)==1,"Migration from old native mode disables OEM gesture without losing original");
        check(bar(context).getInt(BarGesture.APPLIED,-1)==0,"Migration updates only the last applied gesture value");
        restored = BarGesture.restore(context);
        check(restored && gesture(context)==1 && !bar(context).contains(BarGesture.ORIGINAL),"Migrated app reports successful original gesture restore");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"1");
        BarGesture.disableBreenoGesture(context);
        context.secure.put(BarGesture.GESTURE_KEY,"7");
        restored = BarGesture.restore(context);
        check(restored && gesture(context)==7 && !bar(context).contains(BarGesture.ORIGINAL),"Restore successfully preserves later external setting change");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"1");
        BarGesture.disableBreenoGesture(context);
        context.permission = -1;
        restored = BarGesture.restore(context);
        check(!restored && gesture(context)==0 && bar(context).contains(BarGesture.ORIGINAL),"Revocation reports failure and retains pending recovery data");
        context.permission = 0;
        restored = BarGesture.restore(context);
        check(restored && gesture(context)==1 && !bar(context).contains(BarGesture.ORIGINAL),"Permission restoration allows successful deferred original-value restore");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"1");
        BarGesture.disableBreenoGesture(context);
        context.failIntWrites = 1;
        restored = BarGesture.restore(context);
        check(!restored && gesture(context)==0 && bar(context).contains(BarGesture.ORIGINAL),"Rejected restore write reports failure and retains original for retry");
        restored = BarGesture.restore(context);
        check(restored && gesture(context)==1 && !bar(context).contains(BarGesture.ORIGINAL),"Retry restores original after temporary settings failure");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"1");
        bar(context).failNextCommit = true;
        BarGesture.disableBreenoGesture(context);
        check(gesture(context)==1 && context.writes==0,"Unpersisted backup prevents system change");
        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"7");
        BarGesture.disableBreenoGesture(context);
        check(gesture(context)==7 && context.writes==0 && !bar(context).contains(BarGesture.ORIGINAL),"Unknown OEM value stays untouched");
        context = new Context();
        context.permission = -1;
        context.secure.put(BarGesture.GESTURE_KEY,"1");
        BarGesture.disableBreenoGesture(context);
        check(gesture(context)==1 && context.writes==0,"WSS required for gesture changes");
        check(BarGesture.restore(context) && context.writes==0,"No pending restore succeeds even without WSS");

        Context originalOff = new Context();
        originalOff.secure.put(BarGesture.GESTURE_KEY,"0");
        originalOff.beforeWrite = () -> check(bar(originalOff).getInt(BarGesture.ORIGINAL,-1)==0
            && bar(originalOff).getInt(BarGesture.APPLIED,-1)==1,"Native enable commits original off value before its system write");
        check(BarGesture.enableBreenoGesture(originalOff) && gesture(originalOff)==1,"Optional native mode enables the OEM event source from original off");
        originalOff.beforeWrite = null;
        BarGesture.disableBreenoGesture(originalOff);
        check(gesture(originalOff)==0 && bar(originalOff).getInt(BarGesture.ORIGINAL,-1)==0
            && bar(originalOff).getInt(BarGesture.APPLIED,-1)==0,"Native to touch transition preserves original off across both writes");
        check(BarGesture.restore(originalOff) && gesture(originalOff)==0 && !bar(originalOff).contains(BarGesture.ORIGINAL)
            && !bar(originalOff).contains(BarGesture.APPLIED),"Stopping native to touch cycle restores original off and clears pending metadata");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"1");
        BarGesture.disableBreenoGesture(context);
        check(gesture(context)==0 && bar(context).getInt(BarGesture.ORIGINAL,-1)==1,"Touch cycle saves original on before disabling the shortcut");
        check(BarGesture.enableBreenoGesture(context) && gesture(context)==1
            && bar(context).getInt(BarGesture.ORIGINAL,-1)==1,"Touch to native transition does not overwrite original on");
        check(BarGesture.restore(context) && gesture(context)==1 && !bar(context).contains(BarGesture.ORIGINAL),"Stopping touch to native cycle keeps original on");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"1");
        check(BarGesture.enableBreenoGesture(context) && context.writes==0
            && !bar(context).contains(BarGesture.ORIGINAL),"Native enable already at original on is an idempotent success");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"0");
        context.permission = -1;
        check(!BarGesture.enableBreenoGesture(context) && gesture(context)==0 && context.writes==0,"Revoked WSS prevents native enable from original off");
        context.permission = 0;
        bar(context).failNextCommit = true;
        check(!BarGesture.enableBreenoGesture(context) && gesture(context)==0 && context.writes==0,"Failed native backup commit prevents system changes");
        check(BarGesture.enableBreenoGesture(context) && gesture(context)==1,"Retry after commit failure can enable native mode");
        context.permission = -1;
        int savedWrites = context.writes;
        BarGesture.disableBreenoGesture(context);
        check(gesture(context)==1 && context.writes==savedWrites,"Revoked WSS also prevents a native to touch system write");
        check(!BarGesture.restore(context) && bar(context).getInt(BarGesture.ORIGINAL,-1)==0,"Revoked permission keeps original off pending for deferred restoration");
        context.permission = 0;
        check(BarGesture.restore(context) && gesture(context)==0,"Restoring WSS permits the original off value to be restored");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"0");
        context.failIntWrites = 1;
        check(!BarGesture.enableBreenoGesture(context) && gesture(context)==0
            && bar(context).getInt(BarGesture.ORIGINAL,-1)==0,"Rejected native setting write reports failure and keeps recovery information");
        check(BarGesture.enableBreenoGesture(context) && gesture(context)==1 && context.writes==2,"Native enable retries a rejected system setting write");
        check(BarGesture.restore(context) && gesture(context)==0 && context.writes==3,"Retry cycle still restores exactly the original off value");

        context = new Context();
        context.secure.put(BarGesture.GESTURE_KEY,"7");
        check(!BarGesture.enableBreenoGesture(context) && gesture(context)==7 && context.writes==0
            && !bar(context).contains(BarGesture.ORIGINAL),"Native mode preserves an unknown OEM setting value");
        context = new Context();
        check(!BarGesture.enableBreenoGesture(context) && gesture(context)==-1 && context.writes==0,"Native mode does not invent a missing OEM setting");

        String google = "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService";
        String other = "com.example.assist/.Vis";
        check(VoiceRepair.googleSelected(google),"Selected Google voice service is repairable");
        check(!VoiceRepair.googleSelected(null) && !VoiceRepair.googleSelected("") && !VoiceRepair.googleSelected("com.google.android.googlequicksearchbox/")
            && !VoiceRepair.googleSelected("com.google.android.googlequicksearchbox.evil/.Vis")
            && !VoiceRepair.googleSelected("com.google.android.googlequicksearchbox/.A:com.example/.B")
            && !VoiceRepair.googleSelected(other),"Only exactly one Google component is repairable");

        // First activation (Start / power option on): wanted only with power on, WSS and Google selected.
        Context voice = new Context();
        voice.secure.put(VoiceRepair.KEY,google);
        check(VoiceRepair.activationWanted(voice,true),"First activation with Google selected and WSS rebinds once");
        check(!VoiceRepair.activationWanted(voice,false),"Power option off: activation never rebinds");
        voice.permission = -1;
        check(!VoiceRepair.activationWanted(voice,true),"Missing WSS: activation skips the rebind");
        voice.permission = 0;
        voice.secure.put(VoiceRepair.KEY,other);
        check(!VoiceRepair.activationWanted(voice,true),"Another selected assistant: activation skips the rebind");
        voice.secure.put(VoiceRepair.KEY,google);
        voice.throwReads = 1;
        check(!VoiceRepair.activationWanted(voice,true),"Unreadable setting: activation skips the rebind without crashing");
        check(voice.writes==0,"Activation decisions never write");

        voice.beforeWrite = () -> { if (voice.writes==1) check(google.equals(bar(voice).getString(VoiceRepair.PENDING,null)),"Exact original journaled before clearing the voice service"); };
        check(VoiceRepair.run(voice),"Repair reports exact original restored");
        voice.beforeWrite = null;
        check(google.equals(voice.secure.get(VoiceRepair.KEY)) && voice.writes==2 && !bar(voice).contains(VoiceRepair.PENDING),"Repair clears once, restores the exact same Google value and drops the journal");

        for (String value : new String[] { null, other, "com.google.android.googlequicksearchbox/.A:com.example/.B" }) {
            context = new Context();
            if (value != null) context.secure.put(VoiceRepair.KEY,value);
            check(!VoiceRepair.run(context) && context.writes==0 && java.util.Objects.equals(value,context.secure.get(VoiceRepair.KEY)),"Non-Google or absent selection is never changed");
        }
        context = new Context();
        context.permission = -1;
        context.secure.put(VoiceRepair.KEY,google);
        check(!VoiceRepair.run(context) && context.writes==0 && google.equals(context.secure.get(VoiceRepair.KEY)),"Missing WSS: repair makes no writes");
        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        bar(context).failNextCommit = true;
        check(!VoiceRepair.run(context) && context.writes==0 && google.equals(context.secure.get(VoiceRepair.KEY)),"Unpersisted journal prevents clearing the voice service");

        // Exceptions from Settings never escape and never lose the original.
        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        context.throwReads = 1;
        check(!VoiceRepair.run(context) && context.writes==0 && google.equals(context.secure.get(VoiceRepair.KEY)),"Read exception before repair changes nothing");
        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        context.failStringWrites = 1;
        check(!VoiceRepair.run(context) && google.equals(context.secure.get(VoiceRepair.KEY)) && !bar(context).contains(VoiceRepair.PENDING),"Rejected clear leaves selection and no stale journal");
        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        context.throwStringWrites = 1;
        check(!VoiceRepair.run(context) && google.equals(context.secure.get(VoiceRepair.KEY)) && !bar(context).contains(VoiceRepair.PENDING),"SecurityException on clear leaves selection and no stale journal");
        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        context.throwAfterStringWrites = 1;
        check(!VoiceRepair.run(context) && google.equals(context.secure.get(VoiceRepair.KEY)) && !bar(context).contains(VoiceRepair.PENDING),"Clear that landed but threw is immediately restored to the exact original");
        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        check(VoiceRepair.begin(context),"Repair clears the voice service key");
        context.throwStringWrites = 1;
        check(!VoiceRepair.finish(context) && context.secure.get(VoiceRepair.KEY)==null && google.equals(bar(context).getString(VoiceRepair.PENDING,null)),"SecurityException on restore keeps the exact journal");
        context.throwReads = 1;
        check(!VoiceRepair.recover(context) && bar(context).contains(VoiceRepair.PENDING),"Read exception during recovery keeps the journal");
        check(VoiceRepair.recover(context) && google.equals(context.secure.get(VoiceRepair.KEY)) && !bar(context).contains(VoiceRepair.PENDING),"Later recovery restores the exact original");

        // Process death mid-repair: the next service start restores.
        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        check(VoiceRepair.begin(context) && context.secure.get(VoiceRepair.KEY)==null,"Repair clears only the voice service key");
        check(VoiceRepair.recover(context) && google.equals(context.secure.get(VoiceRepair.KEY)),"Next service start finishes an interrupted repair with the exact original");
        check(VoiceRepair.recover(context) && context.writes==2,"Recovering again is idempotent");

        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        VoiceRepair.begin(context);
        context.secure.put(VoiceRepair.KEY,other);
        check(!VoiceRepair.recover(context) && other.equals(context.secure.get(VoiceRepair.KEY))
            && !bar(context).contains(VoiceRepair.PENDING),"A selection made by someone else during repair is never overwritten");

        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        VoiceRepair.begin(context);
        context.failStringWrites = 1;
        check(!VoiceRepair.finish(context) && context.secure.get(VoiceRepair.KEY)==null && bar(context).contains(VoiceRepair.PENDING),"Rejected restore keeps the journal for retry");
        context.permission = -1;
        check(!VoiceRepair.finish(context) && bar(context).contains(VoiceRepair.PENDING),"Revoked WSS keeps the journal for retry");
        context.permission = 0;
        check(VoiceRepair.finish(context) && google.equals(context.secure.get(VoiceRepair.KEY)),"Retry restores the exact original");

        context = new Context();
        context.secure.put(VoiceRepair.KEY,google);
        VoiceRepair.begin(context);
        check(VoiceRepair.run(context) && google.equals(context.secure.get(VoiceRepair.KEY)) && context.writes==4
            && !bar(context).contains(VoiceRepair.PENDING),"A new repair first restores a leftover journal, then rebinds once");

        // Repeated requests: concurrent clicks are deduplicated and recovery never cuts a live repair short.
        Context shared = new Context();
        shared.secure.put(VoiceRepair.KEY,google);
        boolean[] first = new boolean[1];
        Thread running = new Thread(() -> first[0] = VoiceRepair.run(shared));
        running.start();
        long until = System.currentTimeMillis() + 2000;
        while (shared.secure.get(VoiceRepair.KEY) != null && System.currentTimeMillis() < until) Thread.onSpinWait();
        check(VoiceRepair.busy() && shared.secure.get(VoiceRepair.KEY)==null,"First request is mid-repair");
        check(!VoiceRepair.run(shared),"A concurrent repeated request is dropped");
        check(VoiceRepair.recover(shared) && shared.secure.get(VoiceRepair.KEY)==null,"Service-start recovery does not cut a live repair short");
        try { running.join(); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        check(first[0] && !VoiceRepair.busy() && google.equals(shared.secure.get(VoiceRepair.KEY)) && shared.writes==2,"Repeated request caused exactly one clear and one restore");
        check(VoiceRepair.run(shared) && shared.writes==4,"A later explicit request runs again");

        // Power long press: rebind -> exact restore -> settle, and only then may BarService request Gemini.
        Context press = new Context();
        press.secure.put(VoiceRepair.KEY,google);
        java.util.List<String> order = new java.util.ArrayList<>();
        press.beforeWrite = () -> order.add(press.secure.get(VoiceRepair.KEY) == null ? "restore" : "clear");
        long started = System.nanoTime();
        boolean ready = VoiceRepair.prepare(press) == VoiceRepair.REBOUND;
        long took = (System.nanoTime() - started) / 1_000_000;
        order.add("gemini");
        check(ready && order.equals(java.util.List.of("clear","restore","gemini")) && google.equals(press.secure.get(VoiceRepair.KEY))
            && !bar(press).contains(VoiceRepair.PENDING) && took >= VoiceRepair.UNBOUND_MS + VoiceRepair.SETTLE_MS,"Power press: rebind, exact restore and settle all finish before Gemini");
        check(VoiceRepair.prepare(press) == VoiceRepair.REBOUND && press.writes==4 && google.equals(press.secure.get(VoiceRepair.KEY)),"A second accepted power press rebinds again");
        press.beforeWrite = () -> { if (press.writes==6) press.failStringWrites = 1; };
        check(VoiceRepair.prepare(press) == VoiceRepair.NOT_RESTORED && press.secure.get(VoiceRepair.KEY)==null && google.equals(bar(press).getString(VoiceRepair.PENDING,null))
            && !VoiceRepair.busy(),"Failed restore: no Gemini, exact journal kept, not left busy");
        press.beforeWrite = null;
        press.permission = -1;
        int beforeRetry = press.writes;
        check(VoiceRepair.prepare(press) == VoiceRepair.RESTORE_PENDING && press.writes==beforeRetry && press.secure.get(VoiceRepair.KEY)==null
            && google.equals(bar(press).getString(VoiceRepair.PENDING,null)) && !VoiceRepair.busy(),"Journal restore failing again (WSS revoked): fail closed, journal kept, no extra clear");
        press.permission = 0;
        check(VoiceRepair.prepare(press) == VoiceRepair.REBOUND && google.equals(press.secure.get(VoiceRepair.KEY)) && !bar(press).contains(VoiceRepair.PENDING),"Next press restores the journaled Google value first, then rebinds");
        Context plain = new Context();
        plain.secure.put(VoiceRepair.KEY,google);
        plain.permission = -1;
        check(VoiceRepair.prepare(plain) == VoiceRepair.NO_WSS && plain.writes==0,"Without WSS the press goes straight to Gemini, no writes");
        plain.permission = 0;
        plain.secure.put(VoiceRepair.KEY,other);
        check(VoiceRepair.prepare(plain) == VoiceRepair.NOT_GOOGLE && plain.writes==0 && other.equals(plain.secure.get(VoiceRepair.KEY)),"Another selected assistant is never touched by a power press");
        Context manual = new Context();
        manual.secure.put(VoiceRepair.KEY,google);
        Thread repair = new Thread(() -> VoiceRepair.run(manual));
        repair.start();
        until = System.currentTimeMillis() + 2000;
        while (manual.secure.get(VoiceRepair.KEY) != null && System.currentTimeMillis() < until) Thread.onSpinWait();
        check(VoiceRepair.busy() && VoiceRepair.prepare(manual) == VoiceRepair.BUSY,"A press during a running rebind is dropped, not queued");
        try { repair.join(); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        check(!VoiceRepair.busy() && manual.writes==2 && google.equals(manual.secure.get(VoiceRepair.KEY)),"The running rebind still restores once and releases busy");
        // Only positive outcomes may go on to Gemini (the former true results); every refusal stays closed.
        for (int outcome : new int[]{VoiceRepair.REBOUND,VoiceRepair.NO_WSS,VoiceRepair.NOT_GOOGLE}) check(VoiceRepair.ready(outcome),"Rebind outcome "+outcome+" may request Gemini");
        for (int outcome : new int[]{VoiceRepair.BUSY,VoiceRepair.RESTORE_PENDING,VoiceRepair.NOT_RESTORED,VoiceRepair.INTERRUPTED,VoiceRepair.ERROR}) check(!VoiceRepair.ready(outcome) && !VoiceRepair.describe(outcome).isEmpty(),"Rebind outcome "+outcome+" never requests Gemini");
        // 3-button home key: managed with the gesture key in native mode, own journal, untouched when absent or by touch mode.
        Context homeKeys = new Context();
        homeKeys.secure.put(BarGesture.GESTURE_KEY,"0"); homeKeys.secure.put(BarGesture.HOME_KEY,"0");
        check(BarGesture.enableBreenoGesture(homeKeys) && "1".equals(homeKeys.secure.get(BarGesture.HOME_KEY)) && gesture(homeKeys)==1,"Native mode turns on the home long press with the gesture bar");
        check(BarGesture.restore(homeKeys) && "0".equals(homeKeys.secure.get(BarGesture.HOME_KEY)) && gesture(homeKeys)==0
            && !bar(homeKeys).contains(BarGesture.HOME_ORIGINAL),"Both originals come back");
        Context homeOn = new Context();
        homeOn.secure.put(BarGesture.GESTURE_KEY,"1"); homeOn.secure.put(BarGesture.HOME_KEY,"1");
        BarGesture.disableBreenoGesture(homeOn);
        check("1".equals(homeOn.secure.get(BarGesture.HOME_KEY)) && gesture(homeOn)==0,"Touch mode never changes the home key");
        Context homeChanged = new Context();
        homeChanged.secure.put(BarGesture.GESTURE_KEY,"1"); homeChanged.secure.put(BarGesture.HOME_KEY,"0");
        check(BarGesture.enableBreenoGesture(homeChanged),"Enable with home off");
        homeChanged.secure.put(BarGesture.HOME_KEY,"0"); // user turned it off again in ColorOS settings
        check(BarGesture.restore(homeChanged) && "0".equals(homeChanged.secure.get(BarGesture.HOME_KEY)),"A home value changed by the user is kept");
        Context navigation = new Context();
        check(!BarGesture.threeButton(navigation),"Missing navigation value is not 3-button");
        navigation.secure.put(BarGesture.NAV_KEY,"0");
        check(BarGesture.threeButton(navigation),"hide_navigationbar_enable 0 is 3-button");
        navigation.secure.put(BarGesture.NAV_KEY,"3");
        check(!BarGesture.threeButton(navigation),"Gesture navigation is not 3-button");
        // Power double press: journal before any write, write-only (hidden keys are unreadable), restore = unset.
        for (int mask = 0; mask < 32; mask++)
            check(PowerButton.wanted((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0,(mask&16)!=0) == (mask==31),"Power keys are handed over only when every condition holds ("+mask+")");
        Context keys = new Context();
        check(PowerButton.restore(keys) && keys.globalWrites==0,"Nothing applied: restore is a no-op");
        check(PowerButton.apply(keys) && "0".equals(keys.global.get(PowerButton.SHORT)) && "2".equals(keys.global.get(PowerButton.DOUBLE)) && PowerButton.applied(keys),"Apply sets short=nothing and double=wait");
        check(PowerButton.apply(keys) && keys.globalWrites==2,"Apply is idempotent within the process");
        check(PowerButton.restore(keys) && keys.global.isEmpty() && !PowerButton.applied(keys),"Restore puts both back to unset");
        Context custom = new Context();
        custom.global.put(PowerButton.SHORT,"1");
        check(PowerButton.apply(custom) && PowerButton.restore(custom) && custom.global.isEmpty(),"Restore is unset even over a hand-set value (documented ceiling)");
        Context denied = new Context(); denied.permission = -1;
        check(!PowerButton.apply(denied) && denied.globalWrites==0 && !PowerButton.applied(denied),"No WSS: nothing changes");
        // Double write succeeds, short write fails: both end unset and the journal is cleared.
        Context halfway = new Context();
        halfway.globalWritesFailAt = 2;
        check(!PowerButton.apply(halfway) && halfway.global.isEmpty() && !PowerButton.applied(halfway),"A failed second write is undone");
        Context pending = new Context();
        check(PowerButton.apply(pending),"Apply before WSS is revoked");
        pending.permission = -1;
        check(!PowerButton.restore(pending) && PowerButton.applied(pending) && "0".equals(pending.global.get(PowerButton.SHORT)),"Restore without WSS keeps the journal for later");
        pending.permission = 0;
        check(PowerButton.restore(pending) && pending.global.isEmpty(),"Restore completes once WSS is back");
        Context journal = new Context();
        journal.createDeviceProtectedStorageContext().getSharedPreferences("power_keys",0).failNextCommit = true;
        check(!PowerButton.apply(journal) && journal.globalWrites==0,"No journal, no system change");
        Context locked = new Context();
        check(PowerButton.apply(locked),"Apply before reboot");
        locked.credentialLocked = true;
        check(PowerButton.restore(locked) && locked.global.isEmpty(),"Restore works before the first unlock (device-protected journal)");
        System.out.println("PASS: " + checks + " stateful Android settings checks; actual recovery and gesture classes, no device used.");
    }
}
'@
foreach ($relative in $fixtures.Keys) {
    $target = Join-Path $stateFolder $relative
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $target))
    [IO.File]::WriteAllText($target,$fixtures[$relative],(New-Object Text.UTF8Encoding($false)))
}
$stateSources = @(Get-ChildItem -LiteralPath $stateFolder -Filter *.java -Recurse -File | Select-Object -ExpandProperty FullName)
$stateSources += Join-Path $PSScriptRoot 'micts/fast-launch/BootRecovery.java'
$stateSources += Join-Path $PSScriptRoot 'micts/fast-launch/BarGesture.java'
$stateSources += Join-Path $PSScriptRoot 'micts/fast-launch/VoiceRepair.java'
$stateSources += Join-Path $PSScriptRoot 'micts/fast-launch/PowerButton.java'
$stateClasses = Join-Path $stateFolder 'classes'
& javac.exe --release 17 -encoding UTF-8 -d $stateClasses @stateSources
if ($LASTEXITCODE) { throw 'Stateful Android test compilation failed' }
& java.exe -cp $stateClasses dev.padhelper.fastcts.StateHarness
if ($LASTEXITCODE) { throw 'Stateful Android regression failed' }

$manifest = New-Object Xml.XmlDocument
$manifest.Load((Join-Path $PSScriptRoot 'micts/fast-launch/AndroidManifest.xml'))
$namespaces = New-Object Xml.XmlNamespaceManager($manifest.NameTable)
$namespaces.AddNamespace('android','http://schemas.android.com/apk/res/android')
$command = $manifest.SelectSingleNode('/manifest/application/activity[@android:name=".SetupCommandActivity"]',$namespaces)
if (-not $command -or $command.GetAttribute('exported',$namespaces.LookupNamespace('android')) -ne 'true' -or
    $command.GetAttribute('permission',$namespaces.LookupNamespace('android')) -ne 'android.permission.DUMP') {
    throw 'Automation entry must require the platform DUMP permission'
}
$publicSettings = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'micts/fast-launch/SetupActivity.java'))
if ($publicSettings -match 'get(?:Boolean|String|Int)Extra\s*\(\s*"setup_') { throw 'Public settings must not execute automation extras' }
$securityChecks = 2
if ($manifest.SelectNodes('/manifest/application/provider',$namespaces).Count -ne 0) {
    throw 'The retired shell registration provider must not return; the app needs no content provider'
}
$securityChecks++
$bridge = $manifest.SelectSingleNode('/manifest/application/activity[@android:name=".LogAccessActivity"]',$namespaces)
if (-not $bridge -or $bridge.GetAttribute('exported',$namespaces.LookupNamespace('android')) -ne 'false' -or
    $bridge.GetAttribute('excludeFromRecents',$namespaces.LookupNamespace('android')) -ne 'true' -or $bridge.SelectNodes('intent-filter').Count -ne 0) {
    throw 'Log approval bridge must be internal, filterless and hidden from recents'
}
$securityChecks++
$permissions = @($manifest.SelectNodes('/manifest/uses-permission',$namespaces) | ForEach-Object { $_.GetAttribute('name',$namespaces.LookupNamespace('android')) } | Sort-Object)
if (($permissions -join ',') -cne 'android.permission.INTERNET,android.permission.READ_LOGS,android.permission.RECEIVE_BOOT_COMPLETED,android.permission.REQUEST_INSTALL_PACKAGES,android.permission.WRITE_SECURE_SETTINGS') {
    throw ('Unexpected permission set: ' + ($permissions -join ','))
}
$securityChecks++
if ($manifest.OuterXml -match 'foregroundServiceType|FOREGROUND_SERVICE|SYSTEM_ALERT_WINDOW|android:process=') {
    throw 'Log session must stay in the accessibility service: no foreground service, overlay or extra process'
}
$securityChecks++
$appFolder = Join-Path $PSScriptRoot 'micts/fast-launch'
foreach ($retired in @('NativeBarAgent','NativeBarBridge','NativeBarProtocol','NativeBarProvider','NativeBarWire')) {
    if (Test-Path -LiteralPath (Join-Path $appFolder ($retired+'.java'))) { throw ('Retired ADB agent backend still present: '+$retired) }
    $securityChecks++
}
$allSources = @{}
Get-ChildItem -LiteralPath $appFolder -Filter *.java -File | ForEach-Object { $allSources[$_.Name] = [IO.File]::ReadAllText($_.FullName) }
foreach ($name in $allSources.Keys) {
    if ($allSources[$name] -match 'ProcessBuilder|Runtime\.getRuntime\(\)\.exec|/system/bin/logcat|app_process|NativeBarAgent') {
        throw ('No logcat child, shell agent or command execution is allowed: '+$name)
    }
}
$securityChecks++
# Network only in the updater, only to the repository's GitHub hosts, only from the settings screen (button, or one
# opt-out check per open that only shows a banner; installing still needs the user's tap and Android's confirmation).
$network = @($allSources.Keys | Where-Object { $allSources[$_] -match 'java\.net\.|HttpsURLConnection|HttpURLConnection|URLConnection|okhttp|WebView' } | Sort-Object)
if (($network -join ',') -cne 'AppUpdate.java') { throw ('Network code is allowed only in AppUpdate.java: ' + ($network -join ',')) }
$hosts = @([regex]::Matches($allSources['AppUpdate.java'], 'https://[^"/]+') | ForEach-Object { $_.Value } | Sort-Object -Unique)
if (($hosts -join ',') -cne 'https://github.com,https://raw.githubusercontent.com') { throw ('Updater may contact only GitHub: ' + ($hosts -join ',')) }
$updateUsers = @($allSources.Keys | Where-Object { $_ -ne 'AppUpdate.java' -and $allSources[$_] -match 'AppUpdate\.(check|install)\(' } | Sort-Object)
if (($updateUsers -join ',') -cne 'SetupActivity.java' -or
    $allSources['SetupActivity.java'] -notmatch 'button\("업데이트 확인", this::checkUpdate\)' -or
    ([regex]::Matches($allSources['SetupActivity.java'], 'checkUpdate\(true\)')).Count -ne 1 -or
    $allSources['SetupActivity.java'] -notmatch 'if \(preferences\.getBoolean\(UPDATE_AUTO, true\)\) checkUpdate\(true\);' -or
    $allSources['SetupActivity.java'] -notmatch 'if \(auto\) \{ if \(failure == null && release != null\) showUpdateBanner\(release\); return; \}' -or
    ([regex]::Matches($allSources['SetupActivity.java'], 'installUpdate\(release\)')).Count -ne 2 -or
    $allSources['AppUpdate.java'] -notmatch '(?s)if \(!hex\(digest\.digest\(\)\)\.equals\(release\.sha256\)\) throw[^;]*;.*session\.commit\(' -or
    $allSources['AppUpdate.java'] -notmatch 'if \(!committed\) installer\.abandonSession\(id\);') {
    throw 'Updates run only from the settings screen (button or opt-out check on open), install only on a tap, and a session is committed only after the SHA-256 matches'
}
$updateReceiver = $manifest.SelectSingleNode('/manifest/application/receiver[@android:name=".AppUpdate$Result"]',$namespaces)
if (-not $updateReceiver -or $updateReceiver.GetAttribute('exported',$namespaces.LookupNamespace('android')) -ne 'false' -or $updateReceiver.SelectNodes('intent-filter').Count -ne 0) {
    throw 'Install status receiver must be internal and filterless'
}
$securityChecks++
$opened = @($allSources.Keys | Where-Object { $_ -ne 'BarService.java' -and $allSources[$_] -match 'openLogSession\s*\(' })
if (($opened -join ',') -cne 'LogAccessActivity.java') { throw 'Only the focused bridge may open a new log session' }
$securityChecks++
if ($allSources['LogSession.java'] -match 'FileOutputStream|openFileOutput|getSharedPreferences|Log\.[a-z]\([^;]*new String') {
    throw 'Log session must not persist or re-log raw log content'
}
$securityChecks++
if ($allSources['BarService.java'] -notmatch 'GeminiSession\.launch\(this, lockedAllowed\)') { throw 'Power trigger must dispatch to GeminiSession.launch' }
$securityChecks++
if ($allSources['LogSession.java'] -notmatch '(?s)handler\.post\(\(\) -> \{\s*if \(!current\(session\)\) return;\s*long delivered = System\.currentTimeMillis\(\);\s*if \(LogTrigger\.deliverable\(entry, delivered\)\) listener\.trigger\(kind\);') {
    throw 'Queued triggers must recheck session and freshness on the main thread before dispatch'
}
$securityChecks++
if (([regex]::Matches($allSources['LogSession.java'], 'listener\.trigger\(')).Count -ne 1) { throw 'The freshness-checked post must be the only trigger dispatch' }
$securityChecks++
if ($allSources['LogSession.java'] -notmatch 'input\.read\(packet, 0, packet\.length\)\)\s*>\s*0') { throw 'A zero-length SEQPACKET read (EOF) must end the session, not spin' }
$securityChecks++
if ($allSources['LogSession.java'] -notmatch 'diagnostics < MAX_DIAGNOSTICS' -or $allSources['LogSession.java'] -match 'Log\.[a-z]\([^;]*new String\(packet') {
    throw 'Log diagnostics must stay bounded and structural'
}
$securityChecks++
$service = $allSources['BarService.java']
$method = '(?:(?!\r?\n    (?:private|static|@Override)).)*' # stays inside one method body
$repairUsers = @($allSources.Keys | Where-Object { $_ -ne 'VoiceRepair.java' -and $allSources[$_] -match 'VoiceRepair\.(run|begin|prepare)\(' } | Sort-Object)
if (($repairUsers -join ',') -cne 'BarService.java,SetupActivity.java' -or
    ([regex]::Matches($service, 'VoiceRepair\.(run|begin|prepare)\(')).Count -ne 2 -or
    $service -notmatch ('(?s)private void powerGesture\(\) \{' + $method + 'VoiceRepair\.prepare\(app\)') -or
    $service -notmatch ('(?s)private final Runnable warm = \(\) -> \{' + $method + '!power\(this\)' + $method + 'VoiceRepair\.run\(app\)') -or
    $service -notmatch 'else if \(kind == LogTrigger\.GOOGLE_DIED\) googleDied\(\);' -or
    ([regex]::Matches($allSources['SetupActivity.java'], 'VoiceRepair\.(run|begin|prepare)\(')).Count -ne 1 -or
    $allSources['SetupActivity.java'] -notmatch ('(?s)private void repairVoice\(\) \{' + $method + 'VoiceRepair\.run\(app\)')) {
    throw 'Google voice rebind runs only per accepted power press, the power-option warm-up after a Google :interactor kill, and the explicit repair button (never BAR/touch CTS)'
}
$securityChecks++
if ($service -notmatch 'VoiceRepair\.recover\(this\)') { throw 'Service start must finish an interrupted voice rebind' }
$securityChecks++
# Order: worker rebinds, then posts; the main thread rechecks the request and only then asks for Gemini.
if ([regex]::Matches($service, 'GeminiSession\.launch\(').Count -ne 1 -or
    $service -notmatch '(?s)rebind = VoiceRepair\.prepare\(app\);.{0,200}handler\.post\(\(\) -> finishPower\(request, since, result, lockedAllowed\)\)' -or
    $service -notmatch ('(?s)private void finishPower\(long request, long since, int rebind, boolean lockedSnapshot\) \{\s*powerPending = false;' + $method +
        'boolean lockedAllowed = lockedSnapshot && lockedAssist\(this\);\s*if \(instance != this \|\| request != powerRequest \|\| !powerAllowed\(lockedAllowed, age\)\)' + $method +
        'boolean started = VoiceRepair\.ready\(rebind\) && GeminiSession\.launch\(this, lockedAllowed\);') -or
    $service -notmatch ('(?s)private void powerGesture\(\) \{' + $method + 'if \(powerPending\) \{' + $method + 'powerPending = true;') -or
    $service -notmatch ('(?s)private void powerGesture\(\) \{\s*(?://[^\n]*\s*)?boolean lockedAllowed = lockedAssist\(this\);\s*if \(!powerAllowed\(lockedAllowed, 0\)\) \{')) {
    throw 'Gemini may be requested only after the rebind finished and the request was rechecked'
}
$securityChecks++
# Power double press: the only global action is the screen-off for a logged single press while our power-key settings are
# applied; the two power_button_* settings are written only by PowerButton (journal, exact restore).
$allowedLock = 'performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)'
$joinedSources = $allSources.Values -join "`n"
if (([regex]::Matches($joinedSources, 'performGlobalAction\(')).Count -ne 1 -or
    ([regex]::Matches($service, [regex]::Escape($allowedLock))).Count -ne 1 -or
    $service -notmatch ('(?s)private void singlePress\(\) \{\s*if \(!PowerButton\.applied\(this\) \|\| !getSystemService\(PowerManager\.class\)\.isInteractive\(\)\) return;\s*if \(' + [regex]::Escape($allowedLock) + '\) return;' + $method + 'PowerButton\.restore\(this\);') -or
    @($allSources.Keys | Where-Object { $allSources[$_] -match 'Settings\.Global\.put|power_button_' }) -join ',' -cne 'PowerButton.java' -or
    $service -notmatch '(?s)if \(PowerButton\.wanted\(doublePress\(this\), doubleTarget\(this\), instance == this && granted\(this\), BootRecovery\.permitted\(this\),\s*logs\.state\(\) == LogSession\.ACTIVE\)\) \{' + $method + '\} else PowerButton\.restore\(this\);' -or
    $service -notmatch '(?s)logs\.close\(\);\s*PowerButton\.restore\(this\);' -or
    $allSources['BootReceiver.java'] -notmatch '(?s)LOCKED_BOOT_COMPLETED\.equals\(intent\.getAction\(\)\)\) \{' + $method + 'PowerButton\.restore\(context\);' -or
    $service -notmatch 'static boolean doublePress\(Context context\) \{ return prefs\(context\)\.getBoolean\(POWER_DOUBLE, false\); \}') {
    throw 'Power double press: one screen-off action, power_button_* written only by PowerButton, applied only with a live session and restored on every gap'
}
$allowedSources = $joinedSources.Replace($allowedLock, '')
$securityChecks++
# One screen policy for press, late finish and GeminiSession: LogTrigger.powerLaunch/powerScreen; locked requests are gesture-only (no assist data/screenshot).
$gemini = $allSources['GeminiSession.java']
if ($service -notmatch '(?s)private boolean powerAllowed\(boolean lockedAllowed, long age\) \{\s*return LogTrigger\.powerLaunch\(power\(this\), granted\(this\), getSystemService\(PowerManager\.class\)\.isInteractive\(\),\s*getSystemService\(KeyguardManager\.class\)\.isKeyguardLocked\(\), lockedAllowed, logs\.state\(\) == LogSession\.ACTIVE, age\);' -or
    ([regex]::Matches($service, 'LogTrigger\.powerLaunch\(')).Count -ne 1 -or
    $service -match '(?s)private void (?:powerGesture|finishPower)\([^)]*\) \{(?:(?!\r?\n    (?:private|static|@Override)).)*unlockedScreen\(' -or
    $gemini -notmatch 'private static final int FLAGS = 7;' -or $gemini -notmatch 'static final int LOCKED_FLAGS = 4;' -or
    $gemini -notmatch '(?s)boolean locked = keyguard\.isKeyguardLocked\(\);\s*if \(!LogTrigger\.powerScreen\(power\.isInteractive\(\), locked, lockedAllowed\)\) return false;\s*int flags = locked \? LOCKED_FLAGS : FLAGS;' -or
    ([regex]::Matches($gemini, 'showSessionFromSession", null, args, flags')).Count -ne 2 -or ([regex]::Matches($gemini, ', FLAGS[,)]')).Count -ne 0 -or
    ([regex]::Matches($gemini, 'LogTrigger\.powerScreen\(')).Count -ne 2 -or
    $allowedSources -match 'requestDismissKeyguard|disableKeyguard|newKeyguardLock|FLAG_DISMISS_KEYGUARD|FLAG_SHOW_WHEN_LOCKED|setShowWhenLocked|setTurnScreenOn|FLAG_TURN_SCREEN_ON|ACQUIRE_CAUSES_WAKEUP|newWakeLock|wakeUp\(|launchVoiceAssistFromKeyguard|LOCK_SCREEN|lockscreen') {
    throw 'Locked-screen assistant: one shared gate, gesture-only flags while locked, no keyguard dismissal, wake or lock-setting changes'
}
$securityChecks++
# The bottom trigger (CTS / chosen app) stays unlocked-only regardless of the locked-screen option.
if ($service -notmatch 'private boolean maySearch\(\) \{ return enabled\(this\) && granted\(this\) && unlockedScreen\(\); \}' -or
    $service -match ('(?s)private (?:void|boolean|String) (?:barGesture|launchSearch|maySearch|searchBlocked|attach)\([^)]*\) \{' + $method + 'lockedAssist') -or
    $allSources['MainActivity.java'] -match 'lockedAssist|POWER_LOCKED') {
    throw 'CTS and chosen-app launches must stay blocked while locked'
}
$securityChecks++
# Cancellation: screen off, power option off, log-session change and service destruction all invalidate a pending request.
if ($service -notmatch 'ACTION_SCREEN_OFF\.equals\(intent\.getAction\(\)\)\) \{[^}]*powerRequest\+\+;' -or
    $service -notmatch 'if \(!power\(this\)\) powerRequest\+\+;' -or
    $service -notmatch 'stateChanged\(boolean declined\) \{\s*powerRequest\+\+;' -or
    $service -notmatch 'onDestroy\(\) \{\s*if \(instance == this\) instance = null;\s*powerRequest\+\+;' -or
    $service -notmatch '(?s)static void setLockedAssist\(Context context, boolean on\) \{\s*prefs\(context\)\.edit\(\)\.putBoolean\(POWER_LOCKED, on\)\.apply\(\);\s*BarService service = instance;\s*if \(service != null\) service\.powerRequest\+\+;' -or
    $service -notmatch 'boolean lockedAssist\(Context context\) \{ return prefs\(context\)\.getBoolean\(POWER_LOCKED, false\); \}' -or
    $service -match ('(?s)private void (?:barGesture|launchSearch)\(\) \{' + $method + '(?:powerRequest|powerPending|VoiceRepair)')) {
    throw 'Pending power requests must be cancelled on lock/off/session change/destroy, without touching BAR/touch CTS'
}
$securityChecks++
# Start and the power switch never rebind or toast about Google; only the explicit repair button gives feedback.
$setup = $allSources['SetupActivity.java']
$begin = [regex]::Match($setup, '(?s)private void begin\(\) \{(.*?)\r?\n    \}').Groups[1].Value
$powerToggle = [regex]::Match($setup, '(?s)powerSwitch = toggle\((.*?)\r?\n        \}\);').Groups[1].Value
if (-not $begin -or -not $powerToggle -or ($begin + $powerToggle) -match 'repairVoice|VoiceRepair|Toast|Google' -or
    $setup -match 'repairVoice\((?:true|false)\)' -or ([regex]::Matches($setup, 'repairVoice\b')).Count -ne 2 -or
    $setup -notmatch 'button\("Google 음성 연결 다시 시작", this::repairVoice\);') {
    throw 'Start and enabling the power option must stay silent about Google; repair feedback only from its button'
}
$securityChecks++
# Programmatic sync (onResume/refresh) must never act as a user change: no repair, mode, power or boot-option setter.
$settingsSource = $allSources['SetupActivity.java']
$unguarded = [regex]::Replace($settingsSource, '(?s)syncing = true;\s*try \{.*?\} finally \{ syncing = false; \}', '')
$unguarded = $unguarded.Replace('modeGroup.check(BarService.nativeMode(this) ? colorId : touchId);', '') # initial state, before the listener exists
if ($unguarded -match 'powerSwitch\.setChecked\(|lockedSwitch\.setChecked\(|doubleSwitch\.setChecked\(|bootSwitch\.setChecked\(|modeGroup\.check\(' -or
    $settingsSource -notmatch '(?s)modeGroup\.setOnCheckedChangeListener\(\(group, selected\) -> \{\s*if \(syncing\) return;' -or
    $settingsSource -notmatch '(?s)powerSwitch = toggle\("[^"]*", BarService\.power\(this\), \(view, checked\) -> \{\s*if \(syncing\) return;' -or
    $settingsSource -notmatch '(?s)lockedSwitch = toggle\("[^"]*", BarService\.lockedAssist\(this\), \(view, checked\) -> \{\s*if \(syncing\) return;' -or
    $settingsSource -notmatch '(?s)doubleSwitch = toggle\("[^"]*", BarService\.doublePress\(this\), \(view, checked\) -> \{\s*if \(syncing\) return;' -or
    $settingsSource -notmatch '(?s)if \(consent\) builder\.setPositiveButton\("[^"]*", \(dialog, which\) -> \{\s*BarService\.setDoublePress\(this, true\);' -or
    ([regex]::Matches($settingsSource, 'setDoublePress\(this, true\)')).Count -ne 1 -or
    $settingsSource -notmatch '(?s)bootSwitch = toggle\("[^"]*", BootRecovery\.wanted\(this\), \(view, checked\) -> \{\s*if \(syncing\) return;') {
    throw 'Settings refresh must not trigger repair or enable/disable setters'
}
$securityChecks++
if ([regex]::Matches($allSources['VoiceRepair.java'], 'Settings\.Secure\.put\w*\(context\.getContentResolver\(\), KEY,').Count -ne 2 -or
    $allSources['VoiceRepair.java'] -match '"assistant"|Settings\.(Global|System)\.put|ROLE_ASSISTANT|RoleManager') {
    throw 'Voice repair may only clear and restore voice_interaction_service'
}
$securityChecks++
if ($allSources['GeminiSession.java'] -match 'ACTION_ASSIST|ACTION_VOICE_COMMAND|VOICE_ASSIST|ACTION_SEARCH_LONG_PRESS|getLaunchIntentForPackage|makeMainActivity|CATEGORY_LAUNCHER|startActivity') {
    throw 'Small Gemini must not fall back to launcher, ASSIST or voice-command intents'
}
$securityChecks++
$launcher = $manifest.SelectSingleNode('/manifest/application/activity[@android:name=".MainActivity"]',$namespaces)
if (-not $launcher -or $launcher.GetAttribute('exported',$namespaces.LookupNamespace('android')) -ne 'false') {
    throw 'Internal touch-trigger activity must not be exported'
}
$securityChecks++
$barService = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'micts/fast-launch/BarService.java'))
if ($barService -match 'GLOBAL_ACTION_BACK') { throw 'Touch-area service must not redirect through global back actions' }
$securityChecks++
# Accessibility events stay fully unsubscribed: no window events, packages or flags, and no event handling.
if ($barService -match 'TYPE_WINDOW_|TYPE_VIEW_|com\.heytap\.speechassist|com\.coloros\.colordirectservice|FLAG_RETRIEVE_INTERACTIVE_WINDOWS|FLAG_REPORT_VIEW_IDS|FLAG_REQUEST_' -or
    $barService -notmatch 'info\.eventTypes = 0;\s*info\.packageNames = null;\s*info\.flags = 0;\s*setServiceInfo\(info\);' -or
    ([regex]::Matches($barService, '\.eventTypes\s*=')).Count -ne 1 -or ([regex]::Matches($barService, '\.packageNames\s*=')).Count -ne 1 -or
    ([regex]::Matches($barService, 'info\.flags\s*=')).Count -ne 1 -or
    $barService -notmatch 'public void onAccessibilityEvent\(AccessibilityEvent event\) \{ \}') {
    throw 'Touch-area service must keep eventTypes 0, packageNames null and flags 0, with an empty event handler'
}
$securityChecks++
# Retired boot auto-Allow: no consent state, node/window reading, clicks or approval history anywhere.
if (Test-Path -LiteralPath (Join-Path $appFolder 'BootConsent.java')) { throw 'Retired boot auto-Allow source must be deleted' }
if ($allowedSources -match 'BootConsent|performAction|ACTION_CLICK|AccessibilityNodeInfo|AccessibilityWindowInfo|getWindows\(|getRootInActiveWindow|findAccessibilityNodeInfos|getSource\(|log_approved_boot|LOG_APPROVED|bootCompleted|AutoApproval|autoBridge|autoSession|dispatchGesture|takeScreenshot|getBoundsIn|performGlobalAction|ACTION_SET_TEXT') {
    throw 'No automatic Allow: no node or window reading, clicks, gestures, screenshots or boot approval history'
}
$securityChecks++
# The log-access confirmation is always answered by the user: manual requests and the first-unlock prompt only open it.
if ($barService -notmatch 'static void requestApproval\(Context context\) \{\s*prefs\(context\)\.edit\(\)\.putBoolean\(LOG_DECLINED, false\)\.apply\(\);\s*launchBridge\(context\);\s*\}' -or
    $barService -notmatch 'bar\.edit\(\)\.putInt\(LOG_AUTO, attempts \+ 1\)\.apply\(\);\s*launchBridge\(this\);\s*\}' -or
    $allSources['BootReceiver.java'] -match 'BarService\.') {
    throw 'Manual and first-unlock log approval prompts must only be shown, never answered by the app'
}
$securityChecks++
if ($publicSettings -match 'nativeSwitch|setup_native_bar|기본 바 애니메이션 연결|\bNativeBar\b') {
    throw 'Settings must not expose the retired native assistant bridge'
}
$securityChecks++
$commandSource = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'micts/fast-launch/SetupCommandActivity.java'))
if ($commandSource -match '(?:hasExtra|getBooleanExtra)\s*\(\s*"setup_native_bar"') {
    throw 'Protected automation must not re-enable the retired native bridge'
}
$securityChecks++
if (($barService + $publicSettings + $commandSource) -match '(?:getBoolean|putBoolean)\s*\(\s*"native_bar"') {
    throw 'Retired native_bar preference must not select or re-enable a trigger'
}
$securityChecks++
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'micts/fast-launch/NativeBar.java')) {
    throw 'Retired native bridge source must not be compiled into the touch-area app'
}
$securityChecks++
$metadata = New-Object Xml.XmlDocument
$metadata.Load((Join-Path $PSScriptRoot 'micts/fast-launch/res/xml/bottom_search_accessibility.xml'))
foreach ($capability in @('canRetrieveWindowContent','canPerformGestures','canTakeScreenshot','isAccessibilityTool')) {
    if ($metadata.DocumentElement.GetAttribute($capability,$namespaces.LookupNamespace('android')) -ne 'false') {
        throw ('Touch-area service must not request capability: '+$capability)
    }
    $securityChecks++
}
# No static event, package or flag subscription in XML either.
if (@($metadata.DocumentElement.Attributes | Where-Object { $_.LocalName -in @('accessibilityEventTypes','packageNames','accessibilityFlags','canRequestFilterKeyEvents','canRequestTouchExplorationMode','canControlMagnification') }).Count -ne 0) {
    throw 'Accessibility metadata must not subscribe to events, packages or flags'
}
$securityChecks++
# One CTS classification: readiness warnings and the launch path agree (default target or MiCTS picked from the list).
if ($allSources['SetupActivity.java'] -notmatch 'MainActivity\.cts\(preferences\.getString\(MainActivity\.TARGET_COMPONENT, ""\)\)' -or
    $allSources['MainActivity.java'] -notmatch 'if \(!cts\(selected\)\) \{' -or
    ([regex]::Matches($allSources['MainActivity.java'], '"com\.parallelc\.micts\.ui\.activity\.MainActivity"')).Count -ne 2) {
    throw 'Settings readiness and MainActivity must share one CTS target classification'
}
$securityChecks++
# Last-attempt diagnostics record request acceptance/handoff only; they never claim a visible CTS or Gemini window.
foreach ($name in @('BarService.java','MainActivity.java','StatusPolicy.java')) {
    if ($allSources[$name] -match '떴|열렸|표시됨|성공') { throw ('Diagnostics must not claim visual success: '+$name) }
}
$securityChecks++
Write-Output ('PASS: '+$securityChecks+' automation, log-session and accessibility security checks; retired agent, window handoff, window content, gesture and screenshot capabilities remain disabled; no automatic Allow.')
