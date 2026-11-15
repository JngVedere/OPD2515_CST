package dev.padhelper.fastcts;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scoped rebind of an already selected Google voice interaction service, run only on explicit user actions
 * (Start, enabling the power option, or the repair button), never per gesture.
 * Observed on OPD2515: the key stays Google but the system binding is disconnected (dumpsys
 * "mBound=true mService=null"); session requests are then accepted while no assistant window appears.
 * Clearing the key briefly and writing back the exact same value rebinds it. No other assistant is
 * ever selected, and a value this app did not clear is never overwritten. The original is journaled
 * before the system write and the journal is dropped only once the original is back (or someone else
 * chose a different assistant), so a failure or process death is finished later by recover().
 */
final class VoiceRepair {
    static final String KEY = "voice_interaction_service", PENDING = "voice_repair_original";
    /** Unbound window proven on device (350 ms); the system rebinds after the exact value returns. */
    static final long UNBOUND_MS = 350;
    private static final String TAG = "PadVoice", GOOGLE = "com.google.android.googlequicksearchbox/";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private VoiceRepair() {}

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("bar", Context.MODE_PRIVATE); }
    private static boolean writable(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Exactly one Google component (not a list, not another package). */
    static boolean googleSelected(String value) {
        return value != null && value.startsWith(GOOGLE) && value.length() > GOOGLE.length()
            && value.indexOf(':') < 0 && value.indexOf('/', GOOGLE.length()) < 0;
    }

    /** Explicit activation (Start / power option on): only with the power option on, WSS granted and Google selected. */
    static boolean activationWanted(Context context, boolean powerOn) {
        try {
            return powerOn && writable(context) && googleSelected(Settings.Secure.getString(context.getContentResolver(), KEY));
        } catch (RuntimeException unavailable) { return false; }
    }

    static boolean busy() { return RUNNING.get(); }

    /**
     * Blocking (sleeps UNBOUND_MS); call off the main thread. Concurrent requests are dropped, not queued.
     * True only if the exact original is back in place after a rebind.
     */
    static boolean run(Context context) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        try {
            if (!begin(context)) return false;
            boolean restored = false;
            try { Thread.sleep(UNBOUND_MS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { restored = finish(context); }
            Log.i(TAG, "rebind restored=" + restored);
            return restored;
        } finally { RUNNING.set(false); }
    }

    /** Service start: completes a repair interrupted by process death. Never runs beside an active repair. */
    static boolean recover(Context context) { return busy() || finish(context); }

    /** Journals, then clears the selected Google service. False means the selection is (again) unchanged or journaled for recover(). */
    static synchronized boolean begin(Context context) {
        try {
            finish(context);
            if (prefs(context).contains(PENDING) || !writable(context)) return false;
            String original = Settings.Secure.getString(context.getContentResolver(), KEY);
            if (!googleSelected(original) || !prefs(context).edit().putString(PENDING, original).commit()) return false;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "rebind not started: " + unavailable.getClass().getSimpleName());
            return false;
        }
        boolean cleared;
        try { cleared = Settings.Secure.putString(context.getContentResolver(), KEY, null); }
        catch (RuntimeException rejected) { cleared = false; }
        if (!cleared) finish(context); // Whatever the write did, the journal puts back exactly the original.
        return cleared;
    }

    /**
     * Restores the journaled value if this app's clear is still in effect. A different non-empty value
     * (chosen by someone else meanwhile) is left alone and reported as not restored. On any failure the
     * journal is kept for a later retry.
     */
    static synchronized boolean finish(Context context) {
        try {
            String original = prefs(context).getString(PENDING, null);
            if (original == null) return true;
            String current = Settings.Secure.getString(context.getContentResolver(), KEY);
            boolean cleared = current == null || current.isEmpty();
            if (cleared && (!writable(context) || !Settings.Secure.putString(context.getContentResolver(), KEY, original))) return false;
            if (!prefs(context).edit().remove(PENDING).commit()) return false;
            return cleared || original.equals(current);
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "rebind restore pending: " + unavailable.getClass().getSimpleName());
            return false;
        }
    }
}
