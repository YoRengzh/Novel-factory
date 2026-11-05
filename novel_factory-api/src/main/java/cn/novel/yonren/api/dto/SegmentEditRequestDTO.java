package cn.novel.yonren.api.dto;

import lombok.Data;

/**
 * 段落级文本编辑请求（POST /api/stories/{storyDirName}/chapters/{chapterNo}/segment-edit）：
 * 用户在正文里选中一段文字，要求 AI 只改这一段——润色 / 扩写 / 续写。
 *
 * 为什么不复用整章修订：整章修订走 CHAPTER_REVISE 场景的完整 prompt（含账本、伏笔、
 * 风格统计等大块上下文），单次成本与耗时都在"整章"量级；用户只是想把一句话写顺，等 2 分钟
 * 换回一整章改写并不划算。这里单独走一次短 prompt 调用，只回传替换片段，不落盘、不动账本。
 */
@Data
public class SegmentEditRequestDTO {

    /** 选中的原文片段（必须能在章节正文里逐字定位，服务端会再校验一次） */
    private String selectedText;

    /** 动作：polish=润色 / expand=扩写 / continue=续写。未知值由服务端拒绝 */
    private String action;

    /** 用户附加要求（可选，如"再冷峻一点""扩到两倍长度"） */
    private String instruction;

    /** 选中片段之前的上下文（可选，前端截取若干字符；用于保持语气与指代连贯） */
    private String contextBefore;

    /** 选中片段之后的上下文（可选，同上） */
    private String contextAfter;
}
