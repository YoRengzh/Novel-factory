package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.memory.CharacterStateProjector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WriterContextAssemblerTest {

    private static ScenePlan scene(String id, String location, String intention, String outcome, String... cast) {
        return new ScenePlan(id, List.of(cast), intention, "管事说没带钥匙", List.of(), outcome,
                "推进", List.of(), location, null);
    }

    private static ChapterPlanItemEntity item(ScenePlan... scenes) {
        return ChapterPlanItemEntity.builder().chapterNo(3).schemaVersion(2)
                .characters(List.of("沈砚", "周芸")).scenes(List.of(scenes)).build();
    }

    @Test
    void disabledSectionBudgetReallyKeepsAllLocationsTogether() {
        var plan = WriterContextAssembler.assemble(item(
                scene("s-1", "药铺", "借钥匙", "被拒"),
                scene("s-2", "后巷", "追查", "看见账本")), List.of(), 0);

        assertEquals(1, plan.sections().size());
        assertTrue(plan.sections().get(0).render().contains("借钥匙"));
        assertTrue(plan.sections().get(0).render().contains("看见账本"));
        assertFalse(plan.knowledgeBoundarySupplied());
    }

    @Test
    void writerReceivesVerificationSubjectAndCriterionWithoutInventedAlternative() {
        var causality = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality(
                null, null, null, null, List.of(
                new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.VerificationObligation(
                        "许衡", "苏尘", "基础法术", "独立完成一次施法", "合格才准报名", null)));
        var scene = new ScenePlan("exam", List.of("许衡", "苏尘"), "接受考校", "施法迟缓", List.of(),
                "依据考校结果判定资格", "考校", List.of(), "报名廊", null, causality);
        String text = WriterContextAssembler.assemble(item(scene), List.of()).sections().get(0).render();

        assertTrue(text.contains("被核验对象：苏尘"));
        assertTrue(text.contains("须实际核验的项目：基础法术"));
        assertTrue(text.contains("判定标准：独立完成一次施法"));
        assertFalse(text.contains("明确允许的替代"));
    }

    @Test
    void rendersScenesVerbatimWithoutIdsOrHashes() {
        var plan = WriterContextAssembler.assemble(item(
                scene("s-1", "药铺", "沈砚要借钥匙", "管事说没带")), List.of());

        String text = plan.sections().get(0).render();
        assertTrue(text.contains("沈砚要借钥匙"));
        assertTrue(text.contains("管事说没带"));
        assertFalse(text.contains("s-1"), "场景 ID 是规划层标识，不进正文材料");
        assertFalse(text.contains("hash"));
    }

    @Test
    void shortChapterStaysOneSectionDespiteLocationChange() {
        var plan = WriterContextAssembler.assemble(item(
                scene("s-1", "药铺", "借钥匙", "被拒"),
                scene("s-2", "药铺", "再问一次", "管事改口"),
                scene("s-3", "后巷", "跟踪管事", "发现账本")), List.of());

        assertEquals(1, plan.sections().size(), "整章装得下就不分段，避免关闭候选与整章修订");
        String text = plan.sections().get(0).render();
        assertTrue(text.contains("再问一次"));
        assertTrue(text.contains("发现账本"));
    }

    @Test
    void splitsOnlyWhenMaterialExceedsBudget() {
        var plan = WriterContextAssembler.assemble(item(
                scene("s-1", "药铺", "借钥匙", "被拒"),
                scene("s-2", "后巷", "跟踪管事", "发现账本")), List.of(), 60);

        assertEquals(2, plan.sections().size(), "超预算时在换场处断开");
        assertTrue(plan.sections().get(0).render().contains("借钥匙"));
        assertFalse(plan.sections().get(0).render().contains("发现账本"));
    }

    @Test
    void sectionOnlyCarriesItsOwnCastState() {
        var summary = boundSummary("沈砚怀疑管事偷了账本。周芸的目标是查清盐账。");
        var plan = WriterContextAssembler.assemble(item(
                scene("s-1", "药铺", "借钥匙", "被拒", "沈砚"),
                scene("s-2", "后巷", "追查", "发现账本", "周芸")), List.of(summary), 40);

        assertEquals(2, plan.sections().size());
        String first = plan.sections().get(0).render();
        String second = plan.sections().get(1).render();
        assertTrue(first.contains("沈砚"));
        assertFalse(first.contains("查清盐账"), "不在场人物的目标不进本段材料");
        assertTrue(second.contains("查清盐账"));
    }

    @Test
    void writerStateProjectionDropsAuditFields() {
        var summary = boundSummary("沈砚怀疑管事偷了账本。");
        var plan = WriterContextAssembler.assemble(item(
                scene("s-1", "药铺", "借钥匙", "被拒", "沈砚")), List.of(summary));

        String text = plan.sections().get(0).render();
        assertTrue(text.contains("怀疑：管事偷了账本"));
        assertFalse(text.contains("character-fact-"), "正文投影不带 factId");
        assertFalse(text.contains("第2章"), "正文禁止章节号元信息");
        assertFalse(text.contains("hash"));
    }

    /** 按真实链路绑定证据：未绑定的人物状态不会进入投影，测试不能绕过这道校验。 */
    private static ChapterSummaryEntity boundSummary(String content) {
        var chapter = ChapterContentEntity.builder().chapterNo(2).title("查账").content(content).build();
        var manuscript = ChapterManuscript.of(chapter);
        var summary = ChapterSummaryEntity.builder().chapterNo(2)
                .manuscriptHash(manuscript.hash())
                .characterEffects(List.of(
                        new CharacterEffect("沈砚", CharacterEffect.Kind.BELIEF, "账本去向",
                                CharacterEffect.State.SUSPECTED, "管事偷了账本", "沈砚怀疑管事偷了账本。",
                                "观察", null),
                        new CharacterEffect("周芸", CharacterEffect.Kind.GOAL, "查盐账",
                                CharacterEffect.State.ESTABLISHED, "查清盐账", "周芸的目标是查清盐账。",
                                "自述", null)))
                .build();
        CharacterStateProjector.bind(summary, manuscript, List.of());
        return summary;
    }

    @Test
    void chapterGoalOnEverySectionButEndingOnlyOnLast() {
        var item = ChapterPlanItemEntity.builder().chapterNo(3).schemaVersion(2)
                .goal("拿到盐账副本")
                .endingHook("他把账本揣进怀里，没有回头")
                .characters(List.of("沈砚"))
                .scenes(List.of(
                        scene("s-1", "药铺", "借钥匙", "被拒"),
                        scene("s-2", "后巷", "追查", "发现账本")))
                .build();

        var plan = WriterContextAssembler.assemble(item, List.of(), 40);

        assertEquals(2, plan.sections().size());
        assertTrue(plan.sections().get(0).render().contains("拿到盐账副本"), "每段都要知道本章要做什么");
        assertFalse(plan.sections().get(0).render().contains("没有回头"), "中途段落不给章末落点");
        assertTrue(plan.sections().get(1).render().contains("没有回头"), "结尾落点只给最后一段");
    }

    @Test
    void directContextCarriesVerifiedFactsWithoutAuditPayload() {
        var verified = new cn.novel.yonren.domain.novel.model.valobj.plan.FactReference(
                "ITEM", 2, "a".repeat(64), "铜钥匙", "只能开外门", "锁孔只容得下外门钥匙");
        var scene = new ScenePlan("s-1", List.of("沈砚"), "进入内室", "内室落锁",
                List.of(verified), "停在外门", "推进", List.of(), "账房", null);
        var context = new WriterChapterContext(java.util.Set.of(verified), "", "", "", "", "", "", "", "", "");

        String text = WriterContextAssembler.assemble(item(scene), List.of(), context, 6000)
                .sections().get(0).render();

        assertTrue(text.contains("铜钥匙：只能开外门"));
        assertFalse(text.contains("a".repeat(64)));
        assertFalse(text.contains("锁孔只容得下外门钥匙"), "正文要事实语义，不需要审计引文");
    }

    @Test
    void directContextKeepsCharacterCardsPhysicalStateAndPreviousTail() {
        var context = new WriterChapterContext(java.util.Set.of(),
                "【人物决策卡·设计依据而非已发生事实】\n沈砚\n底线：不拿无辜者作交换",
                "【人物此前选择及后果】\n目标：查账；选择：相信管事；后果：账本被转移",
                "【截至上一章末的物理状态】\n- 时间与地点：夜里，药铺",
                "【与本章有关的未收线索】\n- 管事藏起的账本",
                "不得提前确认内鬼身份", "", "", "管事在他身后合上了门。", "");

        var section = WriterContextAssembler.assemble(item(
                scene("s-1", "药铺", "追问账本", "管事承认见过账本")), List.of(), context, 6000)
                .sections().get(0);

        assertTrue(section.render().contains("不拿无辜者作交换"));
        assertTrue(section.render().contains("账本被转移"));
        assertTrue(section.render().contains("夜里，药铺"));
        assertTrue(section.render().contains("管事在他身后合上了门。"));
        assertTrue(section.render().contains("不得提前确认内鬼身份"));
        assertTrue(section.brief().contains("追问账本"), "DIRECT 必须给动态资料选择器非空 Brief");
        assertFalse(section.brief().contains("不得提前确认内鬼身份"));
    }

    @Test
    void legacyPlanFallsBackToKeyEvents() {
        var legacy = ChapterPlanItemEntity.builder().chapterNo(1)
                .keyEvents(List.of("借钥匙", "看见账本")).build();

        var plan = WriterContextAssembler.assemble(legacy, List.of());

        assertEquals(1, plan.sections().size());
        assertTrue(plan.sections().get(0).render().contains("借钥匙"));
        assertTrue(plan.sections().get(0).render().contains("看见账本"));
    }

    @Test
    void omitsPlanningStepRationaleAndChoiceWorkOrderFieldsWhileKeepingActorsAndObligations() {
        var actor = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.ActorInput(
                "苏尘", "保住大考资格", "被刘全刁难", List.of(), List.of("废苗簿"), "以为这是普通青穗草");
        var choice = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.Choice(
                "保名声 vs 保资格", "当众动手", "结下私怨", "刘全怀恨在心");
        var step = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.CausalStep(
                "苏尘", "将病苗登记废苗簿", "刘全", "脸色铁青收手", "硬藏只会引来搜查", List.of(), choice);
        var obligation = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.VerificationObligation(
                "许衡", "苏尘", "辨药", "说清病纹出处", "决定报名资格", null);
        var causality = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality(
                List.of(actor), List.of(step), null, List.of(), List.of(obligation));
        var scene = new ScenePlan("s-1", List.of("苏尘", "刘全"), "交差", "刘全发难",
                List.of(), "刘全收手", "立威", List.of(), "收药棚", null, causality);

        String text = WriterContextAssembler.assemble(item(scene), List.of()).sections().get(0).render();

        assertTrue(text.contains("苏尘想达到：保住大考资格"));
        assertTrue(text.contains("苏尘承受的压力：被刘全刁难"));
        assertTrue(text.contains("苏尘此刻以为：以为这是普通青穗草"));
        assertTrue(text.contains("关键动作：苏尘：将病苗登记废苗簿"));
        assertTrue(text.contains("对方反应：刘全：脸色铁青收手"));
        assertTrue(text.contains("被核验对象：苏尘"));
        assertFalse(text.contains("行动依据"));
        assertFalse(text.contains("价值冲突"));
        assertFalse(text.contains("放弃的做法"));
        assertFalse(text.contains("这个选择的代价"));
        assertFalse(text.contains("预计关系影响"));
        assertFalse(text.contains("节奏与余味"), "不将规划分析用的function总结词喂给写手");
    }

    @Test
    void usesFunctionAsFallbackLandingWhenOutcomeIsBlank() {
        var scene = new ScenePlan("s-1", List.of("苏尘"), "探路", "石梯湿滑",
                List.of(), null, "发现石殿入口", List.of(), "后山", null);

        String text = WriterContextAssembler.assemble(item(scene), List.of()).sections().get(0).render();

        assertTrue(text.contains("这一场结束时的落点：发现石殿入口"));
        assertFalse(text.contains("节奏与余味"));
    }

    @Test
    void ignoresUnresolvedOrMisnamedKnowledgeRefsInDirectMode() {
        var actor = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.ActorInput(
                "苏尘", "验证青芝", "时间紧迫", List.of("尚可青芝"), List.of("青芝"), "丹裂可补");
        var causality = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality(
                List.of(actor), List.of(), null, List.of(), List.of());
        var scene = new ScenePlan("s-1", List.of("苏尘"), "补丹裂", "青芝药粉有限",
                List.of(), "贴补成功", "衔接", List.of(), "廊下", null, causality);

        var plan = WriterContextAssembler.assemble(item(scene), List.of(), WriterChapterContext.empty(), 6000);

        assertEquals(1, plan.sections().size());
        assertTrue(plan.sections().get(0).render().contains("补丹裂"));
    }

    @Test
    void masksSupportingActorPressureAndBeliefWhenPovCharacterIsSet() {
        var povActor = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.ActorInput(
                "苏尘", "保住考核资格", "随时可能被贬", List.of(), List.of("短刀"), "管事暂未起疑",
                null, "直接承认漏登账册，当面请管事给一次补登机会");
        var sideActor = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.ActorInput(
                "周贵", "摸清苏尘来历", "在药园资历浅怕担责", List.of(), List.of("值夜册子"), "草药来历可疑",
                "说话慢半拍爱敲案边，公事口吻藏私心", "假借核查药畦损耗顺路试探，绝不直说想压价");
        var causality = new cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality(
                List.of(povActor, sideActor), List.of(), null, List.of(), List.of());
        var scene = new ScenePlan("s-1", List.of("苏尘", "周贵"), "交药盘问", "气氛僵硬",
                List.of(), "暂压不上报", "对峙", List.of(), "值房", null, causality);
        var context = new WriterChapterContext(java.util.Set.of(), "", "", "", "", "", "", "", "", "", "苏尘");

        String text = WriterContextAssembler.assemble(item(scene), List.of(), context, 6000)
                .sections().get(0).render();

        assertTrue(text.contains("苏尘承受的压力：随时可能被贬"));
        assertTrue(text.contains("苏尘此刻以为：管事暂未起疑"));
        assertFalse(text.contains("周贵承受的压力"));
        assertFalse(text.contains("周贵此刻以为"));
        assertTrue(text.contains("周贵外在意图（借动作、眼神与话锋体现）：摸清苏尘来历"));
        assertTrue(text.contains("周贵戏底暗线（供拿捏潜台词、停顿与话中带刺；限知视角下通过外在言行演出来，不写成全知内心独白或静态简历）：压力：在药园资历浅怕担责；此刻以为：草药来历可疑"));
        assertTrue(text.contains("周贵本场腔调或小动作（写作参考）：说话慢半拍爱敲案边，公事口吻藏私心"));
        assertTrue(text.contains("周贵当场说话或应对方式（写作参考）：假借核查药畦损耗顺路试探，绝不直说想压价"));
        assertTrue(text.contains("苏尘当场说话或应对方式（写作参考）：直接承认漏登账册，当面请管事给一次补登机会"));
        assertTrue(text.contains("周贵可用资源：值夜册子"));
    }
}
