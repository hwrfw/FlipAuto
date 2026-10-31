package com.flipauto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 发布信息解析与版本比较。 */
public class UpdateCheckerTest {
    private static final String RELEASE = "{"
        + "\"tag_name\":\"v6.1\","
        + "\"html_url\":\"https://github.com/hwrfw/FlipAuto/releases/tag/v6.1\","
        + "\"body\":\"## 更新内容\\n- 修复网速显示\\n- 新增[检查更新](https://example.com)\\n\","
        + "\"assets\":["
        + "{\"name\":\"notes.txt\",\"browser_download_url\":\"https://example.com/n.txt\",\"size\":10},"
        + "{\"name\":\"FlipAuto-6.1-release.apk\",\"browser_download_url\":\"https://example.com/a.apk\",\"size\":20300000}"
        + "]}";

    @Test public void parsesTagVersionAndNotes() {
        UpdateChecker.Release release = UpdateChecker.parse(RELEASE);
        assertEquals("v6.1", release.tag);
        assertEquals("6.1", release.version);
        assertTrue(release.notes.startsWith("## 更新内容"));
    }

    @Test public void picksApkAssetOnly() {
        UpdateChecker.Release release = UpdateChecker.parse(RELEASE);
        assertEquals("https://example.com/a.apk", release.apkUrl);
        assertEquals(20300000L, release.apkSize);
    }

    @Test public void fallsBackToReleasePageWithoutAsset() {
        UpdateChecker.Release release = UpdateChecker.parse(
            "{\"tag_name\":\"v6.2\",\"html_url\":\"https://github.com/hwrfw/FlipAuto/releases/tag/v6.2\"}");
        assertEquals("https://github.com/hwrfw/FlipAuto/releases/tag/v6.2", release.apkUrl);
        assertEquals(0L, release.apkSize);
    }

    @Test public void rejectsInvalidPayload() {
        assertNull(UpdateChecker.parse("not json"));
        assertNull(UpdateChecker.parse("{\"body\":\"没有版本号\"}"));
    }

    @Test public void trimsTagPrefixAndPrereleaseSuffix() {
        assertEquals("6.1", UpdateChecker.trimVersion("v6.1"));
        assertEquals("6.0.1", UpdateChecker.trimVersion("6.0.1-beta"));
        assertEquals("6.1", UpdateChecker.trimVersion("V6.1"));
        assertEquals("", UpdateChecker.trimVersion(null));
    }

    @Test public void comparesNumericSegments() {
        assertTrue(UpdateChecker.compare("6.1", "6.0") > 0);
        assertTrue(UpdateChecker.compare("6.0", "6.1") < 0);
        assertEquals(0, UpdateChecker.compare("6.0", "6.0.0"));
        assertTrue(UpdateChecker.compare("6.0.10", "6.0.9") > 0);
        assertTrue(UpdateChecker.compare("10.0", "9.9") > 0);
    }

    @Test public void releaseBeatsItsPrerelease() {
        assertTrue(UpdateChecker.compare("6.1", "6.1-beta") > 0);
        assertTrue(UpdateChecker.compare("6.1-beta", "6.1") < 0);
    }

    @Test public void detectsNewerOnlyWhenAhead() {
        assertTrue(UpdateChecker.isNewer("6.1", "6.0"));
        assertFalse(UpdateChecker.isNewer("6.0", "6.0"));
        assertFalse(UpdateChecker.isNewer("5.9", "6.0"));
    }

    @Test public void summarizesMarkdown() {
        String notes = UpdateChecker.summary("## 更新内容\n\n- 修复网速\n- 新增[检查更新](https://example.com)\n", 100);
        assertEquals("更新内容\n修复网速\n新增检查更新", notes);
    }

    @Test public void truncatesLongNotes() {
        String notes = UpdateChecker.summary("一".repeat(50), 10);
        assertEquals(10, notes.length());
        assertTrue(notes.endsWith("…"));
    }

    @Test public void formatsAssetSize() {
        assertEquals("19.4 MB", UpdateChecker.size(20300000));
        assertEquals("512 KB", UpdateChecker.size(524288));
        assertEquals("", UpdateChecker.size(0));
    }
}
