package dev.padhelper.fastcts;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
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
    private static final String REFRESH_KEY = "oplus_customize_screen_refresh_rate";
    private static final String SCREEN_MODE_SERVICE = "oplusscreenmode";
    private static final String SCREEN_MODE_DESCRIPTOR = "com.oplus.screenmode.IOplusScreenMode";
    private static final int REQUEST_REFRESH_RATE = 4, RATE_ID_144 = 4;
    private static final String REFRESH_REQUEST = "refresh_request";
    /** 0 = ColorOS's 자동 (index 0), the rest in Hz. */
    static final int[] REFRESH_RATES = {0, 60, 90, 120, 144};
    // The Settings controller and the Binder service use the same rate IDs on OPD2515.
    private static final int[] REFRESH_INDEXES = {0, 2, 1, 3, RATE_ID_144};
    enum RefreshChange { APPLIED, COLOROS_ONLY, FAILED }

    static boolean supportsScreenModeRequest() {
        return "OPD2515".equals(Build.MODEL) && Build.VERSION.SDK_INT == 37;
    }

    /** The chosen rate in Hz, 0 for ColorOS's 자동, -1 when unreadable or unknown. */
    static int maxRefreshRate(Context context) {
        int index;
        try { index = Settings.Secure.getInt(context.getContentResolver(), REFRESH_KEY, -1); }
        catch (RuntimeException unreadable) { return -1; }
        for (int i = 0; i < REFRESH_INDEXES.length; i++) if (REFRESH_INDEXES[i] == index) return REFRESH_RATES[i];
        return -1;
    }

    /** Saves the ColorOS mode, then applies or releases the global request on the validated firmware. */
    static RefreshChange setMaxRefreshRate(Context context, int hz) {
        for (int i = 0; i < REFRESH_RATES.length; i++) {
            if (REFRESH_RATES[i] != hz) continue;
            int mode = REFRESH_INDEXES[i];
            // Cleared before the key changes, so the key watcher never takes this app's own change for a ColorOS choice.
            prefs(context).edit().remove(REFRESH_REQUEST).apply();
            try {
                if (!Settings.Secure.putInt(context.getContentResolver(), REFRESH_KEY, mode)) return RefreshChange.FAILED;
            } catch (RuntimeException refused) {
                Log.w("PadTweaks", "refresh rate setting refused");
                return RefreshChange.FAILED;
            }
            boolean applied = supportsScreenModeRequest() && requestScreenMode(mode);
            // Remembered so reapply can send it again after boot (only while it is an active, non-자동 request).
            if (applied && mode != 0) prefs(context).edit().putInt(REFRESH_REQUEST, mode).apply();
            return applied ? RefreshChange.APPLIED : RefreshChange.COLOROS_ONLY;
        }
        return RefreshChange.FAILED;
    }

    private static boolean requestScreenMode(int mode) {
        boolean enable = mode != 0;
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, SCREEN_MODE_SERVICE);
            if (binder == null || !SCREEN_MODE_DESCRIPTOR.equals(binder.getInterfaceDescriptor())) return false;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(SCREEN_MODE_DESCRIPTOR);
                data.writeBoolean(enable);
                data.writeInt(enable ? mode : RATE_ID_144);
                if (!binder.transact(REQUEST_REFRESH_RATE, data, reply, 0)) return false;
                reply.readException();
                boolean accepted = reply.readBoolean();
                Log.i("PadTweaks", "screen mode request=" + mode + " accepted=" + accepted);
                // On this firmware releasing an already absent vote also returns false.
                return accepted || !enable;
            } finally {
                reply.recycle();
                data.recycle();
            }
        } catch (ReflectiveOperationException | RemoteException | RuntimeException refused) {
            Log.w("PadTweaks", "screen mode request unavailable: " + refused.getClass().getSimpleName());
            return false;
        }
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
        reapplyRefreshRate(context);
    }

    /**
     * Device (OPD2515, 2026-11-28): at boot ColorOS rebuilds the 144Hz request itself only when its saved choice is 144
     * AND System peak_refresh_rate is 144. With peak at 120 the screen stays at 120Hz, and apps cannot write peak
     * ("You cannot keep your settings in the secure settings", even with both settings permissions). So the request this
     * app made is sent again, while ColorOS's saved choice still matches it; a choice made in ColorOS settings wins.
     */
    private static void reapplyRefreshRate(Context context) {
        int mode = prefs(context).getInt(REFRESH_REQUEST, 0);
        if (mode == 0) return;
        int current;
        try { current = Settings.Secure.getInt(context.getContentResolver(), REFRESH_KEY, -1); }
        catch (RuntimeException unreadable) { return; }
        if (current != mode || !supportsScreenModeRequest()) { prefs(context).edit().remove(REFRESH_REQUEST).apply(); return; }
        // Blocking vendor Binder call: kept off the caller's (main) thread.
        new Thread(() -> Log.i("PadTweaks", "refresh request re-sent: " + mode + " ok=" + requestScreenMode(mode)),
            "PadRefreshRate").start();
    }

    private static android.database.ContentObserver refreshWatch;

    /**
     * Firmware (oplus-services OplusRefreshRatePolicyImpl): the request is one vote slot (priority 70) that a release
     * clears whatever rate it names, and ColorOS's own settings observer for REFRESH_KEY never touches that slot. So a
     * rate picked later in ColorOS settings would stay overridden by this app's request; it is released here instead.
     */
    static synchronized void watchRefreshRate(Context context) {
        if (refreshWatch != null) return;
        Context app = context.getApplicationContext();
        refreshWatch = new android.database.ContentObserver(null) { // binder thread: the blocking release may run here
            @Override public void onChange(boolean selfChange) {
                int mode = prefs(app).getInt(REFRESH_REQUEST, 0);
                if (mode == 0) return;
                int current;
                try { current = Settings.Secure.getInt(app.getContentResolver(), REFRESH_KEY, -1); }
                catch (RuntimeException unreadable) { return; }
                if (current == mode) return;
                prefs(app).edit().remove(REFRESH_REQUEST).apply();
                Log.i("PadTweaks", "ColorOS rate changed to " + current + ": request released ok=" + requestScreenMode(0));
            }
        };
        try { app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(REFRESH_KEY), false, refreshWatch); }
        catch (RuntimeException unavailable) { refreshWatch = null; }
    }

    /** Releases this app's refresh request, if any (모든 설정 초기화). ColorOS's own saved choice is left as it is. */
    private static void releaseRefreshRate(Context context) {
        if (prefs(context).getInt(REFRESH_REQUEST, 0) == 0) return;
        prefs(context).edit().remove(REFRESH_REQUEST).apply();
        new Thread(() -> Log.i("PadTweaks", "refresh request released ok=" + requestScreenMode(0)), "PadRefreshRate").start();
    }

    /** 모든 설정 초기화: every switch of this class back to its default (fails quietly without 보안 설정 쓰기). */
    static void resetDefaults(Context context) {
        setSecure(context, ROTATION_SUGGESTIONS, true); // rotation button shown again = '자동 회전 제안 끄기' off
        setSecure(context, CLOCK_SECONDS, false);
        setSecure(context, SYSTEM_OPT, false);
        setAnimationScale(context, 1f);
        releaseRefreshRate(context);
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
