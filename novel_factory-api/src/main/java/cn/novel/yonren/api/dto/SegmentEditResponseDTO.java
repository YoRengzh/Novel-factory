package cn.novel.yonren.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 段落级文本编辑响应：只回传替换片段，前端以对照方式让用户确认后自行替换。
 *
 * <p><b>为什么不直接落盘</b>：AI 改写是"建议"不是"结果"——用户可能只想要其中一句。
 * 让前端做 diff 对照、用户点确认再走已有的 PUT /chapters/{no} 保存，既保留可撤销，
 * 也让这次编辑复用既有的摘要回流链路（不另开一条写盘路径）。
 */
@Data
@AllArgsConstructor
public class SegmentEditResponseDTO {

    /** 改写后的片段（仅这一段，不含上下文） */
    private String editedText;

    /** 实际执行的动作（回显，便于前端提示） */
    private String action;

    /** 是否被兜底降级（模型输出无法提取时返回原文，此标记为 true，前端提示"未改动"） */
    private boolean fallback;

    /** 降级/失败原因（fallback=true 时给出可读说明；正常为 null） */
    private String note;
}
