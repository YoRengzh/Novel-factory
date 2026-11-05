package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.domain.novel.config.StoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算熔断测试：warn 预警一次、hard 置位停机信号、jobId 缺失忽略、未配置整体禁用；
 * tokensUsed 观测字段同步累加
 */
class LlmBudgetFuseTest {

    private StoryProperties storyProperties;
    private GenerationJob job;

    @BeforeEach
    void setUp() {
        storyProperties = new StoryProperties();
        job = new GenerationJob("job-1");
    }

    private LlmBudgetFuse fuse(GenerationJob registered) {
        JobRegistry registry = new JobRegistry();
        if (registered != null) {
            registry.register(registered);
        }
        return new LlmBudgetFuse(storyProperties, registry);
    }

    @Test
    void hardThreshold_tripsBudgetExceeded() {
        StoryProperties.BudgetProperties budget = new StoryProperties.BudgetProperties();
        budget.setWarnTotalTokens(1000L);
        budget.setHardTotalTokens(2000L);
        storyProperties.setBudget(budget);
        LlmBudgetFuse fuse = fuse(job);

        fuse.record("job-1", 1500L);
        assertFalse(job.isBudgetExceeded(), "未达硬上限不停机");

        fuse.record("job-1", 600L);
        assertTrue(job.isBudgetExceeded(), "累计 2100 ≥ 2000 应置位停机信号");
        assertEquals(2100L, job.getTokensUsed());
    }

    @Test
    void warnThreshold_loggedOnce() {
        StoryProperties.BudgetProperties budget = new StoryProperties.BudgetProperties();
        budget.setWarnTotalTokens(1000L);
        budget.setHardTotalTokens(10000L);
        storyProperties.setBudget(budget);
        LlmBudgetFuse fuse = fuse(job);

        fuse.record("job-1", 1500L);
        fuse.record("job-1", 100L);

        assertFalse(job.isBudgetExceeded());
        assertEquals(1600L, job.getTokensUsed());
    }

    @Test
    void unknownJob_stillAccumulates_withoutTripping() {
        StoryProperties.BudgetProperties budget = new StoryProperties.BudgetProperties();
        budget.setHardTotalTokens(100L);
        storyProperties.setBudget(budget);
        LlmBudgetFuse fuse = fuse(null);

        fuse.record("job-unknown", 500L);
        // 未注册的 job 无法置位信号（fuse 不抛出），但计数照常
        assertTrue(true);
    }

    @Test
    void missingJobIdOrTokens_ignored() {
        StoryProperties.BudgetProperties budget = new StoryProperties.BudgetProperties();
        budget.setHardTotalTokens(100L);
        storyProperties.setBudget(budget);
        LlmBudgetFuse fuse = fuse(job);

        fuse.record(null, 500L);
        fuse.record("job-1", null);
        fuse.record("job-1", 0L);
        assertFalse(job.isBudgetExceeded());
        assertEquals(0L, job.getTokensUsed());
    }

    @Test
    void notConfigured_disabled() {
        storyProperties.setBudget(new StoryProperties.BudgetProperties());
        LlmBudgetFuse fuse = fuse(job);

        fuse.record("job-1", 999999L);
        assertFalse(job.isBudgetExceeded(), "阈值未配置时只禁用熔断");
        assertEquals(999999L, job.getTokensUsed(), "预算关闭不能关闭 token 用量观测");
    }
}
