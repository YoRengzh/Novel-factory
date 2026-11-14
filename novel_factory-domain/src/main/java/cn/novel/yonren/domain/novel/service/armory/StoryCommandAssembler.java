package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DTO → 领域命令装配（trigger 层工具）：同步与异步两个 controller 共用，
 * storyVO 未显式传入时用 yml 默认配置补齐；worldId 在此做入口校验（集合名约束）
 *
 * 无状态、纯函数式（静态方法 + 私有构造），因此不做 Spring Bean，
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
        String depthMode = cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthSettings
                .normalizeMode(request.getCharacterDepthMode());
        // OFF保留缺席，与历史序列化及basis一致；显式非OFF只在启动时冻结。
        if (!"OFF".equals(depthMode)) storyContextEntity.setCharacterDepthMode(depthMode);

        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .storyContextEntity(storyContextEntity)
                .resumeStoryDir(request.getResumeStoryDir())
                .maxChapterCount(request.getMaxChapterCount())
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

}
