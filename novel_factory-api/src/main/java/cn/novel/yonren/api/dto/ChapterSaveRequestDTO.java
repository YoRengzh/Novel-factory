package cn.novel.yonren.api.dto;

import lombok.Data;

@Data
public class ChapterSaveRequestDTO {

    /** 人工编辑后的整章正文 */
    private String content;

    /**
     * 是否在保存后重建本章记忆（重跑摘要 + 重建向量索引）。
     *
     *   · 缺省/null/false = 只落盘：改文风（去 AI 味、润色措辞）通常不需要重算整章摘要；
     *   · true = 保存并立即重建本章：正文与待回流标记先原子落盘，再调用模型。
     *       重建失败时保留待办；中间章的后续关联记忆也在续写前按序补算。
     *
     * 无论取值如何，保存都会推进编辑纪元，使编辑前生成的旧稿失效。
     */
    private Boolean reflow;

}
