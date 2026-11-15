param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$jar = Join-Path $Sdk 'platforms/android-37.0/android.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Android SDK platform android-37.0 is required for this developer test.' }
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
        check(BarGesture.releaseShouldTrigger(400, false, false));
        check(!BarGesture.releaseShouldTrigger(399, false, false));
        check(!BarGesture.releaseShouldTrigger(1000, true, false));
        check(!BarGesture.releaseShouldTrigger(1000, false, true));
        System.out.println("PASS: " + checks + " Android recovery checks; actual app policy and service-list code, no device used.");
    }
}
'@
[IO.File]::WriteAllText($harness,$source,(New-Object Text.UTF8Encoding($false)))
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'micts/fast-launch') -Filter *.java -File | Select-Object -ExpandProperty FullName)
& javac.exe --release 17 -encoding UTF-8 -classpath $jar -d $folder @sources $harness
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
    public Editor edit() { return new Editor(); }
    public final class Editor {
        private final Map<String,Object> pending = new HashMap<>();
        public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
        public Editor putInt(String key, int value) { pending.put(key, value); return this; }
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
    public final Map<String,String> secure = new HashMap<>();
    public int permission, bootCount = 4, writes, failIntWrites, failStringWrites;
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
        public static String getString(Context context, String key) { return context.secure.get(key); }
        public static int getInt(Context context, String key, int fallback) {
            String value = getString(context,key); return value == null ? fallback : Integer.parseInt(value);
        }
        public static boolean putString(Context context, String key, String value) {
            context.writes++;
            if (context.beforeWrite != null) context.beforeWrite.run();
            if (context.failStringWrites > 0) { context.failStringWrites--; return false; }
            context.secure.put(key,value); return true;
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
$provider = $manifest.SelectSingleNode('/manifest/application/provider[@android:name=".NativeBarProvider"]',$namespaces)
if (-not $provider -or $provider.GetAttribute('exported',$namespaces.LookupNamespace('android')) -ne 'true' -or
    $provider.GetAttribute('permission',$namespaces.LookupNamespace('android')) -ne 'android.permission.DUMP' -or
    $provider.GetAttribute('authorities',$namespaces.LookupNamespace('android')) -ne 'dev.padhelper.fastcts.nativebar') {
    throw 'Native bar registration must require DUMP on the fixed app authority'
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
if ($barService -match 'TYPE_WINDOW_STATE_CHANGED|com\.heytap\.speechassist|com\.coloros\.colordirectservice') {
    throw 'Touch-area service must not subscribe to OEM assistant windows'
}
$securityChecks++
if ($barService -notmatch '\.eventTypes\s*=\s*0\s*;' -or $barService -notmatch '\.packageNames\s*=\s*null\s*;') {
    throw 'Touch-area service must explicitly disable accessibility event subscriptions'
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
foreach ($capability in @('canRetrieveWindowContent','canPerformGestures','canTakeScreenshot')) {
    if ($metadata.DocumentElement.GetAttribute($capability,$namespaces.LookupNamespace('android')) -ne 'false') {
        throw ('Touch-area service must not request capability: '+$capability)
    }
    $securityChecks++
}
Write-Output ('PASS: '+$securityChecks+' automation and accessibility security checks; retired window handoff and screen capabilities remain disabled.')
