package cn.novel.yonren.domain.novel.model.aggregate;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 章节计划聚合根
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterPlanAggregate {

    private String storyId;

    private List<ChapterPlanItemEntity> chapters;

    /** 分支推演时本版规划的风险自评（2-3 条；单稿路径为 null），供评审择优与复盘 */
    private List<String> risks;

}
