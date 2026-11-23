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
import java.util.Set;

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
        assertTrue(rendered.contains("入场前提引用"));
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
    void absentCausalityKeepsQuietRenderWithoutInventingReferences() {
        String expected = "\n\n【权威场景契约】场景最终结果以 outcome/function 为准。location/time 是连续性元数据，用于核对场景状态，不要求在首句或第一段显式写出。\n"
                + "场景 1｜id：archive\n人物：[江燃]\n意图(intention)：独自整理旧信\n阻力(resistance)："
                + "\n最终结果(outcome)：\n场景功能(function)：消化\n地点(location)：书房\n时间(time)：当晚"
                + "\n入场前提引用(entryStates，待核验，不是本场新结果)：[]"
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
        assertTrue(rendered.contains("constraints 是待核验约束引用，非本场新结果"), rendered);
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

    @Test
    void renderMarksParsedStringsAsUnverifiedWithoutChangingThePlan() {
        String raw = "{\"chapters\":[{\"chapterNo\":1,\"schemaVersion\":2,\"title\":\"旧信\",\"goal\":\"整理\","
                + "\"scenes\":[{\"id\":\"s1\",\"characters\":[\"江燃\"],\"intention\":\"整理旧信\",\"function\":\"静场\","
                + "\"entryStates\":[\"设备此前已具备远程能力\"],"
                + "\"causality\":{\"constraints\":[\"通行牌允许进入禁地\"]}}]}]}";
        var plan = ChapterPlanChecks.parseLenient(raw);
        assertNotNull(plan);
        String before = JSON.toJSONString(plan);
        String rendered = ScenePlanPolicy.render(plan.getChapters().get(0).getScenes());
        assertTrue(rendered.contains("设备此前已具备远程能力"));
        assertTrue(rendered.contains("通行牌允许进入禁地"));
        for (String field : List.of("entryStates[0]", "causality.constraints[0]")) {
            assertTrue(rendered.contains(field + "：来源缺失或不完整：未核验描述"), rendered);
        }
        assertTrue(rendered.contains("不得仅凭此条认定既成事实或已具备能力"));
        assertFalse(rendered.contains("已有事实引用"));
        assertFalse(rendered.contains("真实事实引用"));
        assertEquals(before, JSON.toJSONString(plan));
    }

    @Test
    void writerReceivesOnlyVerifiedWorldFactsAndKeepsBeliefsPersonal() {
        String hash = "b".repeat(64);
        FactReference verified = new FactReference("ITEM", 8, hash, "契纸", "印章完整", "检视了契纸的印章");
        FactReference completeButUnverified = new FactReference("ITEM", 8, hash, "钥匙", "能开禁地",
                "查看了钥匙");
        FactReference bare = FactReference.fromText("设备此前已具备远程能力");
        SceneCausality causality = new SceneCausality(List.of(
                new SceneCausality.ActorInput("江燃", null, null, null, null, "这条路通向后门")),
                null, null, List.of(completeButUnverified, verified));
        ScenePlan scene = new ScenePlan("trade", List.of("江燃"), "确认货物", "没有约好价钱",
                List.of(bare, verified), "收下货物", null, null, null, null, causality);
        ChapterPlanItemEntity item = v2(List.of(scene));

        String prompt = ScenePlanPolicy.renderForWriting(item, Set.of(verified));
        assertTrue(prompt.contains("已证实的前情：契纸：印章完整"), prompt);
        assertTrue(prompt.contains("已证实的限制：契纸：印章完整"), prompt);
        assertTrue(prompt.contains("江燃此刻相信：这条路通向后门"), prompt);
        for (String omitted : List.of("能开禁地", "具备远程能力", hash, "ITEM", "检视了契纸")) {
            assertFalse(prompt.contains(omitted), prompt);
        }
        assertFalse(ScenePlanPolicy.renderForWriting(item).contains("契纸"), "缺少核验上下文时不认定事实");
    }

    @Test
    void writerKeepsTradeAndActionsAndValidSceneEndings() {
        SceneCausality causality = new SceneCausality(null, List.of(
                new SceneCausality.CausalStep("江燃", "先验印", "掌柜", "把契纸转过来", "试探掌柜是否认识他", null),
                new SceneCausality.CausalStep("江燃", "签下欠条", null, null, null, null)),
                new SceneCausality.Terms("掌柜", "江燃", "半袋米", "三枚铜钱", "由江燃验印", "三日内付清",
                        "收回剩余的米"), null);
        ScenePlan trade = new ScenePlan("trade", List.of("江燃", "掌柜"), "取米", "手头短缺",
                null, "收下半袋米", "余韵：终于放下内疚", List.of("next-stage"), null, null, causality);
        ScenePlan letter = scene("letter", "审查旧信", "字迹模糊", null, "首次发现签名伪造");
        var plan = v2(List.of(trade, letter));
        assertEquals(List.of(), ScenePlanPolicy.violations(plan));

        String prompt = ScenePlanPolicy.renderForWriting(plan);
        assertTrue(prompt.indexOf("先验印") >= 0 && prompt.indexOf("先验印") < prompt.indexOf("签下欠条"), prompt);
        for (String part : List.of("掌柜的回应：把契纸转过来", "掌柜向江燃提供：半袋米", "回报：三枚铜钱",
                "验收方式：由江燃验印", "期限：三日内付清", "未达约定时：收回剩余的米", "本场落点：收下半袋米",
                "余味与节奏：余韵：终于放下内疚", "本场落点：首次发现签名伪造")) {
            assertTrue(prompt.contains(part), prompt);
        }
        assertFalse(prompt.contains("试探掌柜是否认识他"));
        assertFalse(prompt.contains("next-stage"));
    }

    @Test
    void renderDistinguishesCompleteSourceShapeFromActualVerification() {
        FactReference complete = new FactReference("ITEM", 8, "a".repeat(64), "旧信", "封存", "封存旧信");
        String rendered = renderReferences(complete);
        assertTrue(rendered.contains("来源字段齐全，仍须与可信来源核对；字段齐全不等于已核验。"));
        assertFalse(rendered.contains("来源缺失或不完整"));
        for (String value : List.of("ITEM", "8", "a".repeat(64), "旧信", "封存", "封存旧信")) {
            assertTrue(rendered.contains(value));
        }
        List<FactReference> invalid = List.of(
                new FactReference(null, 8, "a".repeat(64), "旧信", "封存", "封存旧信"),
                new FactReference(" ", 8, "a".repeat(64), "旧信", "封存", "封存旧信"),
                new FactReference("ITEM", null, "a".repeat(64), "旧信", "封存", "封存旧信"),
                new FactReference("ITEM", 0, "a".repeat(64), "旧信", "封存", "封存旧信"),
                new FactReference("ITEM", -1, "a".repeat(64), "旧信", "封存", "封存旧信"),
                new FactReference("ITEM", 8, null, "旧信", "封存", "封存旧信"),
                new FactReference("ITEM", 8, "g".repeat(64), "旧信", "封存", "封存旧信"),
                new FactReference("ITEM", 8, "hash", "旧信", "封存", "封存旧信"),
                new FactReference("ITEM", 8, "a".repeat(64), " ", "封存", "封存旧信"),
                new FactReference("ITEM", 8, "a".repeat(64), "旧信", null, "封存旧信"),
                new FactReference("ITEM", 8, "a".repeat(64), "旧信", "封存", " "));
        for (FactReference fact : invalid) {
            String partial = renderReferences(fact);
            for (String field : List.of("entryStates[0]", "causality.constraints[0]")) {
                assertTrue(partial.contains(field + "：来源缺失或不完整：未核验描述"), partial);
            }
            assertFalse(partial.contains("来源字段齐全"));
        }
    }

    @Test
    void verificationProjectsBoundSubjectItemCriterionResultAndOnlyAuthorizedAlternative() throws Exception {
        for (String alternative : Arrays.<String>asList(null, "作者授权以本人既有验字记录代验，仅记录完整且身份一致时可用")) {
            var obligation = new SceneCausality.VerificationObligation("林青", "江燃", "辨字能力", "独立读出署名",
                    "决定是否接手核对，未通过则待验", alternative);
            var causality = new SceneCausality(null, null, null, null, List.of(obligation));
            var scene = new ScenePlan("reading", List.of("江燃", "林青"), "核验辨字能力", "署名模糊", null,
                    "按核验结论决定是否接手", "核验", null, null, null, causality);
            var plan = v2(List.of(scene));
            String before = JSON.toJSONString(plan);
            String writing = ScenePlanPolicy.renderForWriting(plan);
            for (String required : List.of("本场核验：林青核验江燃的辨字能力", "判定标准：独立读出署名",
                    "结果用于：决定是否接手核对，未通过则待验")) {
                assertTrue(writing.contains(required), writing);
            }
            if (alternative == null) {
                assertTrue(writing.contains("未授权替代，不得换被验主体、项目或标准"), writing);
                assertFalse(writing.contains("已授权替代及条件"), writing);
            } else {
                assertTrue(writing.contains("已授权替代及条件：" + alternative), writing);
            }
            String review = ScenePlanPolicy.render(plan.getScenes());
            assertTrue(review.contains(JSON.toJSONString(causality)));
            assertTrue(review.contains("缺省不得新增替代规则"));
            assertEquals(before, JSON.toJSONString(plan), "投影不改权威计划或其hash输入");
            ScenePlanPolicy.normalize(plan);
            assertSame(obligation, plan.getScenes().get(0).causality().verificationObligations().get(0));
            assertEquals(List.of(), ScenePlanPolicy.violations(plan));
            assertEquals(List.of(), plan.getDialogueScenes());
            var mapper = new ObjectMapper();
            assertEquals(plan, mapper.readValue(mapper.writeValueAsString(plan), ChapterPlanItemEntity.class));
            assertEquals(plan, JSON.parseObject(JSON.toJSONString(plan), ChapterPlanItemEntity.class));
        }
        String quiet = ScenePlanPolicy.renderForWriting(v2(List.of(scene("quiet", "看雨", null, null, "静场"))));
        assertFalse(quiet.contains("本场核验"));
        assertFalse(quiet.contains("替代边界"));
    }

    private static String renderReferences(FactReference fact) {
        ScenePlan scene = new ScenePlan("s1", List.of("江燃"), "整理旧信", null,
                List.of(fact), null, "静场", null, null, null,
                new SceneCausality(null, null, null, List.of(fact)));
        return ScenePlanPolicy.render(List.of(scene));
    }

    private static ChapterPlanItemEntity v2(List<ScenePlan> scenes) {
        return ChapterPlanItemEntity.builder().schemaVersion(2).scenes(scenes).build();
    }

    private static ScenePlan scene(String id, String intention, String resistance, String outcome, String function) {
        return new ScenePlan(id, List.of("江燃"), intention, resistance, null, outcome, function, null, "书房", "当晚");
    }
}
