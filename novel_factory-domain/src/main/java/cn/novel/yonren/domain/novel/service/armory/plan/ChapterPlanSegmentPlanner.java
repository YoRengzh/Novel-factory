package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.llm.PlanBranchService;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChapterPlanSegmentPlanner {

    /**
     * 计划输出 schema 复杂（嵌套数组+枚举），格式抖动概率高于正文/摘要，重试上限 2 次
     */
    static final int MAX_ATTEMPTS = 2;

    private final LlmInvokeService llmInvokeService;

    private final PlanBranchService planBranchService;

    /** 规划结果：raw 供生成记录复盘（rawResult），plan 已完成编号平移与结构校验 */
    public record PlannedSegment(String raw, ChapterPlanAggregate plan) {
    }

    /**
     * 规划一段（startChapter..endChapter）：全流程见类注释。全败抛 AppException。
     *
     * @param blueprint   该段生效的阶段蓝图（可为 null=无蓝图模式，闸门按"无档位表"口径跳过）
     * @param rosterNames 角色名册（角色账本姓名，调用方由摘要构建；可空）——
     *                    对话承载闸门"事件里是否出现两个已知人物"的人名来源；
     *                    空名册时降级为仅按本章 characters 判定，池仍不足则人名/占比两条跳过
     */
    public PlannedSegment planSegment(StoryVO storyVO, PromptContext ctx, String prompt,
                                      int startChapter, int endChapter,
                                      StageBlueprintEntity blueprint,
                                      List<String> rosterNames,
                                      Map<String, String> usedPromptMap) {
        int expected = endChapter - startChapter + 1;
        String label = "第" + startChapter + "-" + endChapter + "章段";

        PlannedSegment parsed = invokeWithFallback(storyVO, ctx, prompt, label, expected, usedPromptMap, true);
        // 段内编号校正：模型输出段内编号（从 1 起）时平移为全局编号
        ChapterPlanChecks.normalizeChapterNumbers(parsed.plan(), startChapter - 1);
        ChapterPlanChecks.validateSegmentStructure(parsed.plan().getChapters(), expected, startChapter);

        // 主线推进闸门 + 一次带反馈重规划：把违规原文回注给规划模型让它自己修，
        // 而不是直接抛异常（那等于把整批交给下一次随机采样），也不静默放行（那等于没有闸门）。
        // 校验只做档位下标的序比较，档位表由蓝图针对本书生成，因此换任何题材都成立
        List<String> suspenseLadder = blueprint == null ? null : blueprint.getSuspenseLadder();
        if (!ChapterPlanChecks.hasUsableLadder(blueprint)) {
            // 留痕：静默跳过与"跑了但没触发"事后无法区分（无蓝图模式/老故事属已知降级）
            // 注意：档位表不可用只跳过**档位序比较**；档位描述去重不依赖档位表，仍会执行（见 validateSuspenseAdvance）
            log.info("{}主线推进闸门：无可用档位表，跳过档位序比较（档位描述去重仍然执行）", label);
        } else {
            log.info("{}主线推进闸门启用：档位表 {} 档，校验 {} 章", label, suspenseLadder.size(), expected);
        }
        // 章级主线推进：与档位是两个维度——档位答"走到第几格"（纵向），
        // 本项答"这一章主线做了什么"（横向）。档位只有 3-6 档覆盖整个阶段，
        // 多章共用同一档是常态，故横向维度必须独立校验。
        List<StageBlueprintEntity.MainLineBeat> mainLine =
                blueprint == null ? null : blueprint.getMainLineByChapter();
        if (mainLine == null || mainLine.isEmpty()) {
            log.info("{}章级主线推进闸门跳过：蓝图未给出 mainLineByChapter"
                    + "（老数据或补采失败，属已知降级而非静默）", label);
        }
        String ladderIssue = ChapterPlanChecks.validateSuspenseAdvance(parsed.plan().getChapters(), suspenseLadder);
        String mainLineIssue = ChapterPlanChecks.validateMainLineAdvance(parsed.plan().getChapters(), mainLine);
        String timeAdvanceIssue = ChapterPlanChecks.validateTimeAdvance(parsed.plan().getChapters());
        Integer pacingBudgetYear = blueprint == null ? null : blueprint.getPacingBudgetStartYear();
        boolean jumpRequired = pacingBudgetYear != null
                && blueprint.getPacingLagYears() != null && blueprint.getPacingLagYears() >= 1;
        String pacingIssue = jumpRequired
                ? ChapterPlanChecks.validateTimeAdvancePacing(parsed.plan().getChapters(), pacingBudgetYear)
                : null;
        // 对话承载闸门：把「有没有戏可演」从散文规则变成结构契约（dialogueScenes 声明 + 逐字比对 + 人名计数）。
        // 第 105 章 5 条关键事件 0 条对话承载照样放行，就是这条规则没有执法通道的直接后果
        log.info("{}对话承载闸门启用：校验 {} 章（non-transition ≥{} / transition ≥{}；账本名册 {} 名）",
                label, expected, ChapterPlanChecks.DIALOGUE_SCENES_MIN,
                ChapterPlanChecks.DIALOGUE_SCENES_MIN_TRANSITION,
                rosterNames == null ? 0 : rosterNames.size());
        String dialogueIssue = ChapterPlanChecks.validateDialogueCarriage(parsed.plan().getChapters(), rosterNames);
        if (ladderIssue == null && mainLineIssue == null && timeAdvanceIssue == null
                && pacingIssue == null && dialogueIssue == null) {
            log.info("{}主线/章级推进闸门通过（档位表{}；章级推进 {} 条{}）", label,
                    ChapterPlanChecks.hasUsableLadder(blueprint) ? " " + suspenseLadder.size() + " 档" : "不可用",
                    mainLine == null ? 0 : mainLine.size(),
                    jumpRequired ? "；追进度跳接校验通过" : "");
            return parsed;
        }
        // 两类违规各自带**差异化修正指令** 教训：反馈不点名就等于让模型原样重生成一遍）
        StringBuilder feedback = new StringBuilder();
        if (ladderIssue != null) {
            feedback.append(ChapterPlanChecks.suspenseFeedback(ladderIssue));
        }
        if (mainLineIssue != null) {
            feedback.append(ChapterPlanChecks.mainLineFeedback(mainLineIssue));
        }
        if (timeAdvanceIssue != null) {
            feedback.append(ChapterPlanChecks.timeAdvanceFeedback(timeAdvanceIssue));
        }
        if (pacingIssue != null) {
            feedback.append(ChapterPlanChecks.timeAdvancePacingFeedback(pacingBudgetYear));
        }
        if (dialogueIssue != null) {
            feedback.append(ChapterPlanChecks.dialogueCarriageFeedback(dialogueIssue));
        }
        log.warn("{}推进校验未通过，带违规信息重新规划一次：{}｜{}｜{}｜{}", label,
                ladderIssue == null ? "-" : ladderIssue,
                mainLineIssue == null ? "-" : mainLineIssue,
                timeAdvanceIssue == null ? "-" : timeAdvanceIssue,
                dialogueIssue == null ? "-" : dialogueIssue);
        PlannedSegment retry = invokeWithFallback(storyVO, ctx,
                prompt + feedback, label, expected, usedPromptMap, false);
        ChapterPlanChecks.normalizeChapterNumbers(retry.plan(), startChapter - 1);
        ChapterPlanChecks.validateSegmentStructure(retry.plan().getChapters(), expected, startChapter);
        String stillBadLadder = ChapterPlanChecks.validateSuspenseAdvance(retry.plan().getChapters(), suspenseLadder);
        String stillBadMainLine = ChapterPlanChecks.validateMainLineAdvance(retry.plan().getChapters(), mainLine);
        String stillBadTimeAdvance = ChapterPlanChecks.validateTimeAdvance(retry.plan().getChapters());
        String stillBadPacing = jumpRequired
                ? ChapterPlanChecks.validateTimeAdvancePacing(retry.plan().getChapters(), pacingBudgetYear) : null;
        String stillBadDialogue = ChapterPlanChecks.validateDialogueCarriage(retry.plan().getChapters(), rosterNames);
        if (jumpRequired && (stillBadTimeAdvance != null || stillBadPacing != null)) {
            // 跳接段 fail-closed：放行等于整批继续在旧时间线上空转（实测：整批都在清偿旧债，
            // 大纲声明的时间跳跃被无视）。时间跳跃无法靠事后体检补救——体检只能报警，打回人工才是唯一出口
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    label + "追进度跳接两次重规划仍未通过时间校验（大纲预算 " + pacingBudgetYear
                            + " 年，滞后 " + blueprint.getPacingLagYears() + " 年）。"
                            + "为防止整批在旧时间线空转，本批终止：请在人工裁决中显式声明时间跳跃，"
                            + "或调整大纲段时间标记后重试");
        }
        if (stillBadLadder != null || stillBadMainLine != null || stillBadTimeAdvance != null
                || stillBadDialogue != null) {
            // 二次仍不通过 → 告警放行：宁可这一段推进弱一点，也不要因规划僵持让整批失败。
            // 该情形会被体检的 suspenseAdvanceRate / suspenseHold 暴露（观测层看得见），不属静默吞掉
            // （跳接段例外：上面已 fail-closed，走不到这里）
            log.warn("{}推进校验二次未通过，放行本次计划（已重规划一次）：{}｜{}｜{}｜{}", label,
                    stillBadLadder == null ? "-" : stillBadLadder,
                    stillBadMainLine == null ? "-" : stillBadMainLine,
                    stillBadTimeAdvance == null ? "-" : stillBadTimeAdvance,
                    stillBadDialogue == null ? "-" : stillBadDialogue);
        } else {
            log.info("{}推进校验：重规划后通过", label);
        }
        return retry;
    }

    /**
     * 单段调用 + 三级降级闭环：分支推演（若启用）→ 稳健/进取两版出方向建议 → 评审择优方向 →
     * 按方向生成正式计划；分支/按方向生成全败时静默回退单稿重试路径
     */
    private PlannedSegment invokeWithFallback(StoryVO storyVO, PromptContext ctx,
                                              String prompt, String label, int expectedChapterCount,
                                              Map<String, String> usedPromptMap, boolean branchExploration) {
        if (branchExploration && planBranchService.enabled()) {
            String branched = planBranchService.exploreAndPick(storyVO, ctx, prompt, label, usedPromptMap);
            if (branched != null) {
                PlannedSegment branchedPlan = invokeWithDirection(storyVO, ctx, prompt,
                        expectedChapterCount, branched, usedPromptMap);
                if (branchedPlan != null) {
                    return branchedPlan;
                }
            }
            log.warn("{}分支推演未产出可用计划，回退单稿规划", label);
        }
        Integer lastActual = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String callPrompt = attempt > 1 && lastActual != null
                    ? prompt + countCorrection(lastActual, expectedChapterCount) : prompt;
            String raw = llmInvokeService.invoke(storyVO, PromptScene.CHAPTER_PLAN, ctx, callPrompt, usedPromptMap);
            PlanAttempt parsed = parsePlanExpecting(raw, expectedChapterCount, label);
            if (parsed.plan() != null) {
                return new PlannedSegment(raw, parsed.plan());
            }
            lastActual = parsed.actual();
            log.warn("{}计划第 {}/{} 次输出无法通过解析或章数校验{}", label, attempt, MAX_ATTEMPTS,
                    attempt < MAX_ATTEMPTS ? "，将重试" : "");
        }
        throw new AppException(ResponseCode.UN_ERROR.getCode(),
                label + "计划生成与解析彻底失败（含修复后重解与一次重试），本批终止。"
                        + "原始输出已记录于日志，可调整章节数量或重试");
    }

    /**
     * 按分支推演的胜出方向生成正式章节计划：方向 JSON 注入 prompt 后走既有重试循环，
     * 且按预期章数校验（可解析但章数不符计入失败重试）。全败返回 null（调用方回退单稿路径）。
     */
    private PlannedSegment invokeWithDirection(StoryVO storyVO, PromptContext ctx,
                                               String prompt, int expectedChapterCount, String directionRaw,
                                               Map<String, String> usedPromptMap) {
        String directionPrompt = prompt + "\n\n【已选规划方向】严格按此方向细化制定本段章节计划（保持原有 JSON schema 与输出格式不变）：\n"
                + directionRaw;
        Integer lastActual = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String callPrompt = attempt > 1 && lastActual != null
                    ? directionPrompt + countCorrection(lastActual, expectedChapterCount) : directionPrompt;
            String raw = llmInvokeService.invoke(storyVO, PromptScene.CHAPTER_PLAN, ctx, callPrompt, usedPromptMap);
            PlanAttempt parsed = parsePlanExpecting(raw, expectedChapterCount, "按方向生成");
            if (parsed.plan() != null) {
                return new PlannedSegment(raw, parsed.plan());
            }
            lastActual = parsed.actual();
            log.warn("按方向生成章节计划第 {}/{} 次输出无法通过解析或章数校验{}", attempt, MAX_ATTEMPTS,
                    attempt < MAX_ATTEMPTS ? "，将重试" : "");
        }
        return null;
    }

    /**
     * 解析并按预期章数校验：JSON 可解析但 chapters 数量与预期不符时视为本轮失败（不进入既有
     * 修复分支，WARN 后留给下一轮重试）。模型常把"整段章号"（长区间心智）与"本批章数"
     * 混算，或被剧情中的'本批收束章'语义带偏提前截止，少章/多章是可自愈的高频故障，
     * 重试成本远低于整批终止。返回实际章数供下一轮注入纠错
     */
    private static PlanAttempt parsePlanExpecting(String raw, int expectedChapterCount, String scene) {
        ChapterPlanAggregate plan = ChapterPlanChecks.parseLenient(raw);
        if (plan == null) {
            return new PlanAttempt(null, -1);
        }
        int actual = plan.getChapters() == null ? 0 : plan.getChapters().size();
        if (actual != expectedChapterCount) {
            log.warn("{}计划输出可解析但章数不符（预期 {} 章，实际 {} 章），按本轮失败计入重试",
                    scene, expectedChapterCount, actual);
            return new PlanAttempt(null, actual);
        }
        return new PlanAttempt(plan, actual);
    }

    /** 章数不符重试时的纠错指令：把上轮实际输出与目标章数明确钉给模型，责令补足重出 */
    private static String countCorrection(int lastActual, int expected) {
        return "\n\n【上轮数量纠错，必须遵守】你上一次输出的章节计划只有 " + lastActual + " 章，"
                + "本批必须规划完整 " + expected + " 章（chapterNo 连续递增），把缺失章节补齐后重新输出完整 JSON，"
                + "严禁再次少章或多章——即使剧情已被编排为提前收束，也要规划满 " + expected + " 章。";
    }

    /** 解析尝试结果：plan=null 时 actual 携带实际章数（-1=不可解析）供下一轮纠错 */
    private record PlanAttempt(ChapterPlanAggregate plan, int actual) {
    }
}
