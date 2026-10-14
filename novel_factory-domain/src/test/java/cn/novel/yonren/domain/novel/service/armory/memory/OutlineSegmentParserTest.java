package cn.novel.yonren.domain.novel.service.armory.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大纲解析器测试（2026-10-05）：夹具取自新书《重启千禧》真实 chapterGoal 的代表性片段，
 * 覆盖全部格式变体——单值/区间年龄、单值/区间年份、季节与上下半年后缀、无年龄段、卷头。
 * 通用性验收：完全无结构的自由大纲解析为空（fail-soft），消费端自动豁免。
 */
class OutlineSegmentParserTest {

    /** 真实片段：覆盖卷头 + 全部时间标记变体 */
    private static final String REAL_CHAPTER_GOAL = """
            【卷一·巷子烟火与早慧神童】(1-90章)：1-10章 2002年（4岁） 宿醉醒来回到2002年，变成了4岁的幼童。借着孩童的身份撒泼打滚，拔掉钢笔巧妙阻止了父母签下导致前世破产的亲戚担保合同；11-20章 2003年（5岁） 充分利用幼年大脑发育的黄金时期汲取数理基础；31-40章 2005-2006年（7-8岁） 小学中年级，自学高中数学与算法基础；41-50章 2007年（9岁） 参加小学某数学竞赛，凭降维打击的思维夺冠，'巷子神童'名号打响；81-90章 2010年秋（12岁） 升入省重点初中，第一卷在初中蝉鸣中收尾。|| 【卷二·隐秘猎手与集训争锋】(91-180章)：141-150章 2015年（17岁） 出征国际奥林匹克（国际学科竞赛）斩获双料满分金牌；201-210章 2017年春 某量化基金在国内外证券与市场逆势暴赚；231-240章 2017年上半年 关键节点：以独立研究员身份参与到某研究机构的早期交流中。""";

    @Test
    @DisplayName("真实大纲：卷骨架 2 个、段全解析、时间标记逐项正确")
    void parse_realChapterGoal_fullyStructured() {
        OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(REAL_CHAPTER_GOAL);

        assertEquals(2, outline.volumes().size());
        assertEquals(1, outline.volumes().get(0).volumeNo());
        assertEquals(1, outline.volumes().get(0).startChapter());
        assertEquals(90, outline.volumes().get(0).endChapter());
        assertEquals(2, outline.volumes().get(1).volumeNo());
        assertEquals(91, outline.volumes().get(1).startChapter());

        // 段落齐全（夹具含 8 个章段）
        assertEquals(8, outline.segments().size());

        // 段 1-10：单值年份 + 单值年龄
        OutlineSegmentParser.OutlineSegment s1 = outline.segmentFor(5);
        assertEquals(1, s1.startChapter());
        assertEquals(10, s1.endChapter());
        assertEquals(2002, s1.yearStart());
        assertEquals(2002, s1.yearEnd());
        assertEquals(4, s1.ageStart());
        assertEquals(4, s1.ageEnd());
        assertTrue(s1.milestone().contains("担保合同"), s1.milestone());

        // 段 31-40：年份区间 + 年龄区间
        OutlineSegmentParser.OutlineSegment s3 = outline.segmentFor(35);
        assertEquals(2005, s3.yearStart());
        assertEquals(2006, s3.yearEnd());
        assertEquals(7, s3.ageStart());
        assertEquals(8, s3.ageEnd());
        assertTrue(s3.milestone().contains("高中数学"), s3.milestone());

        // 段 81-90：季节后缀进 label，年龄正常
        OutlineSegmentParser.OutlineSegment s8 = outline.segmentFor(85);
        assertEquals(2010, s8.yearStart());
        assertTrue(s8.timeLabel().contains("2010年"), s8.timeLabel());
        assertEquals(12, s8.ageStart());
        assertTrue(s8.milestone().contains("省重点初中"), s8.milestone());

        // 段 201-210：无年龄段——年龄为 null，里程碑保留
        OutlineSegmentParser.OutlineSegment noAge = outline.segmentFor(205);
        assertEquals(2017, noAge.yearStart());
        assertNull(noAge.ageStart());
        assertTrue(noAge.milestone().contains("逆势暴赚"), noAge.milestone());

        // 段 231-240：半年后缀 + 冒引导词的里程碑
        OutlineSegmentParser.OutlineSegment halfYear = outline.segmentFor(235);
        assertEquals(2017, halfYear.yearStart());
        assertTrue(halfYear.timeLabel().contains("2017年"), halfYear.timeLabel());
        assertTrue(halfYear.milestone().contains("早期交流"), halfYear.milestone());
    }

    @Test
    @DisplayName("segmentFor：段内命中、超出大纲覆盖返回 null（不冒充）")
    void segmentFor_hitsAndMisses() {
        OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(REAL_CHAPTER_GOAL);

        assertEquals(41, outline.segmentFor(45).startChapter());
        assertNull(outline.segmentFor(400), "超出大纲覆盖不得拿别的段冒充——进度对齐据此判'超出'");
        assertNull(outline.segmentFor(0));
    }

    @Test
    @DisplayName("无结构自由大纲：解析为空，消费端 fail-soft 豁免")
    void parse_freeFormOutline_returnsEmpty() {
        assertTrue(OutlineSegmentParser.parse("主角在巷子里慢慢长大，经历了许多温情的日常，最终成长为顶天立地的人。").isEmpty());
        assertTrue(OutlineSegmentParser.parse("").isEmpty());
        assertTrue(OutlineSegmentParser.parse(null).isEmpty());
    }

    @Test
    @DisplayName("部分可解析：有章号的段照常提取，其余跳过")
    void parse_partialStructure_keepsParseableSegments() {
        String mixed = "第一部分：主角觉醒。之后 5-8章 学院试炼，主角崭露头角。尾声：归乡。";
        OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(mixed);

        assertEquals(1, outline.segments().size());
        assertEquals(5, outline.segments().get(0).startChapter());
        assertEquals(8, outline.segments().get(0).endChapter());
        assertNull(outline.segments().get(0).ageStart(), "无年龄标记时为 null（不编造）");
        assertTrue(outline.segments().get(0).milestone().contains("学院试炼"));
    }
}
