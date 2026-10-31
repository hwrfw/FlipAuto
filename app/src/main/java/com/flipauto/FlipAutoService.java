package com.flipauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;

/**
 * 外屏悬浮气泡服务：
 * 外屏小窗应用的悬浮球：点击启动/收起，拖动吸附边缘，长按删除。
 * 气泡的显示/隐藏由 CoverService 通过外屏窗口检测后广播驱动。
 * 多任务入口已改为屏幕下方常驻按键（CoverService 提供），不再使用悬浮球。
 */
public final class FlipAutoService extends Service {
    public static final String ACTION_SHOW_BUBBLE = "com.flipauto.auto.SHOW_BUBBLE";
    public static final String ACTION_CLEAR_BUBBLES = "com.flipauto.auto.CLEAR_BUBBLES";
    public static final String ACTION_UPDATE_BUBBLE_SIZE = "com.flipauto.auto.UPDATE_BUBBLE_SIZE";
    public static final String ACTION_HIDE_BUBBLES_TEMP = "com.flipauto.auto.HIDE_BUBBLES_TEMP";
    /** 用户长按手动关闭悬浮球：通知无障碍服务从 activeSmallWindows 移除该包名，不再自动弹出。 */
    public static final String ACTION_FORGET_BUBBLE = "com.flipauto.auto.FORGET_BUBBLE";
    public static final String ACTION_OPEN_NOTIFICATION_PANEL = "com.flipauto.auto.OPEN_NOTIFICATION_PANEL";
    /** 开屏恢复：在外屏启动指定包（由无障碍服务检测触发，前台服务执行启动以绕过后台限制）。 */
    public static final String ACTION_RESTORE_APP = "com.flipauto.auto.RESTORE_APP";
    /** 自动启动：在外屏启动偏好里的目标应用。 */
    public static final String ACTION_AUTO_LAUNCH = "com.flipauto.auto.AUTO_LAUNCH";
    /** 启动辅助：短暂显示一个 1px 可见悬浮窗口，让本 uid 具备可见窗口以豁免后台启动限制（BAL）。 */
    public static final String ACTION_PREPARE_LAUNCH = "com.flipauto.auto.PREPARE_LAUNCH";

    private static final String CHANNEL_ID = "flipauto_channel";
    private static final long AUTO_HIDE_DELAY = 3000;
    public static volatile long autoLaunchDisabledUntil = 0;

    private Prefs prefs;
    private WindowManager windowManager;
    private Context displayContext;
    private DisplayManager displayManager;
    private final Map<String, BubbleHolder> activeBubbles = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean areBubblesTemporarilyHidden = false;
    private Runnable restoreBubblesRunnable;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { mainHandler.post(FlipAutoService.this::clampAndSnapAllBubbles); }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) { mainHandler.post(FlipAutoService.this::clampAndSnapAllBubbles); }
    };

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_SHOW_BUBBLE.equals(action)) {
                String pkg = intent.getStringExtra("package");
                boolean show = intent.getBooleanExtra("show", false);
                if (pkg != null) { if (show) showBubble(pkg); else removeBubble(pkg); }
            } else if (ACTION_CLEAR_BUBBLES.equals(action)) clearAllBubbles();
            else if (ACTION_UPDATE_BUBBLE_SIZE.equals(action)) updateAllBubblesSize();
            else if (ACTION_HIDE_BUBBLES_TEMP.equals(action)) hideAllBubblesTemporarily();
            else if (ACTION_RESTORE_APP.equals(action)) {
                // 旧路径：前台服务从后台 startActivity 会触发 BAL 限制而静默失败。
                // 现在恢复统一由 CoverService.launchPackageZip 执行，这里仅保留日志以便发现回归。
                android.util.Log.w("FlipCover-Restore", "ACTION_RESTORE_APP 已弃用：恢复应走 CoverService.launchPackageZip");
            }             else if (ACTION_AUTO_LAUNCH.equals(action)) {
                android.util.Log.w("FlipCover-Restore", "ACTION_AUTO_LAUNCH 已弃用：自动启动应走 CoverService.launchPackageZip");
            } else if (ACTION_PREPARE_LAUNCH.equals(action)) {
                showLaunchAssist();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        try { createNotificationChannel(); startForeground(1, buildNotification()); }
        catch (Exception e) { Log.e("FlipAutoService", "启动前台服务失败", e); }
        prefs = new Prefs(this);
        displayContext = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            try {
                Display externalDisplay = displayManager.getDisplay(1);
                if (externalDisplay != null) {
                    try {
                        displayContext = createDisplayContext(externalDisplay);
                        windowManager = (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
                    } catch (Exception e) { displayContext = this; windowManager = (WindowManager) getSystemService(WINDOW_SERVICE); }
                }
            } catch (Exception ignored) { }
        }
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (displayManager != null) displayManager.registerDisplayListener(displayListener, mainHandler);
        restoreBubblesRunnable = new Runnable() {
            @Override public void run() {
                areBubblesTemporarilyHidden = false;
                for (BubbleHolder holder : activeBubbles.values()) {
                    if (!holder.isAttached && holder.view != null && windowManager != null) {
                        try { windowManager.addView(holder.view, holder.params); holder.isAttached = true;
                            if (holder.isHidden) applyHalfHidden(holder); else applyExpanded(holder); } catch (Exception ignored) { }
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW_BUBBLE); filter.addAction(ACTION_CLEAR_BUBBLES); filter.addAction(ACTION_UPDATE_BUBBLE_SIZE);
        filter.addAction(ACTION_HIDE_BUBBLES_TEMP); filter.addAction(ACTION_RESTORE_APP); filter.addAction(ACTION_AUTO_LAUNCH);
        filter.addAction(ACTION_PREPARE_LAUNCH);
        registerReceiverSafe(configReceiver, filter);
    }

    private void registerReceiverSafe(BroadcastReceiver receiver, IntentFilter filter) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, filter);
        } catch (Exception ignored) { }
    }

    // ==================== 气泡窗口 ====================
    private static final class BubbleHolder {
        View view; WindowManager.LayoutParams params; boolean isHidden; Runnable hideRunnable; int size; boolean isSticky; boolean isAttached;
        BubbleHolder(View view, WindowManager.LayoutParams params, int size) {
            this.view = view; this.params = params; this.size = size; this.isHidden = false; this.isSticky = false; this.hideRunnable = null; this.isAttached = false;
        }
    }

    private void hideAllBubblesTemporarily() {
        areBubblesTemporarilyHidden = true;
        mainHandler.removeCallbacks(restoreBubblesRunnable);
        for (BubbleHolder holder : activeBubbles.values()) {
            if (holder.isAttached && holder.view != null && windowManager != null) {
                try { windowManager.removeView(holder.view); holder.isAttached = false; } catch (Exception ignored) { }
            }
        }
        mainHandler.postDelayed(restoreBubblesRunnable, 10000);
    }

    private void clampAndSnapAllBubbles() {
        if (displayContext == null || windowManager == null) return;
        DisplayMetrics metrics = realMetrics();
        int maxH = metrics.heightPixels;
        for (BubbleHolder holder : activeBubbles.values()) {
            if (!holder.isAttached) continue;
            WindowManager.LayoutParams p = holder.params;
            if (holder.isHidden) applyHalfHidden(holder); else snapToEdge(p, holder.size);
            if (p.y < 0) p.y = 0;
            if (p.y + holder.size > maxH) p.y = maxH - holder.size;
            try { windowManager.updateViewLayout(holder.view, p); } catch (Exception ignored) { }
        }
    }

    // ==================== 启动辅助窗口（绕过后台启动限制 BAL） ====================
    private View launchAssistView;
    private Runnable launchAssistRemover;
    private static final long LAUNCH_ASSIST_KEEP_MS = 5000;

    /**
     * Android 14+ 的后台 Activity 启动限制（BAL）会「静默」拦截后台 startActivity（不抛异常，
     * 只在 logcat 留 Background activity launch blocked）。系统对「uid 存在可见窗口」放行，
     * FlipAuto 原版之所以能生效，正是因为它常驻气泡悬浮窗。
     * 这里在启动前临时挂一个 1px 透明窗口，启动完成后延迟移除，既不常驻也不影响观感。
     */
    private void showLaunchAssist() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.canDrawOverlays(this)) {
            Log.w("FlipAutoService", "无悬浮窗权限，跳过启动辅助窗口（BAL 可能拦截恢复）");
            return;
        }
        mainHandler.post(() -> {
            try {
                if (launchAssistRemover != null) { mainHandler.removeCallbacks(launchAssistRemover); launchAssistRemover = null; }
                WindowManager wm = windowManager != null ? windowManager : (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm == null) return;
                if (launchAssistView == null) {
                    View v = new View(this);
                    int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                            1, 1, type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSPARENT);
                    p.gravity = Gravity.START | Gravity.TOP;
                    p.x = 0; p.y = 0; p.alpha = 0.01f;
                    wm.addView(v, p);
                    launchAssistView = v;
                }
                launchAssistRemover = this::removeLaunchAssist;
                mainHandler.postDelayed(launchAssistRemover, LAUNCH_ASSIST_KEEP_MS);
            } catch (Exception e) { Log.w("FlipAutoService", "启动辅助窗口失败", e); }
        });
    }

    private void removeLaunchAssist() {
        try {
            if (launchAssistView != null) {
                WindowManager wm = windowManager != null ? windowManager : (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) wm.removeView(launchAssistView);
            }
        } catch (Exception ignored) { }
        launchAssistView = null;
        launchAssistRemover = null;
    }

    private void resetToHidden(BubbleHolder holder) {
        if (!holder.isAttached) return;
        if (holder.hideRunnable != null) { mainHandler.removeCallbacks(holder.hideRunnable); holder.hideRunnable = null; }
        applyHalfHidden(holder); holder.isHidden = true; holder.isSticky = false;
        try { windowManager.updateViewLayout(holder.view, holder.params); } catch (Exception ignored) { }
    }

    private void expandAndStartTimer(BubbleHolder holder) {
        if (!holder.isAttached) return;
        if (holder.hideRunnable != null) { mainHandler.removeCallbacks(holder.hideRunnable); holder.hideRunnable = null; }
        applyExpanded(holder); holder.isHidden = false; holder.isSticky = true;
        try { windowManager.updateViewLayout(holder.view, holder.params); } catch (Exception ignored) { }
        holder.hideRunnable = () -> { resetToHidden(holder); holder.hideRunnable = null; };
        mainHandler.postDelayed(holder.hideRunnable, AUTO_HIDE_DELAY);
    }

    private void snapToEdge(WindowManager.LayoutParams params, int size) {
        DisplayMetrics metrics = realMetrics();
        int maxW = metrics.widthPixels;
        if (params.x + size / 2 < maxW / 2) params.x = 0; else params.x = maxW - size;
    }

    private void applyHalfHidden(BubbleHolder holder) {
        DisplayMetrics metrics = realMetrics();
        int maxW = metrics.widthPixels;
        int offset = holder.size / 2;
        if (holder.params.x + holder.size / 2 < maxW / 2) holder.params.x = -offset; else holder.params.x = maxW - offset;
        holder.view.setAlpha(0.5f);
    }

    private void applyExpanded(BubbleHolder holder) {
        if (holder.params.x + holder.size / 2 < screenWidth() / 2) holder.params.x = 0; else holder.params.x = screenWidth() - holder.size;
        holder.view.setAlpha(0.6f);
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            Display live = (displayManager != null) ? displayManager.getDisplay(1) : null;
            if (live == null && displayContext != null) live = displayContext.getDisplay();
            if (live != null) live.getRealMetrics(metrics);
        } catch (Exception ignored) { }
        return metrics;
    }

    private int screenWidth() { return realMetrics().widthPixels; }

    private void showBubble(String pkg) {
        if (!prefs.flipBubbles() || activeBubbles.containsKey(pkg)) return;
        if (windowManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;
        final FrameLayout bubbleLayout = new FrameLayout(displayContext);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL); bg.setColor(0x88000000); bg.setStroke(2, 0x55FFFFFF);
        bubbleLayout.setBackground(bg); bubbleLayout.setPadding(10, 10, 10, 10); bubbleLayout.setAlpha(0.6f);
        ImageView iconView = new ImageView(displayContext);
        try { iconView.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Exception e) { return; }
        bubbleLayout.addView(iconView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final int size = prefs.flipBubbleSize();
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size, layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = 0; params.y = 300;
        final BubbleHolder holder = new BubbleHolder(bubbleLayout, params, size);
        snapToEdge(params, size); bubbleLayout.setAlpha(0.6f);
        if (!areBubblesTemporarilyHidden) {
            try { windowManager.addView(bubbleLayout, params); holder.isAttached = true; } catch (Exception e) { return; }
        }
        activeBubbles.put(pkg, holder);
        holder.hideRunnable = () -> { resetToHidden(holder); holder.hideRunnable = null; };
        mainHandler.postDelayed(holder.hideRunnable, AUTO_HIDE_DELAY);
        bubbleLayout.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY; private float initialTouchX, initialTouchY; private boolean isMoved = false; private long downTime;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (!holder.isAttached) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x; initialY = params.y; initialTouchX = event.getRawX(); initialTouchY = event.getRawY(); isMoved = false; downTime = System.currentTimeMillis(); return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - initialTouchX, dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoved = true;
                            if (holder.isHidden) { if (holder.hideRunnable != null) { mainHandler.removeCallbacks(holder.hideRunnable); holder.hideRunnable = null; } applyExpanded(holder); holder.isHidden = false; }
                            params.x = initialX + (int) dx; params.y = initialY + (int) dy;
                            try { windowManager.updateViewLayout(bubbleLayout, params); } catch (Exception ignored) { }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!isMoved) {
                            long duration = System.currentTimeMillis() - downTime;
                            if (duration > 500) {
                                removeBubble(pkg);
                                // 通知无障碍服务从 activeSmallWindows 移除该包名，不再自动弹出
                                try { Intent forget = new Intent(ACTION_FORGET_BUBBLE); forget.putExtra("package", pkg); forget.setPackage(getPackageName()); sendBroadcast(forget); } catch (Exception ignored) { }
                                return true;
                            }
                            if (holder.isHidden) { if (holder.hideRunnable != null) { mainHandler.removeCallbacks(holder.hideRunnable); holder.hideRunnable = null; } expandAndStartTimer(holder); }
                            else { if (holder.hideRunnable != null) { mainHandler.removeCallbacks(holder.hideRunnable); holder.hideRunnable = null; } launchPackage(pkg);
                                holder.hideRunnable = () -> { resetToHidden(holder); holder.hideRunnable = null; }; mainHandler.postDelayed(holder.hideRunnable, AUTO_HIDE_DELAY); }
                        } else {
                            if (holder.hideRunnable != null) { mainHandler.removeCallbacks(holder.hideRunnable); holder.hideRunnable = null; }
                            if (isNearEdge(params.x, size)) {
                                snapToEdge(params, size); holder.view.setAlpha(0.6f); holder.isHidden = false; holder.isSticky = true;
                                try { windowManager.updateViewLayout(holder.view, params); } catch (Exception ignored) { }
                                holder.hideRunnable = () -> { resetToHidden(holder); holder.hideRunnable = null; }; mainHandler.postDelayed(holder.hideRunnable, AUTO_HIDE_DELAY);
                            } else { holder.view.setAlpha(0.6f); holder.isHidden = false; holder.isSticky = false; try { windowManager.updateViewLayout(holder.view, params); } catch (Exception ignored) { } }
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void removeBubble(String pkg) {
        BubbleHolder holder = activeBubbles.remove(pkg);
        if (holder != null) {
            if (holder.hideRunnable != null) mainHandler.removeCallbacks(holder.hideRunnable);
            if (holder.isAttached) { try { windowManager.removeView(holder.view); } catch (Exception ignored) { } holder.isAttached = false; }
        }
    }

    private void clearAllBubbles() {
        for (BubbleHolder holder : activeBubbles.values()) {
            if (holder.hideRunnable != null) mainHandler.removeCallbacks(holder.hideRunnable);
            if (holder.isAttached) { try { windowManager.removeView(holder.view); } catch (Exception ignored) { } holder.isAttached = false; }
        }
        activeBubbles.clear();
    }

    private void updateAllBubblesSize() {
        int size = prefs.flipBubbleSize();
        for (BubbleHolder holder : activeBubbles.values()) {
            if (!holder.isAttached) continue;
            holder.size = size; holder.params.width = size; holder.params.height = size;
            if (holder.isHidden) applyHalfHidden(holder); else applyExpanded(holder);
            try { windowManager.updateViewLayout(holder.view, holder.params); } catch (Exception ignored) { }
        }
    }

    private boolean isNearEdge(int x, int size) {
        int w = screenWidth();
        int threshold = (int) (w * 0.15f);
        return (x <= threshold) || (x + size >= w - threshold);
    }

    private void launchPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) { android.util.Log.e("FlipCover-Restore", "launchPackage: no launch intent for " + packageName); return; }
            // 复用已有任务（从后台拉回），没有才新建：不能加 FLAG_ACTIVITY_MULTIPLE_TASK（会强制新建 → 冷启动）。
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    int extId = 1;
                    if (displayContext != null && displayContext.getDisplay() != null) extId = displayContext.getDisplay().getDisplayId();
                    else if (displayManager != null && displayManager.getDisplay(1) != null) extId = 1;
                    android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(extId);
                    startActivity(launchIntent, options.toBundle());
                    android.util.Log.d("FlipCover-Restore", "launchPackage: started " + packageName + " on display " + extId);
                    return;
                } catch (Exception e) { android.util.Log.e("FlipCover-Restore", "launchPackage: external display launch failed", e); }
            }
            startActivity(launchIntent);
            android.util.Log.d("FlipCover-Restore", "launchPackage: started " + packageName + " on default display");
        } catch (Exception ignored) { }
    }

    // ==================== 前台通知 ====================
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "外屏增强服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("FlipAuto")
                .setContentText("悬浮气泡服务运行中")
                .setSmallIcon(R.drawable.ic_ms_apps)
                .setContentIntent(pendingIntent)
                .build();
    }

    @Override public void onDestroy() {
        if (displayManager != null) { try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) { } }
        clearAllBubbles();
        removeLaunchAssist();
        try { unregisterReceiver(configReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
