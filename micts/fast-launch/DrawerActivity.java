package dev.padhelper.fastcts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * App drawer icon (through the exported DrawerLaunch alias). What a tap opens is the user's choice in settings
 * (ACTION): the settings screen (default, user decision 0.3.10), Circle to Search or Gemini Assistant. A choice the user
 * saved earlier (e.g. Circle to Search) is kept; only an unset value follows the default. Only that saved choice is read,
 * never the intent (any app can start a launcher entry).
 * No window at all (Theme.NoDisplay) and it finishes at once, so the launcher's app-open animation has nothing to expand
 * into and reverts (the icon bounces back). Circle to Search is requested only after that bounce has settled. Requested
 * sooner, ColorOS hands Circle to Search's own opening to the launcher's still-running launch animation: the icon grows
 * into a white card, the drawer is cleared to the bare wallpaper (seen as a blank screen), and the capture can show the
 * half-bounced icon. Device (OPD2515, app drawer and home screen, raw frames): 120/250 ms still captured the bouncing
 * icon, 320/360/400 ms were clean. Gemini Assistant needs no extra wait: its Google rebind alone takes ~0.4 s.
 * A refused direct Circle to Search call goes to MainActivity's fallback (MiCTS when installed, otherwise the failure
 * record and toast).
 */
public final class DrawerActivity extends Activity {
    // ponytail: fixed wait matched to the ColorOS launcher's bounce-back; no system signal marks its end.
    static final long LAUNCHER_SETTLE_MS = 350;
    static final String ACTION = "drawer_action";
    static final int CTS = 0, ASSISTANT = 1, SETTINGS = 2;

    /** Saved choice (unset: the settings screen), clamped: an unknown stored value opens Circle to Search. */
    static int action(Context context) {
        int value = context.getSharedPreferences("bar", MODE_PRIVATE).getInt(ACTION, SETTINGS);
        return value == ASSISTANT || value == SETTINGS ? value : CTS;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Context app = getApplicationContext();
        int action = action(this);
        if (action == SETTINGS) {
            startActivity(new Intent(this, SetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } else if (action == ASSISTANT) {
            BarService.tileAssistant(app);
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!CtsSession.open(app)) fallback(app);
            }, LAUNCHER_SETTLE_MS);
        }
        finish(); // NoDisplay: must finish before onResume
    }

    private static void fallback(Context app) {
        try {
            app.startActivity(new Intent(app, MainActivity.class).putExtra(MainActivity.DRAWER_FALLBACK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException unavailable) { }
    }
}
