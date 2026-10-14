package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.FinaleAuditEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 终局审查测试：四维审计、证据机械校验（子串命中/编造/越界）、四维不齐/异常 fail-soft、
 * 开放伏笔入参进 prompt
 */
class FinaleReviewServiceTest {

    private LlmGateway llmGateway;
    private FinaleReviewService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        service = new FinaleReviewService(llmGateway);
    }

    @Test
    void review_allFourPass() {
        StageBlueprintEntity stage = finalStage();
        stubModel("{\"overall\":true,\"dimensions\":["
                + "{\"dimension\":\"FINALE_COMMITMENTS\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"FORESHADOW\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"CHARACTER_FATE\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"WORLD_STATE\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"}]}");

        FinaleAuditEntity audit = service.review(module(), stage,
                List.of(openForeshadow()), summaries(), null);

        assertEquals(4, audit.getDimensions().size());
        assertTrue(audit.isOverallPass());
        assertFalse(audit.isForcedClose());
        assertTrue(audit.getReworkTasks().isEmpty());
    }

    @Test
    void review_fabricatedEvidenceJudgedUnmet() {
        StageBlueprintEntity stage = finalStage();
        stubModel("{\"overall\":false,\"dimensions\":["
                + "{\"dimension\":\"FINALE_COMMITMENTS\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"FORESHADOW\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"CHARACTER_FATE\",\"met\":true,\"chapterNo\":5,\"evidence\":\"这句话在摘要里不存在\"},"
                + "{\"dimension\":\"WORLD_STATE\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"}],"
                + "\"openForeshadows\":[\"魔种未清\"]}");

        FinaleAuditEntity audit = service.review(module(), stage,
                List.of(openForeshadow()), summaries(), null);

        assertFalse(audit.isOverallPass());
        FinaleAuditEntity.DimensionResult fate = audit.getDimensions().stream()
                .filter(d -> FinaleReviewService.DIM_CHARACTER_FATE.equals(d.getDimension())).findFirst().orElseThrow();
        // 宁严勿松：引用非该章记忆原文 → 未通过，证据置空
        assertFalse(fate.getMet());
        assertNull(fate.getEvidence());
        assertTrue(fate.getNote().contains("证据校验失败"));
        // 缺口落入 reworkTasks 供末卷返工
        assertTrue(audit.getReworkTasks().stream().anyMatch(t -> t.contains("主要角色命运")));
    }

    @Test
    void review_chapterNoOutOfRangeJudgedUnmet() {
        StageBlueprintEntity stage = finalStage();
        stubModel("{\"overall\":false,\"dimensions\":["
                + "{\"dimension\":\"FINALE_COMMITMENTS\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"FORESHADOW\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"CHARACTER_FATE\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"WORLD_STATE\",\"met\":true,\"chapterNo\":99,\"evidence\":\"气旋一涨\"}]}");

        FinaleAuditEntity audit = service.review(module(), stage,
                List.of(openForeshadow()), summaries(), null);

        assertFalse(audit.isOverallPass());
        FinaleAuditEntity.DimensionResult world = audit.getDimensions().stream()
                .filter(d -> FinaleReviewService.DIM_WORLD_STATE.equals(d.getDimension())).findFirst().orElseThrow();
        assertFalse(world.getMet());
        assertTrue(world.getNote().contains("越界"));
    }

    @Test
    void review_dimensionMissingReturnsNull() {
        StageBlueprintEntity stage = finalStage();
        stubModel("{\"overall\":true,\"dimensions\":["
                + "{\"dimension\":\"FINALE_COMMITMENTS\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"FORESHADOW\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"},"
                + "{\"dimension\":\"CHARACTER_FATE\",\"met\":true,\"chapterNo\":5,\"evidence\":\"气旋一涨\"}]}");

        assertNull(service.review(module(), stage, List.of(), summaries(), null));
    }

    @Test
    void review_llmFailureReturnsNull() {
        StageBlueprintEntity stage = finalStage();
        when(llmGateway.complete(any(), any(LlmCall.class))).thenThrow(new RuntimeException("timeout"));

        assertNull(service.review(module(), stage, List.of(), summaries(), null));
    }

    @Test
    void review_noStageRangeSummariesReturnsNull() {
        StageBlueprintEntity stage = finalStage();
        // 无区间内摘要 → 不可核验
        assertNull(service.review(module(), stage, List.of(),
                List.of(ChapterSummaryEntity.builder().chapterNo(1).summary("x").build()), null));
    }

    @Test
    void review_promptCarriesFourDimensionsAndOpenForeshadows() {
        StageBlueprintEntity stage = finalStage();
        stubModel("{\"overall\":true,\"dimensions\":["
                + "{\"dimension\":\"FINALE_COMMITMENTS\",\"met\":false,\"note\":\"缺口\"},"
                + "{\"dimension\":\"FORESHADOW\",\"met\":false,\"note\":\"缺口\"},"
                + "{\"dimension\":\"CHARACTER_FATE\",\"met\":false,\"note\":\"缺口\"},"
                + "{\"dimension\":\"WORLD_STATE\",\"met\":false,\"note\":\"缺口\"}]}");
        Map<String, String> sink = new HashMap<>();

        service.review(module(), stage, List.of(openForeshadow()), summaries(), sink);

        String user = sink.get("user");
        assertTrue(user.contains("FINALE_COMMITMENTS"));
        assertTrue(user.contains("FORESHADOW"));
        assertTrue(user.contains("CHARACTER_FATE"));
        assertTrue(user.contains("WORLD_STATE"));
        assertTrue(user.contains("宁严勿松"));
        // 开放伏笔进 prompt
        assertTrue(user.contains("魔种未清"));
        // 终局承诺来源：未兑现 beats 注入
        assertTrue(user.contains("剿灭魔种群"));
        // 可核验文本 = 摘要 + 账本状态串，与机械校验同源
        assertTrue(user.contains("气旋一涨"));
        assertTrue(user.contains("（幽冥谷：公开敌对）"));
    }

    private StageBlueprintEntity finalStage() {
        return StageBlueprintEntity.builder()
                .stageNo(6)
                .startChapter(1)
                .endChapter(10)
                .storyPhase("EPILOGUE")
                .finalVolumeDeclared(true)
                .stageGoal("灾后收束")
                .completedFinaleBeats(List.of("主角突破", "平息兽潮"))
                .remainingFinaleBeats(List.of("剿灭魔种群"))
                .exitConditions(List.of("魔种群覆灭", "幽冥谷安分"))
                .build();
    }

    private ForeshadowPriorityService.ScoredForeshadow openForeshadow() {
        return new ForeshadowPriorityService.ScoredForeshadow(
                new ChapterMemoryService.PendingForeshadow(3, "魔种未清，恐成后患", "魔种未清", 4),
                90, ForeshadowPriorityService.Tier.BREAKER);
    }

    private List<ChapterSummaryEntity> summaries() {
        return List.of(
                ChapterSummaryEntity.builder()
                        .chapterNo(5).title("第5章")
                        .summary("小陈夜探废矿，丹田气旋一涨，炼气九层的门槛被他一脚跨过。魔种群在巢穴被尽数焚灭。")
                        .build(),
                ChapterSummaryEntity.builder()
                        .chapterNo(9).title("第9章")
                        .summary("幽冥谷使者夜袭外门，被击退后订立盟约。")
                        .characterStates(List.of(new ChapterSummaryEntity.StateEntry(
                                "幽冥谷", "公开敌对", "幽冥谷使者夜袭外门")))
                        .factionStates(List.of(new ChapterSummaryEntity.StateEntry(
                                "外门", "重建中", "外门开始重建")))
                        .build());
    }

    private StoryVO.Module module() {
        return new StoryVO.Module();
    }

    private void stubModel(String content) {
        when(llmGateway.complete(any(), any(LlmCall.class))).thenReturn(content);
    }
}