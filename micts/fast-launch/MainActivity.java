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

/**
 * Opens the linked target. Circle to Search: the direct system call (CtsSession); only if that is refused and MiCTS is
 * installed, the old path (Google's exported handoff activity, then the unchanged MiCTS app).
 */
public final class MainActivity extends Activity {
    static final String TARGET_COMPONENT = "target_component";
    static final String TARGET_LABEL = "target_label";
    /** Internal extra from BarService: the bottom-trigger attempt this launch reports to (0 = none). */
    static final String ATTEMPT = "attempt";
    /** Internal extra from BarService: power double press, so its own target and record are used. */
    static final String DOUBLE = "double";
    /** Internal extra from BarService: the direct call was already refused, go straight to the fallback. */
    static final String DIRECT_TRIED = "direct_tried";
    static final String CTS_ACCEPTED = "서클 투 서치 요청 전달됨 · 화면 표시 미검증";
    /** Internal extra from this app's quick settings tiles (PendingIntent): which tile was tapped. */
    static final String SOURCE = "source";
    static final int TILE_SEARCH = 1, TILE_ASSISTANT = 2;
    /**
     * Exported launcher alias (app drawer icon), now targeting the windowless DrawerActivity. Kept here for the settings
     * switch that shows or hides it.
     */
    static final String DRAWER = "dev.padhelper.fastcts.DrawerLaunch";
    /** Internal extra from DrawerActivity: its direct call was refused; Circle to Search fallback only. */
    static final String DRAWER_FALLBACK = "drawer_fallback";
    private static final ComponentName MICTS = new ComponentName("com.parallelc.micts", "com.parallelc.micts.ui.activity.MainActivity");

    /** The default target and an explicitly picked MiCTS both take the CTS path (Google warmup, then MiCTS). */
    static boolean cts(String selected) {
        return selected == null || selected.isEmpty() || MICTS.equals(ComponentName.unflattenFromString(selected));
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable trigger = this::openMiCTS;
    private long attempt;
    private String record = BarService.LAST_BAR;
    private boolean micTsPending;
    private String warmup = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        boolean drawer = intent.getBooleanExtra(DRAWER_FALLBACK, false); // MainActivity is not exported: internal only
        int source = drawer ? 0 : intent.getIntExtra(SOURCE, 0);
        // The drawer icon and the Search tile always mean Circle to Search, whatever the linked-app setting is (the
        // Assistant tile never reads it either).
        boolean forceCts = drawer || source == TILE_SEARCH || source == TILE_ASSISTANT;
        attempt = drawer ? 0 : intent.getLongExtra(ATTEMPT, 0);
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
        boolean twice = !drawer && intent.getBooleanExtra(DOUBLE, false);
        if (twice) record = BarService.LAST_DOUBLE;
        String selected = forceCts ? "" : getSharedPreferences("bar", MODE_PRIVATE).getString(twice ? BarService.POWER_DOUBLE_TARGET : TARGET_COMPONENT, "");
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
                    record(StatusPolicy.HANDED_OFF, "앱 실행 요청 전달됨 · 화면 표시 미검증");
                } catch (ActivityNotFoundException | SecurityException error) {
                    Log.w("FastMiCTS", "Selected app unavailable", error);
                    record(StatusPolicy.FAILED, "선택한 앱을 열 수 없음 (" + error.getClass().getSimpleName() + ") · 설정에서 다시 선택 필요");
                    Toast.makeText(this, "선택한 앱을 열 수 없습니다. 설정에서 다시 선택해주세요.", Toast.LENGTH_LONG).show();
                } finally { finish(); }
                return;
            }
        }
        boolean direct = !drawer && !intent.getBooleanExtra(DIRECT_TRIED, false);
        if (source != TILE_SEARCH && source != TILE_ASSISTANT) { openCts(direct); return; }
        // Tiles: wait until the collapsing shade has handed the focus to this (transparent) window. Otherwise Circle to
        // Search captures the shade, and a Gemini window shown during the collapse is dismissed. The timeout is a backstop.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        afterShade = source == TILE_SEARCH ? () -> openCts(true) : () -> { afterShade = null; BarService.tileAssistant(this); finish(); };
        handler.postDelayed(afterShade, SHADE_TIMEOUT);
    }

    /** Backstop for the Search tile when no window focus arrives (ms). */
    private static final long SHADE_TIMEOUT = 1000;
    private Runnable afterShade;

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        Runnable pending = afterShade;
        if (!focused || pending == null) return;
        handler.removeCallbacks(pending);
        pending.run();
    }

    private void openCts(boolean direct) {
        afterShade = null;
        if (direct && CtsSession.open(this)) {
            record(StatusPolicy.ACCEPTED, CTS_ACCEPTED);
            finish();
            return;
        }
        // Fallback: the direct call was refused (Google not selected, screen locked, or the hidden call unavailable).
        if (!micTsInstalled()) {
            record(StatusPolicy.FAILED, "서클 투 서치를 열 수 없음 · Google 앱이 기본 디지털 어시스턴트인지 확인");
            Toast.makeText(this, "서클 투 서치를 열지 못했습니다.\n기본 디지털 어시스턴트가\nGoogle인지 확인해주세요.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            startActivity(new Intent().setClassName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.gsa.assistant.handoff.AssistantHandoffActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            // Allow Google's activity to resume before MiCTS requests its voice session.
            warmup = "직접 호출 실패 · Google 준비 요청 후 ";
            micTsPending = true;
            handler.postDelayed(trigger, 0);
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w("FastMiCTS", "Google warmup unavailable; using MiCTS directly", error);
            warmup = "직접 호출 실패 · Google 준비 실패 (" + error.getClass().getSimpleName() + ") 후 ";
            openMiCTS();
        }
    }

    private boolean micTsInstalled() {
        try { getPackageManager().getActivityInfo(MICTS, 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException missing) { return false; }
    }

    private void record(int outcome, String detail) { BarService.attemptUpdate(this, record, attempt, outcome, detail); }

    private void openMiCTS() {
        micTsPending = false;
        try {
            startActivity(new Intent().setClassName(
                "com.parallelc.micts", "com.parallelc.micts.ui.activity.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            record(StatusPolicy.HANDED_OFF, warmup + "MiCTS 요청 전달됨 · 화면 표시 미검증");
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
        if (afterShade != null) handler.removeCallbacks(afterShade);
        if (micTsPending) record(StatusPolicy.CANCELLED, warmup + "MiCTS 요청 전에 실행 화면이 종료됨");
        super.onDestroy();
    }
}
