package com.flipauto;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.view.View;
import android.view.MotionEvent;
import android.animation.ValueAnimator;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class CoverService extends AccessibilityService implements DisplayManager.DisplayListener {
    public static volatile CoverService instance;
    public static volatile String status = "无障碍服务未启用";
    private static final ArrayDeque<String> lifecycleHistory = new ArrayDeque<>();
    final Handler main = new Handler(Looper.getMainLooper());
    final Map<String, Integer> states = new HashMap<>();
    Prefs prefs;
    volatile Context screenContext;
    /**
     * 真正用来挂载窗口的上下文：绑定外屏 display 且带窗口 token。
     * screenContext 可能再派生自 createConfigurationContext（界面缩放），
     * 那样它拿到的 WindowManager 会丢掉 display 绑定，窗口会被加到已熄灭的内屏。
     */
    private volatile Context windowContext;
    /** 连续挂载失败次数，用于退避重试与切换挂载方式。 */
    private int mountFailures;
    /** 最近一次挂载失败的完整原因，供诊断页查看。 */
    private String lastMountError = "";
    /** 窗口上下文路径失败后退回到纯 display 上下文挂载。 */
    private boolean mountPlainContext;
    private final Runnable mountRetry = () -> { if (instance == this) reconcile(true); };
    Display display;
    DockGeometry.Placement placement;
    private WindowManager windows;
    private DockView dock;
    private PanelEntryView panelEntry;
    private DockGeometry.Placement panelEntryPlacement;
    private DockGeometry.Placement dockPlacement;
    private final DockVisibility dockVisibility = new DockVisibility();
    private Boolean keyboardVisible;
    private final Map<Integer, String> windowPackages = new java.util.LinkedHashMap<>();
    private LinearLayout panel;
    private FrameLayout panelHost;
    PanelGlassSession panelGlass;
    private boolean panelMemoryFallback;
    private String lastGlassDiagnostics = "玻璃背景：未打开\n";
    private DockGeometry.Box panelFrame;
    private StatusBarView statusBar;
    private DockGeometry.Box statusBox;
    private android.widget.TextView debugOverlay;
    private final android.graphics.Rect debugRect = new android.graphics.Rect();
    private AppHubView hub;
    private PanelGlassSession taskGlass;
    private RecentTasksView taskSurface;
    private String lastTaskGlassDiagnostics = "多任务玻璃：未打开\n";
    private FrameLayout hubHost;
    private DockGeometry.Box hubFrame;
    private boolean panelDragging;
    private float panelProgress = 1;
    private boolean panelPulling, panelTargetOpen = true, panelPullCancelOpen, panelPullFresh;
    private float panelPullStartProgress, panelPullStartDistance;
    private ValueAnimator panelAnimation;
    private ValueAnimator panelTintAnimation;
    private android.graphics.drawable.ColorDrawable panelBackdrop;
    private TextView feedback;
    private Panels panels;
    private ControlEditorView controlEditor;
    private MediaSessions mediaSessions;
    private Boolean mediaAccessReady;
    private DetailSheet details;
    private ControlDetails detailContent;
    private boolean detailBlurApplied;
    private CoverRotation automaticRotation;
    private Consumer<Boolean> blurListener;
    private Consumer<Boolean> hubBlurListener;
    private WindowManager hubBlurWindows;
    private String panelPage = "", signature = "";
    /**
     * 缺口位置以本次启动第一次读到的为准：按旋转方向各记一次，
     * 之后系统再报告不同的缺口（亮屏瞬间、合盖过程中常见）也不再跟着跳。
     */
    private final java.util.HashMap<Integer, ArrayList<DockGeometry.Box>> cutoutCache = new java.util.HashMap<>();
    // ===== 免 Shizuku 旋转 / 外屏窗口监测 / 开屏恢复 / 自动启动 =====
    private RotationManager noRootRotation;
    private final Runnable windowPoller = this::pollExternalWindows;
    private final java.util.Set<String> activeSmallWindows = new java.util.HashSet<>();
    private String lastRecordedPackage = null;
    private Runnable pendingRestoreRunnable = null;
    private boolean isCharging = false;
    /** 外屏状态机，用于 onDisplayChanged 备选触发。 */
    private final java.util.Map<Integer, Integer> lastCoverDisplayStates = new java.util.HashMap<>();
    /** 全局事件计数器，仅用于日志去重。 */
    private long flipEventSeq = 0;
    /** 外屏最近一次「由灭到亮」的时刻（毫秒）。用于「回到外屏桌面」兜底触发的时间窗。 */
    private volatile long coverOnSince = 0;
    /** 上一次轮询观察到的外屏亮灭状态。 */
    private volatile boolean lastCoverOn = false;
    /** 上一次轮询观察到的锁屏状态，用于检测解锁并触发等待中的开屏恢复。 */
    private volatile boolean lastKeyguardLocked = false;
    /** 有开屏恢复在等待解锁（锁屏时启动会被系统拦截，等解锁后再执行）。 */
    private volatile boolean pendingRestoreAfterUnlock = false;
    /** 外屏上次确认亮起的时间戳，用于切换 app 时短暂灭屏的防抖（避免状态栏被移除再重建）。 */
    private volatile long lastCoverOnMs = 0;
    /** 本次外屏亮起窗口期内是否已经用「桌面兜底」触发过恢复，避免反复重启应用。 */
    private volatile boolean desktopRestoreHandled = false;
    /** 外屏亮起后允许「桌面兜底触发」的时间窗。 */
    private static final long COVER_DESKTOP_WINDOW_MS = 5000;
    /**
     * 会话闸门：一次「外屏由灭到亮」只允许真正执行一次恢复启动。
     * 没有它时，外屏亮着期间 systemui/notilus 的窗口事件每秒数次，
     * 加上默认防抖延时 0ms，会对「最近应用」反复 startActivity ——
     * 表现就是授权无障碍后应用被不停推到前台（若最近应用是系统设置，就是一直弹设置）。
     */
    private volatile boolean restoreDoneThisSession = false;
    /** 两次真实恢复之间的最小间隔（毫秒），兜住连续开合盖。 */
    private static final long RESTORE_MIN_INTERVAL_MS = 3000;
    private volatile long lastRestoreAt = 0;
    /** 自动启动（外屏主屏 / AOD）的冷却时刻，防止事件密集时反复拉起目标应用。 */
    private volatile long lastAutoLaunchAt = 0;
    /** isCoverDisplayOn() 的短缓存，避免每个窗口事件都遍历显示器。 */
    private volatile boolean coverOnCached = false;
    private volatile long coverOnCachedAt = 0;
    private BroadcastReceiver batteryReceiver;
    private BroadcastReceiver systemStateReceiver;
    private static final String FLIP_TRIGGER_CLASS = "android.widget.FrameLayout";
    private record BrightnessRequest(Panels target, int displayId, int value, long deadline, long sequence) { }
    private BrightnessRequest pendingBrightness, activeBrightness;
    private long brightnessSequence;
    private final Runnable drainBrightness = this::drainBrightness;
    private int savedPage;
    private boolean screenshotInProgress;
    private boolean systemControlsBusy;
    private SystemControlGuard systemControlGuard;
    private Toast systemControlsToast;
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private CameraManager cameras;
    private String torchCamera;
    Boolean torchOn;
    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCamera)) { torchOn = enabled; if (panels != null) panels.updateStates(); if (detailContent!=null) detailContent.torchChanged(); }
        }
        @Override public void onTorchModeUnavailable(String cameraId) { if (cameraId.equals(torchCamera)) { torchOn = null; if (panels != null) panels.updateStates(); if (detailContent!=null) detailContent.torchChanged(); } }
    };
    private int navigationInsetPixels;
    private final Runnable updateDisplay = () -> reconcile(false);
    private final Runnable settledDisplay = () -> reconcile(false);
    private final Runnable updateNotifications = () -> {
        boolean ready = CoverNotifications.ready();
        if (mediaAccessReady == null || mediaAccessReady != ready) { mediaAccessReady = ready; if (mediaSessions != null) mediaSessions.refresh(); }
        if (statusBar != null) statusBar.refreshNotifications();
        if (hub != null && hub.isAttachedToWindow() && hub.expanded()) hub.notificationsChanged();
        StatusBarView panelStatus = panel == null ? null : panel.findViewWithTag("panel-status");
        if (panelStatus != null) panelStatus.refreshNotifications();
        if (panelPage.equals("notifications") && !panelDragging && panels != null) panels.refreshNotifications();
    };
    private final Runnable bridgeChanged = () -> {
        ShizukuBridge bridge = CoverApp.bridge(this);
        handleBridgeChanged(bridge.granted(), bridge.connected());
    };
    private void handleBridgeChanged(boolean granted, boolean connected) {
        if (systemControlGuard != null) systemControlGuard.changed();
        boolean ready = granted && connected;
        if (hub != null) { if (!ready) hub.recentFailure("Shizuku 连接中断"); else requestHubTasks(null); }
        if (!ready) {
            closeTaskGlass(); refreshBlurState();
            pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
            stopAutomaticRotation();
            states.clear();
            if (detailContent!=null) detailContent.statesInvalidated();
            if (panels != null) { panels.updateStates(); panels.brightness(new ShizukuBridge.Result(false, "连接中断", "")); }
        } else if (panelPage.equals("controls")) refreshStates(true);
    }
    private final Runnable updatePreferences = () -> reconcile(true);
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (preferences, key) -> {
        if ("system_controls_disabled".equals(key) || "ui_scale_calibrated".equals(key)) return;
        // 缺口记录由服务自己维护：只丢掉内存缓存，不在这里重建窗口，避免「写入记录 → 重建 → 再写入」互相触发。
        if (key != null && key.startsWith("cutout_")) { cutoutCache.clear(); return; }
        // flip_last_app 只是运行时记录最近应用，不需要重建窗口（否则每次切换 app 都会强制重建状态栏和 dock 栏）
        if ("flip_last_app".equals(key)) return;
        if ("panel".equals(key) && panel != null && panelPage.equals("controls")) { if (controlEditor == null) { dismissDetails(); populatePanel("controls"); refreshStates(true); } return; }
        if ("hub_workspace".equals(key) || "hub_workspace_compact".equals(key)) { if (hub != null) hub.workspacePreferencesChanged(); return; }
        if ("hub_pins".equals(key) || "hub_pinned".equals(key)) { if (hub != null) hub.dockPreferencesChanged(); return; }
        if ("app_usage".equals(key)) { if (hub != null) hub.sortChanged(); return; }
        if (key != null && key.startsWith("hub_workspace_")) { if (hub != null) hub.workspaceOptionsChanged(); return; }
        if (key != null && (key.equals("layout_backup") || key.equals("layout_saved_at") || key.equals("layout_undo") || key.equals("panel_undo") || key.equals("hub_sort") || key.equals("hub_workspace") || key.equals("hub_workspace_compact"))) return;
        if ("debug_overlay".equals(key)) { main.post(this::syncDebugOverlay); return; }
        if (key != null && (key.equals("ui_scale") || key.equals("privilege_mode") || key.startsWith("flip_"))) { syncFlipAutoService(); if ("privilege_mode".equals(key)) refreshStates(false, () -> { }); }
        main.removeCallbacks(updatePreferences); main.post(updatePreferences);
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // 总开关关闭时，所有功能失效，不处理任何广播
            if (prefs == null || !prefs.enabled()) return;
            if (systemControlGuard != null && (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) || Intent.ACTION_USER_PRESENT.equals(intent.getAction()))) systemControlGuard.changed();
            // 用户解锁后，如果有等待的开屏恢复（锁屏时被拦截），立即执行
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                FlipLog.d("[开屏恢复] 收到 ACTION_USER_PRESENT，pendingRestoreAfterUnlock=" + pendingRestoreAfterUnlock);
                if (pendingRestoreAfterUnlock) {
                    pendingRestoreAfterUnlock = false;
                    FlipLog.d("[开屏恢复] 用户已解锁，执行等待中的恢复");
                    triggerRestore("unlock");
                }
            }
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                stopAutomaticRotation();
                main.removeCallbacks(updateDisplay); main.removeCallbacks(settledDisplay);
                // 全局 SCREEN_OFF 可能是内屏灭屏（合盖），此时外屏可能还亮着。
                // 只有确认外屏也灭屏时才移除窗口，避免误删外屏状态栏/dock。
                Display ext = prefs != null ? Displays.selected(CoverService.this, prefs) : null;
                if (ext == null || ext.getState() != Display.STATE_ON) {
                    removeWindows(); signature = ""; setStatus("息屏期间已隐藏");
                } else {
                    FlipLog.d("收到全局 SCREEN_OFF 但外屏仍亮起，保留外屏窗口");
                    lastCoverOnMs = System.currentTimeMillis();
                }
            } else if (android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) refreshBlurState();
            else scheduleDisplay();
        }
    };
    @Override protected void onServiceConnected() {
        instance = this; prefs = new Prefs(this); automaticRotation = new CoverRotation(this); prefs.data.registerOnSharedPreferenceChangeListener(preferenceListener);
        cutoutCache.clear();
        String savedLast = prefs.flipLastApp(); if (!savedLast.isEmpty()) lastRecordedPackage = savedLast;
        // 免 Shizuku 旋转：必须用外屏 display context，0 尺寸 orientation overlay 才会作用于外屏
        DisplayManager rotDm = getSystemService(DisplayManager.class);
        Display rotDisplay = rotDm == null ? null : rotDm.getDisplay(1);
        if (rotDisplay == null && rotDm != null) for (Display d : rotDm.getDisplays()) if (d.getDisplayId() != Display.DEFAULT_DISPLAY) { rotDisplay = d; break; }
        Context rotContext = rotDisplay == null ? this : createDisplayContext(rotDisplay);
        noRootRotation = new RotationManager(rotContext); noRootRotation.init();
        if (systemControlGuard != null) systemControlGuard.close();
        systemControlGuard = new SystemControlGuard(prefs, main, CoverApp.bridge(this), result -> {
            states.put("system_controls", result.ok ? 0 : -1);
            if (panels != null) panels.updateStates();
            if (detailContent!=null) detailContent.stateChanged("system_controls");
            if (!result.ok) android.util.Log.w("SystemControlGuard", result.message);
        });
        systemControlGuard.changed();
        getSystemService(DisplayManager.class).registerDisplayListener(this, main);
        IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT); filter.addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(screenReceiver, filter);
        try {
            IntentFilter battery = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            batteryReceiver = new BroadcastReceiver() { @Override public void onReceive(Context context, Intent intent) { int plugged = intent.getIntExtra("plugged", 0); isCharging = plugged != 0; } };
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, battery, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(batteryReceiver, battery);
        } catch (Exception ignored) { }
        // 悬浮球被手动长按关闭：从 activeSmallWindows 移除该包名，不再自动弹出
        try {
            IntentFilter bubbleFilter = new IntentFilter(FlipAutoService.ACTION_FORGET_BUBBLE);
            BroadcastReceiver bubbleForgetReceiver = new BroadcastReceiver() { @Override public void onReceive(Context context, Intent intent) { String pkg = intent.getStringExtra("package"); if (pkg != null) activeSmallWindows.remove(pkg); } };
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bubbleForgetReceiver, bubbleFilter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(bubbleForgetReceiver, bubbleFilter);
        } catch (Exception ignored) { }
        // 系统状态变化广播：控制中心打开时实时刷新状态（与状态栏侦测速度同步）
        try {
            IntentFilter stateFilter = new IntentFilter();
            stateFilter.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
            stateFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            stateFilter.addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION);
            stateFilter.addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            stateFilter.addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            stateFilter.addAction(android.location.LocationManager.MODE_CHANGED_ACTION);
            stateFilter.addAction(android.nfc.NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
            stateFilter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
            systemStateReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    // 控制中心正在显示时，立即刷新状态（不读亮度，避免频繁请求）
                    if (panel != null && panelPage != null && panelPage.equals("controls")) {
                        refreshStates(false);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(systemStateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(systemStateReceiver, stateFilter);
        } catch (Exception ignored) { }
        cameras = getSystemService(CameraManager.class);
        try {
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics info = cameras.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) && Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(info.get(CameraCharacteristics.LENS_FACING))) { torchCamera = id; break; }
            }
            cameras.registerTorchCallback(torchCallback, main);
        } catch (Exception ignored) { }
        CoverApp.bridge(this).addObserver(bridgeChanged); CoverApp.bridge(this).connect(); reconcile(true); scheduleDisplay();
        main.removeCallbacks(windowPoller); main.postDelayed(windowPoller, 1000);
        syncFlipAutoService();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // 总开关关闭时，所有功能失效，不处理任何无障碍事件
        if (prefs == null || !prefs.enabled()) return;
        // Read window IDs/types and event package only; never request a node tree or its text.
        observeForeground(event);
        if (getSystemService(KeyguardManager.class).isKeyguardLocked() && dock != null) reconcile(false);
        scheduleDisplay();
        flipAutoOnEvent(event);
    }
    private void observeForeground(AccessibilityEvent event) {
        if (prefs == null) return;
        Display target = Displays.selected(this, prefs);
        if (target == null || target.getDisplayId() == Display.DEFAULT_DISPLAY) return;
        try {
            android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            try {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> items = all.get(target.getDisplayId());
                if (items == null) return;
                String active = null, focused = null; boolean inputMethod = false;
                for (android.view.accessibility.AccessibilityWindowInfo window : items) {
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) inputMethod = true;
                    if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                    if (event != null && window.getId() == event.getWindowId() && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) windowPackages.put(window.getId(), event.getPackageName().toString());
                    String name = windowPackages.get(window.getId());
                    if (name != null && window.isActive() && active == null) active = name;
                    if (name != null && window.isFocused() && focused == null) focused = name;
                }
                // Keep a small cache so a temporarily covered app can regain focus without
                // a new state event. IDs and package names are never persisted or exported.
                while (windowPackages.size() > 32) windowPackages.remove(windowPackages.keySet().iterator().next());
                dockVisibility.foreground(active != null ? active : focused);
                keyboardVisible = inputMethod;
                if (display != null && display.getDisplayId() == target.getDisplayId()) { syncDockVisibility(); syncHubEntry(); syncPanelTouchable(); syncDebugOverlay(); }
            } finally { for (int i = 0; i < all.size(); i++) for (android.view.accessibility.AccessibilityWindowInfo window : all.valueAt(i)) window.recycle(); }
        } catch (RuntimeException ignored) { /* Keep the last state; a long press always remains available. */ }
    }
    @Override public void onInterrupt() {
        // This interrupts spoken/haptic feedback, not the service or its persistent windows.
    }

    // ==================== 外屏监测 / 开屏恢复 / 自动启动 ====================
    /** 依据偏好启动或停止外屏增强前台服务（悬浮气泡 / 开屏恢复 / 自动启动均依赖它执行启动）。 */
    private void syncFlipAutoService() {
        if (prefs == null) return;
        // 总开关关闭时，停止 FlipAutoService（开屏恢复/自动启动/悬浮气泡全部失效）
        boolean needed = prefs.enabled() && (prefs.flipBubbles() || prefs.flipRestore() || prefs.flipAutoLaunch());
        Intent service = new Intent(this, FlipAutoService.class);
        try {
            if (needed) { if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service); }
            else stopService(service);
        } catch (Exception ignored) { }
    }

    // ==================== 屏幕下方多任务按键（dock recents，按特权模式分流） ====================
    /** 设置页修改特权模式后刷新控制中心状态。 */
    public void statesChanged() {
        if (prefs != null && panels != null) refreshStates(true, () -> { });
    }
    /** 设置页切换免 Shizuku 旋转模式后立即生效。 */
    public void setNoRootRotationMode(int mode) { if (noRootRotation != null) noRootRotation.setMode(mode); }

    private void flipAutoOnEvent(AccessibilityEvent event) {
        try {
            int type = event.getEventType();
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && prefs.flipBubbles()) pollExternalWindows();
            CharSequence pkgSeq = event.getPackageName(), clsSeq = event.getClassName();
            String packageName = pkgSeq == null ? "" : pkgSeq.toString();
            String className = clsSeq == null ? "" : clsSeq.toString();
            // ===== 全量诊断日志：记录所有到达的窗口事件（含 displayId），用于定位外屏容器包名 =====
            // 只记录 WINDOW_STATE_CHANGED 以避免 TYPE_WINDOWS_CHANGED 刷屏；其余事件跳过日志。
            int eventDisplay = event.getDisplayId();
            // 不能用 event.getDisplayId() 判定「是否来自外屏」。
            // 多数 One UI 版本的外屏窗口事件仍以 displayId=0（DEFAULT_DISPLAY）上报，
            // 以它作为必要条件会同时卡死「记录最近应用」和「恢复触发」，
            // 表现就是「完全没有复原动作」——这正是长期未解决的现象。
            // 改用可靠的 Display.getState()：合盖时外屏 ON 且内屏灭，展开时外屏 OFF。
            boolean fromCoverDisplay = isCoverDisplayOn();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                long seq = ++flipEventSeq;
                FlipLog.d("[FLIP] EVT#" + seq + " d=" + eventDisplay
                        + " cov=" + (fromCoverDisplay ? "ON" : "OFF")
                        + " pkg=" + (packageName.isEmpty() ? "<null>" : packageName)
                        + " cls=" + (className.isEmpty() ? "<null>" : className));
            }
            if (packageName.isEmpty() || className.isEmpty()) return;

            // ---- 记录最近使用的外屏应用 ----
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            boolean kgLocked = keyguard != null && (keyguard.isKeyguardLocked() || keyguard.isDeviceLocked());
            boolean shouldRecord = !kgLocked;
            if (prefs.flipRestoreCharging() && isCharging) shouldRecord = false;
            // 冗余保护：即使排除列表被用户清空，系统关键包也绝不记录/恢复。
            boolean systemPackage = isSystemCoverPackage(packageName);
            if (fromCoverDisplay && shouldRecord && !systemPackage
                    && !prefs.flipExcluded().contains(packageName) && isLaunchableApp(packageName)) {
                if (!packageName.equals(lastRecordedPackage)) {
                    lastRecordedPackage = packageName; prefs.flipLastApp(packageName);
                    FlipLog.d("记录最近应用: " + packageName);
                }
            }

            // ---- 自动启动 ----
            boolean shouldLaunch = false;
            if (prefs.flipDetectSubHome() && packageName.equals("com.android.systemui") && className.equals("com.android.systemui.subscreen.SubHomeActivity")) shouldLaunch = true;
            if (prefs.flipDetectAod() && packageName.equals("com.samsung.android.app.aodservice")) shouldLaunch = true;
            String autoTarget = prefs.flipAutoLaunch() ? prefs.flipTargetApp() : null;
            // 自动启动条件：检测到目标 Activity + 设置了目标应用 + 冷却时间已过 + 没有开屏恢复在等待解锁
            // 锁屏时有开屏恢复在等待解锁时，禁止自动启动（避免两者冲突，且锁屏时启动会被系统拦截）
            // 额外检查：没有待执行的开屏恢复任务（pendingRestoreRunnable），避免外屏刚亮起时自动启动抢跑
            boolean doAutoLaunch = shouldLaunch && autoTarget != null && !autoTarget.isEmpty()
                    && !pendingRestoreAfterUnlock
                    && pendingRestoreRunnable == null
                    && System.currentTimeMillis() >= FlipAutoService.autoLaunchDisabledUntil
                    && System.currentTimeMillis() - lastAutoLaunchAt >= RESTORE_MIN_INTERVAL_MS;

            // ---- 开屏恢复触发：外屏容器出现时恢复最近应用 ----
            // 自动启动优先：如果设置了目标替换应用，容器出现时只启动目标应用，不执行开屏恢复（避免两者冲突）。
            boolean coverContainer = packageName.equals("com.samsung.systemui.notilus")
                    || packageName.equals("com.samsung.systemui")
                    || packageName.startsWith("com.samsung.systemui.")
                    || packageName.equals("com.android.systemui")
                    || packageName.equals("com.samsung.android.app.cocktailbarservice");
            if (coverContainer && fromCoverDisplay && prefs.flipRestore() && !doAutoLaunch) {
                FlipLog.d("容器触发, pkg=" + packageName + ", cls=" + className + ", 最近应用=" + lastRecordedPackage);
                triggerRestore("container:" + packageName);
            }

            // 自动启动优先：检测到 SubHomeActivity 时，如果设置了目标替换应用，只启动目标应用，不执行开屏恢复
            if (doAutoLaunch) {
                lastAutoLaunchAt = System.currentTimeMillis();
                // 取消待执行的开屏恢复，避免覆盖自动启动的目标应用
                if (pendingRestoreRunnable != null) { main.removeCallbacks(pendingRestoreRunnable); pendingRestoreRunnable = null; FlipLog.d("[自动启动] 取消待执行的开屏恢复"); }
                // 标记本次会话已恢复，防止开屏恢复重复执行
                restoreDoneThisSession = true;
                FlipLog.d("[自动启动] 检测到 " + className + "，启动目标替换应用: " + autoTarget);
                // 外屏刚亮起时（<2秒）系统可能拦截 Activity 启动，延迟 500ms 再启动提高成功率
                long sinceCoverOn = System.currentTimeMillis() - coverOnSince;
                if (sinceCoverOn < 2000) {
                    FlipLog.d("[自动启动] 外屏刚亮起(" + sinceCoverOn + "ms)，延迟 500ms 启动避免被系统拦截");
                    main.postDelayed(() -> launchPackageZip(autoTarget), 500);
                } else {
                    launchPackageZip(autoTarget);
                }
                // 注意：自动启动不更新 lastRecordedPackage——最近应用记录只反映用户实际使用的应用，
                // 自动启动目标只是"滑动到桌面时替换启动"，不应覆盖开屏恢复的目标。
            } else if (shouldLaunch && prefs.flipRestore()) {
                // 没有设置自动启动目标时，检测到 SubHomeActivity 才执行开屏恢复（恢复最近应用）。
                // 注意：不调用 beginCoverSession——beginCoverSession 只在外屏由灭到亮时调用（poll/display-on），
                // 这里重复调用会重置 restoreDoneThisSession 导致开屏恢复重复执行。
                triggerRestore("detect:" + packageName);
            }
        } catch (RuntimeException e) { FlipLog.d("flipAutoOnEvent 异常: " + e.getMessage()); }
    }

    /** 外屏由灭到亮：开启一次新的恢复会话，重置所有「已触发」标记。 */
    private void beginCoverSession(String reason) {
        // 防抖：1 秒内多个触发源（poll/display-on/detect）重复调用时，只重置一次，
        // 避免 restoreDoneThisSession 被频繁重置导致开屏恢复重复执行。
        long now = System.currentTimeMillis();
        if (now - coverOnSince < 1000) { FlipLog.d("[开屏恢复] beginCoverSession 防抖忽略(" + reason + ")，距上次 " + (now - coverOnSince) + "ms"); return; }
        coverOnSince = now;
        desktopRestoreHandled = false;
        restoreDoneThisSession = false;
        pendingRestoreAfterUnlock = false;
        // 新的一次开盖：允许重新尝试挂载，否则上一次失败后本轮再也不会重试。
        mountFailures = 0;
        FlipLog.d("[开屏恢复] 外屏亮起(" + reason + ")，开启新恢复会话，已记录最近应用=" + lastRecordedPackage + "，恢复开关=" + (prefs != null && prefs.flipRestore()));
    }

    /**
     * 集中处理开屏恢复触发：容器事件（flipAutoOnEvent）与外屏亮起（onDisplayChanged）均可调用。
     * 连续触发会被合并（防抖），并且一次外屏亮起最多只真正启动一次。
     */
    private void triggerRestore(String source) {
        if (prefs == null || !prefs.flipRestore()) { FlipLog.d("[开屏恢复] 触发(" + source + ") → 取消：开关未开启"); return; }
        FlipLog.d("[开屏恢复] 触发(" + source + ")，延迟 " + prefs.flipRestoreDelay() + "ms 后执行，当前最近应用=" + lastRecordedPackage);
        if (pendingRestoreRunnable != null) { main.removeCallbacks(pendingRestoreRunnable); FlipLog.d("[开屏恢复] 合并上一次待执行的恢复"); }
        pendingRestoreRunnable = () -> {
            pendingRestoreRunnable = null;
            if (prefs == null || !prefs.flipRestore()) { FlipLog.d("[开屏恢复] 执行取消：开关未开启"); return; }
            if (restoreDoneThisSession) { FlipLog.d("[开屏恢复] 执行取消：本次外屏亮起已恢复过"); return; }
            long now = System.currentTimeMillis();
            if (now - lastRestoreAt < RESTORE_MIN_INTERVAL_MS) { FlipLog.d("[开屏恢复] 执行取消：冷却中(" + (now - lastRestoreAt) + "ms < " + RESTORE_MIN_INTERVAL_MS + "ms)"); return; }
            if (prefs.flipRestoreCharging() && isCharging) { FlipLog.d("[开屏恢复] 执行取消：充电中且开启了充电不恢复"); return; }
            KeyguardManager km = getSystemService(KeyguardManager.class);
            boolean keyguardLocked = km != null && (km.isKeyguardLocked() || km.isDeviceLocked());
            FlipLog.d("[开屏恢复] 执行检查：keyguard=" + (keyguardLocked ? "锁定" : "未锁定") + "，最近应用=" + lastRecordedPackage);
            if (keyguardLocked) {
                // 锁屏时启动 Activity 会被系统拦截，等用户解锁后再执行（ACTION_USER_PRESENT 触发）。
                pendingRestoreAfterUnlock = true;
                FlipLog.d("[开屏恢复] 锁屏中，等待解锁后恢复: " + lastRecordedPackage);
                return;
            }
            if (lastRecordedPackage == null) { FlipLog.d("[开屏恢复] 执行取消：没有最近应用"); return; }
            if (prefs.flipExcluded().contains(lastRecordedPackage)) { FlipLog.d("[开屏恢复] 执行取消：应用在排除列表 " + lastRecordedPackage); return; }
            if (isSystemCoverPackage(lastRecordedPackage)) { FlipLog.d("[开屏恢复] 执行取消：系统界面不可作为恢复目标 " + lastRecordedPackage); return; }
            if (!isLaunchableApp(lastRecordedPackage)) { FlipLog.d("[开屏恢复] 执行取消：应用不可启动 " + lastRecordedPackage); return; }
            restoreDoneThisSession = true;
            lastRestoreAt = System.currentTimeMillis();
            FlipLog.d("[开屏恢复] ✓ 开始在外屏恢复启动: " + lastRecordedPackage);
            launchPackageZip(lastRecordedPackage);
        };
        main.postDelayed(pendingRestoreRunnable, prefs.flipRestoreDelay());
    }

    /**
     * 每 1 秒轮询外屏窗口：小窗应用显示悬浮气泡；同时用窗口树记录外屏最近应用。
     * 记录最近应用不再依赖 event.getDisplayId()（该值在三星外屏上常为 0），
     * 而是直接读取按 displayId 索引的窗口树，这与气泡功能用的是同一条已验证可用的通道。
     */
    private void pollExternalWindows() {
        main.removeCallbacks(windowPoller);
        try {
            if (instance == this && prefs != null && (prefs.flipBubbles() || prefs.flipRestore()) && Build.VERSION.SDK_INT >= 30) {
                // dock 栏同款：通过 Displays.selected() 获取用户选择的外屏，而非遍历所有 display
                Display ext = Displays.selected(this, prefs);
                if (ext != null) {
                    // 外屏亮灭状态机：合盖（外屏由灭到亮）是开屏恢复的时间原点。
                    // 每秒轮询检测，比 onDisplayChanged 更可靠（后者只在显示配置变化时触发，不响应亮灭）。
                    boolean coverOn = ext.getState() == Display.STATE_ON;
                    if (coverOn && !lastCoverOn) {
                        beginCoverSession("poll display " + ext.getDisplayId());
                        // 第一次开屏（外屏由灭到亮）→ 直接执行开屏恢复，恢复最近应用。
                        // 之后用户滑动到 SubHomeActivity → 执行自动启动（目标替换应用），两者不冲突。
                        if (prefs.flipRestore()) triggerRestore("cover-on:" + ext.getDisplayId());
                        // 外屏重新亮起后立即触发 reconcile，确保状态栏和 dock 栏及时挂载（避免 signature 为空导致延迟）
                        scheduleDisplay();
                    }
                    lastCoverOn = coverOn;
                    // 轮询检测解锁：ACTION_USER_PRESENT 广播可能丢失，这里用 KeyguardManager 兜底。
                    // 如果之前是锁定状态，现在解锁了，并且有等待中的开屏恢复，立即执行。
                    if (coverOn) {
                        try {
                            KeyguardManager km = getSystemService(KeyguardManager.class);
                            boolean nowLocked = km != null && (km.isKeyguardLocked() || km.isDeviceLocked());
                            if (lastKeyguardLocked && !nowLocked && pendingRestoreAfterUnlock) {
                                pendingRestoreAfterUnlock = false;
                                FlipLog.d("[开屏恢复] 轮询检测到解锁，执行等待中的恢复");
                                triggerRestore("poll-unlock");
                            }
                            lastKeyguardLocked = nowLocked;
                        } catch (RuntimeException ignored) { }
                    }
                    android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
                        try {
                            java.util.List<android.view.accessibility.AccessibilityWindowInfo> extWindows = all.get(ext.getDisplayId());
                            android.graphics.Point size = Displays.size(ext);
                            int wThreshold = (int) (size.x * 0.95f), hThreshold = (int) (size.y * 0.95f);
                            java.util.Set<String> visibleAll = new java.util.HashSet<>(), visibleSmall = new java.util.HashSet<>(), visibleFull = new java.util.HashSet<>();
                            String topPackage = null;
                            if (extWindows != null) for (android.view.accessibility.AccessibilityWindowInfo window : extWindows) {
                                if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                                android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                                if (root == null) continue;
                                CharSequence pkgSeq = root.getPackageName(); root.recycle();
                                if (pkgSeq == null) continue;
                                String pkg = pkgSeq.toString();
                                if (prefs.flipBubbleExcluded().contains(pkg)) continue;
                                visibleAll.add(pkg);
                                if (topPackage == null && (window.isActive() || window.isFocused())) topPackage = pkg;
                            android.graphics.Rect bounds = new android.graphics.Rect();
                                window.getBoundsInScreen(bounds);
                                // FlipAuto 判定：宽高都 >0 且 <=95% 屏幕 → 小窗；否则 → 全屏
                                if (bounds.width() > 0 && bounds.height() > 0
                                        && bounds.width() <= wThreshold && bounds.height() <= hThreshold) {
                                    visibleSmall.add(pkg);
                                } else {
                                    visibleFull.add(pkg);
                                }
                            }
                            visibleSmall.removeAll(visibleFull);
                            // FlipAuto 悬浮气泡逻辑：
                            // - 应用不在外屏可见（被切走/在内屏）→ 显示气泡（方便快速切回）
                            // - 应用全屏显示 → 隐藏气泡并从活跃列表移除
                            // - 应用小窗显示 → 隐藏气泡（保留在活跃列表，切走后还需显示）
                            for (String pkg : new java.util.HashSet<>(activeSmallWindows)) {
                                if (!visibleAll.contains(pkg)) {
                                    sendFlipBubble(pkg, true);
                                } else if (visibleFull.contains(pkg)) {
                                    activeSmallWindows.remove(pkg);
                                    sendFlipBubble(pkg, false);
                                } else if (visibleSmall.contains(pkg)) {
                                    sendFlipBubble(pkg, false);
                                }
                            }
                            // 新的小窗应用加入活跃列表（不立即显示气泡，因为当前正在小窗显示）
                            for (String pkg : visibleSmall) if (!activeSmallWindows.contains(pkg)) { activeSmallWindows.add(pkg); }
                            // ---- 开屏恢复数据源：记录外屏当前前台应用 ----
                            if (prefs.flipRestore() && coverOn) {
                                if (topPackage == null && !visibleFull.isEmpty()) topPackage = visibleFull.iterator().next();
                                recordCoverApp(topPackage);
                                // ---- 兜底触发：外屏亮起后短时间内回到桌面（外屏没有用户应用）即恢复 ----
                                boolean hasUserApp = false;
                                for (String p : visibleAll) if (!isSystemCoverPackage(p)) { hasUserApp = true; break; }
                                if (hasUserApp) desktopRestoreHandled = false;
                                else if (!desktopRestoreHandled && coverOnSince > 0
                                        && System.currentTimeMillis() - coverOnSince < COVER_DESKTOP_WINDOW_MS) {
                                    desktopRestoreHandled = true;
                                    FlipLog.d("[开屏恢复] 桌面兜底触发, 外屏窗口=" + (visibleAll.isEmpty() ? "空" : visibleAll) + ", 距亮起" + (System.currentTimeMillis() - coverOnSince) + "ms");
                                    triggerRestore("cover-desktop:" + ext.getDisplayId());
                                }
                            }
                        } finally { for (int i = 0; i < all.size(); i++) for (android.view.accessibility.AccessibilityWindowInfo window : all.valueAt(i)) window.recycle(); }
                    }
                }
            } catch (RuntimeException ignored) { }
        main.postDelayed(windowPoller, 1000);
    }

    /** 记录外屏最近使用的应用：只在外屏亮起、未锁屏、未被排除且可启动时写入。 */
    private void recordCoverApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) { FlipLog.d("[最后应用更新] 跳过：包名为空"); return; }
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard != null && (keyguard.isKeyguardLocked() || keyguard.isDeviceLocked())) { FlipLog.d("[最后应用更新] 跳过 " + pkg + "：锁屏中"); return; }
        if (prefs.flipRestoreCharging() && isCharging) { FlipLog.d("[最后应用更新] 跳过 " + pkg + "：充电中且开启了充电不恢复"); return; }
        if (isSystemCoverPackage(pkg)) { FlipLog.d("[最后应用更新] 跳过 " + pkg + "：系统界面包"); return; }
        if (prefs.flipExcluded().contains(pkg)) { FlipLog.d("[最后应用更新] 跳过 " + pkg + "：在排除列表"); return; }
        if (!isLaunchableApp(pkg)) { FlipLog.d("[最后应用更新] 跳过 " + pkg + "：不可启动"); return; }
        if (!pkg.equals(lastRecordedPackage)) {
            String old = lastRecordedPackage;
            lastRecordedPackage = pkg; prefs.flipLastApp(pkg);
            FlipLog.d("[最后应用更新] 已更新: " + pkg + "（之前: " + (old == null ? "空" : old) + "）");
        }
    }

    /** 外屏宿主 / 系统界面包：绝不作为「最近应用」记录或恢复目标。 */
    private boolean isSystemCoverPackage(String pkg) {
        return pkg == null || pkg.isEmpty()
                || pkg.equals("com.android.systemui")
                || pkg.startsWith("com.samsung.systemui")
                || pkg.equals("com.samsung.android.app.cocktailbarservice")
                || pkg.equals("com.samsung.android.app.aodservice")
                || pkg.equals("com.sec.android.app.launcher")
                || pkg.equals("android")
                // 系统设置绝不能当作「最近应用」：用户往往正是在设置页授权无障碍，
                // 一旦被记录就会被开屏恢复反复推到前台（表现为「一直弹系统设置」）。
                || pkg.equals("com.android.settings")
                || pkg.equals("com.android.phone")
                || pkg.equals(getPackageName());
    }

    /** 外屏当前是否亮起（合盖状态）。用 Display.getState()，比 event.getDisplayId() 可靠；结果做 200ms 缓存。 */
    private boolean isCoverDisplayOn() {
        long now = System.currentTimeMillis();
        if (now - coverOnCachedAt < 200) return coverOnCached;
        boolean on = false;
        try {
            DisplayManager dm = getSystemService(DisplayManager.class);
            if (dm != null) for (Display d : dm.getDisplays()) {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                if (d.getState() == Display.STATE_ON) { on = true; break; }
            }
        } catch (RuntimeException ignored) { }
        coverOnCached = on; coverOnCachedAt = now;
        return on;
    }

    /** 启动后回看外屏是否真的出现了目标应用，用于区分「启动成功」与「被系统静默拦截」。 */
    private void verifyLaunchResult(String pkg, int displayId) {
        main.postDelayed(() -> {
            try {
                android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
                boolean found = false;
                try {
                    java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = all.get(displayId);
                    if (windows != null) for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                        android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                        if (root == null) continue;
                        CharSequence name = root.getPackageName(); root.recycle();
                        if (name != null && pkg.equals(name.toString())) { found = true; break; }
                    }
                } finally { for (int i = 0; i < all.size(); i++) for (android.view.accessibility.AccessibilityWindowInfo window : all.valueAt(i)) window.recycle(); }
                FlipLog.d("[开屏恢复] 启动回看 " + pkg + " → " + (found ? "已在外屏出现（成功）" : "外屏未见（疑似被系统拦截）"));
            } catch (RuntimeException ignored) { }
        }, 1500);
    }

    /** 通知前台服务挂一个 1px 可见窗口，让本 uid 具备可见窗口以豁免后台启动限制。 */
    private void prepareLaunchWindow() {
        try {
            Intent intent = new Intent(FlipAutoService.ACTION_PREPARE_LAUNCH);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception ignored) { }
    }

    private void sendFlipBubble(String pkg, boolean show) {
        try {
            Intent intent = new Intent(FlipAutoService.ACTION_SHOW_BUBBLE);
            intent.putExtra("package", pkg); intent.putExtra("show", show); intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception ignored) { }
    }

    private boolean isLaunchableApp(String packageName) {
        try { return getPackageManager().getLaunchIntentForPackage(packageName) != null; }
        catch (Exception e) { return false; }
    }

    // ==================== zip（FlipAuto）免 Shizuku 启动方案 ====================
    /** 引导用户授予 SYSTEM_ALERT_WINDOW 悬浮窗权限（免 Shizuku 旋转固定方向需要）。 */
    private void requestOverlayPermission() {
        try {
            Intent oi = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()));
            oi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(oi);
        } catch (RuntimeException ignored) {
            NoRootOps.openSettings(this, Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        }
    }
    /** 在外屏启动指定包：getLaunchIntentForPackage + setLaunchDisplayId（无需 Shizuku）。 */
    /** 在外屏启动指定包（AccessibilityService 天然豁免 BAL 后台启动限制，直接在此执行）。 */
    private void launchPackageZip(String pkg) {
        if (pkg == null || pkg.isEmpty()) { FlipLog.d("启动取消: 包名为空"); return; }
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) { FlipLog.d("启动失败: 无 launch intent " + pkg); message("无法启动该应用：" + pkg); return; }
            // 复用已有任务（从后台拉回），没有才新建：
            // FLAG_ACTIVITY_NEW_TASK + RESET_TASK_IF_NEEDED 会让系统匹配该包已有的 task，
            // 命中则把它带到前台（并在需要时迁移到目标 display），不命中才新建。
            // 绝不能加 FLAG_ACTIVITY_MULTIPLE_TASK（其语义是「已有 task 也照样新建」→ 冷启动/多开）
            // 也不要加 CLEAR_TOP / CLEAR_TASK（会清空已有任务栈，等于重新打开）。
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            // 动态确定外屏 display id：优先当前外屏，其次 display 1，再次非默认 display
            int d = -1;
            if (display != null) d = display.getDisplayId();
            if (d <= 0) {
                DisplayManager dm = getSystemService(DisplayManager.class);
                if (dm != null) {
                    Display d1 = dm.getDisplay(1);
                    if (d1 != null) d = 1;
                    else for (Display dd : dm.getDisplays()) if (dd.getDisplayId() != Display.DEFAULT_DISPLAY) { d = dd.getDisplayId(); break; }
                }
            }
            if (d <= 0) d = 1; // Z Flip5 外屏固定为 1
            FlipLog.d("[开屏恢复] 启动准备: pkg=" + pkg + ", targetDisplay=" + d + ", 已挂载1px豁免窗");
            prepareLaunchWindow();
            final int launchDisplay = d;
            // 已经在外屏前台就不再启动：重复 startActivity 会让部分应用重建界面，看着又像「重新打开」。
            if (packageVisibleOnDisplay(pkg, launchDisplay)) { FlipLog.d("[开屏恢复] 跳过启动：" + pkg + " 已在外屏前台"); return; }
            main.postDelayed(() -> {
                try {
                    startActivity(launch, ActivityOptions.makeBasic().setLaunchDisplayId(launchDisplay).toBundle());
                    if (prefs != null) prefs.noteAppUsed(pkg); // 开屏恢复/自动启动也算「用过」，应用列表同步置顶
                    FlipLog.d("[开屏恢复] ✓ startActivity 已提交: " + pkg + " → display " + launchDisplay);
                } catch (RuntimeException outer) {
                    FlipLog.d("[开屏恢复] 外屏启动失败(" + outer.getClass().getSimpleName() + "): " + outer.getMessage() + "，回退默认 display");
                    try { startActivity(launch); } catch (RuntimeException fallback) {
                        FlipLog.d("[开屏恢复] 默认 display 回退也失败: " + fallback.getMessage());
                    }
                }
                verifyLaunchResult(pkg, launchDisplay);
            }, 200);
        } catch (RuntimeException e) { FlipLog.d("[开屏恢复] 启动异常(" + e.getClass().getSimpleName() + "): " + e.getMessage()); }
    }

    /**
     * 缩放校准兜底：升级用户没走过引导页时，在首次读到外屏真实密度后补一次。
     * 只做一次 —— 之后用户手动调的 ui_scale 不会被覆盖。
     */
    private void calibrateUiScale(int dpi) {
        if (prefs == null || dpi <= 0 || dpi >= 1000) return;
        if (prefs.data.contains("ui_scale_calibrated")) return;
        prefs.uiScale(Math.round(LauncherSetupActivity.BASE_DENSITY_DPI * 100f / dpi));
        prefs.data.edit().putBoolean("ui_scale_calibrated", true).apply();
        FlipLog.d("按外屏密度校准界面缩放: " + dpi + " dpi → " + prefs.uiScale() + "%");
    }

    /** 指定 display 上是否已经有该包的应用窗口（即它已经在前台）。 */
    private boolean packageVisibleOnDisplay(String pkg, int displayId) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            try {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = all.get(displayId);
                if (windows == null) return false;
                for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                    if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    CharSequence name = root.getPackageName(); root.recycle();
                    if (name != null && pkg.equals(name.toString())) return true;
                }
            } finally { for (int i = 0; i < all.size(); i++) for (android.view.accessibility.AccessibilityWindowInfo w : all.valueAt(i)) w.recycle(); }
        } catch (RuntimeException ignored) { }
        return false;
    }

    /** 打开外屏最近任务（还原 zip 方案，双保险）：先 performGlobalAction 触发系统级任务，再延迟 150ms 显式 Intent 强制在外屏打开。 */
    private void openRecentsZip() {
        dismissHub(); closePanel();
        // 抑制自动启动，避免打开 Recents 后被自动启动逻辑干扰
        FlipAutoService.autoLaunchDisabledUntil = System.currentTimeMillis() + 1500;
        // 步骤 1：系统级最近任务（zip 通过 OPEN_RECENTS 广播让无障碍服务执行 performGlobalAction）
        performGlobalAction(GLOBAL_ACTION_RECENTS);
        // 步骤 2：150ms 后显式 Intent 启动 Samsung RecentsActivity 到外屏，兜底并强制刷新
        main.postDelayed(() -> {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.sec.android.app.launcher", "com.android.quickstep.RecentsActivity");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                intent.putExtra("android.intent.extra.CLEAR_TASK", true);
                intent.putExtra("android.intent.extra.FORCE_NEW_TASK", true);
                intent.putExtra("force_fresh", true);
                int d = display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
                try { startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(d).toBundle()); }
                catch (RuntimeException outer) { startActivity(intent); }
            } catch (RuntimeException ignored) { }
        }, 150);
    }
    @Override public void onDisplayAdded(int id) { if (prefs == null || !prefs.enabled()) return; scheduleDisplay(); if (systemControlGuard != null) systemControlGuard.changed(); }
    @Override public void onDisplayRemoved(int id) { if (prefs == null || !prefs.enabled()) return; scheduleDisplay(); if (systemControlGuard != null) systemControlGuard.changed(); }
    @Override public void onDisplayChanged(int id) {
        if (prefs == null || !prefs.enabled()) return;
        scheduleDisplay();
        if (systemControlGuard != null && (id == Display.DEFAULT_DISPLAY || display != null && id == display.getDisplayId())) systemControlGuard.changed();
        // 备选触发：外屏状态从非 ON 变为 ON（合盖再展开 / 外屏重新亮起）。
        // 只在状态真正发生变化时触发一次，避免启动初期误触发。
        if (prefs != null && prefs.flipRestore() && id > Display.DEFAULT_DISPLAY) {
            DisplayManager dm = getSystemService(DisplayManager.class);
            Display d = dm == null ? null : dm.getDisplay(id);
            if (d != null) {
                int current = d.getState();
                Integer previous = lastCoverDisplayStates.put(id, current);
                if (current == Display.STATE_ON && previous != null && previous != Display.STATE_ON) {
                    FlipLog.d("外屏状态变化 " + previous + " → " + current + " (display " + id + ")");
                    lastCoverOn = true; // 同步 poll 轮询的状态标记，避免 poll 再次触发 beginCoverSession
                    beginCoverSession("display-on:" + id);
                    triggerRestore("display-on:" + id);
                }
            }
        }
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); scheduleDisplay(); }
    private void scheduleDisplay() {
        if (instance != this) return;
        // Coalesce a burst without postponing the first check indefinitely. A single later
        // check handles display/keyguard state that settles after the original notification.
        if (!main.hasCallbacks(updateDisplay)) main.postDelayed(updateDisplay, 100);
        main.removeCallbacks(settledDisplay); main.postDelayed(settledDisplay, 1000);
    }
    /** Debug 悬浮窗：居中显示当前前台应用包名，用于调试外屏应用识别。 */
    private void syncDebugOverlay() {
        if (instance != this || prefs == null) return;
        boolean show = prefs.debugOverlay() && prefs.enabled() && windows != null;
        if (show && debugOverlay == null) {
            Context ctx = screenContext != null ? screenContext : this;
            debugOverlay = new android.widget.TextView(ctx);
            debugOverlay.setTextColor(0xFFFFFFFF);
            debugOverlay.setBackgroundColor(0xCC000000);
            debugOverlay.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8));
            debugOverlay.setTextSize(11);
            debugOverlay.setGravity(android.view.Gravity.CENTER);
            debugOverlay.setMaxWidth(Ui.dp(ctx, 300));
            debugOverlay.setSingleLine(false);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.CENTER;
            try { windows.addView(debugOverlay, lp); FlipLog.d("[Debug] 悬浮窗已创建"); } catch (RuntimeException e) { FlipLog.d("[Debug] 悬浮窗创建失败: " + e.getMessage()); debugOverlay = null; return; }
        } else if (!show && debugOverlay != null) {
            try { windows.removeViewImmediate(debugOverlay); } catch (RuntimeException ignored) { }
            debugOverlay = null;
            FlipLog.d("[Debug] 悬浮窗已移除");
        }
        if (debugOverlay != null) {
            String pkg = dockVisibility != null ? dockVisibility.currentForeground() : null;
            debugOverlay.setText(pkg == null || pkg.isEmpty() ? "（无前台应用）" : pkg);
        }
    }
    /**
     * 缺口定位以首次安装记录的第一个有效缺口为准：每个旋转方向各记一次并写入偏好，
     * 之后服务重启、手机重启都沿用记录值，系统再报告什么都不会改变定位。
     * 只有用户主动「重新定位缺口」才会清空重来。
     */
    private ArrayList<DockGeometry.Box> cutoutsFor(Display selected, float physicalDensity) {
        int rotation = selected.getRotation();
        Point size = Displays.size(selected);
        ArrayList<DockGeometry.Box> cached = cutoutCache.get(rotation);
        if (cached != null) {
            if (fits(cached, size)) return cached;
            cutoutCache.remove(rotation);   // 分辨率变了，旧缓存作废
        }

        ArrayList<DockGeometry.Box> recorded = CutoutRecord.decode(prefs.cutoutRecord(rotation), size.x, size.y);
        if (recorded != null) {
            cutoutCache.put(rotation, recorded);
            return recorded;
        }

        ArrayList<DockGeometry.Box> read = new ArrayList<>();
        android.view.DisplayCutout cutout = selected.getCutout();
        if (cutout != null) for (android.graphics.Rect r : cutout.getBoundingRects()) read.add(new DockGeometry.Box(r.left, r.top, r.width(), r.height()));
        if (read.isEmpty() || DockGeometry.validCutout(size.x, size.y, read, physicalDensity) == null) {
            // 无效读数既不记录也不缓存：每次上报的瞬时值可能不同，缓存会让窗口反复重建。
            return new ArrayList<>();
        }
        prefs.cutoutRecord(rotation, CutoutRecord.encode(selected.getDisplayId(), rotation, size.x, size.y, read, System.currentTimeMillis()));
        FlipLog.d("[缺口] 首次记录 rotation=" + rotation + " · " + cutoutKey(read));
        cutoutCache.put(rotation, read);
        return read;
    }

    private static String cutoutKey(ArrayList<DockGeometry.Box> cutouts) {
        if (cutouts.isEmpty()) return "none";
        StringBuilder b = new StringBuilder();
        for (DockGeometry.Box box : cutouts) b.append(box.x()).append(',').append(box.y()).append(',').append(box.width()).append('x').append(box.height()).append(';');
        return b.toString();
    }

    private static boolean fits(java.util.List<DockGeometry.Box> boxes, Point size) {
        for (DockGeometry.Box box : boxes) if (box.right() > size.x || box.bottom() > size.y) return false;
        return true;
    }

    private void reconcile(boolean force) {
        if (instance != this || prefs == null || screenshotInProgress) return;
        if (automaticRotation != null) automaticRotation.checkActive();
        try {
            Display selected = Displays.selected(this, prefs);
            boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
            boolean coverOn = selected != null && selected.getState() == Display.STATE_ON;
            if (coverOn) lastCoverOnMs = System.currentTimeMillis();
            // 切换 app 时外屏可能短暂波动（灭屏/未检测到/锁屏报告，<1秒），此时不立即移除窗口，
            // 避免状态栏和 dock 栏被移除再重建导致"重新加载"。
            boolean briefFlicker = dock != null && System.currentTimeMillis() - lastCoverOnMs < 1000
                    && (!coverOn || selected == null || locked);
            if (!prefs.enabled() || !prefs.onboardingDone() || (!briefFlicker && (selected == null || locked || !coverOn))) {
                if (briefFlicker) { FlipLog.d("外屏短暂波动防抖(selected=" + (selected==null?"null":selected.getDisplayId()) + ", locked=" + locked + ")，保留窗口"); return; }
                // 总开关关闭时，取消所有待执行的开屏恢复，停止自动旋转
                if (!prefs.enabled()) {
                    if (pendingRestoreRunnable != null) { main.removeCallbacks(pendingRestoreRunnable); pendingRestoreRunnable = null; }
                    pendingRestoreAfterUnlock = false;
                    stopAutomaticRotation();
                }
                // 下次外屏亮起要重新给满重试机会，否则一次历史失败会永久卡住挂载。
                removeWindows(); signature = ""; mountFailures = 0;
                setStatus(!prefs.enabled() ? "快捷栏已暂停" : !prefs.onboardingDone() ? "请先完成初始设置引导" : selected == null ? "未检测到外屏，请手动选择" : locked ? "系统报告锁屏，快捷栏已隐藏" : "外屏未亮起，快捷栏已隐藏（状态 " + selected.getState() + "）");
                return;
            }
            Point size = Displays.size(selected);
            if (display == null || display.getDisplayId() != selected.getDisplayId()) { dockVisibility.reset(); windowPackages.clear(); keyboardVisible = null; }
            observeForeground(null);
            Context context = createDisplayContext(selected);
            // 界面整体 DPI 调节：按 uiScale 覆盖外屏 context 的 densityDpi，所有 dp 计算随之缩放。
            int baseDpi = context.getResources().getDisplayMetrics().densityDpi;
            calibrateUiScale(baseDpi);
            // 缩放只作用于派生出来的 screenContext（用来创建视图、算 dp）。
            // 挂载一律用 windowContext：createConfigurationContext 派生出的 Context 可能丢掉
            // display 绑定，窗口会被提交到已熄灭的内屏，系统直接抛 BadTokenException。
            Context scaledContext = context;
            float uiScale = prefs.uiScaleFactor();
            if (uiScale != 1f) {
                try {
                    android.content.res.Configuration scaled = new android.content.res.Configuration(context.getResources().getConfiguration());
                    scaled.densityDpi = Math.max(120, Math.min(640, Math.round(baseDpi * uiScale)));
                    scaledContext = context.createConfigurationContext(scaled);
                } catch (RuntimeException e) { scaledContext = context; }
            }
            float density = scaledContext.getResources().getDisplayMetrics().density;
            ArrayList<DockGeometry.Box> cutouts = cutoutsFor(selected, baseDpi / 160f);
            String nextSignature = selected.getDisplayId() + ":" + selected.getRotation() + ":" + size + ":" + density + ":" + cutoutKey(cutouts);
            boolean sigMatch = nextSignature.equals(signature);
            boolean dockOk = dock != null && dock.isAttachedToWindow();
            boolean entryOk = panelEntry != null && panelEntry.isAttachedToWindow();
            if (!force && sigMatch && dockOk && entryOk) { syncDockVisibility(); return; }
            if (!force && (!sigMatch || !dockOk || !entryOk)) FlipLog.d("[reconcile] 重建窗口: force=" + force + ", sigMatch=" + sigMatch + ", dockOk=" + dockOk + ", entryOk=" + entryOk + ", signature=" + signature + " → " + nextSignature);
            String reopen = panelPage; boolean reopenHub = hub != null && !hub.closing(), expandedHub = reopenHub && hub.expanded(), reopenTasks = reopenHub && hub.showingTasks();
            android.os.Bundle editorState = controlEditor != null && display != null && display.getDisplayId() == selected.getDisplayId() ? controlEditor.snapshot() : null;
            RecentTasks.Task selectedTask = reopenHub ? hub.selectedTask() : null;
            AppHubView.WorkspaceState workspaceState = reopenHub ? hub.workspaceState() : null;
            removeWindows();
            // 挂载前再确认一次：开合盖瞬间外屏可能已经熄灭，此时加窗必然被系统拒绝。
            if (!selected.isValid() || selected.getState() != Display.STATE_ON) { signature = ""; setStatus("外屏未亮起，快捷栏已隐藏（状态 " + selected.getState() + "）"); return; }
            display = selected;
            windowContext = windowContextFor(selected, context);
            screenContext = windowContext;
            if (scaledContext != null && scaledContext != context) {
                try {
                    android.content.res.Configuration scaled = new android.content.res.Configuration(windowContext.getResources().getConfiguration());
                    scaled.densityDpi = Math.max(120, Math.min(640, Math.round(baseDpi * prefs.uiScaleFactor())));
                    screenContext = windowContext.createConfigurationContext(scaled);
                } catch (RuntimeException e) { screenContext = windowContext; }
            }
            windows = windowContext.getSystemService(WindowManager.class);
            Display bound = null;
            try { bound = windows.getDefaultDisplay(); } catch (RuntimeException ignored) { }
            if (bound == null || bound.getDisplayId() != selected.getDisplayId()) {
                FlipLog.d("窗口上下文未绑定外屏（display " + (bound == null ? "null" : bound.getDisplayId()) + "），改用 display 上下文挂载");
                windows = context.getSystemService(WindowManager.class);
            }
            placement = DockGeometry.resolve(size.x, size.y, cutouts, density, prefs.corner(selected.getRotation()), prefs.widthRatio(), prefs.heightRatio(), prefs.data.getBoolean("auto_placement", true));
            DockGeometry.Placement entryAnchor = placement;
            int panelHomeInset = 0;
            android.graphics.Insets widgetSystemInsets = android.graphics.Insets.NONE;
            try {
                android.view.WindowInsets insets = windows.getMaximumWindowMetrics().getWindowInsets();
                panelHomeInset = Math.max(insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()).bottom, insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom);
                widgetSystemInsets = android.graphics.Insets.max(insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.statusBars()), insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()));
            } catch (RuntimeException ignored) { }
            navigationInsetPixels = 0;
            if (prefs.avoidNavigation()) {
                try {
                    android.view.WindowInsets insets = windows.getMaximumWindowMetrics().getWindowInsets();
                    android.graphics.Insets safe = android.graphics.Insets.max(insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()), insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()));
                    navigationInsetPixels = switch (placement.edge()) { case DockGeometry.TOP -> safe.top; case DockGeometry.LEFT -> safe.left; case DockGeometry.RIGHT -> safe.right; default -> safe.bottom; };
                } catch (RuntimeException ignored) { }
                placement = DockGeometry.avoidEdge(placement, size.x, size.y, Math.max(navigationInsetPixels, Ui.dp(screenContext, 16)), Ui.dp(screenContext, prefs.navigationGap()));
            }
            if (!prefs.avoidNavigation()) placement = DockGeometry.edgeTouch(placement, size.x, size.y);
            DockGeometry.Box content = DockGeometry.panelContent(placement, size.x, size.y, cutouts);
            panelEntryPlacement = DockGeometry.panelEntry(entryAnchor, placement, size.x, size.y, cutouts, density, panelHomeInset);
            DockGeometry.Box entryContent = panelEntryPlacement.panel();
            int contentLeft = Math.max(content.x(), entryContent.x()), contentTop = Math.max(content.y(), entryContent.y());
            content = new DockGeometry.Box(contentLeft, contentTop, Math.max(1, Math.min(content.right(), entryContent.right()) - contentLeft), Math.max(1, Math.min(content.bottom(), entryContent.bottom()) - contentTop));
            placement = new DockGeometry.Placement(placement.visual(), placement.touch(), content, placement.edge(), placement.measured());
            panelFrame = new DockGeometry.Box(0, 0, size.x, size.y);
            if (prefs.statusEnabled()) {
                DockGeometry.Box area = placement.panel(); int height = Math.round(Ui.dp(screenContext, 20) * prefs.statusScale() / 100f);
                int margin = Math.max(4, Math.round(3 * density));
                int left = area.x() <= margin ? 0 : area.x(), top = area.y() <= margin ? 0 : area.y(), right = area.right() >= size.x - margin ? size.x : area.right();
                statusBox = new DockGeometry.Box(left, top, right - left, height + area.y() - top);
                placement = new DockGeometry.Placement(placement.visual(), placement.touch(), new DockGeometry.Box(area.x(), area.y() + height + Ui.dp(screenContext, 4), area.width(), Math.max(1, area.height() - height - Ui.dp(screenContext, 4))), placement.edge(), placement.measured());
            }
            hubFrame = DockGeometry.hubContent(placement, size.x, size.y, cutouts, panelHomeInset);
            ensurePanelHost(); addDock();
            if (prefs.statusEnabled()) { statusBar = new StatusBarView(screenContext, prefs); windows.addView(statusBar, statusParameters()); }
            panelEntry = new PanelEntryView(screenContext, prefs, panelEntryPlacement, chromeListener());
            windows.addView(panelEntry, panelEntryParameters());
            DockGeometry.Box widgetArea = placement.panel();
            int widgetLeft = Math.max(widgetArea.x(), widgetSystemInsets.left), widgetTop = Math.max(widgetArea.y(), widgetSystemInsets.top);
            int widgetRight = Math.min(widgetArea.right(), size.x - widgetSystemInsets.right), widgetBottom = Math.min(widgetArea.bottom(), size.y - widgetSystemInsets.bottom);
            CoverApp.widgets(this).safeArea(selected, size.x, size.y, new DockGeometry.Box(widgetLeft, widgetTop, Math.max(0, widgetRight - widgetLeft), Math.max(0, widgetBottom - widgetTop)));
            signature = nextSignature;
            mountFailures = 0; lastMountError = "";
            boolean cutoutKnown = DockGeometry.validCutout(size.x, size.y, cutouts, baseDpi / 160f) != null;
            setStatus("快捷栏运行中 · 屏幕 " + selected.getDisplayId()
                    + (placement.measured() ? " · 按缺口定位" : cutoutKnown ? " · 手动校准" : " · 位置需校准"));
            syncDebugOverlay();
            if (!reopen.isEmpty()) { showPanel(reopen); if (editorState != null) editControls(editorState); } else if (reopenHub) { showHub(expandedHub, reopenTasks); if (hub != null) { hub.restoreWorkspaceState(workspaceState); if (reopenTasks) { hub.showTasks(true); hub.selectTask(selectedTask); } } }
        } catch (RuntimeException e) {
            removeWindows(); signature = "";
            lastMountError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " · " + e.getMessage());
            Display target = Displays.selected(this, prefs);
            FlipLog.d("外屏挂载失败: " + lastMountError + " · display " + (target == null ? "无" : target.getDisplayId() + " 状态 " + target.getState()) + " · 窗口管理器 display " + boundDisplayId() + " · 挂载方式 " + (mountPlainContext ? "display" : "窗口上下文"));
            setStatus("外屏挂载失败：" + e.getClass().getSimpleName());
            scheduleMountRetry();
        }
    }
    /** 当前 WindowManager 绑定的 display id，用于判断窗口会不会被挂到内屏。 */
    private int boundDisplayId() {
        if (windows == null) return -1;
        try { Display bound = windows.getDefaultDisplay(); return bound == null ? -1 : bound.getDisplayId(); } catch (RuntimeException e) { return -1; }
    }
    private Context windowContextFor(Display selected, Context displayContext) {
        if (mountPlainContext) return displayContext;
        try { return createWindowContext(selected, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null); }
        catch (RuntimeException | LinkageError e) { FlipLog.d("窗口上下文创建失败，改用 display 上下文: " + e); return displayContext; }
    }
    private void releaseWindowContext() {
        Context previous = windowContext;
        windowContext = null;
        if (previous == null) return;
        try { previous.getClass().getMethod("release").invoke(previous); } catch (Throwable ignored) { }
    }
    /** 挂载失败后的退避重试：外屏开着就多试几次，避免一次瞬时失败后一直挂着不恢复。 */
    private void scheduleMountRetry() {
        if (prefs == null) return;
        Display selected = Displays.selected(this, prefs);
        if (selected == null || !selected.isValid() || selected.getState() != Display.STATE_ON) { mountFailures = 0; return; }
        if (mountFailures >= 6) { setStatus("外屏挂载失败，已停止重试：" + lastMountError); return; }
        // 两条挂载路径（窗口上下文 / 纯 display 上下文）轮换，避免一条路被系统拒绝就再也起不来。
        if (mountFailures >= 2) { mountPlainContext = !mountPlainContext; FlipLog.d("重试切换挂载方式 → " + (mountPlainContext ? "display 上下文" : "窗口上下文")); }
        long[] delays = {250, 600, 1200, 2500, 4000, 6000};
        long delay = delays[Math.min(mountFailures, delays.length - 1)];
        mountFailures++;
        main.removeCallbacks(mountRetry);
        main.postDelayed(mountRetry, delay);
    }
    private void addDock() {
        boolean compact = dockCompact();
        FlipLog.d("[Dock栏] 创建 dock: compact=" + compact + ", savedPage=" + savedPage + ", autoHide=" + prefs.autoHideDock());
        dockPlacement = compact ? DockGeometry.handlesOnly(placement, screenContext.getResources().getDisplayMetrics().density) : placement;
        dock = new DockView(screenContext, prefs, dockPlacement, savedPage, chromeListener(), compact);
        if (compact) dock.setVisibility(View.GONE);
        windows.addView(dock, dockParameters());
    }
    private DockView.Listener chromeListener() {
        return new DockView.Listener() {
                @Override public void action(String id) { act(id); }
                @Override public void configure() { openSettings("dock"); }
                @Override public void beginPull(String page, float distance, float originY) { beginPanelPull(page, distance, originY); }
                @Override public void pull(String page, float distance) { pullPanel(page, distance); }
                @Override public void release(String page, float distance, float velocity, boolean canceled) { releasePanel(distance, velocity, canceled); }
                @Override public void toggleVisibility() { dockVisibility.toggle(prefs.autoHideDock(), prefs.compactApps()); syncDockVisibility(); }
            };
    }
    private WindowManager.LayoutParams dockParameters() {
        WindowManager.LayoutParams layout = parameters(dockPlacement.touch());
        if (dock.compact()) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return layout;
    }
    private WindowManager.LayoutParams panelEntryParameters() {
        WindowManager.LayoutParams layout = parameters(panelEntryPlacement.touch()); layout.setTitle("外屏双白条入口");
        // 键盘弹出时入口区域也需要穿透，避免影响键盘底部输入
        if (controlEditor != null || hub != null || (prefs.avoidKeyboard() && Boolean.TRUE.equals(keyboardVisible))) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return layout;
    }
    private void syncHubEntry() {
        if (panelEntry == null || windows == null) return;
        panelEntry.suspended(hub != null);
        try { windows.updateViewLayout(panelEntry, panelEntryParameters()); } catch (RuntimeException ignored) { }
    }
    /** 键盘弹出时通知中心/控制面板也设置穿透，避免影响键盘底部输入区域。 */
    private void syncPanelTouchable() {
        if (panelHost == null || windows == null) return;
        try {
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams) panelHost.getLayoutParams();
            boolean keyboardUp = prefs.avoidKeyboard() && Boolean.TRUE.equals(keyboardVisible);
            if (keyboardUp) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else if (panelTargetOpen) layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windows.updateViewLayout(panelHost, layout);
        } catch (RuntimeException ignored) { }
    }
    private void syncDockVisibility() {
        if (dock == null || windows == null || panelDragging) return;
        // 按应用排除栏位：当前前台应用在排除列表中时隐藏对应栏位
        boolean excludeDock = dockVisibility.isExcluded(prefs.excludeDockApps());
        boolean excludeStatus = dockVisibility.isExcluded(prefs.excludeNotificationApps());
        int dockVis = excludeDock ? View.GONE : View.VISIBLE;
        int statusVis = excludeStatus ? View.GONE : View.VISIBLE;
        if (dock.getVisibility() != dockVis) {
            dock.setVisibility(dockVis);
            FlipLog.d("[Dock栏] 按应用排除: " + dockVisibility.currentForeground() + " → " + (excludeDock ? "隐藏" : "显示"));
        }
        if (statusBar != null && statusBar.getVisibility() != statusVis && panel == null) {
            statusBar.setVisibility(statusVis);
            FlipLog.d("[状态栏] 按应用排除: " + dockVisibility.currentForeground() + " → " + (excludeStatus ? "隐藏" : "显示"));
        }
        boolean oldCompact = dock.compact(), newCompact = dockCompact();
        if (oldCompact == newCompact) return;
        FlipLog.d("[Dock栏] 紧凑模式变化: " + oldCompact + " → " + newCompact + "（autoHide=" + prefs.autoHideDock() + ", compactApps=" + prefs.compactApps() + ", keyboard=" + keyboardVisible + "），重建 dock");
        try { savedPage = dock.page(); windows.removeViewImmediate(dock); dock = null; addDock(); }
        catch (RuntimeException e) { FlipLog.d("[Dock栏] 重建失败: " + e.getMessage()); removeWindows(); signature = ""; scheduleDisplay(); }
    }
    private boolean dockCompact() { dockVisibility.keyboard(prefs.avoidKeyboard() && Boolean.TRUE.equals(keyboardVisible)); return dockVisibility.compact(prefs.autoHideDock(), prefs.compactApps()); }
    private WindowManager.LayoutParams parameters(DockGeometry.Box box) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(box.width(), box.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
        // These are physical display coordinates; locale direction must not mirror the camera cutout.
        params.gravity = Gravity.TOP | Gravity.LEFT; params.x = box.x(); params.y = box.y();
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0); params.setTitle("FlipAuto"); return params;
    }
    private boolean glassTargetSelected(PanelGlassSession opening) { Display selected=Displays.selected(this,prefs); return selected != null && selected.getDisplayId() == opening.displayId; }
    private static boolean glassPage(String page) { return page.equals("controls") || page.equals("notifications") || page.equals("media") || page.equals("rotation"); }
    void showPanel(String page) { showPanel(page, false); }
    private void showPanel(String page, boolean dragging) {
        if (windows == null || dock == null) return;
        dismissHub();
        boolean retainedFallback = panel != null && panelMemoryFallback && glassPage(page);
        // A failed or pending capture belongs to this opening too; switching pages must not retry it.
        PanelGlassSession retained = panelGlass != null && panelGlass.matches(display) && glassPage(page) ? panelGlass : null;
        if (retained != null) { panelGlass = null; retained.clearViews(); }
        closePanel(); panelMemoryFallback = retainedFallback; panelGlass = retained; panelPage = page; panelDragging = dragging; panelTargetOpen = !dragging; panelProgress = dragging ? 0 : 1;
        buildPanelContent(page, panelFrame, page.equals("controls") && statusBox != null ? statusBox.y() : placement.panel().y());
        try {
            WindowManager.LayoutParams layout = parameters(panelFrame);
            ensurePanelHost(); panelHost.addView(panel, new FrameLayout.LayoutParams(-1, -1));
            boolean capture = !panelMemoryFallback && panelGlass == null && glassPage(page) && PanelGlassSession.allowed(screenContext, prefs);
            if (capture) panelGlass = new PanelGlassSession(screenContext, display);
            if (page.equals("controls") && statusBar != null) { statusBar.setAlpha(panelGlass == null ? 1 : 0); statusBar.setVisibility(panelGlass == null ? View.INVISIBLE : View.VISIBLE); }
            panelHost.setVisibility(panelGlass != null && panelGlass.preparing() ? View.INVISIBLE : View.VISIBLE);
            if (dragging) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            applyPanelBlur(layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled());
            windows.updateViewLayout(panelHost, layout); setPanelProgress(panelProgress);
            if (panelGlass != null) {
                panelGlass.attach(panelHost, page.equals("notifications"));
                if (capture) {
                    PanelGlassSession opening = panelGlass;
                    opening.capture(this, () -> panelGlass == opening && panelHost != null && panel != null && display != null && opening.matches(display) && glassTargetSelected(opening) && display.getState() == Display.STATE_ON && !getSystemService(KeyguardManager.class).isKeyguardLocked(), () -> {
                        panelHost.setVisibility(View.VISIBLE);
                        lastGlassDiagnostics = opening.diagnostics();
                    });
                }
            }
            if (panelEntry != null) panelEntry.panelVisible(true);
            // The persistent panel host was added below the chrome. Keep the entry's
            // existing surface attached so showing/settling a panel cannot blink it.
            if (panelGlass == null && Build.VERSION.SDK_INT >= 31 && prefs.panelBlur()) {
                LinearLayout observed = panel; WindowManager observedWindows = windows;
                blurListener = enabled -> {
                    if (panel != observed || windows != observedWindows || panelHost == null || !(panelHost.getLayoutParams() instanceof WindowManager.LayoutParams current)) return;
                    applyPanelBlur(current, enabled);
                    try { windows.updateViewLayout(panelHost, current); } catch (RuntimeException ignored) { }
                };
                windows.addCrossWindowBlurEnabledListener(getMainExecutor(), blurListener);
            }
        }
        catch (RuntimeException e) { closePanel(); message("面板挂载失败：" + e.getClass().getSimpleName()); return; }
        if (page.equals("controls")) refreshStates(true);
    }
    /** The same content surface is used by the overlay and by display-sized component checks. */
    LinearLayout buildPanelContent(String page, DockGeometry.Box frame, int contentTop) {
        panelFrame = frame;
        panelPage = page;
        panel = new PanelSurface(screenContext, panelEdge(), panelExtent(), prefs.haptics(), new PanelHeaderView.Listener() {
            @Override public void begin() { cancelPanelAnimation(); panelPulling = false; panelDragging = true; }
            @Override public float currentProgress() { return panelProgress; }
            @Override public boolean cancelCloses() { return !panelTargetOpen; }
            @Override public void progress(float value) { setPanelProgress(value); }
            @Override public void finish(boolean close) { settlePanel(!close); }
        }); int padding = Ui.dp(screenContext, 6); DockGeometry.Box content = placement.panel();
        panel.setPadding(content.x() + padding, contentTop + padding, panelFrame.width() - content.right() + padding, panelFrame.height() - content.bottom());
        panel.setBackgroundColor(Ui.BACKGROUND);
        populatePanel(page); return panel;
    }
    private void populatePanel(String page) {
        panel.removeAllViews();
        if (page.equals("controls")) { StatusBarView batteryRow = new StatusBarView(screenContext, prefs, true); batteryRow.setTag("panel-status"); panel.addView(batteryRow, new LinearLayout.LayoutParams(-1, batteryRow.heightPixels())); }
        panels = new Panels(this); View contents = panels.build(page);
        LinearLayout header = Ui.row(screenContext); header.setTag("panel-header");
        String title = switch (page) { case "rotation" -> "外屏旋转"; case "notifications" -> "通知中心"; case "media" -> "媒体控制"; default -> "控制中心"; };
        TextView heading = Ui.heading(screenContext, title, 17); heading.setMinimumHeight(Ui.dp(screenContext, 36)); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setAccessibilityHeading(true);
        if (page.equals("notifications")) {
            heading.setSingleLine(); heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout titleArea = new FrameLayout(screenContext); titleArea.addView(heading, new FrameLayout.LayoutParams(-1, -1));
            TextView notice = (TextView) panels.notificationNotice(); notice.setSingleLine(); notice.setEllipsize(android.text.TextUtils.TruncateAt.END); titleArea.addView(notice, new FrameLayout.LayoutParams(-1, -1));
            panels.bindNotificationTitle(heading);
            header.addView(titleArea, new LinearLayout.LayoutParams(0, Ui.dp(screenContext, 36), 1));
            View settings = Ui.iconButton(screenContext, R.drawable.ic_ms_settings, "通知中心设置", this::openNotificationCenterSettings); settings.setTag("notification-center-settings"); settings.setBackground(Ui.ripple(screenContext, Ui.SURFACE, 18)); settings.setTooltipText("系统通知设置");
            LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(Ui.dp(screenContext, 36), Ui.dp(screenContext, 36)); settingsSize.setMarginEnd(Ui.dp(screenContext, 6)); header.addView(settings, settingsSize);
            LinearLayout.LayoutParams clearSize = new LinearLayout.LayoutParams(-2, Ui.dp(screenContext, 36)); clearSize.setMarginEnd(Ui.dp(screenContext, 6)); header.addView(panels.notificationClearAction(), clearSize);
        } else header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (page.equals("controls")) { View edit = RuntimeVisuals.button(screenContext, R.drawable.ic_ms_edit, "编辑控制中心", this::editControls); edit.setTag("control-edit"); header.addView(edit); }
        if (page.equals("controls")) { View add = RuntimeVisuals.button(screenContext, R.drawable.ic_ms_add, "添加控制按钮", () -> { editControls(); if (controlEditor != null) controlEditor.showPicker(); }); add.setTag("control-add"); header.addView(add); }
        if (page.equals("controls")) {
            android.widget.ImageButton refresh = RuntimeVisuals.button(screenContext, R.drawable.ic_ms_refresh, "刷新状态", () -> { }); refresh.setTag("control-refresh");
            refresh.setOnClickListener(v -> { refresh.setEnabled(false); refresh.setActivated(true); refresh.setStateDescription("正在刷新"); refreshStates(true, () -> { refresh.setEnabled(true); refresh.setActivated(false); refresh.setStateDescription("刷新状态"); }); }); header.addView(refresh);
        }
        header.addView(RuntimeVisuals.button(screenContext, R.drawable.ic_ms_close, "关闭面板", this::closePanel)); LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2); headerParams.bottomMargin = Ui.dp(screenContext, 3); panel.addView(header, headerParams);
        if (page.equals("controls") || page.equals("notifications")) for (int i=1;i<header.getChildCount();i++) {
            View action=header.getChildAt(i); android.view.ViewGroup.LayoutParams size=action.getLayoutParams();
            header.removeViewAt(i); header.addView(new PanelActionSlot(action),i,size);
        }
        ScrollView scroll = new ScrollView(screenContext); scroll.setFillViewport(page.equals("controls")); scroll.setVerticalScrollBarEnabled(!page.equals("notifications")); scroll.addView(contents);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        feedback = Ui.text(screenContext, "", 11, Ui.MUTED); feedback.setVisibility(View.GONE); feedback.setMaxLines(2); panel.addView(feedback);
    }
    void editControls() { editControls(null); }
    private void editControls(android.os.Bundle restored) {
        if (panel == null || !panelPage.equals("controls") || controlEditor != null) return;
        dismissDetails(); pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
        panel.removeAllViews(); panels = null; feedback = null;
        controlEditor = new ControlEditorView(screenContext, prefs, restored, id -> CoverApp.bridge(this).run("tile_add", -1, 0, ActionCatalog.component(id).flattenToString(), result -> message(result.message)), () -> {
            if (panel == null || controlEditor == null) return;
            screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(controlEditor.getWindowToken(), 0);
            controlEditor = null; populatePanel("controls"); panelEditorFocus(false); if (instance == this) refreshStates(true);
        });
        controlEditor.glass(panelGlass); panel.addView(controlEditor, new LinearLayout.LayoutParams(-1, 0, 1)); panelEditorFocus(true); controlEditor.requestFocus();
    }
    private void panelEditorFocus(boolean editing) {
        if (windows == null || panelHost == null || !(panelHost.getLayoutParams() instanceof WindowManager.LayoutParams layout)) return;
        if (editing) layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; else layout.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layout.softInputMode = (editing ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        try { windows.updateViewLayout(panelHost, layout); if (panelEntry != null) windows.updateViewLayout(panelEntry, panelEntryParameters()); } catch (RuntimeException ignored) { closePanel(); }
    }
    private void ensurePanelHost() {
        if (panelHost != null) return;
        panelHost = new FrameLayout(screenContext); panelHost.setClipChildren(true); panelHost.setVisibility(View.INVISIBLE);
        WindowManager.LayoutParams layout = parameters(panelFrame); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        windows.addView(panelHost, layout);
    }
    void closePanel() {
        panelMemoryFallback = false;
        if (panelGlass != null) { lastGlassDiagnostics = panelGlass.diagnostics(); panelGlass.close(); panelGlass = null; }
        if (controlEditor != null) { screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(controlEditor.getWindowToken(), 0); controlEditor.cancelDrag(); controlEditor = null; }
        if (panelTintAnimation != null) panelTintAnimation.cancel(); panelTintAnimation = null; panelBackdrop = null;
        pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
        dismissDetails();
        cancelPanelAnimation(); panelDragging = false; panelPulling = false; panelTargetOpen = false;
        if (statusBar != null) { statusBar.setAlpha(1); statusBar.setVisibility(View.VISIBLE); }
        if (Build.VERSION.SDK_INT >= 31 && blurListener != null && windows != null) windows.removeCrossWindowBlurEnabledListener(blurListener);
        blurListener = null;
        if (panelHost != null && windows != null) { panelHost.setVisibility(View.INVISIBLE); panelHost.removeAllViews(); try { WindowManager.LayoutParams layout = (WindowManager.LayoutParams) panelHost.getLayoutParams(); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0; if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(0); windows.updateViewLayout(panelHost, layout); } catch (RuntimeException ignored) { } }
        panel = null; feedback = null; panels = null; panelPage = ""; panelProgress = 1;
        if (panelEntry != null) panelEntry.panelVisible(false);
        if (panelEntry != null && windows != null) { try { windows.updateViewLayout(panelEntry, panelEntryParameters()); } catch (RuntimeException ignored) { } }
    }
    private void applyPanelBlur(WindowManager.LayoutParams layout, boolean supported) {
        if (panelGlass != null) {
            layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0;
            if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(0);
            return;
        }
        boolean enabled = visualEffectsAllowed() && supported && !panelDragging && !panelPage.equals("notifications");
        int tint = enabled ? 0xC2000000 : Ui.BACKGROUND;
        if (panelBackdrop == null) { panelBackdrop = new android.graphics.drawable.ColorDrawable(tint); panel.setBackground(panelBackdrop); }
        else if (panelBackdrop.getColor() != tint) {
            if (panelTintAnimation != null) panelTintAnimation.cancel();
            // Only local paint is interpolated; no per-frame window blur reconfiguration.
            if (!enabled || !ValueAnimator.areAnimatorsEnabled()) panelBackdrop.setColor(tint);
            else { int start = panelBackdrop.getColor(); android.graphics.drawable.ColorDrawable target = panelBackdrop; panelTintAnimation = ValueAnimator.ofFloat(0, 1); panelTintAnimation.setDuration(180); panelTintAnimation.addUpdateListener(frame -> target.setColor(RuntimeVisuals.blend(start, tint, (float) frame.getAnimatedValue()))); panelTintAnimation.start(); }
        }
        if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(enabled ? Math.min(100, Ui.dp(screenContext, 32)) : 0);
        if (enabled) { layout.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND; layout.dimAmount = .18f; }
        else { layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0; }
        if (details != null) setDetailBlur(true);
    }
    private boolean visualEffectsAllowed() { return !panelMemoryFallback && Build.VERSION.SDK_INT >= 31 && prefs.panelBlur() && !screenContext.getSystemService(android.os.PowerManager.class).isPowerSaveMode(); }
    private void refreshBlurState() {
        if (screenContext == null || windows == null) return;
        if (taskGlass != null && !PanelGlassSession.allowed(screenContext, prefs)) closeTaskGlass();
        if (panelGlass != null && !PanelGlassSession.allowed(screenContext, prefs)) { lastGlassDiagnostics = panelGlass.diagnostics(); panelGlass.close(); panelGlass = null; if (panelHost != null) panelHost.setVisibility(View.VISIBLE); }
        boolean supported = Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled();
        if (panel != null && panelHost != null && panelHost.getLayoutParams() instanceof WindowManager.LayoutParams layout) {
            applyPanelBlur(layout, supported);
            try { windows.updateViewLayout(panelHost, layout); } catch (RuntimeException ignored) { }
        }
        if (hub != null && hubHost != null && hubHost.getLayoutParams() instanceof WindowManager.LayoutParams layout) {
            applyHubBlur(hub, layout, supported);
            try { windows.updateViewLayout(hubHost, layout); } catch (RuntimeException ignored) { }
        }
    }
    String blurDiagnostics() {
        String accelerated = panelHost == null || !panelHost.isAttachedToWindow() ? "未挂载" : String.valueOf(panelHost.isHardwareAccelerated());
        return "外屏面板宿主硬件加速：" + accelerated + "\n详情本地模糊当前启用：" + detailBlurApplied + "\n" + (panelGlass == null ? lastGlassDiagnostics + "当前会话已释放 · 纹理 0B\n" : panelGlass.diagnostics()) + "多任务：\n" + (taskGlass == null ? lastTaskGlassDiagnostics + "当前会话已释放 · 纹理 0B\n" : taskGlass.diagnostics());
    }
    void showDetails(String id, View source) {
        if (panel == null || !(panel.getParent() instanceof FrameLayout host)) return;
        dismissDetails();
        if (panelGlass != null) panelGlass.beginModal(panel);
        DetailSheet sheet = new DetailSheet(screenContext, ActionCatalog.label(screenContext, id), this::dismissDetails); details = sheet;
        DockGeometry.Box safe = placement.panel(); FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(safe.width(), safe.height()); position.leftMargin = safe.x(); position.topMargin = safe.y();
        host.addView(sheet, position); panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setDetailBlur(true);
        if (windows != null && panelHost != null) { WindowManager.LayoutParams params = (WindowManager.LayoutParams) panelHost.getLayoutParams(); params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(panelHost, params); }
        detailContent = new ControlDetails(this, sheet); detailContent.build(id); sheet.enter(source instanceof Panels.Tile tile ? tile.face : source, panelGlass);
        if (prefs.haptics() && source != null) source.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }
    private void setDetailBlur(boolean enabled) {
        boolean active = enabled && panelGlass == null && panel != null && panel.isHardwareAccelerated() && visualEffectsAllowed();
        if (detailBlurApplied == active) return;
        detailBlurApplied = active;
        if (panel != null && Build.VERSION.SDK_INT >= 31) panel.setRenderEffect(active ? android.graphics.RenderEffect.createBlurEffect(Ui.dp(screenContext, 5), Ui.dp(screenContext, 5), android.graphics.Shader.TileMode.CLAMP) : null);
    }
    void dismissDetails() {
        if (detailContent != null) { detailContent.close(); detailContent = null; }
        if (details == null) return;
        if (panelGlass != null) panelGlass.endModal();
        if (details.getParent() instanceof android.view.ViewGroup host) host.removeView(details); details = null;
        setDetailBlur(false); if (panel != null) panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (windows != null && panelHost != null) try { WindowManager.LayoutParams params = (WindowManager.LayoutParams) panelHost.getLayoutParams(); params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(panelHost, params); } catch (RuntimeException ignored) { }
    }
    boolean rotationAutomatic() {
        if (prefs != null && !prefs.useShizuku("rotation")) return noRootRotation != null && noRootRotation.getMode() != RotationManager.MODE_OFF;
        return automaticRotation != null && automaticRotation.enabled();
    }
    /** 外屏旋转三模式：自动 / 手动（已固定方向）/ 关闭（交回系统）。免 Shizuku 时取自 RotationManager。 */
    int rotationMode() {
        if (prefs != null && !prefs.useShizuku("rotation")) return noRootRotation == null ? RotationManager.MODE_OFF : noRootRotation.getMode();
        if (automaticRotation != null && automaticRotation.enabled()) return RotationManager.MODE_AUTO;
        return prefs != null && prefs.data.getInt("rotation_lock_angle", -1) >= 0 ? RotationManager.MODE_MANUAL : RotationManager.MODE_OFF;
    }
    /** 三模式的短标签，用于面板磁贴与提示（自动与手动不再共用一句）。 */
    String rotationModeShortLabel() {
        return switch (rotationMode()) {
            case RotationManager.MODE_AUTO -> "自动旋转";
            case RotationManager.MODE_MANUAL -> "手动旋转";
            default -> "旋转关闭";
        };
    }
    /** 循环切换三模式：自动 → 手动 → 关闭 → 自动。 */
    void cycleRotationMode() { setRotationMode((rotationMode() + 1) % 3); }
    /** 三模式各自的界面文字，控制中心按此显示当前状态（自动与手动不再共用一句）。 */
    String rotationModeLabel() {
        return switch (rotationMode()) {
            case RotationManager.MODE_AUTO -> "自动旋转 · 跟随握持方向";
            case RotationManager.MODE_MANUAL -> "手动旋转 · 已固定外屏方向";
            default -> "已关闭 · 交回系统旋转";
        };
    }
    /** 直接切到指定旋转模式（0 自动 / 1 手动 / 2 关闭），用于控制中心的三选一。 */
    void setRotationMode(int mode) {
        if (prefs != null && !prefs.useShizuku("rotation")) {
            if (noRootRotation != null) {
                noRootRotation.setMode(mode);
                if (mode != RotationManager.MODE_OFF && !noRootRotation.isOverlayActive()) {
                    message("固定方向需要「显示在其他应用上层」权限");
                    requestOverlayPermission();
                } else message(noRootRotation.getModeLabel());
            }
            if (panels != null) panels.updateStates();
            return;
        }
        if (mode == RotationManager.MODE_AUTO) {
            if (prefs != null) prefs.data.edit().putInt("rotation_lock_angle", -1).apply();
            stopAutomaticRotation();
            if (automaticRotation == null || !automaticRotation.start()) message("自动旋转需要外屏、方向传感器与 Shizuku");
            else message("自动旋转已开启，以当前方向为基准");
        } else if (mode == RotationManager.MODE_MANUAL) {
            lockRotation(display == null ? 0 : display.getRotation());
        } else {
            if (prefs != null) prefs.data.edit().putInt("rotation_lock_angle", -1).apply();
            stopAutomaticRotation();
            shell("rotation_auto", 0, "", null);
            message("已恢复系统旋转");
        }
        if (panels != null) panels.updateStates();
    }
    void stopAutomaticRotation() {
        if (prefs != null && !prefs.useShizuku("rotation")) { if (noRootRotation != null) noRootRotation.setMode(RotationManager.MODE_OFF); if (panels != null) panels.updateStates(); return; }
        if (automaticRotation != null) automaticRotation.stop(); if (panels != null) panels.updateStates();
    }
    void lockRotation(int angle) {
        if (prefs != null && !prefs.useShizuku("rotation")) {
            if (noRootRotation != null) noRootRotation.setMode(RotationManager.MODE_MANUAL);
            if (panels != null) panels.updateStates();
            return;
        }
        int target = display == null ? -1 : display.getDisplayId(); stopAutomaticRotation();
        if (prefs != null) prefs.data.edit().putInt("rotation_lock_angle", angle).apply();
        Runnable lock = () -> { Display selected = Displays.selected(this, prefs); if (selected == null || selected.getDisplayId() != target || selected.getState() != Display.STATE_ON || getSystemService(KeyguardManager.class).isKeyguardLocked()) return; shell("rotation", angle, "", null); };
        if (automaticRotation != null) automaticRotation.afterCurrent(lock); else lock.run();
    }
    void toggleRotation() {
        if (prefs != null && !prefs.useShizuku("rotation")) {
            if (noRootRotation != null) {
                noRootRotation.cycleMode();
                if (noRootRotation.getMode() != RotationManager.MODE_OFF && !noRootRotation.isOverlayActive()) {
                    message("固定方向需要「显示在其他应用上层」权限");
                    requestOverlayPermission();
                } else message(noRootRotation.getModeLabel());
            }
            if (panels != null) panels.updateStates();
            return;
        }
        if (rotationAutomatic()) lockRotation(display.getRotation());
        else if (automaticRotation == null || !automaticRotation.start()) message("自动旋转需要外屏、方向传感器与 Shizuku");
        else message("自动旋转已开启，以当前方向为基准");
        if (panels != null) panels.updateStates();
    }
    private WindowManager.LayoutParams statusParameters() {
        WindowManager.LayoutParams layout = parameters(statusBox); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; layout.setTitle("外屏状态栏"); return layout;
    }
    private void raiseChrome() {
        if (dock != null) { windows.removeViewImmediate(dock); windows.addView(dock, dockParameters()); }
        if (statusBar != null) { windows.removeViewImmediate(statusBar); windows.addView(statusBar, statusParameters()); }
        if (panelEntry != null) { windows.removeViewImmediate(panelEntry); windows.addView(panelEntry, panelEntryParameters()); }
    }
    private int panelEdge() { return DockGeometry.BOTTOM; }
    private float panelExtent() { return Math.max(1, panelFrame.height()); }
    private void setPanelProgress(float value) {
        panelProgress = Math.max(0, Math.min(1, value)); if (panel == null) return;
        float offset = (1 - panelProgress) * panelExtent();
        panel.setTranslationX(0); panel.setTranslationY(offset);
    }
    private void beginPanelPull(String page, float distance, float originY) {
        if (controlEditor != null) return;
        // An already open page is idempotent: no rebuild, loss of scroll, or jump to zero.
        if (panel != null && page.equals(panelPage) && !panelDragging && panelProgress >= 1) return;
        boolean continuing = panel != null && page.equals(panelPage);
        if (!continuing) showPanel(page, true);
        if (panel == null || !page.equals(panelPage)) return;
        panelPullCancelOpen = panelTargetOpen; cancelPanelAnimation(); panelDragging = true; panelPulling = true;
        panelPullFresh = !continuing;
        panelPullStartProgress = continuing ? panelProgress : PanelDrag.entryStart(originY, panelFrame.y(), panelExtent()); panelPullStartDistance = continuing ? distance : 0;
    }
    private void pullPanel(String page, float distance) {
        if (panelPulling && page.equals(panelPage)) setPanelProgress(PanelDrag.progress(panelPullStartProgress, distance - panelPullStartDistance, panelExtent()));
    }
    private void releasePanel(float distance, float velocity, boolean canceled) {
        if (!panelPulling || panel == null) return;
        panelPulling = false;
        if (!canceled) setPanelProgress(PanelDrag.progress(panelPullStartProgress, distance - panelPullStartDistance, panelExtent()));
        // Camera/Home clearance is a position offset, never user-travel toward a commit.
        float traveled = panelPullFresh ? distance : panelProgress * panelExtent();
        settlePanel(canceled ? panelPullCancelOpen : PanelDrag.shouldOpen(traveled, panelExtent(), velocity, screenContext.getResources().getDisplayMetrics().density));
    }
    private void cancelPanelAnimation() { if (panelAnimation != null) { panelAnimation.removeAllListeners(); panelAnimation.cancel(); panelAnimation = null; } }
    private void settlePanel(boolean open) {
        cancelPanelAnimation(); panelTargetOpen = open;
        Runnable settled = () -> {
            panelAnimation = null; panelDragging = false;
            if (!open) { closePanel(); return; }
            if (panelHost == null) return;
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams) panelHost.getLayoutParams(); layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            applyPanelBlur(layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled());
            try { windows.updateViewLayout(panelHost, layout); if (panelPage.equals("notifications")) main.post(updateNotifications); } catch (RuntimeException ignored) { closePanel(); }
        };
        if (!ValueAnimator.areAnimatorsEnabled()) { setPanelProgress(open ? 1 : 0); settled.run(); return; }
        panelAnimation = ValueAnimator.ofFloat(panelProgress, open ? 1 : 0); panelAnimation.setDuration(180);
        panelAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator());
        panelAnimation.addUpdateListener(animation -> setPanelProgress((float) animation.getAnimatedValue()));
        panelAnimation.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animator) { settled.run(); } }); panelAnimation.start();
    }
    private void showHub() {
        showHub(true);
    }
    private WindowManager.LayoutParams hubParameters(boolean expanded) {
        // Keep one safe-area window: the transparent region dismisses a temporary Dock,
        // and expanding the catalog does not move or replace its application row.
        WindowManager.LayoutParams layout = parameters(hub != null && hub.showingTasks() ? panelFrame : hubFrame);
        layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layout.softInputMode = (expanded ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        applyHubBlur(hub, layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled());
        return layout;
    }
    void applyHubBlur(AppHubView target, WindowManager.LayoutParams layout, boolean supported) {
        if (target.showingTasks()) {
            target.setBackdropBlur(false);
            layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0;
            if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(0);
            return;
        }
        boolean expanded = target.expanded(), enabled = expanded && visualEffectsAllowed() && supported;
        target.setBackdropBlur(enabled);
        if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(enabled ? Math.min(100, Ui.dp(screenContext, 32)) : 0);
        if (enabled) layout.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND; else layout.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        if (expanded) { layout.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND; layout.dimAmount = enabled ? .18f : .32f; }
        else { layout.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND; layout.dimAmount = 0; }
    }
    private void taskPageChanged(RecentTasksView page) {
        closeTaskGlass(); taskSurface = page;
        if (hub == null || hubHost == null || windows == null) return;
        if (page != null) { hub.prepareTaskEntrance(); page.safeArea(hubFrame, panelFrame); }
        try { windows.updateViewLayout(hubHost, hubParameters(hub.expanded())); } catch (RuntimeException failure) { removeHubImmediately(); return; }
        if (page == null) return;
        Display selected = Displays.selected(this, prefs);
        if (selected != null && display != null && selected.getDisplayId() == display.getDisplayId() && display.getDisplayId() != Display.DEFAULT_DISPLAY && display.getState() == Display.STATE_ON && PanelGlassSession.allowed(screenContext, prefs)) {
            PanelGlassSession opening = new PanelGlassSession(screenContext, display); taskGlass = opening;
            AppHubView owner = hub; FrameLayout host = hubHost;
            host.setVisibility(View.INVISIBLE); refreshBlurState();
            opening.attach(page, true);
            opening.capture(this, () -> taskGlass == opening && taskSurface == page && hub == owner && hubHost == host && owner.isAttachedToWindow() && !owner.closing() && display != null && opening.matches(display) && glassTargetSelected(opening) && display.getState() == Display.STATE_ON && PanelGlassSession.allowed(screenContext, prefs), () -> {
                host.setVisibility(View.VISIBLE); owner.revealTasks(page); lastTaskGlassDiagnostics = opening.diagnostics();
            });
        } else { refreshBlurState(); hub.revealTasks(page); }
    }
    private void closeTaskGlass() {
        if (taskGlass != null) { lastTaskGlassDiagnostics = taskGlass.diagnostics(); taskGlass.close(); taskGlass = null; }
        if (taskSurface != null) { taskSurface.glass(null); taskSurface.previewsVisible(); }
        if (hubHost != null) hubHost.setVisibility(View.VISIBLE);
    }
    private void showHub(boolean expandedInitially) { showHub(expandedInitially, false); }
    private void showHub(boolean expandedInitially, boolean tasksInitially) {
        if (windows == null || dock == null) return;
        closePanel(); removeHubImmediately();
        hub = new AppHubView(screenContext, prefs, new AppHubView.Listener() {
            @Override public void action(String id) { if (!id.startsWith("app:") && !java.util.Set.of("app_dock", "app_hub", "recents", "back").contains(id)) dismissHub(); act(id); }
            @Override public void editFavorites() { openSettings("favorites"); }
            @Override public void editPinned() { openSettings("hub_pin"); }
            @Override public void settings() { openSettings("main"); }
            @Override public void applicationSettings(String id, boolean uninstall) {
                android.content.ComponentName component = ActionCatalog.component(id); if (component == null) return;
                Intent intent = new Intent(uninstall ? Intent.ACTION_DELETE : android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", component.getPackageName(), null)); launch(intent);
            }
            @Override public void close() { dismissHub(); }
            @Override public void refreshRecents() { requestHubTasks(null); }
            @Override public void clearRecents(java.util.List<RecentTasks.Task> tasks) { requestHubTasks(tasks); }
            @Override public void closeTask(RecentTasks.Task task) { requestHubTasks(java.util.List.of(task), true); }
            @Override public void openTask(RecentTasks.Task task) { openHubTask(task); }
            @Override public boolean managesTaskEntrance() { return true; }
            @Override public void taskPageChanged(RecentTasksView page) { CoverService.this.taskPageChanged(page); }
            @Override public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) { requestTaskSnapshot(task, callback); }
            @Override public void expand(boolean expanded) {
                if (hub == null || hubHost == null || !hubHost.isAttachedToWindow()) return;
                WindowManager.LayoutParams layout = hubParameters(expanded);
                if (!expanded) screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(hub.getWindowToken(), 0);
                try { windows.updateViewLayout(hubHost, layout); } catch (RuntimeException e) { removeHubImmediately(); }
            }
        });
        // Let the fixed dock key receive its complete click before toggling the hub.
        // ACTION_OUTSIDE on DOWN would close it early, then UP would reopen it.
        hub.dockEdge(placement.edge()); hub.setExpanded(expandedInitially); if (tasksInitially) hub.showTasks(true); hub.prepareEntrance();
        attachHubContent(hub);
        if (tasksInitially) hubHost.setVisibility(View.INVISIBLE);
        try {
            windows.addView(hubHost, hubParameters(expandedInitially)); syncHubEntry(); raiseChrome();
            if (tasksInitially) {
                AppHubView opening = hub; RecentTasksView page = taskSurface;
                opening.postOnAnimation(() -> { if (hub == opening && !opening.closing() && opening.isAttachedToWindow()) taskPageChanged(page); });
            } else hub.enter();
            if (Build.VERSION.SDK_INT >= 31 && prefs.panelBlur()) {
                AppHubView observed = hub; FrameLayout observedHost = hubHost; WindowManager observedWindows = windows; hubBlurWindows = observedWindows;
                hubBlurListener = enabled -> {
                    if (hub != observed || hubHost != observedHost || windows != observedWindows || !(observedHost.getLayoutParams() instanceof WindowManager.LayoutParams layout)) return;
                    applyHubBlur(observed, layout, enabled);
                    try { observedWindows.updateViewLayout(observedHost, layout); } catch (RuntimeException ignored) { }
                };
                observedWindows.addCrossWindowBlurEnabledListener(getMainExecutor(), hubBlurListener);
            }
        } catch (RuntimeException e) { removeHubImmediately(); message("应用中心暂不可用"); }
    }
    FrameLayout attachHubContent(AppHubView content) {
        // Match the panel host structure: move/fade a child while the window's
        // physical bounds and input coordinate space stay fixed until completion.
        hubHost = new FrameLayout(screenContext); hubHost.addView(content, new FrameLayout.LayoutParams(-1, -1)); return hubHost;
    }
    private void dismissHub() {
        if (hub == null) return; AppHubView target = hub;
        target.dismiss(() -> { if (hub == target) removeHubImmediately(); });
    }
    private void removeHubImmediately() {
        closeTaskGlass(); taskSurface = null;
        if (Build.VERSION.SDK_INT >= 31 && hubBlurListener != null && hubBlurWindows != null) try { hubBlurWindows.removeCrossWindowBlurEnabledListener(hubBlurListener); } catch (RuntimeException ignored) { }
        hubBlurListener = null; hubBlurWindows = null;
        AppHubView removed = hub; FrameLayout host = hubHost; hub = null; hubHost = null;
        if (removed != null) { screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(removed.getWindowToken(), 0); removed.dispose(); }
        if (host != null) { try { windows.removeViewImmediate(host); } catch (RuntimeException ignored) { } host.removeAllViews(); }
        syncHubEntry();
    }
    private void requestHubTasks(java.util.List<RecentTasks.Task> clearing) {
        requestHubTasks(clearing, false);
    }
    private void requestHubTasks(java.util.List<RecentTasks.Task> clearing, boolean explicitTask) {
        AppHubView target = hub; Display selected = Displays.selected(this, prefs);
        if (target == null || target.closing() || !target.isAttachedToWindow() || target.recentBusy()) return;
        if (clearing == null && CoverApp.bridge(this).busy()) return;
        if (display == null || selected == null || selected.getDisplayId() != display.getDisplayId() || selected.getDisplayId() <= 0 || selected.getState() != Display.STATE_ON || getSystemService(KeyguardManager.class).isKeyguardLocked()) { target.recentResult(java.util.List.of(), null); target.recentFailure("目标外屏不可用"); return; }
        int displayId = selected.getDisplayId();
        boolean clearingTasks = clearing != null;
        target.recentBusy(true);
        java.util.function.BooleanSupplier active = () -> hub == target && !target.closing() && target.isAttachedToWindow();
        java.util.function.Consumer<AppRecentTasks.Response> complete = result -> {
            if (hub != target) return;
            if (result.unchanged()) { target.recentBusy(false); message(result.message()); return; }
            if (!result.ok()) { target.recentFailure(result.message()); if (clearingTasks) message(result.message()); return; }
            AppRecentTasks.Snapshot snapshot = result.snapshot(); target.recentCapabilities(snapshot.canOpen(), snapshot.canClear(), snapshot.canSnapshot()); target.recentResult(snapshot.tasks(), result.message());
        };
        if (explicitTask) AppRecentTasks.dismiss(this, prefs, clearing.get(0), active, complete);
        else AppRecentTasks.request(this, prefs, displayId, clearing, active, complete);
    }
    private boolean currentTaskDisplay(int id) {
        Display selected = Displays.selected(this, prefs);
        return instance == this && prefs.enabled() && id > 0 && display != null && display.getDisplayId() == id && selected != null && selected.getDisplayId() == id && selected.getState() == Display.STATE_ON && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private void openHubTask(RecentTasks.Task task) {
        AppHubView target = hub;
        if (target == null || target.closing() || !target.isAttachedToWindow() || target.recentBusy()) return;
        if (!currentTaskDisplay(task.displayId())) { target.recentFailure("目标外屏已改变"); return; }
        target.recentBusy(true);
        AppRecentTasks.open(this, prefs, task, () -> hub == target && !target.closing() && target.isAttachedToWindow(), result -> finishHubTask(target, task, result));
    }
    void finishHubTask(AppHubView target, RecentTasks.Task task, ShizukuBridge.Result result) {
        if (hub != target) return;
        if (!currentTaskDisplay(task.displayId())) { if (hub == target) target.recentFailure("外屏状态已改变"); return; }
        String state = "";
        if (result.ok) try { state = new JSONObject(result.output).getString("state"); } catch (Exception ignored) { }
        if (state.equals("opened")) { dismissHub(); closePanel(); }
        else if (hub == target) {
            String failure = state.equals("gone") ? "原窗口已结束，可重新打开应用" : result.ok ? "已请求恢复，但未确认任务在外屏可见" : result.message;
            if (target.closing()) target.recentFailure(failure); else target.taskOpenFailure(task, state.equals("gone"), failure);
        }
    }
    private void requestTaskSnapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) {
        AppHubView target = hub;
        if (target == null || !target.showingTasks() || !currentTaskDisplay(task.displayId())) { callback.accept(new ShizukuBridge.Snapshot(null, "外屏状态已改变")); return; }
        CoverApp.bridge(this).snapshot(task.displayId(), task, result -> {
            if (hub != target || !target.showingTasks() || !currentTaskDisplay(task.displayId())) {
                if (result.bitmap() != null) result.bitmap().recycle(); callback.accept(new ShizukuBridge.Snapshot(null, "外屏状态已改变")); return;
            }
            callback.accept(result);
        });
    }
    private void removeWindows() {
        // 记录移除原因（调用栈前3帧），帮助定位状态栏/dock栏被意外移除的场景
        StackTraceElement[] stack = new RuntimeException().getStackTrace();
        StringBuilder caller = new StringBuilder();
        for (int i = 1; i < Math.min(4, stack.length); i++) caller.append(stack[i].getMethodName()).append(" ← ");
        FlipLog.d("[removeWindows] 移除所有窗口，调用链: " + caller);
        CoverApp.widgets(this).safeArea(null, 0, 0, null);
        if (systemControlsToast != null) { systemControlsToast.cancel(); systemControlsToast = null; }
        removeHubImmediately(); closePanel();
        if (panelHost != null) { try { windows.removeViewImmediate(panelHost); } catch (RuntimeException ignored) { } panelHost = null; }
        if (statusBar != null) { try { windows.removeViewImmediate(statusBar); } catch (RuntimeException ignored) { } statusBar = null; }
        if (debugOverlay != null) { try { if (windows != null) windows.removeViewImmediate(debugOverlay); } catch (RuntimeException ignored) { } debugOverlay = null; }
        if (panelEntry != null) { PanelEntryView removed = panelEntry; panelEntry = null; try { windows.removeViewImmediate(removed); } catch (RuntimeException ignored) { } }
        statusBox = null;
        DockView removed = dock; dock = null;
        if (removed != null) { savedPage = removed.page(); try { windows.removeViewImmediate(removed); } catch (RuntimeException ignored) { } }
        panelEntryPlacement = null; hubFrame = null;
        releaseWindowContext();
    }
    /** 提示等需要真实 display 的场景用挂载上下文，派生出的缩放上下文可能不绑定外屏。 */
    private Context toastContext() {
        if (windowContext != null) return windowContext;
        return screenContext != null ? screenContext : this;
    }
    String windowDiagnostics() {
        String entry = panelEntry != null && panelEntry.isAttachedToWindow() && panelEntryPlacement != null
            ? (panelEntryPlacement.vertical() ? "上条通知／下条控制中心" : "左条通知／右条控制中心") + " · " + (panelEntryPlacement.measured() ? "已记录缺口" : "校准估计，需本机确认") + " · " + panelEntryPlacement.touch() : "未挂载";
        return "系统导航边缘：" + navigationInsetPixels + "px\n导航避让：" + prefs.avoidNavigation() + "，额外内移 " + prefs.navigationGap() + "dp\n" + "快捷栏窗口：" + (dock == null ? "未创建" : dock.isAttachedToWindow() ? "已挂载" : "已脱离，等待恢复") + "\n挂载失败原因：" + (lastMountError.isEmpty() ? "无" : lastMountError) + (mountFailures > 0 ? "（已重试 " + mountFailures + " 次）" : "") + "\n挂载方式：" + (mountPlainContext ? "display 上下文（回退）" : windowContext == null ? "未挂载" : "窗口上下文 · display " + boundDisplayId()) + "\n双白条入口：" + entry + "\n显示模式：" + (dock != null && dock.compact() ? "快捷按钮已隐藏" : "快捷按钮") + "\n外屏键盘：" + (keyboardVisible == null ? "未知" : keyboardVisible ? "已检测到" : "未检测到") + "\n";
    }
    private static void setStatus(String value) {
        if (value.equals(status)) return;
        status = value;
        if (lifecycleHistory.size() == 10) lifecycleHistory.removeFirst();
        lifecycleHistory.addLast(android.os.SystemClock.elapsedRealtime() / 1000 + "s · " + value);
    }
    static String lifecycleDiagnostics() { return "最近显示状态（本次进程，开机后秒数）：\n" + String.join("\n", lifecycleHistory) + "\n"; }
    /** 诊断页「重新挂载」：清掉失败计数并立刻再试一次，不必等下一次开合盖。 */
    static void remount() {
        CoverService self = instance;
        if (self == null) return;
        self.main.post(() -> { if (instance == self) { self.mountFailures = 0; self.mountPlainContext = false; self.reconcile(true); } });
    }
    /** 设置页「重新定位缺口」：丢掉内存里的旧值，按当前系统缺口重新记录一次。 */
    static void resetCutouts() {
        CoverService self = instance;
        if (self == null) return;
        self.main.post(() -> { if (instance == self) { self.cutoutCache.clear(); self.reconcile(true); } });
    }
    void message(String text) {
        if (feedback != null && panelPage.equals("controls")) { feedback.announceForAccessibility(text); Toast.makeText(toastContext(), text, Toast.LENGTH_SHORT).show(); }
        else if (feedback != null) { feedback.setVisibility(View.VISIBLE); feedback.setText(text); feedback.announceForAccessibility(text); }
        else Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
    void shell(String operation, int value, String component, ShizukuBridge.Callback after) {
        String action = operation.equals("tile_click") ? "tile:" + component : operation; Panels target = panels;
        if (target != null && target.working(action)) return;
        if (target != null) target.working(action, true);
        message("正在执行…");
        CoverApp.bridge(this).run(operation, display == null ? -1 : display.getDisplayId(), value, component, result -> {
            if (instance != this) return;
            if (target != null) target.working(action, false);
            message(result.message); if (after != null) after.accept(result);
        });
    }
    void refreshStates(boolean readBrightness) { refreshStates(readBrightness, () -> { }); }
    private void refreshStates(boolean readBrightness, Runnable finished) {
        // 全部状态读取使用免 root 方案（NoRootOps），不再依赖 Shizuku states
        // 避免 Shizuku 连接异常导致状态全部未知，读取更稳定
        int preservedSystemControls = states.getOrDefault("system_controls", -1);
        states.clear();
        for (String id : new String[]{"wifi", "bluetooth", "data", "dnd", "vpn", "airplane", "nfc", "hotspot", "powersave", "location"}) {
            states.put(id, NoRootOps.state(this, id));
        }
        // 内外屏控制中心状态只能由 Shizuku 读取：已连接时保留上次读到的值，
        // 否则每次刷新都会把它重置为未知，与用户刚切换的结果矛盾。
        states.put("system_controls", prefs != null && prefs.useShizuku("system_controls") && CoverApp.bridge(this).connected() ? preservedSystemControls : -1);
        if (detailContent != null) detailContent.statesChanged();
        if (panels != null) panels.updateStates();
        finished.run();
        if (readBrightness && panelPage.equals("controls") && panels != null && panels.hasBrightness() && display != null) requestBrightness(panels, -1);
    }
    void setBrightness(Panels target, int value) {
        if (value < 0 || value > 100) return;
        requestBrightness(target, value);
    }
    private boolean brightnessCurrent(BrightnessRequest request) {
        return panels == request.target() && panelPage.equals("controls") && request.target().hasBrightness() && currentTaskDisplay(request.displayId());
    }
    private void requestBrightness(Panels target, int value) {
        if (display == null) return;
        if (prefs != null && !prefs.useShizuku("brightness")) {
            // 免 Shizuku：通过 WRITE_SETTINGS 权限直接调节系统亮度
            if (value >= 0) {
                if (NoRootOps.canWriteSettings(this)) {
                    // 滑块 0-100 映射到系统亮度 0-255
                    int sysValue = (int) Math.round(value / 100.0 * 255);
                    NoRootOps.setBrightness(this, sysValue);
                    target.brightnessWorking(false);
                } else {
                    target.brightnessWorking(false);
                    message("请先授权「修改系统设置」权限");
                    NoRootOps.openSettings(this, android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
                }
            }
            return;
        }
        // Refreshes must never replace a user value waiting for the shared Shizuku service.
        if (value < 0 && (pendingBrightness != null || (activeBrightness != null && activeBrightness.target() == target && activeBrightness.sequence() == brightnessSequence))) return;
        BrightnessRequest request = new BrightnessRequest(target, display.getDisplayId(), value, android.os.SystemClock.uptimeMillis() + 2500, brightnessSequence + 1);
        if (!brightnessCurrent(request)) return;
        brightnessSequence = request.sequence();
        pendingBrightness = request;
        if (value >= 0) { target.brightnessWorking(true); message("正在调节外屏亮度…"); }
        main.removeCallbacks(drainBrightness); drainBrightness();
    }
    private void drainBrightness() {
        BrightnessRequest request = pendingBrightness;
        if (request == null) return;
        if (!brightnessCurrent(request)) { pendingBrightness = null; return; }
        ShizukuBridge bridge = CoverApp.bridge(this);
        if (activeBrightness != null || bridge.busy()) {
            if (android.os.SystemClock.uptimeMillis() < request.deadline()) { main.postDelayed(drainBrightness, 80); return; }
            pendingBrightness = null;
            ShizukuBridge.Result timeout = new ShizukuBridge.Result(false, "亮度操作等待超时，请重试", "", true);
            if (request.value() >= 0) { request.target().brightnessWorking(false); request.target().brightnessWritten(timeout); }
            message(timeout.message); return;
        }
        pendingBrightness = null; activeBrightness = request;
        bridge.run(request.value() < 0 ? "brightness_read" : "brightness", request.displayId(), Math.max(0, request.value()), "", result -> {
            if (activeBrightness == request) activeBrightness = null;
            if (brightnessCurrent(request) && request.sequence() == brightnessSequence) {
                if (request.value() < 0) request.target().brightness(result); else { request.target().brightnessWorking(false); request.target().brightnessWritten(result); }
                if (request.value() >= 0 || !result.ok) message(result.message);
            }
            main.removeCallbacks(drainBrightness); drainBrightness();
        });
    }
    Boolean on(String id) {
        if (id.equals("torch")) return torchOn;
        int value = states.getOrDefault(id, -1);
        if (value < 0) return null;
        if (id.equals("dnd")) return value > 0;
        return value <= 1 ? value == 1 : null;
    }
    void act(String id) {
        switch (id) {
            case "app_dock" -> { if (hub == null) showHub(false); else if (hub.closing()) { closePanel(); hub.reopen(); hub.setExpanded(false); } else if (hub.expanded()) hub.setExpanded(false); else dismissHub(); }
            case "app_hub" -> { if (hub == null) showHub(); else if (hub.closing()) { closePanel(); hub.reopen(); hub.showTasks(false); hub.setExpanded(true); } else if (hub.showingTasks()) hub.showTasks(false); else if (!hub.expanded()) hub.setExpanded(true); else dismissHub(); }
            case "controls", "rotation", "media" -> { if (panelPage.equals(id)) closePanel(); else showPanel(id); }
            case "notifications" -> global(GLOBAL_ACTION_NOTIFICATIONS);
            case "notification_list" -> { if (panelPage.equals("notifications")) closePanel(); else showPanel("notifications"); }
            case "configure" -> openSettings("dock");
            case "apps" -> openSettings("apps");
            case "home" -> returnHome();
            case "back" -> { if (controlEditor != null) controlEditor.back(); else if (details != null) details.back(); else if (hub != null && !hub.closing()) hub.back(); else if (panel != null) closePanel(); else if (hub == null) global(GLOBAL_ACTION_BACK); }
            case "recents" -> {
                if (prefs.useShizuku("recents")) {
                    if (hub != null && hub.showingTasks() && !hub.closing()) dismissHub();
                    else { if (hub == null) showHub(true, true); if (hub != null) { if (hub.closing()) { closePanel(); hub.reopen(); } hub.showTasks(true); } }
                } else {
                    // normal（免 Shizuku）：zip 方案显式打开外屏最近任务
                    openRecentsZip();
                }
            }
            case "system_recents" -> global(GLOBAL_ACTION_RECENTS);
            case "lock" -> global(GLOBAL_ACTION_LOCK_SCREEN);
            case "screenshot" -> screenshot();
            case "torch" -> toggleTorch();
            case "system_controls" -> setSystemControls(-1);
            case "nfc" -> {
                // NFC Shizuku 反射实现在 Android 16+ 不可靠，统一跳转系统设置
                NoRootOps.openSettings(this, android.provider.Settings.ACTION_NFC_SETTINGS);
                message("已打开 NFC 设置");
                closePanel();
            }
            case "hotspot" -> {
                // 免 root 模式：直接跳转系统设置
                if (prefs != null && !prefs.useShizuku(id)) {
                    NoRootOps.openSettings(this, android.provider.Settings.ACTION_WIRELESS_SETTINGS);
                    message("热点请在系统设置中开关");
                    closePanel();
                } else if (!CoverApp.bridge(this).connected()) { if (panel == null || !panelPage.equals("controls")) showPanel("controls"); showDetails(id, null); }
                else connectivityAction(id, -1, "", null);
            }
            case "wifi", "bluetooth", "data", "dnd", "vpn", "airplane", "location" -> {
                // 免 root 模式：不出现二级菜单，直接跳转系统设置
                if (prefs != null && !prefs.useShizuku(id)) {
                    setSwitch(id, false); // false 仅作占位，NoRootOps.toggle 会忽略具体值直接跳转设置
                    return;
                }
                Boolean value = on(id);
                if (value == null) { showPanel("controls"); panels.chooseState(id); }
                else setSwitch(id, !value);
            }
            case "powersave" -> {
                // 免 root 模式：系统限制省电模式开关，直接跳转三星省电设置由用户手动操作
                if (prefs != null && !prefs.useShizuku(id)) {
                    NoRootOps.toggle(this, "powersave", false); // 跳转目标统一在 NoRootOps（三星省电设置页）
                    message("省电模式请在系统设置中切换");
                    closePanel();
                    main.postDelayed(() -> refreshStates(false, () -> { }), 300);
                    return;
                }
                Boolean value = on(id);
                if (value == null) { showPanel("controls"); panels.chooseState(id); }
                else setSwitch(id, !value);
            }
            default -> {
                if (id.startsWith("tile:")) shell("tile_click", 0, ActionCatalog.component(id).flattenToString(), null);
                else if (id.startsWith("app:")) launchApp(id);
            }
        }
    }
    void connectivityStateChanged(String id, int value) { states.put(id, value); if (panels != null) panels.updateStates(); }
    void refreshConnectivityStates() {
        if (instance != this) return;
        // 免 root（Shizuku 未连接）：NFC/热点状态统一由 NoRootOps 提供。
        // 此时 bridge.run 会立即返回失败并把两项置为 -1，覆盖可读的真实状态，故直接跳过。
        if (!CoverApp.bridge(this).connected()) return;
        Panels target = panels; int screen = display == null ? -1 : display.getDisplayId();
        if (target == null || !panelPage.equals("controls") || !currentTaskDisplay(screen)) return;
        CoverApp.bridge(this).run("connectivity_states", screen, 0, "", result -> {
            if (instance != this || panels != target || !currentTaskDisplay(screen) || result.retryable) return;
            try { JSONObject values = result.ok ? new JSONObject(result.output) : new JSONObject(); states.put("nfc", values.optInt("nfc", -1)); states.put("hotspot", values.optInt("hotspot", -1)); }
            catch (Exception error) { states.put("nfc", -1); states.put("hotspot", -1); }
            target.updateStates();
        });
    }
    boolean connectivityDisplay(int screen) { return instance == this && currentTaskDisplay(screen); }
    void connectivityAction(String operation, int value, String input, ShizukuBridge.Callback after) {
        if (prefs != null && !prefs.useShizuku(operation)) {
            boolean nfc = operation.startsWith("nfc");
            // 免 root 模式下 NFC/热点无法直接开关，提示用户后跳转系统设置手动操作
            message(nfc ? "NFC 请在系统设置中开关" : "热点请在系统设置中开关");
            NoRootOps.openSettings(this, nfc ? Settings.ACTION_NFC_SETTINGS : Settings.ACTION_WIRELESS_SETTINGS);
            if (after != null) after.accept(new ShizukuBridge.Result(true, "已打开设置", ""));
            return;
        }
        int screen = display == null ? -1 : display.getDisplayId();
        if (!currentTaskDisplay(screen)) { message("外屏不可用，请解锁后重试"); if (after != null) after.accept(new ShizukuBridge.Result(false, "外屏不可用", "")); return; }
        Panels target = panels; String id = operation.startsWith("nfc") ? "nfc" : "hotspot";
        if (target != null) { if (target.working(id)) { if (after != null) after.accept(new ShizukuBridge.Result(false, "上一项仍在执行，请稍后重试", "", true)); return; } target.working(id, true); }
        CoverApp.bridge(this).run(operation, screen, value, input, result -> {
            if (target != null) target.working(id, false);
            if (instance != this || !currentTaskDisplay(screen)) return;
            message(result.message); if (after != null) after.accept(result); refreshConnectivityStates();
        });
    }
    void setSwitch(String id, boolean enabled) {
        setSwitch(id,enabled,() -> { });
    }
    void setSwitch(String id, boolean enabled,Runnable finished) {
        if (prefs != null && !prefs.useShizuku(id)) {
            Panels target = panels;
            if (target != null) target.working(id, true);
            NoRootOps.toggle(this, id, enabled);
            // 免 Shizuku 模式：切换后关闭控制中心（多数开关会跳转系统设置页）
            main.postDelayed(() -> { closePanel(); refreshStates(false, () -> { if (target != null && panels == target) target.working(id, false); finished.run(); }); }, 300);
            return;
        }
        Panels target = panels;
        shell(id, enabled ? 1 : 0, "", result -> {
            if (target != null && panels == target) target.working(id, result.ok);
            // 蓝牙开启是异步操作，系统需要更长时间完成，增加刷新延迟
            int refreshDelay = id.equals("bluetooth") && enabled ? 1500 : 700;
            main.postDelayed(() -> refreshStates(false, () -> { if (target != null && panels == target) target.working(id, false); finished.run(); }), refreshDelay);
        });
    }
    void setSystemControls(int value) {
        setSystemControls(value,() -> { });
    }
    void setSystemControls(int value,Runnable finished) {
        if (prefs != null && !prefs.useShizuku("system_controls")) {
            message("内外屏控制中心需要 Shizuku 授权");
            if (finished != null) finished.run();
            return;
        }
        if (systemControlsBusy) { finished.run(); return; }
        SystemControlGuard guard = systemControlGuard;
        int requested = guard == null ? value : guard.begin(value);
        systemControlsBusy = true; Panels target = panels;
        if (target != null) target.working("system_controls", true);
        if (feedback != null) { feedback.setVisibility(View.GONE); feedback.announceForAccessibility("正在切换内外屏控制中心"); }
        CoverApp.bridge(this).run("system_controls", -1, requested, "", result -> {
            systemControlsBusy = false;
            if (guard != null) guard.finish(result);
            if (instance != this) return;
            if (!result.retryable) {
                int state = -1;
                if (result.output.equals("0") || result.output.equals("1")) state = Integer.parseInt(result.output);
                states.put("system_controls", state);
            }
            if (target != null) target.working("system_controls", false);
            if (panels != null) panels.updateStates();
            systemControlsTip(result.message);
            finished.run();
        });
    }
    void systemControlsTip(String text) {
        if (systemControlsToast != null) { systemControlsToast.cancel(); systemControlsToast = null; }
        if (screenContext == null) return;
        if (!screenContext.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
            if (feedback != null) { feedback.setText(text + "；通知权限未开启，长按图标开启"); feedback.setVisibility(View.VISIBLE); feedback.announceForAccessibility(feedback.getText()); }
            return;
        }
        // Use the system's text Toast on the existing cover context; never fall back to display 0.
        systemControlsToast = Toast.makeText(toastContext(), text, Toast.LENGTH_LONG);
        systemControlsToast.show();
    }
    private void returnHome() {
        int target = display == null ? -1 : display.getDisplayId();
        if (!currentTaskDisplay(target)) { message("外屏不可用，请解锁后重试"); return; }
        if (prefs.homeAction().equals("launcher")) {
            // Home 目标是引导里选的第三方启动器：包名为空或不可启动时回退三星卡片页，避免按了没反应。
            String pkg = prefs.flipTargetApp();
            if (pkg == null || pkg.isEmpty() || !isLaunchableApp(pkg)) { message("未设置外屏启动器，改用三星卡片页"); }
            else { dismissHub(); closePanel(); launchPackageZip(pkg); return; }
        }
        Context owner = screenContext;
        if (prefs.homeAction().equals("clock")) {
            dismissHub(); closePanel();
            main.postDelayed(() -> {
                if (owner != screenContext || !currentTaskDisplay(target) || !prefs.homeAction().equals("clock")) return;
                if (!performGlobalAction(GLOBAL_ACTION_HOME)) message("系统未接受返回锁屏页面");
            }, 80);
            return;
        }
        // Samsung rejects ordinary app launches of its cover home from another app.
        // The authorized, fixed Shizuku operation resumes the native task without a HOME event.
        message("正在返回三星卡片…");
        CoverApp.bridge(this).run("native_home", target, 0, "", () -> owner == screenContext && currentTaskDisplay(target) && prefs.homeAction().equals("cards"), result -> {
            if (owner != screenContext || !currentTaskDisplay(target) || !prefs.homeAction().equals("cards")) return;
            if (result.ok) launcherAccepted(); else message(result.message);
        });
    }
    private void global(int action) { if (action == GLOBAL_ACTION_LOCK_SCREEN) removeHubImmediately(); else dismissHub(); closePanel(); main.postDelayed(() -> { if (!performGlobalAction(action)) message("系统未接受此操作"); }, 80); }
    void openSettings(String section) { launch(new Intent(this, MainActivity.class).putExtra("section", section)); }
    void launchApp(String id) {
        int target = display == null ? -1 : display.getDisplayId();
        CoverApp.launcher(this).launch(this, prefs, id, target, () -> instance == this && prefs.enabled() && display != null && display.getDisplayId() == target, this::message, accepted -> { if (accepted) launcherAccepted(); });
    }
    void launcherAccepted() { dismissHub(); closePanel(); }
    boolean launcherAction(String id, int target) {
        if (!currentTaskDisplay(target) || !prefs.actions("favorites").contains(id) || !ActionCatalog.valid(id)) return false;
        act(id); return true;
    }
    void launch(Intent intent) {
        if (!notificationDisplayReady()) { message("外屏不可用，请解锁后重试"); return; }
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle()); dismissHub(); closePanel(); }
        catch (RuntimeException e) { message("无法在外屏打开：" + e.getClass().getSimpleName()); }
    }
    void openNotification(StatusBarNotification item) {
        if (!notificationDisplayReady()) { message("外屏不可用，请解锁后重新打开通知中心"); return; }
        final String pkg = item.getPackageName();
        // FlipAuto 双保险：先发送 contentIntent，延迟 150ms 后直接启动应用包名兜底
        if (item.getNotification().contentIntent != null) {
            try {
                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId());
                if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                item.getNotification().contentIntent.send(this, 0, null, null, null, null, options.toBundle());
            } catch (Exception ignored) { }
        }
        main.postDelayed(() -> launchPackageOnCover(pkg), 150);
        closePanel();
    }
    /** 在外屏直接启动应用包名（FlipAuto 兜底方案） */
    private void launchPackageOnCover(String packageName) {
        if (packageName == null || packageName.isEmpty() || display == null) return;
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) return;
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId());
            startActivity(launchIntent, options.toBundle());
        } catch (Throwable ignored) { }
    }
    private boolean notificationDisplayReady() {
        Display selected = Displays.selected(this, prefs);
        return display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY && selected != null && selected.getDisplayId() == display.getDisplayId() && selected.getState() == Display.STATE_ON && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    void openNotificationSettings(StatusBarNotification item) {
        if (!notificationDisplayReady()) { message("外屏不可用，请解锁后重试"); return; }
        if (!item.getUser().equals(android.os.Process.myUserHandle())) { message("请在通知所属的用户中管理"); return; }
        try {
            startActivity(NotificationGroups.settingsIntent(item).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle());
            dismissHub(); closePanel();
        } catch (RuntimeException failure) { message("无法打开该应用的通知设置"); }
    }
    void openNotificationCenterSettings() {
        if (!notificationDisplayReady()) { message("外屏不可用，请解锁后重试"); return; }
        if (Build.VERSION.SDK_INT < 33) { message("当前系统未提供通知管理入口"); return; }
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle()); dismissHub(); closePanel();
        } catch (RuntimeException failure) { message("无法打开通知设置"); }
    }
    public void notificationsChanged() { if (!main.hasCallbacks(updateNotifications)) main.postDelayed(updateNotifications, 200); }
    public void permissionsChanged() { main.removeCallbacks(updatePreferences); main.post(updatePreferences); }
    private void toggleTorch() {
        setTorch(!Boolean.TRUE.equals(torchOn));
    }
    void setTorch(boolean enabled) {
        if (torchCamera == null) { message("未找到可用闪光灯"); return; }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) { openSettings("main"); message("请先授予相机权限"); return; }
        try { cameras.setTorchMode(torchCamera, enabled); message("手电筒已切换"); }
        catch (Exception e) { message("手电筒不可用：" + e.getClass().getSimpleName()); }
    }
    MediaSessions mediaSessions() { if (mediaSessions == null) mediaSessions = new MediaSessions(screenContext); return mediaSessions; }
    void openMediaPlayer() {
        android.media.session.MediaController controller = mediaSessions().controller();
        if (controller == null) { message("请先打开播放器"); return; }
        if (display == null || display.getDisplayId() == 0) { message("没有可用外屏"); return; }
        android.app.PendingIntent entry = controller.getSessionActivity();
        if (entry == null) { Intent intent = screenContext.getPackageManager().getLaunchIntentForPackage(controller.getPackageName()); if (intent != null) launch(intent); else message("播放器未提供打开入口"); return; }
        try {
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId());
            if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            entry.send(this, 0, null, null, null, null, options.toBundle()); closePanel();
        } catch (Exception e) { message("播放器入口已失效或被系统限制"); }
    }
    void media(int code) {
        AudioManager audio = getSystemService(AudioManager.class);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)); audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code)); message("媒体按键已发送");
    }
    private void screenshot() {
        if (display == null || screenshotInProgress) return;
        int displayId = display.getDisplayId(); screenshotInProgress = true; removeWindows();
        main.postDelayed(() -> {
            try { takeScreenshot(displayId, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap copy = null, source = null;
                try { source = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace()); if (source != null) copy = source.copy(Bitmap.Config.ARGB_8888, false); }
                catch (RuntimeException ignored) { }
                finally { if (source != null) source.recycle(); result.getHardwareBuffer().close(); }
                screenshotInProgress = false;
                if (instance != CoverService.this) { if (copy != null) copy.recycle(); return; }
                reconcile(true);
                if (copy == null) { message("无法读取外屏截图"); return; }
                Bitmap saved = copy; files.execute(() -> saveScreenshot(saved));
            }
            @Override public void onFailure(int errorCode) { screenshotInProgress = false; if (instance == CoverService.this) { reconcile(true); message("截图被系统拒绝，错误码 " + errorCode); } }
            }); } catch (RuntimeException e) { screenshotInProgress = false; reconcile(true); message("截图不可用：" + e.getClass().getSimpleName()); }
        }, 160);
    }
    private void saveScreenshot(Bitmap bitmap) {
        Uri uri = null;
        try {
            ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, "FlipCover-" + System.currentTimeMillis() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png"); values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FlipCover"); values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            try (OutputStream output = getContentResolver().openOutputStream(uri)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG failed"); }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); getContentResolver().update(uri, values, null, null);
            main.post(() -> message("截图已保存到 Pictures/FlipCover"));
        } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            main.post(() -> message("截图保存失败"));
        } finally { bitmap.recycle(); }
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level != TRIM_MEMORY_UI_HIDDEN && level >= TRIM_MEMORY_RUNNING_LOW) closeTaskGlass();
        if (level != TRIM_MEMORY_UI_HIDDEN && level >= TRIM_MEMORY_RUNNING_LOW && panelGlass != null) { panelMemoryFallback = true; lastGlassDiagnostics = panelGlass.diagnostics(); panelGlass.close(); panelGlass = null; if (panelHost != null) panelHost.setVisibility(View.VISIBLE); refreshBlurState(); }
    }
    @Override public void onLowMemory() { super.onLowMemory(); closeTaskGlass(); }
    @Override protected void dump(java.io.FileDescriptor descriptor, java.io.PrintWriter writer, String[] arguments) { super.dump(descriptor, writer, arguments); writer.println(blurDiagnostics()); }
    @Override public void onDestroy() {
        stopAutomaticRotation();
        if (noRootRotation != null) { noRootRotation.destroy(); noRootRotation = null; }
        if (systemControlGuard != null) systemControlGuard.close();
        instance = null; setStatus("无障碍服务已断开"); main.removeCallbacksAndMessages(null); removeWindows();
        windowPackages.clear(); dockVisibility.reset();
        main.removeCallbacks(windowPoller);
        if (pendingRestoreRunnable != null) main.removeCallbacks(pendingRestoreRunnable);
        CoverApp.bridge(this).removeObserver(bridgeChanged);
        if (prefs != null) prefs.data.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        getSystemService(DisplayManager.class).unregisterDisplayListener(this);
        try { unregisterReceiver(screenReceiver); } catch (RuntimeException ignored) { }
        try { if (batteryReceiver != null) unregisterReceiver(batteryReceiver); } catch (RuntimeException ignored) { }
        try { if (systemStateReceiver != null) unregisterReceiver(systemStateReceiver); } catch (RuntimeException ignored) { }
        if (cameras != null) cameras.unregisterTorchCallback(torchCallback);
        files.shutdown(); super.onDestroy();
    }
}
