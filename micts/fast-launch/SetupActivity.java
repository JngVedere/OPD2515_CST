package dev.padhelper.fastcts;

import android.app.*;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.ViewGroup;
import android.widget.*;
import java.text.Collator;
import java.util.*;

public final class SetupActivity extends Activity {
    /** ColorOS "제스처 안내 막대 숨기기" (1 hidden, 0 shown); read only, never written. */
    private static final String GESTURE_BAR = "navbar_gesture_visibility";
    /** ColorOS taskbar switch (system table, 1 on); read only, never written. */
    private static final String TASKBAR = "enable_launcher_taskbar";
    private static final int ERROR_COLOR = 0xFFD93025;
    private TextView status, logStatus, powerStatus, doubleStatus, recentStatus, targetLabel, doubleLabel;
    private int secondary;
    private LinearLayout body, warnings, updateBanner, statusCard;
    private Switch autoUpdateSwitch;
    /** Where text/toggle/button add views: body, or the current settings card opened by section(). */
    private LinearLayout section;
    private SharedPreferences preferences;
    private Switch areaSwitch, recentSwitch, bootSwitch, powerSwitch, lockedSwitch, doubleSwitch;
    private RadioGroup modeGroup;
    private int touchId, colorId;
    private boolean waitingAccessibility;
    /** True while controls are synced from stored state; listeners then must not act as if the user changed them. */
    private boolean syncing;
    private final BroadcastReceiver serviceState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };
    /**
     * Foreground-only (onStart..onStop): picks up the ColorOS gesture-bar or taskbar switch, or an ADB change, while this
     * screen is visible. One observer for both URIs, so the single unregister in onStop removes both.
     */
    private final ContentObserver gestureBar = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("bar", MODE_PRIVATE);
        setTitle("Circle-to-Search Trigger");
        if (getActionBar() != null) getActionBar().hide();
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        section = body;
        int pad = dp(24);
        body.setPadding(pad, pad, pad, dp(64));
        ScrollView scroll = new ScrollView(this);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scroll.addView(body);
        setContentView(scroll);
        text("Circle-to-Search Trigger", 24).setTypeface(null, android.graphics.Typeface.BOLD);
        text("하단 중앙을 길게 눌러 선택한 앱을 엽니다.", 18);
        android.content.res.TypedArray colors = obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
        secondary = colors.getColor(0, 0xFF808080);
        colors.recycle();
        // Update banner: filled only when the automatic check on open finds a newer version.
        updateBanner = new LinearLayout(this);
        updateBanner.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable green = new android.graphics.drawable.GradientDrawable();
        green.setColor(0x2622C55E); // Translucent green reads on both light and dark backgrounds.
        green.setStroke(dp(1), UPDATE_COLOR);
        green.setCornerRadius(dp(12));
        updateBanner.setBackground(green);
        updateBanner.setPadding(dp(16), dp(10), dp(8), dp(4));
        updateBanner.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerParams.topMargin = dp(8);
        body.addView(updateBanner, bannerParams);
        // Warning card: only the missing prerequisites, each row opens where it is fixed. Hidden when nothing is missing.
        warnings = new LinearLayout(this);
        warnings.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable amber = new android.graphics.drawable.GradientDrawable();
        amber.setColor(0x26F59E0B); // Translucent amber reads on both light and dark backgrounds.
        amber.setCornerRadius(dp(12));
        warnings.setBackground(amber);
        warnings.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        warningParams.topMargin = dp(8);
        body.addView(warnings, warningParams);
        // Status card: one row per pathway plus the recent records; tapping anywhere opens the last-attempt details.
        LinearLayout card = card();
        card.setPadding(dp(16), dp(2), dp(16), dp(2));
        card.setOnClickListener(v -> showAttempts());
        // Display order; refresh() hides the rows of features that are off (and their dividers).
        statusCard = card;
        logStatus = cardRow(card, false);
        status = cardRow(card, true);
        powerStatus = cardRow(card, true);
        doubleStatus = cardRow(card, true);
        recentStatus = cardRow(card, true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(8); cardParams.bottomMargin = dp(12);
        body.addView(card, cardParams);
        button("사용 시작", this::begin);
        button("끄기", () -> { BarService.stop(this); refresh(); });

        section("실행 방식");
        modeGroup = new RadioGroup(this);
        RadioButton touch = phrase(new RadioButton(this)), color = phrase(new RadioButton(this));
        touchId = android.view.View.generateViewId(); colorId = android.view.View.generateViewId();
        touch.setId(touchId); touch.setText("하단 영역 인식"); touch.setTextSize(18); touch.setMinHeight(dp(52));
        color.setId(colorId); color.setText("ColorOS 기본 하단 바 · 로그 감지"); color.setTextSize(18); color.setMinHeight(dp(52));
        modeGroup.addView(touch); modeGroup.addView(color); section.addView(modeGroup);
        modeGroup.check(BarService.nativeMode(this) ? colorId : touchId);
        modeGroup.setOnCheckedChangeListener((group, selected) -> {
            if (syncing) return;
            BarService.setMode(this, selected == colorId ? BarService.NATIVE : BarService.TOUCH);
            if (selected == colorId && BarService.enabled(this)) approveIfNeeded();
            refresh();
        });
        text("하단 영역 인식은 투명한 터치 영역을 씁니다. ColorOS 기본 하단 바는 시스템 애니메이션을 그대로 쓰고, 기기 로그로 길게 누르기를 감지합니다. 3버튼 탐색에서는 홈 버튼을 길게 누르면 됩니다.\n"
            + "3버튼 탐색에서 하단 영역 인식을 쓰면 홈 화면의 최근 앱 버튼을 영역이 가릴 수 있습니다.\n앱을 삭제하기 전에는 '끄기'를 먼저 눌러 원래 제스처 설정을 복구해주세요.", 14);

        section("전원 버튼");
        powerSwitch = toggle("전원 버튼 길게 눌러 Gemini Assistant 열기", BarService.power(this), (view, checked) -> {
            if (syncing) return;
            BarService.setPower(this, checked);
            if (checked) approveIfNeeded();
            refresh();
        });
        text("Gemini 앱 전체 화면 대신 Gemini Assistant 창을 엽니다. 하단 실행 방식과 따로 켜고 끌 수 있습니다.\n접근성, 기기 로그 권한, ColorOS의 '전원 버튼으로 음성 비서 호출' 설정이 필요합니다.", 14);
        divider(section);
        lockedSwitch = toggle("잠금 화면에서 Gemini Assistant 호출", BarService.lockedAssist(this), (view, checked) -> {
            if (syncing) return;
            BarService.setLockedAssist(this, checked);
            refresh();
        });
        text("화면이 꺼졌거나 잠겨 있어도 전원 버튼으로 Gemini Assistant를 엽니다. 잠금을 풀거나 우회하지 않으며, 잠긴 상태에서는 화면 내용과 스크린샷을 보내지 않습니다.\nGemini의 잠금 화면 설정이 필요할 수 있고, 재부팅 후에는 첫 잠금 해제와 기기 로그 승인 뒤부터 동작합니다.", 14);
        divider(section);
        button("Google 음성 연결 다시 시작", this::repairVoice);
        text("Gemini Assistant가 뜨지 않을 때 눌러주세요. 이미 선택된 Google 음성 서비스를 약 0.35초 해제했다가 같은 값으로 되돌려 다시 연결하며, 다른 어시스턴트로 바꾸지 않습니다.\n보안 설정 쓰기 권한이 있으면 전원 버튼을 누를 때마다 자동으로도 실행됩니다.", 14);

        section("기기 로그");
        button("기기 로그 접근 승인 요청", () -> { BarService.requestApproval(this); status.postDelayed(this::refresh, 500); });
        text("ColorOS 기본 하단 바와 전원 버튼이 함께 쓰는 연결입니다. 재부팅 후 첫 잠금 해제 때 Android 확인 창이 자동으로 뜹니다.\n창이 가려졌거나 거부했다면 이 버튼으로 다시 요청하세요. 앱이 승인을 대신 누르지는 않습니다.", 14);

        section("터치 영역");
        areaSwitch = toggle("인식 영역 표시", preferences.getBoolean("show_area", false), (view, checked) -> {
            preferences.edit().putBoolean("show_area", checked).apply();
            BarService.showPreview(checked);
        });
        text("하단 영역 인식의 실제 범위를 파란색으로 보여줍니다. 설정 화면을 벗어나면 다시 투명해집니다.", 14);

        section("최근 앱");
        recentSwitch = toggle("최근 앱에 표시", preferences.getBoolean("show_recent", false), (view, checked) -> {
            preferences.edit().putBoolean("show_recent", checked).apply();
            applyRecentVisibility();
        });
        text("이 설정 화면을 최근 앱 목록에 표시합니다. 앱 서랍에는 아이콘이 없습니다.\n최근 앱에서 이 앱 카드를 잠가 두면 '모두 닫기' 때 앱이 종료되지 않습니다. 잠근 뒤에는 표시를 꺼도 잠금이 유지됩니다.", 14);

        section("연결 앱");
        targetLabel = text("", 18);
        button("앱 선택", this::chooseApp);
        text("기본값은 서클 투 서치 빠른 실행입니다. 다른 앱을 고르면 그 앱이 바로 열립니다.", 14);
        divider(section);
        text("서클 투 서치를 쓰려면 Google 앱과 MiCTS를 설치하고, 기본 디지털 어시스턴트를 Google로 선택해주세요.", 14);
        button("기본 어시스턴트 설정", () -> {
            try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); }
            catch (ActivityNotFoundException unavailable) {
                Toast.makeText(this, "설정 → 앱 → 기본 앱에서 디지털 어시스턴트를 Google로 선택해주세요.", Toast.LENGTH_LONG).show();
                startActivity(freshSettings(Settings.ACTION_SETTINGS));
            }
        });

        section("추가 설정");
        bootSwitch = toggle("재부팅 후 자동 복구", BootRecovery.wanted(this), (view, checked) -> {
            if (syncing) return;
            if (checked &&!BootRecovery.permitted(this)) {
                ((Switch) view).setChecked(false);
                Toast.makeText(this, "아래 최초 ADB 설정 안내를 확인하고 권한을 먼저 허용해주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            preferences.edit().putBoolean(BootRecovery.OPTION, checked).apply();
        });
        text("직접 켜 두었던 이 앱의 접근성이 재부팅 후 꺼져 있으면 다시 켭니다. 보안 설정 쓰기 권한이 필요합니다.", 14);
        button("최초 ADB 설정 안내", this::adbHelp);
        divider(section);
        button("접근성 설정", this::openAccessibility);
        button("빠른 설정에 버튼 추가", () -> {
            if (Build.VERSION.SDK_INT >= 33) {
                getSystemService(StatusBarManager.class).requestAddTileService(
                    new ComponentName(this, BarTile.class), "Circle-to-Search Trigger",
                    Icon.createWithResource(this, android.R.drawable.ic_menu_search),
                    getMainExecutor(), result -> { });
            } else Toast.makeText(this, "빠른 설정의 편집 화면에서 Circle-to-Search Trigger 타일을 직접 추가해주세요.", Toast.LENGTH_LONG).show();
        });
        button("자동 실행 설정", this::openAutoStart);
        button("앱 정보와 절전 설정", this::openAppInfo);
        button("최근 앱 정리 제외 설정", this::recentLockPrompt);
        button("설정 닫기", () -> { applyRecentVisibility(); moveTaskToBack(true); });

        section("업데이트");
        autoUpdateSwitch = toggle("설정을 열 때 업데이트 자동 확인", preferences.getBoolean(UPDATE_AUTO, true),
            (view, checked) -> preferences.edit().putBoolean(UPDATE_AUTO, checked).apply());
        text("이 설정 화면을 열 때마다 한 번 GitHub에서 새 버전을 확인하고, 있으면 화면 맨 위에 알려줍니다. 새 버전이 없거나 확인에 실패하면 아무것도 표시하지 않습니다.", 14);
        divider(section);
        toggle("베타 버전도 확인", preferences.getBoolean(UPDATE_BETA, false),
            (view, checked) -> preferences.edit().putBoolean(UPDATE_BETA, checked).apply());
        text("켜면 정식 버전보다 먼저 나오는 베타 버전도 업데이트로 알려줍니다. 새 기능을 먼저 써볼 수 있지만, 아직 검증 중이라 오류가 있을 수 있습니다.\n끄면 정식 버전만 확인합니다. 베타를 설치한 뒤 끄면, 더 높은 정식 버전이 나올 때 그 버전으로 업데이트됩니다.", 14);
        divider(section);
        button("업데이트 확인", this::checkUpdate);
        text("GitHub에서 새 버전 정보만 받아오며, 기기 정보는 보내지 않습니다. 새 버전은 파일 검증 후 Android 설치 화면으로 설치합니다.\n처음 한 번은 '출처를 알 수 없는 앱 설치' 허용이 필요하고, 업데이트 후에는 기기 로그 승인을 다시 해야 합니다.", 14);
        section("고급");
        // Experimental: changes system power-key settings. Red outline so the risk is visible before reading anything.
        LinearLayout experimental = new LinearLayout(this);
        experimental.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable danger = new android.graphics.drawable.GradientDrawable();
        danger.setColor(0x14D93025); // Faint red fill reads on both light and dark backgrounds.
        danger.setStroke(dp(2), ERROR_COLOR);
        danger.setCornerRadius(dp(10));
        experimental.setBackground(danger);
        experimental.setPadding(dp(14), 0, dp(14), dp(8));
        LinearLayout.LayoutParams experimentalParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        experimentalParams.topMargin = dp(4); experimentalParams.bottomMargin = dp(8);
        section.addView(experimental, experimentalParams);
        // Collapsed on every open; the status card still shows whether the double press is on.
        // Title row: section title as is on the left, the expand/collapse label at the right end; the whole row toggles.
        TextView advancedTitle = (TextView) section.getChildAt(0);
        section.removeView(advancedTitle);
        LinearLayout advancedRow = new LinearLayout(this);
        advancedRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        advancedRow.setMinimumHeight(dp(48));
        advancedRow.addView(advancedTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView advancedToggle = phrase(new TextView(this));
        advancedToggle.setTextSize(14); advancedToggle.setTextColor(secondary);
        advancedToggle.setPadding(dp(12), dp(14), 0, dp(4)); // Same vertical padding as the title, so both share a baseline.
        advancedRow.addView(advancedToggle);
        section.addView(advancedRow, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable showAdvanced = () -> advancedToggle.setText(
            experimental.getVisibility() == android.view.View.VISIBLE ? "접기 ▴" : "펼치기 ▾");
        experimental.setVisibility(android.view.View.GONE);
        showAdvanced.run();
        advancedRow.setOnClickListener(v -> {
            experimental.setVisibility(experimental.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE);
            showAdvanced.run();
        });
        section = experimental;
        TextView experimentalTitle = text("⚠ 실험적 기능", 18);
        experimentalTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        experimentalTitle.setTextColor(ERROR_COLOR);
        experimentalTitle.setPadding(0, dp(12), 0, dp(2));
        text("시스템 설정을 바꾸는 위험할 수 있는 기능입니다. 주의사항을 끝까지 읽은 뒤에만 켜주세요.", 14);
        divider(section);
        doubleSwitch = toggle("전원 버튼 두 번 눌러 앱 실행", BarService.doublePress(this), (view, checked) -> {
            if (syncing) return;
            if (!checked) { BarService.setDoublePress(this, false); refresh(); return; }
            setSynced(doubleSwitch, false); // On only after the risks are accepted below.
            if (!BootRecovery.permitted(this) || !BarService.logsPermitted(this)) {
                Toast.makeText(this, "보안 설정 쓰기와 기기 로그 권한이 필요합니다. 최초 ADB 설정 안내를 확인해주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            doubleRisks(true);
        });
        doubleLabel = text("", 16);
        button("두 번 누르면 열 앱 선택", () -> chooseApp(true));
        text("켜면 전원 버튼을 한 번 눌렀을 때 시스템 대신 이 앱이 화면을 끕니다. 그래서 화면이 꺼지기까지 약 0.3초 늦어지고, 앱이 멈추면 잠시 전원 버튼으로 화면이 꺼지지 않을 수 있습니다.\n"
            + "앱을 삭제하기 전에는 반드시 먼저 꺼주세요. 실행할 앱이 '아무것도 하지 않기'이면 전원 버튼은 원래대로 동작합니다.", 14);
        button("두 번 누르기 주의사항과 복구 방법", () -> doubleRisks(false));
        section = body; // Footer sits below the cards.
        text("상태 알림 없이 실행됩니다. 선택한 방식으로 연결 앱을 엽니다. 화면 내용 읽기와 캡처는 사용하지 않습니다.", 14)
            .setPadding(0, dp(16), 0, dp(9));
        try {
            text("버전 " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName, 14);
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            text("버전 정보 없음", 14);
        }
        if (preferences.getBoolean(UPDATE_AUTO, true)) checkUpdate(true);
    }

    /** Main thread only: one check or download at a time. */
    private boolean updating;
    private static final String UPDATE_BETA = "update_beta", UPDATE_AUTO = "update_auto";
    private static final int UPDATE_COLOR = 0xFF16A34A;

    private void checkUpdate() { checkUpdate(false); }

    /** auto: the once-per-open check; silent unless a newer version exists, which shows the top banner instead of a dialog. */
    private void checkUpdate(boolean auto) {
        if (updating) { if (!auto) Toast.makeText(this, "이미 업데이트를 확인하는 중입니다.", Toast.LENGTH_SHORT).show(); return; }
        updating = true;
        if (!auto) Toast.makeText(this, "최신 버전을 확인하는 중입니다.", Toast.LENGTH_SHORT).show();
        Context app = getApplicationContext();
        boolean beta = preferences.getBoolean(UPDATE_BETA, false);
        new Thread(() -> {
            AppUpdate.Release found = null;
            String error = null;
            try { found = AppUpdate.check(app, beta); } catch (Exception failed) { error = failed.getClass().getSimpleName(); }
            AppUpdate.Release release = found;
            String failure = error;
            runOnUiThread(() -> {
                updating = false;
                if (isFinishing() || isDestroyed()) return;
                if (auto) { if (failure == null && release != null) showUpdateBanner(release); return; }
                if (failure != null) Toast.makeText(this, "업데이트 정보를 가져오지 못했습니다 (" + failure + "). 인터넷 연결을 확인해주세요.", Toast.LENGTH_LONG).show();
                else if (release == null) Toast.makeText(this, "최신 버전입니다.", Toast.LENGTH_SHORT).show();
                else showPhrased(new AlertDialog.Builder(this).setTitle((release.beta ? "새 베타 버전 " : "새 버전 ") + release.version)
                    .setMessage((release.beta ? "베타 버전은 오류가 있을 수 있습니다. " : "")
                        + "새 버전을 내려받아 설치할까요? 설치 후 앱이 다시 시작되며 기기 로그 승인을 다시 해야 합니다.")
                    .setPositiveButton("업데이트", (dialog, which) -> installUpdate(release))
                    .setNeutralButton("변경 사항 보기", (dialog, which) -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl()))); }
                        catch (ActivityNotFoundException missing) { Toast.makeText(this, "브라우저를 열 수 없습니다.", Toast.LENGTH_SHORT).show(); }
                    })
                    .setNegativeButton("나중에", null));
            });
        }, "PadUpdateCheck").start();
    }

    private void showUpdateBanner(AppUpdate.Release release) {
        updateBanner.removeAllViews();
        TextView message = phrase(new TextView(this));
        SpannableStringBuilder text = new SpannableStringBuilder();
        span(text, (release.beta ? "새 베타 업데이트가 있습니다 · " : "새로운 업데이트가 있습니다 · ") + release.version,
            new AbsoluteSizeSpan(16, true), new StyleSpan(android.graphics.Typeface.BOLD), new ForegroundColorSpan(UPDATE_COLOR));
        span(text, "\n" + (release.beta ? "베타 버전은 오류가 있을 수 있습니다. " : "") + "설치 후 앱이 다시 시작되며 기기 로그 승인을 다시 해야 합니다.",
            new AbsoluteSizeSpan(14, true), new ForegroundColorSpan(secondary));
        message.setText(text);
        message.setLineSpacing(dp(2), 1f);
        updateBanner.addView(message);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(android.view.Gravity.END);
        actions.addView(bannerAction("다시는 알리지 않기", () -> {
            autoUpdateSwitch.setChecked(false); // Its listener stores the choice; the 업데이트 section can turn it back on.
            updateBanner.setVisibility(android.view.View.GONE);
            Toast.makeText(this, "자동 업데이트 알림을 껐습니다. 업데이트 항목에서 다시 켤 수 있습니다.", Toast.LENGTH_LONG).show();
        }));
        actions.addView(bannerAction("무시", () -> updateBanner.setVisibility(android.view.View.GONE)));
        actions.addView(bannerAction("업데이트", () -> { updateBanner.setVisibility(android.view.View.GONE); installUpdate(release); }));
        updateBanner.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateBanner.setVisibility(android.view.View.VISIBLE);
    }

    private Button bannerAction(String label, Runnable action) {
        Button view = phrase(new Button(this, null, android.R.attr.borderlessButtonStyle));
        view.setText(label); view.setAllCaps(false);
        view.setTextColor(UPDATE_COLOR);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void installUpdate(AppUpdate.Release release) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "이 앱의 설치 허용을 켠 뒤 업데이트 확인을 다시 눌러주세요.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        if (updating) return;
        updating = true;
        Toast.makeText(this, "새 버전을 내려받는 중입니다.", Toast.LENGTH_SHORT).show();
        Context app = getApplicationContext();
        new Thread(() -> {
            String failure = null;
            try { AppUpdate.install(app, release); }
            catch (java.security.GeneralSecurityException mismatch) { failure = "내려받은 파일 검증에 실패했습니다. 다시 시도해주세요."; }
            catch (Exception failed) { failure = "새 버전을 내려받지 못했습니다 (" + failed.getClass().getSimpleName() + ")."; }
            String message = failure;
            runOnUiThread(() -> {
                updating = false;
                if (message != null) Toast.makeText(app, message, Toast.LENGTH_LONG).show();
            });
        }, "PadUpdateInstall").start();
    }

    /** Only after an explicit switch change: one standard confirmation if no session is live. */
    private void approveIfNeeded() {
        if (BarService.granted(this) && BarService.logsPermitted(this) && BarService.serviceRunning()
                && BarService.logsWanted(this) && BarService.logState() == LogSession.OFF) BarService.requestApproval(this);
    }

    private void adbHelp() {
        int user = getApplicationInfo().uid / 100000;
        String grant = "adb shell pm grant --user " + user + " " + getPackageName() + " android.permission.";
        String command = grant + "WRITE_SECURE_SETTINGS\n" + grant + "READ_LOGS";
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("최초 ADB 설정");
        LinearLayout panel = new LinearLayout(builder.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), 0);
        block(panel, "USB 디버깅으로 본인 기기를 연결한 뒤 ADB가 있는 컴퓨터에서 아래 명령을 한 번 실행하세요. 여러 기기가 연결되어 있으면 adb -s로 대상을 지정하세요.", false);
        block(panel, command, true);
        block(panel, "보안 설정 쓰기: 앱이 시스템 설정을 바꿀 수 있는 강한 권한입니다. 재부팅 후 자기 접근성 복구와 하단 바 제스처 전환에 사용하며, ColorOS 기본 바 방식에 필요합니다."
            + "\n\n기기 로그 읽기: ColorOS 기본 바와 전원 버튼 길게 누르기를 감지하는 데 사용합니다. 로그를 저장하거나 외부로 보내지 않습니다. 권한은 재부팅 후에도 유지되지만, 재부팅마다 첫 잠금 해제 뒤 Android의 확인 창에서 직접 허용해야 합니다.", false);
        block(panel, new SpannableStringBuilder()
            .append("Breeno 상태: " + (LogSession.breenoDisabled(this) ? "비활성화됨" : "비활성화 필요"),
                new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append("\n켜져 있으면 기본 바는 동작하지 않고, 전원 버튼에서 Breeno가 함께 열릴 수 있습니다. 원하는 경우에만 직접 실행하세요."), false);
        block(panel, "adb shell pm disable-user --user " + user + " com.heytap.speechassist", true);
        block(panel, "명령을 실행하면 Android가 앱을 한 번 다시 시작합니다. 그 뒤 접근성이 꺼지거나 '작동하지 않음'으로 바뀌면, 접근성 설정에서 이 앱을 껐다가 다시 켜주세요."
            + "\n\n명령이 권한 오류로 실패하면 개발자 옵션의 'Disable system optimization'을 켜고 다시 실행하세요. 이 항목은 시스템 언어가 English일 때만 보일 수 있습니다.", false);
        block(panel, "앱이 스스로 권한을 부여하거나 Breeno를 끄지 않습니다.", false);
        ScrollView scroll = new ScrollView(builder.getContext());
        scroll.addView(panel);
        builder.setView(scroll)
            .setPositiveButton("권한 명령 복사", (dialog, which) -> {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Circle-to-Search Trigger ADB 설정", command));
                Toast.makeText(this, "명령을 복사했습니다.", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("닫기", null).show();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Dialog paragraph; code=true renders a selectable, wrapping monospace box like a markdown code fence. */
    private void block(LinearLayout parent, CharSequence value, boolean code) {
        TextView view = phrase(new TextView(parent.getContext()));
        view.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        view.setText(value);
        if (code) {
            android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
            box.setColor(0x26808080); // Translucent gray reads on both light and dark dialog backgrounds.
            box.setCornerRadius(dp(6));
            view.setBackground(box);
            view.setPadding(dp(12), dp(10), dp(12), dp(10));
            view.setTypeface(android.graphics.Typeface.MONOSPACE);
            view.setTextSize(13);
            view.setTextIsSelectable(true);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        parent.addView(view, params);
    }

    /** Korean wraps per character by default ("기기 로그 / 로"); phrase style keeps words together (Android 13+). */
    private static <T extends TextView> T phrase(T view) {
        if (Build.VERSION.SDK_INT >= 33) view.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        return view;
    }

    /** Dialog bodies get the same word-kept wrapping; the message view exists only once the dialog is shown. */
    private static void showPhrased(AlertDialog.Builder builder) {
        TextView message = builder.show().findViewById(android.R.id.message);
        if (message != null) phrase(message);
    }

    private TextView text(String value, int size) {
        TextView view = phrase(new TextView(this));
        view.setText(value); view.setTextSize(size);
        view.setPadding(0, dp(5), 0, dp(9));
        if (size == 14) { view.setTextColor(secondary); view.setLineSpacing(dp(2), 1f); } // 14sp = description, like the status card detail.
        section.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    /** Faint rounded gray card shared by the status card and the settings sections. */
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable shade = new android.graphics.drawable.GradientDrawable();
        shade.setColor(0x14808080); // Faint gray reads on both light and dark backgrounds.
        shade.setCornerRadius(dp(12));
        card.setBackground(shade);
        return card;
    }

    /** Opens a new settings card with a bold title; following text/toggle/button calls land inside it. */
    private void section(String label) {
        section = card();
        section.setPadding(dp(16), 0, dp(16), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        body.addView(section, params);
        TextView view = text(label, 18);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(14), 0, dp(4));
    }

    private void divider(LinearLayout card) {
        android.view.View line = new android.view.View(this);
        line.setBackgroundColor(0x33808080);
        card.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
    }

    private TextView cardRow(LinearLayout card, boolean divider) {
        if (divider) divider(card);
        TextView view = phrase(new TextView(this));
        view.setTextSize(14); view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(12), 0, dp(12));
        card.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    /** Small label; the state's first part as a bold headline (red for 오류); the rest as smaller detail. */
    private CharSequence row(String label, String state) {
        int split = state.indexOf(" · ");
        String head = split < 0 ? state : state.substring(0, split);
        boolean error = head.equals("오류");
        SpannableStringBuilder text = new SpannableStringBuilder();
        span(text, label + "\n", new AbsoluteSizeSpan(13, true), new ForegroundColorSpan(secondary));
        span(text, head, new AbsoluteSizeSpan(18, true), new StyleSpan(android.graphics.Typeface.BOLD), error ? new ForegroundColorSpan(ERROR_COLOR) : null);
        if (split >= 0) span(text, "\n" + state.substring(split + 3), error ? null : new ForegroundColorSpan(secondary));
        return text;
    }

    private static void span(SpannableStringBuilder text, String value, Object... styles) {
        int start = text.length();
        text.append(value);
        for (Object style : styles) if (style != null) text.setSpan(style, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private Switch toggle(String label, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        Switch view = phrase(new Switch(this));
        view.setText(label); view.setTextSize(18);
        view.setMinHeight(dp(56));
        view.setChecked(checked);
        view.setOnCheckedChangeListener(listener);
        section.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void button(String label, Runnable action) {
        Button view = phrase(new Button(this));
        view.setText(label); view.setAllCaps(false);
        view.setOnClickListener(v -> action.run());
        section.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void applyRecentVisibility() {
        boolean visible = preferences.getBoolean("show_recent", false);
        for (ActivityManager.AppTask task : getSystemService(ActivityManager.class).getAppTasks()) {
            ComponentName base = task.getTaskInfo().baseActivity;
            if (base != null && base.equals(new ComponentName(this, SetupActivity.class)))
                task.setExcludeFromRecents(!visible);
        }
    }

    private static final String NOTHING = "아무것도 하지 않기 (기본)";

    private void chooseApp() { chooseApp(false); }

    /** twice: the power double-press target. Same app list; "nothing" (null component) first, the default. */
    private void chooseApp(boolean twice) {
        Map<String, AppChoice> byPackage = new LinkedHashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : getPackageManager().queryIntentActivities(launcher, 0)) {
            if (!info.activityInfo.exported || getPackageName().equals(info.activityInfo.packageName)) continue;
            String label = info.loadLabel(getPackageManager()).toString();
            String component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString();
            byPackage.putIfAbsent(info.activityInfo.packageName, new AppChoice(label, component));
        }
        ArrayList<AppChoice> choices = new ArrayList<>(byPackage.values());
        Collator order = Collator.getInstance();
        choices.sort((a, b) -> order.compare(a.label, b.label));
        choices.add(0, new AppChoice(twice ? "서클 투 서치 (빠른 실행)" : "서클 투 서치 (기본 · 빠른 실행)", ""));
        if (twice) choices.add(0, new AppChoice(NOTHING, null));
        ArrayAdapter<AppChoice> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, choices);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), 0, dp(16), 0);
        EditText search = new EditText(this);
        search.setHint("앱 이름으로 검색"); search.setSingleLine(true);
        panel.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(twice ? "두 번 누르면 열 앱" : "연결할 앱 선택")
            .setView(panel).setNegativeButton("취소", null).create();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { adapter.getFilter().filter(s); }
            @Override public void afterTextChanged(Editable value) { }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppChoice choice = adapter.getItem(position);
            if (choice == null) return;
            SharedPreferences.Editor edit = preferences.edit();
            if (twice) {
                if (choice.component == null) edit.remove(BarService.POWER_DOUBLE_TARGET).remove(BarService.POWER_DOUBLE_LABEL);
                else edit.putString(BarService.POWER_DOUBLE_TARGET, choice.component).putString(BarService.POWER_DOUBLE_LABEL, choice.label);
            } else if (choice.component.isEmpty()) edit.remove(MainActivity.TARGET_COMPONENT).remove(MainActivity.TARGET_LABEL);
            else edit.putString(MainActivity.TARGET_COMPONENT, choice.component).putString(MainActivity.TARGET_LABEL, choice.label);
            edit.apply();
            if (twice) { BarService.refreshConfiguration(); approveIfNeeded(); }
            refresh();
            dialog.dismiss();
        });
        dialog.show();
    }

    private static final class AppChoice {
        final String label, component;
        AppChoice(String label, String component) { this.label = label; this.component = component; }
        @Override public String toString() { return label; }
    }

    private void setSynced(Switch view, boolean checked) {
        syncing = true;
        try { view.setChecked(checked); } finally { syncing = false; }
    }

    /** consent: the switch asked to turn on; it turns on only through the accept button. */
    private void doubleRisks(boolean consent) {
        String restore = "adb shell settings delete global " + PowerButton.SHORT + "\nadb shell settings delete global " + PowerButton.DOUBLE;
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("전원 버튼 두 번 누르기 주의사항");
        LinearLayout panel = new LinearLayout(builder.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), 0);
        block(panel, "ColorOS에는 전원 버튼 두 번 누르기 기능이 없어, 이 앱이 시스템 전원 버튼 설정 두 개를 바꿔서 만듭니다."
            + "\n\n• 한 번 누르기: 시스템 동작을 '아무것도 안 함'으로 바꾸고, 이 앱이 접근성으로 화면을 대신 끕니다."
            + "\n• 두 번 누르기: 시스템이 두 번째 누름을 기다리게 하고, 기기 로그로 감지해 선택한 앱을 엽니다.", false);
        block(panel, "알아둘 점"
            + "\n\n1. 두 번째 누름을 기다리느라 화면이 꺼지기까지 약 0.3초 늦어집니다."
            + "\n\n2. 기기 로그 연결이 끊기거나 접근성이 꺼지면, 앱이 원래 설정으로 자동 복구합니다."
            + "\n\n3. 앱이 갑자기 종료되면(최근 앱 '모두 닫기', 메모리 정리) 앱이 다시 실행될 때까지 몇 초 동안 전원 버튼으로 화면이 꺼지지 않을 수 있습니다. 최근 앱에서 이 앱 카드를 잠가 두세요."
            + "\n\n4. 특히 '강제 종료'를 하면 접근성이 꺼진 채 앱이 다시 실행되지 않아, 그동안 전원 버튼으로 화면이 꺼지지 않습니다(화면 자동 꺼짐은 동작). 이 앱 설정 화면을 열거나 빠른 설정 타일이 보이도록 빠른 설정을 내리면 바로 원래대로 돌아갑니다."
            + "\n\n5. 앱은 잠금이 풀린 화면에서만 엽니다. 꺼진 화면이나 잠금 화면에서 두 번 누르면 화면만 켜진 채로 있습니다."
            + "\n\n6. 화면 켜기와 길게 눌러 Gemini Assistant 열기는 그대로입니다."
            + "\n\n7. 재부팅하면 첫 잠금 해제 전에 원래 설정으로 돌아가고, 기기 로그를 승인하면 다시 적용됩니다. 자동 실행이 꺼져 있거나 강제 종료 뒤라면 이 복구가 동작하지 않을 수 있습니다."
            + "\n\n8. 실행할 앱이 '아무것도 하지 않기'이면 설정을 바꾸지 않아 전원 버튼은 원래대로 동작합니다.", false);
        block(panel, new SpannableStringBuilder()
            .append("앱을 삭제하기 전에는 반드시 이 기능을 먼저 끄세요.", new StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append("\n켠 채로 삭제하거나 앱이 계속 실행되지 못하면 전원 버튼으로 화면이 꺼지지 않는 상태가 남을 수 있습니다. 그때는 컴퓨터에서 아래 명령으로 원래대로 되돌리세요."), false);
        block(panel, restore, true);
        ScrollView scroll = new ScrollView(builder.getContext());
        scroll.addView(panel);
        builder.setView(scroll).setNeutralButton("복구 명령 복사", (dialog, which) -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("전원 버튼 설정 복구", restore));
            Toast.makeText(this, "명령을 복사했습니다.", Toast.LENGTH_SHORT).show();
        });
        if (consent) builder.setPositiveButton("이해했고 켜기", (dialog, which) -> {
            BarService.setDoublePress(this, true);
            setSynced(doubleSwitch, true);
            approveIfNeeded();
            if (!BarService.doubleTarget(this)) Toast.makeText(this, "두 번 누르면 열 앱을 선택해야 적용됩니다.", Toast.LENGTH_LONG).show();
            refresh();
        }).setNegativeButton("취소", null);
        else builder.setNegativeButton("닫기", null);
        builder.show();
    }

    private String doubleState() {
        if (!BarService.doublePress(this)) return "꺼짐 · 전원 버튼은 원래대로 동작";
        if (!BarService.doubleTarget(this)) return "대기 · 열 앱이 '아무것도 하지 않기'라 전원 버튼은 원래대로 동작";
        if (PowerButton.applied(this)) return "적용됨 · 한 번 누르면 이 앱이 화면을 끄고, 두 번 누르면 선택한 앱을 엽니다";
        if (!BootRecovery.permitted(this) || !BarService.logsPermitted(this)) return "권한 필요 · 최초 ADB 설정 안내 참고";
        return "대기 · 기기 로그가 연결되면 적용 · 그 전까지 전원 버튼은 원래대로 동작";
    }

    private void begin() {
        BarService.start(this);
        if (!BarService.granted(this)) { waitingAccessibility = true; openAccessibility(); }
        else { approveIfNeeded(); status.postDelayed(this::refresh, 250); }
    }

    /**
     * Explicit repair button only, with visible feedback: rebinds the already selected Google voice service
     * once, never another assistant. Start and the power switch stay silent; power presses rebind in BarService.
     */
    private void repairVoice() {
        if (!BootRecovery.permitted(this)) {
            Toast.makeText(this, "보안 설정 쓰기 권한이 필요합니다. 최초 ADB 설정 안내를 확인해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!VoiceRepair.activationWanted(this, true)) {
            Toast.makeText(this, "Google이 음성 서비스로 선택되어 있지 않아 변경하지 않았습니다. 기본 어시스턴트 설정을 확인해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (VoiceRepair.busy()) {
            Toast.makeText(this, "이미 Google 음성 연결을 다시 시작하는 중입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Context app = getApplicationContext();
        new Thread(() -> {
            boolean restored;
            try { restored = VoiceRepair.run(app); }
            catch (RuntimeException unexpected) { restored = false; }
            if (!restored && VoiceRepair.busy()) return; // A concurrent request owns this repair and reports it.
            boolean result = restored;
            runOnUiThread(() -> Toast.makeText(app, result
                ? "Google 음성 서비스 연결을 다시 시작했습니다. 잠시 후 전원 버튼을 길게 눌러 확인해주세요."
                : "Google 음성 연결을 다시 시작하지 못했습니다. 기본 어시스턴트 설정에서 Google 선택을 확인해주세요.", Toast.LENGTH_LONG).show());
        }, "PadVoiceRepair").start();
    }

    private void openAccessibility() {
        // The per-service details page needs a system permission (device: SecurityException), so the list with a hint.
        Toast.makeText(this, "다운로드한 앱 → Circle-to-Search Trigger를 켜주세요.", Toast.LENGTH_LONG).show();
        startActivity(freshSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    /** ColorOS keeps "자동 실행" behind a system-only permission; open Settings with the exact path instead. */
    private void openAutoStart() {
        Toast.makeText(this, "설정 → 앱 → 자동 실행에서 Circle-to-Search Trigger를 켜주세요.", Toast.LENGTH_LONG).show();
        startActivity(freshSettings(Settings.ACTION_SETTINGS));
    }

    private void openAppInfo() {
        Toast.makeText(this, "배터리 사용량 → 백그라운드 활동 허용을 켜주세요.", Toast.LENGTH_LONG).show();
        startActivity(freshSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName())));
    }

    /**
     * A fresh Settings task, so the page named in the hint opens instead of whatever sub-page Settings was left on
     * (device: the accessibility list reopened on "접근성 메뉴", the home page on an old Wi-Fi page).
     */
    private static Intent freshSettings(String action) {
        return new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    /** ColorOS "백그라운드 활동 허용" puts the app on the battery-optimization allowlist; restriction is the opposite. */
    private boolean backgroundLimited() {
        return !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())
            || getSystemService(ActivityManager.class).isBackgroundRestricted();
    }

    private boolean autoStartMissed() {
        long bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        try {
            return BootRecovery.autoStartMissed(Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1),
                BootRecovery.capturedBoot(this), getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime, bootedAt);
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return false; }
    }

    private static final String AUTOSTART_CONFIRMED = "autostart_confirmed";

    /** The user's own confirmation is the only "on" signal available; a later blocked boot clears it again. */
    private void autoStartPrompt() {
        showPhrased(new AlertDialog.Builder(this).setTitle("자동 실행")
            .setMessage("설정 → 앱 → 자동 실행에서 Circle-to-Search Trigger가 켜져 있는지 확인해주세요.\n\n켜져 있다면 '켜져 있음'을 눌러 이 안내를 닫을 수 있습니다. 재부팅 때 자동 실행이 막힌 것이 확인되면 다시 알려드립니다.")
            .setPositiveButton("설정 열기", (dialog, which) -> openAutoStart())
            .setNeutralButton("켜져 있음", (dialog, which) -> { preferences.edit().putBoolean(AUTOSTART_CONFIRMED, true).apply(); refresh(); })
            .setNegativeButton("닫기", null));
    }

    private static final String RECENT_LOCKED_AT = "recent_locked_at";

    /**
     * The card lock is unreadable too, but its absence is not: an unlocked app is killed by recents "모두 닫기" (even with
     * the card hidden), which its own exit history records. Only the newest exit counts.
     */
    private boolean cleanupKilled() {
        try {
            for (android.app.ApplicationExitInfo exit : getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(null, 0, 1))
                return StatusPolicy.cleanupKill(exit.getReason(), exit.getDescription(), exit.getTimestamp(), preferences.getLong(RECENT_LOCKED_AT, 0));
        } catch (RuntimeException unavailable) { }
        return false;
    }

    private void recentLockPrompt() {
        showPhrased(new AlertDialog.Builder(this).setTitle("최근 앱 정리 제외")
            .setMessage("1. '지금 설정'을 누르면 이 화면이 최근 앱 목록에 표시됩니다.\n2. 최근 앱 화면에서 Circle-to-Search Trigger 이름을 눌러 '잠금'을 선택합니다.\n3. 필요하면 '최근 앱에 표시'를 다시 끕니다. 잠금은 유지됩니다.\n\n잠그지 않으면 '모두 닫기' 때 앱이 종료되어 기기 로그를 다시 승인해야 합니다.")
            .setPositiveButton("지금 설정", (dialog, which) -> {
                recentSwitch.setChecked(true);
                Toast.makeText(this, "최근 앱 화면에서 이 앱 이름을 눌러 '잠금'을 선택해주세요.", Toast.LENGTH_LONG).show();
            })
            .setNeutralButton("잠갔음", (dialog, which) -> {
                preferences.edit().putLong(RECENT_LOCKED_AT, System.currentTimeMillis()).apply(); refresh(); })
            .setNegativeButton("닫기", null));
    }

    /** Lists missing prerequisites; each row opens where it is fixed. Changes no system setting. */
    private void refreshWarnings() {
        warnings.removeAllViews();
        if (!BarService.granted(this))
            warn("접근성이 꺼져 있습니다", "눌러서 Circle-to-Search Trigger를 켜주세요.", this::openAccessibility);
        else if (!BarService.serviceRunning())
            // Device: granting READ_LOGS over ADB kills the process ("changed gids"); Android then marks the service
            // crashed and never rebinds it until the user toggles it.
            warn("접근성이 작동하지 않습니다", "권한을 새로 받은 뒤 멈출 수 있습니다. 눌러서 Circle-to-Search Trigger를 껐다가 다시 켜주세요.", this::openAccessibility);
        if (!BootRecovery.permitted(this))
            warn("보안 설정 쓰기 권한이 없습니다", "재부팅 후 자동 복구와 ColorOS 기본 하단 바에 필요합니다. 눌러서 ADB 설정 안내 보기", this::adbHelp);
        if (BarService.logsWanted(this) && !BarService.logsPermitted(this))
            warn("기기 로그 권한이 없습니다", "ColorOS 기본 하단 바와 전원 버튼에 필요합니다. 눌러서 ADB 설정 안내 보기", this::adbHelp);
        else if (BarService.logsWanted(this) && BarService.serviceRunning() && BarService.logState() == LogSession.OFF)
            warn("기기 로그가 연결되지 않았습니다", "눌러서 접근 승인을 요청해주세요.", () -> {
                BarService.requestApproval(this); status.postDelayed(this::refresh, 500); });
        if (backgroundLimited())
            warn("백그라운드 활동이 제한되어 있습니다", "앱이 종료될 수 있습니다. 눌러서 배터리 사용량 → 백그라운드 활동 허용", this::openAppInfo);
        // ColorOS keeps "자동 실행" unreadable to apps (no app op or setting changes; provider and page need a system-only
        // permission). So: a reminder until the user confirms it, and a hard warning whenever a boot proves it blocked.
        if (autoStartMissed()) {
            preferences.edit().remove(AUTOSTART_CONFIRMED).apply();
            warn("이번 부팅에서 자동 실행이 차단되었습니다", "재부팅 후 접근성이 꺼질 수 있습니다. 설정 → 앱 → 자동 실행에서 이 앱을 켜주세요.", this::autoStartPrompt);
        } else if (!preferences.getBoolean(AUTOSTART_CONFIRMED, false))
            warn("자동 실행이 켜져 있는지 확인해주세요", "꺼져 있으면 재부팅 후 접근성이 꺼질 수 있습니다. 앱이 이 설정을 직접 읽을 수 없어 한 번 확인이 필요합니다.", this::autoStartPrompt);
        if (cleanupKilled())
            warn("최근 앱 정리로 앱이 종료되었습니다", "기기 로그를 다시 승인해야 합니다. 최근 앱에서 이 앱 카드를 잠가 두면 막을 수 있습니다. 눌러서 방법 보기", this::recentLockPrompt);
        warnings.setVisibility(warnings.getChildCount() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void warn(String head, String detail, Runnable fix) {
        TextView view = phrase(new TextView(this));
        SpannableStringBuilder text = new SpannableStringBuilder();
        span(text, "⚠ " + head, new AbsoluteSizeSpan(16, true), new StyleSpan(android.graphics.Typeface.BOLD));
        span(text, "\n" + detail, new AbsoluteSizeSpan(14, true), new ForegroundColorSpan(secondary));
        view.setText(text);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(10), 0, dp(10));
        view.setOnClickListener(v -> fix.run());
        if (warnings.getChildCount() > 0) divider(warnings);
        warnings.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void refresh() {
        boolean nativeMode = BarService.nativeMode(this);
        syncing = true;
        try { modeGroup.check(nativeMode ? colorId : touchId); } finally { syncing = false; }
        areaSwitch.setEnabled(!nativeMode);
        lockedSwitch.setEnabled(BarService.power(this)); // Sub-option of the power switch; the policy also requires power on.
        int log = BarService.logState();
        String visibility = null, taskbar = null; // null: unreadable (e.g. SecurityException), never assumed shown or taskbar on
        try { visibility = Settings.Secure.getString(getContentResolver(), GESTURE_BAR); } catch (RuntimeException unreadable) { }
        try { taskbar = Settings.System.getString(getContentResolver(), TASKBAR); } catch (RuntimeException unreadable) { }
        String nav = null;
        try { nav = Settings.Secure.getString(getContentResolver(), BarGesture.NAV_KEY); } catch (RuntimeException unreadable) { }
        int gesture = StatusPolicy.gestureBar(visibility, taskbar, nav);
        String state = StatusPolicy.bar(BarService.granted(this), BarService.enabled(this), BarService.serviceRunning(), nativeMode,
            BootRecovery.permitted(this), LogSession.breenoDisabled(this), BarService.logsPermitted(this), log == LogSession.ACTIVE,
            log == LogSession.CONNECTING, BarService.ready(this), MainActivity.cts(preferences.getString(MainActivity.TARGET_COMPONENT, "")),
            BarService.googleVoice(this), gesture);
        long now = System.currentTimeMillis();
        boolean[] shown = shownPathways();
        logStatus.setText(row("기기 로그", logState()));
        status.setText(row("하단 실행", state));
        powerStatus.setText(row("전원 버튼", powerState()));
        doubleStatus.setText(row("전원 버튼 두 번 누르기", doubleState()));
        showRows(new TextView[]{logStatus, status, powerStatus, doubleStatus, recentStatus},
            new boolean[]{BarService.logsWanted(this), shown[0], shown[1], shown[2], true});
        SpannableStringBuilder recent = new SpannableStringBuilder();
        span(recent, "최근 기록 · 눌러서 자세히", new AbsoluteSizeSpan(13, true), new ForegroundColorSpan(secondary));
        if (shown[0]) recent.append("\n하단  ").append(StatusPolicy.summary(preferences.getString(BarService.LAST_BAR, null), now));
        if (shown[1]) recent.append("\n전원  ").append(StatusPolicy.summary(preferences.getString(BarService.LAST_POWER, null), now));
        if (shown[2]) recent.append("\n두 번  ").append(StatusPolicy.summary(preferences.getString(BarService.LAST_DOUBLE, null), now));
        recentStatus.setText(recent);
        targetLabel.setText(preferences.getString(MainActivity.TARGET_LABEL, "서클 투 서치 (기본 · 빠른 실행)"));
        doubleLabel.setText("두 번 누르면: " + preferences.getString(BarService.POWER_DOUBLE_LABEL,
            BarService.doubleTarget(this) ? "서클 투 서치 (빠른 실행)" : NOTHING));
        refreshWarnings();
    }

    /**
     * Bottom, power long press, power double press: whether each is turned on. With everything off the bottom row
     * still shows (as off), so the status card is never empty.
     */
    private boolean[] shownPathways() {
        boolean power = BarService.power(this), twice = BarService.doublePress(this);
        return new boolean[]{BarService.enabled(this) || !(power || twice), power, twice};
    }

    /** Shows the given rows of the status card; a divider shows only between two visible rows. */
    private void showRows(TextView[] rows, boolean[] visible) {
        boolean above = false;
        for (int i = 0; i < rows.length; i++) {
            rows[i].setVisibility(visible[i] ? android.view.View.VISIBLE : android.view.View.GONE);
            int index = statusCard.indexOfChild(rows[i]);
            if (index > 0) statusCard.getChildAt(index - 1).setVisibility(above && visible[i] ? android.view.View.VISIBLE : android.view.View.GONE);
            above |= visible[i];
        }
    }

    /** Read-only: the newest attempt per pathway as recorded locally; never claims a window appeared. */
    private void showAttempts() {
        long now = System.currentTimeMillis();
        boolean[] shown = shownPathways();
        StringBuilder records = new StringBuilder();
        if (shown[0]) records.append("[하단 · 연결 앱]\n").append(StatusPolicy.detail(preferences.getString(BarService.LAST_BAR, null), now)).append("\n\n");
        if (shown[1]) records.append("[전원 버튼 · Gemini Assistant]\n").append(StatusPolicy.detail(preferences.getString(BarService.LAST_POWER, null), now)).append("\n\n");
        if (shown[2]) records.append("[전원 버튼 두 번 누르기]\n").append(StatusPolicy.detail(preferences.getString(BarService.LAST_DOUBLE, null), now)).append("\n\n");
        showPhrased(new AlertDialog.Builder(this).setTitle("최근 실행 기록")
            .setMessage(records
                + "경로마다 마지막 1건만 이 기기에 저장하며 외부로 보내지 않습니다. 앱은 실행 요청 전달과 Gemini 요청 수락까지만 확인하며,"
                + " 서클 투 서치나 Gemini 창이 실제로 떴는지는 확인하지 않습니다. Google 상태는 선택과 설치·사용 여부만 확인하며 실제 연결 상태는 알 수 없습니다."
                + " 길게 눌렀는데 기록이 바뀌지 않았다면 입력이 감지되지 않았을 수 있으니 기기 로그 연결과 ColorOS 전원 버튼 음성 비서 호출 설정을 확인해보세요.")
            .setPositiveButton("닫기", null));
    }

    /** Structural state only: a session counts as connected after a system-UID entry, never on own-UID output. */
    private String logState() {
        if (!BarService.logsWanted(this)) return "꺼짐 · 사용하는 기능 없음";
        if (!BarService.logsPermitted(this)) return "권한 필요 · 최초 ADB 설정 안내 참고";
        if (!BarService.granted(this) || !BarService.serviceRunning()) return "접근성 연결 대기";
        switch (BarService.logState()) {
            case LogSession.ACTIVE: return "연결됨";
            case LogSession.CONNECTING: return "확인 창에서 허용 대기 중";
            default: return "승인 필요 · 기기 로그 접근 승인 요청을 눌러주세요";
        }
    }

    private String powerState() {
        int log = BarService.logState();
        return StatusPolicy.power(BarService.power(this), BarService.granted(this), BarService.serviceRunning(), BarService.logsPermitted(this),
            log == LogSession.ACTIVE, log == LogSession.CONNECTING, BarService.googleVoice(this), BootRecovery.permitted(this));
    }

    /** Event-driven refresh when returning from Gemini, CTS or a dialog over this screen; no polling. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Exported settings UI deliberately ignores all automation extras.
    }

    @Override public void onResume() {
        super.onResume();
        syncing = true;
        try {
            bootSwitch.setChecked(BootRecovery.wanted(this));
            powerSwitch.setChecked(BarService.power(this));
            lockedSwitch.setChecked(BarService.lockedAssist(this));
            doubleSwitch.setChecked(BarService.doublePress(this));
        } finally { syncing = false; }
        BarService.releaseOrphanedPowerKeys(this);
        BarService.refreshConfiguration(); refresh(); applyRecentVisibility();
        status.postDelayed(this::refresh, 300);
        BarService.showPreview(areaSwitch.isChecked());
        if (waitingAccessibility && BarService.granted(this)) { waitingAccessibility = false; begin(); }
    }

    @Override public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(BarService.STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(serviceState, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(serviceState, filter);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(GESTURE_BAR), false, gestureBar);
        getContentResolver().registerContentObserver(Settings.System.getUriFor(TASKBAR), false, gestureBar);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(BarGesture.NAV_KEY), false, gestureBar);
    }

    @Override public void onStop() {
        getContentResolver().unregisterContentObserver(gestureBar);
        unregisterReceiver(serviceState);
        super.onStop();
    }

    @Override public void onPause() {
        BarService.showPreview(false);
        super.onPause();
    }
}
