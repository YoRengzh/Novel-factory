package cn.novel.yonren.domain.novel.service.armory.quality;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatchIntegrityPolicy {

    /** 数字锚最小长度：单字符噪声大，两位以上才当事实（如 17 号、1987、8:59 的 59） */
    private static final int MIN_NUMBER_ANCHOR_LENGTH = 2;
    /** 数字锚上限：防长正文里数字过多把判据淹没 */
    private static final int MAX_NUMBER_ANCHORS = 40;

    /** 数字串（连续 ASCII 数字）；中文数字日期不在此列——宁可漏报，也不引入模糊判据 */
    private static final Pattern NUMBER_RUN = Pattern.compile("\\d{" + MIN_NUMBER_ANCHOR_LENGTH + ",}");

    private PatchIntegrityPolicy() {
    }

    /**
     * 校验结果：{@code acceptable} 为 false 时，{@code lostEvents}/{@code lostAnchors} 给出具体损失项。
     *
     * @param lostEvents   原稿覆盖、补丁后不覆盖的关键事件
     * @param lostAnchors  原稿有、补丁后全章再无的实体名/数字
     */
    public record Verdict(boolean acceptable, List<String> lostEvents, List<String> lostAnchors) {

        /** 拒绝原因（可读，供日志与回退说明）；通过时为空串 */
        public String reason() {
            if (acceptable) {
                return "";
            }
            List<String> parts = new ArrayList<>(2);
            if (!lostEvents.isEmpty()) {
                parts.add("丢失关键事件 " + lostEvents.size() + " 条（" + StringUtils.abbreviate(lostEvents.get(0), 40) + "）");
            }
            if (!lostAnchors.isEmpty()) {
                parts.add("丢失关键实体/数字 " + lostAnchors.size() + " 项（" + String.join("、", lostAnchors.stream().limit(5).toList()) + "）");
            }
            return String.join("；", parts);
        }
    }

    /**
     * 综合校验：两条判据各自与允许损失额比较。
     *
     * <p>额度必须**分开计**：把实体损失并进事件额度，等于把事件额度悄悄放宽——两者性质不同
     * （事件是剧情骨架，实体/数字是事实锚），能容忍的程度也不同。
     *
     * @param allowedEventLoss  允许丢失的关键事件条数（修订链路 1；密度挤水 0）
     * @param allowedAnchorLoss 允许丢失的实体/数字条数（密度挤水 0）
     */
    public static Verdict check(String original, String patched, List<String> keyEvents, List<String> anchors,
                               int allowedEventLoss, int allowedAnchorLoss) {
        List<String> lostEvents = lostEvents(original, patched, keyEvents);
        List<String> lostAnchors = lostAnchors(original, patched, anchors);
        boolean acceptable = lostEvents.size() <= Math.max(0, allowedEventLoss)
                && lostAnchors.size() <= Math.max(0, allowedAnchorLoss);
        return new Verdict(acceptable, lostEvents, lostAnchors);
    }

    /**
     * 原稿覆盖、补丁后不覆盖的关键事件（原稿本就未覆盖的不计入——修订无从恢复从未存在的内容）。
     *
     * <p>入参是**原稿/补丁稿原文**，归一化在本方法内完成：让调用方自己归一化是留给后人踩的坑
     * （漏归一化不会报错，只会让判据静默变松）。
     */
    public static List<String> lostEvents(String original, String patched, List<String> keyEvents) {
        List<String> lost = new ArrayList<>();
        if (keyEvents == null || keyEvents.isEmpty()) {
            return lost;
        }
        String normalizedOriginal = normalize(original);
        String normalizedPatched = normalize(patched);
        for (String keyEvent : keyEvents) {
            if (StringUtils.isBlank(keyEvent)) {
                continue;
            }
            if (!PlanAdherencePolicy.covered(normalizedOriginal, keyEvent)) {
                continue;
            }
            if (!PlanAdherencePolicy.covered(normalizedPatched, keyEvent)) {
                lost.add(keyEvent.trim());
            }
        }
        return lost;
    }

    /** 原稿有、补丁后全章再无的锚（实体名/数字串）；入参为原稿/补丁稿原文 */
    public static List<String> lostAnchors(String original, String patched, List<String> anchors) {
        List<String> lost = new ArrayList<>();
        if (anchors == null || anchors.isEmpty()) {
            return lost;
        }
        String normalizedOriginal = normalize(original);
        String normalizedPatched = normalize(patched);
        for (String anchor : anchors) {
            String value = normalize(anchor);
            if (value.isEmpty()) {
                continue;
            }
            if (normalizedOriginal.contains(value) && !normalizedPatched.contains(value)) {
                lost.add(value);
            }
        }
        return lost;
    }

    /**
     * 从正文提取数字串锚（≥{@value #MIN_NUMBER_ANCHOR_LENGTH} 位、去重、限量）。
     *
     * <p>数字是年代文里最硬的事实（考场号、年份、时间、金额），丢一个就是事实错误；
     * 而"只出现一次的实体整段消失"正是密度补丁最容易犯、又最难被发现的那种损失。
     */
    public static List<String> numberAnchors(String content) {
        if (StringUtils.isBlank(content)) {
            return List.of();
        }
        Set<String> anchors = new LinkedHashSet<>();
        Matcher matcher = NUMBER_RUN.matcher(content);
        while (matcher.find() && anchors.size() < MAX_NUMBER_ANCHORS) {
            anchors.add(matcher.group());
        }
        return new ArrayList<>(anchors);
    }

    /** 角色名锚：计划里列出的角色（去掉空白后的原文），空计划返回空表 */
    public static List<String> characterAnchors(List<String> characters) {
        if (characters == null || characters.isEmpty()) {
            return List.of();
        }
        Set<String> anchors = new LinkedHashSet<>();
        for (String name : characters) {
            String value = normalize(name);
            if (!value.isEmpty()) {
                anchors.add(value);
            }
        }
        return new ArrayList<>(anchors);
    }

    /** 与两条补丁路径的既有口径一致：去全部空白后比对 */
    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}
