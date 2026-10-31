package com.flipauto;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 首次引导的最后一页：选择外屏默认应用（同时作为 Home 键与自动启动的目标），并按系统显示密度校准界面缩放。
 *
 * 选中的应用包名写入 flip_target_app，同时把 Home 按钮目标设为该应用。
 * 缩放只按 350 dpi 基准换算一次：这套界面原本是按 350 dpi 适配的，实测密度不同时需要按比例缩放，
 * 才能在外屏上保持同样的相对尺寸。这里只读系统密度，绝不修改系统显示设置。
 */
public final class LauncherSetupActivity extends Activity {
    /** 设计基准密度：界面元素按 350 dpi 调好的视觉比例。 */
    static final int BASE_DENSITY_DPI = 350;

    private Prefs prefs;
    private TextView dpiSummary;
    private LinearLayout launcherList;
    private EditText searchInput;
    private final List<Candidate> candidates = new ArrayList<>();
    private String selected = "";

    private static final class Candidate {
        final String pkg;
        final CharSequence label;
        final Drawable icon;
        Candidate(String pkg, CharSequence label, Drawable icon) { this.pkg = pkg; this.label = label; this.icon = icon; }
    }

    @Override protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LanguageUtil.applyLanguage(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(R.layout.activity_launcher_setup);
        dpiSummary = findViewById(R.id.dpiSummary);
        launcherList = findViewById(R.id.launcherList);
        Button finish = findViewById(R.id.btnFinish), skip = findViewById(R.id.btnSkip);
        selected = prefs.flipTargetApp();
        calibrateScale();
        setupSearch();
        loadCandidates();
        finish.setOnClickListener(v -> complete());
        skip.setOnClickListener(v -> complete());
    }

    /** 添加搜索框，支持按应用名称或包名实时过滤。 */
    private void setupSearch() {
        searchInput = new EditText(this);
        searchInput.setHint("搜索应用名称或包名");
        searchInput.setTextSize(13);
        searchInput.setPadding(24, 20, 24, 20);
        searchInput.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, 16);
        searchInput.setLayoutParams(params);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterList(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        // 将搜索框插入到列表前面
        ((LinearLayout) launcherList.getParent()).addView(searchInput, 1);
    }

    /** 按搜索关键词过滤应用列表。 */
    private void filterList() {
        String q = searchInput.getText() == null ? "" : searchInput.getText().toString().trim().toLowerCase();
        launcherList.removeAllViews();
        boolean found = false;
        for (Candidate item : candidates) {
            if (!q.isEmpty() && !item.label.toString().toLowerCase().contains(q) && !item.pkg.toLowerCase().contains(q)) continue;
            launcherList.addView(row(item));
            found = true;
        }
        if (!found) {
            TextView empty = new TextView(this);
            empty.setText("没有找到匹配的应用");
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 32, 0, 32);
            launcherList.addView(empty);
        }
    }

    /** 按外屏实际密度反算 ui_scale；只做一次，之后用户手动调的值不会被覆盖。 */
    private void calibrateScale() {
        int dpi = coverDpi();
        boolean fromCover = dpi > 0;
        if (!fromCover) dpi = defaultDpi();
        if (dpi <= 0 || dpi >= 1000) { dpiSummary.setText(R.string.launcher_setup_uncalibrated); return; }
        int scale = Math.round(BASE_DENSITY_DPI * 100f / dpi);
        if (!prefs.data.contains("ui_scale_calibrated")) {
            prefs.uiScale(scale);
            prefs.data.edit().putBoolean("ui_scale_calibrated", true).apply();
        }
        int applied = prefs.uiScale();
        dpiSummary.setText(fromCover
                ? getString(R.string.launcher_setup_calibrated, dpi, applied)
                : getString(R.string.launcher_setup_dpi_fallback, dpi, applied));
    }

    /** 外屏（display 1）的真实密度；展开手机时外屏仍可被 DisplayManager 读到。 */
    private int coverDpi() {
        try {
            DisplayManager dm = getSystemService(DisplayManager.class);
            if (dm == null) return 0;
            Display target = dm.getDisplay(1);
            if (target == null && prefs.displayId() > 0) target = dm.getDisplay(prefs.displayId());
            if (target == null) for (Display item : dm.getDisplays()) if (item.getDisplayId() != Display.DEFAULT_DISPLAY) { target = item; break; }
            if (target == null) return 0;
            DisplayMetrics metrics = new DisplayMetrics();
            target.getRealMetrics(metrics);
            return metrics.densityDpi;
        } catch (RuntimeException ignored) { return 0; }
    }

    private int defaultDpi() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics.densityDpi;
    }

    /** 列出所有已安装应用（包括无启动器图标的系统应用），用户可任选一个作为外屏默认应用。 */
    private void loadCandidates() {
        PackageManager pm = getPackageManager();
        String self = getPackageName();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo ai : apps) {
            String pkg = ai.packageName;
            if (pkg.equals(self)) continue;
            if (contains(pkg)) continue;
            try { candidates.add(new Candidate(pkg, ai.loadLabel(pm), ai.loadIcon(pm))); }
            catch (RuntimeException ignored) { }
        }
        candidates.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        if (candidates.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.launcher_setup_none);
            empty.setTextSize(12);
            launcherList.addView(empty);
            return;
        }
        for (Candidate item : candidates) launcherList.addView(row(item));
    }

    private boolean contains(String pkg) {
        for (Candidate item : candidates) if (item.pkg.equals(pkg)) return true;
        return false;
    }

    private android.view.View row(Candidate item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(6, 8, 6, 8);
        boolean active = item.pkg.equals(selected);
        row.setBackgroundColor(active ? 0x332196F3 : 0x00000000);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(item.icon);
        int size = (int) (28 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(size, size);
        iconParams.setMargins(0, 0, (int) (8 * getResources().getDisplayMetrics().density), 0);
        row.addView(icon, iconParams);
        TextView label = new TextView(this);
        label.setText(item.label);
        label.setTextSize(13);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(label);
        row.setOnClickListener(v -> {
            selected = item.pkg;
            prefs.data.edit().putString("flip_target_app", item.pkg).putString("home_action", "launcher").apply();
            // 自动将选中的应用加入悬浮球排除列表
            java.util.Set<String> excluded = new java.util.HashSet<>(prefs.flipBubbleExcluded());
            if (!excluded.contains(item.pkg)) {
                excluded.add(item.pkg);
                prefs.flipBubbleExcluded(excluded);
            }
            filterList();
        });
        return row;
    }

    /** 检查通知使用权是否已授权。 */
    private boolean isNotificationListenerEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) return false;
            ComponentName expected = new ComponentName(this, CoverNotifications.class);
            for (String component : flat.split(":")) {
                if (ComponentName.unflattenFromString(component) != null
                        && ComponentName.unflattenFromString(component).equals(expected)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private void complete() {
        prefs.onboardingDone(true);
        // 如果未授权通知使用权，跳转到系统设置引导用户授权
        if (!isNotificationListenerEnabled()) {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (RuntimeException ignored) { }
        }
        // Android 13+ 申请通知权限（用于 TOAST 及通知显示）
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 200);
        } else {
            enterMain();
        }
    }

    private void enterMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 200) enterMain();
    }
}
