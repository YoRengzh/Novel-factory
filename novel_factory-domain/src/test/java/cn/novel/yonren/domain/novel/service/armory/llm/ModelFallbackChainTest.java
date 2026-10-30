package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局兜底模型链测试（2026-10-23 由按场景降级链收敛而来）。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>链最长 2 站：主模型 → 全局兜底；</li>
 *   <li>兜底缺省字段回落主模型，显式填写的字段以兜底为准（跨供应商时地址密钥必须能变）；</li>
 *   <li>兜底与主模型同名时跳过（同一模型重试只会再失败一次）。</li>
 * </ul>
 */
class ModelFallbackChainTest {

    private static StoryVO.Module.ChatModel primary(String model) {
        StoryVO.Module.ChatModel m = new StoryVO.Module.ChatModel();
        m.setModel(model);
        m.setMaxTokens(16384L);
        m.setTemperature(0.1);
        m.setEnableThinking(false);
        m.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");
        m.setApiKey("sk-test");
        m.setCompletionsPath("/v1/chat/completions");
        return m;
    }

    private static StoryVO.Module.ChatModel fallback(String model) {
        StoryVO.Module.ChatModel m = new StoryVO.Module.ChatModel();
        m.setModel(model);
        return m;
    }

    private static StoryVO.Module module(StoryVO.Module.ChatModel fallbackModel) {
        StoryVO.Module module = new StoryVO.Module();
        module.setFallbackModel(fallbackModel);
        return module;
    }

    @Test
    @DisplayName("无兜底配置时链长为 1（只有主模型），退化为原行为")
    void noFallbackKeepsSingleEntry() {
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(null), ModelScene.CHAPTER_PLAN, primary("qwen3.7-max-2026-05-20"));

        assertEquals(1, chain.size());
        assertEquals("qwen3.7-max-2026-05-20", chain.get(0).getModel());
    }

    @Test
    @DisplayName("兜底只填模型名时，其余参数一律沿用主模型")
    void fallbackInheritsPrimaryParamsWhenNotSpecified() {
        StoryVO.Module.ChatModel primary = primary("qwen3.7-max-2026-05-20");
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(fallback("qwen3.8-flash")), ModelScene.CHAPTER_PLAN, primary);

        assertEquals(2, chain.size());
        StoryVO.Module.ChatModel fb = chain.get(1);
        assertEquals("qwen3.8-flash", fb.getModel());
        assertEquals(16384L, fb.getMaxTokens(), "maxTokens 未指定时沿用主模型");
        assertEquals(0.1, fb.getTemperature(), 1e-9);
        assertEquals(false, fb.getEnableThinking());
        assertEquals(primary.getBaseUrl(), fb.getBaseUrl());
        assertEquals(primary.getApiKey(), fb.getApiKey());
        assertEquals(primary.getCompletionsPath(), fb.getCompletionsPath());
    }

    @Test
    @DisplayName("兜底显式填写的地址/密钥/上限覆盖主模型——跨供应商时必须能变")
    void fallbackExplicitFieldsWin() {
        StoryVO.Module.ChatModel fb = fallback("deepseek-v4-pro");
        fb.setBaseUrl("https://api.deepseek.com");
        fb.setApiKey("sk-deepseek");
        fb.setMaxTokens(8192L);
        fb.setTemperature(0.5);

        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(fb), ModelScene.CHAPTER_PLAN, primary("glm-5.3-flash"));

        assertEquals(2, chain.size());
        StoryVO.Module.ChatModel selected = chain.get(1);
        assertEquals("deepseek-v4-pro", selected.getModel());
        assertEquals("https://api.deepseek.com", selected.getBaseUrl());
        assertEquals("sk-deepseek", selected.getApiKey());
        assertEquals(8192L, selected.getMaxTokens(), "异族模型上限必须可独立指定");
        assertEquals(0.5, selected.getTemperature(), 1e-9);
    }

    @Test
    @DisplayName("兜底与主模型同名且同端点时跳过：换了个名字还是同一个模型，没有任何意义")
    void sameAsPrimaryIsSkipped() {
        StoryVO.Module.ChatModel fb = fallback("qwen3.8-flash");
        // fallback() 默认不带地址密钥 = 继承主模型端点（mergeWithPrimary 语义）
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(fb),
                ModelScene.CHAPTER_CONTENT, primary("qwen3.8-flash"));

        assertEquals(1, chain.size(), "同名同端点兜底不得进链");
    }

    @Test
    @DisplayName("同名异站仍进入降级链：换供应商不是同一模型重复调用")
    void sameModelDifferentProviderIsKept() {
        StoryVO.Module.ChatModel fb = fallback("qwen3.8-flash");
        fb.setBaseUrl("https://relay.example.com");
        fb.setApiKey("sk-relay");
        fb.setProtocol("anthropic");

        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(fb), ModelScene.CHAPTER_CONTENT, primary("qwen3.8-flash"));

        assertEquals(2, chain.size(), "同名异站必须保留兜底");
        assertEquals("https://relay.example.com", chain.get(1).getBaseUrl());
        assertEquals("anthropic", chain.get(1).getProtocol());
    }

    @Test
    @DisplayName("同名同地址但不同密钥仍进入降级链（账号额度/限流可独立）")
    void sameModelSameUrlDifferentKeyIsKept() {
        StoryVO.Module.ChatModel primary = primary("qwen3.8-flash");
        StoryVO.Module.ChatModel fb = fallback("qwen3.8-flash");
        fb.setBaseUrl(primary.getBaseUrl());
        fb.setApiKey("sk-other-account");

        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(fb), ModelScene.CHAPTER_CONTENT, primary);

        assertEquals(2, chain.size());
        assertEquals("sk-other-account", chain.get(1).getApiKey());
    }

    @Test
    @DisplayName("兜底模型名为空时不启用（视为未配置）")
    void blankFallbackModelDisables() {
        StoryVO.Module.ChatModel fb = fallback("   ");
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(fb), ModelScene.CHAPTER_PLAN, primary("qwen3.8-flash"));

        assertEquals(1, chain.size());
    }

    @Test
    @DisplayName("主模型缺失（无可用配置）时返回空链，调用方据此直接失败而不是空跑")
    void missingPrimaryYieldsEmptyChain() {
        assertTrue(ModelFallbackChain.build(module(fallback("x")), ModelScene.CHAPTER_PLAN, null).isEmpty());

        StoryVO.Module.ChatModel blank = new StoryVO.Module.ChatModel();
        assertTrue(ModelFallbackChain.build(module(fallback("x")), ModelScene.CHAPTER_PLAN, blank).isEmpty());
    }

    @Test
    @DisplayName("场景为 null 时兜底照样生效（统一模型模式下无场景语境）")
    void nullSceneStillUsesFallback() {
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(fallback("deepseek-v4-flash")), null, primary("qwen3.8-flash"));

        assertEquals(2, chain.size());
        assertEquals("deepseek-v4-flash", chain.get(1).getModel());
    }

    @Test
    @DisplayName("链长上限为 2：单个兜底不会产生更长的链")
    void chainLengthIsCappedAtTwo() {
        assertEquals(2, ModelFallbackChain.MAX_CHAIN_LENGTH);
    }

    @Test
    @DisplayName("mergeWithPrimary 对空字段回落、非空字段保留")
    void mergeWithPrimaryFallsBackFieldByField() {
        StoryVO.Module.ChatModel fb = new StoryVO.Module.ChatModel();
        fb.setModel("fallback-model");
        fb.setApiKey("sk-fb");

        StoryVO.Module.ChatModel merged = ModelFallbackChain.mergeWithPrimary(fb, primary("primary-model"));

        assertEquals("fallback-model", merged.getModel());
        assertEquals("sk-fb", merged.getApiKey(), "显式密钥以兜底为准");
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode", merged.getBaseUrl(),
                "baseUrl 为空时回落主模型");
        assertEquals(Boolean.FALSE, merged.getEnableThinking(), "兜底未指定时沿用主模型的 false");
    }
}
