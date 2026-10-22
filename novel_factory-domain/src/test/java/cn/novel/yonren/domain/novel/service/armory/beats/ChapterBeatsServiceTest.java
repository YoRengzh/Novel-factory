package cn.novel.yonren.domain.novel.service.armory.beats;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterBeatsEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 场景节拍服务测试：正常生成、解析失败降级、节拍数越界降级、渲染输出
 */
class ChapterBeatsServiceTest {

    private LlmGateway llmGateway;
    private ChapterBeatsService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        service = new ChapterBeatsService(llmGateway);
    }

    @Test
    void buildBeats_validJson_returnsBeats() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                "{\"beats\":[{\"location\":\"冰室\",\"characters\":[\"主角乙\",\"角色寅\"],"
                        + "\"conflict\":\"金丹大能逼宫\",\"infoGain\":\"黑剑出土\",\"weight\":\"40%\"},"
                        + "{\"location\":\"洞口\",\"characters\":[\"主角乙\"],"
                        + "\"conflict\":\"三老拦截\",\"infoGain\":\"挥出第一剑\",\"weight\":\"60%\"}]}");

        ChapterBeatsEntity beats = service.buildBeats(storyVO(), item(), 5, "【前情】上一章结尾");

        assertNotNull(beats);
        assertEquals(2, beats.getBeats().size());
        assertEquals("冰室", beats.getBeats().get(0).getLocation());
    }

    @Test
    void buildBeats_parseFailure_returnsNull() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn("这不是 JSON");

        assertNull(service.buildBeats(storyVO(), item(), 5, "【前情】上一章结尾"));
    }

    @Test
    void buildBeats_tooFewBeats_returnsNull() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                "{\"beats\":[{\"location\":\"冰室\",\"characters\":[\"主角乙\"],"
                        + "\"conflict\":\"逼宫\",\"infoGain\":\"出土\",\"weight\":\"100%\"}]}");

        assertNull(service.buildBeats(storyVO(), item(), 5, "【前情】上一章结尾"), "少于下限节拍数应降级");
    }

    @Test
    void buildBeats_llmThrows_returnsNull() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenThrow(new RuntimeException("网络爆炸"));

        assertNull(service.buildBeats(storyVO(), item(), 5, "【前情】上一章结尾"), "异常应 fail-soft 降级");
    }

    @Test
    void renderBeatsPrompt_null_returnsEmpty() {
        assertEquals("", service.renderBeatsPrompt(null));
    }

    @Test
    void renderBeatsPrompt_withBeats_rendersAllFields() {
        ChapterBeatsEntity beats = new ChapterBeatsEntity(List.of(
                new ChapterBeatsEntity.Beat("冰室", List.of("主角乙", "角色寅"), "大能逼宫", null, "黑剑出土", "40%"),
                new ChapterBeatsEntity.Beat("洞口", List.of("主角乙"), "三老拦截", null, "第一剑", "60%")));

        String prompt = service.renderBeatsPrompt(beats);

        assertTrue(prompt.contains("节拍 1"));
        assertTrue(prompt.contains("地点：冰室"));
        assertTrue(prompt.contains("在场：主角乙、角色寅"));
        assertTrue(prompt.contains("冲突：大能逼宫"));
        assertTrue(prompt.contains("信息增量：黑剑出土"));
        assertTrue(prompt.contains("篇幅占比：约40%"));
        assertTrue(prompt.contains("逐拍扩写"));
    }

    @Test
    void hasStateChange_requiresInfoGain() {
        // 节拍 prompt 里早有"每拍必须兑现信息增量"的纪律，但此前**没有任何机械校验**。
        // 判据与纪律同源：infoGain 承载「新信息 / 状态变化 / 关系变化」。
        assertTrue(ChapterBeatsService.hasStateChange(
                new ChapterBeatsEntity.Beat("实验室", null, null, null, "确立敌对关系", null)));
        assertFalse(ChapterBeatsService.hasStateChange(
                        new ChapterBeatsEntity.Beat("实验室", null, "两人争吵", null, null, null)),
                "只有冲突、没有信息增量的拍属「原地循环」，应判为无状态变化");
        assertFalse(ChapterBeatsService.hasStateChange(null));
    }

    @Test
    void renderBeatsPrompt_flagsBeatWithoutStateChange() {
        ChapterBeatsEntity beats = ChapterBeatsEntity.builder()
                .beats(List.of(
                        new ChapterBeatsEntity.Beat("实验室", List.of("角色壬"), "交接", null,
                                "角色壬获得可复核的行为线索", "50%"),
                        new ChapterBeatsEntity.Beat("宿舍", List.of("角色癸"), "独自沉默", null, null, "50%")))
                .build();

        String prompt = service.renderBeatsPrompt(beats);

        assertTrue(prompt.contains("信息增量：角色壬获得可复核的行为线索"));
        assertTrue(prompt.contains("本拍未声明信息增量"), "无状态变化的拍必须被标注为可合并");
        assertTrue(prompt.contains("严禁原地扩写"));
    }

    private StoryVO storyVO() {
        StoryVO storyVO = new StoryVO();
        storyVO.setModule(new StoryVO.Module());
        return storyVO;
    }

    private ChapterPlanItemEntity item() {
        ChapterPlanItemEntity item = new ChapterPlanItemEntity();
        item.setChapterNo(5);
        item.setTitle("拔剑出渊");
        item.setGoal("拔剑");
        item.setKeyEvents(List.of("拔剑", "迎战"));
        item.setEndingHook("一剑斩出");
        return item;
    }


    // ==================== 移植自旧系统的闸门用例 ====================

    /** 生产实测（第 131/133/135 章）：模型无视 prompt 的 {"beats":…} 包装、直接返回顶层数组 */
    @Test
    void buildBeats_bareArray_isWrappedAndParsed() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                "[{\"location\":\"某家书房\",\"characters\":[\"主角甲\",\"角色丁\"],"
                        + "\"conflict\":\"化学陪练\",\"infoGain\":\"溶液平衡短板暴露\",\"weight\":\"50%\"},"
                        + "{\"location\":\"桂花树下\",\"characters\":[\"主角甲\"],"
                        + "\"conflict\":\"省队约定\",\"infoGain\":\"双向绑定成立\",\"weight\":\"50%\"}]");

        ChapterBeatsEntity beats = service.buildBeats(storyVO(), item(), 131, "【前情】上一章结尾");

        assertNotNull(beats, "裸数组应被包装后解析成功，不得降级");
        assertEquals(2, beats.getBeats().size());
        assertEquals("某家书房", beats.getBeats().get(0).getLocation());
        assertEquals("桂花树下", beats.getBeats().get(1).getLocation());
    }

    /** 围栏包裹的裸数组同样兜底：```json 开头的顶层数组不能漏网 */
    @Test
    void buildBeats_fencedBareArray_isWrappedAndParsed() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                "```json\n[{\"location\":\"考场\",\"characters\":[\"主角甲\"],"
                        + "\"conflict\":\"压轴题\",\"infoGain\":\"攻克\",\"weight\":\"60%\"},"
                        + "{\"location\":\"榜单前\",\"characters\":[\"主角甲\"],"
                        + "\"conflict\":\"质疑\",\"infoGain\":\"进省队\",\"weight\":\"40%\"}]\n```");

        ChapterBeatsEntity beats = service.buildBeats(storyVO(), item(), 132, "【前情】上一章结尾");

        assertNotNull(beats, "围栏裸数组应被剥离围栏后包装解析");
        assertEquals(2, beats.getBeats().size());
        assertEquals("考场", beats.getBeats().get(0).getLocation());
    }

    /** 节拍 prompt 必须把 dialogue 写进 JSON 示例——字段不进示例就等于不存在（本项目已有此教训） */
    @Test
    void buildBeats_promptDeclaresDialogueInSchema() {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(
                "{\"beats\":[{\"location\":\"冰室\",\"characters\":[\"主角乙\"],\"conflict\":\"逼宫\","
                        + "\"dialogue\":\"「让开。」\",\"infoGain\":\"出土\",\"weight\":\"40%\"},"
                        + "{\"location\":\"洞口\",\"characters\":[\"主角乙\"],\"conflict\":\"拦截\","
                        + "\"dialogue\":\"\",\"infoGain\":\"第一剑\",\"weight\":\"60%\"}]}");
        org.mockito.ArgumentCaptor<LlmCall> captor = org.mockito.ArgumentCaptor.forClass(LlmCall.class);

        ChapterBeatsEntity beats = service.buildBeats(storyVO(), item(), 5, "【前情】上一章结尾");

        org.mockito.Mockito.verify(llmGateway).complete(any(), captor.capture());
        String sent = captor.getValue().getUserPrompt();
        assertTrue(sent.contains("\"dialogue\""), "JSON 示例必须含 dialogue：" + sent);
        assertTrue(sent.contains("对白供给"), "必须写明对白供给的要求：" + sent);
        assertNotNull(beats);
        assertTrue(ChapterBeatsService.hasDialogue(beats.getBeats().get(0)));
        assertFalse(ChapterBeatsService.hasDialogue(beats.getBeats().get(1)),
                "留空字符串表示本拍无对白");
    }

    /**
     * FREE 模式（enforcesBeats=false）下节拍只是建议顺序——此前这句语义由 ChapterWorker 的
     * FREE_BEATS_NOTE 在节拍块**之前**追加，紧跟着的节拍块又写"请逐拍扩写，不要跳过或合并"，
     * 后者更靠后更具体、实际生效，enforcesBeats() 于是从未起过作用。
     */
    @Test
    void renderBeatsPrompt_advisoryMode_dropsForcedExpansion() {
        ChapterBeatsEntity beats = ChapterBeatsEntity.builder()
                .beats(List.of(
                        new ChapterBeatsEntity.Beat("房间", List.of("主角甲"), "取出信纸",
                                "「明天寄。」", "准备回信", "50%"),
                        new ChapterBeatsEntity.Beat("邮局", List.of("主角甲"), "投递", null, "信已寄出", "50%")))
                .build();

        String advisory = service.renderBeatsPrompt(beats, false);
        String enforced = service.renderBeatsPrompt(beats, true);

        assertTrue(advisory.contains("建议顺序"), advisory);
        assertTrue(advisory.contains("可自行调整、合并或重组"), advisory);
        assertFalse(advisory.contains("不要跳过或合并"), "建议模式下不得再要求逐拍强制扩写：" + advisory);
        assertTrue(enforced.contains("不要跳过或合并"), enforced);
        assertFalse(enforced.contains("建议顺序"), enforced);
    }

    /**
     * 全章零对白拍 → 在 prompt 侧补一条硬要求（根因在节拍生成侧，这里只兜底告知）。
     *
     * <p>实测第 96 章：节拍五要素里没有对白，写手逐拍扩写后全章有效对话仅 9 句、
     * 轮次密度 4.4/千字（劣化线 6.0），同批其它章 12-27。
     */
    @Test
    void renderBeatsPrompt_allSilentBeats_addsDialogueFallback() {
        ChapterBeatsEntity beats = ChapterBeatsEntity.builder()
                .beats(List.of(
                        new ChapterBeatsEntity.Beat("房间", List.of("主角甲"), "取出信纸", null, "准备回信", "50%"),
                        new ChapterBeatsEntity.Beat("房间", List.of("主角甲"), "折好塞入", null, "信件就位", "50%")))
                .build();

        String prompt = service.renderBeatsPrompt(beats);

        assertTrue(prompt.contains("对白承载·兜底"), prompt);
        assertTrue(prompt.contains("至少安排 1 场由对白承载的关键事件"), prompt);
    }

    /**
     * 对白是节拍表的显式供给项：有对白的拍要渲染出来，没有的拍要标注——
     * 只给"冲突/动作"时，"他把信折好塞进信封"是合法的一拍，写手据此就能交出一整章动作分解。
     */
    @Test
    void renderBeatsPrompt_rendersDialogueAndMarksSilentBeats() {
        ChapterBeatsEntity beats = ChapterBeatsEntity.builder()
                .beats(List.of(
                        new ChapterBeatsEntity.Beat("邮局", List.of("主角甲", "角色丁"), "买邮票",
                                "「这回她能看到草稿纸？」", "确认通信范式", "50%"),
                        new ChapterBeatsEntity.Beat("家中", List.of("主角甲"), "整理信件", null, "信件归位", "50%")))
                .build();

        String prompt = service.renderBeatsPrompt(beats);

        assertTrue(prompt.contains("对白：「这回她能看到草稿纸？」"), prompt);
        assertTrue(prompt.contains("对白：（本拍无对白）"), "无对白的拍必须被标注：" + prompt);
        assertFalse(prompt.contains("对白承载·兜底"), "有对白拍时不应触发全章兜底提示：" + prompt);
    }
}
