package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.ActorInput;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.CausalStep;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.Choice;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.ProfileRef;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.Terms;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SceneCausalityPolicyTest {

    private static final String PROFILE_HASH = "a".repeat(64);
    // 固定旧因果样本，不从新 record 动态构造期望 JSON/hash。
    private static final String LEGACY_JACKSON_JSON = "{\"actors\":[{\"character\":\"江燃\",\"want\":\"整理旧信\","
            + "\"knowledgeRefs\":[\"character-fact-id-1\"],\"belief\":\"以为信未读\"}],"
            + "\"steps\":[{\"actor\":\"江燃\",\"action\":\"将信归档\",\"because\":\"留待明日核对\","
            + "\"requires\":[\"character-fact-id-1\"]}]}";
    private static final String LEGACY_FASTJSON_JSON = "{\"actors\":[{\"belief\":\"以为信未读\",\"character\":\"江燃\","
            + "\"knowledgeRefs\":[\"character-fact-id-1\"],\"want\":\"整理旧信\"}],"
            + "\"steps\":[{\"action\":\"将信归档\",\"actor\":\"江燃\",\"because\":\"留待明日核对\","
            + "\"requires\":[\"character-fact-id-1\"]}]}";

    @Test
    void absentProfileAndChoiceKeepOldConstructorsJsonAndGoldenHashes() throws Exception {
        ActorInput actor = new ActorInput("江燃", "整理旧信", null, List.of("character-fact-id-1"), null, "以为信未读");
        CausalStep step = new CausalStep("江燃", "将信归档", null, null, "留待明日核对", List.of("character-fact-id-1"));
        SceneCausality legacy = new SceneCausality(List.of(actor), List.of(step), null, null);
        SceneCausality explicitNull = new SceneCausality(List.of(new ActorInput("江燃", "整理旧信", null,
                List.of("character-fact-id-1"), null, "以为信未读", null)),
                List.of(new CausalStep("江燃", "将信归档", null, null, "留待明日核对",
                        List.of("character-fact-id-1"), null)), null, null);
        ObjectMapper mapper = new ObjectMapper();
        String nullJson = LEGACY_JACKSON_JSON.replace("\"belief\":\"以为信未读\"}",
                        "\"belief\":\"以为信未读\",\"profileRef\":null}")
                .replace("\"requires\":[\"character-fact-id-1\"]}",
                        "\"requires\":[\"character-fact-id-1\"],\"choice\":null}");
        for (SceneCausality causality : List.of(legacy, explicitNull,
                mapper.readValue(LEGACY_JACKSON_JSON, SceneCausality.class),
                JSON.parseObject(LEGACY_FASTJSON_JSON, SceneCausality.class),
                mapper.readValue(nullJson, SceneCausality.class), JSON.parseObject(nullJson, SceneCausality.class))) {
            assertEquals(legacy, causality);
            assertNull(causality.actors().get(0).profileRef());
            assertNull(causality.steps().get(0).choice());
            String jackson = mapper.writeValueAsString(causality);
            String fastjson = JSON.toJSONString(causality);
            assertEquals(LEGACY_JACKSON_JSON, jackson);
            assertEquals(LEGACY_FASTJSON_JSON, fastjson);
            assertEquals("1179282c71989bff4af06aa309da579939993c13d04a2211f675e9a80e7664fb",
                    ChapterManuscript.digest(jackson));
            assertEquals("3fd4e10da41c7d70fcc004a16e47795d9a61ca5f687fe488d9338c4b34906d45",
                    ChapterManuscript.digest(fastjson));
        }
    }

    @Test
    void solitaryChoiceRoundTripsWithoutInventingAResponderOrRequiredCost() throws Exception {
        ProfileRef reference = new ProfileRef("character-a", "revision-1", PROFILE_HASH,
                List.of("values", "boundaries"));
        Choice choice = new Choice("求真与珍惜旧物", "立即拆封", null, null);
        ActorInput actor = new ActorInput("江燃", "核对旧信", null, null, null, null, reference);
        CausalStep step = new CausalStep("江燃", "保留封口留待日后核对", null, null,
                "不愿损坏仅存的笔迹", null, choice);
        SceneCausality causality = new SceneCausality(List.of(actor), List.of(step), null, null);
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(causality, mapper.readValue(mapper.writeValueAsString(causality), SceneCausality.class));
        assertEquals(causality, JSON.parseObject(JSON.toJSONString(causality), SceneCausality.class));
        assertEquals(List.of(), SceneCausalityPolicy.violations(quiet(causality)));
        assertNull(step.responder());
        assertNull(step.choice().cost());
        assertNull(step.choice().expectedRelationshipImpact());
        assertNull(actor.knowledgeRefs(), "引用作者档案不能自动生成角色已知事实");
        assertNull(step.requires());
        assertNull(causality.constraints());
    }

    @Test
    void freeCooperationAndEmptyChoiceDoNotRequireConflictSacrificeOrRelationshipChange() {
        for (Choice choice : List.of(new Choice(null, null, null, null),
                new Choice(null, null, "无额外代价", null))) {
            SceneCausality causality = new SceneCausality(null,
                    List.of(new CausalStep("江燃", "递出副本", "林青", "接过副本", "愿意帮忙", null, choice)),
                    new Terms("江燃", "林青", "赠送副本", "无偿", null, null, null), null);
            assertEquals(List.of(), SceneCausalityPolicy.violations(scene(causality, List.of("江燃", "林青"))));
            assertSame(choice, causality.steps().get(0).choice());
            assertNull(choice.valueConflict());
            assertNull(choice.foregoneOption());
            assertNull(choice.expectedRelationshipImpact());
        }
    }

    @Test
    void profileReferenceOnlyChecksDeclaredIdentityVersionHashAndFieldShape() {
        ProfileRef incomplete = new ProfileRef(" ", null, "not-a-hash", Arrays.asList(null, "values", " values "));
        SceneCausality causality = new SceneCausality(List.of(new ActorInput("江燃", null, null,
                null, null, null, incomplete)), null, null, null);
        List<String> violations = SceneCausalityPolicy.violations(quiet(causality));
        assertEquals(5, violations.size());
        for (String field : List.of("characterId", "revision", "contentHash", "fields[0]", "fields[2]")) {
            assertTrue(violations.stream().anyMatch(value -> value.startsWith("causality.actors[0].profileRef." + field)),
                    violations.toString());
        }
        for (List<String> fields : Arrays.<List<String>>asList(null, List.of())) {
            ProfileRef reference = new ProfileRef("not-a-world-fact", "frozen-version", PROFILE_HASH, fields);
            SceneCausality missingFields = new SceneCausality(List.of(new ActorInput("江燃", null, null,
                    null, null, null, reference)), null, null, null);
            assertIssue(SceneCausalityPolicy.violations(quiet(missingFields)),
                    "causality.actors[0].profileRef.fields", "引用");
        }
        assertSame(incomplete, causality.actors().get(0).profileRef());
    }

    @Test
    void profileReferencesCannotBeUsedAsKnowledgeOrActionFactIds() {
        ProfileRef reference = new ProfileRef("character-a", "revision-1", PROFILE_HASH, List.of("values"));
        SceneCausality causality = new SceneCausality(List.of(new ActorInput("江燃", null, null,
                List.of("character-fact-id-1", JSON.toJSONString(reference)), null, null, reference)),
                List.of(new CausalStep("江燃", "翻阅", null, null, null,
                        List.of("character-fact-id-1", "profile:character-a/values"))), null, null);
        List<String> violations = SceneCausalityPolicy.violations(quiet(causality));
        assertEquals(2, violations.size());
        assertIssue(violations, "causality.actors[0].knowledgeRefs[1]", "不能混用");
        assertIssue(violations, "causality.steps[0].requires[1]", "不能混用");
    }

    @Test
    void profileFieldsAreDefensiveWithoutHidingInvalidNullEntries() {
        List<String> fields = new ArrayList<>(Arrays.asList("values", null));
        ProfileRef reference = new ProfileRef("character-a", "revision-1", PROFILE_HASH, fields);
        fields.clear();
        assertEquals(Arrays.asList("values", null), reference.fields());
        assertThrows(UnsupportedOperationException.class, () -> reference.fields().clear());
    }

    @Test
    void absentOrEmptyFieldsAreLegalWithoutAddingAQuota() throws Exception {
        assertEquals(List.of(), SceneCausalityPolicy.violations(null));
        assertEquals(List.of(), SceneCausalityPolicy.violations(quiet(null)));
        SceneCausality absent = new SceneCausality(null, null, null, null);
        assertNull(absent.actors());
        assertNull(absent.steps());
        assertNull(absent.terms());
        assertNull(absent.constraints());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(absent, mapper.readValue("{}", SceneCausality.class));
        assertEquals(absent, JSON.parseObject("{}", SceneCausality.class));
        for (SceneCausality causality : List.of(absent,
                new SceneCausality(List.of(), List.of(), null, List.of()),
                new SceneCausality(null, null, new Terms(null, " ", "", null, null, null, null), null))) {
            ChapterPlanItemEntity plan = ChapterPlanItemEntity.builder().schemaVersion(2)
                    .scenes(List.of(quiet(causality))).build();
            assertEquals(List.of(), SceneCausalityPolicy.violations(plan.getScenes().get(0)));
            ScenePlanPolicy.normalize(plan);
            assertEquals(List.of(), ScenePlanPolicy.violations(plan));
            assertSame(causality, plan.getScenes().get(0).causality());
            assertEquals(List.of(), plan.getDialogueScenes());
            assertNull(causality.terms() == null ? null : causality.terms().deadline());
        }
    }

    @Test
    void solitaryActionDoesNotNeedMotivationResponseConflictTransactionOrChange() {
        ActorInput actor = new ActorInput("江燃", null, null, null, null);
        assertNull(actor.belief());
        assertNull(actor.knowledgeRefs());
        assertNull(actor.resources());
        CausalStep step = new CausalStep("江燃", "坐着看雨", null, null, null, null);
        SceneCausality causality = new SceneCausality(List.of(actor), List.of(step), null, null);
        ScenePlan scene = quiet(causality);
        assertEquals(List.of(), SceneCausalityPolicy.violations(scene));
        ChapterPlanItemEntity plan = ChapterPlanItemEntity.builder().schemaVersion(2).scenes(List.of(scene)).build();
        assertEquals(List.of(), ScenePlanPolicy.violations(plan));
        assertNull(step.response());
        assertNull(step.because());
        assertNull(step.requires());
        assertEquals(List.of(), SceneCausalityPolicy.violations(quiet(
                new SceneCausality(null, List.of(step), null, null))),
                "actors 清单不是每个合法行动主体的必填重复登记");
    }

    @Test
    void duplicateAndOffSceneActorInputsAreReportedWithoutDroppingThem() {
        ActorInput one = new ActorInput("江燃", null, null, null, null);
        ActorInput duplicate = new ActorInput(" 江燃 ", null, null, null, null);
        ActorInput outsider = new ActorInput("林青", null, null, null, null);
        ActorInput anonymous = new ActorInput(" ", null, null, null, null);
        SceneCausality causality = new SceneCausality(List.of(one, duplicate, outsider, anonymous), null, null, null);
        List<String> violations = SceneCausalityPolicy.violations(quiet(causality));
        assertEquals(3, violations.size());
        assertIssue(violations, "causality.actors[1].character", "重复");
        assertIssue(violations, "causality.actors[2].character", "不在 scene.characters");
        assertIssue(violations, "causality.actors[3].character", "身份未明");
        assertEquals(List.of(one, duplicate, outsider, anonymous), causality.actors());
    }

    @Test
    void providedStepsRequireOnlyBasicActionIdentityAndValidNamedResponders() {
        SceneCausality causality = new SceneCausality(null, List.of(
                new CausalStep(null, "抬头", null, null, null, null),
                new CausalStep("江燃", " ", null, null, null, null),
                new CausalStep("林青", "进门", null, null, null, null),
                new CausalStep("江燃", "招手", "林青", null, null, null),
                new CausalStep("江燃", "等候", " ", "点头", null, null)), null, null);
        List<String> violations = SceneCausalityPolicy.violations(quiet(causality));
        assertEquals(5, violations.size());
        assertIssue(violations, "causality.steps[0].actor", "身份未明");
        assertIssue(violations, "causality.steps[1].action", "行动未明");
        assertIssue(violations, "causality.steps[2].actor", "不在 scene.characters");
        assertIssue(violations, "causality.steps[3].responder", "不在 scene.characters");
        assertIssue(violations, "causality.steps[4].responder", "回应人物未明");
        assertEquals(List.of(), SceneCausalityPolicy.violations(scene(
                new SceneCausality(null, List.of(new CausalStep("江燃", "等候", "林青", null, null, null)), null, null),
                List.of("江燃", "林青"))), "指定对方不强制杜撰对方回应");
    }

    @Test
    void listEntriesKeepIndexesForStructuralErrorsInsteadOfFailingDuringParsing() throws Exception {
        String json = """
                {"actors":[null,{"character":"江燃","knowledgeRefs":[null," "],"resources":[""]}],
                 "steps":[null,{"actor":"江燃","action":"翻阅","requires":[null,""]}],
                 "constraints":[null]}
                """;
        SceneCausality causality = new ObjectMapper().readValue(json, SceneCausality.class);
        assertEquals(causality, JSON.parseObject(json, SceneCausality.class));
        List<String> violations = SceneCausalityPolicy.violations(quiet(causality));
        assertEquals(8, violations.size());
        for (String path : List.of("causality.actors[0]", "causality.actors[1].knowledgeRefs[0]",
                "causality.actors[1].knowledgeRefs[1]", "causality.actors[1].resources[0]",
                "causality.steps[0]", "causality.steps[1].requires[0]", "causality.steps[1].requires[1]",
                "causality.constraints[0]")) {
            assertIssue(violations, path, "空");
        }
    }

    @Test
    void mistakenBeliefIsNotPromotedToOrComparedAsAWorldFact() {
        FactReference fact = new FactReference("item", 8, "hash", "旧信", "已焚毁", "信纸已化为灰烬");
        ActorInput actor = new ActorInput("江燃", null, null, List.of("character-fact-id-1", "character-fact-id-2"),
                null, "误以为旧信仍然完好");
        SceneCausality causality = new SceneCausality(List.of(actor), null, null, List.of(fact));
        ScenePlan scene = new ScenePlan("remember", List.of("江燃"), "独自回忆", null,
                List.of(fact), null, "消化", null, null, null, causality);
        assertEquals(List.of(), SceneCausalityPolicy.violations(scene));
        assertEquals(List.of(), ScenePlanPolicy.violations(ChapterPlanItemEntity.builder()
                .schemaVersion(2).scenes(List.of(scene)).build()));
        assertEquals("误以为旧信仍然完好", actor.belief());
        assertEquals(List.of("character-fact-id-1", "character-fact-id-2"), actor.knowledgeRefs());
        assertEquals(List.of(fact), causality.constraints());
    }

    @Test
    void nonemptyConstraintsRemainUnmergedAndAwaitFactAcceptance() {
        FactReference one = new FactReference("item", 8, "hash-1", "旧信", "完好", "收到旧信");
        FactReference two = new FactReference("item", 9, "hash-2", "旧信", "已焚毁", "烧毁旧信");
        FactReference unresolved = new FactReference(null, null, null, "未知物件", null, null);
        SceneCausality causality = new SceneCausality(null, null, null, List.of(one, two, unresolved));
        assertEquals(List.of(), SceneCausalityPolicy.violations(quiet(causality)),
                "机械结构通过不表示事实真实；来源、同主体状态及缺少证据由 PlanAcceptance 核验");
        assertEquals(List.of(one, two, unresolved), causality.constraints());
        assertSame(one, causality.constraints().get(0));
        assertSame(two, causality.constraints().get(1));
    }

    @Test
    void immediateOrExplicitlyFreeExchangeNeedsNoInventedVerificationDeadlineOrFailureCost() {
        for (Terms terms : List.of(
                new Terms("江燃", "林青", "递交旧信副本", "当场支付五元", null, null, null),
                new Terms("江燃", "林青", "赠送旧信副本", "无偿", null, null, null),
                new Terms("江燃", "林青", "借出旧信副本", "看完归还", "林青逐页核对副本", null, null),
                new Terms("江燃", "林青", "借出旧信副本", "归还副本", "双方核对页数", "明日日落前", "未归还则暂停借阅"))) {
            SceneCausality causality = new SceneCausality(null, null, terms, null);
            ScenePlan scene = scene(causality, List.of("江燃", "林青"));
            assertEquals(List.of(), SceneCausalityPolicy.violations(scene));
            assertSame(terms, scene.causality().terms());
        }
    }

    @Test
    void causalExchangeRoundTripsAllOptionalFieldsWithoutChangingTheirMeaning() throws Exception {
        SceneCausality causality = new SceneCausality(
                List.of(new ActorInput("江燃", "取回副本", "担心原信受损", List.of("character-fact-id-3"),
                        List.of("五元"), "相信副本完整"), new ActorInput("林青", null, null, null, null)),
                List.of(new CausalStep("江燃", "支付五元", "林青", "交出副本", "已约定交换", List.of("副本备妥"))),
                new Terms("林青", "江燃", "旧信副本", "五元", "双方逐页核验", "当场", "缺页则退款"),
                List.of(new FactReference("item", 8, "hash", "旧信副本", "备妥", "复印完整的旧信")));
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(causality, mapper.readValue(mapper.writeValueAsString(causality), SceneCausality.class));
        assertEquals(causality, JSON.parseObject(JSON.toJSONString(causality), SceneCausality.class));
        assertEquals(List.of(), SceneCausalityPolicy.violations(scene(causality, List.of("江燃", "林青"))));
    }

    @Test
    void incompleteExchangeReportsSpecificUnknownsWithoutFillingThemIn() {
        Terms incomplete = new Terms("江燃", null, " ", null, null, null, null);
        ScenePlan scene = scene(new SceneCausality(null, null, incomplete, null), List.of("江燃", "林青"));
        List<String> violations = SceneCausalityPolicy.violations(scene);
        assertEquals(3, violations.size());
        assertIssue(violations, "causality.terms.receiver", "身份未明");
        assertIssue(violations, "causality.terms.consideration", "内容未明");
        assertIssue(violations, "causality.terms.payment", "回报未明");
        assertSame(incomplete, scene.causality().terms());
        assertNull(incomplete.receiver());
        assertNull(incomplete.payment());
        assertNull(incomplete.deadline());
        assertNull(incomplete.onFailure());
    }

    @Test
    void exchangePartiesMustBeDifferentOnSceneCharacters() {
        for (Terms terms : List.of(
                new Terms("外人", "林青", "旧信副本", "五元", null, null, null),
                new Terms("江燃", "外人", "旧信副本", "五元", null, null, null),
                new Terms("江燃", " 江燃 ", "旧信副本", "五元", null, null, null))) {
            List<String> violations = SceneCausalityPolicy.violations(scene(
                    new SceneCausality(null, null, terms, null), List.of("江燃", "林青")));
            assertEquals(1, violations.size());
            assertTrue(violations.get(0).contains("不在 scene.characters")
                    || violations.get(0).contains("同一人物"), violations.toString());
        }
    }

    @Test
    void verificationOrDeadlineAloneDoesNotSilentlyInventAnExchange() {
        for (Terms terms : List.of(new Terms(null, null, null, null, "双方现场核验", null, null),
                new Terms(null, null, null, null, null, "明天", null),
                new Terms(null, null, null, null, null, null, "退回原物"))) {
            List<String> violations = SceneCausalityPolicy.violations(scene(
                    new SceneCausality(null, null, terms, null), List.of("江燃", "林青")));
            assertEquals(4, violations.size());
            for (String field : List.of("offerer", "receiver", "consideration", "payment")) {
                assertIssue(violations, "causality.terms." + field, "未明");
            }
        }
    }

    @Test
    void optionalListsAreDefensiveSnapshotsAndKeepNullEntriesForValidation() {
        List<String> refs = new ArrayList<>(Arrays.asList("character-fact-id-1", null));
        List<String> resources = new ArrayList<>(List.of("封套"));
        ActorInput actor = new ActorInput("江燃", null, null, refs, resources);
        CausalStep step = new CausalStep("江燃", "整理", null, null, null, refs);
        List<ActorInput> actors = new ArrayList<>(List.of(actor));
        List<CausalStep> steps = new ArrayList<>(List.of(step));
        FactReference fact = new FactReference("item", 8, "hash", "旧信", "封存", "收好旧信");
        List<FactReference> constraints = new ArrayList<>(Arrays.asList(fact, null));
        SceneCausality causality = new SceneCausality(actors, steps, null, constraints);
        refs.clear();
        resources.clear();
        actors.clear();
        steps.clear();
        constraints.clear();
        assertEquals(Arrays.asList("character-fact-id-1", null), actor.knowledgeRefs());
        assertEquals(List.of("封套"), actor.resources());
        assertEquals(actor.knowledgeRefs(), step.requires());
        assertEquals(List.of(actor), causality.actors());
        assertEquals(List.of(step), causality.steps());
        assertEquals(Arrays.asList(fact, null), causality.constraints());
        assertThrows(UnsupportedOperationException.class, () -> actor.knowledgeRefs().clear());
        assertThrows(UnsupportedOperationException.class, () -> actor.resources().clear());
        assertThrows(UnsupportedOperationException.class, () -> step.requires().clear());
        assertThrows(UnsupportedOperationException.class, () -> causality.actors().clear());
        assertThrows(UnsupportedOperationException.class, () -> causality.steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> causality.constraints().clear());
    }

    private static void assertIssue(List<String> violations, String path, String detail) {
        assertTrue(violations.stream().anyMatch(v -> v.startsWith(path + " ") && v.contains(detail)),
                violations.toString());
    }

    private static ScenePlan quiet(SceneCausality causality) {
        return scene(causality, List.of("江燃"));
    }

    private static ScenePlan scene(SceneCausality causality, List<String> characters) {
        return new ScenePlan("quiet", characters, "静静回忆", null, null, null,
                "静场：看雨", null, "书房", "当晚", causality);
    }
}
