package dev.padhelper.fastcts;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Optional system tweaks under 설정 → 고급 (user request; the same switches the 欢太工具箱 app offers, written here with this
 * app's own WRITE_SECURE_SETTINGS instead of a shell). Every key written is listed in this class and pinned by the
 * security test; nothing is written unless the user flips the matching switch.
 * Not possible without a shell (no permission an app can hold): enabling the hidden System UI Tuner screen, freezing
 * the OPPO installer page (com.oplus.appdetail) and the private refresh-rate keys. Those show the ADB command instead.
 */
final class Tweaks {
    private Tweaks() {}

    // Secure keys (Android / SystemUI).
    static final String EXTRA_DIM = "reduce_bright_colors_activated", ROTATION_SUGGESTIONS = "show_rotation_suggestions",
        CLOCK_SECONDS = "clock_seconds";
    // Device name: global names + the Bluetooth and hotspot names shown to other devices.
    private static final String NAME_GLOBAL = "device_name", NAME_REVISED = "revise_device_name",
        NAME_BLUETOOTH = "bluetooth_name", NAME_TETHER = "device_tether_name";

    static final ComponentName TUNER = new ComponentName("com.android.systemui", "com.android.systemui.tuner.TunerActivity");
    static final String INSTALLER_PAGE = "com.oplus.appdetail";

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("bar", Context.MODE_PRIVATE); }

    /**
     * These three keys are @hide: since Android 12 an app may write them (WRITE_SECURE_SETTINGS) but not read them
     * (device: SecurityException "is not readable"). So the switches show what this app last wrote; unset is the default.
     */
    static boolean secureOn(Context context, String key, boolean unset) {
        return prefs(context).getBoolean(STATE + key, unset);
    }

    private static final String STATE = "tweak_state_";

    /** Writes one of this class's secure keys and remembers it; false when the write was refused. */
    static boolean setSecure(Context context, String key, boolean on) {
        if (!EXTRA_DIM.equals(key) && !ROTATION_SUGGESTIONS.equals(key) && !CLOCK_SECONDS.equals(key)) return false;
        try {
            if (!Settings.Secure.putInt(context.getContentResolver(), key, on ? 1 : 0)) return false;
        } catch (RuntimeException refused) { Log.w("PadTweaks", "secure write refused: " + key); return false; }
        prefs(context).edit().putBoolean(STATE + key, on).apply();
        return true;
    }

    // --- High refresh -------------------------------------------------------------------------------------------------
    // The rate keys are private System settings: SettingsProvider refuses them to any app (IllegalArgumentException on the
    // device, even with WRITE_SECURE_SETTINGS). 欢太工具箱 writes them through Shizuku (shell); here the user runs the
    // same commands over ADB.

    static final String HIGH_REFRESH_ON = "adb shell \"settings put secure oplus_customize_screen_refresh_rate 0; "
        + "settings put system peak_refresh_rate 1; settings put system min_refresh_rate 1; settings put system user_refresh_rate 1\"";

    /** Puts the peak back to the display's highest rate (what ColorOS stores when a rate is picked in its own panel). */
    static String highRefreshOff(Context context) {
        String max = maxRefreshRate(context);
        return "adb shell settings put system peak_refresh_rate " + (max == null ? "144.0" : max);
    }

    /** Highest refresh rate this display offers, as Settings stores it ("144.0"); null when unknown. */
    private static String maxRefreshRate(Context context) {
        android.view.Display display = context.getSystemService(android.hardware.display.DisplayManager.class)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if (display == null) return null;
        float best = 0;
        for (android.view.Display.Mode mode : display.getSupportedModes()) best = Math.max(best, mode.getRefreshRate());
        return best > 0 ? String.valueOf((float) Math.round(best)) : null;
    }

    // --- Device name --------------------------------------------------------------------------------------------------

    static String deviceName(Context context) {
        String name = Settings.Global.getString(context.getContentResolver(), NAME_REVISED);
        if (name == null || name.trim().isEmpty()) name = Settings.Global.getString(context.getContentResolver(), NAME_GLOBAL);
        return name == null ? "" : name;
    }

    /** Writes the name where ColorOS, Android, Bluetooth and the hotspot read it, without the online check. */
    static boolean setDeviceName(Context context, String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 64) return false;
        android.content.ContentResolver resolver = context.getContentResolver();
        try {
            return Settings.Global.putString(resolver, NAME_REVISED, clean)
                & Settings.Global.putString(resolver, NAME_GLOBAL, clean)
                & Settings.Secure.putString(resolver, NAME_BLUETOOTH, clean)
                & Settings.Secure.putString(resolver, NAME_TETHER, clean);
        } catch (RuntimeException refused) {
            Log.w("PadTweaks", "device name refused: " + refused.getClass().getSimpleName());
            return false;
        }
    }

    // --- Screens and component states ---------------------------------------------------------------------------------

    /** Disabled component or package (only an ADB/shell command can change it). */
    static boolean componentEnabled(Context context, ComponentName component) {
        try {
            int state = context.getPackageManager().getComponentEnabledSetting(component);
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (IllegalArgumentException missing) { return false; }
    }

    static boolean packageFrozen(Context context, String packageName) {
        try {
            int state = context.getPackageManager().getApplicationEnabledSetting(packageName);
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (IllegalArgumentException missing) { return false; }
    }

    /** Opens a system Settings page by class name; false when this ROM does not have or export it. */
    static boolean openSettingsPage(Context context, String className) {
        return start(context, new Intent().setClassName("com.android.settings", className));
    }

    static boolean start(Context context, Intent intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (ActivityNotFoundException | SecurityException unavailable) { return false; }
    }
}
