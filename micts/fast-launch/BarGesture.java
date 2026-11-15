package dev.padhelper.fastcts;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/** Avoids the OEM bar shortcut competing with the app's own touch area. */
final class BarGesture {
    static final String GESTURE_KEY = "oplus_gesture_handle_cui_enable";
    // Keep 2.6's recovery keys so upgrades retain the original system value.
    static final String ORIGINAL = "gesture_original";
    static final String APPLIED = "gesture_applied";

    static void disableBreenoGesture(Context context) {
        apply(context, 0);
    }

    static boolean enableBreenoGesture(Context context) { return apply(context, 1); }

    private static boolean apply(Context context, int value) {
        if (!BootRecovery.permitted(context)) return false;
        try {
            int previous = Settings.Secure.getInt(context.getContentResolver(), GESTURE_KEY, -1);
            if (previous != 0 && previous != 1) return false;
            if (previous == value) return true;
            SharedPreferences preferences = context.getSharedPreferences("bar", Context.MODE_PRIVATE);
            SharedPreferences.Editor edit = preferences.edit();
            if (!preferences.contains(ORIGINAL)) edit.putInt(ORIGINAL, previous);
            if (!edit.putInt(APPLIED, value).commit()) {
                Log.w("PadBar", "Could not save original bar gesture");
                return false;
            }
            return Settings.Secure.putInt(context.getContentResolver(), GESTURE_KEY, value);
        } catch (SecurityException error) { Log.w("PadBar", "Set the Breeno bar gesture manually", error); return false; }
    }

    static boolean restore(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("bar", Context.MODE_PRIVATE);
        if (!preferences.contains(ORIGINAL)) return true;
        if (!BootRecovery.permitted(context)) return false;
        try {
            int original = preferences.getInt(ORIGINAL, -1);
            int current = Settings.Secure.getInt(context.getContentResolver(), GESTURE_KEY, -1);
            int applied = preferences.getInt(APPLIED, -1);
            if (current == applied && current != original
                    && !Settings.Secure.putInt(context.getContentResolver(), GESTURE_KEY, original)) return false;
            return preferences.edit().remove(ORIGINAL).remove(APPLIED).commit();
        } catch (SecurityException error) {
            Log.w("PadBar", "Original bar gesture could not be restored", error);
            return false;
        }
    }

    static boolean releaseShouldTrigger(long heldMillis, boolean moved, boolean alreadyFired) {
        return heldMillis >= 400 && !moved && !alreadyFired;
    }
}
