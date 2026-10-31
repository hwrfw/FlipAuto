package com.flipauto;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedMeterTest {
    private static final double KB = 1024d, MB = 1024d * 1024d;

    @Test public void firstSampleOnlyRecordsBaseline() {
        SpeedMeter meter = new SpeedMeter();
        assertEquals(-1d, meter.sample(1000, 0), 0);
        assertEquals(-1d, meter.rate(), 0);
    }

    @Test public void rateUsesActualElapsedTime() {
        SpeedMeter meter = new SpeedMeter();
        meter.sample(0, 0);
        // 2000ms 内累计增加 2MB，即 1MB/s
        assertEquals(MB, meter.sample((long) (2 * MB), 2000), 1);
    }

    @Test public void irregularIntervalStillCorrect() {
        SpeedMeter meter = new SpeedMeter();
        meter.sample(100, 100);
        // 实际间隔 2500ms（定时器抖动），500KB → 200KB/s
        assertEquals(200 * KB, meter.sample(100 + (long) (500 * KB), 2600), 1);
    }

    @Test public void tooShortIntervalIsDiscarded() {
        SpeedMeter meter = new SpeedMeter();
        meter.sample(0, 0);
        assertEquals(-1d, meter.sample((long) MB, 100), 0);
        // 丢弃后基线已推进到 (1MB, 100ms)，下一次按真实间隔计算
        assertEquals(MB * 1000d / 1900d, meter.sample((long) (2 * MB), 2000), 1);
    }

    @Test public void longGapIsDiscardedInsteadOfAveraged() {
        SpeedMeter meter = new SpeedMeter();
        meter.sample(0, 0);
        // 挂起 10 分钟后恢复：若按平均计算会把速率算成极低值
        assertEquals(-1d, meter.sample((long) (600 * MB), 600_000), 0);
        assertEquals(MB, meter.sample((long) (602 * MB), 602_000), 1);
    }

    @Test public void counterResetRebuildsBaseline() {
        SpeedMeter meter = new SpeedMeter();
        meter.sample((long) (10 * MB), 0);
        // 累计值回落说明统计被重置，不能算出负速率
        assertEquals(-1d, meter.sample(1024, 2000), 0);
        assertEquals(MB, meter.sample(1024 + (long) (2 * MB), 4000), 1);
    }

    @Test public void resetClearsBaseline() {
        SpeedMeter meter = new SpeedMeter();
        meter.sample(0, 0);
        meter.sample((long) (2 * MB), 2000);
        meter.reset();
        assertEquals(-1d, meter.sample((long) (4 * MB), 4000), 0);
    }

    @Test public void formatUsesBinaryUnits() {
        assertEquals("0K/s", SpeedMeter.format(0));
        assertEquals("0K/s", SpeedMeter.format(511));
        assertEquals("1K/s", SpeedMeter.format(KB));
        assertEquals("512K/s", SpeedMeter.format(512 * KB));
        assertEquals("1.5M/s", SpeedMeter.format(1.5 * MB));
        assertEquals("20.0M/s", SpeedMeter.format(20 * MB));
    }

    @Test public void formatPromotesKiloToMegaAtBoundary() {
        // 1023.6K 四舍五入到 1024K，应显示 1.0M/s 而不是 1024K/s
        assertEquals("1.0M/s", SpeedMeter.format(1023.6 * KB));
        assertEquals("1023K/s", SpeedMeter.format(1023.4 * KB));
    }

    @Test public void idleTrafficReadsZero() {
        SpeedMeter meter = new SpeedMeter();
        meter.sample(0, 0);
        // 2 秒内只有 200 字节的心跳流量，按 0 显示而不是 1K/s
        assertEquals("0K/s", SpeedMeter.format(meter.sample(200, 2000)));
    }
}
