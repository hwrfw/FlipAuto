package com.flipauto;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 应用内调试日志（免 logcat）：开屏恢复 / 自动启动 / 多任务等关键节点写入，
 * 用户可在「外屏增强 → 恢复调试日志」中直接查看。
 */
public final class FlipLog {
    public static final int MAX = 120;
    public static final List<String> entries = Collections.synchronizedList(new ArrayList<>());
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private FlipLog() { }

    public static void d(String msg) {
        entries.add(FMT.format(new Date()) + "  " + msg);
        while (entries.size() > MAX) entries.remove(0);
    }

    public static void clear() { entries.clear(); }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (entries) {
            for (String e : entries) sb.append(e).append('\n');
        }
        return sb.length() == 0 ? "（暂无日志，请先使用外屏并合盖再展开触发恢复）" : sb.toString();
    }
}
