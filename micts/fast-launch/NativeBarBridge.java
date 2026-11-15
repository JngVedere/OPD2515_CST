package dev.padhelper.fastcts;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;

/** Accessibility-owned Binder endpoint, registered only by authenticated ADB shell. */
final class NativeBarBridge implements AutoCloseable {
    private static volatile NativeBarBridge current;
    private final Handler handler;
    private final Runnable changed, gesture;
    private final long version;
    private Session session;
    private boolean active, reading, closed;
    private long generation;

    static boolean breenoDisabled(Context context) {
        try {
            int state = context.getPackageManager().getApplicationEnabledSetting("com.heytap.speechassist");
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (IllegalArgumentException missing) { return true; }
    }

    static String activationCommand(Context context) throws PackageManager.NameNotFoundException {
        String apk = context.getApplicationInfo().sourceDir;
        if (!apk.matches("/[-A-Za-z0-9_./+=@~]+\\.apk")) throw new IllegalStateException("Unsupported APK path");
        int uid = context.getApplicationInfo().uid;
        int systemUi = context.getPackageManager().getApplicationInfo("com.android.systemui", 0).uid;
        long version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        return "adb shell \"CLASSPATH=" + apk + " /system/bin/nohup /system/bin/app_process /system/bin dev.padhelper.fastcts.NativeBarAgent "
            + uid + " " + version + " " + systemUi + " " + (uid / 100000) + " >/dev/null 2>&1 </dev/null &\"";
    }

    static String reason(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error.getClass().getSimpleName();
    }

    NativeBarBridge(Context context, Handler handler, Runnable changed, Runnable gesture) {
        this.handler = handler; this.changed = changed; this.gesture = gesture;
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException impossible) { throw new IllegalStateException(impossible); }
    }

    synchronized void start() { if (!closed) current = this; }

    static Bundle register(Bundle request) {
        if (Binder.getCallingUid() != 2000) throw new SecurityException("ADB shell only");
        NativeBarBridge bridge = current;
        return bridge == null ? result(NativeBarProtocol.UNAVAILABLE) : bridge.registerAgent(request);
    }

    static Bundle result(int code) {
        Bundle reply = new Bundle(); reply.putInt(NativeBarProtocol.KEY_RESULT, code); return reply;
    }

    private synchronized Bundle registerAgent(Bundle request) {
        if (closed) return result(NativeBarProtocol.UNAVAILABLE);
        if (request == null || request.getInt(NativeBarProtocol.KEY_PROTOCOL, -1) != NativeBarProtocol.VERSION
            || request.getLong(NativeBarProtocol.KEY_VERSION, -1) != version) return result(NativeBarProtocol.REJECT);
        IBinder callback = request.getBinder(NativeBarProtocol.KEY_CALLBACK);
        if (callback == null) return result(NativeBarProtocol.REJECT);
        Session next = new Session(callback);
        try { callback.linkToDeath(next.death, 0); }
        catch (RemoteException dead) { return result(NativeBarProtocol.UNAVAILABLE); }
        drop(true);
        session = next;
        Bundle reply = result(NativeBarProtocol.OK);
        reply.putBinder(NativeBarProtocol.KEY_EVENTS, next.events);
        // Wait for READY: the agent must have its event endpoint before any RUN is sent.
        return reply;
    }

    private final class Session {
        final IBinder callback;
        boolean ready;
        final IBinder.DeathRecipient death = () -> handler.post(() -> disconnected(this));
        final Binder events = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (Binder.getCallingUid() != 2000) throw new SecurityException("ADB shell only");
                data.enforceInterface(NativeBarProtocol.DESCRIPTOR);
                if (code != NativeBarProtocol.READY && code != NativeBarProtocol.RUNNING
                    && code != NativeBarProtocol.PAUSED && code != NativeBarProtocol.HOLD) return false;
                if (data.dataAvail() != 16) throw new BadParcelableException("Unexpected native-bar data");
                long epoch = data.readLong(), time = data.readLong();
                if (code == NativeBarProtocol.READY && (epoch != 0 || time != 0)) return false;
                handler.post(() -> receive(Session.this, code, epoch, time));
                return true;
            }
        };
        Session(IBinder callback) { this.callback = callback; }
    }

    private void receive(Session source, int code, long epoch, long time) {
        boolean hold = false;
        synchronized (this) {
            if (closed || session != source) return;
            if (code == NativeBarProtocol.READY) {
                if (source.ready) return;
                source.ready = true;
                control(active ? NativeBarProtocol.RUN : NativeBarProtocol.PAUSE, ++generation);
            } else {
                if (!source.ready || epoch != generation) return;
                if (code == NativeBarProtocol.RUNNING) reading = active;
                else if (code == NativeBarProtocol.PAUSED) reading = false;
                else hold = active && reading && NativeBarProtocol.freshHold(time, SystemClock.elapsedRealtime());
            }
        }
        if (hold) gesture.run();
        else changed.run();
    }

    private synchronized void disconnected(Session source) {
        if (session != source) return;
        drop(false); handler.post(changed);
    }

    synchronized boolean connected() { return session != null && session.ready; }
    synchronized boolean reading() { return connected() && reading; }

    synchronized void active(boolean enabled) {
        if (active == enabled) return;
        active = enabled; reading = false; ++generation;
        if (connected()) control(enabled ? NativeBarProtocol.RUN : NativeBarProtocol.PAUSE, generation);
    }

    private void control(int code, long epoch) {
        if (session == null) return;
        try { NativeBarWire.send(session.callback, code, epoch, 0); }
        catch (RemoteException dead) { drop(false); handler.post(changed); }
    }

    synchronized void quit() { drop(true); handler.post(changed); }

    private void drop(boolean quit) {
        Session old = session;
        session = null; reading = false; ++generation;
        if (old == null) return;
        old.callback.unlinkToDeath(old.death, 0);
        try { NativeBarWire.send(old.callback, quit ? NativeBarProtocol.QUIT : NativeBarProtocol.DETACH, 0, 0); }
        catch (RemoteException ignored) { }
    }

    @Override public synchronized void close() {
        closed = true;
        if (current == this) current = null;
        drop(false);
    }
}
