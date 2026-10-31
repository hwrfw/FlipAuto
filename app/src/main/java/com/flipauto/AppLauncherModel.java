package com.flipauto;

import android.content.ComponentName;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Shared launcher rules; neither renderer owns a second app/folder configuration. */
final class AppLauncherModel {
    static final String EDIT_HINT = "如需编辑，请到浮窗启动器内操作。";
    static long itemId(String id) { long hash = 0xcbf29ce484222325L; for (int i = 0; i < id.length(); i++) { hash ^= id.charAt(i); hash *= 0x100000001b3L; } return hash; }
    static List<AppCatalogCache.Entry> select(List<AppCatalogCache.Entry> catalog, List<String> pins, JSONObject aliases, String query, String order, List<RecentTasks.Task> tasks, AppSearchIndex index) {
        return select(catalog, pins, aliases, query, order, tasks, index, new HashMap<>());
    }
    /**
     * @param usage 本应用内记录的启动时间（package → millis），来自 {@code Prefs.appUsage()}。
     *              「used」排序下它整段优先于系统最近任务 —— 系统任务每几秒就会变，混排会让列表抖动。
     */
    static List<AppCatalogCache.Entry> select(List<AppCatalogCache.Entry> catalog, List<String> pins, JSONObject aliases, String query, String order, List<RecentTasks.Task> tasks, AppSearchIndex index, Map<String, Long> usage) {
        List<AppCatalogCache.Entry> result = new ArrayList<>();
        for (AppCatalogCache.Entry app : catalog) if ((!query.isEmpty() || !pins.contains(app.id())) && index.matches(app, aliases.optString(app.id(), ""), query)) result.add(app);
        if (order.equals("reverse")) Collections.reverse(result);
        else if (order.equals("recent")) { Map<String, Integer> ranks = new HashMap<>(); for (RecentTasks.Task task : tasks) ranks.putIfAbsent(task.packageName(), ranks.size()); result.sort((a, b) -> Integer.compare(ranks.getOrDefault(a.packageName(), Integer.MAX_VALUE), ranks.getOrDefault(b.packageName(), Integer.MAX_VALUE))); }
        else if (order.equals("used")) {
            Map<String, Integer> ranks = new HashMap<>();
            for (RecentTasks.Task task : tasks) ranks.putIfAbsent(task.packageName(), ranks.size());
            java.text.Collator collator = java.text.Collator.getInstance();
            result.sort((a, b) -> {
                long usedA = usage.getOrDefault(a.packageName(), 0L), usedB = usage.getOrDefault(b.packageName(), 0L);
                if (usedA != usedB) return Long.compare(usedB, usedA);   // 点过的按最近时间倒序，整段置顶
                if (usedA != 0L) return collator.compare(a.label(), b.label());
                int rankA = ranks.getOrDefault(a.packageName(), Integer.MAX_VALUE), rankB = ranks.getOrDefault(b.packageName(), Integer.MAX_VALUE);
                if (rankA != rankB) return Integer.compare(rankA, rankB); // 没点过的：系统最近任务优先
                return collator.compare(a.label(), b.label());            // 都没有：名称序
            });
        }
        return List.copyOf(result);
    }
    static AppWorkspaceLayout reconcile(AppWorkspaceLayout saved, List<AppCatalogCache.Entry> catalog, List<String> pins, boolean compact) {
        List<String> available = new ArrayList<>(); for (AppCatalogCache.Entry app : catalog) if (!pins.contains(app.id())) available.add(app.id());
        return saved.reconcile(available, compact);
    }
    static String label(String id, JSONObject aliases, AppCatalogCache cache) {
        String alias = aliases.optString(id, ""); if (!alias.isEmpty()) return alias;
        String cached = cache.label(id); if (cached != null) return cached;
        for (ActionCatalog.Action action : ActionCatalog.BUILT_INS) if (action.id().equals(id)) return action.label();
        ComponentName name = ActionCatalog.component(id); return name == null ? "应用" : name.getPackageName();
    }
    static Map<String, Integer> badges(boolean enabled, boolean ready, List<StatusBarNotification> notifications) {
        Map<String, Integer> result = new HashMap<>();
        if (enabled && ready) for (StatusBarNotification item : notifications) if (item.getUser().equals(android.os.Process.myUserHandle()) && !item.isOngoing() && (item.getNotification().flags & android.app.Notification.FLAG_GROUP_SUMMARY) == 0) result.merge(item.getPackageName(), 1, Integer::sum);
        return result;
    }
    static int badge(String id, Map<String, Integer> badges) { ComponentName name = ActionCatalog.component(id); return name == null ? 0 : badges.getOrDefault(name.getPackageName(), 0); }
}
