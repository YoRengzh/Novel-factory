package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;

/** 两条 embedding 检索链共享的任务摘要；不接收原始 prompt、整章正文或渲染提示词。 */
public final class ReferenceQueryBuilder {

    public static final int MAX_QUERY_CHARS = 2048;

    private ReferenceQueryBuilder() {
    }

    public static String build(PromptScene scene, PromptContext ctx) {
        StringBuilder query = new StringBuilder("写作场景：").append(task(scene));
        if (ctx == null) {
            return query.toString();
        }
        append(query, "题材", ctx.getTheme(), 256);
        append(query, "风格", ctx.getStyle(), 192);
        append(query, "章节类型", ctx.getChapterType() == null ? null : ctx.getChapterType().getDesc(), 128);
        append(query, "章号", ctx.getChapterNo() == null ? null : ctx.getChapterNo().toString(), 16);
        append(query, "总章数", ctx.getTotalChapters() == null ? null : ctx.getTotalChapters().toString(), 16);
        append(query, "本章要点", ctx.getChapterBrief(), 512);
        return query.toString();
    }

    /** 每项先独立收敛长度，再作为完整的带标签字段追加，绝不末尾截断整个 query。 */
    private static void append(StringBuilder query, String field, String value, int fieldLimit) {
        if (value == null || value.isBlank()) {
            return;
        }
        String label = "；" + field + "：";
        String text = bounded(value.strip(), fieldLimit);
        if (query.length() + label.length() + text.length() <= MAX_QUERY_CHARS) {
            query.append(label).append(text);
        }
    }

    private static String bounded(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        int cut = limit;
        // 优先句/字段边界；无边界的短标签仍需保证 Unicode 安全。
        for (int i = limit - 1; i >= limit / 2; i--) {
            if ("\n；;。！？、，, ".indexOf(value.charAt(i)) >= 0) {
                cut = i + 1;
                break;
            }
        }
        if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut).stripTrailing();
    }

    private static String task(PromptScene scene) {
        if (scene == null) {
            return "未指定任务";
        }
        return switch (scene) {
            case CHAPTER_PLAN -> "规划章节大纲";
            case CHAPTER_CONTENT -> "撰写章节正文";
            case CHAPTER_REVISE -> "修订章节正文";
            case CHAPTER_AUDIT -> "审校章节正文";
            case STAGE_BLUEPRINT -> "制定阶段蓝图";
            case VOLUME_BLUEPRINT -> "制定卷蓝图";
            case SETTING_DRAFT -> "生成设定集草稿";
        };
    }
}
