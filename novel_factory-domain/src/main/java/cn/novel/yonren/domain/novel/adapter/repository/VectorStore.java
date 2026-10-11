package cn.novel.yonren.domain.novel.adapter.repository;

import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;

import java.util.List;

/**
 * 向量存储端口：领域层只认"建集合/写入/相似检索"三个动作，屏蔽 Qdrant 等具体实现。
 * 集合维度与距离度量在首次创建时定死（与 embedding 模型输出维度严格一致）。
 */
public interface VectorStore {

    /** 确保集合存在（已存在则不动） */
    void ensureCollection(String collection, int vectorSize);

    /** 批量写入；同 id 覆盖（幂等摄入的基础） */
    void upsert(String collection, List<VectorPoint> points);

    /** 相似检索：返回按分数降序的前 limit 个点 */
    List<ScoredVectorPoint> search(String collection, float[] queryVector, int limit);

    /** 清空整集合（删除全部点）；本集合后续由下次 ensureCollection+upsert 自动重建 */
    void clear(String collection);

    /**
     * 列出集合内全部点的 id/kind/文本（不含向量）。供索引对账使用：
     * 只有知道了"现在到底存了什么"，才能只改该改的点，而不是整集合重嵌一遍。
     * 集合不存在按"什么都没有"处理（返回空表，不抛异常）。
     */
    List<StoredPoint> listPoints(String collection);

    /** 按 id 删除点（对账中判定为"不该再存在"的点）；id 不存在时静默忽略 */
    void delete(String collection, List<String> ids);

    /** 集合当前点数；集合不存在返回 0。对账后的机械核对用（应有点数 == 实际点数） */
    long count(String collection);

}
