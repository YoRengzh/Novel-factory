package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体档案注入测试（docs/enhancement-plan.md E1）：
 * 休眠实体（超出 ACTIVE_WINDOW 未被提及）在正文/规划被涉及时注入档案；
 * 活跃实体不注入（近章摘要已覆盖）；封顶 3 个按最久未见优先；关系与早期事实入档
 */
class ChapterMemoryServiceDossierTest {

    private final ChapterMemoryService service = new ChapterMemoryService(new ForeshadowPriorityService());

    private ChapterSummaryEntity summary(int chapterNo, String characterName, String status) {
        ChapterSummaryEntity.StateEntry state = new ChapterSummaryEntity.StateEntry();
        state.setName(characterName);
        state.setStatus(status);
        return ChapterSummaryEntity.builder()
                .chapterNo(chapterNo)
                .characterStates(List.of(state))
                .build();
    }

    @Test
    void dossier_itemMentionsDormantCharacter_injectsArchive() {
        // 老陈只在第 1 章出现（lastChapterNo=1），最新 15 章 → 休眠；本章计划又涉及他
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "老陈", "铁匠铺掌柜"),
                summary(15, "小陈", "主角"));
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(16).title("回铁匠铺").goal("重返铁匠铺")
                .characters(List.of("小陈", "老陈")).keyEvents(List.of("买兵器")).build();

        List<PromptBudgetGuard.Block> blocks = service.buildEntityDossierBlocks(summaries, item, null);

        assertEquals(1, blocks.size());
        String body = blocks.get(0).content();
        assertTrue(body.contains("【实体档案·老陈】"), body);
        assertTrue(body.contains("首见第1章"), body);
        assertTrue(body.contains("上次出现第1章"), body);
        assertTrue(body.contains("当前状态：铁匠铺掌柜"), body);
    }

    @Test
    void dossier_activeEntity_notInjected() {
        // 老陈休眠但本章不涉及他；小陈活跃（近章摘要已覆盖，不需要档案）
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "老陈", "铁匠铺掌柜"),
                summary(15, "小陈", "主角"));
        ChapterPlanItemEntity item = ChapterPlanItemEntity.builder()
                .chapterNo(16).title("独自行动").goal("小陈闭关")
                .characters(List.of("小陈")).build();

        List<PromptBudgetGuard.Block> blocks = service.buildEntityDossierBlocks(summaries, item, null);

        assertTrue(blocks.isEmpty(), "活跃实体与未被涉及的休眠实体都不注入");
    }

    @Test
    void dossier_planningMode_nullItem_takesOldestFirst_cappedAtThree() {
        // 四个休眠实体：老陈/老王/老李 last=1（最久），阿七 last=5；规划路径取最久未见的前 3 个
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, "老陈", "甲"),
                summary(2, "老王", "乙"),
                summary(3, "老李", "丙"),
                summary(5, "阿七", "丁"),
                summary(15, "小陈", "主角"));

        List<PromptBudgetGuard.Block> blocks = service.buildEntityDossierBlocks(summaries, null, null);

        // 所有实体合并进同一个块：预算守卫要求场景内 label 唯一，多块同 label 会炸规划路径
        assertEquals(1, blocks.size());
        String joined = blocks.get(0).content();
        assertTrue(joined.contains("老陈") && joined.contains("老王") && joined.contains("老李"),
                "最久未见的实体优先：" + joined);
        assertFalse(joined.contains("【实体档案·阿七】"), "最近休眠的让位给更久的");
    }

    @Test
    void dossier_relationAndEarlyFact_included() {
        ChapterSummaryEntity first = summary(1, "老陈", "铁匠铺掌柜");
        ChapterSummaryEntity.ConsistencyFact fact = new ChapterSummaryEntity.ConsistencyFact();
        fact.setType("INJURY");
        fact.setSubject("老陈");
        fact.setValue("左臂被妖兽咬伤");
        first.setConsistencyFacts(List.of(fact));
        List<ChapterSummaryEntity> summaries = List.of(first, summary(15, "小陈", "主角"));

        ConsistencyIndexEntity.RelationEntry relation = new ConsistencyIndexEntity.RelationEntry();
        relation.setPair("老陈与小陈");
        relation.setRelation("师徒");
        relation.setFirstChapter(1);
        relation.setLastChapter(3);
        ConsistencyIndexEntity index = ConsistencyIndexEntity.builder()
                .relations(List.of(relation))
                .build();

        List<PromptBudgetGuard.Block> blocks = service.buildEntityDossierBlocks(
                summaries, ChapterPlanItemEntity.builder()
                        .chapterNo(16).goal("去找老陈").characters(List.of("老陈")).build(),
                index);

        assertEquals(1, blocks.size());
        String body = blocks.get(0).content();
        assertTrue(body.contains("关系：老陈与小陈——师徒（第3章）"), body);
        assertTrue(body.contains("第1章：左臂被妖兽咬伤"), body);
    }

    @Test
    void dossier_emptySummaries_returnsEmpty() {
        assertTrue(service.buildEntityDossierBlocks(List.of(), null, null).isEmpty());
        assertTrue(service.buildEntityDossierBlocks(null, null, null).isEmpty());
    }

    private void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }
}
