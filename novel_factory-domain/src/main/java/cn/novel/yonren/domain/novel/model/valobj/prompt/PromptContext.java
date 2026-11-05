package cn.novel.yonren.domain.novel.model.valobj.prompt;

import cn.novel.yonren.types.enums.ChapterType;
import lombok.Builder;
import lombok.Data;

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

}
