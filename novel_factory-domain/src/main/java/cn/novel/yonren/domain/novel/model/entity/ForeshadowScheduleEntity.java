package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;


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
    // 覆盖区间终点章号（含，机械取自阶段蓝图 endChapter）
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
         * 长线在养标记：在哪一阶段末被裁决判为 PARKED（有意慢慢养的线）。null = 未在养。
         *
         * <p><b>为什么用标记而不是新 status</b>：{@link #active()} 只认 PLANNED/PLANTED，
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

    public static List<ScheduleItem> carriableItems(ForeshadowScheduleEntity previous) {
        return itemsOf(previous).stream()
                .filter(Objects::nonNull)
                .filter(ScheduleItem::active)
                .toList();
    }
}
