package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.StoryGenerateService;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作业编排测试：异步完成、失败透传、排队期取消跳树、运行期协作取消、队列满 503、MDC 清理。
 * 使用与生产同构的小线程池（core=max=1），等待通过轮询终态实现
 */
class StoryJobServiceTest {

    private StoryGenerateService storyGenerateService;
    private JobRegistry jobRegistry;
    private IStoryRepository storyRepository;
    private RunPlanService runPlanService;

    private cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService batchHealthService;

    private cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties storyProperties;

    private ThreadPoolExecutor executor;
    private StoryJobService storyJobService;

    @BeforeEach
    void setUp() {
        storyGenerateService = mock(StoryGenerateService.class);
        jobRegistry = new JobRegistry();
        storyRepository = mock(IStoryRepository.class);
        runPlanService = mock(RunPlanService.class);
        batchHealthService = mock(cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthService.class);
        // 审批门默认关闭的配置对象：本测试类针对作业编排，审批流程另见 StoryJobServicePlanApprovalTest
        storyProperties = new cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties();
        executor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
        storyJobService = new StoryJobService(storyGenerateService, jobRegistry, storyRepository, executor,
                runPlanService, batchHealthService, storyProperties);
        // worker 会先建上下文再进树：mock 下给一个真实上下文，避免 null 上下文掩盖参数装配缺陷
        when(storyGenerateService.newDynamicContext(any())).thenAnswer(inv -> {
            cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory.DynamicContext ctx =
                    new cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory.DynamicContext();
            ctx.setJob(inv.getArgument(0));
            return ctx;
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void submit_generatesAsyncAndCompletes() throws Exception {
        AtomicReference<String> mdcDuringGenerate = new AtomicReference<>();
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            mdcDuringGenerate.set(MDC.get("trace-id"));
            return StoryGenerateResultAggregate.builder().storyDirName("20260903-story-0001").build();
        });

        GenerationJob job = storyJobService.submit(ArmoryCommandEntity.builder().build());

        assertEquals(cn.novel.yonren.types.enums.JobStatus.CREATED, job.getStatus());
        assertNotNull(job.getJobId());
        assertTrue(job.getJobId().startsWith("job-"));
        awaitTerminal(job);
        assertEquals(cn.novel.yonren.types.enums.JobStatus.COMPLETED, job.getStatus());
        assertEquals("20260903-story-0001", job.getStoryDirName());
        // 生成期间 worker 线程 MDC 已与 jobId 关联（日志/usage 归因的前提）
        assertEquals(job.getJobId(), mdcDuringGenerate.get());
    }

    @Test
    void submit_rejectsSecondJobForSameStory() throws Exception {
        // 同一故事并发两个作业：后完成者会用陈旧上下文整表覆盖正文/摘要。
        // 审批门挂起时线程已归还，这条守卫是唯一能挡住“另一个续写作业插进来”的地方
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(300);   // 让第一个作业在提交第二个时仍在 RUNNING
            return StoryGenerateResultAggregate.builder().storyDirName("20260903-story-0001").build();
        });
        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .resumeStoryDir("20260903-story-0001").build();

        GenerationJob first = storyJobService.submit(command);

        assertThrows(AppException.class, () -> storyJobService.submit(command));
        awaitTerminal(first);
    }

    @Test
    void completedBatch_runPlanSaysContinue_submitsNextBatchWithIncrementedRound() throws Exception {
        // 自动续批（阶段六）：本批正常完成 → RunPlan 判定继续 → 再投一个子作业（批序号递增），
        // 直到判定停止为止。这是"无人值守"的核心回路，必须端到端跑通而不是只测决策函数
        when(storyGenerateService.generate(any(), any(), any()))
                .thenReturn(StoryGenerateResultAggregate.builder().storyDirName("20260903-story-0001").build());
        when(storyRepository.resolveStoryDirectory("20260903-story-0001"))
                .thenReturn(Path.of("docs/workspace/stories/20260903-story-0001"));
        when(storyRepository.readChapterNumbers(any())).thenReturn(List.of(1, 2, 3));
        // ChapterWorker 走 4 参、StoryJobService 走 5 参：两处都桩上，避免静默返回 null 失去断言意义
        when(batchHealthService.assess(any(), any(), any()))
                .thenReturn(new cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport(
                        3, List.of(), cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport.Level.OK,
                        List.of()));
        when(storyRepository.readForeshadowSettlements(any())).thenReturn(List.of());
        when(batchHealthService.assess(any(), any(), any(), any(), any()))
                .thenReturn(new cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport(
                        3, List.of(), cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport.Level.OK,
                        List.of()));
        when(runPlanService.enabled()).thenReturn(true);
        // 第一次判继续、第二次判停止（否则会无限续批）
        when(runPlanService.decide(anyInt(), anyInt(), any())).thenReturn(
                new RunPlanService.Decision(true, "继续自动续批：已完成 3/100 章"),
                new RunPlanService.Decision(false, "已达标：103/100 章"));
        when(runPlanService.nextBatchCommand(any(), anyString(), anyInt())).thenAnswer(inv ->
                ArmoryCommandEntity.builder()
                        .storyContextEntity(cn.novel.yonren.domain.novel.model.entity.StoryContextEntity.builder()
                                .chapterCount(3).build())
                        .resumeStoryDir(inv.getArgument(1))
                        .build());

        GenerationJob job = storyJobService.submit(ArmoryCommandEntity.builder().build());

        awaitTerminal(job);
        assertEquals(cn.novel.yonren.types.enums.JobStatus.COMPLETED, job.getStatus());
        // 子作业被真的跑了一遍（第二次 generate 调用）
        verify(storyGenerateService, timeout(5000).times(2)).generate(any(), any(), any());
        // 批序号递增：第一次决策用 1，第二次用 2（maxBatches 判定与日志归因都依赖它）
        ArgumentCaptor<Integer> rounds = ArgumentCaptor.forClass(Integer.class);
        verify(runPlanService, timeout(5000).times(2)).decide(anyInt(), rounds.capture(), any());
        assertEquals(List.of(1, 2), rounds.getAllValues(), "批序号应逐批递增");
        verify(runPlanService).nextBatchCommand(any(), eq("20260903-story-0001"), eq(3));
    }

    @Test
    void generateFailure_doesNotAutoContinue() throws Exception {
        // 失败绝不续批：同一错误再犯一遍只会更贵，且会把失败掩盖成"一直在跑"
        when(runPlanService.enabled()).thenReturn(true);
        when(storyGenerateService.generate(any(), any(), any()))
                .thenThrow(new AppException("0001", "续写失败：正文与摘要不一致"));

        GenerationJob job = storyJobService.submit(ArmoryCommandEntity.builder().build());

        awaitTerminal(job);
        assertEquals(cn.novel.yonren.types.enums.JobStatus.FAILED, job.getStatus());
        verify(runPlanService, never()).decide(anyInt(), anyInt(), any());
    }

    @Test
    void runPlanDisabled_neverConsultsDecide() throws Exception {
        when(storyGenerateService.generate(any(), any(), any()))
                .thenReturn(StoryGenerateResultAggregate.builder().storyDirName("20260903-story-0001").build());
        when(runPlanService.enabled()).thenReturn(false);

        GenerationJob job = storyJobService.submit(ArmoryCommandEntity.builder().build());

        awaitTerminal(job);
        verify(runPlanService, never()).decide(anyInt(), anyInt(), any());
    }

    @Test
    void submit_generateFailure_marksFailedWithMessage() throws Exception {        when(storyGenerateService.generate(any(), any(), any()))
                .thenThrow(new AppException("0001", "续写失败：正文与摘要不一致"));

        GenerationJob job = storyJobService.submit(ArmoryCommandEntity.builder().build());

        awaitTerminal(job);
        assertEquals(cn.novel.yonren.types.enums.JobStatus.FAILED, job.getStatus());
        assertEquals("续写失败：正文与摘要不一致", job.getErrorMessage());
        assertNotNull(job.getFinishedAtMs());
    }

    @Test
    void submit_queuedJobCancelledBeforeStart_skipsGenerationTree() throws Exception {
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            firstRunning.countDown();
            releaseFirst.await(10, TimeUnit.SECONDS);
            return StoryGenerateResultAggregate.builder().storyDirName("20260903-story-0001").build();
        });

        GenerationJob first = storyJobService.submit(ArmoryCommandEntity.builder().build());
        assertTrue(firstRunning.await(10, TimeUnit.SECONDS));
        // 第二个作业进入队列（CREATED）
        GenerationJob queued = storyJobService.submit(ArmoryCommandEntity.builder().build());
        assertEquals(cn.novel.yonren.types.enums.JobStatus.CREATED, queued.getStatus());

        GenerationJob cancelled = storyJobService.cancel(queued.getJobId());
        assertEquals(cn.novel.yonren.types.enums.JobStatus.CANCELLED, cancelled.getStatus());

        releaseFirst.countDown();
        awaitTerminal(first);
        awaitTerminal(queued);
        assertEquals(cn.novel.yonren.types.enums.JobStatus.CANCELLED, queued.getStatus());
        // 排队期取消：规则树被跳过，generate 只执行了 first 一次
        verify(storyGenerateService, times(1)).generate(any(), any(), any());
    }

    @Test
    void submit_cancelWhileRunning_goesCancellingThenCancelled() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return StoryGenerateResultAggregate.builder().storyDirName("20260903-story-0001").build();
        });

        GenerationJob job = storyJobService.submit(ArmoryCommandEntity.builder().build());
        assertTrue(running.await(10, TimeUnit.SECONDS));

        assertEquals(cn.novel.yonren.types.enums.JobStatus.CANCELLING, storyJobService.cancel(job.getJobId()).getStatus());

        release.countDown();
        awaitTerminal(job);
        assertEquals(cn.novel.yonren.types.enums.JobStatus.CANCELLED, job.getStatus());
    }

    @Test
    void submit_queueFull_marksFailedAndThrows() throws Exception {
        // 无缓冲直连队列：首个作业占住唯一线程后，后续提交立即被 AbortPolicy 拒绝
        ThreadPoolExecutor tinyExecutor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        StoryJobService tinyService = new StoryJobService(storyGenerateService, jobRegistry, storyRepository, tinyExecutor,
                runPlanService, batchHealthService, storyProperties);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(storyGenerateService.generate(any(), any(), any())).thenAnswer(inv -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return StoryGenerateResultAggregate.builder().build();
        });

        tinyService.submit(ArmoryCommandEntity.builder().build());
        assertTrue(running.await(10, TimeUnit.SECONDS));

        AppException e = assertThrows(AppException.class,
                () -> tinyService.submit(ArmoryCommandEntity.builder().build()));
        assertTrue(e.getInfo().contains("队列已满"));

        release.countDown();
        tinyExecutor.shutdownNow();
    }

    @Test
    void workerThread_cleansMdcAfterJob() throws Exception {
        when(storyGenerateService.generate(any(), any(), any()))
                .thenReturn(StoryGenerateResultAggregate.builder().storyDirName("20260903-story-0001").build());

        GenerationJob job = storyJobService.submit(ArmoryCommandEntity.builder().build());
        awaitTerminal(job);

        // 复用同一 worker 线程跑一个探针任务：作业结束后 MDC 必须已被清理
        AtomicReference<String> probe = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(() -> {
            probe.set(MDC.get("trace-id"));
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(probe.get(), "worker 线程 MDC 未清理，会污染后续日志归因");
    }

    private void awaitTerminal(GenerationJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            cn.novel.yonren.types.enums.JobStatus status = job.getStatus();
            if (status == cn.novel.yonren.types.enums.JobStatus.COMPLETED || status == cn.novel.yonren.types.enums.JobStatus.FAILED || status == cn.novel.yonren.types.enums.JobStatus.CANCELLED) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("作业超时未达终态：" + job.getJobId() + " 当前 " + job.getStatus());
    }
}
