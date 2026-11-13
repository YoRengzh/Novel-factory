package cn.novel.yonren.domain.novel.model.valobj.authoring;

import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 作者工作副本与执行引用共用的不可变版本；不替代正文、摘要或原始 bible。 */
public record StoryAuthoringSnapshot(int schemaVersion, String revision, String hash, int effectiveFromChapter,
                                     Map<String, String> settingsOverrides, List<Entry> entries,
                                     String characterDepthMode) {
    public static final int SCHEMA_VERSION = 1;

    public StoryAuthoringSnapshot {
        settingsOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(settingsOverrides)));
        entries = List.copyOf(Objects.requireNonNull(entries));
    }

    public enum Kind { CHARACTER, ITEM, FACTION, FORESHADOW }
    public enum Operation { EXTRACTION_CORRECTION, FUTURE_INTENT }
    public enum Status { AUTHOR_CORRECTED, INTENT }

    /** AUTHOR_CORRECTED 仅表示作者有正文摘录依据，不表示系统已完成语义核验。 */
    public record Entry(String id, Kind kind, String entityName, Operation operation, String before, String after,
                        String reason, Integer sourceChapter, String sourceHash, String evidence,
                        int effectiveFromChapter, Status status) { }

    /** 保留 legacy + character 的历史口径；只有存在激活引用才加入作者版本。 */
    public static String bindBasis(String legacyPlusCharacter, StoryAuthoringSnapshot active) {
        return active == null ? legacyPlusCharacter
                : ChapterManuscript.digest("authoring-basis-v1\n" + legacyPlusCharacter + "\n" + active.hash());
    }
}
