package cn.novel.yonren.domain.novel.model.valobj.prompt;

import lombok.Data;

import java.util.List;

/**
 * 资料选择器结构化输出：LLM 从索引中选出的参考资料文件名列表
 */
@Data
public class ReferenceSelection {

    /** 选中的资料文件名（不含扩展名） */
    private List<String> references;

}
