package cn.novel.yonren.domain.novel.model.valobj.authoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** null 表示保留该部分；空数组显式清空条目，空字符串显式清空非必填设定。 */
public record StoryAuthoringPatch(String expectedRevision, String expectedBasis, Map<String, String> settings,
                                  List<StoryAuthoringSnapshot.Entry> entries, String characterDepthMode) {
    public StoryAuthoringPatch {
        settings = settings == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        entries = entries == null ? null : Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
