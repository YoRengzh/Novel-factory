package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.*;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/** 阶段新增明确人物时有界补卡，已有作者卡和锁字段保留。 */
@Service
public class CharacterStageProfileService {
    @Resource private CharacterDecisionDesignService designer;
    @Resource private IStoryRepository repository;

    public void ensure(ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext context, StageBlueprintEntity stage, int start) throws Exception {
        var story = command.getStoryContextEntity();
        if (!CharacterDepthSettings.applies(story) || story.getCharacterDepthSettings() == null || stage == null) return;
        var current = story.getCharacterDecisionProfiles();
        var known = current == null ? List.<String>of() : current.profiles().stream().map(CharacterDecisionProfile::displayName).toList();
        List<String> targets = stage.getCastFocus() == null ? List.of() : stage.getCastFocus().stream()
                .filter(java.util.Objects::nonNull).map(String::strip).filter(name -> name.matches("[\\p{L}][\\p{L}\\p{M} .·’'-]{0,79}"))
                .filter(name -> !List.of("主角", "主人公", "配角", "众人", "反派").contains(name) && !known.contains(name))
                .distinct().limit(story.getCharacterDepthSettings().maxNewProfilesPerPass()).toList();
        if (targets.isEmpty()) return;
        var designed = designer.design(command, context, targets);
        CharacterDecisionDesignService.ensureRunning(context);
        if (designed.profiles().isEmpty()) return;
        var adopted = CharacterDecisionProfileService.adoptAuto(current, designed.profiles(), start);
        story.setCharacterDecisionProfiles(adopted);
        if (context.getStoryDir() != null) repository.writeActiveCharacterExecution(context.getStoryDir(), CharacterExecutionContext.from(story));
    }
}
