package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

/**
 * 卷末清账结算实体：阶段出口对长期未填伏笔的逐条裁决台账。
 * 单独落盘 memory/foreshadow-settlements.json，不动 summaries.json 的 schema——
 * 老故事续写依赖其纯数组格式，加字段不如单开文件稳（同 quality-debts.json 决策）。
 * VOID 条目由记忆层从伏笔账出账；RECOVER 条目由规划 prompt 注入限期回收。
 * 崩溃一致性：阶段出口先落结算文件再随检查点重写 summaries，续写预载时按结算文件对齐账本
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForeshadowSettlementEntity {

    /** 决策：限期回收（能自然融入后续剧情，结转下一阶段前段兑现） */
    public static final String DECISION_RECOVER = "RECOVER";

    /** 决策：弃置出账（剧情已走远，强行回收会注水，不再追踪） */
    public static final String DECISION_VOID = "VOID";

    /**
     * 决策：**已完成**（这条线其实已经在已写章节里自然兑现了，回写排期并停止追问）。
     *
     * <p><b>为什么必须补这一档</b>（2026-10-09）：原 schema 只有 RECOVER / VOID 两个出口，
     * 而裁决对逾期排期线的标注是「⚠️ 已过计划回收章——欠账」，题面又写着"存疑一律 VOID"，
     * 于是模型只能选 RECOVER（= 结转下一阶段前段再收一次）。闭环就这样永远关不上：
     * RECOVER → 逾期补收 → 写手再写一遍 → 回收匹配器不认 → 排期项仍是 PLANTED → 下一阶段又 RECOVER。
     * 实测「2026」那条线在第 5 章埋、原计划第 45 章收，到第 100 章仍被每段下令"必须补收"，
     * 而 100 章的摘要里它的关键锚（"2026"）出现 0 次——匹配器看不见它其实已经在第 93/95 章兑现。
     */
    public static final String DECISION_DONE = "DONE";

    /**
     * 决策：**长线在养**（这条线是设计上就要慢慢养的，保留追踪但**不再每段要求回收**）。
     *
     * <p><b>为什么必须有这一档</b>（2026-10-10）：RECOVER 的处置是"结转下一阶段前段限期回收"，
     * 而一条被判 RECOVER 的长线会被**每一段重新下令回收一次**——规划层只好反复安排同一件事。
     * 实测「2026」那条（第 0 章埋、计划第 45 章收）到第 100 章仍在被催收，而被催出来的
     * 只是"格子纸又一次被折好塞进口袋"这类静态动作。它的意图原文写着"为后续身份暴露危机
     * 埋下**长线**伏笔"——判它是长线本来就对，错的是只有 RECOVER 这一个出口可用。
     *
     * <p>与其它三档的处置差异：
     * <ul>
     *   <li>不像 {@link #DECISION_VOID} 那样出账——线仍在伏笔账里，观察者仍可能提到它；</li>
     *   <li>不像 {@link #DECISION_RECOVER} 那样注入【限期回收】——本段不主动安排它；</li>
     *   <li>不像 {@link #DECISION_DONE} 那样关账——排期项保持 PLANTED，真的兑现时
     *       {@code matchPayoff} 仍会自动把它置 PAID（在养是可自动解除的）。</li>
     * </ul>
     */
    public static final String DECISION_PARKED = "PARKED";

    // 清账发生的阶段号
    private Integer stageNo;

    // 阶段末章号（裁决基准章号：未填伏笔的滞留与分级以该章为 latestNo）
    private Integer stageEndChapter;

    // 逐条裁决（与阶段出口的未填清单一一对应）
    private List<SettlementDecision> decisions;

    /**
     * 单条裁决：content=伏笔登记原文（账本按文本匹配，出账以此精确对齐），
     * chapterNo=埋设章号，decision=RECOVER|VOID|DONE，reason=裁决理由（RECOVER 必须含自然连接点），
     * actualPayoffChapter=判 DONE 时该线实际兑现的章号（缺省为 null）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettlementDecision {
        private String content;
        private Integer chapterNo;
        private String decision;
        private String reason;
        /** 判 DONE 时必填：该线在已写章节中实际兑现的章号；缺失或非法时 DONE 会被降级回 RECOVER */
        private Integer actualPayoffChapter;
    }

    /** 提取全部弃置条目的登记原文（跨阶段聚合、去重），供伏笔账出账剔除 */
    public static List<String> voidedContents(List<ForeshadowSettlementEntity> settlements) {
        return contentsOf(settlements, DECISION_VOID);
    }

    /**
     * 提取全部"长线在养"条目的登记原文（跨阶段聚合、去重）。
     *
     * <p>用途与 {@link #voidedContents} **不同**：在养的线不出账（它仍在账本里、观察者仍可能提到它），
     * 只用于把"漏收伏笔数"这类指标里的在养项排除掉——那条指标的定位就是
     * "「有意养的长线」与「被写忘了的线」的分界线"，在养项已被明确裁定为前者，不该再算它漏收。
     */
    public static List<String> parkedContents(List<ForeshadowSettlementEntity> settlements) {
        return contentsOf(settlements, DECISION_PARKED);
    }

    private static List<String> contentsOf(List<ForeshadowSettlementEntity> settlements, String decision) {
        if (settlements == null) {
            return List.of();
        }
        return settlements.stream()
                .filter(Objects::nonNull)
                .filter(s -> s.getDecisions() != null)
                .flatMap(s -> s.getDecisions().stream())
                .filter(d -> d != null && decision.equals(d.getDecision())
                        && d.getContent() != null && !d.getContent().isBlank())
                .map(d -> d.getContent().trim())
                .distinct()
                .toList();
    }

    /** 提取对某段规划生效的限期回收目标：裁决阶段末章 == 段起点-1（即上一阶段出口） */
    public static List<SettlementDecision> recoverTargets(List<ForeshadowSettlementEntity> settlements, int segmentStartNo) {
        if (settlements == null) {
            return List.of();
        }
        return settlements.stream()
                .filter(Objects::nonNull)
                .filter(s -> s.getStageEndChapter() != null && s.getStageEndChapter() == segmentStartNo - 1)
                .filter(s -> s.getDecisions() != null)
                .flatMap(s -> s.getDecisions().stream())
                .filter(d -> d != null && DECISION_RECOVER.equals(d.getDecision())
                        && d.getContent() != null && !d.getContent().isBlank())
                .toList();
    }
}
