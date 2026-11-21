package dev.padhelper.fastcts;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

/**
 * Invisible, non-exported bridge. It only gives this app a focused TOP window so Android can show its
 * own device-log access confirmation; it never answers that confirmation itself (BarService may, only with the
 * user's opt-in auto-allow option). Bounded lifetime.
 * Touches pass through (not touchable, transparent): after boot another system window (e.g. "USB 사용") can
 * keep focus for a while, and an invisible touch-eating window made the whole screen look frozen. Since nothing is
 * blocked, the bridge can wait longer for that window to go away.
 */
public final class LogAccessActivity extends Activity {
    private static final long REQUEST_DELAY = 420, FOCUS_TIMEOUT = 30000, LIFETIME = 45000, ANSWERED_DELAY = 300;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed, focused, requested, finished;
    private final Runnable request = this::requestOnce;
    // Another window kept focus (device: ColorOS's post-boot accessibility warning): nothing was asked, so try again soon.
    private final Runnable noFocus = () -> { if (!requested) { BarService.logBridgeUnused(); end(); } };
    private final BroadcastReceiver state = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (requested && BarService.logState() != LogSession.CONNECTING) end();
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        BarService.bridgeShowing(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        // Content is fully transparent anyway. 0.8 = Android's maximum obscuring opacity, so touches still reach other
        // apps' windows underneath; 0 would make the window invisible to input and it would never receive focus.
        attributes.alpha = 0.8f;
        getWindow().setAttributes(attributes);
        setContentView(new View(this));
        overridePendingTransition(0, 0);
        IntentFilter filter = new IntentFilter(BarService.STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(state, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(state, filter);
        handler.postDelayed(noFocus, FOCUS_TIMEOUT);
        handler.postDelayed(this::end, LIFETIME);
    }

    @Override protected void onResume() { super.onResume(); resumed = true; schedule(); }

    @Override protected void onPause() {
        resumed = false; focused = false;
        handler.removeCallbacks(request);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        focused = hasFocus;
        // Focus back after the request: Android's confirmation was answered or dismissed; nothing left to wait for.
        if (requested && hasFocus) { BarService.logAnswered(); handler.postDelayed(this::end, ANSWERED_DELAY); }
        schedule();
    }

    private void schedule() {
        if (finished || requested || !resumed || !focused) return;
        handler.removeCallbacks(request);
        handler.postDelayed(request, REQUEST_DELAY);
    }

    private void requestOnce() {
        if (finished || requested || !resumed || !focused) return;
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) { end(); return; }
        requested = true;
        handler.removeCallbacks(noFocus);
        if (!BarService.openLogSession(this) || BarService.logState() != LogSession.CONNECTING) end();
    }

    private void end() {
        if (finished) return;
        finished = true;
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(state);
        BarService.bridgeShowing(false);
        super.onDestroy();
    }
}
