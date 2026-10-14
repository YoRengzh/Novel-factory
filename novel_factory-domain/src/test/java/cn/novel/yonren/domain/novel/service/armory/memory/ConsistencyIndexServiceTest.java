package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsistencyIndexServiceTest {
    private final ConsistencyIndexService service = new ConsistencyIndexService();

    @Test
    void rebuildProjectsTimelineInjuryAndMechanismWithoutChangingThreeLedgers() {
        StoryVO story = new StoryVO();
        StoryVO.StoryFeatures features = new StoryVO.StoryFeatures();
        features.setHasCheatMechanism(true);
        features.setCheatMechanismName("剑意提取系统");
        story.setFeatures(features);
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder()
                .chapterNo(3).summary("夜探废矿").timePoint("寒潮第三日")
                .characterStates(List.of(new ChapterSummaryEntity.StateEntry("林越", "左肩重伤，包扎后行动受限", "正文证据")))
                .itemStates(List.of(new ChapterSummaryEntity.StateEntry("剑意提取系统", "冷却中", "系统提示")))
                .cheatMechanismUsed(true)
                .build();

        ConsistencyIndexEntity index = service.rebuild(List.of(summary), story);

        assertEquals(1, index.getTimeline().size());
        assertEquals("寒潮第三日", index.getTimeline().get(0).getStoryTime());
        assertEquals(1, index.getInjuries().size());
        assertEquals("重", index.getInjuries().get(0).getSeverity());
        assertEquals(1, index.getMechanisms().size());
        assertTrue(service.cheatRuleEnabled(story));
    }

    @Test
    void cheatRuleIsDisabledWhenFeatureMissing() {
        StoryVO story = new StoryVO();
        assertFalse(service.cheatRuleEnabled(story));
        assertTrue(service.rebuild(List.of(), story).getMechanisms().isEmpty());
    }

    @Test
    void renderPromptIncludesConfirmedIndexesOnly() {
        ConsistencyIndexEntity index = ConsistencyIndexEntity.builder()
                .timeline(List.of(new ConsistencyIndexEntity.TimelineEntry("进入北境", 4, "寒潮第三日", null)))
                .build();
        assertTrue(service.renderPrompt(index).contains("寒潮第三日"));
        assertEquals("", service.renderPrompt(ConsistencyIndexEntity.builder().build()));
    }

    @Test
    void mechanismIntervalOnlyAppliesWhenExplicitlyEnabled() {
        StoryVO disabled = new StoryVO();
        assertTrue(service.mechanismUsageIssues(List.of(), disabled, 3).isEmpty());

        StoryVO enabled = new StoryVO();
        StoryVO.StoryFeatures features = new StoryVO.StoryFeatures();
        features.setHasCheatMechanism(true);
        features.setCheatMechanismName("剑意提取系统");
        features.setCheatUsageInterval(3);
        enabled.setFeatures(features);
        assertEquals(1, service.mechanismUsageIssues(List.of(), enabled, 3).size());
        assertTrue(service.mechanismUsageIssues(List.of(), enabled, 4).isEmpty());
    }

    @Test
    void mechanismUseResetsThreeChapterInterval() {
        StoryVO story = new StoryVO();
        StoryVO.StoryFeatures features = new StoryVO.StoryFeatures();
        features.setHasCheatMechanism(true);
        story.setFeatures(features);
        List<ChapterSummaryEntity> summaries = List.of(
                ChapterSummaryEntity.builder().chapterNo(2).cheatMechanismUsed(true).build());
        assertTrue(service.mechanismUsageIssues(summaries, story, 4).isEmpty());
        assertEquals(1, service.mechanismUsageIssues(summaries, story, 5).size());
    }

    @Test
    void mechanismIntervalOne_disablesCheckInsteadOfFlaggingEveryChapter() {
        // interval=1 时 gap % 1 恒为 0 ⇒ 未使用的章之后**章章命中**，
        // 写手陷入"必须用金手指"与"金手指没额度"互相矛盾的要求，两轮修订必然耗尽。
        // 实测 5 章 5 条 BLOCKING 全部出自这里。间隔 1 表达的是"规划层要求每章都用"，
        // 不该由机械门禁逐字兜底——故直接停用该检查，交给规划层与审校。
        StoryVO story = new StoryVO();
        StoryVO.StoryFeatures features = new StoryVO.StoryFeatures();
        features.setHasCheatMechanism(true);
        features.setCheatMechanismName("签到系统");
        features.setCheatUsageInterval(1);
        story.setFeatures(features);

        assertTrue(service.mechanismUsageIssues(List.of(), story, 1).isEmpty());
        assertTrue(service.mechanismUsageIssues(List.of(), story, 2).isEmpty());
        assertTrue(service.mechanismUsageIssues(List.of(), story, 5).isEmpty());
        assertTrue(service.mechanismUsageIssues(null, story, 5).isEmpty());
    }

    // ---------- 机制门禁：改为真 BLOCKING 前的两处判据修正 ----------

    @Test
    void mechanismUsageIssueWaivedWhenChapterItselfMentionsIt() {
        // 检查已上移到质量门内、摘要落盘之前 ⇒ 必须回读正文，否则"本章用了但摘要还没生成"会误判超期
        StoryVO story = mechanismStory("剑意提取系统", 3);
        String used = "他催动剑意提取系统，把残剑里的剑意一寸寸抽了出来。";
        String unused = "他只是静静地站着，看着窗外的雪。";

        assertTrue(service.mechanismUsageIssues(List.of(), story, 3, used).isEmpty(),
                "本章正文已提及机制，不应判超期未使用");
        assertEquals(1, service.mechanismUsageIssues(List.of(), story, 3, unused).size(),
                "本章确实没用，才该报");
    }

    @Test
    void longConfiguredMechanismNameAcceptsConservativeAliasMention() {
        StoryVO story = mechanismStory("前世记忆与幼儿大脑黄金期", 1);

        assertTrue(service.mentionsMechanism("凭着前世记忆，他提前避开了那场争执。", story),
                "长机制名应允许由连接词拆出的四字以上语义片段命中");
        assertTrue(service.mechanismUsageIssues(List.of(), story, 1,
                "幼儿大脑黄金期让他学得比同龄人更快。").isEmpty());
        assertFalse(service.mentionsMechanism("他回忆起昨天在幼儿园见过的老师。", story),
                "不得退化成任意二字或普通词命中");
    }

    @Test
    void describesMechanismNeedsLongParagraphWithPrincipleKeyword() {
        StoryVO story = mechanismStory("剑意提取系统", 3);
        String withPrinciple = "剑意提取系统的运作原理是把剑意拆成可储存的片段，" + "字".repeat(40);
        String nameOnly = "他拿出剑意提取系统看了一眼。" + "字".repeat(60);

        assertTrue(service.describesMechanism(withPrinciple, story));
        assertFalse(service.describesMechanism(nameOnly, story), "只提名字、不讲原理不算描述");
        assertFalse(service.describesMechanism("", story));
    }

    @Test
    void mechanismDescriptionIsInertBeforeQuotaFilled() {
        // 额度 = MAX_MECHANISM_DESCRIPTIONS(2)：此前只描述过 1 次时，本章再描述仍合法
        StoryVO story = mechanismStory("剑意提取系统", 3);
        List<ChapterSummaryEntity> onePrior = List.of(described(22));
        List<ChapterSummaryEntity> twoPrior = List.of(described(22), described(26));

        assertTrue(service.mechanismDescriptionIssues(onePrior, story, 26, true).isEmpty(),
                "此前只描述过 1 次 → 本章是第 2 次，仍在『首次建立 + 关键升级』额度内");
        assertEquals(1, service.mechanismDescriptionIssues(twoPrior, story, 28, true).size(),
                "此前已描述 2 次 → 本章第 3 次描述即违规");
    }

    @Test
    void mechanismDescriptionFiresOnlyOnTheChapterThatExceedsQuota() {
        // 回归：原实现每章用全量 contents 重算累计值，第 3 个描述章出现后**其后每章**都报
        // （162 章样本实测连报 135 章 = 83.3%）。增量口径只应报"新引入描述且额度已满"的那几章。
        StoryVO story = mechanismStory("剑意提取系统", 3);
        List<ChapterSummaryEntity> beforeThird = List.of(described(22), described(26));

        assertEquals(1, service.mechanismDescriptionIssues(beforeThird, story, 28, true).size(),
                "第 3 个描述章（额度已满 + 本章新描述）应报");
        assertTrue(service.mechanismDescriptionIssues(beforeThird, story, 29, false).isEmpty(),
                "第 29 章若自身没有描述段落，不得因累计值 > 2 而连坐——这正是原实现的缺陷");

        List<ChapterSummaryEntity> afterThird = List.of(described(22), described(26), described(28));
        assertEquals(1, service.mechanismDescriptionIssues(afterThird, story, 29, true).size(),
                "第 29 章自己又描述了一次，应报");
    }

    @Test
    void mechanismDescriptionIsInertWhenCheatRuleDisabled() {
        StoryVO disabled = new StoryVO();
        List<ChapterSummaryEntity> summaries = List.of(described(1), described(2));
        assertTrue(service.mechanismDescriptionIssues(summaries, disabled, 3, true).isEmpty());
        assertTrue(service.mechanismIssues(disabled, summaries, 3, "代码视界").isEmpty());
    }

    private StoryVO mechanismStory(String name, int interval) {
        StoryVO story = new StoryVO();
        StoryVO.StoryFeatures features = new StoryVO.StoryFeatures();
        features.setHasCheatMechanism(true);
        features.setCheatMechanismName(name);
        features.setCheatUsageInterval(interval);
        story.setFeatures(features);
        return story;
    }

    private ChapterSummaryEntity described(int chapterNo) {
        return ChapterSummaryEntity.builder()
                .chapterNo(chapterNo).mechanismDescribed(true)
                .cheatMechanismUsed(true)
                .build();
    }

    @Test
    void rebuildProjectsVerifiedTermAndNumberFacts() {
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder().chapterNo(8)
                .consistencyFacts(List.of(
                        new ChapterSummaryEntity.ConsistencyFact("TERM", "天门关", "天门城", null, "证据"),
                        new ChapterSummaryEntity.ConsistencyFact("NUMBER", "北境守军", "三千人", "北境", "证据")))
                .build();
        ConsistencyIndexEntity index = service.rebuild(List.of(summary), new StoryVO());
        assertEquals("天门关", index.getTerms().get(0).getCanonical());
        assertEquals("三千人", index.getNumbers().get(0).getValue());
        assertTrue(service.renderPrompt(index).contains("关键数字"));
    }

    @Test
    void renderTimeAnchor_rendersLatestStoryTimeAndAgeWithSource() {
        // 时序锚是把"阶段锚"像境界一样锁定进每一路 prompt 的止血点
        //（首发事故为"十一个月婴儿写数论证明"，重跑又绕道为"涂鸦=答案/观察者附会"）
        ChapterSummaryEntity ch16 = ChapterSummaryEntity.builder().chapterNo(16).timePoint("正月十六上午")
                .consistencyFacts(List.of(new ChapterSummaryEntity.ConsistencyFact(
                        "NUMBER", "某角色月龄", "十一个月", "角色丙", "证据")))
                .build();
        ChapterSummaryEntity ch25 = ChapterSummaryEntity.builder().chapterNo(25).timePoint("正月某日下午").build();

        String anchor = ConsistencyIndexService.renderTimeAnchor(List.of(ch16, ch25));

        assertTrue(anchor.contains("【时序锚】"));
        assertTrue(anchor.contains("正月某日下午"), "取最新时间点");
        assertTrue(anchor.contains("角色丙年龄：十一个月"));
        assertTrue(anchor.contains("第16章摘要记录"), "必须标注年龄来源章，供按故事时间推算");
        assertTrue(anchor.contains("认知可以超前"), "认知/媒介两分法必须写进锚里");
        assertTrue(anchor.contains("媒介不能超前"));
        // 通用化的间接展示禁令与解读纪律：载体换了本质没变，解读须累积且留不确定
        assertTrue(anchor.contains("可被他人在事后解读出具体含义"), "可解码载体＝能力展示，必须同禁");
        assertTrue(anchor.contains("单次观察"), "观察者不得从单次观察就坐实具体知识");
        assertTrue(anchor.contains("多次重复"), "解读须建立在多次重复之上");
    }

    @Test
    void renderTimeAnchor_returnsEmptyWithoutAgeFact() {
        // 无年龄事实时绝不编造一个年龄塞进 prompt；由装配器整块过滤
        ChapterSummaryEntity onlyTime = ChapterSummaryEntity.builder().chapterNo(3).timePoint("寒潮第三日").build();
        assertEquals("", ConsistencyIndexService.renderTimeAnchor(List.of(onlyTime)));
        assertEquals("", ConsistencyIndexService.renderTimeAnchor(null));
        assertEquals("", ConsistencyIndexService.renderTimeAnchor(List.of()));
    }

    @Test
    void renderSettingsAgeAnchor_extractsChildAgeBoundToStageWord() {
        // 新书首段无摘要 → 摘要锚为空，需用设定兜底锚顶上（否则第一章/首段计划零年龄约束）
        String anchor = ConsistencyIndexService.renderSettingsAgeAnchor(
                "2002年的平行现实世界。主角角色丙在2002年以4岁幼童的身份苏醒。",
                "角色丙，灵魂是前世科技公司创始人",
                "化作江南旧巷子里一个4岁幼童。");

        assertTrue(anchor.contains("【时序锚】"));
        assertTrue(anchor.contains("角色丙起始年龄：4岁"), "人名取自主人公字段头部");
        assertTrue(anchor.contains("来自故事设定"));
        assertTrue(anchor.contains("认知可以超前"), "必须共用摘要锚的硬约束正文");
        assertTrue(anchor.contains("媒介不能超前"));
    }

    @Test
    void renderSettingsAgeAnchor_ignoresAdultAges() {
        // 命中从严：成人年龄（前世38岁/38岁时）不得误锚成当前年龄
        assertEquals("", ConsistencyIndexService.renderSettingsAgeAnchor(
                "主角前世是38岁的创始人", "他38岁时公司上市", "中年重生"));
        assertEquals("", ConsistencyIndexService.renderSettingsAgeAnchor(null, null, null));
        assertEquals("", ConsistencyIndexService.renderSettingsAgeAnchor("", "", ""));
    }

    @Test
    void renderSettingsAgeAnchor_supportsChineseNumeralAndRejectsOutOfRange() {
        assertTrue(ConsistencyIndexService.renderSettingsAgeAnchor("以一个四岁孩童的身份回到过去", null, null)
                .contains("起始年龄：4岁"));
        assertTrue(ConsistencyIndexService.renderSettingsAgeAnchor("以年仅十二岁少年的身份", null, null)
                .contains("起始年龄：12岁"));
        assertEquals("", ConsistencyIndexService.renderSettingsAgeAnchor("年方四十岁的中年人", null, null),
                "超出入锚区间（1-18）不锚");
    }

    @Test
    void renderSettingsAgeAnchorIfNoSummaries_onlyWhenNoSummaries() {
        assertEquals("", ConsistencyIndexService.renderSettingsAgeAnchorIfNoSummaries(
                List.of(ChapterSummaryEntity.builder().chapterNo(1).build()),
                "以4岁幼童的身份苏醒", null, null), "有摘要时不得回退设定锚（防起始年龄钉死中期）");
        assertTrue(ConsistencyIndexService.renderSettingsAgeAnchorIfNoSummaries(
                List.of(), "以4岁幼童的身份苏醒", null, null).contains("起始年龄：4岁"));
        assertTrue(ConsistencyIndexService.renderSettingsAgeAnchorIfNoSummaries(
                null, "以4岁幼童的身份苏醒", null, null).contains("起始年龄：4岁"));
    }

    @Test
    void renderTimeAnchor_picksLatestAgeFactAndFallsBackToSubjectName() {
        ChapterSummaryEntity ch10 = ChapterSummaryEntity.builder().chapterNo(10)
                .consistencyFacts(List.of(new ChapterSummaryEntity.ConsistencyFact(
                        "NUMBER", "某角色月龄", "十个月", "角色丙", "证据")))
                .build();
        // scope 缺失：人物名从 subject 去掉年龄后缀兜底（"角色丙年龄"→"某角色"）
        ChapterSummaryEntity ch16 = ChapterSummaryEntity.builder().chapterNo(16)
                .consistencyFacts(List.of(new ChapterSummaryEntity.ConsistencyFact(
                        "NUMBER", "角色丙年龄", "十一个月", null, "证据")))
                .build();

        String anchor = ConsistencyIndexService.renderTimeAnchor(List.of(ch16, ch10));

        assertTrue(anchor.contains("十一个月"), "取章号最大的年龄事实");
        assertFalse(anchor.contains("十个月"));
        assertTrue(anchor.contains("角色丙年龄："), "scope 缺失时用 subject 去后缀兜底");
    }

    @Test
    void renderTimeAnchor_ignoresNonNumberFacts() {
        // 只有 NUMBER 类型的年龄事实才算锚点：TERM 里出现"月龄"字样不应被误采
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder().chapterNo(5)
                .consistencyFacts(List.of(new ChapterSummaryEntity.ConsistencyFact(
                        "TERM", "月龄", "十个月", "角色丙", "证据")))
                .build();
        assertEquals("", ConsistencyIndexService.renderTimeAnchor(List.of(summary)));
    }

    @Test
    void rebuildProjectsRelationFactsWithUpsertSemantics() {
        // #8 第一步：RELATION 事实进入关系台账——同 pair 覆盖旧值（台账回答"现在什么关系"），
        // 首见章号保留在 firstChapter，lastChapter 随更新推进
        ChapterSummaryEntity first = ChapterSummaryEntity.builder().chapterNo(10)
                .consistencyFacts(List.of(new ChapterSummaryEntity.ConsistencyFact(
                        "RELATION", "陆沉与墨璇玑", "结盟", null, "正文证据一")))
                .build();
        ChapterSummaryEntity second = ChapterSummaryEntity.builder().chapterNo(20)
                .consistencyFacts(List.of(new ChapterSummaryEntity.ConsistencyFact(
                        "RELATION", "陆沉与墨璇玑", "决裂", null, "正文证据二")))
                .build();

        ConsistencyIndexEntity index = service.rebuild(List.of(first, second), new StoryVO());

        assertEquals(1, index.getRelations().size(), "同 pair 只保留一条（当前态）");
        ConsistencyIndexEntity.RelationEntry r = index.getRelations().get(0);
        assertEquals("陆沉与墨璇玑", r.getPair());
        assertEquals("决裂", r.getRelation(), "应为最近一次的关系态");
        assertEquals(10, r.getFirstChapter());
        assertEquals(20, r.getLastChapter());
        assertTrue(service.renderPrompt(index).contains("关系台账"));
    }
}
