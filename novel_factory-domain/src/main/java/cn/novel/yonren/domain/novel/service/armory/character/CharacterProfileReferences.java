package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import java.util.List;
import java.util.Objects;

/** 只检查已经声明的档案引用，不把缺省causality变成逐场配额。 */
public final class CharacterProfileReferences {
    private CharacterProfileReferences() { }
    public static void validate(StoryContextEntity story, ChapterPlanItemEntity plan) {
        if (plan.getScenes() == null) return;
        for (var scene : plan.getScenes()) {
            if (scene.causality() == null || scene.causality().actors() == null) continue;
            for (var actor : scene.causality().actors()) {
                if (actor == null || actor.profileRef() == null) continue;
                var ref = actor.profileRef();
                var set = story == null ? null : story.getCharacterDecisionProfiles();
                if (set == null || !Objects.equals(ref.revision(), set.revision()) || !Objects.equals(ref.contentHash(), set.hash())
                        || set.effectiveFromChapter() > plan.getChapterNo()) throw new IllegalStateException("CHARACTER_PROFILE_REFERENCE_VERSION_MISMATCH");
                var card = set.profiles().stream().filter(profile -> Objects.equals(profile.characterId(), ref.characterId())
                        && Objects.equals(profile.displayName(), actor.character())).findFirst().orElse(null);
                if (card == null || ref.fields() == null || ref.fields().isEmpty()) throw new IllegalStateException("CHARACTER_PROFILE_REFERENCE_IDENTITY_MISMATCH");
                var fields = CharacterDecisionProfileService.fieldEntries(card).stream().map(field -> field.key()).toList();
                if (!fields.containsAll(ref.fields())) throw new IllegalStateException("CHARACTER_PROFILE_REFERENCE_FIELD_MISMATCH");
            }
        }
    }
}
