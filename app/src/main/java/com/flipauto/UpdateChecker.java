package com.flipauto;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * 通过 GitHub Releases 接口查询最新版本。
 *
 * <p>只读取公开的发布信息：版本号、更新说明与安装包地址。匿名请求受 GitHub 限流
 * （每 IP 每小时 60 次），因此调用方应缓存结果，不要频繁轮询。
 */
public final class UpdateChecker {
    /** 发布仓库，需与 README 与下载页一致。 */
    static final String REPO = "hwrfw/FlipAuto";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** 发布页，找不到安装包资源时作为跳转目标。 */
    static final String PAGE = "https://github.com/" + REPO + "/releases/latest";
    /** 响应体上限，正常发布信息只有几 KB。 */
    private static final int LIMIT = 262144;
    private static final int TIMEOUT = 8000;

    private UpdateChecker() { }

    /** 一次查询的结果：成功时 {@code release} 非空，失败时 {@code error} 非空。 */
    static final class Result {
        final Release release;
        final boolean newer;
        final String error;

        Result(Release release, boolean newer, String error) {
            this.release = release;
            this.newer = newer;
            this.error = error;
        }

        boolean ok() { return error == null; }
    }

    /** 发布信息。 */
    static final class Release {
        final String tag;
        final String version;
        final String notes;
        final String pageUrl;
        final String apkUrl;
        final long apkSize;

        Release(String tag, String version, String notes, String pageUrl, String apkUrl, long apkSize) {
            this.tag = tag;
            this.version = version;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }
    }

    /**
     * 查询最新发布并与当前版本比较。
     *
     * <p>会阻塞当前线程，调用方需放在工作线程执行。
     *
     * @param currentVersion 当前 versionName，例如 {@code 6.0}
     */
    static Result check(String currentVersion) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API).openConnection();
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "FlipAuto/" + currentVersion);
            int code = connection.getResponseCode();
            if (code == 404) return new Result(null, false, "仓库里还没有正式发布");
            if (code == 403 || code == 429) return new Result(null, false, "请求过于频繁，请稍后再试");
            if (code != 200) return new Result(null, false, "GitHub 返回 " + code);
            Release release = parse(read(connection.getInputStream()));
            if (release == null) return new Result(null, false, "发布信息格式异常");
            return new Result(release, isNewer(release.version, currentVersion), null);
        } catch (UnknownHostException e) {
            return new Result(null, false, "无法连接 GitHub，请检查网络");
        } catch (java.net.SocketTimeoutException e) {
            return new Result(null, false, "连接超时，请稍后重试");
        } catch (IOException e) {
            return new Result(null, false, "网络不可用");
        } catch (Exception e) {
            return new Result(null, false, "检查失败：" + e.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) > 0) {
                text.append(buffer, 0, count);
                if (text.length() > LIMIT) break;
            }
        }
        return text.toString();
    }

    /** 解析发布接口的响应；缺少版本号时返回 null。 */
    static Release parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            String tag = root.optString("tag_name", "").trim();
            if (tag.isEmpty()) return null;
            String page = root.optString("html_url", PAGE);
            String apk = null;
            long size = 0;
            JSONArray assets = root.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                apk = asset.optString("browser_download_url", null);
                size = asset.optLong("size", 0);
                break;
            }
            return new Release(tag, trimVersion(tag), root.optString("body", "").trim(), page, apk == null ? page : apk, size);
        } catch (JSONException e) {
            return null;
        }
    }

    /** 去掉 {@code v} 前缀与预发布标记，只留版本数字，例如 {@code v6.1-beta} → {@code 6.1}。 */
    static String trimVersion(String tag) {
        if (tag == null) return "";
        String value = tag.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("v")) value = value.substring(1);
        int cut = value.indexOf('-');
        if (cut > 0) value = value.substring(0, cut);
        return value.trim();
    }

    /** 远端版本是否高于当前版本。 */
    static boolean isNewer(String remote, String current) {
        return compare(remote, current) > 0;
    }

    /**
     * 按数字段比较两个版本号。
     *
     * <p>缺省的段按 0 处理，因此 {@code 6.0} 与 {@code 6.0.0} 相等；数字段大于同位置的
     * 非数字段，使 {@code 6.1} 高于 {@code 6.1-beta}。
     */
    static int compare(String left, String right) {
        String[] a = segments(left), b = segments(right);
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            String x = i < a.length ? a[i] : "0", y = i < b.length ? b[i] : "0";
            boolean xNumber = x.matches("\\d+"), yNumber = y.matches("\\d+");
            int result;
            if (xNumber && yNumber) result = Long.compare(Long.parseLong(x), Long.parseLong(y));
            else if (xNumber) result = 1;
            else if (yNumber) result = -1;
            else result = x.compareTo(y);
            if (result != 0) return result > 0 ? 1 : -1;
        }
        return 0;
    }

    private static String[] segments(String version) {
        if (version == null) return new String[0];
        String value = version.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("^[^0-9a-z]+", "");
        if (value.isEmpty()) return new String[0];
        return value.split("[^0-9a-z]+");
    }

    /** 更新说明摘要：去掉空行与 Markdown 标记，超出长度截断。 */
    static String summary(String notes, int limit) {
        if (notes == null) return "";
        StringBuilder text = new StringBuilder();
        for (String line : notes.split("\\r?\\n")) {
            String value = line.replaceAll("^[#>*\\-+\\s]+", "").replaceAll("\\[(.*?)]\\(.*?\\)", "$1").trim();
            if (value.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(value);
        }
        String result = text.toString();
        return result.length() <= limit ? result : result.substring(0, Math.max(0, limit - 1)).trim() + "…";
    }

    /** 安装包大小，用于在确认下载前提示。 */
    static String size(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return Math.max(1, bytes / 1024) + " KB";
    }
}
