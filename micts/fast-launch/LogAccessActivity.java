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
import android.util.Log;
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
        handler.postDelayed(lifetime, LIFETIME);
    }

    private boolean dialogUp;
    private long created = android.os.SystemClock.elapsedRealtime();
    /** Bounded lifetime, longer only while Android's confirmation is still on screen (up to LogSession.DIALOG_WAIT). */
    private final Runnable lifetime = new Runnable() {
        @Override public void run() {
            boolean waiting = dialogUp && !focused && BarService.logState() == LogSession.CONNECTING
                && android.os.SystemClock.elapsedRealtime() - created < LogSession.DIALOG_WAIT;
            if (waiting) handler.postDelayed(this, LogSession.DIALOG_RECHECK); else end();
        }
    };

    @Override protected void onResume() { super.onResume(); resumed = true; schedule(); }

    @Override protected void onPause() {
        resumed = false; focused = false;
        handler.removeCallbacks(request);
        super.onPause();
    }

    /**
     * Stopped = no longer visible (Home, screen off, another full-screen app). Android's own confirmation is a dialog
     * over this bridge and only pauses it, so while it is up this does not run. Device (0.4.1-patch2 test): the screen
     * turned off right after asking, the lost focus counted as "confirmation shown" and the session waited for nothing.
     */
    @Override protected void onStop() {
        if (requested && dialogUp && BarService.logState() == LogSession.CONNECTING) {
            dialogUp = false;
            Log.i("PadLog", "bridge stopped while waiting: confirmation no longer on screen");
            BarService.logDialogGone();
            end();
        }
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        focused = hasFocus;
        // Focus back after the request: Android's confirmation was answered or dismissed; nothing left to wait for.
        if (requested && hasFocus) { BarService.logAnswered(); handler.postDelayed(this::end, ANSWERED_DELAY); }
        // Focus lost right after asking: the confirmation is up. The session then waits for its answer (and so does this
        // bridge, so the answer is still seen when it comes after LIFETIME).
        if (requested && !hasFocus && !finished) { BarService.logDialogShown(); dialogUp = true; }
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
        // Inside Android's refusal window the request would be refused without a dialog: say so and ask once it ends.
        long wait = BarService.declineWait(this);
        if (wait > 0) {
            android.widget.Toast.makeText(getApplicationContext(), "방금 거부해서 바로 다시 물어볼 수 없습니다.\n"
                + ((wait + 999) / 1000) + "초 뒤에 승인 창을 다시 띄웁니다.", android.widget.Toast.LENGTH_LONG).show();
            BarService.requestAfterDecline(wait);
            end();
            return;
        }
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
