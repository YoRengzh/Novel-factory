package cn.novel.yonren.infrastructure.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考内容处理测试：中转站常把思考包在 &lt;think&gt; 里混进 content 返回，
 * 不剥离会导致 JSON 场景解析失败、正文场景出现"模型自述"。
 */
class ThinkingMarkupStripTest {

    @Test
    void stripsThinkBlock() {
        String raw = "<think>用户要一章玄幻，我需要先梳理主线……</think>{\"chapters\":[]}";
        assertEquals("{\"chapters\":[]}", SpringAiLlmGateway.stripThinkingMarkup(raw));
    }

    @Test
    void stripsThinkingVariantAndMultiline() {
        String raw = "<thinking>\n第一步：分析\n第二步：规划\n</thinking>\n真正的正文";
        assertEquals("真正的正文", SpringAiLlmGateway.stripThinkingMarkup(raw));
    }

    @Test
    void stripsFencedReasoningBlock() {
        String raw = "```reasoning\n思考过程……\n```\n{\"ok\":true}";
        assertEquals("{\"ok\":true}", SpringAiLlmGateway.stripThinkingMarkup(raw));
    }

    @Test
    void stripsMultipleBlocks() {
        String raw = "<think>A</think>正文一<think>B</think>正文二";
        assertEquals("正文一正文二", SpringAiLlmGateway.stripThinkingMarkup(raw));
    }

    @Test
    void keepsNormalTextUntouched() {
        // 正文里出现"思考"二字不该被误伤
        String raw = "他思考了很久，最终决定动手。";
        assertEquals(raw, SpringAiLlmGateway.stripThinkingMarkup(raw));
        // 尖括号但不是 think 标签的（如 XML 片段）也不动
        String xml = "<detail>重要</detail>";
        assertEquals(xml, SpringAiLlmGateway.stripThinkingMarkup(xml));
    }

    @Test
    void allThinkingBecomesEmpty() {
        // 整段都是思考：返回空串，让上层按"空回复"走换站/失败，而不是把空白当正文
        assertEquals("", SpringAiLlmGateway.stripThinkingMarkup("<think>全是思考</think>"));
        assertEquals("", SpringAiLlmGateway.stripThinkingMarkup("<think>A</think>   \n  "));
    }

    @Test
    void nullAndBlankSafe() {
        assertEquals(null, SpringAiLlmGateway.stripThinkingMarkup(null));
        assertEquals("", SpringAiLlmGateway.stripThinkingMarkup(""));
        assertEquals("   ", SpringAiLlmGateway.stripThinkingMarkup("   "));
    }

    @Test
    void noTagFastPath() {
        // 无 '<' 时直接返回，不跑正则（每章正文都会走这条路径）
        String longText = "正文".repeat(5000);
        assertEquals(longText, SpringAiLlmGateway.stripThinkingMarkup(longText));
    }
}
