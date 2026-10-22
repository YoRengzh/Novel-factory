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

/**
 * 角色名字列表的容错反序列化器（2026-10-01 实测后新增）。
 *
 * <p><b>为什么需要</b>：`ChapterBeatsEntity.Beat.characters` 声明为 {@code List<String>}，
 * 但模型经常输出<b>对象数组</b>，把"角色名:角色身份"塞进去：
 * <pre>
 * "characters": [ {"主角甲":"婴儿"}, {"角色甲":"父亲"}, {"角色乙":"母亲"} ]
 * </pre>
 * 期望 {@code ["主角甲","角色甲","角色乙"]}，实际收到对象 ⇒ Jackson 反序列化抛异常
 * ⇒ {@code BeanOutputConverter.convert} 失败 ⇒ 整章节拍丢失、降级为无节拍直写（SCAFFOLDED）。
 * 实测第 1–10 章批次第 4 章即因此降级（连抛 5 次解析错误）。
 *
 * <p><b>容错策略</b>（只取能确定的部分，绝不猜）：
 * <ul>
 *   <li>字符串元素 → 原样保留；</li>
 *   <li>对象元素 → 取<b>键</b>（键是角色名，值是身份描述）——这正是模型想表达的语义；</li>
 *   <li>纯值对象（如 {@code {"name":"主角甲"}}）→ 优先取 {@code name} 等常见键的值；</li>
 *   <li>数组元素 → 递归展开；</li>
 *   <li>null → 跳过。</li>
 * </ul>
 * 提示词侧同时给出正例，两条腿并行——模型这种行为很常见，光靠提示词治不干净。
 */
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
            // 否则取键名：模型写 {"主角甲":"婴儿"}，角色名在键上
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
