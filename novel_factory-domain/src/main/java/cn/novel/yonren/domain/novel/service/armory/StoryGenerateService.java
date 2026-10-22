package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.StoryBibleParser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 故事生成领域服务：装配入参并执行规则树。
 * 续写感知收敛在此：目录解析/记忆预载/编号偏移/上章结尾全部就绪后塞入 DynamicContext，
 * 树内节点只消费预载结果，不感知 resumeStoryDir 概念
 */
@Service
@Slf4j
public class StoryGenerateService {

    @Resource
    private DefaultArmoryFactory armoryFactory;

    @Resource
    private IStoryRepository storyRepository;

    @Resource
    private ChapterMemoryService chapterMemoryService;

    @Resource
    private StoryProperties storyProperties;

    @Resource
    private cn.novel.yonren.domain.novel.service.armory.node.GenerateChapterContentNode generateChapterContentNode;

    public StoryGenerateResultAggregate generate(ArmoryCommandEntity command) throws Exception {
        return generate(command, null);
    }

    /**
     * 异步作业入口：job 非空时注入 DynamicContext，节点树借其做取消检查/进度上报/run 目录合并；
     * 同步调试路径传 null，行为与历史完全一致
     */
    public StoryGenerateResultAggregate generate(ArmoryCommandEntity command, GenerationJob job) throws Exception {
        return generate(command, job, newDynamicContext(job));
    }

    /**
     * 复用外部持有的 DynamicContext 执行规则树。
     * 供章节计划审批门挂起后**续跑剩余阶段**使用：挂起期间上下文（storyDir/runDir/蓝图链/
     * 计划分段/故事上下文）原样保留在内存，恢复时无需从磁盘重建，也不会重复烧规划 token。
     *
     * <p>注意：本重载**不重复**执行续写预载与完结上限收敛——这两步已在首次进入时作用于同一上下文，
     * 重跑会重复预载记忆并可能因目录已建而改变语义
     */
    public StoryGenerateResultAggregate generate(ArmoryCommandEntity command, GenerationJob job,
                                                 DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // storyVO 的默认配置装配已上移至 trigger 层
        // 续写：解析原目录（缺失硬失败）并预载记忆，规划与正文由此无缝承接；首发：跳过
        if (StringUtils.isNotBlank(command.getResumeStoryDir())) {
            preloadResumeContext(command, command.getResumeStoryDir(), dynamicContext);
        }
        // 全书总章数上限（完结保护）：超出/已达上限则截断或拒绝
        enforceChapterLimit(command, dynamicContext);
        // 导演通道：作者创作要点带入上下文，供三个规划 prompt 注入
        dynamicContext.setCreativeNotes(command.getCreativeNotes());
        return armoryFactory.armoryStrategyHandler().apply(command, dynamicContext);
    }

    /** 新建动态上下文并注入作业载体（同步调试路径 job 为 null） */
    public DefaultArmoryFactory.DynamicContext newDynamicContext(GenerationJob job) {
        DefaultArmoryFactory.DynamicContext dynamicContext = new DefaultArmoryFactory.DynamicContext();
        dynamicContext.setJob(job);
        return dynamicContext;
    }

    public StoryGenerateResultAggregate resumeAfterPlanApproval(ArmoryCommandEntity command, GenerationJob job,
                                                               DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        if (dynamicContext == null || dynamicContext.getChapterPlanAggregate() == null) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "审批续跑上下文缺失章节计划（挂起现场已失效），请改用 resumeStoryDir 携带计划重新提交本批");
        }
        if (job == null) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "审批续跑仅支持异步作业路径（同步调试路径不经过审批门，无续跑语义）");
        }
        dynamicContext.setPlanApproved(true);
        dynamicContext.setJob(job);
        return generateChapterContentNode.apply(command, dynamicContext);
    }


    private void enforceChapterLimit(ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        Integer effective = resolveEffectiveMax(command, dynamicContext);
        dynamicContext.setMaxChapterCount(effective);
        if (effective == null) {
            return;
        }
        int offset = dynamicContext.getChapterOffset();
        StoryContextEntity ctx = command.getStoryContextEntity();
        int chapterCount = ctx == null || ctx.getChapterCount() == null ? 0 : ctx.getChapterCount();
        if (offset >= effective) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "故事已达总章数上限 " + effective + " 章（当前已 " + offset + " 章），已完结，不再生成。");
        }
        int allowed = Math.min(chapterCount, effective - offset);
        if (allowed < chapterCount) {
            log.info("总章数上限 {}，本批请求 {} 章，截断为 {} 章（已生成 {} 章）", effective, chapterCount, allowed, offset);
            // 契约性截断（见 DynamicContext 字段不变式表）：下游规划 prompt/worker 的批次章数
            // 都从 storyContextEntity.chapterCount 读取，原地改写是完结保护的落地方式，非脏写
            ctx.setChapterCount(allowed);
        }
    }


    private Integer resolveEffectiveMax(ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 只有"请求里显式给出"的上限才有资格参与 min 比较
        Integer requestedMax = command.getMaxChapterCount() != null && command.getMaxChapterCount() > 0
                ? command.getMaxChapterCount() : null;
        Integer configuredMax = configuredMax();

        boolean firstGeneration = dynamicContext.getStoryDir() == null;
        if (firstGeneration) {
            // 首发：目录由树内创建，此处仅内存生效，落盘转交 PersistChapterPlanNode（幂等不覆盖）
            return requestedMax != null ? requestedMax : configuredMax;
        }

        IStoryRepository.StoryMeta persisted = storyRepository.readStoryMeta(dynamicContext.getStoryDir());
        if (persisted != null) {
            int cap = persisted.maxChapterCount();
            // 0/负数 = 未固化（手工改过的 story-meta、旧格式或半截写入）：不能当成真实上限，
            // 否则 enforceChapterLimit 会以“已达总章数上限 0 章，已完结”永久拒写续写
            if (cap <= 0) {
                log.warn("story-meta 的完结上限为 {}（非法/未设置），本次按请求值或 yml 兜底，不按已完结处理",
                        cap);
                return requestedMax != null ? requestedMax : configuredMax;
            }
            if (requestedMax != null && requestedMax < cap) {
                log.info("请求完结上限 {} 低于本书已固化上限 {}，本批按 {} 执行（固化值不可下调其语义不退让）", requestedMax, cap, requestedMax);
                return requestedMax;
            }
            if (requestedMax != null && requestedMax > cap) {
                log.warn("请求完结上限 {} 高于本书已固化上限 {}，按 {} 执行，不再接受提高上限", requestedMax, cap, cap);
            }
            return cap;
        }

        // 老故事首次接入完结保护：以请求值优先、否则 yml 兜底确立并固化
        Integer established = requestedMax != null ? requestedMax : configuredMax;
        if (established != null) {
            storyRepository.writeStoryMeta(dynamicContext.getStoryDir(), new IStoryRepository.StoryMeta(established));
        }
        return established;
    }

    /** yml 的全局默认完结上限（仅当完结保护开启且值 > 0 时有效），无则 null */
    private Integer configuredMax() {
        if (storyProperties == null || storyProperties.getConstraints() == null
                || !Boolean.TRUE.equals(storyProperties.getConstraints().getEnforceChapterLimit())) {
            return null;
        }
        Integer configured = storyProperties.getConstraints().getMaxChapterCount();
        return configured != null && configured > 0 ? configured : null;
    }

    private void preloadResumeContext(ArmoryCommandEntity command, String resumeStoryDir, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 目录不存在直接硬失败：绝不在错误目录上重起炉灶
        Path storyDir = storyRepository.resolveStoryDirectory(resumeStoryDir);
        dynamicContext.setStoryDir(storyDir);
        String bible = storyRepository.readStoryBible(storyDir);
        restoreStoryFeatures(command, bible);
        restoreCharacterProfiles(command, bible);

        List<ChapterSummaryEntity> history = storyRepository.readChapterSummaries(storyDir);
        dynamicContext.setChapterSummaries(new ArrayList<>(history));
        dynamicContext.setConsistencyIndex(storyRepository.readConsistencyIndex(storyDir));
        dynamicContext.setStyleStat(storyRepository.readStyleStat(storyDir));
        dynamicContext.setQualityDebts(new ArrayList<>(storyRepository.readQualityDebts(storyDir)));
        dynamicContext.setStageBlueprints(new ArrayList<>(storyRepository.readStageBlueprints(storyDir)));
        dynamicContext.setVolumes(new ArrayList<>(storyRepository.readVolumes(storyDir)));

        // 卷末清账结算：预载历史裁决——VOID 条目据此从伏笔账出账（崩溃恢复时 summaries 可能尚未
        // 随检查点重写，以结算文件对齐），RECOVER 条目由规划 prompt 按段起点注入限期回收
        List<ForeshadowSettlementEntity> settlements = storyRepository.readForeshadowSettlements(storyDir);
        if (settlements != null && !settlements.isEmpty()) {
            chapterMemoryService.stripVoidedForeshadows(history, ForeshadowSettlementEntity.voidedContents(settlements));
            dynamicContext.setForeshadowSettlements(new ArrayList<>(settlements));
        }

        dynamicContext.setForeshadowSchedules(
                new ArrayList<>(storyRepository.readForeshadowSchedule(storyDir)));

        // 锁步校验：正文文件与记忆摘要必须严格对齐（最大编号一致、编号从 1 连续），否则硬失败
        validateResumeLockStep(resumeStoryDir, history, storyRepository.readChapterNumbers(storyDir));

        int chapterOffset = history.stream()
                .mapToInt(s -> s.getChapterNo() == null ? 0 : s.getChapterNo())
                .max().orElse(0);
        dynamicContext.setChapterOffset(chapterOffset);

        // 上一章结尾原文：规划 prompt 与跨批首章正文共用（衔接硬性要求）
        String latestContent = storyRepository.readLatestChapterContent(storyDir);
        dynamicContext.setPrevChapterTail(chapterMemoryService.tailByParagraph(
                latestContent, ChapterMemoryService.DEFAULT_PREV_TAIL_LENGTH));

        // 历史末章的一致性偏差警示，随记忆前缀回灌
        history.stream()
                .filter(s -> s.getChapterNo() != null)
                .max(Comparator.comparing(ChapterSummaryEntity::getChapterNo))
                .ifPresent(latest -> dynamicContext.setPendingConflicts(latest.getContinuityConflicts()));

        log.info("续写模式：目录 {}（原目录追加），历史 {} 章，新章从 {} 开始",
                resumeStoryDir, history.size(), chapterOffset + 1);
    }

    /**
     * 续写时从 bible **强制回填**人物档案（与 {@link #restoreStoryFeatures} 同款理由：bible 是唯一权威）。
     *
     * <p>为什么必须由后端回填、而不是依赖前端把该字段原样带回来：漏带不会报错，只会表现为
     * "人物代词与既定事实悄悄消失"——而它正是防代词/班级写错的那道锚。
     * bible 没有这一行（老故事）时不动作，保持既有行为。
     */
    private void restoreCharacterProfiles(ArmoryCommandEntity command, String bible) {
        if (command == null || command.getStoryContextEntity() == null || bible == null || bible.isBlank()) {
            return;
        }
        String profiles = StoryBibleParser.parse(bible).characters();
        if (profiles != null && !profiles.isBlank()) {
            command.getStoryContextEntity().setCharacterProfiles(profiles);
        }
    }

    private void restoreStoryFeatures(ArmoryCommandEntity command, String bible) {
        if (command == null || command.getStoryVO() == null || command.getStoryVO().getFeatures() != null
                || bible == null || bible.isBlank()) return;
        // 解析委托 StoryBibleParser：标签与切分规则和写入侧（appendBibleLine，ASCII 冒号）共用同一份
        StoryBibleParser.Snapshot snapshot = StoryBibleParser.parse(bible);
        // 三态：null = bible 未声明金手指（老故事）→ 不恢复，保持 features 为 null
        if (snapshot.hasCheatMechanism() == null) return;
        cn.novel.yonren.domain.novel.model.valobj.StoryVO.StoryFeatures features =
                new cn.novel.yonren.domain.novel.model.valobj.StoryVO.StoryFeatures();
        features.setHasCheatMechanism(snapshot.hasCheatMechanism());
        features.setCheatMechanismName(snapshot.cheatMechanismName());
        // 未写/无法解析时沿用原默认间隔 3
        features.setCheatUsageInterval(snapshot.cheatUsageInterval() != null ? snapshot.cheatUsageInterval() : 3);
        command.getStoryVO().setFeatures(features);
    }

    /**
     * 续写锁步校验（包级可见供单测）：正文文件与记忆摘要必须严格对齐——
     * 最大编号一致、编号从 1 起连续无缺口（重复即产生缺口）。
     * 任一不一致说明目录被外部改动/损坏，硬失败避免在错误偏移上续写
     */
    static void validateResumeLockStep(String resumeStoryDir, List<ChapterSummaryEntity> history, List<Integer> chapterNumbers) {
        int chapterMax = chapterNumbers == null ? 0
                : chapterNumbers.stream().mapToInt(Integer::intValue).max().orElse(0);
        int summaryMax = history == null ? 0 : history.stream()
                .mapToInt(s -> s.getChapterNo() == null ? 0 : s.getChapterNo()).max().orElse(0);

        if (chapterMax != summaryMax) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "续写失败：正文最大章节号(" + chapterMax + ")与记忆摘要最大章节号(" + summaryMax
                            + ")不一致，故事目录 " + resumeStoryDir + " 可能被外部改动或损坏，请检查后重试");
        }
        if (chapterMax == 0) {
            return;
        }
        Set<Integer> chapterNoSet = new HashSet<>(chapterNumbers);
        for (int i = 1; i <= chapterMax; i++) {
            if (!chapterNoSet.contains(i)) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(),
                        "续写失败：第 " + i + " 章正文文件缺失或重复（编号应连续 1~" + chapterMax + "），故事目录 "
                                + resumeStoryDir + " 可能被外部改动或损坏，请检查后重试");
            }
        }
        Set<Integer> summaryNumbers = new HashSet<>();
        for (ChapterSummaryEntity s : history) {
            if (s != null && s.getChapterNo() != null) {
                summaryNumbers.add(s.getChapterNo());
            }
        }
        for (int i = 1; i <= summaryMax; i++) {
            if (!summaryNumbers.contains(i)) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(),
                        "续写失败：第 " + i + " 章记忆摘要缺失或重复（编号应连续 1~" + summaryMax + "），故事目录 "
                                + resumeStoryDir + " 可能被外部改动或损坏，请检查后重试");
            }
        }
    }
}
