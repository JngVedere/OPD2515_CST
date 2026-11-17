package dev.padhelper.fastcts;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * Opt-in power double press. On OPD2515 ColorOS never waits for a second press and turns the screen off on the first.
 * With the AOSP global "power_button_double_press" set it waits and logs "PowerKey: onMultiPress ... count:2", but then
 * still runs the short-press action, so the short press is also set to "nothing" and this app turns the screen off
 * itself on the logged single press (device, 2026-11-17: physical single/double/long presses, wake from off unchanged).
 *
 * Applied only while the device-log session is live; otherwise both are put back to unset. Write-only: these keys are
 * @hide, and apps cannot read them since Android 12 (device: SecurityException "not readable"). Both are unset on this
 * device by default and an unset or null value means the system default, so restore always writes null.
 * ponytail: a value someone set by hand is not preserved; journal it only if a readable source ever appears.
 * The "applied" journal lives in device-protected storage so the locked-boot receiver can restore before the first unlock.
 */
final class PowerButton {
    static final String SHORT = "power_button_short_press", DOUBLE = "power_button_double_press";
    /** 0 = do nothing; 2 = AOSP brightness boost, used here only so ColorOS waits for and logs a second press. */
    static final String SHORT_VALUE = "0", DOUBLE_VALUE = "2";
    private static final String PREFS = "power_keys", APPLIED = "applied";
    private static final String TAG = "PadPowerKeys";
    /** Written in this process: values are rewritten once after a process restart instead of trusted from the journal. */
    private static boolean written;

    private PowerButton() {}

    /** All conditions for the system power key to be handed to this app. */
    static boolean wanted(boolean optedIn, boolean targetChosen, boolean accessibilityLive, boolean secureWrite, boolean logActive) {
        return optedIn && targetChosen && accessibilityLive && secureWrite && logActive;
    }

    private static SharedPreferences journal(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean applied(Context context) { return journal(context).getBoolean(APPLIED, false); }

    /** Journals first (so any write is always undone later), then sets both values. Idempotent. */
    static boolean apply(Context context) {
        if (!BootRecovery.permitted(context)) return false;
        SharedPreferences journal = journal(context);
        if (written && journal.getBoolean(APPLIED, false)) return true;
        if (!journal.edit().putBoolean(APPLIED, true).commit()) {
            Log.w(TAG, "Could not journal the power key change");
            return false;
        }
        try {
            // Double first: if the second write fails, the short press still turns the screen off as before.
            written = Settings.Global.putString(context.getContentResolver(), DOUBLE, DOUBLE_VALUE)
                && Settings.Global.putString(context.getContentResolver(), SHORT, SHORT_VALUE);
        } catch (SecurityException error) {
            Log.w(TAG, "Power key settings unavailable", error);
            written = false;
        }
        if (!written) restore(context);
        return written;
    }

    /** Puts both back to unset; clears the journal only when both writes succeeded. */
    static boolean restore(Context context) {
        SharedPreferences journal = journal(context);
        if (!journal.getBoolean(APPLIED, false)) return true;
        if (!BootRecovery.permitted(context)) return false;
        written = false;
        try {
            // Short first: the screen-off press comes back before anything else.
            boolean ok = Settings.Global.putString(context.getContentResolver(), SHORT, null)
                & Settings.Global.putString(context.getContentResolver(), DOUBLE, null);
            return ok && journal.edit().remove(APPLIED).commit();
        } catch (SecurityException error) {
            Log.w(TAG, "Power key settings could not be restored", error);
            return false;
        }
    }
}
