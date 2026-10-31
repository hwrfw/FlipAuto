package com.flipauto;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.provider.Settings;

/** 「使用情况访问」权限的检查与跳转；该权限由用户在系统设置中授予，不能弹窗申请。 */
final class UsageAccess {
    private UsageAccess() { }

    static boolean granted(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        try {
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 打开系统的使用情况访问列表；个别机型没有该页面时退回本应用详情。 */
    static void open(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            try {
                context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.getPackageName(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { }
        }
    }
}
