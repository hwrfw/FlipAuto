package com.flipauto;

import java.util.Locale;

/**
 * 实时网速采样。
 *
 * <p>调用方每轮把系统统计的累计收发字节数传进来，速率由相邻两次读数的差值除以实际间隔
 * 得到，因此不受定时器抖动影响。间隔过短（重复触发）或过长（界面不可见、设备休眠）时
 * 丢弃本次样本，避免把几分钟的流量平均成「0K/s」。
 *
 * <p>仅负责数值与文本，不访问系统接口，便于单元测试。
 */
final class SpeedMeter {
    /** 相邻采样的最短间隔，低于此值视为同一轮重复触发。 */
    static final long MIN_INTERVAL_MS = 500;
    /** 相邻采样的最长间隔，超过此值说明中途有休眠或长时间未采样。 */
    static final long MAX_INTERVAL_MS = 6000;
    /** 低于该值按 0 处理，避免空闲时显示 0K/s 与 1K/s 之间跳动。 */
    static final double IDLE_BYTES_PER_SECOND = 512;

    private long lastBytes = -1;
    private long lastTime;
    private double rate = -1;

    /** 清空基线，下一次采样只记录不计算。 */
    void reset() {
        lastBytes = -1;
        lastTime = 0;
        rate = -1;
    }

    /**
     * 采样一次。
     *
     * @param totalBytes 系统累计收发字节数（单调递增）
     * @param nowMs      单调时钟毫秒值
     * @return 速率（字节/秒）；返回 -1 表示本次没有有效读数
     */
    double sample(long totalBytes, long nowMs) {
        long elapsed = nowMs - lastTime;
        long previous = lastBytes;
        lastBytes = totalBytes;
        lastTime = nowMs;
        // 首次采样、间隔异常、计数器回绕（设备重启或统计被重置）都只重建基线
        if (previous < 0 || totalBytes < previous || elapsed < MIN_INTERVAL_MS || elapsed > MAX_INTERVAL_MS) {
            rate = -1;
            return -1;
        }
        rate = (totalBytes - previous) * 1000d / elapsed;
        return rate;
    }

    /** 最近一次有效速率（字节/秒），无有效样本时为 -1。 */
    double rate() { return rate; }

    /** 按 1024 进制换算为状态栏文本，K/s 取整、M/s 保留一位小数。 */
    static String format(double bytesPerSecond) {
        if (bytesPerSecond < IDLE_BYTES_PER_SECOND) return "0K/s";
        if (bytesPerSecond < 1024d * 1024d) {
            long kilo = Math.round(bytesPerSecond / 1024d);
            // 四舍五入后到达 1024K 时进位为 1.0M，避免出现「1024K/s」
            if (kilo < 1024) return kilo + "K/s";
            return "1.0M/s";
        }
        return String.format(Locale.ROOT, "%.1fM/s", bytesPerSecond / (1024d * 1024d));
    }
}
