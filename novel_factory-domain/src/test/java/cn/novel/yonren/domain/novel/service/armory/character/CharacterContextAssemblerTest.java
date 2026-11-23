package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterDecisionProfile;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterContextAssemblerTest {
    @Test
    void writerViewKeepsDesignFieldsWithoutIdentityAndVersionMetadata() {
        var profiles = CharacterDecisionProfileService.adoptAuthor(null, List.of(
                new CharacterDecisionProfile("沈砚", false, "查明盐账", "获得信任", "害怕牵连无辜",
                        "先救人再查账", "不拿无辜者作交换", "管事的异常举动", "有证据才行动")), 1);
        var story = StoryContextEntity.builder()
                .characterDepthSettings(CharacterDepthSettings.defaults("APPLY"))
                .characterDecisionProfiles(profiles).build();

        String planning = new CharacterContextAssembler().assemble(story, List.of("沈砚"), 3).text();
        String writing = new CharacterContextAssembler().assembleForWriter(story, List.of("沈砚"), 3).text();

        assertTrue(writing.contains("不拿无辜者作交换"));
        assertTrue(writing.contains("有证据才行动"));
        assertFalse(writing.contains("character-"));
        assertFalse(writing.contains("hash"));
        assertFalse(writing.contains("档案版本"));
        assertFalse(writing.contains("scenes.causality"));
        assertTrue(planning.contains("档案版本"), "原规划视图必须保持兼容");
        assertTrue(planning.contains("scenes.causality"));
    }

    @Test
    void compactWriterViewDropsAcademicFieldsAndKeepsDramaticHooks() {
        var profiles = CharacterDecisionProfileService.adoptAuthor(null, List.of(
                new CharacterDecisionProfile("苏尘", false, "保住大考资格", "在稳妥与冒险间建立稳定判断",
                        "怕被逐出药园", "长生大道＞名声排面", "不拿同门作弃子", "下品杂灵根进度慢",
                        "先侦察再评估收益成本")), 1);
        var story = StoryContextEntity.builder()
                .characterDepthSettings(CharacterDepthSettings.defaults("APPLY"))
                .characterDecisionProfiles(profiles).build();

        String compact = new CharacterContextAssembler().assembleCompactForWriter(story, List.of("苏尘"), 1).text();

        assertTrue(compact.contains("【出场人物底色】"));
        assertTrue(compact.contains("诉求：保住大考资格"));
        assertTrue(compact.contains("忌惮与软肋：怕被逐出药园"));
        assertTrue(compact.contains("底线：不拿同门作弃子"));
        assertTrue(compact.contains("眼下牵挂：下品杂灵根进度慢"));
        assertFalse(compact.contains("在稳妥与冒险间建立稳定判断"));
        assertFalse(compact.contains("长生大道＞名声排面"));
        assertFalse(compact.contains("先侦察再评估收益成本"));
        assertFalse(compact.contains("character-"));
        assertFalse(compact.contains("hash"));
    }
}
