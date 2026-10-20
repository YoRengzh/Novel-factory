package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.domain.novel.model.valobj.properties.QualityReviewProperties;
import cn.novel.yonren.domain.novel.model.valobj.properties.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryMemoryProperties;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 功能开关覆盖测试：安装包用户改不了 jar 内 yml，因此这些开关必须能经设置页运行时切换。
 * 核心不变量：覆盖优先、未覆盖回退静态快照、清除覆盖能回到原值。
 */
class FeatureTogglesTest {

    private StoryProperties storyProperties() {
        StoryProperties p = new StoryProperties();
        p.getAudit().setEnabled(true);
        p.getParagraphAudit().setEnabled(true);
        p.getRevise().setEnabled(true);
        p.getCandidate().setEnabled(true);
        p.getLedgerAdjudicate().setEnabled(true);
        return p;
    }

    private FeatureToggles toggles(StoryProperties story) {
        ReferenceSelectorProperties ref = new ReferenceSelectorProperties();
        ref.setEnabled(true);
        StoryMemoryProperties memory = new StoryMemoryProperties();
        memory.setEnabled(true);
        QualityReviewProperties quality = new QualityReviewProperties();
        quality.setEnabled(true);
        FeatureToggles t = new FeatureToggles(story, ref, memory, quality);
        t.snapshot();
        return t;
    }

    @Test
    void apply_overridesWrittenToConfigBeans() {
        StoryProperties story = storyProperties();
        FeatureToggles toggles = toggles(story);

        Map<String, Boolean> overrides = new LinkedHashMap<>();
        overrides.put(FeatureToggles.AUDIT, false);
        overrides.put(FeatureToggles.CANDIDATE, false);
        toggles.apply(overrides);

        // 服务层直接读 bean：覆盖必须真的写回去，否则开关是假的
        assertFalse(story.getAudit().isEnabled(), "审校开关必须落到 bean 上");
        assertFalse(story.getCandidate().isEnabled(), "候选选优开关必须落到 bean 上");
        assertTrue(story.getRevise().isEnabled(), "未覆盖的开关保持原值");
    }

    @Test
    void apply_emptyOverridesRestoresStaticSnapshot() {
        StoryProperties story = storyProperties();
        FeatureToggles toggles = toggles(story);

        toggles.apply(Map.of(FeatureToggles.AUDIT, false));
        assertFalse(story.getAudit().isEnabled());

        // 空表 = 清除全部覆盖 → 回到启动快照
        toggles.apply(Map.of());
        assertTrue(story.getAudit().isEnabled(), "清除覆盖必须回退到静态值");
        assertFalse(toggles.isOverridden(FeatureToggles.AUDIT));
    }

    @Test
    void apply_unknownKeyDropped() {
        StoryProperties story = storyProperties();
        FeatureToggles toggles = toggles(story);

        toggles.apply(Map.of("not-a-real-toggle", false));

        assertFalse(toggles.isOverridden("not-a-real-toggle"));
        assertTrue(story.getAudit().isEnabled(), "未知 key 不得误伤任何开关");
    }

    @Test
    void view_exposesStaticOverrideAndEffectiveValues() {
        StoryProperties story = storyProperties();
        FeatureToggles toggles = toggles(story);
        toggles.apply(Map.of(FeatureToggles.QUALITY_REVIEW, false));

        List<LlmConfigDTO.ToggleView> rows = toggles.view();
        assertEquals(FeatureToggles.Def.values().length, rows.size());
        LlmConfigDTO.ToggleView quality = rows.stream()
                .filter(r -> FeatureToggles.QUALITY_REVIEW.equals(r.getKey()))
                .findFirst().orElseThrow();
        assertTrue(quality.isStaticValue(), "静态值来自 yml");
        assertEquals(Boolean.FALSE, quality.getOverrideValue());
        assertFalse(quality.isEffectiveValue());

        LlmConfigDTO.ToggleView audit = rows.stream()
                .filter(r -> FeatureToggles.AUDIT.equals(r.getKey()))
                .findFirst().orElseThrow();
        assertTrue(audit.isEffectiveValue());
        assertNotNull(audit.getCostHint(), "每行必须给出成本提示，用户才知道关掉能省什么");
    }

    @Test
    void costHintsCoverDurationHeavyScenes() {
        // 候选选优是单章 20+ 分钟的主因，提示里必须点明耗时量级
        String candidateHint = FeatureToggles.Def.CANDIDATE.getCostHint();
        assertTrue(candidateHint.contains("20"), "候选选优的提示必须写明耗时量级：" + candidateHint);
    }
}
