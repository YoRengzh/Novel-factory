package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 设计字段整体入选，不截断条件句；设计卡不进入事实账本。 */
@Service
public class CharacterContextAssembler {
    public record Assembly(String text, List<String> visibleFieldIds, List<String> diagnostics) { }

    public Assembly assemble(StoryContextEntity story, Collection<String> characters, int chapterNo) {
        if (!CharacterDepthSettings.applies(story)) return new Assembly("", List.of(), List.of());
        var profiles = story.getCharacterDecisionProfiles();
        if (profiles == null || profiles.profiles().isEmpty()) return new Assembly("", List.of(), List.of("NO_PROFILES"));
        CharacterDecisionProfileService.validatePersisted(profiles);
        if (profiles.effectiveFromChapter() > chapterNo) return new Assembly("", List.of(), List.of("NOT_YET_EFFECTIVE"));
        String guidance = "\n使用卡字段时，scenes.causality.actors.profileRef 引用 characterId/revision/contentHash/fields；步骤的 action/because 写选择和理由，steps.choice 可补 valueConflict/foregoneOption/cost/expectedRelationshipImpact。不伪造历史 factId。\n";
        int limit = CharacterDepthSettings.contextLimit(story) - guidance.length();
        StringBuilder content = new StringBuilder("\n\n【人物决策卡·设计依据而非已发生事实】\n档案版本：")
                .append(profiles.revision()).append(" · hash：").append(profiles.hash())
                .append("\n人物的欲望、价值、底线和决策习惯参与选择，不覆盖已有认知和能力；不要求全员成长、互动或付出代价。\n");
        List<String> visible = new ArrayList<>(), diagnostics = new ArrayList<>();
        for (var profile : profiles.profiles()) {
            if (characters != null && !characters.isEmpty() && characters.stream().noneMatch(name ->
                    Objects.equals(name, profile.displayName()) || Objects.equals(name, profile.characterId()))) continue;
            String heading = "\n" + profile.displayName() + " [" + profile.characterId() + "]\n";
            if (content.length() + heading.length() > limit) { diagnostics.add("PROFILE_OMITTED:" + profile.characterId()); continue; }
            content.append(heading);
            for (var field : CharacterDecisionProfileService.fieldEntries(profile)) {
                String rendered = field.label() + " [" + field.key() + "]：" + field.value() + "\n";
                if (content.length() + rendered.length() > limit) { diagnostics.add("FIELD_OMITTED:" + profile.characterId() + ":" + field.key()); continue; }
                content.append(rendered);
                visible.add(profile.characterId() + ":" + field.key());
            }
        }
        if (visible.isEmpty()) return new Assembly("", List.of(), diagnostics.isEmpty() ? List.of("NO_VISIBLE_FIELDS") : List.copyOf(diagnostics));
        content.append(guidance);
        return new Assembly(content.toString(), List.copyOf(visible), List.copyOf(diagnostics));
    }
}
