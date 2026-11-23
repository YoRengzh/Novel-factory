package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChapterWritingPlanServiceTest {
    @Test
    void retriesWithoutFallingBackToTheFullPlan() {
        var calls = new AtomicInteger();
        var item = ChapterPlanItemEntity.builder().keyEvents(List.of("借钥匙", "看见账本")).build();
        var service = new ChapterWritingPlanService((module, call) -> switch (calls.incrementAndGet()) {
            case 1 -> """
                    {"sections":[{"viewpoint":"周芸","entry":"本卷答案是红匣密约",
                    "scenes":[{"number":1,"events":"借钥匙"},{"number":2,"events":"看见账本"}]}]}
                    """;
            default -> """
                    {"sections":[{"viewpoint":"周芸","entry":"来借钥匙",
                    "scenes":[{"number":1,"events":"借钥匙"},{"number":2,"events":"看见账本"}]}]}
                    """;
        });

        var plan = service.prepare(new StoryVO(), item, 2, "本卷答案是红匣密约", List.of("红匣密约"));

        assertEquals(2, calls.get());
        assertFalse(plan.sections().get(0).render().contains("红匣密约"));
        assertEquals(List.of(1, 2), plan.sections().get(0).scenes().stream().map(ChapterWritingPlan.Scene::number).toList());
    }

    @Test
    void omittedScenePreventsDraftingFromIncompleteMaterial() {
        var item = ChapterPlanItemEntity.builder().keyEvents(List.of("借钥匙", "看见账本")).build();
        var service = new ChapterWritingPlanService((module, call) -> """
                {"sections":[{"viewpoint":"周芸","scenes":[{"number":1,"events":"借钥匙"}]}]}
                """);

        assertThrows(AppException.class, () -> service.prepare(new StoryVO(), item, 2, "完整计划", List.of()));
    }
}
