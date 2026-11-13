package cn.novel.yonren.domain.novel.adapter.llm;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;

import java.util.List;

/**
 * Embedding 网关端口：文本 → 向量。
 * 本项目聊天与 embedding 供应商拆分，故独立于 LlmGateway 成端口。
 * 实现位于 infrastructure（Spring AI 适配）
 */
public interface EmbeddingGateway {

    /**
     * 批量向量化（供应商支持一次请求多个文本，调用方自行控制批大小）
     *
     * @param module 请求级配置（含 embedding-api 接入块）
     * @param texts  待向量化文本，非空
     * @return 与 texts 同序的向量列表
     */
    List<float[]> embed(StoryVO.Module module, List<String> texts);

    /**
     * 冻结本次使用的生效配置并返回会话：索引构建与查询向量必须来自同一个向量空间。
     *
     * 运行时 embedding 覆盖是每次调用时重新读取的，多批构建之间可能切换地址、模型或维度；
     * 索引一旦混用两套向量空间，检索分数即失去意义。会话在创建时固定生效接入与维度。
     * 不支持冻结的实现必须明确拒绝，不能用旧签名包装每次重新解析配置的向量调用。
     */
    default EmbeddingSession session(StoryVO.Module module) {
        throw new UnsupportedOperationException("该 embedding 网关不支持冻结会话");
    }

    /** 冻结会话：同一向量空间内的批量向量化与空间签名。 */
    interface EmbeddingSession {

        /** 批量向量化（与 {@link #embed} 同序语义）。 */
        List<float[]> embed(List<String> texts);

        /** 向量空间签名（地址、路径、模型与维度的摘要，不含密钥）。 */
        String signature();

        /** 生效维度；未显式配置时为 null（由首次返回推断）。 */
        Integer dimensions();
    }

}
