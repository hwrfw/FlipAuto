package com.flipauto;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    /** 配置备份格式版本：导出时写入，导入时作为可接受上限。 */
    private static final int CONFIG_VERSION = 15;
    /** 配置备份大小上限（字节），避免异常文件占用内存。 */
    private static final int CONFIG_LIMIT_BYTES = 262144;
    /** 请求码：导出到文件 / 从文件导入。 */
    private static final int REQUEST_EXPORT = 201, REQUEST_IMPORT = 202;
    /**
     * 导出时跳过的偏好项：小组件模板已有专用字段承载。
     */
    private static final java.util.Set<String> EXPORT_SKIPPED_PREFS =
            new java.util.HashSet<>(java.util.Arrays.asList("widget_templates",
                    "cutout_0", "cutout_1", "cutout_2", "cutout_3"));
    /**
     * 导入时跳过的偏好项：设备相关项换机后无效，
     * 小组件模板已由 {@link WidgetTemplates} 单独校验写入。
     */
    private static final java.util.Set<String> IMPORT_SKIPPED_PREFS = new java.util.HashSet<>(java.util.Arrays.asList(
            "display", "privilege_mode", "flip_last_app", "onboarding_done",
            "ui_scale_calibrated", "system_controls_disabled", "widget_templates",
            "cutout_0", "cutout_1", "cutout_2", "cutout_3"
    ));

    private Prefs prefs;
    private LinearLayout body, outer, panelPreviewHost;
    private ScrollView scroll;
    private FrameLayout rootLayer;
    private DetailSheet sheet;
    private TextView statusLabel;
    private boolean panelPreviewExpanded, bindingUI;
    private String editing = "dock", route = "main", category = "builtin", diagnosticText = "";
    private String draftKind;
    private ArrayList<String> draft;
    private SettingsOrderList orderList;
    private Bundle orderState = new Bundle();
    private TextView orderCount, orderUndoLabel;
    private View orderUndoBar, orderAdd;
    private Bundle libraryStates = new Bundle();
    private String renderedLibrary;
    /** 待处理的导入来源：已选文件但尚未写入时保留，Activity 重建后继续读取。 */
    private String pendingImportUri;
    /** 导入请求序号：用于丢弃过期或已离开页面的异步结果。 */
    private int importRequest;
    private Bundle pageState = new Bundle();
    private final SettingsNavigator navigator = new SettingsNavigator();
    private final List<Runnable> statusUpdates = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() { refreshStatus(); main.postDelayed(this, 1200); }
    };
    @Override protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LanguageUtil.applyLanguage(newBase));
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); prefs = new Prefs(this);
        // 首启引导：未完成时先进入权限申请页
        if (!prefs.onboardingDone()) { startActivity(new Intent(this, PermissionActivity.class)); finish(); return; }
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (saved != null) {
            navigator.restore(saved.getBundle("navigation")); panelPreviewExpanded = saved.getBoolean("panel_preview");
            pendingImportUri = saved.getString("import_uri"); draft = saved.getStringArrayList("draft"); draftKind = saved.getString("draft_kind");
            Bundle restoredOrder = saved.getBundle("order_state"); if (restoredOrder != null) orderState = restoredOrder;
            Bundle restoredLibrary = saved.getBundle("library_states"); if (restoredLibrary != null) libraryStates = restoredLibrary;
        }
        Bundle arguments = navigator.current();
        if (arguments != null) applyArguments(arguments);
        else { route = getIntent().getStringExtra("section"); if (route == null) route = "main"; }
        showRoute(route);
        if (pendingImportUri != null) readImport(android.net.Uri.parse(pendingImportUri));
    }
    @Override protected void onResume() {
        super.onResume(); main.removeCallbacks(statusRefresh); main.post(statusRefresh); CoverApp.bridge(this).connect();
    }
    @Override protected void onPause() {
        main.removeCallbacks(statusRefresh);
        if (orderList != null) orderList.cancelDrag();
        super.onPause();
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        saveLibraryState(); out.putBundle("library_states", libraryStates);
        out.putBundle("order_state", orderList == null ? orderState : orderList.saveState());
        out.putString("import_uri", pendingImportUri); out.putBundle("navigation", navigator.save(scroll, body)); out.putBoolean("panel_preview", panelPreviewExpanded); out.putStringArrayList("draft", draft); out.putString("draft_kind", draftKind); super.onSaveInstanceState(out);
    }
    private void applyArguments(Bundle args) {
        route = args.getString("page", "main"); editing = args.getString("editing", "dock"); category = args.getString("category", "builtin");
    }
    private void showRoute(String page) {
        switch (page) {
            case "dock", "panel", "favorites" -> editor(page, false);
            case "group_dock", "group_appearance", "group_apps", "group_device", "group_backup", "group_flip" -> categoryPage(page);
            case "status" -> statusSettings();
            case "appearance" -> appearanceSettings();
            case "background" -> backgroundSettings();
            case "gestures" -> gestureSettings();
            case "visibility" -> visibilitySettings();
            case "visibility_apps" -> applicationList();
            case "layout_backup" -> layoutSettings();
            case "hand" -> handSettings();
            case "hub" -> hubSettings();
            case "hub_pin" -> orderEditor("hub_pin");
            case "order" -> orderEditor(editing);
            case "library" -> library(category, false);
            case "permissions" -> permissions();
            case "display" -> chooseDisplay();
            case "about" -> about();
            case "calibrate" -> calibrate();
            case "apps" -> library("apps", true);
            case "diagnostics" -> diagnostics();
            case "flip" -> flipSettings();
            default -> home();
        }
    }
    private void begin(String title, String page) {
        saveLibraryState(); renderedLibrary = null;
        if (orderList != null) { orderState = orderList.saveState(); orderList.cancelDrag(); orderList = null; }
        if (!page.equals(route)) { pendingImportUri = null; importRequest++; }
        if (rootLayer != null) getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(rootLayer.getWindowToken(), 0);
        Bundle args = new Bundle(); args.putString("page", page); args.putString("editing", editing); args.putString("category", category);
        pageState = navigator.enter(args, scroll, body); route = page; statusLabel = null; panelPreviewHost = null; statusUpdates.clear(); sheet = null;
        rootLayer = new FrameLayout(this); rootLayer.setBackgroundColor(0x00000000);
        rootLayer.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime()); view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets; });
        outer = SettingsUi.column(this); rootLayer.addView(outer, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = SettingsUi.row(this); header.setMinimumHeight(Ui.dp(this, 52)); header.setPadding(Ui.dp(this, page.equals("main") ? 16 : 2), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        if (!page.equals("main")) { View back = SettingsUi.iconButton(this, R.drawable.ic_ms_arrow_back, "返回", this::back); back.setTag("settings-back"); header.addView(back); }
        TextView heading = SettingsUi.heading(this, title, 20); heading.setAccessibilityHeading(true); heading.setTag("settings-title"); header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (page.equals("main")) header.addView(SettingsUi.iconButton(this, R.drawable.ic_ms_search, "搜索设置", () -> { EditText input = body.findViewWithTag("settings-query"); if (input != null) { input.setVisibility(View.VISIBLE); input.requestFocus(); scroll.smoothScrollTo(0, input.getTop() - Ui.dp(this, 8)); getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); } }));
        outer.addView(header);
        scroll = new ScrollView(this); scroll.setTag("settings-scroll"); scroll.setFillViewport(false);
        body = SettingsUi.column(this); body.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 20)); body.setFocusableInTouchMode(true); scroll.addView(body); body.requestFocus();
        outer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(rootLayer); rootLayer.requestApplyInsets(); SettingsNavigator.restoreViews(scroll, body, pageState);
    }
    // Android 13+ uses the registered OnBackInvokedDispatcher; this is only the API 30–32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { back(); }
    private void back() {
        if (sheet != null) { dismissSheet(); return; }
        if (route.equals("order") && draft != null && !draft.equals(savedActions(draftKind))) {
            confirm("放弃这次编辑？", "尚未完成的顺序和增删不会保存。", "放弃更改", () -> { draft = null; draftKind = null; popPage(); }); return;
        }
        if (route.equals("order")) { draft = null; draftKind = null; }
        popPage();
    }
    private void popPage() {
        pendingImportUri = null; importRequest++;
        Bundle previous = navigator.back();
        if (previous == null) { finish(); return; }
        applyArguments(previous); showRoute(route);
    }
    private LinearLayout group(String tag) { LinearLayout group = SettingsUi.group(this); group.setTag(tag); SettingsUi.add(body, group); return group; }
    private TextView note(String message) { TextView text = SettingsUi.text(this, message, 14, SettingsUi.MUTED); text.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0); SettingsUi.add(body, text); return text; }
    private void link(LinearLayout parent, int icon, String title, String summary, String page) { View row = SettingsUi.settingRow(this, icon, title, summary, () -> showRoute(page)); row.setTag("settings-link-" + page); parent.addView(row); }
    private void related(String title, String page) { SettingsUi.section(body, "相关设置"); LinearLayout group = group("related"); group.setBackground(Ui.background(this, 0xFF0C192F, 20)); link(group, 0, title, "", page); }
    private record Setting(String group, String page, int icon, String title, String keywords, Supplier<String> value) { }
    /** 主页分组入口；图标与摘要取自该分组实际内容，避免与子页脱节。 */
    private record HomeGroup(String page, String title, int icon, Supplier<String> summary) { }
    private List<HomeGroup> homeGroups() {
        return List.of(
            new HomeGroup("group_flip", "外屏增强", R.drawable.ic_ms_accessibility_new, () -> (prefs.flipBubbles() ? "气泡开启" : "气泡关闭") + " · " + (prefs.flipRestore() ? "开屏恢复开启" : "开屏恢复关闭")),
            new HomeGroup("group_dock", "快捷栏与手势", R.drawable.ic_ms_tune, () -> prefs.perPage() + " 个 / 页 · " + (prefs.autoHideDock() ? "自动收起" : "手动切换")),
            new HomeGroup("panel", "控制中心", R.drawable.ic_ms_view_carousel, () -> prefs.panelColumns() + " 列 · " + new String[]{"紧凑", "标准", "宽松"}[prefs.panelDensity()]),
            new HomeGroup("group_apps", "应用与小组件", R.drawable.ic_ms_apps, () -> "固定应用 " + prefs.hubPins().size() + "/" + AppDockPlacement.LIMIT),
            new HomeGroup("group_appearance", "状态栏与外观", R.drawable.ic_ms_brightness_6, () -> prefs.statusScale() + "% · 界面缩放 " + prefs.uiScale() + "%"),
            new HomeGroup("group_device", "设备与权限", R.drawable.ic_ms_shield, () -> "特权模式：" + prefs.privilegeLabel()),
            new HomeGroup("group_backup", "备份与关于", R.drawable.ic_ms_refresh, () -> "版本 " + BuildConfig.VERSION_NAME)
        );
    }
    private List<Setting> settings() {
        return List.of(
            new Setting("外屏增强", "flip", R.drawable.ic_ms_accessibility_new, "悬浮气泡与开屏恢复", "悬浮球 气泡 开屏恢复 自动启动 目标应用 小窗", () -> (prefs.flipBubbles() ? "气泡开启" : "气泡关闭") + " · " + (prefs.flipRestore() ? "开屏恢复开启" : "开屏恢复关闭")),
            new Setting("快捷栏与手势", "dock", R.drawable.ic_ms_tune, "按钮与布局", "数量 固定键 顺序 阻尼 快捷栏 Home 主页 锁屏 应用中心 多任务", () -> prefs.perPage() + " 个 / 页 · " + ActionCatalog.label(this, prefs.pinnedAction())),
            new Setting("快捷栏与手势", "visibility", R.drawable.ic_ms_home, "自动显示与收起", "隐藏 桌面 键盘 应用名单", () -> prefs.autoHideDock() ? "按前台应用自动收起" : "手动切换"),
            new Setting("快捷栏与手势", "gestures", R.drawable.ic_ms_swap_vert, "手势与导航避让", "震动 偏移 白条 摄像头 上滑 通知", () -> prefs.data.getBoolean("gestures_enabled", true) ? "白条拖动已开启" : "白条拖动已关闭"),
            new Setting("快捷栏与手势", "hand", R.drawable.ic_ms_accessibility_new, "单手布局", "左手 右手", () -> handLabel()),
            new Setting("控制中心", "panel", R.drawable.ic_ms_tune, "控制中心", "列数 密度 名称 字号 亮度 音量 媒体 预设 工具 按钮 撤销", () -> prefs.panelColumns() + " 列 · " + new String[]{"紧凑", "标准", "宽松"}[prefs.panelDensity()]),
            new Setting("应用与小组件", "hub", R.drawable.ic_ms_apps, "应用 Dock 与中心", "固定应用 底部 清理 九宫格", () -> "固定应用 " + prefs.hubPins().size() + "/" + AppDockPlacement.LIMIT),
            new Setting("应用与小组件", "favorites", R.drawable.ic_ms_edit, "侧栏常用项", "收藏 工具 排序", () -> prefs.actions("favorites").size() + " 个常用项"),
            new Setting("状态栏与外观", "status", R.drawable.ic_ms_wifi, "状态栏", "电量 电池 百分比 大小 时间 通知 网速 左右留白 安全区", () -> prefs.statusScale() + "% · " + (prefs.statusEnabled() ? "常驻已开启" : "常驻已关闭")),
            new Setting("状态栏与外观", "appearance", R.drawable.ic_ms_brightness_6, "悬浮栏风格", "透明 描边 黑底 黑白 图标 缩放 DPI 界面", () -> prefs.uiScale() + "% · " + appearanceLabel()),
            new Setting("状态栏与外观", "background", R.drawable.ic_ms_view_carousel, "面板背景", "模糊 毛玻璃 应用中心", () -> prefs.panelBlur() ? "背景模糊已开启" : "纯黑背景"),
            new Setting("设备与权限", "display", R.drawable.ic_ms_phone_android, "目标外屏", "显示器 选择 屏幕", this::displaySummary),
            new Setting("设备与权限", "calibrate", R.drawable.ic_ms_display_settings, "屏幕适配", "缺口 重新定位 角落 长度 厚度 校准", () -> prefs.data.getBoolean("auto_placement", true) ? "根据系统缺口定位" : "手动校准"),
            new Setting("设备与权限", "permissions", R.drawable.ic_ms_shield, "权限中心", "授权 无障碍 Shizuku 通知 电话 手电筒 特权模式 免root", () -> (CoverService.instance == null ? "无障碍未连接" : "无障碍已连接") + " · " + prefs.privilegeLabel()),
            new Setting("设备与权限", "diagnostics", R.drawable.ic_ms_info, "问题诊断", "兼容性 检测 复制", () -> "设备与窗口信息"),
            new Setting("备份与关于", "layout_backup", R.drawable.ic_ms_refresh, "布局备份与恢复", "保存 重置 撤销 默认", () -> prefs.data.contains("layout_backup") ? "已保存布局" : "尚未保存布局"),
            new Setting("备份与关于", "about", R.drawable.ic_ms_info, "关于与更新日志", "版本 隐私 许可", () -> "版本 " + BuildConfig.VERSION_NAME)
        );
    }
    private void home() {
        begin("FlipAuto", "main");
        // 主开关
        LinearLayout master = group("master"); master.setBackground(Ui.background(this, SettingsUi.MASTER, 26));
        Switch enabled = SettingsUi.toggle(master, "开启 FlipAuto", "已暂停 · 配置保留", prefs.enabled(), checked -> { prefs.data.edit().putBoolean("enabled", checked).apply(); refreshStatus(); });
        enabled.setTag("settings-enabled");
        enabled.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{0xFF4A88FF, 0xFF65656C}));
        statusLabel = SettingsUi.toggleSummary(enabled); refreshStatus();

        // 搜索：紧邻主开关，无需滚动即可使用
        EditText search = search("搜索设置（电量、手势、应用…）", "settings-query"); search.setVisibility(View.VISIBLE);
        android.widget.ImageButton clearBtn = new android.widget.ImageButton(this); clearBtn.setImageResource(R.drawable.ic_ms_close); clearBtn.setColorFilter(SettingsUi.MUTED); clearBtn.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 20)); clearBtn.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8)); clearBtn.setVisibility(View.GONE);
        clearBtn.setOnClickListener(v -> search.setText(""));
        android.widget.FrameLayout searchWrap = new android.widget.FrameLayout(this);
        searchWrap.addView(search, new android.widget.FrameLayout.LayoutParams(-1, -2));
        searchWrap.addView(clearBtn, new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        SettingsUi.add(body, searchWrap);

        // 运行状态：正常时一行带过，异常时才展开可点按的修复入口
        LinearLayout state = group("runtime-state");
        state.addView(stateLine());
        if (CoverService.instance == null) state.addView(SettingsUi.settingRow(this, 0, "开启无障碍服务", "点按前往系统设置", () -> { try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (RuntimeException e) { toast("无法打开无障碍设置"); } }));
        if (prefs.privilegeMode().startsWith("shizuku") && !CoverApp.bridge(this).connected()) state.addView(SettingsUi.settingRow(this, 0, "连接 Shizuku", "点按前往授权", () -> showRoute("permissions")));

        // 分组入口 / 搜索结果
        LinearLayout results = SettingsUi.column(this); results.setTag("settings-results"); body.addView(results);
        Runnable filter = () -> {
            results.removeAllViews(); String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            clearBtn.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
            LinearLayout group = SettingsUi.group(this); results.addView(group);
            if (query.isEmpty()) { for (HomeGroup item : homeGroups()) link(group, item.icon(), item.title(), item.summary().get(), item.page()); return; }
            int count = 0;
            for (Setting item : settings()) if ((item.title + item.keywords + item.group).toLowerCase(java.util.Locale.ROOT).contains(query)) {
                View row = SettingsUi.settingRow(this, item.icon, item.title, item.value.get() + "\n" + item.group, () -> { showRoute(item.page); if (query.contains("电量") || query.contains("百分比")) main.post(() -> focusSetting("battery_percent")); }); row.setTag("search-" + item.page); group.addView(row); count++;
            }
            if ("原生外屏小组件 三星卡片 桌面组件 组合卡片".contains(query)) { group.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_apps, "原生外屏小组件", "应用与小组件", this::openWidgets)); count++; }
            if ("导入 导出 配置文件 备份".contains(query)) { link(group, R.drawable.ic_ms_upload, "配置文件", "备份与关于", "group_backup"); count++; }
            if ("日志 调试 debug 状态栏 dock 开屏恢复 自动启动".contains(query)) { group.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_info, "恢复调试日志", "查看开屏恢复、自动启动、状态栏等调试日志", this::showFlipLog)); count++; }
            if (count == 0) group.addView(SettingsUi.settingRow(this, 0, "没有找到设置", "试试「电量」「手势」或「应用」", null));
        };
        watch(search, filter); filter.run();
    }
    /** 一行运行状态：无障碍与 Shizuku 连接情况。 */
    private LinearLayout stateLine() {
        boolean accessibilityReady = CoverService.instance != null;
        boolean shizukuMode = prefs.privilegeMode().startsWith("shizuku");
        boolean shizukuReady = CoverApp.bridge(this).connected();
        LinearLayout row = SettingsUi.row(this); row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        TextView accessibility = SettingsUi.text(this, accessibilityReady ? "● 无障碍已连接" : "● 无障碍未开启", 13, accessibilityReady ? 0xFF4CAF50 : 0xFFE53935);
        accessibility.setPadding(0, 0, Ui.dp(this, 18), 0); row.addView(accessibility);
        String label = !shizukuMode ? "● 免 Shizuku 模式" : shizukuReady ? "● Shizuku 已连接" : "● Shizuku 未连接";
        row.addView(SettingsUi.text(this, label, 13, !shizukuMode ? SettingsUi.MUTED : shizukuReady ? 0xFF4CAF50 : 0xFFFFA000));
        return row;
    }
    private void categoryPage(String page) {
        String title = switch (page) { case "group_dock" -> "快捷栏与手势"; case "group_appearance" -> "状态栏与外观"; case "group_apps" -> "应用与小组件"; case "group_device" -> "设备与权限"; case "group_flip" -> "外屏增强"; default -> "备份与关于"; };
        begin(title, page); LinearLayout group = group("category-options");
        for (Setting item : settings()) if (item.group.equals(title)) { SettingsUi.ValueRow row = new SettingsUi.ValueRow(this, item.icon, item.title, item.value.get(), true, () -> showRoute(item.page)); row.setTag("settings-link-" + item.page); group.addView(row); }
        if (page.equals("group_apps")) group.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_apps, "原生外屏小组件", "多张组合卡片 · 每张4×4自由布局", this::openWidgets));
        if (page.equals("group_backup")) {
            SettingsUi.section(body, "配置文件");
            LinearLayout files = group("configuration-files");
            files.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_upload, "导出配置", "全部偏好、布局与小组件模板；文件名带时间戳", this::exportConfiguration));
            files.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_download, "导入配置", "先显示内容摘要，确认后覆盖", this::importConfiguration));
            note("目标外屏与权限不随配置导出或导入，保持本机设置不变。");
        }
        SettingsUi.section(body, "危险操作");
        LinearLayout danger = group("category-danger");
        // 父菜单级别的恢复按钮：只重置该菜单下所有子菜单的设置
        danger.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_delete, "恢复本菜单默认", "仅重置「" + title + "」下的设置项，不可撤销", () -> confirm("恢复" + title + "默认设置？", "仅重置当前菜单下的所有设置项，其他菜单不受影响。", "恢复", () -> { resetCategory(page); showRoute(page); })));
    }
    /** 重置指定父菜单下所有子菜单的设置 */
    private void resetCategory(String page) {
        android.content.SharedPreferences.Editor editor = prefs.data.edit();
        switch (page) {
            case "group_dock" -> { // 快捷栏与手势：按钮与布局、自动显示与收起、手势与导航避让、单手布局
                for (String key : new String[]{"per_page", "pinned_action", "damping", "dock_auto_hide", "avoid_keyboard",
                        "exclude_notification_apps", "exclude_dock_apps", "dock_compact_apps", "gestures_enabled", "haptics",
                        "navigation_gap", "tap_handles", "hand_side", "avoid_navigation"}) editor.remove(key);
            }
            case "group_appearance" -> { // 状态栏与外观：状态栏、悬浮栏风格、面板背景
                for (String key : new String[]{"status_enabled", "status_scale", "status_safe_left", "status_safe_right",
                        "status_preset", "status_notification_count", "battery_percent", "battery_percent_inside",
                        "status_speed_precise", "chrome_style", "panel_blur", "ui_scale"}) editor.remove(key);
                for (String item : Prefs.STATUS_ITEMS) editor.remove("status_" + item);
            }
            case "group_apps" -> { // 应用与小组件：应用 Dock 与中心
                for (String key : new String[]{"hub_pins", "hub_pinned", "hub_sort", "favorites", "hub_workspace",
                        "hub_workspace_compact", "hub_workspace_locked", "hub_workspace_labels", "hub_workspace_badges",
                        "hub_workspace_density", "hub_workspace_aliases"}) editor.remove(key);
            }
            case "group_flip" -> { // 外屏增强：开屏恢复、自动启动、悬浮气泡
                for (String key : new String[]{"flip_restore", "flip_restore_delay", "flip_restore_charging", "flip_auto_launch",
                        "flip_detect_subhome", "flip_detect_aod", "flip_target_app", "flip_bubbles", "flip_bubble_size",
                        "flip_excluded", "flip_bubble_excluded", "flip_excluded_order"}) editor.remove(key);
                RotationManager.writeMode(this, RotationManager.MODE_AUTO);
            }
            case "group_backup" -> { // 备份与关于：问题诊断中的 debug 开关
                editor.remove("debug_overlay");
            }
            case "group_device" -> { // 设备与权限：只重置屏幕适配的几何记录，目标外屏与权限保持不变
                editor.remove("auto_placement").remove("dock_width").remove("dock_height");
                for (int rotation = 0; rotation < 4; rotation++) editor.remove("corner_" + rotation).remove("cutout_" + rotation);
            }
        }
        editor.apply();
        if ("group_device".equals(page)) CoverService.resetCutouts();
        toast("已恢复" + (switch (page) { case "group_dock" -> "快捷栏与手势"; case "group_appearance" -> "状态栏与外观"; case "group_apps" -> "应用与小组件"; case "group_device" -> "设备与权限"; case "group_flip" -> "外屏增强"; default -> "备份与关于"; }) + "默认设置");
    }
    private void openWidgets() { startActivity(new Intent(this, NativeWidgetActivity.class)); }
    private EditText search(String hint, String tag) { EditText input = SettingsUi.search(this, hint, pageState.getString(tag, "")); input.setTag(tag); return input; }
    private void watch(EditText input, Runnable update) { input.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); } public void afterTextChanged(android.text.Editable text) { } }); }
    private void focusSetting(String key) { View view = body.findViewWithTag(key); if (view != null) { android.graphics.Rect bounds = new android.graphics.Rect(); view.getDrawingRect(bounds); body.offsetDescendantRectToMyCoords(view, bounds); scroll.smoothScrollTo(0, bounds.top); view.requestFocus(); } }
    private Switch toggle(LinearLayout group, String title, String summary, String key, boolean initial) {
        Switch toggle = SettingsUi.toggle(group, title, summary, prefs.data.getBoolean(key, initial), value -> { if (bindingUI) return; prefs.data.edit().putBoolean(key, value).apply(); }); toggle.setTag(key); return toggle;
    }
    private void choice(LinearLayout group, String title, String tag, String[] labels, Object[] values, Supplier<Object> selected, Consumer<Object> save) {
        SettingsUi.ValueRow row = SettingsUi.valueRow(this, title, choiceLabel(labels, values, selected.get()), null); row.setTag(tag); row.setFocusable(true); row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0));
        row.setOnClickListener(v -> choose(title, labels, values, selected.get(), value -> { save.accept(value); row.value(choiceLabel(labels, values, selected.get())); })); group.addView(row);
    }
    private String choiceLabel(String[] labels, Object[] values, Object selected) { for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) return labels[i]; return "自定义"; }
    private void choose(String title, String[] labels, Object[] values, Object selected, Consumer<Object> save) {
        DetailSheet modal = openSheet(title);
        for (int i = 0; i < labels.length; i++) { Object value = values[i]; boolean active = value.equals(selected); LinearLayout row = SettingsUi.row(this); row.setMinimumHeight(Ui.dp(this, 56)); row.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            android.widget.RadioButton radio = new android.widget.RadioButton(this); radio.setChecked(active); radio.setClickable(false); radio.setFocusable(false); radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); radio.setButtonTintList(android.content.res.ColorStateList.valueOf(SettingsUi.ACCENT)); row.addView(radio);
            TextView label = SettingsUi.text(this, labels[i], 16, active ? SettingsUi.ACCENT : SettingsUi.TEXT); row.addView(label, new LinearLayout.LayoutParams(0, -2, 1)); row.setContentDescription(labels[i]); row.setStateDescription(active ? "已选择" : "未选择"); row.setFocusable(true); row.setTag("settings-choice-" + value); row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0)); row.setOnClickListener(v -> { try { save.accept(value); dismissSheet(); } catch (RuntimeException error) { toast("未保存：" + error.getMessage()); } }); modal.content.addView(row);
        }
    }
    private DetailSheet openSheet(String title) { dismissSheet(); DetailSheet modal = new DetailSheet(this, title, this::dismissSheet); modal.settingsStyle(); sheet = modal; rootLayer.addView(modal, new FrameLayout.LayoutParams(-1, -1)); modal.enter(null); return modal; }
    private void dismissSheet() { if (sheet != null) { pendingImportUri = null; rootLayer.removeView(sheet); sheet = null; } }
    private void confirm(String title, String message, String action, Runnable commit) { DetailSheet modal = openSheet(title); TextView words = SettingsUi.text(this, message, 14, SettingsUi.MUTED); words.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 14)); modal.content.addView(words); Button okay = SettingsUi.button(this, action, () -> { dismissSheet(); commit.run(); }); okay.setTag("settings-confirm"); modal.content.addView(okay); modal.content.addView(SettingsUi.button(this, "取消", this::dismissSheet)); }
    private void statusSettings() {
        begin("状态栏", "status"); LinearLayout master = group("status-master"); toggle(master, "常驻状态栏", "亮屏且解锁时显示；以下大小与留白也用于控制中心", "status_enabled", true);
        LinearLayout appearance = group("status-appearance");
        slider(appearance, "显示大小", "status_scale", 50, 150, prefs.statusScale(), "%", value -> {}, value -> prefs.data.edit().putInt("status_scale", value).apply());
        LinearLayout safe = group("status-safe-area"); slider(safe, "左侧留白", "status_safe_left", 0, 48, prefs.statusSafeLeft(), "dp", value -> {}, value -> prefs.data.edit().putInt("status_safe_left", value).apply());
        slider(safe, "右侧留白", "status_safe_right", 0, 48, prefs.statusSafeRight(), "dp", value -> {}, value -> prefs.data.edit().putInt("status_safe_right", value).apply());
        note("留白不随大小缩小；常驻栏关闭后仍影响控制中心。");
        LinearLayout contents = group("status-content");
        choice(contents, "显示项目预设", "status-preset", new String[]{"极简", "日常", "全部"}, new Object[]{"minimal", "daily", "full"}, () -> prefs.statusPreset(), value -> { prefs.statusPreset((String) value); bindingUI = true; for (String item : Prefs.STATUS_ITEMS) { Switch toggle = body.findViewWithTag("status_" + item); if (toggle != null) toggle.setChecked(prefs.statusItem(item)); } bindingUI = false; });
        String[][] items = {
            {"time", "时间", "使用系统时间格式"},
            {"wifi", "Wi-Fi", "当前网络连接与信号强度"},
            {"battery", "电池与充电", "常驻电量百分比在下方单独设置"},
            {"notifications", "通知应用图标", "数量可自定义，超出用三个点表示"},
            {"alarm", "闹钟", "存在下次闹钟时显示"},
            {"vpn", "VPN", "VPN 连接时显示"},
            {"dnd", "免打扰", "免打扰模式开启时显示"},
            {"bluetooth", "蓝牙", "蓝牙开启时显示，已连接会高亮"},
            {"airplane", "飞行模式", "飞行模式开启时显示"},
            {"nfc", "NFC", "NFC 开启时显示"},
            {"hotspot", "移动热点", "热点开启时显示"},
            {"gps", "GPS定位", "定位开启时显示"},
            {"speed", "实时网速", "系统统计的实时收发速率，每 2 秒刷新"},
            {"cellular", "移动网络", "最多双卡，需要电话状态权限"}
        };
        for (String[] item : items) { Switch control = toggle(contents, item[1], item[2], "status_" + item[0], true); control.setOnCheckedChangeListener((button, checked) -> { if (bindingUI) return; prefs.data.edit().putBoolean("status_" + item[0], checked).apply(); SettingsUi.ValueRow row = body.findViewWithTag("status-preset"); row.value(choiceLabel(new String[]{"极简", "日常", "全部"}, new Object[]{"minimal", "daily", "full"}, prefs.statusPreset())); }); }
        LinearLayout speedOptions = group("speed-options");
        Switch preciseSpeed = toggle(speedOptions, "VPN 精确网速", "开 VPN 时按网络类型统计，同一份流量只计一次", "status_speed_precise", false);
        preciseSpeed.setOnCheckedChangeListener((button, checked) -> { if (bindingUI) return; prefs.data.edit().putBoolean("status_speed_precise", checked).apply(); if (checked && !UsageAccess.granted(this)) UsageAccess.open(this); });
        permission(speedOptions, "使用情况访问", "「VPN 精确网速」依赖此权限；未授权时按系统总量显示", () -> UsageAccess.granted(this) ? "已授权" : "未授权", () -> UsageAccess.open(this));
        LinearLayout battery = group("battery-options"); toggle(battery, "常驻电量百分比", "状态栏与控制中心始终显示电量百分比", "battery_percent", false); toggle(battery, "电源内显示电量", "在电池图标内部显示百分比数字", "battery_percent_inside", false);
        LinearLayout notifOpts = group("notification-options"); choice(notifOpts, "通知图标数量", "status-notif-count", new String[]{"2 个", "3 个", "4 个", "5 个", "6 个", "7 个", "8 个"}, new Object[]{2, 3, 4, 5, 6, 7, 8}, () -> prefs.statusNotificationCount(), value -> { prefs.statusNotificationCount((int) value); });
        LinearLayout links = group("status-links"); link(links, 0, "权限中心", "通知使用权与移动网络授权", "permissions"); link(links, 0, "悬浮栏风格", appearanceLabel(), "appearance");
    }
    private void slider(LinearLayout parent, String title, String key, int min, int max, int value, String unit, java.util.function.IntConsumer preview, java.util.function.IntConsumer save) {
        LinearLayout block = SettingsUi.column(this); block.setTag("slider-group-" + key); block.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 8)); block.addView(SettingsUi.heading(this, title, 16));
        TextView number = SettingsUi.text(this, value + " " + unit, 14, SettingsUi.ACCENT); number.setPadding(0, Ui.dp(this, 4), 0, 0); block.addView(number);
        SettingsUi.Slider bar = new SettingsUi.Slider(this, min, max, value, next -> { number.setText(next + " " + unit); preview.accept(next); }, save); bar.setTag(key); bar.setContentDescription(title + "，单位 " + unit); block.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48))); parent.addView(block);
    }
    private String appearanceLabel() { return switch (prefs.chromeStyle()) { case "black" -> "纯黑实底"; case "light" -> "透明 · 浅色图标"; case "dark" -> "透明 · 深色图标"; case "shadow" -> "透明 · 图标阴影"; default -> "透明 · 对比描边"; }; }
    private String handLabel() { return prefs.leftHand() ? "左手" : prefs.handSide().equals("right") ? "右手" : "默认"; }
    private void appearanceSettings() {
        begin("悬浮栏风格", "appearance"); LinearLayout preview = group("chrome-preview"); preview.addView(new SettingsIllustration(this, SettingsIllustration.CHROME, prefs)); note("本地图形示意 · 明暗背景对比");
        LinearLayout options = group("chrome-options"); String[] values = {"contrast", "shadow", "black", "light", "dark"}, labels = {"透明 · 对比描边", "透明 · 图标阴影", "纯黑实底", "透明 · 浅色图标", "透明 · 深色图标"};
        visualOptions(options, values, labels, prefs.chromeStyle(), value -> { prefs.data.edit().putString("chrome_style", value).apply(); preview.invalidate(); ((SettingsIllustration) preview.getChildAt(0)).invalidate(); }, "chrome-");
        note("透明描边用本地图标轮廓提高对比度，不截屏、不读取其他应用配色。");
        SettingsUi.section(body, "界面整体缩放（DPI 调节）");
        LinearLayout scale = group("flip-scale");
        slider(scale, "界面缩放", "ui_scale", 50, 200, prefs.uiScale(), "%", value -> { }, value -> prefs.data.edit().putInt("ui_scale", value).apply());
        note("按百分比缩放界面（等效修改 DPI，50%–200%），即时生效。");
        related("面板背景", "background");
    }
    private void visualOptions(LinearLayout parent, String[] values, String[] labels, String selected, Consumer<String> save, String prefix) {
        List<TextView> indicators = new ArrayList<>();
        for (int i = 0; i < values.length; i++) { String value = values[i]; LinearLayout row = SettingsUi.row(this); row.setMinimumHeight(Ui.dp(this, 56)); row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            TextView mark = SettingsUi.text(this, value.equals(selected) ? "●" : "○", 20, SettingsUi.ACCENT); mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); indicators.add(mark); row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 32), -2)); TextView label = SettingsUi.text(this, labels[i], 16, SettingsUi.TEXT); row.addView(label, new LinearLayout.LayoutParams(0, -2, 1)); row.setTag(prefix + value); row.setFocusable(true); row.setSelected(value.equals(selected)); row.setContentDescription(labels[i]); row.setStateDescription(value.equals(selected) ? "已选择" : "未选择"); row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0));
            row.setOnClickListener(v -> { try { save.accept(value); } catch (RuntimeException error) { toast("未保存：" + error.getMessage()); return; } for (int j = 0; j < values.length; j++) { boolean active = values[j].equals(value); indicators.get(j).setText(active ? "●" : "○"); View sibling = (View) indicators.get(j).getParent(); sibling.setSelected(active); sibling.setStateDescription(active ? "已选择" : "未选择"); } }); parent.addView(row);
        }
    }
    private void backgroundSettings() { begin("面板背景", "background"); LinearLayout bgGroup = group("background-options"); toggle(bgGroup, "背景模糊", "通知、控制中心与多任务使用液态玻璃，应用中心使用背景模糊", "panel_blur", true); note("打开时取一帧所选外屏背景，仅存内存，关闭即释放。"); related("问题诊断", "diagnostics"); }
    private void gestureSettings() {
        begin("手势与导航避让", "gestures"); LinearLayout illustration = group("gesture-preview"); illustration.addView(new SettingsIllustration(this, SettingsIllustration.GESTURES, prefs)); note("白条默认隐藏，触摸入口保留：0° 在摄像头上方，其余方向在右下。左侧上滑打开通知，右侧上滑打开控制中心。");
        LinearLayout gestures = group("gesture-options"); toggle(gestures, "白条上滑", "白条可见时长按可切换快捷按钮，隐藏时长按无效", "gestures_enabled", true); toggle(gestures, "震动反馈", "遵循系统震动设置", "haptics", true);
        LinearLayout navigation = group("navigation-options"); toggle(navigation, "快捷按钮避让导航区", "仅移动快捷按钮；白条独立避开摄像头与系统强制手势区", "avoid_navigation", false); slider(navigation, "快捷按钮额外向内偏移", "navigation_gap", 0, 48, prefs.navigationGap(), "dp", value -> { }, value -> prefs.data.edit().putInt("navigation_gap", value).apply());
        note("快捷按钮滑动翻页；收起后仍保留上滑入口。系统 Home 手势不受影响。");
        related("按钮与布局", "dock");
    }
    private void handSettings() {
        begin("单手布局", "hand"); LinearLayout preview = group("hand-preview"); addDockPreview(preview); LinearLayout handGroup = group("hand-options"); visualOptions(handGroup, new String[]{"auto", "left", "right"}, new String[]{"默认", "左手", "右手"}, prefs.handSide(), value -> { prefs.data.edit().putString("hand_side", value).apply(); preview.removeAllViews(); addDockPreview(preview); }, "hand-");
        note("调整固定键、侧栏与工具区顺序；摄像头缺口不会镜像。");
    }
    private void addDockPreview(LinearLayout parent) {
        FrameLayout preview = new FrameLayout(this); preview.setTag("dock-preview"); parent.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 72)));
        preview.post(() -> { if (isDestroyed() || !preview.isAttachedToWindow()) return; int width = Math.min(preview.getWidth(), Ui.dp(this, prefs.perPage() * 44)), height = Ui.dp(this, 44); if (width <= 0) return;
            DockGeometry.Placement placement = new DockGeometry.Placement(new DockGeometry.Box(0, Ui.dp(this, 8), width, Ui.dp(this, 32)), new DockGeometry.Box(0, 0, width, height), new DockGeometry.Box(0, 0, width, height), DockGeometry.BOTTOM, false);
            DockView dock = new DockView(this, prefs, placement, 0, new DockView.Listener() { @Override public void action(String id) { toast("预览：" + ActionCatalog.label(MainActivity.this, id)); } @Override public void configure() { } }); preview.addView(dock, new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        });
    }
    private void visibilitySettings() { begin("自动显示与收起", "visibility"); LinearLayout group = group("visibility-options"); toggle(group, "按前台应用自动收起", "桌面与系统面板隐藏快捷按钮，底部上滑入口保留", "dock_auto_hide", true); toggle(group, "键盘出现时收起", "仅检测外屏输入法窗口，收起后恢复", "avoid_keyboard", true); SettingsUi.ValueRow excludeRow = SettingsUi.valueRow(this, "按应用排除栏位", excludeAppsCount() + " 个应用", null); excludeRow.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0)); excludeRow.setOnClickListener(v -> chooseExcludeAppsMerged(excludeRow)); group.addView(excludeRow); SettingsUi.ValueRow compactRow = SettingsUi.valueRow(this, "指定应用隐藏快捷按钮", prefs.compactApps().size() + " 个应用", null); compactRow.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0)); compactRow.setOnClickListener(v -> showRoute("visibility_apps")); group.addView(compactRow); note("所选应用在前台时自动隐藏对应栏位；每个应用可分别排除通知栏与快捷栏，已勾选的置顶显示。白条可见时长按可临时切换按钮。"); }
    private void applicationList() {
        begin("指定应用隐藏快捷按钮", "visibility_apps");
        SettingsApplications list = new SettingsApplications(this, prefs, "visibility", pageState, entry -> { });
        showApplications(list);
    }
    private void showApplications(SettingsApplications list) { outer.removeView(scroll); body = list; outer.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); }
    private void editor(String kind, boolean ignored) {
        editing = kind;
        if (kind.equals("favorites")) { begin("侧栏常用项", "favorites"); link(group("favorite-options"), 0, "编辑常用项", prefs.actions("favorites").size() + " 个 · 应用与工具", "order"); note("侧栏常用项与底部固定应用独立。"); return; }
        begin(kind.equals("dock") ? "按钮与布局" : "控制中心", kind);
        if (kind.equals("dock")) {
            LinearLayout preview = group("dock-preview-group"); addDockPreview(preview);
            choice(preview, "每页按钮", "dock-count", new String[]{"2 个", "3 个", "4 个", "5 个"}, new Object[]{2, 3, 4, 5}, () -> prefs.perPage(), value -> { prefs.data.edit().putInt("per_page", (int) value).apply(); View old = preview.findViewWithTag("dock-preview"); preview.removeView(old); addDockPreview(preview); });
            LinearLayout options = group("dock-options"); options.addView(SettingsUi.valueRow(this, "固定按钮", ActionCatalog.label(this, prefs.pinnedAction()), () -> { editing = "pinned"; library("builtin", false); }));
            choice(options, "Home 按钮效果", "dock-home-action", new String[]{"返回锁屏页面（时钟首页）", "返回应用中心（需 Shizuku）", "返回第三方启动器"}, new Object[]{"clock", "cards", "launcher"}, () -> prefs.homeAction(), value -> prefs.data.edit().putString("home_action", (String) value).apply());
            choice(options, "起步阻尼", "dock-damping", new String[]{"轻", "标准", "强"}, new Object[]{0, 1, 2}, () -> prefs.damping(), value -> prefs.data.edit().putInt("damping", (int) value).apply());
            options.addView(SettingsUi.valueRow(this, "按钮顺序", prefs.scrollingActions().size() + " 个翻页按钮", () -> orderEditor("dock"))); note("每页包含 1 个固定按钮，其余按钮可滑动翻页。"); note("返回锁屏页面：回到三星时钟首页，不锁定手机。返回应用中心：需 Shizuku，回到三星卡片页。"); related("自动显示与收起", "visibility"); return;
        }
        LinearLayout preview = group("panel-preview-group"); Button toggle = SettingsUi.button(this, panelPreviewExpanded ? "收起布局预览" : "展开布局预览", () -> { panelPreviewExpanded = !panelPreviewExpanded; updatePanelPreview(); }); toggle.setTag("panel-preview-toggle"); preview.addView(toggle); panelPreviewHost = SettingsUi.column(this); preview.addView(panelPreviewHost); updatePanelPreview();
        LinearLayout layout = group("panel-layout-options");
        choice(layout, "布局预设", "panel-preset", new String[]{"标准 · 4 列", "紧凑 · 5 列", "易点 · 3 列"}, new Object[]{"standard", "compact", "easy"}, () -> prefs.panelPreset(), value -> { try { prefs.panelPreset((String) value); updatePanelRows(); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage()); } });
        panelChoice(layout, "每行图标数量", "columns", new String[]{"3 列", "4 列", "5 列"}, new Object[]{3, 4, 5}, () -> prefs.panelColumns());
        panelChoice(layout, "排列疏密", "density", new String[]{"紧凑", "标准", "宽松"}, new Object[]{0, 1, 2}, () -> prefs.panelDensity());
        panelToggle(layout, "显示按钮名称", "隐藏后仍保留无障碍名称与长按详情", "labels", prefs.panelLabels());
        panelChoice(layout, "名称大小", "labelSize", new String[]{"小", "标准", "大"}, new Object[]{0, 1, 2}, () -> prefs.panelLabelSize());
        LinearLayout tools = group("panel-tool-options"); panelChoice(tools, "工具区位置", "toolsPosition", new String[]{"自动", "侧边", "下方"}, new Object[]{"auto", "side", "bottom"}, () -> prefs.panelToolsPosition());
        panelToggle(tools, "外屏亮度", "仅控制所选外屏", "brightness", prefs.panelBrightness()); panelToggle(tools, "媒体音量", "调节整个手机的媒体音量", "volume", prefs.panelVolume()); panelToggle(tools, "媒体卡", "关闭后释放会话订阅", "media", prefs.panelMedia()); panelToggle(tools, "闲置媒体收纳", "没有会话时缩为入口", "mediaIdle", prefs.panelMediaIdle());
        LinearLayout buttons = group("panel-buttons"); buttons.addView(SettingsUi.valueRow(this, "快捷按钮", prefs.actions("panel").size() + " 个 · 排序、添加和移除", () -> orderEditor("panel")));
        LinearLayout restore = group("panel-restore"); View reset = SettingsUi.settingRow(this, 0, "恢复布局默认", "只恢复布局、名称与工具区，可撤销", () -> confirm("恢复控制中心布局？", "不会替换按钮、目标外屏、权限或共享外观。", "恢复默认", () -> { try { prefs.resetPanelSettings(); updatePanelRows(); } catch (Exception e) { toast("恢复失败：" + e.getMessage()); } })); reset.setTag("panel-reset"); restore.addView(reset);
        View undo = SettingsUi.settingRow(this, 0, "撤销上次布局调节", "恢复最近一次调整前的布局", () -> { try { prefs.undoPanelSettings(); updatePanelRows(); } catch (Exception e) { toast("撤销失败：" + e.getMessage()); } }); undo.setTag("panel-undo"); restore.addView(undo);
        LinearLayout links = group("panel-links"); link(links, 0, "状态栏", "时间与通知在左，网络与电池在右", "status"); link(links, 0, "面板背景", "与应用中心共用", "background"); updatePanelRows();
    }
    private void panelChoice(LinearLayout parent, String title, String key, String[] labels, Object[] values, Supplier<Object> selected) { choice(parent, title, "panel-option-" + key, labels, values, selected, value -> changePanelOption(key, value)); statusUpdates.add(() -> { SettingsUi.ValueRow row = body.findViewWithTag("panel-option-" + key); if (row != null) row.value(choiceLabel(labels, values, selected.get())); }); }
    private void panelToggle(LinearLayout parent, String title, String summary, String key, boolean checked) { Switch toggle = SettingsUi.toggle(parent, title, summary, checked, value -> { if (!bindingUI) changePanelOption(key, value); }); toggle.setTag("panel-option-" + key); }
    private void changePanelOption(String key, Object value) { try { prefs.applyPanelSettings(prefs.panelSnapshot().put(key, value)); updatePanelRows(); } catch (Exception error) { throw new IllegalArgumentException(error.getMessage()); } }
    private void updatePanelRows() {
        if (!route.equals("panel")) return; for (Runnable update : List.copyOf(statusUpdates)) update.run();
        SettingsUi.ValueRow preset = body.findViewWithTag("panel-preset"); if (preset != null) preset.value(choiceLabel(new String[]{"标准 · 4 列", "紧凑 · 5 列", "易点 · 3 列"}, new Object[]{"standard", "compact", "easy"}, prefs.panelPreset()));
        bindingUI = true; try { JSONObject snapshot = prefs.panelSnapshot(); for (String key : new String[]{"labels", "brightness", "volume", "media", "mediaIdle"}) { Switch toggle = body.findViewWithTag("panel-option-" + key); if (toggle != null) toggle.setChecked(snapshot.getBoolean(key)); } } catch (Exception ignored) { } finally { bindingUI = false; }
        View labelSize = body.findViewWithTag("panel-option-labelSize"); if (labelSize != null) labelSize.setVisibility(prefs.panelLabels() ? View.VISIBLE : View.GONE);
        Switch idle = body.findViewWithTag("panel-option-mediaIdle"); if (idle != null) ((View) idle.getParent()).setVisibility(prefs.panelMedia() ? View.VISIBLE : View.GONE);
        View undo = body.findViewWithTag("panel-undo"); if (undo != null) { undo.setEnabled(prefs.hasPanelUndo()); undo.setAlpha(prefs.hasPanelUndo() ? 1 : .55f); } updatePanelPreview();
    }
    private void updatePanelPreview() {
        if (panelPreviewHost == null) return; Button button = body.findViewWithTag("panel-preview-toggle"); if (button != null) button.setText(panelPreviewExpanded ? "收起布局预览" : "展开布局预览"); panelPreviewHost.removeAllViews();
        if (panelPreviewExpanded) { TextView info = SettingsUi.text(this, "布局示意 · 操作停用，非实时设备状态", 14, SettingsUi.MUTED); info.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 8)); panelPreviewHost.addView(info); CoverService previewOwner = new CoverService(); previewOwner.screenContext = this; previewOwner.prefs = prefs; View content = new Panels(previewOwner, true).build("controls"); content.setTag("panel-settings-preview"); content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); panelPreviewHost.addView(content); }
    }
    private List<String> savedActions(String kind) { return kind.equals("hub_pin") ? prefs.hubPins() : kind.equals("dock") ? prefs.scrollingActions() : prefs.actions(kind); }
    private void orderEditor(String kind) {
        editing = kind; boolean fresh = draft == null || !kind.equals(draftKind);
        if (fresh) { draft = new ArrayList<>(savedActions(kind)); draftKind = kind; }
        begin(getResources().getConfiguration().fontScale > 1.3f ? "编辑" : kind.equals("hub_pin") ? "编辑固定应用" : "编辑快捷项", "order");
        if (fresh) orderState = new Bundle();
        Button done = SettingsUi.button(this, "完成", () -> { try { if (draftKind.equals("hub_pin")) prefs.saveHubPins(draft); else prefs.saveActions(draftKind, draft); draft = null; draftKind = null; popPage(); } catch (RuntimeException e) { toast("未保存：" + e.getMessage()); } }); done.setTag("order-done"); done.setBackground(Ui.ripple(this, 0, 16)); ((LinearLayout) outer.getChildAt(0)).addView(done);
        LinearLayout actions = SettingsUi.row(this); actions.setTag("order-actions"); actions.setPadding(Ui.dp(this, 14), 0, 0, 0);
        orderCount = SettingsUi.text(this, "", 14, SettingsUi.MUTED); orderCount.setTag("order-count"); actions.addView(orderCount, new LinearLayout.LayoutParams(0, -2, 1));
        orderAdd = SettingsUi.button(this, "+ 添加", () -> library(kind.equals("hub_pin") ? "apps" : "builtin", false)); orderAdd.setTag("order-add"); actions.addView(orderAdd); body.addView(actions);
        actions.setPadding(Ui.dp(this, 14), 0, 0, Ui.dp(this, 8));
        orderList = new SettingsOrderList(this, draft, scroll, orderState, this::orderOptions, this::updateOrderState); body.addView(orderList);
        LinearLayout undo = SettingsUi.row(this); undo.setTag("order-undo-bar"); undo.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 8), Ui.dp(this, 4)); undo.setBackgroundColor(SettingsUi.SURFACE);
        orderUndoLabel = SettingsUi.text(this, "", 14, SettingsUi.TEXT); orderUndoLabel.setSingleLine(); orderUndoLabel.setEllipsize(android.text.TextUtils.TruncateAt.END); undo.addView(orderUndoLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Button undoButton = SettingsUi.button(this, "撤销", () -> orderList.undo()); undoButton.setTag("order-undo"); undo.addView(undoButton); orderUndoBar = undo; outer.addView(undo); updateOrderState();
    }
    private void updateOrderState() {
        int limit = "hub_pin".equals(draftKind) ? AppDockPlacement.LIMIT : 30;
        orderCount.setText(draft.size() + " / " + limit + (getResources().getConfiguration().fontScale > 1.3f ? "" : " · 长按拖动")); orderAdd.setEnabled(draft.size() < limit);
        orderAdd.setContentDescription(draft.size() < limit ? "添加快捷项" : "已达到 " + limit + " 项上限");
        boolean undo = orderList.canUndo() && draft.size() < limit; orderUndoBar.setVisibility(undo ? View.VISIBLE : View.GONE);
        orderUndoLabel.setText(undo ? "已移除 " + orderList.removedLabel() : "");
    }
    private void orderOptions(String id) {
        int position = draft.indexOf(id); if (position < 0) return;
        ArrayList<String> labels = new ArrayList<>(); ArrayList<Object> positions = new ArrayList<>();
        if (position > 0) { labels.add("上移"); positions.add(position - 1); if (position > 1) { labels.add("移到顶部"); positions.add(0); } }
        if (position + 1 < draft.size()) { labels.add("下移"); positions.add(position + 1); if (position + 2 < draft.size()) { labels.add("移到底部"); positions.add(draft.size() - 1); } }
        labels.add("移除"); positions.add(-1);
        DetailSheet modal = openSheet(ActionCatalog.label(this, id));
        for (int i = 0; i < labels.size(); i++) { int target = (int) positions.get(i); View action = SettingsUi.settingRow(this, target < 0 ? R.drawable.ic_ms_close : target < position ? R.drawable.ic_ms_keyboard_arrow_up : R.drawable.ic_ms_keyboard_arrow_down, labels.get(i), "", () -> { dismissSheet(); if (target < 0) orderList.remove(id); else orderList.move(id, target); }); action.setTag("order-position-" + target); modal.content.addView(action); }
    }
    private void hubSettings() {
        begin("应用 Dock 与中心", "hub"); LinearLayout group = group("hub-options"); group.addView(SettingsUi.valueRow(this, "底部固定应用", prefs.hubPins().size() + "/" + AppDockPlacement.LIMIT + " 个", () -> orderEditor("hub_pin"))); link(group, 0, "侧栏常用项", prefs.actions("favorites").size() + " 个 · 与固定应用独立", "favorites"); group.addView(SettingsUi.settingRow(this, 0, "固定键使用应用中心", ActionCatalog.label(this, prefs.pinnedAction()), () -> { prefs.pin("app_hub"); hubSettings(); }));
        if (!prefs.hubPins().isEmpty()) group.addView(SettingsUi.settingRow(this, 0, "移除全部固定应用", "不会移除侧栏常用项", () -> confirm("移除全部固定应用？", "只清空底部固定列表，不卸载应用。", "移除", () -> { prefs.saveHubPins(List.of()); hubSettings(); })));
        note("快捷栏临时呼出，九宫格原位展开全部应用；打开应用或确认清理后关闭。");
    }
    private void library(String kind, boolean launchOnly) {
        category = kind; begin(launchOnly ? "打开应用" : editing.equals("pinned") ? "选择固定按钮" : "添加快捷项", launchOnly ? "apps" : "library");
        if (!launchOnly) {
            renderedLibrary = kind; Bundle remembered = libraryStates.getBundle(kind); pageState = remembered == null ? new Bundle() : new Bundle(remembered);
            if (!editing.equals("hub_pin")) libraryTabs(kind);
            SettingsNavigator.restoreViews(scroll, body, pageState);
        }
        if (kind.equals("apps")) {
            SettingsApplications list = new SettingsApplications(this, prefs, "picker", pageState, entry -> { if (launchOnly) launchApplication(entry.id()); else addSelected(entry.id()); });
            if (!launchOnly) list.pickerSelection(this::librarySelected); showApplications(list); return;
        }
        if (kind.equals("tiles")) note("应用磁贴为实验功能；添加到本工具不会自动注册或授权。");
        LinearLayout list = group("library-items");
        worker.execute(() -> { List<String> all = kind.equals("tiles") ? ActionCatalog.applicationTiles(this) : ActionCatalog.BUILT_INS.stream().map(ActionCatalog.Action::id).collect(java.util.stream.Collectors.toList());
            // 底部快捷栏不再提供旋转按钮（免 Shizuku 下不可靠）；控制中心仍保留。
            List<String> ids = "dock".equals(editing) ? all.stream().filter(id -> !"rotation".equals(id)).collect(java.util.stream.Collectors.toList()) : all;
            main.post(() -> { if (isDestroyed() || !list.isAttachedToWindow()) return;
                for (String id : ids) {
                    LinearLayout row = SettingsUi.shortcutRow(this, id, ActionCatalog.label(this, id), "", ActionCatalog.icon(this, id), librarySelected(id), () -> addSelected(id)); list.addView(row);
                    if (id.startsWith("tile:")) { Button register = SettingsUi.button(this, "注册到系统快捷设置", () -> CoverApp.bridge(this).run("tile_add", -1, 0, ActionCatalog.component(id).flattenToString(), result -> toast(result.message))); register.setContentDescription("将“" + ActionCatalog.label(this, id) + "”注册到系统快捷设置"); LinearLayout.LayoutParams entry = new LinearLayout.LayoutParams(-1, -2); entry.setMarginStart(Ui.dp(this, 64)); list.addView(register, entry); }
                } if (ids.isEmpty()) list.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_apps, "没有可添加的应用磁贴", "安装提供快捷设置磁贴的应用后，可在这里选择", null));
                SettingsNavigator.restoreViews(scroll, body, pageState);
            });
        });
    }
    private boolean librarySelected(String id) { return editing.equals("pinned") ? prefs.pinnedAction().equals(id) : draft != null ? draft.contains(id) || editing.equals("dock") && prefs.pinnedAction().equals(id) : savedActions(editing).contains(id); }
    private void saveLibraryState() { if (renderedLibrary != null) libraryStates.putBundle(renderedLibrary, SettingsNavigator.capture(scroll, body)); }
    private void libraryTabs(String selected) {
        outer.addView(SettingsUi.sourceTabs(this, selected, id -> library(id, false)), 1, new LinearLayout.LayoutParams(-1, -2));
    }
    private void addSelected(String id) {
        if (editing.equals("pinned")) { prefs.pin(id); popPage(); return; }
        if (draft == null) { draftKind = editing; draft = new ArrayList<>(savedActions(editing)); }
        int limit = editing.equals("hub_pin") ? AppDockPlacement.LIMIT : 30;
        if (draft.contains(id) || editing.equals("dock") && prefs.pinnedAction().equals(id)) { toast("已在列表中"); return; }
        if (draft.size() >= limit) { toast("最多 " + limit + " 个"); return; }
        draft.add(id); toast("已添加，点「完成」保存"); popPage();
    }
    private void launchApplication(String id) {
        Display display = Displays.selected(this, prefs); if (display == null) { toast("请先选择外屏"); return; }
        if (prefs.enabled() && CoverService.instance != null && CoverService.instance.display != null && CoverService.instance.display.getDisplayId() == display.getDisplayId()) { CoverService.instance.launchApp(id); return; }
        if (prefs.appRotation(ActionCatalog.component(id).getPackageName()) >= 0) toast("FlipAuto 未运行，沿用当前方向");
        try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ActionCatalog.component(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle()); }
        catch (RuntimeException e) { toast("应用不能在此外屏启动：" + e.getClass().getSimpleName()); }
    }
    private void flipSettings() {
        begin("外屏增强", "flip");
        SettingsUi.section(body, "悬浮气泡（小窗恢复）");
        LinearLayout bubbles = group("flip-bubbles");
        toggle(bubbles, "悬浮气泡", "外屏小窗应用显示可拖动气泡，点击启动或收起", "flip_bubbles", true);
        slider(bubbles, "气泡大小", "flip_bubble_size", 40, 100, prefs.flipBubbleSize(), "dp", value -> { }, value -> prefs.data.edit().putInt("flip_bubble_size", value).apply());
        SettingsUi.section(body, "开屏恢复与自动启动");
        LinearLayout restore = group("flip-restore");
        toggle(restore, "开屏恢复应用", "合盖再展开时自动恢复上次使用的外屏应用", "flip_restore", true);
        slider(restore, "恢复延迟", "flip_restore_delay", 0, 5000, prefs.flipRestoreDelay(), "毫秒", value -> { }, value -> prefs.data.edit().putInt("flip_restore_delay", value).apply());
        toggle(restore, "充电时禁用恢复", "充电状态下不自动恢复上次应用", "flip_restore_charging", false);
        toggle(restore, "应用自动启动", "检测到外屏主页或 AOD 时自动启动目标应用", "flip_auto_launch", true);
        toggle(restore, "检测外屏主页", "外屏主页出现时触发自动启动", "flip_detect_subhome", true);
        toggle(restore, "检测 AOD 界面", "更灵敏，但可能更耗电", "flip_detect_aod", false);
        SettingsUi.ValueRow target = SettingsUi.valueRow(this, "自动启动目标应用", prefs.flipTargetApp().isEmpty() ? "未选择" : prefs.flipTargetApp(), null);
        target.setTag("flip-target"); target.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0));
        target.setOnClickListener(v -> chooseTargetApp(target)); restore.addView(target);
        SettingsUi.ValueRow excludedRow = SettingsUi.valueRow(this, "恢复排除列表", prefs.flipExcluded().isEmpty() ? "未排除任何应用" : prefs.flipExcluded().size() + " 个应用已排除", null);
        excludedRow.setTag("flip-excluded"); excludedRow.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0));
        excludedRow.setOnClickListener(v -> chooseExcludedApps(excludedRow, "restore")); restore.addView(excludedRow);
        SettingsUi.ValueRow bubbleExcludedRow = SettingsUi.valueRow(this, "气泡排除列表", prefs.flipBubbleExcluded().isEmpty() ? "未排除任何应用" : prefs.flipBubbleExcluded().size() + " 个应用已排除", null);
        bubbleExcludedRow.setTag("flip-bubble-excluded"); bubbleExcludedRow.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0));
        bubbleExcludedRow.setOnClickListener(v -> chooseExcludedApps(bubbleExcludedRow, "bubble")); restore.addView(bubbleExcludedRow);
        note("被排除的应用不会被开屏恢复记录，也不显示悬浮气泡。列表含已安装与系统包。");
        SettingsUi.section(body, "免 Shizuku 旋转");
        LinearLayout rotate = group("flip-rotate");
        choice(rotate, "旋转模式", "flip-rotation-mode",
                new String[]{"自动旋转", "手动锁定", "关闭"},
                new Object[]{RotationManager.MODE_AUTO, RotationManager.MODE_MANUAL, RotationManager.MODE_OFF},
                () -> RotationManager.readMode(this),
                value -> { int m = (Integer) value; RotationManager.writeMode(this, m); if (CoverService.instance != null) CoverService.instance.setNoRootRotationMode(m); });
        note("仅免 Shizuku 模式生效：自动跟随传感器，手动锁定方向，关闭则不干预。需要悬浮窗权限。");
        SettingsUi.section(body, "调试");
        LinearLayout debug = group("flip-debug");
        debug.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_info, "恢复调试日志",
            "查看开屏恢复、自动启动、状态栏等调试日志", this::showFlipLog));
        debug.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_delete, "清空调试日志",
            "清空后立即操作一次开屏，便于对照", () -> { FlipLog.clear(); toast("调试日志已清空"); }));
        note("复现步骤：清空日志 → 合盖 → 在外屏使用应用 → 展开 → 再次合盖，回到此页查看。" +
             "日志中 cov=ON/OFF 为外屏亮灭，d= 为事件上报的 displayId（多数机型恒为 0，属正常）。");
        related("特权模式与授权", "permissions");
    }

    private void showFlipLog() {
        DetailSheet modal = openSheet("恢复调试日志");
        TextView text = SettingsUi.text(this, FlipLog.dump(), 12, SettingsUi.TEXT);
        text.setTextIsSelectable(true);
        text.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 14));
        modal.content.addView(text);
        modal.content.addView(SettingsUi.button(this, "复制全部", () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(
                ClipData.newPlainText("FlipLog", FlipLog.dump()));
            toast("日志已复制");
        }));
    }

    private static final class AppItem {
        final String label, pkg; final Drawable icon;
        AppItem(String label, String pkg, Drawable icon) { this.label = label; this.pkg = pkg; this.icon = icon; }
    }

    /** 统计排除应用数（去重：同时排除通知栏和dock栏的应用只算一次） */
    private int excludeAppsCount() {
        java.util.Set<String> all = new java.util.HashSet<>(prefs.excludeNotificationApps());
        all.addAll(prefs.excludeDockApps());
        return all.size();
    }

    /** 选择排除通知栏/dock栏的应用。type="notification" 或 "dock"。 */
    /** 合并版排除列表：每个应用两个独立开关（排除通知栏 / 排除 dock 栏），已勾选的置顶。 */
    private void chooseExcludeAppsMerged(SettingsUi.ValueRow row) {
        DetailSheet modal = openSheet("按应用排除栏位");
        modal.compactWidth(380, 1);
        EditText input = search("搜索应用名称或包名", "exclude-merged-search");
        modal.content.addView(input);
        final boolean[] showAllPkgs = {false};
        final Runnable[] updateRef = new Runnable[1];
        LinearLayout options = new LinearLayout(this); options.setOrientation(LinearLayout.HORIZONTAL); options.setGravity(Gravity.CENTER_VERTICAL);
        options.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        TextView optLabel = new TextView(this); optLabel.setText("显示所有包名（含系统应用）"); optLabel.setTextColor(SettingsUi.TEXT); optLabel.setTextSize(13);
        options.addView(optLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Switch showAllSwitch = new Switch(this); showAllSwitch.setOnCheckedChangeListener((v, checked) -> { showAllPkgs[0] = checked; updateRef[0].run(); });
        options.addView(showAllSwitch);
        modal.content.addView(options);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        modal.content.addView(list);
        java.util.Set<String> notifSet = new java.util.HashSet<>(prefs.excludeNotificationApps());
        java.util.Set<String> dockSet = new java.util.HashSet<>(prefs.excludeDockApps());
        PackageManager pm = getPackageManager();
        updateRef[0] = () -> {
            list.removeAllViews();
            List<AppItem> items = new ArrayList<>();
            java.util.Set<String> seenPkgs = new java.util.HashSet<>();
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                seenPkgs.add(pkg);
                String label; try { label = ri.loadLabel(pm).toString(); } catch (RuntimeException e) { label = pkg; }
                Drawable icon; try { icon = ri.loadIcon(pm); } catch (RuntimeException e) { icon = null; }
                items.add(new AppItem(label, pkg, icon));
            }
            if (showAllPkgs[0]) {
                for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                    if (ai.packageName.equals(getPackageName()) || seenPkgs.contains(ai.packageName)) continue;
                    seenPkgs.add(ai.packageName);
                    String label; try { label = pm.getApplicationLabel(ai).toString(); } catch (RuntimeException e) { label = ai.packageName; }
                    Drawable icon; try { icon = pm.getApplicationIcon(ai); } catch (RuntimeException e) { icon = null; }
                    items.add(new AppItem(label, ai.packageName, icon));
                }
            }
            String q = input.getText() == null ? "" : input.getText().toString().trim().toLowerCase();
            // 已勾选（任一开关开启）的置顶，按勾选时间倒序；未勾选的按名称排序
            List<AppItem> ordered = new ArrayList<>(items);
            ordered.sort((a, b) -> {
                boolean ca = notifSet.contains(a.pkg) || dockSet.contains(a.pkg);
                boolean cb = notifSet.contains(b.pkg) || dockSet.contains(b.pkg);
                if (ca != cb) return ca ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            });
            for (AppItem it : ordered) {
                if (!q.isEmpty() && !it.label.toLowerCase().contains(q) && !it.pkg.toLowerCase().contains(q)) continue;
                boolean hasNotif = notifSet.contains(it.pkg);
                boolean hasDock = dockSet.contains(it.pkg);
                // 行：图标 + 名称 + 两个开关（宽卡片，减少左右边距）
                LinearLayout appRow = new LinearLayout(this);
                appRow.setOrientation(LinearLayout.HORIZONTAL); appRow.setGravity(Gravity.CENTER_VERTICAL);
                int pad = Ui.dp(this, 6); appRow.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
                appRow.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0));
                ImageView iv = new ImageView(this); if (it.icon != null) iv.setImageDrawable(it.icon);
                int iconSize = Ui.dp(this, 26); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize); ip.rightMargin = Ui.dp(this, 10);
                appRow.addView(iv, ip);
                LinearLayout texts = new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
                TextView t = new TextView(this); t.setText(it.label); t.setTextColor(SettingsUi.TEXT); t.setTextSize(14); t.setSingleLine(); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView s = new TextView(this); s.setText(it.pkg); s.setTextColor(SettingsUi.MUTED); s.setTextSize(11); s.setSingleLine(); s.setEllipsize(android.text.TextUtils.TruncateAt.END);
                texts.addView(t); texts.addView(s);
                appRow.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                // 排除通知栏开关
                Switch swNotif = SettingsUi.toggle(appRow, "通知栏", "", hasNotif, value -> {
                    if (value) notifSet.add(it.pkg); else notifSet.remove(it.pkg);
                    prefs.excludeNotificationApps(notifSet); updateRef[0].run();
                });
                swNotif.setTag("exclude-notif-" + it.pkg);
                // 排除 dock 栏开关
                Switch swDock = SettingsUi.toggle(appRow, "dock", "", hasDock, value -> {
                    if (value) dockSet.add(it.pkg); else dockSet.remove(it.pkg);
                    prefs.excludeDockApps(dockSet); updateRef[0].run();
                });
                swDock.setTag("exclude-dock-" + it.pkg);
                list.addView(appRow);
            }
        };
        watch(input, updateRef[0]);
        updateRef[0].run();
        modal.content.addView(SettingsUi.button(this, "完成", () -> {
            java.util.Set<String> all = new java.util.HashSet<>(notifSet);
            all.addAll(dockSet);
            int total = all.size();
            row.value(total == 0 ? "未设置" : total + " 个应用");
            dismissSheet();
        }));
    }

    private void chooseTargetApp(SettingsUi.ValueRow row) {
        DetailSheet modal = openSheet("选择自动启动目标应用");
        EditText input = search("搜索应用名称或包名", "flip-app-search");
        modal.content.addView(input);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        modal.content.addView(list);
        PackageManager pm = getPackageManager();
        List<AppItem> items = new ArrayList<>();
        // 列出所有已安装应用（包括无启动器图标的系统应用），用户可任选一个作为自动启动目标
        List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (android.content.pm.ApplicationInfo ai : apps) {
            String pkg = ai.packageName;
            if (pkg.equals(getPackageName())) continue;
            String label;
            try { label = ai.loadLabel(pm).toString(); } catch (RuntimeException e) { label = pkg; }
            Drawable icon;
            try { icon = ai.loadIcon(pm); } catch (RuntimeException e) { icon = null; }
            items.add(new AppItem(label, pkg, icon));
        }
        items.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        Runnable update = () -> {
            list.removeAllViews();
            String q = input.getText() == null ? "" : input.getText().toString().trim().toLowerCase();
            for (AppItem it : items) {
                if (!q.isEmpty() && !it.label.toLowerCase().contains(q) && !it.pkg.toLowerCase().contains(q)) continue;
                list.addView(appRow(it.icon, it.label, it.pkg, () -> {
                    prefs.data.edit().putString("flip_target_app", it.pkg).apply();
                    // 自动将选中的启动器加入悬浮球排除列表
                    java.util.Set<String> excluded = new java.util.HashSet<>(prefs.flipBubbleExcluded());
                    if (!excluded.contains(it.pkg)) {
                        excluded.add(it.pkg);
                        prefs.flipBubbleExcluded(excluded);
                        toast("已将 " + it.label + " 加入悬浮球排除列表");
                    }
                    row.value(it.label);
                    dismissSheet();
                }));
            }
        };
        watch(input, update);
        update.run();
        modal.content.addView(SettingsUi.button(this, "不选择（清除）", () -> { prefs.data.edit().putString("flip_target_app", "").apply(); row.value("未选择"); dismissSheet(); }));
    }

    /** 应用排除列表：多选应用（含未安装的默认系统包），type="restore" 恢复排除，type="bubble" 气泡排除。 */
    private void chooseExcludedApps(SettingsUi.ValueRow row, String type) {
        boolean isBubble = "bubble".equals(type);
        String title = isBubble ? "气泡排除列表" : "恢复排除列表";
        java.util.Set<String> defaultSet = isBubble ? Prefs.DEFAULT_BUBBLE_EXCLUDED : Prefs.DEFAULT_EXCLUDED;
        DetailSheet modal = openSheet(title);
        EditText input = search("搜索应用名称或包名", "flip-excluded-search-" + type);
        modal.content.addView(input);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        modal.content.addView(list);
        java.util.Set<String> excluded = new java.util.HashSet<>(isBubble ? prefs.flipBubbleExcluded() : prefs.flipExcluded());
        PackageManager pm = getPackageManager();
        List<AppItem> items = new ArrayList<>();
        java.util.Set<String> seenPkgs = new java.util.HashSet<>();
        // 已安装应用
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            seenPkgs.add(pkg);
            String label; try { label = ri.loadLabel(pm).toString(); } catch (RuntimeException e) { label = pkg; }
            Drawable icon; try { icon = ri.loadIcon(pm); } catch (RuntimeException e) { icon = null; }
            items.add(new AppItem(label, pkg, icon));
        }
        // 未安装的默认排除包（显示为「未安装」）
        for (String pkg : defaultSet) {
            if (!seenPkgs.contains(pkg) && !pkg.equals(getPackageName())) {
                items.add(new AppItem("（未安装）" + pkg, pkg, null));
            }
        }
        // 已排除但不在默认列表且未安装的包
        for (String pkg : excluded) {
            if (!seenPkgs.contains(pkg) && !defaultSet.contains(pkg) && !pkg.equals(getPackageName())) {
                items.add(new AppItem("（未安装）" + pkg, pkg, null));
            }
        }
        items.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        List<String> order = new ArrayList<>(prefs.excludedOrder());
        order.removeIf(pkg -> !excluded.contains(pkg));
        for (String pkg : excluded) if (!order.contains(pkg)) order.add(0, pkg);
        final Runnable[] updateRef = new Runnable[1];
        updateRef[0] = () -> {
            list.removeAllViews();
            String q = input.getText() == null ? "" : input.getText().toString().trim().toLowerCase();
            List<AppItem> ordered = new ArrayList<>(items);
            ordered.sort((a, b) -> {
                boolean ca = excluded.contains(a.pkg), cb = excluded.contains(b.pkg);
                if (ca != cb) return ca ? -1 : 1;
                if (ca) { int ia = order.indexOf(a.pkg), ib = order.indexOf(b.pkg); return Integer.compare(ia < 0 ? Integer.MAX_VALUE : ia, ib < 0 ? Integer.MAX_VALUE : ib); }
                return a.label.compareToIgnoreCase(b.label);
            });
            for (AppItem it : ordered) {
                if (!q.isEmpty() && !it.label.toLowerCase().contains(q) && !it.pkg.toLowerCase().contains(q)) continue;
                boolean checked = excluded.contains(it.pkg);
                list.addView(appRow(it.icon, (checked ? "✓ " : "　") + it.label, it.pkg, () -> {
                    if (checked) { excluded.remove(it.pkg); order.remove(it.pkg); }
                    else { excluded.add(it.pkg); order.remove(it.pkg); order.add(0, it.pkg); }
                    if (isBubble) prefs.flipBubbleExcluded(excluded); else prefs.flipExcluded(excluded);
                    prefs.excludedOrder(order);
                    updateRef[0].run();
                }));
            }
        };
        watch(input, updateRef[0]);
        updateRef[0].run();
        modal.content.addView(SettingsUi.button(this, "恢复默认排除", () -> {
            excluded.clear();
            excluded.addAll(defaultSet);
            if (isBubble) prefs.flipBubbleExcluded(excluded); else prefs.flipExcluded(excluded);
            order.clear(); order.addAll(defaultSet); prefs.excludedOrder(order);
            updateRef[0].run();
            row.value(excluded.size() + " 个应用已排除");
            toast("已恢复默认排除列表");
        }));
        modal.content.addView(SettingsUi.button(this, "完成", () -> {
            row.value(excluded.isEmpty() ? "未排除任何应用" : excluded.size() + " 个应用已排除");
            dismissSheet();
        }));
    }

    /** 带应用图标的可点击行（图标 + 应用名 + 包名）。 */
    private View appRow(Drawable icon, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 12); row.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0));
        ImageView iv = new ImageView(this);
        if (icon != null) iv.setImageDrawable(icon);
        int iconSize = Ui.dp(this, 28);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize); ip.rightMargin = Ui.dp(this, 12);
        row.addView(iv, ip);
        LinearLayout texts = new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this); t.setText(title); t.setTextColor(SettingsUi.TEXT); t.setTextSize(15); t.setSingleLine(); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView s = new TextView(this); s.setText(subtitle); s.setTextColor(SettingsUi.MUTED); s.setTextSize(12); s.setSingleLine(); s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t); texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private void permissions() {
        begin("权限中心", "permissions");

        // 必须权限
        SettingsUi.section(body, "必须权限");
        LinearLayout required = group("permissions-required");
        permission(required, "无障碍服务", "开屏恢复、外屏窗口检测、多任务/Home/返回、悬浮球",
            () -> CoverService.instance == null ? "未连接" : "已连接",
            () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permission(required, "悬浮窗权限", "显示状态栏、快捷栏、控制中心、通知中心等悬浮界面",
            () -> Settings.canDrawOverlays(this) ? "已允许" : "未允许",
            this::openOverlaySettings);
        permission(required, "通知使用权", "显示通知中心、媒体播放控制、状态栏通知图标",
            () -> CoverNotifications.ready() ? "已连接" : "未授权",
            () -> { Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(this, CoverNotifications.class).flattenToString()); if (!open(detail)) open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); });

        // 可选权限
        SettingsUi.section(body, "可选权限（提升体验）");
        LinearLayout optional = group("permissions-optional");
        permission(optional, "修改系统设置", "免 Shizuku 模式下直接调节亮度、旋转锁定、屏幕超时",
            () -> canWriteSettings() ? "已授权" : "未授权",
            () -> { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { try { startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception e) { toast("无法打开系统设置"); } } });
        permission(optional, "忽略电池优化", "后台保活，避免开屏恢复、自动启动被系统杀死",
            () -> isIgnoringBatteryOptimizations() ? "已允许" : "未允许",
            () -> { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + getPackageName()))); });
        permission(optional, "相机（手电筒）", "控制中心手电筒开关",
            () -> checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权",
            () -> requestPermissions(new String[]{Manifest.permission.CAMERA}, 101));
        permission(optional, "移动网络信息", "显示信号强度，不读取号码",
            () -> checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权",
            () -> requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 102));
        permission(optional, "应用列表", "应用抽屉、排除列表、自动启动目标选择",
            () -> hasAppListPermission() ? "已授权" : "未授权",
            () -> { Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS); intent.setData(android.net.Uri.parse("package:" + getPackageName())); open(intent); });
        permission(optional, "通知权限", "系统原生提示（Toast），关闭会拦截后台提示",
            () -> getSystemService(android.app.NotificationManager.class).areNotificationsEnabled() ? "已允许" : "未允许",
            () -> open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));

        // Shizuku 高级授权
        SettingsUi.section(body, "高级授权");
        LinearLayout advanced = group("permissions-advanced");
        permission(advanced, "系统控制（Shizuku）", "直接开关 WiFi/飞行模式/NFC/热点等系统限制功能",
            () -> CoverApp.bridge(this).status(),
            () -> { try { CoverApp.bridge(this).requestPermission(); } catch (RuntimeException e) { toast("请先启动 Shizuku"); } });

        // 系统开关执行方式
        SettingsUi.section(body, "系统开关执行方式");
        LinearLayout privilege = group("privilege-mode");
        choice(privilege, "特权模式", "privilege-mode-choice", new String[]{"免 Shizuku（推荐）", "Shizuku（全部）", "Shizuku（自定义授权）"}, new Object[]{"normal", "shizuku_all", "shizuku_custom"}, prefs::privilegeMode, value -> {
            prefs.privilegeMode((String) value);
            if ("shizuku_custom".equals(value)) main.post(() -> shizukuScopeMenu());
            refreshStatus();
        });
        SettingsUi.ValueRow scope = SettingsUi.valueRow(this, "Shizuku 授权范围", scopeSummary(), "shizuku_custom".equals(prefs.privilegeMode()) ? () -> { shizukuScopeMenu(); refreshStatus(); } : null);
        scope.setTag("shizuku-scope"); scope.setEnabled("shizuku_custom".equals(prefs.privilegeMode())); scope.setAlpha(scope.isEnabled() ? 1f : 0.45f); privilege.addView(scope);
        statusUpdates.add(() -> { scope.value(scopeSummary()); scope.setEnabled("shizuku_custom".equals(prefs.privilegeMode())); scope.setAlpha(scope.isEnabled() ? 1f : 0.45f); });
        note("免 Shizuku：常规开关直接切换，系统限制的开关跳转设置页。Shizuku（全部）：所有开关经 Shizuku 直切。自定义授权：仅勾选的操作走 Shizuku。");
    }

    private boolean canWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return Settings.System.canWrite(this);
        return true;
    }

    private boolean isIgnoringBatteryOptimizations() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
        if (pm == null) return false;
        try { return pm.isIgnoringBatteryOptimizations(getPackageName()); }
        catch (Exception ignored) { return false; }
    }

    private boolean hasAppListPermission() {
        try {
            java.util.List<android.content.pm.ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
            if (apps != null) { for (android.content.pm.ApplicationInfo info : apps) { if (!info.packageName.equals(getPackageName())) return true; } }
        } catch (Exception ignored) { }
        return false;
    }
    /** 打开系统悬浮窗授权页（免 Shizuku 旋转固定方向需要）。 */
    private void openOverlaySettings() {
        try { open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()))); }
        catch (RuntimeException e) { open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
    }
    private String scopeSummary() {        if (!prefs.privilegeMode().equals("shizuku_custom")) return "切换至「Shizuku（自定义授权）」后可勾选";
        java.util.Set<String> scope = prefs.shizukuScope();
        if (scope.isEmpty()) return "尚未授权任何操作";
        String[] names = {"wifi", "bluetooth", "data", "dnd", "airplane", "nfc", "hotspot", "rotation", "brightness", "recents", "system_controls"};
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String id : names) if (scope.contains(id)) labels.add(ActionCatalog.label(this, id));
        return labels.isEmpty() ? "尚未授权任何操作" : String.join("、", labels);
    }
    private void shizukuScopeMenu() {
        DetailSheet modal = openSheet("Shizuku 授权范围");
        TextView words = SettingsUi.text(this, "勾选需要 Shizuku 直切的操作，其余保持免特权方式：", 14, SettingsUi.MUTED);
        words.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 8)); modal.content.addView(words);
        String[] ids = {"wifi", "bluetooth", "data", "dnd", "airplane", "nfc", "hotspot", "rotation", "brightness", "recents", "system_controls"};
        String[] names = {"Wi-Fi", "蓝牙", "移动数据", "勿扰模式", "飞行模式", "NFC", "热点", "自动旋转", "亮度", "最近任务（玻璃多任务）", "内外屏控制中心"};
        java.util.Set<String> scope = new java.util.HashSet<>(prefs.shizukuScope());
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            Switch toggle = SettingsUi.toggle(modal.content, names[i], "", scope.contains(id), checked -> {
                if (checked) scope.add(id); else scope.remove(id);
                prefs.shizukuScope(scope);
                if (CoverService.instance != null) CoverService.instance.statesChanged();
            });
            toggle.setTag("shizuku-scope-" + id);
        }
        modal.content.addView(SettingsUi.button(this, "完成", this::dismissSheet));
    }
    private void permission(LinearLayout group, String title, String usage, Supplier<String> status, Runnable action) {
        SettingsUi.ValueRow row = SettingsUi.valueRow(this, title, usage + " · " + status.get(), action);
        group.addView(row);
        statusUpdates.add(() -> row.value(usage + " · " + status.get()));
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) { super.onRequestPermissionsResult(request, permissions, grants); if (CoverService.instance != null) CoverService.instance.permissionsChanged(); refreshStatus(); }
    private void refreshStatus() {
        if (statusLabel != null) { String state = !prefs.enabled() ? "已暂停 · 配置保留" : CoverService.instance == null ? "待授权：无障碍服务" : Displays.selected(this, prefs) == null ? "待选择目标外屏" : CoverService.status; if (!state.contentEquals(statusLabel.getText())) statusLabel.setText(state); View enabled = body.findViewWithTag("settings-enabled"); if (enabled != null) enabled.setContentDescription("开启 FlipAuto，" + state); }
        if (route.equals("permissions") || route.equals("status")) for (Runnable update : List.copyOf(statusUpdates)) update.run();
    }
    private String displaySummary() { Display display = Displays.selected(this, prefs); return display == null ? "尚未选择可用外屏" : display.getName() + (display.getState() == Display.STATE_ON ? " · 已亮起" : " · 未亮起"); }
    private void chooseDisplay() {
        begin("目标外屏", "display"); LinearLayout group = group("display-options");
        group.addView(SettingsUi.valueRow(this, "自动识别外屏", prefs.displayId() == -1 ? "已选择" : "只识别可靠的副屏", () -> { prefs.data.edit().putInt("display", -1).apply(); chooseDisplay(); }));
        for (Display display : Displays.all(this)) { boolean mainDisplay = display.getDisplayId() == Display.DEFAULT_DISPLAY; View row = SettingsUi.settingRow(this, R.drawable.ic_ms_phone_android, display.getName(), mainDisplay ? "主屏 · 不作为目标外屏" : (prefs.displayId() == display.getDisplayId() ? "已选择 · " : "") + Displays.describe(display), mainDisplay ? null : () -> { prefs.data.edit().putInt("display", display.getDisplayId()).apply(); chooseDisplay(); }); group.addView(row); }
        note("仅操作明确选择或可靠识别的外屏。目标失联时保留选择，不回退到主屏。");
    }
    private void calibrate() {
        begin("屏幕适配", "calibrate"); Display display = Displays.selected(this, prefs);
        if (display == null) { link(group("calibrate-unavailable"), 0, "先选择外屏", "未发现可用的目标显示器", "display"); return; }
        TextView cutoutStatus = note(cutoutSummary(display));
        // 放在页面最前面：一进屏幕适配就能看到，不必滚到页面底部找。
        group("calibrate-relocate").addView(SettingsUi.settingRow(this, R.drawable.ic_ms_display_settings, "重新定位缺口",
                "清除已记录的位置并重新读取，仅在快捷栏明显偏离摄像头时使用",
                () -> confirm("重新定位缺口？", "会清除已记录的缺口位置，外屏亮起后重新读取并固定。", "重新定位", () -> {
                    prefs.clearCutoutRecords(); CoverService.resetCutouts();
                    cutoutStatus.setText(cutoutSummary(display)); toast("已清除记录，外屏亮起后会重新定位");
                })));
        LinearLayout preview = group("calibration-preview");
        preview.addView(new SettingsIllustration(this, SettingsIllustration.SAFE_AREA, prefs));
        LinearLayout options = group("calibration-options"); LinearLayout manual = SettingsUi.group(this); manual.setTag("calibration-manual"); SettingsUi.add(body, manual);
        Switch automatic = SettingsUi.toggle(options, "根据系统缺口定位", "使用首次记录的缺口，按方向分别保存", prefs.data.getBoolean("auto_placement", true), enabled -> { prefs.data.edit().putBoolean("auto_placement", enabled).apply(); manual.setVisibility(enabled ? View.GONE : View.VISIBLE); }); automatic.setTag("auto_placement"); manual.setVisibility(automatic.isChecked() ? View.GONE : View.VISIBLE);
        choice(manual, "当前方向的停靠角落", "calibration-corner", new String[]{"左上", "右上", "右下", "左下"}, new Object[]{0, 1, 2, 3}, () -> prefs.corner(display.getRotation()), value -> { if (Displays.selected(this, prefs) == null || Displays.selected(this, prefs).getDisplayId() != display.getDisplayId()) throw new IllegalStateException("外屏已改变，请重新打开设置"); prefs.data.edit().putInt("corner_" + display.getRotation(), (int) value).apply(); });
        slider(manual, "快捷栏长度", "dock_width", 25, 70, Math.round(prefs.widthRatio() * 100), "%", value -> { }, value -> prefs.data.edit().putFloat("dock_width", value / 100f).apply());
        slider(manual, "可见区域厚度", "dock_height", 5, 22, Math.round(prefs.heightRatio() * 100), "%", value -> { }, value -> prefs.data.edit().putFloat("dock_height", value / 100f).apply());
        group("calibrate-stop").addView(SettingsUi.settingRow(this, 0, "暂停快捷栏", "保留当前布局配置", () -> { prefs.data.edit().putBoolean("enabled", false).apply(); toast("已暂停快捷栏"); }));
    }
    /** 屏幕适配页顶部说明：列出已记录缺口的方向，没有记录时说明会自动记录。 */
    private String cutoutSummary(Display display) {
        StringBuilder recorded = new StringBuilder();
        for (int rotation = 0; rotation < 4; rotation++) {
            if (!prefs.cutoutRecord(rotation).isEmpty()) recorded.append(recorded.length() == 0 ? "" : " / ").append(rotation * 90).append('°');
        }
        if (recorded.length() > 0) return "已记录缺口：" + recorded + "。首次安装时固定，之后不再随系统变化。";
        return display.getCutout() == null ? "系统尚未报告缺口，外屏亮起后会自动记录第一个有效缺口。" : "外屏亮起后会自动记录第一个有效缺口。";
    }
    private void layoutSettings() {
        begin("布局备份与恢复", "layout_backup"); long saved = prefs.data.getLong("layout_saved_at", 0); note(saved == 0 ? "尚未保存布局" : "已保存：" + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(new java.util.Date(saved)));
        LinearLayout backup = group("layout-actions"); backup.addView(SettingsUi.settingRow(this, 0, "保存当前布局", saved == 0 ? "保存按钮、面板、侧栏、状态栏与位置" : "替换已有本机备份", () -> { Runnable save = () -> { try { prefs.saveLayout(); layoutSettings(); toast("布局已保存"); } catch (Exception e) { toast("保存失败：" + e.getMessage()); } }; if (saved == 0) save.run(); else confirm("替换布局备份？", "当前布局将覆盖之前保存的本机备份。", "替换", save); }));
        View restore = SettingsUi.settingRow(this, 0, "恢复已保存布局", "保留外屏选择、权限和系统显示设置", () -> confirm("恢复已保存布局？", "覆盖当前布局，完成后可撤销。", "恢复", () -> restoreLayout(false))); restore.setEnabled(prefs.data.contains("layout_backup")); backup.addView(restore);
        View undo = SettingsUi.settingRow(this, 0, "撤销上次恢复", "返回恢复前的布局", () -> restoreLayout(true)); undo.setEnabled(prefs.data.contains("layout_undo")); backup.addView(undo);
        group("layout-reset").addView(SettingsUi.settingRow(this, 0, "恢复默认布局", "仅重置本工具布局，可撤销", () -> confirm("恢复默认布局？", "不会改变目标外屏、权限或系统分辨率。", "恢复默认", () -> { try { prefs.resetLayout(); layoutSettings(); } catch (Exception e) { toast("恢复失败：" + e.getMessage()); } })));
    }
    private void restoreLayout(boolean undo) { try { prefs.restoreLayout(undo); layoutSettings(); toast(undo ? "已撤销恢复" : "已恢复，可撤销"); } catch (Exception e) { toast("恢复失败：" + e.getMessage()); } }

    private void about() {
        begin("关于", "about");
        LinearLayout info = group("about-info");
        info.addView(SettingsUi.settingRow(this, R.drawable.ic_launcher, "FlipAuto",
            "版本 " + BuildConfig.VERSION_NAME + "（构建 " + BuildConfig.VERSION_CODE + "）\n"
            + (BuildConfig.DEBUG ? "验证版" : "正式版") + " · 支持 Android 13 – 17", null));

        SettingsUi.section(body, "版权声明");
        LinearLayout copyright = group("about-copyright");
        TextView copyrightText = SettingsUi.text(this,
            "© 2026 FlipAuto 贡献者。保留所有权利。\n\n"
            + "FlipAuto 是一款独立的外屏增强软件，基于开源项目「外屏助手」二次开发而来。\n\n"
            + "本软件按「现状」提供，不提供任何明示或暗示的担保，包括但不限于适销性、特定用途适用性和非侵权性的担保。在任何情况下，作者或版权持有人均不对因使用本软件而产生的任何索赔、损害或其他责任负责。",
            13, SettingsUi.MUTED);
        copyrightText.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        copyright.addView(copyrightText);

        SettingsUi.section(body, "开源项目与许可");
        LinearLayout licenses = group("about-licenses");
        TextView licenseText = SettingsUi.text(this,
            "本项目基于以下开源项目二次开发，并使用以下开源软件：\n\n"
            + "【基础项目】\n"
            + "• 外屏助手（xiaomaoju/zflip5screenhelper）\n"
            + "  https://github.com/xiaomaoju/zflip5screenhelper\n"
            + "• FlipAuto（hwrfw/flipauto）\n"
            + "  https://github.com/hwrfw/flipauto\n\n"
            + "【依赖库】\n"
            + "• Shizuku — MIT License\n"
            + "  https://github.com/RikkaApps/Shizuku\n"
            + "• Material Symbols — Apache License 2.0\n"
            + "  https://github.com/google/material-design-icons\n"
            + "• AndroidX — Apache License 2.0\n"
            + "  https://developer.android.com/jetpack/androidx\n"
            + "• Kotlin — Apache License 2.0\n"
            + "  https://kotlinlang.org\n\n"
            + "上述许可的完整文本可在应用安装目录的 assets 文件夹中查阅。",
            13, SettingsUi.MUTED);
        licenseText.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        licenses.addView(licenseText);

        SettingsUi.section(body, "隐私承诺");
        LinearLayout privacy = group("about-privacy");
        TextView privacyText = SettingsUi.text(this,
            "本应用承诺：\n"
            + "• 不收集任何个人信息\n"
            + "• 不联网、不上传数据\n"
            + "• 通知内容仅在内存中处理，不落盘、不上传\n"
            + "• 所有设置仅存储在本地",
            13, SettingsUi.MUTED);
        privacyText.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        privacy.addView(privacyText);

        SettingsUi.section(body, "更新日志");
        LinearLayout changelog = group("about-changelog");
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open("changelog.txt"), StandardCharsets.UTF_8))) {
            String content = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            if (content.trim().isEmpty()) {
                note("暂无更新日志");
            } else {
                TextView log = SettingsUi.text(this, content, 14, SettingsUi.TEXT);
                log.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
                changelog.addView(log);
            }
        } catch (java.io.IOException e) { note("更新日志暂不可用"); }

        related("问题诊断", "diagnostics");
    }
    private void diagnostics() {
        begin("问题诊断", "diagnostics"); Display selected = Displays.selected(this, prefs); LinearLayout summary = group("diagnostic-summary"); summary.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_info, "当前运行状态", CoverService.status, null)); summary.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_phone_android, "目标外屏", displaySummary(), null));
        StringBuilder report = new StringBuilder("FlipAuto ").append(BuildConfig.VERSION_NAME).append("\nAndroid ").append(android.os.Build.VERSION.RELEASE).append(" / API ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        report.append("快捷栏状态：").append(CoverService.status).append("\n显示开关：").append(prefs.enabled()).append("\n系统报告锁屏：").append(getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()).append('\n');
        report.append("面板模糊开关：").append(prefs.panelBlur()).append("\n系统允许跨窗口模糊：").append(android.os.Build.VERSION.SDK_INT >= 31 && getSystemService(android.view.WindowManager.class).isCrossWindowBlurEnabled()).append("\n省电模式：").append(getSystemService(android.os.PowerManager.class).isPowerSaveMode()).append('\n');
        for (Display display : Displays.all(this)) report.append(Displays.describe(display)).append("\n状态：").append(display.getState()).append("\n缺口：").append(display.getCutout()).append('\n');
        CoverService service = CoverService.instance; if (service != null) { report.append(service.windowDiagnostics()).append(service.blurDiagnostics()); if (service.placement != null) report.append("可见区：").append(service.placement.visual()).append("\n触控区：").append(service.placement.touch()).append("\n缺口定位：").append(service.placement.measured()); } report.append(CoverService.lifecycleDiagnostics());
        diagnosticText = report.toString(); TextView details = SettingsUi.text(this, diagnosticText, 14, SettingsUi.MUTED); details.setTextIsSelectable(true); details.setVisibility(View.GONE);
        LinearLayout actions = group("diagnostic-actions"); actions.addView(SettingsUi.settingRow(this, 0, "显示原始详情", "显示器、窗口与兼容信息", () -> details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        View probe = SettingsUi.settingRow(this, 0, "通过 Shizuku 检测", "只读检测，不改变设备设置", null); probe.setOnClickListener(v -> { v.setEnabled(false); CoverApp.bridge(this).run("diagnostics", selected == null ? -1 : selected.getDisplayId(), 0, "", result -> { if (isDestroyed() || !v.isAttachedToWindow()) return; v.setEnabled(true); diagnosticText = report + "\n" + result.message + "\n" + result.output; details.setText(diagnosticText); details.setVisibility(View.VISIBLE); }); }); actions.addView(probe);
        actions.addView(SettingsUi.settingRow(this, 0, "重新挂载快捷栏", "挂载失败后无需开合盖，立即重试一次", () -> { CoverService.remount(); toast("已请求重新挂载"); }));
        LinearLayout debugGroup = group("debug-options");
        toggle(debugGroup, "Debug 悬浮窗", "屏幕居中显示当前前台应用包名，用于调试外屏应用识别", "debug_overlay", false);
        actions.addView(SettingsUi.settingRow(this, 0, "复制检测结果", "不包含通知正文", () -> { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("外屏检测", diagnosticText)); toast("已复制"); })); SettingsUi.add(body, details);
    }
    /** 导出配置：文件名带时间戳，便于区分多次备份。 */
    private void exportConfiguration() {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.getDefault()).format(new java.util.Date());
        open(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "FlipAuto-" + stamp + ".json"), REQUEST_EXPORT);
    }
    /** 导入配置：先读取并展示摘要，确认后才写入。 */
    private void importConfiguration() {
        open(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), REQUEST_IMPORT);
    }
    JSONObject exportConfigurationData() throws org.json.JSONException {
        JSONObject config = new JSONObject().put("version", CONFIG_VERSION)
                .put("exportedAt", System.currentTimeMillis())
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("appVersionCode", BuildConfig.VERSION_CODE)
                .put("rotations", prefs.rotationRules()).put("layout", prefs.layoutSnapshot()).put("avoidKeyboard", prefs.avoidKeyboard()).put("haptics", prefs.haptics()).put("favorites", new JSONArray(prefs.actions("favorites"))).put("hubPinned", prefs.hubPinned()).put("statusEnabled", prefs.statusEnabled()).put("gestures", prefs.data.getBoolean("gestures_enabled", true)).put("pinned", prefs.pinnedAction()).put("blur", prefs.panelBlur()).put("dock", new JSONArray(prefs.actions("dock"))).put("panel", new JSONArray(prefs.actions("panel"))).put("perPage", prefs.perPage()).put("damping", prefs.damping());
        JSONObject statusItems = new JSONObject();
        for (String item : Prefs.STATUS_ITEMS) statusItems.put(item, prefs.statusItem(item));
        config.put("statusItems", statusItems);
        config.put("dockAutoHide", prefs.autoHideDock()).put("compactApps", new JSONArray(prefs.compactApps()));
        config.put("widgetTemplates", CoverApp.widgets(this).exportTemplates());
        // 全量备份：导出整个 SharedPreferences，确保所有设置（外屏增强/状态栏缩放/控制中心布局/排除列表等）都被包含
        JSONObject allPrefs = new JSONObject();
        java.util.Map<String, ?> all = prefs.data.getAll();
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey(); Object val = entry.getValue();
            if (val == null || EXPORT_SKIPPED_PREFS.contains(key)) continue;
            if (val instanceof Boolean) allPrefs.put(key, (Boolean) val);
            else if (val instanceof Integer) allPrefs.put(key, (Integer) val);
            else if (val instanceof Long) allPrefs.put(key, (Long) val);
            else if (val instanceof Float) allPrefs.put(key, (Float) val);
            else if (val instanceof String) allPrefs.put(key, (String) val);
            else if (val instanceof java.util.Set) {
                JSONArray arr = new JSONArray();
                for (Object s : (java.util.Set<?>) val) arr.put(s);
                allPrefs.put(key, arr);
            }
        }
        config.put("allPrefs", allPrefs);
        return config;
    }
    /**
     * 应用配置内容：先完整校验，全部通过后才写入，避免无效内容部分落盘。
     *
     * @return 恢复摘要，例如「快捷栏 12 项 · 控制中心 8 项 · 偏好 96 项」
     */
    String applyConfigurationData(JSONObject config) throws org.json.JSONException {
        if (!config.has("version")) throw new IllegalArgumentException("不是有效的配置备份：缺少格式版本");
        for (String key : new String[]{"dock", "panel"}) if (!config.has(key)) throw new IllegalArgumentException("不是有效的配置备份：缺少 " + key);
        for (String key : new String[]{"version", "perPage", "damping"}) {
            Object raw = config.get(key); if (!(raw instanceof Number value) || value.doubleValue() != value.intValue()) throw new IllegalArgumentException("配置数字无效：" + key);
        }
        for (String key : new String[]{"avoidKeyboard", "haptics", "statusEnabled", "gestures", "blur", "dockAutoHide"}) if (config.has(key) && !(config.get(key) instanceof Boolean)) throw new IllegalArgumentException("配置开关值无效：" + key);
        for (String key : new String[]{"favorites", "compactApps"}) if (config.has(key) && !(config.get(key) instanceof JSONArray)) throw new IllegalArgumentException("配置列表无效：" + key);
        if (config.has("statusItems")) {
            JSONObject items = config.getJSONObject("statusItems");
            for (String item : Prefs.STATUS_ITEMS) if (items.has(item) && !(items.get(item) instanceof Boolean)) throw new IllegalArgumentException("状态栏开关值无效");
        }
        int version = config.getInt("version");
        if (version < 1 || version > CONFIG_VERSION) throw new IllegalArgumentException("不支持的配置版本（最高 " + CONFIG_VERSION + "）");
        java.util.Map<String, List<String>> parsed = new java.util.HashMap<>();
        for (String key : new String[]{"dock", "panel"}) {
            JSONArray array = config.getJSONArray(key); List<String> ids = new ArrayList<>();
            if (array.length() > 30) throw new IllegalArgumentException("快捷项过多");
            for (int i = 0; i < array.length(); i++) { String id = array.getString(i); if (!ActionCatalog.valid(id)) throw new IllegalArgumentException("无效快捷项"); if (!ids.contains(id)) ids.add(id); }
            parsed.put(key, ids);
        }
        JSONArray favorites = config.optJSONArray("favorites"); List<String> savedFavorites = new ArrayList<>();
        if (favorites != null) { if (favorites.length() > 30) throw new IllegalArgumentException("常用项过多"); for (int i = 0; i < favorites.length(); i++) { String id = favorites.getString(i); if (!ActionCatalog.valid(id)) throw new IllegalArgumentException("无效常用项"); if (!savedFavorites.contains(id)) savedFavorites.add(id); } }
        String hubPinned = config.optString("hubPinned", "");
        if (!hubPinned.isEmpty() && (!hubPinned.startsWith("app:") || !ActionCatalog.valid(hubPinned))) throw new IllegalArgumentException("无效固定应用");
        String pinned = config.optString("pinned", "app_hub");
        if (!ActionCatalog.valid(pinned)) throw new IllegalArgumentException("无效固定按钮");
        int count = config.getInt("perPage"), damping = config.getInt("damping");
        if (count < 2 || count > 5 || damping < 0 || damping > 2) throw new IllegalArgumentException("无效设置");
        android.content.SharedPreferences.Editor update = prefs.data.edit().putString("hub_pinned", hubPinned).putBoolean("status_enabled", config.optBoolean("statusEnabled", true)).putBoolean("gestures_enabled", config.optBoolean("gestures", true));
        org.json.JSONArray legacyPins = new org.json.JSONArray(); if (!hubPinned.isEmpty()) legacyPins.put(hubPinned); prefs.prepareHubPins(legacyPins, update);
        JSONObject statusItems = config.optJSONObject("statusItems");
        if (statusItems != null) for (String item : Prefs.STATUS_ITEMS) update.putBoolean("status_" + item, statusItems.optBoolean(item, true));
        JSONArray compactApps = config.optJSONArray("compactApps");
        if (compactApps != null) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (int i = 0; i < compactApps.length(); i++) { String name = compactApps.getString(i); if (!name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("无效应用规则"); names.add(name); }
            update.putStringSet("dock_compact_apps", names);
        }
        update.putBoolean("dock_auto_hide", config.optBoolean("dockAutoHide", true)).putString("home_action", "cards");
        if (favorites != null) update.putString("favorites", new JSONArray(savedFavorites).toString());
        update.putString("pinned_action", pinned).putBoolean("panel_blur", config.optBoolean("blur", true)).putString("dock", new JSONArray(parsed.get("dock")).toString()).putString("panel", new JSONArray(parsed.get("panel")).toString()).putInt("per_page", count).putInt("damping", damping);
        update.putBoolean("avoid_keyboard", config.optBoolean("avoidKeyboard", prefs.avoidKeyboard())).putBoolean("haptics", config.optBoolean("haptics", prefs.haptics()));
        if (version >= 4) prefs.prepareLayout(config.getJSONObject("layout"), update);
        else {
            update.putInt("panel_columns", 4);
            for (String key : new String[]{"panel_density", "panel_labels", "panel_label_size", "panel_tools_position", "panel_brightness", "panel_volume", "panel_media", "panel_media_idle", "panel_undo", "hub_workspace", "hub_workspace_compact", "hub_workspace_locked", "hub_workspace_labels", "hub_workspace_badges", "hub_workspace_density", "hub_workspace_aliases", "status_safe_left", "status_safe_right"}) update.remove(key);
        }
        if (version >= 5) prefs.prepareRotations(config.getJSONObject("rotations"), update);
        if (version >= 13) update.putString("widget_templates", WidgetTemplates.json(WidgetTemplates.read(config.getJSONArray("widgetTemplates"))).toString());
        // 全量导入：备份含 allPrefs 时逐项写入，跳过设备相关项与已单独处理的项
        int restored = 0;
        if (config.has("allPrefs")) {
            JSONObject allPrefs = config.getJSONObject("allPrefs");
            java.util.Iterator<String> keys = allPrefs.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (IMPORT_SKIPPED_PREFS.contains(key)) continue;
                Object val = allPrefs.get(key);
                if (val instanceof Boolean) update.putBoolean(key, (Boolean) val);
                else if (val instanceof Integer) update.putInt(key, (Integer) val);
                else if (val instanceof Long) update.putLong(key, (Long) val);
                else if (val instanceof Float) update.putFloat(key, (Float) val);
                else if (val instanceof String) update.putString(key, (String) val);
                else if (val instanceof JSONArray) {
                    java.util.Set<String> set = new java.util.HashSet<>();
                    JSONArray arr = (JSONArray) val;
                    for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                    update.putStringSet(key, set);
                }
                restored++;
            }
        }
        update.apply();
        return "快捷栏 " + parsed.get("dock").size() + " 项 · 控制中心 " + parsed.get("panel").size() + " 项 · 偏好 " + restored + " 项";
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        if (request != REQUEST_EXPORT && request != REQUEST_IMPORT) return;
        if (request == REQUEST_IMPORT) { readImport(intent.getData()); return; }
        android.net.Uri target = intent.getData(); String name = fileName(target);
        toast("正在导出配置…");
        worker.execute(() -> {
            try {
                byte[] encoded = exportConfigurationData().toString(2).getBytes(StandardCharsets.UTF_8);
                if (encoded.length > CONFIG_LIMIT_BYTES) throw new IllegalArgumentException("配置超过 " + (CONFIG_LIMIT_BYTES / 1024) + " KiB");
                try (java.io.OutputStream stream = getContentResolver().openOutputStream(target)) { stream.write(encoded); }
                toast("已导出 " + name);
            } catch (Exception error) { toast("导出失败：" + describe(error)); }
        });
    }
    /** 读取待导入配置：完整解析后展示摘要，确认后才写入。 */
    private void readImport(android.net.Uri uri) {
        pendingImportUri = uri.toString(); int request = ++importRequest; String source = route; String name = fileName(uri);
        toast("正在读取配置…");
        worker.execute(() -> {
            try {
                byte[] data;
                try (java.io.InputStream stream = getContentResolver().openInputStream(uri); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024]; int count;
                    while (bytes.size() <= CONFIG_LIMIT_BYTES && (count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    data = bytes.toByteArray();
                }
                if (data.length > CONFIG_LIMIT_BYTES) throw new IllegalArgumentException("配置超过 " + (CONFIG_LIMIT_BYTES / 1024) + " KiB");
                JSONObject config = new JSONObject(new String(data, StandardCharsets.UTF_8));
                String summary = configurationSummary(name, config);
                main.post(() -> {
                    if (isDestroyed() || isFinishing() || request != importRequest || !source.equals(route)) return;
                    confirm("导入配置？", summary, "导入", () -> {
                        try {
                            String restored = applyConfigurationData(config);
                            pendingImportUri = null;
                            toast("已导入 " + restored); showRoute(route);
                        } catch (Exception error) { toast("配置未导入：" + describe(error)); }
                    });
                });
            } catch (Exception error) { main.post(() -> { if (request == importRequest && !isDestroyed()) { pendingImportUri = null; toast("配置读取失败：" + describe(error)); } }); }
        });
    }
    /** 导入确认框的配置摘要：文件名、导出时间、来源版本与主要内容，供用户核对导入的是哪个文件。 */
    private String configurationSummary(String name, JSONObject config) {
        StringBuilder text = new StringBuilder("文件：").append(name).append('\n');
        long exportedAt = config.optLong("exportedAt", 0L);
        if (exportedAt > 0) text.append("导出时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(exportedAt))).append('\n');
        String appVersion = config.optString("appVersion", "");
        if (!appVersion.isEmpty()) text.append("来源版本：").append(appVersion).append("（格式 ").append(config.optInt("version", 0)).append("）\n");
        text.append("内容：快捷栏 ").append(arraySize(config, "dock")).append(" 项 · 控制中心 ").append(arraySize(config, "panel")).append(" 项");
        JSONObject statusItems = config.optJSONObject("statusItems");
        if (statusItems != null) text.append(" · 状态栏 ").append(statusItems.length()).append(" 项");
        JSONObject allPrefs = config.optJSONObject("allPrefs");
        if (allPrefs != null) text.append(" · 偏好 ").append(allPrefs.length()).append(" 项");
        int templates = arraySize(config, "widgetTemplates");
        if (templates > 0) text.append(" · 小组件模板 ").append(templates).append(" 份");
        text.append("\n\n将覆盖按钮、布局与偏好；保留目标外屏与权限。小组件模板需在组合卡片编辑页逐项恢复，不自动替换现有卡片。无效内容不会部分写入。");
        return text.toString();
    }
    /** 取数组字段长度；字段缺失或类型不符时返回 0。 */
    private static int arraySize(JSONObject config, String key) {
        JSONArray array = config.optJSONArray(key);
        return array == null ? 0 : array.length();
    }
    /** 从 SAF 文档 URI 提取展示用文件名。 */
    private static String fileName(android.net.Uri uri) {
        String last = uri == null ? null : uri.getLastPathSegment();
        if (last == null || last.isEmpty()) return "配置文件";
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }
    /** 异常信息兜底：message 为空时退化为异常类型名。 */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
    private void toast(String message) { main.post(() -> { if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }); }
    private boolean open(Intent intent) { try { startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(getDisplay().getDisplayId()).toBundle()); return true; } catch (RuntimeException e) { toast("系统未允许打开此入口"); return false; } }
    private void open(Intent intent, int request) { try { startActivityForResult(intent, request, ActivityOptions.makeBasic().setLaunchDisplayId(getDisplay().getDisplayId()).toBundle()); } catch (RuntimeException e) { toast("无法打开系统入口：" + e.getClass().getSimpleName()); } }
}
