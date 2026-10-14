package cn.novel.yonren.domain.novel.service.armory.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正文级对话承载对账测试：夹具全部取自 20261004-story-0001 第 106-110 章实测数据
 * （计划声明 vs 成文正文），把校准结论直接锁进用例——匹配阈值一旦改动破坏真实数据的
 * 行为（该报的不报、该放的不放）会立刻在这里暴露。
 */
class DialoguePresencePolicyTest {

    /** 第 110 章实测：声明两句，正文只写出前一句，「嗯」被降级成"点了点头"——必须识别出缺失 */
    @Test
    void ch110_deliveredOneOfTwo_reportsSecondMissing() {
        String content = """
                角色甲直起身，把铅笔搁回笔筒，转过头来看他。
                「既然记着，这个也记上。」
                声音不高，跟平时叫儿子吃饭一个调。
                角色乙点了点头。
                两个人隔着屋子对看了一眼。""";
        List<String> missing = DialoguePresencePolicy.missingScenes(
                List.of("角色甲：「既然记着，这个也记上。」", "角色乙：「嗯。」"), content);

        assertEquals(1, missing.size(), "只有「嗯」那条场景缺失：" + missing);
        assertTrue(missing.get(0).contains("角色乙"), "缺失的应是角色乙那条：" + missing);
    }

    /** 两句都写出（哪怕引号形态/标点有微差）→ 无缺失 */
    @Test
    void bothDelivered_noMissing() {
        String content = """
                她转过头来看他。
                「既然记着，这个也记上。」
                角色乙应了。
                「嗯。」""";
        List<String> missing = DialoguePresencePolicy.missingScenes(
                List.of("角色甲：「既然记着，这个也记上。」", "角色乙：「嗯。」"), content);

        assertTrue(missing.isEmpty(), "两句都已落进正文：" + missing);
    }

    /** 第 108 章实测：整句重写（LCS 占比 0.15）按缺失报——是否触发修复由密度判定，本类只如实报告 */
    @Test
    void ch108_wholeRewrite_reportedMissing() {
        String declared = "角色丙：「好，但你得自己把最后一步补上。」";
        String content = "他把笔递过去。\n「后半段我写到这儿，剩下的你自己接。」\n她接过了笔。";
        List<String> missing = DialoguePresencePolicy.missingScenes(List.of(declared), content);

        assertEquals(1, missing.size(), "整句重写超出改写容忍线，应如实报缺失：" + missing);
    }

    /** 第 109 章实测：同场景有两句正文对白，近原句子句（共享段 10/18=0.56）达线即算场景已交付 */
    @Test
    void ch109_wordReordered_countsAsDelivered() {
        String declared = "马老师：「省队总教练亲自关联的来源，我有责任归档。」";
        String content = """
                他把归档夹合上。
                「这封信的来源，是省队总教练亲自关联的。」
                「通信归档的依据是省队总教练关联来源，我有责任归档。」
                方组长没再问。""";
        List<String> missing = DialoguePresencePolicy.missingScenes(List.of(declared), content);

        assertTrue(missing.isEmpty(), "场景内任一句台词达线（0.56≥0.5）即算交付：" + missing);
    }

    /** 容忍线下界：只有词序对调一句（共享段 8/18=0.44）时低于 0.5 线，如实报缺失 */
    @Test
    void ch109_reorderOnly_belowTolerance_reportedMissing() {
        String declared = "马老师：「省队总教练亲自关联的来源，我有责任归档。」";
        String content = "他把归档夹合上。\n「通信归档的依据是省队总教练关联来源，我有责任归档。」\n方组长没再问。";
        List<String> missing = DialoguePresencePolicy.missingScenes(List.of(declared), content);

        assertEquals(1, missing.size(), "单独的词序对调（0.44）低于容忍线，应报缺失");
    }

    /** 第 108 章实测：写手在声明台词前后加了话，但核心句原样在——包含匹配判已交付 */
    @Test
    void ch108_expandedLine_countsAsDelivered() {
        String declared = "角色己：「这题我一个人做不完，你来补后半段。」";
        String content = "她把信纸推过来。\n「省里来的信里夹的。那道构造题，我一个人做不完。你来补后半段。」\n他看着那行字。";
        List<String> missing = DialoguePresencePolicy.missingScenes(List.of(declared), content);

        assertTrue(missing.isEmpty(), "声明台词完整包含在正文对白中：" + missing);
    }

    /** 台词进了叙述但没进引号对白，不算交付——对话承载承诺的是"开腔"，不是"提及" */
    @Test
    void narrationMentionWithoutQuotes_notDelivered() {
        String content = """
                角色乙嗯了一声，算是应了。
                角色甲既然记着，这个也记上了。""";
        List<String> missing = DialoguePresencePolicy.missingScenes(
                List.of("角色乙：「嗯。」", "角色甲：「既然记着，这个也记上。」"), content);

        assertEquals(2, missing.size(), "无引号对白一律不算交付：" + missing);
    }

    /** 纯叙述式声明（无引号台词）无法机械核对——跳过，不得误报 */
    @Test
    void sceneWithoutQuotedSpeech_skipped() {
        String content = "他把碗端起来又放下，一句话没说。";
        List<String> missing = DialoguePresencePolicy.missingScenes(
                List.of("两人在厨房里低声商量报名的事"), content);

        assertTrue(missing.isEmpty(), "无台词可校验的声明不判缺失");
    }

    /** 短台词的包含匹配只认短对白：长句里的单字不构成交付（「他嗯了一声站起来」≠「嗯」） */
    @Test
    void shortSpeech_onlyMatchesShortUtterance() {
        String declared = "角色乙：「嗯。」";
        String longOnly = "他嗯了一声站起来，走到门口。\n「走吧，不早了。」";
        assertEquals(1, DialoguePresencePolicy.missingScenes(List.of(declared), longOnly).size(),
                "长句包含单字属噪声，不算交付");
        String shortUtterance = "他抬起头。\n「嗯。」";
        assertTrue(DialoguePresencePolicy.missingScenes(List.of(declared), shortUtterance).isEmpty(),
                "短对白里的短台词算交付");
    }

    /** 防御路径：入参为空/null 时直接返回空，不抛异常 */
    @Test
    void nullOrEmptyInputs_safeEmpty() {
        assertTrue(DialoguePresencePolicy.missingScenes(null, "「嗯。」").isEmpty());
        assertTrue(DialoguePresencePolicy.missingScenes(List.of(), "「嗯。」").isEmpty());
        assertTrue(DialoguePresencePolicy.missingScenes(List.of("角色乙：「嗯。」"), null).isEmpty());
        assertTrue(DialoguePresencePolicy.missingScenes(List.of("角色乙：「嗯。」"), "  ").isEmpty());
    }
}
