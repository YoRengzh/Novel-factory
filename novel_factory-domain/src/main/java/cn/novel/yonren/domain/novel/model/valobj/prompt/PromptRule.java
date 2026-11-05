package cn.novel.yonren.domain.novel.model.valobj.prompt;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 提示词规则实体：一条可注入的写作规则
 */
@Data
@AllArgsConstructor
public class PromptRule {

    /** 规则名（md 文件名或语义名） */
    private String name;

    /** 规则内容（md 文件全文） */
    private String content;

    /** 注入位置 */
    private InjectPosition injectPosition;

    public enum InjectPosition {
        /** 注入 System Message（全局固定规则） */
        SYSTEM,
        /** 追加到 User Message 末尾（动态规则） */
        USER_TAIL
    }

}
