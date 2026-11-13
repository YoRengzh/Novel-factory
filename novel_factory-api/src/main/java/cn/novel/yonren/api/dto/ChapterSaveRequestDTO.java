package cn.novel.yonren.api.dto;

import lombok.Data;

@Data
public class ChapterSaveRequestDTO {

    /** 人工编辑后的整章正文 */
    private String content;

    /**
     * 是否触发记忆回流（重跑摘要 + 重建向量索引）。
     *
     *   · 缺省/null = true：保持旧行为，整章手改后重算摘要；
     *   · false = 只落盘不回流：段落级润色/扩写不改情节事实，重算摘要是纯浪费
     *       （避免额外的 LLM 摘要与向量重建开销）；
     *   · 续写引入了新内容，必须传 true 或省略，否则账本会与正文脱节。
     *
     */
    private Boolean reflow;

}
