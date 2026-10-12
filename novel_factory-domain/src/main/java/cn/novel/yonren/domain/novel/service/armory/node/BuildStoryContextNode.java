package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.prompt.ChapterGoalWindowPolicy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 构建故事上下文节点：将用户输入 + yml 配置拼装成故事上下文
 */
@Service
@Slf4j
public class BuildStoryContextNode extends AbstractArmorySupport {

    @Resource
    private BuildVolumeBlueprintNode buildVolumeBlueprintNode;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return buildVolumeBlueprintNode;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 BuildStoryContextNode - 构建上下文");

        StoryContextEntity storyContextEntity = requestParameter.getStoryContextEntity();
        StoryVO storyVO = requestParameter.getStoryVO();
        StoryVO.Defaults defaults = (storyVO != null ? storyVO.getDefaults() : null);

        // 续写批次渲染全局总章数（历史 + 本批），避免 bible 误导
        int chapterOffset = dynamicContext.getChapterOffset();
        String chapterCountDisplay = chapterOffset > 0
                ? String.valueOf(chapterOffset + storyContextEntity.getChapterCount())
                : String.valueOf(storyContextEntity.getChapterCount());

        StringBuilder sb = new StringBuilder();

        sb.append("你是一名资深网络小说作家，请根据以下信息创作小说章节。\n")
          .append("小说名称：").append(storyContextEntity.getNovel_title()).append("\n")
          .append("小说类型：").append(storyContextEntity.getTheme()).append("\n")
          .append("小说格调：").append(storyContextEntity.getStyle()).append("\n")
          .append("世界观：").append(storyContextEntity.getWorldSetting()).append("\n")
          .append("叙述视角：").append(storyContextEntity.getPerspective()).append("\n")
          .append("目标读者：").append(storyContextEntity.getTargetAudience()).append("\n")
          .append("语气基调：").append(storyContextEntity.getTone()).append("\n")
          .append("主人公：").append(storyContextEntity.getProtagonist()).append("\n");
        // 人物档案（代词与既定事实）：规划与正文两个场景都吃这份「故事设定」，故此处渲染一次即可覆盖两处。
        // 值缺失（老故事/未声明）时整行不出现——不能给模型一个空的"主要人物"栏去猜。
        if (StringUtils.isNotBlank(storyContextEntity.getCharacterProfiles())) {
            sb.append("主要人物（代词与既定事实，全书必须一致，不得改变）\n")
              .append(storyContextEntity.getCharacterProfiles()).append("\n");
        }
        sb.append("故事概述：").append(storyContextEntity.getOutline()).append("\n")
          .append("章节数量：").append(chapterCountDisplay).append("\n")
          .append("章节总体目标：").append(ChapterGoalWindowPolicy.window(
                  storyContextEntity.getChapterGoal(), chapterOffset + 1)).append("\n");

        if (defaults != null) {
            sb.append("语言：").append(defaults.getLanguage()).append("\n");
        }

        dynamicContext.setStoryContextEntity(storyContextEntity);
        dynamicContext.setStoryContext(sb.toString());
        return router(requestParameter, dynamicContext);
    }
}
