package dev.padhelper.fastcts;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

/** Watches this app's accessibility connection and attempts one opted-in restore after user unlock. */
final class BootRecovery {
    static final String SERVICE = "dev.padhelper.fastcts/dev.padhelper.fastcts.BarService";
    static final String SHORT_SERVICE = "dev.padhelper.fastcts/.BarService";
    static final String OPTION = "boot_recovery";
    static final String TEMP_OPTION = "boot_watch_opt_in";
    static final String APPROVED = "accessibility_approved";
    private static final String TAG = "PadBoot";
    private static final long WINDOW_MS = 30_000;
    private static BroadcastReceiver unlockReceiver;
    private static Watch watch;

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

    /** The list without this service (experimental overlay mode); unchanged when it was not in it. */
    static String withoutOwn(String services) {
        if (!containsOwn(services)) return services;
        StringBuilder kept = new StringBuilder();
        for (String entry : services.split(":"))
            if (!entry.isEmpty() && !SERVICE.equals(entry) && !SHORT_SERVICE.equals(entry))
                kept.append(kept.length() == 0 ? "" : ":").append(entry);
        return kept.toString();
    }

    static boolean permitted(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences bar(Context context) {
        return context.getSharedPreferences("bar", Context.MODE_PRIVATE);
    }

    private static SharedPreferences boot(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("boot", Context.MODE_PRIVATE);
    }

    static boolean wanted(Context context) {
        SharedPreferences preferences = bar(context);
        return preferences.getBoolean(TEMP_OPTION, preferences.getBoolean(OPTION, true));
    }

    static boolean active(Context context) { return wanted(context) && permitted(context); }

    static void setActive(Context context, boolean active) {
        bar(context).edit().putBoolean(OPTION, active).remove(TEMP_OPTION).apply();
        if (!active) stop(context);
    }

    /**
     * ColorOS "자동 실행" off blocks this app's boot broadcasts (device: no LOCKED_BOOT_COMPLETED capture that boot), so
     * a captured boot count behind the current one means auto-start was blocked. Unknown after an install or update
     * made since this boot, when no boot broadcast could have arrived yet.
     */
    static boolean autoStartMissed(int currentBoot, int capturedBoot, long installedAt, long bootedAt) {
        return currentBoot >= 0 && capturedBoot != currentBoot && installedAt < bootedAt;
    }

    static int capturedBoot(Context context) {
        return boot(context).getInt("count", -2);
    }

    private static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    static void lockedBoot(Context context) {
        Context app = context.getApplicationContext();
        boot(app).edit().putInt("count", bootCount(app)).apply();
        if (isUserUnlocked(app)) {
            unlockedBoot(app);
            return;
        }
        if (unlockReceiver != null) return;
        unlockReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { unlockedBoot(app); }
        };
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(unlockReceiver, filter);
        } catch (RuntimeException error) {
            unlockReceiver = null; // BOOT_COMPLETED can still start the check.
            Log.w(TAG, "Unable to listen for user unlock", error);
        }
    }

    private static boolean isUserUnlocked(Context context) {
        return context.getSystemService(UserManager.class).isUserUnlocked();
    }

    static void unlockedBoot(Context context) {
        if (!isUserUnlocked(context)) return;
        Context app = context.getApplicationContext();
        if (unlockReceiver != null) {
            app.unregisterReceiver(unlockReceiver);
            unlockReceiver = null;
        }
        int count = bootCount(app);
        SharedPreferences state = boot(app);
        if (count < 0 || state.getInt("watch_boot", -1) == count) return;
        // Consume the first unlock even if monitoring is disabled. Later deliveries in this boot must not open a new window.
        if (!state.edit().putInt("watch_boot", count).commit()) {
            Log.w(TAG, "Unable to record boot check");
            return;
        }
        if (!wanted(app) || !BarService.enabled(app) || BarService.overlayMode(app)
                || !bar(app).getBoolean(APPROVED, false)) {
            Log.i(TAG, "Accessibility watch skipped: wanted=" + wanted(app) + " enabled=" + BarService.enabled(app)
                + " overlay=" + BarService.overlayMode(app) + " previouslyBound=" + bar(app).getBoolean(APPROVED, false));
            return;
        }
        watch = new Watch(app);
        try {
            watch.start();
        } catch (RuntimeException error) {
            watch.close("error");
            Log.w(TAG, "Accessibility watch unavailable", error);
        }
    }

    static void serviceChanged() {
        if (watch != null) watch.check("service", false);
    }

    static void stop(Context context) {
        if (watch != null) watch.close("disabled");
        Alerts.accessibilityRestored(context);
    }

    private static boolean restore(Context app) {
        if (!permitted(app)) {
            Log.i(TAG, "Accessibility restore skipped: secure-settings permission missing");
            return false;
        }
        try {
            var resolver = app.getContentResolver();
            String current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (!containsOwn(current) && !Settings.Secure.putString(resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withOwn(current))) return false;
            return Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                || Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException refused) {
            Log.w(TAG, "Accessibility restore permission unavailable", refused);
            return false;
        }
    }

    private static final class Watch {
        private final Context app;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final long start = SystemClock.elapsedRealtime();
        private final ContentObserver observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { check("settings", false); }
        };
        private boolean attempted;
        private boolean notified;
        private String lastState;

        Watch(Context app) { this.app = app; }

        void start() {
            for (String key : new String[]{Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED}) {
                app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key), false, observer);
            }
            handler.postDelayed(() -> check("deadline", true), WINDOW_MS);
            check("unlock", false);
        }

        void check(String event, boolean deadline) {
            if (watch != this) return;
            long age = SystemClock.elapsedRealtime() - start;
            if (!deadline && age >= WINDOW_MS) {
                close("expired");
                return;
            }
            try {
                boolean registered = BarService.accessibilityRegistered(app);
                boolean enabled = Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
                boolean bound = BarService.accessibilityBound();
                String state = "registered=" + registered + " master=" + enabled + " bound=" + bound
                    + " logsWanted=" + BarService.logsWanted(app) + " logState=" + BarService.logState();
                if (deadline || !state.equals(lastState)) {
                    Log.i(TAG, "Accessibility watch " + event + " at " + age + "ms: " + state);
                    lastState = state;
                }
                // ColorOS removes our service from the list. A temporary master-switch change before binding can
                // precede that removal and must leave the one restore attempt available.
                if (!registered && !attempted && age < WINDOW_MS && wanted(app)) {
                    attempted = true;
                    boolean submitted = restore(app);
                    Log.i(TAG, "Accessibility restore submitted=" + submitted);
                    if (submitted) return;
                }
                if (!notified && (!registered || (deadline && (!enabled || !bound)))) {
                    notified = true;
                    Alerts.accessibilityMissing(app);
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to check accessibility", error);
                close("error");
            } finally {
                if (deadline) close("deadline");
            }
        }

        void close(String reason) {
            if (watch != this) return;
            watch = null;
            handler.removeCallbacksAndMessages(null);
            app.getContentResolver().unregisterContentObserver(observer);
            Log.i(TAG, "Accessibility watch closed: " + reason);
        }
    }
}
