package cn.novel.yonren.types.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节拍 characters 容错反序列化测试。
 * 复现第 1–10 章批次第 4 章：模型输出对象数组导致 BeanOutputConverter 解析失败、整章降级 SCAFFOLDED。
 *
 * 随反序列化器迁入 types 后，不再依赖 domain 的 ChapterBeatsEntity——* 这里用本地 fixture 复刻同款注解挂载（{@code List<String> characters} +
 * {@code @JsonDeserialize(using = CharacterNameListDeserializer.class)}），反序列化行为等价。
 */
class CharacterNameListDeserializerTest {

    /** 与 ChapterBeatsEntity.Beat 同款挂载形态的最小载体 */
    static class Beat {
        public String location;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = CharacterNameListDeserializer.class)
        public List<String> characters;
        public String conflict;
        public String infoGain;
        public String weight;
    }

    static class Payload {
        public List<Beat> beats;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Payload parse(String json) throws Exception {
        return MAPPER.readValue(json, Payload.class);
    }

    /** 正常形式：字符串数组 */
    @Test
    void plainStringArray_parses() throws Exception {
        Payload beats = parse("""
                {"beats":[{"location":"老屋","characters":["角色丙","角色戊"],
                "conflict":"争执","infoGain":"得知真相","weight":"60%"}]}
                """);
        assertEquals(List.of("角色丙", "角色戊"), beats.beats.get(0).characters);
    }

    /** 回归：实测第 4 章的失败输入——对象数组，角色名在键上 */
    @Test
    void objectArray_takesKeysAsNames() throws Exception {
        Payload beats = parse("""
                {"beats":[{"location":"老宅客厅",
                "characters":[{"角色丙":"婴儿"},{"角色戊":"父亲"},{"角色辰":"母亲"}],
                "conflict":"啼哭迫使父亲中断阅读","infoGain":"看到原始股诈骗案新闻","weight":"30%"}]}
                """);
        List<String> names = beats.beats.get(0).characters;
        assertEquals(List.of("角色丙", "角色戊", "角色辰"), names);
    }

    /** 混合形式：字符串 + 对象混排 */
    @Test
    void mixedArray_parses() throws Exception {
        Payload beats = parse("""
                {"beats":[{"location":"小铺","characters":["角色丙",{"角色辰":"母亲"}],
                "conflict":"盘点","infoGain":"生意上正轨","weight":"40%"}]}
                """);
        assertEquals(List.of("角色丙", "角色辰"), beats.beats.get(0).characters);
    }

    /** 纯值对象：优先取 name 等常见键的值，而不是键名 */
    @Test
    void namedObject_prefersValueOverKey() throws Exception {
        Payload beats = parse("""
                {"beats":[{"location":"书店","characters":[{"name":"角色丙"},{"name":"角色庚"}],
                "conflict":"挑书","infoGain":"开蒙","weight":"50%"}]}
                """);
        assertEquals(List.of("角色丙", "角色庚"), beats.beats.get(0).characters);
    }

    /** null / 空数组不得抛异常 */
    @Test
    void nullAndEmpty_doNotThrow() throws Exception {
        Payload nullCase = parse("""
                {"beats":[{"location":"家","characters":null,
                "conflict":"x","infoGain":"y","weight":"100%"}]}
                """);
        assertTrue(nullCase.beats.get(0).characters == null
                || nullCase.beats.get(0).characters.isEmpty());

        Payload emptyCase = parse("""
                {"beats":[{"location":"家","characters":[],
                "conflict":"x","infoGain":"y","weight":"100%"}]}
                """);
        assertTrue(emptyCase.beats.get(0).characters.isEmpty());
    }

    /** 整章节拍：确认不再因 characters 类型而整体解析失败 */
    @Test
    void fullBeatsPayload_noLongerFails() throws Exception {
        String json = """
                {"beats":[
                  {"location":"老宅客厅","characters":[{"角色丙":"婴儿"},{"角色戊":"父亲"}],
                   "conflict":"制造啼哭迫使父亲中断阅读","infoGain":"看到原始股诈骗案新闻","weight":"30%"},
                  {"location":"老宅卧室摇篮旁","characters":[{"角色丙":"婴儿"}],
                   "conflict":"观察父亲保留凭证","infoGain":"确立章末悬念","weight":"10%"}
                ]}
                """;
        Payload beats = parse(json);
        assertNotNull(beats.beats);
        assertEquals(2, beats.beats.size());
        assertEquals(List.of("角色丙", "角色戊"), beats.beats.get(0).characters);
        assertEquals(List.of("角色丙"), beats.beats.get(1).characters);
    }
}
