package dev.padhelper.fastcts;

import android.app.*;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;

/** Event-driven touch target. No polling, network, wake lock, or screen capture. */
public final class BarService extends AccessibilityService {
    static final String MODE = "trigger_mode", TOUCH = "touch", NATIVE = "coloros_log";
    static final String STATE_CHANGED = "dev.padhelper.fastcts.STATE_CHANGED";
    private static BarService instance;
    static volatile boolean running;
    private static boolean preview;
    private final Handler handler = Handler.createAsync(Looper.getMainLooper());
    private WindowManager windows;
    private View target;
    private float downX, downY;
    private boolean moved, holdFired;
    private int attachAttempts;
    private NativeBarBridge nativeBridge;

    static boolean ready(Context context) {
        if (nativeMode(context)) return instance != null && enabled(context) && instance.nativeBridge != null && instance.nativeBridge.reading()
            && BootRecovery.permitted(context) && android.provider.Settings.Secure.getInt(context.getContentResolver(), BarGesture.GESTURE_KEY, -1) == 1;
        return instance != null && enabled(context)
            && instance.target != null && instance.target.isAttachedToWindow();
    }

    static boolean nativeMode(Context context) { return NATIVE.equals(context.getSharedPreferences("bar", MODE_PRIVATE).getString(MODE, TOUCH)); }
    static void setMode(Context context, String mode) {
        if (!TOUCH.equals(mode) && !NATIVE.equals(mode)) throw new IllegalArgumentException("Unknown trigger mode");
        context.getSharedPreferences("bar", MODE_PRIVATE).edit().putString(MODE, mode).apply();
        refreshConfiguration();
    }
    static boolean agentConnected() { return instance != null && instance.nativeBridge != null && instance.nativeBridge.connected(); }
    static void stopAgent() { if (instance != null && instance.nativeBridge != null) instance.nativeBridge.quit(); }

    static void refreshConfiguration() {
        if (instance != null) instance.configure();
    }

    static boolean enabled(Context context) {
        return context.getSharedPreferences("bar", MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void start(Context context) {
        context.getSharedPreferences("bar", MODE_PRIVATE).edit().putBoolean("enabled", true).remove("native_bar").apply();
        if (instance != null) { running = true; instance.configure(); }
    }

    static void stop(Context context) {
        context.getSharedPreferences("bar", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
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
        return BootRecovery.containsOwn(android.provider.Settings.Secure.getString(context.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
    }

    private final Runnable longPress = () -> {
        if (holdFired || moved || target == null || nativeMode(this) || !maySearch()) return;
        holdFired = true;
        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        launchSearch();
    };

    private boolean maySearch() {
        return enabled(this) && granted(this)
                && getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private void nativeGesture() {
        if (nativeMode(this) && maySearch() && nativeBridge != null && nativeBridge.reading()) launchSearch();
    }

    private void launchSearch() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException error) {
            Log.e("PadBar", "Search launch failed", error);
        }
    }

    private void nativeStateChanged() {
        if (instance != this) return;
        configure();
        sendBroadcast(new Intent(STATE_CHANGED).setPackage(getPackageName()));
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                cancelPending(); detach();
                if (nativeBridge != null) nativeBridge.active(false);
            }
            else configure();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        windows = getSystemService(WindowManager.class);
        nativeBridge = new NativeBarBridge(this, handler, this::nativeStateChanged, this::nativeGesture);
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
        nativeBridge.start();
        getSharedPreferences("bar", MODE_PRIVATE).edit().putBoolean(BootRecovery.APPROVED, true).remove("native_bar").apply();
        running = enabled(this);
        configure();
    }

    private void configure() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        nativeBridge.start(); // Publish the current accessibility-owned Binder endpoint.
        // Touch events belong to our own overlay. No other apps' window events.
        info.eventTypes = 0;
        info.packageNames = null;
        info.flags = 0;
        setServiceInfo(info);
        running = enabled(this);
        handler.removeCallbacks(restoreTarget);
        handler.removeCallbacks(longPress);
        if (!enabled(this)) { detach(); nativeBridge.active(false); }
        else if (nativeMode(this)) {
            detach();
            boolean active = maySearch() && BootRecovery.permitted(this) && NativeBarBridge.breenoDisabled(this);
            nativeBridge.active(active);
            // The OEM gesture is on only while the agent genuinely reads; paused or disconnected restores the original.
            if (active && nativeBridge.reading()) BarGesture.enableBreenoGesture(this);
            else BarGesture.restore(this);
        } else {
            nativeBridge.active(false);
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
        if (!enabled(this) || nativeMode(this) || !granted(this) || instance != this
            || !getSystemService(PowerManager.class).isInteractive()
            || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        if (target != null) { BarGesture.disableBreenoGesture(this); return; }
        View view = new View(this);
        view.setContentDescription("하단 검색 길게 누르기 영역");
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
        nativeBridge.close();
        if (nativeMode(this)) BarGesture.restore(this); // No reader while unbound; reapplied after the next RUNNING.
        unregisterReceiver(screen);
        super.onDestroy();
    }

    @Override public boolean onUnbind(Intent intent) {
        String approved = android.provider.Settings.Secure.getString(getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (!BootRecovery.containsOwn(approved)) BarGesture.restore(this);
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
