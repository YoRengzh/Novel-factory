package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.types.enums.PromptScene;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资料分组测试：分组决定「哪些场景的索引里出现这份资料」。
 *
 * <p>动机：58 份资料平铺时 ref-select 要在长表里挑 3 份，选准率随条目数下降；
 * 按场景先筛到十几二十行能显著收窄选择范围。这里钉住三条不变量：
 * ① 每份资料必有所属组（不能漏检）；② 每个场景至少能看见一组；
 * ③ 跨场景资料（技法类）不会因为分组而对正文场景消失。
 */
class ReferenceGroupTest {

    @Test
    void groupOf_classifiesByPrefixAndExplicitList() {
        // 情节结构：全部 plot-structure-* 前缀归结构组
        assertEquals(ReferenceGroup.STRUCTURE, ReferenceGroup.groupOf("plot-structure-three-act"));
        assertEquals(ReferenceGroup.STRUCTURE, ReferenceGroup.groupOf("outline-template"));
        assertEquals(ReferenceGroup.STRUCTURE, ReferenceGroup.groupOf("pacing-control"));

        // 题材/设定：seed-* 前缀 + 世界观人物类
        assertEquals(ReferenceGroup.GENRE, ReferenceGroup.groupOf("seed-atoms-fantasy"));
        assertEquals(ReferenceGroup.GENRE, ReferenceGroup.groupOf("worldbuilding"));
        assertEquals(ReferenceGroup.GENRE, ReferenceGroup.groupOf("villain-design"));

        // 技法类与未知文件：归技法组（保守归属，保证能被搜到）
        assertEquals(ReferenceGroup.TECHNIQUE, ReferenceGroup.groupOf("de-ai-principles"));
        assertEquals(ReferenceGroup.TECHNIQUE, ReferenceGroup.groupOf("human-texture-rhythm"));
        assertEquals(ReferenceGroup.TECHNIQUE, ReferenceGroup.groupOf("从未见过的文件名"));
        assertEquals(ReferenceGroup.TECHNIQUE, ReferenceGroup.groupOf(null));
    }

    @Test
    void groupsFor_每个场景至少能看见一组资料() {
        for (PromptScene scene : PromptScene.values()) {
            Set<String> groups = ReferenceGroup.groupsFor(scene);
            assertFalse(groups.isEmpty(), scene + " 分组为空，该场景将永远拿不到参考资料");
        }
    }

    @Test
    void groupsFor_正文场景能看见技法组() {
        // 这是最关键的不变量：写正文时必须能选到 AI 味/情绪/句式类资料
        assertTrue(ReferenceGroup.groupsFor(PromptScene.CHAPTER_CONTENT).contains(ReferenceGroup.TECHNIQUE));
        assertTrue(ReferenceGroup.groupsFor(PromptScene.CHAPTER_REVISE).contains(ReferenceGroup.TECHNIQUE));
        assertTrue(ReferenceGroup.groupsFor(PromptScene.CHAPTER_AUDIT).contains(ReferenceGroup.TECHNIQUE));
    }

    @Test
    void groupsFor_计划场景能看见结构组() {
        assertTrue(ReferenceGroup.groupsFor(PromptScene.CHAPTER_PLAN).contains(ReferenceGroup.STRUCTURE));
        // 题材打法对规划有用（哪类题材走哪套节拍），故计划场景同时可见题材组
        assertTrue(ReferenceGroup.groupsFor(PromptScene.CHAPTER_PLAN).contains(ReferenceGroup.GENRE));
    }

    @Test
    void groupsFor_设定场景能看见题材组() {
        assertTrue(ReferenceGroup.groupsFor(PromptScene.SETTING_DRAFT).contains(ReferenceGroup.GENRE));
        assertTrue(ReferenceGroup.groupsFor(PromptScene.STAGE_BLUEPRINT).contains(ReferenceGroup.GENRE));
    }

    @Test
    void indexEntry_旧构造器默认归技法组() {
        // 兼容性：4 参构造器（旧代码/旧测试）必须仍可用，且不会因 group=null 被过滤掉
        ReferenceIndexEntry entry = new ReferenceIndexEntry("x", "标题", "摘要", "references/x.md");
        assertEquals(ReferenceGroup.TECHNIQUE, entry.getGroup());
    }

    @Test
    void 实际资料库的每份文件都能归类且分组非空() {
        // 用真实索引验证：不能有文件因分组配置疏漏而落进"未知"导致检索不到
        ReferenceIndexLoader loader = new ReferenceIndexLoader();
        List<ReferenceIndexEntry> index = loader.getIndex();
        assertFalse(index.isEmpty());
        for (ReferenceIndexEntry entry : index) {
            assertTrue(entry.getGroup() != null && !entry.getGroup().isBlank(),
                    entry.getFileName() + " 没有分组");
            assertTrue(List.of(ReferenceGroup.TECHNIQUE, ReferenceGroup.STRUCTURE, ReferenceGroup.GENRE)
                            .contains(entry.getGroup()),
                    entry.getFileName() + " 分组值非法：" + entry.getGroup());
        }
        // 打印分布，便于人工核对归类是否合理
        long technique = index.stream().filter(e -> ReferenceGroup.TECHNIQUE.equals(e.getGroup())).count();
        long structure = index.stream().filter(e -> ReferenceGroup.STRUCTURE.equals(e.getGroup())).count();
        long genre = index.stream().filter(e -> ReferenceGroup.GENRE.equals(e.getGroup())).count();
        assertEquals(index.size(), technique + structure + genre);
        assertTrue(structure >= 15, "结构组应包含全部 plot-structure-*，实际 " + structure);
        assertTrue(genre >= 10, "题材组应包含 seed-* 与世界/人物类，实际 " + genre);
        assertTrue(technique >= 15, "技法组应包含 AI 味/情绪/句式类，实际 " + technique);
    }
}
