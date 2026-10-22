package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import java.util.Set;
import java.util.HashSet;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.service.armory.plan.StoryPacing;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 滚动大纲服务：阶段蓝图的触发判断/窗口计算/prompt 组装/解析规整，纯函数无状态——
 * LLM 调用与落盘由蓝图节点与仓储承担。
 * 治长线漂移：近程记忆只有 5 章摘要，阶段方向原先只存在于静态大纲；
 * 每跨过阶段边界生成一版阶段蓝图链式锚定，生成输入固定四件套：
 * 原始大纲（北极星）+ 上一版蓝图（强制结转审计）+ 近章摘要 + 待回收伏笔。
 * 窗口规则：起步首版用默认窗长（1-10 章）写死；老故事中途接入的首版即自适应；
 * 后续蓝图起点 = 上一版终点 + 1，终点由模型按弧线自定，机械钳制在 30-80 章内
 */
@Service
@Slf4j
public class RollingOutlineService {

    /** 首阶段默认窗长（章）：唯一写死处，仅故事起步的首版蓝图使用 */
    public static final int STAGE_LENGTH = 10;
    /** 后续阶段窗长下限（章，含）：30 */
    public static final int MIN_STAGE_LENGTH = 30;
    /** 后续阶段窗长上限（章，含）：80 */
    public static final int MAX_STAGE_LENGTH = 80;
    /** 里程碑任务条数上限 */
    public static final int MAX_TASKS = 6;

    /**
     * **章级主线推进的排期窗**（章，2026-10-02）。
     *
     * <p>阶段长度被 {@link #MIN_STAGE_LENGTH}/{@link #MAX_STAGE_LENGTH} 钳制在 30-80 章
     * （批次不足时才截断到批次末），一次产出 80 条章级推进不现实——输出过长易被
     * {@code finish_reason=length} 静默截断。故只要求覆盖阶段开头的有界窗口，
     * 批次跑完若有余量，由下一版蓝图续写（与滚动大纲的既有哲学一致）。
     *
     * <p>取 15 = 3 个标准段（段长 5 章），够覆盖一次续写的批次量。
     */
    public static final int MAINLINE_WINDOW = 15;
    /** 阶段进入护栏条数上限 */
    public static final int MAX_ENTRY_CONSTRAINTS = 6;
    /** 阶段退出条件条数上限 */
    public static final int MAX_EXIT_CONDITIONS = 8;
    /** 结转任务条数上限 */
    public static final int MAX_CARRIED_TASKS = 8;
    /** 收官节点上限，避免模型不断追加新终局 */
    public static final int MAX_FINALE_BEATS = 12;
    /** 完结预算前置提前量（章）：距硬性完结上限 ≤ 该值时蓝图进入收官收敛（不再开新线/逐步清空终局节点） */
    public static final int CONVERGENCE_LEAD = 60;
    /** 卷窗长下限（章，含）：一次剧情移动（承转合）的最小体量 */
    public static final int MIN_VOLUME_LENGTH = 300;
    /** 卷窗长上限（章，含）：单卷最大体量（超限由末卷收敛排布消化） */
    public static final int MAX_VOLUME_LENGTH = 800;
    /** 卷内"承转合"节拍条数上限 */
    public static final int MAX_VOLUME_BEATS = 4;
    /** 卷内弧清单条数上限 */
    public static final int MAX_VOLUME_ARC_PLAN = 8;
    /** 卷结束条件条数上限 */
    public static final int MAX_VOLUME_EXIT_CONDITIONS = 8;
    /** 卷级伏笔种子条数上限 */
    public static final int MAX_VOLUME_SEEDS = 8;
    /** 蓝图生成回看的近章摘要数 */
    public static final int BLUEPRINT_SUMMARY_COUNT = 10;

    private static final JsonMapper BLUEPRINT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * 悬念档位表的**聚焦补采结果**（只含补采的两个字段）。
     */
    public record SuspenseLadderPatch(String coreSuspense, List<String> suspenseLadder) {
    }

    /**
     * 补采 prompt 的时序锚渲染（2026-10-04，三处聚焦补采共用）：与主蓝图（{@code buildGenerationPrompt}）
     * 同一口径——先摘要锚（当前年龄），新书首段无摘要时退设定兜底锚；两者皆无则返回空串不注入（不编造）。
     * 档位表/章级推进/排期 intent 都会下沉为计划与正文，源头不带锚，下游闸门就会与产出互相打架。
     */
    private String renderAnchorForPrompt(StoryContextEntity storyContext, List<ChapterSummaryEntity> summaries) {
        String anchor = ConsistencyIndexService.renderTimeAnchor(summaries);
        if (StringUtils.isBlank(anchor) && storyContext != null) {
            anchor = ConsistencyIndexService.renderSettingsAgeAnchorIfNoSummaries(summaries,
                    storyContext.getWorldSetting(), storyContext.getProtagonist(), storyContext.getOutline());
        }
        return StringUtils.isBlank(anchor) ? "" : "\n" + anchor + "\n";
    }

    /**
     * 组装**悬念档位补采 prompt**（2026-09-22）。
     *
     * <p><b>为什么需要补采</b>：档位表是"主线推进闸门"的唯一标尺，但在完整蓝图 prompt 里
     * 它只是第 11 条要求——实测模型会在长 prompt 里**静默省略**这两个字段
     * （同批用短聚焦 prompt 探针则 100% 给出：5 档、全是可观察表述）。
     * 若就此放过，档位表恒为 null，锚块不注入、校验跳过、指标不出——整条推进链路**一声不响地空转**。
     * 所以缺了就**再问一次，只问这两个字段**（短 prompt 服从率高），而不是把可靠性押在一次服从上。
     *
     * <p>输入用"故事大纲 + 本阶段目标/任务"即可：档位表描述的是主线悬念的推进阶段，
     * 不需要完整上下文；prompt 越短，模型越不会漏字段。
     *
     * <p><b>时序锚（2026-10-04）</b>：档位/里程碑同样会产出"婴儿写数论"式超龄表述，
     * 补采短 prompt 也不例外——与主蓝图同口径注入锚，新书首段用设定兜底。
     */
    public String buildSuspenseLadderRepairPrompt(StoryContextEntity storyContext,
                                                  StageBlueprintEntity blueprint,
                                                  List<ChapterSummaryEntity> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名资深小说主编。上一步为长篇连载制定阶段蓝图时，遗漏了「主线推进标尺」两项字段，")
                .append("现在**只补这两项**，不要重复输出其他字段。\n\n");
        sb.append("【故事大纲】\n").append(nullToBlank(storyContext == null ? null : storyContext.getOutline())).append("\n");
        sb.append("【主人公】\n").append(nullToBlank(storyContext == null ? null : storyContext.getProtagonist())).append("\n");
        sb.append(renderAnchorForPrompt(storyContext, summaries));
        sb.append("【本阶段目标】\n").append(nullToBlank(blueprint == null ? null : blueprint.getStageGoal())).append("\n");
        if (blueprint != null && blueprint.getTasks() != null && !blueprint.getTasks().isEmpty()) {
            sb.append("【本阶段里程碑】\n");
            for (String task : blueprint.getTasks()) {
                sb.append("- ").append(nullToBlank(task)).append("\n");
            }
        }
        sb.append("\n【要求】\n")
                .append("1. coreSuspense：一句话写明**本书的核心悬念**——全书围绕「什么未知」展开，跨阶段稳定；\n")
                .append("2. suspenseLadder：该悬念的**推进档位表**，3-6 档、有序，从「完全不知」排到「彻底摊牌」；\n")
                .append("3. 每档必须写成**可观察**的表述（谁知道了什么 / 谁做了什么决定 / 什么被公开），")
                .append("**严禁**写成内心感受、情绪波动或氛围渲染；\n")
                .append("4. 档位会被逐章机械比对：索引不得倒退，非过渡章不得连续 3 章停在同一档；\n")
                .append("5. 不要输出 Markdown，不要输出解释文字。\n");
        sb.append("\n请严格按照以下 JSON 格式输出（**只输出这两个字段**）：\n")
                .append("{\"coreSuspense\":\"本书核心悬念一句话\",")
                .append("\"suspenseLadder\":[\"档位1（完全不知）\",\"档位2（可观察的中间态）\",\"档位3（彻底摊牌）\"]}");
        return sb.toString();
    }

    /**
     * 解析补采输出（只认这两个字段）；不可用返回 null——调用方据此告警，不让缺失静默通过。
     */
    public SuspenseLadderPatch parseSuspenseLadderPatch(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            SuspenseLadderPatch patch = JsonParseFallback.parse(raw, text -> {
                try {
                    return BLUEPRINT_MAPPER.readValue(text, SuspenseLadderPatch.class);
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            });
            if (patch == null || patch.suspenseLadder() == null || patch.suspenseLadder().size() < 2) {
                return null;
            }
            return patch;
        } catch (Exception e) {
            log.warn("悬念档位补采输出解析失败：{}", e.getMessage());
            return null;
        }
    }

    // ==================== 章级主线推进 · 聚焦补采 ====================

    /**
     * 章级主线推进的**聚焦补采结果**（只含这一个字段）。
     */
    public record MainLinePatch(List<StageBlueprintEntity.MainLineBeat> mainLineByChapter) {
    }

    /** 排期窗终点：{@code min(endChapter, startChapter + MAINLINE_WINDOW - 1)} */
    public static int mainLineWindowEnd(StageBlueprintEntity blueprint) {
        if (blueprint == null || blueprint.getStartChapter() == null) {
            return 0;
        }
        int start = blueprint.getStartChapter();
        int end = blueprint.getEndChapter() == null ? start : blueprint.getEndChapter();
        return Math.min(end, start + MAINLINE_WINDOW - 1);
    }

    /**
     * 组装**章级主线推进补采 prompt**（2026-10-02）。
     *
     * <p><b>为什么又是"聚焦补采"</b>：与 {@code suspenseLadder} 同样的理由——
     * 那条字段当年放在完整蓝图 prompt 里（十几个要求 + 长 schema）会被模型**静默省略**，
     * 最后靠"只问这两个字段"的短 prompt 才稳定。**可靠性来自"缺了就补问"，不来自"要求写得够醒目"。**
     * 章级推进更是个逐章数组，放在长 prompt 里被省略的概率只会更高。
     *
     * <p>输入带**已确定的档位表**：章级推进必须与档位相容（不得出现档位倒退），
     * 否则两张表会互相打架——校验侧却是各自独立跑的，冲突要到很晚才暴露。
     */
    public String buildMainLineRepairPrompt(StoryContextEntity storyContext, StageBlueprintEntity blueprint,
                                            List<ChapterSummaryEntity> summaries) {
        int start = blueprint == null || blueprint.getStartChapter() == null ? 1 : blueprint.getStartChapter();
        int end = mainLineWindowEnd(blueprint);
        int count = Math.max(1, end - start + 1);

        StringBuilder sb = new StringBuilder();
        sb.append("你是一名资深小说主编。上一步为长篇连载制定阶段蓝图时，遗漏了「逐章主线推进」字段，")
                .append("现在**只补这一项**，不要重复输出其他字段。\n\n");
        sb.append("【故事大纲】\n").append(nullToBlank(storyContext == null ? null : storyContext.getOutline())).append("\n");
        sb.append("【主人公】\n").append(nullToBlank(storyContext == null ? null : storyContext.getProtagonist())).append("\n");
        sb.append(renderAnchorForPrompt(storyContext, summaries));
        sb.append("【本阶段目标】\n").append(nullToBlank(blueprint == null ? null : blueprint.getStageGoal())).append("\n");
        if (blueprint != null && blueprint.getTasks() != null && !blueprint.getTasks().isEmpty()) {
            sb.append("【本阶段里程碑】\n");
            for (String task : blueprint.getTasks()) {
                sb.append("- ").append(nullToBlank(task)).append("\n");
            }
        }
        if (blueprint != null && blueprint.getSuspenseLadder() != null && !blueprint.getSuspenseLadder().isEmpty()) {
            sb.append("【核心悬念】\n").append(nullToBlank(blueprint.getCoreSuspense())).append("\n");
            sb.append("【悬念推进档位表】（已确定，章级推进必须与之相容，档位索引不得倒退）\n");
            List<String> ladder = blueprint.getSuspenseLadder();
            for (int i = 0; i < ladder.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(nullToBlank(ladder.get(i))).append("\n");
            }
        }
        sb.append("\n【要求】\n")
                .append("1. 为第 ").append(start).append("–").append(end).append(" 章的**每一章**写一条「本章主线推进」，")
                .append("共 ").append(count).append(" 条，章号从 ").append(start).append(" 连续排到 ")
                .append(end).append("，不得缺章或跳号；\n")
                .append("2. 每条写**本章在主线上的推进**：谁知道了什么 / 谁做了什么决定 / 什么被公开，")
                .append("**严禁**写成内心感受、情绪波动或氛围渲染；\n")
                .append("3. 相邻两章**不得雷同**——读者要能一眼看出这两章各自推进了什么；")
                .append("更不得用「继续观察」「进一步推进」这类空话占位；\n")
                .append("3.1 ⚠️ **每条必须回答『比上一章多知道了什么 / 改变了什么决定』**——这是主线推进的实质：\n")
                .append("  若上一章已说过「角色丁决定守密」，本章不能写成「角色丁守住秘密」——那是同一件事换场景；")
                .append("  要写的是「角色丁把守密从答应升级为**主动对外遮挡**」或「角色甲把判断从怀疑升级为**摊牌**」；")
                .append("  若两章的推进语可互换位置（把本章和上一章的顺序对调也读得通），说明推进写空了，必须重写；\n")
                .append("  （实测反例：连续 4 章的推进都是「核心圈初步形成保护性默契」+ 各换场景——内容变了、档位语义没变，")
                .append("   阅读观感就是整段停滞。档位语义级推进 = 新事实被知晓 / 新决定被做出，不是「又发生了一件事」。）\n")
                .append("3.2 ⚠️ **每条推进必须写明由谁对谁的什么交锋承载**（盘问/拒绝/对峙/合作/撞见/被迫表态）——")
                .append("**禁止**写成纯单人独处动作链（整理/比对/记录/发呆）或纯流程仪式（清点/封装/锁存/交接）：\n")
                .append("  主线事实本身可以是单人认知推进，但**落点必须在人际接触中暴露**——")
                .append("「主角独自推断出 X」的合法写法是「被同伴撞见推理痕迹，两人就『你到底在查什么』对峙，他在拒答中让 X 暴露」；")
                .append("（实测反例：封存仪式章「清点→倒石灰→焊死→锁门→交钥匙」有两个人在场却无一句交锋，正文只写出 1055 字；")
                .append("拆信推断章整章独处，全章有效对白 5 句。）\n")
                .append("3.3 ⚠️ **每条推进必须以剧情线标签开头**，六选一：")
                .append("【学习】（数学/竞赛/学业能力成长）/【本金】（某资产/家庭财务/资本积累与变现）")
                .append("/【程序】（编程/技术工程/工具制作）/【悬疑】（暗线/旧案/异常事件）")
                .append("/【关系】（同伴/家庭/情感）/【家庭】（父母线/亲情决策）；\n")
                .append("  ⚠️ **主粮配额：学习+本金+程序 合计每段 ≥2 章**——本书的底色是硬核成长线，主粮不许缺席；\n")
                .append("  主粮章的推进必须取自故事圣经章节目标时间表上该学段的**里程碑链条**")
                .append("（如初一自学计算机与算法、初二重返省级联赛并拿到名次、竞赛奖金完成家庭财务正循环），")
                .append("不得用「整理笔记/查规则/归档备案」类行政动作充数——那是事务不是成长；\n")
                .append("  且**悬疑暗线每段至多半数、不得连续两章**——暗线信息可以改由主粮/关系场景承载")
                .append("（如竞赛讨论中带出线索、同伴对话中暴露变化），")
                .append("严禁整段连排暗线把主角自身的成长挤出舞台（实测 106-110 段暗线连排，学习线整段缺席；")
                .append("106-120 十五章主粮仅 3 章、本金 0/程序 0——这是本纪律要拦截的头号问题）。\n")
                .append("4. 整体必须与上方档位表相容：档位索引不得倒退，且每一章的推进应能对应到某个档位；\n")
                .append("5. 不要输出 Markdown，不要输出解释文字。\n");
        sb.append("\n请严格按照以下 JSON 格式输出（**只输出这一个字段**）：\n")
                .append("{\"mainLineByChapter\":[{\"chapterNo\":").append(start)
                .append(",\"advance\":\"【学习】本章主线推进（可观察表述，写明谁对谁的什么交锋承载）\"},")
                .append("{\"chapterNo\":").append(Math.min(start + 1, end)).append(",\"advance\":\"【本金】…\"}]}");
        return sb.toString();
    }

    // ==================== 章级推进 · 线别纪律（2026-10-11） ====================

    /**
     * 章级推进允许的剧情线标签：主粮线（学习/本金/程序）受**下限**配额约束，
     * 悬疑为暗线（受**上限**约束），关系/家庭为成长面（无配额，填满剩余空间）。
     * 2026-10-11 由四标签扩为六：本金/程序原先在词汇表里不存在——财务章只能标【家庭】
     * （111 章某资产核算）、技术章无标可打，硬核线在标签层面就不可见（实测 106-120 主粮仅 3/15）。
     */
    public static final Set<String> MAIN_LINE_TAGS = Set.of("学习", "悬疑", "关系", "家庭", "本金", "程序");

    /** 主粮线标签：本书的底色是硬核成长（学业/资本/技术），配额下限见 {@link #mainLineDisciplineIssues} */
    public static final Set<String> HARD_LINE_TAGS = Set.of("学习", "本金", "程序");

    /** 主粮配额下限分子/分母：每段主粮章 ≥ 总章数×2/5（5 章段即 ≥2 章），与悬疑上限（≤半数）对称 */
    public static final int HARD_LINE_MIN_NUMERATOR = 2;
    public static final int HARD_LINE_DENOMINATOR = 5;

    /** 线别分布观测行的固定显示顺序（主粮在前）——Set 无序，观测行需要稳定可 diff */
    private static final String[] TAG_DISPLAY_ORDER = {"学习", "本金", "程序", "悬疑", "关系", "家庭"};

    /**
     * 章级推进的**线别纪律**机械校验：每条推进必须以剧情线标签开头；悬疑暗线
     * 每段不得超过半数、不得连续两章；主粮线（学习/本金/程序）每段不得低于 2/5。
     * 治两个方向——"整段滑进暗线、主角学习线蒸发"（上限）与"压掉暗线后空间被关系/家庭
     * 吃掉、硬核主粮仍缺席"（下限，实测 106-120 主粮 3/15、本金 0、程序 0）。
     *
     * <p>返回违规清单（空 = 通过）。执法通道与聚焦补采同款：调用方（BuildStageBlueprintNode）
     * 据此**带反馈重问一次**，仍不过则 WARN 留痕——蓝图层没有重试循环，
     * 下游还有段计划的对话承载闸门作第二道兜底。
     */
    public static List<String> mainLineDisciplineIssues(StageBlueprintEntity blueprint) {
        List<String> issues = new ArrayList<>();
        if (blueprint == null || blueprint.getMainLineByChapter() == null) {
            return issues;
        }
        List<StageBlueprintEntity.MainLineBeat> beats = blueprint.getMainLineByChapter().stream()
                .filter(b -> b != null && b.getChapterNo() != null && StringUtils.isNotBlank(b.getAdvance()))
                .toList();
        if (beats.isEmpty()) {
            return issues;
        }
        int suspense = 0;
        int hardLine = 0;
        Integer prevNo = null;
        boolean prevSuspense = false;
        for (StageBlueprintEntity.MainLineBeat beat : beats) {
            String advance = beat.getAdvance().trim();
            String tag = null;
            if (advance.startsWith("【")) {
                int close = advance.indexOf('】');
                if (close > 1 && MAIN_LINE_TAGS.contains(advance.substring(1, close))) {
                    tag = advance.substring(1, close);
                }
            }
            if (tag == null) {
                issues.add("第 " + beat.getChapterNo() + " 章推进未标剧情线标签"
                        + "（须以【学习】/【本金】/【程序】/【悬疑】/【关系】/【家庭】之一开头）："
                        + brief(advance));
                prevSuspense = false;
                prevNo = beat.getChapterNo();
                continue;
            }
            if (HARD_LINE_TAGS.contains(tag)) {
                hardLine++;
            }
            boolean isSuspense = "悬疑".equals(tag);
            if (isSuspense) {
                suspense++;
                if (prevSuspense && prevNo != null && beat.getChapterNo() == prevNo + 1) {
                    issues.add("第 " + prevNo + "-" + beat.getChapterNo() + " 章悬疑暗线连续两章"
                            + "——暗线信息须改由学习/关系场景承载");
                }
            }
            prevSuspense = isSuspense;
            prevNo = beat.getChapterNo();
        }
        if (suspense * 2 > beats.size()) {
            issues.add("悬疑暗线里程碑 " + suspense + "/" + beats.size() + " 超过半数"
                    + "——把其中一部分改为【学习】/【关系】承载同一信息");
        }
        // 主粮下限：只有悬疑上限时，压出来的空间会被关系/家庭吃掉而非回到主粮——
        // 实测 106-120 十五章主粮 3/15（学习）且本金 0、程序 0，其中 1 章还是行政事务。
        // 上限治"暗线连排"，下限治"主粮蒸发"，两者必须成对存在
        if (hardLine * HARD_LINE_DENOMINATOR < beats.size() * HARD_LINE_MIN_NUMERATOR) {
            issues.add("主粮线（学习/本金/程序）仅 " + hardLine + "/" + beats.size() + " 章，"
                    + "低于配额下限 2/5——本书的底色是硬核成长线，每段至少 2 章必须由主粮承载"
                    + "（推进取自章节目标时间表的里程碑链条，行政动作不算数）");
        }
        return issues;
    }

    /**
     * 线别分布观测行（固定顺序：主粮在前、未标兜底）——每段蓝图一眼可查，
     * 不必再人工数 rolling-outline.json。观测不到的失衡就不会被修。
     */
    public static String mainLineDistribution(StageBlueprintEntity blueprint) {
        if (blueprint == null || blueprint.getMainLineByChapter() == null) {
            return "（无章级推进）";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String tag : TAG_DISPLAY_ORDER) {
            counts.put(tag, 0);
        }
        int untagged = 0;
        for (StageBlueprintEntity.MainLineBeat beat : blueprint.getMainLineByChapter()) {
            if (beat == null || StringUtils.isBlank(beat.getAdvance())) {
                continue;
            }
            String advance = beat.getAdvance().trim();
            String tag = null;
            if (advance.startsWith("【")) {
                int close = advance.indexOf('】');
                if (close > 1 && MAIN_LINE_TAGS.contains(advance.substring(1, close))) {
                    tag = advance.substring(1, close);
                }
            }
            if (tag == null) {
                untagged++;
            } else {
                counts.merge(tag, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            sb.append(sb.length() > 0 ? " " : "").append(entry.getKey()).append(entry.getValue());
        }
        if (untagged > 0) {
            sb.append(" 未标").append(untagged);
        }
        return sb.toString();
    }

    /** 线别纪律驳回的回注话术：与聚焦补采同款差异化修正指令 */
    public static String mainLineDisciplineFeedback(List<String> issues) {
        return "\n\n【上一版章级主线推进被机械驳回】以下问题必须修正（其余部分可保留）：\n- "
                + String.join("；\n- ", issues)
                + "\n\n修正要求（逐条执行，**不得只换措辞**）："
                + "\n1. 每条推进以【学习】/【本金】/【程序】/【悬疑】/【关系】/【家庭】之一**开头**；"
                + "\n2. 主粮线（学习/本金/程序）每段至少 2 章——不足时把对应章的推进改为由主粮承载，"
                + "内容取自章节目标时间表的里程碑链条（竞赛/学业/某资产/技术），行政动作不算数；"
                + "\n3. 悬疑暗线至多半数、不得连续两章——暗线信息不删，改由主粮/关系场景承载"
                + "（如同伴讨论中带出线索、对峙中暴露变化）；"
                + "\n4. 每条推进写明**由谁对谁的什么交锋承载**，禁止纯独处动作链与纯流程仪式；"
                + "\n5. 重新输出**完整** mainLineByChapter（章数与章号不变）。";
    }

    /** 违规信息里截断以便阅读 */
    private static String brief(String value) {
        return value == null ? "" : (value.length() <= 30 ? value : value.substring(0, 30) + "…");
    }

    // ==================== 伏笔兑现排期表 · 聚焦补采P2b） ====================

    /**
     * 伏笔排期补采结果（只含这一个字段）。
     *
     * <p>⚠️ 分量名必须**逐字等于 prompt 里给的 JSON key**（{@code foreshadowSchedule}）——
     * 叫 {@code schedule} 会让 Jackson 找不到属性、整个 patch 反序列化为 null，
     * 而调用方只会看到"补采没拿到结果"，根因极难追。（与 {@code MainLinePatch.mainLineByChapter} 同款约定。）
     */
    public record ForeshadowSchedulePatch(
            List<ForeshadowScheduleEntity.ScheduleItem> foreshadowSchedule) {
    }

    /**
     * 组装**伏笔兑现排期表补采 prompt**（P2b）。
     *
     * <p>同为**聚焦补采**：排期表是个逐条对象数组，放在长 prompt 里被静默省略的概率只会比档位表更高。
     *
     * <p><b>核心要求是"至少 1 条跨阶段"</b>——这是治「段计划视野 5 章 ⇒ 只能本段内埋本段内收」
     * 的唯一结构性手段。不写死这条，排期表会退化成"把本来就会收的线登记一遍"。
     *
     * <p><b>时序锚（2026-10-04）</b>：新书 1-5 章实测，无锚的排期补采会产出
     * "4岁主角无意识写出2026年日期"这类违反时序锚的 intent——它经【必须埋设】强压给计划与写手后，
     * 审校按锚判 BLOCKING，修订无法在不丢事件的前提下修复，整链死锁。锚在此处是源头闸门。
     */
    public String buildForeshadowScheduleRepairPrompt(StoryContextEntity storyContext,
                                                     StageBlueprintEntity blueprint,
                                                     int hardTotal,
                                                     List<ForeshadowScheduleEntity.ScheduleItem> carried,
                                                     List<ChapterSummaryEntity> summaries) {
        int start = blueprint == null || blueprint.getStartChapter() == null ? 1 : blueprint.getStartChapter();
        int end = blueprint == null || blueprint.getEndChapter() == null ? start : blueprint.getEndChapter();

        StringBuilder sb = new StringBuilder();
        sb.append("你是一名资深小说主编。上一步为长篇连载制定阶段蓝图时，遗漏了「伏笔兑现排期表」，")
                .append("现在**只补这一项**，不要重复输出其他字段。\n\n");
        sb.append("【故事大纲】\n").append(nullToBlank(storyContext == null ? null : storyContext.getOutline())).append("\n");
        sb.append("【本阶段目标】\n").append(nullToBlank(blueprint == null ? null : blueprint.getStageGoal())).append("\n");
        sb.append(renderAnchorForPrompt(storyContext, summaries));
        if (blueprint != null && blueprint.getTasks() != null && !blueprint.getTasks().isEmpty()) {
            sb.append("【本阶段里程碑】\n");
            for (String task : blueprint.getTasks()) {
                sb.append("- ").append(nullToBlank(task)).append("\n");
            }
        }
        if (carried != null && !carried.isEmpty()) {
            sb.append("【上一版已排期、仍在养的长线】（**不要重复排**，它们会自动结转）\n");
            for (ForeshadowScheduleEntity.ScheduleItem item : carried) {
                sb.append("- ").append(nullToBlank(item.getIntent()))
                        .append("（第").append(item.getPlantChapter()).append("章埋 → 第")
                        .append(item.getPayoffChapter()).append("章收，状态 ").append(item.getStatus()).append("）\n");
            }
        }
        sb.append("\n【要求】\n")
                .append("1. 列出本阶段新安排的伏笔，每条给出 intent（要达成什么，一两句话说清）/ ")
                .append("plantChapter（计划埋设章）/ payoffChapter（计划回收章）；\n")
                .append("2. plantChapter 必须落在本阶段区间（第 ").append(start).append("-").append(end).append(" 章）内；\n")
                .append("3. **payoffChapter 必须大于 plantChapter**；\n");
        // ⚠️ "至少 1 条跨阶段"只在**还有后续章节**时才提得出。
        // 若全书就在本阶段收束（hardTotal <= end），这条要求**永远无法满足**——
        // 照写会让模型被反复要求做不可能的事、且调用方空转重问。故条件化。
        if (hardTotal > end) {
            sb.append("4. **至少 1 条的 payoffChapter 要大于 ").append(end)
                    .append("**——即这条线要跨到后续阶段才收。这是硬要求：全部在本阶段内收，")
                    .append("等于又回到「埋下去立刻兑现」，读者来不及惦记；\n");
        } else {
            sb.append("4. 全书在本阶段收束，**所有线都必须在第 ").append(end)
                    .append(" 章前收完**，不得留悬而未决的长线；\n");
        }
        sb.append("5. payoffChapter 不得超过全书硬性完结上限第 ").append(hardTotal).append(" 章；\n")
                .append("6. 短线（跨度 1-3 章）允许存在，但**不得全部都是短线**，总条数 3-8 条；\n")
                .append("7. intent 必须服从上方【时序锚】硬约束：**不得要求主角做出超出其阶段极限的可观察动作**")
                .append("（如幼龄角色书写文字/数字、独立完成精细操作、用载体向他人传递可解码的具体信息）——")
                .append("确需展示早慧时，写成该阶段内的合法表达（倾向、注视、趋避、被协助完成）；")
                .append("**且含「主角能力/早慧展示」的 intent 在本阶段至多 2 条**——其余排期应为人际、事件、物件或关系线，")
                .append("展示类排期逐章一条会让读者第 3 次就预判套路；\n")
                .append("8. 不要输出 Markdown，不要输出解释文字。\n");
        sb.append("\n请严格按照以下 JSON 格式输出（**只输出这一个字段**）：\n")
                .append("{\"foreshadowSchedule\":[{\"intent\":\"这条线要达成什么\",\"plantChapter\":")
                .append(start).append(",\"payoffChapter\":").append(end + 1).append("}]}");
        return sb.toString();
    }

    /**
     * 解析排期表补采输出；不可用返回 null（调用方告警，不让缺失静默通过）。
     *
     * <p>逐条机械校验：章号齐全、{@code plant} 落在阶段区间内、{@code payoff > plant}、
     * {@code payoff <= hardTotal}。任一条不合格即整表退回（半张表比没有更危险：
     * 打标会因章号错位而大面积漏配）。
     *
     * <p>⚠️ **"至少 1 条跨阶段"不在这里判定**——那是"补采质量"问题而非"格式"问题，
     * 由调用方决定是重问一次还是告警放行（见 {@code BuildStageBlueprintNode}）。
     */
    public ForeshadowSchedulePatch parseForeshadowSchedulePatch(String raw, StageBlueprintEntity blueprint,
                                                               int hardTotal) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        int start = blueprint == null || blueprint.getStartChapter() == null ? 1 : blueprint.getStartChapter();
        int end = blueprint == null || blueprint.getEndChapter() == null ? start : blueprint.getEndChapter();
        try {
            ForeshadowSchedulePatch patch = JsonParseFallback.parse(raw, text -> {
                try {
                    return BLUEPRINT_MAPPER.readValue(text, ForeshadowSchedulePatch.class);
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            });
            if (patch == null || patch.foreshadowSchedule() == null || patch.foreshadowSchedule().isEmpty()) {
                return null;
            }
            List<ForeshadowScheduleEntity.ScheduleItem> valid = new ArrayList<>();
            for (ForeshadowScheduleEntity.ScheduleItem item : patch.foreshadowSchedule()) {
                if (item == null || StringUtils.isBlank(item.getIntent())
                        || item.getPlantChapter() == null || item.getPayoffChapter() == null) {
                    log.warn("伏笔排期补采：条目缺字段，整表退回");
                    return null;
                }
                if (item.getPlantChapter() < start || item.getPlantChapter() > end) {
                    log.warn("伏笔排期补采：plantChapter {} 不在阶段区间 {}-{}，整表退回",
                            item.getPlantChapter(), start, end);
                    return null;
                }
                if (item.getPayoffChapter() <= item.getPlantChapter()) {
                    log.warn("伏笔排期补采：payoffChapter {} 未大于 plantChapter {}，整表退回",
                            item.getPayoffChapter(), item.getPlantChapter());
                    return null;
                }
                if (item.getPayoffChapter() > hardTotal) {
                    log.warn("伏笔排期补采：payoffChapter {} 超出全书上限 {}，整表退回",
                            item.getPayoffChapter(), hardTotal);
                    return null;
                }
                item.setSpan(item.getPayoffChapter() - item.getPlantChapter());
                item.setStatus(ForeshadowScheduleEntity.STATUS_PLANNED);
                valid.add(item);
            }
            return new ForeshadowSchedulePatch(valid);
        } catch (Exception e) {
            log.warn("伏笔排期补采输出解析失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 排期表**链式结转 + 去重**（P2b）。
     *
     * <p>新版补采的条目与上一版结转的活线条目会撞车（模型看不见上一版的全貌），
     * 故按 **intent 归一文本去重，保留结转版**——结转条目带着 {@code status} 履历
     * （已 PLANTED 的线不该被打回 PLANNED，否则打标会重来一遍）。
     */
    public List<ForeshadowScheduleEntity.ScheduleItem> mergeScheduleItems(
            List<ForeshadowScheduleEntity.ScheduleItem> carried,
            List<ForeshadowScheduleEntity.ScheduleItem> fresh) {
        List<ForeshadowScheduleEntity.ScheduleItem> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ForeshadowScheduleEntity.ScheduleItem item : union(carried, fresh)) {
            String key = normalizeIntent(item.getIntent());
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            merged.add(item);
        }
        return merged;
    }

    private static List<ForeshadowScheduleEntity.ScheduleItem> union(
            List<ForeshadowScheduleEntity.ScheduleItem> carried,
            List<ForeshadowScheduleEntity.ScheduleItem> fresh) {
        List<ForeshadowScheduleEntity.ScheduleItem> all = new ArrayList<>();
        if (carried != null) {
            all.addAll(carried);
        }
        if (fresh != null) {
            all.addAll(fresh);
        }
        return all;
    }

    private static String normalizeIntent(String intent) {
        return intent == null ? "" : intent.replaceAll("[\\s\u3000“”\"「」『』，。、,.]", "");
    }

    /**
     * 解析章级主线推进补采输出；不可用返回 null——调用方据此告警，不让缺失静默通过。
     *
     * <p>校验口径：条目非空、且章号**连续覆盖排期窗**才认。
     * 部分覆盖（如只给了前 3 章的条目）按不可用处理——半张表比没有更危险：
     * 校验会因"缺章"不断驳回，而模型每次补采都只给前几条。
     */
    public MainLinePatch parseMainLinePatch(String raw, StageBlueprintEntity blueprint) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        int start = blueprint == null || blueprint.getStartChapter() == null ? 1 : blueprint.getStartChapter();
        int end = mainLineWindowEnd(blueprint);
        try {
            MainLinePatch patch = JsonParseFallback.parse(raw, text -> {
                try {
                    return BLUEPRINT_MAPPER.readValue(text, MainLinePatch.class);
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            });
            if (patch == null || patch.mainLineByChapter() == null || patch.mainLineByChapter().isEmpty()) {
                return null;
            }
            List<StageBlueprintEntity.MainLineBeat> beats = patch.mainLineByChapter().stream()
                    .filter(b -> b != null && b.getChapterNo() != null && StringUtils.isNotBlank(b.getAdvance()))
                    .sorted(Comparator.comparingInt(StageBlueprintEntity.MainLineBeat::getChapterNo))
                    .toList();
            if (beats.size() != Math.max(1, end - start + 1)) {
                log.warn("章级主线推进补采条目数不符：期望 {} 条（第 {}-{} 章），实际 {} 条",
                        Math.max(1, end - start + 1), start, end, beats.size());
                return null;
            }
            for (int i = 0; i < beats.size(); i++) {
                if (beats.get(i).getChapterNo() != start + i) {
                    log.warn("章级主线推进补采章号不连续：第 {} 条为第 {} 章，期望第 {} 章",
                            i + 1, beats.get(i).getChapterNo(), start + i);
                    return null;
                }
            }
            return new MainLinePatch(beats);
        } catch (Exception e) {
            log.warn("章级主线推进补采输出解析失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 触发判断：无蓝图，或最新蓝图的覆盖区间未及下一章（跨过阶段边界即生成新版）
     */
    public boolean needsGeneration(List<StageBlueprintEntity> blueprints, int nextChapterNo) {
        StageBlueprintEntity latest = latestOf(blueprints);
        return latest == null || latest.getEndChapter() == null || latest.getEndChapter() < nextChapterNo;
    }

    public StageBlueprintEntity latestOf(List<StageBlueprintEntity> blueprints) {
        if (blueprints == null || blueprints.isEmpty()) {
            return null;
        }
        return blueprints.get(blueprints.size() - 1);
    }

    /**
     * 计算下一版蓝图的窗口：
     * ① 无前版且下一章仍在默认窗长内（故事起步）——fixed，第 1-10 章写死；
     * ② 无前版且已连载超出默认窗长（老故事中途接入）——首版即 adaptive，从下一章起；
     * ③ 有前版——adaptive，起点 = 上一版终点 + 1，终点待模型自定（钳制见 normalize）
     */
    public StageWindow nextWindow(StageBlueprintEntity previous, int nextChapterNo) {
        if (previous == null) {
            if (nextChapterNo <= STAGE_LENGTH) {
                return StageWindow.fixed(1, 1);
            }
            return StageWindow.adaptive(1, nextChapterNo);
        }
        int prevEnd = previous.getEndChapter() != null ? previous.getEndChapter() : nextChapterNo - 1;
        return StageWindow.adaptive(previous.getStageNo() + 1, prevEnd + 1);
    }

    /**
     * 蓝图窗口：fixed 表示窗长由默认值写死；adaptive 表示终点由模型按弧线自定（30-80 章钳制）
     */
    public record StageWindow(int stageNo, int startChapter, boolean adaptive) {

        public static StageWindow fixed(int stageNo, int startChapter) {
            return new StageWindow(stageNo, startChapter, false);
        }

        public static StageWindow adaptive(int stageNo, int startChapter) {
            return new StageWindow(stageNo, startChapter, true);
        }
    }

    /**
     * 批次计划分段：一段 = 一批连续章节 + 归属的阶段蓝图（可为 null，无蓝图模式）
     */
    public record StageSegment(int startChapter, int endChapter, StageBlueprintEntity blueprint) {
    }

    /**
     * 批次分段：把 [batchStart, batchEnd] 按阶段蓝图覆盖区间切成若干段，逐段独立规划。
     * 蓝图链缺口（生成失败留下的尾段）回退最新一版蓝图——与蓝图 fail-soft 语义一致；
     * 无任何蓝图时返回整批单段（blueprint=null，走无蓝图模式）
     */
    public List<StageSegment> segmentBatch(List<StageBlueprintEntity> blueprints, int batchStart, int batchEnd) {
        if (batchEnd < batchStart) {
            return List.of();
        }
        List<StageSegment> segments = new ArrayList<>();
        int cursor = batchStart;
        while (cursor <= batchEnd) {
            StageBlueprintEntity covering = findCovering(blueprints, cursor);
            // 仅当蓝图真实覆盖 cursor 时才按其终点切段；回退蓝图（缺口兜底）不得钳制段终点
            boolean covered = covering != null && covering.getStartChapter() != null
                    && covering.getEndChapter() != null
                    && covering.getStartChapter() <= cursor && covering.getEndChapter() >= cursor;
            int end = covered ? Math.min(batchEnd, covering.getEndChapter()) : batchEnd;
            segments.add(new StageSegment(cursor, end, covering));
            cursor = end + 1;
        }
        return segments;
    }

    /**
     * 找覆盖指定章的蓝图；无覆盖时回退链上最新一版（fail-soft 缺口兜底），全无则返回 null
     */
    private StageBlueprintEntity findCovering(List<StageBlueprintEntity> blueprints, int chapterNo) {
        StageBlueprintEntity fallback = null;
        if (blueprints != null) {
            for (StageBlueprintEntity blueprint : blueprints) {
                if (blueprint == null || blueprint.getStartChapter() == null) {
                    continue;
                }
                fallback = blueprint;
                if (blueprint.getStartChapter() <= chapterNo
                        && blueprint.getEndChapter() != null && blueprint.getEndChapter() >= chapterNo) {
                    return blueprint;
                }
            }
        }
        return fallback;
    }

    /**
     * 阶段终点的有效钳制区间：标准窗长 30-80 章，但终点不得超出本次批次末章——
     * 起点距批次末章不足一个标准窗长（余量 < 30 章）时，区间收缩为 [batchEnd, batchEnd]，
     * 强制本阶段收束到批次末章（钳制目标与蓝图覆盖目标一致，保证生成循环必然终止）
     */
    private int[] effectiveEndBounds(int startChapter, int batchEnd) {
        int bound = batchEnd > 0 ? batchEnd : Integer.MAX_VALUE;
        int maxEnd = Math.min(startChapter + MAX_STAGE_LENGTH - 1, bound);
        int minEnd = Math.min(startChapter + MIN_STAGE_LENGTH - 1, maxEnd);
        return new int[]{minEnd, maxEnd};
    }

    /**
     * 组装蓝图生成 prompt（无硬性完结上限）：委托 hardTotal=null
     */
    public String buildGenerationPrompt(StoryContextEntity storyContext,
                                        StageBlueprintEntity previous,
                                        List<ChapterSummaryEntity> summaries,
                                        List<String> foreshadowLines,
                                        StageWindow window,
                                        int batchEnd) {
        return buildGenerationPrompt(storyContext, previous, summaries, foreshadowLines, window, batchEnd, null);
    }

    /**
     * 组装蓝图生成 prompt：输入四件套 + 反配额约束 + 结转审计要求。
     * batchEnd 为本次批次的末章（chapterOffset + chapterCount），阶段终点不得超出。
     * hardTotal 为全书硬性完结上限（null=未强制）：非空时把上限注入完结契约，并在
     * ① 距上限 ≤ CONVERGENCE_LEAD 时进入收官收敛（不再开新线、逐步清空终局节点）；
     * ② 本阶段窗口直达上限时强制收官卷（finalVolumeDeclared=true + EPILOGUE + 终局节点全部完成）。
     */
    public String buildGenerationPrompt(StoryContextEntity storyContext,
                                        StageBlueprintEntity previous,
                                        List<ChapterSummaryEntity> summaries,
                                        List<String> foreshadowLines,
                                        StageWindow window,
                                        int batchEnd,
                                        Integer hardTotal) {
        // 无当前卷调用：退化两段式（存量故事/卷生成失败），不注入卷方向锚
        return buildGenerationPrompt(storyContext, previous, summaries, foreshadowLines,
                window, batchEnd, hardTotal, null);
    }

    /**
     * 组装弧（阶段蓝图）生成 prompt（八参：携带当前卷）。在【上一版阶段蓝图】前插入【所属卷方向锚】：
     * 卷是全书长期锚，弧在其窗口内行使阶段蓝图职责——把卷主旨/承转合/卷内弧清单注入，锚定本弧的全局位置；
     * prompt 末尾同时给出弧归属的 JSON 字段（volumeNo/volumeTitle/arcNo/arcGoal）供模型回填卷内序号。
     */
    public String buildGenerationPrompt(StoryContextEntity storyContext,
                                        StageBlueprintEntity previous,
                                        List<ChapterSummaryEntity> summaries,
                                        List<String> foreshadowLines,
                                        StageWindow window,
                                        int batchEnd,
                                        Integer hardTotal,
                                        VolumeBlueprintEntity volume) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名资深小说主编，负责为长篇连载制定阶段蓝图（滚动大纲），保证长线剧情不偏离主线。\n\n");

        sb.append("【故事大纲】\n").append(nullToBlank(storyContext == null ? null : storyContext.getOutline())).append("\n");
        sb.append("\n【主人公】\n").append(nullToBlank(storyContext == null ? null : storyContext.getProtagonist())).append("\n");

        // 时序锚：把主角当前年龄摆进蓝图决策视野。蓝图是"里程碑/悬念档位/退出条件"的
        // 源头——若蓝图自己就产出"婴儿完成数论验证"这类超龄里程碑，下游计划层无论怎样都会照抄。
        // 无年龄事实时不渲染（不编造）；新书首段还没有摘要，用设定兜底锚顶上（否则首个蓝图零年龄约束）。
        String timeAnchor = ConsistencyIndexService.renderTimeAnchor(summaries);
        if (StringUtils.isBlank(timeAnchor) && storyContext != null) {
            timeAnchor = ConsistencyIndexService.renderSettingsAgeAnchorIfNoSummaries(summaries,
                    storyContext.getWorldSetting(), storyContext.getProtagonist(), storyContext.getOutline());
        }
        if (StringUtils.isNotBlank(timeAnchor)) {
            sb.append("\n").append(timeAnchor).append("\n");
        }

        if (volume != null) {
            sb.append("\n【所属卷方向锚】（本弧所在的卷——全书长期锚，本弧必须在其框架内推进）\n");
            sb.append("当前所在第 ").append(volume.getVolumeNo()).append(" 卷《")
                    .append(nullToBlank(volume.getTitle())).append("》（第 ")
                    .append(volume.getStartChapter()).append("-")
                    .append(volume.getEndChapter()).append(" 章）。\n");
            sb.append("卷主旨：").append(nullToBlank(volume.getThemeShift())).append("\n");
            if (volume.getBeats() != null && !volume.getBeats().isEmpty()) {
                sb.append("卷承转合：").append(String.join("；", volume.getBeats())).append("\n");
            }
            if (volume.getSeeds() != null && !volume.getSeeds().isEmpty()) {
                sb.append("卷级伏笔（滚向卷尾回收）：").append(String.join("；", volume.getSeeds())).append("\n");
            }
            if (volume.getArcPlan() != null && !volume.getArcPlan().isEmpty()) {
                sb.append("卷内弧清单（含弧预算，规划阶段窗口时对齐）：");
                for (VolumeBlueprintEntity.ArcPlan arc : volume.getArcPlan()) {
                    if (arc != null && StringUtils.isNotBlank(arc.getOneLineGoal())) {
                        sb.append("【弧").append(arc.getArcNo()).append("】").append(arc.getOneLineGoal());
                        if (arc.getEstimatedChapters() != null) {
                            sb.append("（预计 ").append(arc.getEstimatedChapters()).append(" 章）");
                        }
                        if (StringUtils.isNotBlank(arc.getTurningPoint())) {
                            sb.append("〔转折：").append(arc.getTurningPoint()).append("〕");
                        }
                        sb.append("；");
                    }
                }
                sb.append("\n");
            }
            if (isFinalVolume(volume, hardTotal)) {
                sb.append("本弧位于全书末卷：较卷级粗收敛更细地清空终局——本弧内不得再开辟新的远期主线/势力/设定，")
                        .append("终局节点的推进应按弧内窗口逐步收窄，为到硬性完结上限自然收束留足窗口；严禁拖戏或注水。\n");
            }
        }

        // 进度对齐块：本阶段窗口覆盖的大纲段与里程碑——蓝图任务必须服务大纲路标。
        // 此前 chapterGoal（作者的分章预算）从不进蓝图 prompt，规划层在 4 岁旧城街区上写"9 岁华杯赛"路标而无人对表。
        // chapterGoal 无结构（散文大纲/解析失败）时豁免——与体检的进度对齐指标同源同容错。
        // 2026-10-11 方案 A（重锚）：正文实际时间落后大纲章段预算是结构性的（追历机制连续多批未收敛，
        // 学校线里程碑锚在年龄上，硬追日历等于永远执行"未到期"的路标）——大纲段的时间预算按正文
        // 实际锚点**整体平移**后作为本阶段执行时间线：学校线里程碑保留顺序随实际时间滑动；
        // 某资产等真实历史事件不随平移（它们锚定真实年份，正文迟早会走到）。同一次生成内所有段
        // 共用同一个平移量（从段首所在段计算），段间距不得被压乱。
        if (storyContext != null && StringUtils.isNotBlank(storyContext.getChapterGoal())) {
            OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(storyContext.getChapterGoal());
            if (!outline.isEmpty() && batchEnd > window.startChapter()) {
                Integer anchorYear = StoryPacing.latestAnchorYear(summaries);
                Integer anchorAge = StoryPacing.latestAnchorAge(summaries);
                OutlineSegmentParser.OutlineSegment primary = outline.segmentFor(window.startChapter());
                Integer shiftYears = StoryPacing.calendarShiftYears(primary, anchorYear);
                Integer shiftAges = (primary != null && primary.ageStart() != null && anchorAge != null)
                        ? primary.ageStart() - anchorAge : null;
                sb.append("\n【进度对齐·大纲路标】（本阶段窗口覆盖的大纲段——tasks 必须把剧情推进到这些路标）\n");
                if (shiftYears != null && shiftYears > 0) {
                    sb.append("⚠️ 时间账重锚：正文实际在 ").append(anchorYear).append(" 年")
                            .append(anchorAge == null ? "" : "/" + anchorAge + " 岁")
                            .append("，大纲预算已按实际时间整体平移 ").append(shiftYears).append(" 年——")
                            .append("下方【重锚预算】即本阶段的执行时间线。学校/竞赛/升学类里程碑**保留顺序**按重锚预算执行；")
                            .append("某资产行情与真实历史事件**仍按真实年份**（不随平移）；原大纲年份只作里程碑顺序参考。\n");
                }
                int shown = 0;
                for (OutlineSegmentParser.OutlineSegment seg : outline.segments()) {
                    if (seg.endChapter() < window.startChapter() || seg.startChapter() > batchEnd) {
                        continue;
                    }
                    StoryPacing.SlidBudget slid = StoryPacing.slideSegment(seg, shiftYears, shiftAges);
                    String slidLabel = slid.yearStart() == null && slid.ageStart() == null ? ""
                            : "【重锚预算 " + (slid.yearStart() == null ? "" : slid.yearStart() + "-"
                                    + (slid.yearEnd() == null ? slid.yearStart() : slid.yearEnd()) + "年")
                            + (slid.ageStart() == null ? "" : " " + slid.ageStart() + "-"
                                    + (slid.ageEnd() == null ? slid.ageStart() : slid.ageEnd()) + "岁")
                            + "】";
                    sb.append("- 第 ").append(seg.startChapter()).append("-").append(seg.endChapter())
                            .append(" 章").append(slidLabel)
                            .append(shiftYears != null && shiftYears > 0 && StringUtils.isNotBlank(seg.timeLabel())
                                    ? "（原大纲" + seg.timeLabel() + "）" : "")
                            .append("：").append(seg.milestone()).append("\n");
                    shown++;
                }
                if (shown > 0) {
                    sb.append("【任务配比·硬约束】本阶段 tasks 必须把主粮线里程碑（竞赛/学业/某资产/技术）")
                            .append("排进窗口内对应章——与章级主线推进的线别纪律同口径：主粮章（学习/本金/程序）")
                            .append("每段 ≥2、悬疑暗线 ≤2（机械校验会驳回）；重锚预算内确实排不完的里程碑")
                            .append("可顺延至后续段，但顺序不得乱、不得跳过。\n");
                }
            }
        }

        sb.append("\n【上一版阶段蓝图】\n");
        if (previous == null) {
            sb.append("（无——这是第一版阶段蓝图）\n");
        } else {
            sb.append("阶段目标：").append(nullToBlank(previous.getStageGoal())).append("\n");
            if (previous.getTasks() != null) {
                for (String task : previous.getTasks()) {
                    sb.append("- ").append(task).append("\n");
                }
            }
            // 结转清单必须**原样渲染**：第 3 条要求模型"对上一版每条未完成任务逐条结转"，
            // 而此前这里只渲染 tasks/exitResults/stageReport——模型根本看不到上一版 carriedTasks，
            // 只能凭近章摘要重猜，于是被注入的约定事件（"面邀必须兑现""附加赛必须完成"）
            // 在换版时被静默丢弃。实测：第 14 阶段注入的附加赛任务在第 15 阶段 carriedTasks 中消失，
            // 系统侧无法察觉（模型不报错，只是不再提）。补齐输入，"逐条结转"才有据可依。
            if (previous.getCarriedTasks() != null && !previous.getCarriedTasks().isEmpty()) {
                sb.append("\n【上一版结转任务】（第 3 条要求逐条处置：完成/进行中/放弃，严禁静默丢弃）\n");
                for (StageBlueprintEntity.CarriedTaskEntity task : previous.getCarriedTasks()) {
                    if (task == null || StringUtils.isBlank(task.getContent())) {
                        continue;
                    }
                    sb.append("- [").append(StringUtils.defaultIfBlank(task.getStatus(), "进行中")).append("] ")
                            .append(task.getContent().trim());
                    if (StringUtils.isNotBlank(task.getNote())) {
                        sb.append("（").append(task.getNote().trim()).append("）");
                    }
                    sb.append("\n");
                }
            }
            if (previous.getExitResults() != null && !previous.getExitResults().isEmpty()) {
                sb.append("\n【上一阶段退出条件核验】（机械核验结果，未达成项必须结转）\n");
                for (StageBlueprintEntity.ExitConditionResult result : previous.getExitResults()) {
                    if (Boolean.TRUE.equals(result.getMet())) {
                        sb.append("- 已达成：").append(nullToBlank(result.getCondition()))
                                .append("（证据：第").append(result.getChapterNo()).append("章「")
                                .append(nullToBlank(result.getEvidence())).append("」）\n");
                    } else {
                        sb.append("- 未达成：").append(nullToBlank(result.getCondition()))
                                .append("（缺口：").append(nullToBlank(result.getNote())).append("）\n");
                    }
                }
            }
            if (StringUtils.isNotBlank(previous.getStageReport())) {
                sb.append("\n【上一阶段节奏报告】（机械统计，供本阶段规划校准）\n")
                        .append(previous.getStageReport()).append("\n");
            }
        }

        sb.append("\n【近章剧情摘要】（截至当前进度的实际情况）\n");
        List<ChapterSummaryEntity> recent = recentSummaries(summaries);
        if (recent.isEmpty()) {
            sb.append("（尚无章节摘要——本蓝图完全依据故事大纲制定）\n");
        } else {
            for (ChapterSummaryEntity summary : recent) {
                sb.append("- 第").append(summary.getChapterNo()).append("章《").append(nullToBlank(summary.getTitle()))
                        .append("》：").append(nullToBlank(summary.getSummary())).append("\n");
            }
        }

        sb.append("\n【当前待回收伏笔】\n");
        if (foreshadowLines == null || foreshadowLines.isEmpty()) {
            sb.append("（暂无）\n");
        } else {
            for (String line : foreshadowLines) {
                sb.append("- ").append(line).append("\n");
            }
        }

        sb.append("\n【篇幅与完结契约】\n");
        if (previous == null) {
            sb.append("这是首版蓝图。请根据故事大纲给出 estimatedTotalChapters（预计总章数），它只是估算，不是硬性完结章号。\n");
        } else {
            sb.append("预计总章数：").append(previous.getEstimatedTotalChapters() == null ? "未设定" : previous.getEstimatedTotalChapters())
                    .append("；当前阶段：").append(StringUtils.defaultString(previous.getStoryPhase(), "NORMAL"))
                    .append("；已声明收官卷：").append(Boolean.TRUE.equals(previous.getFinalVolumeDeclared()) ? "是" : "否").append("\n");
            if (previous.getRemainingFinaleBeats() != null && !previous.getRemainingFinaleBeats().isEmpty()) {
                sb.append("尚未完成的终局节点：").append(String.join("、", previous.getRemainingFinaleBeats())).append("\n");
            }
        }
        sb.append("预计总章数必须给出合理整数；同时给出 estimatedRemainingChaptersMin/Max。只有所有终局节点完成后才允许 remainingFinaleBeats 为空，禁止仅因达到预计章数就完结。\n");

        if (hardTotal != null && hardTotal > 0) {
            sb.append("全书硬性完结上限：第 ").append(hardTotal).append(" 章。任何阶段的 endChapter 与章节规划都不得超出该章；")
                    .append("剧情必须在此上限前自然收束，禁止为逼近上限而开新的远期主线、新增主要反派或长期支线。\n");
        }
        {
            // 直达上限（本窗口终点被机械收束到硬上限）才算全书最后一阶段；收敛以距上限剩余预算判断
            boolean reachingCap = hardTotal != null && hardTotal > 0
                    && effectiveEndBounds(window.startChapter(), batchEnd)[0] >= hardTotal;
            boolean converging = hardTotal != null && hardTotal > 0
                    && hardTotal - window.startChapter() <= CONVERGENCE_LEAD;
            if (reachingCap) {
                sb.append("本阶段将直达完结上限第 ").append(hardTotal).append(" 章，是全书最后一个阶段：finalVolumeDeclared 必须为 true，")
                        .append("storyPhase 必须为 EPILOGUE，remainingFinaleBeats 必须安排在本阶段内全部完成并清空；")
                        .append("不得再新增任何终局节点、新角色或新设定。\n");
            } else if (converging) {
                sb.append("距硬性完结上限第 ").append(hardTotal).append(" 章已不足 ").append(CONVERGENCE_LEAD)
                        .append(" 章，本阶段起进入收官收敛：不再开启新的长距离主线/势力/设定，")
                        .append("冲突规模宜随剩余预算递减，remainingFinaleBeats 需逐步清空，为到限自然收束留出窗口，严禁拖戏或注水。\n");
            }
        }

        sb.append("\n【阶段窗口】\n");
        int[] bounds = effectiveEndBounds(window.startChapter(), batchEnd);
        int minEnd = bounds[0];
        int maxEnd = bounds[1];
        if (!window.adaptive()) {
            sb.append("本阶段窗口由系统默认值固定：第 ").append(window.startChapter())
                    .append("-").append(Math.min(window.startChapter() + STAGE_LENGTH - 1, maxEnd))
                    .append(" 章（仅故事起步的首版蓝图如此，后续阶段窗口将由你按剧情弧线自行设定）。\n");
        } else if (minEnd == maxEnd) {
            sb.append("本阶段起点为第 ").append(window.startChapter()).append(" 章。本次规划覆盖至第 ").append(batchEnd)
                    .append(" 章，起点距批次末章不足一个标准窗长：endChapter 必须为 ").append(maxEnd)
                    .append("，本阶段直接收束到批次末章。\n")
                    .append("tasks 必须安排全部【当前待回收伏笔】的回收与主线收束，这是本批最后一段，不留悬而未决的主线。\n");
        } else {
            sb.append("本阶段起点为第 ").append(window.startChapter()).append(" 章，终点请根据本阶段任务的自然弧线自行设定 endChapter")
                    .append("——宜取弧线收束点（如一场主要冲突尘埃落定、一段势力格局定型的节点）。\n")
                    .append("本次规划覆盖至第 ").append(batchEnd).append(" 章，endChapter 不得超出该章；窗长约束 30-80 章：")
                    .append("endChapter 必须落在 ").append(minEnd).append("~").append(maxEnd)
                    .append(" 之间，超出部分机械侧会按此区间钳制。上一版遗留任务如仍在推进，窗口应为其预留空间。\n");
            if (maxEnd == batchEnd) {
                sb.append("注意：本阶段最远可覆盖至批次末章——若剧情弧线在此收束，endChapter 应设为 ")
                        .append(batchEnd).append("，并在 tasks 中安排全部待回收伏笔的回收。\n");
            }
        }

        sb.append("\n【任务】");
        if (window.adaptive()) {
            sb.append("为第 ").append(window.startChapter()).append(" 章起（第 ").append(window.stageNo())
                    .append(" 阶段）制定阶段蓝图，窗口终点按上方约束自行设定。\n");
        } else {
            sb.append("为第 ").append(window.startChapter()).append("-")
                    .append(window.startChapter() + STAGE_LENGTH - 1)
                    .append(" 章（第 ").append(window.stageNo()).append(" 阶段）制定阶段蓝图。\n");
        }
        sb.append("\n【要求】\n")
                .append("1. stageGoal：1-2 句，描述本阶段结束时故事应到达的状态，必须服务于故事大纲的方向。\n")
                .append("2. tasks：3-6 条里程碑，必须是**状态型目标**——每条都要能回答"
                        + "\"谁比之前多知道了什么 / 谁做了什么不可逆的决定 / 谁与谁的关系变了\"（如\"主角突破至筑基期\""
                        + "\"揭开古物来历的一半\"\"与某家关系转为敌对\"）。\n")
                .append("  · 严禁写成活动配额（如\"安排3场战斗\"\"回收5个伏笔\"）。\n")
                .append("  · ⚠️ **同样严禁写成\"完成某个动作/手续\"**：实测反例——"
                        + "\"完成贴邮票投递的完整动作\"\"将格子纸与便条收入蓝皮本固定外层\"。"
                        + "这类目标无法用场面承载，章节只能被写成流程说明（写手把每个动作拆开逐一描写，"
                        + "全章没有戏）；必须把动作**换成谁因此改变了什么**，"
                        + "例如\"角色甲把2026搁置下去，夫妻达成『等孩子自己开口』的默契\"。\n")
                .append("  · 取词与题材相称：现实题材不要套用修炼类词（突破/修为/境界），"
                        + "改用认知与关系词（确认/摊牌/放手/默许/被说服/达成默契）。\n")
                .append("  · 自检：把一条 task 里的动作部分删掉后，若只剩下\"信寄出去了\"这类流程结果，"
                        + "那它是流程不是里程碑，必须重写成它造成的认知或关系变化。\n")
                .append("  · 2.1 ⚠️ **剧情线配比**：里程碑必须给主角的学习/能力/关系线留出明确份额——")
                .append("悬疑/暗线类里程碑每段**至多半数且不得连续两章**，严禁整段连排暗线把主角自身的成长挤出舞台")
                .append("（实测 106-110 段：封存仪式与拆信推断连排，竞赛/学业线整段缺席，评分 payoff/expectation 双低）。\n")
                .append("3. carriedTasks：对【上一版阶段蓝图】中每条未完成任务逐条结转——已完成标\"完成\"，")
                .append("部分推进标\"进行中\"并在 note 注明进展，决定不再推进标\"放弃\"并在 note 注明理由；")
                .append("严禁静默丢弃未完成任务。若无上一版蓝图则输出空数组。\n")
                .append("3.1 🚫 **冻结线红线**：若【上一版结转任务】中含\"静默期/冻结/禁止/不得推进\"字样的条目，")
                .append("该剧情线本阶段**只允许结转，不得安排任何新任务**——严禁把它写进 tasks、exitConditions、")
                .append("suspenseLadder 或 mainLineByChapter。实测反例（第 16 阶段）：结转清单明写"
                        + "「虹镇线静默期：≤1次且必须是已封存状态余波、严禁新追查/新实物」，")
                .append("模型却在 tasks 里安排了「角色甲透露虹镇旧事的新信息碎片」——把\"冻结\"读成了\"再注入一条\"，")
                .append("于是第 140 章凭空多出地窖账册，与第 130 章\"铁箱已焊死封存\"的定案正面冲突。")
                .append("冻结线的正确写法是：carriedTasks 里原样结转并保持\"进行中\"，tasks/exitConditions 里一个字都不提。\n")
                .append("4. 待回收伏笔可把相关条目编排进本阶段任务，但必须与剧情自然契合，严禁为清账强行回收。\n")
                .append("5. entryConstraints：2-4 条本阶段开始时应已成立的局面事实（承接上一版蓝图任务状态与近章摘要），")
                .append("必须是事实性陈述，将作为本阶段章节规划的护栏。\n")
                .append("6. exitConditions：3-6 条可核对的达成谓词，必须可观察、可引用、有终止性——")
                .append("\"主角境界达到筑基\"可核对，\"主角变强\"不可核对；")
                .append("每条至少对应 stageGoal 或 tasks 中一项，阶段末章后将逐条机械核验，无法核验的写法一律不许出现。\n")
                .append("6.1 ⚠️ **一条条件只能指向一个可引用的场景**：核验时要求为每条给出**单章内的一段连续原文**作证据，")
                .append("所以下列写法一律禁止，必须拆成多条独立条件：\n")
                .append("  · 对比/并列两种要素：「A 与 B 的行为模式对比」「线下 X 与线上 Y」")
                .append("→ 拆成「A 如何表现」「B 如何表现」两条；\n")
                .append("  · 二选一：「出现 X 或 Y」「至少带出 X 或 Y 中的一项」")
                .append("→ 只写证据实际会落地的那个场景（二选一还额外要求你把场景收敛，否则核验时两边都对不上）；\n")
                .append("  · 跨章演化：「从 A 状态转变为 B 状态」→ 拆成「A 状态已成立」「B 状态已成立」两条。\n")
                .append("  ⇒ 自检标准：这条条件的证据，能否在**某一章**里找到一段连续原文？找不到就说明它该拆开。\n")
                .append("6.2 ⚠️ **条件只描述「叙事状态是否成立」，不得预设实现细节**。核验是拿摘要/正文去**匹配语义**，")
                .append("而你并不知道正文最终会怎么落笔；一旦把写法写死，同一叙事意图用别的写法实现就会被判未达成。")
                .append("下列写法一律禁止：\n")
                .append("  · **指定台词原文**：「说出『到此为止』」「台词中出现『XXXX』」")
                .append("→ 写「表达出终止追查的明确态度」——同义措辞（如\"到这儿为止\"）同样应判达成；\n")
                .append("  · **指定精确次数**：「连续三次」「至少两次」「反复不少于 N 回」")
                .append("→ 写「有过持续观察/多次关注的表现」；次数是执行细节，写死会把\"写了但次数不同\"判失败；\n")
                .append("  · **指定物件位置/载体**：「铁盒内存在 X」「存放在抽屉最里层」「以某物覆盖」")
                .append("→ 写「X 已被妥善收存（位置不限）」，除非该位置本身就是剧情关键；\n")
                .append("  · **指定场景细枝**：「完成第一顿晚饭」「在某个具体时辰」")
                .append("→ 写该场景要承载的叙事作用（如\"新居生活秩序已建立\"），让正文自选承载方式；\n")
                .append("  · **指定说话人身份之外的措辞限定**：「由父亲或母亲说出」「用否定句式」→ 只保留\"由谁说出\"。\n")
                .append("  ⇒ 自检标准：把这条条件交给一个**没看过正文**的人，他能仅凭\"叙事状态\"判断达成与否吗？")
                .append("若他必须逐字比对台词、数字或位置才能判断，就说明写法写死了。\n")
                .append("7. 上一阶段未达成的退出条件由系统**原样注入**本阶段 exitConditions 并在阶段末重验一次；")
                .append("你无须在 carriedTasks 里重复登记，**严禁**把它们的原文或改写版再写进 exitConditions，")
                .append("也严禁改写其措辞或标为放弃（重验仍不达成的由系统自动出账）；")
                .append("但本阶段 tasks 必须包含使其达成的路径。\n")
                .append("8. storyPhase 只能填写 NORMAL/PREPARATION/ESCALATION/WAR/RESOLUTION/EPILOGUE；")
                .append("当故事仍在终局准备（集结、建堡垒、取证、领悟能力）时不得填写 RESOLUTION 或 EPILOGUE。\n")
                .append("9. finalVolumeDeclared 只有在主要冲突已经进入收官卷时才可为 true；一旦上一版为 true，必须保持 true，不得回退。\n")
                .append("10. remainingFinaleBeats 列出尚未完成的决战、真相、重塑天地、善后、尾声等节点；")
                .append("节点未清空前不得声称全书完结，也不得新增主要反派或长期支线。\n")
                .append("11. ⚠️ coreSuspense / suspenseLadder——**主线推进的机械标尺**，必须认真给出：\n")
                .append("    - coreSuspense：一句话写明**本书的核心悬念**（全书围绕'什么未知'展开），跨阶段保持稳定；\n")
                .append("    - suspenseLadder：该悬念的**推进档位表**，3-6 档、有序，从'完全不知'排到'彻底摊牌'；\n")
                .append("    - 每档必须写成**可观察**的表述（谁知道了什么 / 谁做了什么决定 / 什么被公开），")
                .append("严禁写成内心感受、情绪波动或氛围；\n")
                .append("    - 档位会被逐章机械比对（章计划回填 suspenseBeat）：索引不得倒退，")
                .append("非过渡章**不得连续 3 章停在同一档**；\n")
                .append("    - **'产生怀疑又被自我否定圆回'不算推进**——若某章结束时的认知档位与章首相同，那就是原地章；\n")
                .append("    - **档数必须 ≥ 本阶段章数**：4 档配 5 章必然多章共用一档（实测连续 4 章停在同一档——")
                .append("档数少于章数时，最宽的那一档会被反复共用并撑满整段，主线观感上就是停滞；")
                .append("相邻两档必须**语义可区分**（上一档结了什么新事实/谁下了什么新决定，下一档推进了什么），")
                .append("严禁把'形成默契/关系深化/局势升级'这类**状态性宽词**当档位——它能装下整段，等于没有档位；")
                .append("超过 6 档会让每档失去意义。\n")
                .append("12. ⚠️ 里程碑必须与人物阶段（年龄/生理/状态）相符（对照上方【时序锚】，如给出）：\n")
                .append("    - 先按【时序锚】估算本阶段结束时的故事时间与主角年龄；\n")
                .append("    - tasks / suspenseLadder / exitConditions 中**不得出现超出该阶段能力边界的里程碑**")
                .append("（一切超出该年龄生理/状态极限的动作、语言、书写、知识外化与专注要求——")
                .append("如幼儿完成数论推演、连贯书写、长篇推演、成句口语，其他阶段同理）；\n")
                .append("    - 也不得把能力展示包装成**可被他人事后解码的间接传递**（涂鸦摆出答案、摆物摆出警告等）")
                .append("当作阶段里程碑：载体换了，能力展示的本质没变；观察者的解读须跨章累积、保留不确定；\n")
                .append("    - 圣经章节带目标只表示剧情方向，不得据此让主角跨越尚未到达的生理阶段；\n")
                .append("    - 本阶段若跨过成长节点（周岁、入园、入学等），必须在 tasks 中显式安排过渡，不得跳级设定。\n")
                .append("12.1 ⚠️ stageEndYear / stageEndAge（必填）：声明本阶段末的故事时间与主角年龄——")
                .append("**从上方【进度对齐·大纲路标】的重锚预算取值**（已按正文实际时间平移；")
                .append("某资产等真实历史事件年份不随平移）。相邻两个阶段的 stageEnd 仍须**单调递增**且不重叠。")
                .append("13. 不要输出 Markdown，不要输出解释文字。\n");

        int exampleEnd = window.adaptive() ? Math.min(window.startChapter() + 44, maxEnd)
                : Math.min(window.startChapter() + STAGE_LENGTH - 1, maxEnd);
        sb.append("\n请严格按照以下 JSON 格式输出：\n")
                .append("{\"stageNo\":").append(window.stageNo())
                .append(",\"startChapter\":").append(window.startChapter())
                .append(",\"endChapter\":").append(exampleEnd)
                .append(",\"storyPhase\":\"PREPARATION\",\"finalVolumeDeclared\":false")
                .append(",\"estimatedTotalChapters\":158,\"estimatedRemainingChaptersMin\":120,\"estimatedRemainingChaptersMax\":135")
                .append(",\"remainingFinaleBeats\":[\"终局节点1\"],\"completedFinaleBeats\":[]")
                .append(",\"volumeNo\":").append(volume == null ? 1 : volume.getVolumeNo())
                .append(",\"volumeTitle\":").append(volume == null ? "无卷" : nullToBlank(volume.getTitle()))
                .append(",\"arcNo\":1").append(",\"arcGoal\":\"本弧一句话目标（对应卷内弧清单中本弧的 oneLineGoal）\"")
                .append(",\"stageGoal\":\"阶段目标\",\"tasks\":[\"里程碑1\"],")
                .append("\"coreSuspense\":\"本书核心悬念一句话\",")
                .append("\"suspenseLadder\":[\"档位1（完全不知）\",\"档位2（可观察的中间态）\",\"档位3（彻底摊牌）\"],")
                .append("\"entryConstraints\":[\"本阶段开始时应成立的局面1\"],")
                .append("\"exitConditions\":[\"可核对的达成谓词1\"],")
                .append("\"carriedTasks\":[{\"content\":\"上一版任务\",\"status\":\"完成\",\"note\":\"说明\"}]}");
        return sb.toString();
    }

    /**
     * 解析模型输出并规整（无硬性完结上限）：委托 hardTotal=null
     */
    public StageBlueprintEntity parse(String raw, StageBlueprintEntity previous, int nextChapterNo, int batchEnd) {
        return parse(raw, previous, nextChapterNo, batchEnd, null);
    }

    /**
     * 解析模型输出并规整；两轮降级全败返回 null（蓝图是增强件，调用方 fail-soft 继续）。
     * 窗口不信任模型输出的章号：fixed 首版按默认值覆盖，adaptive 按标准窗长钳制且不超出批次末章（见 normalize）。
     * hardTotal 非空且本阶段终点直达上限时机械强制收官（normalize 内）
     */
    public StageBlueprintEntity parse(String raw, StageBlueprintEntity previous, int nextChapterNo, int batchEnd,
                                      Integer hardTotal) {
        StageBlueprintEntity blueprint = JsonParseFallback.parse(raw, this::readBlueprint);
        return blueprint == null ? null : normalize(blueprint, previous, nextChapterNo, batchEnd, hardTotal);
    }

    private StageBlueprintEntity readBlueprint(String raw) {
        try {
            return BLUEPRINT_MAPPER.readValue(raw, StageBlueprintEntity.class);
        } catch (Exception e) {
            // 抛非受检异常交给 JsonParseFallback 按"解析失败"语义降级（JsonRepair 后重解）
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * 规整：fixed 首版窗口按默认值覆盖模型输出（不超出批次末章）；adaptive 终点钳制在标准窗长内
     * 且不超出批次末章——起点距批次末章不足一个标准窗长时强制收束（区间收缩为 [batchEnd, batchEnd]），
     * 模型漏报终点时取区间下限；任务封顶去空，结转状态兜底。
     * hardTotal 非空且终点直达上限时机械强制收官（finalVolumeDeclared=true + storyPhase≥RESOLUTION），
     * 不信任模型的收官自评——保证第 hardTotal 章所在的最后阶段必然被规划为收官卷
     */
    public StageBlueprintEntity normalize(StageBlueprintEntity blueprint, StageBlueprintEntity previous,
                                          int nextChapterNo, int batchEnd) {
        return normalize(blueprint, previous, nextChapterNo, batchEnd, null);
    }

    /**
     * 规整（携带硬性完结上限）：见四参重载；hardTotal 非空且终点直达上限时机械强制收官
     */
    public StageBlueprintEntity normalize(StageBlueprintEntity blueprint, StageBlueprintEntity previous,
                                          int nextChapterNo, int batchEnd, Integer hardTotal) {
        if (blueprint == null) {
            return null;
        }
        StageWindow window = nextWindow(previous, nextChapterNo);
        blueprint.setStageNo(window.stageNo());
        blueprint.setStartChapter(window.startChapter());
        blueprint.setStoryPhase(StringUtils.defaultIfBlank(blueprint.getStoryPhase(),
                previous == null ? "NORMAL" : StringUtils.defaultIfBlank(previous.getStoryPhase(), "NORMAL")));
        if (previous != null && phaseRank(blueprint.getStoryPhase()) < phaseRank(previous.getStoryPhase())) {
            blueprint.setStoryPhase(previous.getStoryPhase());
        }
        if (previous != null && Boolean.TRUE.equals(previous.getFinalVolumeDeclared())) {
            blueprint.setFinalVolumeDeclared(true);
        } else if (blueprint.getFinalVolumeDeclared() == null) {
            blueprint.setFinalVolumeDeclared(false);
        }
        if (blueprint.getEstimatedTotalChapters() == null && previous != null) {
            blueprint.setEstimatedTotalChapters(previous.getEstimatedTotalChapters());
        }
        if (blueprint.getEstimatedRemainingChaptersMin() == null && previous != null) {
            blueprint.setEstimatedRemainingChaptersMin(previous.getEstimatedRemainingChaptersMin());
        }
        if (blueprint.getEstimatedRemainingChaptersMax() == null && previous != null) {
            blueprint.setEstimatedRemainingChaptersMax(previous.getEstimatedRemainingChaptersMax());
        }
        if (blueprint.getEstimatedTotalChapters() != null && blueprint.getEstimatedTotalChapters() > 0) {
            int remaining = Math.max(0, blueprint.getEstimatedTotalChapters() - (nextChapterNo - 1));
            int fallbackMin = Math.max(0, (int) Math.floor(remaining * 0.85));
            int fallbackMax = (int) Math.ceil(remaining * 1.15);
            if (blueprint.getEstimatedRemainingChaptersMin() == null) {
                blueprint.setEstimatedRemainingChaptersMin(fallbackMin);
            }
            if (blueprint.getEstimatedRemainingChaptersMax() == null) {
                blueprint.setEstimatedRemainingChaptersMax(fallbackMax);
            }
            if (blueprint.getEstimatedRemainingChaptersMin() < 0) {
                blueprint.setEstimatedRemainingChaptersMin(0);
            }
            if (blueprint.getEstimatedRemainingChaptersMax() < blueprint.getEstimatedRemainingChaptersMin()) {
                blueprint.setEstimatedRemainingChaptersMax(blueprint.getEstimatedRemainingChaptersMin());
            }
        }
        if (blueprint.getRemainingFinaleBeats() == null && previous != null) {
            blueprint.setRemainingFinaleBeats(previous.getRemainingFinaleBeats());
        }
        if (blueprint.getCompletedFinaleBeats() == null && previous != null) {
            blueprint.setCompletedFinaleBeats(previous.getCompletedFinaleBeats());
        }
        blueprint.setRemainingFinaleBeats(capStrings(blueprint.getRemainingFinaleBeats(), MAX_FINALE_BEATS));
        blueprint.setCompletedFinaleBeats(capStrings(blueprint.getCompletedFinaleBeats(), MAX_FINALE_BEATS));
        int[] bounds = effectiveEndBounds(window.startChapter(), batchEnd);
        if (!window.adaptive()) {
            blueprint.setEndChapter(Math.min(window.startChapter() + STAGE_LENGTH - 1, bounds[1]));
        } else {
            Integer modelEnd = blueprint.getEndChapter();
            blueprint.setEndChapter(modelEnd == null || modelEnd < bounds[0] ? bounds[0] : Math.min(modelEnd, bounds[1]));
        }
        if (hardTotal != null && hardTotal > 0) {
            if (blueprint.getEndChapter() != null && blueprint.getEndChapter() > hardTotal) {
                blueprint.setEndChapter(hardTotal);
            }
            // 终点直达硬上限即全书最后一阶段：机械强制收官卷（不信任模型自评）
            if (blueprint.getEndChapter() != null && blueprint.getEndChapter() == hardTotal) {
                blueprint.setFinalVolumeDeclared(true);
                if (phaseRank(blueprint.getStoryPhase()) < phaseRank("RESOLUTION")) {
                    blueprint.setStoryPhase("RESOLUTION");
                }
            }
        }
        blueprint.setStageGoal(StringUtils.trimToNull(blueprint.getStageGoal()));
        blueprint.setTasks(capStrings(blueprint.getTasks(), MAX_TASKS));
        blueprint.setEntryConstraints(capStrings(blueprint.getEntryConstraints(), MAX_ENTRY_CONSTRAINTS));
        blueprint.setExitConditions(capStrings(blueprint.getExitConditions(), MAX_EXIT_CONDITIONS));
        blueprint.setCarriedTasks(capCarriedTasks(blueprint.getCarriedTasks(), MAX_CARRIED_TASKS));
        return blueprint;
    }

    /**
     * 卷窗口：卷是长期锚，起点由上一卷终点 +1（首卷为连载起点），终点由模型按剧情移动自定，
     * 机械钳制在 MIN/MAX_VOLUME_LENGTH 章之间（不随批次末章收缩——卷覆盖长时间跨度）
     */
    public record VolumeWindow(int volumeNo, int startChapter) {
    }

    /**
     * 卷触发判断：无卷，或最新卷的覆盖区间未及下一章（跨过卷边界即生成新卷）
     */
    public boolean needsVolumeGeneration(List<VolumeBlueprintEntity> volumes, int nextChapterNo) {
        VolumeBlueprintEntity latest = latestVolumeOf(volumes);
        return latest == null || latest.getEndChapter() == null || latest.getEndChapter() < nextChapterNo;
    }

    public VolumeBlueprintEntity latestVolumeOf(List<VolumeBlueprintEntity> volumes) {
        if (volumes == null || volumes.isEmpty()) {
            return null;
        }
        return volumes.get(volumes.size() - 1);
    }

    /**
     * 章号所属卷：卷链中起点不大于该章号的最新一卷（卷与卷连续覆盖，故即该章所在卷）。
     * 章节计划/正文的卷方向锚用它而非 {@link #latestVolumeOf}——批次跨卷边界时最新卷
     * 可能尚未开始（如上一卷终点在批次中间），用它会把下一卷主旨/卷级伏笔提前泄给旧卷章节。
     * 章号早于所有卷起点（卷生成失败退化为两段式）返回 null，不注入方向锚
     */
    public VolumeBlueprintEntity volumeAt(List<VolumeBlueprintEntity> volumes, int chapterNo) {
        if (volumes == null || volumes.isEmpty()) {
            return null;
        }
        VolumeBlueprintEntity found = null;
        for (VolumeBlueprintEntity volume : volumes) {
            if (volume == null || volume.getStartChapter() == null || volume.getStartChapter() > chapterNo) {
                continue;
            }
            if (found == null || volume.getStartChapter() >= found.getStartChapter()) {
                found = volume;
            }
        }
        return found;
    }

    /**
     * 是否全书末卷：卷的覆盖区间越到硬性完结上限（endChapter 达到/越过 hardTotal），
     * 或已无硬上限但该卷覆盖当前进度且其终点不低于 hardTotal 兜底。用于给弧注入"末卷细收敛"框架
     */
    public boolean isFinalVolume(VolumeBlueprintEntity volume, Integer hardTotal) {
        if (volume == null || volume.getEndChapter() == null) {
            return false;
        }
        if (hardTotal != null && hardTotal > 0) {
            return volume.getEndChapter() >= hardTotal;
        }
        return false;
    }

    /**
     * 下一卷起点：无卷则连载起点，有卷则上一卷终点 + 1（卷与卷连续覆盖，不留缺口）
     */
    public int nextVolumeStart(List<VolumeBlueprintEntity> volumes, int nextChapterNo) {
        VolumeBlueprintEntity latest = latestVolumeOf(volumes);
        if (latest == null || latest.getEndChapter() == null) {
            return nextChapterNo;
        }
        return latest.getEndChapter() + 1;
    }

    /**
     * 卷终点的有效钳制区间：MIN~MAX_VOLUME_LENGTH 窗长，不随批次末章收缩。
     * 完结上限（hardTotal）在 normalizeVolume 内另行钳制，末卷据此排布收敛
     */
    private int[] effectiveVolumeEndBounds(int startChapter) {
        return new int[]{startChapter + MIN_VOLUME_LENGTH - 1, startChapter + MAX_VOLUME_LENGTH - 1};
    }

    /**
     * 组装卷蓝图生成 prompt：原始大纲 + 上一卷 + 近章摘要，锚定一层剧情移动（承转合）。
     * hardTotal 非空时钳制本卷终点到上限，并声明"本卷为全书末卷"的收敛排布要求
     */
    public String buildVolumePrompt(StoryContextEntity storyContext,
                                    VolumeBlueprintEntity previous,
                                    List<ChapterSummaryEntity> summaries,
                                    VolumeWindow window,
                                    Integer hardTotal) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名资深小说总编，负责为长篇连载制定\"卷\"蓝图（全书长期锚）：一卷 = 一次剧情移动（承转合），由若干弧组成。\n\n");

        sb.append("【故事大纲】\n").append(nullToBlank(storyContext == null ? null : storyContext.getOutline())).append("\n");
        sb.append("\n【主人公】\n").append(nullToBlank(storyContext == null ? null : storyContext.getProtagonist())).append("\n");

        sb.append("\n【上一卷】\n");
        if (previous == null) {
            sb.append("（无——这是第一卷）\n");
        } else {
            sb.append("上一卷终点：第").append(previous.getEndChapter()).append("章\n");
            sb.append("卷主旨：").append(nullToBlank(previous.getThemeShift())).append("\n");
            if (previous.getBeats() != null) {
                for (String beat : previous.getBeats()) {
                    sb.append("- ").append(beat).append("\n");
                }
            }
        }

        sb.append("\n【近章剧情摘要】（截至当前进度的实际推进）\n");
        List<ChapterSummaryEntity> recent = recentSummaries(summaries);
        if (recent.isEmpty()) {
            sb.append("（尚无章节摘要——本卷完全依据故事大纲制定）\n");
        } else {
            for (ChapterSummaryEntity summary : recent) {
                sb.append("- 第").append(summary.getChapterNo()).append("章《").append(nullToBlank(summary.getTitle()))
                        .append("》：").append(nullToBlank(summary.getSummary())).append("\n");
            }
        }

        sb.append("\n【篇幅契约】\n");
        sb.append("请给出本卷的预计总章数（endChapter - startChapter + 1 的合理整数），")
                .append("并保持与全书长期推进节奏一致——不得为凑体量注水，也不得让卷体量过小而把剧情移动切碎。\n");
        if (hardTotal != null && hardTotal > 0) {
            sb.append("全书硬性完结上限：第 ").append(hardTotal)
                    .append(" 章。本卷 endChapter 不得超出该章；若上限距本卷起点不足一个常规卷规模，")
                    .append("则本卷即全书末卷：themeShift 必须指向最终状态转变，beats 收敛为收官排布，")
                    .append("卷内弧必须全部安排终局推进，禁止为凑体量新增长期主线或新势力。\n");
        }

        sb.append("\n【任务】为第 ").append(window.startChapter()).append(" 章起（第 ")
                .append(window.volumeNo()).append(" 卷）制定一卷长期锚定蓝图，本卷起点为第 ")
                .append(window.startChapter()).append(" 章，列出一段独立且完整的剧情移动。\n");

        sb.append("\n【要求】\n")
                .append("1. themeShift：1-2 句，描述本卷结束时主角/世界应到达的状态转变，必须服务故事大纲方向。\n")
                .append("2. beats：2-4 步\"承转合\"节拍，每步一句，标识本卷内部的剧情推进骨架。\n")
                .append("3. arcPlan：2-8 条卷内弧，每条 {arcNo, oneLineGoal, estimatedChapters, turningPoint}，"
                        + "弧是后续阶段蓝图（30-80 章窗口）的上级归属；estimatedChapters 为该弧预计章数（5-40），"
                        + "**各弧之和应约等于本卷章数**；turningPoint 用一句话写该弧的转折点（可空）。\n")
                .append("4. volumeExitConditions：3-6 条可核对的达成谓词（可观察、有终止性），卷末机械核验。\n")
                .append("5. seeds：卷级长线伏笔种子，供后续各弧埋设、卷尾统一回收。\n")
                .append("6. endChapter 由你按剧情移动自然收束点自定，机械侧钳制在 ").append(MIN_VOLUME_LENGTH)
                .append("~").append(MAX_VOLUME_LENGTH).append(" 章窗长内；不得超出完结上限。\n")
                .append("7. 不要输出 Markdown，不要输出解释文字。\n");

        int exampleEnd = window.startChapter() + 479;
        sb.append("\n请严格按照以下 JSON 格式输出：\n")
                .append("{\"volumeNo\":").append(window.volumeNo())
                .append(",\"startChapter\":").append(window.startChapter())
                .append(",\"endChapter\":").append(exampleEnd)
                .append(",\"title\":\"卷名\",\"themeShift\":\"本卷结束时的状态转变\",")
                .append("\"beats\":[\"承：...\",\"转：...\"],")
                .append("\"arcPlan\":[{\"arcNo\":1,\"oneLineGoal\":\"本弧一句话目标\",\"estimatedChapters\":12,\"turningPoint\":\"该弧转折点\"}],")
                .append("\"volumeExitConditions\":[\"可核对的达成谓词1\"],")
                .append("\"seeds\":[\"卷级长线伏笔种子1\"]}");
        return sb.toString();
    }

    /**
     * 解析模型输出并规整卷蓝图；两轮降级全败返回 null（卷是增强件，调用方 fail-soft 继续）
     */
    public VolumeBlueprintEntity parseVolume(String raw, VolumeBlueprintEntity previous,
                                             int nextChapterNo, int batchEnd, Integer hardTotal) {
        VolumeBlueprintEntity volume = JsonParseFallback.parse(raw, this::readVolume);
        return volume == null ? null : normalizeVolume(volume, previous, nextChapterNo, batchEnd, hardTotal);
    }

    private VolumeBlueprintEntity readVolume(String raw) {
        try {
            return BLUEPRINT_MAPPER.readValue(raw, VolumeBlueprintEntity.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * 规整卷蓝图：卷号/起点由链式推导覆盖，终点钳制在卷窗长内并受完结上限约束；
     * 各列表封顶去空。与阶段蓝图不同，卷窗口不随批次末章收缩（卷为长期锚）
     */
    public VolumeBlueprintEntity normalizeVolume(VolumeBlueprintEntity volume, VolumeBlueprintEntity previous,
                                                 int nextChapterNo, int batchEnd, Integer hardTotal) {
        if (volume == null) {
            return null;
        }
        int start = (previous == null || previous.getEndChapter() == null)
                ? nextChapterNo : previous.getEndChapter() + 1;
        int volumeNo = (previous == null) ? 1 : previous.getVolumeNo() == null ? 1 : previous.getVolumeNo() + 1;
        volume.setVolumeNo(volumeNo);
        volume.setStartChapter(start);

        int[] bounds = effectiveVolumeEndBounds(start);
        Integer modelEnd = volume.getEndChapter();
        volume.setEndChapter(modelEnd == null || modelEnd < bounds[0] ? bounds[0] : Math.min(modelEnd, bounds[1]));
        if (hardTotal != null && hardTotal > 0 && volume.getEndChapter() != null && volume.getEndChapter() > hardTotal) {
            volume.setEndChapter(hardTotal);
        }

        volume.setTitle(StringUtils.trimToNull(volume.getTitle()));
        volume.setThemeShift(StringUtils.trimToNull(volume.getThemeShift()));
        volume.setBeats(capStrings(volume.getBeats(), MAX_VOLUME_BEATS));
        volume.setArcPlan(capArcPlan(volume.getArcPlan(), MAX_VOLUME_ARC_PLAN));
        volume.setVolumeExitConditions(capStrings(volume.getVolumeExitConditions(), MAX_VOLUME_EXIT_CONDITIONS));
        volume.setSeeds(capStrings(volume.getSeeds(), MAX_VOLUME_SEEDS));
        return volume;
    }

    private List<VolumeBlueprintEntity.ArcPlan> capArcPlan(List<VolumeBlueprintEntity.ArcPlan> plans, int limit) {
        if (plans == null) {
            return List.of();
        }
        List<VolumeBlueprintEntity.ArcPlan> capped = new ArrayList<>();
        for (VolumeBlueprintEntity.ArcPlan plan : plans) {
            if (capped.size() >= limit) {
                break;
            }
            if (plan == null || StringUtils.isBlank(plan.getOneLineGoal())) {
                continue;
            }
            capped.add(VolumeBlueprintEntity.ArcPlan.builder()
                    .arcNo(plan.getArcNo() == null ? capped.size() + 1 : plan.getArcNo())
                    .oneLineGoal(plan.getOneLineGoal().trim())
                    .estimatedChapters(plan.getEstimatedChapters() == null ? null
                            : Math.max(1, Math.min(80, plan.getEstimatedChapters())))
                    .turningPoint(StringUtils.trimToNull(plan.getTurningPoint()))
                    .build());
        }
        return capped;
    }

    private List<ChapterSummaryEntity> recentSummaries(List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<ChapterSummaryEntity> ordered = new ArrayList<>(summaries);
        ordered.sort(Comparator.comparing(ChapterSummaryEntity::getChapterNo));
        int from = Math.max(0, ordered.size() - BLUEPRINT_SUMMARY_COUNT);
        return ordered.subList(from, ordered.size());
    }

    private List<String> capStrings(List<String> values, int limit) {
        if (values == null) {
            return List.of();
        }
        List<String> capped = new ArrayList<>();
        for (String value : values) {
            if (capped.size() >= limit) {
                break;
            }
            if (StringUtils.isNotBlank(value)) {
                capped.add(value.trim());
            }
        }
        return capped;
    }

    private List<StageBlueprintEntity.CarriedTaskEntity> capCarriedTasks(List<StageBlueprintEntity.CarriedTaskEntity> carried, int limit) {
        if (carried == null) {
            return List.of();
        }
        List<StageBlueprintEntity.CarriedTaskEntity> capped = new ArrayList<>();
        for (StageBlueprintEntity.CarriedTaskEntity task : carried) {
            if (capped.size() >= limit) {
                break;
            }
            if (task == null || StringUtils.isBlank(task.getContent())) {
                continue;
            }
            String status = StringUtils.defaultIfBlank(task.getStatus(), "进行中");
            capped.add(new StageBlueprintEntity.CarriedTaskEntity(task.getContent().trim(), status.trim(),
                    StringUtils.trimToNull(task.getNote())));
        }
        return capped;
    }

    private int phaseRank(String phase) {
        if (phase == null) return 0;
        return switch (phase.toUpperCase()) {
            case "PREPARATION" -> 1;
            case "ESCALATION" -> 2;
            case "WAR" -> 3;
            case "RESOLUTION" -> 4;
            case "EPILOGUE" -> 5;
            default -> 0;
        };
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

}
