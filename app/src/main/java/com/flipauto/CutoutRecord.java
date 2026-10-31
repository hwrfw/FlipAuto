package com.flipauto;

import java.util.ArrayList;
import java.util.List;

/**
 * 外屏缺口记录：首次安装读到第一个有效缺口后写入，之后固定使用。
 *
 * <p>存成纯文本而不是 JSON，是为了让编解码不依赖 Android 运行时，可以直接做单元测试。
 * 格式：{@code v1|displayId|rotation|width|height|at|x,y,w,h;x,y,w,h;}。
 * 宽度或高度对不上（换机、改分辨率）时整条记录作废，由调用方重新捕获。
 */
final class CutoutRecord {
    private CutoutRecord() { }

    static String encode(int displayId, int rotation, int width, int height, List<DockGeometry.Box> boxes, long at) {
        StringBuilder out = new StringBuilder("v1|").append(displayId).append('|').append(rotation).append('|')
                .append(width).append('|').append(height).append('|').append(at).append('|');
        for (DockGeometry.Box box : boxes) {
            out.append(box.x()).append(',').append(box.y()).append(',').append(box.width()).append(',').append(box.height()).append(';');
        }
        return out.toString();
    }

    /** 解析失败、尺寸不符或数据越界都返回 null，表示这条记录不能用。 */
    static ArrayList<DockGeometry.Box> decode(String raw, int width, int height) {
        if (raw == null || raw.isEmpty()) return null;
        String[] head = raw.split("\\|", -1);
        if (head.length != 7 || !"v1".equals(head[0])) return null;
        try {
            if (Integer.parseInt(head[3]) != width || Integer.parseInt(head[4]) != height) return null;
            ArrayList<DockGeometry.Box> boxes = new ArrayList<>();
            for (String token : head[6].split(";")) {
                if (token.isEmpty()) continue;
                String[] part = token.split(",");
                if (part.length != 4) return null;
                int x = Integer.parseInt(part[0]), y = Integer.parseInt(part[1]);
                int boxWidth = Integer.parseInt(part[2]), boxHeight = Integer.parseInt(part[3]);
                if (x < 0 || y < 0 || boxWidth <= 0 || boxHeight <= 0) return null;
                if (x + boxWidth > width || y + boxHeight > height) return null;
                boxes.add(new DockGeometry.Box(x, y, boxWidth, boxHeight));
            }
            return boxes.isEmpty() ? null : boxes;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** 记录写入时间；解析不出来返回 0（视为很久以前，不再允许覆盖）。 */
    static long timestamp(String raw) {
        if (raw == null || raw.isEmpty()) return 0L;
        String[] head = raw.split("\\|", -1);
        if (head.length != 7 || !"v1".equals(head[0])) return 0L;
        try {
            return Long.parseLong(head[5]);
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

}
