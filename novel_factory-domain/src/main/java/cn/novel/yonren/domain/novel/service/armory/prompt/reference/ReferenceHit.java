package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

/**
 * 块级检索命中：保留块身份、正文与分数，与文件级检索只回文件名的关键区别。
 *
 * @param atom  命中原子的完整内容（正文随 payload 返回，无需回源）
 * @param score 相似分（Cosine，越大越相近）
 */
public record ReferenceHit(ReferenceAtom atom, double score) {

    public String atomId() {
        return atom.atomId();
    }

    public String docId() {
        return atom.docId();
    }

    public int chars() {
        return atom.content() == null ? 0 : atom.content().length();
    }
}
