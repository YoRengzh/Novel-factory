package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 索引加载测试：classpath 单层扫描、头部解析、缓存与 genres/styles 排除
 */
class ReferenceIndexLoaderTest {

    private final ReferenceIndexLoader loader = new ReferenceIndexLoader();

    @Test
    void index_containsReferencesRootFiles() {
        List<String> fileNames = loader.getIndex().stream()
                .map(ReferenceIndexEntry::getFileName)
                .collect(Collectors.toList());
        // references 根下的写作资料入索引；ainovel-cli/Go 工程设计文档不属于 Java 运行时资料
        // 54 → 58：吸纳 chinese-webnovel-skills 四份合卷（人味节奏/情绪台词/开篇金手指/语域纪律）
        assertEquals(58, fileNames.size());
        assertTrue(fileNames.containsAll(List.of(
                "chapter-guide", "chapter-template", "character-building",
                "character-template", "consistency", "content-expansion", "dialogue-writing",
                "differentiation", "longform-planning", "outline-template", "quality-checklist",
                "action-scenes", "emotion-curve", "foreshadowing", "genre-flow-tactics",
                "multi-thread", "opening-design",
                "pacing-control", "restrained-emotion", "scene-writing", "subject-and-ops",
                "transitions", "trope-usage",
                "transmigration-transition", "de-ai-principles", "corpus-distillation",
                "style-distillation", "cross-chapter-tables",
                "villain-design", "worldbuilding",
                "seed-architecture", "seed-atoms-fantasy", "seed-atoms-romance",
                "exemplar-battle", "exemplar-dialogue", "exemplar-breakthrough", "exemplar-emotion",
                "human-texture-rhythm", "emotion-display-dialogue", "opening-hooks-goldfinger",
                "register-discipline",
                "plot-structure-three-act", "plot-structure-heros-journey", "plot-structure-mystery",
                "plot-structure-romance", "plot-structure-thriller", "plot-structure-twist",
                "plot-structure-multiline", "plot-structure-short", "plot-structure-five-act",
                "plot-structure-kishotenketsu", "plot-structure-circular", "plot-structure-network",
                "plot-structure-fragmented", "plot-structure-frame", "plot-structure-seven-point",
                "plot-structure-save-the-cat", "plot-structure-story-circle")));
        assertTrue(fileNames.stream().noneMatch(n -> n.startsWith("design-")));
    }

    @Test
    void index_excludesGenresAndStyles() {
        List<String> fileNames = loader.getIndex().stream()
                .map(ReferenceIndexEntry::getFileName)
                .collect(Collectors.toList());
        // 单层通配不扫入 genres 子目录、styles 目录与 rules 目录（确定性规则不走动态选择，防双路重复注入）
        assertTrue(fileNames.stream().noneMatch(n ->
                n.equals("arc-templates") || n.equals("style-references") || n.equals("default")
                        || n.equals("anti-ai-tone") || n.equals("plot-structures") || n.equals("hook-techniques")));
    }

    @Test
    void index_entryFieldsParsed() {
        ReferenceIndexEntry entry = byName("dialogue-writing");
        assertEquals("references/dialogue-writing.md", entry.getRelativePath());
        assertNotNull(entry.getTitle());
        assertTrue(entry.getDigest().contains("对话"));
    }

    @Test
    void index_templateFileNowHasDigest() {
        // 修正：纯模板文件此前没有首段简介，选择器只能看到标题——
        // 而 chapter-template 的标题甚至是占位符「第[X]章：[章节标题]」。现已补齐
        ReferenceIndexEntry entry = byName("chapter-template");
        assertNotNull(entry);
        assertEquals("章节正文与备注模板", entry.getTitle());
        assertNotNull(entry.getDigest());
        assertTrue(entry.getDigest().contains("结构参考"));
    }

    @Test
    void index_everyEntryHasUsableTitleAndDigest() {
        // 回归守卫：动态索引是 LLM 选资料的唯一依据。标题是占位符、或摘要为空/以代码块开头，
        // 都会让选择器拿到无意义信号。此前对"哪些文件能被选准"没有任何断言，问题长期未被发现
        List<ReferenceIndexEntry> entries = loader.getIndex();
        assertTrue(entries.size() > 40);
        for (ReferenceIndexEntry entry : entries) {
            assertTrue(entry.getTitle() != null && !entry.getTitle().isBlank()
                            && !entry.getTitle().contains("[X]"),
                    entry.getFileName() + " 的标题是占位符或为空：" + entry.getTitle());
            assertTrue(entry.getDigest() != null && !entry.getDigest().isBlank(),
                    entry.getFileName() + " 缺少首段简介（一级标题后直接是子标题），选择器拿不到用途信号");
            assertFalse(entry.getDigest().startsWith("```"),
                    entry.getFileName() + " 的摘要以代码块开头，不是用途说明：" + entry.getDigest());
        }
    }

    @Test
    void index_sortedByFileName() {
        List<String> fileNames = loader.getIndex().stream()
                .map(ReferenceIndexEntry::getFileName)
                .collect(Collectors.toList());
        assertEquals(fileNames.stream().sorted().collect(Collectors.toList()), fileNames);
    }

    @Test
    void index_cachedAcrossCalls() {
        assertSame(loader.getIndex(), loader.getIndex());
    }

    private ReferenceIndexEntry byName(String fileName) {
        return loader.getIndex().stream()
                .filter(e -> e.getFileName().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("索引中不存在: " + fileName));
    }

}
