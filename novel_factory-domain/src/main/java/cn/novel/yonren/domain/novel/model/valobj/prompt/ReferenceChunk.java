package cn.novel.yonren.domain.novel.model.valobj.prompt;

/**
 * 一份资料的检索单元：按 ## 小节切出的块。
 * 检索命中块 → 按 fileName 聚合 → 注入整份文件全文（检索单元 ≠ 注入单元）。
 */
public record ReferenceChunk(String fileName, String sectionTitle, String content) {

    /** 入向量化的文本：标题承载主题框架，避免正文脱离上下文后语义漂移 */
    public String embedText() {
        return sectionTitle + "\n" + content;
    }

}
