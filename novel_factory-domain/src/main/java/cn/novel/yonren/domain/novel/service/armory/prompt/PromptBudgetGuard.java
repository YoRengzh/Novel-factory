package cn.novel.yonren.domain.novel.service.armory.prompt;

import cn.novel.yonren.domain.novel.model.valobj.properties.PromptBudgetProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBudgetGuard {

    private final PromptBudgetProperties properties;

    /** 块间分隔符（与逐块直拼的旧格式一致） */
    private static final String SEPARATOR = "\n\n";
    /** 截断标记：明确告知模型本块是不完整摘录 */
    private static final String TRUNCATION_MARKER = "\n[本块已按前缀总预算截断]";
    /** 允许截断的最小剩余预算：低于此值截出来的残句无意义，不如整块丢弃 */
    private static final int MIN_TRUNCATED_BLOCK_CHARS = 200;
    /** 核心优先级阈值：priority &le; 此值的块被裁剪即视为预算已咬到核心约束，装配后升 WARN */
    private static final int CORE_PRIORITY = 3;
    /** 余量告警阈值：未裁剪但占比达到此值同样升 WARN（"余量假设"失效的早期信号） */
    private static final int NEAR_LIMIT_PERCENT = 90;

    public enum PrefixBlock {
        /** 账本末态红线：位置/持有物/修为的硬对齐锚点 */
        EDGE_STATE("末态红线", 1, true),
        /** 风格警示：跨章逐字重复句 + 疲劳词超频统计（2026-10-01 由 6 上调至 4） */
        STYLE_WARNING("风格警示", 4, true),
        /** 疲劳词前置预警：机械门禁词表与红线（写手不看会被打回修订）；2026-10-01 由 5 上调至 4 */
        FATIGUE_BLACKLIST("疲劳词红线", 4, true),
        /** 一致性索引：时间线/伤势/术语/关键数字 */
        CONSISTENCY("一致性索引", 4, true),
        /** 本章禁泄清单：伏笔谜底关键词（与逐字扫描同源，截断会静默丢词，故不可截断——放不下就整块弃） */
        SECRECY_GUARD("禁泄清单", 3, false),
        /** 文风指纹库：本书已建立的风格基准（正向激励）；上调至 5（行为指导优先于背景补完） */
        STYLE_FINGERPRINT("文风指纹", 5, true),
        /**
         * 已用情节模式黑名单：防重复套路，上调至 4。理由：它是**唯一**专门防重复情节的块，
         * 却曾排在优先级最低位，实测旧系统第 96-100 章前缀预算连续打满、五章全部丢弃本块——
         * 那批于是在提示词层面完全没有防重复约束，出现跨章重演同一场景与同一伏笔被安排两遍。
         * 按本类准则"行为指导块必须优先于背景补完块"，它不该排在"久远唤醒"（5）之下。
         */
        USED_PATTERN_BLACKLIST("情节模式黑名单", 4, true),
        /** 上章审校反馈（P2）：语义 MINOR 压缩要点——审校报出的问题首次产生行动通道（2026-10-01 由 5 上调至 4） */
        REVIEW_FEEDBACK("审校反馈", 4, true);

        private final String label;
        private final int priority;
        private final boolean truncatable;

        PrefixBlock(String label, int priority, boolean truncatable) {
            this.label = label;
            this.priority = priority;
            this.truncatable = truncatable;
        }

        /** 构造装配块（内容空白时由装配器统一过滤，不占预算、不进日志） */
        public Block toBlock(String content) {
            return new Block(label, priority, truncatable, content);
        }
    }

    public record Block(String label, int priority, boolean truncatable, String content) {
    }


    public String assembleChapterPrefix(int chapterNo, List<Block> blocks) {
        return assemble("正文", chapterNo, properties.getTotalPrefixChars(), blocks);
    }

    /**
     * 章节计划输入段入口：预算取自 {@code planPrefixChars}，块与渲染顺序由调用方给出。
     * 规划 prompt 的固定要求与 schema 段不进本入口——那是规划契约，不参与裁剪
     */
    public String assemblePlanInput(int startNo, List<Block> blocks) {
        return assemble("章节计划输入段", startNo, properties.getPlanPrefixChars(), blocks);
    }

    /**
     * 通用装配入口：渲染顺序 = 入参顺序，淘汰次序 = priority；budget &le; 0 时退化为直拼（便于对比排查）。
     * scene / scopeNo 仅用于装配日志归因（如"正文"+"章号"、"章节计划输入段"+"段起点章号"）
     */
    public String assemble(String scene, int scopeNo, int budget, List<Block> blocks) {
        List<Block> present = presentBlocks(scene, blocks);
        if (present.isEmpty()) {
            return "";
        }
        if (budget <= 0) {
            Map<String, Block> all = indexByLabel(present);
            String result = render(present, all);
            log.info("前缀预算[{}] 第{}章 合计{}字（块{}字+分隔{}字，总额约束未启用，逐块直拼）= [{}]",
                    scene, scopeNo, result.length(), contentChars(all), separatorChars(result, all),
                    describe(present, all));
            return result;
        }

        List<Block> admitted = new ArrayList<>();
        List<String> truncated = new ArrayList<>();
        List<Block> truncatedBlocks = new ArrayList<>();
        List<Block> skipped = new ArrayList<>();
        int remaining = budget;

        List<Block> byPriority = new ArrayList<>(present);
        byPriority.sort(Comparator.comparingInt(Block::priority));
        for (Block block : byPriority) {
            int separator = admitted.isEmpty() ? 0 : SEPARATOR.length();
            if (block.content().length() + separator <= remaining) {
                admitted.add(block);
                remaining -= block.content().length() + separator;
                continue;
            }
            int room = remaining - separator;
            if (block.truncatable() && room >= MIN_TRUNCATED_BLOCK_CHARS) {
                String truncatedContent = truncate(block.content(), room);
                admitted.add(new Block(block.label(), block.priority(), true, truncatedContent));
                truncated.add(block.label());
                truncatedBlocks.add(block);
                // 按实际占用扣减（truncate 保证返回长度 ≤ room，余量恒非负）：
                // 截断不清零余量，其后同档/低档块仍按规则继续装填。原实现此处直接 remaining=0，
                // 排在截断块之后的所有块不分优先级整批出局——旧系统实测（96-100、111-115 两批）
                // 把情节模式黑名单/审校反馈两个小纠正通道整批误杀
                remaining -= separator + truncatedContent.length();
                continue;
            }
            skipped.add(block);
        }
        if (admitted.isEmpty()) {
            // 极端配置（上限小于最小可读块）：强制保留最高优先块，输入段不得为空
            Block top = byPriority.get(0);
            admitted.add(new Block(top.label(), top.priority(), top.truncatable(),
                    truncate(top.content(), budget)));
            truncated.add(top.label());
            truncatedBlocks.add(top);
            skipped.removeIf(block -> block.label().equals(top.label()));
        }

        Map<String, Block> admittedByLabel = indexByLabel(admitted);
        String result = render(present, admittedByLabel);
        int usedPercent = (int) Math.round(result.length() * 100.0 / budget);
        // 三个数必须自洽：合计 = 块内容字 + 分隔字，且后面的逐块清单之和恰等于"块内容字"——
        // 否则读日志的人（或解析脚本）拿分块数字去对账会永远差 2×(块数-1)，无法判断是不是出了别的问题
        log.info("前缀预算[{}] 第{}章 合计{}字（块{}字+分隔{}字）/上限{}字({}%) = [{}]{}",
                scene, scopeNo, result.length(), contentChars(admittedByLabel),
                separatorChars(result, admittedByLabel), budget, usedPercent,
                describe(present, admittedByLabel), notice(truncated, skipped));
        alertIfBudgetBites(scene, scopeNo, truncatedBlocks, skipped, usedPercent);
        return result;
    }

    /**
     * 入参过滤：非空内容才算"有此块"；label 在场景内重复即 fail-fast——
     * 重复 label 会让同一块被渲染两次、预算扣减与实际输出脱节（不变式被静默击穿）
     */
    private List<Block> presentBlocks(String scene, List<Block> blocks) {
        List<Block> present = new ArrayList<>();
        if (blocks == null) {
            return present;
        }
        Set<String> labels = new LinkedHashSet<>();
        for (Block block : blocks) {
            if (block == null) {
                continue;
            }
            if (!labels.add(block.label())) {
                throw new IllegalArgumentException("前缀装配块 label 重复：" + block.label()
                        + "（场景 " + scene + "）——重复 label 会使同一块被渲染两次并击穿预算不变式，"
                        + "请保证场景内 label 唯一");
            }
            if (StringUtils.isNotBlank(block.content())) {
                present.add(block);
            }
        }
        return present;
    }

    private void alertIfBudgetBites(String scene, int scopeNo, List<Block> truncated, List<Block> skipped,
                                    int usedPercent) {
        List<Block> sacrificed = new ArrayList<>(truncated);
        sacrificed.addAll(skipped);
        List<String> hardDropped = skipped.stream()
                .filter(block -> !block.truncatable())
                .map(Block::label)
                .distinct()
                .toList();
        List<String> coreSacrificed = sacrificed.stream()
                .filter(block -> block.priority() <= CORE_PRIORITY)
                .map(Block::label)
                .distinct()
                .toList();
        if (!hardDropped.isEmpty() || !coreSacrificed.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            if (!hardDropped.isEmpty()) {
                detail.append("不可截断块被整块丢弃").append(hardDropped).append("（该约束本次未进入输入，依赖下游兜底）");
            }
            if (!coreSacrificed.isEmpty()) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("核心块(≤").append(CORE_PRIORITY).append(")被裁剪").append(coreSacrificed);
            }
            log.warn("前缀预算[{}] 第{}章: 预算已触及核心约束——{}。设计信号：回查块的膨胀来源，而非直接调大上限",
                    scene, scopeNo, detail);
            return;
        }
        List<String> truncatedLow = truncated.stream().map(Block::label).distinct().toList();
        List<String> droppedLow = skipped.stream().map(Block::label).distinct().toList();
        if (!truncatedLow.isEmpty() || !droppedLow.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            if (!truncatedLow.isEmpty()) {
                detail.append("截断").append(truncatedLow);
            }
            if (!droppedLow.isEmpty()) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("丢弃").append(droppedLow);
            }
            log.warn("前缀预算[{}] 第{}章: 占比{}%，已按优先级裁剪低价值块——{}。"
                            + "若这些块其实在起作用，应上调其 priority，而不是调大上限",
                    scene, scopeNo, usedPercent, detail);
            return;
        }
        if (usedPercent >= NEAR_LIMIT_PERCENT) {
            log.warn("前缀预算[{}] 第{}章: 占比已达{}%（未发生任何裁剪，仅临近上限）——"
                            + "上限的余量假设需按各块字数重新定标",
                    scene, scopeNo, usedPercent);
        }
    }

    /** 按渲染顺序（present 顺序）拼接已入选块，保证"超预算只影响谁被丢，不影响谁在前面" */
    private String render(List<Block> present, Map<String, Block> admittedByLabel) {
        StringBuilder sb = new StringBuilder();
        for (Block block : present) {
            Block hit = admittedByLabel.get(block.label());
            if (hit == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(hit.content());
        }
        return sb.toString();
    }

    private Map<String, Block> indexByLabel(List<Block> blocks) {
        Map<String, Block> byLabel = new LinkedHashMap<>();
        for (Block block : blocks) {
            byLabel.put(block.label(), block);
        }
        return byLabel;
    }

    /**
     * 按剩余预算截断：优先段落边界，其次行边界，兜底硬截；裁不动就截到只留标记。
     * 返回长度保证 &le; budget（截断标记也计入预算）
     */
    private String truncate(String content, int budget) {
        if (content.length() <= budget) {
            return content;
        }
        if (budget <= TRUNCATION_MARKER.length()) {
            return content.substring(0, Math.max(0, budget));
        }
        int limit = budget - TRUNCATION_MARKER.length();
        int cut = content.lastIndexOf("\n\n", limit);
        if (cut < limit / 2) {
            cut = content.lastIndexOf('\n', limit);
        }
        if (cut < limit / 2) {
            cut = limit;
        }
        return content.substring(0, Math.max(0, cut)).trim() + TRUNCATION_MARKER;
    }

    /** 各入选块字数（按渲染顺序），供日志定位膨胀来源 */
    private String describe(List<Block> present, Map<String, Block> admittedByLabel) {
        StringBuilder sb = new StringBuilder();
        for (Block block : present) {
            Block hit = admittedByLabel.get(block.label());
            if (hit == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("｜");
            }
            sb.append(block.label()).append(hit.content().length());
        }
        return sb.toString();
    }

    /** 入选块的内容字数之和：与 {@link #describe} 的逐块清单同口径（两者相加必须相等） */
    private int contentChars(Map<String, Block> admittedByLabel) {
        int sum = 0;
        for (Block block : admittedByLabel.values()) {
            sum += block.content().length();
        }
        return sum;
    }

    /** 分隔符占用 = 渲染总长 − 块内容字之和（块数−1 个分隔符，恒为非负） */
    private int separatorChars(String rendered, Map<String, Block> admittedByLabel) {
        return rendered.length() - contentChars(admittedByLabel);
    }

    private String notice(List<String> truncated, List<Block> skipped) {
        StringBuilder sb = new StringBuilder();
        if (!truncated.isEmpty()) {
            sb.append("；截断[").append(String.join("、", truncated)).append("]");
        }
        if (!skipped.isEmpty()) {
            sb.append("；丢弃[").append(String.join("、",
                    skipped.stream().map(Block::label).toList())).append("]");
        }
        return sb.toString();
    }

}