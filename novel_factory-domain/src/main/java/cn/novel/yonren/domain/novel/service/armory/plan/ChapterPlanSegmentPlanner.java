package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.llm.PlanBranchService;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.quality.ForeshadowLedgerPressurePolicy;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 段计划规划器：一次规划一段（startChapter..endChapter）的章节计划，是惰性分段规划的执行体。
 *
 * <p><b>为什么分段规划</b>：批次开头一次性规划整批会让后段计划基于过旧的记忆——
 * 写手已经写出了新的事实，而计划还停在批次开始时的设想。改为推进到段边界时用**最新记忆**
 * 规划下一段（见 {@code ChapterWorker#planNextSegment}），后段计划才不陈旧。
 *
 * <p><b>全流程</b>（{@link #planSegment}）：
 * <ol>
 *   <li><b>调用与降级</b>：{@link #invokeWithFallback} 解析失败重试至 {@link #MAX_ATTEMPTS} 次；
 *       分支推演（{@code plan.branches>1}）时多版生成、自评风险后择优；全败抛 AppException
 *       （计划是整批的地基，缺失无法继续——与蓝图/卷的 fail-soft 不同）。</li>
 *   <li><b>编号校正与结构校验</b>：模型输出段内编号（从 1 起）时平移为全局编号；
 *       {@code validateSegmentStructure} 校验条数、连续性、标题齐备。</li>
 *   <li><b>软闸门 + 一次带反馈重规划</b>：主线推进、对话承载等闸门违规时，把违规原文回注给
 *       规划模型让它自己修（一次机会）；仍不过则**不阻断**，把遗留违规经
 *       {@link PlannedSegment#residualGateIssues} 交给下游注入正文 prompt 作硬约束。</li>
 * </ol>
 *
 * <p><b>失败哲学</b>：结构问题（条数/编号）硬失败——半张计划比没有更危险，会让逐章循环取到错位条目；
 * 内容问题（推进弱、对白少）软处理——宁可这一段推进弱一点，也不要因规划僵持让整批失败。
 */
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

    /** 规划结果：raw 供生成记录复盘（rawResult），plan 已完成编号平移与结构校验；
     *  residualGateIssues 为二次重规划仍未通过的遗留违规（非空时下游注入正文 prompt 作硬约束） */
    public record PlannedSegment(String raw, ChapterPlanAggregate plan, String residualGateIssues) {
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
        return planSegment(storyVO, ctx, prompt, startChapter, endChapter, blueprint, rosterNames,
                usedPromptMap, null, null, null);
    }

    /**
     * 带伏笔账数据的重载：伏笔净增闸门（高压期）需要历史摘要与结算台账来判定在途水位。
     *
     * <p>与 {@code rosterNames} 同款——都由调用方从上下文提取后显式传入，规划器不依赖上下文对象，
     * 便于单测直接构造。两个参数可为 null（无数据时该闸门按"非高压期"跳过，行为与引入前一致）。
     */
    public PlannedSegment planSegment(StoryVO storyVO, PromptContext ctx, String prompt,
                                      int startChapter, int endChapter,
                                      StageBlueprintEntity blueprint,
                                      List<String> rosterNames,
                                      Map<String, String> usedPromptMap,
                                      List<ChapterSummaryEntity> summariesForPressure,
                                      List<ForeshadowSettlementEntity> settlementsForPressure) {
        return planSegment(storyVO, ctx, prompt, startChapter, endChapter, blueprint, rosterNames,
                usedPromptMap, summariesForPressure, settlementsForPressure, null);
    }

    /**
     * 带作业载体的重载：规划期每两次模型调用之间检查取消/熔断信号。
     *
     * <p>为什么需要：本方法一次调用最多串起 9 次 LLM 请求（分支推演 3 次 + 按方向生成 2 次 +
     * 单稿降级重试 2 次 + 带反馈重规划 2 次），单次请求可达数分钟。入口处的一次检查
     * （{@code CallChapterPlanLlmNode}）挡不住这条内部链路——实测用户点取消后仍等了约 8 分钟才停。
     * {@code job} 为 null（同步调试路径）时全部检查跳过，行为与引入前一致。
     */
    public PlannedSegment planSegment(StoryVO storyVO, PromptContext ctx, String prompt,
                                      int startChapter, int endChapter,
                                      StageBlueprintEntity blueprint,
                                      List<String> rosterNames,
                                      Map<String, String> usedPromptMap,
                                      List<ChapterSummaryEntity> summariesForPressure,
                                      List<ForeshadowSettlementEntity> settlementsForPressure,
                                      GenerationJob job) {
        int expected = endChapter - startChapter + 1;
        String label = "第" + startChapter + "-" + endChapter + "章段";

        PlannedSegment parsed = invokeWithFallback(storyVO, ctx, prompt, label, expected, usedPromptMap, true, job);
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
        // 时间推进单调性（2026-10-26）：这条规则此前只写在提示词里（"全段 timeAdvance 必须单调递增"），
        // 没有任何执行通道——与"跳月吞线""伏笔净增"两闸门引入前的状态相同：写在 prompt 里的规则
        // 不校验就等于没有。实测某段 5 章计划合计推进 59 天、正文只走 16 天，即靠这条缺口放行。
        String timeOrderIssue = TimeAdvanceChecks.validateMonotonic(parsed.plan().getChapters());
        if (timeOrderIssue != null) {
            timeAdvanceIssue = timeAdvanceIssue == null
                    ? timeOrderIssue : timeAdvanceIssue + "；" + timeOrderIssue;
        }
        // 跨章场景重演：与前两项正交——那两项管"推进说得对不对"，本项管"同一件事是不是被说了两遍"。
        // 实测逃逸：相隔两章的同一场景（第96/99章）、以及在共用原文后追加一句子项（第98/99章，
        // 而"档位原文之后附本章独有子项"恰是本项目 prompt 自己建议的写法）
        String sceneReplayIssue = ChapterPlanChecks.validateSceneReplay(parsed.plan().getChapters(), suspenseLadder);
        // 追进度跳接校验：蓝图挂 pacing 即本段需要时间跳跃——只写在散文里规划层永远不执行，
        // 末章 timeAdvance 必须显式推进到预算年份才算跳了
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
        // 伏笔净增闸门（高压期）：压力块在规划 prompt 里给了"净增上限 0"的**警告**，
        // 但警告不是执法——实测同一份计划里压力块写明收线优先，计划仍在"埋设第N章长线"。
        // 本项把那条要求变成可驳回的结构校验（与其它规划闸门同款：带反馈重规划一次，二次放行）。
        // 低压期本项恒为 null（只治"账已满还在铺新线"）。
        String netGrowthIssue = ForeshadowLedgerPressurePolicy.validatePlanNetGrowth(
                parsed.plan().getChapters(), summariesForPressure,
                ForeshadowLedgerPressurePolicy.voidedOf(settlementsForPressure));
        if (netGrowthIssue != null) {
            log.warn("{}伏笔净增闸门未通过（高压期）：{}", label, netGrowthIssue);
        }
        // 跳月不得吞线（2026-10-19）：一次跨 ≥1 个月的章节必须交代已铺开的待收线索。
        // 实测事故：188/190 章两次跳月把"苏家观察员""Vossner 座谈"两条铺了九章的线整段吞掉，
        // 读者视角是"刚铺就断"。待收线索取上一段遗留、尚未回收的伏笔内容。
        List<String> openThreads = openThreadsOf(summariesForPressure, startChapter);
        String jumpSwallowIssue = ChapterPlanChecks.validateJumpDoesNotSwallowThreads(
                parsed.plan().getChapters(), openThreads);
        if (jumpSwallowIssue != null) {
            log.warn("{}跳月吞线闸门未通过：{}", label, jumpSwallowIssue);
        }
        if (ladderIssue == null && mainLineIssue == null && timeAdvanceIssue == null
                && sceneReplayIssue == null && pacingIssue == null && dialogueIssue == null
                && netGrowthIssue == null && jumpSwallowIssue == null) {
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
        // 单调性违规走自己的修正指令（差异化反馈，而非复用"缺声明"的模板）：
        // 两类问题的修法不同——缺 timeAdvance 要"补声明"，倒退要"改日期/写法"，
        // 混用模板会让模型对着错误指令改
        if (timeOrderIssue != null) {
            feedback.append(TimeAdvanceChecks.monotonicFeedback(timeOrderIssue));
        }
        if (pacingIssue != null) {
            feedback.append(ChapterPlanChecks.timeAdvancePacingFeedback(pacingBudgetYear));
        }
        if (sceneReplayIssue != null) {
            feedback.append(ChapterPlanChecks.sceneReplayFeedback(sceneReplayIssue));
        }
        if (dialogueIssue != null) {
            feedback.append(ChapterPlanChecks.dialogueCarriageFeedback(dialogueIssue));
        }
        if (netGrowthIssue != null) {
            feedback.append(ForeshadowLedgerPressurePolicy.netGrowthFeedback(netGrowthIssue));
        }
        if (jumpSwallowIssue != null) {
            feedback.append(ChapterPlanChecks.jumpSwallowFeedback(jumpSwallowIssue));
        }
        log.warn("{}推进校验未通过，带违规信息重新规划一次：{}｜{}｜{}｜{}｜{}｜{}", label,
                ladderIssue == null ? "-" : ladderIssue,
                mainLineIssue == null ? "-" : mainLineIssue,
                timeAdvanceIssue == null ? "-" : timeAdvanceIssue,
                sceneReplayIssue == null ? "-" : sceneReplayIssue,
                dialogueIssue == null ? "-" : dialogueIssue,
                netGrowthIssue == null ? "-" : netGrowthIssue);
        PlannedSegment retry = invokeWithFallback(storyVO, ctx,
                prompt + feedback, label, expected, usedPromptMap, false, job);
        ChapterPlanChecks.normalizeChapterNumbers(retry.plan(), startChapter - 1);
        ChapterPlanChecks.validateSegmentStructure(retry.plan().getChapters(), expected, startChapter);
        String stillBadLadder = ChapterPlanChecks.validateSuspenseAdvance(retry.plan().getChapters(), suspenseLadder);
        String stillBadMainLine = ChapterPlanChecks.validateMainLineAdvance(retry.plan().getChapters(), mainLine);
        String stillBadTimeAdvance = ChapterPlanChecks.validateTimeAdvance(retry.plan().getChapters());
        String stillBadTimeOrder = TimeAdvanceChecks.validateMonotonic(retry.plan().getChapters());
        if (stillBadTimeOrder != null) {
            stillBadTimeAdvance = stillBadTimeAdvance == null
                    ? stillBadTimeOrder : stillBadTimeAdvance + "；" + stillBadTimeOrder;
        }
        String stillBadSceneReplay = ChapterPlanChecks.validateSceneReplay(retry.plan().getChapters(), suspenseLadder);
        String stillBadDialogue = ChapterPlanChecks.validateDialogueCarriage(retry.plan().getChapters(), rosterNames);
        String stillBadPacing = jumpRequired
                ? ChapterPlanChecks.validateTimeAdvancePacing(retry.plan().getChapters(), pacingBudgetYear) : null;
        String stillBadNetGrowth = ForeshadowLedgerPressurePolicy.validatePlanNetGrowth(
                retry.plan().getChapters(), summariesForPressure,
                ForeshadowLedgerPressurePolicy.voidedOf(settlementsForPressure));
        String stillBadJumpSwallow = ChapterPlanChecks.validateJumpDoesNotSwallowThreads(
                retry.plan().getChapters(), openThreads);
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
                || stillBadSceneReplay != null || stillBadDialogue != null || stillBadNetGrowth != null
                || stillBadJumpSwallow != null) {
            // 二次仍不通过 → 告警放行：宁可这一段推进弱一点，也不要因规划僵持让整批失败。
            // 该情形会被体检的 suspenseAdvanceRate / suspenseHold 暴露（观测层看得见），不属静默吞掉
            // （跳接段例外：上面已 fail-closed，走不到这里）
            log.warn("{}推进校验二次未通过，放行本次计划（已重规划一次）：{}｜{}｜{}｜{}｜{}｜{}", label,
                    stillBadLadder == null ? "-" : stillBadLadder,
                    stillBadMainLine == null ? "-" : stillBadMainLine,
                    stillBadTimeAdvance == null ? "-" : stillBadTimeAdvance,
                    stillBadSceneReplay == null ? "-" : stillBadSceneReplay,
                    stillBadDialogue == null ? "-" : stillBadDialogue,
                    stillBadNetGrowth == null ? "-" : stillBadNetGrowth);
            // 遗留违规随规划结果下行（2026-10-13）：放行不等于问题消失——同一件事被排给两章、
            // 档位倒退这类违规若不告诉写手，正文侧只会在残缺契约上自由发挥（实测第 136 章：
            // 计划闸门报了"136/137 事件重合"仍放行，写手把重叠事件写完就没料，首稿 87 字）。
            // 正文 prompt 注入后，写手至少知道"哪些推进必须本章独有、哪些不得再写"。
            StringBuilder residual = new StringBuilder();
            if (stillBadLadder != null) {
                residual.append(ChapterPlanChecks.suspenseFeedback(stillBadLadder));
            }
            if (stillBadMainLine != null) {
                residual.append(ChapterPlanChecks.mainLineFeedback(stillBadMainLine));
            }
            if (stillBadTimeAdvance != null) {
                residual.append(ChapterPlanChecks.timeAdvanceFeedback(stillBadTimeAdvance));
            }
            if (stillBadTimeOrder != null) {
                residual.append(TimeAdvanceChecks.monotonicFeedback(stillBadTimeOrder));
            }
            if (stillBadSceneReplay != null) {
                residual.append(ChapterPlanChecks.sceneReplayFeedback(stillBadSceneReplay));
            }
            if (stillBadDialogue != null) {
                residual.append(ChapterPlanChecks.dialogueCarriageFeedback(stillBadDialogue));
            }
            if (stillBadNetGrowth != null) {
                residual.append(ForeshadowLedgerPressurePolicy.netGrowthFeedback(stillBadNetGrowth));
            }
            if (stillBadJumpSwallow != null) {
                residual.append(ChapterPlanChecks.jumpSwallowFeedback(stillBadJumpSwallow));
            }
            return new PlannedSegment(retry.raw(), retry.plan(), residual.toString());
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
                                              Map<String, String> usedPromptMap, boolean branchExploration,
                                              GenerationJob job) {
        if (branchExploration && planBranchService.enabled()) {
            String branched = planBranchService.exploreAndPick(storyVO, ctx, prompt, label, usedPromptMap, job);
            if (branched != null) {
                PlannedSegment branchedPlan = invokeWithDirection(storyVO, ctx, prompt,
                        expectedChapterCount, branched, usedPromptMap, job);
                if (branchedPlan != null) {
                    return branchedPlan;
                }
            }
            log.warn("{}分支推演未产出可用计划，回退单稿规划", label);
        }
        Integer lastActual = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // 每轮调用前检查取消/熔断：一轮失败后重试前是自然的检查点，置位即停，不再烧下一次请求
            throwIfCancelled(job, label, attempt, MAX_ATTEMPTS);
            String callPrompt = attempt > 1 && lastActual != null
                    ? prompt + countCorrection(lastActual, expectedChapterCount) : prompt;
            String raw = llmInvokeService.invoke(storyVO, PromptScene.CHAPTER_PLAN, ctx, callPrompt, usedPromptMap);
            PlanAttempt parsed = parsePlanExpecting(raw, expectedChapterCount, label);
            if (parsed.plan() != null) {
                return new PlannedSegment(raw, parsed.plan(), null);
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
                                               Map<String, String> usedPromptMap, GenerationJob job) {
        String directionPrompt = prompt + "\n\n【已选规划方向】严格按此方向细化制定本段章节计划（保持原有 JSON schema 与输出格式不变）：\n"
                + directionRaw;
        Integer lastActual = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throwIfCancelled(job, "按方向生成", attempt, MAX_ATTEMPTS);
            String callPrompt = attempt > 1 && lastActual != null
                    ? directionPrompt + countCorrection(lastActual, expectedChapterCount) : directionPrompt;
            String raw = llmInvokeService.invoke(storyVO, PromptScene.CHAPTER_PLAN, ctx, callPrompt, usedPromptMap);
            PlanAttempt parsed = parsePlanExpecting(raw, expectedChapterCount, "按方向生成");
            if (parsed.plan() != null) {
                return new PlannedSegment(raw, parsed.plan(), null);
            }
            lastActual = parsed.actual();
            log.warn("按方向生成章节计划第 {}/{} 次输出无法通过解析或章数校验{}", attempt, MAX_ATTEMPTS,
                    attempt < MAX_ATTEMPTS ? "，将重试" : "");
        }
        return null;
    }

    /**
     * 规划期协作取消/熔断检查：置位即抛异常，由作业层 catch 按 CANCELLED 收敛终态
     * （与 {@code CallChapterPlanLlmNode} / {@code planNextSegment} 同款语义）。
     *
     * <p>抛在重试轮次**头部**而非请求之后：已发出的一次请求无法撤回，但下一轮不再发。
     * 这不是"当前章做完再停"，规划期尚未产出任何章节，停下就是零损失。
     */
    private static void throwIfCancelled(GenerationJob job, String label, int attempt, int maxAttempts) {
        if (job == null) {
            return;
        }
        if (job.isCancelRequested()) {
            log.info("{}规划第 {}/{} 次调用前检测到取消请求，停止规划（已完成章节均已落盘，可 resume 续写）",
                    label, attempt, maxAttempts);
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "作业在规划期已请求取消，停止规划（已完成章节不受影响，可 resume 续写）");
        }
        if (job.isBudgetExceeded()) {
            log.info("{}规划第 {}/{} 次调用前检测到预算熔断，停止规划（已完成章节均已落盘，可 resume 续写）",
                    label, attempt, maxAttempts);
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "作业在规划期已触发预算熔断，停止规划（已完成章节不受影响，可 resume 续写）");
        }
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

    /**
     * **上一段遗留的待收线索**：截止 {@code beforeChapter} 之前已埋、尚未回收的伏笔内容。
     *
     * <p>供"跳月不得吞线"闸门使用——跳月章必须交代的正是这些线。
     * 只取最近 {@value #OPEN_THREADS_LOOKBACK} 条（按埋设章倒序）：更早的线要么已进长线在养、
     * 要么已在阶段出口被裁决，不该由一次跳月负责交代；不设上限会让跳月章被要求交代几十条，
     * 闸门变成"永远过不了"。
     */
    private static List<String> openThreadsOf(List<ChapterSummaryEntity> summaries, int beforeChapter) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> resolved = new java.util.HashSet<>();
        for (ChapterSummaryEntity s : summaries) {
            if (s == null || s.getForeshadowingResolved() == null) {
                continue;
            }
            for (String r : s.getForeshadowingResolved()) {
                if (StringUtils.isNotBlank(r)) {
                    resolved.add(r.trim());
                }
            }
        }
        List<String> threads = new ArrayList<>();
        // 从新到旧遍历，只收本段开始前埋下的
        for (int i = summaries.size() - 1; i >= 0 && threads.size() < OPEN_THREADS_LOOKBACK; i--) {
            ChapterSummaryEntity s = summaries.get(i);
            if (s == null || s.getChapterNo() == null || s.getChapterNo() >= beforeChapter
                    || s.getForeshadowingNew() == null) {
                continue;
            }
            for (String item : s.getForeshadowingNew()) {
                if (StringUtils.isBlank(item)) {
                    continue;
                }
                String content = item.trim();
                boolean alreadyResolved = resolved.stream()
                        .anyMatch(r -> r.equals(content) || r.contains(content) || content.contains(r));
                if (!alreadyResolved && !threads.contains(content)) {
                    threads.add(content);
                    if (threads.size() >= OPEN_THREADS_LOOKBACK) {
                        break;
                    }
                }
            }
        }
        return threads;
    }

    /** 跳月闸门考察的待收线索条数上限：太多会让闸门永远过不了 */
    private static final int OPEN_THREADS_LOOKBACK = 5;

}
