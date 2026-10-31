package com.flipauto;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Prefs {
    static final String[] STATUS_ITEMS = {"time", "wifi", "battery", "notifications", "alarm", "speed", "cellular", "vpn", "dnd", "bluetooth", "airplane", "nfc", "hotspot", "gps"};
    /** FlipAuto 默认排除列表（开屏恢复用）。用户未修改时返回此列表。 */
    static final java.util.Set<String> DEFAULT_EXCLUDED = java.util.Set.of(
        "com.android.systemui",
        "com.samsung.android.app.aodservice",
        "com.samsung.systemui.notilus",
        "android",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.sm",
        "com.samsung.android.sm_cn",
        "com.samsung.android.permissioncontroller",
        "com.samsung.android.packageinstaller"
    );
    /** FlipAuto 默认气泡排除列表（悬浮气泡用），与恢复排除列表内容相同但独立维护。 */
    static final java.util.Set<String> DEFAULT_BUBBLE_EXCLUDED = java.util.Set.of(
        "com.android.systemui",
        "com.samsung.android.app.aodservice",
        "com.samsung.systemui.notilus",
        "android",
        "com.sec.android.app.launcher",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.home",
        "com.samsung.android.app.spage",
        "com.samsung.android.sm",
        "com.samsung.android.sm_cn",
        "com.samsung.android.permissioncontroller",
        "com.samsung.android.packageinstaller",
        "com.samsung.android.app.smartcapture",
        "com.samsung.android.camera",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.game.gamehome",
        "com.samsung.android.game.gametools",
        "com.samsung.android.accessibility.talkback",
        "com.samsung.android.forest",
        "com.samsung.android.kidsinstaller",
        "com.samsung.android.pay",
        "com.samsung.knox.securefolder",
        "com.flipauto"
    );
    public final SharedPreferences data;
    public Prefs(Context context) {
        data = context.getSharedPreferences("cover", Context.MODE_PRIVATE);
        if (!data.getBoolean("hub_default_migrated", false)) {
            SharedPreferences.Editor edit = data.edit().putBoolean("hub_default_migrated", true);
            if (!data.contains("pinned_action") || data.getString("pinned_action", "").equals("controls")) edit.putString("pinned_action", "app_hub");
            edit.apply();
        }
        if (!data.getBoolean("app_dock_migrated", false)) {
            SharedPreferences.Editor edit = data.edit().putBoolean("app_dock_migrated", true);
            if (data.contains("dock") && !pinnedAction().equals("app_dock")) {
                List<String> ids = actions("dock");
                if (!ids.contains("app_dock") && ids.size() < 30) { ids.add(0, "app_dock"); edit.putString("dock", new JSONArray(ids).toString()); }
            }
            edit.apply();
        }
        if (!data.getBoolean("dock_v3_migrated", false)) {
            // 把默认 dock 换成 6 项。只对「从未手动排过序」的用户生效：
            // 判定用「当前项是否全部属于旧默认集合」而不是数组相等 ——
            // 老用户已被 app_dock_migrated 插过 app_dock，严格相等会失配。
            SharedPreferences.Editor edit = data.edit().putBoolean("dock_v3_migrated", true);
            List<String> ids = actions("dock");
            boolean untouched = !data.contains("dock")
                    || new java.util.HashSet<>(Arrays.asList(DOCK_LEGACY)).containsAll(ids);
            if (untouched) try { edit.putString("dock", new JSONArray(Arrays.asList(DOCK_DEFAULT)).toString()); } catch (Exception ignored) { }
            edit.apply();
        }
        if (!data.getBoolean("system_controls_migrated", false)) {
            SharedPreferences.Editor edit = data.edit().putBoolean("system_controls_migrated", true);
            if (data.contains("panel")) {
                List<String> ids = actions("panel");
                if (!ids.contains("system_controls") && ids.size() < 30) { ids.add("system_controls"); edit.putString("panel", new JSONArray(ids).toString()); }
            }
            edit.apply();
        }
    }
    public boolean enabled() { return data.getBoolean("enabled", false); }
    boolean systemControlsDisabled() { return data.getBoolean("system_controls_disabled", false); }
    void systemControlsDisabled(boolean disabled) { data.edit().putBoolean("system_controls_disabled", disabled).apply(); }
    public int displayId() { return data.getInt("display", -1); }
    /** 每页按钮数：默认 4，最多 5（一页排完常用按钮）。 */
    public int perPage() { return Math.max(2, Math.min(5, data.getInt("per_page", 4))); }
    public String pinnedAction() { String id = data.getString("pinned_action", "app_hub"); if ("rotation".equals(id)) id = "app_hub"; return ActionCatalog.valid(id) ? id : "app_hub"; }
    public String hubPinned() { List<String> ids = hubPins(); return ids.isEmpty() ? "" : ids.get(0); }
    public List<String> hubPins() {
        try { String raw = data.getString("hub_pins", null); if (raw != null) return checkedHubPins(new JSONArray(raw)); }
        catch (Exception ignored) { return new ArrayList<>(); }
        String legacy = data.getString("hub_pinned", ""); List<String> result = new ArrayList<>();
        if (legacy.startsWith("app:") && ActionCatalog.valid(legacy)) result.add(legacy); return result;
    }
    static List<String> checkedHubPins(JSONArray source) throws JSONException {
        if (source.length() > AppDockPlacement.LIMIT) throw new IllegalArgumentException("底部常用最多" + AppDockPlacement.LIMIT + "个应用");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) { String id = source.getString(i); if (!id.startsWith("app:") || !ActionCatalog.valid(id) || result.contains(id)) throw new IllegalArgumentException("无效底部常用应用"); result.add(id); } return result;
    }
    SharedPreferences.Editor prepareHubPins(JSONArray source, SharedPreferences.Editor update) throws JSONException {
        List<String> ids = checkedHubPins(source); return update.putString("hub_pins", new JSONArray(ids).toString()).putString("hub_pinned", ids.isEmpty() ? "" : ids.get(0));
    }
    public void saveHubPins(List<String> ids) { saveDockPlacement(ids, AppDockPlacement.workspace(workspace(), hubPins(), ids, workspaceCompact())); }
    void saveDockPlacement(List<String> ids, AppWorkspaceLayout layout) {
        try { SharedPreferences.Editor update = prepareHubPins(new JSONArray(ids), data.edit()); for (String id : ids) if (layout.apps().contains(id)) throw new IllegalArgumentException("常驻应用不能同时占用桌面格位"); update.putString("hub_workspace", workspaceStorage(layout)).apply(); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    /** 默认「最近打开优先」：在本应用里点过的应用浮到顶部；已保存过偏好（手动改过）的用户不受影响。 */
    public String hubSort() { String value = data.getString("hub_sort", "used"); return validHubSort(value) ? value : "used"; }
    static boolean validHubSort(String value) { return java.util.Set.of("manual", "name", "reverse", "recent", "used").contains(value); }
    boolean workspaceCompact() { return data.getBoolean("hub_workspace_compact", false); }
    boolean workspaceLocked() { return data.getBoolean("hub_workspace_locked", false); }
    boolean workspaceLabels() { return data.getBoolean("hub_workspace_labels", true); }
    boolean workspaceBadges() { return data.getBoolean("hub_workspace_badges", false); }
    String workspaceDensity() { return data.getString("hub_workspace_density", "compact"); }
    JSONObject workspaceAliases() { try { return new JSONObject(data.getString("hub_workspace_aliases", "{}")); } catch (JSONException error) { return new JSONObject(); } }
    String workspaceAlias(String id) { return workspaceAliases().optString(id, ""); }
    void workspaceAlias(String id, String name) {
        if (!id.startsWith("app:") || !ActionCatalog.valid(id) || name.codePointCount(0, name.length()) > 24 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("应用别名最多24个字符");
        try { JSONObject aliases = workspaceAliases(); if (name.trim().isEmpty()) aliases.remove(id); else aliases.put(id, name.trim()); if (aliases.length() > AppWorkspaceLayout.MAX_APPS) throw new IllegalArgumentException("应用别名过多"); data.edit().putString("hub_workspace_aliases", aliases.toString()).apply(); } catch (JSONException error) { throw new IllegalArgumentException(error); }
    }
    AppWorkspaceLayout workspace() {
        try { String raw = data.getString("hub_workspace", "[]"); if (raw.startsWith("[")) return readWorkspace(new JSONArray(raw), 5, 3, false); JSONObject value = new JSONObject(raw); return readWorkspace(value.getJSONArray("items"), workspaceInteger(value.get("columns")), workspaceInteger(value.get("rows")), true); }
        catch (JSONException | IllegalArgumentException error) { return new AppWorkspaceLayout(); }
    }
    private static int workspaceInteger(Object value) { if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) throw new IllegalArgumentException("应用布局数字无效"); return number.intValue(); }
    private static AppWorkspaceLayout readWorkspace(JSONArray source, int columns, int rows, boolean withFolders) throws JSONException {
        if (source.length() > AppWorkspaceLayout.MAX_APPS) throw new IllegalArgumentException("应用布局过大");
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        java.util.Map<String, AppWorkspaceLayout.Folder> folders = new java.util.LinkedHashMap<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i); String id = item.getString("id"); Object raw = item.get("slot");
            if (result.containsKey(id)) throw new IllegalArgumentException("应用布局条目重复");
            if (withFolders && id.matches("folder:[a-fA-F0-9-]{36}")) {
                JSONArray list = item.getJSONArray("members"); if (list.length() < 2 || list.length() > 9) throw new IllegalArgumentException("文件夹需有2–9个应用"); List<String> members = new ArrayList<>();
                for (int m = 0; m < list.length(); m++) { String app = list.getString(m); if (!app.startsWith("app:") || !ActionCatalog.valid(app)) throw new IllegalArgumentException("文件夹成员必须是应用"); members.add(app); }
                Object name = item.get("name"); if (!(name instanceof String)) throw new IllegalArgumentException("文件夹名称必须是文字"); folders.put(id, new AppWorkspaceLayout.Folder((String) name, members, workspaceInteger(item.get("color"))));
            } else if (!id.startsWith("app:") || !ActionCatalog.valid(id) || item.has("members")) throw new IllegalArgumentException("应用布局条目无效");
            result.put(id, workspaceInteger(raw));
        }
        return new AppWorkspaceLayout(result, folders, columns, rows);
    }
    private static JSONArray workspaceJson(AppWorkspaceLayout layout) throws JSONException {
        JSONArray result = new JSONArray(); for (String id : layout.ordered()) { JSONObject item = new JSONObject().put("id", id).put("slot", layout.slot(id)); AppWorkspaceLayout.Folder folder = layout.folder(id); if (folder != null) item.put("name", folder.name()).put("members", new JSONArray(folder.members())).put("color", folder.color()); result.put(item); } return result;
    }
    private static String workspaceStorage(AppWorkspaceLayout layout) throws JSONException { return new JSONObject().put("columns", layout.columns()).put("rows", layout.rows()).put("items", workspaceJson(layout)).toString(); }
    void saveWorkspace(AppWorkspaceLayout layout, boolean compact) {
        try { data.edit().putString("hub_workspace", workspaceStorage(layout)).putBoolean("hub_workspace_compact", compact).apply(); }
        catch (JSONException error) { throw new IllegalArgumentException(error); }
    }
    public int statusScale() { return Math.max(50, Math.min(150, data.getInt("status_scale", 85))); }
    public int statusSafeLeft() { return Math.max(0, Math.min(48, data.getInt("status_safe_left", 16))); }
    public int statusSafeRight() { return Math.max(0, Math.min(48, data.getInt("status_safe_right", 16))); }
    public boolean batteryPercent() { return data.getBoolean("battery_percent", false); }
    public boolean batteryPercentInside() { return data.getBoolean("battery_percent_inside", false); }
    public String chromeStyle() { String style = data.getString("chrome_style", "contrast"); return validChrome(style) ? style : "contrast"; }
    static boolean validChrome(String style) { return java.util.Set.of("contrast", "black", "light", "dark", "shadow").contains(style); }
    public boolean avoidNavigation() { return data.getBoolean("avoid_navigation", false); }
    public int navigationGap() { return Math.max(0, Math.min(48, data.getInt("navigation_gap", 8))); }
    // Keep the legacy configuration field readable/exportable, but never restore tap-to-panel.
    public boolean tapHandles() { return false; }
    public boolean statusEnabled() { return data.getBoolean("status_enabled", true); }
    public boolean autoHideDock() { return data.getBoolean("dock_auto_hide", true); }
    /** Home 按钮目标：锁屏时钟页 / 三星卡片页（需 Shizuku） / 引导里选定的第三方启动器。 */
    public String homeAction() { String value = data.getString("home_action", "cards"); return validHomeAction(value) ? value : "cards"; }
    static boolean validHomeAction(String value) { return "clock".equals(value) || "cards".equals(value) || "launcher".equals(value); }
    public boolean avoidKeyboard() { return data.getBoolean("avoid_keyboard", true); }
    public boolean haptics() { return data.getBoolean("haptics", true); }
    public String handSide() { String value = data.getString("hand_side", "auto"); return validHand(value) ? value : "auto"; }
    static boolean validHand(String value) { return value.equals("auto") || value.equals("left") || value.equals("right"); }
    public boolean leftHand() { return handSide().equals("left"); }
    JSONObject rotationRules() { try { return new JSONObject(data.getString("app_rotations", "{}")); } catch (JSONException e) { return new JSONObject(); } }
    public int appRotation(String packageName) { int value = rotationRules().optInt(packageName, -1); return value >= 0 && value <= 4 ? value : -1; }
    void saveAppRotation(String packageName, int value) throws JSONException {
        JSONObject rules = rotationRules(); if (value == -1) rules.remove(packageName); else rules.put(packageName, value);
        prepareRotations(rules, data.edit()).apply();
    }
    SharedPreferences.Editor prepareRotations(JSONObject rules, SharedPreferences.Editor update) throws JSONException {
        if (rules.length() > 200) throw new IllegalArgumentException("应用方向规则最多 200 项");
        java.util.Iterator<String> keys = rules.keys(); JSONObject checked = new JSONObject();
        while (keys.hasNext()) {
            String key = keys.next(); Object raw = rules.get(key);
            if (!key.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") || !(raw instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 0 || number.intValue() > 4) throw new IllegalArgumentException("无效应用方向规则");
            checked.put(key, ((Number) raw).intValue());
        }
        return update.putString("app_rotations", checked.toString());
    }
    static String rotationLabel(int value) { return value == 4 ? "跟随系统" : value >= 0 && value <= 3 ? value * 90 + "°" : "沿用当前方向"; }
    void statusPreset(String name) {
        if (!name.equals("minimal") && !name.equals("daily") && !name.equals("full")) throw new IllegalArgumentException("无效状态栏预设");
        SharedPreferences.Editor update = data.edit();
        for (String item : STATUS_ITEMS) update.putBoolean("status_" + item, presetItem(name, item)); update.apply();
    }
    String statusPreset() {
        for (String name : new String[]{"minimal", "daily", "full"}) { boolean matches = true; for (String item : STATUS_ITEMS) if (statusItem(item) != presetItem(name, item)) matches = false; if (matches) return name; }
        return "custom";
    }
    private boolean presetItem(String name, String item) { return name.equals("full") || item.equals("time") || item.equals("wifi") || item.equals("battery") || name.equals("daily") && (item.equals("notifications") || item.equals("alarm")); }
    public java.util.Set<String> compactApps() { return new java.util.HashSet<>(data.getStringSet("dock_compact_apps", java.util.Set.of("com.android.systemui"))); }
    /** 在这些应用前台时隐藏通知栏（排除通知栏）。默认排除系统界面与三星系统应用。 */
    public java.util.Set<String> excludeNotificationApps() { return new java.util.HashSet<>(data.getStringSet("exclude_notification_apps", DEFAULT_EXCLUDED)); }
    void excludeNotificationApps(java.util.Set<String> set) { data.edit().putStringSet("exclude_notification_apps", set == null ? DEFAULT_EXCLUDED : new java.util.HashSet<>(set)).apply(); }
    /** 在这些应用前台时隐藏 dock 栏（排除 dock 栏）。默认排除系统界面与三星系统应用。 */
    public java.util.Set<String> excludeDockApps() { return new java.util.HashSet<>(data.getStringSet("exclude_dock_apps", DEFAULT_EXCLUDED)); }
    void excludeDockApps(java.util.Set<String> set) { data.edit().putStringSet("exclude_dock_apps", set == null ? DEFAULT_EXCLUDED : new java.util.HashSet<>(set)).apply(); }
    public boolean statusItem(String item) { return data.getBoolean("status_" + item, true); }

    /** 开 VPN 时读取代理应用通知里的网速，避免系统总量把隧道流量算两遍。 */
    public boolean smartSpeed() { return data.getBoolean("status_speed_smart", true); }

    /** 指定侦测网速的应用；为空表示自动识别代理类应用。 */
    public java.util.Set<String> speedApps() { return new java.util.HashSet<>(data.getStringSet("speed_apps", java.util.Set.of())); }
    public void speedApps(java.util.Set<String> set) { data.edit().putStringSet("speed_apps", set == null ? java.util.Set.of() : new java.util.HashSet<>(set)).apply(); }

    /** 状态栏通知图标最大显示数量，超出部分用三个点表示。默认 4，范围 2-8。 */
    public int statusNotificationCount() { return Math.max(2, Math.min(8, data.getInt("status_notification_count", 4))); }
    public void statusNotificationCount(int count) { data.edit().putInt("status_notification_count", Math.max(2, Math.min(8, count))).apply(); }
    public List<String> scrollingActions() { List<String> ids = actions("dock"); ids.remove(pinnedAction()); return ids; }
    public void pin(String id) {
        if (id.equals(pinnedAction()) || !ActionCatalog.valid(id)) return;
        List<String> ids = scrollingActions(); int index = ids.indexOf(id);
        if (index >= 0) ids.set(index, pinnedAction());
        else if (ids.size() < 30) ids.add(0, pinnedAction());
        data.edit().putString("pinned_action", id).putString("dock", new JSONArray(ids).toString()).apply();
    }
    public int damping() { return Math.max(0, Math.min(2, data.getInt("damping", 1))); }
    public int panelColumns() { int value = data.getInt("panel_columns", 4); return validPanelColumns(value) ? value : 4; }
    static boolean validPanelColumns(int value) { return value >= 3 && value <= 5; }
    public int panelDensity() { int value = data.getInt("panel_density", 1); return value >= 0 && value <= 2 ? value : 1; }
    public boolean panelLabels() { return data.getBoolean("panel_labels", true); }
    public int panelLabelSize() { int value = data.getInt("panel_label_size", 1); return value >= 0 && value <= 2 ? value : 1; }
    public String panelToolsPosition() { String value = data.getString("panel_tools_position", "auto"); return validPanelToolsPosition(value) ? value : "auto"; }
    static boolean validPanelToolsPosition(String value) { return "auto".equals(value) || "side".equals(value) || "bottom".equals(value); }
    public boolean panelBrightness() { return data.getBoolean("panel_brightness", true); }
    public boolean panelVolume() { return data.getBoolean("panel_volume", true); }
    public boolean panelMedia() { return data.getBoolean("panel_media", true); }
    public boolean panelMediaIdle() { return data.getBoolean("panel_media_idle", false); }
    JSONObject panelSnapshot() throws JSONException {
        return new JSONObject().put("columns", panelColumns()).put("density", panelDensity()).put("labels", panelLabels()).put("labelSize", panelLabelSize()).put("toolsPosition", panelToolsPosition())
            .put("brightness", panelBrightness()).put("volume", panelVolume()).put("media", panelMedia()).put("mediaIdle", panelMediaIdle());
    }
    private static JSONObject defaultPanelSettings() throws JSONException {
        return new JSONObject().put("columns", 4).put("density", 1).put("labels", true).put("labelSize", 1).put("toolsPosition", "auto").put("brightness", true).put("volume", true).put("media", true).put("mediaIdle", false);
    }
    /** Validates the complete local snapshot before its editor may be applied. */
    private SharedPreferences.Editor preparePanelSettings(JSONObject settings, SharedPreferences.Editor update) throws JSONException {
        for (String key : new String[]{"columns", "density", "labelSize"}) {
            Object raw = settings.get(key);
            if (!(raw instanceof Number value) || value.doubleValue() != value.intValue() || (key.equals("columns") ? !validPanelColumns(value.intValue()) : value.intValue() < 0 || value.intValue() > 2)) throw new IllegalArgumentException("控制中心布局值无效");
        }
        Object position = settings.get("toolsPosition"); if (!(position instanceof String value) || !validPanelToolsPosition(value)) throw new IllegalArgumentException("工具区位置无效");
        for (String key : new String[]{"labels", "brightness", "volume", "media", "mediaIdle"}) if (!(settings.get(key) instanceof Boolean)) throw new IllegalArgumentException("控制中心开关值无效");
        return update.putInt("panel_columns", settings.getInt("columns")).putInt("panel_density", settings.getInt("density")).putBoolean("panel_labels", settings.getBoolean("labels"))
            .putInt("panel_label_size", settings.getInt("labelSize")).putString("panel_tools_position", settings.getString("toolsPosition")).putBoolean("panel_brightness", settings.getBoolean("brightness"))
            .putBoolean("panel_volume", settings.getBoolean("volume")).putBoolean("panel_media", settings.getBoolean("media")).putBoolean("panel_media_idle", settings.getBoolean("mediaIdle"));
    }
    void applyPanelSettings(JSONObject settings) throws JSONException {
        SharedPreferences.Editor update = preparePanelSettings(settings, data.edit()); JSONObject before = panelSnapshot();
        boolean changed = false; java.util.Iterator<String> keys = before.keys();
        while (keys.hasNext()) { String key = keys.next(); Object prior = before.get(key), next = settings.get(key); if (prior instanceof Number a && next instanceof Number b ? a.intValue() != b.intValue() : !prior.equals(next)) changed = true; }
        if (changed) update.putString("panel_undo", before.toString()).apply();
    }
    void resetPanelSettings() throws JSONException { applyPanelSettings(defaultPanelSettings()); }
    boolean hasPanelUndo() { return data.contains("panel_undo"); }
    void undoPanelSettings() throws JSONException {
        String saved = data.getString("panel_undo", null); if (saved == null) throw new IllegalArgumentException("还没有可撤销的控制中心修改");
        preparePanelSettings(new JSONObject(saved), data.edit()).remove("panel_undo").apply();
    }
    void panelPreset(String name) throws JSONException {
        int columns, density, size;
        switch (name) {
            case "standard": columns = 4; density = 1; size = 1; break;
            case "compact": columns = 5; density = 0; size = 0; break;
            case "easy": columns = 3; density = 2; size = 2; break;
            default: throw new IllegalArgumentException("控制中心预设无效");
        }
        applyPanelSettings(panelSnapshot().put("columns", columns).put("density", density).put("labels", true).put("labelSize", size));
    }
    String panelPreset() {
        if (!panelLabels()) return "custom";
        if (panelColumns() == 4 && panelDensity() == 1 && panelLabelSize() == 1) return "standard";
        if (panelColumns() == 5 && panelDensity() == 0 && panelLabelSize() == 0) return "compact";
        if (panelColumns() == 3 && panelDensity() == 2 && panelLabelSize() == 2) return "easy";
        return "custom";
    }
    public boolean panelBlur() { return data.getBoolean("panel_blur", true); }
    // ===== 特权模式 / 界面缩放 / 外屏增强功能 =====
    /** 特权模式："normal" = 免 Shizuku（默认）；"shizuku_all" = 全部走 Shizuku；"shizuku_custom" = 按授权菜单选择。 */
    public String privilegeMode() {
        String value = data.getString("privilege_mode", "normal");
        if (value.equals("none")) value = "normal";
        if (value.equals("shizuku")) return "shizuku_all";
        return value.equals("shizuku_all") || value.equals("shizuku_custom") ? value : "normal";
    }
    void privilegeMode(String value) {
        String normalized = value.equals("shizuku_all") || value.equals("shizuku_custom") ? value : "normal";
        data.edit().putString("privilege_mode", normalized).apply();
    }
    public boolean noRoot() { return !privilegeMode().startsWith("shizuku"); }
    /** Shizuku 自定义授权范围：已勾选的操作 ID 集合（空集 = 未授权任何操作）。 */
    public java.util.Set<String> shizukuScope() { return data.getStringSet("shizuku_scope", java.util.Set.of()); }
    void shizukuScope(java.util.Set<String> scope) { data.edit().putStringSet("shizuku_scope", scope == null ? java.util.Set.of() : new java.util.HashSet<>(scope)).apply(); }
    /** 单个操作是否应走 Shizuku（normal 一律 false；shizuku_all 一律 true；shizuku_custom 看授权菜单）。 */
    public boolean useShizuku(String id) {
        // 旋转强制使用免 root 实现（Shizuku 旋转在部分系统上不可靠）
        if ("rotation".equals(id)) return false;
        // 运行时 Shizuku 断开时临时回退免 root，不影响用户配置
        if (!ShizukuBridge.runtimeShizukuAvailable) return false;
        String mode = privilegeMode();
        if (!mode.startsWith("shizuku")) return false;
        if (mode.equals("shizuku_all")) return true;
        return shizukuScope().contains(id);
    }
    public String privilegeLabel() {
        return switch (privilegeMode()) {
            case "shizuku_all" -> "Shizuku（全部）";
            case "shizuku_custom" -> "Shizuku（自定义授权）";
            default -> "免 Shizuku（推荐）";
        };
    }
    /** 界面整体缩放（百分比 50–200，默认 100），作用于外屏 UI 的 dp 计算。 */
    public int uiScale() { return Math.max(50, Math.min(200, data.getInt("ui_scale", 100))); }
    void uiScale(int value) { data.edit().putInt("ui_scale", Math.max(50, Math.min(200, value))).apply(); }
    public float uiScaleFactor() { return uiScale() / 100f; }
    // ---- 外屏增强（原 FlipAuto 悬浮气泡） ----
    public boolean flipBubbles() { return data.getBoolean("flip_bubbles", true); }
    public int flipBubbleSize() { return Math.max(40, Math.min(100, data.getInt("flip_bubble_size", 60))); }
    public boolean flipRestore() { return data.getBoolean("flip_restore", true); }
    public int flipRestoreDelay() { return Math.max(0, Math.min(5000, data.getInt("flip_restore_delay", 0))); }
    public boolean flipRestoreCharging() { return data.getBoolean("flip_restore_charging", false); }
    public boolean flipAutoLaunch() { return data.getBoolean("flip_auto_launch", true); }
    public boolean flipDetectSubHome() { return data.getBoolean("flip_detect_subhome", true); }
    public boolean flipDetectAod() { return data.getBoolean("flip_detect_aod", false); }
    public String flipTargetApp() { return data.getString("flip_target_app", ""); }
    /** 最近一次外屏使用的应用（开屏恢复用，持久化，避免服务重启丢失）。 */
    public String flipLastApp() { return data.getString("flip_last_app", ""); }
    void flipLastApp(String pkg) { data.edit().putString("flip_last_app", pkg == null ? "" : pkg).apply(); }
    /** 应用在本应用内被启动的时间戳（package → millis），用于「点过的浮到列表顶部」。 */
    public java.util.Map<String, Long> appUsage() {
        java.util.Map<String, Long> result = new java.util.HashMap<>();
        try {
            JSONObject json = new JSONObject(data.getString("app_usage", "{}"));
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) { String pkg = keys.next(); long at = json.optLong(pkg, 0L); if (at > 0) result.put(pkg, at); }
        } catch (Exception ignored) { }
        return result;
    }
    /** 记录一次「从本应用启动该应用」；超过 200 条时裁掉最旧的，避免无限增长。 */
    public void noteAppUsed(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        java.util.Map<String, Long> usage = appUsage();
        usage.put(pkg, System.currentTimeMillis());
        if (usage.size() > 200) {
            java.util.List<java.util.Map.Entry<String, Long>> oldest = new java.util.ArrayList<>(usage.entrySet());
            oldest.sort(java.util.Map.Entry.comparingByValue());
            for (int i = 0; i < oldest.size() - 200; i++) usage.remove(oldest.get(i).getKey());
        }
        JSONObject json = new JSONObject();
        for (java.util.Map.Entry<String, Long> entry : usage.entrySet()) try { json.put(entry.getKey(), entry.getValue()); } catch (Exception ignored) { }
        data.edit().putString("app_usage", json.toString()).apply();
    }
    public String flipLanguage() { return data.getString("flip_language", ""); }
    void flipLanguage(String value) { data.edit().putString("flip_language", value == null ? "" : value).apply(); }
    public boolean onboardingDone() { return data.getBoolean("onboarding_done", false); }
    void onboardingDone(boolean done) { data.edit().putBoolean("onboarding_done", done).apply(); }
    public boolean debugOverlay() { return data.getBoolean("debug_overlay", false); }
    /** 未保存过时返回 FlipAuto 默认排除列表；已保存（包括空集）时以用户集合为准。 */
    public java.util.Set<String> flipExcluded() {
        java.util.Set<String> stored = data.getStringSet("flip_excluded", null);
        return stored == null ? DEFAULT_EXCLUDED : java.util.Set.copyOf(stored);
    }
    void flipExcluded(java.util.Set<String> set) {
        // 写入时才落盘：用户没动过时键不存在，flipExcluded() 继续返回默认值。
        data.edit().putStringSet("flip_excluded",
            set == null ? DEFAULT_EXCLUDED : new java.util.HashSet<>(set)).apply();
    }
    /** 气泡排除列表（悬浮气泡用），独立于恢复排除列表。未保存时返回默认值。 */
    public java.util.Set<String> flipBubbleExcluded() {
        java.util.Set<String> stored = data.getStringSet("flip_bubble_excluded", null);
        return stored == null ? DEFAULT_BUBBLE_EXCLUDED : java.util.Set.copyOf(stored);
    }
    void flipBubbleExcluded(java.util.Set<String> set) {
        data.edit().putStringSet("flip_bubble_excluded",
            set == null ? DEFAULT_BUBBLE_EXCLUDED : new java.util.HashSet<>(set)).apply();
    }
    /** 排除列表的勾选顺序（最近勾选在最前），仅用于界面「已勾选置顶」显示。 */
    public List<String> excludedOrder() {
        String raw = data.getString("flip_excluded_order", "");
        List<String> result = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) for (String pkg : raw.split(",")) if (!pkg.isEmpty() && !result.contains(pkg)) result.add(pkg);
        return result;
    }
    void excludedOrder(List<String> order) { data.edit().putString("flip_excluded_order", order == null ? "" : String.join(",", order)).apply(); }

    /** dock 默认按钮：返回 / Home / 多任务 / 手电筒 / 截图（5个，一页排完）。 */
    static final String[] DOCK_DEFAULT = {"back", "home", "recents", "torch", "screenshot"};
    /** 旧版 dock 默认（10 项），仅用于迁移判定：识别「用户从未手动排过序」的情形。 */
    static final String[] DOCK_LEGACY = {"app_dock", "controls", "rotation", "notifications", "back", "home", "recents", "torch", "screenshot", "configure"};

    public List<String> actions(String kind) {
        String[] defaults = kind.equals("favorites") ? new String[]{"screenshot", "rotation", "notification_list"} : kind.equals("dock")
            ? DOCK_DEFAULT
            : new String[]{"wifi", "bluetooth", "data", "torch", "dnd", "airplane", "rotation", "screenshot", "nfc", "location", "powersave"};
        String saved = data.getString(kind, null);
        if (saved == null) return new ArrayList<>(Arrays.asList(defaults));
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length() && result.size() < 30; i++) {
                String id = array.optString(i);
                // dock 已移除旋转按钮（免 Shizuku 下不可靠），读取时顺带清掉历史残留。
                if (kind.equals("dock") && "rotation".equals(id)) continue;
                if (ActionCatalog.valid(id) && !result.contains(id)) result.add(id);
            }
        } catch (Exception ignored) { return new ArrayList<>(Arrays.asList(defaults)); }
        return result;
    }
    public void saveActions(String kind, List<String> ids) { data.edit().putString(kind, new JSONArray(ids).toString()).apply(); }
    public int corner(int rotation) { return data.getInt("corner_" + rotation, (3 + rotation) % 4); }
    /** 首次安装记录的缺口；空字符串表示该方向尚未记录。 */
    public String cutoutRecord(int rotation) { return data.getString("cutout_" + rotation, ""); }
    public void cutoutRecord(int rotation, String record) { data.edit().putString("cutout_" + rotation, record == null ? "" : record).apply(); }
    /** 清空所有方向的缺口记录，外屏下次挂载时重新读取并固定。 */
    public void clearCutoutRecords() {
        android.content.SharedPreferences.Editor update = data.edit();
        for (int rotation = 0; rotation < 4; rotation++) update.remove("cutout_" + rotation);
        update.apply();
    }
    public float widthRatio() { return Math.max(.25f, Math.min(.70f, data.getFloat("dock_width", .46f))); }
    public float heightRatio() { return Math.max(.05f, Math.min(.22f, data.getFloat("dock_height", .088f))); }
    JSONObject layoutSnapshot() throws JSONException {
        JSONObject panelOptions = panelSnapshot(); panelOptions.remove("columns");
        JSONObject layout = new JSONObject().put("version", 10).put("workspace", workspaceJson(workspace())).put("workspaceColumns", workspace().columns()).put("workspaceRows", workspace().rows()).put("workspaceLocked", workspaceLocked()).put("workspaceLabels", workspaceLabels()).put("workspaceBadges", workspaceBadges()).put("workspaceDensity", workspaceDensity()).put("workspaceAliases", workspaceAliases()).put("workspaceCompact", workspaceCompact()).put("panelColumns", panelColumns()).put("panelOptions", panelOptions).put("hubPins", new JSONArray(hubPins())).put("hubSort", hubSort()).put("chrome", chromeStyle()).put("statusScale", statusScale()).put("statusSafeLeft", statusSafeLeft()).put("statusSafeRight", statusSafeRight()).put("batteryPercent", batteryPercent()).put("avoidNavigation", avoidNavigation()).put("navigationGap", navigationGap()).put("tapHandles", tapHandles()).put("hand", handSide()).put("perPage", perPage()).put("pinned", pinnedAction()).put("hubPinned", hubPinned()).put("damping", damping())
            .put("dock", new JSONArray(actions("dock"))).put("panel", new JSONArray(actions("panel"))).put("favorites", new JSONArray(actions("favorites")))
            .put("automatic", data.getBoolean("auto_placement", true)).put("width", widthRatio()).put("height", heightRatio()).put("blur", panelBlur()).put("statusEnabled", statusEnabled());
        layout.put("homeAction", homeAction());
        JSONArray corners = new JSONArray(); for (int rotation = 0; rotation < 4; rotation++) corners.put(corner(rotation)); layout.put("corners", corners);
        JSONObject status = new JSONObject(); for (String item : STATUS_ITEMS) status.put(item, statusItem(item)); return layout.put("statusItems", status);
    }
    /** Builds one unapplied editor; malformed input cannot partially change the live layout. */
    SharedPreferences.Editor prepareLayout(JSONObject layout, SharedPreferences.Editor update) throws JSONException {
        for (String key : new String[]{"version", "perPage", "damping"}) workspaceInteger(layout.get(key));
        for (String key : new String[]{"automatic", "blur", "statusEnabled", "batteryPercent", "avoidNavigation", "tapHandles"}) if (layout.has(key) && !(layout.get(key) instanceof Boolean)) throw new IllegalArgumentException("布局开关值无效");
        for (String key : new String[]{"width", "height"}) if (!(layout.get(key) instanceof Number)) throw new IllegalArgumentException("布局尺寸无效");
        int version = layout.getInt("version"); if (version < 1 || version > 10) throw new IllegalArgumentException("不支持的布局版本");
        Object home = version >= 10 ? layout.get("homeAction") : "cards";
        if (!(home instanceof String action) || !validHomeAction(action)) throw new IllegalArgumentException("Home 按钮效果无效");
        update.putString("home_action", (String) home);
        AppWorkspaceLayout workspace = version >= 8 ? readWorkspace(layout.getJSONArray("workspace"), version >= 9 ? workspaceInteger(layout.get("workspaceColumns")) : 5, version >= 9 ? workspaceInteger(layout.get("workspaceRows")) : 3, version >= 9) : new AppWorkspaceLayout();
        Object compact = version >= 8 ? layout.get("workspaceCompact") : false;
        if (!(compact instanceof Boolean)) throw new IllegalArgumentException("自动补位设置无效");
        if ((Boolean) compact && !workspace.equals(workspace.compact())) throw new IllegalArgumentException("自动补位布局不能包含空位");
        update.putString("hub_workspace", workspaceStorage(workspace)).putBoolean("hub_workspace_compact", (Boolean) compact);
        for (String option : new String[]{"Locked", "Labels", "Badges"}) { Object value = version >= 9 ? layout.get("workspace" + option) : option.equals("Labels"); if (!(value instanceof Boolean)) throw new IllegalArgumentException("桌面设置无效"); update.putBoolean("hub_workspace_" + option.toLowerCase(java.util.Locale.ROOT), (Boolean) value); }
        String density = version >= 9 ? layout.getString("workspaceDensity") : "compact";
        if (!java.util.Set.of("compact", "normal", "easy").contains(density)) throw new IllegalArgumentException("桌面密度无效");
        JSONObject aliases = version >= 9 ? layout.getJSONObject("workspaceAliases") : new JSONObject();
        if (aliases.length() > AppWorkspaceLayout.MAX_APPS) throw new IllegalArgumentException("应用别名过多");
        java.util.Iterator<String> names = aliases.keys(); while (names.hasNext()) { String id = names.next(); Object value = aliases.get(id); if (!id.startsWith("app:") || !ActionCatalog.valid(id) || !(value instanceof String text) || text.trim().isEmpty() || text.codePointCount(0, text.length()) > 24 || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("应用别名无效"); }
        update.putString("hub_workspace_density", density).putString("hub_workspace_aliases", aliases.toString());
        Object rawColumns = version >= 5 ? layout.get("panelColumns") : 4;
        if (!(rawColumns instanceof Number number) || number.doubleValue() != number.intValue() || !validPanelColumns(number.intValue())) throw new IllegalArgumentException("控制中心列数无效");
        JSONObject panelOptions = version >= 6 ? new JSONObject(layout.getJSONObject("panelOptions").toString()) : defaultPanelSettings();
        preparePanelSettings(panelOptions.put("columns", ((Number) rawColumns).intValue()), update).remove("panel_undo");
        for (String side : new String[]{"Left", "Right"}) {
            Object raw = version >= 7 ? layout.get("statusSafe" + side) : 16;
            if (!(raw instanceof Number value) || value.doubleValue() != value.intValue() || value.intValue() < 0 || value.intValue() > 48) throw new IllegalArgumentException("状态栏安全留白无效");
            update.putInt(side.equals("Left") ? "status_safe_left" : "status_safe_right", ((Number) raw).intValue());
        }
        if (version >= 3) {
            String style = layout.getString("chrome"); Object scale = layout.get("statusScale"), gap = layout.get("navigationGap");
            if (!validChrome(style) || !(scale instanceof Number a) || a.doubleValue() != a.intValue() || a.intValue() < 50 || a.intValue() > 150 || !(gap instanceof Number b) || b.doubleValue() != b.intValue() || b.intValue() < 0 || b.intValue() > 48) throw new IllegalArgumentException("状态栏大小或手势避让值无效");
            update.putString("chrome_style", style).putInt("status_scale", ((Number) scale).intValue()).putBoolean("battery_percent", layout.getBoolean("batteryPercent"))
                .putBoolean("avoid_navigation", layout.getBoolean("avoidNavigation")).putInt("navigation_gap", ((Number) gap).intValue()).putBoolean("tap_handles", layout.getBoolean("tapHandles"));
        }
        String hand = version == 1 ? "auto" : layout.getString("hand"); if (!validHand(hand)) throw new IllegalArgumentException("无效单手布局");
        int count = layout.getInt("perPage"), damping = layout.getInt("damping"); double width = layout.getDouble("width"), height = layout.getDouble("height");
        if (count < 2 || count > 5 || damping < 0 || damping > 2 || !Double.isFinite(width) || !Double.isFinite(height) || width < .25 || width > .70 || height < .05 || height > .22) throw new IllegalArgumentException("布局尺寸或按钮数量无效");
        String pinned = layout.getString("pinned"), hubPinned = layout.getString("hubPinned");
        if (!ActionCatalog.valid(pinned) || !hubPinned.isEmpty() && (!hubPinned.startsWith("app:") || !ActionCatalog.valid(hubPinned))) throw new IllegalArgumentException("布局按钮无效");
        JSONArray hubPins = version >= 4 ? layout.getJSONArray("hubPins") : new JSONArray(); if (version < 4 && !hubPinned.isEmpty()) hubPins.put(hubPinned);
        for (String id : checkedHubPins(hubPins)) if (workspace.apps().contains(id)) throw new IllegalArgumentException("常驻应用不能同时占用桌面或文件夹格位");
        prepareHubPins(hubPins, update); String hubSort = version >= 4 ? layout.getString("hubSort") : "name"; if (!validHubSort(hubSort)) throw new IllegalArgumentException("应用排序无效"); update.putString("hub_sort", hubSort);
        for (String key : new String[]{"dock", "panel", "favorites"}) {
            JSONArray source = layout.getJSONArray(key); if (source.length() > 30) throw new IllegalArgumentException("布局快捷项过多"); List<String> ids = new ArrayList<>();
            for (int i = 0; i < source.length(); i++) { String id = source.getString(i); if (!ActionCatalog.valid(id)) throw new IllegalArgumentException("布局快捷项无效"); if (!ids.contains(id)) ids.add(id); }
            update.putString(key, new JSONArray(ids).toString());
        }
        JSONArray corners = layout.getJSONArray("corners"); if (corners.length() != 4) throw new IllegalArgumentException("缺少四方向位置");
        for (int rotation = 0; rotation < 4; rotation++) { int corner = workspaceInteger(corners.get(rotation)); if (corner < 0 || corner > 3) throw new IllegalArgumentException("无效停靠位置"); update.putInt("corner_" + rotation, corner); }
        JSONObject status = layout.getJSONObject("statusItems"); for (String item : STATUS_ITEMS) { if (!(status.get(item) instanceof Boolean)) throw new IllegalArgumentException("状态栏开关值无效"); update.putBoolean("status_" + item, status.getBoolean(item)); }
        return update.putString("hand_side", hand).putInt("per_page", count).putInt("damping", damping).putString("pinned_action", pinned)
            .putBoolean("auto_placement", layout.getBoolean("automatic")).putFloat("dock_width", (float) width).putFloat("dock_height", (float) height)
            .putBoolean("panel_blur", layout.getBoolean("blur")).putBoolean("status_enabled", layout.getBoolean("statusEnabled"));
    }
    void saveLayout() throws JSONException { data.edit().putString("layout_backup", layoutSnapshot().toString()).putLong("layout_saved_at", System.currentTimeMillis()).apply(); }
    void restoreLayout(boolean undo) throws JSONException {
        String saved = data.getString(undo ? "layout_undo" : "layout_backup", null); if (saved == null) throw new IllegalArgumentException("还没有可恢复的布局");
        SharedPreferences.Editor update = prepareLayout(new JSONObject(saved), data.edit());
        if (undo) update.remove("layout_undo"); else update.putString("layout_undo", layoutSnapshot().toString()); update.apply();
    }
    void resetLayout() throws JSONException {
        SharedPreferences.Editor update = data.edit().putString("layout_undo", layoutSnapshot().toString());
        update.remove("home_action");
        update.remove("hub_workspace").remove("hub_workspace_compact").remove("hub_workspace_locked").remove("hub_workspace_labels").remove("hub_workspace_badges").remove("hub_workspace_density").remove("hub_workspace_aliases");
        for (String key : new String[]{"panel_columns", "panel_density", "panel_labels", "panel_label_size", "panel_tools_position", "panel_brightness", "panel_volume", "panel_media", "panel_media_idle", "panel_undo", "hub_pins", "hub_sort", "chrome_style", "status_scale", "status_safe_left", "status_safe_right", "battery_percent", "avoid_navigation", "navigation_gap", "tap_handles", "hand_side", "per_page", "damping", "pinned_action", "hub_pinned", "dock", "panel", "favorites", "auto_placement", "dock_width", "dock_height", "panel_blur", "status_enabled"}) update.remove(key);
        for (int rotation = 0; rotation < 4; rotation++) update.remove("corner_" + rotation);
        for (String item : STATUS_ITEMS) update.remove("status_" + item); update.apply();
    }
}
