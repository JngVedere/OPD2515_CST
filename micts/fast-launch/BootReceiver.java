package dev.padhelper.fastcts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/** No ongoing service: watches only the first five seconds after BOOT_COMPLETED. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            // No log reader exists before unlock: the system short press (screen off) comes back first. Not on the later
            // BOOT_COMPLETED, which can arrive after a fresh session has applied the settings again.
            PowerButton.restore(context);
            BootRecovery.captureBeforeUnlock(context);
            return;
        }
        // Overlay mode (experimental): no accessibility to restore; the service is started directly.
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && BarService.overlayMode(context)) {
            try { context.startService(new Intent(context, BarService.class)); }
            catch (RuntimeException refused) { Log.w("PadBoot", "overlay mode start refused", refused); }
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || !BootRecovery.eligible(context)) return;
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable restore = () -> BootRecovery.restore(app);
        boolean[] active = {true};
        ContentObserver observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                if (!active[0]) return;
                handler.removeCallbacks(restore);
                handler.postDelayed(restore, 300);
            }
        };
        try {
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer);
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_ENABLED), false, observer);
            handler.postDelayed(restore, 250);
            handler.postDelayed(() -> {
                try { BootRecovery.restore(app); }
                finally {
                    active[0] = false;
                    handler.removeCallbacks(restore);
                    app.getContentResolver().unregisterContentObserver(observer);
                    pending.finish();
                    Log.i("PadBoot", "Boot recovery window closed");
                }
            }, 5000);
        } catch (RuntimeException error) {
            active[0] = false;
            app.getContentResolver().unregisterContentObserver(observer);
            pending.finish();
            Log.w("PadBoot", "Boot recovery unavailable", error);
        }
    }
}
