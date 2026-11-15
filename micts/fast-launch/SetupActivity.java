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
    private TextView status, targetLabel;
    private LinearLayout body;
    private SharedPreferences preferences;
    private Switch areaSwitch, recentSwitch, bootSwitch;
    private RadioGroup modeGroup;
    private int touchId, colorId;
    private boolean waitingAccessibility;
    private final BroadcastReceiver serviceState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("bar", MODE_PRIVATE);
        setTitle("하단 검색");
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
        text("하단 검색", 24).setTypeface(null, android.graphics.Typeface.BOLD);
        text("하단 중앙을 길게 눌러 선택한 앱을 엽니다.", 18);
        status = text("", 16);
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
            refresh();
        });
        text("하단 영역은 투명한 터치 영역을 사용합니다. ColorOS 기본 바는 시스템 애니메이션과 제스처 이벤트를 사용하며 별도 ADB 활성화가 필요합니다. 두 방식은 동시에 실행되지 않습니다. 끄기는 변경 전 제스처 값을 복구하므로 앱 삭제 전에 먼저 눌러주세요.", 14);
        button("기본 바 ADB 활성화 안내", this::nativeBarHelp);
        button("기본 바 에이전트 종료", () -> { BarService.stopAgent(); refresh(); });

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
        button("최초 ADB 설정 안내", () -> {
            String command = "adb shell pm grant --user 0 " + getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS";
            new AlertDialog.Builder(this).setTitle("선택 권한: 보안 설정 쓰기")
                .setMessage("USB 디버깅으로 본인 기기를 연결한 뒤 ADB가 있는 컴퓨터에서 아래 명령을 실행하세요. 여러 기기나 사용자가 있으면 대상 일련번호와 사용자 번호를 지정하세요.\n\n"
                    + command + "\n\n앱이 시스템 설정을 바꿀 수 있는 강한 권한입니다. 이전에 승인한 자기 접근성의 부팅 복구와 하단 바의 Breeno 호출 억제에 사용합니다. 앱이 스스로 부여할 수 없으며 ColorOS가 거부하면 수동 접근성·제스처 설정을 사용하세요. 권한 부여 후 자동 복구 옵션을 직접 켜주세요.")
                .setPositiveButton("명령 복사", (dialog, which) -> {
                    getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("하단 검색 ADB 설정", command));
                    Toast.makeText(this, "명령을 복사했습니다.", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("닫기", null).show();
        });
        button("접근성 설정", this::openAccessibility);
        button("빠른 설정에 버튼 추가", () -> {
            if (Build.VERSION.SDK_INT >= 33) {
                getSystemService(StatusBarManager.class).requestAddTileService(
                    new ComponentName(this, BarTile.class), "하단 검색",
                    Icon.createWithResource(this, android.R.drawable.ic_menu_search),
                    getMainExecutor(), result -> { });
            } else Toast.makeText(this, "빠른 설정의 편집 화면에서 하단 검색 타일을 직접 추가해주세요.", Toast.LENGTH_LONG).show();
        });
        button("자동 실행 설정", () -> {
            Toast.makeText(this, "설정에서 앱 → 자동 실행 → 하단 검색을 허용해주세요.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        });
        button("앱 정보와 절전 설정", () -> startActivity(new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        button("최근 앱 정리 제외 설정", () -> {
            recentSwitch.setChecked(true);
            Toast.makeText(this, "최근 앱에서 하단 검색 카드를 잠근 뒤, 필요하면 최근 앱 표시를 다시 꺼주세요.", Toast.LENGTH_LONG).show();
        });
        button("설정 닫기", () -> { applyRecentVisibility(); moveTaskToBack(true); });
        text("상태 알림 없이 실행됩니다. 선택한 방식으로 연결 앱을 엽니다. 화면 내용 읽기와 캡처는 사용하지 않습니다.", 14);
    }

    private void nativeBarHelp() {
        try {
            String command = NativeBarBridge.activationCommand(this);
            int user = getApplicationInfo().uid / 100000;
            new AlertDialog.Builder(this).setTitle("ColorOS 기본 바 활성화")
                .setMessage("접근성과 보안 설정 쓰기 권한을 먼저 허용하고 ColorOS 기본 바 방식을 선택하세요. Breeno가 꺼져 있어야 합니다. 현재 상태: "
                    + (NativeBarBridge.breenoDisabled(this) ? "Breeno 비활성화됨" : "Breeno 비활성화 필요")
                    + "\n\n필요한 경우에만 본인 기기에서 실행할 Breeno 비활성화 명령:\nadb shell pm disable-user --user " + user + " com.heytap.speechassist"
                    + "\n\nADB가 있는 컴퓨터에서 실행할 에이전트 활성화 명령:\n" + command
                    + "\n\n에이전트는 ADB 셸 권한으로 시스템의 해당 제스처 로그만 감지합니다. 화면·음성·검색어를 수집하거나 로그를 저장하지 않습니다. 재부팅·앱 업데이트 후에는 다시 활성화해야 합니다. 연결된 동안에는 위 실행 방식만 바꿔 전환할 수 있습니다. 여러 기기에서는 adb -s로 대상을 지정하세요.")
                .setPositiveButton("활성화 명령 복사", (dialog, which) -> {
                    getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("기본 바 활성화", command));
                    Toast.makeText(this, "에이전트 활성화 명령을 복사했습니다.", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("닫기", null).show();
        } catch (Exception unavailable) {
            Toast.makeText(this, "이 기기의 기본 바 활성화 정보를 확인할 수 없습니다.", Toast.LENGTH_LONG).show();
        }
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
        else status.postDelayed(this::refresh, 250);
    }

    private void openAccessibility() {
        Toast.makeText(this, "다운로드한 앱에서 하단 검색을 켜주세요.", Toast.LENGTH_LONG).show();
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
        else if (nativeMode && !NativeBarBridge.breenoDisabled(this)) state = "기본 바 · Breeno 비활성화 필요";
        else if (nativeMode && !BarService.agentConnected()) state = "기본 바 · ADB 활성화 필요";
        else if (!BarService.ready(this)) state = nativeMode ? "기본 바 · 로그 수신 또는 제스처 설정 확인 필요" : "터치 영역 연결 대기";
        else state = nativeMode ? "사용 중 · ColorOS 기본 바 연결" : "사용 중 · 터치 영역 연결";
        status.setText("상태  " + state);
        targetLabel.setText(preferences.getString(MainActivity.TARGET_LABEL, "서클 투 서치 (기본 · 빠른 실행)"));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Exported settings UI deliberately ignores all automation extras.
    }

    @Override public void onResume() {
        super.onResume();
        bootSwitch.setChecked(BootRecovery.wanted(this));
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
