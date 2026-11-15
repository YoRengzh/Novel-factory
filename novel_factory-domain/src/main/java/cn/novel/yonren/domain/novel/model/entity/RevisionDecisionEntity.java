package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 章节修订决策
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevisionDecisionEntity {

    /** 是否尝试过修订 */
    private boolean attempted;

    /** 修订稿是否被采纳 */
    private boolean accepted;

    /** 采纳/拒绝原因 */
    private String reason;

    /** 计划与可信事实不能同时成立时停止修订，不把它当作普通稿件失败重试。 */
    @Builder.Default
    private boolean planAdjustmentRequired = false;

    /** 复核专用候选：修订造成的计划事件词面缺失，不能混入旧 BLOCKING 改变其索引。 */
    @Builder.Default
    private java.util.List<String> reviewCandidates = java.util.List.of();

}
