package com.flipauto;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.VelocityTracker;
import android.widget.FrameLayout;

/** Horizontal gestures: left-swipe reveals action rail, right-swipe past threshold directly dismisses. */
final class NotificationSwipeRow extends FrameLayout {
    final View surface;
    private final View rail;
    private final Runnable reveal;
    private Runnable onDismiss;
    private final float slop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint deletePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Drawable deleteIcon;
    private float startX, startY, startOffset;
    private boolean dragging, vertical, startedOpen, railTouch, swipingRight;
    private int actionWidth, layers;
    private VelocityTracker velocity;
    private boolean targetOpen, sequenceCanceled;
    private boolean dismissible = true;
    void setDismissible(boolean canDismiss) { this.dismissible = canDismiss; }
    NotificationSwipeRow(Context context, View surface, View rail, Runnable reveal) {
        super(context); this.surface = surface; this.rail = rail; this.reveal = reveal;
        slop = ViewConfiguration.get(context).getScaledTouchSlop(); setWillNotDraw(false);
        deletePaint.setColor(0xFFD93025);
        deleteIcon = Ui.icon(context, R.drawable.ic_ms_delete, 0xFFFFFFFF);
        addView(rail, new LayoutParams(Ui.dp(context, 96), Ui.dp(context, 48), Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        addView(surface, new LayoutParams(-1, -2)); setActionsWidth(96); setRailVisible(false);
    }
    void setOnDismissListener(Runnable listener) { this.onDismiss = listener; }
    void setActionsWidth(int dp) {
        int width = Ui.dp(getContext(), dp); if (actionWidth == width) return;
        actionWidth = width; rail.getLayoutParams().width = width; rail.requestLayout();
        if (opened()) { surface.animate().cancel(); surface.setTranslationX(-actionWidth); }
    }
    boolean opened() { return surface.getTranslationX() < -1; }
    void showActions(boolean show) {
        targetOpen = show; surface.animate().withEndAction(null).cancel(); if (show) { reveal.run(); setRailVisible(true); }
        float target = show ? -actionWidth : 0;
        if (!ValueAnimator.areAnimatorsEnabled()) { surface.setTranslationX(target); setRailVisible(show); return; }
        long duration = Math.max(60, Math.round(150 * Math.abs(target - surface.getTranslationX()) / Math.max(1, actionWidth)));
        surface.animate().translationX(target).setDuration(duration).setInterpolator(new android.view.animation.DecelerateInterpolator()).withEndAction(() -> { if (!targetOpen) setRailVisible(false); }).start();
    }
    private void setRailVisible(boolean visible) {
        rail.setVisibility(visible ? VISIBLE : INVISIBLE);
        rail.setImportantForAccessibility(visible ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    void setLayers(int count) {
        int next = Math.min(2, Math.max(0, count - 1)); if (next == layers) return; layers = next;
        LayoutParams params = (LayoutParams) surface.getLayoutParams(); params.bottomMargin = Ui.dp(getContext(), layers * 3); surface.setLayoutParams(params); invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 右滑删除背景（仅可清除通知）
        float tx = surface.getTranslationX();
        if (tx > 0 && onDismiss != null && dismissible) {
            canvas.drawRect(0, 0, tx, getHeight(), deletePaint);
            int iconSize = Ui.dp(getContext(), 24);
            int iconX = (int) Math.min(tx - iconSize - Ui.dp(getContext(), 16), getWidth() - iconSize - Ui.dp(getContext(), 16));
            if (iconX > Ui.dp(getContext(), 8)) {
                deleteIcon.setBounds(iconX, (getHeight() - iconSize) / 2, iconX + iconSize, (getHeight() - iconSize) / 2 + iconSize);
                deleteIcon.draw(canvas);
            }
        }
        for (int i = layers; i > 0; i--) {
            float inset = Ui.dp(getContext(), i * 4), bottom = getHeight() - Ui.dp(getContext(), (layers - i) * 3);
            paint.setColor(i == 1 ? 0xFF23262C : 0xFF191C21);
            canvas.drawRoundRect(inset, Ui.dp(getContext(), 8), getWidth() - inset, bottom, Ui.dp(getContext(), 14), Ui.dp(getContext(), 14), paint);
        }
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            surface.animate().cancel(); startX = event.getX(); startY = event.getY(); startOffset = surface.getTranslationX();
            startedOpen = targetOpen; dragging = false; vertical = false; swipingRight = false;
            railTouch = opened() && startX >= getWidth() + startOffset;
            return opened() && !railTouch;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) { vertical = true; showActions(startedOpen); return false; }
        if (action == MotionEvent.ACTION_MOVE && !vertical && !railTouch) {
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) vertical = true;
            // 常驻通知（不可清除）右滑时拦截事件但不移动，阻止 surface 消费
            if (dx > 0 && !dismissible) { return true; }
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                dragging = true; swipingRight = dx > 0;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (!swipingRight) { reveal.run(); setRailVisible(true); }
                return true;
            }
        }
        return dragging;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && !vertical) {
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (!dragging && Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) { vertical = true; showActions(false); }
            // 常驻通知不允许右滑删除
            if (dx > 0 && !dismissible) { return true; }
            if (!vertical && (dragging || Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f)) {
                dragging = true; swipingRight = dx > 0;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (swipingRight) {
                    // 右滑：允许滑出屏幕（最大到宽度），显示删除背景
                    surface.setTranslationX(Math.min(getWidth(), Math.max(0, startOffset + dx)));
                    invalidate();
                } else {
                    reveal.run(); setRailVisible(true);
                    surface.setTranslationX(Math.max(-actionWidth, Math.min(0, startOffset + dx)));
                }
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            float speed = 0; if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = velocity.getXVelocity(); }
            float threshold = Ui.dp(getContext(), 450);
            float tx = surface.getTranslationX();
            if (action == MotionEvent.ACTION_UP && !vertical && swipingRight && onDismiss != null) {
                // 右滑删除：超过宽度 30% 或向右速度超过阈值 → 直接删除
                boolean dismiss = speed > threshold || tx > getWidth() * 0.3f;
                if (dismiss) {
                    // 动画滑出后回调删除
                    surface.animate().translationX(getWidth()).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .withEndAction(() -> { if (onDismiss != null) onDismiss.run(); }).start();
                    dragging = false; getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
            }
            boolean open = action == MotionEvent.ACTION_UP && !vertical && !swipingRight ? dragging && (Math.abs(speed) > threshold ? speed < 0 : tx < -actionWidth * .4f) : startedOpen;
            showActions(open);
            if (swipingRight && !open) { surface.animate().translationX(0).setDuration(150).start(); }
            dragging = false; if (action != MotionEvent.ACTION_UP) vertical = true;
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(); if (action == MotionEvent.ACTION_DOWN) { sequenceCanceled = false; releaseVelocity(); velocity = VelocityTracker.obtain(); }
        if (!sequenceCanceled && (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN)) {
            sequenceCanceled = true; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle();
            dragging = false; vertical = true; showActions(startedOpen); releaseVelocity();
        }
        if (sequenceCanceled) return true;
        if (velocity != null) velocity.addMovement(event); boolean result = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) releaseVelocity(); return result;
    }
    private void releaseVelocity() { if (velocity != null) velocity.recycle(); velocity = null; }
    @Override protected void onDetachedFromWindow() { releaseVelocity(); targetOpen = false; surface.animate().withEndAction(null).cancel(); surface.setTranslationX(0); setRailVisible(false); super.onDetachedFromWindow(); }
}
