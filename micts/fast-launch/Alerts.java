package dev.padhelper.fastcts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Optional notifications (both off by default; settings -> 알림): device-log access that an enabled option needs is
 * missing, and a new version is available. Plain dismissible notifications only, never a persistent or foreground one.
 * The update check is a periodic system job (24 h), scheduled only while that alert is on, and guarded so it runs at most
 * once per StatusPolicy.UPDATE_INTERVAL. Device (ColorOS 17): an unlock broadcast to the service was skipped
 * ("oplus skip sp"), so it could not carry the check.
 */
final class Alerts {
    static final String LOG = "alert_log", UPDATE = "alert_update";
    /**
     * Opt-in: the device-log notice also as an Android 16 Live Update, which ColorOS shows in the capsule by the camera.
     * Device (ColorOS 17): promotion also needs the per-app "라이브 알림에 라이브 업데이트 표시" switch, off by default
     * (canPostPromotedNotifications false until the user turns it on). Android promotes only ongoing notices, so the
     * notice is ongoing only while it can actually be promoted; it is cancelled as soon as the log reconnects.
     */
    static final String ISLAND = "alert_island";
    private static final String CHECKED_AT = "update_checked_at", NOTIFIED_CODE = "update_notified_code";
    static final int LOG_MISSING = 2, NEW_VERSION = 3, TEST = 4;
    private static final long TEST_MS = 10_000;
    /** Set when an update notification went out; the settings screen shows its update banner once on the next open. */
    private static final String PENDING = "update_pending";

    private Alerts() {}

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("bar", Context.MODE_PRIVATE); }

    static boolean wanted(Context context, String key) { return prefs(context).getBoolean(key, false); }

    /**
     * Device-log access missing: posted only when the user turned this notification on. approve: the tap opens the same
     * internal bridge as the settings button, so Android's own confirmation appears directly (the user still answers it).
     * Without the ADB grant a confirmation cannot help, so the tap opens settings instead.
     */
    static void logMissing(Context context, boolean approve, String title, String text) {
        if (!wanted(context, LOG)) return;
        post(context, LOG_MISSING, "log", "기기 로그 권한", title, text, wanted(context, ISLAND), approve
            ? new Intent(context, LogAccessActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
            : settings(context));
    }

    // Settings with no extras: that exported screen ignores intent extras by design.
    private static Intent settings(Context context) {
        return new Intent(context, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static void logRestored(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(LOG_MISSING);
    }

    private static final int UPDATE_JOB = 1;

    /**
     * Update alert on: a persisted 24 h job. Off: the job is cancelled. Kept if already scheduled. No network constraint:
     * that needs ACCESS_NETWORK_STATE (device: the schedule call threw without it); an offline run just waits 24 h.
     * Never throws: this runs while the accessibility service connects, where an exception would take the service down.
     */
    static void scheduleUpdateCheck(Context context) {
        try {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            if (!wanted(context, UPDATE)) { jobs.cancel(UPDATE_JOB); return; }
            if (jobs.getPendingJob(UPDATE_JOB) != null) return; // rescheduling would restart the 24 h period
            jobs.schedule(new JobInfo.Builder(UPDATE_JOB, new ComponentName(context, UpdateJob.class))
                .setPeriodic(StatusPolicy.UPDATE_INTERVAL).setPersisted(true).build());
        } catch (RuntimeException unavailable) {
            Log.w("PadAlerts", "update job unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    /** The scheduled check. Not exported; only the system's job scheduler (BIND_JOB_SERVICE) binds it. */
    public static final class UpdateJob extends JobService {
        @Override public boolean onStartJob(JobParameters params) {
            maybeCheckUpdate(this, getSharedPreferences("bar", MODE_PRIVATE).getBoolean(SetupActivity.UPDATE_BETA, false),
                () -> jobFinished(params, false));
            return true;
        }
        @Override public boolean onStopJob(JobParameters params) { return false; }
    }

    /** One check per 24 h, recorded before the request so a failure also waits a full interval. done runs when finished. */
    static void maybeCheckUpdate(Context context, boolean includeBeta, Runnable done) {
        SharedPreferences bar = prefs(context);
        long now = System.currentTimeMillis();
        if (!bar.getBoolean(UPDATE, false) || !StatusPolicy.updateCheckDue(now, bar.getLong(CHECKED_AT, 0))) { done.run(); return; }
        Log.i("PadAlerts", "update check (24 h)");
        bar.edit().putLong(CHECKED_AT, now).apply();
        Context app = context.getApplicationContext();
        try {
            new Thread(() -> {
                try {
                    AppUpdate.Release release = AppUpdate.check(app, includeBeta);
                    // One notification per version: the same release is never announced twice.
                    if (release == null || release.code == prefs(app).getLong(NOTIFIED_CODE, -1)) return;
                    prefs(app).edit().putLong(NOTIFIED_CODE, release.code).putBoolean(PENDING, true).apply();
                    post(app, NEW_VERSION, "update", "업데이트",
                        (release.beta ? "새 베타 업데이트 · " : "새로운 업데이트 · ") + release.version,
                        "눌러서 설정 화면에서 설치할 수 있습니다.", false, settings(app));
                } catch (Exception failed) {
                    Log.i("PadAlerts", "update check failed: " + failed.getClass().getSimpleName());
                } finally { done.run(); }
            }, "PadUpdateCheck").start();
        } catch (RuntimeException | OutOfMemoryError unavailable) { done.run(); }
    }

    /** Settings screen open: true once after an update notification (the screen then checks and shows its banner). */
    static boolean takePendingUpdate(Context context) {
        if (!prefs(context).getBoolean(PENDING, false)) return false;
        prefs(context).edit().remove(PENDING).apply();
        return true;
    }

    /** open: an explicit intent to one of this app's own activities, without extras. */
    /** Whether a Live Update from this app can reach the capsule now (Android 16+, per-app switch on). */
    static boolean islandAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return Build.VERSION.SDK_INT >= 36 && manager != null && manager.canPostPromotedNotifications();
    }

    private static void post(Context context, int id, String channel, String channelName, String title, String text,
            boolean island, Intent open) {
        post(context, id, channel, channelName, title, text, island, open, "로그 승인", 0);
    }

    /** Settings button demo: the log notice's capsule form with test text; it removes itself after TEST_MS. */
    static void test(Context context) {
        post(context, TEST, "log", "기기 로그 권한", "카메라 섬 테스트 알림", "Circle-to-Search Trigger의 알림이 여기에 뜹니다. 10초 뒤 사라집니다.",
            true, settings(context), "테스트", TEST_MS);
    }

    /** chip: the capsule's short text. timeout > 0: the system removes the notice after that many ms. */
    private static void post(Context context, int id, String channel, String channelName, String title, String text,
            boolean island, Intent open, String chip, long timeout) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = context.getResources().getIdentifier("ic_tile_assistant", "drawable", context.getPackageName()); // no R class in this build
        Notification.Builder builder = new Notification.Builder(context, channel)
            .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_error).setContentTitle(title).setContentText(text)
            .setContentIntent(tap).setAutoCancel(true);
        // Live Update (device-verified recipe): big text, ongoing, promotion request; the capsule shows the short text.
        boolean live = island && islandAllowed(context);
        if (live) builder.setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(live).setOnlyAlertOnce(true)
            .setRequestPromotedOngoing(true).setShortCriticalText(chip);
        if (timeout > 0) builder.setTimeoutAfter(timeout);
        manager.notify(id, builder.build());
    }
}
