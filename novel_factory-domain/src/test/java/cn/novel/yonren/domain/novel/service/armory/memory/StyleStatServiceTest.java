package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风格统计测试：跨章逐字句查重、疲劳词累加与超频、警示渲染与上限
 */
class StyleStatServiceTest {

    private StyleStatService service;
    private StyleStatEntity stat;

    @BeforeEach
    void setUp() {
        service = new StyleStatService();
        stat = service.empty();
    }

    @Test
    void merge_collectsLongSentencesAndSkipsShort() {
        service.merge(stat, "小陈在夜色中悄然潜入后山废矿深处。短句。他握紧了怀中那面沉默的古物。");
        // 句末标点被切分符剥掉，句集条目不含标点；短句（2 字）不参与
        System.out.println("切分结果: " + stat.getUsedSentences());
        assertEquals(2, stat.getUsedSentences().size());
        assertTrue(stat.getUsedSentences().contains("小陈在夜色中悄然潜入后山废矿深处"));
    }

    @Test
    void merge_detectsCrossChapterRepeatedSentence() {        String sentence = "小陈在夜色中悄然潜入后山废矿深处，脚步轻得像一片落叶";
        service.merge(stat, sentence + "。本章其余内容。");
        service.merge(stat, "另一章的内容。" + sentence + "。");

        assertEquals(1, stat.getRepeatedSentences().size());
        assertEquals(sentence, stat.getRepeatedSentences().get(0));
    }

    @Test
    void merge_detectsNearDuplicateSentenceNotJustExactMatch() {
        // 实测病症：第 9/10 章结尾仅差一个"他"字——
        // 「枕头底下，那根烟硌着后脑勺，像一根小小的骨头」vs「…硌着他的后脑勺…」。
        // 精确匹配下跨章重复统计为 0 条，等于完全失明；本用例守住近重复判据。
        service.merge(stat, "枕头底下，那根烟硌着后脑勺，像一根小小的骨头。");
        service.merge(stat, "枕头底下，那根烟硌着他的后脑勺，像一根小小的骨头。");

        assertTrue(stat.getRepeatedSentences().stream().anyMatch(s -> s.contains("那根烟硌着")),
                "仅改一个字的近重复必须被捕获：" + stat.getRepeatedSentences());
    }

    @Test
    void merge_doesNotFlagUnrelatedSentences() {
        // 阈值取偏保守：宁可漏报，也不要把正常文体误标成重复
        service.merge(stat, "角色戊把自行车推到巷口，链条上沾满了泥。");
        service.merge(stat, "角色癸端着旧脸盆从厨房出来，盆里是刚晾好的绿豆汤。");

        assertTrue(stat.getRepeatedSentences().isEmpty(),
                "内容无关的句子不得被误判为重复：" + stat.getRepeatedSentences());
    }

    @Test
    void merge_accumulatesFatigueWordCounts() {
        service.merge(stat, "一丝寒意爬上脊背。仿佛 whole world 静了。一缕血腥味弥漫。");
        service.merge(stat, "又一丝寒意。再次仿佛停滞。");

        assertEquals(2, stat.getFatigueWords().get("一丝"));
        assertEquals(2, stat.getFatigueWords().get("仿佛"));
        assertEquals(1, stat.getFatigueWords().get("一缕"));
    }

    @Test
    void renderWarning_listsRepeatedSentencesAndOverusedWords() {
        String sentence = "他咽下喉头的腥甜，指节因为用力而发白，眼神一点点冷了下去";
        service.merge(stat, sentence + "。");
        // 同一句再出现一次进重复名单；疲劳词灌到阈值以上
        for (int i = 0; i < StyleStatService.FATIGUE_THRESHOLD; i++) {
            service.merge(stat, "一丝寒意。" + i);
        }
        service.merge(stat, sentence + "。再次出现。");

        String warning = service.renderWarning(stat);

        assertTrue(warning.contains("风格警示"));
        // 措辞随近重复判据一并调整：不再只针对"逐字"，包含"高度相似"的句子
        assertTrue(warning.contains("高度相似"), "警示应覆盖近重复而不只是逐字重复：" + warning);
        assertTrue(warning.contains(sentence));
        assertTrue(warning.contains("疲劳词超频"));
        assertTrue(warning.contains("一丝(" + StyleStatService.FATIGUE_THRESHOLD + "次)"));
    }

    @Test
    void renderWarning_emptyWhenNoIssues() {
        service.merge(stat, "正常的一段文字，没有重复也没有超频词。");
        assertEquals("", service.renderWarning(stat));
        assertEquals("", service.renderWarning(null));
    }

    @Test
    void renderContentFatigueBlacklist_listsFullCatalogAndNearLimitWords() {
        // 逼近阈值（阈值-1）的词应被点名
        for (int i = 0; i < StyleStatService.FATIGUE_THRESHOLD - 1; i++) {
            service.merge(stat, "一丝寒意。" + i);
        }

        String blacklist = service.renderContentFatigueBlacklist(stat);

        assertTrue(blacklist.contains("疲劳词预警名单"));
        // 全量词表在场（门禁词表三类各抽一个）
        assertTrue(blacklist.contains("不禁"));
        assertTrue(blacklist.contains("眼神复杂"));
        assertTrue(blacklist.contains("喉结滚动"));
        // 门禁红线文案与 StyleViolationPolicy 判据一致
        assertTrue(blacklist.contains("3 次/千字"));
        assertTrue(blacklist.contains("已逼近阈值的词"));
        assertTrue(blacklist.contains("一丝（全书已累计" + (StyleStatService.FATIGUE_THRESHOLD - 1) + "次）"));
    }

    @Test
    void renderContentFatigueBlacklist_noNearLimitOnFreshStat() {
        // 新故事/空账本：只有全量词表，无逼近点名；null 账本同样不炸
        String fresh = service.renderContentFatigueBlacklist(stat);
        assertTrue(fresh.contains("疲劳词预警名单"));
        assertFalse(fresh.contains("已逼近阈值的词"));
        assertFalse(service.renderContentFatigueBlacklist(null).contains("已逼近阈值的词"));
    }

    @Test
    void renderReviseFatigueBlacklist_keepsNearLimitCallout() {
        for (int i = 0; i < StyleStatService.FATIGUE_THRESHOLD - 1; i++) {
            service.merge(stat, "一丝寒意。" + i);
        }

        String blacklist = service.renderReviseFatigueBlacklist(stat);

        assertTrue(blacklist.contains("疲劳词禁新增名单"));
        assertTrue(blacklist.contains("已逼近阈值的词"));
        assertTrue(blacklist.contains("一丝（全书已累计" + (StyleStatService.FATIGUE_THRESHOLD - 1) + "次）"));
    }

    @Test
    void merge_tracksStockMicroActionFatigueWords() {
        for (int i = 0; i < StyleStatService.FATIGUE_THRESHOLD; i++) {
            service.merge(stat, "他喉结滚动，目光沉了下去。");
        }

        String warning = service.renderWarning(stat);

        assertTrue(warning.contains("疲劳词超频"));
        assertTrue(warning.contains("喉结滚动(" + StyleStatService.FATIGUE_THRESHOLD + "次)"));
    }

    @Test
    void merge_capsUsedSentencesToRollingWindow() {
        // 用**互不相似**的句子验证滚动窗口本身：近重复判据会把"只差一两个字的"
        // 模板式句子判为重复、不再进"已见句集"，所以不能用同模板批量生成的句子做本用例的 fixture。
        // 下面三组短句两两不同，任意两句至多共享一组（约占 1/3 篇幅），远低于近重复阈值。
        String first = sentence(0);
        for (int i = 0; i < StyleStatService.MAX_USED_SENTENCES + 10; i++) {
            service.merge(stat, sentence(i));
        }

        assertEquals(StyleStatService.MAX_USED_SENTENCES, stat.getUsedSentences().size(),
                "已见句集必须被滚动窗口封顶，否则无界增长");
        assertTrue(!stat.getUsedSentences().contains(first), "最老的句子应被挤出");
    }

    /** 由三组互不相同的短句拼成的长句；索引组合覆盖 5×5×5=125 种，足够 300+ 句互不重复（四维共 625 种） */
    private static String sentence(int i) {
        String[] subjects = {
                "角色戊推着那辆某牌自行车", "角色癸端着旧脸盆从厨房出来", "角色丙蹲在巷口的青石板上",
                "王阿婆拎着煤球篮子走过去", "角色庚把碎花布罩在电视机上"};
        String[] actions = {
                "说要赶在晌午前把账对完", "顺手把窗台上的灰抹了一遍", "听见远处传来一阵自行车的铃响",
                "把口袋里的零钱数了两遍", "抬头看了看天边压过来的乌云"};
        String[] tails = {
                "心里盘算着这个月还差多少", "嘴上应着手上却没停", "打算等雨小些再去铺子里",
                "想着明早还得去一趟供销社", "把东西归整齐了才坐下"};
        // 第四维：句集窗口 2026-10-10 由 80 上调到 300，125 种组合已不够铺满窗口，
        // 这里补一维把种类提到 5×5×5×5=625（夹具仍须"两两不同"，否则会被近重复判据吃掉）
        String[] marks = {
                "门口的梧桐叶落了一地", "灶上的水壶正冒着白汽", "墙根的猫换了个姿势",
                "街对面的喇叭响了两声", "桌上的旧茶缸挪了半寸"};
        return subjects[i % subjects.length]
                + actions[(i / subjects.length) % actions.length]
                + tails[(i / (subjects.length * actions.length)) % tails.length]
                + marks[(i / (subjects.length * actions.length * tails.length)) % marks.length] + "。";
    }

    @Test
    void merge_nullSafe() {
        service.merge(stat, null);
        service.merge(null, "内容");
        assertEquals(0, stat.getUsedSentences().size());
    }

    /**
     * 跨章对白逐字重复必须查得到（2026-10-10）。
     *
     * <p>实测第 100 章与第 102 章一字不差地问了同一句「那句话还会不会兑现？」，而当时**两处都查不到**：
     * 通用句集上限只有 80 句（约一章的量），第 100 章那句在第 102 章生成前已被第 101 章的句子挤出去。
     * 对白句因此单独入一个大得多的窗口。
     */
    @Test
    @DisplayName("跨章对白查重：短对白也要查得到，窗口须跨越两章以上")
    void crossChapterDialogueRepeats_findsShortLineAcrossChapters() {
        String line = "「那句话还会不会兑现？」";
        service.merge(stat, "她凑过来看那张通知。" + line + "他没有回答。");
        // 隔一章灌入大量长句，把通用句集窗口彻底挤满（复刻"隔两章"的真实条件）
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            filler.append("这是第").append(i).append("句足够长的填充叙述用来把通用句集窗口挤满。");
        }
        service.merge(stat, filler.toString());

        List<String> repeats = service.crossChapterDialogueRepeats(stat,
                "梧桐树底下她站住了。" + line + "角色丙没答。");

        assertEquals(List.of(line), repeats, "短对白隔章重复必须被查到——句集窗口不够长是原来的漏洞");
    }

    /** 只认逐字相等：近重复与超短对白不进这条 BLOCKING 口径，仍走风格警示的软记账 */
    @Test
    @DisplayName("跨章对白查重：近重复与超短应答不算")
    void crossChapterDialogueRepeats_ignoresNearDuplicateAndTooShort() {
        service.merge(stat, "「那句话还会不会兑现？」「好。」");

        assertEquals(List.of("「那句话还会不会兑现？」"),
                service.crossChapterDialogueRepeats(stat, "「那句话还会不会兑现？」"));
        assertTrue(service.crossChapterDialogueRepeats(stat, "「丙班还等得到吧？」").isEmpty(),
                "改一个字属近重复，走软通道不进 BLOCKING");
        assertTrue(service.crossChapterDialogueRepeats(stat, "「好。」").isEmpty(),
                "超短应答不入索引（称呼与应答复用是正常文体）");
    }

    /** 门槛标定：套话短答（含引号 6 字）不进这条 BLOCKING 口径——它们在正常文里本来就会自然复现 */
    @Test
    @DisplayName("跨章对白查重：套话短答不触发（门槛 8 字，实测标定）")
    void crossChapterDialogueRepeats_ignoresFormulaicShortReplies() {
        service.merge(stat, "「我知道了。」「明天再说。」「那句话还会不会兑现？」");

        assertTrue(service.crossChapterDialogueRepeats(stat, "「我知道了。」").isEmpty(),
                "「我知道了。」含引号 6 字，低于 8 字门槛：把套话扫进 BLOCKING 等于白改一稿");
        assertTrue(service.crossChapterDialogueRepeats(stat, "「明天再说。」").isEmpty());
        assertEquals(List.of("「那句话还会不会兑现？」"),
                service.crossChapterDialogueRepeats(stat, "「那句话还会不会兑现？」"),
                "真重复（10 字问句）必须抓到——它就是本条判据的标定样本");
    }



    // ==================== 移植自旧系统的闸门用例 ====================

    /** 同一句在章内一字不差出现两次 → 检出（跨章查重只比"本章 vs 历史"，看不住章内自撞） */
    @Test
    @DisplayName("章内重复：同一句在章内出现两次即检出")
    void intraChapterDuplicate_detectsRepeatedSentence() {
        String content = "他把那封信折好收进内袋。院子里没有人，风把晾着的衣服吹得鼓起来。他把那封信折好收进内袋。";
        List<String> dups = StyleStatService.intraChapterDuplicateSentences(content);
        assertEquals(List.of("他把那封信折好收进内袋"), dups, "章内重复句应被检出：" + dups);
    }

    /** 重复句之间隔着别的短句也要抓到——只比相邻两句会漏掉这种形态（实测成稿就是这样） */
    @Test
    @DisplayName("章内重复：隔句重复同样检出")
    void intraChapterDuplicate_detectsWhenSeparatedByOtherSentence() {
        String content = "她把帆布包往枕头里侧推了推。她一直没睡着。她把帆布包往枕头里侧推了推。";
        List<String> dups = StyleStatService.intraChapterDuplicateSentences(content);
        assertEquals(1, dups.size(), "隔句重复应被检出：" + dups);
    }

    /** 短句复用是正常文体（称呼、短答），不得误报 */
    @Test
    @DisplayName("章内重复：短句复用不误报")
    void intraChapterDuplicate_ignoresShortSentences() {
        String content = "他点头。她笑了。他点头。她又笑了。";
        assertTrue(StyleStatService.intraChapterDuplicateSentences(content).isEmpty(),
                "短句复用不该误报");
    }

    /** 不重复的正文不得误报（宁可漏报不误伤） */
    @Test
    @DisplayName("章内重复：正常正文不误报")
    void intraChapterDuplicate_noFalsePositiveOnNormalProse() {
        String content = "他推开门，院子里没有人。风把晾着的衣服吹得鼓起来，绳子吱吱响。"
                + "他站了一会儿，转身把门带上了。";
        assertTrue(StyleStatService.intraChapterDuplicateSentences(content).isEmpty(),
                "无重复的正常正文不得误报");
    }

    /** 同一句出现三次也只报一次（去重） */
    @Test
    @DisplayName("章内重复：多次重复只报一条")
    void intraChapterDuplicate_reportsOncePerSentence() {
        String content = "他把那封信折好收进内袋。他把那封信折好收进内袋。他把那封信折好收进内袋。";
        assertEquals(1, StyleStatService.intraChapterDuplicateSentences(content).size(),
                "同一句多次重复只报一条");
    }
}
