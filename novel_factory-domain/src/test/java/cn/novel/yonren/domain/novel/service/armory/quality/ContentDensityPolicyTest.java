package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentDensityPolicyTest {
    @Test
    void quotationCountIsObservationNotARequirementToAddDialogue() {
        assertEquals(3, ContentDensityPolicy.countDialogue("“一句话。”「另一句。」\"最后一句。\""));
        String hint = ContentDensityPolicy.renderLowDensityHint(0);
        if (hint != null) {
            assertFalse(hint.contains("最低要求"));
            assertFalse(hint.contains("缺口"));
            assertFalse(hint.contains("增加角色间对话"));
            assertFalse(hint.contains("25 句"));
        }
    }

    @Test
    void narrativeSimileMarkersAreObservedWithoutBecomingStyleIssues() {
        String content = "风贴着窗纸响，像被人从窗外轻叩。"
                + "“屋里冷得像冰窖似的。”「你听起来像被人吓了一跳。」"
                + "\"别针似的？\"『细得像一根针。』灯火小得像一粒栗子。";
        assertEquals(2, ContentDensityPolicy.countNarrativeSimileMarkers(content));
        assertEquals(0, ContentDensityPolicy.countNarrativeSimileMarkers(null));
        assertTrue(StyleViolationPolicy.check(content).isEmpty());
    }
}
