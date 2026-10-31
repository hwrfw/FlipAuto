package com.flipauto;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedNotificationTest {
    private static final double KB = 1024d, MB = 1024d * 1024d;

    @Test public void clashMetaArrowAfterValue() {
        // Clash Meta：箭头在数字后面，标题是机场名
        assertEquals(0d, SpeedNotification.parse("三毛机场", "0 Bytes/s ↑ 0 Bytes/s ↓"), 0);
        assertEquals(1.5 * MB + 300 * KB, SpeedNotification.parse("三毛机场", "1.5 MB/s ↑ 300 KB/s ↓"), 1);
    }

    @Test public void clashMetaKeepsDirectionsApart() {
        // 上下行数值不同时不能对调
        assertEquals(2 * MB + 1 * MB, SpeedNotification.parse("2 MB/s ↑ 1 MB/s ↓"), 1);
    }

    @Test public void arrowBeforeValue() {
        assertEquals(1.2 * MB + 3.4 * MB, SpeedNotification.parse("↑ 1.2 MB/s  ↓ 3.4 MB/s"), 1);
    }

    @Test public void compactArrowsKeepBothDirections() {
        assertEquals(KB + 2 * KB, SpeedNotification.parse("↓1KB/s↑2KB/s"), 1);
        assertEquals(KB + 2 * KB, SpeedNotification.parse("↑1KB/s↓2KB/s"), 1);
    }

    @Test public void chineseLabels() {
        assertEquals(512 * KB + 1.5 * MB, SpeedNotification.parse("上传: 512 KB/s  下载: 1.5 MB/s"), 1);
    }

    @Test public void englishLabels() {
        assertEquals(3 * KB, SpeedNotification.parse("sent 1 KB/s · received 2 KB/s"), 1);
    }

    @Test public void singleDirectionOnly() {
        assertEquals(2 * MB, SpeedNotification.parse("下载 2 MB/s"), 1);
    }

    @Test public void unlabeledSpeedIsAccepted() {
        assertEquals(3 * MB, SpeedNotification.parse("当前速率 3 MB/s"), 1);
    }

    @Test public void byteUnits() {
        assertEquals(800d, SpeedNotification.parse("↓ 800 B/s"), 0);
        assertEquals(1.5 * KB, SpeedNotification.parse("↓ 1536 Bytes/s"), 0);
        assertEquals(1.5 * MB, SpeedNotification.parse("↓ 1.5 MiB/s"), 1);
    }

    @Test public void textWithoutSpeedIsIgnored() {
        assertEquals(-1d, SpeedNotification.parse("Clash Meta 已连接", "代理运行中"), 0);
        assertEquals(-1d, SpeedNotification.parse((CharSequence) null), 0);
    }

    @Test public void decimalCommaIsAccepted() {
        assertEquals(1.5 * MB, SpeedNotification.parse("↓ 1,5 MB/s"), 1);
    }

    @Test public void recognizesProxyPackages() {
        assertTrue(SpeedNotification.isVpnApp("com.github.metacubex.clash.meta"));
        assertTrue(SpeedNotification.isVpnApp("com.v2ray.ang"));
        assertTrue(SpeedNotification.isVpnApp("io.nekohasekai.sagernet"));
        assertFalse(SpeedNotification.isVpnApp("com.tencent.mm"));
        assertFalse(SpeedNotification.isVpnApp(null));
    }
}
