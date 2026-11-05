package cn.novel.yonren.domain.novel.model.valobj.prompt;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 参考资料索引条目：从 md 文件头部提取的轻量元信息，
 * 对应 skill 机制的 name/description 层——仅用于 LLM 判断是否需要该资料
 */
@Data
@AllArgsConstructor
public class ReferenceIndexEntry {

    /**
     * 默认分组「技法组」。分组词汇表（technique/structure/genre）是跨层字符串协议：
     * 由 ReferenceGroup 归类、FeatureToggles 的 REFERENCE_GROUP_* 开关按同名 key 消费，
     * 因此这里持有字面量而不是引用服务类，避免 model → service 逆向依赖。
     */
    private static final String DEFAULT_GROUP = "technique";

    /** 文件名（不含扩展名），同时作为选择器的输出标识与白名单校验键 */
    private String fileName;

    /** 一级标题（# 后文本） */
    private String title;

    /** 标题后首段简介（截断），纯模板文件可为空 */
    private String digest;

    /** 相对 assets 的路径（如 references/dialogue-writing.md），用于按需加载全文 */
    private String relativePath;

    /** 资料分组：决定哪些场景的索引里会出现它（technique/structure/genre，归类见 ReferenceGroup） */
    private String group;

    /** 兼容旧构造：分组默认归入技法组（未分类资料的保守归属，保证不会被检索漏掉） */
    public ReferenceIndexEntry(String fileName, String title, String digest, String relativePath) {
        this(fileName, title, digest, relativePath, DEFAULT_GROUP);
    }

}
