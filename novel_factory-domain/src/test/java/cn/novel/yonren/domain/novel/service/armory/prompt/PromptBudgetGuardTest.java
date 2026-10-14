package cn.novel.yonren.domain.novel.service.armory.prompt;

import cn.novel.yonren.domain.novel.model.valobj.properties.PromptBudgetProperties;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard.Block;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard.PrefixBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀总预算守门测试：未超预算时输出与逐块直拼逐字一致（渲染顺序=入参顺序，重构不改行为），
 * 超预算时按优先级淘汰/截断（与渲染顺序解耦），且结果长度恒不超上限；
 * label 重复（会让同一块渲染两次）fail-fast
 */
class PromptBudgetGuardTest {

    private PromptBudgetProperties properties;
    private PromptBudgetGuard guard;

    @BeforeEach
    void setUp() {
        properties = new PromptBudgetProperties();
        guard = new PromptBudgetGuard(properties);
    }

    @Test
    void chapterPrefixKeepsLegacyBlockOrderUnderBudget() {
        properties.setTotalPrefixChars(10000);
        // 正文路径的块序列：末态红线 → 记忆各节（分块） → 门禁类块；未超预算时逐字直拼
        String result = guard.assembleChapterPrefix(7, List.of(
                PrefixBlock.EDGE_STATE.toBlock("末态红线"),
                new Block("境界锁定", 1, false, "【主角境界锁定】炼气一层"),
                new Block("近章摘要", 2, true, "【前章剧情摘要】- 第6章：入门"),
                PrefixBlock.SECRECY_GUARD.toBlock("禁泄清单"),
                PrefixBlock.STYLE_WARNING.toBlock("风格警示")));

        assertEquals("末态红线\n\n【主角境界锁定】炼气一层\n\n【前章剧情摘要】- 第6章：入门\n\n禁泄清单\n\n风格警示",
                result);
    }

    @Test
    void budgetShedsLowPriorityFirstRegardlessOfOrder() {
        properties.setTotalPrefixChars(60);
        // 低优先块排在高优先块之前：被淘汰的应是低优先者，而渲染位置仍按入参顺序
        String result = guard.assembleChapterPrefix(3, List.of(
                new Block("软提示", 8, true, "软".repeat(40)),
                new Block("核心块", 1, true, "核".repeat(40))));

        assertTrue(result.contains("核".repeat(40)), "最高优先块必须完整保留");
        assertFalse(result.contains("软"), "预算不足时先弃低优先块，与它排在第几位无关");
    }

    @Test
    void oversizedTruncatableBlockTruncatedAtParagraphBoundary() {
        properties.setTotalPrefixChars(500);
        // 指纹块取 60 字 > 摘要截断后的剩余余量（58 字）：预算确已耗尽、整块丢弃。
        // 截断后余量继续装填小块的行为由 truncationMustNotZeroOutRemainingBudget 单独锁定
        String result = guard.assembleChapterPrefix(3, List.of(
                PrefixBlock.EDGE_STATE.toBlock("红线".repeat(10)),
                new Block("近章摘要", 2, true, paragraphs(100, 10)),
                PrefixBlock.STYLE_FINGERPRINT.toBlock("指纹".repeat(30))));

        assertTrue(result.length() <= 500, "结果长度必须受总预算约束，实际 " + result.length());
        assertTrue(result.startsWith("红线".repeat(10)), "最高优先块必须完整保留且置于最顶部");
        assertTrue(result.contains("[本块已按前缀总预算截断]"), "超限的可截断块应按段落截断并标注");
        assertTrue(result.contains("甲"), "截断后应保留该块头部内容");
        assertFalse(result.contains("指纹"), "预算耗尽后低优先级块应被丢弃");
    }

    /**
     * 诚实记账的回归用例：截断必须按实际占用扣减余量，不得清零。
     *
     * <p>原实现在截断后直接 {@code remaining = 0}，排在截断块之后的所有块不分优先级整批出局——
     * 旧系统实测第 111/112/115 章「截断[一致性索引]；丢弃[情节模式黑名单、审校反馈、久远唤醒、文风指纹]」，
     * 截断后的余量本可装入小纠正通道却被白白扔掉。本用例锁定：摘要截断剩 58 字余量时，
     * 20 字的审校反馈（4 档）必须存活；40 字的文风指纹（5 档）装不下、仍应丢弃。
     */
    @Test
    void truncationMustNotZeroOutRemainingBudget() {
        properties.setTotalPrefixChars(500);
        // 近章摘要 1018 字：截到段落边界 406 字 + 标记 14 字 = 420 字，余量 500 - 20 - 2 - 420 = 58 字
        String result = guard.assembleChapterPrefix(3, List.of(
                PrefixBlock.EDGE_STATE.toBlock("红线".repeat(10)),
                new Block("近章摘要", 2, true, paragraphs(100, 10)),
                PrefixBlock.REVIEW_FEEDBACK.toBlock("反馈".repeat(10)),
                PrefixBlock.STYLE_FINGERPRINT.toBlock("指纹".repeat(20))));

        assertTrue(result.contains("[本块已按前缀总预算截断]"), "近章摘要应被段落截断");
        assertTrue(result.contains("反馈".repeat(10)),
                "截断后的余量（58 字）必须继续装填后续小块——审校反馈应存活，实际：" + result);
        assertFalse(result.contains("指纹".repeat(20)), "余量装不下的大块仍应被丢弃");
        assertTrue(result.length() <= 500, "结果长度必须受总预算约束，实际 " + result.length());
    }

    @Test
    void nonTruncatableBlockIsDroppedInsteadOfTruncated() {
        properties.setTotalPrefixChars(400);
        // 禁泄清单不可截断：截断会静默丢关键词，与逐字扫描的判定口径脱节 → 放不下整块弃
        String result = guard.assembleChapterPrefix(5, List.of(
                PrefixBlock.EDGE_STATE.toBlock("红线".repeat(10)),
                PrefixBlock.SECRECY_GUARD.toBlock("禁泄".repeat(250)),
                PrefixBlock.STYLE_FINGERPRINT.toBlock("指纹".repeat(10))));

        assertTrue(result.length() <= 400, "结果长度必须受总预算约束，实际 " + result.length());
        assertFalse(result.contains("禁泄"), "不可截断块放不下时不得出现残块");
        assertTrue(result.contains("指纹".repeat(10)), "放得下的低优先小块仍应入选（不浪费预算）");
    }

    @Test
    void zeroBudgetDisablesEnforcement() {
        properties.setTotalPrefixChars(0);
        String memory = paragraphs(100, 10);
        String result = guard.assembleChapterPrefix(1, List.of(
                PrefixBlock.EDGE_STATE.toBlock("红线"),
                new Block("近章摘要", 2, true, memory)));

        assertEquals("红线\n\n" + memory, result, "关闭总额约束时应退化为逐块直拼");
    }

    @Test
    void blankBlocksDoNotConsumeBudgetOrEmitSeparators() {
        properties.setTotalPrefixChars(1000);
        String result = guard.assembleChapterPrefix(2, List.of(
                PrefixBlock.EDGE_STATE.toBlock("红线"),
                PrefixBlock.STYLE_WARNING.toBlock(""),
                PrefixBlock.STYLE_FINGERPRINT.toBlock(null),
                PrefixBlock.USED_PATTERN_BLACKLIST.toBlock("   "),
                new Block("近章摘要", 2, true, "记忆")));

        assertEquals("红线\n\n记忆", result);
    }

    @Test
    void tinyBudgetStillKeepsTopPriorityBlockBounded() {
        properties.setTotalPrefixChars(50);
        String result = guard.assembleChapterPrefix(1, List.of(
                PrefixBlock.EDGE_STATE.toBlock("红线".repeat(30)),
                new Block("近章摘要", 2, true, "记忆".repeat(60))));

        assertFalse(result.isBlank(), "极端配置下前缀也不得为空（最高优先块强制保留）");
        assertTrue(result.length() <= 50, "强制保留同样受上限约束，实际 " + result.length());
        assertTrue(result.startsWith("红线"), "强制保留的应是最高优先块");
    }

    @Test
    void duplicateLabelFailsFastInsteadOfRenderingTwice() {
        properties.setTotalPrefixChars(1000);
        // 重复 label 会让同一块被渲染两次、预算扣减与实际输出脱节（不变式被静默击穿）→ fail-fast
        List<Block> blocks = List.of(
                new Block("近章摘要", 2, true, "甲甲甲"),
                new Block("近章摘要", 2, true, "乙乙乙"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.assembleChapterPrefix(1, blocks));

        assertTrue(ex.getMessage().contains("近章摘要"), "异常信息应点名重复的 label，便于定位调用方");
    }

    /**
     * yml 绑定守卫：budget 块插在 story-memory 与后续兄弟键之间，一旦缩进写错会被 YAML 静默吞掉
     * （属性保持默认值、不报错），故在绑定层拦住
     */
    @Test
    void bindsTotalPrefixCharsFromRealYaml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("novel-gen",
                new FileSystemResource("../novel_factory-app/src/main/resources/novel-generation.yml"));
        assertTrue(sources.stream()
                        .anyMatch(source -> source.containsProperty("story.prompt.budget.total-prefix-chars")),
                "yml 缺少 story.prompt.budget.total-prefix-chars（或缩进错误："
                        + "budget 必须是 story.prompt 之下的兄弟块）");

        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        PromptBudgetProperties bound = binder.bind("story.prompt.budget", PromptBudgetProperties.class)
                .orElseThrow(() -> new AssertionError("story.prompt.budget 绑定失败"));
        assertNotNull(bound);
        assertTrue(bound.getTotalPrefixChars() > 0, "total-prefix-chars 应为正数");
    }

    private static String paragraphs(int paragraphChars, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("甲".repeat(paragraphChars));
        }
        return sb.toString();
    }

    /**
     * 2026-10-01 优先级重排的回归：预算紧张时「久远唤醒」(5) 必须先于
     * 「审校反馈/风格警示/疲劳词红线」(4) 被丢弃。
     *
     * <p>动机来自实测：第 8/12/13 章连续丢弃这三块行为指导，而超长章与禁泄违例连续三批反复发作——
     * 写手看不到"上一章已被指出的问题"，等于每章从头再犯。这四块的相对次序是本次修复的核心约束，
     * 因此钉成断言，避免日后调优先级时无意回退。
     */
    @Test
    void reviewFeedbackAndStyleGuardsOutliveRecallUnderBudget() {
        // 久远唤醒的优先级（ChapterMemoryService.MEMORY_PRIORITY_RECALL），由 4 降为 5
        final int recallPriority = 5;
        int review = PrefixBlock.REVIEW_FEEDBACK.toBlock("x").priority();
        int fatigue = PrefixBlock.FATIGUE_BLACKLIST.toBlock("x").priority();
        int style = PrefixBlock.STYLE_WARNING.toBlock("x").priority();

        assertTrue(review < recallPriority,
                "审校反馈必须高于久远唤醒(" + recallPriority + ")，实际 " + review);
        assertTrue(fatigue < recallPriority,
                "疲劳词红线必须高于久远唤醒(" + recallPriority + ")，实际 " + fatigue);
        assertTrue(style < recallPriority,
                "风格警示必须高于久远唤醒(" + recallPriority + ")，实际 " + style);
        // 仍应低于核心约束块（1~3），不得挤掉末态红线/禁泄清单
        assertTrue(review > PrefixBlock.SECRECY_GUARD.toBlock("x").priority(),
                "行为指导块不得挤掉禁泄清单");

        // 端到端：预算只够一块时，priority 4 的行为指导存活、priority 5 的久远唤醒被淘汰
        // 注意装配输出的是块**内容**（label 不进正文），故用可辨识的内容前缀断言
        properties.setTotalPrefixChars(30);
        String result = guard.assembleChapterPrefix(3, List.of(
                new Block("审校反馈", 4, true, "审校反馈" + "甲".repeat(20)),
                new Block("久远唤醒", 5, true, "久远唤醒" + "乙".repeat(20))));
        assertTrue(result.contains("审校反馈"), "行为指导块应存活，实际：" + result);
        assertFalse(result.contains("久远唤醒"), "久远唤醒应先被淘汰，实际：" + result);
    }

    /**
     * 情节模式黑名单必须与行为指导块同档，不得再排到"久远唤醒"之后。
     *
     * <p>它是**唯一**专门防重复情节的块，原先 priority 最低。实测旧系统某批前缀预算
     * 连续 99-100% 打满、**五章全部丢弃本块**——那批在提示词层面完全没有防重复约束，
     * 随即出现跨章重演同一场景（第 96/99 章）与同一条伏笔被排两遍（第 97/100 章）。
     */
    @Test
    void patternBlacklistOutlivesRecallAndFingerprintUnderBudget() {
        final int recallPriority = 5;
        int pattern = PrefixBlock.USED_PATTERN_BLACKLIST.toBlock("x").priority();
        int fingerprint = PrefixBlock.STYLE_FINGERPRINT.toBlock("x").priority();

        assertTrue(pattern < recallPriority,
                "情节模式黑名单必须高于久远唤醒(" + recallPriority + ")，实际 " + pattern);
        assertTrue(pattern > PrefixBlock.SECRECY_GUARD.toBlock("x").priority(),
                "但它不得挤掉禁泄清单这类核心事实约束");
        assertTrue(fingerprint <= recallPriority,
                "文风指纹不高于久远唤醒即可——它是正向激励，不承担纠错职责，实际 " + fingerprint);

        // 端到端：预算只够一块内容时，防重复块存活、久远唤醒先被淘汰
        properties.setTotalPrefixChars(30);
        String result = guard.assembleChapterPrefix(3, List.of(
                new Block("情节模式黑名单", pattern, true, "情节模式黑名单" + "甲".repeat(20)),
                new Block("久远唤醒", recallPriority, true, "久远唤醒" + "乙".repeat(20))));
        assertTrue(result.contains("情节模式黑名单"), "防重复块应存活，实际：" + result);
        assertFalse(result.contains("久远唤醒"), "久远唤醒应先被淘汰，实际：" + result);
    }

}