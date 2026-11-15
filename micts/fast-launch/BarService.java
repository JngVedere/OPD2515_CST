package dev.padhelper.fastcts;

import android.Manifest;
import android.app.*;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
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

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("bar", MODE_PRIVATE); }

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
        if (holdFired || moved || target == null || nativeMode(this) || !maySearch()) return;
        holdFired = true;
        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        launchSearch();
    };

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
            + " session=" + logs.state();
    }

    private void barGesture() {
        if (nativeMode(this) && maySearch() && logs.state() == LogSession.ACTIVE) launchSearch();
        else Log.i("PadBar", "bar dispatch skipped " + dispatchState());
    }

    private void powerGesture() {
        if (!power(this) || !granted(this) || !unlockedScreen() || logs.state() != LogSession.ACTIVE) {
            Log.i("PadPower", "power dispatch skipped " + dispatchState());
            return;
        }
        Log.i("PadPower", "power dispatch -> GeminiSession.launch");
        boolean started = GeminiSession.launch(this);
        Log.i("PadPower", "GeminiSession.launch returned " + started);
        if (!started) android.widget.Toast.makeText(this, "작은 Gemini를 열지 못했습니다.", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void launchSearch() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException error) {
            Log.e("PadBar", "Search launch failed", error);
        }
    }

    private void notifyState() {
        if (instance != this) return;
        configure();
        sendBroadcast(new Intent(STATE_CHANGED).setPackage(getPackageName()));
    }

    private final LogSession.Listener logEvents = new LogSession.Listener() {
        @Override public void stateChanged(boolean declined) {
            if (declined) prefs(BarService.this).edit().putBoolean(LOG_DECLINED, true).apply();
            notifyState();
        }
        @Override public void trigger(int kind) {
            Log.i("PadLog", "trigger kind=" + kind + " instance=" + (instance == BarService.this));
            if (kind == LogTrigger.BAR) barGesture();
            else if (kind == LogTrigger.POWER) powerGesture();
        }
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
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { cancelPending(); detach(); }
            configure();
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) maybeAutoPrompt();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        windows = getSystemService(WindowManager.class);
        int systemUi;
        try { systemUi = getPackageManager().getApplicationInfo("com.android.systemui", 0).uid; }
        catch (PackageManager.NameNotFoundException missing) { systemUi = -1; } // Bar events then never match.
        int uid = getApplicationInfo().uid;
        logs = new LogSession(handler, logEvents, systemUi, uid / 100000);
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
                    handler.postDelayed(longPress, 400);
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
