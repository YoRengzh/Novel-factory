package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.memory.OutlineSegmentParser;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import org.apache.commons.lang3.StringUtils;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 卷蓝图节点：全书长期锚（顶部一次性规划）。
 * 批次开头判断本批是否跨过卷边界（批次末章超出最新卷覆盖区间），是则链式生成新卷直至覆盖批次末章。
 * 卷是长期锚——仅在卷边界生成、落盘后不随进度滚动微调；生成在阶段蓝图节点之前执行，供弧锚定全局位置。
 * 卷同样按 fail-soft 处理：生成/解析失败或未配置向量记忆时，剩余章段以无卷（两段式）模式继续，不阻断生成。
 * 生成结果写入 DynamicContext，随 PersistChapterPlanNode 统一落盘 volume-blueprint.json，并幂等索引入向量记忆
 */
@Service
@Slf4j
public class BuildVolumeBlueprintNode extends AbstractArmorySupport {

    @Resource
    private BuildStageBlueprintNode buildStageBlueprintNode;

    @Resource
    private RollingOutlineService rollingOutlineService;

    @Resource
    private LlmInvokeService llmInvokeService;

    @Resource
    private StoryMemoryService storyMemoryService;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return buildStageBlueprintNode;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 BuildVolumeBlueprintNode - 卷蓝图（全书长期锚）");
        prepareVolume(requestParameter, dynamicContext);
        return router(requestParameter, dynamicContext);
    }

    /**
     * 触发判断 + 逐卷链式生成 + 写入上下文（包级可见供单测绕过树路由）。
     * 判断基准是本批末章：批次跨越多卷边界时循环生成，直至卷链覆盖批次末章
     */
    void prepareVolume(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        List<VolumeBlueprintEntity> volumes = dynamicContext.getVolumes() != null
                ? dynamicContext.getVolumes() : new ArrayList<>();
        int chapterOffset = dynamicContext.getChapterOffset();
        int nextChapterNo = chapterOffset + 1;
        int batchEnd = chapterOffset + requestParameter.getStoryContextEntity().getChapterCount();

        if (!rollingOutlineService.needsVolumeGeneration(volumes, batchEnd)) {
            dynamicContext.setCurrentVolume(rollingOutlineService.latestVolumeOf(volumes));
            log.info("卷蓝图复用：第{}卷（第{}-{}章），下一章 {}",
                    dynamicContext.getCurrentVolume().getVolumeNo(),
                    dynamicContext.getCurrentVolume().getStartChapter(),
                    dynamicContext.getCurrentVolume().getEndChapter(), nextChapterNo);
            return;
        }

        // 批次跨卷边界：最新卷终点之后逐卷生成，直至卷链覆盖批次末章
        while (rollingOutlineService.needsVolumeGeneration(volumes, batchEnd)) {
            // 规划期协作取消/熔断检查（每轮迭代头部）：与 BuildStageBlueprintNode 同款——
            // 卷链式生成发生在阶段蓝图与章节计划之前，缺了这里取消在整个卷蓝图期不生效
            GenerationJob planJob = dynamicContext.getJob();
            if (planJob != null && (planJob.isCancelRequested() || planJob.isBudgetExceeded())) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(),
                        "作业在卷蓝图生成期已请求取消/触发熔断，停止规划（已完成章节不受影响，可 resume 续写）");
            }
            VolumeBlueprintEntity previous = rollingOutlineService.latestVolumeOf(volumes);
            VolumeBlueprintEntity volume = generateForWindow(requestParameter, dynamicContext, previous, batchEnd);
            if (volume == null) {
                // 卷是增强件：生成失败停止链式生成，剩余章段退化为无卷（两段式），不阻断计划生成
                log.warn("卷蓝图生成失败，剩余章段以{}继续（fail-soft，不阻断计划生成）",
                        previous == null ? "无卷模式（两段式）" : "上一卷（第" + previous.getVolumeNo() + "卷）");
                break;
            }
            if (volume.getEndChapter() == null || volume.getEndChapter() < volume.getStartChapter()) {
                log.warn("卷蓝图终点 {} 未越过起点 {}，终止链式生成", volume.getEndChapter(), volume.getStartChapter());
                break;
            }
            volumes.add(volume);
        }

        dynamicContext.setVolumes(volumes.isEmpty() ? null : volumes);
        VolumeBlueprintEntity current = rollingOutlineService.latestVolumeOf(volumes);
        dynamicContext.setCurrentVolume(current);
        dynamicContext.setVolumeBlueprint(current);
    }

    /**
     * 为单个卷窗口生成卷蓝图：组卷级 prompt、调用模型、解析规整、索引入向量记忆。
     * batchEnd 为本次批次末章，仅作卷触发基准；卷终点不随批次收缩（长期锚）
     */
    private VolumeBlueprintEntity generateForWindow(ArmoryCommandEntity requestParameter,
                                                    DefaultArmoryFactory.DynamicContext dynamicContext,
                                                    VolumeBlueprintEntity previous,
                                                    int batchEnd) {
        int nextChapterNo = dynamicContext.getChapterOffset() + 1;
        int windowStart = (previous == null || previous.getEndChapter() == null)
                ? nextChapterNo : previous.getEndChapter() + 1;
        int volumeNo = (previous == null) ? 1 : previous.getVolumeNo() == null ? 1 : previous.getVolumeNo() + 1;
        RollingOutlineService.VolumeWindow window = new RollingOutlineService.VolumeWindow(volumeNo, windowStart);

        Integer hardTotal = dynamicContext.getMaxChapterCount();
        // 导演通道：作者创作要点置于 prompt 最顶部（不可裁剪）
        String prompt = cn.novel.yonren.domain.novel.service.armory.CreativeNotes
                .block(dynamicContext.getCreativeNotes())
                + rollingOutlineService.buildVolumePrompt(
                requestParameter.getStoryContextEntity(), previous,
                dynamicContext.getChapterSummaries(), window, hardTotal);

        // Phase 4 向量方向层：按当前弧意图语义召回历史卷方向（kind=VOLUME），补足超越"直接上一卷"的远期承诺。
        // 检索失败/未配置记忆时为空，不改变 prompt 语义（fail-soft，纯增益）
        try {
            List<StoryMemoryService.RecallHit> directions = storyMemoryService.retrieveDirections(
                    requestParameter.getStoryVO() == null ? null : requestParameter.getStoryVO().getModule(),
                    dynamicContext.getStoryDir(),
                    requestParameter.getStoryContextEntity().getOutline(),
                    requestParameter.getStoryContextEntity().getWorldId());
            if (directions != null && !directions.isEmpty()) {
                StringBuilder sb = new StringBuilder(prompt);
                sb.append("\n【历史卷方向参考】（向量召回，供传承早期卷主旨/承转合承诺）\n");
                for (StoryMemoryService.RecallHit hit : directions) {
                    sb.append("- ").append(hit.text()).append("\n");
                }
                prompt = sb.toString();
            }
        } catch (Exception e) {
            log.warn("卷向量方向召回失败，已跳过（不阻断生成）：{}", e.getMessage());
        }

        // 大纲分卷钳制：chapterGoal 的卷骨架是全书路标——本卷区间机械锁定，
        // 模型只填卷主旨/承转合/弧线（根治"1-360 融成一卷"——卷路标与正文漂移无人对表）。
        // 无大纲/无匹配卷时保持原行为（fail-soft，通用化：非分章大纲自动豁免）
        OutlineSegmentParser.OutlineVolumeSkeleton outlineVolume = null;
        try {
            OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(
                    requestParameter.getStoryContextEntity().getChapterGoal());
            for (OutlineSegmentParser.OutlineVolumeSkeleton v : outline.volumes()) {
                if (windowStart >= v.startChapter() && windowStart <= v.endChapter()) {
                    outlineVolume = v;
                    break;
                }
            }
            if (outlineVolume != null) {
                StringBuilder sv = new StringBuilder(prompt);
                sv.append("\n【卷预算钳制·硬约束】按作者大纲，本卷（卷").append(outlineVolume.volumeNo())
                        .append("《").append(outlineVolume.title()).append("》）章区间固定为第 ")
                        .append(outlineVolume.startChapter()).append("-").append(outlineVolume.endChapter())
                        .append(" 章——卷主旨/承转合/弧线必须填满该区间，不得另行划定卷终点。本卷覆盖的大纲段：\n");
                for (OutlineSegmentParser.OutlineSegment seg : outline.segments()) {
                    if (seg.volumeNo() == outlineVolume.volumeNo()) {
                        sv.append("- 第 ").append(seg.startChapter()).append("-").append(seg.endChapter())
                                .append(" 章").append(StringUtils.isBlank(seg.timeLabel()) ? ""
                                        : "【" + seg.timeLabel() + "】")
                                .append("：").append(seg.milestone()).append("\n");
                    }
                }
                prompt = sv.toString();
            }
        } catch (Exception e) {
            log.warn("大纲卷骨架解析失败，已跳过分卷钳制（不阻断生成）：{}", e.getMessage());
        }

        StoryContextEntity storyContext = requestParameter.getStoryContextEntity();
        PromptContext promptContext = PromptContext.builder()
                .theme(storyContext.getTheme())
                .style(storyContext.getStyle())
                .totalChapters(storyContext.getChapterCount())
                .build();

        Map<String, String> volumePrompts = new HashMap<>();
        String raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.VOLUME_BLUEPRINT,
                promptContext, prompt, volumePrompts);
        recordVolumePrompts(dynamicContext, volumePrompts);

        VolumeBlueprintEntity volume = rollingOutlineService.parseVolume(raw, previous, nextChapterNo, batchEnd, hardTotal);
        if (volume == null) {
            return null;
        }
        if (outlineVolume != null) {
            // 机械钳制：卷区间以大纲骨架为准（模型产出仅作主题参考）
            volume.setStartChapter(outlineVolume.startChapter());
            volume.setEndChapter(outlineVolume.endChapter());
        }

        // 卷蓝图幂等索引入向量记忆（kind=VOLUME），供后续弧/卷生成检索"方向增益"
        try {
            StoryVO.Module module = requestParameter.getStoryVO() == null
                    ? null : requestParameter.getStoryVO().getModule();
            storyMemoryService.indexVolume(module, dynamicContext.getStoryDir(), volume);
        } catch (Exception e) {
            log.warn("卷向量索引失败，已跳过（不阻塞生成）：{}", e.getMessage());
        }

        log.info("卷蓝图生成：第{}卷（第{}-{}章），卷主旨：{}，弧 {} 条",
                volume.getVolumeNo(), volume.getStartChapter(), volume.getEndChapter(),
                volume.getThemeShift(), volume.getArcPlan() == null ? 0 : volume.getArcPlan().size());
        return volume;
    }

    /**
     * 卷 prompt 并入 usedPromptMap 供复盘（volume: 前缀，合并式写入——不覆盖 blueprint: 记录）
     */
    private void recordVolumePrompts(DefaultArmoryFactory.DynamicContext dynamicContext, Map<String, String> volumePrompts) {
        Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
        volumePrompts.forEach((key, value) -> used.put("volume:" + key, value));
        dynamicContext.setUsedPromptMap(used);
    }

}