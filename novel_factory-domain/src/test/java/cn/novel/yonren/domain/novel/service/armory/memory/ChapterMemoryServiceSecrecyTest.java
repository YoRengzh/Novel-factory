package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本章禁泄清单构建测试：登记 payoffHints 的未揭伏笔进清单；揭示章（计划带回收/揭示意图）
 * 豁免；未登记/已回收/存量老数据自动跳过
 */
class ChapterMemoryServiceSecrecyTest {

    private final ChapterMemoryService service = new ChapterMemoryService(new ForeshadowPriorityService());

    private ChapterSummaryEntity summary(int chapterNo, String newForeshadow, String payoffHint) {
        ChapterSummaryEntity.SeedEntry seed = new ChapterSummaryEntity.SeedEntry();
        seed.setContent(newForeshadow);
        seed.setExcerpt(null);
        seed.setImportance(4);
        seed.setPayoffHints(payoffHint == null ? null : List.of(payoffHint));
        return ChapterSummaryEntity.builder()
                .chapterNo(chapterNo)
                .foreshadowingNew(newForeshadow == null ? null : List.of(newForeshadow))
                .foreshadowSeeds(seed == null ? null : List.of(seed))
                .build();
    }

    @Test
    void plantedForeshadowWithHints_inGuard() {
        ChapterSummaryEntity s = summary(3, "主角乙发现封存处出现异动", "封印之下是上古剑灵");

        ChapterMemoryService.SecrecyGuard guard = service.buildSecrecyGuard(List.of(s), new ChapterPlanItemEntity());

        assertFalse(guard.isEmpty());
        assertTrue(guard.keywords().contains("封印之下是上古剑灵"));
        assertTrue(guard.promptBlock().contains("【本章禁泄清单】"));
        assertTrue(guard.promptBlock().contains("主角乙发现封存处出现异动"));
    }

    @Test
    void revealChapter_exempted() {
        ChapterSummaryEntity s = summary(3, "主角乙发现封存处出现异动", "封印之下是上古剑灵");
        ChapterPlanItemEntity revealPlan = ChapterPlanItemEntity.builder()
                .keyEvents(List.of("揭示封存处出现异动的真相（回收第3章埋设的封存处出现异动）"))
                .build();

        assertTrue(service.buildSecrecyGuard(List.of(s), revealPlan).isEmpty(), "揭示章必须豁免");
    }

    @Test
    void advancingWithoutReveal_notExempted() {
        // 计划只推进伏笔（调查）而未到揭示，禁泄清单仍生效
        ChapterSummaryEntity s = summary(3, "主角乙发现封存处出现异动", "封印之下是上古剑灵");
        ChapterPlanItemEntity advancePlan = ChapterPlanItemEntity.builder()
                .keyEvents(List.of("主角乙继续调查封存处出现异动的缘由"))
                .build();

        assertFalse(service.buildSecrecyGuard(List.of(s), advancePlan).isEmpty());
    }

    @Test
    void resolvedForeshadow_outOfGuard() {
        ChapterSummaryEntity planted = summary(3, "主角乙发现封存处出现异动", "封印之下是上古剑灵");
        ChapterSummaryEntity resolved = ChapterSummaryEntity.builder()
                .chapterNo(5)
                .foreshadowingResolved(List.of("主角乙发现封存处出现异动"))
                .build();

        assertTrue(service.buildSecrecyGuard(List.of(planted, resolved), new ChapterPlanItemEntity()).isEmpty(),
                "已回收伏笔不再是秘密");
    }

    @Test
    void legacySeedWithoutHints_skipped() {
        // 存量数据（无 payoffHints）不误伤：不进清单，仅靠审校兜底
        ChapterSummaryEntity legacy = ChapterSummaryEntity.builder()
                .chapterNo(3)
                .foreshadowingNew(List.of("主角乙发现封存处出现异动"))
                .build();

        assertTrue(service.buildSecrecyGuard(List.of(legacy), new ChapterPlanItemEntity()).isEmpty());
    }

    @Test
    void emptySummaries_safe() {
        assertTrue(service.buildSecrecyGuard(null, null).isEmpty());
        assertTrue(service.buildSecrecyGuard(List.of(), null).isEmpty());
    }
}
