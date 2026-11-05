package cn.novel.yonren.domain.novel.model.valobj.quality;

import java.util.List;


public record BatchHealthReport(int chapterCount,
                                List<Metric> metrics,
                                Level overall,
                                List<String> recommendations) {

    /** 总体分级：单项越线记录、多项劣化升级 */
    public enum Level {
        OK, WATCH, DEGRADED, CRITICAL;

        /** 取更差的一档 */
        public Level worse(Level other) {
            return this.ordinal() >= other.ordinal() ? this : other;
        }
    }

    /** 指标方向：决定"越大越好"还是"越小越好"，避免每条阈值都手写两遍 */
    public enum Direction {
        HIGHER_IS_BETTER, LOWER_IS_BETTER
    }

    /**
     * 单项指标。
     *
     * @param key        稳定标识（供前端与自动化脚本按 key 取值，不要随文案改）
     * @param label      中文名
     * @param value      实测值
     * @param unit       单位（"%" / "个/章" / "条" / ""）
     * @param healthyLine 健康线的人类可读描述（含实测基线对照，便于人工判断阈值是否合理）
     * @param level      本项分级
     * @param detail     补充说明（分布、来源、口径）
     */
    public record Metric(String key, String label, double value, String unit,
                         String healthyLine, Direction direction, Level level, String detail) {

        /** 渲染为一行文本 */
        public String render() {
            return "%-6s %-14s %s%s（目标 %s）%s".formatted(
                    level, label, format(value), unit, healthyLine,
                    detail == null || detail.isBlank() ? "" : " ｜ " + detail);
        }
    }

    /** 分级是否为"需要人工过问" */
    public boolean needsAttention() {
        return overall == Level.DEGRADED || overall == Level.CRITICAL;
    }

    /** 渲染整份报告（供日志与接口文本展示） */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("批末体检：").append(overall).append("（样本 ").append(chapterCount).append(" 章）");
        if (overall == Level.OK) {
            sb.append("——各指标均在健康线内");
        }
        for (Metric metric : metrics) {
            sb.append("\n  ").append(metric.render());
        }
        if (!recommendations.isEmpty()) {
            sb.append("\n  建议：");
            for (String rec : recommendations) {
                sb.append("\n  - ").append(rec);
            }
        }
        return sb.toString();
    }

    /** 数值格式化：整数不带小数点，小数保留两位（BatchHealthService 渲染指标共用同一口径） */
    public static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) value);
        }
        return String.format("%.2f", value);
    }
}
