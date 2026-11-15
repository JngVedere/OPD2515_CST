package dev.padhelper.fastcts;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ComponentName;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

/** Warms Google's exported handoff activity, then opens the unchanged MiCTS app. */
public final class MainActivity extends Activity {
    static final String TARGET_COMPONENT = "target_component";
    static final String TARGET_LABEL = "target_label";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable trigger = this::openMiCTS;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // This trampoline must not recolor or cover the screen that Google captures.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarContrastEnforced(false);
        getWindow().setNavigationBarContrastEnforced(false);
        String selected = getSharedPreferences("bar", MODE_PRIVATE).getString(TARGET_COMPONENT, "");
        if (!selected.isEmpty()) {
            ComponentName component = ComponentName.unflattenFromString(selected);
            if (component == null || getPackageName().equals(component.getPackageName())) {
                Toast.makeText(this, "설정에서 연결할 앱을 다시 선택해주세요.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            // MiCTS keeps its existing warmup path even when selected from the app list.
            if (!component.equals(new ComponentName("com.parallelc.micts", "com.parallelc.micts.ui.activity.MainActivity"))) {
                try {
                    startActivity(Intent.makeMainActivity(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                } catch (ActivityNotFoundException | SecurityException error) {
                    Log.w("FastMiCTS", "Selected app unavailable", error);
                    Toast.makeText(this, "선택한 앱을 열 수 없습니다. 설정에서 다시 선택해주세요.", Toast.LENGTH_LONG).show();
                } finally { finish(); }
                return;
            }
        }
        try {
            startActivity(new Intent().setClassName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.gsa.assistant.handoff.AssistantHandoffActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            // Allow Google's activity to resume before MiCTS requests its voice session.
            handler.postDelayed(trigger, 150);
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w("FastMiCTS", "Google warmup unavailable; using MiCTS directly", error);
            openMiCTS();
        }
    }

    private void openMiCTS() {
        try {
            startActivity(new Intent().setClassName(
                "com.parallelc.micts", "com.parallelc.micts.ui.activity.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e("FastMiCTS", "MiCTS could not be opened", error);
            Toast.makeText(this, "MiCTS 앱을 설치하고 활성화해주세요.", Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(trigger);
        super.onDestroy();
    }
}
