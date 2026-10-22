package cn.novel.yonren.domain.novel.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量库检索命中：score 语义由距离度量决定（本项目 Cosine，越大越相近）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoredVectorPoint {

    private String id;

    private double score;

    private Map<String, String> payload;

}
