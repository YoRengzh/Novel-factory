package cn.novel.yonren.api.dto;

import java.util.List;
import java.util.Map;

public record StoryAuthoringSnapshotDTO(int schemaVersion, String revision, String hash, int effectiveFromChapter,
                                        Map<String, String> settingsOverrides, List<StoryAuthoringEntryDTO> entries,
                                        String characterDepthMode) { }
