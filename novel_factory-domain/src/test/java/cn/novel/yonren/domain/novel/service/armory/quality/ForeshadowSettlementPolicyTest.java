package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清账审计口径测试（2026-10-02，P2a）。
 *
 * <p>反例取自真实裁决文本：两个阶段 19 条 VOID 里约 9–10 条的 reason 写着
 * 「已在第24章兑现」「已在第9章解决」「第17章已闭合」——剧情收了、账本没记。
 */
class ForeshadowSettlementPolicyTest {

    private static ForeshadowSettlementEntity.SettlementDecision decision(String decision, String reason) {
        return ForeshadowSettlementEntity.SettlementDecision.builder()
                .content("x").chapterNo(1).decision(decision).reason(reason).build();
    }

    private static ForeshadowSettlementEntity block(ForeshadowSettlementEntity.SettlementDecision... ds) {
        return ForeshadowSettlementEntity.builder()
                .stageNo(3).stageEndChapter(20).decisions(List.of(ds)).build();
    }

    /**
     * 静默兑现的口径必须**同时含裁决补认的「已完成」**（2026-10-10）。
     *
     * <p>裁决新增 DONE 之后，"账记为未填、实际已兑现"的条目不再走 {@code VOID + 【已兑现】}，
     * 而是判 DONE。若指标仍只数 VOID 那一支，它会随着 DONE 的启用**凭空变好**——
     * 而摘要层漏记的问题一点没变。被判 DONE 恰恰证明自动匹配没认出那次兑现，是同一份证据。
     */
    @Test
    @DisplayName("口径自洽：DONE 与 VOID-【已兑现】同属「摘要漏记回收」的证据")
    void doneIsPartOfTheOmissionEvidence() {
        ForeshadowSettlementEntity settlement = block(
                decision("VOID", "【已兑现】第12章已给出答案"),
                decision("DONE", "第15章已确认"),
                decision("RECOVER", "后续剧情可自然接住"),
                decision("PARKED", "指向身份暴露危机，是长线"));

        assertEquals(1, ForeshadowSettlementPolicy.silentPayoffCount(List.of(settlement)));
        assertEquals(1, ForeshadowSettlementPolicy.doneCount(List.of(settlement)));
        assertEquals(1, ForeshadowSettlementPolicy.voidCount(List.of(settlement)));
    }

    /** 真实措辞：这些都不是"闲笔"，而是"已兑现但漏记账" */
    @Test
    @DisplayName("looksSilentlyResolved：识别真实裁决里的静默兑现措辞")
    void recognizesRealSilentPayoffReasons() {
        assertTrue(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", "第17章已明确第二批下岗名单落实，该悬念已闭合，无需再追踪。")));
        assertTrue(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", "买书承诺已在第24章新华书店挑书情节中兑现，伏笔已自然消解。")));
        assertTrue(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", "父母对陆瑾瑜异常的认知已在第16章顾老头测试中自然承接并深化，该伏笔已被消化。")));
        assertTrue(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", "老顾见证异常已在第21章正式测试中升级为外部认证，该早期见证已被覆盖。")));
        assertTrue(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", "陆建军决裂后已在第18-19章卷土重来推销骗局并被识破，该伏笔已自然回收完毕。")));
    }

    /** 真弃置（走远/无连接点）**不得**被算成静默兑现——否则会高估记账缺口 */
    @Test
    @DisplayName("looksSilentlyResolved：真弃置不算静默兑现")
    void genuineVoidIsNotSilentPayoff() {
        assertFalse(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", "修车行老板提及的国道事故刹车失灵疑点与当前弄堂教育主线无关，剧情已走远。")), 
                "「已走远」是真弃置，不能算静默兑现");
        assertFalse(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", "属远期闲笔，后续章节目标未安排对应节点。")));
        assertFalse(ForeshadowSettlementPolicy.looksSilentlyResolved(decision("VOID", null)));
        assertFalse(ForeshadowSettlementPolicy.looksSilentlyResolved(null));
    }

    /** RECOVER 本来就承认线还活着，不算静默兑现 */
    @Test
    @DisplayName("looksSilentlyResolved：RECOVER 不算")
    void recoverIsNotSilentPayoff() {
        assertFalse(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("RECOVER", "可在第13章年底名单公布时兑现，与家庭生计转型自然衔接。")));
    }

    /** 主判据：reason 以【已兑现】开头（prompt 要求模型显式标注） */
    @Test
    @DisplayName("looksSilentlyResolved：主判据前缀")
    void prefixIsPrimaryCriterion() {
        assertTrue(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", ForeshadowSettlementPolicy.SILENT_PAYOFF_PREFIX + "第18章已由合同残片识破。")));
    }

    @Test
    @DisplayName("silentPayoffCount / voidCount：跨阶段聚合")
    void countsAcrossStages() {
        List<ForeshadowSettlementEntity> settlements = List.of(
                block(decision("VOID", "已在第9章解决"),
                        decision("VOID", "剧情已走远"),
                        decision("RECOVER", "可在第30章回收")),
                block(decision("VOID", "第17章已闭合"),
                        decision("VOID", "属家庭温情闲笔，无对应兑现节点")));

        assertEquals(2L, ForeshadowSettlementPolicy.silentPayoffCount(settlements));
        assertEquals(4L, ForeshadowSettlementPolicy.voidCount(settlements));
    }

    /**
     * **精度回归**：无章号引用的否定用法不得算成静默兑现。
     *
     * <p>真实假阳性（ch8）：「张阿婆说法在父母认真对待孩子异常后已融入日常，
     * **无独立回收必要**」——句尾"回收"是否定用法，被宽松模式误判。
     * 加"必须写明第几章"约束后不再命中，且语义自洽：真已兑现的线理应说得出在哪一章兑现。
     */
    @Test
    @DisplayName("looksSilentlyResolved：无章号引用的否定用法不算")
    void negationWithoutChapterRefIsNotSilentPayoff() {
        assertFalse(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", "张阿婆说法在父母认真对待孩子异常后已融入日常，无独立回收必要。")));
        assertFalse(ForeshadowSettlementPolicy.looksSilentlyResolved(
                decision("VOID", "银锁承诺属家庭温情闲笔，后续章节目标无对应兑现节点，弃置不影响主线。")));
    }

    @Test
    @DisplayName("空输入安全")
    void emptySafe() {
        assertEquals(0L, ForeshadowSettlementPolicy.silentPayoffCount(null));
        assertEquals(0L, ForeshadowSettlementPolicy.silentPayoffCount(List.of()));
        assertEquals(0L, ForeshadowSettlementPolicy.voidCount(null));
    }
}
