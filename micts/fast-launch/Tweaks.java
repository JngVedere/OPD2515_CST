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
 * security test. Switches are changed through user input or a settings reset, and saved choices can be reapplied at boot.
 * Not possible without a shell (no permission an app can hold): freezing the OPPO installer page (com.oplus.appdetail),
 * which shows the ADB command instead. The refresh rate goes through ColorOS's own Secure key, not the private System
 * keys. The System UI Tuner opens through TUNER_ALIAS.
 */
final class Tweaks {
    private Tweaks() {}

    // Secure keys (Android / SystemUI).
    static final String EXTRA_DIM = "reduce_bright_colors_activated", ROTATION_SUGGESTIONS = "show_rotation_suggestions",
        CLOCK_SECONDS = "clock_seconds";
    /**
     * ColorOS Settings shows 개발자 옵션 → 'Disable system optimization' only in English, or when this secure flag is 1
     * (device Settings: SystemOptimizationPreferenceController.isAvailable). LuckyTool's '显示禁用系统优化' sets the same flag.
     */
    static final String SYSTEM_OPT = "system_opt_enable";
    // Device name: global names + the Bluetooth and hotspot names shown to other devices.
    private static final String NAME_GLOBAL = "device_name", NAME_REVISED = "revise_device_name",
        NAME_BLUETOOTH = "bluetooth_name", NAME_TETHER = "device_tether_name";

    static final ComponentName TUNER = new ComponentName("com.android.systemui", "com.android.systemui.tuner.TunerActivity");
    /** Exported, permissionless activity-alias of TunerActivity that stays enabled while the activity itself is disabled. */
    static final ComponentName TUNER_ALIAS = new ComponentName("com.android.systemui", "com.android.systemui.DemoMode");
    static final String INSTALLER_PAGE = "com.oplus.appdetail";

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("bar", Context.MODE_PRIVATE); }

    /**
     * These keys are @hide: since Android 12 an app may write them (WRITE_SECURE_SETTINGS) but not read them
     * (device: SecurityException "is not readable"). So the switches show what this app last wrote; unset is the default.
     */
    static boolean secureOn(Context context, String key, boolean unset) {
        // SYSTEM_OPT is ColorOS's own key, not an Android one; read it when allowed (another app such as LuckyTool may
        // have set it), else fall back to what this app last wrote.
        if (SYSTEM_OPT.equals(key)) {
            try { return Settings.Secure.getInt(context.getContentResolver(), key, unset ? 1 : 0) == 1; }
            catch (RuntimeException unreadable) { Log.i("PadTweaks", "secure read refused: " + key); }
        }
        return prefs(context).getBoolean(STATE + key, unset);
    }

    private static final String STATE = "tweak_state_";

    /** Writes one of this class's secure keys and remembers it; false when the write was refused. */
    static boolean setSecure(Context context, String key, boolean on) {
        if (!EXTRA_DIM.equals(key) && !ROTATION_SUGGESTIONS.equals(key) && !CLOCK_SECONDS.equals(key) && !SYSTEM_OPT.equals(key)) return false;
        try {
            if (!Settings.Secure.putInt(context.getContentResolver(), key, on ? 1 : 0)) return false;
        } catch (RuntimeException refused) { Log.w("PadTweaks", "secure write refused: " + key); return false; }
        prefs(context).edit().putBoolean(STATE + key, on).apply();
        return true;
    }

    // --- Max refresh rate ---------------------------------------------------------------------------------------------
    // ColorOS keeps its 화면 주사율 choice in a Secure key, which this app may write with WRITE_SECURE_SETTINGS (no ADB).
    // Device: writing it alone is enough, ColorOS moves the private System peak_refresh_rate itself (60, 90 and 120 took
    // effect at once). Indexes from Settings' ScreenRefreshRateController: 0 auto, 1 = 90, 2 = 60, 3 = 120, 4 = 144.
    // 144 stays 120 outside apps ColorOS lets run at 144 (games), even with peak_refresh_rate forced to 144 over ADB.

    private static final String REFRESH_KEY = "oplus_customize_screen_refresh_rate";
    /** 0 = ColorOS's 자동 (index 0), the rest in Hz. */
    static final int[] REFRESH_RATES = {0, 60, 90, 120, 144};
    private static final int[] REFRESH_INDEXES = {0, 2, 1, 3, 4};

    /** The chosen rate in Hz, 0 for ColorOS's 자동, -1 when unreadable or unknown. */
    static int maxRefreshRate(Context context) {
        int index;
        try { index = Settings.Secure.getInt(context.getContentResolver(), REFRESH_KEY, -1); }
        catch (RuntimeException unreadable) { return -1; }
        for (int i = 0; i < REFRESH_INDEXES.length; i++) if (REFRESH_INDEXES[i] == index) return REFRESH_RATES[i];
        return -1;
    }

    /** Writes one of REFRESH_RATES (0 = 자동); false when the rate is not offered or the write was refused. */
    static boolean setMaxRefreshRate(Context context, int hz) {
        for (int i = 0; i < REFRESH_RATES.length; i++) {
            if (REFRESH_RATES[i] != hz) continue;
            try { return Settings.Secure.putInt(context.getContentResolver(), REFRESH_KEY, REFRESH_INDEXES[i]); }
            catch (RuntimeException refused) { Log.w("PadTweaks", "refresh rate refused"); return false; }
        }
        return false;
    }

    // --- Animation scale ----------------------------------------------------------------------------------------------
    // Developer options offer 0.5x steps only; the three Global keys take any value (欢太工具箱's 动画速度 writes the same
    // keys with Settings.Global.putFloat). WindowManager follows the keys at once, no restart needed.

    private static final String[] ANIMATION_KEYS = {"window_animation_scale", "transition_animation_scale", "animator_duration_scale"};

    /** The animator duration scale stands for all three (they are set together here). */
    static float animationScale(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(), ANIMATION_KEYS[2], 1f);
    }

    /** Writes the same scale to all three keys; false when a write was refused. */
    static boolean setAnimationScale(Context context, float scale) {
        if (scale < 0f || scale > 10f) return false;
        try {
            boolean all = true;
            for (String key : ANIMATION_KEYS) all &= Settings.Global.putFloat(context.getContentResolver(), key, scale);
            return all;
        } catch (RuntimeException refused) { Log.w("PadTweaks", "animation scale refused"); return false; }
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

    // --- Extra dim (딥 다크 모드 tile) ---------------------------------------------------------------------------------

    /**
     * Whether Extra Dim is on, from ColorDisplayManager (hidden API, no permission to read); the secure key itself is
     * unreadable to apps. null when unavailable.
     */
    static Boolean extraDim(Context context) {
        try {
            Object manager = context.getSystemService("color_display");
            if (manager == null) return null;
            Object on = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(manager.getClass(), manager, "isReduceBrightColorsActivated");
            return on instanceof Boolean ? (Boolean) on : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }

    static boolean setExtraDim(Context context, boolean on) { return setSecure(context, EXTRA_DIM, on); }

    /**
     * After a reboot: puts back the switches the user set in this app. Device (0.4.1-patch2): ColorOS resets
     * clock_seconds to 0 at every boot while the switch, which shows what this app last wrote, still read "on".
     * Only values this app wrote are re-applied; Extra Dim is left to Android's own "keep on after restart" option.
     */
    static void reapply(Context context) {
        for (String key : new String[]{CLOCK_SECONDS, ROTATION_SUGGESTIONS, SYSTEM_OPT}) {
            if (!prefs(context).contains(STATE + key)) continue;
            boolean wanted = prefs(context).getBoolean(STATE + key, false);
            try {
                if (Settings.Secure.getInt(context.getContentResolver(), key, wanted ? 0 : 1) == (wanted ? 1 : 0)) continue;
            } catch (RuntimeException unreadable) { /* @hide keys may be unreadable: write anyway */ }
            if (setSecure(context, key, wanted)) Log.i("PadTweaks", "re-applied after boot: " + key);
        }
    }

    /** 모든 설정 초기화: every switch of this class back to its default (fails quietly without 보안 설정 쓰기). */
    static void resetDefaults(Context context) {
        setSecure(context, ROTATION_SUGGESTIONS, true); // rotation button shown again = '자동 회전 제안 끄기' off
        setSecure(context, CLOCK_SECONDS, false);
        setSecure(context, SYSTEM_OPT, false);
        setAnimationScale(context, 1f);
    }

    // --- Developer options --------------------------------------------------------------------------------------------

    private static final String DEVELOPER = "development_settings_enabled"; // Settings.Global, the switch itself

    static boolean developerOptionsOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), DEVELOPER, 0) == 1;
    }

    /** Only switches developer options on (never off); false when the write was refused. */
    static boolean enableDeveloperOptions(Context context) {
        try { return Settings.Global.putInt(context.getContentResolver(), DEVELOPER, 1); }
        catch (RuntimeException refused) { Log.w("PadTweaks", "developer options refused"); return false; }
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

    /**
     * From an activity the page joins that activity's task, so Back returns to it (device: with a new task, Back from
     * 더 어둡게 went to the home screen); from any other context it needs its own task.
     */
    static boolean start(Context context, Intent intent) {
        if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Log.w("PadTweaks", "page unavailable: " + intent.getAction() + " " + intent.getComponent() + " " + unavailable.getClass().getSimpleName());
            return false;
        }
    }
}
