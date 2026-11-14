package cn.novel.yonren.domain.novel.model.valobj;

import java.util.List;

/** 有界点分页；nextCursor 为适配器生成的不透明游标，null 表示没有下一页。 */
public record VectorPointPage(List<VectorPointView> points, String nextCursor) {
    public VectorPointPage {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
