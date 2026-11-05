package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.StoryGenerateService;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.types.enums.JobStatus;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.exception.PlanApprovalSuspendedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节计划审批门在作业编排层的行为测试：
 * 挂起不置失败且释放线程、裁决通过后续跑剩余阶段、修订稿未过机械校验则拒绝且保持挂起、
 * 驳回中止、超时按 AUTO_APPROVE 退化成黑盒。
 *
 * 本测试模拟审批门节点的契约：先 {@code markAwaitingApproval}（**携带待裁决计划**）再抛挂起信号
 * （真实场景由 PlanApprovalGateNode 完成，见 PlanApprovalGateNodeTest）。
 * 计划与状态一次性交接是"状态可见 ⇒ 计划可读"不变式的依据，故断言无需等待服务层登记
 */
class StoryJobServicePlanApprovalTest {

    private StoryGenerateService storyGenerateService;
    private JobRegistry jobRegistry;
    private IStoryRepository storyRepository;
    private RunPlanService runPlanService;
    private ThreadPoolExecutor executor;
    private StoryProperties storyProperties;
    private StoryJobService storyJobService;

    private final AtomicReference<DefaultArmoryFactory.DynamicContext> capturedContext = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        storyGenerateService = mock(StoryGenerateService.class);
        jobRegistry = new JobRegistry();
        storyRepository = mock(IStoryRepository.class);
        runPlanService = mock(RunPlanService.class);
        storyProperties = new StoryProperties();
        executor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
        storyJobService = new StoryJobService(storyGenerateService, jobRegistry, storyRepository, executor,
                runPlanService, mock(cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService.class),
                storyProperties);

        when(storyGenerateService.newDynamicContext(any())).thenAnswer(inv -> {
            DefaultArmoryFactory.DynamicContext ctx = new DefaultArmoryFactory.DynamicContext();
            ctx.setJob(inv.getArgument(0));
            capturedContext.set(ctx);
            return ctx;
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private ArmoryCommandEntity command() {
        return ArmoryCommandEntity.builder()
                .storyContextEntity(cn.novel.yonren.domain.novel.model.entity.StoryContextEntity.builder()
                        .novel_title("测试").chapterCount(2).build())
                .build();
    }

    /** 模拟审批门节点：携待裁决计划置挂起态 + 抛控制信号（与生产契约一致） */
    private Answer<Object> suspendAnswer() {
        return inv -> {
            GenerationJob job = inv.getArgument(1);
            DefaultArmoryFactory.DynamicContext ctx = capturedContext.get();
            job.markAwaitingApproval(System.currentTimeMillis() + 3_600_000L,
                    ctx == null ? null : ctx.getChapterPlanAggregate());
            throw new PlanApprovalSuspendedException("章节计划审批门挂起");
        };
    }

    private ChapterPlanAggregate plan(int from, int count) {
        List<ChapterPlanItemEntity> items = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(ChapterPlanItemEntity.builder()
                    .chapterNo(from + i).title("第" + (from + i) + "章").goal("目标")
                    .keyEvents(List.of("事件")).build());
        }
        return ChapterPlanAggregate.builder().storyId("story-1").chapters(items).build();
    }

    private boolean waitForStatus(String jobId, JobStatus expected) {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            GenerationJob job = jobRegistry.get(jobId);
            if (job != null && job.getStatus() == expected) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 审批裁决动作的重试助手：状态置位（树内 markAwaitingApproval，挂起异常抛出前）与
     * 待裁决记录落表（runJob 捕获异常后的 registerPendingApproval）非原子——状态先可见，
     * 记录微窗口后落表。真实 HTTP 客户端对该窗口的语义就是重试（claimPending 原子取走后
     * 重复调用只会得到 null，不会重复推进），测试同样容忍。
     * 校验类 AppException（如修订稿章数不符）不属于该窗口，原样上抛给 assertThrows 断言
     */
    private <T> T awaitPendingReady(java.util.function.Supplier<T> action) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            T result = action.get();
            if (result != null) {
                return result;
            }
            Thread.sleep(10);
        }
        return null;
    }

    @Test
    void suspend_keepsJobAliveAndReleasesWorkerThread() throws Exception {
        // 用调用计数切换行为，而不是二次打桩：Mockito 的 when(mock.method()) 会先执行已有答案，
        // 二次打桩会当场触发挂起答案（参数为匹配器占位值），表现为极难定位的 NPE
        AtomicInteger calls = new AtomicInteger();
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                GenerationJob suspended = inv.getArgument(1);
                suspended.markAwaitingApproval(System.currentTimeMillis() + 3_600_000L);
                throw new PlanApprovalSuspendedException("章节计划审批门挂起");
            }
            return StoryGenerateResultAggregate.builder().storyDirName("d").build();
        });

        GenerationJob job = storyJobService.submit(command());
        assertTrue(waitForStatus(job.getJobId(), JobStatus.AWAITING_APPROVAL),
                "挂起必须落在 AWAITING_APPROVAL，而不是 FAILED");

        assertNull(job.getErrorMessage(), "挂起不是失败，不许写错误信息");
        assertNotNull(job.getPlanApprovalDeadlineMs());

        // 线程已归还：队列里再投一个作业能立刻跑起来（若被 sleep/await 占住则此处必然超时）
        GenerationJob second = storyJobService.submit(command());
        assertTrue(waitForStatus(second.getJobId(), JobStatus.COMPLETED),
                "挂起不得占用唯一的 worker 线程：否则第二个作业永远排不上队");
    }

    @Test
    void approve_withoutEdit_resumesRemainingStagesWithOriginalPlan() throws Exception {
        ChapterPlanAggregate original = plan(1, 2);
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            GenerationJob job = inv.getArgument(1);
            capturedContext.get().setChapterPlanAggregate(original);
            capturedContext.get().setPlannedChapterCount(2);
            job.markAwaitingApproval(System.currentTimeMillis() + 3_600_000L, original);
            throw new PlanApprovalSuspendedException("挂起");
        });
        when(storyGenerateService.resumeAfterPlanApproval(any(), any(), any()))
                .thenReturn(StoryGenerateResultAggregate.builder().storyDirName("d").build());

        GenerationJob job = storyJobService.submit(command());
        assertTrue(waitForStatus(job.getJobId(), JobStatus.AWAITING_APPROVAL));
        // 不变式：状态可见 ⇒ 待裁决计划可读（计划随状态一次性交接，不依赖服务层登记的时序）
        assertEquals(2, ((ChapterPlanAggregate) storyJobService.pendingChapterPlan(job.getJobId())).getChapters().size());

        // registerPendingApproval（runJob 捕获异常后落表）与状态置位（树内 markAwaitingApproval）非原子：
        // 状态先可见，待裁决记录微窗口后落表——真实客户端语义就是重试，测试同样容忍（见 awaitPendingReady）
        GenerationJob approved = awaitPendingReady(() -> storyJobService.approveChapterPlan(job.getJobId(), null));

        assertNotNull(approved);
        assertTrue(waitForStatus(job.getJobId(), JobStatus.COMPLETED));
        verify(storyGenerateService).resumeAfterPlanApproval(any(), any(), any());
        assertEquals(1, job.getApprovalRound());
        assertNull(storyJobService.pendingChapterPlan(job.getJobId()), "裁决后待裁决记录必须清空");
    }

    @Test
    void approve_withEditedPlan_passesEditedPlanToResume() throws Exception {
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            GenerationJob job = inv.getArgument(1);
            capturedContext.get().setChapterPlanAggregate(plan(1, 2));
            capturedContext.get().setPlannedChapterCount(2);
            job.markAwaitingApproval(System.currentTimeMillis() + 3_600_000L);
            throw new PlanApprovalSuspendedException("挂起");
        });
        when(storyGenerateService.resumeAfterPlanApproval(any(), any(), any()))
                .thenReturn(StoryGenerateResultAggregate.builder().storyDirName("d").build());

        GenerationJob job = storyJobService.submit(command());
        assertTrue(waitForStatus(job.getJobId(), JobStatus.AWAITING_APPROVAL));

        ChapterPlanAggregate edited = plan(1, 2);
        edited.getChapters().get(0).setTitle("人工改过的标题");
        assertNotNull(awaitPendingReady(() -> storyJobService.approveChapterPlan(job.getJobId(), edited)));

        assertTrue(waitForStatus(job.getJobId(), JobStatus.COMPLETED));
        assertEquals("人工改过的标题",
                capturedContext.get().getChapterPlanAggregate().getChapters().get(0).getTitle(),
                "人工修订稿必须原样进入续跑上下文，不能被原版覆盖");
    }

    @Test
    void approve_withInvalidEditedPlan_throwsAndStaysAwaiting() throws Exception {
        ChapterPlanAggregate original = plan(1, 2);
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            GenerationJob job = inv.getArgument(1);
            capturedContext.get().setChapterPlanAggregate(original);
            capturedContext.get().setPlannedChapterCount(2);
            job.markAwaitingApproval(System.currentTimeMillis() + 3_600_000L, original);
            throw new PlanApprovalSuspendedException("挂起");
        });

        GenerationJob job = storyJobService.submit(command());
        assertTrue(waitForStatus(job.getJobId(), JobStatus.AWAITING_APPROVAL));

        // 只改标题却删掉一章：章数不符，必须被机械校验拦住
        ChapterPlanAggregate broken = plan(1, 1);
        AppException ex = assertThrows(AppException.class,
                () -> awaitPendingReady(() -> storyJobService.approveChapterPlan(job.getJobId(), broken)));
        assertTrue(ex.getInfo().contains("章数不符"), "报错要说清是章数问题，实际：" + ex.getInfo());

        assertEquals(JobStatus.AWAITING_APPROVAL, job.getStatus(), "校验失败必须保持挂起，等用户改正后重提");
        assertNotNull(storyJobService.pendingChapterPlan(job.getJobId()), "校验失败不得吃掉待裁决记录");
    }

    @Test
    void reject_cancelsBatchWithDistinctStage() throws Exception {
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(suspendAnswer());

        GenerationJob job = storyJobService.submit(command());
        assertTrue(waitForStatus(job.getJobId(), JobStatus.AWAITING_APPROVAL));

        assertNotNull(awaitPendingReady(() -> storyJobService.rejectChapterPlan(job.getJobId())));
        assertEquals(JobStatus.CANCELLED, job.getStatus());
        assertEquals("PLAN_REJECTED", job.getCurrentStage());
        assertNull(storyJobService.pendingChapterPlan(job.getJobId()));
    }

    @Test
    void approve_onNonAwaitingJob_returnsNull() {
        assertNull(storyJobService.approveChapterPlan("job-unknown", null));
        assertNull(storyJobService.rejectChapterPlan("job-unknown"));
    }

    @Test
    void timeout_autoApprove_resumesWithOriginalPlan() throws Exception {
        storyProperties.getPlanApproval().setTimeoutSeconds(1L);
        storyProperties.getPlanApproval().setTimeoutAction("AUTO_APPROVE");
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            GenerationJob job = inv.getArgument(1);
            capturedContext.get().setChapterPlanAggregate(plan(1, 2));
            capturedContext.get().setPlannedChapterCount(2);
            job.markAwaitingApproval(System.currentTimeMillis() + 1_000L);
            throw new PlanApprovalSuspendedException("挂起");
        });
        when(storyGenerateService.resumeAfterPlanApproval(any(), any(), any()))
                .thenReturn(StoryGenerateResultAggregate.builder().storyDirName("d").build());

        GenerationJob job = storyJobService.submit(command());
        assertTrue(waitForStatus(job.getJobId(), JobStatus.AWAITING_APPROVAL));

        // 超时由独立计时器线程触发（不占用 jobExecutor），按 AUTO_APPROVE 采纳 AI 原版继续
        assertTrue(waitForStatus(job.getJobId(), JobStatus.COMPLETED),
                "超时必须能自动推进到终态，否则作业会永远僵在挂起态");
        verify(storyGenerateService).resumeAfterPlanApproval(any(), any(), any());
        assertEquals(0, job.getApprovalRound(), "超时放行不计入人工裁决轮次");
    }

    @Test
    void timeout_abort_cancelsWithoutResume() throws Exception {
        storyProperties.getPlanApproval().setTimeoutSeconds(1L);
        storyProperties.getPlanApproval().setTimeoutAction("ABORT");
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(suspendAnswer());

        GenerationJob job = storyJobService.submit(command());
        assertTrue(waitForStatus(job.getJobId(), JobStatus.AWAITING_APPROVAL));
        assertTrue(waitForStatus(job.getJobId(), JobStatus.CANCELLED));
        assertEquals("PLAN_APPROVAL_TIMEOUT_ABORTED", job.getCurrentStage());
    }
}
