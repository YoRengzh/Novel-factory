package cn.novel.yonren.domain.novel.service.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作业状态机测试：终态不可再迁移（排队期取消不被覆盖）、取消信号幂等、进度与耗时记录
 */
class GenerationJobTest {

    @Test
    void markRunning_onlyFromCreated() {
        GenerationJob job = new GenerationJob("job-1");
        assertTrue(job.markRunning());
        assertFalse(job.markRunning(), "RUNNING 不可重复进入");
    }

    @Test
    void markRunning_refusedOnTerminal() {
        GenerationJob job = new GenerationJob("job-1");
        job.markCancelled();
        assertFalse(job.markRunning(), "排队期已取消的作业不得被覆盖回 RUNNING");
        assertEquals(cn.novel.yonren.types.enums.JobStatus.CANCELLED, job.getStatus());
    }

    @Test
    void requestCancel_idempotent_andRefusedOnTerminal() {
        GenerationJob job = new GenerationJob("job-1");
        assertTrue(job.requestCancel());
        assertTrue(job.requestCancel(), "重复取消幂等");

        job.markFailed("boom");
        assertFalse(job.requestCancel(), "终态不再受理取消");
    }

    @Test
    void markCancelling_onlyFromRunning() {
        GenerationJob job = new GenerationJob("job-1");
        assertFalse(job.markCancelling(), "CREATED 不可直接进入 CANCELLING");
        job.markRunning();
        assertTrue(job.markCancelling());
        assertEquals(cn.novel.yonren.types.enums.JobStatus.CANCELLING, job.getStatus());
    }

    @Test
    void markFailed_recordsMessageAndTimestamp() {
        GenerationJob job = new GenerationJob("job-1");
        job.markRunning();
        job.markFailed("审校彻底失败");
        assertEquals(cn.novel.yonren.types.enums.JobStatus.FAILED, job.getStatus());
        assertEquals("审校彻底失败", job.getErrorMessage());
        assertNotNull(job.getFinishedAtMs());
    }

    @Test
    void markCompleted_recordsStoryDirName() {
        GenerationJob job = new GenerationJob("job-1");
        job.markRunning();
        job.markCompleted("20260903-story-0001");
        assertEquals(cn.novel.yonren.types.enums.JobStatus.COMPLETED, job.getStatus());
        assertEquals("20260903-story-0001", job.getStoryDirName());
        assertNotNull(job.getFinishedAtMs());
    }

    @Test
    void updateProgress_andChapterDurations() {
        GenerationJob job = new GenerationJob("job-1");
        assertNull(job.getStoryDirName());
        job.updateProgress("CHAPTER_GENERATION", 3, 10, "20260903-story-0002");
        job.recordChapterDuration(3, 1234L);

        assertEquals("CHAPTER_GENERATION", job.getCurrentStage());
        assertEquals(3, job.getCurrentChapter());
        assertEquals(10, job.getTotalChapters());
        assertEquals("20260903-story-0002", job.getStoryDirName());
        assertEquals(1234L, job.getChapterDurations().get("3"),
                "键为章号字符串：保证 job-status.json 是合法 JSON（fastjson2 对非字符串键写不带引号）");
        assertNotNull(job.getStartedAtMs());
    }

    @Test
    void markAwaitingApproval_handsOverPendingPlanAtomically() {
        // 不变式：状态置位与计划交接同一次调用完成（先写计划再置状态），
        // 于是"读到 AWAITING_APPROVAL 的读者必然也能读到计划"，不再受服务层登记时序影响
        GenerationJob job = new GenerationJob("job-1");
        job.markRunning();
        var plan = cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate.builder()
                .storyId("story-1").chapters(java.util.List.of()).build();

        assertTrue(job.markAwaitingApproval(123L, plan));

        assertTrue(job.isAwaitingApproval());
        assertSame(plan, job.getAwaitingPlan(), "状态可见 ⇒ 计划可读");
        assertTrue(job.exitApprovalWait());
        assertNull(job.getAwaitingPlan(), "退出挂起必须清空，防裁决后读到过期计划");
    }

    /**
     * 回归守卫：job-status.json 必须是**合法 JSON**。
     * 实测缺陷：fastjson2 把 {@code Map<Integer,Long>} 的键写成不带引号的 {@code {1:114150}}，
     * 文件扩展名是 .json，却让 Jackson / python json.load / jq 全部解析失败——* 这类"文件看起来正常、只有标准解析器才报错"的问题最容易长期潜伏。
     */
    @Test
    void jobStatusSerializationIsStrictJson() throws Exception {
        GenerationJob job = new GenerationJob("job-1");
        job.recordChapterDuration(1, 114150L);
        job.recordChapterDuration(12, 93590L);
        job.markRunning();
        job.markCompleted("20260927-story-0001");

        String json = com.alibaba.fastjson2.JSON.toJSONString(job);

        // 用 Jackson 做严格校验：fastjson2 自己太宽松，会把非法 JSON 也读进来，故不能自证
        com.fasterxml.jackson.databind.ObjectMapper strict =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = strict.readTree(json);
        assertEquals(114150L, node.get("chapterDurations").get("1").asLong(),
                "章号键必须带引号，否则不是合法 JSON");
        assertEquals(93590L, node.get("chapterDurations").get("12").asLong());
    }
}
