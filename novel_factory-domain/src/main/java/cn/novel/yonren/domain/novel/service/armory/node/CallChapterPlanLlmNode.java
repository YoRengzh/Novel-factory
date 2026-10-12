package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanSegmentPlanner;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用章节计划模型节点：规划执行的完整闭环（分支推演/三级降级/结构校验/编号校正/
 * 主线推进闸门）已下沉至 {@link ChapterPlanSegmentPlanner}，本节点只做批次级编排——
 * 单段批次合成整批一段，跨段批次逐段调用后合并为整批计划。
 * 批次正好落在一个阶段内（最常见情形）时计划整段由这里产出，worker 的惰性分段规划不会被触发
 */
@Service
@Slf4j
public class CallChapterPlanLlmNode extends AbstractArmorySupport {

    private final ChapterPlanSegmentPlanner planner;

    public CallChapterPlanLlmNode(ChapterPlanSegmentPlanner planner) {
        this.planner = planner;
    }

    @Resource
    private ParseChapterPlanNode parseChapterPlanNode;

    @Resource
    private ChapterMemoryService chapterMemoryService;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return parseChapterPlanNode;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 CallChapterPlanLlmNode - 调用章节计划模型");

        // 规划期协作取消/熔断检查 补）：内容循环只在章界检查，而规划一旦开始
        // 可能连续 10-20 分钟（强制思考模型）——若入口不检查，"取消"在整个规划期都不生效，
        // 用户按停止后优雅关闭还会等待挂起的 HTTP 调用，表现为"彻底卡死"
        GenerationJob planJob = dynamicContext == null ? null : dynamicContext.getJob();
        if (planJob != null && (planJob.isCancelRequested() || planJob.isBudgetExceeded())) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "作业在规划阶段前已请求取消/触发熔断，停止规划（已生成章节不受影响，可 resume 续写）");
        }
        if (planJob != null) {
            // 规划期进度可见：此前 job-status 在整个规划期静止，"卡死"与"在跑"无法区分
            planJob.updateProgress("CHAPTER_PLAN", dynamicContext.getChapterOffset() + 1,
                    dynamicContext.getChapterOffset() + requestParameter.getStoryContextEntity().getChapterCount(),
                    dynamicContext.getStoryDir() == null ? null : dynamicContext.getStoryDir().getFileName().toString());
        }

        StoryContextEntity storyContext = requestParameter.getStoryContextEntity();
        PromptContext ctx = PromptContext.builder()
                .theme(storyContext.getTheme())
                .style(storyContext.getStyle())
                .totalChapters(storyContext.getChapterCount())
                .build();

        invokePlanWithFallback(requestParameter, dynamicContext, ctx);

        return router(requestParameter, dynamicContext);
    }

    /**
     * 调用 + 三级降级闭环（执行细节在 planner）。惰性分段规划：跨阶段批次仅调用已装配
     * prompt 的段（首段；后续段由 worker 在段边界用最新记忆经 planner 惰性规划），段内
     * 编号校正后合并。成功后 rawResult 与解析结果均写入 dynamicContext
     */
    ChapterPlanAggregate invokePlanWithFallback(ArmoryCommandEntity requestParameter,
                                                DefaultArmoryFactory.DynamicContext dynamicContext,
                                                PromptContext ctx) {
        // 对话承载闸门的人名来源：角色账本名册（新书首段摘要为空 → 空名册，闸门自动降级）
        List<String> rosterNames = chapterMemoryService.characterRoster(dynamicContext.getChapterSummaries());
        List<RollingOutlineService.StageSegment> segments = dynamicContext.getPlanSegments();
        if (segments == null || segments.size() <= 1) {
            // 单段批次：合成覆盖整批的一段走规划器——分支推演/降级重试/闸门口径与分段路径完全一致
            int batchStart = dynamicContext.getChapterOffset() + 1;
            int batchEnd = batchStart + requestParameter.getStoryContextEntity().getChapterCount() - 1;
            ChapterPlanSegmentPlanner.PlannedSegment planned = planner.planSegment(
                    requestParameter.getStoryVO(), ctx, dynamicContext.getPrompt(),
                    batchStart, batchEnd, dynamicContext.getStageBlueprint(), rosterNames,
                    dynamicContext.getUsedPromptMap());
            dynamicContext.setRawResult(planned.raw());
            dynamicContext.setChapterPlanAggregate(planned.plan());
            return planned.plan();
        }

        List<String> prompts = dynamicContext.getPlanSegmentPrompts();
        List<ChapterPlanItemEntity> mergedChapters = new ArrayList<>();
        List<String> rawSegments = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            RollingOutlineService.StageSegment segment = segments.get(i);
            ChapterPlanSegmentPlanner.PlannedSegment parsed = planner.planSegment(
                    requestParameter.getStoryVO(), ctx, prompts.get(i),
                    segment.startChapter(), segment.endChapter(), segment.blueprint(), rosterNames,
                    dynamicContext.getUsedPromptMap());
            mergedChapters.addAll(parsed.plan().getChapters());
            rawSegments.add(parsed.raw());
        }

        ChapterPlanAggregate merged = new ChapterPlanAggregate();
        merged.setChapters(mergedChapters);
        dynamicContext.setRawResult(String.join("\n\n===== 分段计划原始输出 =====\n\n", rawSegments));
        dynamicContext.setChapterPlanAggregate(merged);
        return merged;
    }
}
