package cn.novel.yonren.types.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bible 解析测试：口径宽容（未知标签忽略、缺失字段为 null、CRLF 兼容），
 * 但值本身必须逐字还原——解析偏差不会报错，只会在续写时表现为"设定莫名丢失"。
 * 长文本必须保留段落、段内冒号与行尾样式；重复标签必须报告歧义而不是静默取值。
 */
class StoryBibleParserTest {

    /** 磁盘上 story-bible.txt 的同构样本（完全合成） */
    private static final String SAMPLE = String.join("\n",
            "小说名称: 《示例：归零之城》",
            "小说类型: 悬疑/推理/都市",
            "小说格调: 冷硬推理，重证据链与人物动机",
            "世界观: 沿海旧港被一桩旧案封锁，居民彼此戒备。",
            "叙述视角: 第三人称限知视角（主跟随主角）",
            "目标读者: 男频/悬疑/刑侦",
            "语气基调: 压抑克制，冷静收束",
            "主人公: 主角甲，30岁，外表寡言，实则擅长物证比对。",
            "故事概述: 主角甲从旧港打捞出一件带焚字纹的袖标，牵出多年前的旧案。",
            "章节数量: 180",
            "章节目标: 1-30章 旧案重启：证据与封锁",
            "世界ID: synthetic-world-01",
            "语言: 中文",
            "总字数: 2000",
            "存在金手指: true",
            "金手指名称: 物证回溯",
            "金手指使用间隔: 3");

    @Test
    void parse_extractsEverySettingField() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(SAMPLE);

        assertEquals("《示例：归零之城》", bible.novelTitle());
        assertEquals("悬疑/推理/都市", bible.theme());
        assertEquals("冷硬推理，重证据链与人物动机", bible.style());
        assertEquals("沿海旧港被一桩旧案封锁，居民彼此戒备。", bible.worldSetting());
        assertEquals("第三人称限知视角（主跟随主角）", bible.perspective());
        assertEquals("男频/悬疑/刑侦", bible.targetAudience());
        assertEquals("压抑克制，冷静收束", bible.tone());
        assertEquals("主角甲，30岁，外表寡言，实则擅长物证比对。", bible.protagonist());
        assertEquals("主角甲从旧港打捞出一件带焚字纹的袖标，牵出多年前的旧案。", bible.outline());
        assertEquals("1-30章 旧案重启：证据与封锁", bible.chapterGoal());
        assertEquals("synthetic-world-01", bible.worldId());
        assertEquals(Boolean.TRUE, bible.hasCheatMechanism());
        assertEquals("物证回溯", bible.cheatMechanismName());
        assertEquals(3, bible.cheatUsageInterval());
    }

    @Test
    void parse_onlySplitsOnFirstAsciiColon_keepingFullWidthColonsInValues() {
        // 书名、章节目标里都有全角冒号；用 split(":") 会把值切成两半
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(SAMPLE);

        assertEquals("《示例：归零之城》", bible.novelTitle());
        assertEquals("1-30章 旧案重启：证据与封锁", bible.chapterGoal());
    }

    @Test
    void parse_toleratesCrlf() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(SAMPLE.replace("\n", "\r\n"));

        // bible 用 System.lineSeparator() 写入，Windows 上是 CRLF，行尾不可残留 \r 混进值里
        assertEquals("《示例：归零之城》", bible.novelTitle());
        assertEquals("synthetic-world-01", bible.worldId());
    }

    @Test
    void parse_missingLabelsBecomeNull_insteadOfThrowing() {
        // 老故事可能由更早版本写入，标签集合未必齐全
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse("小说名称: 只有书名\n");

        assertEquals("只有书名", bible.novelTitle());
        assertNull(bible.theme());
        assertNull(bible.outline());
        assertNull(bible.worldId());
        assertNull(bible.hasCheatMechanism());
        assertNull(bible.cheatUsageInterval());
    }

    @Test
    void parse_blankOrNullInput_yieldsEmptySnapshot() {
        StoryBibleParser.Snapshot fromNull = StoryBibleParser.parse(null);
        StoryBibleParser.Snapshot fromBlank = StoryBibleParser.parse("   \n\n  ");

        assertNull(fromNull.novelTitle());
        assertNull(fromBlank.novelTitle());
        assertNull(fromBlank.hasCheatMechanism());
    }

    @Test
    void parse_ignoresUnknownLabelsAndMalformedLines() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(String.join("\n",
                "小说名称: 甲",
                "一个没有冒号的行",
                ": 只有冒号没有标签",
                "未来新增的字段: 值",
                "空值字段:    ",
                "小说类型: 乙"));

        assertEquals("甲", bible.novelTitle());
        assertEquals("乙", bible.theme());
    }

    @Test
    void parse_cheatMechanismDistinguishesAbsentFromExplicitFalse() {
        // null = bible 没写（老故事）；false = 明确声明"没有金手指"。两者语义不同，不能合并
        assertNull(StoryBibleParser.parse("小说名称: 甲").hasCheatMechanism());
        assertEquals(Boolean.FALSE,
                StoryBibleParser.parse("小说名称: 甲\n存在金手指: false").hasCheatMechanism());
        assertEquals(Boolean.TRUE,
                StoryBibleParser.parse("小说名称: 甲\n存在金手指: TRUE").hasCheatMechanism());
    }

    @Test
    void parse_cheatIntervalTakesLeadingDigits() {
        assertEquals(5, StoryBibleParser.parse("金手指使用间隔: 5（每五章一次）").cheatUsageInterval());
        assertEquals(3, StoryBibleParser.parse("金手指使用间隔: 3").cheatUsageInterval());
        // 完全取不到数字时宁可返回 null，也不要瞎猜一个间隔（3/5 章规则会因此错位）
        assertNull(StoryBibleParser.parse("金手指使用间隔: 视剧情而定").cheatUsageInterval());
    }

    @Test
    void parse_leadingTrailingWhitespaceIsTrimmed() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse("小说名称:    甲   \n小说类型:乙\n");

        assertEquals("甲", bible.novelTitle());
        assertEquals("乙", bible.theme());
    }

    /**
     * 主要人物档案（代词与既定事实）必须能读回来。
     *
     * 它是防"代词写错、志愿写错"的事实锚：读取侧与写入侧共用 {@code LABEL_CHARACTERS} 常量，
     * 本用例守住"写进去能读出来"——值解析不出来不报错，只会表现为设定悄悄丢失。
     */
    @Test
    void parse_characterProfilesRoundTrip() {
        String roster = "角色甲（男，用「他」）；角色乙（女，用「她」）。既定事实（不得改写）：角色乙报名志愿二班";

        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(
                "小说名称: 甲\n主人公: 角色甲\n主要人物: " + roster + "\n故事概述: 乙\n");

        assertEquals(roster, bible.characters());
        assertEquals("角色甲", bible.protagonist());
        assertEquals("乙", bible.outline(), "人物档案是单行值，不能把后面的行吃掉");
    }

    /** 老 bible 没有这一行：返回 null（调用方据此不渲染，而不是渲染一个空栏） */
    @Test
    void parse_characterProfilesNullForLegacyBible() {
        assertNull(StoryBibleParser.parse("小说名称: 甲\n").characters());
    }

    /**
     * 老写入路径曾把 Java null 写成字面量 "null"（典型：主要人物: null）。
     * 解析侧必须把字面量 "null" 当缺失，而不是当成真实设定。
     */
    @Test
    void parse_literalNullValueIsTreatedAsMissing() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(String.join("\n",
                "小说名称: 甲",
                "主要人物: null",
                "世界ID: null",
                "小说类型: 乙"));

        assertNull(bible.characters(), "字面量 null 不是人物档案");
        assertNull(bible.worldId(), "字面量 null 不是世界 ID");
        assertEquals("甲", bible.novelTitle());
        assertEquals("乙", bible.theme());
    }

    /** 值里包含 null 子串的合法设定不能被误伤 */
    @Test
    void parse_valueContainingNullSubstringIsKept() {
        StoryBibleParser.Snapshot bible = StoryBibleParser.parse(
                "小说名称: 甲\n主要人物: nullpointer 观察者（男，用「他」）\n");

        assertEquals("nullpointer 观察者（男，用「他」）", bible.characters());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void parse_preservesMultiParagraphOutlineInsteadOfFirstLine(String lineBreak) {
        // 完全合成样本：旧解析只留下首行，整段实际更长，后续段落与结局不可丢。
        String lfOutline = "起".repeat(203) + "\n\n关键发展:" + "承".repeat(300)
                + "\n\n结局方向：" + "合".repeat(308) + "结局不可丢";
        assertEquals(203, lfOutline.indexOf('\n'));
        String outline = lfOutline.replace("\n", lineBreak);
        String goal = "第一目标" + lineBreak + lineBreak + "第二目标:仍须保留";
        var result = StoryBibleParser.parseDiagnostic(String.join(lineBreak,
                "小说名称: 合成多段回归",
                "故事概述: " + outline,
                "章节数量: 9",
                "章节目标: " + goal,
                "世界ID: synthetic-world"));

        assertEquals(outline, result.snapshot().outline());
        assertEquals(goal, result.snapshot().chapterGoal());
        assertEquals("synthetic-world", result.snapshot().worldId());
        assertTrue(result.diagnostics().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void parse_preservesAllLongTextFields(String lineBreak) {
        String value = "第一段: ASCII 冒号" + lineBreak + lineBreak
                + "  关键发展:第二段保留缩进和行尾空格  " + lineBreak + "末行";
        var result = StoryBibleParser.parseDiagnostic(String.join(lineBreak,
                "小说名称: 合成回归",
                "小说类型: 类型",
                "小说格调: " + value,
                "世界观: " + value,
                "叙述视角: " + value,
                "目标读者: " + value,
                "语气基调: " + value,
                "主人公: " + value,
                "主要人物: " + value,
                "故事概述: " + value,
                "章节数量: 8",
                "章节目标: " + value,
                "世界ID: synthetic-world",
                "语言: 中文",
                "总字数: 2000",
                "存在金手指: true",
                "金手指名称: " + value,
                "金手指使用间隔: 3"));
        var bible = result.snapshot();

        assertEquals(value, bible.style());
        assertEquals(value, bible.worldSetting());
        assertEquals(value, bible.perspective());
        assertEquals(value, bible.targetAudience());
        assertEquals(value, bible.tone());
        assertEquals(value, bible.protagonist());
        assertEquals(value, bible.characters());
        assertEquals(value, bible.outline());
        assertEquals(value, bible.chapterGoal());
        assertEquals(value, bible.cheatMechanismName());
        assertEquals("第一段: ASCII 冒号", bible.mechanismIdentifier(),
                "机制检测只用首行名称，后续说明保留在原字段中");
        assertEquals(Boolean.TRUE, bible.hasCheatMechanism());
        assertEquals(3, bible.cheatUsageInterval());
        assertEquals("synthetic-world", bible.worldId());
        assertFalse(result.hasAmbiguities());
    }

    @Test
    void parse_onlyExactKnownAsciiColonLabelsEndParagraphs() {
        String value = String.join("\n", "正文第一行", "",
                "关键发展:内容", "未来新增的字段: 段内小标题不单独开字段",
                "世界观补充: 前缀相同但不是标签", "故事概述：全角冒号是正文",
                ": 没有标签仍属于当前段落", "句中提到主人公: 仍为内容");
        var result = StoryBibleParser.parseDiagnostic(
                "未知的顶层字段: 忽略\n世界观: " + value + "\n叙述视角: 第三人称\n");

        assertEquals(value, result.snapshot().worldSetting());
        assertEquals("第三人称", result.snapshot().perspective());
        assertNull(result.snapshot().outline());
        assertNull(result.snapshot().protagonist());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void parse_metadataAndEmptyLabelsAreStillBoundaries() {
        var bible = StoryBibleParser.parse(String.join("\n",
                "故事概述: 第一段", "", "第二段",
                "章节数量: null", "未知字段: 忽略，不归入故事概述",
                "章节目标: 第一目标", "第二目标",
                "语言: 中文", "总字数: 2000",
                "主要人物: ", "世界观: 世界第一段", "世界第二段",
                "存在金手指: false", "世界ID: synthetic-world"));

        assertEquals("第一段\n\n第二段", bible.outline());
        assertEquals("第一目标\n第二目标", bible.chapterGoal());
        assertEquals("世界第一段\n世界第二段", bible.worldSetting());
        assertNull(bible.characters());
        assertEquals(Boolean.FALSE, bible.hasCheatMechanism());
        assertEquals("synthetic-world", bible.worldId());
    }

    @Test
    void parse_acceptsValueStartingOnFollowingLine() {
        var bible = StoryBibleParser.parse("世界观: \r\n\r\n  第一段\r\n\r\n    第二段  \r\n主人公: 甲");

        assertEquals("第一段\r\n\r\n    第二段", bible.worldSetting());
        assertEquals("甲", bible.protagonist());
    }

    @Test
    void parse_preservesMixedLineEndingsAndInternalWhitespace() {
        String value = "第一段  \r\n\r\n  第二段: 内容 \n\n第三段\r末行";
        var bible = StoryBibleParser.parse("故事概述: " + value + "\r\n章节数量: 3\n");

        assertEquals(value, bible.outline());
    }

    @Test
    void parse_nullPlaceholderNeverRevivesFromContinuationOrUnknownLabel() {
        var bible = StoryBibleParser.parse(String.join("\n",
                "主要人物: NuLl", "无主续行不能复活人物档案", "关键发展: 同样不能复活",
                "世界ID: NULL", "故事概述: 真实第一段", "真实第二段"));

        assertNull(bible.characters());
        assertNull(bible.worldId());
        assertEquals("真实第一段\n真实第二段", bible.outline());
    }

    @Test
    void parse_nullInsideNonNullParagraphIsNotDiscarded() {
        var bible = StoryBibleParser.parse("世界观: 有效第一段\nnull\n\n有效末段\n主要人物: \nnull\n");

        assertEquals("有效第一段\nnull\n\n有效末段", bible.worldSetting());
        assertNull(bible.characters());
    }

    @ParameterizedTest
    @ValueSource(strings = {"候选甲", "候选乙", "", "null"})
    void parseDiagnostic_duplicateLabelsNeverPickFirstOrLastValue(String secondValue) {
        String text = "世界观: 候选甲\n\n第一值后半段\n小说名称: 未冲突\n世界观: "
                + secondValue + "\n主人公: 独立主角";
        var result = StoryBibleParser.parseDiagnostic(text);

        assertNull(result.snapshot().worldSetting());
        assertEquals("未冲突", result.snapshot().novelTitle());
        assertEquals("独立主角", result.snapshot().protagonist());
        assertEquals(List.of(new StoryBibleParser.Diagnostic(StoryBibleParser.DiagnosticCode.DUPLICATE_LABEL,
                StoryBibleParser.LABEL_WORLD_SETTING, 1, 5)), result.diagnostics());
        assertTrue(result.hasAmbiguities());
        assertEquals(result.snapshot(), StoryBibleParser.parse(text));
    }

    @Test
    void parseDiagnostic_identicalDuplicateValuesAreAlsoAmbiguous() {
        var result = StoryBibleParser.parseDiagnostic("章节目标: 相同\n世界ID: synthetic-world\n章节目标: 相同");

        assertNull(result.snapshot().chapterGoal());
        assertTrue(result.hasAmbiguities());
        assertEquals(List.of(new StoryBibleParser.Diagnostic(StoryBibleParser.DiagnosticCode.DUPLICATE_LABEL,
                StoryBibleParser.LABEL_CHAPTER_GOAL, 1, 3)), result.diagnostics());
    }

    @Test
    void parseDiagnostic_duplicateBoundaryAlsoRejectsPotentiallyTruncatedNeighbors() {
        var result = StoryBibleParser.parseDiagnostic(String.join("\r\n",
                "世界观: SECRET_FIRST_PARAGRAPH", "第一段续行",
                "主人公: SECRET_EMBEDDED_HEADING", "可能仍属于世界观的段落",
                "叙述视角: SECRET_PERSPECTIVE", "主人公: SECRET_REAL_FIELD",
                "主要人物: 独立人物档案", "故事概述: 未冲突的概述", "章节数量: 10"));

        assertNull(result.snapshot().protagonist());
        assertNull(result.snapshot().worldSetting(), "不把被同名小标题截断的首段当完整设定");
        assertNull(result.snapshot().perspective());
        assertEquals("独立人物档案", result.snapshot().characters());
        assertEquals("未冲突的概述", result.snapshot().outline());
        assertEquals(List.of(
                new StoryBibleParser.Diagnostic(StoryBibleParser.DiagnosticCode.DUPLICATE_LABEL,
                        StoryBibleParser.LABEL_PROTAGONIST, 3, 6),
                new StoryBibleParser.Diagnostic(StoryBibleParser.DiagnosticCode.AMBIGUOUS_FIELD_BOUNDARY,
                        StoryBibleParser.LABEL_WORLD_SETTING, 1, 3),
                new StoryBibleParser.Diagnostic(StoryBibleParser.DiagnosticCode.AMBIGUOUS_FIELD_BOUNDARY,
                        StoryBibleParser.LABEL_PERSPECTIVE, 5, 6)), result.diagnostics());
        assertFalse(result.diagnostics().toString().contains("SECRET_"));
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
    }

    @Test
    void parseDiagnostic_duplicateMetadataAlsoReportsAmbiguousBoundary() {
        var result = StoryBibleParser.parseDiagnostic("章节数量: 3\n章节目标: 目标\n章节数量: 5\n世界ID: world");

        assertTrue(result.hasAmbiguities());
        assertNull(result.snapshot().chapterGoal());
        assertEquals("world", result.snapshot().worldId());
        assertEquals(List.of(
                new StoryBibleParser.Diagnostic(StoryBibleParser.DiagnosticCode.DUPLICATE_LABEL,
                        StoryBibleParser.LABEL_CHAPTER_COUNT, 1, 3),
                new StoryBibleParser.Diagnostic(StoryBibleParser.DiagnosticCode.AMBIGUOUS_FIELD_BOUNDARY,
                        StoryBibleParser.LABEL_CHAPTER_GOAL, 2, 3)), result.diagnostics());
    }

    @Test
    void parseDiagnostic_nullFirstOccurrenceAndThirdOccurrenceDoNotHideConflict() {
        var result = StoryBibleParser.parseDiagnostic(
                "主要人物: null\n小说名称: 标题\n主要人物: 第二值\n小说类型: 类型\n主要人物: 第三值");

        assertNull(result.snapshot().characters());
        assertEquals(List.of(
                new StoryBibleParser.Diagnostic(StoryBibleParser.DiagnosticCode.DUPLICATE_LABEL,
                        StoryBibleParser.LABEL_CHARACTERS, 1, 3),
                new StoryBibleParser.Diagnostic(StoryBibleParser.DiagnosticCode.DUPLICATE_LABEL,
                        StoryBibleParser.LABEL_CHARACTERS, 1, 5)), result.diagnostics());
    }
}
