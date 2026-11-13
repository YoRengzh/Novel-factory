package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanPromptService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 装配章节计划 prompt 节点：批次级编排——计算批次是否跨阶段分段，
 * 逐段委托 {@link ChapterPlanPromptService} 组装 prompt（每段注入归属该段的蓝图）。
 * 续写批次注入完整记忆前缀（近章摘要 + 三账本 + 伏笔账 + 上章偏差 + 上章结尾原文），
 * 要求计划输出全局章节号并无缝承接前情
 */
@Service
@Slf4j
public class BuildChapterPlanPromptNode extends AbstractArmorySupport {

    @Resource
    private CallChapterPlanLlmNode callChapterPlanLlmNode;

    @Resource
    private ChapterPlanPromptService planPromptService;

    @Resource
    private RollingOutlineService rollingOutlineService;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return callChapterPlanLlmNode;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 BuildChapterPlanPromptNode - 构建章节计划提示词");

        int batchStart = dynamicContext.getChapterOffset() + 1;
        int batchEnd = batchStart + requestParameter.getStoryContextEntity().getChapterCount() - 1;
        List<RollingOutlineService.StageSegment> segments = computeSegments(dynamicContext, batchStart, batchEnd);

        // 复用蓝图节点已写入的 usedPromptMap（blueprint: 前缀），蓝图与计划的 prompt 一并供复盘
        Map<String, String> usedPromptMap = dynamicContext.getUsedPromptMap() != null
                ? dynamicContext.getUsedPromptMap() : new HashMap<>();
        usedPromptMap.put("system", "你是一名资深网络小说策划编辑。");

        if (segments.size() <= 1) {
            // 单段批次：整批一次规划（与历史行为一致）
            String prompt = planPromptService.buildPlanPrompt(dynamicContext, batchStart, batchEnd,
                    segments.isEmpty() ? null : segments.get(0).blueprint(), true,
                    requestParameter.getStoryVO().getModule(),
                    requestParameter.getStoryContextEntity().getWorldId());
            dynamicContext.setPrompt(prompt);
            dynamicContext.setPlanSegments(null);
            dynamicContext.setPlanSegmentPrompts(null);
            dynamicContext.setPlannedSegmentEnd(batchEnd);
            dynamicContext.setPlannedChapterCount(requestParameter.getStoryContextEntity().getChapterCount());
            usedPromptMap.put("user", prompt);
        } else {
            // 跨阶段批次：惰性分段规划——仅装配首段 prompt，后续段在生成推进到段边界时
            // 用最新记忆即时规划（消除"批次开头一次性规划、后段记忆过旧"的陈旧问题）。
            // 阶段蓝图链仍在此处一次生成（方向层对陈旧不敏感），章级规划按段惰性执行。
            RollingOutlineService.StageSegment first = segments.get(0);
            String firstPrompt = planPromptService.buildPlanPrompt(dynamicContext, first.startChapter(), first.endChapter(),
                    first.blueprint(), first.endChapter() >= batchEnd,
                    requestParameter.getStoryVO().getModule(),
                    requestParameter.getStoryContextEntity().getWorldId());
            dynamicContext.setPrompt(firstPrompt);
            dynamicContext.setPlanSegments(segments);
            dynamicContext.setPlanSegmentPrompts(new ArrayList<>(List.of(firstPrompt)));
            dynamicContext.setPlannedSegmentEnd(first.endChapter());
            dynamicContext.setPlannedChapterCount(first.endChapter() - batchStart + 1);
            usedPromptMap.put("user:第" + first.startChapter() + "-" + first.endChapter() + "章", firstPrompt);
            log.info("批次跨越阶段边界，分段惰性规划：首段 {}-{} 已装配，余 {} 段待生成推进后规划",
                    first.startChapter(), first.endChapter(), segments.size() - 1);
        }
        dynamicContext.setUsedPromptMap(usedPromptMap);

        return router(requestParameter, dynamicContext);
    }

    /** 按阶段蓝图覆盖区间切分批次（包级可见供单测） */
    List<RollingOutlineService.StageSegment> computeSegments(DefaultArmoryFactory.DynamicContext dynamicContext,
                                                             int batchStart, int batchEnd) {
        return rollingOutlineService.segmentBatch(dynamicContext.getStageBlueprints(), batchStart, batchEnd);
    }
}
