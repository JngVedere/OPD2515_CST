package dev.padhelper.fastcts;

import android.app.*;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.ViewGroup;
import android.widget.*;
import java.text.Collator;
import java.util.*;

public final class SetupActivity extends Activity {
    private TextView status, logStatus, targetLabel;
    private LinearLayout body;
    private SharedPreferences preferences;
    private Switch areaSwitch, recentSwitch, bootSwitch, powerSwitch;
    private RadioGroup modeGroup;
    private int touchId, colorId;
    private boolean waitingAccessibility;
    private final BroadcastReceiver serviceState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("bar", MODE_PRIVATE);
        setTitle("Circle-to-Search Trigger");
        if (getActionBar() != null) getActionBar().hide();
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
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
        status = text("", 16);
        logStatus = text("", 16);
        button("사용 시작", this::begin);
        button("끄기", () -> { BarService.stop(this); refresh(); });

        heading("실행 방식");
        modeGroup = new RadioGroup(this);
        RadioButton touch = new RadioButton(this), color = new RadioButton(this);
        touchId = android.view.View.generateViewId(); colorId = android.view.View.generateViewId();
        touch.setId(touchId); touch.setText("하단 영역 인식"); touch.setTextSize(18); touch.setMinHeight(dp(52));
        color.setId(colorId); color.setText("ColorOS 기본 하단 바 · 로그 감지"); color.setTextSize(18); color.setMinHeight(dp(52));
        modeGroup.addView(touch); modeGroup.addView(color); body.addView(modeGroup);
        modeGroup.check(BarService.nativeMode(this) ? colorId : touchId);
        modeGroup.setOnCheckedChangeListener((group, selected) -> {
            BarService.setMode(this, selected == colorId ? BarService.NATIVE : BarService.TOUCH);
            if (selected == colorId && BarService.enabled(this)) approveIfNeeded();
            refresh();
        });
        text("하단 영역은 투명한 터치 영역을 사용합니다. ColorOS 기본 바는 시스템 애니메이션을 그대로 쓰고, 기기 로그로 길게 누르기를 감지합니다. 두 방식은 동시에 실행되지 않습니다. 끄기는 변경 전 제스처 값을 복구하므로 앱 삭제 전에 먼저 눌러주세요.", 14);

        heading("전원 버튼");
        powerSwitch = toggle("전원 버튼 길게 눌러 작은 Gemini 열기", BarService.power(this), (view, checked) -> {
            BarService.setPower(this, checked);
            if (checked) approveIfNeeded();
            refresh();
        });
        text("전원 버튼을 길게 누르면 Gemini 앱 전체 화면이 아닌 작은 Gemini 창을 엽니다. 하단 실행 방식과 따로 켜고 끌 수 있으며, 잠금 화면에서는 동작하지 않습니다. 접근성, 기기 로그 권한과 ColorOS의 전원 버튼 음성 비서 호출 설정이 필요합니다.", 14);

        heading("기기 로그");
        button("기기 로그 접근 승인 요청", () -> { BarService.requestApproval(this); status.postDelayed(this::refresh, 500); });
        text("ColorOS 기본 바와 전원 버튼은 하나의 기기 로그 연결을 함께 사용합니다. 재부팅하면 첫 잠금 해제 뒤 Android의 기기 로그 접근 확인 창이 자동으로 한 번 뜨며, 허용하면 두 기능이 함께 다시 동작합니다. 다른 시스템 창에 가려 확인 창이 보이지 않았거나 거부했다면 이 버튼으로 다시 요청하세요. 앱이 승인을 대신 누르지 않으며, 강제 종료·권한 회수·시스템 제한으로 연결이 끊길 수 있습니다.", 14);

        heading("터치 영역");
        areaSwitch = toggle("인식 영역 표시", preferences.getBoolean("show_area", false), (view, checked) -> {
            preferences.edit().putBoolean("show_area", checked).apply();
            BarService.showPreview(checked);
        });
        text("하단 영역 방식의 실제 인식 범위를 파란색으로 표시합니다. 설정 화면을 벗어나면 다시 투명해집니다. 기본 바 방식에서는 이 영역을 만들지 않습니다.", 14);

        heading("최근 앱");
        recentSwitch = toggle("최근 앱에 표시", preferences.getBoolean("show_recent", false), (view, checked) -> {
            preferences.edit().putBoolean("show_recent", checked).apply();
            applyRecentVisibility();
        });
        text("이 설정 화면을 최근 앱 목록에 보이거나 숨깁니다. 앱서랍 아이콘은 표시하지 않습니다.", 14);

        heading("연결 앱");
        targetLabel = text("", 18);
        button("앱 선택", this::chooseApp);
        text("기본값은 서클 투 서치의 빠른 실행입니다. 다른 앱을 선택하면 그 앱이 바로 열립니다.", 14);
        text("서클 투 서치는 Google 앱과 MiCTS를 별도로 설치한 뒤, 기본 디지털 어시스턴트를 Google로 선택해주세요.", 14);
        button("기본 어시스턴트 설정", () -> {
            try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); }
            catch (ActivityNotFoundException unavailable) {
                Toast.makeText(this, "설정 → 앱 → 기본 앱에서 디지털 어시스턴트를 Google로 선택해주세요.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        heading("추가 설정");
        bootSwitch = toggle("재부팅 후 자동 복구", BootRecovery.wanted(this), (view, checked) -> {
            if (checked && !BootRecovery.permitted(this)) {
                ((Switch) view).setChecked(false);
                Toast.makeText(this, "아래 최초 ADB 설정 안내를 확인하고 권한을 먼저 허용해주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            preferences.edit().putBoolean(BootRecovery.OPTION, checked).apply();
        });
        text("이전에 직접 켠 접근성만 재부팅 직후 복구합니다. 부팅 전에 접근성을 끄거나 이 옵션·사용을 끄면 자동 복구하지 않습니다. 이 선택 기능은 최초 한 번 ADB로 보안 설정 쓰기 권한을 허용해야 합니다.", 14);
        button("최초 ADB 설정 안내", this::adbHelp);
        button("접근성 설정", this::openAccessibility);
        button("빠른 설정에 버튼 추가", () -> {
            if (Build.VERSION.SDK_INT >= 33) {
                getSystemService(StatusBarManager.class).requestAddTileService(
                    new ComponentName(this, BarTile.class), "Circle-to-Search Trigger",
                    Icon.createWithResource(this, android.R.drawable.ic_menu_search),
                    getMainExecutor(), result -> { });
            } else Toast.makeText(this, "빠른 설정의 편집 화면에서 Circle-to-Search Trigger 타일을 직접 추가해주세요.", Toast.LENGTH_LONG).show();
        });
        button("자동 실행 설정", () -> {
            Toast.makeText(this, "설정에서 앱 → 자동 실행 → Circle-to-Search Trigger을 허용해주세요.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        });
        button("앱 정보와 절전 설정", () -> startActivity(new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        button("최근 앱 정리 제외 설정", () -> {
            recentSwitch.setChecked(true);
            Toast.makeText(this, "최근 앱에서 Circle-to-Search Trigger 카드를 잠근 뒤, 필요하면 최근 앱 표시를 다시 꺼주세요.", Toast.LENGTH_LONG).show();
        });
        button("설정 닫기", () -> { applyRecentVisibility(); moveTaskToBack(true); });
        text("상태 알림 없이 실행됩니다. 선택한 방식으로 연결 앱을 엽니다. 화면 내용 읽기와 캡처는 사용하지 않습니다.", 14);
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
        new AlertDialog.Builder(this).setTitle("최초 ADB 설정")
            .setMessage("USB 디버깅으로 본인 기기를 연결한 뒤 ADB가 있는 컴퓨터에서 아래 명령을 한 번 실행하세요. 여러 기기가 연결되어 있으면 adb -s로 대상을 지정하세요.\n\n"
                + command
                + "\n\n보안 설정 쓰기: 앱이 시스템 설정을 바꿀 수 있는 강한 권한입니다. 재부팅 후 자기 접근성 복구와 하단 바 제스처 전환에 사용하며, ColorOS 기본 바 방식에 필요합니다."
                + "\n\n기기 로그 읽기: ColorOS 기본 바와 전원 버튼 길게 누르기를 감지하는 데 사용합니다. 로그를 저장하거나 외부로 보내지 않습니다. 권한은 재부팅 후에도 유지되지만, 재부팅마다 첫 잠금 해제 뒤 Android의 확인 창에서 직접 허용해야 합니다."
                + "\n\nBreeno 상태: " + (LogSession.breenoDisabled(this) ? "비활성화됨" : "비활성화 필요")
                + ". 켜져 있으면 기본 바는 동작하지 않고, 전원 버튼에서 Breeno가 함께 열릴 수 있습니다. 원하는 경우에만 직접 실행하세요.\nadb shell pm disable-user --user " + user + " com.heytap.speechassist"
                + "\n\n앱이 스스로 권한을 부여하거나 Breeno를 끄지 않습니다.")
            .setPositiveButton("권한 명령 복사", (dialog, which) -> {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Circle-to-Search Trigger ADB 설정", command));
                Toast.makeText(this, "명령을 복사했습니다.", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("닫기", null).show();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size);
        view.setPadding(0, dp(5), 0, dp(9));
        body.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void heading(String label) {
        TextView view = text(label, 18);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(16), 0, dp(5));
    }

    private Switch toggle(String label, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        Switch view = new Switch(this);
        view.setText(label); view.setTextSize(18);
        view.setMinHeight(dp(56));
        view.setChecked(checked);
        view.setOnCheckedChangeListener(listener);
        body.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label); view.setAllCaps(false);
        view.setOnClickListener(v -> action.run());
        body.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void applyRecentVisibility() {
        boolean visible = preferences.getBoolean("show_recent", false);
        for (ActivityManager.AppTask task : getSystemService(ActivityManager.class).getAppTasks()) {
            ComponentName base = task.getTaskInfo().baseActivity;
            if (base != null && base.equals(new ComponentName(this, SetupActivity.class)))
                task.setExcludeFromRecents(!visible);
        }
    }

    private void chooseApp() {
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
        choices.add(0, new AppChoice("서클 투 서치 (기본 · 빠른 실행)", ""));
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
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("연결할 앱 선택")
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
            if (choice.component.isEmpty()) edit.remove(MainActivity.TARGET_COMPONENT).remove(MainActivity.TARGET_LABEL);
            else edit.putString(MainActivity.TARGET_COMPONENT, choice.component).putString(MainActivity.TARGET_LABEL, choice.label);
            edit.apply();
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

    private void begin() {
        BarService.start(this);
        if (!BarService.granted(this)) { waitingAccessibility = true; openAccessibility(); }
        else { approveIfNeeded(); status.postDelayed(this::refresh, 250); }
    }

    private void openAccessibility() {
        Toast.makeText(this, "다운로드한 앱에서 Circle-to-Search Trigger을 켜주세요.", Toast.LENGTH_LONG).show();
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void refresh() {
        boolean nativeMode = BarService.nativeMode(this);
        modeGroup.check(nativeMode ? colorId : touchId);
        areaSwitch.setEnabled(!nativeMode);
        String state;
        if (!BarService.granted(this)) state = "접근성 설정 필요";
        else if (!BarService.enabled(this)) state = "꺼짐";
        else if (nativeMode && !BootRecovery.permitted(this)) state = "기본 바 · 보안 설정 쓰기 권한 필요";
        else if (nativeMode && !LogSession.breenoDisabled(this)) state = "기본 바 · Breeno 비활성화 필요";
        else if (nativeMode && BarService.logState() != LogSession.ACTIVE) state = "기본 바 · 기기 로그 연결 대기";
        else if (!BarService.ready(this)) state = nativeMode ? "기본 바 · 제스처 설정 확인 필요" : "터치 영역 연결 대기";
        else state = nativeMode ? "사용 중 · ColorOS 기본 바 연결" : "사용 중 · 터치 영역 연결";
        status.setText("상태  " + state);
        logStatus.setText("기기 로그  " + logState() + "\n전원 버튼  " + powerState());
        targetLabel.setText(preferences.getString(MainActivity.TARGET_LABEL, "서클 투 서치 (기본 · 빠른 실행)"));
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
        if (!BarService.power(this)) return "꺼짐";
        return BarService.logState() == LogSession.ACTIVE && BarService.serviceRunning() ? "감지 중" : "기기 로그 연결 대기";
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Exported settings UI deliberately ignores all automation extras.
    }

    @Override public void onResume() {
        super.onResume();
        bootSwitch.setChecked(BootRecovery.wanted(this));
        powerSwitch.setChecked(BarService.power(this));
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
    }

    @Override public void onStop() { unregisterReceiver(serviceState); super.onStop(); }

    @Override public void onPause() {
        BarService.showPreview(false);
        super.onPause();
    }
}
