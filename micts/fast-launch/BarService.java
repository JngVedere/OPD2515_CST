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
    private static final String LOG_BOOT = "log_boot", LOG_AUTO = "log_auto", LOG_DECLINED = "log_declined";
    private static final long BRIDGE_LIFETIME = 13000;
    private static BarService instance;
    static volatile boolean running;
    private static volatile long bridgeSince;
    private static boolean preview;
    private final Handler handler = Handler.createAsync(Looper.getMainLooper());
    private WindowManager windows;
    private View target;
    private float downX, downY;
    private boolean moved, holdFired;
    private int attachAttempts;
    private LogSession logs;
    /** Main thread only. One power rebind in flight; bumping powerRequest cancels it (screen off, power off, session change, destroy). */
    private boolean powerPending;
    private long powerRequest, powerAttempt;
    /** Main thread only: last warm-up (elapsedRealtime) and a kill seen while the screen was off (warmed at screen on). */
    private long lastWarm = -WARM_INTERVAL;
    private boolean warmOnScreen;
    /**
     * After a kill, the next Gemini request reaches a cold :interactor and no window appears (device: 11/11 after clear all);
     * a rebind ahead of time restarts it (3/3). ponytail: fixed 0.5 s delay and 3 s rate limit (bounds a kill loop to one
     * 350 ms unbind per 3 s), tune on device.
     */
    private static final long WARM_DELAY = 500, WARM_INTERVAL = 3000;
    /** Newest attempt per pathway only (StatusPolicy records); main thread only. */
    static final String LAST_BAR = "last_bar", LAST_POWER = "last_power";
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
            && BootRecovery.permitted(context) && Settings.Secure.getInt(context.getContentResolver(), BarGesture.GESTURE_KEY, -1) == 1;
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

    static boolean logsPermitted(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean logsWanted(Context context) { return power(context) || (enabled(context) && nativeMode(context)); }
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

    private String barInput() { return nativeMode(this) ? "ColorOS 기본 바 길게 누르기 (기기 로그)" : "터치 영역 길게 누르기"; }

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
            + " lockedAssist=" + lockedAssist(this) + " session=" + logs.state();
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
        if (started) attemptUpdate(this, LAST_POWER, powerAttempt, StatusPolicy.ACCEPTED,
            rebound + " · 시스템이 Gemini 세션 요청을 수락함 · Gemini Assistant 창 표시는 확인하지 않음");
        else attemptUpdate(this, LAST_POWER, powerAttempt, StatusPolicy.FAILED, rebound + " · " + (!VoiceRepair.ready(rebind)
            ? "Gemini 요청 안 함" : googleVoice(this) == StatusPolicy.GOOGLE_READY ? "시스템이 Gemini 세션 요청을 수락하지 않음"
            : "Google 음성 서비스 미선택 또는 사용 불가"));
        if (!started) android.widget.Toast.makeText(this, "Gemini Assistant를 열지 못했습니다.", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void launchSearch() {
        long attempt = attemptBegin(this, LAST_BAR, StatusPolicy.PENDING, barInput(), "입력 인식 · 실행 화면 시작 요청");
        try {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.ATTEMPT, attempt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException error) {
            Log.e("PadBar", "Search launch failed", error);
            attemptUpdate(this, LAST_BAR, attempt, StatusPolicy.FAILED, "실행 화면을 시작하지 못함 (" + error.getClass().getSimpleName() + ")");
        }
    }

    private void notifyState() {
        if (instance != this) return;
        configure();
        sendBroadcast(new Intent(STATE_CHANGED).setPackage(getPackageName()));
    }

    private final LogSession.Listener logEvents = new LogSession.Listener() {
        @Override public void stateChanged(boolean declined) {
            powerRequest++; // A session change cancels a power request from the old session.
            if (declined) prefs(BarService.this).edit().putBoolean(LOG_DECLINED, true).apply();
            notifyState();
        }
        @Override public void trigger(int kind) {
            Log.i("PadLog", "trigger kind=" + kind + " instance=" + (instance == BarService.this));
            if (kind == LogTrigger.BAR) barGesture();
            else if (kind == LogTrigger.POWER) powerGesture();
            else if (kind == LogTrigger.GOOGLE_DIED) googleDied();
        }
    };

    /** Google's :interactor was killed: restart it now (or at the next screen on) so the next power press is not cold. */
    private void googleDied() {
        if (!getSystemService(PowerManager.class).isInteractive()) { warmOnScreen = true; return; }
        handler.removeCallbacks(warm);
        handler.postDelayed(warm, WARM_DELAY); // Let the kill sweep (clear all) finish first.
    }

    private final Runnable warm = () -> {
        warmOnScreen = false;
        long now = SystemClock.elapsedRealtime();
        if (instance != this || !power(this) || powerPending || now - lastWarm < WARM_INTERVAL
            || !VoiceRepair.activationWanted(this, true)) { Log.i("PadVoice", "warm-up skipped"); return; }
        lastWarm = now;
        Context app = getApplicationContext();
        try {
            new Thread(() -> Log.i("PadVoice", "warm-up after Google kill restored=" + VoiceRepair.run(app)), "PadGoogleWarm").start();
        } catch (RuntimeException | OutOfMemoryError unavailable) { Log.w("PadVoice", "warm-up thread unavailable"); }
    };

    /** First unlock after boot (or after the session was lost): at most AUTO_PROMPTS bridge windows per boot. */
    private void maybeAutoPrompt() {
        SharedPreferences bar = prefs(this);
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (bar.getInt(LOG_BOOT, -2) != boot)
            bar.edit().putInt(LOG_BOOT, boot).putInt(LOG_AUTO, 0).putBoolean(LOG_DECLINED, false).apply();
        int attempts = bar.getInt(LOG_AUTO, 0);
        PowerManager power = getSystemService(PowerManager.class);
        if (!LogTrigger.autoPrompt(granted(this) && logsWanted(this), logsPermitted(this), logs.state() != LogSession.OFF,
                bridgeShowing(), power.isInteractive(), getSystemService(KeyguardManager.class).isKeyguardLocked(),
                bar.getBoolean(LOG_DECLINED, false), attempts)) return;
        bar.edit().putInt(LOG_AUTO, attempts + 1).apply();
        launchBridge(this);
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // The log session stays open across screen changes; only the touch window and OEM gesture follow the screen.
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { cancelPending(); detach(); powerRequest++; }
            configure();
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && warmOnScreen) googleDied();
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) maybeAutoPrompt();
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
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs(this).edit().putBoolean(BootRecovery.APPROVED, true).remove("native_bar").apply();
        running = enabled(this);
        notifyState(); // configure + refresh any visible settings screen (it may predate this connection)
        maybeAutoPrompt();
    }

    private void configure() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        // Touch events belong to our own overlay. No other apps' window events.
        info.eventTypes = 0;
        info.packageNames = null;
        info.flags = 0;
        setServiceInfo(info);
        running = enabled(this);
        handler.removeCallbacks(restoreTarget);
        handler.removeCallbacks(longPress);
        if (!power(this)) powerRequest++; // Power option off: no delayed Gemini request survives.
        if (!logsWanted(this) || !logsPermitted(this)) logs.close();
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
                    handler.postDelayed(longPress, BarGesture.HOLD_MS);
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
                    if (BarGesture.releaseShouldTrigger(event.getEventTime()-event.getDownTime(), moved, holdFired)) longPress.run();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    moved = true;
                    handler.removeCallbacks(longPress);
                    break;
            }
            return true;
        });
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(200), dp(38),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("PadBarTouchTarget");
        try {
            windows.addView(view, p);
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
        running = false;
        cancelPending();
        detach();
        logs.close();
        if (nativeMode(this)) BarGesture.restore(this); // No reader while unbound; reapplied after the next approved session.
        unregisterReceiver(screen);
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
