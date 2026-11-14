package cn.novel.yonren.api.dto;

import java.util.List;

/** 只读冻结执行引用，随checkpoint恢复，不等同作者工作副本。 */
public record CharacterExecutionContextDTO(Integer basisVersion, CharacterDepthSettingsDTO settings,
                                           ProfileSet profiles) {
    public record ProfileSet(int schemaVersion, String revision, String hash, Integer effectiveFromChapter,
                             List<CharacterDecisionProfileDTO> profiles) { }
}
