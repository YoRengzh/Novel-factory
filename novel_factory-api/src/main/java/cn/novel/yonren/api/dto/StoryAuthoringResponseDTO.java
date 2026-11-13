package cn.novel.yonren.api.dto;

import java.util.List;
import java.util.Map;

/** preview.snapshot 是候选，revision/basis 仍是当前 GET 基线；saved 只有完整持久化成功才为 true。 */
public record StoryAuthoringResponseDTO(Map<String, String> settings, String revision, String basis,
                                        StoryAuthoringSnapshotDTO snapshot, StoryAuthoringSnapshotDTO activeSnapshot,
                                        List<StoryAuthoringSourceDTO> sources, int effectiveFromChapter,
                                        String previewHash, Boolean saved) { }
