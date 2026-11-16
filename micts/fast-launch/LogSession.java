package dev.padhelper.fastcts;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.util.Log;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Exactly one app-UID SOCK_SEQPACKET connection to logd, owned by the accessibility service.
 * No logcat child, shell agent, polling or heartbeat: a blocking read, and health is proven only by
 * entries whose logd-recorded writer is the system UID (own-UID entries never count).
 * Callbacks run on the service's main handler; raw log data never leaves the reader thread.
 */
final class LogSession {
    static final int OFF = 0, CONNECTING = 1, ACTIVE = 2;
    private static final String TAG = "PadLog";
    /** Structural candidate diagnostics per session; bounded so a noisy writer cannot flood the log. */
    private static final int MAX_DIAGNOSTICS = 64;
    interface Listener {
        void stateChanged(boolean declined);
        void trigger(int kind);
    }

    private final Handler handler;
    private final Listener listener;
    private final int systemUiUid, launcherUid, user;
    private LocalSocket socket;
    private int state = OFF;
    private long generation;

    LogSession(Handler handler, Listener listener, int systemUiUid, int launcherUid, int user) {
        this.handler = handler; this.listener = listener;
        this.systemUiUid = systemUiUid; this.launcherUid = launcherUid; this.user = user;
    }

    synchronized int state() { return state; }

    /** The ColorOS bar event is the failed start of a disabled (or absent) Breeno service. */
    static boolean breenoDisabled(Context context) {
        try {
            int state = context.getPackageManager().getApplicationEnabledSetting("com.heytap.speechassist");
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (IllegalArgumentException missing) { return true; }
    }

    /** Opens the single connection. Call only while this app UID is TOP (focused bridge activity). */
    synchronized boolean open() {
        if (socket != null) return true;
        LocalSocket created = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
        socket = created; state = CONNECTING;
        long session = ++generation;
        Thread reader = new Thread(() -> read(created, session), "PadLogSession");
        reader.setDaemon(true);
        reader.start();
        handler.postDelayed(() -> activationTimeout(session), LogTrigger.ACTIVATION_TIMEOUT);
        Log.i(TAG, "state CONNECTING session=" + session);
        return true;
    }

    synchronized void close() { if (drop(generation)) Log.i(TAG, "state OFF reason=closed"); }

    private synchronized boolean current(long session) { return session == generation && socket != null; }

    private void read(LocalSocket created, long session) {
        byte[] packet = new byte[LogTrigger.MAX_PACKET];
        LogTrigger.Gate gate = new LogTrigger.Gate(System.currentTimeMillis());
        boolean trusted = false, coalesced = false;
        int diagnostics = 0;
        try {
            created.connect(new LocalSocketAddress("logdr", LocalSocketAddress.Namespace.RESERVED));
            created.getOutputStream().write(LogTrigger.COMMAND.getBytes(StandardCharsets.US_ASCII));
            InputStream input = created.getInputStream();
            // SEQPACKET: one entry per read; 0 (EOF/EPIPE) or -1 ends the session instead of spinning.
            for (int count; (count = input.read(packet, 0, packet.length)) > 0;) {
                if (!current(session)) return;
                if (!trusted && LogTrigger.systemEntry(packet, count)) {
                    trusted = true;
                    handler.post(() -> activated(session));
                }
                if (!coalesced && LogTrigger.trailing(packet, count) > 0) {
                    coalesced = true;
                    Log.w(TAG, "coalesced read session=" + session + " bytes=" + count + " extra=" + LogTrigger.trailing(packet, count));
                }
                int candidate = LogTrigger.candidate(packet, count);
                int kind = trusted ? LogTrigger.classify(packet, count, systemUiUid, launcherUid, user) : LogTrigger.NONE;
                long now = System.currentTimeMillis();
                int outcome = kind == LogTrigger.NONE ? LogTrigger.NOT_TRIGGER : gate.check(kind, LogTrigger.millis(packet), now);
                if ((candidate != LogTrigger.NONE || kind != LogTrigger.NONE) && diagnostics < MAX_DIAGNOSTICS) {
                    diagnostics++;
                    Log.i(TAG, LogTrigger.describe(packet, count, candidate, kind, outcome, now) + " trusted=" + trusted);
                }
                if (outcome == LogTrigger.ACCEPT) {
                    long entry = LogTrigger.millis(packet);
                    handler.post(() -> {
                        if (!current(session)) return;
                        long delivered = System.currentTimeMillis();
                        if (LogTrigger.deliverable(entry, delivered)) listener.trigger(kind);
                        else Log.w(TAG, "trigger dropped kind=" + kind + " queued age=" + (delivered - entry) + "ms");
                    });
                }
            }
        } catch (IOException ended) {
            if (current(session)) Log.w(TAG, "Log session ended: " + ended.getClass().getSimpleName());
        }
        handler.post(() -> { if (drop(session)) { Log.i(TAG, "state OFF reason=reader-ended session=" + session); listener.stateChanged(false); } });
    }

    private void activated(long session) {
        synchronized (this) { if (!current(session) || state != CONNECTING) return; state = ACTIVE; }
        Log.i(TAG, "state ACTIVE reason=system-entry session=" + session);
        listener.stateChanged(false);
    }

    private void activationTimeout(long session) {
        synchronized (this) { if (!current(session) || state != CONNECTING) return; }
        // No system-UID entry: Android's confirmation was declined, dismissed or never shown.
        if (drop(session)) { Log.i(TAG, "state OFF reason=activation-timeout session=" + session); listener.stateChanged(true); }
    }

    private synchronized boolean drop(long session) {
        if (session != generation || socket == null) return false;
        LocalSocket old = socket;
        socket = null; state = OFF; ++generation;
        try { old.shutdownInput(); } catch (IOException | RuntimeException ignored) { }
        try { old.close(); } catch (IOException ignored) { }
        return true;
    }
}
