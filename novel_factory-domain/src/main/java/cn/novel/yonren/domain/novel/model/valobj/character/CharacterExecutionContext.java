package cn.novel.yonren.domain.novel.model.valobj.character;

import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 执行引用随章检查点回退，作者root工作副本不回退。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterExecutionContext(Integer basisVersion, CharacterDepthSettings settings,
                                        CharacterDecisionProfileSet profiles) {
    public CharacterExecutionContext {
        if (!Integer.valueOf(2).equals(basisVersion) || settings == null) throw new IllegalArgumentException("人物执行版本非法");
        if (profiles != null && (profiles.schemaVersion() != 1 || profiles.revision() == null || profiles.hash() == null
                || !profiles.hash().matches("[a-f0-9]{64}") || profiles.effectiveFromChapter() < 1 || profiles.profiles() == null)) {
            throw new IllegalArgumentException("人物档案版本结构非法");
        }
    }
    public static CharacterExecutionContext from(StoryContextEntity story) {
        return story == null || story.getCharacterDepthSettings() == null ? null
                : new CharacterExecutionContext(2, story.getCharacterDepthSettings(), story.getCharacterDecisionProfiles());
    }
    public String fingerprint() {
        return ChapterManuscript.digest("character-execution-v2\n" + settings.mode() + "\n" + settings.contextMaxChars()
                + "\n" + settings.feedbackMaxChars() + "\n" + settings.maxNewProfilesPerPass() + "\n" + settings.policyVersion()
                + "\n" + (profiles == null ? "ABSENT" : profiles.revision() + "\n" + profiles.hash()));
    }
    public static String bindBasis(String legacyBasis, CharacterExecutionContext context) {
        return context == null ? legacyBasis
                : ChapterManuscript.digest("character-basis-v2\n" + legacyBasis + "\n" + context.fingerprint());
    }
}
