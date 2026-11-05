package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量资料检索器：查询上下文向量化 → 相似检索 → 按 fileName 聚合回源。
 * 首次调用懒加载索引（进程内一次）；索引/检索失败向上抛，由编排层快速失败终止作业（不降级 LLM 选择）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorReferenceRetriever {

    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final ReferenceVectorIndexer indexer;
    private final ReferenceSelectorProperties properties;

    private volatile boolean indexed = false;

    /**
     * 检索与当前写作任务语义相关的资料文件名（已去重、按分数降序聚合）
     */
    public List<String> retrieve(StoryVO.Module module, PromptScene scene, PromptContext ctx) {
        ensureIndexed(module);

        float[] queryVector = embeddingGateway.embed(module, List.of(buildQueryText(scene, ctx))).get(0);
        List<ScoredVectorPoint> hits = vectorStore.search(ReferenceVectorIndexer.COLLECTION,
                queryVector, properties.getTopK());

        List<String> fileNames = new ArrayList<>();
        for (ScoredVectorPoint hit : hits) {
            if (hit.getScore() < properties.getMinScore()) {
                continue;
            }
            String fileName = hit.getPayload().get(ReferenceVectorIndexer.PAYLOAD_FILE);
            if (fileName != null && !fileNames.contains(fileName)) {
                fileNames.add(fileName);
            }
        }
        log.info("向量资料检索完成：命中 {} 点，聚合 {} 份资料：{}", hits.size(), fileNames.size(), fileNames);
        return fileNames;
    }

    private synchronized void ensureIndexed(StoryVO.Module module) {
        if (indexed) {
            return;
        }
        indexer.index(module);
        indexed = true;
    }

    private String buildQueryText(PromptScene scene, PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("写作场景：").append(scene == PromptScene.CHAPTER_PLAN ? "规划全书章节大纲" : "撰写章节正文");
        if (ctx.getTheme() != null) {
            sb.append("；题材：").append(ctx.getTheme());
        }
        if (ctx.getStyle() != null) {
            sb.append("；风格：").append(ctx.getStyle());
        }
        if (ctx.getChapterType() != null) {
            sb.append("；章节类型：").append(ctx.getChapterType().getDesc());
        }
        if (ctx.getChapterBrief() != null) {
            sb.append("；本章要点：").append(ctx.getChapterBrief());
        }
        return sb.toString();
    }

}
