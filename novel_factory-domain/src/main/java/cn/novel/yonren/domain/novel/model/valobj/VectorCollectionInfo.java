package cn.novel.yonren.domain.novel.model.valobj;

/** 集合管理摘要；status 仅为 ready/error，损坏或读取失败通过 error 留痕。 */
public record VectorCollectionInfo(String name, Integer dimensions, long pointCount,
                                   String status, String error) {
}
