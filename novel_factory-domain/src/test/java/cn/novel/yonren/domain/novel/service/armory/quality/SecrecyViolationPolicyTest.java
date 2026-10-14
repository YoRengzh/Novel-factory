package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伏笔保密边界机械门禁测试：谜底关键词逐字命中即 BLOCKING（foreshadow 维度），
 * 多次命中合并计数；未命中/空输入零 issue
 */
class SecrecyViolationPolicyTest {

    @Test
    void keywordHit_producesBlockingForeshadowIssue() {
        String content = "他握紧手中的旧玉佩，忽然明白了：那枚玉佩实为关键凭证。";
        List<ChapterIssueEntity> issues = SecrecyViolationPolicy.check(content, List.of("那枚玉佩实为关键凭证"));

        assertEquals(1, issues.size());
        assertEquals("foreshadow", issues.get(0).getDimension());
        assertEquals("BLOCKING", issues.get(0).getSeverity());
        assertTrue(issues.get(0).getEvidence().contains("那枚玉佩实为关键凭证×1"));
    }

    @Test
    void multipleHits_countedPerKeyword() {
        String content = "事故背后另有主使。他反复确认，事故背后另有主使这件事不能再让第三人知道。";
        List<ChapterIssueEntity> issues = SecrecyViolationPolicy.check(content, List.of("事故背后另有主使"));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).getEvidence().contains("事故背后另有主使×2"));
    }

    @Test
    void noHit_returnsEmpty() {
        String content = "他推开门，看见桌上的信。信封没有落款，只有一枚烧焦的蜡印。";
        assertTrue(SecrecyViolationPolicy.check(content, List.of("那枚玉佩实为关键凭证")).isEmpty());
    }

    @Test
    void blankInputs_safe() {
        assertTrue(SecrecyViolationPolicy.check(null, List.of("x")).isEmpty());
        assertTrue(SecrecyViolationPolicy.check("正文", null).isEmpty());
        assertTrue(SecrecyViolationPolicy.check("正文", List.of()).isEmpty());
    }
}
