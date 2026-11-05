package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceChunk;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 参考资料向量索引器：扫描 → 按标题切块 → 批量向量化 → 幂等写入向量库。
 * 点 id 由 fileName#节序 确定性生成，重启重建时同 id 覆盖（天然幂等），
 * 资料内容变更随每次进程启动自动生效——v1 不做哈希增量，语料量级下重建成本可忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceVectorIndexer {

    public static final String COLLECTION = "novel-references";
    static final String PAYLOAD_FILE = "fileName";
    static final String PAYLOAD_TITLE = "sectionTitle";

    /** 批量向量化上限（供应商单请求数组上限 64，留余量） */
    private static final int BATCH_SIZE = 16;

    private final ReferenceIndexLoader referenceIndexLoader;
    private final PromptRuleFileLoader fileLoader;
    private final ReferenceChunker chunker;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;

    /**
     * 全量重建索引
     *
     * @return 写入的点数
     */
    public int index(StoryVO.Module module) {
        List<ReferenceChunk> chunks = new ArrayList<>();
        for (ReferenceIndexEntry entry : referenceIndexLoader.getIndex()) {
            String content = fileLoader.load(entry.getRelativePath());
            if (StringUtils.isBlank(content)) {
                continue;
            }
            chunks.addAll(chunker.chunk(entry.getFileName(), content));
        }
        if (chunks.isEmpty()) {
            log.warn("参考资料切块结果为空，跳过向量索引");
            return 0;
        }

        List<VectorPoint> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<ReferenceChunk> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            List<float[]> vectors = embeddingGateway.embed(module,
                    batch.stream().map(ReferenceChunk::embedText).toList());
            for (int j = 0; j < batch.size(); j++) {
                ReferenceChunk chunk = batch.get(j);
                points.add(VectorPoint.builder()
                        .id(stableId(chunk.fileName(), i + j))
                        .vector(vectors.get(j))
                        .payload(Map.of(PAYLOAD_FILE, chunk.fileName(),
                                PAYLOAD_TITLE, chunk.sectionTitle()))
                        .build());
            }
        }

        vectorStore.ensureCollection(COLLECTION, points.get(0).getVector().length);
        vectorStore.upsert(COLLECTION, points);
        log.info("参考资料向量索引完成：{} 块 → {} 点（维度 {}）",
                chunks.size(), points.size(), points.get(0).getVector().length);
        return points.size();
    }

    /** 稳定点 id：同一文件同一节序，跨进程重建 id 不变 */
    private String stableId(String fileName, int sectionIndex) {
        return UUID.nameUUIDFromBytes((fileName + "#" + sectionIndex).getBytes()).toString();
    }

}
