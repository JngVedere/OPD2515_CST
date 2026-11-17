package dev.padhelper.fastcts;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/** Avoids the OEM bar shortcut competing with the app's own touch area. */
final class BarGesture {
    static final String GESTURE_KEY = "oplus_gesture_handle_cui_enable";
    /**
     * 3-button navigation: ColorOS "long-press home for Breeno" (device: 1 by default; at 0 the long press logs no Breeno
     * start, so the native mode cannot see it). Independent of GESTURE_KEY. Same journal and restore rules, own keys.
     */
    static final String HOME_KEY = "oplus_home_button_cui_enable";
    /** OEM navigation mode: 0 = 3-button (device: gesture modes are 2/3). Readable by apps, unlike navigation_mode. */
    static final String NAV_KEY = "hide_navigationbar_enable";
    // Keep 2.6's recovery keys so upgrades retain the original system value.
    static final String ORIGINAL = "gesture_original";
    static final String APPLIED = "gesture_applied";
    static final String HOME_ORIGINAL = "home_original", HOME_APPLIED = "home_applied";

    static boolean threeButton(Context context) {
        try { return Settings.Secure.getInt(context.getContentResolver(), NAV_KEY, -1) == 0; }
        catch (SecurityException unreadable) { return false; }
    }

    static void disableBreenoGesture(Context context) {
        apply(context, GESTURE_KEY, ORIGINAL, APPLIED, 0);
    }

    /** Native mode: the gesture-bar key decides the result; the home key is best effort (absent on other devices). */
    static boolean enableBreenoGesture(Context context) {
        apply(context, HOME_KEY, HOME_ORIGINAL, HOME_APPLIED, 1);
        return apply(context, GESTURE_KEY, ORIGINAL, APPLIED, 1);
    }

    private static boolean apply(Context context, String key, String originalKey, String appliedKey, int value) {
        if (!BootRecovery.permitted(context)) return false;
        try {
            int previous = Settings.Secure.getInt(context.getContentResolver(), key, -1);
            if (previous != 0 && previous != 1) return false;
            if (previous == value) return true;
            SharedPreferences preferences = context.getSharedPreferences("bar", Context.MODE_PRIVATE);
            SharedPreferences.Editor edit = preferences.edit();
            if (!preferences.contains(originalKey)) edit.putInt(originalKey, previous);
            if (!edit.putInt(appliedKey, value).commit()) {
                Log.w("PadBar", "Could not save original bar gesture");
                return false;
            }
            return Settings.Secure.putInt(context.getContentResolver(), key, value);
        } catch (SecurityException error) { Log.w("PadBar", "Set the Breeno bar gesture manually", error); return false; }
    }

    static boolean restore(Context context) {
        boolean home = restore(context, HOME_KEY, HOME_ORIGINAL, HOME_APPLIED);
        return restore(context, GESTURE_KEY, ORIGINAL, APPLIED) && home;
    }

    private static boolean restore(Context context, String key, String originalKey, String appliedKey) {
        SharedPreferences preferences = context.getSharedPreferences("bar", Context.MODE_PRIVATE);
        if (!preferences.contains(originalKey)) return true;
        if (!BootRecovery.permitted(context)) return false;
        try {
            int original = preferences.getInt(originalKey, -1);
            int current = Settings.Secure.getInt(context.getContentResolver(), key, -1);
            int applied = preferences.getInt(appliedKey, -1);
            if (current == applied && current != original
                    && !Settings.Secure.putInt(context.getContentResolver(), key, original)) return false;
            return preferences.edit().remove(originalKey).remove(appliedKey).commit();
        } catch (SecurityException error) {
            Log.w("PadBar", "Original bar gesture could not be restored", error);
            return false;
        }
    }

    static boolean releaseShouldTrigger(long heldMillis, long holdMillis, boolean moved, boolean alreadyFired) {
        return heldMillis >= holdMillis && !moved && !alreadyFired;
    }
}
