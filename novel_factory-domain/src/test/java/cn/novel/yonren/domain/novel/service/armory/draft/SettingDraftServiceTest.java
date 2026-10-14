package cn.novel.yonren.domain.novel.service.armory.draft;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设定集草稿服务测试：一键全量生成、单字段重生成时的机械锁定、
 * 重生成必须"真的换方向"的 prompt 约束、以及解析失败/字段缺失的重试闭环。
 */
class SettingDraftServiceTest {

    /** 模型产出的完整草稿（含一个 schema 之外的多余键 notes，用来验证不会因此整份丢弃） */
    private static final String FULL_DRAFT = "{\"novelTitle\":\"示例书\",\"style\":\"冷硬悬疑，节奏紧\","
            + "\"worldSetting\":\"模型给的世界观\",\"perspective\":\"第三人称限知视角\",\"targetAudience\":\"男频\","
            + "\"tone\":\"压抑里留一线希望\",\"protagonist\":\"模型给的主人公\","
            + "\"outline\":\"模型给的故事概述\",\"chapterGoal\":\"让读者体验层层解谜的快感\","
            + "\"totalChapters\":180,\"rationale\":\"以十七年前旧案牵引主线\",\"notes\":\"这是schema外的多余键\"}";

    /** 第 2 次尝试才可解析：第一次给个残缺 JSON */
    private static final String TRUNCATED = "{\"novelTitle\":\"示例书\",\"style\":\"冷硬悬疑\"";

    /** 完全不是 JSON（模型拒答/串到闲聊）：JsonRepair 也救不回来 */
    private static final String NOT_JSON = "抱歉，我无法按要求生成这份设定集。";

    /** 可解析但 outline 为空 —— 属于"缺失字段"，应触发重试而非直接采用 */
    private static final String MISSING_OUTLINE = "{\"novelTitle\":\"示例书\",\"style\":\"冷硬悬疑，节奏紧\","
            + "\"worldSetting\":\"模型给的世界观\",\"perspective\":\"第三人称限知视角\",\"targetAudience\":\"男频\","
            + "\"tone\":\"压抑里留一线希望\",\"protagonist\":\"模型给的主人公\","
            + "\"outline\":\"   \",\"chapterGoal\":\"让读者体验层层解谜的快感\",\"totalChapters\":180}";

    private static final StoryVO STORY_VO = new StoryVO();

    @Mock
    private LlmInvokeService llmInvokeService;

    @InjectMocks
    private SettingDraftService settingDraftService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ==================== 全量生成 ====================

    @Test
    void draft_whenNoTargets_generatesAllFields() {
        stubLlm(FULL_DRAFT);

        SettingDraftService.Result result = settingDraftService.draft(STORY_VO, request(null, null));

        assertEquals("示例书", result.fields().getNovelTitle());
        assertEquals("模型给的世界观", result.fields().getWorldSetting());
        assertEquals("模型给的故事概述", result.fields().getOutline());
        assertEquals(180, result.fields().getTotalChapters());
        // 空 targets 归一为"全部字段"
        assertEquals(10, result.regenerated().size());
        assertTrue(result.regenerated().contains("outline"));
        verify(llmInvokeService, times(1)).invoke(any(), eq(PromptScene.SETTING_DRAFT), any(), anyString(), any());
    }

    @Test
    void draft_promptOnlyNeedsTheme_andCarriesUserPinnedPreferences() {
        stubLlm(FULL_DRAFT);

        SettingDraftService.DraftRequest req = new SettingDraftService.DraftRequest(
                "都市异能", "逆袭打脸爽文", "男频", "紧张带爽感", "第三人称",
                "不要系统流", null, null);
        settingDraftService.draft(STORY_VO, req);

        String prompt = capturedPrompt(1).get(0);
        assertTrue(prompt.contains("【题材】都市异能"), "题材必须进 prompt");
        assertTrue(prompt.contains("用户已定的风格倾向】逆袭打脸爽文"), "用户偏好应作为约束传入");
        assertTrue(prompt.contains("不要系统流"), "额外要求必须进 prompt");
        // 首次生成没有旧值，不该出现"保持既有设定"这类只对重生成有意义的段落
        assertTrue(!prompt.contains("必须保持不变的既有设定"), "无旧值时不应出现锁定段落");
        assertTrue(!prompt.contains("与上一版的区别"), "无旧值时不应出现差异化段落");
    }

    // ==================== 单字段重生成：机械锁定 ====================

    @Test
    void draft_targetsOutline_keepsLockedFieldsFromPrevious_evenIfModelRewritesThem() {
        stubLlm(FULL_DRAFT);
        Map<String, String> previous = Map.of(
                "novelTitle", "上一版书名",
                "worldSetting", "上一版世界观",
                "protagonist", "上一版主人公",
                "totalChapters", "240",
                "outline", "上一版故事概述");

        SettingDraftService.Result result = settingDraftService.draft(STORY_VO, request(List.of("outline"), previous));

        // 目标字段采纳模型的新产出
        assertEquals("模型给的故事概述", result.fields().getOutline());
        // 非目标字段一律以调用方给的值为准——模型即使改了也不生效
        assertEquals("上一版书名", result.fields().getNovelTitle());
        assertEquals("上一版世界观", result.fields().getWorldSetting());
        assertEquals("上一版主人公", result.fields().getProtagonist());
        assertEquals(240, result.fields().getTotalChapters());
        assertEquals(List.of("outline"), result.regenerated());
    }

    @Test
    void draft_targetsOutline_promptLocksExistingSettingAndDemandsADifferentDirection() {
        stubLlm(FULL_DRAFT);
        Map<String, String> previous = Map.of(
                "worldSetting", "上一版世界观", "outline", "上一版故事概述");

        settingDraftService.draft(STORY_VO, request(List.of("outline"), previous));

        String prompt = capturedPrompt(1).get(0);
        assertTrue(prompt.contains("必须保持不变的既有设定"), "应告知模型哪些设定被锁定");
        assertTrue(prompt.contains("上一版世界观"), "锁定字段的具体取值必须喂给模型，否则新内容会与之矛盾");
        assertTrue(prompt.contains("本次需要重写的字段】故事概述"), "应指明本次只重写故事概述");
        assertTrue(prompt.contains("与上一版的区别"), "重生成必须要求换方向");
        assertTrue(prompt.contains("上一版故事概述"), "上一版内容要作为对照传入");
        // 非目标字段的上一版值不应出现在"区别"段落里（它属于锁定段落）
        assertTrue(prompt.indexOf("上一版故事概述") > prompt.indexOf("与上一版的区别"),
                "上一版概述应出现在差异化段落之后");
    }

    @Test
    void draft_targetsOutlineWithBlankPrevious_behavesLikeFullGeneration() {
        stubLlm(FULL_DRAFT);

        SettingDraftService.Result result = settingDraftService.draft(STORY_VO,
                request(List.of("outline"), Map.of("worldSetting", "   ")));

        // 旧值全空 → 无可锁定项，模型产出原样保留
        assertEquals("模型给的世界观", result.fields().getWorldSetting());
        String prompt = capturedPrompt(1).get(0);
        assertTrue(!prompt.contains("必须保持不变的既有设定"));
    }

    // ==================== 入参校验 ====================

    @Test
    void draft_withoutTheme_rejectedAsIllegalParameter() {
        // 直接构造（不走 helper）——helper 默认带题材，用它测"题材为空"会测了个寂寞
        AppException ex = assertThrows(AppException.class, () -> settingDraftService.draft(STORY_VO,
                new SettingDraftService.DraftRequest(null, null, null, null, null, null, null, null)));
        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), ex.getCode());
        assertTrue(ex.getInfo().contains("题材"));

        AppException blank = assertThrows(AppException.class, () -> settingDraftService.draft(STORY_VO,
                new SettingDraftService.DraftRequest("   ", null, null, null, null, null, null, null)));
        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), blank.getCode());

        verify(llmInvokeService, times(0)).invoke(any(), any(), any(), anyString(), any());
    }

    @Test
    void draft_withUnknownTargetField_rejectedInsteadOfSilentlyIgnored() {
        AtomicInteger calls = stubLlmReturning(FULL_DRAFT);

        AppException ex = assertThrows(AppException.class,
                () -> settingDraftService.draft(STORY_VO, request(List.of("world_setting"), null)));

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), ex.getCode());
        assertTrue(ex.getInfo().contains("world_setting"), "报错要指出写错的字段名，否则'点了重生却什么都没变'极难排查");
        assertEquals(0, calls.get(), "参数校验不通过时不该浪费一次 LLM 调用");
    }

    // ==================== 重试闭环 ====================

    @Test
    void draft_whenOutputIsNotJsonAtAll_retrySaysFormatIsInvalid() {
        stubLlm(NOT_JSON, FULL_DRAFT);

        SettingDraftService.Result result = settingDraftService.draft(STORY_VO, request(null, null));

        assertEquals("示例书", result.fields().getNovelTitle());
        List<String> prompts = capturedPrompt(2);
        assertTrue(prompts.get(1).contains("JSON 格式不合法"),
                "解析彻底失败时应告诉模型是格式问题，而不是罗列一堆'字段缺失'误导它");
        assertTrue(!prompts.get(1).contains("这些字段缺失"),
                "格式失败时不该走字段缺失的措辞——两者给模型的指令不同");
    }

    @Test
    void draft_whenOutputTruncated_repairSalvagesWhatItCan_thenNamesTheMissingFields() {
        stubLlm(TRUNCATED, FULL_DRAFT);

        SettingDraftService.Result result = settingDraftService.draft(STORY_VO, request(null, null));

        assertEquals("模型给的故事概述", result.fields().getOutline());
        List<String> prompts = capturedPrompt(2);
        // 截断的 JSON 会被 JsonRepair 补齐成"只有前两个字段"的合法对象 → 走字段缺失分支
        assertTrue(prompts.get(1).contains("缺失或为空"), "被修复但字段不全时，应走字段缺失措辞");
        assertTrue(prompts.get(1).contains("outline"), "缺失字段名要逐个点名，模型才知道该补什么");
    }

    @Test
    void draft_whenFieldMissing_retriesWithThatFieldName() {
        stubLlm(MISSING_OUTLINE, FULL_DRAFT);

        SettingDraftService.Result result = settingDraftService.draft(STORY_VO, request(null, null));

        assertEquals("模型给的故事概述", result.fields().getOutline());
        List<String> prompts = capturedPrompt(2);
        assertTrue(prompts.get(1).contains("outline"), "缺失字段名应出现在纠错指令里");
    }

    @Test
    void draft_whenBothAttemptsUnusable_throwsWithActionableMessage() {
        stubLlm(TRUNCATED, TRUNCATED);

        AppException ex = assertThrows(AppException.class,
                () -> settingDraftService.draft(STORY_VO, request(null, null)));

        assertEquals(ResponseCode.UN_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getInfo().contains("重试"), "报错应给出可操作的建议");
        verify(llmInvokeService, times(2)).invoke(any(), eq(PromptScene.SETTING_DRAFT), any(), anyString(), any());
    }

    @Test
    void draft_toleratesExtraKeysInModelOutput() {
        stubLlm(FULL_DRAFT);

        // FULL_DRAFT 里有一个 schema 之外的 notes 键：不应导致整份草稿被判为解析失败
        SettingDraftService.Result result = settingDraftService.draft(STORY_VO, request(null, null));

        assertEquals("示例书", result.fields().getNovelTitle());
    }

    @Test
    void draft_carriesThemeIntoPromptContext_forGenreMaterialRouting() {
        stubLlm(FULL_DRAFT);

        settingDraftService.draft(STORY_VO, request(null, null));

        ArgumentCaptor<cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext> ctxCaptor =
                ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext.class);
        verify(llmInvokeService).invoke(any(), any(), ctxCaptor.capture(), anyString(), any());
        // theme/style 进 PromptContext 才能让题材资料策略（GenreMaterialStrategy）路由到对应题材资料包
        assertEquals("都市异能", ctxCaptor.getValue().getTheme());
        assertNull(ctxCaptor.getValue().getChapterNo());
    }

    // ==================== helpers ====================

    private static SettingDraftService.DraftRequest request(List<String> targets, Map<String, String> previous) {
        return new SettingDraftService.DraftRequest(
                "都市异能", null, null, null, null, null, targets, previous);
    }

    /** 单次 LLM 打桩：命中后按调用次数依次返回（避免对同一 mock 二次打桩触发旧答案） */
    private AtomicInteger stubLlmReturning(String... outputs) {
        AtomicInteger calls = new AtomicInteger();
        when(llmInvokeService.invoke(any(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> outputs[Math.min(calls.getAndIncrement(), outputs.length - 1)]);
        return calls;
    }

    private void stubLlm(String... outputs) {
        stubLlmReturning(outputs);
    }

    /** 取第 n 次调用的 userPrompt（第 4 个入参） */
    private List<String> capturedPrompt(int expectedCalls) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llmInvokeService, times(expectedCalls))
                .invoke(any(), any(), any(), captor.capture(), any());
        return captor.getAllValues();
    }

}
