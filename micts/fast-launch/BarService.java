package dev.padhelper.fastcts;

import android.Manifest;
import android.app.*;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;

/** Event-driven touch target and owner of the single device-log session. No polling, network, wake lock, or screen capture. */
public final class BarService extends AccessibilityService {
    static final String MODE = "trigger_mode", TOUCH = "touch", NATIVE = "coloros_log", POWER = "power_gemini";
    static final String STATE_CHANGED = "dev.padhelper.fastcts.STATE_CHANGED";
    private static final String LOG_BOOT = "log_boot", LOG_DECLINED = "log_declined";
    /** Automatic prompts used this boot (at most LogTrigger.AUTO_PROMPTS); read by the settings status. */
    static final String LOG_AUTO = "log_auto";
    private static final long BRIDGE_LIFETIME = 13000;
    private static BarService instance;
    static volatile boolean running;
    private static volatile long bridgeSince;
    private static boolean preview;
    private final Handler handler = Handler.createAsync(Looper.getMainLooper());
    /** Taskbar or navigation mode switched: the default touch-area width may change. Fires only on a change. */
    private final android.database.ContentObserver barLayout = new android.database.ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { refreshArea(); }
    };
    private WindowManager windows;
    private View target;
    private float downX, downY;
    private boolean moved, holdFired;
    /** Hold time of the current press: read once at ACTION_DOWN, so the timer and the release check agree. */
    private long pressHold = TouchArea.HOLD;
    private int attachAttempts;
    private LogSession logs;
    /** Main thread only. One power rebind in flight; bumping powerRequest cancels it (screen off, power off, session change, destroy). */
    private boolean powerPending;
    private long powerRequest, powerAttempt;
    /**
     * Main thread only. After a kill, the next Gemini request reaches a cold :interactor and no window appears (device:
     * 11/11 after clear all); a rebind ahead of time restarts it (3/3). Timing: LogTrigger.Warm. warmOnScreen: a kill
     * seen while the screen was off, warmed at screen on.
     */
    private final LogTrigger.Warm warmTiming = new LogTrigger.Warm();
    private boolean warmOnScreen;
    /** Newest attempt per pathway only (StatusPolicy records); main thread only. */
    static final String LAST_BAR = "last_bar", LAST_POWER = "last_power", LAST_DOUBLE = "last_double";
    private static final String POWER_INPUT = "전원 버튼 길게 누르기 (기기 로그)";

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("bar", MODE_PRIVATE); }

    static long attemptBegin(Context context, String key, int outcome, String source, String detail) {
        SharedPreferences bar = prefs(context);
        String next = StatusPolicy.begin(bar.getString(key, null), System.currentTimeMillis(), outcome, source, detail);
        bar.edit().putString(key, next).apply();
        return StatusPolicy.id(next);
    }

    /** Ignored when stale: a newer attempt started or this one already has its result. */
    static void attemptUpdate(Context context, String key, long id, int outcome, String detail) {
        SharedPreferences bar = prefs(context);
        String next = StatusPolicy.update(bar.getString(key, null), id, System.currentTimeMillis(), outcome, detail);
        if (next != null) bar.edit().putString(key, next).apply();
    }

    /**
     * StatusPolicy.GOOGLE_*: selection plus whether that Google service component is installed and enabled (the Google
     * package is already in the manifest's queries). The live system binding is not observable here.
     */
    static int googleVoice(Context context) {
        try {
            String selected = Settings.Secure.getString(context.getContentResolver(), VoiceRepair.KEY);
            if (!VoiceRepair.googleSelected(selected)) return StatusPolicy.GOOGLE_NONE;
            ComponentName component = ComponentName.unflattenFromString(selected);
            // Flags 0: a disabled component or package is reported as not found.
            return component != null && context.getPackageManager().getServiceInfo(component, 0).isEnabled()
                ? StatusPolicy.GOOGLE_READY : StatusPolicy.GOOGLE_UNAVAILABLE;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return StatusPolicy.GOOGLE_UNAVAILABLE; }
    }

    static boolean ready(Context context) {
        if (nativeMode(context)) return instance != null && enabled(context) && logState() == LogSession.ACTIVE
            && BootRecovery.permitted(context) && Settings.Secure.getInt(context.getContentResolver(),
                BarGesture.threeButton(context) ? BarGesture.HOME_KEY : BarGesture.GESTURE_KEY, -1) == 1;
        return instance != null && enabled(context)
            && instance.target != null && instance.target.isAttachedToWindow();
    }

    static boolean nativeMode(Context context) { return NATIVE.equals(prefs(context).getString(MODE, TOUCH)); }
    static void setMode(Context context, String mode) {
        if (!TOUCH.equals(mode) && !NATIVE.equals(mode)) throw new IllegalArgumentException("Unknown trigger mode");
        prefs(context).edit().putString(MODE, mode).apply();
        refreshConfiguration();
    }

    /** Power long press -> small Gemini. Independent of the bottom trigger; on by default. */
    static boolean power(Context context) { return prefs(context).getBoolean(POWER, true); }
    static void setPower(Context context, boolean on) {
        prefs(context).edit().putBoolean(POWER, on).apply();
        refreshConfiguration();
    }

    /**
     * Sub-option of POWER, off by default: also request the assistant while the keyguard is locked (screen woken by the
     * power key itself). Never unlocks, dismisses or weakens the keyguard. Not before the first unlock after boot: this
     * service is not direct-boot aware and the log session needs the user's approval after unlock.
     */
    static final String POWER_LOCKED = "power_locked_assist";
    static boolean lockedAssist(Context context) { return prefs(context).getBoolean(POWER_LOCKED, false); }
    static void setLockedAssist(Context context, boolean on) {
        prefs(context).edit().putBoolean(POWER_LOCKED, on).apply();
        BarService service = instance;
        if (service != null) service.powerRequest++; // Any change cancels a request made under the old policy.
        refreshConfiguration();
    }

    /**
     * Opt-in power double press (PowerButton), off by default. Target: absent = nothing (the power key is then left to
     * the system), "" = circle to search, else a launcher component.
     */
    static final String POWER_DOUBLE = "power_double", POWER_DOUBLE_TARGET = "power_double_target", POWER_DOUBLE_LABEL = "power_double_label";
    private static final String DOUBLE_INPUT = "전원 버튼 두 번 누르기 (기기 로그)";
    static boolean doublePress(Context context) { return prefs(context).getBoolean(POWER_DOUBLE, false); }
    static boolean doubleTarget(Context context) { return prefs(context).contains(POWER_DOUBLE_TARGET); }
    static boolean doubleWanted(Context context) { return doublePress(context) && doubleTarget(context); }
    static void setDoublePress(Context context, boolean on) {
        prefs(context).edit().putBoolean(POWER_DOUBLE, on).apply();
        refreshConfiguration();
    }

    /**
     * Any of this app's own screens or its tile is running while the service is not: no reader, so the power key goes back
     * to the system. Device: a force stop marks the service crashed and ColorOS clears the enabled list, so the service
     * never comes back on its own and onDestroy never ran.
     */
    static void releaseOrphanedPowerKeys(Context context) { if (instance == null) PowerButton.restore(context); }

    static boolean logsPermitted(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean logsWanted(Context context) { return power(context) || doubleWanted(context) || (enabled(context) && nativeMode(context)); }
    static int logState() { BarService service = instance; return service == null ? LogSession.OFF : service.logs.state(); }
    static boolean serviceRunning() { return instance != null; }

    static void bridgeShowing(boolean showing) { bridgeSince = showing ? SystemClock.elapsedRealtime() : 0; }
    private static boolean bridgeShowing() {
        long since = bridgeSince;
        return since != 0 && SystemClock.elapsedRealtime() - since < BRIDGE_LIFETIME;
    }

    /** Called only by the focused bridge activity, so the request is made while this UID is TOP. */
    static boolean openLogSession(Context context) {
        BarService service = instance;
        if (service == null || !logsPermitted(context) || !logsWanted(context)) return false;
        boolean opened = service.logs.open();
        service.notifyState();
        return opened;
    }

    /**
     * The bridge timed out without focus, so it asked nothing: the next automatic prompt follows shortly (still within the
     * per-boot AUTO_PROMPTS cap). No refusal happened, so Android's post-refusal wait does not apply.
     */
    static void logBridgeUnused() {
        BarService service = instance;
        if (service == null) return;
        service.handler.removeCallbacks(service.autoRetry);
        service.handler.postDelayed(service.autoRetry, UNUSED_BRIDGE_RETRY);
    }
    private static final long UNUSED_BRIDGE_RETRY = 2_000;

    /** The bridge regained focus after its request: Android's confirmation closed (see LogSession.answered). */
    static void logAnswered() {
        BarService service = instance;
        if (service != null) service.logs.answered();
    }

    /**
     * Last refusal (wall clock, 0 = none) and refusals this boot, for the settings status. Device: after a refusal Android
     * silently refuses new requests for about a minute (DECLINE_COOLDOWN), so a retry before then shows nothing.
     */
    static final String LOG_DENIED_AT = "log_denied_at", LOG_DENIALS = "log_denials";
    static final long DECLINE_COOLDOWN = 60_000;

    /** Explicit user action from settings: one more standard confirmation, even after an earlier decline. */
    static void requestApproval(Context context) {
        prefs(context).edit().putBoolean(LOG_DECLINED, false).apply();
        launchBridge(context);
    }

    private static void launchBridge(Context context) {
        try {
            context.startActivity(new Intent(context, LogAccessActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException error) { Log.w("PadLog", "Approval bridge unavailable", error); }
    }

    static void refreshConfiguration() {
        if (instance != null) instance.configure();
    }

    static boolean enabled(Context context) {
        return prefs(context).getBoolean("enabled", false);
    }

    static void start(Context context) {
        prefs(context).edit().putBoolean("enabled", true).remove("native_bar").apply();
        if (instance != null) { running = true; instance.configure(); }
    }

    static void stop(Context context) {
        prefs(context).edit().putBoolean("enabled", false).apply();
        running = false;
        if (instance != null) { instance.cancelPending(); instance.detach(); instance.configure(); }
        if (!BarGesture.restore(context)) android.widget.Toast.makeText(context,
            "기본 바 설정 복구를 완료하지 못했습니다. 권한과 저장 공간을 확인한 뒤 다시 끄기를 눌러주세요.",
            android.widget.Toast.LENGTH_LONG).show();
    }

    static int holdMs(Context context) { return TouchArea.hold(prefs(context).getInt(TouchArea.HOLD_KEY, TouchArea.HOLD)); }

    /** Touch area for the current orientation, clamped to the screen (dp). */
    static int[] area(Context context, boolean landscape, int screenWidth, int screenHeight) {
        SharedPreferences bar = prefs(context);
        String taskbar = null, nav = null; // unreadable: narrow default
        try { taskbar = Settings.System.getString(context.getContentResolver(), SetupActivity.TASKBAR); } catch (RuntimeException unreadable) { }
        try { nav = Settings.Secure.getString(context.getContentResolver(), BarGesture.NAV_KEY); } catch (RuntimeException unreadable) { }
        boolean wide = TouchArea.taskbar(taskbar, nav);
        int[] raw = new int[4];
        for (int i = 0; i < 4; i++) raw[i] = bar.getInt(TouchArea.key(landscape, TouchArea.FIELDS[i]), TouchArea.fallback(TouchArea.FIELDS[i], wide, landscape));
        return TouchArea.area(raw[0], raw[1], raw[2], raw[3], screenWidth, screenHeight);
    }

    /** Applies a changed area to the attached touch target immediately (the hold time is read at each press). */
    static void refreshArea() {
        BarService service = instance;
        if (service == null || service.target == null || !service.target.isAttachedToWindow()) return;
        try { service.windows.updateViewLayout(service.target, service.areaParams()); }
        catch (IllegalArgumentException detached) { }
    }

    private WindowManager.LayoutParams areaParams() {
        android.graphics.Rect bounds = windows.getMaximumWindowMetrics().getBounds();
        float density = getResources().getDisplayMetrics().density;
        int[] area = area(this, bounds.width() > bounds.height(),
            Math.round(bounds.width() / density), Math.round(bounds.height() / density));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(area[2]), dp(area[3]),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.x = dp(area[0]);
        p.y = dp(area[1]);
        p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("PadBarTouchTarget");
        return p;
    }

    static void showPreview(boolean visible) {
        preview = visible;
        if (instance != null) instance.updateAppearance();
    }

    private void updateAppearance() {
        if (target == null) return;
        if (preview) {
            GradientDrawable area = new GradientDrawable();
            area.setColor(0x663B82F6);
            area.setStroke(dp(2), 0xFF2563EB);
            area.setCornerRadius(dp(8));
            target.setBackground(area);
        } else target.setBackground(null);
    }

    static boolean granted(Context context) {
        // The manager's enabled-service API can omit an approved service while
        // it is being rebound. Approval and live readiness are separate states.
        return BootRecovery.containsOwn(Settings.Secure.getString(context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
    }

    private final Runnable longPress = () -> {
        if (holdFired || moved || target == null || nativeMode(this)) return;
        holdFired = true;
        if (!maySearch()) { attemptBegin(this, LAST_BAR, StatusPolicy.CANCELLED, barInput(), searchBlocked()); return; }
        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        launchSearch();
    };

    private String barInput() {
        if (!nativeMode(this)) return "터치 영역 길게 누르기";
        return BarGesture.threeButton(this) ? "3버튼 홈 버튼 길게 누르기 (기기 로그)" : "ColorOS 기본 바 길게 누르기 (기기 로그)";
    }

    private String searchBlocked() {
        if (!enabled(this)) return "사용 꺼짐";
        if (!granted(this)) return "접근성 승인 해제됨";
        return unlockedScreen() ? "기기 로그 연결 끊김" : "화면 꺼짐 또는 잠금 상태";
    }

    private boolean unlockedScreen() {
        return getSystemService(PowerManager.class).isInteractive()
            && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private boolean maySearch() { return enabled(this) && granted(this) && unlockedScreen(); }

    /** Structural dispatch preconditions only (booleans and state), for distinguishing gate vs dispatch on device. */
    private String dispatchState() {
        PowerManager power = getSystemService(PowerManager.class);
        return "enabled=" + enabled(this) + " native=" + nativeMode(this) + " power=" + power(this) + " granted=" + granted(this)
            + " interactive=" + power.isInteractive() + " locked=" + getSystemService(KeyguardManager.class).isKeyguardLocked()
            + " lockedAssist=" + lockedAssist(this) + " double=" + doubleWanted(this) + " powerKeys=" + PowerButton.applied(this)
            + " session=" + logs.state();
    }

    /** One power policy for the press and the late finish gate (GeminiSession re-checks the same screen rule). */
    private boolean powerAllowed(boolean lockedAllowed, long age) {
        return LogTrigger.powerLaunch(power(this), granted(this), getSystemService(PowerManager.class).isInteractive(),
            getSystemService(KeyguardManager.class).isKeyguardLocked(), lockedAllowed, logs.state() == LogSession.ACTIVE, age);
    }

    private String powerBlocked(boolean lockedAllowed, long age) {
        return StatusPolicy.powerBlocked(power(this), granted(this), getSystemService(PowerManager.class).isInteractive(),
            getSystemService(KeyguardManager.class).isKeyguardLocked(), lockedAllowed, logs.state() == LogSession.ACTIVE, age);
    }

    private void barGesture() {
        if (nativeMode(this) && maySearch() && logs.state() == LogSession.ACTIVE) launchSearch();
        else {
            Log.i("PadBar", "bar dispatch skipped " + dispatchState());
            if (nativeMode(this) && enabled(this)) attemptBegin(this, LAST_BAR, StatusPolicy.CANCELLED, barInput(), searchBlocked());
        }
    }

    private void powerGesture() {
        // Snapshot at the press: switching the option on later never widens this request to a locked screen.
        boolean lockedAllowed = lockedAssist(this);
        if (!powerAllowed(lockedAllowed, 0)) {
            Log.i("PadPower", "power dispatch skipped " + dispatchState());
            if (power(this)) attemptBegin(this, LAST_POWER, StatusPolicy.CANCELLED, POWER_INPUT, powerBlocked(lockedAllowed, 0));
            return;
        }
        // The in-flight attempt keeps its record; this duplicate press is only logged.
        if (powerPending) { Log.i("PadPower", "power dispatch skipped rebind-pending"); return; }
        // Every accepted press rebinds the selected Google service first (it can go unresponsive after long idle).
        powerPending = true;
        long request = ++powerRequest, since = SystemClock.elapsedRealtime();
        powerAttempt = attemptBegin(this, LAST_POWER, StatusPolicy.PENDING, POWER_INPUT, "입력 인식 · Google 음성 연결 다시 시작 중");
        Context app = getApplicationContext();
        Log.i("PadPower", "power dispatch -> rebind request=" + request);
        try {
            new Thread(() -> {
                int rebind;
                try { rebind = VoiceRepair.prepare(app); }
                catch (RuntimeException unexpected) { rebind = VoiceRepair.ERROR; }
                int result = rebind;
                handler.post(() -> finishPower(request, since, result, lockedAllowed));
            }, "PadPowerRebind").start();
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            powerPending = false;
            Log.w("PadPower", "rebind thread unavailable: " + unavailable.getClass().getSimpleName());
            attemptUpdate(this, LAST_POWER, powerAttempt, StatusPolicy.FAILED,
                "Google 재연결 작업을 시작하지 못함 (" + unavailable.getClass().getSimpleName() + ")");
        }
    }

    /** Runs after the exact selection is restored and settled; drops the request if anything changed meanwhile. */
    private void finishPower(long request, long since, int rebind, boolean lockedSnapshot) {
        powerPending = false;
        long age = SystemClock.elapsedRealtime() - since;
        String rebound = VoiceRepair.describe(rebind);
        boolean lockedAllowed = lockedSnapshot && lockedAssist(this);
        if (instance != this || request != powerRequest || !powerAllowed(lockedAllowed, age)) {
            Log.i("PadPower", "power launch dropped request=" + request + " age=" + age + "ms current=" + (request == powerRequest)
                + " instance=" + (instance == this) + " " + dispatchState());
            String reason = instance != this ? "접근성 서비스 종료" : powerBlocked(lockedAllowed, age);
            attemptUpdate(this, LAST_POWER, powerAttempt, StatusPolicy.CANCELLED, rebound + " · Gemini 요청 취소: "
                + (reason != null ? reason : "화면 꺼짐·옵션 변경·기기 로그 세션 변경"));
            return;
        }
        Log.i("PadPower", "rebind outcome=" + rebind + " age=" + age + "ms -> GeminiSession.launch");
        boolean started = VoiceRepair.ready(rebind) && GeminiSession.launch(this, lockedAllowed);
        // true = the system accepted the session request; it does not prove an assistant window appeared.
        Log.i("PadPower", "GeminiSession.launch returned " + started + " (request acceptance, window unverified)");
        // Press-to-request time in the record: if a press feels slow, the record shows whether the delay was on this side.
        if (started) attemptUpdate(this, LAST_POWER, powerAttempt, StatusPolicy.ACCEPTED,
            rebound + " · 시스템이 Gemini 세션 요청을 수락함 (누름 후 " + (SystemClock.elapsedRealtime() - since)
                + "ms) · Gemini Assistant 창 표시는 확인하지 않음");
        else attemptUpdate(this, LAST_POWER, powerAttempt, StatusPolicy.FAILED, rebound + " · " + (!VoiceRepair.ready(rebind)
            ? "Gemini 요청 안 함" : googleVoice(this) == StatusPolicy.GOOGLE_READY ? "시스템이 Gemini 세션 요청을 수락하지 않음"
            : "Google 음성 서비스 미선택 또는 사용 불가"));
        if (!started) android.widget.Toast.makeText(this, "Gemini Assistant를 열지 못했습니다.", android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * Assistant quick settings tile (0.3.6): an explicit tap, so neither the power option nor a log session is needed.
     * Same order as a power press: rebind the selected Google service on a worker, then GeminiSession's shared screen
     * gate with the locked-screen option (snapshot at the tap, rechecked).
     */
    static void tileAssistant(Context context) {
        Context app = context.getApplicationContext();
        boolean lockedSnapshot = lockedAssist(app);
        Handler main = new Handler(Looper.getMainLooper());
        try {
            new Thread(() -> {
                int rebind;
                try { rebind = VoiceRepair.prepare(app); }
                catch (RuntimeException unexpected) { rebind = VoiceRepair.ERROR; }
                int result = rebind;
                main.post(() -> {
                    boolean started = VoiceRepair.ready(result) && GeminiSession.launch(app, lockedSnapshot && lockedAssist(app));
                    Log.i("PadTile", "assistant tile rebind=" + result + " started=" + started);
                    if (!started) android.widget.Toast.makeText(app, "Gemini Assistant를 열지 못했습니다.", android.widget.Toast.LENGTH_SHORT).show();
                });
            }, "PadTileAssistant").start();
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            Log.w("PadTile", "assistant tile thread unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    private void launchSearch() {
        launchTarget(LAST_BAR, attemptBegin(this, LAST_BAR, StatusPolicy.PENDING, barInput(), "입력 인식 · 실행 화면 시작 요청"), false);
    }

    /**
     * Circle to Search target: the direct system call from this running service (no activity hop). Any other target, or a
     * refused direct call (MiCTS fallback), goes through MainActivity.
     */
    private void launchTarget(String record, long attempt, boolean twice) {
        boolean cts = MainActivity.cts(prefs(this).getString(twice ? POWER_DOUBLE_TARGET : MainActivity.TARGET_COMPONENT, ""));
        if (cts && CtsSession.open(this)) {
            attemptUpdate(this, record, attempt, StatusPolicy.ACCEPTED, MainActivity.CTS_ACCEPTED);
            return;
        }
        try {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.ATTEMPT, attempt).putExtra(MainActivity.DOUBLE, twice)
                .putExtra(MainActivity.DIRECT_TRIED, cts).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException error) {
            Log.e("PadBar", "Search launch failed", error);
            attemptUpdate(this, record, attempt, StatusPolicy.FAILED, "실행 화면을 시작하지 못함 (" + error.getClass().getSimpleName() + ")");
        }
    }

    private void notifyState() {
        if (instance != this) return;
        configure();
        sendBroadcast(new Intent(STATE_CHANGED).setPackage(getPackageName()));
    }

    /** Main thread: the log session was ACTIVE at the last state change, so a drop to OFF is a lost connection. */
    private boolean logWasActive;

    /**
     * Shortly after the service connects, a wanted session that is still not approved. Device: after an update installed
     * while locked, no prompt could show and the unlock broadcast was skipped, so the session stayed off with no decline.
     * While the approval prompt is up the check waits for it instead (its answer, or its timeout, decides).
     */
    private final Runnable logCheck = new Runnable() {
        @Override public void run() {
            if (instance != BarService.this || !logsWanted(BarService.this) || !logsPermitted(BarService.this)
                    || logs.state() == LogSession.ACTIVE) return;
            if (bridgeShowing() || logs.state() == LogSession.CONNECTING) { handler.postDelayed(this, LOG_CHECK_DELAY); return; }
            Alerts.logMissing(BarService.this, true, "기기 로그 승인이 필요합니다", "ColorOS 기본 바·전원 버튼 실행이 멈춰 있습니다. 눌러서 승인해주세요.");
        }
    };
    /**
     * After a refusal, one automatic prompt per DECLINE_COOLDOWN (user decision), within the per-boot AUTO_PROMPTS cap and
     * only on an interactive, unlocked screen; afterwards the unlock watch takes over. The user still answers every prompt.
     * The notice follows a little later, so a running retry makes it wait instead of doubling up.
     */
    private final Runnable autoRetry = () -> maybeAutoPrompt(true);
    private static final long LOG_CHECK_DELAY = 15_000, RETRY_NOTICE_GAP = 2_000;

    private final LogSession.Listener logEvents = new LogSession.Listener() {
        @Override public void stateChanged(boolean declined) {
            powerRequest++; // A session change cancels a power request from the old session.
            SharedPreferences bar = prefs(BarService.this);
            if (declined) bar.edit().putBoolean(LOG_DECLINED, true).putLong(LOG_DENIED_AT, System.currentTimeMillis())
                .putInt(LOG_DENIALS, bar.getInt(LOG_DENIALS, 0) + 1).apply();
            int state = logs.state();
            if (state == LogSession.ACTIVE) {
                // Approved after all: an earlier (possibly timed-out) decline no longer holds back later auto prompts.
                bar.edit().putBoolean(LOG_DECLINED, false).remove(LOG_DENIED_AT).apply();
                handler.removeCallbacks(logCheck);
                handler.removeCallbacks(autoRetry);
                Alerts.logRestored(BarService.this);
            }
            else if (declined) {
                handler.removeCallbacks(autoRetry);
                handler.postDelayed(autoRetry, DECLINE_COOLDOWN);
                handler.removeCallbacks(logCheck);
                handler.postDelayed(logCheck, DECLINE_COOLDOWN + RETRY_NOTICE_GAP);
            }
            else if (logsWanted(BarService.this) && logWasActive && state == LogSession.OFF)
                Alerts.logMissing(BarService.this, true, "기기 로그 연결이 끊겼습니다", "ColorOS 기본 바·전원 버튼 실행이 멈췄습니다. 눌러서 다시 승인해주세요.");
            logWasActive = state == LogSession.ACTIVE;
            notifyState();
        }
        @Override public void trigger(int kind) {
            if (kind == LogTrigger.ANY_KILL) { anyKill(); return; } // frequent and routine: not logged
            Log.i("PadLog", "trigger kind=" + kind + " instance=" + (instance == BarService.this));
            if (kind == LogTrigger.BAR) barGesture();
            // Home long press exists only in 3-button navigation; elsewhere a caller-1000 Breeno line is not the bar.
            else if (kind == LogTrigger.HOME) {
                if (BarGesture.threeButton(BarService.this)) barGesture();
                else Log.i("PadBar", "home dispatch skipped: not 3-button navigation");
            }
            else if (kind == LogTrigger.POWER) powerGesture();
            else if (kind == LogTrigger.GOOGLE_DIED) googleDied();
            else if (kind == LogTrigger.POWER_SINGLE) singlePress();
            else if (kind == LogTrigger.POWER_DOUBLE) doublePressed();
        }
    };

    /** The system no longer turns the screen off on a short press while our settings are in place; this does it instead. */
    private void singlePress() {
        if (!PowerButton.applied(this) || !getSystemService(PowerManager.class).isInteractive()) return;
        if (performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) return;
        // Never leave a power key that does nothing: give the short press back to the system.
        Log.w("PadPowerKeys", "screen-off action refused; restoring power key settings");
        PowerButton.restore(this);
        android.widget.Toast.makeText(this, "화면을 끄지 못해 전원 버튼 설정을 원래대로 되돌렸습니다.", android.widget.Toast.LENGTH_LONG).show();
    }

    private void doublePressed() {
        if (!PowerButton.applied(this)) return;
        if (!doubleWanted(this) || !granted(this) || !unlockedScreen()) {
            Log.i("PadPowerKeys", "double press skipped " + dispatchState());
            attemptBegin(this, LAST_DOUBLE, StatusPolicy.CANCELLED, DOUBLE_INPUT,
                !doubleWanted(this) ? "두 번 누르기 꺼짐" : !granted(this) ? "접근성 승인 해제됨" : "화면 꺼짐 또는 잠금 상태");
            return;
        }
        launchTarget(LAST_DOUBLE, attemptBegin(this, LAST_DOUBLE, StatusPolicy.PENDING, DOUBLE_INPUT, "입력 인식 · 실행 화면 시작 요청"), true);
    }

    /** Google's :interactor was killed: restart it now (or at the next screen on) so the next power press is not cold. */
    private void googleDied() {
        if (!getSystemService(PowerManager.class).isInteractive()) { warmOnScreen = true; return; }
        scheduleWarm(warmTiming.googleDied(SystemClock.elapsedRealtime()));
    }

    /** Let the kill sweep (clear all) finish first: each further kill pushes the warm-up back, up to the deadline. */
    private void anyKill() {
        long at = warmTiming.anyKill(SystemClock.elapsedRealtime());
        if (at >= 0) scheduleWarm(at);
    }

    private void scheduleWarm(long at) {
        handler.removeCallbacks(warm);
        if (at < 0) { Log.i("PadVoice", "warm-up skipped: repeated Google kill"); return; }
        handler.postDelayed(warm, Math.max(0, at - SystemClock.elapsedRealtime()));
    }

    private final Runnable warm = () -> {
        warmOnScreen = false;
        if (instance != this || !power(this) || powerPending || !VoiceRepair.activationWanted(this, true)) {
            warmTiming.cancel();
            Log.i("PadVoice", "warm-up skipped");
            return;
        }
        warmTiming.warmed(SystemClock.elapsedRealtime());
        Context app = getApplicationContext();
        try {
            new Thread(() -> Log.i("PadVoice", "warm-up after Google kill restored=" + VoiceRepair.run(app)), "PadGoogleWarm").start();
        } catch (RuntimeException | OutOfMemoryError unavailable) { Log.w("PadVoice", "warm-up thread unavailable"); }
    };

    /**
     * First unlock after boot (or after the session was lost): at most AUTO_PROMPTS bridge windows per boot.
     * afterUnlock: a fresh unlock the user just made also retries after a "decline". Device: after boot the USB dialog
     * and ColorOS's accessibility warning took focus, the unanswered confirmation timed out and counted as declined,
     * which stopped every later prompt that boot. The per-boot cap still bounds a real refusal.
     */
    private void maybeAutoPrompt() { maybeAutoPrompt(false); }

    private void maybeAutoPrompt(boolean afterUnlock) {
        SharedPreferences bar = prefs(this);
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (bar.getInt(LOG_BOOT, -2) != boot)
            bar.edit().putInt(LOG_BOOT, boot).putInt(LOG_AUTO, 0).putBoolean(LOG_DECLINED, false).remove(LOG_DENIED_AT).putInt(LOG_DENIALS, 0).apply();
        int attempts = bar.getInt(LOG_AUTO, 0);
        PowerManager power = getSystemService(PowerManager.class);
        boolean declined = !afterUnlock && bar.getBoolean(LOG_DECLINED, false);
        long deniedAt = bar.getLong(LOG_DENIED_AT, 0);
        if (declined && deniedAt > 0 && logs.state() == LogSession.OFF) {
            // The retry after a refusal lives in this process's handler; a restart (reinstall, ColorOS killing the app)
            // lost it while the settings status still promised it. Re-arm it from the stored refusal time.
            handler.removeCallbacks(autoRetry);
            handler.postDelayed(autoRetry, Math.max(0, Math.min(DECLINE_COOLDOWN, deniedAt + DECLINE_COOLDOWN - System.currentTimeMillis())));
        }
        if (!LogTrigger.autoPrompt(granted(this) && logsWanted(this), logsPermitted(this), logs.state() != LogSession.OFF,
                bridgeShowing(), power.isInteractive(), getSystemService(KeyguardManager.class).isKeyguardLocked(),
                declined, attempts)) {
            Log.i("PadLog", "auto prompt skipped: state=" + logs.state() + " declined=" + declined + " attempts=" + attempts);
            return;
        }
        // First prompt of a boot waits until GUIDE_SETTLE after this service connected. Device (ColorOS 17): at unlock
        // ColorOS clears enabled_accessibility_services, boot recovery adds this service back, and ColorOS's anti-fraud
        // check (FraudBehaviorDetectManager) then opens its accessibility warning 30 s later, with a 4 s countdown that
        // ignores every other tap. A confirmation asked before it ended up under it, so "허용 안 함" or a tap beside the
        // dialog seemed to freeze the tablet. The bridge also asks only while it has focus.
        long sinceConnect = SystemClock.elapsedRealtime() - connectedAt;
        if (attempts == 0 && sinceConnect < GUIDE_SETTLE) {
            handler.removeCallbacks(bootPrompt);
            handler.postDelayed(bootPrompt, GUIDE_SETTLE - sinceConnect);
            Log.i("PadLog", "first prompt waits " + (GUIDE_SETTLE - sinceConnect) + " ms for the ColorOS accessibility warning");
            return;
        }
        bar.edit().putInt(LOG_AUTO, attempts + 1).apply();
        launchBridge(this);
    }

    private static final long GUIDE_SETTLE = 32_000;
    /** elapsedRealtime of onServiceConnected (accessibility just enabled or re-added). */
    private long connectedAt;
    private final Runnable bootPrompt = () -> maybeAutoPrompt(true);

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // The log session stays open across screen changes; only the touch window and OEM gesture follow the screen.
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { cancelPending(); detach(); powerRequest++; handler.removeCallbacks(unlockWatch); }
            configure();
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                if (warmOnScreen) googleDied();
                unlockPolls = 0;
                handler.removeCallbacks(unlockWatch);
                if (logsWanted(BarService.this) && logsPermitted(BarService.this) && logs.state() == LogSession.OFF)
                    handler.postDelayed(unlockWatch, UNLOCK_POLL);
            }
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) maybeAutoPrompt();
        }
    };

    /**
     * Device (ColorOS 17): USER_PRESENT to this service was skipped ("oplus skip sp"), so a session lost while the screen
     * was off never got its prompt on unlock. While the screen is on, locked and a wanted session is off, the keyguard is
     * checked once a second (at most UNLOCK_POLLS times); the first unlocked check runs the normal capped auto prompt.
     */
    private static final long UNLOCK_POLL = 1000;
    private static final int UNLOCK_POLLS = 60;
    private int unlockPolls;
    private final Runnable unlockWatch = new Runnable() {
        @Override public void run() {
            if (instance != BarService.this || !getSystemService(PowerManager.class).isInteractive()) return;
            if (!getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                Log.i("PadLog", "unlock seen after screen on: auto prompt check");
                maybeAutoPrompt(true);
            } else if (++unlockPolls < UNLOCK_POLLS) handler.postDelayed(this, UNLOCK_POLL);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        windows = getSystemService(WindowManager.class);
        VoiceRepair.recover(this); // A voice rebind interrupted by process death restores the exact original selection.
        int systemUi;
        try { systemUi = getPackageManager().getApplicationInfo("com.android.systemui", 0).uid; }
        catch (PackageManager.NameNotFoundException missing) { systemUi = -1; } // Bar events then never match.
        // With the taskbar on, the bar hold is reported by the launcher. Only this exact package counts, and only when it is
        // a preinstalled (or updated preinstalled) enabled app: never the current HOME or any other app. -1 never matches.
        int launcher = -1;
        try {
            ApplicationInfo home = getPackageManager().getApplicationInfo("com.android.launcher", 0);
            if (home.enabled && (home.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) launcher = home.uid;
        } catch (PackageManager.NameNotFoundException missing) { }
        int uid = getApplicationInfo().uid;
        logs = new LogSession(handler, logEvents, systemUi, launcher, uid / 100000);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        // These are protected system broadcasts; SystemUI may use a different UID.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, RECEIVER_EXPORTED);
        else registerReceiver(screen, filter);
        getContentResolver().registerContentObserver(Settings.System.getUriFor(SetupActivity.TASKBAR), false, barLayout);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(BarGesture.NAV_KEY), false, barLayout);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        connectedAt = SystemClock.elapsedRealtime();
        prefs(this).edit().putBoolean(BootRecovery.APPROVED, true).remove("native_bar").apply();
        running = enabled(this);
        Alerts.scheduleUpdateCheck(this); // restores the job after a force-stop or reinstall cleared it
        // An option that reads device logs is on, but the one-time ADB grant is missing (reinstall, revoke).
        if (logsWanted(this) && !logsPermitted(this)) Alerts.logMissing(this, false, "기기 로그 권한이 없습니다",
            "ColorOS 기본 바·전원 버튼 실행에 필요합니다. 눌러서 최초 ADB 설정 안내를 확인해주세요.");
        handler.removeCallbacks(logCheck);
        handler.postDelayed(logCheck, LOG_CHECK_DELAY);
        notifyState(); // configure + refresh any visible settings screen (it may predate this connection)
        maybeAutoPrompt();
    }

    private void configure() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        // Touch events belong to our own overlay. No other apps' window events. Written only when it differs: every
        // setServiceInfo makes system_server recompute the whole accessibility state (device: 3x per screen off/on/unlock).
        if (info.eventTypes != 0 || info.packageNames != null || info.flags != 0) {
            info.eventTypes = 0;
            info.packageNames = null;
            info.flags = 0;
            setServiceInfo(info);
        }
        running = enabled(this);
        handler.removeCallbacks(restoreTarget);
        handler.removeCallbacks(longPress);
        if (!power(this)) powerRequest++; // Power option off: no delayed Gemini request survives.
        if (!logsWanted(this) || !logsPermitted(this)) logs.close();
        // The power key is handed over only while every press can be seen; any gap gives it back to the system.
        if (PowerButton.wanted(doublePress(this), doubleTarget(this), instance == this && granted(this), BootRecovery.permitted(this),
                logs.state() == LogSession.ACTIVE)) {
            if (!PowerButton.apply(this)) Log.w("PadPowerKeys", "power key settings not applied");
        } else PowerButton.restore(this);
        if (!enabled(this)) detach();
        else if (nativeMode(this)) {
            detach();
            // The OEM gesture is on only while the session genuinely receives system logs; otherwise the original is restored.
            if (maySearch() && BootRecovery.permitted(this) && LogSession.breenoDisabled(this) && logs.state() == LogSession.ACTIVE)
                BarGesture.enableBreenoGesture(this);
            else BarGesture.restore(this);
        } else {
            attachAttempts = 0; handler.post(restoreTarget);
        }
    }

    private final Runnable restoreTarget = new Runnable() {
        @Override public void run() {
            if (instance != BarService.this || !enabled(BarService.this) || nativeMode(BarService.this)) return;
            attach();
            // Screen-on can arrive before unlock finishes. Retry only this transition.
            if ((target == null || !target.isAttachedToWindow()) && ++attachAttempts < 10
                    && getSystemService(PowerManager.class).isInteractive()) handler.postDelayed(this, 200);
        }
    };

    private void cancelPending() {
        handler.removeCallbacks(restoreTarget);
        handler.removeCallbacks(longPress);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void attach() {
        if (target != null && !target.isAttachedToWindow()) detach();
        if (!enabled(this) || nativeMode(this) || !granted(this) || instance != this || !unlockedScreen()) return;
        if (target != null) { BarGesture.disableBreenoGesture(this); return; }
        View view = new View(this);
        view.setContentDescription("Circle-to-Search Trigger 길게 누르기 영역");
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    moved = false; holdFired = false;
                    downX = event.getRawX(); downY = event.getRawY();
                    handler.removeCallbacks(longPress);
                    pressHold = holdMs(this);
                    handler.postDelayed(longPress, pressHold);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > slop) {
                        moved = true;
                        handler.removeCallbacks(longPress);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    // Some OEM input scheduling delays the timer until release.
                    if (BarGesture.releaseShouldTrigger(event.getEventTime()-event.getDownTime(), pressHold, moved, holdFired)) longPress.run();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    moved = true;
                    handler.removeCallbacks(longPress);
                    break;
            }
            return true;
        });
        try {
            windows.addView(view, areaParams());
            target = view;
            BarGesture.disableBreenoGesture(this);
            updateAppearance();
        }
        catch (RuntimeException error) { Log.e("PadBar", "Touch target unavailable", error); }
    }

    private void detach() {
        handler.removeCallbacks(longPress);
        if (target != null) {
            try { windows.removeViewImmediate(target); }
            catch (IllegalArgumentException ignored) { }
            target = null;
        }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); detach(); configure();
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        powerRequest++;
        handler.removeCallbacks(warm);
        handler.removeCallbacks(logCheck);
        handler.removeCallbacks(autoRetry);
        handler.removeCallbacks(bootPrompt);
        handler.removeCallbacks(unlockWatch);
        running = false;
        cancelPending();
        detach();
        logs.close();
        PowerButton.restore(this); // No reader, no screen-off from this app: the system short press comes back first.
        if (nativeMode(this)) BarGesture.restore(this); // No reader while unbound; reapplied after the next approved session.
        unregisterReceiver(screen);
        getContentResolver().unregisterContentObserver(barLayout);
        super.onDestroy();
    }

    @Override public boolean onUnbind(Intent intent) {
        String approved = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (!BootRecovery.containsOwn(approved)) BarGesture.restore(this);
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
