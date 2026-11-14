package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.character.CharacterProfilePlanningService;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class EnsureCharacterDecisionProfilesNode extends AbstractArmorySupport {
    @Resource private CharacterProfilePlanningService profiles;
    @Resource private BuildVolumeBlueprintNode next;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(
            ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext context) {
        return next;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext context) throws Exception {
        profiles.ensure(command, context);
        context.setStoryContextEntity(command.getStoryContextEntity());
        return router(command, context);
    }
}
