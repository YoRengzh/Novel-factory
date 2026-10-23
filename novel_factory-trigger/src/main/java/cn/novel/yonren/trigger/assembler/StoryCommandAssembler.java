package cn.novel.yonren.trigger.assembler;

import cn.novel.yonren.api.dto.ChapterPlanApprovalRequestDTO;
import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DTO → 领域命令装配（trigger 层工具）：同步与异步两个 controller 共用，
 * storyVO 未显式传入时用 yml 默认配置补齐；worldId 在此做入口校验（集合名约束）
 *
 * <p>无状态、纯函数式（静态方法 + 私有构造），因此不做 Spring Bean，
 * 避免为一次字段搬运和格式校验引入容器生命周期。
 */
public final class StoryCommandAssembler {

    /** Qdrant 集合名保守白名单：字母数字开头，1-63 位，仅字母数字/下划线/连字符 */
    static final Pattern WORLD_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$");

    private StoryCommandAssembler() {
    }

    public static ArmoryCommandEntity toCommand(StoryGenerateRequestDTO request, StoryProperties storyProperties) {
        String worldId = validateWorldId(request.getWorldId());

        StoryContextEntity storyContextEntity = StoryContextEntity.builder()
                .novel_title(request.getNovel_title())
                .theme(request.getTheme())
                .style(request.getStyle())
                .worldSetting(request.getWorldSetting())
                .perspective(request.getPerspective())
                .targetAudience(request.getTargetAudience())
                .tone(request.getTone())
                .protagonist(request.getProtagonist())
                .outline(request.getOutline())
                .chapterGoal(request.getChapterGoal())
                .chapterCount(request.getChapterCount())
                .worldId(worldId)
                .build();

        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .storyContextEntity(storyContextEntity)
                .resumeStoryDir(request.getResumeStoryDir())
                .maxChapterCount(request.getMaxChapterCount())
                .autoApprovePlan(request.getAutoApprovePlan())
                .creativeNotes(request.getCreativeNotes())
                .build();

        // 默认模型配置装配上移至触发层：未显式传入 storyVO 时用 yml 配置补齐
        if (command.getStoryVO() == null) {
            command.setStoryVO(storyProperties.toStoryVO());
        }
        if (request.getHasCheatMechanism() != null || request.getCheatMechanismName() != null
                || request.getCheatUsageInterval() != null) {
            StoryVO.StoryFeatures features = new StoryVO.StoryFeatures();
            features.setHasCheatMechanism(Boolean.TRUE.equals(request.getHasCheatMechanism()));
            features.setCheatMechanismName(request.getCheatMechanismName());
            features.setCheatUsageInterval(request.getCheatUsageInterval() == null ? 3
                    : Math.max(1, request.getCheatUsageInterval()));
            command.getStoryVO().setFeatures(features);
        }
        return command;
    }

    static String validateWorldId(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return null;
        }
        String trimmed = worldId.trim();
        if (!WORLD_ID_PATTERN.matcher(trimmed).matches()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "worldId 格式非法：仅允许字母数字开头、1-63 位字母数字/下划线/连字符，收到：" + worldId);
        }
        return trimmed;
    }

    /**
     * 章节计划裁决请求 → 领域聚合（DTO 适配）。
     * 返回 null 表示"未携带修订稿"（chapters 为空），由领域层采纳 AI 原版计划。
     *
     * <p>{@code chapterType} 用 {@link ChapterTypeVO#of} 容错解析：未识别值降级 NORMAL，
     * 避免前端传 "NORMAL"/"普通" 之类的变体导致整次裁决 400
     */
    public static ChapterPlanAggregate toChapterPlan(ChapterPlanApprovalRequestDTO request) {
        if (request == null || request.getChapters() == null || request.getChapters().isEmpty()) {
            return null;
        }
        List<ChapterPlanItemEntity> items = new ArrayList<>(request.getChapters().size());
        for (ChapterPlanApprovalRequestDTO.Chapter chapter : request.getChapters()) {
            ChapterPlanItemEntity item = new ChapterPlanItemEntity();
            item.setChapterNo(chapter.getChapterNo());
            item.setTitle(chapter.getTitle());
            item.setGoal(chapter.getGoal());
            item.setCharacters(chapter.getCharacters());
            item.setKeyEvents(chapter.getKeyEvents());
            item.setEndingHook(chapter.getEndingHook());
            item.setChapterType(ChapterTypeVO.of(chapter.getChapterType()));
            // 裁决卡未展示、但下游消费的字段：原样透传，否则人工改一个字就会把它们清空
            item.setDialogueScenes(chapter.getDialogueScenes());
            item.setTimeAdvance(chapter.getTimeAdvance());
            item.setSuspenseBeat(chapter.getSuspenseBeat());
            item.setMainLineAdvance(chapter.getMainLineAdvance());
            items.add(item);
        }
        return ChapterPlanAggregate.builder()
                .storyId(request.getStoryId())
                .chapters(items)
                .risks(request.getRisks())
                .build();
    }

}
