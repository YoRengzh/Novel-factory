package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可核验文本测试（2026-09-22）。
 *
 * <p>这个类针对的是一个实测踩过的假阴性：阶段退出条件要求"某动作被文本**明确描写**"
 * （如"角色壬食指轻叩桌面两下"），而 {@code summary} 按设计只记主干、明确排除动作细节——
 * 于是正文明明写着「指尖在桌面上无意识地轻叩了两下」，核验在摘要里找不到任何字样，只能判未达成。
 * {@code verifiableDetails} 就是为补这个粒度而加的。
 */
class ChapterMemoryTextTest {

    @Test
    void includesSummaryStatesAndVerifiableDetails() {
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder()
                .chapterNo(3).title("第三章").summary("角色壬在会上指出方案的可维护性风险。")
                .characterStates(List.of(new ChapterSummaryEntity.StateEntry("角色壬", "拒绝拆组", "证据")))
                .verifiableDetails(List.of("指尖在桌面上无意识地轻叩了两下"))
                .build();

        String text = ChapterMemoryText.of(summary);

        assertTrue(text.contains("角色壬在会上指出方案的可维护性风险"));
        assertTrue(text.contains("角色壬：拒绝拆组"));
        assertTrue(text.contains("【可核验细节】"));
        assertTrue(text.contains("指尖在桌面上无意识地轻叩了两下"),
                "细节必须进核验文本——否则细节型证据永远核不到");
    }

    @Test
    void handlesNullAndMissingDetailsGracefully() {
        assertEquals("", ChapterMemoryText.of(null));
        assertFalse(ChapterMemoryText.of(ChapterSummaryEntity.builder().chapterNo(1).build())
                .contains("【可核验细节】"), "没有细节时不输出空标题");
    }

    @Test
    void blankDetailsAreSkipped() {
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder()
                .chapterNo(1).summary("正文")
                .verifiableDetails(Arrays.asList("  ", null, "有效细节"))
                .build();

        String text = ChapterMemoryText.of(summary);

        assertTrue(text.contains("有效细节"));
        assertTrue(text.contains("【可核验细节】（有效细节）"), "空白项不得进文本");
    }
}
