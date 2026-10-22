package cn.novel.yonren.types.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JSON 修复工具测试：悬空逗号、markdown 包裹、字段抢救（含转义反转义）
 */
class JsonRepairTest {

    @Test
    void repair_removesDanglingCommaInObject() {
        // 第 5 章实测故障模式
        String bad = "{\"a\":\"值\",\"b\":\"重伤\",}";
        assertEquals("{\"a\":\"值\",\"b\":\"重伤\"}", JsonRepair.repair(bad));
    }

    @Test
    void repair_removesDanglingCommaInArray() {
        assertEquals("{\"a\":[1,2]}", JsonRepair.repair("{\"a\":[1,2,]}"));
    }

    @Test
    void repair_stripsMarkdownFence() {
        String wrapped = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", JsonRepair.repair(wrapped));
    }

    @Test
    void repair_keepsValidJsonUnchanged() {
        String valid = "{\"a\":\"文本，含中文标点。\"}";
        assertEquals(valid, JsonRepair.repair(valid));
    }

    @Test
    void repair_nullSafe() {
        assertNull(JsonRepair.repair(null));
    }

    @Test
    void extractStringField_plainValue() {
        String raw = "{\"summary\":\"小陈深入废矿。\",\"other\":1}";
        assertEquals("小陈深入废矿。", JsonRepair.extractStringField(raw, "summary"));
    }

    @Test
    void extractString_fieldWithEscapedQuotesAndNewlines() {
        String raw = "{\"content\":\"他说：\\\"别跟着我\\\"。\\n随后遁入矿道。\"}";
        String extracted = JsonRepair.extractStringField(raw, "content");
        // 抠出值完成转义还原：\" → " 、\n → 换行
        assertEquals("他说：\"别跟着我\"。\n随后遁入矿道。", extracted);
    }

    @Test
    void extractStringField_missingFieldReturnsNull() {
        assertNull(JsonRepair.extractStringField("{\"a\":1}", "content"));
        assertNull(JsonRepair.extractStringField("不是 JSON 的文本", "content"));
        assertNull(JsonRepair.extractStringField(null, "content"));
    }

    @Test
    void extractStringField_hugeValueDoesNotOverflowStack() {
        // 回归：旧正则实现按字符递归匹配，正文级长度（~8 千字）即触发 StackOverflowError
        String big = "正文内容".repeat(30_000);
        String raw = "{\"chapterNo\":62,\"content\":\"" + big + "\",\"title\":\"标题\"}";
        assertEquals(big, JsonRepair.extractStringField(raw, "content"));
    }

    @Test
    void extractStringField_unclosedValueReturnsNull() {
        // 截断输出：与旧正则语义一致，宁可不抢救也不返回半截垃圾
        assertNull(JsonRepair.extractStringField("{\"content\":\"写到一半", "content"));
    }

    @Test
    void repair_stripsProseAroundJson() {
        // 第 62 章实测故障模式：模型先解说后输出 JSON，杂文剥离后可被二级降级直接解析
        String json = "{\"chapterNo\":62,\"content\":\"正文\",\"title\":\"标题\"}";
        String raw = "我先对齐上一章的状态，再直接写正文。" + json;
        assertEquals(json, JsonRepair.repair(raw));
        assertEquals("正文", JsonRepair.extractStringField(raw, "content"));
    }

    @Test
    void repair_proseWithBraceButNoJsonKeptUnchanged() {
        // 杂文含花括号但无完整 JSON：截取结果解析必然失败，repair 保持尽力而为
        String raw = "他说{不重要}就走了";
        assertEquals("{不重要}", JsonRepair.repair(raw));
    }

    @Test
    void escapeInteriorQuotes_singlePair() {
        String bad = "{\"key\":\"对方提及\\\"照雪计划\\\"\"}";
        // 构造真正的裸引号输入
        String input = "{\"key\":\"对方提及\"照雪计划\"\"}";
        assertEquals(bad, JsonRepair.escapeInteriorQuotes(input));
    }

    @Test
    void escapeInteriorQuotes_alreadyEscapedUnchanged() {
        String valid = "{\"key\":\"他说\\\"别\\\"\"}";
        assertEquals(valid, JsonRepair.escapeInteriorQuotes(valid));
    }

    @Test
    void escapeInteriorQuotes_validJsonUnchanged() {
        String valid = "{\"a\":\"文本\",\"b\":\"正常\"}";
        assertEquals(valid, JsonRepair.escapeInteriorQuotes(valid));
    }

    @Test
    void escapeInteriorQuotes_multipleFields() {
        String input = "{\"a\":\"提及\"计划\"\",\"b\":\"说到\"目标\"\"}";
        String expected = "{\"a\":\"提及\\\"计划\\\"\",\"b\":\"说到\\\"目标\\\"\"}";
        assertEquals(expected, JsonRepair.escapeInteriorQuotes(input));
    }

    @Test
    void escapeInteriorQuotes_nullSafe() {
        assertNull(JsonRepair.escapeInteriorQuotes(null));
    }

    @Test
    void escapeInteriorQuotes_closingQuoteBeforeBoundaryNotEscaped() {
        // "hello"} — 引号后跟 } 是真正的关闭引号，不应被转义
        String input = "{\"a\":\"hello\"}";
        assertEquals(input, JsonRepair.escapeInteriorQuotes(input));
    }

    @Test
    void escapeInteriorQuotes_closingQuoteBeforeCommaNotEscaped() {
        String input = "{\"a\":\"hello\",\"b\":\"world\"}";
        assertEquals(input, JsonRepair.escapeInteriorQuotes(input));
    }

    @Test
    void fixCommaInKey_single() {
        // 实测故障：键闭合引号丢失、逗号被吞进键内；前缀 "chapterNo":8, 不得被吸收进匹配
        String bad = "{\"chapterNo\":8,\"title,\"身世\"}";
        assertEquals("{\"chapterNo\":8,\"title\":\"身世\"}", JsonRepair.fixCommaInKey(bad));
    }

    @Test
    void fixCommaInKey_multipleOccurrences() {
        String bad = "{\"chapterNo\":8,\"title,\"身世\",\"chapterNo\":9,\"title,\"危机\"}";
        String expected = "{\"chapterNo\":8,\"title\":\"身世\",\"chapterNo\":9,\"title\":\"危机\"}";
        assertEquals(expected, JsonRepair.fixCommaInKey(bad));
    }

    @Test
    void fixCommaInKey_keepsValidJsonUnchanged() {
        String valid = "{\"a\":1,\"b\":\"值\",\"c,d\":\"键含逗号\"}";
        assertEquals(valid, JsonRepair.fixCommaInKey(valid));
    }

    @Test
    void fixCommaInKey_ignoresValuePosition() {
        // 值位置（引号前是冒号）的 ," 不是键名病症，不得误修
        String input = "{\"key\":\"他说,\"好的\"\"}";
        assertEquals(input, JsonRepair.fixCommaInKey(input));
    }

    @Test
    void fixCommaInKey_nullSafe() {
        assertNull(JsonRepair.fixCommaInKey(null));
    }

    @Test
    void balanceUnclosed_appendsMissingBrace() {
        // 正文最常见截断：写完 content 字符串后缺闭合 }
        String bad = "{\"chapterNo\":49,\"title\":\"紫雷落处\",\"content\":\"（本章完）\"";
        assertEquals("{\"chapterNo\":49,\"title\":\"紫雷落处\",\"content\":\"（本章完）\"}",
                JsonRepair.repair(bad));
    }

    @Test
    void balanceUnclosed_unclosedStringLeftUntouched() {
        // 字符串本身未闭合（内容不完整）：不补引号也不补括号，交给调用方决定
        String bad = "{\"chapterNo\":49,\"content\":\"正文写到这里";
        assertEquals(bad, JsonRepair.repair(bad));
    }

    @Test
    void balanceUnclosed_keepsValidJsonUnchanged() {
        String valid = "{\"a\":\"文本，含中文标点。\"}";
        assertEquals(valid, JsonRepair.repair(valid));
    }

    @Test
    void balanceUnclosed_nestedArraysAndObjects() {
        String bad = "{\"issues\":[{\"dimension\":\"consistency\",\"severity\":\"MINOR\"}";
        assertEquals("{\"issues\":[{\"dimension\":\"consistency\",\"severity\":\"MINOR\"}]}",
                JsonRepair.repair(bad));
    }

    @Test
    void balanceUnclosed_braceInsideStringNotCounted() {
        // 字符串值里的 } 不应被当作结构闭合
        String bad = "{\"content\":\"他说}好\"}";
        // 结构：{ content:"他说}好" }  —— 字符串内的 } 不影响括号配对，本已平衡
        assertEquals(bad, JsonRepair.repair(bad));
    }

    @Test
    void balanceUnclosed_realChapterTruncation() {
        // 模拟第49章真实故障：正文含换行、引号、中文标点，结尾缺 }
        String content = "荒原的风带着铁锈味。\\n\\n（本章完）";
        String bad = "{\"chapterNo\":49,\"title\":\"紫雷落处\",\"content\":\"" + content + "\"";
        String expected = "{\"chapterNo\":49,\"title\":\"紫雷落处\",\"content\":\"" + content + "\"}";
        assertEquals(expected, JsonRepair.repair(bad));
    }



    // ==================== 移植自旧系统的闸门用例 ====================

    /** 全部为空时返回 null（调用方据此判失败，而不是拿空串当成功） */
    @Test
    void extractStringField_skipBlank_allBlankReturnsNull() {
        String raw = "{\"content\":\"\",\"content\":\"   \"}";
        assertNull(JsonRepair.extractStringField(raw, "content", true));
    }

    /** 重复键且**首个为空**时，skipBlank 必须继续往后找，而不是返回空串 */
    @Test
    void extractStringField_skipBlank_skipsLeadingEmptyValue() {
        String raw = "{\"content\":\"\",\"content\":\"后面的真正文\"}";
        assertEquals("后面的真正文", JsonRepair.extractStringField(raw, "content", true));
        // 不跳过时返回空串（旧语义，保持向后兼容）
        assertEquals("", JsonRepair.extractStringField(raw, "content"));
    }

    /** 回归：重复 content 键（先有正文、后补空串）——skipBlank 必须拿回前面那段真正文 */
    @Test
    void extractStringField_skipBlank_takesFirstNonBlankAmongDuplicateKeys() {
        String raw = "{\"chapterNo\":167,\"title\":\"四十分钟的边界\","
                + "\"content\":\"真正文内容\",\"content\":\"\",\"note\":\"\"}";
        // 旧行为：首个出现即返回（拿到真正文，但若首个是空串就会丢稿）
        assertEquals("真正文内容", JsonRepair.extractStringField(raw, "content"));
        // 显式 skipBlank 同样拿到非空值
        assertEquals("真正文内容", JsonRepair.extractStringField(raw, "content", true));
    }
}
