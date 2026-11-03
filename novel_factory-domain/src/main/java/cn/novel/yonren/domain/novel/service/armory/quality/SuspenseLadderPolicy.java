package cn.novel.yonren.domain.novel.service.armory.quality;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;


public final class SuspenseLadderPolicy {

    /** 缺少可核验章级细推进时的同档停留门槛；不是语义停滞判据。 */
    public static final int HOLD_LIMIT = 3;

    private static boolean transitionExempt(Beat beat, int trailingTransitionRun) {
        return beat.transition() && trailingTransitionRun == 1;
    }

    private SuspenseLadderPolicy() {
    }

    /** verifiedMainLineAdvance 仅由调用方完成蓝图对表、去标签去重后置为 true，不能由附加文本推断。 */
    public record Beat(int chapterNo, String rawBeat, boolean transition, boolean verifiedMainLineAdvance) {
        public Beat(int chapterNo, String rawBeat, boolean transition) {
            this(chapterNo, rawBeat, transition, false);
        }
    }

    /** 最长粗档停留段：仅作观测；达门槛不等于已经判定语义停滞。 */
    public record HoldRun(int length, int startChapterNo, int endChapterNo) {
        public boolean violated() {
            return length >= HOLD_LIMIT;
        }
    }

    /** 档位表可用（至少 2 档）——不足时所有校验与统计一律跳过，不得凭空判定 */
    public static boolean usable(List<String> ladder) {
        return ladder != null && ladder.size() >= 2;
    }

    public static List<String> violations(List<Beat> beats, List<String> ladder) {
        List<String> issues = new ArrayList<>();
        if (beats == null || beats.isEmpty() || !usable(ladder)) {
            return issues;
        }
        Integer prevIndex = null;
        int run = 0;
        int trailingTransitionRun = 0;
        boolean runFlagged = false;
        boolean runVerified = false;
        for (Beat beat : beats) {
            if (beat == null) {
                trailingTransitionRun = 0;
                continue;
            }
            int index = resolveIndex(beat.rawBeat(), ladder);
            if (index < 0) {
                issues.add("第 " + beat.chapterNo() + " 章未声明 suspenseBeat，或所填内容不是档位表原文");
                // 缺声明不计入停留链，避免把格式问题放大成推进问题；同时打断"连续过渡"计数
                trailingTransitionRun = 0;
                continue;
            }
            trailingTransitionRun = beat.transition() ? trailingTransitionRun + 1 : 0;
            if (prevIndex == null) {
                run = 1;
                runVerified = beat.verifiedMainLineAdvance();
            } else {
                if (index < prevIndex) {
                    issues.add("第 " + beat.chapterNo() + " 章**档位倒退**：" + brief(ladder.get(index))
                            + " ← 上一档 " + brief(ladder.get(prevIndex))
                            + "（「产生怀疑又被自我否定圆回」属于倒退，不是推进）");
                }
                boolean sameRung = index == prevIndex;
                run = sameRung ? run + 1 : 1;
                runVerified = sameRung ? runVerified && beat.verifiedMainLineAdvance()
                        : beat.verifiedMainLineAdvance();
                if (!sameRung) {
                    // 换档即换停留段，重新获得一次告警机会
                    runFlagged = false;
                }
            }
            // 同一停留段只报一次：回注给规划模型的信息要保持可读，重复告警只会稀释重点
            // 过渡章豁免已收紧为"段末恰好 1 章过渡"（见 transitionExempt）：连续 ≥2 章过渡照样报违规
            if (run >= HOLD_LIMIT && !runVerified
                    && !transitionExempt(beat, trailingTransitionRun) && !runFlagged) {
                issues.add("第 " + beat.chapterNo() + " 章起**主线原地风险**：已连续 " + run
                        + " 章停留在同一粗档位「" + brief(ladder.get(index))
                        + "」，且缺少与蓝图逐字对表、去标签后非重复的 mainLineAdvance；不能仅凭同档断言语义停滞"
                        + (beat.transition() ? "（且末尾连续 " + trailingTransitionRun
                        + " 章为过渡章，不是单章蓄势）" : ""));
                runFlagged = true;
            }
            prevIndex = index;
        }
        return issues;
    }

    public static HoldRun maxHold(List<Beat> beats, List<String> ladder) {
        HoldRun longest = new HoldRun(0, 0, 0);
        if (beats == null || beats.isEmpty() || !usable(ladder)) {
            return longest;
        }
        Integer prevIndex = null;
        int run = 0;
        int runStart = 0;
        int trailingTransitionRun = 0;
        for (Beat beat : beats) {
            if (beat == null) {
                trailingTransitionRun = 0;
                continue;
            }
            int index = resolveIndex(beat.rawBeat(), ladder);
            if (index < 0) {
                trailingTransitionRun = 0;
                continue;
            }
            trailingTransitionRun = beat.transition() ? trailingTransitionRun + 1 : 0;
            if (prevIndex != null && index == prevIndex) {
                run++;
            } else {
                run = 1;
                runStart = beat.chapterNo();
            }
            // 保留旧的过渡收尾统计口径；细推进豁免不改变粗档停留观测。
            if (!transitionExempt(beat, trailingTransitionRun) && run > longest.length()) {
                longest = new HoldRun(run, runStart, beat.chapterNo());
            }
            prevIndex = index;
        }
        return longest;
    }

    /**
     * 把章计划回填的档位解析成档位表下标；无法**唯一定位**时返回 -1。
     * 容忍模型常见的"1. 档位原文""档位原文（补一句解释）"，但出现歧义宁可判不通过——
     * 静默归错档会让校验失去意义。
     */
    public static int resolveIndex(String rawBeat, List<String> ladder) {
        if (StringUtils.isBlank(rawBeat) || ladder == null) {
            return -1;
        }
        String normalized = rawBeat.trim().replaceAll("^[0-9]+\\s*[.、)）:：]?\\s*", "");
        if (normalized.isEmpty()) {
            return -1;
        }
        // 精确 / 前缀命中优先
        for (int i = 0; i < ladder.size(); i++) {
            String entry = trimmed(ladder.get(i));
            if (!entry.isEmpty() && (normalized.equals(entry) || normalized.startsWith(entry))) {
                return i;
            }
        }
        // 退一步：唯一包含关系
        int hit = -1;
        for (int i = 0; i < ladder.size(); i++) {
            String entry = trimmed(ladder.get(i));
            if (entry.isEmpty()) {
                continue;
            }
            if (normalized.contains(entry) || entry.contains(normalized)) {
                if (hit >= 0) {
                    return -1;
                }
                hit = i;
            }
        }
        return hit;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    // ==================== 相邻章档位描述去重 ====================

    public static List<String> duplicateBeatViolations(List<Beat> beats) {
        List<String> issues = new ArrayList<>();
        if (beats == null || beats.size() < 2) {
            return issues;
        }
        String prev = null;
        int prevNo = 0;
        int run = 1;
        boolean runVerified = false;
        boolean runFlagged = false;
        for (Beat beat : beats) {
            if (beat == null) {
                continue;
            }
            String current = normalizeForDuplicate(beat.rawBeat());
            if (current.isEmpty()) {
                prev = null;
                prevNo = 0;
                run = 1;
                runVerified = false;
                runFlagged = false;
                continue;
            }
            if (current.equals(prev)) {
                run++;
                runVerified &= beat.verifiedMainLineAdvance();
                // 已核验的独有细推进允许复用粗档原文；缺证据后仍须恢复告警。
                if (run >= 2 && !runVerified && !runFlagged) {
                    issues.add("第 " + prevNo + "–" + beat.chapterNo() + " 章的 suspenseBeat **逐字相同**"
                            + "（「" + brief(current) + "」），且没有可核验的独有细推进。"
                            + "不同推进应写入 mainLineAdvance 并逐字取自蓝图，不要给 suspenseBeat 追加文字绕过去重");
                    runFlagged = true;
                }
            } else {
                prev = current;
                prevNo = beat.chapterNo();
                run = 1;
                runVerified = beat.verifiedMainLineAdvance();
                runFlagged = false;
            }
        }
        return issues;
    }

    /** 去格式噪声后用于逐字比较：空白 / 行首序号 / 含"章"的括注都不算描述差异 */
    private static String normalizeForDuplicate(String rawBeat) {
        String text = trimmed(rawBeat);
        if (text.isEmpty()) {
            return "";
        }
        return text
                .replaceAll("[\\s\u3000]", "")
                .replaceAll("^[0-9]+\\s*[.、)）:：]?", "")
                .replaceAll("[（(][^）)]*章[）)]", "");
    }

    /** 档位条目是"可观察表述"，可能较长，违规信息里截断以便阅读 */
    private static String brief(String value) {
        String text = trimmed(value);
        return text.length() <= 28 ? text : text.substring(0, 28) + "…";
    }
}
