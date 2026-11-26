package dev.padhelper.fastcts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            // No log reader exists before unlock: the system short press (screen off) comes back first. Not on the later
            // BOOT_COMPLETED, which can arrive after a fresh session has applied the settings again.
            PowerButton.restore(context);
            BootRecovery.lockedBoot(context);
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        BootRecovery.unlockedBoot(context);
        // Overlay mode (experimental): no accessibility to restore; the service is started directly.
        if (BarService.overlayMode(context)) {
            try { context.startService(new Intent(context, BarService.class)); }
            catch (RuntimeException refused) { Log.w("PadBoot", "overlay mode start refused", refused); }
        }
    }
}
