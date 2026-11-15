package dev.padhelper.fastcts;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/** Restores only this previously approved service during the finite boot window. */
final class BootRecovery {
    static final String SERVICE = "dev.padhelper.fastcts/dev.padhelper.fastcts.BarService";
    static final String SHORT_SERVICE = "dev.padhelper.fastcts/.BarService";
    static final String OPTION = "boot_recovery";
    static final String APPROVED = "accessibility_approved";

    static boolean containsOwn(String services) {
        if (services == null) return false;
        for (String entry : services.split(":"))
            if (SERVICE.equals(entry) || SHORT_SERVICE.equals(entry)) return true;
        return false;
    }

    static String withOwn(String services) {
        if (containsOwn(services)) return services;
        if (services == null || services.isEmpty()) return SERVICE;
        return services + (services.endsWith(":") ? "" : ":") + SERVICE;
    }

    static boolean permitted(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            == PackageManager.PERMISSION_GRANTED;
    }

    static boolean wanted(Context context) {
        return context.getSharedPreferences("bar", Context.MODE_PRIVATE).getBoolean(OPTION, false);
    }

    static void captureBeforeUnlock(Context context) {
        // Credential-protected preferences are deliberately never opened here.
        String services = Settings.Secure.getString(context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        // AMS temporarily clears the master switch before credential unlock when
        // only direct-boot-unaware services are enabled. The saved list, rather
        // than that computed switch, records the user's existing service consent.
        boolean wasEnabled = containsOwn(services);
        context.createDeviceProtectedStorageContext().getSharedPreferences("boot", Context.MODE_PRIVATE)
            .edit().putInt("count", boot).putBoolean("was_enabled", wasEnabled).commit();
        Log.i("PadBoot", "Captured pre-unlock accessibility state; enabled=" + wasEnabled);
    }

    static boolean shouldRecover(boolean optedIn, boolean enabled, boolean approved,
            boolean permitted, boolean sameBoot, boolean previouslyEnabled) {
        return optedIn && enabled && approved && permitted && sameBoot && previouslyEnabled;
    }

    static boolean eligible(Context context) {
        android.content.SharedPreferences bar = context.getSharedPreferences("bar", Context.MODE_PRIVATE);
        android.content.SharedPreferences boot = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("boot", Context.MODE_PRIVATE);
        int count = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        return shouldRecover(wanted(context), BarService.enabled(context), bar.getBoolean(APPROVED, false),
            permitted(context), count >= 0 && count == boot.getInt("count", -2), boot.getBoolean("was_enabled", false));
    }

    static void restore(Context context) {
        if (!eligible(context)) return;
        try {
            String current = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String updated = withOwn(current);
            boolean missing = !updated.equals(current);
            if (missing && !Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)) {
                Log.w("PadBoot", "Accessibility restore was not accepted");
                return;
            }
            boolean disabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1;
            if (disabled && !Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1)) {
                Log.w("PadBoot", "Accessibility master switch restore was not accepted");
                return;
            }
            if (missing || disabled) Log.i("PadBoot", "Restored previously enabled bottom search after boot");
        } catch (SecurityException error) { Log.w("PadBoot", "Boot restore permission unavailable", error); }
    }
}
