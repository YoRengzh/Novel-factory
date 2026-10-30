package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LedgerEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆组装测试：三账本滚动合并、活跃度过滤、伏笔账分级渲染与回收抵扣、偏差警示、尾部段落截取
 */
class ChapterMemoryServiceTest {

    private final ChapterMemoryService service = new ChapterMemoryService(new ForeshadowPriorityService());

    /**
     * 时间欠账前缀的两处常量必须同值（2026-10-26）：{@code ChapterWorker#TIME_ARREARS_PREFIX} 注入、
     * {@code ChapterMemoryService#TIME_ARREARS_CONFLICT_PREFIX} 分组——它们是刻意解耦的字符串常量
     * （避免跨服务编译期依赖），同值约束没有任何编译器兜底，只能由本测试锁定。
     * 值不一致的后果是欠账条目被当成账本冲突渲染，写手收到"以账本为准"的错误修正指令。
     */
    @Test
    void timeArrearsPrefix_constantsMustMatchAcrossServices() {
        assertEquals(cn.novel.yonren.domain.novel.service.armory.worker.ChapterWorker.TIME_ARREARS_PREFIX,
                ChapterMemoryService.TIME_ARREARS_CONFLICT_PREFIX,
                "两处前缀不同值会让欠账条目落进账本冲突导语，修正指令自相矛盾");
    }

    /** 欠账条目与账本冲突分开导语：前缀条目进【时间目标未达成】，其余进【上章偏差警示】 */
    @Test
    void conflicts_timeArrearsRenderUnderOwnHeader() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));

        String prefix = service.buildMemoryPrefix(summaries, null,
                List.of("【时间欠账】上一章时间推进未达标，本章必须补足时间过渡"), null);

        // 2026-10-29：导语由「必须补足」改为「时间线提示」，不再强制推进
        assertTrue(prefix.contains("【时间线提示】"), "前缀条目必须走独立导语");
        assertTrue(prefix.contains("时间的基准是【时序锚】"), "欠账的修正基准是时序锚而非账本");
        assertFalse(prefix.contains("上一章正文存在与账本冲突的描述"),
                "只有欠账时不得渲染账本冲突导语（避免错误指令：以账本为准）");
    }

    @Test
    void ledger_mergesByChapterOrderAndOverwrites() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(2, states(state("小陈", "炼气一层，藏身外门石缝"), state("赵阔", "派人监视小陈"))),
                summary(1, states(state("小陈", "丹田被废，重伤昏迷"))),
                summary(3, states(state("小陈", "炼气一层，大比连胜六场"), state("许衡", "外门弟子，被小陈击败"))));

        List<LedgerEntry> ledger = service.buildCharacterLedger(summaries);

        assertEquals(3, ledger.size());
        LedgerEntry lin = byName(ledger, "小陈");
        // 后章覆盖前章状态；首登场章保留第 1 章，最近更新章为第 3 章
        assertEquals("炼气一层，大比连胜六场", lin.getStatus());
        assertEquals(1, lin.getFirstChapterNo());
        assertEquals(3, lin.getLastChapterNo());
        LedgerEntry zhao = byName(ledger, "赵阔");
        assertEquals(2, zhao.getFirstChapterNo());
        // 第 3 章未更新赵阔，状态停留在第 2 章
        assertEquals("派人监视小陈", zhao.getStatus());
    }

    @Test
    void ledger_skipsBlankEntries() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("", "无名状态"), state("小陈", "重伤"), null)));

        List<LedgerEntry> ledger = service.buildCharacterLedger(summaries);

        assertEquals(1, ledger.size());
        assertEquals("小陈", ledger.get(0).getName());
    }

    @Test
    void ledger_itemAndFactionExtractors() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));
        summaries.get(0).setItemStates(new ArrayList<>(Arrays.asList(
                new ChapterSummaryEntity.StateEntry("古物", "首次显威，异动续命", null))));
        summaries.get(0).setFactionStates(new ArrayList<>(Arrays.asList(
                new ChapterSummaryEntity.StateEntry("幽冥谷", "暗手伸向外门", null))));

        List<LedgerEntry> items = service.buildLedger(summaries, ChapterSummaryEntity::getItemStates);
        List<LedgerEntry> factions = service.buildLedger(summaries, ChapterSummaryEntity::getFactionStates);
        List<LedgerEntry> characters = service.buildCharacterLedger(summaries);

        assertEquals(1, items.size());
        assertEquals("古物", items.get(0).getName());
        assertEquals(1, factions.size());
        assertEquals(1, characters.size());
    }

    @Test
    void ledger_emptySummaries() {
        assertTrue(service.buildCharacterLedger(null).isEmpty());
        assertTrue(service.buildLedger(List.of(), ChapterSummaryEntity::getItemStates).isEmpty());
    }

    @Test
    void ledger_recordsPrevStatusOnRealTransitionsOnly() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("小陈", "重伤"))),
                summary(3, states(state("小陈", "轻伤"))),
                summary(5, states(state("小陈", "轻伤"))),
                summary(7, states(state("小陈", "痊愈"))));

        LedgerEntry lin = byName(service.buildCharacterLedger(summaries), "小陈");

        assertEquals("痊愈", lin.getStatus());
        // 第5章同值重复更新不得冲掉前次变化记录
        assertEquals("轻伤", lin.getPrevStatus());
        assertEquals(7, lin.getLastChapterNo());
    }

    @Test
    void ledger_firstAppearanceHasNoTrajectory() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));

        LedgerEntry lin = byName(service.buildCharacterLedger(summaries), "小陈");

        assertNull(lin.getPrevStatus());
        assertEquals("", ChapterMemoryService.trajectorySuffix(lin));
    }

    @Test
    void ledger_carriesEvidenceFromLatestState() {
        // 证据随状态滚动：最近一次更新的证据成为账本条目的当前证据
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(new ChapterSummaryEntity.StateEntry("小陈", "重伤", "小陈咬破舌尖，强行催动古物"))),
                summary(3, states(new ChapterSummaryEntity.StateEntry("小陈", "痊愈", "他咳出一口血，缓缓睁眼"))));

        LedgerEntry lin = byName(service.buildCharacterLedger(summaries), "小陈");

        assertEquals("痊愈", lin.getStatus());
        assertEquals("他咳出一口血，缓缓睁眼", lin.getEvidence());
        assertEquals(3, lin.getLastChapterNo());
    }

    @Test
    void ledger_deathReversalFlagsSuspectAndRendersEvidence() {
        // 死亡→非死亡且无明确复活描写：reversalSuspect 置位，渲染追加存疑标记与证据引用
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(new ChapterSummaryEntity.StateEntry("王五", "重伤不治身亡", "他咽下最后一口气"))),
                summary(3, states(new ChapterSummaryEntity.StateEntry("王五", "伤势痊愈", "王五活动了一下手臂"))));

        LedgerEntry wang = byName(service.buildCharacterLedger(summaries), "王五");
        assertTrue(wang.isReversalSuspect());

        String prefix = service.buildMemoryPrefix(summaries, null, null);
        assertTrue(prefix.contains("【复活存疑·待人工确认】"));
        assertTrue(prefix.contains("〔证据：王五活动了一下手臂〕"));
    }

    @Test
    void ledger_explicitResurrectionIsNotFlagged() {
        // 有明确复活描写：合法反转剧情，不标记存疑
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(new ChapterSummaryEntity.StateEntry("王五", "重伤不治身亡", null))),
                summary(3, states(new ChapterSummaryEntity.StateEntry("王五", "复活归来，伤势尽复", null))));

        LedgerEntry wang = byName(service.buildCharacterLedger(summaries), "王五");

        assertFalse(wang.isReversalSuspect());
    }

    @Test
    void prefix_rendersSourceChapterForStableEntries() {
        // 稳定条目（**跨章出现但状态未变**）标明状态出自哪一章。
        // 必须跨两章：只出现一次的条目现已降为"仅列名"，不再带来源章
        //（见 prefix_singleMentionEntryRendersNameOnly）——原 fixture 只有一章，
        // 实际上测的是"单次出现"而非"稳定"，属于命名与数据不符
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("小陈", "重伤"))),
                summary(2, states(state("小陈", "重伤"))));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        assertTrue(prefix.contains("（来源：第2章）"), "稳定条目应标明最近一次提及的章号");
    }

    @Test
    void factsAsOf_truncatesSummariesBeforeChapter() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("小陈", "重伤"))),
                summary(2, states(state("小陈", "轻伤"))),
                summary(3, states(state("小陈", "痊愈"))));

        List<LedgerEntry> asOfTwo = service.factsAsOf(summaries, 3, ChapterSummaryEntity::getCharacterStates);

        assertEquals(1, asOfTwo.size());
        assertEquals("轻伤", asOfTwo.get(0).getStatus());
        assertTrue(service.factsAsOf(summaries, 1, ChapterSummaryEntity::getCharacterStates).isEmpty());
    }

    @Test
    void prefix_rendersTransitionTrajectory() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("小陈", "重伤"))),
                summary(7, states(state("小陈", "痊愈"))));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        assertTrue(prefix.contains("小陈（第1章登场）：痊愈（第7章变化：重伤 → 痊愈）"));
    }

    @Test
    void foreshadowContext_capsToNewestEntries() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT + 5; ch++) {
            summaries.add(summary(ch, states(state("小陈", "炼气"))));
            summaries.get(ch - 1).setForeshadowingNew(List.of("伏笔" + ch));
        }

        List<String> context = service.buildForeshadowContextList(
                summaries, ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT);

        assertEquals(ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT, context.size());
        // 新→旧优先：保留最新 20 条，最早的 5 条被挤出
        assertTrue(context.contains("伏笔" + (ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT + 5)));
        assertTrue(!context.contains("伏笔1"));
        assertTrue(!context.contains("伏笔5"));
    }

    /**
     * 逾期项必须保证在登记清单里。
     *
     * <p>此前是纯"最新 N 条"，于是**埋得越早的线越看不见**；而摘要 prompt 要求回收条目
     * "逐字沿用账中描述"——一条掉出清单的线，模型根本无从登记，于是它永远关不掉账、
     * 被后续每一段反复要求回收。老书实测埋在第 0 章的那条早就掉出了最新 20 条之外，
     * 体检同时报出「清账静默兑现」高占比，两者是同一个缺口的两面。
     */
    @Test
    void foreshadowContext_reservesSlotsForOverdueEntries() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT + 5; ch++) {
            summaries.add(summary(ch, states(state("小陈", "炼气"))));
            summaries.get(ch - 1).setForeshadowingNew(List.of("伏笔" + ch));
        }
        // 第 1 章那条：声明了兑现义务、带排期且回收章早已过（逾期），位置又远在最旧的一端
        ChapterSummaryEntity.SeedEntry overdueSeed =
                new ChapterSummaryEntity.SeedEntry("伏笔1", null, 4);
        overdueSeed.setResolvable(Boolean.TRUE);
        overdueSeed.setScheduledPayoffChapter(5);
        summaries.get(0).setForeshadowSeeds(new ArrayList<>(List.of(overdueSeed)));

        List<String> context = service.buildForeshadowContextList(
                summaries, ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT);

        assertEquals(ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT, context.size(), "总量仍封顶");
        assertTrue(context.contains("伏笔1"),
                "逾期项必须占到位（否则最早埋的线永远看不见、也就永远关不掉账）：" + context);
        assertTrue(context.contains("伏笔" + (ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT + 5)),
                "最新项照常保留：" + context);
    }

    @Test
    void auditForeshadowListExcludesBreakerButRegistrationKeeps() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= 15; ch++) {
            summaries.add(summary(ch, states(state("小陈", "炼气"))));
        }
        // 默认 3 档：第 1 章埋的滞留 14 章 → 30 + 70 = 100 熔断；第 15 章新埋
        summaries.get(0).setForeshadowingNew(List.of("陈年伏笔"));
        summaries.get(14).setForeshadowingNew(List.of("新鲜伏笔"));

        List<String> audit = service.buildAuditForeshadowList(summaries);

        // 审校清单剔除未填（不再构成回收义务），登记清单保留（自然触达仍可核销出账）
        assertFalse(audit.contains("陈年伏笔"));
        assertTrue(audit.contains("新鲜伏笔"));
        assertTrue(service.buildForeshadowContextList(summaries, ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT)
                .contains("陈年伏笔"));
    }

    @Test
    void prefix_dormantNamesCappedToRecentTwenty() {
        List<ChapterSummaryEntity.StateEntry> crowd = new ArrayList<>();
        for (int i = 1; i <= ChapterMemoryService.MAX_DORMANT_NAMES + 5; i++) {
            crowd.add(state("路人" + i, "待命"));
        }
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, crowd.toArray(new ChapterSummaryEntity.StateEntry[0])),
                summary(25, states(state("小陈", "闭关修炼"))));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        // 沉寂名单只展示最近更新的前 20 个，其余封存计数
        assertTrue(prefix.contains("路人" + ChapterMemoryService.MAX_DORMANT_NAMES));
        assertTrue(!prefix.contains("路人" + (ChapterMemoryService.MAX_DORMANT_NAMES + 1)));
        assertTrue(prefix.contains("另有 5 个更早条目已封存"));
    }

    @Test
    void prefix_containsAllLayers() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("小陈", "丹田被废"))),
                summary(2, states(state("小陈", "炼气一层"))));
        summaries.get(0).setForeshadowingNew(List.of("赵阔的灰黑劲气来路不明"));
        summaries.get(1).setForeshadowingResolved(List.of("古物是否为废铁"));
        summaries.get(1).setTimePoint("外门大比当日深夜");

        String prefix = service.buildMemoryPrefix(summaries, "夜色深沉，小陈握紧了古物。", null);

        assertTrue(prefix.contains("前情记忆"));
        assertTrue(prefix.contains("第1章《废丹田》：小陈被赵阔废去丹田。\n"));
        assertTrue(prefix.contains("第2章《古物奇谈》：小陈踏入炼气一层。\n"));
        assertTrue(prefix.contains("最新时点：外门大比当日深夜"));
        assertTrue(prefix.contains("角色账本"));
        assertTrue(prefix.contains("小陈（第1章登场）：炼气一层"));
        // 新埋减去已回收
        assertTrue(prefix.contains("赵阔的灰黑劲气来路不明"));
        assertTrue(prefix.contains("伏笔账"));
        // 上一章结尾原文
        assertTrue(prefix.contains("上一章结尾原文"));
        assertTrue(prefix.contains("夜色深沉，小陈握紧了古物。"));
    }

    @Test
    void prefix_rendersItemAndFactionLedgers() {
        // 物品须跨两章出现（状态未变）才完整渲染；只出现一次的条目已降为仅列名，
        // 故原单章 fixture 不再适用
        List<ChapterSummaryEntity> summaries = new ArrayList<>(List.of(
                summary(1, states(state("小陈", "重伤"))),
                summary(2, states(state("小陈", "轻伤")))));
        summaries.get(0).setItemStates(new ArrayList<>(Arrays.asList(
                new ChapterSummaryEntity.StateEntry("古物", "异动显威", null))));
        summaries.get(1).setItemStates(new ArrayList<>(Arrays.asList(
                new ChapterSummaryEntity.StateEntry("古物", "异动显威", null))));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        assertTrue(prefix.contains("物品账本"));
        assertTrue(prefix.contains("古物（第1章登场）：异动显威"));
        // 无势力条目则不渲染该节
        assertTrue(!prefix.contains("势力账本"));
    }

    @Test
    void prefix_activeWindowSplitsLedger() {
        // 第 1 章登场的角色，最近更新在第 20 章；最新章为第 20 章 → 窗口 [11,20]，第 1 章角色沉寂
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(summary(1, states(state("执事", "冷漠旁观"), state("小陈", "重伤"))));
        for (int no = 2; no <= 20; no++) {
            summaries.add(summary(no, states(state("小陈", "炼气" + no + "层推进中"))));
        }

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        // 小陈最近更新仍在窗口内，逐条列出；执事沉入沉淀名单，只留名字不列状态
        assertTrue(prefix.contains("小陈（第1章登场）：炼气20层推进中"));
        assertTrue(prefix.contains("早期沉寂条目：执事(第1章)"));
        assertTrue(!prefix.contains("执事（第1章登场）"));
    }

    @Test
    void prefix_foreshadowFreshZoneRendersLatestPlantsWithExcerpt() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= ChapterMemoryService.MAX_FRESH_LINES + 5; i++) {
            many.add("伏笔" + i);
        }
        summaries.get(0).setForeshadowingNew(many);

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        // 新埋区（上一章埋设）统一（上章新埋）前缀，上限 3 条带原文；溢出条目仍可见 去章号后前缀合并）
        assertTrue(prefix.contains("（上章新埋）伏笔1"));
        assertTrue(prefix.contains("（上章新埋）伏笔3"));
        assertTrue(prefix.contains("（上章新埋）伏笔4"), "溢出条目仍应可见");
        assertTrue(prefix.contains("（上章新埋）伏笔8"));
        assertTrue(!prefix.contains("沉入背景"));
    }

    @Test
    void prefix_foreshadowHardOverflowFallsBackToContentLines() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(summary(4, states(state("小陈", "炼气"))));
        summaries.add(summary(12, states(state("小陈", "炼气"))));
        List<String> many = new ArrayList<>();
        List<ChapterSummaryEntity.SeedEntry> seeds = new ArrayList<>();
        for (int i = 1; i <= ChapterMemoryService.MAX_HARD_LINES + 1; i++) {
            many.add("伏笔" + i);
            seeds.add(new ChapterSummaryEntity.SeedEntry("伏笔" + i, null, 4));
        }
        summaries.get(0).setForeshadowingNew(many);
        summaries.get(0).setForeshadowSeeds(seeds);

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        // 6 条 4 分伏笔同期滞留 8 章（80 分）全部进硬区，展示上限 5 条，第 6 条回落为内容行仍可见
        assertTrue(prefix.contains("【硬】伏笔1"));
        assertTrue(prefix.contains("【硬】伏笔5"));
        assertTrue(!prefix.contains("【硬】伏笔6"));
        assertTrue(prefix.contains("（更早埋设）伏笔6"));
    }

    @Test
    void prefix_foreshadowSpineNeverBreaks() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= 12; ch++) {
            summaries.add(summary(ch, states(state("小陈", "炼气"))));
        }
        // 5 分主线谜题第 1 章埋，第 12 章时 50 + 55 = 105：超熔断线但保持硬级可见
        summaries.get(0).setForeshadowingNew(List.of("主线核心谜题"));
        summaries.get(0).setForeshadowSeeds(List.of(
                new ChapterSummaryEntity.SeedEntry("主线核心谜题", null, 5)));

        String prefix = service.buildMemoryPrefix(summaries, null, null);
        List<String> audit = service.buildAuditForeshadowList(summaries);

        assertTrue(prefix.contains("【硬】主线核心谜题"));
        assertTrue(!prefix.contains("长期未填"));
        assertTrue(audit.contains("主线核心谜题"));
    }

    @Test
    void prefix_foreshadowTiersEscalateByStagnation() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= 16; ch++) {
            summaries.add(summary(ch, states(state("小陈", "炼气"))));
            summaries.get(ch - 1).setForeshadowingNew(List.of("伏笔" + ch));
        }

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        // 默认 3 档（基础 30）+ 滞留×5：第 1/2 章埋的达 100+ 熔断，第 3~6 章硬区，
        // 第 7~10 章软区，第 11~15 章背景，第 16 章新埋
        assertTrue(prefix.contains("（上章新埋）伏笔16"));
        assertTrue(prefix.contains("【硬】伏笔3"));
        assertTrue(prefix.contains("已悬置多章"), "硬区应带承接指引；规划层语言（必须评估）不得进写手视图");
        assertTrue(!prefix.contains("必须评估"));
        assertTrue(prefix.contains("【软】伏笔10"));
        assertTrue(prefix.contains("（更早埋设）伏笔11"));
        assertTrue(prefix.contains("（更早埋设）伏笔15"));
        assertTrue(!prefix.contains("伏笔1（"), "熔断条目不得以任何形式出现（伏笔1 是伏笔10-16 的子串，需带括号锚定）");
        assertTrue(prefix.contains("另有 2 条伏笔长期未填"));
    }

    @Test
    void prefix_foreshadowImportanceDrivesTier() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("小陈", "重伤"))),
                summary(3, states(state("小陈", "炼气三层"))));
        summaries.get(0).setForeshadowingNew(List.of("主线伏笔", "闲笔伏笔"));
        summaries.get(0).setForeshadowSeeds(List.of(
                new ChapterSummaryEntity.SeedEntry("主线伏笔", null, 5),
                new ChapterSummaryEntity.SeedEntry("闲笔伏笔", null, 1)));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        // 主线 5 档：50 + 滞留 2 章×5 = 60 → 软区；闲笔 1 档：10 + 10 = 20 → 背景
        assertTrue(prefix.contains("【软】主线伏笔"));
        assertTrue(prefix.contains("（更早埋设）闲笔伏笔"));
        assertTrue(!prefix.contains("【软】闲笔伏笔"));
    }

    @Test
    void prefix_foreshadowTop5NewestFirstWithSeedExcerpts() {
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("小陈", "重伤"))),
                summary(2, states(state("小陈", "炼气一层"))),
                summary(3, states(state("小陈", "炼气三层"))));
        summaries.get(0).setForeshadowingNew(List.of("旧伏笔甲"));
        summaries.get(0).setForeshadowSeeds(List.of(
                new ChapterSummaryEntity.SeedEntry("旧伏笔甲", "旧伏笔甲的正文原句。", null)));
        summaries.get(1).setForeshadowingNew(List.of("中段伏笔乙"));
        summaries.get(2).setForeshadowingNew(List.of("新埋伏笔丙"));
        summaries.get(2).setForeshadowSeeds(List.of(
                new ChapterSummaryEntity.SeedEntry("新埋伏笔丙", "新埋伏笔丙的正文原句。", null)));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        // 新埋区在最前（新章优先），背景区按分数降序（旧伏笔甲 40 > 中段乙 35）；有种子原文的条目附原文引用
        assertTrue(prefix.contains("（上章新埋）新埋伏笔丙"));
        assertTrue(prefix.contains("原文引用：新埋伏笔丙的正文原句。"));
        assertTrue(prefix.contains("（更早埋设）中段伏笔乙"));
        assertTrue(prefix.contains("（更早埋设）旧伏笔甲"));
        assertTrue(prefix.indexOf("新埋伏笔丙") < prefix.indexOf("旧伏笔甲"));
    }

    @Test
    void prefix_foreshadowSeedWithoutExcerptRendersContentOnly() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));
        summaries.get(0).setForeshadowingNew(List.of("某伏笔"));
        summaries.get(0).setForeshadowSeeds(List.of(
                new ChapterSummaryEntity.SeedEntry("某伏笔", null, null)));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        assertTrue(prefix.contains("（上章新埋）某伏笔"));
        assertTrue(!prefix.contains("原文引用"));
    }

    @Test
    void prefix_qualityDebtsRecentTwoChaptersCappedFive() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));
        List<QualityDebtEntity> debts = List.of(
                debt(1, issue("consistency", "第1章债：境界矛盾", "建议甲"), issue("pacing", "第1章债2", null)),
                debt(5, issue("hook", "第5章债：钩子断裂", "建议乙"), issue("aesthetic", "第5章债2", null),
                        issue("character", "第5章债3", null)),
                debt(8, issue("consistency", "第8章债：事实冲突", "建议丙"), issue("hook", "第8章债2", null)));

        String prefix = service.buildMemoryPrefix(summaries, null, null, debts);

        assertTrue(prefix.contains("【上章质量债】以下问题审校已确认，本章写作避免同类问题"));
        // 只回灌最近 1~2 章（8、5），最多 5 条：第8章 2 条 + 第5章前 3 条
        assertTrue(prefix.contains("第8章【consistency】第8章债：事实冲突（建议：建议丙）"));
        assertTrue(prefix.contains("第5章【aesthetic】第5章债2"));
        assertTrue(!prefix.contains("第1章债"));
    }

    @Test
    void prefix_qualityDebtsBlockingTakesSlotsBeforeStyleMinor() {
        // 严重度分层的消费端护栏：降档的机械文风 MINOR 与真 BLOCKING 混在同一条债里时，
        // 5 条前缀槽位必须先给 BLOCKING，否则文风提示会把真硬伤挤出回灌前缀
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));
        List<QualityDebtEntity> debts = List.of(
                debt(4, minor("aesthetic", "第4章文风：副词密度"),
                        minor("aesthetic", "第4章文风：章末升华"),
                        minor("aesthetic", "第4章文风：眼神套话"),
                        minor("aesthetic", "第4章文风：身体套话"),
                        minor("aesthetic", "第4章文风：第五条"), 
                        issue("consistency", "第4章真硬伤：境界矛盾", "建议甲")));

        String prefix = service.buildMemoryPrefix(summaries, null, null, debts);

        assertTrue(prefix.contains("第4章【consistency】第4章真硬伤：境界矛盾（建议：建议甲）"),
                "真硬伤必须进入前缀，不得被同章的文风 MINOR 挤掉");
        assertTrue(prefix.contains("【aesthetic】第4章文风：副词密度"), "剩余槽位仍应回灌文风提示");
    }

    @Test
    void prefix_qualityDebtsSkipsResolvedAndNullChapters() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));
        List<QualityDebtEntity> debts = List.of(
                debt(3, issue("hook", "已修复的债", null)),
                debt(6, issue("pacing", "未修复的债", null)));
        debts.get(0).setResolved(true);

        String prefix = service.buildMemoryPrefix(summaries, null, null, debts);

        assertTrue(prefix.contains("未修复的债"));
        assertTrue(!prefix.contains("已修复的债"));
        assertTrue(!service.buildMemoryPrefix(summaries, null, null, List.of()).contains("上章质量债"));
        assertTrue(!service.buildMemoryPrefix(summaries, null, null, null).contains("上章质量债"));
    }

    @Test
    void prefix_rendersStageBlueprintBeforeSummaries() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(10)
                .stageGoal("主角站稳外门，古物秘密初现")
                .tasks(List.of("主角突破至炼气三层", "揭开某关键物来历的一半"))
                .carriedTasks(List.of(new StageBlueprintEntity.CarriedTaskEntity("与赵阔首次交锋", "进行中", "已铺垫仇恨")))
                .build();

        String prefix = service.buildMemoryPrefix(summaries, null, null, null, blueprint);

        assertTrue(prefix.contains("【阶段蓝图】第1阶段（第1-10章）"));
        assertTrue(prefix.contains("阶段目标：主角站稳外门，古物秘密初现"));
        assertTrue(prefix.contains("阶段任务（里程碑，按剧情自然推进，无需每章推进，严禁为凑任务注水）"));
        assertTrue(prefix.contains("- 主角突破至炼气三层"));
        assertTrue(prefix.contains("- 与赵阔首次交锋（进行中，已铺垫仇恨）"));
        // 方向先于细节：蓝图节在剧情摘要之前
        assertTrue(prefix.indexOf("【阶段蓝图】") < prefix.indexOf("【前章剧情摘要】"));
    }

    @Test
    void prefix_omitsStageBlueprintWhenNull() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));

        String prefix = service.buildMemoryPrefix(summaries, null, null, null, null);

        assertTrue(!prefix.contains("阶段蓝图"));
    }

    @Test
    void prefix_rendersVolumeDirectionBeforeStageBlueprint() {
        // 卷方向锚：卷主旨/承转合/卷级伏笔/卷内弧清单此前只在弧生成时可见，
        // 章节计划与正文两层看不到——正文可能偏离卷主旨、把卷级承诺一路漏到卷末清账
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "炼气一层"))));
        VolumeBlueprintEntity volume = VolumeBlueprintEntity.builder()
                .volumeNo(2).title("北荒试炼").startChapter(101).endChapter(400)
                .themeShift("从外门立足转向北荒势力博弈")
                .beats(List.of("起：夺取试炼名额", "承：卷入两族之争", "合：以战立威"))
                .seeds(List.of("兽潮背后有人操控", "北荒王族的旧盟约"))
                .arcPlan(List.of(
                        VolumeBlueprintEntity.ArcPlan.builder().arcNo(1).oneLineGoal("夺名额").build(),
                        VolumeBlueprintEntity.ArcPlan.builder().arcNo(2).oneLineGoal("入北荒").build()))
                .build();
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(2).arcNo(2).startChapter(151).endChapter(200).stageGoal("北荒立足").build();

        String prefix = service.buildMemoryPrefix(summaries, null, null, null, blueprint, volume, null);

        assertTrue(prefix.contains("【当前卷方向锚】第2卷《北荒试炼》（第101-400章）"));
        assertTrue(prefix.contains("卷主旨：从外门立足转向北荒势力博弈"));
        assertTrue(prefix.contains("卷承转合：起：夺取试炼名额；承：卷入两族之争；合：以战立威"));
        assertTrue(prefix.contains("卷级伏笔（滚向卷尾回收）：兽潮背后有人操控；北荒王族的旧盟约"));
        assertTrue(prefix.contains("【2·本弧】入北荒"), "本弧必须标出，供规划层定位卷内位置");
        assertTrue(prefix.contains("【1】夺名额"), "非本弧不带标记");
        // 卷 > 弧 > 章：卷方向锚在阶段蓝图之前，阶段蓝图在摘要之前
        assertTrue(prefix.indexOf("【当前卷方向锚】") < prefix.indexOf("【阶段蓝图】"));
        assertTrue(prefix.indexOf("【阶段蓝图】") < prefix.indexOf("【前章剧情摘要】"));
    }

    @Test
    void prefix_omitsVolumeDirectionWhenNoVolume() {
        // 无卷模式（两段式）/卷生成失败：不注入方向锚，行为同改造前
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));

        String prefix = service.buildMemoryPrefix(summaries, null, null, null, null, null, null);

        assertFalse(prefix.contains("【当前卷方向锚】"));
    }

    @Test
    void memoryBlocks_splitSectionsWithValueBasedPriorities() {
        // 分块视图：整块交给总额守门只能整体截尾（先牺牲尾部：上章结尾/质量债/偏差警示），
        // 拆分后牺牲顺序改由 priority 决定——最近与纠错类数值更小（更不可牺牲），账本/唤醒更低
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("小陈", "炼气一层"))),
                summary(2, states(state("小陈", "炼气二层"))));
        List<QualityDebtEntity> debts = List.of(debt(2, issue("hook", "末尾钩子断裂", null)));
        VolumeBlueprintEntity volume = VolumeBlueprintEntity.builder()
                .volumeNo(1).title("外门崛起").startChapter(1).endChapter(100)
                .themeShift("从废柴到外门第一").build();

        List<PromptBudgetGuard.Block> blocks = service.buildMemoryBlocks(summaries, "上章结尾原文。",
                List.of("上章偏差：位置跳变"), debts, null, volume, null, null);

        assertEquals(List.of("境界锁定", "卷方向锚", "阶段蓝图", "近章摘要", "久远唤醒", "三账本",
                        "伏笔账", "偏差警示", "质量债", "上章结尾"),
                blocks.stream().map(PromptBudgetGuard.Block::label).toList(),
                "渲染顺序必须仍为旧版拼接顺序（分块只改淘汰次序，不改渲染次序）");
        assertTrue(blockByLabel(blocks, "近章摘要").priority() < blockByLabel(blocks, "三账本").priority(),
                "最近记忆必须比账本更不可牺牲");
        assertTrue(blockByLabel(blocks, "近章摘要").priority() < blockByLabel(blocks, "久远唤醒").priority(),
                "最近记忆必须比向量唤醒更不可牺牲");
        // 三账本由 4 上调至 3：实测连续多章被整块截断后，写手看不到事实基准而编造出
        // 与原文差一个量级的数值，且错值被摘要登记成正典。它与"上章结尾"同属"缺了就写错"
        // 的档，故断言改为"不劣于"——账本不得比尾部衔接内容更易被牺牲。
        assertTrue(blockByLabel(blocks, "三账本").priority()
                        <= blockByLabel(blocks, "上章结尾").priority(),
                "账本是事实基准，不得比上章结尾更易被牺牲（同档可接受）");
        assertFalse(blockByLabel(blocks, "境界锁定").truncatable(), "境界锁定截半即误导，只允许整块留弃");
        assertTrue(blockByLabel(blocks, "上章结尾").content().contains("上章结尾原文。"));
        assertTrue(blockByLabel(blocks, "偏差警示").content().contains("上章偏差：位置跳变"));
        assertTrue(blockByLabel(blocks, "质量债").content().contains("末尾钩子断裂"));
        assertTrue(blockByLabel(blocks, "久远唤醒").content().isEmpty(),
                "无命中的节内容为空，由装配器过滤、不占预算");
    }

    @Test
    void timeAnchor_isInjectedAtRealmLockPriorityWhenAgeFactPresent() {
        // 年龄必须像境界一样每路锁定——时序锚与境界锁定同级（priority 1）、不可截断。
        // 背景：26-30 章计划 prompt 里"当前月龄出现 0 次"，导致十一个月大的婴儿写数论证明。
        ChapterSummaryEntity ch16 = summary(16, states(state("角色丙", "十一个月")));
        ch16.setTimePoint("正月十六上午");
        ch16.setConsistencyFacts(new ArrayList<>(List.of(new ChapterSummaryEntity.ConsistencyFact(
                "NUMBER", "某角色月龄", "十一个月", "角色丙", "证据"))));

        List<PromptBudgetGuard.Block> blocks = service.buildMemoryBlocks(
                List.of(ch16), null, null, null, null, null, null, null);

        PromptBudgetGuard.Block anchor = blockByLabel(blocks, "时序锚");
        assertEquals(blockByLabel(blocks, "境界锁定").priority(), anchor.priority(),
                "时序锚与境界锁定同级：都是连续性硬约束");
        assertFalse(anchor.truncatable(), "时序锚截半即误导，只允许整块留弃");
        assertTrue(anchor.content().contains("角色丙年龄：十一个月"));
        List<String> labels = blocks.stream().map(PromptBudgetGuard.Block::label).toList();
        assertTrue(labels.indexOf("境界锁定") < labels.indexOf("时序锚"));
        assertTrue(labels.indexOf("时序锚") < labels.indexOf("卷方向锚"),
                "硬锚置于方向锚之前");
    }

    @Test
    void timeAnchor_blockAbsentWhenNoAgeFact() {
        List<PromptBudgetGuard.Block> blocks = service.buildMemoryBlocks(
                List.of(summary(1, states(state("小陈", "炼气一层")))), null, null, null, null, null, null, null);

        assertTrue(blocks.stream().noneMatch(block -> block.label().equals("时序锚")),
                "无年龄事实时整块不加入（不编造、也不留空标签占位）");
    }

    @Test
    void memoryBlocks_keepDirectionBlocksWhenNoSummaries() {
        // 新书首段（无摘要）不得整块清空——卷方向锚/阶段蓝图不依赖摘要，
        // 是首段计划与第一章唯一的方向来源（实测事故：首段计划输入仅剩[故事设定]，
        // 计划层据此编出超龄事件，阶段出口条件达成率仅 2/5）
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(5)
                .stageGoal("周岁宴完成灵童半公开认证")
                .tasks(List.of("角色戊在周岁宴后私下给出判断性语言"))
                .build();
        VolumeBlueprintEntity volume = VolumeBlueprintEntity.builder()
                .volumeNo(1).title("人间烟火").startChapter(1).endChapter(300)
                .themeShift("从重生看客到扎根此世").build();

        List<PromptBudgetGuard.Block> blocks = service.buildMemoryBlocks(
                List.of(), null, null, null, blueprint, volume, null, null);

        List<String> labels = blocks.stream().map(PromptBudgetGuard.Block::label).toList();
        assertTrue(labels.contains("卷方向锚"), "无摘要时卷方向锚必须保留");
        assertTrue(labels.contains("阶段蓝图"), "无摘要时阶段蓝图必须保留");
        assertTrue(blockByLabel(blocks, "阶段蓝图").content().contains("周岁宴完成灵童半公开认证"));
    }

    @Test
    void memoryBlocks_settingsAnchorPrepended_onlyWhenNoSummaries() {
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageNo(1).startChapter(1).endChapter(5).stageGoal("第一阶段").build();

        List<PromptBudgetGuard.Block> blocks = new ArrayList<>(service.buildMemoryBlocks(
                List.of(), null, null, null, blueprint, null, null, null));
        service.prependSettingsAnchorIfNoSummaries(blocks, List.of(),
                "主角在2002年以4岁幼童的身份苏醒", "角色丙，灵魂是前世科技公司创始人", "化作巷子里的4岁幼童");

        PromptBudgetGuard.Block anchor = blockByLabel(blocks, "时序锚");
        assertNotNull(anchor, "无摘要时必须补设定兜底年龄锚（否则第一章零年龄约束）");
        assertFalse(anchor.truncatable(), "兜底锚同样不可截断");
        assertTrue(anchor.priority() < blockByLabel(blocks, "阶段蓝图").priority(),
                "年龄锚必须高于方向类块（与境界锁定同级）");
        assertTrue(anchor.content().contains("角色丙起始年龄：4岁"));

        // 有摘要时不再追加：以摘要锚为准，防起始年龄钉死在中期
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "炼气一层"))));
        List<PromptBudgetGuard.Block> withSummaries = new ArrayList<>(
                service.buildMemoryBlocks(summaries, null, null, null, null, null, null, null));
        int sizeBefore = withSummaries.size();
        service.prependSettingsAnchorIfNoSummaries(withSummaries, summaries,
                "主角在2002年以4岁幼童的身份苏醒", "角色丙", null);
        assertEquals(sizeBefore, withSummaries.size(), "有摘要时不追加设定兜底锚");
    }

    @Test
    void settingsAnchor_skippedWhenSettingsHaveNoChildAge() {
        // 命中从严：设定里只有成人年龄（"前世38岁"）时不锚——宁可不锚，绝不误锚
        List<PromptBudgetGuard.Block> blocks = new ArrayList<>();
        service.prependSettingsAnchorIfNoSummaries(blocks, List.of(),
                "主角前世是38岁的创始人", "某角色，灵魂是……", "中年重生");
        assertTrue(blocks.isEmpty(), "成人年龄不得被锚成当前年龄");
    }

    @Test
    void buildMemoryPrefix_equalsJoinedBlocks() {
        // 整串视图（章节计划输入段消费）与分块视图（正文装配消费）同源：内容一致，仅块间分隔符口径
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("小陈", "炼气一层"))),
                summary(2, states(state("小陈", "炼气二层"))));
        List<String> conflicts = List.of("上章偏差：位置跳变");
        List<QualityDebtEntity> debts = List.of(debt(2, issue("hook", "末尾钩子断裂", null)));

        String joined = service.buildMemoryBlocks(summaries, "上章结尾。", conflicts, debts, null, null, null, null)
                .stream()
                .map(PromptBudgetGuard.Block::content)
                .filter(content -> !content.isBlank())
                .collect(Collectors.joining("\n\n"));

        assertEquals(joined, service.buildMemoryPrefix(summaries, "上章结尾。", conflicts, debts, null, null, null));
    }

    @Test
    void blueprintForeshadowList_ordersHardSoftBreakerExcludesBackground() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        for (int ch = 1; ch <= 16; ch++) {
            summaries.add(summary(ch, states(state("小陈", "炼气"))));
            summaries.get(ch - 1).setForeshadowingNew(List.of("伏笔" + ch));
        }

        List<String> lines = service.buildBlueprintForeshadowList(summaries);

        // 默认 3 档 + 滞留×5：第3-6章硬区、第7-10章软区、第1-2章未填；背景（11-15）与新埋（16）不构成阶段编排压力
        assertEquals("【硬】伏笔3（第3章埋）", lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.equals("【软】伏笔10（第10章埋）")));
        assertTrue(lines.stream().anyMatch(l -> l.equals("【未填·冻结】伏笔1（第1章埋）")));
        assertTrue(lines.get(lines.size() - 1).startsWith("【未填·冻结】"));
        assertTrue(lines.stream().noneMatch(l -> l.contains("伏笔16")));
        assertTrue(lines.stream().noneMatch(l -> l.contains("伏笔15")));
        assertEquals(10, lines.size());
    }

    @Test
    void blueprintForeshadowList_emptyWhenNoSummaries() {
        assertTrue(service.buildBlueprintForeshadowList(null).isEmpty());
        assertTrue(service.buildBlueprintForeshadowList(List.of()).isEmpty());
    }

    private QualityDebtEntity debt(int chapterNo, ChapterIssueEntity... issues) {
        return QualityDebtEntity.builder()
                .chapterNo(chapterNo)
                .issues(new ArrayList<>(List.of(issues)))
                .resolved(false)
                .build();
    }

    private ChapterIssueEntity issue(String dimension, String description, String suggestion) {
        return ChapterIssueEntity.builder()
                .dimension(dimension)
                .severity("BLOCKING")
                .description(description)
                .suggestion(suggestion)
                .build();
    }

    /** 降档后的机械文风 MINOR（不触发修订/候选，仅落债与回灌） */
    private ChapterIssueEntity minor(String dimension, String description) {
        return ChapterIssueEntity.builder()
                .dimension(dimension)
                .severity("MINOR")
                .description(description)
                .build();
    }

    @Test
    void prefix_locksLatestRealmAtTop() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>(Arrays.asList(
                summary(1, states(state("小陈", "重伤"))),
                summary(2, states(state("小陈", "炼气一层"))),
                summary(3, states(state("小陈", "炼气三层")))));
        summaries.get(1).setCultivationRealm("炼气一层");
        summaries.get(2).setCultivationRealm("炼气三层");

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        // 锁定取最近一条记录，置于前缀顶部、剧情摘要之前
        assertTrue(prefix.indexOf("主角境界锁定") < prefix.indexOf("前章剧情摘要"));
        assertTrue(prefix.contains("主角当前真实境界：炼气三层"));
        assertTrue(prefix.contains("仅当本章计划的关键事件明确包含突破/晋升时才允许变化"));
    }

    @Test
    void prefix_noRealmSectionWhenNeverRecorded() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        assertTrue(!prefix.contains("主角境界锁定"));
    }

    @Test
    void prefix_rendersPendingConflictsWarning() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));

        String prefix = service.buildMemoryPrefix(summaries, null,
                List.of("第2章写小陈后腰被踹，但账本与第1章均无此情节"));

        assertTrue(prefix.contains("上章偏差警示"));
        assertTrue(prefix.contains("以账本为准"));
        assertTrue(prefix.contains("后腰被踹"));
    }

    @Test
    void prefix_noConflictSectionWhenNull() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));

        assertTrue(!service.buildMemoryPrefix(summaries, null, null).contains("上章偏差警示"));
    }

    @Test
    void prefix_resolvedForeshadowingRemoved() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, states(state("小陈", "重伤"))));
        summaries.get(0).setForeshadowingNew(List.of("古物来历", "执事的态度"));
        summaries.get(0).setForeshadowingResolved(List.of("古物来历"));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        assertTrue(prefix.contains("执事的态度"));
        assertTrue(!prefix.contains("- 古物来历"));
    }

    @Test
    void prefix_emptyWhenNoSummaries() {
        assertEquals("", service.buildMemoryPrefix(null, "上章结尾", null));
        assertEquals("", service.buildMemoryPrefix(List.of(), "上章结尾", null));
    }

    @Test
    void tailByParagraph_startsAtParagraphBoundary() {
        String content = String.join("\n",
                "第一段落。".repeat(50),
                "第二段落。".repeat(50),
                "最后一段完整的话。");

        String tail = service.tailByParagraph(content, 100);

        // 截断起点对齐段落边界，最后一段完整保留
        assertTrue(tail.startsWith("第二段落。") || tail.startsWith("最后一段"));
        assertTrue(tail.endsWith("最后一段完整的话。"));
    }

    @Test
    void tailByParagraph_shortContentReturnedFully() {
        assertEquals("短正文。", service.tailByParagraph("短正文。", 500));
        assertNull(service.tailByParagraph(null, 500));
        assertNull(service.tailByParagraph("  ", 500));
    }

    @Test
    void tailByParagraph_normalizesEscapedNewline() {
        String content = "前文。" + "\\n".repeat(60) + "结尾段落。";

        String tail = service.tailByParagraph(content, 50);

        assertTrue(tail.contains("结尾段落。"));
        assertTrue(!tail.contains("\\n"));
    }

    @Test
    void prefix_rendersRecallHitsSection() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, state("小陈", "重伤")));

        String prefix = service.buildMemoryPrefix(summaries, null, null, null, null,
                List.of(new StoryMemoryService.RecallHit("第2章《旧事》：旧事重提。\n伏笔线索", 0.82)));

        assertTrue(prefix.contains("【久远记忆·相关性唤醒】"));
        // 命中原文换行折叠为分号，单行注入
        assertTrue(prefix.contains("第2章《旧事》：旧事重提。；伏笔线索"));
    }

    @Test
    void prefix_omitsRecallSectionWhenEmpty() {
        List<ChapterSummaryEntity> summaries = List.of(summary(1, state("小陈", "重伤")));

        String prefix = service.buildMemoryPrefix(summaries, null, null, null, null, List.of());

        assertFalse(prefix.contains("【久远记忆·相关性唤醒】"));
    }

    @Test
    void breakerForeshadows_onlyBreakerTierWithLatestNoFromStageEnd() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(summary(1, state("小陈", "炼气")));
        summaries.add(summary(30, state("小陈", "炼气")));
        // 重要度 4 第 1 章埋，第 30 章时 40 + 145 = 185 → 熔断；重要度 4 第 29 章埋 → 背景区
        summaries.get(0).setForeshadowingNew(List.of("旧笔记本里的借书卡"));
        summaries.get(0).setForeshadowSeeds(List.of(
                new ChapterSummaryEntity.SeedEntry("旧笔记本里的借书卡", "一张陌生借书卡", 4)));
        summaries.get(1).setForeshadowingNew(List.of("刚埋的新钩子"));
        summaries.get(1).setForeshadowSeeds(List.of(
                new ChapterSummaryEntity.SeedEntry("刚埋的新钩子", null, 4)));

        List<ForeshadowPriorityService.ScoredForeshadow> breakers =
                service.buildBreakerForeshadows(summaries, 30);

        assertEquals(1, breakers.size());
        assertEquals("旧笔记本里的借书卡", breakers.get(0).item().content());
        assertEquals("一张陌生借书卡", breakers.get(0).item().excerpt());
    }

    @Test
    void stripVoidedForeshadows_removesByExactContentAndKeepsOthers() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        summaries.add(summary(1, state("小陈", "炼气")));
        summaries.add(summary(2, state("小陈", "炼气")));
        // List.of 不可变清单也应可剔除（strip 以替换整表实现，不就地改写）
        summaries.get(0).setForeshadowingNew(List.of("废弃的转发链路线索", "旧笔记本里的借书卡"));
        summaries.get(1).setForeshadowingNew(Arrays.asList("刚埋的新钩子", " 废弃的转发链路线索 "));

        service.stripVoidedForeshadows(summaries, Arrays.asList("废弃的转发链路线索", "  ", null));

        assertEquals(List.of("旧笔记本里的借书卡"), summaries.get(0).getForeshadowingNew());
        assertEquals(List.of("刚埋的新钩子"), summaries.get(1).getForeshadowingNew());
        // 再剔除一次为幂等空操作
        service.stripVoidedForeshadows(summaries, List.of("废弃的转发链路线索"));
        assertEquals(List.of("旧笔记本里的借书卡"), summaries.get(0).getForeshadowingNew());
    }

    @Test
    void prefix_singleMentionEntryRendersNameOnly() {
        // 只出现一次的条目降为"仅列名"：实测本书物品账本近一半渲染字数是这类
        // 一次性布景道具（油纸包点心 / 绿豆汤 / 洛阳轴承厂纸盒…），不会再被引用却持续吃前缀预算。
        // 判定依据：buildLedger 里 lastChapterNo 每次提及都会刷新，故"首现章 == 最近提及章"
        // 等价于"全篇只在一章出现过"——这是本规则能成立的前提，须由本用例守住。
        List<ChapterSummaryEntity> summaries = List.of(
                summary(1, states(state("角色丙", "重生为四岁幼童"),
                        state("油纸包点心", "从供销社买回，油纸已渗油"))),
                summary(2, states(state("角色丙", "学会打算盘"))));

        String prefix = service.buildMemoryPrefix(summaries, null, null);

        assertTrue(prefix.contains("角色丙（第1章登场）："), "被复用的条目应完整渲染：" + prefix);
        assertTrue(prefix.contains("学会打算盘"), "被复用条目的状态必须保留");
        assertTrue(prefix.contains("仅出现过一次的条目"), "应有一段降级展示：" + prefix);
        assertTrue(prefix.contains("油纸包点心"), "一次性条目仍保留名字（防后文生造同名物）");
        assertFalse(prefix.contains("油纸已渗油"), "一次性条目的状态不得渲染——这是省预算的主要来源");
    }

    private ChapterSummaryEntity summary(int chapterNo, ChapterSummaryEntity.StateEntry... states) {
        ChapterSummaryEntity entity = ChapterSummaryEntity.builder()
                .chapterNo(chapterNo)
                .title(chapterNo == 1 ? "废丹田" : chapterNo == 2 ? "古物奇谈" : "第" + chapterNo + "章")
                .summary(chapterNo == 1 ? "小陈被赵阔废去丹田。" : chapterNo == 2 ? "小陈踏入炼气一层。" : "剧情推进。")
                .build();
        if (states.length > 0) {
            entity.setCharacterStates(new ArrayList<>(Arrays.asList(states)));
        }
        return entity;
    }

    private ChapterSummaryEntity.StateEntry state(String name, String status) {
        return new ChapterSummaryEntity.StateEntry(name, status, null);
    }

    private ChapterSummaryEntity.StateEntry[] states(ChapterSummaryEntity.StateEntry... entries) {
        return entries;
    }

    private LedgerEntry byName(List<LedgerEntry> ledger, String name) {
        return ledger.stream().filter(e -> e.getName().equals(name)).findFirst().orElseThrow();
    }

    private PromptBudgetGuard.Block blockByLabel(List<PromptBudgetGuard.Block> blocks, String label) {
        return blocks.stream().filter(block -> block.label().equals(label)).findFirst().orElseThrow();
    }

}
