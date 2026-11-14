package cn.novel.yonren.domain.novel.model.valobj.character;

import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Locale;

/** 人物模式/预算在工作启动时冻结；OFF缺席不改变旧JSON或依据。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterDepthSettings(String mode, int contextMaxChars, int feedbackMaxChars,
                                     int maxNewProfilesPerPass, String policyVersion) {
    public static final String POLICY_VERSION = "character-depth-v1";
    public static final int DEFAULT_CONTEXT_CHARS = 4800;
    public static final int DEFAULT_FEEDBACK_CHARS = 600;
    public static final int DEFAULT_MAX_NEW_PROFILES = 4;
    public CharacterDepthSettings {
        mode = normalizeMode(mode);
        if (contextMaxChars < 800 || contextMaxChars > 16000 || feedbackMaxChars < 0 || feedbackMaxChars > 4000
                || maxNewProfilesPerPass < 1 || maxNewProfilesPerPass > 8) {
            throw new IllegalArgumentException("人物上下文预算800..16000、反馈0..4000、单次新卡1..8");
        }
        if (!POLICY_VERSION.equals(policyVersion)) throw new IllegalArgumentException("不支持的人物策略版本");
    }
    public static CharacterDepthSettings defaults(String mode) {
        return new CharacterDepthSettings(mode, DEFAULT_CONTEXT_CHARS, DEFAULT_FEEDBACK_CHARS,
                DEFAULT_MAX_NEW_PROFILES, POLICY_VERSION);
    }
    public static String normalizeMode(String value) {
        String mode = value == null || value.isBlank() ? "OFF" : value.strip().toUpperCase(Locale.ROOT);
        if (!java.util.List.of("OFF", "OBSERVE", "APPLY").contains(mode)) {
            throw new IllegalArgumentException("characterDepthMode只允许OFF/OBSERVE/APPLY");
        }
        return mode;
    }
    public static String mode(StoryContextEntity story) {
        return story == null ? "OFF" : story.getCharacterDepthSettings() == null
                ? normalizeMode(story.getCharacterDepthMode()) : story.getCharacterDepthSettings().mode();
    }
    public static boolean applies(StoryContextEntity story) { return "APPLY".equals(mode(story)); }
    public static boolean observes(StoryContextEntity story) { return !"OFF".equals(mode(story)); }
    public static int contextLimit(StoryContextEntity story) {
        return story == null || story.getCharacterDepthSettings() == null ? DEFAULT_CONTEXT_CHARS
                : story.getCharacterDepthSettings().contextMaxChars();
    }
    public static int feedbackLimit(StoryContextEntity story) {
        return story == null || story.getCharacterDepthSettings() == null ? DEFAULT_FEEDBACK_CHARS
                : story.getCharacterDepthSettings().feedbackMaxChars();
    }
}
