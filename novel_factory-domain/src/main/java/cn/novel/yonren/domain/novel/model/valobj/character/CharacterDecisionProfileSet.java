package cn.novel.yonren.domain.novel.model.valobj.character;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 不可变人物设计集；内容hash不含revision。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterDecisionProfileSet(int schemaVersion, String revision, String hash,
                                          Integer effectiveFromChapter, List<CharacterDecisionProfile> profiles) {
    public static final int SCHEMA_VERSION = 1;
    public CharacterDecisionProfileSet {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }
}
