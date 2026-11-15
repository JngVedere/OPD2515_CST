package dev.padhelper.fastcts;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** Opens the selected Google assistant session after an explicit power gesture. */
final class GeminiSession {
    private static final String TAG = "PadGemini";
    private static final String GOOGLE = "com.google.android.googlequicksearchbox";
    private static final String GOOGLE_PROVIDER = GOOGLE + ".GsaPublicContentProvider";
    // VoiceInteractionSession: SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT | SHOW_SOURCE_ASSIST_GESTURE.
    private static final int FLAGS = 7;

    private GeminiSession() {}

    static boolean launch(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()) {
            return false;
        }
        String selected = Settings.Secure.getString(context.getContentResolver(), "voice_interaction_service");
        ComponentName component = selected == null ? null : ComponentName.unflattenFromString(selected);
        if (component == null || !GOOGLE.equals(component.getPackageName())) {
            Log.w(TAG, "Google is not the active voice interaction service");
            return false;
        }
        // Only an exported, permission-checked Google provider is eligible for this short warmup.
        try (ContentProviderClient ignored = warmGoogle(context)) {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = HiddenApiBypass.invoke(serviceManager, null, "getService", "voiceinteraction");
            if (!(binder instanceof IBinder) || !((IBinder) binder).isBinderAlive()) return false;
            Class<?> stub = Class.forName("com.android.internal.app.IVoiceInteractionManagerService$Stub");
            Object manager = HiddenApiBypass.invoke(stub, null, "asInterface", binder);
            if (manager == null) return false;
            Class<?> managerType = Class.forName("com.android.internal.app.IVoiceInteractionManagerService");
            Bundle args = new Bundle();
            args.putLong("invocation_time_ms", SystemClock.elapsedRealtime());
            // No CTS-specific arguments: Google handles the ordinary selected assistant session.
            Object result = Build.VERSION.SDK_INT >= 34
                ? HiddenApiBypass.invoke(managerType, manager, "showSessionFromSession", null, args, FLAGS, null)
                : HiddenApiBypass.invoke(managerType, manager, "showSessionFromSession", null, args, FLAGS);
            boolean accepted = Boolean.TRUE.equals(result);
            Log.i(TAG, "Assistant request accepted=" + accepted);
            return accepted;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            // Do not log bundles, provider data, screenshots or text from the current app.
            Log.w(TAG, "Assistant request unavailable: " + error.getClass().getSimpleName());
            return false;
        }
    }

    private static ContentProviderClient warmGoogle(Context context) {
        try {
            ProviderInfo info = context.getPackageManager().resolveContentProvider(GOOGLE_PROVIDER, 0);
            if (info == null || !info.exported || !info.enabled || !GOOGLE.equals(info.packageName)) return null;
            if (info.readPermission != null
                    && context.checkSelfPermission(info.readPermission) != PackageManager.PERMISSION_GRANTED) return null;
            return context.getContentResolver().acquireUnstableContentProviderClient(GOOGLE_PROVIDER);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
