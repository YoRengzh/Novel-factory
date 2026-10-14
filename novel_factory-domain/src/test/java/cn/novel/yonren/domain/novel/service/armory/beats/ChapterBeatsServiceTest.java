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
                new ChapterBeatsEntity.Beat("冰室", List.of("主角乙", "角色寅"), "大能逼宫", "黑剑出土", "40%"),
                new ChapterBeatsEntity.Beat("洞口", List.of("主角乙"), "三老拦截", "第一剑", "60%")));

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
                new ChapterBeatsEntity.Beat("实验室", null, null, "确立敌对关系", null)));
        assertFalse(ChapterBeatsService.hasStateChange(
                        new ChapterBeatsEntity.Beat("实验室", null, "两人争吵", null, null)),
                "只有冲突、没有信息增量的拍属「原地循环」，应判为无状态变化");
        assertFalse(ChapterBeatsService.hasStateChange(null));
    }

    @Test
    void renderBeatsPrompt_flagsBeatWithoutStateChange() {
        ChapterBeatsEntity beats = ChapterBeatsEntity.builder()
                .beats(List.of(
                        new ChapterBeatsEntity.Beat("实验室", List.of("许知意"), "交接",
                                "许知意获得可复核的行为线索", "50%"),
                        new ChapterBeatsEntity.Beat("宿舍", List.of("江燃"), "独自沉默", null, "50%")))
                .build();

        String prompt = service.renderBeatsPrompt(beats);

        assertTrue(prompt.contains("信息增量：许知意获得可复核的行为线索"));
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
}
