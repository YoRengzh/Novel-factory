package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;

class ReferenceBlockSourceLifecycleTest {

    @TempDir
    Path temp;
    private ReferenceIndexLoader loader;
    private PromptRuleFileLoader files;
    private ReferenceSourcePolicy policy;
    private ReferenceTestFixtures.Embedding embedding;
    private ReferenceTestFixtures.Store store;
    private ReferenceAtomBuilder builder;
    private ReferenceBlockIndexer indexer;

    @BeforeEach
    void setUp() {
        loader = mock(ReferenceIndexLoader.class);
        files = mock(PromptRuleFileLoader.class);
        policy = new ReferenceSourcePolicy(temp.resolve("state.json"));
        embedding = new ReferenceTestFixtures.Embedding();
        store = new ReferenceTestFixtures.Store();
        builder = new ReferenceAtomBuilder("{\"version\":\"test\",\"docs\":{}}");
        when(loader.sourcePolicy()).thenReturn(policy);
        when(loader.corpusVersion()).thenAnswer(inv -> "full-corpus-r" + policy.revision());
        indexer = newIndexer(builder);
    }

    @Test
    void disabledSourcesAndTheirTransitiveDependentAtomsAreExcludedButIndependentAtomsRemain() {
        ReferenceAtom base = atom("base-source", "base", List.of());
        ReferenceAtom middle = atom("middle-source", "middle", List.of(base.atomId()));
        ReferenceAtom last = atom("last-source", "last", List.of(middle.atomId()));
        ReferenceAtom independent = atom("last-source", "independent", List.of());
        var dependencyBuilder = dependencyBuilder(base, middle, last, independent);
        indexer = newIndexer(dependencyBuilder);
        var session = embedding.session(module());
        var enabled = indexer.loadSnapshot(session);
        assertEquals(4, enabled.atoms().size());
        policy.setEnabled("base-source", false);

        var disabled = indexer.loadSnapshot(session);
        assertEquals(List.of(independent.atomId()), disabled.atoms().stream().map(ReferenceAtom::atomId).toList());
        assertEquals(Set.of(independent.atomId()), disabled.byId().keySet());
        assertNotEquals(enabled.version(), disabled.version());
        assertTrue(dependencyBuilder.validateClosure(disabled.byId(), Set.of("middle-source", "last-source")).isEmpty());
        assertEquals(0, embedding.calls);
    }

    @Test
    void deletedDisabledDependencyIsStillExcludedAfterPolicyRestart() {
        ReferenceAtom base = atom("base-source", "base", List.of());
        ReferenceAtom middle = atom("middle-source", "middle", List.of(base.atomId()));
        ReferenceAtom last = atom("last-source", "last", List.of(middle.atomId()));
        var dependencyBuilder = dependencyBuilder(base, middle, last);
        policy.setEnabled("base-source", false);
        when(loader.sourcePolicy()).thenReturn(new ReferenceSourcePolicy(temp.resolve("state.json")));
        when(loader.getAllIndex()).thenReturn(List.of(entry("middle-source"), entry("last-source")));
        indexer = newIndexer(dependencyBuilder);

        var snapshot = indexer.loadSnapshot(embedding.session(module()));
        assertTrue(snapshot.atoms().isEmpty(), "已删除停用来源的 tombstone 排除全部传递依赖");
        verify(dependencyBuilder, never()).build(eq("base-source"), anyString(), anyString(), anyString(), any());
        assertEquals(0, indexer.preview(embedding.session(module())).atoms());
    }

    @Test
    void genuineUnknownDependencyStillThrowsEvenWhenAnotherDependencySourceIsDisabled() {
        ReferenceAtom base = atom("base-source", "base", List.of());
        ReferenceAtom middle = atom("middle-source", "middle", List.of(base.atomId(), "genuinely-missing#atom"));
        ReferenceAtom last = atom("last-source", "last", List.of(middle.atomId()));
        var dependencyBuilder = dependencyBuilder(base, middle, last);
        indexer = newIndexer(dependencyBuilder);
        policy.setEnabled("base-source", false);

        var error = assertThrows(IllegalStateException.class, () -> indexer.loadSnapshot(embedding.session(module())));
        assertTrue(error.getMessage().contains("依赖不存在的 genuinely-missing#atom"));
        assertEquals(0, embedding.calls);
        assertEquals(0, store.writes);
    }

    @Test
    void allDisabledCorpusHasZeroPreviewAndSnapshotAndNeedsNoEmbedding() {
        when(loader.getAllIndex()).thenReturn(List.of(entry("test-source")));
        when(files.load("references/test-source.md")).thenReturn("# 标题\n\n简介\n\n## 节\n正文");
        policy.setEnabled("test-source", false);
        embedding.dimensions = null;
        var session = embedding.session(module());
        var preview = indexer.preview(session);
        assertEquals(0, preview.documents());
        assertEquals(0, preview.atoms());
        assertEquals(0, preview.embeddingChars());
        assertEquals(0, preview.embeddingBatches());
        assertTrue(indexer.loadSnapshot(session).atoms().isEmpty());
        assertTrue(indexer.ensureIndex(session).atoms().isEmpty());
        assertEquals(0, embedding.calls);
        assertEquals(0, store.writes);
        assertTrue(store.collections.isEmpty());
    }

    @Test
    void bodyChangeAndPolicyRevisionBothChangeVersionEvenWithSameTitles() {
        when(loader.getAllIndex()).thenReturn(List.of(entry("test-source")));
        when(files.load("references/test-source.md")).thenReturn("# 相同标题\n\n相同简介\n\n## 节\n旧正文");
        var session = embedding.session(module());
        String first = indexer.loadSnapshot(session).version();
        when(files.load("references/test-source.md")).thenReturn("# 相同标题\n\n相同简介\n\n## 节\n新正文");
        String second = indexer.loadSnapshot(session).version();
        assertNotEquals(first, second);
        policy.setEnabled("test-source", false);
        String disabled = indexer.loadSnapshot(session).version();
        assertNotEquals(second, disabled);
        policy.setEnabled("test-source", true);
        assertNotEquals(second, indexer.loadSnapshot(session).version(), "恢复同份正文仍使用新 revision");
    }

    @Test
    void correctImmutableVersionCanBeReusedAfterRestartAndInvalidate() {
        when(loader.getAllIndex()).thenReturn(List.of(entry("test-source")));
        when(files.load("references/test-source.md")).thenReturn("# 标题\n\n简介\n\n## 节\n正文");
        var session = embedding.session(module());
        var first = indexer.ensureIndex(session);
        int calls = embedding.calls;
        assertEquals("novel-reference-blocks-" + first.version() + "-d3", first.collection());
        assertEquals(first.collection(), ReferenceBlockIndexer.collectionName(first, 3));
        indexer.invalidate();
        assertEquals(first.collection(), indexer.ensureIndex(session).collection());
        assertEquals(first.collection(), newIndexer(builder).ensureIndex(session).collection());
        assertEquals(calls, embedding.calls);
        assertEquals(1, store.writes);
    }

    @Test
    void frozenSessionWithoutDeclaredDimensionsInfersActualDimensionsAcrossBatches() {
        when(loader.getAllIndex()).thenReturn(List.of(entry("test-source")));
        when(files.load("references/test-source.md")).thenReturn("# 标题\n\n简介\n\n## 节\n正文");
        embedding.dimensions = null;
        var session = embedding.session(module());
        embedding.signature = "runtime-changed";
        embedding.actualDimensions = 7;
        var indexed = indexer.ensureIndex(session);
        assertEquals(3, indexed.dimensions());
        assertEquals("fake-space", indexed.signature());
        assertTrue(indexed.collection().endsWith("-d3"));
        assertEquals(1, embedding.sessionCalls);
    }

    private ReferenceAtomBuilder dependencyBuilder(ReferenceAtom base, ReferenceAtom middle, ReferenceAtom... last) {
        var custom = mock(ReferenceAtomBuilder.class);
        when(custom.policyVersion()).thenReturn("dependencies-v1");
        when(custom.validateClosure(anyMap(), anySet())).thenCallRealMethod();
        when(loader.getAllIndex()).thenReturn(List.of(entry("base-source"), entry("middle-source"), entry("last-source")));
        when(files.load("references/base-source.md")).thenReturn("base body");
        when(files.load("references/middle-source.md")).thenReturn("middle body");
        when(files.load("references/last-source.md")).thenReturn("last body");
        when(custom.build(eq("base-source"), anyString(), anyString(), anyString(), any())).thenReturn(List.of(base));
        when(custom.build(eq("middle-source"), anyString(), anyString(), anyString(), any())).thenReturn(List.of(middle));
        when(custom.build(eq("last-source"), anyString(), anyString(), anyString(), any())).thenReturn(List.of(last));
        return custom;
    }

    private static ReferenceAtom atom(String doc, String id, List<String> requires) {
        String content = "正文 " + id;
        return new ReferenceAtom(doc, doc + "#" + id, doc, id, 0, content, ReferenceAtomBuilder.hash(content),
                "technique", requires, true);
    }

    private ReferenceBlockIndexer newIndexer(ReferenceAtomBuilder atomBuilder) {
        return new ReferenceBlockIndexer(loader, files, atomBuilder, new ReferenceChunker(), embedding, store);
    }

    private static ReferenceIndexEntry entry(String file) {
        return new ReferenceIndexEntry(file, file, "", "references/" + file + ".md");
    }

    private static StoryVO.Module module() {
        return new StoryVO.Module();
    }
}
