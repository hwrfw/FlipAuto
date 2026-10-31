package com.flipauto;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 VPN 类应用的通知里读取实时网速。
 *
 * <p>Clash、Clash Meta、v2rayNG 等代理应用会在常驻通知里持续刷新上下行速率，
 * 这些数字就是隧道内的真实流量，不受系统总量重复计数的影响。
 *
 * <p>各家写法不一样：Clash Meta 是 {@code 0 Bytes/s ↑ 0 Bytes/s ↓}（箭头在后），
 * 另一些是 {@code ↑ 1.2 MB/s ↓ 3.4 MB/s} 或 {@code 上传: 512 KB/s 下载: 1.5 MB/s}
 * （标记在前）。同一段文字里箭头夹在两个数字之间时，靠前还是在后只能靠整体格式判断，
 * 所以先看首个数字前面有没有标记、末尾数字后面有没有箭头，再决定按哪种读法解析。
 */
final class SpeedNotification {
    /** 速率片段：1.2 MB/s、512KB/s、0 Bytes/s、800 B/s 等写法。 */
    private static final Pattern SPEED = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(bytes?|[kmg]i?b|b|[kmg])\\s*/\\s*s", Pattern.CASE_INSENSITIVE);
    /** 常见代理应用的包名，命中时优先采用。 */
    private static final String[] KNOWN = {
            "com.github.kr328.clash",
            "com.github.metacubex.clash",
            "com.v2ray.ang",
            "io.nekohasekai.sagernet",
            "com.surfboardapp.surfboard",
            "com.github.shadowsocks",
            "com.wireguard.android",
            "org.outline.android.client",
    };
    /** 包名关键词，用于识别未列入清单的代理应用。 */
    private static final String[] KEYWORDS = {"clash", "v2ray", "sing-box", "singbox", "shadow", "proxy", "vpn", "surfboard", "sagernet"};
    /** 下行标记：箭头、中文「下/下载/下行」、英文 received/download。 */
    private static final String[] DOWN = {"↓", "下", "received", "download", "rx", "dl"};
    /** 上行标记：箭头、中文「上/上传/上行」、英文 sent/upload。 */
    private static final String[] UP = {"↑", "上", "sent", "upload", "tx", "ul"};

    /** 通知多久没更新就不再采信，避免代理应用退出后数字停在最后一帧。 */
    static final long FRESH_MS = 15000;

    private SpeedNotification() { }

    /** 包名是否像代理/VPN 应用。 */
    static boolean isVpnApp(String packageName) {
        if (packageName == null) return false;
        String name = packageName.toLowerCase(Locale.ROOT);
        for (String known : KNOWN) if (name.equals(known)) return true;
        for (String keyword : KEYWORDS) if (name.contains(keyword)) return true;
        return false;
    }

    /**
     * 从当前通知里找一条可信的实时速率。
     *
     * @param packages 用户指定的应用包名；非空时只看这些应用，为空则自动识别代理类应用
     * @return 上下行合计（字节/秒）；没有可用通知时返回 -1
     */
    static double fromNotifications(List<StatusBarNotification> items, long now, java.util.Set<String> packages) {
        boolean explicit = packages != null && !packages.isEmpty();
        double fallback = -1;
        for (StatusBarNotification item : items) {
            if (now - item.getPostTime() > FRESH_MS) continue;
            double rate = parse(texts(item));
            if (rate < 0) continue;
            if (explicit) {
                if (packages.contains(item.getPackageName())) return rate;
            } else if (isVpnApp(item.getPackageName())) {
                return rate;
            } else if (fallback < 0) {
                fallback = rate;
            }
        }
        return explicit ? -1 : fallback;
    }

    /**
     * 解析一段通知文本里的速率。
     *
     * @return 上下行合计（字节/秒）；没有可识别的速率时返回 -1
     */
    static double parse(CharSequence... texts) {
        double up = -1, down = -1, plain = -1;
        for (CharSequence text : texts) {
            if (text == null) continue;
            String body = text.toString();
            List<Hit> hits = hits(body);
            if (hits.isEmpty()) continue;
            boolean suffix = suffixStyle(body, hits);
            for (Hit hit : hits) {
                int direction = suffix ? after(body, hit) : before(body, hit);
                // 当前读法认不出方向时换另一种再看一眼，避免漏掉只在一侧带标记的写法
                if (direction == 0) direction = suffix ? before(body, hit) : after(body, hit);
                if (direction > 0) down = Math.max(down, hit.value);
                else if (direction < 0) up = Math.max(up, hit.value);
                else plain = Math.max(plain, hit.value);
            }
        }
        if (up >= 0 && down >= 0) return up + down;
        if (down >= 0) return down;
        if (up >= 0) return up;
        return plain;
    }

    /** 一条「数字 + 单位 + /s」及其在文本中的位置。 */
    private static final class Hit {
        final int start, end;
        final double value;
        Hit(int start, int end, double value) { this.start = start; this.end = end; this.value = value; }
    }

    private static List<Hit> hits(String body) {
        List<Hit> hits = new ArrayList<>();
        Matcher matcher = SPEED.matcher(body);
        while (matcher.find()) hits.add(new Hit(matcher.start(), matcher.end(), value(matcher.group(1), matcher.group(2))));
        return hits;
    }

    /**
     * 判断这段文字是「箭头在后」还是「标记在前」。
     *
     * <p>Clash Meta 写成 {@code 0 Bytes/s ↑ 0 Bytes/s ↓}，首个数字前没有标记、末尾数字后有箭头；
     * 其余写法首个数字前就有 ↑ 或「上传」。两者都出现时按「标记在前」处理。
     */
    private static boolean suffixStyle(String body, List<Hit> hits) {
        boolean before = before(body, hits.get(0)) != 0;
        boolean after = after(body, hits.get(hits.size() - 1)) != 0;
        return after && !before;
    }

    /** 数字前面的标记：取距离最近的一个。 */
    private static int before(String body, Hit hit) {
        String prefix = body.substring(Math.max(0, hit.start - 14), hit.start).toLowerCase(Locale.ROOT);
        return nearest(prefix);
    }

    /** 数字后面紧跟的箭头，中间只允许空格。 */
    private static int after(String body, Hit hit) {
        int index = hit.end;
        while (index < body.length() && Character.isWhitespace(body.charAt(index))) index++;
        if (index >= body.length()) return 0;
        char mark = body.charAt(index);
        if (mark == '↑') return -1;
        if (mark == '↓') return 1;
        return 0;
    }

    private static int nearest(String prefix) {
        int down = last(prefix, DOWN), up = last(prefix, UP);
        if (down < 0 && up < 0) return 0;
        return down >= up ? 1 : -1;
    }

    private static int last(String prefix, String[] marks) {
        int index = -1;
        for (String mark : marks) index = Math.max(index, prefix.lastIndexOf(mark));
        return index;
    }

    private static double value(String number, String unit) {
        double amount = Double.parseDouble(number.replace(',', '.'));
        String lower = unit.toLowerCase(Locale.ROOT);
        char head = lower.charAt(0);
        if (lower.startsWith("b") && !lower.startsWith("byte")) return amount;
        switch (head) {
            case 'k': return amount * 1024d;
            case 'm': return amount * 1024d * 1024d;
            case 'g': return amount * 1024d * 1024d * 1024d;
            default: return amount;
        }
    }

    /** 取通知里所有可能带速率的文本：标题、正文、展开正文、副标题与多行正文。 */
    private static CharSequence[] texts(StatusBarNotification item) {
        Bundle extras = item.getNotification().extras;
        if (extras == null) return new CharSequence[0];
        List<CharSequence> out = new ArrayList<>();
        out.add(extras.getCharSequence(Notification.EXTRA_TITLE));
        out.add(extras.getCharSequence(Notification.EXTRA_TEXT));
        out.add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        out.add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        out.add(extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
        out.add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) out.addAll(Arrays.asList(lines));
        return out.toArray(new CharSequence[0]);
    }
}
