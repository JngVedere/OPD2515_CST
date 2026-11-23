package dev.padhelper.fastcts;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
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
    // The switch "제스처 안내 막대 숨기기" itself (device, 2026-11-21: OFF/ON wrote 0/1 here; navbar_gesture_visibility stayed 1
    // with the bar shown, which made the status a false "오류").
    private static final String GESTURE_BAR = "gesture_side_hide_bar_prevention_enable";
    /** ColorOS taskbar switch (system table, 1 on); read only, never written. */
    static final String TASKBAR = "enable_launcher_taskbar";
    private static final int ERROR_COLOR = 0xFFD93025;
    private TextView status, logStatus, powerStatus, doubleStatus, recentStatus, targetLabel, doubleLabel;
    private int secondary;
    /** Theme primary text colour with its disabled state, so a disabled slider label looks disabled. */
    private android.content.res.ColorStateList primary;
    private LinearLayout body, warnings, updateBanner, statusCard;
    private LinearLayout homePage, settingsPage, navTabs;
    /** Floating tab pill: the sliding recessed well (navWell) under a row of icon tabs (navTabs). */
    private android.widget.FrameLayout navBar;
    private android.view.View navWell;
    /** Surface colors (light/dark): page background, cards, and the fills of buttons inside cards. */
    private boolean night;
    private int pageColor, cardColor, fieldColor;
    private ScrollView pageScroll;
    private android.widget.ImageView homeTab, settingsTab;
    private static final String TAB_SETTINGS = "tab_settings";
    private Switch autoUpdateSwitch;
    /** Where text/toggle/button add views: body, or the current settings card opened by section(). */
    private LinearLayout section;
    private SharedPreferences preferences;
    /** Touch-area fold: the preview switch state before the fold opened; non-null exactly while the fold is open. */
    private Boolean previewBeforeFold;
    private boolean autoPreview;
    private Switch areaSwitch, recentSwitch, bootSwitch, powerSwitch, lockedSwitch, doubleSwitch, overlaySwitch;
    private RadioGroup modeGroup;
    private int touchId, colorId;
    private boolean waitingAccessibility;
    /** True while controls are synced from stored state; listeners then must not act as if the user changed them. */
    private boolean syncing;
    /** Touch-area sliders for the current orientation ({x, bottom, width, height}), the hold slider and the Gemini wait slider. */
    private final Slider[] areaSliders = new Slider[4];
    /** Compact (default) hides the card descriptions; Detail shows every one. Per-viewer display preference only. */
    private static final String COMPACT = "compact_ui";
    private final java.util.List<android.view.View> details = new java.util.ArrayList<>();
    private static final String EXPERIMENTAL = "experimental";
    private android.widget.FrameLayout detailToggle;
    /** The Detail / Compact bubble on screen, replaced on the next switch. */
    private android.widget.PopupWindow modeTip;
    private TextView detailKnob;
    private int accent;

    private void showDetails() {
        boolean compact = preferences.getBoolean(COMPACT, true);
        for (android.view.View view : details) view.setVisibility(compact ? android.view.View.GONE : android.view.View.VISIBLE);
        android.graphics.drawable.GradientDrawable knob = new android.graphics.drawable.GradientDrawable();
        // Detail: theme accent (the switches' color) on the right; Compact: the screen's secondary gray on the left.
        int fill = compact ? secondary : accent;
        knob.setColor(fill);
        knob.setCornerRadius(dp(13));
        // Dark theme turns the accent and gray light: pick the label color from the fill so it stays readable.
        detailKnob.setTextColor(android.graphics.Color.luminance(fill) > 0.45f ? 0xFF1F1F24 : 0xFFFFFFFF);
        detailKnob.setBackground(knob);
        detailKnob.setText(compact ? "Compact" : "Detail");
        ((android.widget.FrameLayout.LayoutParams) detailKnob.getLayoutParams()).gravity =
            (compact ? android.view.Gravity.START : android.view.Gravity.END) | android.view.Gravity.CENTER_VERTICAL;
        detailKnob.requestLayout();
        detailToggle.setContentDescription(compact ? "간단히 보기 켜짐, 눌러서 설명 모두 보기" : "설명 모두 보기 켜짐, 눌러서 간단히 보기");
    }

    /** Orientation badge of the touch area section: blue while the area is used, grey in native mode. */
    private TextView facing;
    private final android.graphics.drawable.GradientDrawable facingBox = new android.graphics.drawable.GradientDrawable();
    private Slider holdSlider, settleSlider;
    private final BroadcastReceiver serviceState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };
    /**
     * Foreground-only (onStart..onStop): picks up the ColorOS gesture-bar or taskbar switch, or an ADB change, while this
     * screen is visible. One observer for both URIs, so the single unregister in onStop removes both.
     */
    private final ContentObserver gestureBar = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refresh(); showTuning(); } // default width follows the taskbar
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Straight to the system page. finishAndRemoveTask: a plain finish left this screen's empty task in recents
        // (device: a blank "Circle-to-Search Trigger" card next to 더 어둡게).
        if (state == null && dimTilePressed(getIntent())) { finishAndRemoveTask(); return; }
        preferences = getSharedPreferences("bar", MODE_PRIVATE);
        Tweaks.applyDefaults(this);
        // Hidden from recents by the task flag, not the manifest: a manifest excludeFromRecents makes ColorOS drop "잠금"
        // from the card menu even while the card is shown (device), so the user could not lock the app.
        applyRecentVisibility();
        setTitle("Circle-to-Search Trigger");
        if (getActionBar() != null) getActionBar().hide();
        // ColorOS-style surfaces (user request, like 欢太工具箱): light gray page, plain rounded cards, no outlines.
        night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        pageColor = night ? 0xFF000000 : 0xFFF1F2F4;
        cardColor = night ? 0xFF1C1C1E : 0xFFFFFFFF;
        fieldColor = night ? 0xFF2C2C2E : 0xFFF2F3F5;
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        section = body;
        int pad = dp(20);
        body.setPadding(pad, pad, pad, dp(120)); // room below the last card for the floating tab bar
        // Two pages (user request): 홈 = status and launch options, 설정 = 업데이트, 알림, 고급; a bottom bar switches them.
        homePage = body;
        settingsPage = new LinearLayout(this);
        settingsPage.setOrientation(LinearLayout.VERTICAL);
        settingsPage.setPadding(pad, pad, pad, dp(120));
        LinearLayout pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.VERTICAL);
        pages.addView(homePage);
        pages.addView(settingsPage);
        pageScroll = new ScrollView(this);
        pageScroll.addView(pages);
        // The tab bar floats over the page as a pill (user request), so the page scrolls under it.
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(pageColor);
        root.addView(pageScroll, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        navBar = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams navParams = new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        root.addView(navBar, navParams);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, 0);
            navParams.bottomMargin = bars.bottom + dp(12);
            navBar.setLayoutParams(navParams);
            return insets;
        });
        setContentView(root);
        android.content.res.TypedArray colors = obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary, android.R.attr.textColorPrimaryDisableOnly, android.R.attr.colorAccent});
        secondary = colors.getColor(0, 0xFF808080);
        primary = colors.getColorStateList(1);
        accent = colors.getColor(2, 0xFF4A5A8E);
        colors.recycle();
        buildNavBar(state != null && state.getBoolean(TAB_SETTINGS, false));
        // Title row: app name on the left, the Detail / Compact switch for the card descriptions at the right end.
        TextView title = text("Circle-to-Search Trigger", 30);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(night ? 0xFFF2F2F2 : 0xFF1C1C1E);
        body.removeView(title);
        LinearLayout header = new LinearLayout(this);
        header.setPadding(dp(4), dp(28), 0, 0); // large title with room above it
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // ON/OFF-style switch: gray outlined capsule, a colored pill with the label on one side (Detail right, Compact left).
        detailToggle = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable capsule = new android.graphics.drawable.GradientDrawable();
        capsule.setColor(0x00000000);
        capsule.setStroke(dp(1), secondary); // same thin gray as the rest of the screen
        capsule.setCornerRadius(dp(16));
        detailToggle.setBackground(capsule);
        detailToggle.setPadding(dp(3), dp(3), dp(3), dp(3));
        detailKnob = new TextView(this);
        detailKnob.setTextSize(12);
        detailKnob.setTypeface(null, android.graphics.Typeface.BOLD);
        detailKnob.setTextColor(0xFFFFFFFF);
        detailKnob.setGravity(android.view.Gravity.CENTER);
        detailToggle.addView(detailKnob, new android.widget.FrameLayout.LayoutParams(dp(66), dp(26)));
        detailToggle.setOnClickListener(v -> {
            boolean compact = !preferences.getBoolean(COMPACT, true);
            preferences.edit().putBoolean(COMPACT, compact).apply();
            showDetails();
            // Same bubble as the (i) marks (user request): names the view just switched to.
            if (modeTip != null) modeTip.dismiss();
            android.widget.PopupWindow tip = showTip(v, compact ? "깔끔한 버전" : "자세한 버전", 0xA8);
            modeTip = tip;
            v.postDelayed(tip::dismiss, 2_000); // brief notice, unlike the (i) bubbles (user request)
        });
        header.addView(detailToggle, new LinearLayout.LayoutParams(dp(108), dp(32)));
        body.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView tagline = text("하단 중앙을 길게 눌러 선택한 앱을 엽니다.", 16);
        tagline.setTextColor(secondary);
        tagline.setPadding(dp(4), dp(6), 0, dp(16));
        // Update banner: filled only when the automatic check on open finds a newer version.
        updateBanner = new LinearLayout(this);
        updateBanner.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable green = new android.graphics.drawable.GradientDrawable();
        green.setColor(0x2622C55E); // Translucent green reads on both light and dark backgrounds.
        green.setStroke(dp(1), UPDATE_COLOR);
        green.setCornerRadius(dp(20));
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
        amber.setCornerRadius(dp(20));
        warnings.setBackground(amber);
        warnings.setPadding(dp(18), dp(8), dp(18), dp(8));
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
        text("하단 영역 인식: 투명한 터치 영역을 길게 누릅니다.\n"
            + "ColorOS 기본 하단 바: 시스템 바를 그대로 쓰고, 길게 누르기를 기기 로그로 감지합니다.\n"
            + "3버튼 탐색에서는 홈 버튼을 길게 누르면 됩니다.\n"
            + "3버튼 탐색에서 하단 영역 인식을 쓰면 터치 영역이 최근 앱 버튼을 가릴 수 있습니다.\n"
            + "그때는 '터치 영역 설정'에서 위치를 옮겨주세요.\n"
            + "앱을 삭제하기 전에는 '끄기'를 먼저 눌러 제스처 설정을 되돌려주세요.", 14);
        // 연결 앱 lives under 실행 방식 (user request): what the bottom opens belongs with how it is triggered.
        divider(section);
        TextView targetTitle = text("연결 앱", 16);
        targetTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        targetLabel = text("", 18);
        button("앱 선택", this::chooseApp);
        text("기본값은 서클 투 서치 빠른 실행입니다.\n다른 앱을 고르면 그 앱이 바로 열립니다.", 14);
        divider(section);
        text("서클 투 서치를 쓰려면 기본 디지털 어시스턴트를 Google로 선택해주세요.\n"
            + "MiCTS는 필요 없습니다. 설치되어 있으면 직접 실행이 실패할 때만 씁니다.", 14);
        button("기본 어시스턴트 설정", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
                Toast.makeText(this, "디지털 어시스턴트 앱을\nGoogle로 선택해주세요.", Toast.LENGTH_LONG).show();
            }
            catch (ActivityNotFoundException unavailable) {
                Toast.makeText(this, "설정 → 앱 → 기본 앱에서\n디지털 어시스턴트를 Google로 선택해주세요.", Toast.LENGTH_LONG).show();
                startActivity(freshSettings(Settings.ACTION_SETTINGS));
            }
        });

        // 터치 영역 lives under 연결 앱 in the 실행 방식 card (user request): 실행 방식 / 연결 앱 / 터치 영역 in one card.
        divider(section);
        TextView areaHeading = text("터치 영역", 16);
        areaHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        areaSwitch = toggle("인식 영역 표시", preferences.getBoolean("show_area", false), (view, checked) -> {
            // The fold's automatic on/off is not saved (the switch is locked while the fold is open).
            if (!autoPreview) preferences.edit().putBoolean("show_area", checked).apply();
            BarService.showPreview(checked);
        });
        text("하단 영역 인식의 실제 범위를 파란색으로 보여줍니다.\n설정 화면을 벗어나면 다시 투명해집니다.", 14);
        divider(section);
        boolean landscape = landscape();
        int[] screen = screenDp();
        // Collapsed on every open (user request), like 고급; the title row toggles it. Sliders are built into the box.
        TextView areaTitle = text("터치 영역 설정", 16);
        areaTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        section.removeView(areaTitle);
        LinearLayout areaRow = new LinearLayout(this);
        areaRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        areaRow.setMinimumHeight(dp(48));
        areaRow.addView(areaTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView areaToggle = phrase(new TextView(this));
        areaToggle.setTextSize(14); areaToggle.setTextColor(secondary);
        areaToggle.setPadding(dp(12), 0, 0, 0);
        areaRow.addView(areaToggle);
        section.addView(areaRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout areaBox = new LinearLayout(this);
        areaBox.setOrientation(LinearLayout.VERTICAL);
        section.addView(areaBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable showArea = () -> areaToggle.setText(areaBox.getVisibility() == android.view.View.VISIBLE ? "접기 ▴" : "펼치기 ▾");
        areaBox.setVisibility(android.view.View.GONE);
        showArea.run();
        areaRow.setOnClickListener(v -> {
            boolean open = areaBox.getVisibility() != android.view.View.VISIBLE;
            areaBox.setVisibility(open ? android.view.View.VISIBLE : android.view.View.GONE);
            showArea.run();
            // Opening shows the area and locks the switch (user request); closing unlocks it and puts back the earlier state.
            autoPreview = true;
            try {
                if (open) { previewBeforeFold = areaSwitch.isChecked(); areaSwitch.setChecked(true); }
                else if (previewBeforeFold != null) { areaSwitch.setChecked(previewBeforeFold); previewBeforeFold = null; }
            } finally { autoPreview = false; }
            areaSwitch.setEnabled(!BarService.nativeMode(this) && previewBeforeFold == null);
        });
        LinearLayout areaSection = section;
        section = areaBox;
        // Which value set the sliders edit; the activity is recreated on rotation, so this always matches.
        facing = text((landscape ? "가로" : "세로") + " 화면 값을 조절하는 중", 16);
        facing.setTypeface(null, android.graphics.Typeface.BOLD);
        facing.setGravity(android.view.Gravity.CENTER);
        facingBox.setCornerRadius(dp(10));
        facing.setBackground(facingBox);
        showFacing(true); // refresh() greys it in native mode, like the sliders
        facing.setPadding(dp(14), dp(10), dp(14), dp(10));
        ((LinearLayout.LayoutParams) facing.getLayoutParams()).setMargins(0, dp(4), 0, dp(8));
        text("가로와 세로 화면 값을 따로 저장하며, 지금 방향의 값만 바뀝니다.\n"
            + "조절하는 동안 영역이 파란색으로 보입니다. 하단 영역 인식에서만 쓰입니다.\n"
            + "값을 누르면 숫자로 직접 입력할 수 있습니다.\n"
            + "너비 기본값: 제스처 바와 작업 표시줄이 함께 켜져 있으면\n"
            + "세로 " + TouchArea.TASKBAR_WIDTH + "dp · 가로 " + TouchArea.TASKBAR_WIDTH_LAND + "dp, 아니면 " + TouchArea.WIDTH + "dp입니다.", 14);
        int side = Math.max(0, (screen[0] - TouchArea.MIN_WIDTH) / 2);
        side -= side % 4; // symmetric multiple of the step, so the centred default 0 is a slider position
        areaSliders[0] = slider("가로 위치 (가운데 기준)", "dp", -side, side, 4, true, value -> saveArea(0, value));
        areaSliders[0].range = () -> {
            int[] screenNow = screenDp();
            int room = Math.max(0, (screenNow[0] - BarService.area(this, landscape(), screenNow[0], screenNow[1])[2]) / 2);
            return new int[]{-room, room};
        };
        areaSliders[1] = slider("아래에서 띄우기", "dp", 0, Math.min(TouchArea.MAX_BOTTOM, screen[1] / 2), 2, true, value -> saveArea(1, value));
        areaSliders[2] = slider("너비", "dp", TouchArea.MIN_WIDTH, screen[0], 4, true, value -> saveArea(2, value));
        areaSliders[3] = slider("높이", "dp", TouchArea.MIN_HEIGHT, TouchArea.MAX_HEIGHT, 2, true, value -> saveArea(3, value));
        divider(section);
        holdSlider = slider("길게 누르기 인식 시간", "ms", TouchArea.MIN_HOLD, TouchArea.MAX_HOLD, 5, true, value -> {
            preferences.edit().putInt(TouchArea.HOLD_KEY, value).apply();
        });
        text("짧을수록 빨리 열립니다.\n"
            + "너무 짧으면 일반 터치나 홈 제스처를 길게 누르기로 잘못 인식할 수 있습니다.\n"
            + "기본값은 " + TouchArea.HOLD + "ms입니다.", 14);
        button("터치 영역 기본값으로 되돌리기", () -> {
            SharedPreferences.Editor edit = preferences.edit().remove(TouchArea.HOLD_KEY);
            for (boolean orientation : new boolean[]{false, true})
                for (String field : TouchArea.FIELDS) edit.remove(TouchArea.key(orientation, field));
            edit.apply();
            BarService.refreshArea();
            showTuning();
            Toast.makeText(this, "위치·크기와 인식 시간을\n기본값으로 되돌렸습니다.", Toast.LENGTH_SHORT).show();
        });
        section = areaSection;

        section("전원 버튼");
        powerSwitch = toggle("전원 버튼 길게 눌러 Gemini Assistant 열기", BarService.power(this), (view, checked) -> {
            if (syncing) return;
            BarService.setPower(this, checked);
            if (checked) approveIfNeeded();
            refresh();
        });
        text("Gemini 앱 전체 화면 대신 Gemini Assistant 창을 엽니다.\n"
            + "하단 실행 방식과 따로 켜고 끌 수 있습니다.\n"
            + "필요한 것: 접근성, 기기 로그 권한,\n"
            + "ColorOS의 '전원 버튼으로 음성 비서 호출' 설정", 14);
        divider(section);
        lockedSwitch = toggle("잠금 화면에서 Gemini Assistant 호출", BarService.lockedAssist(this), (view, checked) -> {
            if (syncing) return;
            BarService.setLockedAssist(this, checked);
            refresh();
        });
        text("화면이 꺼졌거나 잠겨 있어도 전원 버튼으로 Gemini Assistant를 엽니다.\n"
            + "잠금을 풀거나 우회하지 않습니다.\n"
            + "잠긴 상태에서는 화면 내용과 스크린샷을 보내지 않습니다.\n"
            + "Gemini의 잠금 화면 설정이 필요할 수 있습니다.\n"
            + "재부팅 후에는 첫 잠금 해제와 기기 로그 승인 뒤부터 동작합니다.", 14);
        divider(section);
        settleSlider = slider("Gemini 요청 전 대기", "ms", 0, TouchArea.MAX_SETTLE, 5, false, value -> {
            preferences.edit().putInt(TouchArea.SETTLE_KEY, value).apply();
        });
        text("전원 버튼을 누르면 Google 음성 연결을 다시 잡습니다(약 0.35초).\n"
            + "그 뒤 이 시간만큼 더 기다렸다가 Gemini Assistant를 요청합니다.\n"
            + "가끔 뜨지 않으면 늘리고, 더 빨리 뜨게 하려면 줄이세요.\n"
            + "기본값은 " + TouchArea.SETTLE + "ms입니다.", 14);
        button("Gemini 대기 기본값으로 되돌리기", () -> {
            preferences.edit().remove(TouchArea.SETTLE_KEY).apply();
            showTuning();
            Toast.makeText(this, "Gemini 요청 전 대기를\n기본값으로 되돌렸습니다.", Toast.LENGTH_SHORT).show();
        });
        divider(section);
        button("Google 음성 연결 다시 시작", this::repairVoice);
        text("Gemini Assistant가 뜨지 않을 때 눌러주세요.\n"
            + "선택된 Google 음성 서비스를 약 0.35초 해제했다가 다시 연결합니다.\n"
            + "다른 어시스턴트로 바꾸지 않습니다.\n"
            + "보안 설정 쓰기 권한이 있으면 전원 버튼을 누를 때마다 자동으로 실행됩니다.", 14);

        section("최근 앱");
        recentSwitch = toggle("최근 앱에 표시", preferences.getBoolean("show_recent", false), (view, checked) -> {
            preferences.edit().putBoolean("show_recent", checked).apply();
            applyRecentVisibility();
        });
        text("이 설정 화면을 최근 앱 목록에 표시합니다.\n"
            + "앱 서랍 아이콘은 '바로 실행'에서 따로 켭니다.\n"
            + "최근 앱에서 이 앱 카드를 잠가 두면 '모두 닫기' 때 종료되지 않습니다.\n"
            + "잠근 뒤에는 표시를 꺼도 잠금이 유지됩니다.", 14);

        section("바로 실행");
        ComponentName drawer = new ComponentName(this, MainActivity.DRAWER);
        // Untouched (DEFAULT) follows the manifest, which shows the icon; only an explicit hide reads as off.
        toggle("앱 서랍에 표시", getPackageManager().getComponentEnabledSetting(drawer) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            (view, checked) -> getPackageManager().setComponentEnabledSetting(drawer, checked
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP));
        text("끄면 앱 서랍에서 아이콘이 사라집니다.\n"
            + "아이콘을 길게 눌러 '설정'을 고르면 언제든 이 화면이 열립니다.", 14);
        TextView iconLabel = text("아이콘을 누르면", 16);
        iconLabel.setPadding(0, dp(10), 0, dp(2));
        RadioGroup iconGroup = new RadioGroup(this);
        String[] iconNames = {"서클 투 서치", "Gemini Assistant", "설정 화면"};
        int[] iconIds = new int[iconNames.length];
        for (int i = 0; i < iconNames.length; i++) {
            RadioButton option = phrase(new RadioButton(this));
            iconIds[i] = android.view.View.generateViewId();
            option.setId(iconIds[i]); option.setText(iconNames[i]); option.setTextSize(18); option.setMinHeight(dp(48));
            iconGroup.addView(option);
        }
        section.addView(iconGroup);
        iconGroup.check(iconIds[DrawerActivity.action(this)]);
        iconGroup.setOnCheckedChangeListener((group, selected) -> {
            for (int i = 0; i < iconIds.length; i++)
                if (iconIds[i] == selected) preferences.edit().putInt(DrawerActivity.ACTION, i).apply();
        });
        text("서클 투 서치는 연결 앱 설정과 상관없이 열립니다.\n"
            + "Gemini Assistant는 전원 버튼 기능이 꺼져 있어도 열립니다.", 14);

        section("추가 안내");
        button("최초 ADB 설정 안내", this::adbHelp);
        divider(section);
        button("접근성 설정", this::openAccessibility);
        button("자동 실행 설정", this::openAutoStart);
        button("앱 정보와 절전 설정", this::openAppInfo);
        button("최근 앱 정리 제외 설정", this::recentLockPrompt);
        button("설정 닫기", () -> { applyRecentVisibility(); moveTaskToBack(true); });

        body = settingsPage; // 업데이트, 알림, 고급 and the footer live on the 설정 page.
        section = body;
        TextView settingsTitle = text("설정", 30); // large page title, like the home page's
        settingsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        settingsTitle.setTextColor(night ? 0xFFF2F2F2 : 0xFF1C1C1E);
        settingsTitle.setPadding(dp(4), dp(28), 0, 0);
        section("업데이트");
        autoUpdateSwitch = toggle("설정을 열 때 업데이트 자동 확인", preferences.getBoolean(UPDATE_AUTO, true),
            (view, checked) -> preferences.edit().putBoolean(UPDATE_AUTO, checked).apply());
        text("이 설정 화면을 열 때마다 GitHub에서 새 버전을 한 번 확인합니다.\n"
            + "새 버전이 있으면 화면 맨 위에 알려줍니다.\n"
            + "없거나 확인에 실패하면 아무것도 표시하지 않습니다.", 14);
        divider(section);
        toggle("베타 버전도 확인", preferences.getBoolean(UPDATE_BETA, false),
            (view, checked) -> preferences.edit().putBoolean(UPDATE_BETA, checked).apply());
        text("켜면 정식 버전보다 먼저 나오는 베타 버전도 알려줍니다.\n"
            + "새 기능을 먼저 쓸 수 있지만, 검증 중이라 오류가 있을 수 있습니다.\n"
            + "끄면 정식 버전만 확인합니다.\n"
            + "베타를 설치한 뒤 끄면, 더 높은 정식 버전이 나올 때 업데이트됩니다.", 14);
        divider(section);
        button("업데이트 확인", this::checkUpdate);
        text("GitHub에서 새 버전 정보만 받아오며, 기기 정보는 보내지 않습니다.\n"
            + "새 버전은 파일을 검증한 뒤 Android 설치 화면으로 설치합니다.\n"
            + "처음 한 번은 '출처를 알 수 없는 앱 설치'를 허용해야 합니다.\n"
            + "업데이트 후에는 기기 로그 승인을 다시 해야 합니다.", 14);
        section("알림");
        // Small "테스트" button beside the switch instead of a full-width button (user request).
        islandAlert = toggle("카메라 섬에도 표시", Alerts.wanted(this, Alerts.ISLAND),
            (view, checked) -> alertChanged(Alerts.ISLAND, checked), "테스트", this::testIsland);
        text("기기 로그 알림을 카메라 옆 캡슐(라이브 알림)에도 띄웁니다.\n"
            + "필요한 것:\n"
            + "· 이 앱 알림 설정의 '라이브 알림에 라이브 업데이트 표시'\n"
            + "· '기기 로그 권한이 없을 때 알림 받기'\n"
            + "캡슐에 뜬 알림은 기기 로그가 다시 연결될 때까지 지울 수 없습니다.\n"
            + "'테스트'를 누르면 10초간 테스트 알림을 보냅니다.\n"
            + "이 앱이 앞에 있으면 섬이 보이지 않아, 이 화면이 부동창으로 바뀝니다.\n"
            + "창을 누르면 다시 조작할 수 있습니다.", 14);
        divider(section);
        logAlert = toggle("기기 로그 권한이 없을 때 알림 받기", Alerts.wanted(this, Alerts.LOG),
            (view, checked) -> alertChanged(Alerts.LOG, checked), "알림이 뜨기까지\n최대 60초가 걸릴 수 있습니다.");
        text("기기 로그가 필요한 옵션(ColorOS 기본 바, 전원 버튼)이 켜져 있을 때 알려줍니다.\n"
            + "· 권한이 없을 때\n"
            + "· 승인되지 않았거나 거부되었을 때\n"
            + "· 연결이 끊겼을 때\n"
            + "알림을 누르면 바로 기기 로그 확인 창이 뜹니다.\n"
            + "ADB 권한이 없을 때는 설정 화면이 열립니다.", 14);
        divider(section);
        updateAlert = toggle("새로운 업데이트가 있을 때 알림 받기", Alerts.wanted(this, Alerts.UPDATE),
            (view, checked) -> alertChanged(Alerts.UPDATE, checked),
            "새 버전은 24시간에 한 번 확인합니다.\n알림이 뜨기까지 최대 24시간이\n걸릴 수 있습니다.");
        text("24시간에 한 번 GitHub에서 새 버전을 확인합니다.\n"
            + "그때 인터넷이 끊겨 있으면 다음 확인은 24시간 뒤입니다.\n"
            + "같은 버전은 한 번만 알려주며, '베타 버전도 확인' 설정을 따릅니다.", 14);
        section("고급");
        LinearLayout advancedCard = section;
        LinearLayout advancedContent = new LinearLayout(this);
        advancedContent.setOrientation(LinearLayout.VERTICAL);
        advancedCard.addView(advancedContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        section = advancedContent;
        buildTweaks();
        // 고급 → 실험적 기능: its own fold below 편의 기능, collapsed on every open (user request); the status card still
        // shows whether the double press is on. One red box per option, so every risk sits next to its own switch.
        divider(section);
        LinearLayout experimentalContent = new LinearLayout(this);
        experimentalContent.setOrientation(LinearLayout.VERTICAL);
        TextView experimentalTitle = text("실험적 기능", 18);
        experimentalTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        experimentalTitle.setPadding(0, dp(8), 0, dp(2));
        section.removeView(experimentalTitle);
        LinearLayout experimentalRow = new LinearLayout(this);
        experimentalRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        experimentalRow.setMinimumHeight(dp(48));
        experimentalRow.addView(experimentalTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView experimentalToggle = phrase(new TextView(this));
        experimentalToggle.setTextSize(14); experimentalToggle.setTextColor(secondary);
        experimentalToggle.setPadding(dp(12), 0, 0, 0);
        experimentalRow.addView(experimentalToggle);
        section.addView(experimentalRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        section.addView(experimentalContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable showExperimental = () -> experimentalToggle.setText(
            experimentalContent.getVisibility() == android.view.View.VISIBLE ? "접기 ▴" : "펼치기 ▾");
        experimentalContent.setVisibility(android.view.View.GONE);
        showExperimental.run();
        experimentalRow.setOnClickListener(v -> {
            experimentalContent.setVisibility(experimentalContent.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE);
            showExperimental.run();
        });
        LinearLayout doubleBox = experimentalBox(experimentalContent), overlayBox = experimentalBox(experimentalContent);
        // 고급 is always open (user request: no 접기/펼치기); its label is a plain one like the other cards'.
        section = doubleBox;
        doubleSwitch = toggle("전원 버튼 두 번 눌러 앱 실행", BarService.doublePress(this), (view, checked) -> {
            if (syncing) return;
            if (!checked) { BarService.setDoublePress(this, false); refresh(); return; }
            setSynced(doubleSwitch, false); // On only after the risks are accepted below.
            if (!BootRecovery.permitted(this) || !BarService.logsPermitted(this)) {
                Toast.makeText(this, "보안 설정 쓰기와 기기 로그 권한이 필요합니다.\n최초 ADB 설정 안내를 확인해주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            doubleRisks(true);
        });
        doubleLabel = text("", 16);
        button("두 번 누르면 열 앱 선택", () -> chooseApp(true));
        text("켜면 전원 버튼을 한 번 눌렀을 때 시스템 대신 이 앱이 화면을 끕니다.\n"
            + "실행할 앱이 '아무것도 하지 않기'이면 전원 버튼은 원래대로 동작합니다.\n"
            + "단점:\n"
            + "· 화면이 꺼지기까지 약 0.3초 늦어집니다.\n"
            + "· 앱이 멈추면 잠시 전원 버튼으로 화면이 꺼지지 않을 수 있습니다.\n"
            + "앱을 삭제하기 전에는 반드시 이 기능을 먼저 꺼주세요.", 14);
        button("두 번 누르기 주의사항과 복구 방법", () -> doubleRisks(false));
        section = overlayBox;
        overlaySwitch = toggle("접근성 없이 실행", BarService.overlayMode(this), (view, checked) -> {
            if (syncing) return;
            if (checked && !Settings.canDrawOverlays(this)) {
                setSynced(overlaySwitch, false); // On only once the permission is there.
                openOverlayPermission();
                return;
            }
            setOverlayMode(checked);
        });
        text("접근성 대신 '다른 앱 위에 표시' 권한으로 동작합니다.\n"
            + "접근성 승인이 필요 없고, 재부팅 때마다 뜨는 접근성 경고 창도 사라집니다.\n"
            + "단점:\n"
            + "· 최근 앱 정리 등으로 앱이 종료되면 다시 켜지지 않습니다.\n"
            + "  최근 앱에서 이 앱 카드를 꼭 잠가주세요.\n"
            + "· 3버튼 탐색에서는 터치 영역을 내비게이션 바 위에 둘 수 없습니다.\n"
            + "  터치 영역을 약 44dp 올리거나 ColorOS 기본 하단 바 방식을 써주세요.\n"
            + "· '전원 버튼 두 번 눌러 앱 실행'을 쓸 수 없습니다.\n"
            + "· 재부팅 후 앱이 스스로 시작하므로, '자동 실행'이 꼭 켜져 있어야 합니다.\n"
            + "켜면 접근성에서 이 앱을 자동으로 끄고, 끄면 다시 켭니다.\n"
            + "보안 설정 쓰기 권한이 없으면 접근성 설정을 직접 바꿔주세요.", 14);
        section = experimentalBox(experimentalContent);
        TextView compatTitle = text("호환성 개선", 16);
        compatTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        compatTitle.setPadding(0, dp(12), 0, 0);
        toggle("빠른 복구", BarService.fastRecover(this),
            (view, checked) -> preferences.edit().putBoolean(BarService.FAST_RECOVER, checked).apply());
        text("게임 등으로 기기 로그 연결이 끊기면 승인 창을 바로 띄웁니다.\n"
            + "잠금이 풀린 화면에서만, 부팅마다 최대 5번까지 띄웁니다.\n"
            + "단점:\n"
            + "· 게임을 끝내자마자 승인 창이 갑자기 나타날 수 있습니다.\n"
            + "· 승인은 매번 직접 눌러야 합니다.", 14);
        toggle("일부 앱에서 하단 영역 인식 강제 사용", BarService.touchAppsOn(this), (view, checked) -> {
            preferences.edit().putBoolean(BarService.TOUCH_APPS_ON, checked).apply();
            BarService.refreshConfiguration();
            if (checked && BarService.touchApps(this).isEmpty()) chooseTouchApps();
        }, "앱 선택", this::chooseTouchApps);
        text("ColorOS 기본 하단 바 방식에서, 고른 앱에서만 하단 터치 영역을 씁니다.\n"
            + "게임 모드에서 바 인식이 안 되는 게임에 쓰세요.\n"
            + "터치 영역이 떠 있는 동안은 게임 모드가 이 앱을 멈추지 않습니다.\n"
            + "그래서 게임을 마친 뒤에도 기기 로그 연결이 유지됩니다.\n"
            + "단점:\n"
            + "· 기기 로그 연결이 끊겨 있으면 앱 전환을 알 수 없어 동작하지 않습니다.\n"
            + "· 고른 앱에서는 터치 영역이 화면 하단 일부를 가립니다.\n"
            + "  위치와 크기는 '터치 영역'에서 바꿀 수 있습니다.\n"
            + "· 실행 방식이 '하단 영역 인식'이면 효과가 없습니다.", 14);
        // Last item of 고급, below 실험적 기능 too (user request).
        section = advancedContent;
        divider(section);
        button("모든 설정 초기화", this::resetPrompt);
        text("권한과 기기 로그 승인은 그대로 두고, 설정만 처음 상태로 되돌립니다.", 14);
        section = body; // Footer sits below the cards.
        text("상태 알림 없이 실행됩니다. 선택한 방식으로 연결 앱을 엽니다.\n화면 내용 읽기와 캡처는 사용하지 않습니다.", 14)
            .setPadding(0, dp(16), 0, dp(9));
        try {
            text("버전 " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName, 14);
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            text("버전 정보 없음", 14);
        }
        TextView licenses = text("오픈소스 라이선스", 14);
        licenses.setPaintFlags(licenses.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        licenses.setOnClickListener(v -> showLicenses());
        showDetails(); // every card is built: apply Detail / Compact
        // takePendingUpdate first: an update notification always gets its banner, even with the auto check off.
        if (Alerts.takePendingUpdate(this) || preferences.getBoolean(UPDATE_AUTO, true)) checkUpdate(true);
    }

    /** Main thread only: one check or download at a time. */
    private boolean updating;
    static final String UPDATE_BETA = "update_beta";
    private static final String UPDATE_AUTO = "update_auto";

    private Switch logAlert, updateAlert, islandAlert;
    private static final int NOTIFY_REQUEST = 41;

    /** Turning either alert on asks for the notification permission when it is missing (Android 13+). */
    private void alertChanged(String key, boolean checked) {
        if (syncing) return;
        preferences.edit().putBoolean(key, checked).apply();
        Alerts.scheduleUpdateCheck(this);
        if (Alerts.LOG.equals(key)) islandAlert.setEnabled(checked); // the capsule only carries the log alert
        if (!checked) return;
        // The capsule also needs this app's Live Updates switch (ColorOS: off by default); open it when it is off.
        if (Alerts.ISLAND.equals(key) && Build.VERSION.SDK_INT >= 36 && !Alerts.islandAllowed(this)) { openPromotionSettings(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_REQUEST);
        else if (!getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) openNotificationSettings();
    }

    /** This app's Live Updates switch ("라이브 알림에 라이브 업데이트 표시", ColorOS: off by default). */
    private void openPromotionSettings() {
        Toast.makeText(this, "'라이브 알림에 라이브 업데이트 표시'를 켜주세요.", Toast.LENGTH_LONG).show();
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (ActivityNotFoundException missing) { openNotificationSettings(); }
    }

    /** Demo: a short-lived test notice in the camera island; sends the user to whatever is still missing first. */
    private void testIsland() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_REQUEST);
            return;
        }
        if (!getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) { openNotificationSettings(); return; }
        if (!Alerts.islandAllowed(this)) {
            if (Build.VERSION.SDK_INT >= 36) openPromotionSettings();
            else Toast.makeText(this, "이 기기에서는 카메라 섬 알림을 쓸 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        islandDemoPending = true;
        if (isInMultiWindowMode()) { islandDemo(); return; }
        // Full screen: reopen this screen as a ColorOS floating window (windowing mode 100, as `am start --windowingMode
        // 100` uses); islandDemo() then runs when it resumes.
        Context app = getApplicationContext();
        finishAndRemoveTask();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
            try { org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(android.app.ActivityOptions.class, options, "setLaunchWindowingMode", 100); }
            catch (Throwable unavailable) { android.util.Log.w("PadIsland", "floating launch unavailable: " + unavailable.getClass().getSimpleName()); }
            // Device: ColorOS ignores launch bounds here; its floating window always opens at its own default size.
            try { app.startActivity(new Intent(app, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle()); }
            catch (RuntimeException blocked) { android.util.Log.w("PadIsland", "reopen blocked: " + blocked.getClass().getSimpleName()); }
        }, ISLAND_REOPEN_DELAY);
    }

    private static final long ISLAND_REOPEN_DELAY = 300, ISLAND_POST_DELAY = 600, ISLAND_DEMO_MS = 10_000;

    /** In-process only (never an intent extra): the next resume of this screen runs the camera-island demo. */
    private static boolean islandDemoPending;

    /**
     * Device (ColorOS 17): the capsule of the app whose task has focus is hidden, floating window or not; a visible window
     * without focus does not hide it. So the floating screen stops taking focus, the home screen behind it is brought to
     * front (task focus moves there; the floating window stays), and only then the test notice is posted, so the capsule
     * is laid out with the launcher in front. Touching this window, or 10 s, ends the demo.
     */
    private void islandDemo() {
        if (!islandDemoPending) return;
        islandDemoPending = false;
        Context app = getApplicationContext();
        if (isInMultiWindowMode()) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException missing) { moveTaskToBack(true); }
        status.postDelayed(() -> {
            Alerts.test(app);
            Toast.makeText(app, "화면 위쪽 카메라 섬을 확인하세요. 10초 뒤 사라집니다.", Toast.LENGTH_SHORT).show();
        }, ISLAND_POST_DELAY);
        status.postDelayed(this::endIslandDemo, ISLAND_POST_DELAY + ISLAND_DEMO_MS);
    }

    private void endIslandDemo() {
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if ((getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) endIslandDemo();
        if (swipeEvent(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    private void openNotificationSettings() {
        Toast.makeText(this, "알림이 꺼져 있습니다. 알림을 허용해주세요.", Toast.LENGTH_LONG).show();
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (ActivityNotFoundException missing) { openAppInfo(); }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != NOTIFY_REQUEST || (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)) return;
        // Denied: the alerts cannot be shown, so both switch back off. Once Android stops asking, send the user to settings.
        preferences.edit().putBoolean(Alerts.LOG, false).putBoolean(Alerts.UPDATE, false).putBoolean(Alerts.ISLAND, false).apply();
        Alerts.scheduleUpdateCheck(this);
        setSynced(logAlert, false);
        setSynced(updateAlert, false);
        setSynced(islandAlert, false);
        if (!shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings();
        else Toast.makeText(this, "알림 권한이 없어 알림을 켜지 않았습니다.", Toast.LENGTH_SHORT).show();
    }
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
                if (failure != null) Toast.makeText(this, "업데이트 정보를 가져오지 못했습니다 (" + failure + ").\n인터넷 연결을 확인해주세요.", Toast.LENGTH_LONG).show();
                else if (release == null) Toast.makeText(this, "최신 버전입니다.", Toast.LENGTH_SHORT).show();
                else showPhrased(new AlertDialog.Builder(this).setTitle((release.beta ? "새 베타 버전 " : "새 버전 ") + release.version)
                    .setMessage((release.beta ? "베타 버전은 오류가 있을 수 있습니다.\n" : "")
                        + "새 버전을 내려받아 설치할까요?\n설치 후 앱이 다시 시작되며,\n기기 로그 승인을 다시 해야 합니다.")
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
        span(text, "\n" + (release.beta ? "베타 버전은 오류가 있을 수 있습니다.\n" : "") + "설치 후 앱이 다시 시작되며 기기 로그 승인을 다시 해야 합니다.",
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
        block(panel, "USB 디버깅으로 본인 기기를 연결한 뒤\n컴퓨터에서 아래 명령어를 실행하세요.\nADB가 필요합니다.", false);
        codeBlock(panel, command);
        // 권한 설명: collapsed right under the command (user request), holding the two permission descriptions.
        TextView permsToggle = phrase(new TextView(builder.getContext()));
        permsToggle.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        permsToggle.setTypeface(null, android.graphics.Typeface.BOLD);
        permsToggle.setPadding(0, dp(2), 0, dp(10));
        panel.addView(permsToggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout perms = new LinearLayout(builder.getContext());
        perms.setOrientation(LinearLayout.VERTICAL);
        block(perms, heading("보안 설정 쓰기", "앱이 시스템 설정을 바꿀 수 있는 강한 권한입니다.\n재부팅 후 접근성 복구와 하단 바 제스처 전환에 씁니다.\nColorOS 기본 바 방식에 필요합니다."), false);
        rule(perms);
        block(perms, heading("기기 로그 읽기", "ColorOS 기본 바와 전원 버튼 길게 누르기를 감지합니다.\n로그를 저장하거나 외부로 보내지 않습니다.\n권한은 재부팅 후에도 유지됩니다.\n다만 재부팅마다 첫 잠금 해제 뒤\nAndroid 확인 창에서 직접 허용해야 합니다."), false);
        perms.setVisibility(android.view.View.GONE);
        panel.addView(perms, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable showPerms = () -> permsToggle.setText("권한 설명 " + (perms.getVisibility() == android.view.View.VISIBLE ? "▴" : "▾"));
        showPerms.run();
        permsToggle.setOnClickListener(v -> {
            perms.setVisibility(perms.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE);
            showPerms.run();
        });
        // Sections split by a rule (user request); each command box has its own copy button.
        rule(panel);
        block(panel, new SpannableStringBuilder()
            .append("Breeno 상태: " + (LogSession.breenoDisabled(this) ? "비활성화됨" : "비활성화 필요"),
                new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append("\n켜져 있으면 기본 바가 동작하지 않고,\n전원 버튼에서 Breeno가 함께 열릴 수 있습니다.\n원하는 경우에만 직접 실행하세요."), false);
        codeBlock(panel, "adb shell pm disable-user --user " + user + " com.heytap.speechassist");
        rule(panel);
        // The option name is kept on one line (no-break spaces), so it does not split as "Disable system / optimization".
        block(panel, "명령이 권한 오류로 실패하면\n개발자 옵션에서 'Disable\u00A0system\u00A0optimization'\n(시스템\u00A0최적화\u00A0비활성화)를 켜고 다시 실행하세요.\n"
            + "이 항목은 시스템 언어가 English일 때만\n보일 수 있습니다.", false);
        Button developer = phrase(new Button(builder.getContext()));
        developer.setText("개발자 옵션 열기"); developer.setAllCaps(false);
        developer.setOnClickListener(v -> openDeveloperOptions(true));
        LinearLayout.LayoutParams developerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        developerParams.bottomMargin = dp(4);
        panel.addView(developer, developerParams);
        rule(panel);
        block(panel, "앱이 스스로 권한을 부여하거나 Breeno를 끄지 않습니다.", false);
        ScrollView scroll = new ScrollView(builder.getContext());
        scroll.addView(panel);
        builder.setView(scroll).setNegativeButton("닫기", null).show();
    }

    /** Developer options; when off, switched on first (WRITE_SECURE_SETTINGS) so the page opens instead of being refused. */
    /**
     * toSystemOpt: the ADB guide's button scrolls straight to 'Disable system optimization' (Settings' standard
     * highlight extra with that item's key; device: ColorOS scrolls to it). Without the item (Korean and the flag off)
     * the page simply opens at the top.
     */
    private void openDeveloperOptions(boolean toSystemOpt) {
        boolean wasOff = !Tweaks.developerOptionsOn(this);
        if (wasOff && !Tweaks.enableDeveloperOptions(this)) {
            Toast.makeText(this, "개발자 옵션이 꺼져 있습니다.\n휴대폰 정보에서 버전 번호를\n7번 눌러 직접 켜주세요.", Toast.LENGTH_LONG).show();
            Tweaks.start(this, new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
            return;
        }
        if (wasOff) Toast.makeText(this, "개발자 옵션을 켰습니다.", Toast.LENGTH_SHORT).show();
        Intent developer = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        if (toSystemOpt) developer.putExtra(":settings:fragment_args_key", "safecenter_system_optimization");
        if (getPackageManager().resolveActivity(developer, 0) != null && Tweaks.start(this, developer)) return;
        // Device: after developer options are switched off in Settings, the page itself is disabled (launch result -91,
        // "not resolved") even once the setting is back on. Settings enables it again when one of its own screens
        // opens, so open Settings and follow with the page as soon as it resolves.
        if (!Tweaks.start(this, new Intent(Settings.ACTION_SETTINGS))) {
            Toast.makeText(this, "개발자 옵션 화면을 열 수 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        long until = android.os.SystemClock.uptimeMillis() + DEVELOPER_WAIT;
        Runnable follow = new Runnable() {
            @Override public void run() {
                if (getPackageManager().resolveActivity(developer, 0) != null) { Tweaks.start(SetupActivity.this, developer); return; }
                if (android.os.SystemClock.uptimeMillis() < until) status.postDelayed(this, 250);
                else Toast.makeText(SetupActivity.this, "설정 → 시스템 및 업데이트 →\n개발자 옵션에서 열어주세요.", Toast.LENGTH_LONG).show();
            }
        };
        status.postDelayed(follow, 250);
    }

    private static final long DEVELOPER_WAIT = 5_000;

    /** A thin rule between dialog sections, with the same spacing as the paragraphs. */
    private void rule(LinearLayout parent) {
        android.view.View line = new android.view.View(parent.getContext());
        line.setBackgroundColor(0x40808080);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.topMargin = dp(8); // more air around each rule (user request)
        params.bottomMargin = dp(20);
        parent.addView(line, params);
    }

    /** A command box (block with code=true) with a small "복사" button at its top-right corner. */
    private void codeBlock(LinearLayout parent, String command) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(parent.getContext());
        LinearLayout holder = new LinearLayout(parent.getContext());
        holder.setOrientation(LinearLayout.VERTICAL);
        block(holder, command, true);
        TextView code = (TextView) holder.getChildAt(0);
        code.setPadding(dp(12), dp(10), dp(64), dp(10)); // room for the button
        frame.addView(holder, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView copy = phrase(new TextView(parent.getContext()));
        copy.setText("복사"); copy.setTextSize(13); copy.setTextColor(accent);
        copy.setPadding(dp(10), dp(4), dp(10), dp(4));
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setCornerRadius(dp(12)); fill.setColor((accent & 0x00FFFFFF) | 0x26000000);
        copy.setBackground(fill);
        copy.setOnClickListener(v -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Circle-to-Search Trigger ADB 명령", command));
            Toast.makeText(this, "명령을 복사했습니다.", Toast.LENGTH_SHORT).show();
        });
        android.widget.FrameLayout.LayoutParams at = new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.END);
        at.setMargins(0, dp(6), dp(6), 0);
        frame.addView(copy, at);
        parent.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Dialog paragraph; code=true renders a selectable, wrapping monospace box like a markdown code fence. */
    /** A bold heading line above the body, as the dialogs' "Breeno 상태" block. */
    private static CharSequence heading(String title, String body) {
        return new SpannableStringBuilder()
            .append(title, new StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append("\n").append(body);
    }

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

    /** GPL-3.0 notice, source link and the bundled Apache-2.0 notice (shipped as res/raw, read locally). */
    private void showLicenses() {
        StringBuilder notice = new StringBuilder("이 앱은 GNU General Public License 3.0\n(GPL-3.0-only)으로 배포됩니다.\n"
            + "보증 없이 제공됩니다.\n소스 코드와 라이선스 전문은 아래 저장소에 있습니다.\n"
            + "https://github.com/JngVedere/OPD2515_CST\n\n"
            + "참고한 프로젝트(코드 미포함):\nColorGoogle, MiCTS, MindTrigger Assist (모두 GPL-3.0)\n\n"
            + "포함된 라이브러리:\nAndroidHiddenApiBypass 6.1 (LSPosed, Apache License 2.0)\n\n");
        int raw = getResources().getIdentifier("notice_hidden_api_bypass", "raw", getPackageName());
        if (raw != 0) {
            try (java.io.InputStream in = getResources().openRawResource(raw)) {
                notice.append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException | RuntimeException unreadable) { }
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), 0);
        block(panel, notice, true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        new AlertDialog.Builder(this).setTitle("오픈소스 라이선스").setView(scroll).setPositiveButton("닫기", null).show();
    }

    /**
     * 편의 기능 타일: what each quick settings tile of this app shows (its own manifest icon and name) and does. The app no
     * longer asks to add tiles (user request); they are added from the quick settings edit screen.
     */
    private void showTiles() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("편의 기능 타일");
        LinearLayout panel = new LinearLayout(builder.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), 0);
        block(panel, "빠른 설정을 내린 뒤 편집(연필) 화면의\n'더 많은 타일'에서 아래 타일을 추가할 수 있습니다.", false);
        tileInfo(panel, BarTile.class, "이 앱의 하단 실행을 켜고 끕니다.\n길게 누르면 이 설정 화면이 열립니다.");
        tileInfo(panel, LaunchTile.Search.class, "서클 투 서치를 엽니다.\n잠겨 있으면 잠금을 푼 뒤 열립니다.");
        tileInfo(panel, LaunchTile.Assistant.class, "Gemini Assistant를 엽니다.\n전원 버튼 기능이 꺼져 있어도 동작합니다.\n'잠금 화면에서 Gemini Assistant 호출'을 켜면\n잠금 화면 위에서도 열립니다.");
        tileInfo(panel, DimTile.class, "화면 더 어둡게(Extra Dim)를 켜고 끕니다.\n보안 설정 쓰기 권한이 필요합니다.\n길게 누르면 '화면 더 어둡게' 설정이 열립니다.");
        ScrollView scroll = new ScrollView(builder.getContext());
        scroll.addView(panel);
        builder.setView(scroll).setNegativeButton("닫기", null).show();
    }

    /** One tile row: the tile's manifest icon and label, then what it does. */
    private void tileInfo(LinearLayout panel, Class<?> tile, String description) {
        android.graphics.drawable.Drawable icon = null;
        CharSequence label = tile.getSimpleName();
        try {
            android.content.pm.ServiceInfo info = getPackageManager().getServiceInfo(new ComponentName(this, tile), 0);
            icon = info.loadIcon(getPackageManager());
            label = info.loadLabel(getPackageManager());
        } catch (PackageManager.NameNotFoundException missing) { }
        rule(panel);
        LinearLayout row = new LinearLayout(panel.getContext());
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView image = new android.widget.ImageView(panel.getContext());
        if (icon != null) { icon.mutate().setTint(accent); image.setImageDrawable(icon); }
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconParams.rightMargin = dp(12);
        row.addView(image, iconParams);
        TextView name = phrase(new TextView(panel.getContext()));
        name.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setText(label);
        row.addView(name);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(6);
        panel.addView(row, rowParams);
        block(panel, description, false);
    }

    /** Translucent fills read on both light and dark backgrounds; blue is the preview colour. */
    private void showFacing(boolean active) {
        int color = active ? 0xFF2563EB : 0xFF8A8A8A;
        facing.setTextColor(color);
        facingBox.setColor(active ? 0x262563EB : 0x1A808080);
        facingBox.setStroke(dp(2), color);
    }

    private TextView text(String value, int size) {
        TextView view = phrase(new TextView(this));
        view.setText(value); view.setTextSize(size);
        if (EXPERIMENTAL.equals(section.getTag()) && value.contains("단점:")) { // the risk label stands out (user request)
            SpannableStringBuilder marked = new SpannableStringBuilder(value);
            for (int at = value.indexOf("단점:"); at >= 0; at = value.indexOf("단점:", at + 1))
                marked.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), at, at + 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(marked);
        }
        view.setPadding(0, dp(5), 0, dp(9));
        if (size == 14) { view.setTextColor(secondary); view.setLineSpacing(dp(2), 1f); } // 14sp = description, like the status card detail.
        // Card descriptions follow Detail / Compact; the footer (body) and the experimental box always show theirs.
        if (size == 14 && section != body && !EXPERIMENTAL.equals(section.getTag())) details.add(view);
        section.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    /** Plain rounded card on the gray page, shared by the status card and the settings sections. */
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable shade = new android.graphics.drawable.GradientDrawable();
        shade.setColor(cardColor);
        shade.setCornerRadius(dp(20));
        card.setBackground(shade);
        return card;
    }

    // --- Bottom navigation ------------------------------------------------------------------------------------------

    /** Floating pill of icon-only tabs (user request, like 欢太工具箱); the names stay as content descriptions. */
    private void buildNavBar(boolean settings) {
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(cardColor);
        pill.setCornerRadius(dp(32));
        navBar.setBackground(pill);
        navBar.setElevation(dp(8));
        navBar.setPadding(dp(8), dp(8), dp(8), dp(8)); // equal on all sides: the tabs sit in the pill's middle
        // The pill floats over the page: a touch on its padding or shadow must not reach the button behind it (device:
        // tapping the pill's edge opened 기본 어시스턴트 설정 underneath). The tabs still get their taps first.
        navBar.setClickable(true);
        navBar.setOnTouchListener((v, event) -> true);
        // The well is one view under the tabs, so switching slides it from tab to tab instead of swapping backgrounds.
        navWell = new android.view.View(this);
        navWell.setBackground(engraved());
        navBar.addView(navWell, new android.widget.FrameLayout.LayoutParams(dp(TAB_WIDTH), dp(48)));
        navTabs = new LinearLayout(this);
        navTabs.setOrientation(LinearLayout.HORIZONTAL);
        navBar.addView(navTabs, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        homeTab = navItem("홈", "ic_nav_home", false);
        settingsTab = navItem("설정", "ic_nav_settings", true);
        onSettingsPage = !settings; // so the first switchPage applies everything
        switchPage(settings, false);
    }

    private static final int TAB_WIDTH = 84;

    private android.widget.ImageView navItem(String label, String icon, boolean settings) {
        android.widget.ImageView item = new android.widget.ImageView(this);
        item.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setContentDescription(label);
        int id = getResources().getIdentifier(icon, "drawable", getPackageName()); // no R class in this build
        if (id != 0) item.setImageDrawable(getDrawable(id).mutate());
        item.setOnClickListener(v -> showPage(settings));
        navTabs.addView(item, new LinearLayout.LayoutParams(dp(TAB_WIDTH), dp(48)));
        return item;
    }

    /**
     * The selected tab sits in a recessed well (user request: 음각): a fill a shade darker than the pill and a soft
     * shadow along the top inner edge, as if pressed into it.
     */
    private android.graphics.drawable.Drawable engraved() {
        android.graphics.drawable.GradientDrawable well = new android.graphics.drawable.GradientDrawable();
        well.setColor(night ? 0xFF2C2C2E : 0xFFE4E6EA);
        well.setCornerRadius(dp(24));
        android.graphics.drawable.GradientDrawable shade = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{night ? 0x66000000 : 0x24000000, 0x00000000, 0x00000000});
        shade.setCornerRadius(dp(24));
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{well, shade});
    }

    private boolean onSettingsPage;

    private void showPage(boolean settings) { switchPage(settings, true); }

    /**
     * Shows a page. Animated: the page slides out toward the side it leaves by and the other slides in, while the well
     * glides to the new tab and the icon colors cross-fade (user request: natural transition, also for swipes).
     */
    private void switchPage(boolean settings, boolean animate) {
        if (homeTab == null) return;
        boolean change = settings != onSettingsPage;
        onSettingsPage = settings;
        moveWell(settings, animate);
        if (!change) { springBack(); return; }
        int width = pageScroll.getWidth();
        if (!animate || width == 0) { applyPage(settings); return; }
        float shift = width * 0.25f * (settings ? 1 : -1); // settings is to the right of home
        pageScroll.animate().cancel();
        pageScroll.animate().translationX(-shift).alpha(0f).setDuration(110)
            .setInterpolator(new android.view.animation.AccelerateInterpolator()).withEndAction(() -> {
                applyPage(settings);
                pageScroll.setTranslationX(shift);
                pageScroll.animate().translationX(0).alpha(1f).setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }).start();
    }

    private void applyPage(boolean settings) {
        pageScroll.scrollTo(0, 0);
        homePage.setVisibility(settings ? android.view.View.GONE : android.view.View.VISIBLE);
        settingsPage.setVisibility(settings ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** Glides the well under the selected tab and fades the icon tints (selected: strong, other: gray). */
    private void moveWell(boolean settings, boolean animate) {
        float target = settings ? dp(TAB_WIDTH) : 0;
        navWell.animate().cancel();
        if (animate) navWell.animate().translationX(target).setDuration(260)
            .setInterpolator(new android.view.animation.OvershootInterpolator(0.7f)).start();
        else navWell.setTranslationX(target);
        int strong = night ? 0xFFF2F2F2 : 0xFF1C1C1E;
        for (android.widget.ImageView tab : new android.widget.ImageView[]{homeTab, settingsTab}) {
            boolean selected = (tab == settingsTab) == settings;
            tab.setSelected(selected);
            int to = selected ? strong : secondary;
            android.content.res.ColorStateList now = tab.getImageTintList();
            if (!animate || now == null) { tab.setImageTintList(android.content.res.ColorStateList.valueOf(to)); continue; }
            android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofArgb(now.getDefaultColor(), to);
            fade.setDuration(200);
            fade.addUpdateListener(a -> tab.setImageTintList(android.content.res.ColorStateList.valueOf((int) a.getAnimatedValue())));
            fade.start();
        }
    }

    private void springBack() {
        if (pageScroll.getTranslationX() != 0) pageScroll.animate().translationX(0).alpha(1f).setDuration(180)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    // --- Swipe between the pages ----------------------------------------------------------------------------------
    // Swiping left or right anywhere except on a slider or a switch's toggle moves to the other page (user request).
    // While dragging, the page follows the finger a little and the well slides along; letting go past the threshold
    // finishes the switch, otherwise both spring back.

    private float swipeStartX, swipeStartY;
    private long swipeStartTime;
    private boolean swipeTracking, swiping;

    private boolean swipeEvent(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                swipeStartX = event.getRawX(); swipeStartY = event.getRawY();
                swipeStartTime = event.getEventTime();
                swiping = false;
                swipeTracking = homeTab != null && !dragControlAt(getWindow().getDecorView(), swipeStartX, swipeStartY);
                return false;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!swipeTracking) return false;
                float dx = event.getRawX() - swipeStartX, dy = event.getRawY() - swipeStartY;
                if (!swiping) {
                    // Lighter than before (user request): starts at 16dp and allows a slightly diagonal drag.
                    if (Math.abs(dy) > dp(16) && Math.abs(dy) > Math.abs(dx)) { swipeTracking = false; return false; }
                    if (Math.abs(dx) < dp(16) || Math.abs(dx) < 1.5f * Math.abs(dy)) return false;
                    swiping = true;
                    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
                    cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel); // the row or button under the finger stops being pressed
                    cancel.recycle();
                    pageScroll.animate().cancel(); navWell.animate().cancel();
                }
                boolean toward = onSettingsPage ? dx > 0 : dx < 0; // only one other page in each direction
                pageScroll.setTranslationX(dx * (toward ? 0.5f : 0.1f));
                float progress = toward ? Math.min(1f, Math.abs(dx) / (pageScroll.getWidth() * 0.35f)) : 0f;
                float from = onSettingsPage ? dp(TAB_WIDTH) : 0, to = onSettingsPage ? 0 : dp(TAB_WIDTH);
                navWell.setTranslationX(from + (to - from) * progress);
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                boolean was = swiping;
                swiping = false; swipeTracking = false;
                if (!was) return false;
                float dx = event.getRawX() - swipeStartX;
                boolean toward = onSettingsPage ? dx > 0 : dx < 0;
                // Far enough (64dp or 15% of the width), or a quick flick (over 0.5dp per ms, at least 32dp).
                long took = Math.max(1, event.getEventTime() - swipeStartTime);
                boolean flick = Math.abs(dx) > dp(32) && Math.abs(dx) / took > dp(1) * 0.5f;
                boolean go = event.getActionMasked() == android.view.MotionEvent.ACTION_UP && toward
                    && (flick || Math.abs(dx) > Math.min(dp(64), pageScroll.getWidth() * 0.15f));
                switchPage(go != onSettingsPage, true); // not far enough: the same page again, which springs back
                return true;
            }
            default:
                return swiping;
        }
    }

    /** A slider anywhere, or the toggle end of a switch row: horizontal drags there belong to the control. */
    private boolean dragControlAt(android.view.View view, float x, float y) {
        if (view.getVisibility() != android.view.View.VISIBLE) return false;
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        if (x < at[0] || y < at[1] || x >= at[0] + view.getWidth() || y >= at[1] + view.getHeight()) return false;
        if (view instanceof android.widget.AbsSeekBar) return true;
        if (view instanceof Switch) return x >= at[0] + view.getWidth() - dp(88);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) if (dragControlAt(group.getChildAt(i), x, y)) return true;
        }
        return false;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(TAB_SETTINGS, onSettingsPage);
    }

    // --- 고급 → 편의 기능 (Tweaks) -------------------------------------------------------------------------------------

    private void buildTweaks() {
        TextView title = text("편의 기능", 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(2));
        // The tile guide sits beside the heading as a "TIP" (user request) instead of its own row.
        section.removeView(title);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        heading.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView tip = phrase(new TextView(this));
        tip.setText("TIP"); tip.setTextSize(12); tip.setTextColor(accent);
        tip.setTypeface(null, android.graphics.Typeface.BOLD);
        tip.setPadding(dp(8), dp(2), dp(8), dp(2));
        android.graphics.drawable.GradientDrawable tipPill = new android.graphics.drawable.GradientDrawable();
        tipPill.setCornerRadius(dp(10)); tipPill.setColor((accent & 0x00FFFFFF) | 0x26000000); // accent at 15%
        tip.setBackground(tipPill);
        LinearLayout.LayoutParams tipGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipGap.leftMargin = dp(10); tipGap.topMargin = dp(6); // the title has 8dp top padding
        heading.addView(tip, tipGap);
        heading.setMinimumHeight(dp(44));
        heading.setOnClickListener(v -> showTiles());
        heading.setContentDescription("편의 기능 타일 안내");
        section.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        text("시스템 설정 일부를 바로 바꾸거나 숨겨진 설정 화면을 엽니다.\n켠 기능은 끄면 원래대로 돌아갑니다.\n보안 설정 쓰기 권한(최초 ADB 설정)이 필요합니다.", 14);
        divider(section);
        // First item of 편의 기능 (user request; it used to sit in 추가 설정 on the home page).
        bootSwitch = toggle("재부팅 후 자동 복구", BootRecovery.active(this), (view, checked) -> {
            if (syncing) return;
            if (checked && !BootRecovery.permitted(this)) {
                ((Switch) view).setChecked(false);
                Toast.makeText(this, "권한이 필요합니다.\n홈 → 추가 안내의\n최초 ADB 설정 안내를 확인해주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            preferences.edit().putBoolean(BootRecovery.OPTION, checked).apply();
        });
        text("직접 켜 두었던 이 앱의 접근성이 재부팅 후 꺼져 있으면 다시 켭니다.\n"
            + "보안 설정 쓰기 권한이 필요합니다.", 14);
        toggle("자동 회전 제안 끄기", !Tweaks.secureOn(this, Tweaks.ROTATION_SUGGESTIONS, true),
            (view, checked) -> tweakSecure(view, Tweaks.ROTATION_SUGGESTIONS, !checked, checked));
        text("화면을 돌렸을 때 모서리에 뜨는 회전 버튼을 숨깁니다.", 14);
        toggle("시계에 초 표시", Tweaks.secureOn(this, Tweaks.CLOCK_SECONDS, false),
            (view, checked) -> tweakSecure(view, Tweaks.CLOCK_SECONDS, checked, checked));
        actionRow("플래시 알림", () -> openPage("com.android.settings.Settings$FlashNotificationsActivity"));
        // A row into the system page (user request): it has the switch and the intensity, so the app writes nothing.
        actionRow("화면 더 어둡게", () -> openPage("com.android.settings.Settings$ReduceBrightColorsSettingsActivity"));
        actionRow("애니메이션 배율", this::animationScaleDialog);
        divider(section);
        // Notification access list (Android's "기기 및 앱 알림"); a public action, so no class name is needed.
        actionRow("기기 및 앱 알림", () -> {
            if (!Tweaks.start(this, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                Toast.makeText(this, "이 기기에서는 이 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
        });
        actionRow("앱 배터리 사용량 설정", () -> openPage("com.android.settings.Settings$AppBatteryUsageActivity"));
        actionRow("기기 이름 변경", false, "네트워크 연결 없이 기기 이름을 변경합니다.\n검열을 우회하여 기기 이름 설정이 가능합니다.", this::renameDevice);
        text("온라인 인증 없이 기기 이름을 바꿉니다.\n블루투스와 핫스팟 이름도 함께 바뀝니다.", 14);
        actionRow("시스템 UI 튜너", this::openTuner);
        actionRow("전역 최대 주사율 설정", this::refreshRateDialog);
        actionRow("개발자 옵션 빠른 접근", () -> openDeveloperOptions(false));
        // Sub-item of 개발자 옵션 빠른 접근 (user request): indented and a size smaller.
        Switch systemOpt = toggle("시스템 최적화 비활성화 항목 표시", Tweaks.secureOn(this, Tweaks.SYSTEM_OPT, false),
            (view, checked) -> tweakSecure(view, Tweaks.SYSTEM_OPT, checked, checked));
        systemOpt.setTextSize(16);
        // A quiet gray switch (user request: not eye-catching) instead of the accent color.
        int[][] states = {{android.R.attr.state_checked}, {}};
        systemOpt.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{secondary, night ? 0xFF8E8E93 : 0xFFF4F4F4}));
        systemOpt.setTrackTintList(new android.content.res.ColorStateList(states,
            new int[]{(secondary & 0x00FFFFFF) | 0x66000000, (secondary & 0x00FFFFFF) | 0x33000000}));
        ((LinearLayout.LayoutParams) systemOpt.getLayoutParams()).leftMargin = dp(20);
        TextView systemOptInfo = text("개발자 옵션의 'Disable system optimization'은\n"
            + "원래 시스템 언어가 English일 때만 보입니다.\n"
            + "켜면 한국어에서도 보입니다.", 14);
        ((LinearLayout.LayoutParams) systemOptInfo.getLayoutParams()).leftMargin = dp(20);
        actionRow("앱 설치 프로그램 광고 제거", true, this::installerAds);
        text("설치 프로그램 광고 제거는 앱 권한으로 바꿀 수 없어\n필요한 ADB 명령을 안내합니다.", 14);
    }

    /** 전역 최대 주사율 설정: ColorOS's own 화면 주사율 choice, written with 보안 설정 쓰기 (device: no ADB needed). */
    private void refreshRateDialog() {
        int current = Tweaks.maxRefreshRate(this);
        String[] labels = new String[Tweaks.REFRESH_RATES.length];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            int hz = Tweaks.REFRESH_RATES[i];
            labels[i] = hz == 0 ? "자동 (ColorOS가 화면에 맞춰 조절)" : hz + "Hz" + (hz == 144 ? " (게임 등 지원 앱에서만 144Hz)" : "");
            if (hz == current) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("전역 최대 주사율 설정")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                int hz = Tweaks.REFRESH_RATES[which];
                Toast.makeText(this, Tweaks.setMaxRefreshRate(this, hz)
                    ? (hz == 0 ? "주사율을 자동으로 설정했습니다." : "최대 주사율을 " + hz + "Hz로 설정했습니다.")
                    : "보안 설정 쓰기 권한이 필요합니다.\n최초 ADB 설정 안내를 확인해주세요.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            })
            .setNegativeButton("닫기", null).show();
    }

    private void actionRow(String label, Runnable action) { actionRow(label, false, action); }

    private void actionRow(String label, boolean adb, Runnable action) { actionRow(label, adb, null, action); }

    /**
     * A tappable row: label on the left, a chevron on the right; adb marks a row that needs a computer (ADB), info adds
     * a small (i) beside the label that shows the text.
     */
    private void actionRow(String label, boolean adb, String info, Runnable action) {
        TextView row = phrase(new TextView(this));
        row.setText(label); row.setTextSize(18);
        row.setTextColor(primary); // same as the switch labels
        row.setMinHeight(dp(56));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView chevron = phrase(new TextView(this));
        chevron.setText("›"); chevron.setTextSize(22); chevron.setTextColor(secondary);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (info == null) line.addView(row, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        else {
            line.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            line.addView(infoMark(label, info));
            line.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        if (adb) {
            TextView badge = phrase(new TextView(this));
            badge.setText("ADB 필요"); badge.setTextSize(12); badge.setTextColor(accent);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            pill.setCornerRadius(dp(10)); pill.setStroke(Math.max(1, dp(1)), accent);
            badge.setBackground(pill);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.rightMargin = dp(10);
            gap.topMargin = dp(2); // optical alignment with the chevron, which sits low in its glyph box
            line.addView(badge, gap);
        }
        line.addView(chevron);
        line.setOnClickListener(v -> action.run());
        section.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A small (i) beside a label; tapping it (or hovering a stylus) shows the info as a bubble. 40dp touch target. */
    private android.view.View infoMark(String label, String info) {
        TextView mark = phrase(new TextView(this));
        mark.setText("i"); mark.setTextSize(12); mark.setTextColor(secondary);
        mark.setTypeface(null, android.graphics.Typeface.BOLD);
        mark.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL); ring.setStroke(Math.max(1, dp(1)), secondary);
        mark.setBackground(ring);
        android.widget.FrameLayout hit = new android.widget.FrameLayout(this);
        hit.setPadding(dp(10), dp(10), dp(10), dp(10));
        hit.addView(mark, new android.widget.FrameLayout.LayoutParams(dp(20), dp(20)));
        hit.setContentDescription(label + " 정보");
        hit.setTooltipText(info); // stylus / mouse hover shows it natively
        hit.setTag(info);
        hit.setOnClickListener(this::showTip);
        return hit;
    }

    /** A switch row with an (i) right after the label. The label still toggles the switch. */
    private Switch toggle(String label, boolean checked, CompoundButton.OnCheckedChangeListener listener, String info) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        TextView name = phrase(new TextView(this));
        name.setText(label); name.setTextSize(18);
        Switch view = phrase(new Switch(this));
        view.setChecked(checked);
        view.setOnCheckedChangeListener(listener);
        name.setTextColor(view.getTextColors());
        name.setOnClickListener(v -> view.toggle());
        row.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(infoMark(label, info));
        row.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(view);
        section.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    /**
     * Hover-style bubble under an (i) mark (user request: no dialog to confirm). Touch has no hover, so a tap shows it;
     * a tap anywhere else (still delivered there) closes it. No timeout (user request).
     */
    private void showTip(android.view.View anchor) { showTip(anchor, (String) anchor.getTag(), 0xF0); }

    /** alpha: the bubble's background opacity (0xF0 for the (i) bubbles, a bit more see-through for the mode notice). */
    private android.widget.PopupWindow showTip(android.view.View anchor, String text, int alpha) {
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        TextView tip = phrase(new TextView(this));
        tip.setText(text); tip.setTextSize(14);
        tip.setTextColor(night ? 0xFF1C1B1F : 0xFFFFFFFF);
        tip.setMaxWidth(dp(320));
        tip.setPadding(dp(12), dp(8), dp(12), dp(8));
        android.graphics.drawable.GradientDrawable bubble = new android.graphics.drawable.GradientDrawable();
        bubble.setCornerRadius(dp(8)); bubble.setColor((alpha << 24) | (night ? 0xE6E1E5 : 0x313033));
        tip.setBackground(bubble);
        android.widget.PopupWindow popup = new android.widget.PopupWindow(tip,
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(4));
        // The (i) marks have empty padding below them, so their bubble tucks up; others sit just under the anchor.
        popup.showAsDropDown(anchor, 0, alpha == 0xF0 ? -dp(6) : dp(6));
        tip.setOnClickListener(v -> popup.dismiss());
        return popup;
    }

    private void tweakSecure(android.view.View view, String key, boolean value, boolean checked) {
        if (syncing) return;
        if (!Tweaks.setSecure(this, key, value)) {
            setSynced((Switch) view, !checked);
            // Without WRITE_SECURE_SETTINGS the clock can still change: SystemUI's own Tuner writes clock_seconds itself
            // (device: 상태 표시줄 → 시간 switched it 0/1), and the Tuner opens without ADB.
            if (Tweaks.CLOCK_SECONDS.equals(key)) {
                showPhrased(new AlertDialog.Builder(this).setTitle("시계에 초 표시")
                    .setMessage("보안 설정 쓰기 권한이 없어 앱이 직접 바꿀 수 없습니다.\n\n"
                        + "대신 시스템 UI 튜너에서 바꿀 수 있습니다.\n상태 표시줄 → 시간 → '"
                        + (value ? "시간, 분, 초 표시" : "시간, 분 표시(기본값)") + "'를 골라주세요.")
                    .setPositiveButton("시스템 UI 튜너 열기", (dialog, which) -> openTuner())
                    .setNegativeButton("닫기", null));
                return;
            }
            Toast.makeText(this, "보안 설정 쓰기 권한이 필요합니다.\n최초 ADB 설정 안내를 확인해주세요.", Toast.LENGTH_LONG).show();
        }
    }

    private void openPage(String className) {
        if (!Tweaks.openSettingsPage(this, className))
            Toast.makeText(this, "이 기기에서는 이 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
    }

    private void renameDevice() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(Tweaks.deviceName(this));
        input.setSelection(input.getText().length());
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showPhrased(new AlertDialog.Builder(this).setTitle("기기 이름 변경").setView(box)
            .setPositiveButton("변경", (dialog, which) -> Toast.makeText(this,
                Tweaks.setDeviceName(this, input.getText().toString()) ? "기기 이름을 바꿨습니다."
                    : "기기 이름을 바꾸지 못했습니다.\n이름이 비어 있지 않은지,\n보안 설정 쓰기 권한이 있는지 확인해주세요.",
                Toast.LENGTH_LONG).show())
            .setNegativeButton("취소", null));
    }

    private void openTuner() {
        // Device (ColorOS 17): TunerActivity is disabled, but SystemUI's exported alias .DemoMode (no permission, enabled)
        // opens the same screen; 欢太工具箱 uses it. The real activity, then the ADB command, only as fallbacks.
        if (Tweaks.start(this, new Intent().setComponent(Tweaks.TUNER_ALIAS))) return;
        if (Tweaks.componentEnabled(this, Tweaks.TUNER) && Tweaks.start(this, new Intent().setComponent(Tweaks.TUNER))) return;
        adbDialog("시스템 UI 튜너",
            "시스템 UI 튜너 화면은 기본으로 숨겨져 있고,\n앱 권한으로는 켤 수 없습니다.\n컴퓨터에서 아래 명령을 한 번 실행한 뒤\n다시 눌러주세요.",
            "adb shell pm enable com.android.systemui/.tuner.TunerActivity");
    }

    private void installerAds() {
        boolean frozen = Tweaks.packageFrozen(this, Tweaks.INSTALLER_PAGE);
        adbDialog("앱 설치 프로그램 광고 제거",
            (frozen ? "현재 상태: 광고 제거됨\n(광고 페이지가 동결되어 있습니다)\n\n" : "현재 상태: 광고 표시됨\n(광고 페이지가 켜져 있습니다)\n\n")
                + "OPPO 설치 프로그램의 추천·광고 페이지\n(com.oplus.appdetail)를 동결합니다.\n시스템 앱을 동결하므로 위험할 수 있습니다.\n앱 권한으로는 바꿀 수 없어 컴퓨터에서 실행해야 합니다.",
            frozen ? "원래대로 되돌리기:" : "동결하기:",
            frozen ? "adb shell pm enable com.oplus.appdetail" : "adb shell pm disable-user --user 0 com.oplus.appdetail");
    }

    /** Text paragraphs and commands in order; a part starting with "adb " is a command box with its own copy button. */
    private void adbDialog(String title, String... parts) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title);
        LinearLayout panel = new LinearLayout(builder.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), 0);
        for (String part : parts) {
            if (part.startsWith("adb ")) codeBlock(panel, part);
            else block(panel, part, false);
        }
        ScrollView scroll = new ScrollView(builder.getContext());
        scroll.addView(panel);
        builder.setView(scroll).setNegativeButton("닫기", null).show();
    }

    /**
     * A red-outlined box for one experimental option, added to parent with its warning header; the option's switch and
     * text go in afterwards. Its descriptions stay visible in Compact (the risk is read before switching on).
     */
    private LinearLayout experimentalBox(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(EXPERIMENTAL);
        android.graphics.drawable.GradientDrawable danger = new android.graphics.drawable.GradientDrawable();
        danger.setColor(0x14D93025); // Faint red fill reads on both light and dark backgrounds.
        danger.setStroke(dp(2), ERROR_COLOR);
        danger.setCornerRadius(dp(10));
        box.setBackground(danger);
        box.setPadding(dp(14), 0, dp(14), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4); params.bottomMargin = dp(8);
        parent.addView(box, params);
        LinearLayout outer = section;
        section = box;
        TextView title = text("⚠ 실험적 기능", 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ERROR_COLOR);
        title.setPadding(0, dp(12), 0, dp(2));
        text("시스템 설정을 바꾸는 위험할 수 있는 기능입니다.\n주의사항을 끝까지 읽은 뒤에만 켜주세요.", 14);
        divider(section);
        section = outer;
        return box;
    }

    /**
     * Opens a new settings card under a small gray label (ColorOS style: the group name sits above the card); following
     * text/toggle/button calls land inside the card. The label's top padding is the gap between cards.
     */
    private void section(String label) {
        TextView sectionLabel = phrase(new TextView(this));
        sectionLabel.setText(label); sectionLabel.setTextSize(14); sectionLabel.setTextColor(secondary);
        sectionLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        sectionLabel.setPadding(dp(12), dp(30), dp(12), dp(8));
        body.addView(sectionLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        section = card();
        section.setPadding(dp(18), dp(6), dp(18), dp(10));
        body.addView(section, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        boolean approval = head.endsWith("승인 필요"); // "승인 필요", "기기 로그 승인 필요": red headline (user request)
        SpannableStringBuilder text = new SpannableStringBuilder();
        span(text, label + "\n", new AbsoluteSizeSpan(13, true), new ForegroundColorSpan(secondary));
        span(text, head, new AbsoluteSizeSpan(18, true), new StyleSpan(android.graphics.Typeface.BOLD), error || approval ? new ForegroundColorSpan(ERROR_COLOR) : null);
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

    /** A switch row with a small tonal button between the label and the switch. The label still toggles the switch. */
    private Switch toggle(String label, boolean checked, CompoundButton.OnCheckedChangeListener listener, String actionLabel, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        TextView name = phrase(new TextView(this));
        name.setText(label); name.setTextSize(18);
        Switch view = phrase(new Switch(this));
        view.setChecked(checked);
        view.setOnCheckedChangeListener(listener);
        name.setTextColor(view.getTextColors()); // same color as the other switch labels
        name.setOnClickListener(v -> view.toggle());
        TextView link = phrase(new TextView(this));
        link.setText(actionLabel); link.setTextSize(14); link.setTextColor(accent);
        link.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        link.setPadding(dp(14), dp(6), dp(14), dp(6));
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setCornerRadius(dp(16)); fill.setColor((accent & 0x00FFFFFF) | 0x26000000); // accent at 15%
        link.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x40000000), fill, null));
        linkParams.rightMargin = dp(10);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(link, linkParams);
        row.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        section.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    /** A flat rounded button: card-colored on the page, a light fill inside a card. No raised shadow. */
    private void button(String label, Runnable action) {
        Button view = phrase(new Button(this));
        view.setText(label); view.setAllCaps(false); view.setTextSize(16);
        view.setTextColor(primary);
        view.setStateListAnimator(null);
        view.setMinHeight(dp(52));
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setCornerRadius(dp(16));
        fill.setColor(section == body ? cardColor : fieldColor);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22808080), fill, null));
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(5); params.bottomMargin = dp(5);
        section.addView(view, params);
    }

    /** Display orientation (not this window's: split screen or a floating window must not pick the other value set). */
    private boolean landscape() {
        android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
        return bounds.width() > bounds.height();
    }

    /** Whole-display size in dp, the same bounds BarService lays the touch area out against. */
    private int[] screenDp() {
        android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
        float density = getResources().getDisplayMetrics().density;
        return new int[]{Math.round(bounds.width() / density), Math.round(bounds.height() / density)};
    }

    /**
     * One area value changed: only that field is stored (clamped against the others), so untouched fields keep following
     * their defaults (e.g. the taskbar width). Reads clamp all four together; applied live.
     */
    private void saveArea(int index, int value) {
        boolean landscape = landscape();
        int[] screen = screenDp();
        int[] area = BarService.area(this, landscape, screen[0], screen[1]);
        area[index] = value;
        area = TouchArea.area(area[0], area[1], area[2], area[3], screen[0], screen[1]);
        preferences.edit().putInt(TouchArea.key(landscape, TouchArea.FIELDS[index]), area[index]).apply();
        area = BarService.area(this, landscape, screen[0], screen[1]);
        for (int i = 0; i < 4; i++) areaSliders[i].show(area[i]); // the dragged one too: it shows the effective (clamped) value
        BarService.refreshArea();
    }

    /** Programmatic slider positions from stored values (never written back: fromUser is false). */
    private void showTuning() {
        int[] screen = screenDp();
        int[] area = BarService.area(this, landscape(), screen[0], screen[1]);
        for (int i = 0; i < 4; i++) areaSliders[i].show(area[i]);
        holdSlider.show(BarService.holdMs(this));
        settleSlider.show(TouchArea.settle(preferences.getInt(TouchArea.SETTLE_KEY, TouchArea.SETTLE)));
    }

    private static final class Slider {
        final SeekBar bar; final TextView label; final String name, unit; final int min, max, step;
        final boolean preview;
        final java.util.function.IntConsumer changed;
        /** Range a typed value must be in right now ({low, high}); the horizontal position narrows it to the current width. */
        java.util.function.Supplier<int[]> range;
        Slider(SeekBar bar, TextView label, String name, String unit, int min, int max, int step, boolean preview,
                java.util.function.IntConsumer changed) {
            this.bar = bar; this.label = label; this.name = name; this.unit = unit; this.min = min; this.max = max; this.step = step;
            this.preview = preview; this.changed = changed;
            range = () -> new int[]{min, max};
        }
        /** The last position is exactly max even when max - min is not a multiple of step. */
        int value(int progress) { return Math.min(max, min + progress * step); }
        void show(int value) {
            bar.setProgress(Math.round((value - min) / (float) step));
            label.setText(name + " · " + value + unit);
            label.setContentDescription(name + " " + value + unit + ", 눌러서 숫자로 입력");
        }
        void setEnabled(boolean enabled) { bar.setEnabled(enabled); label.setEnabled(enabled); }
    }

    /** Labelled slider; touching it shows the touch area in blue until released. */
    private Slider slider(String name, String unit, int min, int max, int step, boolean preview, java.util.function.IntConsumer changed) {
        TextView label = phrase(new TextView(this));
        label.setTextSize(16);
        if (primary != null) label.setTextColor(primary);
        label.setPadding(0, dp(10), 0, 0);
        section.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar bar = new SeekBar(this);
        bar.setMax(Math.max(1, (max - min + step - 1) / step));
        bar.setMinHeight(dp(40));
        section.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Slider slider = new Slider(bar, label, name, unit, min, max, step, preview, changed);
        // Tap the value to type it. A disabled label (native mode) receives no clicks.
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        label.setBackgroundResource(ripple.resourceId);
        label.setOnClickListener(v -> typeValue(slider));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = slider.value(progress);
                label.setText(name + " · " + value + unit);
                if (fromUser) changed.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { if (preview) BarService.showPreview(true); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { if (preview) BarService.showPreview(areaSwitch.isChecked()); }
        });
        return slider;
    }

    /**
     * Number entry for one slider. Input is local only and never logged. ASCII digits (and '-' only where negative values
     * exist) through the key listener and an input filter (covers paste), at most TouchArea.MAX_TYPED characters, no
     * autofill or IME learning. The text is parsed by TouchArea.typed and range-checked before anything is stored; stored
     * values still go through the same clamps as the slider.
     */
    private void typeValue(Slider slider) {
        int[] range = slider.range.get();
        boolean signed = range[0] < 0;
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | (signed ? android.text.InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        android.text.method.DigitsKeyListener digits = android.text.method.DigitsKeyListener.getInstance(java.util.Locale.ROOT, signed, false);
        input.setKeyListener(digits);
        input.setFilters(new android.text.InputFilter[]{digits, new android.text.InputFilter.LengthFilter(TouchArea.MAX_TYPED)});
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setSingleLine(true);
        input.setSaveEnabled(false);
        String current = slider.label.getText().toString();
        String shown = current.substring(current.lastIndexOf(' ') + 1).replace(slider.unit, "");
        if (TouchArea.typed(shown, signed) != null) input.setText(shown);
        input.setSelectAllOnFocus(true);
        input.setHint(range[0] + " ~ " + range[1]);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), 0);
        TextView help = phrase(new TextView(this));
        help.setText(range[0] + "~" + range[1] + slider.unit + " 사이의 숫자를 입력하세요." + (signed ? " 왼쪽은 음수(-)입니다." : ""));
        panel.addView(help);
        panel.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(slider.name).setView(panel)
            .setPositiveButton("저장", null).setNegativeButton("취소", null).create();
        if (slider.preview) {
            BarService.showPreview(true);
            dialog.setOnDismissListener(d -> BarService.showPreview(areaSwitch.isChecked()));
        }
        Runnable save = () -> {
            Integer value = TouchArea.typed(input.getText().toString(), signed);
            int[] now = slider.range.get(); // re-read: the screen may have rotated while the dialog was open
            if (value == null) { input.setError("숫자만 입력해주세요."); return; }
            if (value < now[0] || value > now[1]) { input.setError(now[0] + "~" + now[1] + slider.unit + " 사이로 입력해주세요."); return; }
            slider.changed.accept(value);
            slider.show(value);
            showTuning(); // every slider shows what was actually stored
            dialog.dismiss();
        };
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            save.run();
            return true;
        });
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save.run()); // stays open on an error
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
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

    /** 일부 앱에서 하단 영역 인식 강제 사용: the app list is its own screen (TouchAppsActivity). */
    private void chooseTouchApps() { startActivity(new Intent(this, TouchAppsActivity.class)); }

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
        block(panel, "ColorOS에는 전원 버튼 두 번 누르기 기능이 없습니다.\n그래서 이 앱이 시스템 전원 버튼 설정 두 개를 바꿔 만듭니다."
            + "\n\n• 한 번 누르기\n  시스템 동작을 '아무것도 안 함'으로 바꾸고,\n  이 앱이 접근성으로 화면을 대신 끕니다."
            + "\n• 두 번 누르기\n  시스템이 두 번째 누름을 기다리게 하고,\n  기기 로그로 감지해 선택한 앱을 엽니다.", false);
        block(panel, "알아둘 점"
            + "\n\n1. 두 번째 누름을 기다리느라\n   화면이 꺼지기까지 약 0.3초 늦어집니다."
            + "\n\n2. 기기 로그 연결이 끊기거나 접근성이 꺼지면\n   앱이 원래 설정으로 자동 복구합니다."
            + "\n\n3. 앱이 갑자기 종료되면\n   (최근 앱 '모두 닫기', 메모리 정리)\n   다시 실행될 때까지 몇 초 동안\n   전원 버튼으로 화면이 꺼지지 않을 수 있습니다.\n   최근 앱에서 이 앱 카드를 잠가 두세요."
            + "\n\n4. 특히 '강제 종료'를 하면 접근성이 꺼진 채\n   앱이 다시 실행되지 않습니다.\n   그동안 전원 버튼으로 화면이 꺼지지 않습니다.\n   (화면 자동 꺼짐은 동작합니다)\n   이 설정 화면을 열거나, 빠른 설정을 내려\n   타일이 보이게 하면 바로 원래대로 돌아갑니다."
            + "\n\n5. 앱은 잠금이 풀린 화면에서만 엽니다.\n   꺼진 화면이나 잠금 화면에서 두 번 누르면\n   화면만 켜진 채로 있습니다."
            + "\n\n6. 화면 켜기와 길게 눌러 Gemini Assistant 열기는\n   그대로입니다."
            + "\n\n7. 재부팅하면 첫 잠금 해제 전에 원래 설정으로 돌아가고,\n   기기 로그를 승인하면 다시 적용됩니다.\n   자동 실행이 꺼져 있거나 강제 종료 뒤라면\n   이 복구가 동작하지 않을 수 있습니다."
            + "\n\n8. 실행할 앱이 '아무것도 하지 않기'이면\n   설정을 바꾸지 않아 전원 버튼은 원래대로 동작합니다.", false);
        block(panel, new SpannableStringBuilder()
            .append("앱을 삭제하기 전에는 반드시 이 기능을 먼저 끄세요.", new StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append("\n켠 채로 삭제하거나 앱이 계속 실행되지 못하면\n전원 버튼으로 화면이 꺼지지 않는 상태가 남을 수 있습니다.\n그때는 컴퓨터에서 아래 명령으로 되돌리세요."), false);
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
            Toast.makeText(this, "보안 설정 쓰기 권한이 필요합니다.\n최초 ADB 설정 안내를 확인해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!VoiceRepair.activationWanted(this, true)) {
            Toast.makeText(this, "Google이 음성 서비스로 선택되어 있지 않아\n변경하지 않았습니다.\n기본 어시스턴트 설정을 확인해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (VoiceRepair.busy()) {
            Toast.makeText(this, "이미 Google 음성 연결을\n다시 시작하는 중입니다.", Toast.LENGTH_SHORT).show();
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
                ? "Google 음성 연결을 다시 시작했습니다.\n잠시 후 전원 버튼을 길게 눌러 확인해주세요."
                : "Google 음성 연결을 다시 시작하지 못했습니다.\n기본 어시스턴트 설정에서\nGoogle 선택을 확인해주세요.", Toast.LENGTH_LONG).show());
        }, "PadVoiceRepair").start();
    }

    /** Experimental overlay mode on/off. The accessibility entry follows when WRITE_SECURE_SETTINGS allows it. */
    /**
     * 애니메이션 배율 (user request, like 欢太工具箱's 动画速度): one slider in 0.01x steps from 0 (off) to 2x for the window,
     * transition and animator scales together, applied when the finger lifts. Tapping the value above it takes a typed
     * value from 0 to 10x (user request: the number itself is the input, like the touch-area values).
     */
    private void animationScaleDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("애니메이션 배율");
        android.content.Context ui = builder.getContext();
        LinearLayout panel = new LinearLayout(ui);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), 0);
        TextView value = new TextView(ui);
        value.setTextSize(28); value.setTypeface(null, android.graphics.Typeface.BOLD);
        value.setGravity(android.view.Gravity.CENTER);
        value.setContentDescription("애니메이션 배율 직접 입력");
        panel.addView(value);
        SeekBar bar = new SeekBar(ui);
        bar.setMax(ANIMATION_STEPS);
        float[] shown = {Tweaks.animationScale(this)};
        Runnable refresh = () -> {
            value.setText(shown[0] == 0 ? "애니메이션 없음" : String.format(java.util.Locale.ROOT, "%.2fx", shown[0]));
            bar.setProgress(Math.round(Math.min(ANIMATION_MAX_SLIDER, shown[0]) * 100));
        };
        refresh.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int step, boolean fromUser) {
                if (!fromUser) return;
                shown[0] = step / 100f;
                value.setText(step == 0 ? "애니메이션 없음" : String.format(java.util.Locale.ROOT, "%.2fx", shown[0]));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { applyAnimationScale(shown[0]); }
        });
        value.setOnClickListener(v -> typeAnimationScale(shown[0], scale -> {
            shown[0] = scale;
            refresh.run();
            applyAnimationScale(scale);
        }));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(8); barParams.bottomMargin = dp(12);
        panel.addView(bar, barParams);
        block(panel, "0.01 단위로 조절합니다.\n"
            + "숫자를 누르면 0.00~10.00 사이로 직접 입력할 수 있습니다.\n"
            + "창·전환 애니메이션과 애니메이터 길이를 함께 바꿉니다.\n"
            + "작을수록 빠릅니다. 기본값은 1x입니다.", false);
        AlertDialog dialog = builder.setView(panel).setNegativeButton("닫기", null)
            .setNeutralButton("기본값 (1x)", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            shown[0] = 1f;
            refresh.run();
            applyAnimationScale(1f);
        }));
        dialog.show();
    }

    /** Number entry for the animation scale: 0.00 to 10.00, parsed and range-checked by TouchArea.typedScale. */
    private void typeAnimationScale(float current, java.util.function.Consumer<Float> done) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        android.text.method.DigitsKeyListener digits = android.text.method.DigitsKeyListener.getInstance(java.util.Locale.ROOT, false, true);
        input.setKeyListener(digits);
        input.setFilters(new android.text.InputFilter[]{digits, new android.text.InputFilter.LengthFilter(5)});
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setSingleLine(true); input.setSaveEnabled(false);
        input.setText(String.format(java.util.Locale.ROOT, "%.2f", current));
        input.setSelectAllOnFocus(true);
        input.setHint("0.00 ~ 10.00");
        // No underline (user request): a soft rounded box instead.
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setColor(fieldColor); box.setCornerRadius(dp(12));
        input.setBackground(box);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), 0);
        TextView help = phrase(new TextView(this));
        help.setText("0.00~10.00 사이의 숫자를 입력하세요.");
        panel.addView(help);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(10);
        panel.addView(input, inputParams);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("애니메이션 배율 입력").setView(panel)
            .setPositiveButton("적용", null).setNegativeButton("취소", null).create();
        Runnable apply = () -> {
            Integer hundredths = TouchArea.typedScale(input.getText().toString());
            if (hundredths == null) {
                input.setError("0.00~10.00 사이의 숫자를 입력하세요.");
                return;
            }
            done.accept(hundredths / 100f);
            dialog.dismiss();
        };
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> apply.run()));
        input.setOnEditorActionListener((v, action, event) -> { apply.run(); return true; });
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        input.requestFocus();
    }

    private static final float ANIMATION_MAX_SLIDER = 2f;
    private static final int ANIMATION_STEPS = 200; // 0x to 2x in 0.01x steps

    private void applyAnimationScale(float scale) {
        if (!Tweaks.setAnimationScale(this, scale))
            Toast.makeText(this, "보안 설정 쓰기 권한이 필요합니다.\n최초 ADB 설정 안내를 확인해주세요.", Toast.LENGTH_LONG).show();
    }

    private void resetPrompt() {
        showPhrased(new AlertDialog.Builder(this).setTitle("모든 설정 초기화")
            .setMessage("이 앱의 설정을 모두 처음 상태로 되돌립니다.\n"
                + "실행 방식, 터치 영역, 전원 버튼, 알림,\n"
                + "고급 설정이 모두 기본값으로 바뀝니다.\n\n"
                + "ADB로 준 권한과 기기 로그 승인은 그대로입니다.")
            .setPositiveButton("초기화", (dialog, which) -> resetSettings())
            .setNegativeButton("취소", null));
    }

    /**
     * 모든 설정 초기화: every option back to its default. Kept: permissions (an app cannot revoke its own ADB grants),
     * the 사용 시작/끄기 state, launch records, log approval counters, update bookkeeping and the user's confirmations
     * (recents lock, auto-start), which are state rather than settings.
     */
    private void resetSettings() {
        if (BarService.overlayMode(this)) setOverlayMode(false); // back under accessibility first
        SharedPreferences.Editor edit = preferences.edit();
        for (String key : new String[]{BarService.MODE, BarService.POWER, BarService.POWER_LOCKED, BarService.POWER_DOUBLE,
                BarService.POWER_DOUBLE_TARGET, BarService.POWER_DOUBLE_LABEL, BarService.TOUCH_APPS_ON, BarService.TOUCH_APPS,
                BarService.FAST_RECOVER, BootRecovery.OPTION, MainActivity.TARGET_COMPONENT, MainActivity.TARGET_LABEL,
                DrawerActivity.ACTION, TouchArea.HOLD_KEY, TouchArea.SETTLE_KEY, Alerts.ISLAND, Alerts.LOG, Alerts.UPDATE,
                UPDATE_AUTO, UPDATE_BETA, COMPACT, "show_area", "show_recent"})
            edit.remove(key);
        for (boolean landscape : new boolean[]{false, true})
            for (String field : TouchArea.FIELDS) edit.remove(TouchArea.key(landscape, field));
        edit.apply();
        Tweaks.resetDefaults(this);
        getPackageManager().setComponentEnabledSetting(new ComponentName(this, MainActivity.DRAWER),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP); // 앱 서랍에 표시: on
        BarService.showPreview(false);
        BarService.refreshArea();
        BarService.refreshConfiguration();
        applyRecentVisibility();
        Toast.makeText(this, "모든 설정을\n기본값으로 되돌렸습니다.", Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void setOverlayMode(boolean on) {
        BarService.setOverlayMode(this, on);
        if (on) startService(new Intent(this, BarService.class));
        boolean permitted = BootRecovery.permitted(this);
        try {
            if (permitted) {
                String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    on ? BootRecovery.withoutOwn(services) : BootRecovery.withOwn(services));
                if (!on) Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (SecurityException refused) { permitted = false; }
        if (!on) stopService(new Intent(this, BarService.class)); // the accessibility binding takes over
        if (!permitted) {
            Toast.makeText(this, on ? "접근성에서 Circle-to-Search Trigger를 꺼주세요." : "접근성에서 Circle-to-Search Trigger를 다시 켜주세요.",
                Toast.LENGTH_LONG).show();
            startActivity(freshSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
        refresh();
    }

    private void openOverlayPermission() {
        // Device (ColorOS 17): the package URI is ignored and the full list opens, so the hint names the app.
        Toast.makeText(this, "목록에서 Circle-to-Search Trigger를 찾아\n'다른 앱 위에 표시'를 허용한 뒤\n다시 켜주세요.", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException unavailable) { openAppInfo(); }
    }

    private void openAccessibility() {
        // The per-service details page needs a system permission (device: SecurityException), so the list with a hint.
        Toast.makeText(this, "다운로드한 앱 → Circle-to-Search Trigger를 켜주세요.", Toast.LENGTH_LONG).show();
        startActivity(freshSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    /** ColorOS keeps "자동 실행" behind a system-only permission; open Settings with the exact path instead. */
    private void openAutoStart() {
        // Device (ColorOS 17): every page that lists auto-start (com.oplus.battery StartupAppListActivity,
        // OptimizationAutoStartActivity) and Settings' 앱 page require OPPO's signature permission, and Settings' main
        // page reopens its last sub-page (often Wi-Fi). Settings search is public: open it with the term on the clipboard.
        try {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("자동 실행", "자동 실행"));
            startActivity(freshSettings(Settings.ACTION_APP_SEARCH_SETTINGS));
            Toast.makeText(this, "검색창을 길게 눌러 '자동 실행'을 붙여넣고,\n결과에서 Circle-to-Search Trigger를 켜주세요.", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, "설정 → 앱 → 자동 실행에서\nCircle-to-Search Trigger를 켜주세요.", Toast.LENGTH_LONG).show();
            startActivity(freshSettings(Settings.ACTION_SETTINGS));
        }
    }

    /** App info, where the ⋮ menu holds '제한된 설정 허용' (shown only while the restriction applies). */
    private void openRestrictedSettings() {
        Toast.makeText(this, "오른쪽 위 ⋮ → '제한된 설정 허용'을 눌러주세요.\n메뉴가 없으면 접근성에서 한 번 켜기를\n시도한 뒤 다시 열어주세요.", Toast.LENGTH_LONG).show();
        startActivity(freshSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName())));
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
            .setMessage("설정 → 앱 → 자동 실행에서\nCircle-to-Search Trigger가 켜져 있는지 확인해주세요.\n\n'켜져 있음'을 누르면 이 안내를 닫습니다.\n재부팅 때 자동 실행이 막히면 다시 알려드립니다.")
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

    /** Recents-lock guide in progress: the "최근 앱에 표시" state to restore afterwards (null: none). */
    private Boolean recentBeforeLock;

    /** Shows this screen in recents and opens recents, so the user can lock the card; asks on return whether it is done. */
    private void lockInRecents() {
        if (recentBeforeLock == null) recentBeforeLock = recentSwitch.isChecked();
        // ColorOS launcher's own lock list ("최근 작업 관리자", exported without a permission): one switch per app, listed
        // under the drawer label (OpdCTS). Falls back to the recents card menu when this launcher page is missing.
        try {
            startActivity(new Intent().setClassName("com.android.launcher", "com.oplus.quickstep.locksetting.ui.LockSettingActivity"));
            Toast.makeText(this, "목록에서 OpdCTS를 찾아 켠 뒤\n이 화면으로 돌아와주세요.", Toast.LENGTH_LONG).show();
            return;
        } catch (ActivityNotFoundException | SecurityException unavailable) { }
        recentSwitch.setChecked(true);
        Toast.makeText(this, "Circle-to-Search Trigger 이름을 눌러\n'잠금'을 선택한 뒤 이 화면으로 돌아와주세요.", Toast.LENGTH_LONG).show();
        // A short delay lets the card appear in recents first.
        status.postDelayed(() -> {
            if (!BarService.openRecents())
                Toast.makeText(this, "최근 앱 화면에서 Circle-to-Search Trigger\n이름을 눌러 '잠금'을 선택해주세요.", Toast.LENGTH_LONG).show();
        }, 300);
    }

    /** Back from the recents screen: the lock cannot be read, so the user confirms it. */
    private void askRecentLocked() {
        Boolean before = recentBeforeLock;
        recentBeforeLock = null;
        showPhrased(new AlertDialog.Builder(this).setTitle("최근 앱 잠금")
            .setMessage("최근 작업 관리자에서 OpdCTS를 켰나요?\n최근 앱 화면의 카드 이름 옆 자물쇠 표시로도\n확인할 수 있습니다.")
            .setPositiveButton("잠갔음", (dialog, which) -> {
                preferences.edit().putLong(RECENT_LOCKED_AT, System.currentTimeMillis()).apply();
                if (before != null) recentSwitch.setChecked(before); // the lock stays when the card is hidden again
                refresh();
            })
            .setNegativeButton("다시 시도", (dialog, which) -> lockInRecents())
            .setNeutralButton("나중에", (dialog, which) -> { if (before != null) recentSwitch.setChecked(before); }));
    }

    private void recentLockPrompt() {
        showPhrased(new AlertDialog.Builder(this).setTitle("최근 앱 정리 제외")
            .setMessage("'지금 잠그기'를 누르면 ColorOS의\n'최근 작업 관리자'가 열립니다.\n목록에서 OpdCTS를 찾아 켜주세요.\n최근 앱에서 이 앱 카드를 숨겨도 잠금은 유지됩니다.\n\n잠그지 않으면 '모두 닫기' 때 앱이 종료되어\n"
                + (BarService.logsWanted(this) ? "기기 로그를 다시 승인해야 합니다." : "다시 실행될 때까지 잠시 동작하지 않을 수 있습니다."))
            .setPositiveButton("지금 잠그기", (dialog, which) -> lockInRecents())
            .setNeutralButton("잠갔음", (dialog, which) -> {
                preferences.edit().putLong(RECENT_LOCKED_AT, System.currentTimeMillis()).apply(); refresh(); })
            .setNegativeButton("닫기", null));
    }

    /** Lists missing prerequisites; each row opens where it is fixed. Changes no system setting. */
    private void refreshWarnings() {
        warnings.removeAllViews();
        if (BarService.overlayMode(this)) {
            if (!BarService.granted(this))
                warn("'다른 앱 위에 표시'가 꺼져 있습니다", "접근성 없이 실행에 필요합니다. 눌러서 허용해주세요.", this::openOverlayPermission);
        } else if (!BarService.granted(this))
        {
            warn("접근성이 꺼져 있습니다", "눌러서 Circle-to-Search Trigger를 켜주세요.", this::openAccessibility);
            // Android blocks accessibility once for apps installed from a browser or file manager ("restricted settings");
            // whether it applies cannot be read by the app, so the way out is offered while accessibility is off.
            // Device (Android 17): the entry is greyed out and tapping it shows "앱의 액세스가 거부됨".
            warn("켜려고 하면 '앱의 액세스가 거부됨'이 뜨나요?", "브라우저나 파일 관리자로 설치하면 걸리는 '제한된 설정'입니다.\n눌러서 앱 정보를 연 뒤 오른쪽 위 ⋮ → '제한된 설정 허용'을 누르고\n다시 켜주세요.", this::openRestrictedSettings);
        } else if (!BarService.serviceRunning())
            // Device: granting READ_LOGS over ADB kills the process ("changed gids"); Android then marks the service
            // crashed and never rebinds it until the user toggles it.
            warn("접근성이 작동하지 않습니다", "권한을 새로 받은 뒤 멈출 수 있습니다.\n눌러서 Circle-to-Search Trigger를 껐다가 다시 켜주세요.", this::openAccessibility);
        if (!BootRecovery.permitted(this))
            warn("보안 설정 쓰기 권한이 없습니다", "재부팅 후 자동 복구와 ColorOS 기본 하단 바에 필요합니다.\n눌러서 ADB 설정 안내 보기", this::adbHelp);
        if (BarService.logsWanted(this) && !BarService.logsPermitted(this))
            warn("기기 로그 권한이 없습니다", "ColorOS 기본 하단 바와 전원 버튼에 필요합니다.\n눌러서 ADB 설정 안내 보기", this::adbHelp);
        else if (BarService.logsWanted(this) && BarService.serviceRunning() && BarService.logState() == LogSession.OFF)
            warn("기기 로그가 연결되지 않았습니다", "눌러서 접근 승인을 요청해주세요.", () -> {
                BarService.requestApproval(this); status.postDelayed(this::refresh, 500); });
        if (backgroundLimited())
            warn("백그라운드 활동이 제한되어 있습니다", "앱이 종료될 수 있습니다.\n눌러서 배터리 사용량 → 백그라운드 활동 허용", this::openAppInfo);
        // ColorOS keeps "자동 실행" unreadable to apps (no app op or setting changes; provider and page need a system-only
        // permission). So: a reminder until the user confirms it, and a hard warning whenever a boot proves it blocked.
        if (autoStartMissed()) {
            preferences.edit().remove(AUTOSTART_CONFIRMED).apply();
            warn("이번 부팅에서 자동 실행이 차단되었습니다", "재부팅 후 접근성이 꺼질 수 있습니다.\n설정 → 앱 → 자동 실행에서 이 앱을 켜주세요.", this::autoStartPrompt);
        } else if (!preferences.getBoolean(AUTOSTART_CONFIRMED, false))
            warn("자동 실행이 켜져 있는지 확인해주세요", "꺼져 있으면 재부팅 후 접근성이 꺼질 수 있습니다.\n앱이 이 설정을 직접 읽을 수 없어 한 번 확인이 필요합니다.", this::autoStartPrompt);
        if (cleanupKilled())
            // Re-approval only matters when an option reads device logs (ColorOS bar, power button).
            warn("최근 앱 정리로 앱이 종료되었습니다", (BarService.logsWanted(this) ? "기기 로그를 다시 승인해야 합니다.\n" : "") + "최근 앱에서 이 앱 카드를 잠가 두면 막을 수 있습니다.\n눌러서 방법 보기", this::recentLockPrompt);
        // ColorOS keeps the recents lock list unreadable to apps (signature permission), so: a reminder until the user
        // confirms the lock; a cleanup kill after that proves it missing and shows the warning above again.
        else if (preferences.getLong(RECENT_LOCKED_AT, 0) == 0)
            warn("최근 앱에서 이 앱이 잠겨 있지 않을 수 있습니다", "잠그지 않으면 '모두 닫기' 때 앱이 종료됩니다.\n눌러서 최근 앱에서 잠그기", this::recentLockPrompt);
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
        areaSwitch.setEnabled(!nativeMode && previewBeforeFold == null); // locked on while the touch-area fold is open
        for (Slider slider : areaSliders) slider.setEnabled(!nativeMode);
        showFacing(!nativeMode);
        holdSlider.setEnabled(!nativeMode);
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
                + "경로마다 마지막 1건만 이 기기에 저장하며,\n외부로 보내지 않습니다.\n앱은 실행 요청 전달과 서클 투 서치·Gemini\n요청 수락까지만 확인합니다."
                + "\n창이 실제로 떴는지는 확인하지 않습니다.\nGoogle 상태는 선택과 설치·사용 여부만 확인하며,\n실제 연결 상태는 알 수 없습니다."
                + "\n길게 눌렀는데 기록이 바뀌지 않았다면\n입력이 감지되지 않았을 수 있습니다.\n기기 로그 연결과 ColorOS의\n'전원 버튼으로 음성 비서 호출' 설정을 확인해보세요.")
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
            default:
                // An automatic retry opens the prompt (CONNECTING: "확인 창에서 허용 대기 중"), which refreshes this row.
                return StatusPolicy.logDeclined(preferences.getLong(BarService.LOG_DENIED_AT, 0), preferences.getInt(BarService.LOG_DENIALS, 0),
                    preferences.getInt(BarService.LOG_AUTO, 0), LogTrigger.AUTO_PROMPTS, BarService.DECLINE_COOLDOWN);
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
        // Exported settings UI deliberately ignores all automation extras; the only one read is the long-pressed tile.
        dimTilePressed(intent);
    }

    /**
     * Long press on the 딥 다크 모드 tile: SystemUI sends QS_TILE_PREFERENCES naming the tile, and the system's
     * 화면 더 어둡게 page (switch and intensity) opens instead of this screen. Only ever opens that public page.
     */
    private boolean dimTilePressed(Intent intent) {
        if (intent == null || !android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES.equals(intent.getAction())) return false;
        ComponentName tile = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName.class);
        if (tile == null || !new ComponentName(this, DimTile.class).equals(tile)) return false;
        // Application context: its own task, since this screen removes its task right after (tile long press).
        return Tweaks.openSettingsPage(getApplicationContext(), "com.android.settings.Settings$ReduceBrightColorsSettingsActivity");
    }

    @Override public void onResume() {
        super.onResume();
        islandDemo(); // the reopened floating window of a camera-island demo
        syncing = true;
        try {
            bootSwitch.setChecked(BootRecovery.active(this));
            powerSwitch.setChecked(BarService.power(this));
            lockedSwitch.setChecked(BarService.lockedAssist(this));
            doubleSwitch.setChecked(BarService.doublePress(this));
            overlaySwitch.setChecked(BarService.overlayMode(this));
            // Alerts show on only when Android can deliver them (notifications may be turned off later in system settings);
            // the saved choice stays, so they work again once allowed. The capsule also needs the log alert and Live Updates.
            boolean notify = getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
            logAlert.setChecked(notify && Alerts.wanted(this, Alerts.LOG));
            updateAlert.setChecked(notify && Alerts.wanted(this, Alerts.UPDATE));
            islandAlert.setChecked(logAlert.isChecked() && Alerts.wanted(this, Alerts.ISLAND) && Alerts.islandAllowed(this));
            islandAlert.setEnabled(logAlert.isChecked());
        } finally { syncing = false; }
        // Overlay mode: nothing restarts the service after a kill; opening this screen does.
        if (BarService.overlayMode(this) && !BarService.serviceRunning() && Settings.canDrawOverlays(this))
            startService(new Intent(this, BarService.class));
        BarService.releaseOrphanedPowerKeys(this);
        showTuning();
        BarService.refreshConfiguration(); refresh(); applyRecentVisibility();
        status.postDelayed(this::refresh, 300);
        BarService.showPreview(areaSwitch.isChecked());
        if (recentBeforeLock != null) status.post(this::askRecentLocked);
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
