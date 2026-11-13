package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanChecks;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 校验章节计划节点：结构校验委托 {@link ChapterPlanChecks}
 * （章数、起始章号连续性、必填字段、编号唯一性的唯一实现，与规划执行器共用）
 */
@Service
@Slf4j
public class ValidateChapterPlanNode extends AbstractArmorySupport {

    @Resource
    private GenerateChapterContentNode generateChapterContentNode;

    @Override
    protected StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 ValidateChapterPlanNode - 校验章节计划");

        // 惰性分段规划：首段规划后即校验，预期章数 = 已规划段落的章数（后续段在段边界各自校验）；
        // 单段批次 plannedChapterCount = 批次章数，与历史行为一致
        Integer plannedChapterCount = dynamicContext.getPlannedChapterCount();
        // 续写批次计划输出全局编号，校验起点 = 历史最大章号 + 1
        ChapterPlanAggregate aggregate = dynamicContext.getChapterPlanAggregate();
        ChapterPlanChecks.validateSegmentStructure(aggregate == null ? null : aggregate.getChapters(),
                plannedChapterCount != null ? plannedChapterCount
                        : requestParameter.getStoryContextEntity().getChapterCount(),
                dynamicContext.getChapterOffset() + 1);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 校验通过即进入正文生成：正式生成走有界自动处理，不设人工裁决点
        return generateChapterContentNode;
    }
}
