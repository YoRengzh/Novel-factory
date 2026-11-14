package cn.novel.yonren.api.dto;

import java.util.List;
import java.util.Map;

public record StoryAuthoringSnapshotDTO(int schemaVersion, String revision, String hash, int effectiveFromChapter,
                                        Map<String, String> settingsOverrides, List<StoryAuthoringEntryDTO> entries,
                                        String characterDepthMode, CharacterDepthSettingsDTO characterDepthSettings,
                                        List<CharacterDecisionProfileDTO> characterProfiles,
                                        CharacterExecutionContextDTO characterExecution) {
    public StoryAuthoringSnapshotDTO(int schemaVersion, String revision, String hash, int effectiveFromChapter,
                                     Map<String, String> settingsOverrides, List<StoryAuthoringEntryDTO> entries,
                                     String characterDepthMode) {
        this(schemaVersion, revision, hash, effectiveFromChapter, settingsOverrides, entries,
                characterDepthMode, null, null, null);
    }
}
