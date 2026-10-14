package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 段落结构兜底测试（2026-10-01）：复现第 1–10 章批次 ch2/ch10 的单段巨块。
 */
class ParagraphStructurePolicyTest {

    private static final String SENTENCE = "他把碗放下，看了一眼窗外，天已经黑透了。";

    /** 构造一段无换行的巨块正文 */
    private static String blob(int sentences) {
        return SENTENCE.repeat(sentences);
    }

    @Test
    void singleBlob_isReparagraphed() {
        String raw = blob(200); // 200 * 26 ≈ 5200 字，0 换行
        assertEquals(1, raw.split("\n").length);

        String fixed = ParagraphStructurePolicy.reparagraph(raw);
        assertNotSame(raw, fixed);
        int lines = fixed.split("\n\n", -1).length;
        assertTrue(lines > 10, "应切出多段，实际 " + lines + " 段");
        // 一个字都不能改（除插入的换行）
        assertEquals(raw.replace("\n", ""), fixed.replace("\n", ""));
    }

    @Test
    void reparagraph_doesNotAlterText() {
        String raw = blob(120);
        String fixed = ParagraphStructurePolicy.reparagraph(raw);
        assertEquals(raw, fixed.replace("\n\n", "").replace("\n", ""));
    }

    @Test
    void normalParagraphs_areLeftUntouched() {
        // 每段 ~26 字、篇幅 ~2600 字 → 约 100 段，远高于 2600/300≈8 的下限
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append(SENTENCE).append(SENTENCE).append("\n\n");
        }
        String raw = sb.toString();
        assertSame(raw, ParagraphStructurePolicy.reparagraph(raw), "换行充足时不得改动正文");
        assertFalse(ParagraphStructurePolicy.needsReparagraph(raw));
    }

    @Test
    void shortContent_isSkipped() {
        String raw = blob(10); // 260 字，低于 800 字门槛
        assertSame(raw, ParagraphStructurePolicy.reparagraph(raw));
    }

    @Test
    void checkStructureCollapse_flagsBlobAsMinor() {
        String raw = blob(120);
        var issues = ParagraphStructurePolicy.checkStructureCollapse(
                raw, ParagraphStructurePolicy.paragraphLimit(raw));
        assertEquals(1, issues.size());
        assertEquals("MINOR", issues.get(0).getSeverity());
        assertEquals("aesthetic", issues.get(0).getDimension());
    }

    @Test
    void checkStructureCollapse_ignoresHealthyText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append(SENTENCE).append(SENTENCE).append("\n\n");
        }
        String raw = sb.toString();
        assertTrue(ParagraphStructurePolicy.checkStructureCollapse(
                raw, ParagraphStructurePolicy.paragraphLimit(raw)).isEmpty());
    }

    /**
     * 回归：本次修复的直接动机——单段巨块会让对白行占比失真。
     *
     * <p>用真实形态：叙述为主、对白穿插。巨块（0 换行）时占比恒为 1.0；
     * 重排后叙述段与对白段分开，占比应回落到对白实际占的份额。
     */
    @Test
    void reparagraph_repairsDialogueRatioInvariant() {
        String narration = "他把碗放下，看了一眼窗外，天已经黑透了，屋里的煤球炉只剩下一点暗红的微响，"
                + "巷子里有人在收晾在外面的衣裳，竹竿磕在窗框上，一下，又一下，听得人心里发空。";
        String speech = "「你来了。」";
        // 叙述:对白 ≈ 8:1，贴近真实正文
        String dialogueBlob = (narration.repeat(4) + speech).repeat(10);

        double before = DialogueRatioPolicy.ratioOf(dialogueBlob);
        assertEquals(1.0, before, 1e-9, "巨块含引号时占比恒为 1.0——正是本次要修的失真");

        String fixed = ParagraphStructurePolicy.reparagraph(dialogueBlob);
        double after = DialogueRatioPolicy.ratioOf(fixed);
        assertTrue(after < 1.0, "重排后对白行占比应回落，实际 " + after);
        assertTrue(after > 0.0, "对白行仍应存在，实际 " + after);
    }

    /**
     * 切句不得撕开引号：{@code 「你来了。」他说。} 是完整台词，
     * 不允许在台词内的 {@code 。} 处断开留下孤立 {@code 」}。
     */
    @Test
    void reparagraph_neverSplitsInsideQuotes() {
        // 大量台词 + 少量叙述，故意制造"引号内句号"的诱因
        String raw = ("小李提高声音：「这事儿我不认。你爱怎么办就怎么办。」屋里一时没人接话。"
                + "老王把烟按灭：「不认也得认。」").repeat(20);
        String fixed = ParagraphStructurePolicy.reparagraph(raw);
        for (String para : fixed.split("\n\n")) {
            assertFalse(para.trim().equals("」"), "出现了被撕开的孤立右引号");
            // 每个段落内引号必须成对
            long left = para.chars().filter(c -> c == '「').count();
            long right = para.chars().filter(c -> c == '」').count();
            assertEquals(left, right, "段落内引号不成对：" + para);
        }
    }

    /** 纯叙述巨块（无任何引号）同样应被重排 */
    @Test
    void reparagraph_handlesPureNarration() {
        String raw = blob(150);
        String fixed = ParagraphStructurePolicy.reparagraph(raw);
        assertTrue(fixed.split("\n\n", -1).length > 20);
        assertEquals(0.0, DialogueRatioPolicy.ratioOf(fixed), 1e-9);
    }
}
