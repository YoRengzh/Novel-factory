package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.JobStatus;
import cn.novel.yonren.types.exception.PlanApprovalSuspendedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * 章节计划审批门测试：开关判定（默认关闭、同步路径不支持、作用范围）、挂起时的落盘与状态迁移、
 * 状态迁移失败时退化为直连正文（并发取消场景）。
 */
class PlanApprovalGateNodeTest {

    @Spy
    private StoryProperties storyProperties = new StoryProperties();

    @Mock
    private GenerateChapterContentNode generateChapterContentNode;

    @InjectMocks
    private PlanApprovalGateNode node;

    private ArmoryCommandEntity command;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        command = ArmoryCommandEntity.builder().build();
    }

    private StoryProperties.PlanApprovalProperties config() {
        return storyProperties.getPlanApproval();
    }

    private GenerationJob runningJob(int batchRound) {
        GenerationJob job = new GenerationJob("job-1");
        job.setBatchRound(batchRound);
        assertTrue(job.markRunning());
        return job;
    }

    private DefaultArmoryFactory.DynamicContext context(GenerationJob job, boolean planApproved) {
        DefaultArmoryFactory.DynamicContext ctx = new DefaultArmoryFactory.DynamicContext();
        ctx.setJob(job);
        ctx.setPlanApproved(planApproved);
        ctx.setChapterPlanAggregate(ChapterPlanAggregate.builder()
                .storyId("story-1").chapters(List.of()).build());
        return ctx;
    }

    @Test
    void shouldSuspend_disabledByDefault_false() {
        assertFalse(config().isEnabled(), "开关必须默认关闭，否则会改变所有既有作业的行为");
        assertFalse(node.shouldSuspend(context(runningJob(1), false)));
    }

    @Test
    void shouldSuspend_syncPathWithoutJob_false() {
        config().setEnabled(true);
        assertFalse(node.shouldSuspend(context(null, false)),
                "同步端点会阻塞到全批结束，挂起必撞 HTTP 超时，故不支持");
    }

    @Test
    void shouldSuspend_planAlreadyApproved_false() {
        config().setEnabled(true);
        assertFalse(node.shouldSuspend(context(runningJob(1), true)));
    }

    @Test
    void shouldSuspend_firstBatchOnly_scopeRespected() {
        config().setEnabled(true);
        assertTrue(node.shouldSuspend(context(runningJob(1), false)));
        assertFalse(node.shouldSuspend(context(runningJob(2), false)),
                "FIRST_BATCH_ONLY 只拦首批，否则与自续批每批都停下来等人");
    }

    @Test
    void shouldSuspend_everyBatch_coversLaterBatches() {
        config().setEnabled(true);
        config().setScope("EVERY_BATCH");
        assertTrue(node.shouldSuspend(context(runningJob(3), false)));
    }

    @Test
    void shouldSuspend_scopeIsCaseInsensitive() {
        config().setEnabled(true);
        config().setScope(" every_batch ");
        assertTrue(node.shouldSuspend(context(runningJob(3), false)));
    }

    @Test
    void doApply_gateEnabled_suspendsAfterPersistingPlan() throws Exception {
        config().setEnabled(true);
        config().setTimeoutSeconds(120L);
        GenerationJob job = runningJob(1);
        DefaultArmoryFactory.DynamicContext ctx = context(job, false);
        long before = System.currentTimeMillis();

        assertThrows(PlanApprovalSuspendedException.class, () -> node.doApply(command, ctx));

        verify(generateChapterContentNode).preparePlanCheckpoint(ctx);
        assertEquals(JobStatus.AWAITING_APPROVAL, job.getStatus());
        assertTrue(job.getPlanApprovalDeadlineMs() >= before + 120_000L,
                "截止时刻必须按配置的超时秒数外推");
    }

    @Test
    void doApply_stateTransitionFails_fallsThroughToContent() throws Exception {
        config().setEnabled(true);
        GenerationJob job = runningJob(1);
        // 并发取消把作业推向 CANCELLING：此时不该再弹审批，直接放行让正文循环自行感知取消
        assertTrue(job.markCancelling());

        node.doApply(command, context(job, false));

        verify(generateChapterContentNode).apply(any(), any());
    }

    @Test
    void doApply_gateDisabled_goesStraightToContent() throws Exception {
        node.doApply(command, context(runningJob(1), false));
        verify(generateChapterContentNode).apply(any(), any());
    }

    /**
     * 挂起不消耗规划产物：计划条目原样保留在上下文里，恢复时无需重新规划
     */
    @Test
    void doApply_suspend_keepsPlanIntact() throws Exception {
        config().setEnabled(true);
        GenerationJob job = runningJob(1);
        DefaultArmoryFactory.DynamicContext ctx = context(job, false);
        List<ChapterPlanItemEntity> chapters = new ArrayList<>(List.of(ChapterPlanItemEntity.builder()
                .chapterNo(1).title("第一章").goal("目标").keyEvents(List.of("事件"))
                .chapterType(ChapterType.NORMAL).build()));
        ctx.setChapterPlanAggregate(ChapterPlanAggregate.builder().storyId("story-1").chapters(chapters).build());

        assertThrows(PlanApprovalSuspendedException.class, () -> node.doApply(command, ctx));

        assertEquals(1, ctx.getChapterPlanAggregate().getChapters().size());
        assertEquals("第一章", ctx.getChapterPlanAggregate().getChapters().get(0).getTitle());
    }

    @Test
    @DisplayName("请求级开关：autoApprovePlan=true 时本批不挂起（门开着也透传）")
    void shouldSuspend_requestLevelSwitchBypassesGate() {
        StoryProperties.PlanApprovalProperties cfg = new StoryProperties.PlanApprovalProperties();
        cfg.setEnabled(true);
        cfg.setScope("EVERY_BATCH");
        org.mockito.Mockito.doReturn(cfg).when(storyProperties).getPlanApproval();

        ArmoryCommandEntity withSwitch = ArmoryCommandEntity.builder().autoApprovePlan(Boolean.TRUE).build();
        assertFalse(node.shouldSuspend(withSwitch, context(runningJob(1), false)),
                "autoApprovePlan=true 应跳过审批门");

        // 未传/false 时仍按 yml 走（本批首批 → 挂起）
        assertTrue(node.shouldSuspend(ArmoryCommandEntity.builder().build(), context(runningJob(1), false)),
                "未传开关时配置照常生效");
        assertTrue(node.shouldSuspend(
                        ArmoryCommandEntity.builder().autoApprovePlan(Boolean.FALSE).build(),
                        context(runningJob(1), false)),
                "false 不得收紧也不得放宽配置意图");
    }
}
