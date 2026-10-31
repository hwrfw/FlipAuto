package com.flipauto;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * 免 Shizuku 的外屏旋转管理器。
 * 通过一个 0 尺寸的透明 Overlay 窗口固定/恢复外屏方向，
 * 并在手动模式下用倾斜传感器显示"旋转建议"按钮。
 */
public final class RotationManager {
    public static final int MODE_AUTO = 0;
    public static final int MODE_MANUAL = 1;
    public static final int MODE_OFF = 2;

    private final Context displayContext;
    private boolean overlaysHidden = false;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;

    private View rotationOverlayView = null;
    private View gestureOverlayView = null;

    private OrientationEventListener orientationEventListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable hideGestureRunnable = null;

    private int currentMode = MODE_AUTO;

    public RotationManager(Context displayContext) {
        this.displayContext = displayContext;
        this.windowManager = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = displayContext.getSharedPreferences("flipcover_rotation_pref", Context.MODE_PRIVATE);
        this.currentMode = prefs.getInt("rotation_mode", MODE_AUTO);
    }

    public void init() {
        setupOrientationListener();
        applyMode(currentMode);
    }

    public int getMode() { return currentMode; }

    /** 从持久化读取当前免 Shizuku 旋转模式（设置页无需服务实例即可读）。 */
    public static int readMode(Context ctx) { return ctx.getSharedPreferences("flipcover_rotation_pref", Context.MODE_PRIVATE).getInt("rotation_mode", MODE_AUTO); }
    /** 写入免 Shizuku 旋转模式到持久化（服务实例运行时还需调用 setMode 立即生效）。 */
    public static void writeMode(Context ctx, int mode) { ctx.getSharedPreferences("flipcover_rotation_pref", Context.MODE_PRIVATE).edit().putInt("rotation_mode", mode).apply(); }

    /** 方向 overlay 是否成功添加（false 通常是缺少 SYSTEM_ALERT_WINDOW 悬浮窗权限）。 */
    public boolean isOverlayActive() { return rotationOverlayView != null; }

    public String getModeLabel() {
        switch (currentMode) {
            case MODE_AUTO: return "自动旋转";
            case MODE_MANUAL: return "手动旋转";
            default: return "旋转关闭";
        }
    }

    public void cycleMode() { setMode((currentMode + 1) % 3); }

    public void setMode(int mode) {
        if (mode < MODE_AUTO || mode > MODE_OFF) return;
        this.currentMode = mode;
        prefs.edit().putInt("rotation_mode", mode).apply();
        applyMode(mode);
    }

    private void applyMode(int mode) {
        removeRotationOverlay();
        removeGestureOverlay();
        if (mode == MODE_AUTO) {
            addRotationOverlay(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            if (orientationEventListener != null) orientationEventListener.enable();
        } else if (mode == MODE_MANUAL) {
            addRotationOverlay(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            if (orientationEventListener != null) orientationEventListener.enable();
        } else {
            if (orientationEventListener != null) orientationEventListener.disable();
        }
    }

    private void addRotationOverlay(int orientation) {
        if (rotationOverlayView != null || windowManager == null) return;
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                0, 0, layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.screenOrientation = orientation;
        rotationOverlayView = new View(displayContext);
        try {
            windowManager.addView(rotationOverlayView, params);
        } catch (Exception e) {
            Log.e("RotationManager", "添加旋转 Overlay 失败", e);
        }
    }

    private void removeRotationOverlay() {
        if (rotationOverlayView != null && windowManager != null) {
            try { windowManager.removeView(rotationOverlayView); } catch (Exception ignored) { }
            rotationOverlayView = null;
        }
    }

    private void setRotationOrientation(int orientation) {
        if (rotationOverlayView != null && windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) rotationOverlayView.getLayoutParams();
            if (params.screenOrientation != orientation) {
                params.screenOrientation = orientation;
                try { windowManager.updateViewLayout(rotationOverlayView, params); } catch (Exception ignored) { }
            }
        }
    }

    // ---------- 倾斜触发旋转建议按钮（右边缘锚定） ----------
    private void showGestureButton() {
        if (currentMode != MODE_MANUAL) return;
        if (hideGestureRunnable != null) mainHandler.removeCallbacks(hideGestureRunnable);
        addGestureOverlay();
        hideGestureRunnable = new Runnable() { @Override public void run() { removeGestureOverlay(); } };
        mainHandler.postDelayed(hideGestureRunnable, 3000);
    }

    private void addGestureOverlay() {
        if (gestureOverlayView != null || windowManager == null) return;
        float density = displayContext.getResources().getDisplayMetrics().density;
        int sizePx = (int) (36 * density);
        int padding = (int) (8 * density);
        ImageView btn = new ImageView(displayContext);
        btn.setLayoutParams(new FrameLayout.LayoutParams(sizePx, sizePx));
        btn.setPadding(padding, padding, padding, padding);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(180, 30, 30, 30));
        bg.setStroke((int) (1 * density), Color.DKGRAY);
        btn.setBackground(bg);
        btn.setImageResource(android.R.drawable.ic_menu_rotate);
        btn.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        btn.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                vibrate();
                setRotationOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                mainHandler.postDelayed(() -> {
                    setRotationOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                    removeGestureOverlay();
                }, 1200);
            }
            return true;
        });
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                sizePx, sizePx, layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = (int) (16 * density);
        try {
            windowManager.addView(btn, params);
            gestureOverlayView = btn;
        } catch (Exception e) {
            Log.e("RotationManager", "添加旋转建议按钮失败", e);
        }
    }

    private void removeGestureOverlay() {
        if (gestureOverlayView != null && windowManager != null) {
            try { windowManager.removeView(gestureOverlayView); } catch (Exception ignored) { }
            gestureOverlayView = null;
        }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) displayContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(30);
            }
        } catch (Exception ignored) { }
    }

    private void setupOrientationListener() {
        orientationEventListener = new OrientationEventListener(displayContext) {
            private int lastQuadrant = -1;
            private long lastProcessedTime = 0;
            @Override public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN || currentMode != MODE_MANUAL) return;
                long now = System.currentTimeMillis();
                if (now - lastProcessedTime < 150) return;
                lastProcessedTime = now;
                int threshold = 20;
                int currentQuadrant = -1;
                if (orientation >= 360 - threshold || orientation <= threshold) currentQuadrant = 0;
                else if (orientation >= 90 - threshold && orientation <= 90 + threshold) currentQuadrant = 1;
                else if (orientation >= 180 - threshold && orientation <= 180 + threshold) currentQuadrant = 2;
                else if (orientation >= 270 - threshold && orientation <= 270 + threshold) currentQuadrant = 3;
                if (currentQuadrant != -1 && lastQuadrant != -1 && currentQuadrant != lastQuadrant) showGestureButton();
                if (currentQuadrant != -1) lastQuadrant = currentQuadrant;
            }
        };
    }

    public void hideOverlays() {
        if (overlaysHidden) return;
        overlaysHidden = true;
        removeRotationOverlay();
        removeGestureOverlay();
    }

    public void showOverlays() {
        if (!overlaysHidden) return;
        overlaysHidden = false;
        applyMode(currentMode);
    }

    public void destroy() {
        if (orientationEventListener != null) orientationEventListener.disable();
        removeRotationOverlay();
        removeGestureOverlay();
    }
}
