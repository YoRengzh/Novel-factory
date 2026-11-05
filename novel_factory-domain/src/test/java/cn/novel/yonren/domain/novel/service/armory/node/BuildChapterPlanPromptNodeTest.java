package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanPromptService;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 装配章节计划 prompt 节点测试：节点只保留批次级编排（分段计算 + 审批门整批提前规划），
 * prompt 装配内容的测试见 ChapterPlanPromptServiceTest
 */
class BuildChapterPlanPromptNodeTest {

    @Mock
    private CallChapterPlanLlmNode callChapterPlanLlmNode;

    @Mock
    private ChapterPlanPromptService planPromptService;

    /** 卷链语义：用真实实现（spy）——分段规则由 RollingOutlineServiceTest 覆盖 */
    @Spy
    private RollingOutlineService rollingOutlineService = new RollingOutlineService();

    /** 审批门配置：真实实例（spy），逐用例改 enabled/scope */
    @Spy
    private StoryProperties storyProperties = new StoryProperties();

    @InjectMocks
    private BuildChapterPlanPromptNode node;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void computeSegments_splitsBatchByStageBlueprints() {
        StageBlueprintEntity stage1 = blueprint(1, 1, 10);
        StageBlueprintEntity stage2 = blueprint(2, 11, 55);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(7)
                .stageBlueprints(new ArrayList<>(List.of(stage1, stage2)))
                .build();

        List<RollingOutlineService.StageSegment> segments = node.computeSegments(ctx, 8, 17);

        assertEquals(2, segments.size());
        assertEquals(8, segments.get(0).startChapter());
        assertEquals(10, segments.get(0).endChapter());
        assertSame(stage1, segments.get(0).blueprint());
        assertEquals(11, segments.get(1).startChapter());
        assertEquals(17, segments.get(1).endChapter());
        assertSame(stage2, segments.get(1).blueprint());
    }

    // ---- 审批门整批提前规划（R3）：门开且本批在作用范围内时，人工裁决覆盖全部段落 ----

    @Test
    void doApply_gateEnabledInScope_plansAllSegmentsEagerly() throws Exception {
        storyProperties.getPlanApproval().setEnabled(true);
        when(planPromptService.buildPlanPrompt(any(), anyInt(), anyInt(), any(), anyBoolean(), any(), any()))
                .thenReturn("段prompt");

        DefaultArmoryFactory.DynamicContext ctx = crossStageContext(new GenerationJob("job-1"));
        node.doApply(command(), ctx);

        assertEquals(2, ctx.getPlanSegmentPrompts().size(), "两段 prompt 必须一次装配齐");
        assertEquals(4, ctx.getPlannedSegmentEnd(), "已规划区间覆盖整批，审批通过后不再触发惰性规划");
        assertEquals(4, ctx.getPlannedChapterCount(), "审批校验按整批章数执行");
    }

    @Test
    void doApply_gateDisabled_keepsLazyFirstSegmentOnly() throws Exception {
        // 门关闭（默认）：跨段批次保持惰性分段——只装配首段
        when(planPromptService.buildPlanPrompt(any(), anyInt(), anyInt(), any(), anyBoolean(), any(), any()))
                .thenReturn("段prompt");

        DefaultArmoryFactory.DynamicContext ctx = crossStageContext(new GenerationJob("job-1"));
        node.doApply(command(), ctx);

        assertEquals(1, ctx.getPlanSegmentPrompts().size());
        assertEquals(2, ctx.getPlannedSegmentEnd());
        assertEquals(2, ctx.getPlannedChapterCount());
    }

    @Test
    void doApply_gateEnabledButOutOfScope_keepsLazyFirstSegmentOnly() throws Exception {
        // FIRST_BATCH_ONLY（默认 scope）下第 2 批不挂起：无需整批提前规划
        storyProperties.getPlanApproval().setEnabled(true);
        GenerationJob secondBatch = new GenerationJob("job-2");
        secondBatch.setBatchRound(2);
        when(planPromptService.buildPlanPrompt(any(), anyInt(), anyInt(), any(), anyBoolean(), any(), any()))
                .thenReturn("段prompt");

        DefaultArmoryFactory.DynamicContext ctx = crossStageContext(secondBatch);
        node.doApply(command(), ctx);

        assertEquals(1, ctx.getPlanSegmentPrompts().size());
        assertEquals(2, ctx.getPlannedSegmentEnd());
    }

    private DefaultArmoryFactory.DynamicContext crossStageContext(GenerationJob job) {
        return DefaultArmoryFactory.DynamicContext.builder()
                .chapterOffset(0)
                .stageBlueprints(new ArrayList<>(List.of(
                        blueprint(1, 1, 2), blueprint(2, 3, 4))))
                .job(job)
                .build();
    }

    private ArmoryCommandEntity command() {
        return ArmoryCommandEntity.builder()
                .storyVO(new StoryVO())
                .storyContextEntity(StoryContextEntity.builder().theme("题材").style("风格").chapterCount(4).build())
                .build();
    }

    private StageBlueprintEntity blueprint(int stageNo, int start, Integer end) {
        return StageBlueprintEntity.builder()
                .stageNo(stageNo)
                .startChapter(start)
                .endChapter(end)
                .build();
    }
}
