package cn.novel.yonren.domain.novel.model.valobj;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 管理只读点视图：保留全部业务 payload，不暴露 embedding 向量。 */
public record VectorPointView(String id, Map<String, String> payload) {
    public VectorPointView {
        payload = payload == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
