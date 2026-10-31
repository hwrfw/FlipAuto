package com.flipauto;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

/**
 * 权限引导：必须权限（悬浮窗/无障碍/通知监听）全部授权后进入；
 * 可选权限（电池优化/修改系统设置/相机/应用列表）可稍后授权。
 */
public final class PermissionActivity extends Activity {
    private TextView overlayStatus, accessibilityStatus, notificationStatus;
    private TextView batteryStatus, writeSettingsStatus, cameraStatus, appListStatus;
    private Button btnOverlay, btnAccessibility, btnNotification;
    private Button btnBattery, btnWriteSettings, btnCamera, btnAppList, btnEnter;

    @Override protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LanguageUtil.applyLanguage(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission);
        overlayStatus = findViewById(R.id.overlayStatus);
        accessibilityStatus = findViewById(R.id.accessibilityStatus);
        notificationStatus = findViewById(R.id.notificationStatus);
        batteryStatus = findViewById(R.id.batteryStatus);
        writeSettingsStatus = findViewById(R.id.writeSettingsStatus);
        cameraStatus = findViewById(R.id.cameraStatus);
        appListStatus = findViewById(R.id.appListStatus);
        btnOverlay = findViewById(R.id.btnOverlay);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnNotification = findViewById(R.id.btnNotification);
        btnBattery = findViewById(R.id.btnBattery);
        btnWriteSettings = findViewById(R.id.btnWriteSettings);
        btnCamera = findViewById(R.id.btnCamera);
        btnAppList = findViewById(R.id.btnAppList);
        btnEnter = findViewById(R.id.btnEnter);
        btnOverlay.setOnClickListener(v -> openOverlaySettings());
        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        btnNotification.setOnClickListener(v -> openNotificationAccessSettings());
        btnBattery.setOnClickListener(v -> openBatterySettings());
        btnWriteSettings.setOnClickListener(v -> openWriteSettings());
        btnCamera.setOnClickListener(v -> requestCameraPermission());
        btnAppList.setOnClickListener(v -> openAppListSettings());
        btnEnter.setOnClickListener(v -> {
            startActivity(new Intent(this, LauncherSetupActivity.class));
            finish();
        });
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        refresh();
    }

    private void refresh() {
        // 必须权限
        boolean overlay = Settings.canDrawOverlays(this);
        setStatus(btnOverlay, overlayStatus, overlay, R.string.permission_overlay_granted, R.string.permission_overlay_denied, true);
        boolean accessibility = isAccessibilityEnabled();
        setStatus(btnAccessibility, accessibilityStatus, accessibility, R.string.permission_accessibility_granted, R.string.permission_accessibility_denied, true);
        boolean notification = isNotificationAccessEnabled();
        setStatus(btnNotification, notificationStatus, notification, R.string.permission_notification_granted, R.string.permission_notification_denied, true);

        // 可选权限
        boolean battery = isIgnoringBatteryOptimizations();
        setStatus(btnBattery, batteryStatus, battery, R.string.permission_battery_granted, R.string.permission_battery_denied, false);
        boolean writeSettings = canWriteSettings();
        setOptionalStatus(btnWriteSettings, writeSettingsStatus, writeSettings, "已授权", "未授权");
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        setOptionalStatus(btnCamera, cameraStatus, camera, "已授权", "未授权");
        boolean appList = hasAppListPermission();
        setStatus(btnAppList, appListStatus, appList, R.string.permission_app_list_granted, R.string.permission_app_list_denied, false);

        // 必须权限全部授权才能进入
        boolean allRequired = overlay && accessibility && notification;
        btnEnter.setEnabled(allRequired);
        btnEnter.setText(allRequired ? R.string.permission_enter_ok : R.string.permission_enter_pending);
        btnEnter.setBackgroundTintList(android.content.res.ColorStateList.valueOf(allRequired ? 0xFF4CAF50 : 0xFF9E9E9E));
    }

    private void setStatus(Button button, TextView status, boolean granted, int grantedRes, int deniedRes, boolean required) {
        if (granted) {
            button.setEnabled(false); button.setText(R.string.permission_grant);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF9E9E9E));
            status.setText(grantedRes); status.setTextColor(0xFF4CAF50);
        } else {
            button.setEnabled(true); button.setText(R.string.permission_grant);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(required ? 0xFF2196F3 : 0xFF4CAF50));
            status.setText(deniedRes); status.setTextColor(required ? 0xFFD32F2F : 0xFFFF8F00);
        }
    }

    private void setOptionalStatus(Button button, TextView status, boolean granted, String grantedText, String deniedText) {
        if (granted) {
            button.setEnabled(false); button.setText(R.string.permission_grant);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF9E9E9E));
            status.setText(grantedText); status.setTextColor(0xFF4CAF50);
        } else {
            button.setEnabled(true); button.setText(R.string.permission_grant);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
            status.setText(deniedText); status.setTextColor(0xFFFF8F00);
        }
    }

    private boolean canWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return Settings.System.canWrite(this);
        return true;
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        ComponentName expected = new ComponentName(this, CoverService.class);
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo().serviceInfo != null) {
                ComponentName name = new ComponentName(info.getResolveInfo().serviceInfo.packageName, info.getResolveInfo().serviceInfo.name);
                if (expected.equals(name)) return true;
            }
        }
        return false;
    }

    private boolean isNotificationAccessEnabled() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        try {
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName() + "/" + CoverNotifications.class.getName());
        } catch (Exception ignored) { return false; }
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
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

    private void openOverlaySettings() {
        try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
    }

    private void openAccessibilitySettings() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (Exception ignored) { }
    }

    private void openNotificationAccessSettings() {
        try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (Exception ignored) { }
    }

    private void openBatterySettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            else startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception ignored) { }
    }

    private void openWriteSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) { }
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 100);
        }
    }

    private void openAppListSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) { }
    }
}
