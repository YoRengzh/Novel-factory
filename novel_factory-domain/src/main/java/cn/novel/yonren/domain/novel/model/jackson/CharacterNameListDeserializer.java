package cn.novel.yonren.domain.novel.model.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class CharacterNameListDeserializer extends JsonDeserializer<List<String>> {

    /** 纯值对象里常见的"真正的名字"字段名 */
    private static final List<String> NAME_KEYS = List.of("name", "character", "role", "角色", "姓名", "名字");

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        List<String> names = new ArrayList<>();
        collect(node, names);
        return names;
    }

    private void collect(JsonNode node, List<String> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, out);
            }
            return;
        }
        if (node.isTextual()) {
            String v = node.asText().trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
            return;
        }
        if (node.isObject()) {
            // 先找"真正的名字"键（纯值对象形式）
            for (String key : NAME_KEYS) {
                JsonNode named = node.get(key);
                if (named != null && named.isTextual() && !named.asText().isBlank()) {
                    out.add(named.asText().trim());
                    return;
                }
            }
            // 否则取键名：模型写 {"角色名":"婴儿"}，角色名在键上
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey() == null ? "" : e.getKey().trim();
                if (!key.isEmpty()) {
                    out.add(key);
                }
            }
        }
        // 数字/布尔等其它标量：无意义，丢弃
    }
}
