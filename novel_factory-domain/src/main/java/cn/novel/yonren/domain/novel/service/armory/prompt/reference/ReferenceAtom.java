package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import java.util.List;

/**
 * 资料原子：可独立阅读、不可从中间截断的原文单元，也是块级注入的最小单位。
 *
 * 与 {@link cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceChunk} 的区别：
 * Chunk 只是检索单元，命中后回源整份文件；Atom 同时是注入单元，命中哪块就注入哪块。
 *
 * 身份规则：atomId 由文档标识、标题路径和同路径局部序号计算，文件内原文序号不参与身份，
 * 插入无关小节不会改变后续已有块的 ID。正文哈希单独用于版本校验与缓存失效。
 */
public record ReferenceAtom(String docId, String atomId, String docTitle, String headingPath,
                            int sectionIndex, String content, String contentHash, String group,
                            List<String> requires, boolean standalone) {

    public ReferenceAtom {
        requires = requires == null ? List.of() : List.copyOf(requires);
    }

    public ReferenceAtom withRequires(List<String> dependencies) {
        return new ReferenceAtom(docId, atomId, docTitle, headingPath, sectionIndex, content,
                contentHash, group, dependencies, standalone);
    }

    /** embedding 文本：标题路径承载主题框架，避免正文脱离上下文后语义漂移。 */
    public String embedText() {
        return docTitle + " › " + headingPath + "\n" + content;
    }

    /** 注入时展示的来源标识（不含块 id，避免撑长账单块头）。 */
    public String sourceLabel() {
        return docTitle + " · " + headingPath;
    }
}
