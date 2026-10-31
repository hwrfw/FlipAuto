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
 * 这些数字就是隧道内的真实流量，不受系统总量重复计数的影响。解析只认「数字 + 单位 + /s」
 * 的写法，方向靠数字前面的箭头或中文标签判断，识别不到就返回 -1 交给调用方回退。
 */
final class SpeedNotification {
    /** 速率片段：1.2 MB/s、512KB/s、0 B/s 等写法。 */
    private static final Pattern SPEED = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*([KMG]?)(?:i?B)?\\s*/\\s*s", Pattern.CASE_INSENSITIVE);
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
    /** 数字前面这段文字决定方向。 */
    private static final String[] DOWN = {"↓", "下", "received", "download", "rx", "dl"};
    private static final String[] UP = {"↑", "上", "sent", "upload", "tx", "ul"};

    private SpeedNotification() { }

    /** 通知多久没更新就不再采信，避免代理应用退出后数字停在最后一帧。 */
    static final long FRESH_MS = 15000;

    /**
     * 从当前通知里找一条可信的实时速率。
     *
     * <p>优先采用代理应用的常驻通知；其他应用的通知只有在没有代理通知时才作为兜底，
     * 避免把下载工具的「1.2MB/s」当成 VPN 速率。超过 15 秒没更新的通知视为过期。
     *
     * @return 上下行合计（字节/秒）；没有可用通知时返回 -1
     */
    static double fromNotifications(List<StatusBarNotification> items, long now) {
        double fallback = -1;
        for (StatusBarNotification item : items) {
            if (now - item.getPostTime() > FRESH_MS) continue;
            double rate = parse(texts(item));
            if (rate < 0) continue;
            if (isVpnApp(item.getPackageName())) return rate;
            if (fallback < 0) fallback = rate;
        }
        return fallback;
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

    /** 包名是否像代理/VPN 应用。 */
    static boolean isVpnApp(String packageName) {
        if (packageName == null) return false;
        String name = packageName.toLowerCase(Locale.ROOT);
        for (String known : KNOWN) if (name.equals(known)) return true;
        for (String keyword : KEYWORDS) if (name.contains(keyword)) return true;
        return false;
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
            Matcher matcher = SPEED.matcher(body);
            while (matcher.find()) {
                double value = value(matcher.group(1), matcher.group(2));
                String prefix = body.substring(Math.max(0, matcher.start() - 12), matcher.start()).toLowerCase(Locale.ROOT);
                int direction = direction(prefix);
                if (direction > 0) down = Math.max(down, value);
                else if (direction < 0) up = Math.max(up, value);
                else plain = Math.max(plain, value);
            }
        }
        if (up >= 0 && down >= 0) return up + down;
        if (down >= 0) return down;
        if (up >= 0) return up;
        return plain;
    }

    /**
     * 判断数字前面最近的标记是上行还是下行。
     *
     * <p>取「最后一个出现的标记」而不是「包含任一标记」：紧凑写法里两个方向挨得很近，
     * 例如 {@code ↓1KB/s↑2KB/s}，只看包含关系会把第二个数字也判成下行。
     *
     * @return 1 下行、-1 上行、0 没有方向标记
     */
    private static int direction(String prefix) {
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
        switch (unit.toUpperCase(Locale.ROOT)) {
            case "K": return amount * 1024d;
            case "M": return amount * 1024d * 1024d;
            case "G": return amount * 1024d * 1024d * 1024d;
            default: return amount;
        }
    }
}
