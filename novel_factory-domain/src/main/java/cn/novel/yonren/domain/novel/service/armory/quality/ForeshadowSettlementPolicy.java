package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.regex.Pattern;


public final class ForeshadowSettlementPolicy {

    /** 静默兑现标记前缀：prompt 要求模型为"其实已兑现"的条目显式加此标记 */
    public static final String SILENT_PAYOFF_PREFIX = "【已兑现】";

    private static final Pattern SILENT_PAYOFF_PATTERN = Pattern.compile(
            "第\\s*\\d+.{0,40}(兑现|闭合|消化|覆盖|回收|解决|承接|收束|了结)");

    private ForeshadowSettlementPolicy() {
    }

    /**
     * 该裁决是否为"静默兑现"：被判 VOID，但理由表明它其实已在正文中兑现/承接。
     * 非 VOID 裁决恒为 false（RECOVER 本来就承认它还活着）。
     */
    public static boolean looksSilentlyResolved(ForeshadowSettlementEntity.SettlementDecision decision) {
        if (decision == null
                || !ForeshadowSettlementEntity.DECISION_VOID.equals(decision.getDecision())) {
            return false;
        }
        String reason = decision.getReason();
        if (StringUtils.isBlank(reason)) {
            return false;
        }
        return reason.contains(SILENT_PAYOFF_PREFIX)
                || SILENT_PAYOFF_PATTERN.matcher(reason).find();
    }


    public static long silentPayoffCount(List<ForeshadowSettlementEntity> settlements) {
        if (settlements == null || settlements.isEmpty()) {
            return 0L;
        }
        return settlements.stream()
                .filter(s -> s != null && s.getDecisions() != null)
                .flatMap(s -> s.getDecisions().stream())
                .filter(ForeshadowSettlementPolicy::looksSilentlyResolved)
                .count();
    }

    /** 全部结算里的 VOID 总数（供静默兑现占比归因） */
    public static long voidCount(List<ForeshadowSettlementEntity> settlements) {
        if (settlements == null || settlements.isEmpty()) {
            return 0L;
        }
        return settlements.stream()
                .filter(s -> s != null && s.getDecisions() != null)
                .flatMap(s -> s.getDecisions().stream())
                .filter(d -> d != null && ForeshadowSettlementEntity.DECISION_VOID.equals(d.getDecision()))
                .count();
    }

    /**
     * 全部结算里被判 DONE（已完成）的条数——与静默兑现是同一现象，只是出口换了。
     *
     * <p>裁决新增 DONE 后，"账记为未填、实际已兑现"的条目不再走 VOID + 【已兑现】，
     * 而是判 DONE 并回填兑现章号。若指标仍只数 VOID 那一支，这条指标会随着 DONE 的启用
     * 凭空变好——而摘要层漏记的问题一点没变。它被判 DONE 恰恰证明自动匹配没认出那次兑现，
     * 是漏记的另一份证据。
     */
    public static long doneCount(List<ForeshadowSettlementEntity> settlements) {
        return countOf(settlements, ForeshadowSettlementEntity.DECISION_DONE);
    }

    private static long countOf(List<ForeshadowSettlementEntity> settlements, String decision) {
        if (settlements == null || settlements.isEmpty()) {
            return 0L;
        }
        return settlements.stream()
                .filter(s -> s != null && s.getDecisions() != null)
                .flatMap(s -> s.getDecisions().stream())
                .filter(d -> d != null && decision.equals(d.getDecision()))
                .count();
    }
}
