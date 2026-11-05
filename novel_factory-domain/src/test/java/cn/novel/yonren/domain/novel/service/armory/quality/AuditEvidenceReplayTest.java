package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审校 BLOCKING 证据校验的**离线重放**。
 *
 * 样本来自真实产出：故事 {@code 20260917-story-0001} 第 6-17 章有 8 条 BLOCKING 因
 * 「证据校验未通过」被整条降级为 MINOR（不进修订、不落债）。引文原样取自
 * {@code data/log/log_info.log}（当时日志按 50 字截断，故只取未被截断的 4 条做重放）。
 *
 * 目的：确认新口径「分段多数命中」（{@link EvidenceMatch#segmentsContained}）
 * 只放行**正文里真实存在、只是引法不是连续串**的那些引文，
 * 而**纯账本摘录 / 转述式引文 / 编造引文**仍被拒。
 * 这条边界不能凭直觉定——所以用真实样本锁住。
 */
class AuditEvidenceReplayTest {

    /** 模型引用习惯导致的"非连续"引文：正文里的字都在，只是补了主语或丢了前置分句 */
    private static final String EVIDENCE_MISSING_PREFIX_CLAUSE =
            "陈默从怀里摸出一枚碎灵石。石头入手冰凉，但在靠近洞口三丈的位置时，指尖传来一阵细微的刺痛感";
    private static final String EVIDENCE_SUBJECT_REWRITTEN =
            "陈默回到住处……拿起那块刻有‘癸未’的铜牌。铜牌背面水浸后显现出的字迹依旧清晰";
    private static final String EVIDENCE_ELLIPSIS_JOINED =
            "阿禾昏迷前抓落的碎灵石滚在地上……陈默侧身翻滚，右手撑地时摸到了那块滚落在墙角的碎灵石。";

    /** 纯账本摘录：不含任何正文片段，降级是**正确**的（不能被新口径放行） */
    private static final String EVIDENCE_LEDGER_EXCERPT =
            "账本：碎灵石（位置：陈默手中；被陈默攥紧用于锚定触觉启动感官剥离术）";

    private static boolean isRealStoryAvailable() {
        return Files.isDirectory(STORY_DIR);
    }

    private static final Path STORY_DIR = Paths.get("../docs/workspace/stories/20260917-story-0001/chapters");

    private String chapter(int no) throws Exception {
        return Files.readString(STORY_DIR.resolve(String.format("chapter-%04d.txt", no)), StandardCharsets.UTF_8);
    }

    @Test
    void replay_matrix_locksTheOldAndNewOutcomeOnRealSamples() throws Exception {
        Assumptions.assumeTrue(isRealStoryAvailable(), "样本故事目录不存在，跳过重放");

        // 旧=整串口径；新=分段多数命中。左列是"应该被修复的误伤"，右列是"必须继续拒收"
        assertMatrix("第12章 丢前置分句", chapter(12), EVIDENCE_MISSING_PREFIX_CLAUSE, false, true);
        assertMatrix("第11章 省略号拼段", chapter(11), EVIDENCE_SUBJECT_REWRITTEN, false, true);
        assertMatrix("第8章  省略号拼段", chapter(8), EVIDENCE_ELLIPSIS_JOINED, false, true);
        assertMatrix("第9章  纯账本摘录", chapter(9), EVIDENCE_LEDGER_EXCERPT, false, false);
    }

    private void assertMatrix(String label, String content, String evidence,
                              boolean expectedOld, boolean expectedNew) {
        String normalized = EvidenceMatch.normalize(content);
        boolean old = EvidenceMatch.contained(evidence, normalized);
        boolean now = EvidenceMatch.segmentsContained(evidence, normalized);
        assertEquals(expectedOld, old, label + " 旧口径");
        assertEquals(expectedNew, now, label + " 新口径");
    }

    @Test
    void segmentsContained_acceptsVerbatimButDiscontinuousQuotes() throws Exception {
        Assumptions.assumeTrue(isRealStoryAvailable(), "样本故事目录不存在，跳过重放");

        String ch12 = EvidenceMatch.normalize(chapter(12));
        // 旧口径：整串对不上（正文是「陈默停下脚步，从怀里摸出一枚碎灵石。」）→ 整条 BLOCKING 被误降级
        assertFalse(EvidenceMatch.contained(EVIDENCE_MISSING_PREFIX_CLAUSE, ch12),
                "前置分句被丢掉的引文，旧口径本就判不成立（这正是误伤的成因）");
        // 新口径：长段半数以上可逐字定位 → 证据成立
        assertTrue(EvidenceMatch.segmentsContained(EVIDENCE_MISSING_PREFIX_CLAUSE, ch12),
                "补/丢分句只是表述差异，正文长段确实存在，不应据此作废整条证据");
    }

    @Test
    void segmentsContained_stillRejectsLedgerExcerpts() throws Exception {
        Assumptions.assumeTrue(isRealStoryAvailable(), "样本故事目录不存在，跳过重放");

        String ch9 = EvidenceMatch.normalize(chapter(9));
        assertFalse(EvidenceMatch.segmentsContained(EVIDENCE_LEDGER_EXCERPT, ch9),
                "纯账本摘录里没有可锚定的正文长段，必须继续拒收——放宽的是表述，不是事实有无");
    }

    @Test
    void segmentsContained_rejectsFabricatedEvidence() {
        String content = EvidenceMatch.normalize(
                "陈默停下脚步，从怀里摸出一枚碎灵石。石头入手冰凉，指尖传来一阵刺痛。");
        String fabricated = "他握着那柄祖传古剑，剑身映出漫天星辰，心中默念着无上剑诀";
        assertFalse(EvidenceMatch.segmentsContained(fabricated, content), "编造引文必须被拒");
    }
}
