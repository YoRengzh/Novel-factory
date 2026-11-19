package cn.novel.yonren.domain.novel.model.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 章节审校问题项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterIssueEntity {

    public static final String PENDING_FACT_PREFIX = "待核实：";

    /** 审校维度：consistency/character/pacing/continuity/foreshadow/hook/aesthetic */
    private String dimension;

    /** 严重程度：BLOCKING / MINOR */
    private String severity;

    /** 原文引用（证据） */
    private String evidence;

    /** 用于对照事实矛盾的连续原句，旧记录可以没有。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String factEvidence;

    /** 本章明确的揭晓上限原句，来源是当前计划。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String planEvidence;

    /** 问题描述 */
    private String description;

    /** 修改建议 */
    private String suggestion;

}
