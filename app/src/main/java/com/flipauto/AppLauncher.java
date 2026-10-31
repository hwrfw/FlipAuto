package com.flipauto;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.Display;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One explicit-app launch policy for the floating launcher and native card. */
final class AppLauncher {
    private boolean busy;
    static boolean ready(Context context, Prefs prefs, int target) {
        Display selected = Displays.selected(context, prefs);
        return target > 0 && selected != null && selected.getDisplayId() == target && selected.getState() == Display.STATE_ON && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    void launch(Context context, Prefs prefs, String id, int target, BooleanSupplier ownerReady, Consumer<String> message, Consumer<Boolean> completed) {
        ComponentName component = ActionCatalog.component(id);
        if (component == null || !ready(context, prefs, target) || !ownerReady.getAsBoolean()) { message.accept("外屏不可用，请解锁后重试"); completed.accept(false); return; }
        if (busy) { message.accept("正在打开应用，请稍候"); completed.accept(false); return; }
        // 检测是否为工作资料/分身应用（ID 格式：app:package/name#userN）
        final boolean isWorkProfile = id.contains("#user");
        final UserHandle workUser;
        if (isWorkProfile) {
            UserHandle found = null;
            try {
                int userHash = Integer.parseInt(id.substring(id.lastIndexOf("#user") + 5));
                UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
                if (userManager != null) {
                    for (UserHandle user : userManager.getUserProfiles()) {
                        if (user.hashCode() == userHash) { found = user; break; }
                    }
                }
            } catch (RuntimeException ignored) { }
            workUser = found;
        } else {
            workUser = null;
        }
        // 复用已有任务（从后台拉回），没有才新建。
        // 不能加 FLAG_ACTIVITY_MULTIPLE_TASK：它的语义是「即使已有 task 也新建」，会导致每次点击都冷启动。
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Runnable start = () -> {
            if (!ready(context, prefs, target) || !ownerReady.getAsBoolean()) { message.accept("外屏状态已改变，请重新打开应用"); completed.accept(false); return; }
            try {
                if (isWorkProfile && workUser != null) {
                    // 工作资料应用通过 LauncherApps 启动，指定用户和外屏
                    LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                    if (launcherApps != null) {
                        launcherApps.startMainActivity(component, workUser, null,
                                ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle());
                    } else {
                        context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle());
                    }
                } else {
                    context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle());
                }
                prefs.noteAppUsed(component.getPackageName()); // 记录「点过」，用于应用列表把常用项浮到顶部
                completed.accept(true);
            }
            catch (RuntimeException error) { message.accept("无法在外屏打开：" + error.getClass().getSimpleName()); completed.accept(false); }
        };
        start.run();
    }
}
