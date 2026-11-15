package dev.padhelper.fastcts;

import android.app.ActivityManager;
import android.content.AttributionSource;
import android.content.pm.ProviderInfo;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.lang.reflect.*;

/** Optional app_process entry point. Uses the same external-provider API as Android's content shell tool. */
public final class NativeBarAgent {
    private static final String TAG = "PadNativeAgent";
    private static final long RECOVERY_MILLIS = 30000;
    private final int appUid, systemUiUid, user;
    private IBinder events;
    private java.lang.Process logcat;
    private long generation;
    private volatile long lastEventAt = -1;
    private boolean disconnected, quitting, linked;
    private final IBinder.DeathRecipient death = () -> disconnect(false);

    private final Binder control = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (Binder.getCallingUid() != appUid) throw new SecurityException("Unexpected native-bar app UID");
            data.enforceInterface(NativeBarProtocol.DESCRIPTOR);
            if (data.dataAvail() != 16) throw new BadParcelableException("Unexpected native-bar control");
            long epoch = data.readLong(), time = data.readLong();
            if (time != 0) return false;
            synchronized (NativeBarAgent.this) {
                if (disconnected) return false;
                if (code == NativeBarProtocol.QUIT || code == NativeBarProtocol.DETACH) {
                    if (epoch != 0) return false;
                    disconnect(code == NativeBarProtocol.QUIT); return true;
                }
                if ((code != NativeBarProtocol.RUN && code != NativeBarProtocol.PAUSE)
                    || !NativeBarProtocol.newer(epoch, generation)) return false;
                generation = epoch; stopReader();
                if (code == NativeBarProtocol.RUN) {
                    try { runReader(epoch); }
                    catch (IOException failure) { Log.w(TAG, "Reader unavailable: " + NativeBarBridge.reason(failure)); event(NativeBarProtocol.PAUSED, epoch, 0); }
                } else event(NativeBarProtocol.PAUSED, epoch, 0);
            }
            return true;
        }
    };

    private NativeBarAgent(int appUid, int systemUiUid, int user) { this.appUid = appUid; this.systemUiUid = systemUiUid; this.user = user; }

    private synchronized void event(int code, long epoch, long time) {
        if (events == null || disconnected) return;
        try { NativeBarWire.send(events, code, epoch, time); }
        catch (RemoteException dead) { disconnect(false); }
    }

    private synchronized void stopReader() { if (logcat != null) logcat.destroy(); logcat = null; }

    private synchronized void disconnect(boolean quit) {
        quitting |= quit; disconnected = true; ++generation; stopReader(); notifyAll();
    }

    private void runReader(long epoch) throws IOException {
        long watermark = System.currentTimeMillis();
        java.lang.Process process = new ProcessBuilder("/system/bin/logcat", "-b", "system", "--uid=1000",
            "-v", "epoch", "-T", "1", "ActivityManager:W", "*:S").redirectErrorStream(true).start();
        logcat = process;
        Thread reader = new Thread(() -> {
            long last = -2000;
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line; (line = lines.readLine()) != null;) {
                    synchronized (NativeBarAgent.this) { if (logcat != process || generation != epoch || disconnected) return; }
                    long now = SystemClock.elapsedRealtime();
                    if (NativeBarProtocol.isGesture(line, systemUiUid, user, watermark, System.currentTimeMillis()) && now - last >= 1500) {
                        synchronized (NativeBarAgent.this) {
                            if (logcat == process && generation == epoch && !disconnected) {
                                last = now; lastEventAt = now; event(NativeBarProtocol.HOLD, epoch, now);
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                process.destroy();
                synchronized (NativeBarAgent.this) {
                    if (logcat == process && generation == epoch) {
                        logcat = null; event(NativeBarProtocol.PAUSED, epoch, 0);
                        // Do not remain connected forever with no reader. Re-register through the bounded recovery path.
                        disconnect(false);
                    }
                }
            }
        }, "PadNativeLog");
        reader.setDaemon(true); reader.start();
        event(NativeBarProtocol.RUNNING, epoch, 0);
    }

    /** Shell has no application Context. Acquire and release an external provider like the platform content command. */
    private Bundle register(long version) throws Exception {
        Object manager = ActivityManager.class.getDeclaredMethod("getService").invoke(null);
        Class<?> api = Class.forName("android.app.IActivityManager");
        IBinder token = new Binder();
        Object holder = api.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
            .invoke(manager, NativeBarProtocol.AUTHORITY, user, token, "native-bar");
        if (holder == null) return NativeBarBridge.result(NativeBarProtocol.UNAVAILABLE);
        try {
            ProviderInfo info = (ProviderInfo) holder.getClass().getField("info").get(holder);
            if (info.applicationInfo.uid != appUid || !"dev.padhelper.fastcts".equals(info.packageName))
                throw new SecurityException("Unexpected native-bar provider owner");
            Object provider = holder.getClass().getField("provider").get(holder);
            Bundle request = new Bundle();
            request.putInt(NativeBarProtocol.KEY_PROTOCOL, NativeBarProtocol.VERSION);
            request.putLong(NativeBarProtocol.KEY_VERSION, version);
            request.putBinder(NativeBarProtocol.KEY_CALLBACK, control);
            Class<?> content = Class.forName("android.content.IContentProvider");
            if (Build.VERSION.SDK_INT >= 31) {
                AttributionSource source = new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
                return (Bundle) content.getMethod("call", AttributionSource.class, String.class, String.class, String.class, Bundle.class)
                    .invoke(provider, source, NativeBarProtocol.AUTHORITY, NativeBarProtocol.REGISTER, null, request);
            }
            return (Bundle) content.getMethod("call", String.class, String.class, String.class, String.class, String.class, Bundle.class)
                .invoke(provider, "com.android.shell", null, NativeBarProtocol.AUTHORITY, NativeBarProtocol.REGISTER, null, request);
        } finally {
            api.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                .invoke(manager, NativeBarProtocol.AUTHORITY, token, user);
        }
    }

    private void connect(Bundle reply) throws RemoteException {
        events = reply.getBinder(NativeBarProtocol.KEY_EVENTS);
        if (events == null) throw new RemoteException("Missing native-bar event endpoint");
        events.linkToDeath(death, 0); linked = true;
        event(NativeBarProtocol.READY, 0, 0);
    }

    private synchronized void await() throws InterruptedException { while (!disconnected) wait(); }

    private synchronized void finish() {
        stopReader();
        if (linked) { events.unlinkToDeath(death, 0); linked = false; }
    }

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000 || args.length != 4) throw new SecurityException("Run activation with ADB shell");
        int appUid = Integer.parseInt(args[0]), systemUiUid = Integer.parseInt(args[2]), user = Integer.parseInt(args[3]);
        long version = Long.parseLong(args[1]);
        if (appUid < 10000 || systemUiUid <= 1000 || user < 0 || version <= 0
            || appUid / 100000 != user || systemUiUid / 100000 != user) throw new IllegalArgumentException("Invalid native-bar identity");
        String apk = System.getenv("CLASSPATH");
        long retryUntil = SystemClock.elapsedRealtime() + RECOVERY_MILLIS;
        String lastFailure = null;
        Log.i(TAG, "Binder agent started");
        while (apk != null && new File(apk).isFile()) {
            NativeBarAgent agent = new NativeBarAgent(appUid, systemUiUid, user);
            boolean connected = false;
            long connectedAt = 0;
            try {
                Bundle reply = agent.register(version);
                int result = reply == null ? NativeBarProtocol.UNAVAILABLE : reply.getInt(NativeBarProtocol.KEY_RESULT, NativeBarProtocol.UNAVAILABLE);
                if (result == NativeBarProtocol.REJECT) { Log.w(TAG, "Agent version rejected; reactivate"); return; }
                if (result == NativeBarProtocol.OK) {
                    agent.connect(reply); connected = true; lastFailure = null;
                    connectedAt = SystemClock.elapsedRealtime();
                    Log.i(TAG, "Binder connected");
                    agent.await();
                    if (agent.quitting) { Log.i(TAG, "Stopped by app"); return; }
                }
            } catch (Exception unavailable) {
                String reason = NativeBarBridge.reason(unavailable);
                if (!reason.equals(lastFailure)) Log.w(TAG, "Registration unavailable: " + reason);
                lastFailure = reason;
            } finally { agent.finish(); }
            long now = SystemClock.elapsedRealtime();
            if (connected && (now - connectedAt >= RECOVERY_MILLIS || agent.lastEventAt >= connectedAt))
                retryUntil = now + RECOVERY_MILLIS;
            if (now >= retryUntil) { Log.w(TAG, "Recovery expired; reactivate"); return; }
            Thread.sleep(500);
        }
    }
}
