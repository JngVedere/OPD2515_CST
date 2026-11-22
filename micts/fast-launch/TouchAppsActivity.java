package dev.padhelper.fastcts;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 일부 앱에서 하단 영역 인식 강제 사용: the app list as its own screen, laid out like ColorOS 게임 스페이스 → 관리
 * (search bar, then cards of icon + name + switch; two columns on a wide screen). Apps chosen earlier come first.
 * Each switch is saved and applied at once.
 */
public final class TouchAppsActivity extends Activity {
    private static final class App {
        final String name, label;
        final Drawable icon;
        App(String name, String label, Drawable icon) { this.name = name; this.label = label; this.icon = icon; }
    }

    private SharedPreferences preferences;
    private final Set<String> chosen = new HashSet<>();
    private final List<App> apps = new ArrayList<>();
    private LinearLayout list;
    private TextView loading;
    private String query = "";
    private int secondary, pageColor, cardColor;
    private android.content.res.ColorStateList primary;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().hide(); // own top bar below
        preferences = getSharedPreferences("bar", MODE_PRIVATE);
        chosen.addAll(BarService.touchApps(this));
        android.content.res.TypedArray colors = obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary, android.R.attr.textColorPrimary});
        secondary = colors.getColor(0, 0xFF808080);
        primary = colors.getColorStateList(1);
        colors.recycle();
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        pageColor = night ? 0xFF000000 : 0xFFF1F2F4;
        cardColor = night ? 0xFF1C1C1E : 0xFFFFFFFF;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), 0, dp(20), 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(20) + bars.left, bars.top, dp(20) + bars.right, 0);
            if (list != null) list.setPadding(0, 0, 0, bars.bottom + dp(16));
            return insets;
        });

        // Top bar: round back button and the title.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(12), 0, dp(12));
        TextView back = new TextView(this);
        back.setText("←"); back.setTextSize(22); back.setTextColor(primary);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(cardColor, dp(24)));
        back.setContentDescription("뒤로");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(this);
        title.setText("하단 영역 인식 강제 사용 앱"); title.setTextSize(22); title.setTextColor(primary);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(16), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top);

        // Search: a rounded pill.
        EditText search = new EditText(this);
        search.setHint("앱 이름으로 검색"); search.setSingleLine(true); search.setTextSize(17);
        search.setBackground(round(cardColor, dp(28)));
        search.setPadding(dp(20), dp(14), dp(20), dp(14));
        Drawable glass = getDrawable(android.R.drawable.ic_menu_search);
        if (glass != null) {
            glass = glass.mutate(); glass.setTint(secondary);
            glass.setBounds(0, 0, dp(24), dp(24));
            search.setCompoundDrawablesRelative(glass, null, null, null);
            search.setCompoundDrawablePadding(dp(10));
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("켠 앱이 화면에 있을 때만 ColorOS 기본 바 대신 하단 터치 영역으로 실행합니다.");
        hint.setTextSize(14); hint.setTextColor(secondary);
        hint.setPadding(dp(4), dp(12), dp(4), 0);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        loading = new TextView(this);
        loading.setText("앱 목록을 불러오는 중…"); loading.setTextSize(15); loading.setTextColor(secondary);
        loading.setPadding(dp(4), dp(24), 0, 0);
        list.addView(loading);
        root.setBackgroundColor(pageColor); // same surfaces as the settings screen
        setContentView(root);

        // Labels and icons off the main thread (about a hundred apps).
        new Thread(() -> {
            List<App> loaded = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : getPackageManager().queryIntentActivities(launcher, 0)) {
                String name = info.activityInfo.packageName;
                if (!info.activityInfo.exported || getPackageName().equals(name) || !seen.add(name)) continue;
                loaded.add(new App(name, info.loadLabel(getPackageManager()).toString(), info.loadIcon(getPackageManager())));
            }
            Collator order = Collator.getInstance();
            loaded.sort((a, b) -> order.compare(a.label, b.label));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                apps.addAll(loaded);
                render();
            });
        }, "TouchAppsLoad").start();
    }

    /** Chosen apps (as they were when the screen opened or the search changed), then the rest, filtered by the search. */
    private void render() {
        if (apps.isEmpty()) return;
        list.removeAllViews();
        List<App> on = new ArrayList<>(), off = new ArrayList<>();
        for (App app : apps) {
            if (!query.isEmpty() && !app.label.toLowerCase(Locale.ROOT).contains(query)) continue;
            (chosen.contains(app.name) ? on : off).add(app);
        }
        if (on.isEmpty() && off.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("검색 결과가 없습니다."); none.setTextSize(15); none.setTextColor(secondary);
            none.setPadding(dp(4), dp(24), 0, 0);
            list.addView(none);
            return;
        }
        section("선택한 앱", on);
        section(on.isEmpty() ? "앱" : "다른 앱", off);
    }

    private void section(String label, List<App> section) {
        if (section.isEmpty()) return;
        TextView header = new TextView(this);
        header.setText(label); header.setTextSize(17); header.setTextColor(primary);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(dp(8), dp(24), 0, dp(10));
        list.addView(header);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(cardColor, dp(20)));
        card.setPadding(dp(8), dp(6), dp(8), dp(6));
        int columns = getResources().getConfiguration().screenWidthDp >= 720 ? 2 : 1;
        LinearLayout row = null;
        for (int i = 0; i < section.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(cell(section.get(i)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        // Keep the last cell half width when the count is odd.
        if (row != null && section.size() % columns != 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        list.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View cell(App app) {
        LinearLayout cell = new LinearLayout(this);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setMinimumHeight(dp(72));
        cell.setPadding(dp(12), dp(6), dp(12), dp(6));
        cell.setBackground(getDrawable(rippleId()));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView name = new TextView(this);
        name.setText(app.label); name.setTextSize(17); name.setTextColor(primary);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(16), 0, dp(12), 0);
        cell.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(this);
        toggle.setChecked(chosen.contains(app.name));
        toggle.setClickable(false); toggle.setFocusable(false); // the whole cell toggles
        cell.addView(toggle);
        cell.setContentDescription(app.label);
        cell.setOnClickListener(v -> {
            boolean now = !chosen.contains(app.name);
            if (now) chosen.add(app.name); else chosen.remove(app.name);
            toggle.setChecked(now);
            preferences.edit().putStringSet(BarService.TOUCH_APPS, new HashSet<>(chosen)).apply();
            BarService.refreshConfiguration();
        });
        return cell;
    }

    private int rippleId() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(radius);
        return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
