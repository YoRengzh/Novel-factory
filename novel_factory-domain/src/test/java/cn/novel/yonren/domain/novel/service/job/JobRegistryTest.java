package cn.novel.yonren.domain.novel.service.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存注册表测试：注册/查询/取消三路径（CREATED 直判、RUNNING 转 CANCELLING、终态不动）
 */
class JobRegistryTest {

    private JobRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new JobRegistry();
    }

    @Test
    void registerAndGet() {
        GenerationJob job = new GenerationJob("job-1");
        registry.register(job);
        assertSame(job, registry.get("job-1"));
        assertNull(registry.get("unknown"));
    }

    @Test
    void cancel_created_goesDirectlyCancelled() {
        GenerationJob job = new GenerationJob("job-1");
        registry.register(job);

        GenerationJob cancelled = registry.cancel("job-1");

        assertSame(job, cancelled);
        assertEquals(cn.novel.yonren.types.enums.JobStatus.CANCELLED, cancelled.getStatus());
        assertTrue(cancelled.isCancelRequested());
    }

    @Test
    void cancel_running_goesCancelling() {
        GenerationJob job = new GenerationJob("job-1");
        job.markRunning();
        registry.register(job);

        GenerationJob cancelling = registry.cancel("job-1");

        assertEquals(cn.novel.yonren.types.enums.JobStatus.CANCELLING, cancelling.getStatus());
        assertTrue(cancelling.isCancelRequested());
        // CANCELLING 重复取消幂等，不迁移
        assertEquals(cn.novel.yonren.types.enums.JobStatus.CANCELLING, registry.cancel("job-1").getStatus());
    }

    @Test
    void cancel_terminal_noop() {
        GenerationJob job = new GenerationJob("job-1");
        job.markRunning();
        job.markFailed("boom");
        registry.register(job);

        GenerationJob result = registry.cancel("job-1");

        assertEquals(cn.novel.yonren.types.enums.JobStatus.FAILED, result.getStatus());
        assertFalse(result.isCancelRequested());
    }

    @Test
    void referenceMaintenanceBlocksNewJobsAndCalls() {
        assertTrue(registry.tryBeginReferenceOperation());
        assertTrue(registry.isReferenceBusy());
        assertFalse(registry.tryBeginReferenceUse());
        assertFalse(registry.tryRegister(new GenerationJob("blocked")));
        registry.endReferenceOperation();
        assertTrue(registry.tryRegister(new GenerationJob("accepted")));
    }

    @Test
    void synchronousCallBlocksMaintenanceUntilFinallyReleased() {
        assertTrue(registry.tryBeginReferenceUse());
        assertTrue(registry.tryBeginReferenceUse());
        assertFalse(registry.tryBeginReferenceOperation());
        registry.endReferenceUse();
        assertFalse(registry.tryBeginReferenceOperation());
        registry.endReferenceUse();
        assertTrue(registry.tryBeginReferenceOperation());
        registry.endReferenceOperation();
    }

    @Test
    void cancellingJobStillBlocksGlobalMaintenance() {
        GenerationJob job = new GenerationJob("active");
        job.markRunning();
        registry.register(job);
        registry.cancel(job.getJobId());
        assertFalse(registry.tryBeginReferenceOperation());
        job.markCancelled();
        assertTrue(registry.tryBeginReferenceOperation());
        registry.endReferenceOperation();
    }

    @Test
    void collectionMaintenanceAndAuthoringMaintenanceExcludeEachOther() {
        assertTrue(registry.tryBeginStoryOperation("story-a"));
        assertFalse(registry.tryBeginReferenceOperation());
        registry.endStoryOperation("story-a");
        assertTrue(registry.tryBeginReferenceOperation());
        assertFalse(registry.tryBeginStoryOperation("story-b"));
        assertTrue(registry.isActiveForStory("story-b"));
        registry.endReferenceOperation();
        assertTrue(registry.tryBeginStoryOperation("story-b"));
        registry.endStoryOperation("story-b");
    }

    @Test
    void cancel_unknownReturnsNull() {
        assertNull(registry.cancel("nope"));
    }
}
