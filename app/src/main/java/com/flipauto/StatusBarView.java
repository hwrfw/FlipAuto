package com.flipauto;

import android.Manifest;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 只读状态栏。
 *
 * <p>同一组件同时用于「全局常驻状态栏」与「控制中心内的状态栏」。
 * {@code panelMode} 只改变渲染上下文（背景、前景色、阴影/描边效果），
 * 显示哪些项目、状态取值完全由 {@link Prefs} 与系统状态决定，两处保持一致，
 * 避免出现「全局状态栏显示 NFC 而控制中心不显示」「电量百分比规则不同」等矛盾。
 *
 * <p>订阅与定时器仅在视图挂载期间存活。
 */
final class StatusBarView extends View {
    private final Prefs prefs;
    private final boolean panelMode;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Drawable> notificationIcons = new ArrayList<>();
    private final java.util.Map<Drawable, ChromeShadowDrawable> shadowCache = new java.util.WeakHashMap<>();
    private final List<Cell> cells = new ArrayList<>();
    private final Drawable wifiIcon, alarmIcon, boltIcon, vpnIcon, dndIcon, bluetoothIcon, flightIcon, nfcIcon, hotspotIcon, gpsIcon;
    private final Drawable[] wifiSignalIcons = new Drawable[5];
    private ConnectivityManager connectivity;
    private android.net.wifi.WifiManager wifiManager;
    private SubscriptionManager subscriptions;
    private boolean running, networkRegistered, receiverRegistered, subscriptionsRegistered, wifi, alarm, charging, vpnConnected, dndEnabled;
    private boolean bluetoothOn, bluetoothConnected, airplaneOn, nfcOn, hotspotOn, gpsOn, powerSave;
    private int battery = -1;
    /** WiFi 信号等级：-1=WiFi关闭，0=已开启但无信号/未连接，1-4=信号强度 */
    private int wifiLevel = -1;
    private String speed = "";
    private String clock = "";
    private final SpeedMeter speedMeter = new SpeedMeter();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                battery = level < 0 || scale <= 0 ? -1 : Math.max(0, Math.min(100, Math.round(100f * level / scale)));
                charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            } else if (android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1);
                boolean newBt = state == android.bluetooth.BluetoothAdapter.STATE_ON;
                if (newBt != bluetoothOn) { FlipLog.d("[状态栏] 蓝牙开关: " + bluetoothOn + " → " + newBt); bluetoothOn = newBt; }
            } else if (android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE, -1);
                boolean newConn = state == android.bluetooth.BluetoothAdapter.STATE_CONNECTED;
                if (newConn != bluetoothConnected) { FlipLog.d("[状态栏] 蓝牙连接: " + bluetoothConnected + " → " + newConn); bluetoothConnected = newConn; }
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                boolean newAp = intent.getBooleanExtra("state", false);
                if (newAp != airplaneOn) { FlipLog.d("[状态栏] 飞行模式: " + airplaneOn + " → " + newAp); airplaneOn = newAp; }
            } else if (android.nfc.NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(android.nfc.NfcAdapter.EXTRA_ADAPTER_STATE, -1);
                boolean newNfc = state == android.nfc.NfcAdapter.STATE_ON;
                if (newNfc != nfcOn) { FlipLog.d("[状态栏] NFC: " + nfcOn + " → " + newNfc); nfcOn = newNfc; }
            } else if ("android.net.wifi.WIFI_AP_STATE_CHANGED".equals(action)) {
                int state = intent.getIntExtra("wifi_state", -1);
                boolean newAp2 = state == 13; // WIFI_AP_STATE_ENABLED = 13
                if (newAp2 != hotspotOn) { FlipLog.d("[状态栏] 热点: " + hotspotOn + " → " + newAp2); hotspotOn = newAp2; }
            } else if (android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                // WiFi开关变化，刷新网络状态（WiFi图标/信号/VPN）
                refreshNetwork();
            } else if (android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(action)) {
                // 勿扰模式变化，刷新网络状态（内含勿扰读取）
                refreshNetwork();
            } else if (android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)
                    || android.location.LocationManager.MODE_CHANGED_ACTION.equals(action)) {
                // 省电模式/定位变化，刷新杂项状态（预留扩展）
                refreshMiscStates();
            }
            alarm = context.getSystemService(AlarmManager.class).getNextAlarmClock() != null; updateClock(); invalidate();
        }
    };
    private final ConnectivityManager.NetworkCallback networks = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { refreshNetwork(); }
        @Override public void onLost(Network network) { refreshNetwork(); }
    };
    private final SubscriptionManager.OnSubscriptionsChangedListener subscriptionListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override public void onSubscriptionsChanged() { refreshCells(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateClock();
            boolean showSpeed = prefs.statusItem("speed");
            if (showSpeed) {
                updateSpeed();
            } else if (!speed.isEmpty()) {
                speed = "";
                speedMeter.reset();
            }
            invalidate(); main.postDelayed(this, showSpeed ? 2000 : 60000 - System.currentTimeMillis() % 60000);
        }
    };
    StatusBarView(Context context, Prefs prefs) {
        this(context, prefs, false);
    }
    StatusBarView(Context context, Prefs prefs, boolean panelMode) {
        super(context); this.prefs = prefs; this.panelMode = panelMode; applyAppearance();
        // 状态栏使用原始 density，不随界面缩放（uiScale）变化
        this.rawDensity = context.getResources().getDisplayMetrics().density / prefs.uiScaleFactor();
        wifiIcon = Ui.icon(context, R.drawable.ic_ms_wifi, Ui.TEXT); alarmIcon = Ui.icon(context, R.drawable.ic_ms_alarm, Ui.TEXT); boltIcon = Ui.icon(context, R.drawable.ic_ms_bolt, Ui.TEXT); vpnIcon = Ui.icon(context, R.drawable.ic_status_vpn, Ui.TEXT); dndIcon = Ui.icon(context, R.drawable.ic_ms_do_not_disturb_on, Ui.TEXT);
        bluetoothIcon = Ui.icon(context, R.drawable.ic_ms_bluetooth, Ui.TEXT); flightIcon = Ui.icon(context, R.drawable.ic_ms_flight, Ui.TEXT); nfcIcon = Ui.icon(context, R.drawable.ic_nfc, Ui.TEXT); hotspotIcon = Ui.icon(context, R.drawable.ic_hotspot, Ui.TEXT); gpsIcon = Ui.icon(context, R.drawable.ic_md_location_on, Ui.TEXT);
        wifiSignalIcons[0] = Ui.icon(context, R.drawable.ic_wifi_signal_0, Ui.TEXT);
        wifiSignalIcons[1] = Ui.icon(context, R.drawable.ic_wifi_signal_1, Ui.TEXT);
        wifiSignalIcons[2] = Ui.icon(context, R.drawable.ic_wifi_signal_2, Ui.TEXT);
        wifiSignalIcons[3] = Ui.icon(context, R.drawable.ic_wifi_signal_3, Ui.TEXT);
        wifiSignalIcons[4] = Ui.icon(context, R.drawable.ic_wifi_signal_4, Ui.TEXT);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private final float rawDensity;
    private int dp(float value) { return Math.round(rawDensity * value); }
    int heightPixels() { return Math.max(1, Math.round(dp(20) * scaleFactor())); }
    private float scaleFactor() { return prefs.statusScale() / 100f; }
    boolean showsPercentage() { return prefs.batteryPercent(); }
    /** 前景色：控制中心面板背景固定为深色，需用浅色文字；全局状态栏跟随悬浮栏风格。 */
    private int foreground() { return panelMode ? Ui.TEXT : Ui.chromeColor(prefs); }
    private void applyAppearance() { setBackgroundColor(!panelMode && prefs.chromeStyle().equals("black") ? Ui.BACKGROUND : android.graphics.Color.TRANSPARENT); setLayerType(LAYER_TYPE_SOFTWARE, null); }
    /**
     * 刷新状态栏网速文本。
     *
     * <p>默认用系统累计收发总量算速率，任何网络下都有读数。开 VPN 时同一份流量会在
     * 隧道接口与底层接口各计一次，读数约为实际的两倍，因此 VPN 连接期间优先采用代理应用
     * 常驻通知里自己刷新的上下行速率；没有这样的通知时仍用系统总量，不会显示成 0K/s。
     */
    private void updateSpeed() {
        if (prefs.smartSpeed()) {
            java.util.Set<String> apps = prefs.speedApps();
            // 用户指定了应用就按指定来，没指定时只在 VPN 连接期间自动识别
            if (vpnConnected || !apps.isEmpty()) {
                double notified = SpeedNotification.fromNotifications(CoverNotifications.snapshot(), System.currentTimeMillis(), apps);
                if (notified >= 0) {
                    // 通知里的数字与系统总量不同源，切回总量时重建基线，避免算出跨口径的差值
                    speedMeter.reset();
                    speed = SpeedMeter.format(notified);
                    return;
                }
            }
        }
        double rate = speedMeter.sample(systemTraffic(), SystemClock.elapsedRealtime());
        speed = rate < 0 ? "" : SpeedMeter.format(rate);
    }

    /**
     * 系统统计的累计收发字节数（全局，含移动数据与 WiFi）。
     *
     * <p>只读总量：Android 10 起按接口名取值的 {@code getRxBytes(String)} 一律返回
     * 不支持，逐接口统计拿不到数据，只能退回总量。
     *
     * @return 累计字节数；系统未提供统计时返回 -1
     */
    private long systemTraffic() {
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        return rx < 0 || tx < 0 ? -1 : rx + tx;
    }
    private void updateClock() { clock = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date()); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); running = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED); filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED); filter.addAction(Intent.ACTION_TIME_CHANGED); filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(android.nfc.NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        filter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        // 补充：WiFi开关变化、勿扰模式变化、省电模式变化、定位模式变化
        filter.addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        filter.addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(android.location.LocationManager.MODE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED); else getContext().registerReceiver(receiver, filter);
        receiverRegistered = true; connectivity = getContext().getSystemService(ConnectivityManager.class);
        wifiManager = (android.net.wifi.WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try { connectivity.registerDefaultNetworkCallback(networks, main); networkRegistered = true; } catch (RuntimeException ignored) { }
        refreshNetwork(); refreshNotifications(); refreshCells(); refreshMiscStates();
        if (Build.VERSION.SDK_INT >= 31 && prefs.statusItem("cellular") && getContext().checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            subscriptions = getContext().getSystemService(SubscriptionManager.class);
            try { subscriptions.addOnSubscriptionsChangedListener(getContext().getMainExecutor(), subscriptionListener); subscriptionsRegistered = true; } catch (RuntimeException ignored) { }
        }
        alarm = getContext().getSystemService(AlarmManager.class).getNextAlarmClock() != null; main.post(tick);
    }
    /** 初始化蓝牙/飞行/NFC/热点等杂项状态 */
    private void refreshMiscStates() {
        try { android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter(); bluetoothOn = bt != null && bt.isEnabled(); } catch (Throwable ignored) { }
        airplaneOn = android.provider.Settings.Global.getInt(getContext().getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        // NFC：优先用 NfcAdapter，回退用 Settings.Global（部分设备 isEnabled 不可靠）
        try {
            android.nfc.NfcAdapter nfc = android.nfc.NfcAdapter.getDefaultAdapter(getContext());
            if (nfc != null) {
                nfcOn = nfc.isEnabled();
                FlipLog.d("[状态栏] NFC 适配器状态: isEnabled=" + nfcOn);
            } else {
                nfcOn = android.provider.Settings.Global.getInt(getContext().getContentResolver(), "nfc_on", 0) != 0;
                FlipLog.d("[状态栏] NFC 适配器为 null，回退 Settings.Global: nfc_on=" + nfcOn);
            }
        } catch (Throwable e) {
            nfcOn = android.provider.Settings.Global.getInt(getContext().getContentResolver(), "nfc_on", 0) != 0;
            FlipLog.d("[状态栏] NFC 读取异常(" + e.getClass().getSimpleName() + ")，回退 Settings.Global: " + nfcOn);
        }
        // 热点：尝试多种反射方式（isWifiApEnabled / getWifiApState）
        try {
            if (wifiManager != null) {
                try {
                    java.lang.reflect.Method m = wifiManager.getClass().getMethod("isWifiApEnabled");
                    hotspotOn = (Boolean) m.invoke(wifiManager);
                    FlipLog.d("[状态栏] 热点 isWifiApEnabled=" + hotspotOn);
                } catch (Throwable e1) {
                    try {
                        java.lang.reflect.Method m2 = wifiManager.getClass().getMethod("getWifiApState");
                        int state = (Integer) m2.invoke(wifiManager);
                        hotspotOn = state == 13; // WIFI_AP_STATE_ENABLED
                        FlipLog.d("[状态栏] 热点 getWifiApState=" + state + " → enabled=" + hotspotOn);
                    } catch (Throwable e2) {
                        hotspotOn = false;
                        FlipLog.d("[状态栏] 热点读取全部失败: " + e1.getClass().getSimpleName() + " / " + e2.getClass().getSimpleName());
                    }
                }
            }
        } catch (Throwable ignored) { }
        // GPS/定位状态：优先用 Settings.Secure.LOCATION_MODE（更可靠），回退用 LocationManager
        try {
            int locationMode = android.provider.Settings.Secure.getInt(getContext().getContentResolver(), android.provider.Settings.Secure.LOCATION_MODE, 0);
            gpsOn = locationMode != android.provider.Settings.Secure.LOCATION_MODE_OFF;
            FlipLog.d("[状态栏] GPS Settings.Secure: location_mode=" + locationMode + " → enabled=" + gpsOn);
        } catch (Throwable e) {
            try {
                android.location.LocationManager lm = (android.location.LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
                gpsOn = lm != null && lm.isLocationEnabled();
                FlipLog.d("[状态栏] GPS 回退 LocationManager: isLocationEnabled=" + gpsOn);
            } catch (Throwable e2) { gpsOn = false; }
        }
        FlipLog.d("[状态栏] 初始化状态: bt=" + bluetoothOn + ", airplane=" + airplaneOn + ", nfc=" + nfcOn + ", hotspot=" + hotspotOn + ", gps=" + gpsOn);
        // 省电模式状态：与 NoRootOps.state("powersave") 同源（PowerManager.isPowerSaveMode），
        // 避免 Settings.Global.low_power 与系统真实省电状态不一致导致状态栏与控制中心显示矛盾。
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            powerSave = pm != null && pm.isPowerSaveMode();
        } catch (Throwable ignored) { powerSave = false; }
    }
    private void refreshNetwork() {
        if (!running) return;
        try { NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork()); wifi = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI); vpnConnected = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN); }
        catch (RuntimeException ignored) { wifi = false; vpnConnected = false; }
        // 免打扰状态
        try { android.app.NotificationManager nm = (android.app.NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE); dndEnabled = nm != null && nm.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL; } catch (Throwable ignored) { dndEnabled = false; }
        // 真实 WiFi 状态：开关 + 信号强度
        try {
            if (wifiManager == null || !wifiManager.isWifiEnabled()) wifiLevel = -1;
            else if (!wifi) wifiLevel = 0; // WiFi 已开启但未连接
            else {
                android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
                int rssi = info == null ? -100 : info.getRssi();
                wifiLevel = android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5); // 0-4
            }
        } catch (Throwable ignored) { wifiLevel = wifi ? 2 : -1; }
        invalidate();
    }
    void refreshNotifications() {
        notificationIcons.clear();
        if (!prefs.statusItem("notifications")) { invalidate(); return; }
        HashSet<String> packages = new HashSet<>();
        for (android.service.notification.StatusBarNotification item : CoverNotifications.snapshot()) {
            if (!packages.add(item.getPackageName()) || notificationIcons.size() >= 8) continue;
            try {
                android.graphics.drawable.Icon small = item.getNotification().getSmallIcon();
                Drawable icon;
                boolean isMaskIcon; // 透明底 mask 图标需要填充白色，图片图标不修改
                if (small != null) {
                    icon = small.loadDrawable(getContext());
                    // TYPE_RESOURCE=2（通知 smallIcon，通常是 mask）；TYPE_BITMAP=1 / ADAPTIVE_BITMAP=5 是图片图标
                    int type = small.getType();
                    isMaskIcon = (type == 2 || type == 3 || type == 4); // RESOURCE/DATA/URI 视为 mask，填充白色
                } else {
                    // 回退到应用图标（图片图标），不做修改
                    icon = getContext().getPackageManager().getApplicationIcon(item.getPackageName());
                    isMaskIcon = false;
                }
                if (icon != null) {
                    icon = icon.mutate();
                    if (isMaskIcon) icon.setTint(foreground()); // 透明底 mask 默认填充白色
                    // 支持动态图标（如下载进度动画）
                    if (icon instanceof android.graphics.drawable.Animatable) ((android.graphics.drawable.Animatable) icon).start();
                    notificationIcons.add(!panelMode && prefs.chromeStyle().equals("contrast") ? new ContrastDrawable(icon, dp(.7f)) : icon);
                }
            } catch (Exception ignored) { }
        }
        invalidate();
    }
    void updateOptions() { applyAppearance(); invalidate(); if (running) { refreshNotifications(); refreshCells(); main.removeCallbacks(tick); main.post(tick); } }
    private void refreshCells() {
        if (!running || Build.VERSION.SDK_INT < 31) return;
        for (Cell cell : cells) cell.stop(); cells.clear();
        if (!prefs.statusItem("cellular") || getContext().checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) { invalidate(); return; }
        try {
            List<SubscriptionInfo> active = getContext().getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
            if (active != null) for (SubscriptionInfo subscription : active) {
                if (cells.size() >= 2) break;
                Cell cell = new Cell(getContext().getSystemService(TelephonyManager.class).createForSubscriptionId(subscription.getSubscriptionId())); cells.add(cell); cell.start();
            }
        } catch (RuntimeException ignored) { }
        invalidate();
    }
    @androidx.annotation.RequiresApi(31)
    private final class Cell extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener, TelephonyCallback.DisplayInfoListener {
        final TelephonyManager phone; int level = -1; String type = ""; boolean registered;
        Cell(TelephonyManager phone) { this.phone = phone; }
        void start() { try { phone.registerTelephonyCallback(getContext().getMainExecutor(), this); registered = true; } catch (RuntimeException ignored) { } }
        void stop() { if (registered) try { phone.unregisterTelephonyCallback(this); } catch (RuntimeException ignored) { } }
        @Override public void onSignalStrengthsChanged(SignalStrength signal) { if (running) { level = signal.getLevel(); invalidate(); } }
        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo information) {
            if (!running) return;
            int override = information.getOverrideNetworkType(), network = information.getNetworkType();
            if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED || network == TelephonyManager.NETWORK_TYPE_NR) type = "5G";
            else if (network == TelephonyManager.NETWORK_TYPE_LTE) type = "4G";
            else if (network == TelephonyManager.NETWORK_TYPE_UMTS || network == TelephonyManager.NETWORK_TYPE_HSDPA || network == TelephonyManager.NETWORK_TYPE_HSPA || network == TelephonyManager.NETWORK_TYPE_HSPAP) type = "3G";
            else if (network == TelephonyManager.NETWORK_TYPE_GPRS || network == TelephonyManager.NETWORK_TYPE_EDGE || network == TelephonyManager.NETWORK_TYPE_GSM) type = "2G";
            else type = "";
            invalidate();
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int leftInset = dp(prefs.statusSafeLeft()), rightInset = dp(prefs.statusSafeRight());
        if (getWidth() <= leftInset + rightInset) return;
        int save = canvas.save(); canvas.clipRect(leftInset, 0, getWidth() - rightInset, getHeight()); canvas.translate(leftInset, 0);
        float scale = scaleFactor(); canvas.scale(scale, scale);
        float width = (getWidth() - leftInset - rightInset) / scale, height = getHeight() / scale;
        paint.setStyle(Paint.Style.FILL); paint.setColor(foreground()); paint.setTextSize(dp(16));
        paint.clearShadowLayer();
        float baseline = height / 2f - (paint.ascent() + paint.descent()) / 2, right = width - dp(6), x = dp(6);
        int icon = dp(16), gap = dp(5), middle = Math.round(height / 2);
        if (prefs.statusItem("battery")) { right -= batteryWidth(); drawBattery(canvas, right, baseline, middle, showsPercentage()); right -= gap; }
        if (prefs.statusItem("wifi") && wifiLevel >= 0 && right - dp(16) >= x) { right -= dp(16); drawWifiSignal(canvas, right, middle, wifiLevel); right -= gap; }
        if (prefs.statusItem("cellular") && Build.VERSION.SDK_INT >= 31) for (Cell cell : cells) {
            if (cell.level < 0 || right - dp(25) < x) continue;
            right -= dp(20); float unit = dp(3);
            for (int i = 0; i < 4; i++) { paint.setColor(i < cell.level ? foreground() : Ui.MUTED); canvas.drawRect(right + i * unit, middle + dp(6) - (i + 1) * unit, right + i * unit + unit * .65f, middle + dp(6), paint); }
            paint.setColor(foreground()); paint.setTextSize(dp(6)); text(canvas, cell.type, right, middle - dp(6)); paint.setTextSize(dp(16)); right -= gap;
        }
        if (alarm && prefs.statusItem("alarm") && right - icon > x) { right -= icon; drawIcon(canvas, alarmIcon, right, middle, icon); right -= gap; }
        if (vpnConnected && prefs.statusItem("vpn") && right - icon > x) { right -= icon; drawIcon(canvas, vpnIcon, right, middle, icon); right -= gap; }
        if (dndEnabled && prefs.statusItem("dnd") && right - icon > x) { right -= icon; drawIcon(canvas, dndIcon, right, middle, icon); right -= gap; }
        if (bluetoothOn && prefs.statusItem("bluetooth") && right - icon > x) { right -= icon; drawIcon(canvas, bluetoothIcon, right, middle, icon); right -= gap; }
        if (airplaneOn && prefs.statusItem("airplane") && right - icon > x) { right -= icon; drawIcon(canvas, flightIcon, right, middle, icon); right -= gap; }
        if (nfcOn && prefs.statusItem("nfc") && right - icon > x) { right -= icon; drawIcon(canvas, nfcIcon, right, middle, icon); right -= gap; }
        if (hotspotOn && prefs.statusItem("hotspot") && right - icon > x) { right -= icon; drawIcon(canvas, hotspotIcon, right, middle, icon); right -= gap; }
        if (gpsOn && prefs.statusItem("gps") && right - icon > x) { right -= icon; drawIcon(canvas, gpsIcon, right, middle, icon); right -= gap; }
        if (prefs.statusItem("speed") && !speed.isEmpty()) { paint.setTextSize(dp(16)); float length = paint.measureText(speed); if (right - length > x) { right -= length; text(canvas, speed, right, baseline); right -= gap; } paint.setTextSize(dp(16)); }
        if (prefs.statusItem("time") && x + paint.measureText(clock) <= right) { text(canvas, clock, x, baseline); x += paint.measureText(clock) + gap * 2; }
        // 通知图标：用户自定义最大数量，超出部分用三个点表示
        int maxNotif = prefs.statusNotificationCount();
        int notifCount = notificationIcons.size();
        int showCount = Math.min(notifCount, maxNotif);
        boolean overflow = notifCount > maxNotif;
        if (overflow) showCount = maxNotif - 1; // 留一个位置给三个点
        for (int i = 0; i < showCount; i++) { if (x + icon > right) break; drawIcon(canvas, notificationIcons.get(i), x, middle, icon); x += icon + gap; }
        if (overflow && x + dp(12) <= right) { paint.setTextSize(dp(9)); paint.setColor(foreground()); canvas.drawText("…", x, baseline, paint); paint.setTextSize(dp(16)); }
        canvas.restoreToCount(save);
    }
    private void text(Canvas canvas, String value, float x, float y) {
        if (!panelMode && prefs.chromeStyle().equals("contrast")) { paint.setColor(android.graphics.Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); canvas.drawText(value, x, y, paint); }
        paint.setColor(foreground()); paint.setStyle(Paint.Style.FILL); canvas.drawText(value, x, y, paint);
    }
    private String percent() { return battery < 0 ? "—%" : battery + "%"; }
    /** 绘制真实 WiFi 信号强度图标：level 0=已开启无信号，1-4=信号格数 */
    private void drawWifiSignal(Canvas canvas, float x, int middle, int level) {
        // 官方Android系统WiFi图标（ic_wifi_signal_0~4），通过 drawIcon 适配描边/阴影风格
        int size = dp(16);
        Drawable icon = wifiSignalIcons[Math.max(0, Math.min(4, level))];
        drawIcon(canvas, icon, x, middle, size);
    }
    private float batteryWidth() {
        boolean inside = prefs.batteryPercentInside();
        int baseWidth = dp(30);
        return baseWidth + (!inside && showsPercentage() ? dp(5) + paint.measureText(percent()) : 0) + (charging ? dp(21) : 0) + (powerSave ? dp(20) : 0);
    }
    private float drawBattery(Canvas canvas, float x, float baseline, int middle, boolean percent) {
        boolean inside = prefs.batteryPercentInside();
        int width = dp(26);
        int height = dp(13);
        float line = Math.max(1, dp(.7f));
        // 省电模式：叶子显示在电池左边，16dp 与其他图标一致
        if (powerSave) {
            Drawable leaf = getContext().getDrawable(R.drawable.ic_battery_saver_leaf);
            if (leaf != null) {
                leaf.setTint(0xFF4CAF50); // 绿色叶子
                drawIcon(canvas, leaf, x, middle, dp(16));
                x += dp(16) + dp(4);
            }
        }
        if (!panelMode && prefs.chromeStyle().equals("contrast")) { paint.setStyle(Paint.Style.FILL); paint.setColor(0xDD000000); canvas.drawRoundRect(x - line, middle - height / 2f - line, x + width, middle + height / 2f + line, line * 2, line * 2, paint); paint.setColor(foreground()); }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(line); canvas.drawRoundRect(x, middle - height / 2f, x + width - line * 2, middle + height / 2f, line, line, paint);
        paint.setStyle(Paint.Style.FILL); canvas.drawRect(x + width - line, middle - height / 4f, x + width + line, middle + height / 4f, paint);
        if (battery >= 0) canvas.drawRect(x + line * 2, middle - height / 2f + line * 2, x + line * 2 + (width - line * 6) * battery / 100f, middle + height / 2f - line * 2, paint);
        // 电源内显示电量：智能反色 + 描边突出
        if (inside && battery >= 0) {
            paint.setTextSize(dp(9));
            String num = String.valueOf(battery);
            float textWidth = paint.measureText(num);
            float textX = x + (width - line * 2) / 2f - textWidth / 2f + line;
            float textY = middle - (paint.ascent() + paint.descent()) / 2f;
            float textCenter = x + (width - line * 2) / 2f + line;
            float fillRight = x + line * 2 + (width - line * 6) * battery / 100f;
            boolean textOnFill = textCenter < fillRight;
            int fillColor, strokeColor;
            if (battery <= 20) {
                fillColor = 0xFFFF4444; strokeColor = 0xFF000000;
            } else if (textOnFill) {
                fillColor = 0xFF000000; strokeColor = 0xFFFFFFFF;
            } else {
                fillColor = foreground(); strokeColor = 0xFF000000;
            }
            // 描边轮廓
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.2f)); paint.setColor(strokeColor);
            canvas.drawText(num, textX, textY, paint);
            // 填充
            paint.setStyle(Paint.Style.FILL); paint.setColor(fillColor);
            canvas.drawText(num, textX, textY, paint);
            paint.setTextSize(dp(16)); paint.setColor(foreground());
        }
        x += width + dp(2);
        if (percent && !inside) { x += dp(5); text(canvas, percent(), x, baseline); x += paint.measureText(percent()); }
        // 充电闪电：16dp 与其他图标一致
        if (charging) { x += dp(5); drawIcon(canvas, boltIcon, x, middle, dp(16)); x += dp(16); }
        return x;
    }
    private void drawIcon(Canvas canvas, Drawable drawable, float x, int middle, int size) {
        drawable.setBounds(Math.round(x), middle - size / 2, Math.round(x) + size, middle + size / 2);
        if (drawable instanceof ContrastDrawable) { drawable.draw(canvas); return; }
        // 非透明图标（如彩色通知图标）不着色，保持原始颜色
        boolean isOpaque = drawable.getOpacity() == android.graphics.PixelFormat.OPAQUE;
        if (isOpaque) { drawable.setTintList(null); drawable.draw(canvas); return; }
        if (!panelMode && prefs.chromeStyle().equals("shadow")) {
            ChromeShadowDrawable shadow = shadowCache.get(drawable);
            if (shadow == null) { shadow = ChromeShadowDrawable.forIcon(drawable, getResources().getDisplayMetrics().density); shadowCache.put(drawable, shadow); }
            shadow.setBounds(drawable.getBounds());
            shadow.setTint(foreground());
            shadow.draw(canvas);
            return;
        }
        if (!panelMode && prefs.chromeStyle().equals("contrast")) { drawable.setTint(android.graphics.Color.BLACK); float r = dp(.7f); for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) { if (a == 0 && b == 0) continue; int save = canvas.save(); canvas.translate(a * r, b * r); drawable.draw(canvas); canvas.restoreToCount(save); } }
        drawable.setTint(foreground()); drawable.draw(canvas);
    }
    @Override protected void onDetachedFromWindow() {
        running = false; main.removeCallbacksAndMessages(null);
        if (receiverRegistered) { getContext().unregisterReceiver(receiver); receiverRegistered = false; }
        if (networkRegistered) { connectivity.unregisterNetworkCallback(networks); networkRegistered = false; }
        if (Build.VERSION.SDK_INT >= 31) { for (Cell cell : cells) cell.stop(); cells.clear(); }
        if (subscriptionsRegistered) { subscriptions.removeOnSubscriptionsChangedListener(subscriptionListener); subscriptionsRegistered = false; }
        notificationIcons.clear(); super.onDetachedFromWindow();
    }
}
