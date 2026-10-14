package cn.novel.yonren.domain.novel.model.jackson;

import cn.novel.yonren.domain.novel.model.entity.ChapterBeatsEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节拍 characters 容错反序列化测试（2026-10-01）。
 * 复现第 1–10 章批次第 4 章：模型输出对象数组导致 BeanOutputConverter 解析失败、整章降级 SCAFFOLDED。
 */
class CharacterNameListDeserializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ChapterBeatsEntity parse(String json) throws Exception {
        return MAPPER.readValue(json, ChapterBeatsEntity.class);
    }

    /** 正常形式：字符串数组 */
    @Test
    void plainStringArray_parses() throws Exception {
        ChapterBeatsEntity beats = parse("""
                {"beats":[{"location":"老屋","characters":["角色丙","角色戊"],
                "conflict":"争执","infoGain":"得知真相","weight":"60%"}]}
                """);
        assertEquals(List.of("角色丙", "角色戊"), beats.getBeats().get(0).getCharacters());
    }

    /** 回归：实测第 4 章的失败输入——对象数组，角色名在键上 */
    @Test
    void objectArray_takesKeysAsNames() throws Exception {
        ChapterBeatsEntity beats = parse("""
                {"beats":[{"location":"老宅客厅",
                "characters":[{"角色丙":"婴儿"},{"角色戊":"父亲"},{"角色辰":"母亲"}],
                "conflict":"啼哭迫使父亲中断阅读","infoGain":"看到原始股诈骗案新闻","weight":"30%"}]}
                """);
        List<String> names = beats.getBeats().get(0).getCharacters();
        assertEquals(List.of("角色丙", "角色戊", "角色辰"), names);
    }

    /** 混合形式：字符串 + 对象混排 */
    @Test
    void mixedArray_parses() throws Exception {
        ChapterBeatsEntity beats = parse("""
                {"beats":[{"location":"小铺","characters":["角色丙",{"角色辰":"母亲"}],
                "conflict":"盘点","infoGain":"生意上正轨","weight":"40%"}]}
                """);
        assertEquals(List.of("角色丙", "角色辰"), beats.getBeats().get(0).getCharacters());
    }

    /** 纯值对象：优先取 name 等常见键的值，而不是键名 */
    @Test
    void namedObject_prefersValueOverKey() throws Exception {
        ChapterBeatsEntity beats = parse("""
                {"beats":[{"location":"书店","characters":[{"name":"角色丙"},{"name":"角色庚"}],
                "conflict":"挑书","infoGain":"开蒙","weight":"50%"}]}
                """);
        assertEquals(List.of("角色丙", "角色庚"), beats.getBeats().get(0).getCharacters());
    }

    /** null / 空数组不得抛异常 */
    @Test
    void nullAndEmpty_doNotThrow() throws Exception {
        ChapterBeatsEntity nullCase = parse("""
                {"beats":[{"location":"家","characters":null,
                "conflict":"x","infoGain":"y","weight":"100%"}]}
                """);
        assertTrue(nullCase.getBeats().get(0).getCharacters() == null
                || nullCase.getBeats().get(0).getCharacters().isEmpty());

        ChapterBeatsEntity emptyCase = parse("""
                {"beats":[{"location":"家","characters":[],
                "conflict":"x","infoGain":"y","weight":"100%"}]}
                """);
        assertTrue(emptyCase.getBeats().get(0).getCharacters().isEmpty());
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
        ChapterBeatsEntity beats = parse(json);
        assertNotNull(beats.getBeats());
        assertEquals(2, beats.getBeats().size());
        assertEquals(List.of("角色丙", "角色戊"), beats.getBeats().get(0).getCharacters());
        assertEquals(List.of("角色丙"), beats.getBeats().get(1).getCharacters());
    }
}
