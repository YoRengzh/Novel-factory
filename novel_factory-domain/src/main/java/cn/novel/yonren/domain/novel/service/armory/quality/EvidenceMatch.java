package cn.novel.yonren.domain.novel.service.armory.quality;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;


public final class EvidenceMatch {

    /** 状态证据原文引用上限字数（与摘要 prompt 约束一致，超长截断以保住子串有效性） */
    public static final int STATE_EVIDENCE_MAX_LENGTH = 50;

    private static final double SPAN_COMPACT_RATIO = 0.5;

    private static final int SPAN_COMPACT_MAX_SPAN = 400;

    /** 短语最短字数：过短短语（「然后」「于是」）不参与覆盖判定，防误命中 */
    private static final int PHRASE_MIN_LENGTH = 6;

    /** 短语覆盖档所需最少短语数：多短语时全部命中即放行 */
    private static final int PHRASE_MIN_COUNT = 2;

    private static final int ANCHOR_MIN_LENGTH = 8;

    private static final int SEGMENT_MIN_LENGTH = 8;

    /** 长段命中比例下限：半数及以上逐字可定位即视为证据成立（放宽的是表述，不是事实有无） */
    private static final double SEGMENT_HIT_RATIO = 0.5;

    /** 省略号：全角「…」连续，或三个以上英文句点（与审校侧原口径一致） */
    private static final Pattern ELLIPSIS = Pattern.compile("…+|\\.{3,}");

    /** 短语切分：中文标点或省略号 */
    private static final Pattern PHRASE_SEPARATOR = Pattern.compile("[，。；、！？：,;!?:]+|…+|\\.{3,}");

    private EvidenceMatch() {
    }

    /** 命中档位。前六档可入账（SPREAD / ANCHORED 为留痕档），后两档转隔离待裁决。 */
    public enum Tier {

        EXACT("exact"),
        NORMALIZED("normalized"),
        FRAGMENTED("fragmented"),
        PHRASE_COVERED("phrase-covered"),
        ANCHORED("anchored"),
        SPREAD("spread"),
        PHRASE_PARTIAL("phrase-partial"),
        NO_MATCH("no-match");

        private final String code;

        Tier(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        /**
         * 该档位是否可入账（放宽表述差异，但不放宽事实有无）。
         * SPREAD / ANCHORED 属「留痕档」：调用方应把档位写回条目的 {@code evidenceTier}，
         * 供观测层统计占比——一旦这两档对 continuityConflicts 的贡献异常，即可回收复核。
         */
        public boolean isAccepted() {
            return this == EXACT || this == NORMALIZED || this == FRAGMENTED
                    || this == PHRASE_COVERED || this == ANCHORED || this == SPREAD;
        }

        /**
         * 是否为「放宽留痕档」：已入账，但证据形态弱于标准档（跨章引用 / 单条短语锚定）。
         * 调用方应把此类档位写回条目的 {@code evidenceTier}，供观测层统计放宽档占账本的比例。
         */
        public boolean isLoose() {
            return this == SPREAD || this == ANCHORED;
        }
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\n", "\n")
                .replaceAll("[\\s\\u3000]+", "")
                .replace('“', '"').replace('”', '"')
                .replace('‘', '"').replace('’', '"')
                .replace('\'', '"')
                .replaceAll("^[\"「『]+|[\"」』]+$", "");
    }

    /** 证据裁剪：trim 后截断到上限（与摘要 prompt 的字数约束对齐） */
    public static String truncate(String evidence, int maxLength) {
        if (evidence == null) {
            return "";
        }
        String trimmed = evidence.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /**
     * 宽松命中判定（审校 BLOCKING 证据校验口径）：归一化后整段包含，或省略号分段后各段全部包含。
     *
     * @param evidence          待校验证据
     * @param normalizedContent 已归一化的正文（调用方批量复用以省重复归一化）
     */
    public static boolean contained(String evidence, String normalizedContent) {
        String normalized = normalize(evidence);
        if (normalized.isEmpty() || normalizedContent == null) {
            return false;
        }
        // 纯标点证据（"。" / "……。"）必然“包含”于任何正文：没有可核对的实词就等于没有证据。
        // 只拦“整条无实词”，不设最小长度——短但真实的引文（如“退婚”）仍按原口径判定
        if (!hasSubstantiveChar(normalized)) {
            return false;
        }
        if (normalizedContent.contains(normalized)) {
            return true;
        }
        String[] fragments = ELLIPSIS.split(normalized);
        if (fragments.length <= 1) {
            return false;
        }
        for (String fragment : fragments) {
            if (!fragment.isEmpty() && !normalizedContent.contains(fragment)) {
                return false;
            }
        }
        return true;
    }

    /** 是否含至少一个实词字符（CJK 汉字在 Java 里属于 letter；纯标点/空白返回 false） */
    private static boolean hasSubstantiveChar(String normalized) {
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isLetterOrDigit(normalized.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean segmentsContained(String evidence, String normalizedContent) {
        String normalized = normalize(evidence);
        if (normalized.isEmpty() || normalizedContent == null) {
            return false;
        }
        // 快路径：整串命中（含省略号分段全命中），与旧口径保持一致
        if (contained(evidence, normalizedContent)) {
            return true;
        }
        String[] segments = PHRASE_SEPARATOR.split(normalized);
        int longSegments = 0;
        int hits = 0;
        for (String segment : segments) {
            if (segment.length() < SEGMENT_MIN_LENGTH) {
                continue;
            }
            longSegments++;
            if (normalizedContent.contains(segment)) {
                hits++;
            }
        }
        if (longSegments == 0) {
            // 整条都是短段：没有可锚定的长片段，退回整串判定（宁可漏放，不可放过）
            return false;
        }
        return hits >= Math.max(1, (int) Math.ceil(longSegments * SEGMENT_HIT_RATIO));
    }

    public static Tier classify(String evidence, String content) {
        if (StringUtils.isBlank(evidence)) {
            return Tier.EXACT;
        }
        String trimmed = truncate(evidence, STATE_EVIDENCE_MAX_LENGTH);
        if (StringUtils.isBlank(content)) {
            return Tier.NO_MATCH;
        }
        if (content.contains(trimmed)) {
            return Tier.EXACT;
        }
        String normalizedContent = normalize(content);
        String normalizedEvidence = normalize(trimmed);
        if (normalizedEvidence.isEmpty()) {
            return Tier.NO_MATCH;
        }
        if (normalizedContent.contains(normalizedEvidence)) {
            return Tier.NORMALIZED;
        }
        Tier fragmented = matchFragments(normalizedEvidence, normalizedContent);
        return fragmented != null ? fragmented : matchPhrases(normalizedEvidence, normalizedContent);
    }

    /**
     * 省略号分段命中：各段均可在正文中定位，且最小包围区间不超过正文的 {@link #SPAN_COMPACT_RATIO}。
     *
     * @return 命中档位；某段完全找不到返回 null（交由下一档继续判定）
     */
    private static Tier matchFragments(String normalizedEvidence, String normalizedContent) {
        String[] fragments = ELLIPSIS.split(normalizedEvidence);
        if (fragments.length <= 1) {
            return null;
        }
        int minStart = Integer.MAX_VALUE;
        int maxEnd = -1;
        int cursor = 0;
        for (String fragment : fragments) {
            if (fragment.isEmpty()) {
                continue;
            }
            int index = normalizedContent.indexOf(fragment, cursor);
            if (index < 0) {
                // 模型可能重排同一段对话的语序，回退全局查找；语序不影响可信度，跨度才影响
                index = normalizedContent.indexOf(fragment);
                if (index < 0) {
                    return null;
                }
            }
            minStart = Math.min(minStart, index);
            maxEnd = Math.max(maxEnd, index + fragment.length());
            cursor = index + fragment.length();
        }
        if (maxEnd < 0) {
            return null;
        }
        int span = maxEnd - minStart;
        double ratio = normalizedContent.isEmpty()
                ? 0d : (double) span / normalizedContent.length();
        return ratio <= SPAN_COMPACT_RATIO || span <= SPAN_COMPACT_MAX_SPAN
                ? Tier.FRAGMENTED : Tier.SPREAD;
    }

    private static Tier matchPhrases(String normalizedEvidence, String normalizedContent) {
        String[] phrases = PHRASE_SEPARATOR.split(normalizedEvidence);
        int total = 0;
        int hit = 0;
        int longest = 0;
        for (String phrase : phrases) {
            if (phrase.length() < PHRASE_MIN_LENGTH) {
                continue;
            }
            total++;
            longest = Math.max(longest, phrase.length());
            if (normalizedContent.contains(phrase)) {
                hit++;
            }
        }
        if (total == 0 || hit == 0) {
            return Tier.NO_MATCH;
        }
        if (hit < total) {
            // 部分短语命中：模型改写了部分措辞，不足以自证，交裁决层
            return Tier.PHRASE_PARTIAL;
        }
        if (total >= PHRASE_MIN_COUNT) {
            return Tier.PHRASE_COVERED;
        }
        // 只剩单条短语：以锚定强度而非段数判可信，短锚仍交裁决层
        return longest >= ANCHOR_MIN_LENGTH ? Tier.ANCHORED : Tier.PHRASE_PARTIAL;
    }
}
