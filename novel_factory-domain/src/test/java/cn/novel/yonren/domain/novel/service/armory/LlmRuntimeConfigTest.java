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
        assertEquals(Long.valueOf(4096L), model.getMaxTokens());
        // 未覆盖字段沿用静态
        assertEquals(Double.valueOf(0.7), model.getTemperature());
        assertEquals(Boolean.FALSE, model.getEnableThinking());
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
        assertEquals(Long.valueOf(2048L), planned.getMaxTokens());
        assertEquals(Double.valueOf(0.3), planned.getTemperature());
        // 未配置覆盖的场景：走全局覆盖
        StoryVO.Module.ChatModel content = config.applyModel(ModelScene.CHAPTER_CONTENT, chat);
        assertEquals("global-model", content.getModel());
        assertEquals(Double.valueOf(0.7), content.getTemperature());
        // 静态对象不被污染
        assertEquals("static-model", chat.getModel());
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
