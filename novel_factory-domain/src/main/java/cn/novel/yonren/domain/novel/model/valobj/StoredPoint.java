package cn.novel.yonren.domain.novel.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 集合内已存点的最小视图（索引对账用）：id + kind + 文本，不含向量。
 *
 * <p>存在理由：回滚对账必须先知道"集合里到底有什么"，才能只改该改的点、只删该删的点。
 * 带上 kind 是关键——同一个集合里既有本链路管理的点（chapter/ledger/bible），
 * 也有卷方向点（volume）；对账只该动自己写的那三类，不能把卷方向点当"多余的点"删掉。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredPoint {

    private String id;

    private String kind;

    private String text;

}
