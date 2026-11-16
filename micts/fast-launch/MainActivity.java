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
    /** Internal extra from BarService: the bottom-trigger attempt this launch reports to (0 = none). */
    static final String ATTEMPT = "attempt";
    private static final ComponentName MICTS = new ComponentName("com.parallelc.micts", "com.parallelc.micts.ui.activity.MainActivity");

    /** The default target and an explicitly picked MiCTS both take the CTS path (Google warmup, then MiCTS). */
    static boolean cts(String selected) {
        return selected == null || selected.isEmpty() || MICTS.equals(ComponentName.unflattenFromString(selected));
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable trigger = this::openMiCTS;
    private long attempt;
    private boolean micTsPending;
    private String warmup = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        attempt = getIntent().getLongExtra(ATTEMPT, 0);
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
                record(StatusPolicy.FAILED, "연결 앱 설정이 올바르지 않음 · 설정에서 다시 선택 필요");
                Toast.makeText(this, "설정에서 연결할 앱을 다시 선택해주세요.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            // MiCTS keeps its existing warmup path even when selected from the app list.
            if (!cts(selected)) {
                try {
                    startActivity(Intent.makeMainActivity(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                    record(StatusPolicy.HANDED_OFF, "선택한 앱 실행 요청 전달됨 · 앱 화면 표시는 확인하지 않음");
                } catch (ActivityNotFoundException | SecurityException error) {
                    Log.w("FastMiCTS", "Selected app unavailable", error);
                    record(StatusPolicy.FAILED, "선택한 앱을 열 수 없음 (" + error.getClass().getSimpleName() + ") · 설정에서 다시 선택 필요");
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
            warmup = "Google 준비 요청 후 ";
            micTsPending = true;
            handler.postDelayed(trigger, 0);
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w("FastMiCTS", "Google warmup unavailable; using MiCTS directly", error);
            warmup = "Google 준비 실패 (" + error.getClass().getSimpleName() + ") 후 ";
            openMiCTS();
        }
    }

    private void record(int outcome, String detail) { BarService.attemptUpdate(this, BarService.LAST_BAR, attempt, outcome, detail); }

    private void openMiCTS() {
        micTsPending = false;
        try {
            startActivity(new Intent().setClassName(
                "com.parallelc.micts", "com.parallelc.micts.ui.activity.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            record(StatusPolicy.HANDED_OFF, warmup + "MiCTS 실행 요청 전달됨 · 서클 투 서치 화면 표시는 확인하지 않음");
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e("FastMiCTS", "MiCTS could not be opened", error);
            record(StatusPolicy.FAILED, warmup + "MiCTS를 열 수 없음 (" + error.getClass().getSimpleName() + ") · MiCTS 설치와 활성화 확인");
            Toast.makeText(this, "MiCTS 앱을 설치하고 활성화해주세요.", Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(trigger);
        if (micTsPending) record(StatusPolicy.CANCELLED, warmup + "MiCTS 요청 전에 실행 화면이 종료됨");
        super.onDestroy();
    }
}
