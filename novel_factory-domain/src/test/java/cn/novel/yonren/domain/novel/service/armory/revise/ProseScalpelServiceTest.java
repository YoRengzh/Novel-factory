package cn.novel.yonren.domain.novel.service.armory.revise;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProseScalpelServiceTest {

    private static String longManuscript() {
        return ("苏尘踩着湿滑的青苔石棱往上摸了半丈，指腹蹭破了一层皮，血丝混着冷露渗进石缝。\n\n"
                + "他摸进那座积灰的石殿时，长明灯昏黄的火苗摇了两下。这并不是幻觉，而是真实的灵气波动。\n\n"
                + "刘全比苏尘早入药园一年，平日最爱在管事面前刷脸踩人。此刻他拿起桌上的半块干饼咬了一口，盯着苏尘冷笑。\n\n"
                + "“这株草带着金纹，你藏在篮底想混过去？”刘全把草往台上一拍。\n\n"
                + "苏尘拎起旁边一株正常青穗草搁到案上：“拿碗水来泡一泡，真金还是病纹一眼便知。”\n\n")
                + "院外山风灌进棚里，油灯晃出一圈昏影，苏尘收起药篮，转身往北屋走去。".repeat(32);
    }

    private static ChapterPlanItemEntity item() {
        return ChapterPlanItemEntity.builder()
                .chapterNo(1)
                .characters(List.of("苏尘", "刘全"))
                .build();
    }

    private static StoryProperties enabledProps(int maxPatches) {
        StoryProperties props = new StoryProperties();
        props.getProseScalpel().setEnabled(true);
        props.getProseScalpel().setMaxPatches(maxPatches);
        return props;
    }

    @Test
    void returnsNullWithoutCallingLlmWhenDisabled() {
        LlmGateway gateway = mock(LlmGateway.class);
        StoryProperties props = new StoryProperties();
        ProseScalpelService service = new ProseScalpelService(gateway, props);

        assertNull(service.trim(new StoryVO.Module(), item(), longManuscript(), 1));
        verifyNoInteractions(gateway);
    }

    @Test
    void appliesShrinkPatchesRejectsExpansionAndToleratesBadAnchor() {
        LlmGateway gateway = mock(LlmGateway.class);
        String original = longManuscript();
        when(gateway.complete(any(), any())).thenReturn("""
                {
                  "patches": [
                    {
                      "anchor": "这并不是幻觉，而是真实的灵气波动。",
                      "replacement": "掌心那股灵气波动清清楚楚。"
                    },
                    {
                      "anchor": "刘全比苏尘早入药园一年，平日最爱在管事面前刷脸踩人。",
                      "replacement": ""
                    },
                    {
                      "anchor": "油灯晃出一圈昏影",
                      "replacement": "尝试强行扩写一整段完全多余的话来破坏只删不扩铁律，这段话明显比原来的锚点长出很多很多。"
                    },
                    {
                      "anchor": "原文里根本不存在的锚点句",
                      "replacement": ""
                    }
                  ]
                }
                """);

        ProseScalpelService service = new ProseScalpelService(gateway, enabledProps(8));
        String trimmed = service.trim(new StoryVO.Module(), item(), original, 1);

        assertNotNull(trimmed);
        assertTrue(trimmed.contains("掌心那股灵气波动清清楚楚。"));
        assertFalse(trimmed.contains("这并不是幻觉，而是"));
        assertFalse(trimmed.contains("平日最爱在管事面前刷脸踩人"));
        assertFalse(trimmed.contains("尝试强行扩写"), "扩写补丁必须被长度锁丢弃");
        assertTrue(trimmed.contains("油灯晃出一圈昏影"));
        assertTrue(trimmed.contains("苏尘") && trimmed.contains("刘全"));

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(gateway).complete(any(), captor.capture());
        assertEquals(0.2, captor.getValue().getTemperature());
        assertEquals(ModelScene.PARAGRAPH_AUDIT, captor.getValue().getScene());
        assertNull(captor.getValue().getMaxTokens());
    }

    @Test
    void rejectsPatchesThatDropCharacterOrNumericFacts() {
        LlmGateway gateway = mock(LlmGateway.class);
        String original = "苏尘手里只有 1280 块灵石。\n\n" + longManuscript();
        when(gateway.complete(any(), any())).thenReturn("""
                {
                  "patches": [
                    {
                      "anchor": "苏尘手里只有 1280 块灵石。",
                      "replacement": "苏尘手里空空如也。"
                    }
                  ]
                }
                """);

        ProseScalpelService service = new ProseScalpelService(gateway, enabledProps(8));
        assertNull(service.trim(new StoryVO.Module(), item(), original, 1), "删掉关键数字必须被完整性锁拦回");
    }

    @Test
    void rejectsOverAggressiveTrimBelowEightyPercent() {
        LlmGateway gateway = mock(LlmGateway.class);
        String tail = "院外山风灌进棚里，油灯晃出一圈昏影，苏尘收起药篮，转身往北屋走去。".repeat(32);
        String original = "苏尘站在院里。\n\n" + tail + "\n\n刘全在门边看着。";
        when(gateway.complete(any(), any())).thenReturn(
                "{\"patches\":[{\"anchor\":" + com.alibaba.fastjson2.JSON.toJSONString(tail) + ",\"replacement\":\"\"}]}");

        ProseScalpelService service = new ProseScalpelService(gateway, enabledProps(8));
        assertNull(service.trim(new StoryVO.Module(), item(), original, 1), "删减超过20%篇幅必须回退保留原稿");
    }

    @Test
    void failsSoftWhenLlmThrowsOrReturnsMalformedJson() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.complete(any(), any()))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn("not json at all");
        ProseScalpelService service = new ProseScalpelService(gateway, enabledProps(8));

        assertNull(service.trim(new StoryVO.Module(), item(), longManuscript(), 1));
        assertNull(service.trim(new StoryVO.Module(), item(), longManuscript(), 1));
    }

    @Test
    void skipsUnsafePatchThatDropsAnchorWhileKeepingSafeShrinkPatch() {
        LlmGateway gateway = mock(LlmGateway.class);
        String original = "苏尘手里攥着 1280 块下品灵石。\n\n" + longManuscript();
        when(gateway.complete(any(), any())).thenReturn("""
                {
                  "patches": [
                    {
                      "anchor": "苏尘手里攥着 1280 块下品灵石。",
                      "replacement": "苏尘攥着灵石。"
                    },
                    {
                      "anchor": "刘全比苏尘早入药园一年，平日最爱在管事面前刷脸踩人。",
                      "replacement": ""
                    }
                  ]
                }
                """);

        ProseScalpelService service = new ProseScalpelService(gateway, enabledProps(8));
        String trimmed = service.trim(new StoryVO.Module(), item(), original, 1);

        assertNotNull(trimmed);
        assertTrue(trimmed.contains("1280"), "破坏数字锚点的坏补丁应被单独跳过");
        assertFalse(trimmed.contains("平日最爱在管事面前刷脸踩人"), "同批安全去简历补丁应正常保留生效");
    }

    @Test
    void skipsPatchThatLeavesDanglingCommaWhileKeepingCleanPatch() {
        LlmGateway gateway = mock(LlmGateway.class);
        String original = longManuscript();
        when(gateway.complete(any(), any())).thenReturn("""
                {
                  "patches": [
                    {
                      "anchor": "血丝混着冷露渗进石缝。",
                      "replacement": ""
                    },
                    {
                      "anchor": "刘全比苏尘早入药园一年，平日最爱在管事面前刷脸踩人。",
                      "replacement": ""
                    }
                  ]
                }
                """);

        ProseScalpelService service = new ProseScalpelService(gateway, enabledProps(8));
        String trimmed = service.trim(new StoryVO.Module(), item(), original, 1);

        assertNotNull(trimmed);
        assertTrue(trimmed.contains("血丝混着冷露渗进石缝。"), "会留下逗号残段的补丁必须被跳过");
        assertFalse(trimmed.contains("平日最爱在管事面前刷脸踩人"), "正常整句删除补丁应保留生效");
    }

    @Test
    void repairedParagraphCannotOffsetNewDamageElsewhereInBatch() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.complete(any(), any())).thenReturn("""
                {"patches":[
                  {"anchor":"夜雾里看不太清他的表情，","replacement":"夜雾里看不太清他的表情。"},
                  {"anchor":"走进院子。","replacement":""},
                  {"anchor":"他望着灯火，","replacement":"他望着灯火。"},
                  {"anchor":"转身回屋。","replacement":""}
                ]}
                """);
        String original = "夜雾里看不太清他的表情，\n\n他拿起油灯，走进院子。\n\n他望着灯火，转身回屋。\n\n"
                + longManuscript();
        String edited = new ProseScalpelService(gateway, enabledProps(8))
                .trim(new StoryVO.Module(), item(), original, 2);
        assertNotNull(edited);
        assertTrue(edited.contains("夜雾里看不太清他的表情。"));
        assertTrue(edited.contains("他拿起油灯，走进院子。"));
        assertTrue(edited.contains("他望着灯火。\n\n"));
    }
}
