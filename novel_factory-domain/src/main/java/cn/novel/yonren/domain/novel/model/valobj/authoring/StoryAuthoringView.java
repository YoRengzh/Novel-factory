package cn.novel.yonren.domain.novel.model.valobj.authoring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读维护视图，工作副本设定与当前激活引用分别展示。 */
public record StoryAuthoringView(Map<String, String> settings, String revision, String basis,
                                 StoryAuthoringSnapshot snapshot, StoryAuthoringSnapshot activeSnapshot,
                                 List<StoryAuthoringSource> sources, int effectiveFromChapter) {
    public StoryAuthoringView {
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        sources = List.copyOf(sources);
    }
}
