package com.flipauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启：开机后按偏好自动启动外屏增强服务。 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        if (!prefs.flipBubbles() && !prefs.flipRestore() && !prefs.flipAutoLaunch()) return;
        Intent serviceIntent = new Intent(context, FlipAutoService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent);
            else context.startService(serviceIntent);
        } catch (Exception ignored) { }
    }
}
