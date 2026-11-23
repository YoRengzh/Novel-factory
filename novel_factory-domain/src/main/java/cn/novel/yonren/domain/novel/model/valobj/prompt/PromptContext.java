package cn.novel.yonren.domain.novel.model.valobj.prompt;

import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 提示词装配上下文：携带判定规则命中所需的全部信息
 */
@Data
@Builder
public class PromptContext {

    /** 主题关键词（theme，如"都市异能"） */
    private String theme;

    /** 风格关键词（style，如"逆袭打脸爽文"） */
    private String style;

    /** 当前章号（正文场景使用，大纲场景为 null） */
    private Integer chapterNo;

    /** 总章数 */
    private Integer totalChapters;

    /** 章节类型（正文场景使用） */
    private ChapterType chapterType;

    /** 本章要点（goal+keyEvents 摘要，正文场景用于资料检索与选择的语义细分；可为 null） */
    private String chapterBrief;

    /**
     * 本次实际重生成的目标字段（设定草稿场景使用）。
     * 为空表示整份生成；非空时资料资格只保留与这些字段相关的条目。
     */
    private List<String> targetFields = List.of();

    /** 轻装正文模式（DIRECT）：正文写手仅接收极简文风锚点与视角边界，跳过冗长规则手册与动态写作教材。 */
    @Builder.Default
    private boolean directWriterMode = false;

    public static boolean isDirectWriter(PromptScene scene, PromptContext ctx) {
        return scene == PromptScene.CHAPTER_CONTENT && ctx != null && ctx.isDirectWriterMode();
    }

}
