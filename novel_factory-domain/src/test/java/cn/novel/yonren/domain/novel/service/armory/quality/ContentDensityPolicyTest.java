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
}
