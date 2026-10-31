package com.flipauto;

import java.util.Set;

/** Manual choice lasts for the current foreground app, including all of its pages. */
final class DockVisibility {
    private String foreground = "";
    private Boolean manual;
    private boolean keyboard;
    private Boolean keyboardManual;
    void foreground(String packageName) {
        if (packageName == null || packageName.isEmpty() || packageName.equals(foreground)) return;
        foreground = packageName; manual = null; keyboardManual = null;
    }
    String currentForeground() { return foreground; }
    void keyboard(boolean visible) { if (keyboard != visible) { keyboard = visible; keyboardManual = null; } }
    boolean compact(boolean automatic, Set<String> compactApps) {
        if (keyboard) return keyboardManual == null || keyboardManual;
        return manual != null ? manual : automatic && !foreground.isEmpty() && compactApps.contains(foreground);
    }
    /** 当前前台应用是否在排除列表中（用于完全隐藏 dock 栏或状态栏） */
    boolean isExcluded(Set<String> excludeApps) {
        return !foreground.isEmpty() && excludeApps.contains(foreground);
    }
    void toggle(boolean automatic, Set<String> compactApps) { boolean next = !compact(automatic, compactApps); if (keyboard) keyboardManual = next; else manual = next; }
    void reset() { foreground = ""; manual = null; keyboard = false; keyboardManual = null; }
}
