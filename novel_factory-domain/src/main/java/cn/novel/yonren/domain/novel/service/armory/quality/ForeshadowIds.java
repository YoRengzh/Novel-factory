package cn.novel.yonren.domain.novel.service.armory.quality;

import org.apache.commons.lang3.StringUtils;

/**
 * **伏笔稳定 ID 的形态工具（唯一实现）**：识别 {@code F7-2} / {@code [F7-2]} / {@code （F7-2）}。
 *
 * <p>ID 由 {@code ChapterSummaryService.assignForeshadowIds} 机械分配（{@code F{埋设章号}-{章内序号}}），
 * 由账本渲染（{@code ChapterMemoryService.buildForeshadowContextListWithIds}）带前缀摆给模型，
 * 模型在 {@code foreshadowingResolved} 里**回抄** ID 完成销账。
 *
 * <p><b>为什么单独成类</b>：三处消费端（账本核销、摘要失配检测、体检在途核销）此前各自
 * 持有形态判断，稍有差异就会让"核销成功"与"仍在途"对同一条线给出相反结论——
 * 与 {@code ForeshadowSpanPolicy.normalize} 是文本比对唯一口径同一纪律。
 */
public final class ForeshadowIds {

    /** ID 本体形态：{@code F<埋设章号>-<章内序号>}（字母大小写不敏感） */
    private static final String ID_PATTERN = "[Ff]\\d+-\\d+";

    private ForeshadowIds() {
    }

    /**
     * 归一化 ID 形态：{@code [F7-2]} / {@code F7-2} / {@code （F7-2）} → {@code F7-2}；非 ID 返回 null。
     */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("[") && v.endsWith("]") && v.length() > 2) {
            v = v.substring(1, v.length() - 1).trim();
        } else if (v.startsWith("（") && v.endsWith("）") && v.length() > 2) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v.matches(ID_PATTERN) ? v : null;
    }

    /** 提取账本行的 ID 前缀（{@code [F7-2] 描述} → {@code F7-2}）；无前缀返回 null */
    public static String extractPrefix(String pendingLine) {
        if (StringUtils.isBlank(pendingLine) || !pendingLine.startsWith("[")) {
            return null;
        }
        int close = pendingLine.indexOf(']');
        if (close <= 1) {
            return null;
        }
        String candidate = pendingLine.substring(1, close).trim();
        return candidate.matches(ID_PATTERN) ? candidate : null;
    }

    /** 剥离账本行的 ID 前缀（{@code [F7-2] 描述} → {@code 描述}）；无前缀原样返回 */
    public static String stripPrefix(String pendingLine) {
        return extractPrefix(pendingLine) == null ? pendingLine
                : pendingLine.substring(pendingLine.indexOf(']') + 1).trim();
    }
}
