package cn.novel.yonren.domain.novel.service.armory.quality;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文级对话承载对账（机械，2026-10-11）：计划声明了 dialogueScenes 的章，声明的台词
 * 是否真的以<b>引号对白</b>落进了正文。实测第 110 章：计划声明两句（周秀兰「既然记着，
 * 这个也记上。」＋陆远山「嗯。」），正文只写出前一句，后一句被降级成"点了点头"——
 * 计划层的对话承载闸门只校验声明本身（存在/数量/诚实/双名），管不到正文兑现，
 * 本类补上"声明 → 正文"这半程对账。
 *
 * <p><b>只做缺失识别，不做触发判定</b>（106-110 五章实测校准）：健康章节的写手会大量
 * <b>等价改写</b>声明台词——第 108 章四条场景两条 LCS 占比 &lt;0.2 而交锋完整落在正文
 * （「好，但你得自己把最后一步补上」→「后半段我写到这儿，剩下的你自己接」），逐字对账
 * 会几乎每章白吃一轮修订。因此本类只回答"哪些声明的台词没落地"；是否触发修复由调用方
 * 结合轮次密度坍缩判定（{@code QualityGate#dialogueDensityCollapse}：声明了对话场景的章
 * 密度坍缩从 MINOR 升级 BLOCKING——计划已承诺对话而正文开不了腔是写手违约，不再只是
 * 规划层没给料；缺失清单作为修订靶点随建议下发）。
 *
 * <p><b>匹配口径</b>（三选一即算落地）：①归一化后互相包含（写手扩写/缩写台词）；
 * ②LCS 占声明台词长度 ≥1/2（换词不换意的改写：实测词序对调 0.56 命中、整句重写 0.15 不命中）；
 * ③归一化后 ≤2 字的短台词（「嗯」「好」）只在 ≤4 字的短对白里按包含判定——单字台词包含在
 * 长句里属噪声（「他嗯了一声站起来」不是交付台词）。引号提取复用
 * {@link DialogueRatioPolicy#utterances}（仓库内"什么算一句对白"的唯一定义），LCS 复用
 * {@link PlanAdherencePolicy#longestCommonSubstring}（仓库内唯一实现，避免第三份口径）。
 * 归一化去掉全部标点：台词改写常伴随标点与语气词变化（「就是为了不进保送？」vs
 * 「是不想进保送吧。」），标点不参与匹配。
 */
public final class DialoguePresencePolicy {

    /** 短台词阈值（归一化后 ≤ 此长度按特殊规则判定）：单字/双字台词的包含匹配噪声不可接受 */
    private static final int SHORT_SPEECH_MAX = 2;
    /** 短对白阈值：短台词只允许在 ≤ 此长度的正文对白里找（「嗯，知道了」算交付「嗯」） */
    private static final int SHORT_LINE_MAX = 4;
    /** 改写容忍线：归一化 LCS 长度须达到声明台词长度的此占比（实测 0.56=词序对调命中） */
    private static final double REWRITE_RATIO = 0.5;
    /** 压缩匹配下限：正文对白至少要有这么长，才可能算"声明长台词的压缩版" */
    private static final int COMPRESSED_LINE_FLOOR = 4;

    private DialoguePresencePolicy() {
    }

    /**
     * 找出"声明了台词、但正文引号对白里找不到"的场景声明（按声明原文返回，顺序不变）。
     * 纯叙述式声明（无引号台词）无法机械核对，跳过；正文或声明任一侧为空直接返回空。
     * 正文完全没有引号对白时所有含台词声明都算缺失——该形态由对白格式坍缩检测另行报格式问题，
     * 两条检测语义不同（一个报"没有对白"，一个报"承诺的对话没兑现"），不去重。
     */
    public static List<String> missingScenes(List<String> declaredScenes, String content) {
        if (declaredScenes == null || declaredScenes.isEmpty()
                || content == null || content.isBlank()) {
            return List.of();
        }
        List<String> proseLines = new ArrayList<>();
        for (String utterance : DialogueRatioPolicy.utterances(content)) {
            String normalized = normalize(utterance);
            if (!normalized.isEmpty()) {
                proseLines.add(normalized);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String scene : declaredScenes) {
            if (scene == null || scene.isBlank()) {
                continue;
            }
            List<String> speeches = new ArrayList<>();
            for (String utterance : DialogueRatioPolicy.utterances(scene)) {
                String normalized = normalize(utterance);
                if (!normalized.isEmpty()) {
                    speeches.add(normalized);
                }
            }
            if (speeches.isEmpty() || speeches.stream().anyMatch(speech -> presentIn(speech, proseLines))) {
                continue;
            }
            missing.add(scene);
        }
        return missing;
    }

    /** 单句声明台词是否落在正文对白中：互相包含 / LCS 占比达线 / 短台词只认短对白 */
    private static boolean presentIn(String speech, List<String> proseLines) {
        for (String line : proseLines) {
            if (speech.length() <= SHORT_SPEECH_MAX) {
                if (line.length() <= SHORT_LINE_MAX && line.contains(speech)) {
                    return true;
                }
                continue;
            }
            if (line.contains(speech)
                    || (line.length() >= COMPRESSED_LINE_FLOOR && speech.contains(line))) {
                return true;
            }
            int needed = (int) Math.ceil(speech.length() * REWRITE_RATIO);
            if (PlanAdherencePolicy.longestCommonSubstring(speech, line) >= needed) {
                return true;
            }
        }
        return false;
    }

    /** 台词归一化：去引号、空白与全部标点，只留汉字/字母/数字 */
    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^\\u4e00-\\u9fffA-Za-z0-9]+", "");
    }
}
