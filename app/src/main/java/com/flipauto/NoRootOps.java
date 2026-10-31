package com.flipauto;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.location.LocationManager;

/**
 * 免 Shizuku 的系统操作层：
 * 能用公开 API 直接切换的开关直接切换；受系统限制的开关（Android 10+ Wi-Fi、
 * 移动数据、飞行模式、NFC、热点、定位、省电模式）跳转对应系统设置页，
 * 其中省电模式直接跳转三星省电设置页（{@link #openSamsungPowerSaving}）。
 * 状态读取尽量走公开 API，读不到返回 -1（未知）。
 * 开关状态统一由 {@link #state(Context, String)} 提供，控制中心磁贴、状态栏
 * 与详情页共用同一来源（省电模式取 PowerManager.isPowerSaveMode）。
 */
final class NoRootOps {
    private NoRootOps() { }

    /** 读取整机开关状态：1 = 开，0 = 关，-1 = 未知/无权限。 */
    static int state(Context context, String id) {
        try {
            switch (id) {
                case "wifi": {
                    WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wifi == null) return -1;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        return wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLED ? 1 : wifi.getWifiState() == WifiManager.WIFI_STATE_DISABLED ? 0 : -1;
                    }
                    return wifi.isWifiEnabled() ? 1 : 0;
                }
                case "bluetooth": {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null) return -1;
                    try { return adapter.isEnabled() ? 1 : 0; }
                    catch (SecurityException ignored) { return -1; }
                }
                case "airplane":
                    return Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, -1);
                case "data": {
                    try {
                        android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                        if (tm == null) return -1;
                        return tm.isDataEnabled() ? 1 : 0;
                    } catch (Throwable ignored) { return -1; }
                }
                case "dnd": {
                    android.app.NotificationManager nm = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm == null) return -1;
                    int filter = nm.getCurrentInterruptionFilter();
                    return filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL ? 0 : 1;
                }
                case "vpn": {
                    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm == null) return -1;
                    try { return cm.getNetworkCapabilities(cm.getActiveNetwork()) != null && cm.getNetworkCapabilities(cm.getActiveNetwork()).hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? 1 : 0; }
                    catch (Throwable ignored) { return 0; }
                }
                case "nfc": {
                    NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
                    if (adapter == null) {
                        return Settings.Global.getInt(context.getContentResolver(), "nfc_on", -1);
                    }
                    try { return adapter.isEnabled() ? 1 : 0; }
                    catch (Throwable ignored) { return Settings.Global.getInt(context.getContentResolver(), "nfc_on", -1); }
                }
                case "hotspot": {
                    WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wifi == null) return -1;
                    try {
                        java.lang.reflect.Method method = wifi.getClass().getMethod("isWifiApEnabled");
                        return Boolean.TRUE.equals(method.invoke(wifi)) ? 1 : 0;
                    } catch (Throwable ignored) {
                        try {
                            java.lang.reflect.Method m2 = wifi.getClass().getMethod("getWifiApState");
                            int state = (Integer) m2.invoke(wifi);
                            return state == 13 ? 1 : 0;
                        } catch (Throwable ignored2) { return -1; }
                    }
                }
                case "torch": {
                    CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                    if (cm == null) return -1;
                    try {
                        String[] ids = cm.getCameraIdList();
                        for (String cid : ids) {
                            if (cm.getCameraCharacteristics(cid).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
                                return torchEnabled ? 1 : 0;
                            }
                        }
                        return -1;
                    } catch (Throwable ignored) { return -1; }
                }
                case "rotation":
                    return Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, -1);
                case "brightness":
                    return Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
                case "volume": {
                    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    if (am == null) return -1;
                    return am.getStreamVolume(AudioManager.STREAM_MUSIC);
                }
                case "timeout":
                    return Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, -1);
                case "powersave": {
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (pm == null) return -1;
                    try { return pm.isPowerSaveMode() ? 1 : 0; } catch (Throwable ignored) { return -1; }
                }
                case "location": {
                    // 与状态栏 StatusBarView 判断同源：优先 Settings.Secure.LOCATION_MODE，回退 LocationManager
                    try {
                        int locationMode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE, 0);
                        return locationMode != Settings.Secure.LOCATION_MODE_OFF ? 1 : 0;
                    } catch (Throwable ignored) {
                        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                        if (lm == null) return -1;
                        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled() ? 1 : 0;
                        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? 1 : 0;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    private static boolean torchEnabled = false;
    private static String torchCameraId = null;

    /**
     * 免 Shizuku 切换开关。返回 true 表示切换请求已处理（含已跳转设置页）；
     * 返回 false 表示无法处理（请调用方提示）。
     */
    static boolean toggle(Context context, String id, boolean enable) {
        try {
            switch (id) {
                case "wifi": {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if (wifi != null) { wifi.setWifiEnabled(enable); return true; }
                    }
                    openSettings(context, Settings.Panel.ACTION_WIFI);
                    return true;
                }
                case "bluetooth": {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) {
                        try {
                            if (enable) adapter.enable(); else adapter.disable();
                            return true;
                        } catch (SecurityException ignored) { }
                    }
                    openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS);
                    return true;
                }
                case "data":
                    openSettings(context, Settings.ACTION_DATA_USAGE_SETTINGS);
                    return true;
                case "airplane":
                    openSettings(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS);
                    return true;
                case "dnd":
                    openSettings(context, Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS);
                    return true;
                case "vpn":
                    openSettings(context, Settings.ACTION_VPN_SETTINGS);
                    return true;
                case "nfc":
                    openSettings(context, Settings.ACTION_NFC_SETTINGS);
                    return true;
                case "hotspot": {
                    try {
                        Intent intent = new Intent();
                        intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Throwable e) {
                        openSettings(context, Settings.ACTION_WIRELESS_SETTINGS);
                    }
                    return true;
                }
                case "torch": {
                    CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                    if (cm == null) return false;
                    try {
                        if (torchCameraId == null) {
                            String[] ids = cm.getCameraIdList();
                            for (String cid : ids) {
                                if (cm.getCameraCharacteristics(cid).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
                                    torchCameraId = cid;
                                    break;
                                }
                            }
                        }
                        if (torchCameraId != null) {
                            cm.setTorchMode(torchCameraId, enable);
                            torchEnabled = enable;
                            return true;
                        }
                    } catch (Throwable ignored) { }
                    return false;
                }
                case "rotation":
                    if (canWriteSettings(context)) {
                        Settings.System.putInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, enable ? 1 : 0);
                        return true;
                    }
                    openSettings(context, Settings.ACTION_DISPLAY_SETTINGS);
                    return true;
                case "powersave":
                    // 免 root 无法直接切换省电模式：直接跳转三星省电模式设置页由用户手动操作。
                    return openSamsungPowerSaving(context);
                case "location":
                    openSettings(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** 免 root 调节亮度（0-255），需要 WRITE_SETTINGS 权限。 */
    static boolean setBrightness(Context context, int value) {
        if (!canWriteSettings(context)) return false;
        try {
            int clamped = Math.max(0, Math.min(255, value));
            float brightnessFloat = clamped / 255.0f;
            // 先关闭自动亮度，确保手动设置生效
            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            // 写入亮度值（内屏）
            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, clamped);
            // 通知系统亮度变化，触发立即生效（安卓高版本需要显式通知）
            try {
                context.getContentResolver().notifyChange(android.net.Uri.parse("settings://system/screen_brightness"), null);
                context.getContentResolver().notifyChange(android.net.Uri.parse("settings://system/screen_brightness_mode"), null);
            } catch (Throwable ignored) { }
            // 尝试通过反射调用 DisplayManager 的隐藏 API setBrightness，针对外屏/多显示设备
            try {
                Object displayManager = context.getSystemService(Context.DISPLAY_SERVICE);
                if (displayManager != null) {
                    // 获取所有 display
                    java.lang.reflect.Method getDisplays = displayManager.getClass().getMethod("getDisplays");
                    Object[] displays = (Object[]) getDisplays.invoke(displayManager);
                    if (displays != null) {
                        java.lang.reflect.Method getDisplayId = null;
                        java.lang.reflect.Method setBrightness = null;
                        try { setBrightness = displayManager.getClass().getMethod("setBrightness", int.class, float.class); } catch (Throwable ignored) { }
                        for (Object display : displays) {
                            try {
                                if (getDisplayId == null) getDisplayId = display.getClass().getMethod("getDisplayId");
                                int displayId = (Integer) getDisplayId.invoke(display);
                                // 对所有 display 设置亮度（包括外屏）
                                if (setBrightness != null) {
                                    setBrightness.invoke(displayManager, displayId, brightnessFloat);
                                } else {
                                    // 备选：通过 Display.setBrightness(float)
                                    try {
                                        java.lang.reflect.Method displaySetBrightness = display.getClass().getMethod("setBrightness", float.class);
                                        displaySetBrightness.invoke(display, brightnessFloat);
                                    } catch (Throwable ignored) { }
                                }
                            } catch (Throwable ignored) { }
                        }
                    }
                }
            } catch (Throwable ignored) { }
            // 尝试通过反射调用 PowerManager 的隐藏 API setBrightness
            try {
                Object powerManager = context.getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    java.lang.reflect.Method setBrightness = powerManager.getClass().getMethod("setBrightness", int.class);
                    setBrightness.invoke(powerManager, clamped);
                }
            } catch (Throwable ignored) { }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** 免 root 调节音量（stream 类型，0-max）。 */
    static boolean setVolume(Context context, int stream, int value) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            int max = am.getStreamMaxVolume(stream);
            am.setStreamVolume(stream, Math.max(0, Math.min(max, value)), 0);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** 免 root 调节屏幕超时（毫秒）。 */
    static boolean setTimeout(Context context, int millis) {
        if (!canWriteSettings(context)) return false;
        try {
            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, millis);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** 检查是否有 WRITE_SETTINGS 权限。 */
    static boolean canWriteSettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.System.canWrite(context);
        }
        return true;
    }

    /** 打开系统设置页（新任务，可被系统拦截时静默失败）。 */
    static boolean openSettings(Context context, String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 三星 One UI 各版本的省电模式设置页，按新版本在前依次尝试。 */
    private record SettingsPage(String packageName, String className) { }

    /**
     * 三星未公开省电模式入口的稳定标识，故以组件探测代替硬编码单一目标；
     * 全部不可用时回退到系统标准省电设置入口。
     */
    private static final SettingsPage[] SAMSUNG_POWER_SAVING_PAGES = {
            new SettingsPage("com.android.settings", "com.samsung.android.settings.powersaving.PowerSavingSettingsActivity"),
            new SettingsPage("com.android.settings", "com.samsung.android.settings.battery.BatterySaverSettingsActivity"),
            new SettingsPage("com.android.settings", "com.samsung.android.settings.Settings$PowerSavingSettingsActivity"),
            new SettingsPage("com.android.settings", "com.android.settings.Settings$PowerSavingSettingsActivity")
    };

    /** 直接打开三星省电模式设置页。 */
    private static boolean openSamsungPowerSaving(Context context) {
        for (SettingsPage page : SAMSUNG_POWER_SAVING_PAGES) {
            if (openComponent(context, page.packageName(), page.className())) return true;
        }
        return openSettings(context, Settings.ACTION_BATTERY_SAVER_SETTINGS);
    }

    /** 以显式组件名打开页面，组件不存在或不可启动时返回 false。 */
    private static boolean openComponent(Context context, String packageName, String className) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new android.content.ComponentName(packageName, className));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 免 Shizuku 状态轮询辅助：记住一次读取时间，避免频繁查询。 */
    private static long lastStateRead = 0;
    static boolean stateReadDue() { long now = SystemClock.elapsedRealtime(); if (now - lastStateRead < 800) return false; lastStateRead = now; return true; }
}
