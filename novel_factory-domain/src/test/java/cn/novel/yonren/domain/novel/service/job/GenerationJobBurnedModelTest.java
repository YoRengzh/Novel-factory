package cn.novel.yonren.domain.novel.service.job;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作业烧穿黑名单测试：作用域（场景隔离/不跨作业）与序列化安全
 * （transient——job-status.json 是观测快照，崩溃恢复不回填执行态）。
 */
class GenerationJobBurnedModelTest {

    @Test
    void blacklistScopesBySceneAndJob() {
        GenerationJob job = new GenerationJob("job-1");
        job.recordBurnedModel("chapter-plan", "glm-5.3");

        assertTrue(job.isModelBurned("chapter-plan", "glm-5.3"));
        assertFalse(job.isModelBurned("chapter-content", "glm-5.3"), "黑名单按场景隔离");
        assertFalse(job.isModelBurned("chapter-plan", "qwen3.8-max"), "同场景其他模型不受牵连");
        assertFalse(job.isModelBurned(null, "glm-5.3"), "无场景语境不误判");
        assertFalse(new GenerationJob("job-2").isModelBurned("chapter-plan", "glm-5.3"),
                "黑名单不跨作业");
    }

    @Test
    void blankEntriesAreNotRecorded() {
        GenerationJob job = new GenerationJob("job-1");
        job.recordBurnedModel(null, "glm-5.3");
        job.recordBurnedModel("chapter-plan", " ");
        job.recordBurnedModel("chapter-plan", null);
        assertFalse(job.isModelBurned("chapter-plan", "glm-5.3"));
        assertFalse(job.isModelBurned(null, null));
    }

    @Test
    void burnedModelsSurviveFastjsonRoundTripAsUninitializedNotCorrupted() {
        // job-status.json 走 fastjson2 全字段序列化（writeJobStatus），黑名单必须不进文件；
        // 读回实例（人工查看路径）再记烧穿也不能翻车——fastjson2 反序列化可能绕过构造器，
        // final transient 字段若被 Unsafe 置 null，recordBurnedModel 会直接 NPE
        GenerationJob job = new GenerationJob("job-1");
        job.recordBurnedModel("chapter-plan", "glm-5.3");
        String json = JSON.toJSONString(job);
        assertFalse(json.contains("burnedModels"), "黑名单不得序列化进 job-status.json");

        GenerationJob back = JSON.parseObject(json, GenerationJob.class);
        assertFalse(back.isModelBurned("chapter-plan", "glm-5.3"), "读回实例黑名单为空");
        // 读回实例再次记账必须不抛 NPE（transient final 的初始化语义在此验证）
        back.recordBurnedModel("chapter-plan", "qwen3.8-max");
        assertTrue(back.isModelBurned("chapter-plan", "qwen3.8-max"));
        assertEquals("job-1", back.getJobId(), "无关字段不受序列化影响");
    }
}
