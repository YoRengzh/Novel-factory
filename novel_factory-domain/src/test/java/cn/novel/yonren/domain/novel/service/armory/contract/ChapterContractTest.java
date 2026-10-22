package cn.novel.yonren.domain.novel.service.armory.contract;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节契约与生成模式测试。
 *
 * <p>背景：正文 prompt 此前把"设定 + 记忆 + 计划 + 十条禁令"平级拼接，模型无法判断优先级。
 * 契约把**任务层**提升为可判定对象，生成模式据此决定给多少脚手架（自由 / 骨架 / 强约束）。
 */
class ChapterContractTest {

    @Test
    void completeContractRequiresTaskEventsAndEnding() {
        assertTrue(contract("目标", List.of("事件甲"), "结尾悬念").isComplete());
        // endingHook **不在** ValidateChapterPlanNode 的校验项里 —— 缺它是真实可发生的
        assertFalse(contract("目标", List.of("事件甲"), null).isComplete());
        assertFalse(contract("目标", List.of(), "结尾悬念").isComplete());
        assertFalse(contract(null, List.of("事件甲"), "结尾悬念").isComplete());
    }

    @Test
    void missingPartsNamesWhatIsAbsent() {
        assertEquals(List.of("结尾落点(endingHook)"),
                contract("目标", List.of("事件甲"), null).missingParts());
        assertEquals(List.of("核心任务(goal)", "必须事件(keyEvents)", "结尾落点(endingHook)"),
                contract(null, null, null).missingParts());
        assertTrue(contract("目标", List.of("事件甲"), "悬念").missingParts().isEmpty());
    }

    @Test
    void ofNullPlanYieldsNullContract() {
        assertNull(ChapterContract.of(null, null));
    }

    @Test
    void skeletonFollowsChapterType() {
        assertEquals("日常动作 → 关系或状态变化 → 下一步决定", skeleton(ChapterTypeVO.TRANSITION));
        assertEquals("压力升级 → 做出选择 → 付出代价 → 暂时结果", skeleton(ChapterTypeVO.CLIMAX));
        assertEquals("收束既定线索 → 交代人物去向 → 落在收尾画面上", skeleton(ChapterTypeVO.FINALE));
        assertEquals("目标 → 阻碍 → 转折 → 结果", skeleton(ChapterTypeVO.NORMAL));
        // 章型缺失时退回通用骨架，不抛异常
        assertEquals("目标 → 阻碍 → 转折 → 结果", skeleton(null));
    }

    @Test
    void modeDecide_prefersFreeOnlyWhenContractCompleteAndBeatsUsable() {
        ChapterContract complete = contract("目标", List.of("事件甲"), "悬念");
        assertEquals(GenerationMode.FREE, GenerationMode.decide(complete, true));
        // 契约完整但节拍不可用 → 用兜底骨架
        assertEquals(GenerationMode.SCAFFOLDED, GenerationMode.decide(complete, false));
        // 契约不完整 → 强约束恢复（节拍是否可用都不影响这个判定）
        assertEquals(GenerationMode.RECOVERY,
                GenerationMode.decide(contract("目标", List.of("事件甲"), null), true));
        assertEquals(GenerationMode.RECOVERY, GenerationMode.decide(null, true));
    }

    @Test
    void modeFlags() {
        assertFalse(GenerationMode.FREE.enforcesBeats(), "FREE 下节拍只是建议顺序");
        assertTrue(GenerationMode.SCAFFOLDED.enforcesBeats());
        assertTrue(GenerationMode.RECOVERY.enforcesBeats());
        assertTrue(GenerationMode.RECOVERY.forbidsOutOfPlanElements());
        assertFalse(GenerationMode.SCAFFOLDED.forbidsOutOfPlanElements());
    }

    @Test
    void knowledgeBoundaryOnlyUsesChaptersBeforeCurrent() {
        // 顺序陷阱：必须先按章号过滤、再取最近 N 条。
        // 若先切片后过滤，summaries 里含本章之后的条目时最后 N 条会被整段滤掉，边界静默变空。
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(2).title("第二章").goal("目标")
                .keyEvents(List.of("事件")).endingHook("悬念").build();
        List<ChapterSummaryEntity> summaries = List.of(
                ChapterSummaryEntity.builder().chapterNo(1)
                        .characterStates(List.of(new ChapterSummaryEntity.StateEntry(
                                "软软", "尚未确认无月的身份", "证据"))).build(),
                ChapterSummaryEntity.builder().chapterNo(5)
                        .characterStates(List.of(new ChapterSummaryEntity.StateEntry(
                                "软软", "已经确认无月就是角色癸", "证据"))).build());

        ChapterContract contract = ChapterContract.of(item, summaries);

        assertTrue(contract.knowledgeBoundary().contains("尚未确认"),
                "边界取本章之前的账本");
        assertTrue(!contract.knowledgeBoundary().contains("已经确认"),
                "本章之后的认知不得作为本周边界");
    }

    private ChapterContract contract(String goal, List<String> keyEvents, String endingHook) {
        return ChapterContract.of(ChapterPlanItemEntity.builder()
                .chapterNo(9).title("第九章").goal(goal)
                .characters(List.of("角色癸")).keyEvents(keyEvents).endingHook(endingHook)
                .build(), null);
    }

    private String skeleton(ChapterTypeVO type) {
        return ChapterContract.of(ChapterPlanItemEntity.builder()
                .chapterNo(9).title("第九章").goal("目标")
                .keyEvents(List.of("事件甲")).endingHook("悬念").chapterType(type)
                .build(), null).suggestedSkeleton();
    }
}
