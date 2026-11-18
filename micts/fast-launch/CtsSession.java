package dev.padhelper.fastcts;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Opens Circle to Search with the system call MiCTS (parallelcc, GPL-3.0) uses: the selected Google assistant session with an
 * omni entry point. Written from those call facts; no MiCTS code is included.
 * Device (OPD2515, 0.3.6): the entry key alone selects Circle to Search (values 0, 1 and 2 all open it; without it the
 * same call opens Gemini); the attribution tag and MiCTS's own marker key change nothing; no Google warm-up is needed,
 * also right after recents clear-all.
 */
final class CtsSession {
    static final String ENTRY_KEY = "omni.entry_point";
    /** The value MiCTS sends for an app launch. */
    static final int ENTRY = 1;
    /** SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT | SHOW_SOURCE_ASSIST_GESTURE: Circle to Search needs the screenshot. */
    private static final int FLAGS = 7;

    private CtsSession() {}

    /** Unlocked, interactive screen and Google selected only: Circle to Search never runs over the keyguard. */
    static boolean open(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (power == null || keyguard == null || !LogTrigger.ctsScreen(power.isInteractive(), keyguard.isKeyguardLocked())
                || !GeminiSession.googleSelected(context)) return false;
        try {
            Bundle args = new Bundle();
            args.putLong("invocation_time_ms", SystemClock.elapsedRealtime());
            args.putInt(ENTRY_KEY, ENTRY);
            boolean accepted = GeminiSession.showSession(args, FLAGS);
            Log.i("PadCts", "Circle to Search request accepted=" + accepted);
            return accepted;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w("PadCts", "Circle to Search request unavailable: " + error.getClass().getSimpleName());
            return false;
        }
    }
}
