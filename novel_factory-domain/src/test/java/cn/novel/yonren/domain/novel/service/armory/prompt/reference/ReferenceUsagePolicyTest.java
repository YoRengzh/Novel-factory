package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 本地资格测试：不调用 LLM、向量库、索引预热或生产服务。 */
class ReferenceUsagePolicyTest {

    private final ReferenceUsagePolicy policy = new ReferenceUsagePolicy();
    private final PromptContext fantasy = PromptContext.builder().theme("玄幻修仙").build();

    @Test
    void unknownAssetsAndNullSceneAreDenied() {
        assertFalse(policy.allowsDocument(null, PromptScene.SETTING_DRAFT, fantasy));
        assertFalse(policy.allowsDocument("unregistered-new-doc", PromptScene.CHAPTER_CONTENT, fantasy));
        assertFalse(policy.allowsDocument("worldbuilding", null, fantasy));
        assertFalse(policy.allowsSection(null, "导言", PromptScene.SETTING_DRAFT, fantasy));
        assertFalse(policy.allowsSection("worldbuilding", "", PromptScene.SETTING_DRAFT, fantasy));
        assertFalse(policy.allowsSection("unregistered-new-doc", "导言", PromptScene.SETTING_DRAFT, fantasy));
        assertFalse(policy.allowsFixed("references/genres/new/exemplars.md", PromptScene.CHAPTER_CONTENT, fantasy));
        assertFalse(policy.allowsFixed("assets/../styles/fantasy.md", PromptScene.CHAPTER_CONTENT, fantasy));
        assertFalse(policy.allowsRule("unknown-business-rule", PromptScene.CHAPTER_CONTENT, fantasy));
    }

    @Test
    void mixedDocumentIsNotWholeSafeButItsPrincipleSectionRemainsEligible() {
        assertFalse(policy.allowsDocument("character-building", PromptScene.SETTING_DRAFT, fantasy));
        assertTrue(policy.allowsSection("character-building", "人物深度塑造技巧", PromptScene.SETTING_DRAFT, fantasy));
        assertFalse(policy.allowsSection("character-building", "人物档案模板 › 基本信息",
                PromptScene.SETTING_DRAFT, fantasy));
        assertFalse(policy.allowsSection("character-building", "全文", PromptScene.SETTING_DRAFT, fantasy));
        assertFalse(policy.allowsDocument("outline-template", PromptScene.SETTING_DRAFT, fantasy));
        assertTrue(policy.allowsSection("outline-template", "第一步：先判断作品长度级别",
                PromptScene.SETTING_DRAFT, fantasy));
        assertFalse(policy.allowsSection("outline-template", "大纲的分层与字段口径",
                PromptScene.SETTING_DRAFT, fantasy));
        assertTrue(policy.allowsDocument("worldbuilding", PromptScene.SETTING_DRAFT, fantasy));
    }

    @Test
    void proseExamplesStayInExpressionScenesAndGenrePrinciplesRemainAvailable() {
        for (String id : List.of("exemplar-battle", "exemplar-breakthrough", "exemplar-dialogue", "exemplar-emotion")) {
            assertFalse(policy.allowsDocument(id, PromptScene.SETTING_DRAFT, fantasy));
            assertFalse(policy.allowsDocument(id, PromptScene.CHAPTER_PLAN, fantasy));
            assertTrue(policy.allowsDocument(id, PromptScene.CHAPTER_CONTENT, fantasy));
        }
        for (PromptScene scene : PromptScene.values()) {
            boolean expression = Set.of(PromptScene.CHAPTER_CONTENT, PromptScene.CHAPTER_REVISE,
                    PromptScene.CHAPTER_AUDIT).contains(scene);
            assertEquals(expression, policy.allowsFixed("references/genres/fantasy/exemplars.md", scene, fantasy));
            assertTrue(policy.allowsFixed("styles/fantasy.md", scene, fantasy));
        }
        assertFalse(policy.allowsFixed("references/genres/fantasy/arc-templates.md", PromptScene.SETTING_DRAFT, fantasy));
        assertTrue(policy.allowsFixed("references/genres/fantasy/arc-templates.md", PromptScene.VOLUME_BLUEPRINT, fantasy));
        assertTrue(policy.allowsFixed("assets/styles/fantasy.md", PromptScene.SETTING_DRAFT, fantasy));
        assertTrue(policy.allowsRule("genre-exemplar-fantasy", PromptScene.CHAPTER_CONTENT, fantasy));
        assertFalse(policy.allowsRule("genre-exemplar-fantasy", PromptScene.SETTING_DRAFT, fantasy));
    }

    @Test
    void explicitGenreAndContextConditionsCannotBeBypassedByWrongOrMissingContext() {
        PromptContext romance = PromptContext.builder().theme("言情").build();
        assertFalse(policy.allowsFixed("styles/fantasy.md", PromptScene.SETTING_DRAFT, romance));
        assertFalse(policy.allowsFixed("styles/fantasy.md", PromptScene.SETTING_DRAFT, null));
        assertTrue(policy.allowsFixed("styles/romance.md", PromptScene.SETTING_DRAFT, romance));
        assertTrue(policy.allowsDocument("seed-atoms-fantasy", PromptScene.SETTING_DRAFT, fantasy));
        assertFalse(policy.allowsDocument("seed-atoms-fantasy", PromptScene.SETTING_DRAFT, romance));
        assertFalse(policy.allowsDocument("transmigration-transition", PromptScene.SETTING_DRAFT, fantasy));
        assertTrue(policy.allowsDocument("transmigration-transition", PromptScene.SETTING_DRAFT,
                PromptContext.builder().theme("玄幻穿越").build()));
    }

    @Test
    void toneRegenerationCannotRetrieveWorldOrVillainDesignButKeepsStyleGuidance() {
        var tone = PromptContext.builder().theme("玄幻").targetFields(List.of("tone")).build();
        assertFalse(policy.allowsDocument("worldbuilding", PromptScene.SETTING_DRAFT, tone));
        assertFalse(policy.allowsDocument("villain-design", PromptScene.SETTING_DRAFT, tone));
        assertFalse(policy.allowsSection("character-building", "人物深度塑造技巧", PromptScene.SETTING_DRAFT, tone));
        assertTrue(policy.allowsFixed("styles/fantasy.md", PromptScene.SETTING_DRAFT, tone));
        var world = PromptContext.builder().theme("玄幻").targetFields(List.of("worldSetting")).build();
        assertTrue(policy.allowsDocument("worldbuilding", PromptScene.SETTING_DRAFT, world));
        assertFalse(policy.allowsFixed("styles/fantasy.md", PromptScene.SETTING_DRAFT, world));
    }

    @Test
    void usageVersionIsStableAndDependsOnRawRegistryRatherThanEmbedding() throws IOException {
        String json = usageJson();
        assertEquals(64, policy.version().length());
        assertEquals(policy.version(), new ReferenceUsagePolicy(json).version());
        assertNotEquals(policy.version(), new ReferenceUsagePolicy(json + "\n").version());
        JSONObject parsed = JSON.parseObject(json);
        parsed.getJSONObject("documents").getJSONObject("worldbuilding").put("targetFields", List.of("worldSetting"));
        assertNotEquals(policy.version(), new ReferenceUsagePolicy(parsed.toJSONString()).version());
    }

    @Test
    void allRootCatalogAndFixedResourcesAreExplicitlyRegisteredWithRequiredMetadata() throws IOException {
        JSONObject root = JSON.parseObject(usageJson());
        JSONObject docs = root.getJSONObject("documents");
        Set<String> files = new HashSet<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:assets/references/*.md")) {
            String name = resource.getFilename();
            if (name != null && !name.startsWith("design-")) {
                files.add(name.substring(0, name.length() - 3));
            }
        }
        assertEquals(58, files.size());
        assertEquals(files, docs.keySet());
        for (String group : List.of("documents", "fixed")) {
            JSONObject entries = root.getJSONObject(group);
            for (String key : entries.keySet()) {
                JSONObject entry = entries.getJSONObject(key);
                assertNotNull(entry.getString("group"), key);
                assertFalse(entry.getJSONArray("scenes").isEmpty(), key);
                assertFalse(entry.getJSONArray("purposes").isEmpty(), key);
                assertFalse(entry.getJSONArray("types").isEmpty(), key);
            }
        }
        Set<String> fixedFiles = new HashSet<>();
        for (String pattern : List.of("classpath*:assets/styles/*.md", "classpath*:assets/references/genres/*/*.md")) {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                String url = resource.getURL().toString();
                fixedFiles.add(url.substring(url.lastIndexOf("assets/") + 7));
            }
        }
        assertEquals(fixedFiles, root.getJSONObject("fixed").keySet());
    }

    @Test
    void malformedRegistryFailsClosedDuringConstruction() throws IOException {
        assertThrows(IllegalStateException.class, () -> new ReferenceUsagePolicy("{}"));
        assertThrows(IllegalStateException.class, () -> new ReferenceUsagePolicy(
                usageJson().replace("\"PRINCIPLE\"", "\"UNKNOWN_TYPE\"")));
        assertThrows(IllegalStateException.class, () -> new ReferenceUsagePolicy(
                usageJson().replace("\"group\": \"style\"", "\"group\": null")));
    }

    @Test
    void newExpressionAndStructureResourcesAreRegisteredForTheirScenes() {
        for (String id : List.of("emotion-display-dialogue", "human-texture-rhythm", "register-discipline")) {
            assertTrue(policy.allowsDocument(id, PromptScene.CHAPTER_CONTENT, fantasy), id);
            assertTrue(policy.allowsDocument(id, PromptScene.CHAPTER_REVISE, fantasy), id);
            assertFalse(policy.allowsDocument(id, PromptScene.SETTING_DRAFT, fantasy), id);
        }
        assertTrue(policy.allowsDocument("opening-hooks-goldfinger", PromptScene.CHAPTER_PLAN, fantasy));
        assertFalse(policy.allowsDocument("opening-hooks-goldfinger", PromptScene.SETTING_DRAFT, fantasy));
    }

    private static String usageJson() throws IOException {
        try (var stream = new ClassPathResource("assets/reference-usage-policy.json").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
