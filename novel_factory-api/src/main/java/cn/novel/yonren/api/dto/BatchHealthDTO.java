package cn.novel.yonren.api.dto;

import java.util.List;

/**
 * 批末体检报告 DTO：镜像领域记录 BatchHealthReport 的组件（字段名逐一对齐，JSON 形状不变）。
 *
 * <p>2026-11-05 从"直接返回领域 record"改为 DTO——trigger 层不再触碰 domain 模型类型；
 * 嵌套的 Metric/Level/Direction 同步镜像，key 为稳定标识（前端与自动化脚本按 key 取值）。
 */
public record BatchHealthDTO(int chapterCount,
                             List<MetricDTO> metrics,
                             Level overall,
                             List<String> recommendations) {

    /** 总体分级（与领域枚举同名同序，序列化值不变） */
    public enum Level {
        OK, WATCH, DEGRADED, CRITICAL
    }

    /** 指标方向（与领域枚举同名同序，序列化值不变） */
    public enum Direction {
        HIGHER_IS_BETTER, LOWER_IS_BETTER
    }

    /** 单项指标（组件名与领域 Metric 一致） */
    public record MetricDTO(String key, String label, double value, String unit,
                            String healthyLine, Direction direction, Level level, String detail) {
    }
}
