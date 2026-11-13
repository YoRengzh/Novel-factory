package cn.novel.yonren.domain.novel.service.armory.quality;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文级对话承载对账：核对 dialogueScenes 声明的台词是否以引号对白落进正文。
 * 只识别缺失，不单独决定修复；等价改写可能无法逐字匹配。
 * 调用方结合轮次密度坍缩判定严重度，将缺失清单作为定向修订目标。
 *
 * 匹配口径：归一化包含、LCS 占声明台词至少一半，或短台词在短对白内命中。
 * 引号提取复用 {@link DialogueRatioPolicy#utterances}，LCS 复用
 * {@link PlanAdherencePolicy#longestCommonSubstring}；标点不参与匹配。
 */
public final class DialoguePresencePolicy {

    /** 短台词阈值（归一化后 ≤ 此长度按特殊规则判定）：单字/双字台词的包含匹配噪声不可接受 */
    private static final int SHORT_SPEECH_MAX = 2;
    /** 短对白阈值：短台词只允许在 ≤ 此长度的正文对白里找（「嗯，知道了」算交付「嗯」） */
    private static final int SHORT_LINE_MAX = 4;
    /** 改写容忍线：归一化 LCS 长度须达到声明台词长度的此占比 */
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
