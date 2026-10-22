package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

/**
 * 伏笔兑现排期表（2026-10-02 新增，P2b）：阶段蓝图为伏笔**预先排定埋设章与回收章**的前瞻台账。
 *
 * <p><b>为什么必须是"蓝图级的前瞻表"</b>：兑不兑现、什么时候兑现，是**长视野决策**——
 * 而段计划一次只看 5 章，它只能"本段内埋本段内收"。但 seed 是**写正文时**才由摘要模型产生的，
 * 蓝图无法给一条还不存在的伏笔打标。所以这里存的是**意图 + 章号**，
 * 等种子真被埋下时再由记忆层按章号匹配回填。
 *
 * <p><b>为什么单开文件</b>：沿用 {@link ForeshadowSettlementEntity} 的约定——
 * 老故事续写依赖 {@code summaries.json} 的顶层纯数组格式，加字段不如单开文件稳。
 * ⚠️ 本文件**必须进检查点快照与续写预载**（见 {@code StoryRepository#collectSnapshotSources}），
 * 否则审批挂起/崩溃恢复后排期表丢失、种子的打标悬空。
 *
 * <p><b>链式结转</b>：蓝图每版重建，但已排期的线必须跨版存活——
 * 上一版 {@code PLANNED/PLANTED} 的条目**机械结转**进下一版（不由模型自报），与
 * {@code StageBlueprintEntity.CarriedTaskEntity} 同一套做法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForeshadowScheduleEntity {

    /** 已排期，尚未埋设 */
    public static final String STATUS_PLANNED = "PLANNED";
    /** 已在正文埋下（记忆层匹配回填） */
    public static final String STATUS_PLANTED = "PLANTED";
    /** 已按期兑现 */
    public static final String STATUS_PAID = "PAID";
    /** 排期落空（到期未埋 / 到期未收） */
    public static final String STATUS_MISSED = "MISSED";
    /** 被卷末清账弃置（与 VOID 同步，便于区分"按计划弃置"与"写忘了"） */
    public static final String STATUS_DROPPED = "DROPPED";

    // 阶段号（产出本表的蓝图所属阶段）
    private Integer stageNo;
    // 本表覆盖的阶段区间（供注入时判断"本段落在谁的排期里"）
    private Integer startChapter;
    private Integer endChapter;

    // 逐条排期
    private List<ScheduleItem> items;

    /**
     * 单条排期。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleItem {
        /** 这条线要达成什么（蓝图写的**意图**，供段计划理解后落到具体情节） */
        private String intent;
        /** 计划埋设章（硬约束：回填时按它 ± 窗口匹配 seed） */
        private Integer plantChapter;
        /** 计划回收章（长视野决定，可跨阶段） */
        private Integer payoffChapter;
        /** 计划跨度 = payoff − plant（机械回填，供体检与阈值告警） */
        private Integer span;
        /** PLANNED / PLANTED / PAID / MISSED / DROPPED */
        private String status;
        /** 实际埋设章号（匹配命中时回填）；未命中保持 null */
        private Integer actualPlantChapter;
        /** 实际回收章号（命中 foreshadowingResolved 时回填） */
        private Integer actualPayoffChapter;
        /**
         * 长线在养标记：在哪一阶段末被裁决判为 {@code PARKED}（有意慢慢养的线）。null = 未在养。
         *
         * <p><b>为什么用标记而不是新 status</b>（2026-10-10）：{@link #active()} 只认 PLANNED/PLANTED，
         * 新状态会被 {@code carriableItems} 静默丢掉——那等于把"在养"变成"停止追踪"，
         * 而它恰恰要继续被追踪（观察者仍可能提到它、真兑现时要能自动关账）。
         *
         * <p>在养的条目：不再进逾期补收/挂起、不再被送回阶段出口裁决，但仍随排期表结转，
         * 且 {@code matchPayoff} 命中时仍会自动置 PAID——在养是可以自动解除的。
         */
        private Integer parkedAtChapter;

        /** 是否为"仍在计划中"的活线（结转只带这些） */
        public boolean active() {
            return STATUS_PLANNED.equals(status) || STATUS_PLANTED.equals(status);
        }

        /** 是否处于"长线在养"（有意慢慢养、当前不该被要求回收） */
        public boolean parked() {
            return parkedAtChapter != null;
        }
    }

    /** 全部条目（null 安全） */
    public static List<ScheduleItem> itemsOf(ForeshadowScheduleEntity schedule) {
        return schedule == null || schedule.getItems() == null ? List.of() : schedule.getItems();
    }

    /** 取最近一版的排期表（列表按产出顺序追加，末位为现行版） */
    public static ForeshadowScheduleEntity latestOf(List<ForeshadowScheduleEntity> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            return null;
        }
        return schedules.get(schedules.size() - 1);
    }

    /**
     * 结转上一版的活线条目（PLANNED/PLANTED），供新版排期表并入。
     *
     * <p>与 {@code CarriedTaskEntity} 同款做法：**机械结转，不由模型自报**——
     * 模型每次只看得见本阶段，让它自报结转必然漏。
     */
    public static List<ScheduleItem> carriableItems(ForeshadowScheduleEntity previous) {
        return itemsOf(previous).stream()
                .filter(Objects::nonNull)
                .filter(ScheduleItem::active)
                .toList();
    }
}
