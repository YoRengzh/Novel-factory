package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.api.dto.LlmConfigSaveRequestDTO;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.types.enums.ModelScene;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 运行时覆盖测试：默认原样透传、api/model 覆盖落到调用配置、apiKey 掩码、
 * 落盘持久化（重建实例仍能恢复）、reset 清空回退
 */
class LlmRuntimeConfigTest {

    @TempDir
    Path tmp;

    private StoryVO.Module staticModule() {
        StoryVO.Module.AiApi aiApi = new StoryVO.Module.AiApi();
        aiApi.setBaseUrl("https://static.example.com/compatible-mode");
        aiApi.setApiKey("static-key");
        StoryVO.Module.ChatModel chat = new StoryVO.Module.ChatModel();
        chat.setModel("static-model");
        chat.setMaxTokens(8192L);
        chat.setTemperature(0.7);
        chat.setEnableThinking(false);
        StoryVO.Module module = new StoryVO.Module();
        module.setAiApi(aiApi);
        module.setChatModel(chat);
        return module;
    }

    @Test
    void applyApi_withoutOverlay_returnsSameModule() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        StoryVO.Module module = staticModule();

        assertSame(module, config.applyApi(module));
        assertSame(module.getChatModel(), config.applyModel(module.getChatModel()));
    }

    @Test
    void apply_overridesBaseUrlApiKeyAndModel() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setBaseUrl("https://runtime.example.com/v1");
        request.setApiKey("sk-runtime-secret-1234");
        request.setModel("runtime-model");
        request.setMaxTokens(4096L);
        config.save(request);
        StoryVO.Module module = staticModule();

        StoryVO.Module effective = config.applyApi(module);
        assertEquals("https://runtime.example.com/v1", effective.getAiApi().getBaseUrl());
        assertEquals("sk-runtime-secret-1234", effective.getAiApi().getApiKey());
        // 静态配置对象不被污染：仍保留原值
        assertEquals("https://static.example.com/compatible-mode", module.getAiApi().getBaseUrl());

        StoryVO.Module.ChatModel model = config.applyModel(module.getChatModel());
        assertEquals("runtime-model", model.getModel());
        // maxTokens 是兜底语义（2026-10-23）：静态已配 8192 时全局 4096 不得覆盖它，
        // 否则全局一个数字会压掉各场景按其输出形态调过的上限（chapter-plan 32768 被腰斩即烧穿根因）
        assertEquals(Long.valueOf(8192L), model.getMaxTokens());
        // 未覆盖字段沿用静态
        assertEquals(Double.valueOf(0.7), model.getTemperature());
        assertEquals(Boolean.FALSE, model.getEnableThinking());
    }

    /** 兜底语义的另一半：selected 没有上限时，全局值必须补上（否则等于取消了全局配置能力） */
    @Test
    void applyModel_globalMaxTokensFillsOnlyWhenSelectedHasNone() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setMaxTokens(4096L);
        config.save(request);

        StoryVO.Module.ChatModel noLimit = new StoryVO.Module.ChatModel();
        noLimit.setModel("no-limit-model");
        assertEquals(Long.valueOf(4096L), config.applyModel(noLimit).getMaxTokens());
    }

    @Test
    void applyModel_withSceneApi_coverageKeepsBaseUrlAndApiKey() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setModel("runtime-model");
        config.save(request);
        // 模拟第二模型族（chapter-judge）：场景模型自带异供应商 base-url/api-key
        StoryVO.Module.ChatModel sceneModel = new StoryVO.Module.ChatModel();
        sceneModel.setBaseUrl("https://api.deepseek.com");
        sceneModel.setApiKey("sk-deepseek-secret");
        sceneModel.setModel("deepseek-v4.1-flash");
        sceneModel.setMaxTokens(1024L);

        StoryVO.Module.ChatModel applied = config.applyModel(sceneModel);

        // 覆盖只作用于 model/maxTokens；异供应商地址与密钥必须原样保留（否则评审静默回退主族）
        assertEquals("runtime-model", applied.getModel());
        assertEquals("https://api.deepseek.com", applied.getBaseUrl());
        assertEquals("sk-deepseek-secret", applied.getApiKey());
    }

    @Test
    void applyModel_withoutOverlay_keepsSceneApiUntouched() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        StoryVO.Module.ChatModel sceneModel = new StoryVO.Module.ChatModel();
        sceneModel.setBaseUrl("https://api.deepseek.com");
        sceneModel.setApiKey("sk-deepseek-secret");
        sceneModel.setModel("deepseek-v4.1-flash");

        assertSame(sceneModel, config.applyModel(sceneModel));
        assertEquals("https://api.deepseek.com", sceneModel.getBaseUrl());
        assertEquals("sk-deepseek-secret", sceneModel.getApiKey());
    }

    @Test
    void save_blankApiKeyKeepsExisting_sceneIndependent() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO first = new LlmConfigSaveRequestDTO();
        first.setBaseUrl("https://a.example.com");
        first.setApiKey("sk-abc-key1");
        config.save(first);

        LlmConfigSaveRequestDTO second = new LlmConfigSaveRequestDTO();
        second.setModel("scene-model-2");
        config.save(second);

        LlmConfigDTO view = config.view();
        assertEquals("https://a.example.com", view.getBaseUrl());
        assertEquals("scene-model-2", view.getModel());
        assertTrue(view.isHasKey());
        // apiKey 只回掩码，不回原文
        assertTrue(view.getApiKeyMasked().contains("key1"));
        assertFalse(view.getApiKeyMasked().contains("abc"));
    }

    @Test
    void persistence_survivesRelaunch() throws Exception {
        Path file = tmp.resolve("override.json");
        LlmRuntimeConfig config = new LlmRuntimeConfig(file);
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setBaseUrl("https://persist.example.com");
        request.setApiKey("sk-persist-999");
        request.setModel("persist-model");
        config.save(request);
        assertTrue(Files.isRegularFile(file));

        // 模拟进程重启：同一文件重建实例，覆盖自动恢复
        LlmRuntimeConfig reloaded = new LlmRuntimeConfig(file);
        LlmConfigDTO view = reloaded.view();
        assertTrue(view.isConfigured());
        assertEquals("https://persist.example.com", view.getBaseUrl());
        assertEquals("persist-model", view.getModel());
        assertTrue(view.isHasKey());
        assertEquals("persist-model", reloaded.applyModel(staticModule().getChatModel()).getModel());
    }

    @Test
    void reset_clearsOverlay_fallsBackToPassThrough() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setBaseUrl("https://x.example.com");
        request.setModel("x-model");
        config.save(request);

        LlmConfigSaveRequestDTO reset = new LlmConfigSaveRequestDTO();
        reset.setReset(true);
        config.save(reset);

        LlmConfigDTO view = config.view();
        assertFalse(view.isConfigured());
        assertNull(view.getBaseUrl());
        StoryVO.Module module = staticModule();
        assertSame(module, config.applyApi(module));
        assertSame(module.getChatModel(), config.applyModel(module.getChatModel()));
    }

    // ===== 分场景覆盖 =====

    // ===== 全局兜底模型覆盖 =====

    /** 整表替换语义：缺席字段=清除覆盖（清空输入框保存后覆盖必须消失，不是残留） */
    @Test
    void fallbackSave_isFullReplaceAbsentFieldClearsOverride() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO first = new LlmConfigSaveRequestDTO();
        LlmConfigSaveRequestDTO.SceneOverride fb = sceneOverride("deepseek-v4-pro", 8192L, 0.5);
        fb.setBaseUrl("https://api.deepseek.com");
        first.setFallback(fb);
        config.save(first);

        // 第二次保存只带模型名：maxTokens/baseUrl 覆盖应被清除
        LlmConfigSaveRequestDTO second = new LlmConfigSaveRequestDTO();
        second.setFallback(sceneOverride("qwen3.8-flash", null, null));
        config.save(second);

        StoryVO.Module module = staticModule();
        StoryVO.Module applied = config.applyFallback(module);
        assertEquals("qwen3.8-flash", applied.getFallbackModel().getModel());
        assertNull(applied.getFallbackModel().getMaxTokens(), "缺席字段=清除覆盖");
        assertNull(applied.getFallbackModel().getBaseUrl(), "缺席字段=清除覆盖");
        assertNull(applied.getFallbackModel().getTemperature());
    }

    /** 兜底覆盖落到 module：显式字段以覆盖为准，缺省字段回落 yml 静态兜底 */
    @Test
    void fallbackApply_mergesOverStaticFallback() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        StoryVO.Module module = staticModule();
        StoryVO.Module.ChatModel staticFallback = new StoryVO.Module.ChatModel();
        staticFallback.setModel("static-fb");
        staticFallback.setMaxTokens(4096L);
        module.setFallbackModel(staticFallback);

        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        LlmConfigSaveRequestDTO.SceneOverride fb = sceneOverride("runtime-fb", 16384L, null);
        request.setFallback(fb);
        config.save(request);

        StoryVO.Module applied = config.applyFallback(module);
        assertEquals("runtime-fb", applied.getFallbackModel().getModel());
        assertEquals(Long.valueOf(16384L), applied.getFallbackModel().getMaxTokens());
        assertEquals(Long.valueOf(4096L), staticFallback.getMaxTokens(), "静态对象不被污染");
    }

    /** resetFallback 只清兜底覆盖，不误伤全局/场景覆盖 */
    @Test
    void fallbackReset_doesNotTouchOtherOverrides() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO global = new LlmConfigSaveRequestDTO();
        global.setModel("global-model");
        config.save(global);
        config.save(sceneSave("audit", sceneOverride("audit-model", null, null)));
        config.save(withFallback(sceneOverride("fb-model", null, null)));

        LlmConfigSaveRequestDTO reset = new LlmConfigSaveRequestDTO();
        reset.setResetFallback(true);
        config.save(reset);

        assertEquals("global-model", config.view().getModel());
        assertEquals("audit-model", config.applyModel(
                ModelScene.CHAPTER_AUDIT, staticModule().getChatModel()).getModel());
        StoryProperties statics = new StoryProperties();
        statics.setModule(staticModule());
        assertNull(config.view(statics).getFallback().getOverrideModel());
    }

    private LlmConfigSaveRequestDTO withFallback(LlmConfigSaveRequestDTO.SceneOverride fb) {
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setFallback(fb);
        return request;
    }

    /** 兜底覆盖跨重启持久化 */
    @Test
    void fallbackPersistence_survivesRelaunch() throws Exception {
        Path file = tmp.resolve("override.json");
        LlmRuntimeConfig config = new LlmRuntimeConfig(file);
        config.save(withFallback(sceneOverride("fb-model", 8192L, 0.3)));

        LlmRuntimeConfig reloaded = new LlmRuntimeConfig(file);
        StoryVO.Module applied = reloaded.applyFallback(staticModule());
        assertEquals("fb-model", applied.getFallbackModel().getModel());
        assertEquals(Long.valueOf(8192L), applied.getFallbackModel().getMaxTokens());
    }

    /** 其他分区保存请求不带 fallback 字段时不误伤兜底覆盖 */
    @Test
    void globalSaveWithoutFallback_keepsFallbackOverride() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        config.save(withFallback(sceneOverride("fb-model", null, null)));

        LlmConfigSaveRequestDTO globalOnly = new LlmConfigSaveRequestDTO();
        globalOnly.setModel("global-model");
        config.save(globalOnly);

        StoryVO.Module applied = config.applyFallback(staticModule());
        assertEquals("fb-model", applied.getFallbackModel().getModel());
    }

    private LlmConfigSaveRequestDTO.SceneOverride sceneOverride(String model, Long maxTokens, Double temperature) {
        LlmConfigSaveRequestDTO.SceneOverride so = new LlmConfigSaveRequestDTO.SceneOverride();
        so.setModel(model);
        so.setMaxTokens(maxTokens);
        so.setTemperature(temperature);
        return so;
    }

    private LlmConfigSaveRequestDTO sceneSave(String key, LlmConfigSaveRequestDTO.SceneOverride so) {
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        Map<String, LlmConfigSaveRequestDTO.SceneOverride> scenes = new LinkedHashMap<>();
        scenes.put(key, so);
        request.setScenes(scenes);
        return request;
    }

    @Test
    void applyModel_sceneOverride_winsOverGlobalOverlay_fieldByField() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO global = new LlmConfigSaveRequestDTO();
        global.setModel("global-model");
        global.setMaxTokens(2048L);
        config.save(global);
        LlmConfigSaveRequestDTO sceneReq = sceneSave("chapter-plan",
                sceneOverride("scene-model", null, 0.3));
        config.save(sceneReq);

        StoryVO.Module.ChatModel chat = staticModule().getChatModel();
        // 配置了覆盖的场景：场景覆盖 > 全局覆盖 > 静态（逐字段）
        StoryVO.Module.ChatModel planned = config.applyModel(ModelScene.CHAPTER_PLAN, chat);
        assertEquals("scene-model", planned.getModel());
        // 场景条目没填 maxTokens → 回落到静态的 8192（全局 2048 不再覆盖已有的静态上限）
        assertEquals(Long.valueOf(8192L), planned.getMaxTokens());
        assertEquals(Double.valueOf(0.3), planned.getTemperature());
        // 未配置覆盖的场景：走全局覆盖（模型名），maxTokens 仍尊重静态值
        StoryVO.Module.ChatModel content = config.applyModel(ModelScene.CHAPTER_CONTENT, chat);
        assertEquals("global-model", content.getModel());
        assertEquals(Long.valueOf(8192L), content.getMaxTokens());
        assertEquals(Double.valueOf(0.7), content.getTemperature());
        // 静态对象不被污染
        assertEquals("static-model", chat.getModel());
    }

    /** 场景条目显式填了 maxTokens 时，场景值必须胜过静态与全局（逐字段优先级的最高层） */
    @Test
    void applyModel_sceneMaxTokensOverridesStaticValue() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        config.save(sceneSave("chapter-plan", sceneOverride(null, 32768L, null)));

        StoryVO.Module.ChatModel planned = config.applyModel(
                ModelScene.CHAPTER_PLAN, staticModule().getChatModel());
        assertEquals(Long.valueOf(32768L), planned.getMaxTokens());
    }

    @Test
    void save_globalRequestWithoutScenes_keepsSceneOverrides() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        config.save(sceneSave("audit", sceneOverride("audit-model", 4096L, null)));

        LlmConfigSaveRequestDTO globalOnly = new LlmConfigSaveRequestDTO();
        globalOnly.setModel("global-model");
        config.save(globalOnly);

        // scenes=null 的全局保存不误伤场景覆盖
        StoryVO.Module.ChatModel audit = config.applyModel(ModelScene.CHAPTER_AUDIT, staticModule().getChatModel());
        assertEquals("audit-model", audit.getModel());
        assertEquals(Long.valueOf(4096L), audit.getMaxTokens());
    }

    @Test
    void save_scenesEmptyMapAndResetScenes_clearSceneOverrides() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO global = new LlmConfigSaveRequestDTO();
        global.setModel("global-model");
        config.save(global);
        config.save(sceneSave("audit", sceneOverride("audit-model", null, null)));

        // 空表=清空场景覆盖
        LlmConfigSaveRequestDTO empty = new LlmConfigSaveRequestDTO();
        empty.setScenes(new LinkedHashMap<>());
        config.save(empty);
        assertEquals("global-model",
                config.applyModel(ModelScene.CHAPTER_AUDIT, staticModule().getChatModel()).getModel());

        // 再覆盖后 resetScenes：只清场景，全局保留
        config.save(sceneSave("audit", sceneOverride("audit-model", null, null)));
        LlmConfigSaveRequestDTO resetScenes = new LlmConfigSaveRequestDTO();
        resetScenes.setResetScenes(true);
        config.save(resetScenes);
        assertEquals("global-model",
                config.applyModel(ModelScene.CHAPTER_AUDIT, staticModule().getChatModel()).getModel());
        assertEquals("global-model", config.view().getModel());
    }

    @Test
    void persistence_sceneOverridesSurviveRelaunch() throws Exception {
        Path file = tmp.resolve("override.json");
        LlmRuntimeConfig config = new LlmRuntimeConfig(file);
        config.save(sceneSave("chapter-judge", sceneOverride("judge-model", 1024L, 0.1)));
        assertTrue(Files.isRegularFile(file));

        LlmRuntimeConfig reloaded = new LlmRuntimeConfig(file);
        StoryVO.Module.ChatModel judge = reloaded.applyModel(ModelScene.CHAPTER_JUDGE, staticModule().getChatModel());
        assertEquals("judge-model", judge.getModel());
        assertEquals(Long.valueOf(1024L), judge.getMaxTokens());
        assertEquals(Double.valueOf(0.1), judge.getTemperature());
    }

    @Test
    void save_sceneOverridesWithUnknownKeyDropped() throws Exception {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        Map<String, LlmConfigSaveRequestDTO.SceneOverride> scenes = new LinkedHashMap<>();
        LlmConfigSaveRequestDTO.SceneOverride unknown = new LlmConfigSaveRequestDTO.SceneOverride();
        unknown.setModel("ghost-model");
        scenes.put("not-a-real-scene", unknown);
        scenes.put("audit", sceneOverride("audit-model", null, null));
        request.setScenes(scenes);
        config.save(request);

        // 未知 key 不落盘（存进去也永远不会被路由匹配），合法 key 正常生效
        String persisted = Files.readString(tmp.resolve("override.json"));
        assertFalse(persisted.contains("not-a-real-scene"));
        assertEquals("audit-model",
                config.applyModel(ModelScene.CHAPTER_AUDIT, staticModule().getChatModel()).getModel());
    }

    @Test
    void view_withStatics_buildsSceneMatrix() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        config.save(sceneSave("chapter-plan", sceneOverride("plan-override", null, null)));

        StoryProperties statics = new StoryProperties();
        StoryVO.Module module = staticModule();
        StoryVO.Module.ChatModel planScene = new StoryVO.Module.ChatModel();
        planScene.setModel("plan-static");
        planScene.setMaxTokens(16384L);
        module.setSceneModels(Map.of("chapter-plan", planScene));
        statics.setModule(module);

        LlmConfigDTO view = config.view(statics);
        // 存在场景覆盖 → configured=true；视图同时携带静态侧场景矩阵
        assertTrue(view.isConfigured());
        assertTrue(view.isUnifiedModelEnabled());
        assertEquals("static-model", view.getUnified().getModel());
        assertEquals(ModelScene.values().length, view.getScenes().size());

        LlmConfigDTO.SceneView planRow = view.getScenes().stream()
                .filter(s -> "chapter-plan".equals(s.getKey())).findFirst().orElseThrow();
        assertTrue(planRow.isSceneConfigured());
        assertEquals("plan-static", planRow.getStaticModel());
        assertEquals("plan-override", planRow.getOverrideModel());
        assertTrue(planRow.isOverridden());

        // 未配置场景的行：静态回落统一 chat-model
        LlmConfigDTO.SceneView summaryRow = view.getScenes().stream()
                .filter(s -> "summary".equals(s.getKey())).findFirst().orElseThrow();
        assertFalse(summaryRow.isSceneConfigured());
        assertEquals("static-model", summaryRow.getStaticModel());
        assertFalse(summaryRow.isOverridden());
    }

    // ===== 场景级 API 开关（sceneApiEnabled）=====

    /** 缺省（从未设置过开关）保持旧行为：场景自带地址密钥原样放行 */
    @Test
    void sceneApi_defaultOn_keepsSceneOwnApi() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        config.save(sceneSave("chapter-judge", sceneOverride("judge-model", null, null)));

        StoryVO.Module.ChatModel sceneApi = new StoryVO.Module.ChatModel();
        sceneApi.setModel("judge-model");
        sceneApi.setBaseUrl("https://api.deepseek.com");
        sceneApi.setApiKey("sk-deepseek");

        StoryVO.Module.ChatModel applied = config.applyModel(ModelScene.CHAPTER_JUDGE, sceneApi);

        assertEquals("https://api.deepseek.com", applied.getBaseUrl());
        assertEquals("sk-deepseek", applied.getApiKey());
    }

    /** 开关关闭：场景自带地址密钥被抹掉（交给网关回落全局 ai-api），模型名不受影响 */
    @Test
    void sceneApi_off_stripsSceneApi_butKeepsModel() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO off = new LlmConfigSaveRequestDTO();
        off.setSceneApiEnabled(Boolean.FALSE);
        config.save(off);

        StoryVO.Module.ChatModel sceneApi = new StoryVO.Module.ChatModel();
        sceneApi.setModel("judge-model");
        sceneApi.setBaseUrl("https://api.deepseek.com");
        sceneApi.setApiKey("sk-deepseek");
        sceneApi.setCompletionsPath("/chat/completions");

        StoryVO.Module.ChatModel applied = config.applyModel(ModelScene.CHAPTER_JUDGE, sceneApi);

        assertNull(applied.getBaseUrl());
        assertNull(applied.getApiKey());
        assertNull(applied.getCompletionsPath());
        assertEquals("judge-model", applied.getModel());
        // 源对象不被污染
        assertEquals("https://api.deepseek.com", sceneApi.getBaseUrl());
    }

    /** 开关打开时，场景级地址密钥覆盖生效；空串=清除该场景密钥覆盖 */
    @Test
    void sceneApi_on_sceneAddressKeyOverrideApplies() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO req = new LlmConfigSaveRequestDTO();
        req.setSceneApiEnabled(Boolean.TRUE);
        LlmConfigSaveRequestDTO.SceneOverride so = sceneOverride("audit-model", null, null);
        so.setBaseUrl("https://scene.example.com/v1");
        so.setApiKey("sk-scene-key-9876");
        req.setScenes(new LinkedHashMap<>(Map.of("audit", so)));
        config.save(req);

        StoryVO.Module.ChatModel applied = config.applyModel(ModelScene.CHAPTER_AUDIT, staticModule().getChatModel());
        assertEquals("https://scene.example.com/v1", applied.getBaseUrl());
        assertEquals("sk-scene-key-9876", applied.getApiKey());
        // 视图只回掩码（矩阵需要 statics.module 装配才会返回场景行）
        StoryProperties statics = new StoryProperties();
        statics.setModule(staticModule());
        LlmConfigDTO.SceneView row = config.view(statics).getScenes().stream()
                .filter(s -> "audit".equals(s.getKey())).findFirst().orElseThrow();
        assertEquals("https://scene.example.com/v1", row.getOverrideBaseUrl());
        assertTrue(row.isOverrideHasKey());
        assertFalse(row.getOverrideApiKeyMasked().contains("scene-key"));
    }

    /** 场景密钥留空提交=保留现有覆盖（密钥不回显，不能因一次普通保存被静默清掉）；空串=显式清除 */
    @Test
    void sceneApi_blankKeyKeepsExisting_explicitEmptyClears() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO first = new LlmConfigSaveRequestDTO();
        LlmConfigSaveRequestDTO.SceneOverride so = sceneOverride(null, 2048L, null);
        so.setApiKey("sk-keep-me");
        first.setScenes(new LinkedHashMap<>(Map.of("summary", so)));
        config.save(first);

        // 只改 tokens、不带 apiKey 字段：密钥保留
        config.save(sceneSave("summary", sceneOverride(null, 4096L, null)));
        StoryVO.Module.ChatModel kept = config.applyModel(ModelScene.CHAPTER_SUMMARY, staticModule().getChatModel());
        assertEquals("sk-keep-me", kept.getApiKey());
        assertEquals(Long.valueOf(4096L), kept.getMaxTokens());

        // 显式空串：清除密钥覆盖，回退静态
        LlmConfigSaveRequestDTO.SceneOverride cleared = sceneOverride(null, 4096L, null);
        cleared.setApiKey("");
        config.save(sceneSave("summary", cleared));
        StoryVO.Module.ChatModel afterClear = config.applyModel(ModelScene.CHAPTER_SUMMARY, staticModule().getChatModel());
        assertNull(afterClear.getApiKey());
    }

    /**
     * 纯密钥场景（除不可见 apiKey 外全空）的完整生命周期：普通保存不丢密钥（不可见字段保护），
     * 行内「恢复」→ 前端发 apiKey 空串 → 整条覆盖消失且不留全空壳（矩阵“已覆盖”徽标不常亮）。
     */
    @Test
    void sceneApi_pureKeyOnlyScene_explicitEmptyClearsWholeEntry() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        // 场景只剩密钥：模拟“填了密钥、其它字段没动就保存”的结果
        LlmConfigSaveRequestDTO.SceneOverride pureKey = sceneOverride(null, null, null);
        pureKey.setApiKey("sk-only-key");
        config.save(sceneSave("summary", pureKey));
        StoryVO.Module.ChatModel withKey =
                config.applyModel(ModelScene.CHAPTER_SUMMARY, staticModule().getChatModel());
        assertEquals("sk-only-key", withKey.getApiKey());

        // 普通保存（该场景不在表里）：不可见字段保护把它原样保住
        config.save(sceneSave("audit", sceneOverride("audit-model", null, null)));
        assertEquals("sk-only-key",
                config.applyModel(ModelScene.CHAPTER_SUMMARY, staticModule().getChatModel()).getApiKey());

        // 「恢复」后的保存：apiKey 空串 → 清除后整条为空壳，必须丢弃而非落成全 null 条目。
        // scenes 是全量替换语义（真实前端整表提交）：audit 条目要在同一请求里带上才不丢
        LlmConfigSaveRequestDTO.SceneOverride clearKey = sceneOverride(null, null, null);
        clearKey.setApiKey("");
        LlmConfigSaveRequestDTO clearBoth = sceneSave("summary", clearKey);
        clearBoth.getScenes().put("audit", sceneOverride("audit-model", null, null));
        config.save(clearBoth);

        StoryVO.Module.ChatModel afterClear =
                config.applyModel(ModelScene.CHAPTER_SUMMARY, staticModule().getChatModel());
        assertNull(afterClear.getApiKey());
        // 整条覆盖已消失：maxTokens 回退静态值（staticModule 的 chat 配置即 8192），非覆盖残留
        assertEquals(Long.valueOf(8192L), afterClear.getMaxTokens());
        // 不留空壳：视图里该场景既无覆盖徽标也无密钥
        StoryProperties statics = new StoryProperties();
        statics.setModule(staticModule());
        LlmConfigDTO.SceneView row = config.view(statics).getScenes().stream()
                .filter(s -> "summary".equals(s.getKey())).findFirst().orElseThrow();
        assertFalse(row.isOverridden());
        assertFalse(row.isOverrideHasKey());
        // 清掉 summary 不影响其它场景
        assertEquals("audit-model",
                config.applyModel(ModelScene.CHAPTER_AUDIT, staticModule().getChatModel()).getModel());
    }

    /** 开关持久化：重启后仍生效 */
    @Test
    void sceneApi_switchSurvivesRelaunch() throws Exception {
        Path file = tmp.resolve("override.json");
        LlmRuntimeConfig config = new LlmRuntimeConfig(file);
        LlmConfigSaveRequestDTO off = new LlmConfigSaveRequestDTO();
        off.setSceneApiEnabled(Boolean.FALSE);
        config.save(off);

        LlmRuntimeConfig reloaded = new LlmRuntimeConfig(file);
        assertEquals(Boolean.FALSE, reloaded.view().getSceneApiEnabled());
        StoryVO.Module.ChatModel sceneApi = new StoryVO.Module.ChatModel();
        sceneApi.setBaseUrl("https://api.deepseek.com");
        sceneApi.setApiKey("sk-deepseek");
        assertNull(reloaded.applyModel(ModelScene.CHAPTER_JUDGE, sceneApi).getBaseUrl());
    }

    /** reset 清空全部覆盖后，开关一并回缺省（=场景可自带 API，保持旧行为） */
    @Test
    void reset_clearsSceneApiSwitch() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        LlmConfigSaveRequestDTO off = new LlmConfigSaveRequestDTO();
        off.setSceneApiEnabled(Boolean.FALSE);
        config.save(off);

        LlmConfigSaveRequestDTO reset = new LlmConfigSaveRequestDTO();
        reset.setReset(true);
        config.save(reset);

        assertNull(config.view().getSceneApiEnabled());
    }

    @Test
    void authMode_chatGptPersistsAndResetReturnsToApiKey() {
        Path file = tmp.resolve("override.json");
        LlmRuntimeConfig config = new LlmRuntimeConfig(file);
        LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
        request.setAuthMode("chatgpt");

        config.save(request);

        assertTrue(config.usesChatGptAuth());
        assertEquals("chatgpt", config.view().getAuthMode());
        assertTrue(new LlmRuntimeConfig(file).usesChatGptAuth());

        LlmConfigSaveRequestDTO reset = new LlmConfigSaveRequestDTO();
        reset.setReset(true);
        config.save(reset);
        assertFalse(config.usesChatGptAuth());
        assertEquals("api-key", config.view().getAuthMode());
    }

    /** 场景温度上限 1.0：前端 max 只是提示可被绕过，服务端保存入口必须拦 */
    @Test
    void save_sceneTemperatureAboveOneIsRejected() {
        LlmRuntimeConfig config = new LlmRuntimeConfig(tmp.resolve("override.json"));
        for (double bad : new double[]{1.1, 1.5, 2.0, -0.1}) {
            LlmConfigSaveRequestDTO.SceneOverride so = new LlmConfigSaveRequestDTO.SceneOverride();
            so.setTemperature(bad);
            Map<String, LlmConfigSaveRequestDTO.SceneOverride> scenes = new LinkedHashMap<>();
            scenes.put("chapter-plan", so);
            LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
            request.setScenes(scenes);
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class, () -> config.save(request),
                    "温度 " + bad + " 必须被拒绝");
            assertTrue(e.getMessage().contains("chapter-plan"), "报错必须指明场景");
        }

        // 边界值 0 与 1 合法，且能正常落盘生效
        for (double ok : new double[]{0.0, 1.0}) {
            LlmConfigSaveRequestDTO.SceneOverride so = new LlmConfigSaveRequestDTO.SceneOverride();
            so.setTemperature(ok);
            Map<String, LlmConfigSaveRequestDTO.SceneOverride> scenes = new LinkedHashMap<>();
            scenes.put("chapter-plan", so);
            LlmConfigSaveRequestDTO request = new LlmConfigSaveRequestDTO();
            request.setScenes(scenes);
            config.save(request);
            assertEquals(Double.valueOf(ok), config.applyModel(ModelScene.CHAPTER_PLAN,
                    staticModule().getChatModel()).getTemperature());
        }
    }
}
