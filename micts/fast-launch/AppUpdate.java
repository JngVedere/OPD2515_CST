package dev.padhelper.fastcts;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * Update check from the settings screen only (its "업데이트 확인" button, or once per open while the user keeps the
 * automatic check on; nothing in the background): reads App-Release.json from the repository's main branch,
 * downloads the matching GitHub release APK, checks its SHA-256 and hands it to PackageInstaller, which shows
 * Android's own confirmation (and rejects a different signing key). The only network use in this app; nothing is sent.
 */
final class AppUpdate {
    private static final String REPO = "JngVedere/OPD2515_CST", PACKAGE = "dev.padhelper.fastcts";
    private static final String META = "https://raw.githubusercontent.com/" + REPO + "/main/App-Release.json",
        META_BETA = "https://raw.githubusercontent.com/" + REPO + "/main/App-Release-Beta.json";
    private static final int MAX_META = 4096, MAX_APK = 20 * 1024 * 1024, TIMEOUT = 15000;
    private static final String TAG = "PadUpdate";

    private AppUpdate() {}

    static final class Release {
        final String version, file, sha256;
        final long code;
        boolean beta;
        Release(String version, long code, String file, String sha256) {
            this.version = version; this.code = code; this.file = file; this.sha256 = sha256;
        }
        String apkUrl() { return "https://github.com/" + REPO + "/releases/download/v" + version + "/" + file; }
        String pageUrl() { return "https://github.com/" + REPO + "/releases/tag/v" + version; }
    }

    /** Pure: the published metadata names this package, a strictly newer code and the release naming rules, else null. */
    static Release newer(int schema, String pkg, String version, long code, String file, String sha256, long current) {
        if (schema != 1 || !PACKAGE.equals(pkg) || code <= current || version == null || sha256 == null) return null;
        if (!version.matches("\\d+\\.\\d+\\.\\d+(?:[-.A-Za-z0-9]+)?") || version.contains("..")) return null;
        if (!("Circle-to-Search-Trigger-" + version + ".apk").equals(file) || !sha256.matches("[0-9a-fA-F]{64}")) return null;
        return new Release(version, code, file, sha256.toLowerCase(Locale.ROOT));
    }

    /** Pure: the higher version code of two candidates (either may be null); stable wins a tie. */
    static Release pick(Release stable, Release beta) {
        if (beta == null) return stable;
        if (stable == null || beta.code > stable.code) { beta.beta = true; return beta; }
        return stable;
    }

    /** Blocking (worker thread). Null when this install is already the newest published (stable, or also beta) version. */
    static Release check(Context context, boolean includeBeta) throws Exception {
        long current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        Release stable = parse(read(META), current), beta = null;
        if (includeBeta) {
            try { beta = parse(read(META_BETA), current); }
            catch (java.io.FileNotFoundException none) { } // No beta published right now.
        }
        return pick(stable, beta);
    }

    private static Release parse(byte[] metadata, long current) throws org.json.JSONException {
        JSONObject json = new JSONObject(new String(metadata, StandardCharsets.UTF_8));
        return newer(json.optInt("Schema"), json.optString("Package", null), json.optString("Version", null),
            json.optLong("VersionCode"), json.optString("FileName", null), json.optString("SHA256", null), current);
    }

    /** Blocking (worker thread): streams the APK into an install session; commits only if the SHA-256 matches. */
    static void install(Context context, Release release) throws IOException, GeneralSecurityException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            HttpsURLConnection connection = open(release.apkUrl());
            try {
                long length = connection.getContentLengthLong();
                if (length > MAX_APK) throw new IOException("APK too large");
                try (InputStream in = connection.getInputStream();
                     OutputStream out = session.openWrite("update.apk", 0, length > 0 ? length : -1)) {
                    byte[] buffer = new byte[65536];
                    long total = 0;
                    for (int n; (n = in.read(buffer)) > 0;) {
                        if ((total += n) > MAX_APK) throw new IOException("APK too large");
                        digest.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                    session.fsync(out);
                }
            } finally { connection.disconnect(); }
            if (!hex(digest.digest()).equals(release.sha256)) throw new GeneralSecurityException("SHA-256 mismatch");
            Intent status = new Intent(context, Result.class);
            // Mutable: PackageInstaller fills in the status extras. Explicit component, own package only.
            PendingIntent pending = PendingIntent.getBroadcast(context, id, status,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            committed = true;
            Log.i(TAG, "install session committed version=" + release.version);
        } finally {
            if (!committed) installer.abandonSession(id);
        }
    }

    private static HttpsURLConnection open(String url) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setUseCaches(false);
        int code = connection.getResponseCode();
        if (code == 404) { connection.disconnect(); throw new java.io.FileNotFoundException(url); }
        if (code != 200) { connection.disconnect(); throw new IOException("HTTP " + code); }
        return connection;
    }

    private static byte[] read(String url) throws IOException {
        HttpsURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int n; (n = in.read(buffer)) > 0;) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_META) throw new IOException("Release metadata too large");
            }
            return out.toByteArray();
        } finally { connection.disconnect(); }
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b));
        return text.toString();
    }

    /** Install session status: opens Android's confirmation; success replaces (and restarts) this app. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                try {
                    if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException blocked) {
                    Log.w(TAG, "confirmation unavailable", blocked);
                    Toast.makeText(context, "설치 확인 화면을 열지 못했습니다. 설정 화면에서 다시 시도해주세요.", Toast.LENGTH_LONG).show();
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w(TAG, "install failed status=" + status + " " + message);
                Toast.makeText(context, status == PackageInstaller.STATUS_FAILURE_ABORTED ? "업데이트를 취소했습니다."
                    : "업데이트를 설치하지 못했습니다 (" + status + ").", Toast.LENGTH_LONG).show();
            }
        }
    }
}
