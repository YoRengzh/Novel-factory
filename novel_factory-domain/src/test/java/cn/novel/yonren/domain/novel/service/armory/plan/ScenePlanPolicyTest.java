package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScenePlanPolicyTest {

    // 固定的十字段旧样本；不要由带 causality 的对象生成期望值，否则会掩盖 JSON/hash 漂移。
    private static final String LEGACY_JACKSON_JSON = "{\"id\":\"archive\",\"characters\":[\"江燃\"],"
            + "\"intention\":\"独自整理旧信\",\"resistance\":null,\"entryStates\":[],\"outcome\":null,"
            + "\"function\":\"消化\",\"milestoneRefs\":[],\"location\":\"书房\",\"time\":\"当晚\"}";
    private static final String LEGACY_FASTJSON_JSON = "{\"characters\":[\"江燃\"],\"entryStates\":[],"
            + "\"function\":\"消化\",\"id\":\"archive\",\"intention\":\"独自整理旧信\",\"location\":\"书房\","
            + "\"milestoneRefs\":[],\"time\":\"当晚\"}";

    @Test
    void oldConstructorAndExplicitNullKeepLegacyJsonAndHashes() throws Exception {
        ScenePlan oldConstructor = scene("archive", "独自整理旧信", null, null, "消化");
        ScenePlan explicitNull = new ScenePlan("archive", List.of("江燃"), "独自整理旧信", null,
                List.of(), null, "消化", List.of(), "书房", "当晚", null);
        ObjectMapper mapper = new ObjectMapper();
        String explicitNullJson = LEGACY_JACKSON_JSON.substring(0, LEGACY_JACKSON_JSON.length() - 1)
                + ",\"causality\":null}";
        assertEquals(oldConstructor, explicitNull);
        for (ScenePlan scene : List.of(oldConstructor, explicitNull,
                mapper.readValue(LEGACY_JACKSON_JSON, ScenePlan.class),
                JSON.parseObject(LEGACY_FASTJSON_JSON, ScenePlan.class),
                mapper.readValue(explicitNullJson, ScenePlan.class),
                JSON.parseObject(explicitNullJson, ScenePlan.class))) {
            assertNull(scene.causality(), "缺省因果补充不能自动归一为空对象");
            String jackson = mapper.writeValueAsString(scene);
            String fastjson = JSON.toJSONString(scene);
            assertEquals(LEGACY_JACKSON_JSON, jackson);
            assertEquals(LEGACY_FASTJSON_JSON, fastjson);
            assertEquals("7401d7a4071fae4da28bc6f44e0def5c50d32b44a6af3577f9caacebd070022e",
                    ChapterManuscript.digest(jackson));
            assertEquals("1760b703be80372a28bb29ef812edd97d2ce3305b855086f1994e27bc4884d5b",
                    ChapterManuscript.digest(fastjson));
        }
    }

    @Test
    void legacyChapterPlanHashStaysStableWithAnAbsentCausality() {
        String oldPlanJson = "{\"chapterNo\":9,\"scenes\":[" + LEGACY_FASTJSON_JSON + "],\"schemaVersion\":2}";
        ChapterPlanItemEntity restored = JSON.parseObject(oldPlanJson, ChapterPlanItemEntity.class);
        ChapterPlanItemEntity constructed = v2(List.of(scene("archive", "独自整理旧信", null, null, "消化")));
        constructed.setChapterNo(9);
        assertEquals(constructed, restored);
        for (ChapterPlanItemEntity plan : List.of(restored, constructed)) {
            assertNull(plan.getScenes().get(0).causality());
            String actual = JSON.toJSONString(plan);
            assertEquals(oldPlanJson, actual);
            assertEquals("d4a7e4224cd993474c6520d7928c5e231ce12c452373877c574de32a222f779b",
                    ChapterManuscript.digest(actual));
        }
    }

    @Test
    void nullListsBecomeEmptyAndRecordJsonRoundTrips() throws Exception {
        ScenePlan emptyLists = new ScenePlan("archive", null, "整理", null, null,
                "归档", "消化", null, "书房", "当晚");
        assertEquals(List.of(), emptyLists.characters());
        assertEquals(List.of(), emptyLists.entryStates());
        assertEquals(List.of(), emptyLists.milestoneRefs());
        ScenePlan scene = new ScenePlan("archive", List.of("江燃"), "核对旧信", "字迹模糊",
                List.of(new FactReference("character", 8, "hash", "江燃", "持有旧信", "收到旧信")),
                "旧信归档", "消化", List.of("letters-reviewed"), "书房", "当晚");
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(scene, mapper.readValue(mapper.writeValueAsString(scene), ScenePlan.class));
        ScenePlan missingLists = mapper.readValue("{\"id\":\"archive\",\"intention\":\"整理\"}", ScenePlan.class);
        assertEquals(List.of(), missingLists.characters());
        assertEquals(List.of(), missingLists.entryStates());
        assertEquals(List.of(), missingLists.milestoneRefs());
    }

    @Test
    void normalizeOnlyV2AndNeverFabricatesScenesFromLegacyEvents() {
        ChapterPlanItemEntity legacy = ChapterPlanItemEntity.builder()
                .characters(List.of("旧人物")).keyEvents(List.of("旧事件")).build();
        ScenePlanPolicy.normalize(legacy);
        assertNull(legacy.getScenes());
        assertEquals(List.of("旧人物"), legacy.getCharacters());
        assertEquals(List.of("旧事件"), legacy.getKeyEvents());
        legacy.setSchemaVersion(2);
        ScenePlanPolicy.normalize(legacy);
        assertTrue(legacy.getScenes() == null || legacy.getScenes().isEmpty());
        assertEquals(List.of(), legacy.getCharacters());
        assertEquals(List.of(), legacy.getKeyEvents());
        assertEquals(List.of(), legacy.getDialogueScenes());
    }

    @Test
    void normalizeDerivesPeopleAndFullEventsWithoutChangingAuthoritativeScenes() {
        ScenePlan scene = scene("sealed-letter", "取出旧信", "封口脆弱", "信已取出", "核对");
        ChapterPlanItemEntity plan = v2(List.of(scene));
        plan.setCharacters(List.of("凭空人物"));
        plan.setKeyEvents(List.of("旧结果"));
        plan.setDialogueScenes(List.of("强造对白"));
        ScenePlanPolicy.normalize(plan);
        assertSame(scene, plan.getScenes().get(0));
        assertEquals("sealed-letter", plan.getScenes().get(0).id());
        assertEquals(List.of("江燃"), plan.getCharacters());
        String event = plan.getKeyEvents().get(0);
        for (String part : List.of("取出旧信", "封口脆弱", "信已取出", "核对")) {
            assertTrue(event.contains(part), event);
        }
        assertTrue(event.endsWith("。"), event);
        assertEquals(List.of(), plan.getDialogueScenes());
        ScenePlanPolicy.normalize(plan);
        assertEquals(List.of(event), plan.getKeyEvents(), "派生应幂等");
    }

    @Test
    void v2RequiresNonemptyScenesAndUniqueNonblankIds() {
        assertTrue(ScenePlanPolicy.violations(v2(List.of())).stream().anyMatch(v -> v.contains("scenes")));
        assertFalse(ScenePlanPolicy.violations(v2(List.of(scene("", "核对", "难辨", "归档", null)))).isEmpty());
        ScenePlan one = scene("shared", "核对", "难辨", "归档", null);
        assertTrue(ScenePlanPolicy.violations(v2(List.of(one, one))).stream().anyMatch(v -> v.contains("shared")));
        assertFalse(ScenePlanPolicy.violations(v2(Arrays.asList((ScenePlan) null))).isEmpty());
    }

    @Test
    void scenesNeedCharactersIntentionAndOutcomeOrFunction() {
        assertFalse(ScenePlanPolicy.violations(v2(List.of(new ScenePlan("a", List.of(), "核对", "难辨",
                null, "归档", null, null, null, null)))).isEmpty());
        assertFalse(ScenePlanPolicy.violations(v2(List.of(scene("a", " ", "难辨", "归档", null)))).isEmpty());
        assertFalse(ScenePlanPolicy.violations(v2(List.of(scene("a", "核对", "难辨", null, " ")))).isEmpty());
        assertFalse(ScenePlanPolicy.violations(v2(List.of(scene("a", "核对", null, "归档", "推进")))).isEmpty());
    }

    @Test
    void quietTransitionDigestionAndAfterglowNeedNoExternalConflictOrDialogue() {
        for (String function : List.of("过渡", "消化", "余韵", "静场", "transition", "消化离别后的情绪", "余韵：看向空桌面", "DIGESTION: grieving", "quiet: watching rain")) {
            ChapterPlanItemEntity plan = v2(List.of(scene("quiet", "独自整理旧信", null, null, function)));
            ScenePlanPolicy.normalize(plan);
            assertEquals(List.of(), ScenePlanPolicy.violations(plan), function);
            assertEquals(List.of(), plan.getDialogueScenes());
            assertTrue(plan.getKeyEvents().get(0).contains(function));
        }
    }

    @Test
    void uncertaintyCanSupplyResistanceWithoutInventingExternalConflict() {
        assertEquals(List.of(), ScenePlanPolicy.violations(v2(List.of(
                scene("inspect", "核对署名", "uncertainty：尚无法辨认署名", "确认署名", "调查")))));
        assertFalse(ScenePlanPolicy.violations(v2(List.of(
                scene("inspect", "核对未知署名", null, "确认署名", "调查")))).isEmpty(),
                "任意意图含未知不证明阻力充分，应显式声明在 resistance");
        assertFalse(ScenePlanPolicy.violations(v2(List.of(
                scene("inspect", "核对署名", null, "确认署名", "不是过渡")))).isEmpty());
    }

    @Test
    void renderKeepsEveryIdAndFactAndMilestoneReferenceDistinct() {
        FactReference fact = new FactReference("character", 8, "hash", "江燃", "持有旧信", "收到旧信");
        ScenePlan scene = new ScenePlan("letter-review", List.of("江燃"), "核对旧信", "字迹模糊",
                List.of(fact), "旧信归档", "消化", List.of("letters-reviewed"), "书房", "当晚");
        String rendered = ScenePlanPolicy.render(List.of(scene));
        for (String part : List.of("letter-review", "江燃", "核对旧信", "字迹模糊", "旧信归档", "消化",
                "letters-reviewed", "书房", "当晚", "character", "8", "hash", "持有旧信", "收到旧信")) {
            assertTrue(rendered.contains(part), rendered);
        }
        assertTrue(rendered.contains("已有事实引用"));
        assertTrue(rendered.contains("里程碑引用"));
        assertTrue(rendered.contains("不代表已完成"));
        assertEquals("", ScenePlanPolicy.render(null));
        assertEquals("", ScenePlanPolicy.render(List.of()));
    }

    @Test
    void legacyPlanJsonRemainsReadableAndV2PlanRoundTrips() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChapterPlanItemEntity legacy = mapper.readValue("{\"goal\":\"目标\",\"keyEvents\":[\"旧事件\"],"
                + "\"characters\":[\"江燃\"],\"dialogueScenes\":[]}", ChapterPlanItemEntity.class);
        assertNull(legacy.getSchemaVersion());
        assertNull(legacy.getScenes());
        assertEquals(List.of("旧事件"), legacy.getKeyEvents());
        ChapterPlanItemEntity plan = v2(List.of(scene("letter-review", "核对", "难辨", "归档", null)));
        ChapterPlanItemEntity restored = mapper.readValue(mapper.writeValueAsString(plan), ChapterPlanItemEntity.class);
        assertEquals(plan, restored);
    }

    @Test
    void normalizeAndJsonRoundTripsKeepCausalityAndConstraintsWithoutInventingDialogue() throws Exception {
        FactReference fact = new FactReference("item", 8, "hash", "旧信", "已封存", "将旧信装进封套");
        SceneCausality causality = new SceneCausality(
                List.of(new SceneCausality.ActorInput("江燃", "整理旧信", null,
                        List.of("character-fact-id-1"), List.of("封套"), "相信信封里还有未读的纸条")),
                List.of(new SceneCausality.CausalStep("江燃", "将旧信归档", null, null, null, null)),
                null, List.of(fact));
        ScenePlan scene = new ScenePlan("archive", List.of("江燃"), "独自整理旧信", null,
                List.of(fact), null, "静场：整理旧物", List.of(), "书房", "当晚", causality);
        ChapterPlanItemEntity plan = v2(List.of(scene));
        ScenePlanPolicy.normalize(plan);
        ScenePlanPolicy.normalize(plan);
        assertSame(scene, plan.getScenes().get(0));
        assertSame(causality, plan.getScenes().get(0).causality());
        assertSame(fact, plan.getScenes().get(0).causality().constraints().get(0));
        assertEquals(Integer.valueOf(2), plan.getSchemaVersion());
        assertEquals(List.of(), ScenePlanPolicy.violations(plan));
        assertEquals(List.of(), plan.getDialogueScenes());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(scene, mapper.readValue(mapper.writeValueAsString(scene), ScenePlan.class));
        assertEquals(scene, JSON.parseObject(JSON.toJSONString(scene), ScenePlan.class));
        assertEquals(plan, mapper.readValue(mapper.writeValueAsString(plan), ChapterPlanItemEntity.class));
        assertEquals(plan, JSON.parseObject(JSON.toJSONString(plan), ChapterPlanItemEntity.class));
    }

    @Test
    void structuralCausalityIssuesIncludeChapterSceneAndFieldIndexes() {
        SceneCausality invalid = new SceneCausality(null,
                List.of(new SceneCausality.CausalStep("江燃", " ", null, null, null, null)), null, null);
        ScenePlan scene = new ScenePlan("archive", List.of("江燃"), "整理旧信", null, null,
                null, "消化", null, null, null, invalid);
        List<String> violations = ScenePlanPolicy.violations(v2(List.of(scene)));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).startsWith("scenes[0].causality.steps[0].action"), violations.toString());
    }

    @Test
    void absentCausalityKeepsLegacyRenderExactlyUnchanged() {
        String expected = "\n\n【权威场景契约】场景最终结果以 outcome/function 为准。\n"
                + "场景 1｜id：archive\n人物：[江燃]\n意图(intention)：独自整理旧信\n阻力(resistance)："
                + "\n最终结果(outcome)：\n场景功能(function)：消化\n地点(location)：书房\n时间(time)：当晚"
                + "\n已有事实引用(entryStates，入场状态，不是本场新结果)：[]"
                + "\n里程碑引用(milestoneRefs，仅映射待推进目标，不代表已完成)：[]\n";
        assertEquals(expected, ScenePlanPolicy.render(List.of(scene("archive", "独自整理旧信", null, null, "消化"))));
    }

    @Test
    void renderIncludesProvidedCausalityWithoutPromotingBeliefToWorldFacts() {
        FactReference fact = new FactReference("item", 8, "hash", "旧信", "已焚毁", "信纸已化为灰烬");
        SceneCausality causality = new SceneCausality(
                List.of(new SceneCausality.ActorInput("江燃", null, null, List.of("character-fact-id-2"),
                        null, "误以为旧信仍完好")),
                List.of(new SceneCausality.CausalStep("江燃", "询问信件", "林青", "说明所见", "仍未获知", List.of("知情事实"))),
                new SceneCausality.Terms("林青", "江燃", "递交灰烬", "无偿", "江燃当面查验", null, null),
                List.of(fact));
        ScenePlan scene = new ScenePlan("letter-review", List.of("江燃", "林青"), "核对旧信", "认知不确定",
                List.of(fact), null, "调查", null, null, null, causality);
        String rendered = ScenePlanPolicy.render(List.of(scene));
        assertTrue(rendered.contains(JSON.toJSONString(causality)), rendered);
        assertTrue(rendered.contains("belief 是角色主观所信，可与事实不符"), rendered);
        assertTrue(rendered.contains("knowledgeRefs 是人物事实 ID"), rendered);
        assertTrue(rendered.contains("constraints 是待核验的真实事实引用，非本场新结果"), rendered);
    }

    @Test
    void renderPreservesProfileVersionAndChoiceWithoutTreatingThemAsFactsOrNewScope() {
        SceneCausality.ProfileRef reference = new SceneCausality.ProfileRef("character-a", "frozen-revision",
                "a".repeat(64), List.of("values", "boundaries"));
        SceneCausality.Choice choice = new SceneCausality.Choice("求真与珍惜旧物", "立即拆封", null, null);
        SceneCausality causality = new SceneCausality(List.of(new SceneCausality.ActorInput("江燃", null,
                null, null, null, null, reference)),
                List.of(new SceneCausality.CausalStep("江燃", "保留封口", null, null, "珍惜仅存的笔迹", null, choice)),
                null, null);
        ScenePlan scene = new ScenePlan("preserve-letter", List.of("江燃"), "整理旧信", null,
                null, null, "静场：旧物余韵", null, null, null, causality);
        ChapterPlanItemEntity plan = v2(List.of(scene));
        ScenePlanPolicy.normalize(plan);
        assertEquals(List.of(), ScenePlanPolicy.violations(plan));
        assertSame(reference, plan.getScenes().get(0).causality().actors().get(0).profileRef());
        assertSame(choice, plan.getScenes().get(0).causality().steps().get(0).choice());
        assertEquals(List.of(), plan.getDialogueScenes());
        String rendered = ScenePlanPolicy.render(plan.getScenes());
        assertTrue(rendered.contains(JSON.toJSONString(causality)), rendered);
        assertTrue(rendered.contains("不等于人物已知或正文事实"), rendered);
        assertTrue(rendered.contains("不得放入 knowledgeRefs/requires"), rendered);
        assertTrue(rendered.contains("已选行动见 action、理由见 because"), rendered);
        assertTrue(rendered.contains("均按实际适用，不要求补齐"), rendered);
        assertNull(choice.cost());
        assertNull(causality.actors().get(0).knowledgeRefs());
    }

    private static ChapterPlanItemEntity v2(List<ScenePlan> scenes) {
        return ChapterPlanItemEntity.builder().schemaVersion(2).scenes(scenes).build();
    }

    private static ScenePlan scene(String id, String intention, String resistance, String outcome, String function) {
        return new ScenePlan(id, List.of("江燃"), intention, resistance, null, outcome, function, null, "书房", "当晚");
    }
}
