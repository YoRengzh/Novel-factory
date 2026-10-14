package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大纲偏离检测测试：覆盖口径与修订闸门一致（LCS ≥ 4 字）；
 * 括号注释剥离、低覆盖预警渲染、空事件全覆盖
 */
class PlanAdherencePolicyTest {

    @Test
    void exactContainment_covered() {
        assertTrue(PlanAdherencePolicy.covered("小陈在藏经阁偶得上古残卷，指尖抚过封皮。",
                "小陈在藏经阁偶得上古残卷"));
    }

    @Test
    void paraphraseWithCorePhrase_covered_lcsAtLeast4() {
        // 正文转述但保留核心名词"上古残卷"（4 字公共子串）→ 覆盖
        assertTrue(PlanAdherencePolicy.covered("他从藏经阁的暗格里翻出了一卷上古残卷。",
                "小陈在藏经阁偶得上古残卷"));
    }

    @Test
    void totallyUncovered_notCovered() {
        assertTrue(PlanAdherencePolicy.coverage(
                List.of("小陈在藏经阁偶得上古残卷", "陆瑶察觉长老的异动"),
                "他推开门，看见桌上的信。") < PlanAdherencePolicy.LOW_COVERAGE_THRESHOLD);
    }

    @Test
    void parentheticalHint_stripped_notRequiredInContent() {
        // "回收第N章埋设的XX"是执行提示，只要求 XX 的核心要素落入正文
        assertTrue(PlanAdherencePolicy.covered("封存处出现异动的那一刻，他终于明白了一切。",
                "揭示封存处出现异动的真相（回收第3章埋设的封存处出现异动）"));
    }

    @Test
    void emptyEvents_fullCoverage() {
        assertEquals(1.0, PlanAdherencePolicy.coverage(null, "任意正文"));
        assertEquals(1.0, PlanAdherencePolicy.coverage(List.of(), "任意正文"));
    }

    @Test
    void lowCoverage_rendersHint_highCoverage_returnsNull() {
        List<String> events = List.of("小陈在藏经阁偶得上古残卷", "陆瑶察觉宗门长老的异动");
        String offTopic = "他推开门，看见桌上的信。信封没有落款，只有一枚烧焦的蜡印。";

        String hint = PlanAdherencePolicy.renderLowCoverageHint(events, offTopic);
        assertTrue(hint != null && hint.contains("【计划覆盖预警】"));
        assertTrue(hint.contains("小陈在藏经阁偶得上古残卷"));

        // 覆盖 1/2 = 0.5 < 0.6 → 仍预警
        String halfCovered = "陆瑶察觉宗门长老的异动，却没有在意。";
        assertTrue(PlanAdherencePolicy.renderLowCoverageHint(events, halfCovered) != null);

        // 覆盖 2/3 ≈ 0.67 ≥ 0.6 → 不预警
        List<String> three = List.of("小陈在藏经阁偶得上古残卷", "陆瑶察觉宗门长老的异动", "魔物夜袭山门大阵");
        String twoCovered = "小陈在藏经阁偶得上古残卷。当夜，陆瑶察觉宗门长老的异动。";
        assertNull(PlanAdherencePolicy.renderLowCoverageHint(three, twoCovered));

        assertNull(PlanAdherencePolicy.renderLowCoverageHint(List.of("上古残卷重见天日"),
                "他得到了上古残卷重见天日的消息。"));
        assertNull(PlanAdherencePolicy.renderLowCoverageHint(null, "任意正文"));
    }
}
