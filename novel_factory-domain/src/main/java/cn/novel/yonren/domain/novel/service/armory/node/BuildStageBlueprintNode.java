package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowScheduleService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowSettlementService;
import cn.novel.yonren.domain.novel.service.armory.memory.OutlineSegmentParser;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.memory.StageExitReviewService;
import cn.novel.yonren.domain.novel.service.armory.plan.StoryPacing;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.quality.SuspenseLadderPolicy;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段蓝图节点：滚动大纲的方向盘。
 * 批次开头判断本批是否跨过阶段边界（批次末章超出当前蓝图覆盖区间），
 * 是则逐阶段链式生成新版蓝图（每跨一个边界生成一版，直至覆盖批次末章），
 * 按"原始大纲 + 上一版蓝图 + 近章摘要 + 待回收伏笔"四件套生成并链式结转。
 * 蓝图是增强件——生成/解析失败按 fail-soft 处理，剩余章段以无蓝图（或旧蓝图）模式继续，不阻断计划生成。
 * 生成结果写入 DynamicContext，随 PersistChapterPlanNode 统一落盘 rolling-outline.json
 */
@Service
@Slf4j
public class BuildStageBlueprintNode extends AbstractArmorySupport {

    @Resource
    private BuildChapterPlanPromptNode buildChapterPlanPromptNode;

    @Resource
    private RollingOutlineService rollingOutlineService;

    @Resource
    private LlmInvokeService llmInvokeService;

    @Resource
    private ChapterMemoryService chapterMemoryService;

    @Resource
    private StageExitReviewService stageExitReviewService;

    @Resource
    private ForeshadowSettlementService foreshadowSettlementService;

    /** 阶段出口裁决判 DONE 的线要回写排期表（置 PAID），否则下一段仍会把它当欠账反复下令 */
    @Resource
    private ForeshadowScheduleService foreshadowScheduleService;

    @Resource
    private IStoryRepository storyRepository;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return buildChapterPlanPromptNode;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 BuildStageBlueprintNode - 阶段蓝图（滚动大纲）");
        prepareBlueprint(requestParameter, dynamicContext);
        return router(requestParameter, dynamicContext);
    }

    /**
     * 触发判断 + 逐阶段链式生成 + 写入上下文（包级可见供单测绕过树路由）。
     * 判断基准是本批末章：批次跨越多阶段边界时循环生成，直至蓝图链覆盖批次末章
     */
    void prepareBlueprint(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        List<StageBlueprintEntity> blueprints = dynamicContext.getStageBlueprints() != null
                ? dynamicContext.getStageBlueprints() : new ArrayList<>();
        int chapterOffset = dynamicContext.getChapterOffset();
        int nextChapterNo = chapterOffset + 1;
        int batchEnd = chapterOffset + requestParameter.getStoryContextEntity().getChapterCount();

        if (!rollingOutlineService.needsGeneration(blueprints, batchEnd)) {
            StageBlueprintEntity previous = rollingOutlineService.latestOf(blueprints);
            dynamicContext.setStageBlueprint(previous);
            log.info("阶段蓝图复用：第{}阶段（第{}-{}章），下一章 {}",
                    previous.getStageNo(), previous.getStartChapter(), previous.getEndChapter(), nextChapterNo);
            return;
        }

        // 批次跨边界：上一版终点之后逐阶段生成，直至覆盖批次末章
        while (rollingOutlineService.needsGeneration(blueprints, batchEnd)) {
            // 规划期协作取消/熔断检查（每轮迭代头部）：本节点链式生成可达 5-10 次 LLM 调用、
            // 数分钟量级，且发生在 CallChapterPlanLlmNode 的入口检查**之前**——
            // 缺了这里，"取消"在整个蓝图期不生效，用户按停止后仍继续烧 token。
            // 与 CallChapterPlanLlmNode / planNextSegment 同款语义：抛异常交由作业层按取消/熔断收敛终态
            GenerationJob planJob = dynamicContext == null ? null : dynamicContext.getJob();
            if (planJob != null && (planJob.isCancelRequested() || planJob.isBudgetExceeded())) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(),
                        "作业在阶段蓝图生成期已请求取消/触发熔断，停止规划（已完成章节不受影响，可 resume 续写）");
            }
            StageBlueprintEntity previous = rollingOutlineService.latestOf(blueprints);
            int windowStart = ( previous == null ? nextChapterNo : previous.getEndChapter() + 1 );
            // 失败自动重试一次：蓝图决定本批任务/退出条件/排期，
            // 解析失败静默降级的代价是整批在旧蓝图上滑行（排期原地踏步、新阶段任务与出口条件缺位），
            // 一次重试的成本（60s）远低于整批规划缺位——与段推进校验的"重规划一次"同款宽容度。
            StageBlueprintEntity blueprint = null;
            for (int attempt = 1; attempt <= 2 && blueprint == null; attempt++) {
                if (attempt > 1) {
                    log.warn("阶段蓝图生成/解析失败，自动重试一次（本批任务/退出条件/排期都挂在蓝图上，静默降级代价过高）");
                }
                blueprint = generateForWindow(requestParameter, dynamicContext, previous, windowStart, batchEnd);
            }
            if (blueprint == null) {
                // 蓝图是增强件：重试仍失败则停止链式生成，剩余章段回退上一版（或无蓝图），不阻断计划生成；
                // 规划缺位由批末体检的【阶段规划覆盖】条目显式点名（BatchHealthService#addStagePlanningCoverage），
                // 不再只留一条易被淹没的 WARN
                log.warn("阶段蓝图生成/解析失败（含重试），剩余章段以{}继续（fail-soft，不阻断计划生成）",
                        previous == null ? "无蓝图模式" : "上一版蓝图（第" + previous.getStageNo() + "阶段）");
                break;
            }
            if (blueprint.getEndChapter() == null || blueprint.getEndChapter() < windowStart) {
                // 防御：终点未能推进窗口起点时终止链式生成，避免同窗口无限重复
                log.warn("阶段蓝图终点 {} 未越过窗口起点 {}，终止链式生成", blueprint.getEndChapter(), windowStart);
                break;
            }
            blueprints.add(blueprint);
        }

        dynamicContext.setStageBlueprints(blueprints.isEmpty() ? null : blueprints);
        dynamicContext.setStageBlueprint(findCovering(blueprints, nextChapterNo));
    }

    /**
     * 为单个阶段窗口生成蓝图：组装四件套 prompt、调用模型、解析规整。
     * batchEnd 为本次批次末章（chapterOffset + chapterCount），是阶段终点的钳制上界——
     * 钳制目标与蓝图覆盖目标（needsGeneration 的判断基准）保持一致，保证链式生成必然终止
     */
    private StageBlueprintEntity generateForWindow(ArmoryCommandEntity requestParameter,
                                                   DefaultArmoryFactory.DynamicContext dynamicContext,
                                                   StageBlueprintEntity previous,
                                                   int windowStart,
                                                   int batchEnd) {
        // 上一阶段退出条件核验（外部审计，先于蓝图生成——核验结果进入生成 prompt 的结转指令）；
        // 核验失败整体回退模型自评结转（fail-soft）
        reviewPreviousExitConditions(requestParameter, dynamicContext, previous);

        // 上一阶段出口清账补办：阶段已完结但尚无结算记录（旧版本跨过边界/崩溃跳过）时补一次裁决，
        // VOID 出账 + RECOVER 注入本批规划 prompt（批次开头执行，先于蓝图生成 prompt 装配）
        settleMissedBreakerForeshadows(requestParameter, dynamicContext, previous);

        RollingOutlineService.StageWindow window = rollingOutlineService.nextWindow(previous, windowStart);
        // hardTotal（全书硬性完结上限）随 prompt 注入：预算前置收敛 + 到顶强制收官
        Integer hardTotal = dynamicContext.getMaxChapterCount();
        // 导演通道：作者创作要点置于 prompt 最顶部（不可裁剪）
        String prompt = cn.novel.yonren.domain.novel.service.armory.CreativeNotes
                .block(dynamicContext.getCreativeNotes())
                + rollingOutlineService.buildGenerationPrompt(
                requestParameter.getStoryContextEntity(), previous,
                dynamicContext.getChapterSummaries(),
                chapterMemoryService.buildBlueprintForeshadowList(dynamicContext.getChapterSummaries()),
                window, batchEnd, hardTotal,
                // 当前卷作为弧的全局方向锚（无卷退化为 null → 两段式，不注入卷方向锚）
                dynamicContext.getCurrentVolume());

        StoryContextEntity storyContext = requestParameter.getStoryContextEntity();
        PromptContext promptContext = PromptContext.builder()
                .theme(storyContext.getTheme())
                .style(storyContext.getStyle())
                .totalChapters(storyContext.getChapterCount())
                .build();

        Map<String, String> blueprintPrompts = new HashMap<>();
        String raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.STAGE_BLUEPRINT,
                promptContext, prompt, blueprintPrompts);
        recordBlueprintPrompts(dynamicContext, blueprintPrompts);

        StageBlueprintEntity blueprint = rollingOutlineService.parse(raw, previous, windowStart, batchEnd, hardTotal);
        if (blueprint == null) {
            return null;
        }

        // 卷归属机械化回填：不信任模型重算卷号/卷标题（模型只可自报卷内 arcNo/arcGoal）
        VolumeBlueprintEntity currentVolume = dynamicContext.getCurrentVolume();
        if (currentVolume != null) {
            blueprint.setVolumeNo(currentVolume.getVolumeNo());
            blueprint.setVolumeTitle(StringUtils.trimToNull(currentVolume.getTitle()));
        }

        // 未达成的退出条件机械化结转：不信任模型自评，结转清单缺失即补
        mergeUnmetExitConditions(previous, blueprint);
        // 结转任务机械兜底：模型漏掉的"进行中"任务原样补回（与上一条同款思路——
        // 提示词已渲染上一版结转清单，但仍不把可靠性押在一次服从上）。
        mergeMissingCarriedTasks(previous, blueprint);
        // 结转债务上限：跨段滚动的未完成任务超过硬上限的部分降级为背景清偿
        capCarriedTasks(blueprint);
        // 冻结线越权机械观测：prompt 已加"冻结线红线"（见
        // RollingOutlineService 第 3.1 条），此处兜底观测模型是否仍把冻结线排进了 tasks/exitConditions。
        warnFrozenLineViolations(previous, blueprint);

        // 悬念档位表补采：档位表是"主线推进闸门"的唯一标尺，但在完整蓝图 prompt 里
        // 它只是第 11 条要求——实测模型会在长 prompt 里**静默省略**这两个字段。缺了它，锚块不注入、
        // 校验直接跳过、指标不出：整条推进链路会一声不响地空转。
        //  不把可靠性押在一次服从上：缺了就**只问这两个字段**再补一次（短 prompt 服从率高），
        //    仍拿不到才告警——绝不静默放过。
        if (!SuspenseLadderPolicy.usable(blueprint.getSuspenseLadder())) {
            repairSuspenseLadder(requestParameter, dynamicContext, storyContext, promptContext, blueprint);
        }

        // 章级主线推进补采：与档位表同理——逐章数组放在长 prompt 里被省略的概率更高，
        // 缺了就**只问这一项**再补一次。档位表在补采之后才确定，所以本补采放在它之后，
        // 好把已定档位表一并喂进去（章级推进必须与档位相容，否则两张表会互相打架）。
        repairMainLine(requestParameter, dynamicContext, storyContext, promptContext, blueprint);
        // 章级推进线别纪律：标签缺失/暗线超配额/暗线连排（治"整段滑进悬疑线、学习线蒸发"，
        // 实测 106-110 段正是这样）——带反馈重问一次，仍不过 WARN 留痕（下游还有段计划对话承载闸门兜底）
        enforceMainLineDiscipline(requestParameter, dynamicContext, storyContext, promptContext, blueprint);

        // 伏笔兑现排期表补采：同为"缺了就只问这一项"的聚焦补采。
        // 放在最后——它要看阶段区间与 hardTotal，与档位/章级推进无依赖但同属"蓝图缺失字段"家族。
        repairForeshadowSchedule(requestParameter, dynamicContext, storyContext, promptContext, blueprint);

        log.info("阶段蓝图生成：第{}阶段（第{}-{}章），任务 {} 条，结转 {} 条",
                blueprint.getStageNo(), blueprint.getStartChapter(), blueprint.getEndChapter(),
                blueprint.getTasks() == null ? 0 : blueprint.getTasks().size(),
                blueprint.getCarriedTasks() == null ? 0 : blueprint.getCarriedTasks().size());
        // stageEnd 机械校验+回填
        // 声明 stageEndYear/Age，但实测会胡写（写出早于故事开局的年份）——
        // 不信任声明，以重锚预算为准校验回填（零成本、确定性，与卷区间的机械钳制同款思路）
        repairStageEndFromOutline(requestParameter, storyContext, dynamicContext.getChapterSummaries(),
                blueprint, previous);

        // 追进度机制（时间跳跃任务 + 结转重定基 + 跳接 fail-closed）已于 2026-10-11 退役（进度治理方案 A）：
        // 它连续多批未能把日历追回来，且与重锚方向相反——重锚后蓝图预算 = 正文实际时间 + 段时长，
        // "滞后"不再存在，跳跃无从谈起。段计划侧的跳接校验（ChapterPlanSegmentPlanner）保留为通用能力：
        // 仅当蓝图声明 pacing 字段时才触发，如今无人注入，自然休眠。
        return blueprint;
    }

    /** 结转债务硬上限：跨段滚动的未完成任务超过硬上限的部分降级为背景清偿，不再逼规划层专门造场景 */
    private static final int CARRIED_HARD_CAP = 8;

    private void capCarriedTasks(StageBlueprintEntity blueprint) {
        List<StageBlueprintEntity.CarriedTaskEntity> carried = blueprint.getCarriedTasks();
        if (carried == null || carried.size() <= CARRIED_HARD_CAP) {
            return;
        }
        int downgraded = 0;
        for (int i = CARRIED_HARD_CAP; i < carried.size(); i++) {
            StageBlueprintEntity.CarriedTaskEntity t = carried.get(i);
            if (t == null || t.getContent() == null || t.getContent().startsWith("【背景清偿")) {
                continue;
            }
            t.setContent("【背景清偿·一句带过】" + t.getContent());
            downgraded++;
        }
        if (downgraded > 0) {
            log.info("结转债务上限：{} 条超出硬上限 {}，降级为背景清偿（正文一句带过即可）",
                    downgraded, CARRIED_HARD_CAP);
        }
    }

    /**
     * stageEnd 机械校验+回填：12.1 要求模型从【进度对齐·大纲路标】声明
     * stageEndYear/stageEndAge，但实测会胡写（写出早于故事开局的年份）。
     * 下限 = **重锚预算**：大纲段时间预算按正文实际锚点整体平移后取值，
     * 而非大纲原预算——学校线里程碑保留顺序、随实际时间滑动，硬追原日历只会让蓝图声明
     * 永远跑到正文前面（实测第 111-120 段：原机制回填出 2011/14 岁，正文实际 2010/12 岁）。
     * 校验规则：stageEndYear 必须含 4 位年份且 ≥ 重锚预算、≥ 上一阶段声明（单调递增）；
     * stageEndAge 同理。任一不合格即按重锚预算回填——无锚点/大纲段无数值标记时保持声明原样，不编造。
     *
     */
    private void repairStageEndFromOutline(ArmoryCommandEntity requestParameter,
                                           StoryContextEntity storyContext,
                                           List<ChapterSummaryEntity> summaries,
                                           StageBlueprintEntity blueprint,
                                           StageBlueprintEntity previous) {
        if (blueprint == null || storyContext == null
                || StringUtils.isBlank(storyContext.getChapterGoal())) {
            return;
        }
        OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(storyContext.getChapterGoal());
        if (outline.isEmpty()) {
            return;
        }
        // 重锚平移量从段首所在段计算（与蓝图 prompt 的进度对齐块同口径）；无锚点时 shift=null → 不平移
        OutlineSegmentParser.OutlineSegment primary = outline.segmentFor(
                blueprint.getStartChapter() == null ? 0 : blueprint.getStartChapter());
        Integer shiftYears = StoryPacing.calendarShiftYears(primary, StoryPacing.latestAnchorYear(summaries));
        Integer anchorAge = StoryPacing.latestAnchorAge(summaries);
        Integer shiftAges = (primary != null && primary.ageStart() != null && anchorAge != null)
                ? primary.ageStart() - anchorAge : null;

        int stageEndNo = blueprint.getEndChapter() == null ? 0 : blueprint.getEndChapter();
        OutlineSegmentParser.OutlineSegment seg = outline.segmentFor(stageEndNo);
        if (seg == null && !outline.segments().isEmpty()) {
            seg = outline.segments().get(outline.segments().size() - 1);
        }
        if (seg == null || (seg.yearStart() == null && seg.ageStart() == null)) {
            return; // 大纲段无数值时间标记（境界纪年等）——不编造，保持模型声明
        }
        StoryPacing.SlidBudget slid = StoryPacing.slideSegment(seg, shiftYears, shiftAges);
        Integer declaredYear = extractYear(blueprint.getStageEndYear());
        int floorYear = slid.yearStart() == null ? 0 : slid.yearStart();
        Integer prevYear = previous == null || StringUtils.isBlank(previous.getStageEndYear())
                ? null : extractYear(previous.getStageEndYear());
        if (prevYear != null && prevYear > floorYear) {
            floorYear = prevYear; // 相邻阶段单调递增下限
        }
        if (declaredYear == null || declaredYear < floorYear) {
            // 回填值必须钳到下限：重锚预算是"平移后的段末年份"，它可能**低于上一阶段声明**
            Integer budgetYear = slid.yearEnd() != null ? slid.yearEnd() : slid.yearStart();
            int backfilledYear = Math.max(budgetYear == null ? floorYear : budgetYear, floorYear);
            String backfill = backfilledYear + "年";
            log.info("阶段蓝图 stageEndYear 不合格（声明 {}，重锚下限 {}），已按重锚预算回填为 {}",
                    declaredYear, floorYear, backfill);
            blueprint.setStageEndYear(backfill);
        }
        if (slid.ageStart() != null) {
            Integer declaredAge = extractAge(blueprint.getStageEndAge());
            int floorAge = slid.ageStart();
            // 年龄与年份同款单调下限：原实现只看段预算，不看上一阶段声明，
            // 于是 13 岁 → 12 岁同样能写进去（实测 stage13/14=13岁 → stage15/16=12岁）
            Integer prevAge = previous == null || StringUtils.isBlank(previous.getStageEndAge())
                    ? null : extractAge(previous.getStageEndAge());
            if (prevAge != null && prevAge > floorAge) {
                floorAge = prevAge;
            }
            if (declaredAge == null || declaredAge < floorAge) {
                Integer budgetAge = slid.ageEnd() != null ? slid.ageEnd() : slid.ageStart();
                int backfilledAge = Math.max(budgetAge == null ? floorAge : budgetAge, floorAge);
                String backfill = backfilledAge + "岁";
                log.info("阶段蓝图 stageEndAge 不合格（声明 {}，重锚下限 {}），已按重锚预算回填为 {}",
                        declaredAge, floorAge, backfill);
                blueprint.setStageEndAge(backfill);
            }
        }
    }

    private static Integer extractYear(String text) {
        return StoryPacing.parseYear(text);
    }

    /**
     * 从模型声明里取年龄。先走 {@link StoryPacing#parseAge} 整串口径（与体检的
     * stageEndAge 读取同源，"十三岁"这类中文声明也认、裸"4"也认）；整串不匹配时
     * 退回搜索串内第一个"数字+岁"（声明常带附加语境，如"13岁（初三）"）。
     * 早先只搜索阿拉伯数字+岁：模型写"十三岁"会被判"声明无效"而白白覆写掉有效值。
     */
    private static Integer extractAge(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Integer whole = StoryPacing.parseAge(text);
        if (whole != null) {
            return whole;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,3}|[一二三四五六七八九十]{1,3})\\s*岁").matcher(text);
        return m.find() ? StoryPacing.parseAge(m.group(1)) : null;
    }

    /**
     * **伏笔兑现排期表补采**：主 prompt 遗漏时补问一次，只问这一项。
     *
     * <p>三步：①聚焦补采拿到本阶段新排期 → ②与上一版结转的活线条目**机械合并去重**
     * （模型看不见上一版全貌，必然重复排）→ ③追加进 {@code dynamicContext.foreshadowSchedules} 并落盘。
     *
     * <p><b>质量校验（"至少 1 条跨阶段"）在这里判定</b>，不在 parse 里——
     * 它是补采质量问题而非格式问题：格式不合格整表退回，质量问题**重问一次**，
     * 仍不满足则**告警放行**（排期质量差不该让整批失败）。
     *
     * <p>fail-soft：拿不到排期表时不阻断规划——此时全链路行为与引入 P2b 前完全一致
     * （种子的 {@code scheduledPayoffChapter} 保持 null，清账过滤与漏收指标都不触发）。
     */
    private void repairForeshadowSchedule(ArmoryCommandEntity requestParameter,
                                          DefaultArmoryFactory.DynamicContext dynamicContext,
                                          StoryContextEntity storyContext, PromptContext promptContext,
                                          StageBlueprintEntity blueprint) {
        if (blueprint == null || blueprint.getStartChapter() == null || blueprint.getEndChapter() == null) {
            return;
        }
        int hardTotal = dynamicContext.getMaxChapterCount() == null
                ? blueprint.getEndChapter() : dynamicContext.getMaxChapterCount();
        List<ForeshadowScheduleEntity> schedules = dynamicContext.getForeshadowSchedules() != null
                ? dynamicContext.getForeshadowSchedules() : new ArrayList<>();
        // 幂等守卫：同一阶段已经有排期表就跳过（重跑同一阶段/检查点恢复后重入时不该重复补采）。
        // ⚠️ 与档位表/章级推进不同，排期表**不是** StageBlueprintEntity 的字段，主蓝图 prompt 产不出它，
        // 因此"补采"就是它的**常规产出路径**（每生成一版蓝图一次调用），而非常规路径的兜底。
        if (schedules.stream().anyMatch(x -> x != null
                && java.util.Objects.equals(x.getStageNo(), blueprint.getStageNo()))) {
            log.info("伏笔兑现排期表：第{}阶段已有排期，跳过补采（幂等）", blueprint.getStageNo());
            return;
        }
        List<ForeshadowScheduleEntity.ScheduleItem> carried =
                ForeshadowScheduleEntity.carriableItems(ForeshadowScheduleEntity.latestOf(schedules));

        List<ForeshadowScheduleEntity.ScheduleItem> fresh =
                askSchedule(requestParameter, dynamicContext, storyContext, promptContext, blueprint, hardTotal, carried);
        if (fresh == null) {
            log.warn("伏笔兑现排期补采未取得可用结果（第{}阶段）——本阶段不产生新排期，"
                    + "已有结转 {} 条继续生效；属已知降级而非静默", blueprint.getStageNo(), carried.size());
            return;
        }
        // ⚠️ 质量校验只在"还有后续章节"时进行：hardTotal <= 阶段末章 ⇒ 全书在本阶段收束，
        // 跨阶段要求**永远无法满足**，照查会让调用方空转重问一件不可能的事。
        boolean crossStagePossible = hardTotal > blueprint.getEndChapter();
        if (crossStagePossible && fresh.stream().noneMatch(i -> i.getPayoffChapter() != null
                && i.getPayoffChapter() > blueprint.getEndChapter())) {
            // 质量校验：全在本阶段内收 ⇒ 又回到"埋下去立刻兑现"，正是 P2 要治的病。重问一次。
            log.warn("伏笔排期补采质量不合格（第{}阶段）：没有任何一条跨阶段（payoffChapter 全部 ≤ {}），重问一次",
                    blueprint.getStageNo(), blueprint.getEndChapter());
            fresh = askSchedule(requestParameter, dynamicContext, storyContext, promptContext, blueprint, hardTotal, carried);
            if (fresh == null || fresh.stream().noneMatch(i -> i.getPayoffChapter() != null
                    && i.getPayoffChapter() > blueprint.getEndChapter())) {
                log.warn("伏笔排期补采二次仍无跨阶段条目（第{}阶段）——放行本次排期，"
                        + "该情形会让「本段内埋本段内收」继续存在，可经体检的伏笔跨度指标观测",
                        blueprint.getStageNo());
                if (fresh == null) {
                    return;
                }
            }
        }
        List<ForeshadowScheduleEntity.ScheduleItem> merged =
                rollingOutlineService.mergeScheduleItems(carried, fresh);
        schedules.add(ForeshadowScheduleEntity.builder()
                .stageNo(blueprint.getStageNo())
                .startChapter(blueprint.getStartChapter())
                .endChapter(blueprint.getEndChapter())
                .items(merged)
                .build());
        dynamicContext.setForeshadowSchedules(schedules);
        Path storyDir = dynamicContext.getStoryDir();
        if (storyDir != null) {
            try {
                storyRepository.writeForeshadowSchedule(storyDir, schedules);
            } catch (Exception e) {
                log.error("伏笔排期表落盘失败，本轮仅内存生效", e);
            }
        }
        long crossStage = merged.stream().filter(i -> i.getPayoffChapter() != null
                && i.getPayoffChapter() > blueprint.getEndChapter()).count();
        log.info("伏笔兑现排期表（第{}阶段）：新增 {} 条 + 结转 {} 条 = {} 条，其中跨阶段 {} 条",
                blueprint.getStageNo(), fresh.size(), carried.size(), merged.size(), crossStage);
    }

    /** 单次排期补采调用（含解析）——供"质量不合格重问一次"复用 */
    private List<ForeshadowScheduleEntity.ScheduleItem> askSchedule(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext,
            StoryContextEntity storyContext, PromptContext promptContext, StageBlueprintEntity blueprint,
            int hardTotal, List<ForeshadowScheduleEntity.ScheduleItem> carried) {
        String repairPrompt = rollingOutlineService.buildForeshadowScheduleRepairPrompt(
                storyContext, blueprint, hardTotal, carried, dynamicContext.getChapterSummaries());
        Map<String, String> repairPrompts = new HashMap<>();
        String raw;
        try {
            raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.STAGE_BLUEPRINT,
                    promptContext, repairPrompt, repairPrompts);
        } catch (Exception e) {
            log.warn("伏笔排期补采调用失败：{}", e.getMessage());
            return null;
        }
        recordBlueprintPrompts(dynamicContext, repairPrompts);
        RollingOutlineService.ForeshadowSchedulePatch patch =
                rollingOutlineService.parseForeshadowSchedulePatch(raw, blueprint, hardTotal);
        return patch == null ? null : patch.foreshadowSchedule();
    }

    /**
     * **章级主线推进补采**：主 prompt 遗漏 {@code mainLineByChapter} 时补问一次，且只问这一项。
     *
     * <p>与 {@link #repairSuspenseLadder} 同一套理由与形态：长 prompt 里的逐章数组会被静默省略，
     * 短聚焦 prompt 服从率高得多。**缺了两者的后果不同但同样静默**——
     * 缺档位表 ⇒ 纵向校验空转；缺章级推进 ⇒ 横向校验空转、段计划继续多章复用同一句描述。
     *
     * <p>补采仍拿不到时**必须告警**：否则"跑了但没生效"与"没跑"事后无法区分。
     * <b>不阻断规划</b>——本字段缺失只降级"章级推进校验"，不该让整批失败（fail-soft）。
     */
    private void repairMainLine(ArmoryCommandEntity requestParameter,
                                DefaultArmoryFactory.DynamicContext dynamicContext,
                                StoryContextEntity storyContext, PromptContext promptContext,
                                StageBlueprintEntity blueprint) {
        if (blueprint == null || blueprint.getStartChapter() == null) {
            return;
        }
        int end = RollingOutlineService.mainLineWindowEnd(blueprint);
        int expected = Math.max(1, end - blueprint.getStartChapter() + 1);
        // 触发口径取**宽松**（有内容就不补问），与档位表的 usable(ladder) 同款：
        // 补采只负责"把缺的字段问出来"，**覆盖是否完整交给下游校验**
        // （validateMainLineAdvance 会逐章报"未声明"，不会静默放过）。
        // 若这里改判"必须恰好覆盖排期窗"，同一份夹具在不同窗口的用例会反复触发补采，
        // 而补采本身是额外一次 LLM 调用——用严格口径换来的收益不抵成本。
        if (blueprint.getMainLineByChapter() != null && !blueprint.getMainLineByChapter().isEmpty()) {
            return;
        }
        log.warn("阶段蓝图未给出章级主线推进（第{}阶段，应覆盖第{}-{}章共 {} 条），触发聚焦补采",
                blueprint.getStageNo(), blueprint.getStartChapter(), end, expected);
        String repairPrompt = rollingOutlineService.buildMainLineRepairPrompt(storyContext, blueprint,
                dynamicContext.getChapterSummaries());
        Map<String, String> repairPrompts = new HashMap<>();
        String raw;
        try {
            raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.STAGE_BLUEPRINT,
                    promptContext, repairPrompt, repairPrompts);
        } catch (Exception e) {
            log.warn("章级主线推进补采调用失败，第{}阶段的章级推进校验将跳过：{}",
                    blueprint.getStageNo(), e.getMessage());
            return;
        }
        recordBlueprintPrompts(dynamicContext, repairPrompts);
        RollingOutlineService.MainLinePatch patch =
                rollingOutlineService.parseMainLinePatch(raw, blueprint);
        if (patch == null || patch.mainLineByChapter() == null || patch.mainLineByChapter().isEmpty()) {
            log.warn("章级主线推进补采仍未取得可用结果（第{}阶段）——本阶段的「章级主线推进」注入与校验"
                    + "将跳过，段计划的多章雷同问题暂不受约束，属已知降级而非静默", blueprint.getStageNo());
            return;
        }
        blueprint.setMainLineByChapter(patch.mainLineByChapter());
        log.info("章级主线推进补采成功（第{}阶段）：第 {}-{} 章共 {} 条",
                blueprint.getStageNo(), blueprint.getStartChapter(), end,
                patch.mainLineByChapter().size());
    }

    /**
     * **章级推进线别纪律** ：对补采产出的章级主线推进做机械校验——
     * 每条须带剧情线标签、悬疑暗线不得超半数/连续两章（规则与话术见
     * {@code RollingOutlineService#mainLineDisciplineIssues}）。
     * 违规走聚焦补采同款"带反馈重问一次"：按原补采 prompt 追加驳回反馈重取，
     * 补丁可用则替换；仍违规则 WARN 留痕放行——蓝图层没有重试循环，
     * 下游还有段计划的对话承载闸门作第二道兜底。
     */
    private void enforceMainLineDiscipline(ArmoryCommandEntity requestParameter,
                                           DefaultArmoryFactory.DynamicContext dynamicContext,
                                           StoryContextEntity storyContext, PromptContext promptContext,
                                           StageBlueprintEntity blueprint) {
        List<String> issues = RollingOutlineService.mainLineDisciplineIssues(blueprint);
        if (issues.isEmpty()) {
            log.info("阶段蓝图第{}阶段章级推进线别分布: {}", blueprint.getStageNo(),
                    RollingOutlineService.mainLineDistribution(blueprint));
            return;
        }
        log.warn("阶段蓝图第{}阶段的章级推进未过线别纪律，带反馈重问一次：{}",
                blueprint.getStageNo(), String.join("；", issues));
        String retryPrompt = rollingOutlineService.buildMainLineRepairPrompt(storyContext, blueprint,
                dynamicContext.getChapterSummaries())
                + RollingOutlineService.mainLineDisciplineFeedback(issues);
        Map<String, String> retryPrompts = new HashMap<>();
        String raw;
        try {
            raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.STAGE_BLUEPRINT,
                    promptContext, retryPrompt, retryPrompts);
        } catch (Exception e) {
            log.warn("章级推进线别纪律重问调用失败，按现状放行（体检/段计划闸门可见）：{}",
                    blueprint.getStageNo(), e.getMessage());
            return;
        }
        recordBlueprintPrompts(dynamicContext, retryPrompts);
        RollingOutlineService.MainLinePatch patch = rollingOutlineService.parseMainLinePatch(raw, blueprint);
        if (patch == null || patch.mainLineByChapter() == null || patch.mainLineByChapter().isEmpty()) {
            log.warn("章级推进线别纪律重问未取得可用补丁，按现状放行（第{}阶段）", blueprint.getStageNo());
            return;
        }
        blueprint.setMainLineByChapter(patch.mainLineByChapter());
        List<String> stillBad = RollingOutlineService.mainLineDisciplineIssues(blueprint);
        if (stillBad.isEmpty()) {
            log.info("章级推进线别纪律：重问后通过（第{}阶段）——线别分布: {}", blueprint.getStageNo(),
                    RollingOutlineService.mainLineDistribution(blueprint));
        } else {
            log.warn("章级推进线别纪律重问后仍未通过，放行本次蓝图（第{}阶段）：{}",
                    blueprint.getStageNo(), String.join("；", stillBad));
        }
    }

    /**
     * 上一阶段退出条件核验（外部审计）：仅在上一版蓝图声明了退出条件且尚未核验时执行一次，
     * 结果写回蓝图的 exitResults（随 rolling-outline.json 落盘），供生成 prompt 结转指令使用。
     * 核验失败返回 null → 不写结果，链式生成回退模型自评结转（fail-soft）
     */
    private void reviewPreviousExitConditions(ArmoryCommandEntity requestParameter,
                                              DefaultArmoryFactory.DynamicContext dynamicContext,
                                              StageBlueprintEntity previous) {
        if (previous == null || previous.getExitConditions() == null
                || previous.getExitConditions().isEmpty() || previous.getExitResults() != null) {
            return;
        }
        Map<String, String> reviewPrompts = new HashMap<>();
        // storyVO/module 理论上必有（触发层装配），防御性兜底：缺模型配置时核验跳过走 fail-soft
        StoryVO.Module module = requestParameter.getStoryVO() == null
                ? null : requestParameter.getStoryVO().getModule();
        List<StageBlueprintEntity.ExitConditionResult> results = stageExitReviewService.review(
                module, previous, dynamicContext.getChapterSummaries(), reviewPrompts);
        if (results != null) {
            previous.setExitResults(results);
            log.info("上一阶段退出条件核验完成：第{}阶段，条件 {} 条", previous.getStageNo(), results.size());
        } else {
            log.warn("上一阶段退出条件核验失败，回退模型自评结转（fail-soft）");
        }
        if (!reviewPrompts.isEmpty()) {
            recordReviewPrompts(dynamicContext, reviewPrompts);
        }
    }

    /**
     * 上一阶段出口清账补办：上一阶段已完结（末章号 ≤ 续写偏移）但结算台账无对应记录时，
     * 补一次未填伏笔裁决——VOID 出账（剥离随 DynamicContext 流转，落盘结算文件）、
     * RECOVER 由规划 prompt 按段起点注入限期回收。批次开头已清账/无遗漏/裁决失败均静默跳过（fail-soft）
     */
    private void settleMissedBreakerForeshadows(ArmoryCommandEntity requestParameter,
                                                DefaultArmoryFactory.DynamicContext dynamicContext,
                                                StageBlueprintEntity previous) {
        if (previous == null || previous.getEndChapter() == null
                || previous.getEndChapter() > dynamicContext.getChapterOffset()) {
            return;
        }
        List<ForeshadowSettlementEntity> settlements = dynamicContext.getForeshadowSettlements() != null
                ? dynamicContext.getForeshadowSettlements() : new ArrayList<>();
        boolean alreadySettled = settlements.stream().anyMatch(s -> s != null
                && previous.getEndChapter().equals(s.getStageEndChapter()));
        if (alreadySettled) {
            return;
        }
        Map<String, String> settlementPrompts = new HashMap<>();
        // storyVO/module 理论上必有（触发层装配），防御性兜底：缺模型配置时补办跳过走 fail-soft
        StoryVO.Module module = requestParameter.getStoryVO() == null
                ? null : requestParameter.getStoryVO().getModule();
        List<ForeshadowSettlementEntity.SettlementDecision> decisions = foreshadowSettlementService.settleStageBreakers(
                module, previous, requestParameter.getStoryContextEntity().getChapterGoal(),
                dynamicContext.getChapterSummaries(), settlements, settlementPrompts,
                // 逾期排期线必须并入清账候选（否则"欠账"通道永不触发，见 settleStageBreakers 注释）
                dynamicContext.getForeshadowSchedules());
        if (!settlementPrompts.isEmpty()) {
            // 结算 prompt 以 settlement: 前缀并入 usedPromptMap（与 exit-review:/blueprint: 前缀并存互不覆盖）
            Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                    ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
            settlementPrompts.forEach((key, value) -> used.put("settlement:" + key, value));
            dynamicContext.setUsedPromptMap(used);
        }
        if (decisions == null) {
            log.warn("阶段出口清账补办失败，未填伏笔保持冻结（fail-soft），stage: {}", previous.getStageNo());
            return;
        }
        if (decisions.isEmpty()) {
            log.info("阶段出口清账补办：第{}阶段出口无长期未填伏笔，跳过", previous.getStageNo());
            return;
        }
        dynamicContext.setForeshadowSettlements(settlements);
        // 回写排期表：判 DONE 的线必须真的关账（置 PAID）、判 PARKED 的线要记长线在养——
        // 否则下一段仍会以「逾期补收」再下一次令
        int paid = foreshadowScheduleService.applySettlementOutcomes(
                dynamicContext.getForeshadowSchedules(), decisions,
                previous.getEndChapter() == null ? 0 : previous.getEndChapter());
        Path storyDir = dynamicContext.getStoryDir();
        if (storyDir != null) {
            try {
                storyRepository.writeForeshadowSettlements(storyDir, settlements);
                if (paid > 0) {
                    storyRepository.writeForeshadowSchedule(storyDir, dynamicContext.getForeshadowSchedules());
                }
            } catch (Exception e) {
                log.error("阶段出口清账补办结算落盘失败，本轮仅内存生效", e);
            }
        }
        long recoverCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_RECOVER.equals(d.getDecision())).count();
        long doneCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_DONE.equals(d.getDecision())).count();
        long parkedCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_PARKED.equals(d.getDecision())).count();
        log.info("阶段出口清账补办完成：第{}阶段，未填 {} 条 = 已完成 {} + 长线在养 {} + 弃置 {} + 限期回收 {}"
                        + "（已完成项已置 PAID；在养项不再催收；回收项将注入本批规划）",
                previous.getStageNo(), decisions.size(), doneCount, parkedCount,
                decisions.size() - recoverCount - doneCount - parkedCount, recoverCount);
    }

    private void mergeUnmetExitConditions(StageBlueprintEntity previous, StageBlueprintEntity blueprint) {
        if (previous == null || previous.getExitResults() == null || blueprint == null) {
            return;
        }
        List<String> inheritedBefore = previous.getInheritedExitConditions() != null
                ? previous.getInheritedExitConditions() : List.of();
        List<StageBlueprintEntity.CarriedTaskEntity> carried = blueprint.getCarriedTasks() != null
                ? new ArrayList<>(blueprint.getCarriedTasks()) : new ArrayList<>();
        List<String> conditions = blueprint.getExitConditions() != null
                ? new ArrayList<>(blueprint.getExitConditions()) : new ArrayList<>();
        List<String> inherited = new ArrayList<>();
        boolean changed = false;

        for (StageBlueprintEntity.ExitConditionResult result : previous.getExitResults()) {
            if (Boolean.TRUE.equals(result.getMet()) || StringUtils.isBlank(result.getCondition())) {
                continue;
            }
            String condition = result.getCondition();
            // 章号引用重定基：结转到本阶段后，"第76-80章中至少有一章…"这类条件必须改为
            // 引用**本阶段**的章号，否则校验侧只看本批记忆，结构上永远判定为未达成——
            // 条件会无限结转、每批都拉低达成率，形成永久坏账（实测第5阶段 11 条里有 3 条如此，
            // 备注明写"所给记忆仅包含第81-85章，无第76-80章内容，无法判定"）。
            String rebased = rebaseConditionChapterRefs(condition, blueprint.getStartChapter(), blueprint.getEndChapter());
            if (inheritedBefore.stream().anyMatch(c -> StringUtils.equals(c, condition)
                    || StringUtils.equals(c, rebased))) {
                // 已重验一次仍不达成 → 强制出账，不再结转
                if (!containsCondition(carried, rebased)) {
                    carried.add(new StageBlueprintEntity.CarriedTaskEntity(
                            "【未达成退出条件·出账】" + rebased, "放弃",
                            "连续两阶段核验未达成，机械出账（不再结转，避免单调累积）；缺口："
                                    + StringUtils.defaultString(result.getNote(), "未达成")));
                    changed = true;
                }
                continue;
            }
            if (conditions.stream().noneMatch(c -> StringUtils.equals(c, rebased))) {
                conditions.add(rebased);
                changed = true;
            }
            inherited.add(rebased);
            if (!containsCondition(carried, rebased)) {
                carried.add(new StageBlueprintEntity.CarriedTaskEntity(
                        "【未达成退出条件】" + rebased, "进行中",
                        "机械结转并已注入本阶段退出条件（阶段末重验一次）："
                                + StringUtils.defaultString(result.getNote(), "未达成")));
                changed = true;
            }
        }
        if (changed) {
            blueprint.setExitConditions(conditions);
            blueprint.setCarriedTasks(carried);
            blueprint.setInheritedExitConditions(inherited);
        }
    }

    /** 结转清单里是否已登记该条件（原文双向包含去重，比较前剥离引号类包裹字符） */
    private static boolean containsCondition(List<StageBlueprintEntity.CarriedTaskEntity> carried, String condition) {
        String target = normalizeForDedupe(condition);
        return carried.stream().anyMatch(task -> task != null && task.getContent() != null
                && (normalizeForDedupe(task.getContent()).contains(target)
                        || target.contains(normalizeForDedupe(task.getContent()))));
    }

    /**
     * 去重比较用的归一化：剥离引号/书名号类包裹字符并去掉空白。
     *
     * <p><b>治什么</b>：同一条约束的两份文本仅差一对引号——
     * 模型写了「…必须是已封存状态余波…」，机械兜底的原文本是「…必须是『已封存』状态余波…」，
     * 双向包含在引号处断开 ⇒ 同一约束被登记两次（静默期条目在 stage16 就重复了一对）。
     * 引号是模型对同一概念的排版噪声，不承载语义差异，比较前剥离即可；
     * 刻意只剥离引号类字符，不做同义改写（那会放大误判，把"改述"当成"同一事项"）。
     */
    private static String normalizeForDedupe(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[「」『』“”\"'‘’《》\\s]", "");
    }

    /**
     * 结转任务机械兜底：上一版标记为"进行中"的任务，若模型输出的 carriedTasks 里既没有
     * 同名条目、也没有判为完成/放弃的记录，则原样补回（不信任模型的一次服从）。
     *
     * <p><b>治什么</b>：prompt 第 3 条要求"对上一版每条未完成任务逐条结转……严禁静默丢弃"，
     * 但提示词此前**根本没渲染上一版 carriedTasks**（只渲染了 tasks/exitResults/stageReport），
     * 模型只能凭近章摘要重猜 ⇒ 注入的约定事件（"面邀必须兑现""附加赛必须完成"）在换版时蒸发。
     * 实测：第 14 阶段注入的附加赛任务在第 15 阶段 carriedTasks 中消失，而系统侧零告警。
     * 提示词已同步补齐输入（见 RollingOutlineService 渲染处）；此处再加一道机械闸——
     * 与 {@link #mergeUnmetExitConditions} 同款：不把可靠性押在一次服从上。
     *
     * <p>补回条目一律标"进行中"并注明来源，供阶段末复核与人工复盘；
     * 与已有条目按内容双向包含去重，避免与模型自己写的措辞略异版本重复登记。
     */
    private void mergeMissingCarriedTasks(StageBlueprintEntity previous, StageBlueprintEntity blueprint) {
        if (previous == null || previous.getCarriedTasks() == null || previous.getCarriedTasks().isEmpty()
                || blueprint == null) {
            return;
        }
        List<StageBlueprintEntity.CarriedTaskEntity> carried = blueprint.getCarriedTasks() != null
                ? new ArrayList<>(blueprint.getCarriedTasks()) : new ArrayList<>();
        boolean changed = false;
        for (StageBlueprintEntity.CarriedTaskEntity task : previous.getCarriedTasks()) {
            if (task == null || StringUtils.isBlank(task.getContent())) {
                continue;
            }
            // 只兜底"进行中"：完成/放弃是模型对上一版的显式处置，不该被复活
            String status = StringUtils.defaultIfBlank(task.getStatus(), "进行中").trim();
            if (!"进行中".equals(status)) {
                continue;
            }
            String content = task.getContent().trim();
            if (containsCondition(carried, content)) {
                continue;
            }
            carried.add(new StageBlueprintEntity.CarriedTaskEntity(content, "进行中",
                    "机械结转兜底：上一版标记进行中，模型本次未登记处置（原 note："
                            + StringUtils.abbreviate(StringUtils.defaultString(task.getNote(), "无"), 120) + "）"));
            changed = true;
        }
        if (changed) {
            blueprint.setCarriedTasks(carried);
            log.info("结转任务机械兜底：补回模型漏登记的进行中任务，第{}阶段结转 {} 条",
                    blueprint.getStageNo(), carried.size());
        }
    }

    /** 冻结线关键词（自由文本措辞的机械识别口径）：命中即视为"该线处于冻结/静默期" */
    private static final List<String> FROZEN_LINE_MARKERS = List.of("静默期", "冻结", "禁止", "不得推进");

    /**
     * 冻结线越权观测
     *
     * <p>prompt 第 3.1 条已明令"含静默/冻结字样的结转条目不得排进 tasks/exitConditions/档位/章级推进"，
     * 此处机械兜底观测模型是否仍越权。判定口径刻意宽松（关键词命中即报），因为它的产物是
     * 一条 WARN 日志、不是拦截动作——宁可多报几条供人工扫一眼，也不要漏掉真越权。
     *
     * <p><b>为什么不拦截</b>：冻结线措辞是自由文本，"哪个 task 越了哪条线"的机械判定必然误伤
     * （如"冻结"可能出现在与冻结线无关的技术描述里）；而该情形的后果在正文侧可见
     * （新事实凭空出现 → 与既有定案冲突 → 一致性指标与人工复盘能抓到）。
     * 实测第 16 阶段：结转清单明写虹镇静默期"严禁新实物/新追查"，
     * 模型仍在 tasks 安排"角色甲透露虹镇旧事的新信息碎片"，第 140 章据此造出地窖账册。
     */
    private void warnFrozenLineViolations(StageBlueprintEntity previous, StageBlueprintEntity blueprint) {
        if (previous == null || blueprint == null
                || previous.getCarriedTasks() == null || previous.getCarriedTasks().isEmpty()) {
            return;
        }
        List<String> frozen = previous.getCarriedTasks().stream()
                .filter(t -> t != null && StringUtils.isNotBlank(t.getContent()))
                .map(StageBlueprintEntity.CarriedTaskEntity::getContent)
                .filter(c -> FROZEN_LINE_MARKERS.stream().anyMatch(c::contains))
                .toList();
        if (frozen.isEmpty()) {
            return;
        }
        List<String> planned = new ArrayList<>();
        if (blueprint.getTasks() != null) {
            planned.addAll(blueprint.getTasks());
        }
        if (blueprint.getExitConditions() != null) {
            planned.addAll(blueprint.getExitConditions());
        }
        if (planned.isEmpty()) {
            return;
        }
        for (String frozenLine : frozen) {
            for (String item : planned) {
                if (StringUtils.isBlank(item) || item.contains("静默") || item.contains("冻结")
                        || item.contains("禁止") || item.contains("不得")) {
                    // 新条目自身带冻结措辞 = 模型在复述约束，不是越权
                    continue;
                }
                if (sharesTopicWith(frozenLine, item)) {
                    log.warn("第{}阶段疑似冻结线越权（观测项，未拦截）：结转条目「{}」处于冻结期，"
                                    + "但本阶段 tasks/exitConditions 出现「{}」——请人工核对是否安排了新推进",
                            blueprint.getStageNo(), StringUtils.abbreviate(frozenLine, 50),
                            StringUtils.abbreviate(item, 60));
                }
            }
        }
    }

    /**
     * 粗粒度主题重合：冻结线文本里的中文实词（≥2 字）是否出现在候选条目里。
     * 只用于上面那条观测日志的降噪（避免把完全无关的任务也报出来）。
     */
    private static boolean sharesTopicWith(String frozenLine, String candidate) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]{2,}")
                .matcher(frozenLine);
        java.util.Set<String> topics = new java.util.HashSet<>();
        while (m.find()) {
            topics.add(m.group());
        }
        // 去掉冻结约束自身的高频通用词，剩下的才是"线名"
        topics.removeIf(t -> List.of("静默期", "严禁", "禁止", "不得", "实物", "追查", "动作",
                "下一段", "出现", "状态", "余波", "转移", "宣言", "相关", "必须", "允许").contains(t));
        return topics.stream().anyMatch(candidate::contains);
    }

    /** 条件文本里的章号区间引用（"第76-80章"/"第 76-80 章"），用于结转时的重定基 */
    private static final java.util.regex.Pattern CONDITION_CHAPTER_RANGE =
            java.util.regex.Pattern.compile("第\\s*\\d+\\s*[-–—~]\\s*\\d+\\s*章");

    /**
     * 结转条件重定基：把条件里引用的**旧章号区间**改写为本阶段区间。
     */
    private static String rebaseConditionChapterRefs(String condition, Integer startChapter, Integer endChapter) {
        if (StringUtils.isBlank(condition) || startChapter == null || endChapter == null) {
            return condition;
        }
        String replacement = "第" + startChapter + "-" + endChapter + "章";
        String rebased = CONDITION_CHAPTER_RANGE.matcher(condition).replaceAll(replacement);
        return StringUtils.equals(rebased, condition) ? condition : rebased;
    }

    /**
     * 核验 prompt 并入 usedPromptMap 供复盘（exit-review: 前缀，合并式写入不覆盖 blueprint: 记录）
     */
    private void recordReviewPrompts(DefaultArmoryFactory.DynamicContext dynamicContext, Map<String, String> reviewPrompts) {
        Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
        reviewPrompts.forEach((key, value) -> used.put("exit-review:" + key, value));
        dynamicContext.setUsedPromptMap(used);
    }

    /**
     * 找覆盖指定章的蓝图（链上无覆盖时回退最新一版）；供上下文默认值与测试使用
     */
    private StageBlueprintEntity findCovering(List<StageBlueprintEntity> blueprints, int chapterNo) {
        List<RollingOutlineService.StageSegment> segments = rollingOutlineService.segmentBatch(blueprints, chapterNo, chapterNo);
        return segments.isEmpty() ? null : segments.get(0).blueprint();
    }

    /**
     * 蓝图 prompt 以 blueprint: 前缀并入 usedPromptMap（计划节点复用该 map，不再覆盖），
     * 供生成记录复盘
     */
    /**
     * 蓝图 prompt 并入 usedPromptMap 供复盘（blueprint: 前缀，合并式写入——
     * 链式生成多版/退出条件核验的 prompt 记录互相保留，不互相覆盖）
     */
    /**
     * 悬念档位表**聚焦补采**：主 prompt 遗漏这两项时再问一次，且**只问这两个字段**。
     *
     * <p>设计依据来自实测：同一条要求放在完整蓝图 prompt（十几个要求 + 长 schema）里会被静默省略，
     * 而单独用短 prompt 问则 100% 给出且质量合格（5 档、可观察表述）。
     * 所以可靠性靠"缺了就补问"，不靠"要求写得够醒目"——后者对长 prompt 无效。
     *
     * <p>补采也拿不到时**必须告警**：此时推进闸门与 suspenseHold 指标都会静默失效，
     * 而日志若不留痕，事后无从判断"到底跑了没跑"。
     */
    private void repairSuspenseLadder(ArmoryCommandEntity requestParameter,
                                      DefaultArmoryFactory.DynamicContext dynamicContext,
                                      StoryContextEntity storyContext, PromptContext promptContext,
                                      StageBlueprintEntity blueprint) {
        log.warn("阶段蓝图未给出悬念档位表（第{}阶段），触发聚焦补采", blueprint.getStageNo());
        String repairPrompt = rollingOutlineService.buildSuspenseLadderRepairPrompt(storyContext, blueprint,
                dynamicContext.getChapterSummaries());
        Map<String, String> repairPrompts = new HashMap<>();
        String raw;
        try {
            raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.STAGE_BLUEPRINT,
                    promptContext, repairPrompt, repairPrompts);
        } catch (Exception e) {
            log.warn("悬念档位补采调用失败，第{}阶段的主线推进闸门将跳过：{}",
                    blueprint.getStageNo(), e.getMessage());
            return;
        }
        recordBlueprintPrompts(dynamicContext, repairPrompts);
        RollingOutlineService.SuspenseLadderPatch patch = rollingOutlineService.parseSuspenseLadderPatch(raw);
        if (patch == null || !SuspenseLadderPolicy.usable(patch.suspenseLadder())) {
            log.warn("悬念档位补采仍未取得可用档位表（第{}阶段）——本阶段的「主线推进」闸门与 suspenseHold "
                    + "指标将跳过，属已知降级而非静默", blueprint.getStageNo());
            return;
        }
        if (StringUtils.isBlank(blueprint.getCoreSuspense())) {
            blueprint.setCoreSuspense(patch.coreSuspense());
        }
        blueprint.setSuspenseLadder(patch.suspenseLadder());
        log.info("悬念档位补采成功（第{}阶段）：{} 档", blueprint.getStageNo(), patch.suspenseLadder().size());
    }

    private void recordBlueprintPrompts(DefaultArmoryFactory.DynamicContext dynamicContext, Map<String, String> blueprintPrompts) {
        Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
        blueprintPrompts.forEach((key, value) -> used.put("blueprint:" + key, value));
        dynamicContext.setUsedPromptMap(used);
    }

}
