package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 风格统计实体：纯代码统计的机械层风格账本（不经过 LLM）。
 * 跨章逐字重复句 + 疲劳词计数，随正文逐章滚动合并，用于向后续章节注入风格警示。
 * 与三账本同思路：确定性数据，可由落盘文件重建
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StyleStatEntity {

    /** 已出现过的长句全集（去重，滚动窗口上限由服务控制） */
    private List<String> usedSentences;

    /** 跨章逐字重复句（确认重复，供警示渲染） */
    private List<String> repeatedSentences;

    /** 疲劳词全篇计数（词 → 出现次数） */
    private Map<String, Integer> fatigueWords;

    /**
     * 已出现过的**对白句**全集（含引号，去重，滚动窗口远大于 usedSentences）。
     *
     * {@link #usedSentences} 的窗口较小，无法覆盖较远章节的对白重复。
     * 对白句通常较短，独立的大窗口能低成本追踪跨章重复，避免人物反复复述。
     *
     * 老数据该字段为 null（读取侧一律按空表处理）。
     */
    private List<String> usedDialogueLines;

    /**
     * **重复意象计数**（意象短语 → 全篇出现次数），数据源是外置的
     * {@code assets/config/repetition-watchlist.txt}（见 {@code RepetitionWatchlistCatalog}）。
     *
     * 为什么复用本类而不是新开账本：意象计数与疲劳词计数是同一件事的两个尺度——
     * 都靠"跨章逐字统计 + 滚动合并 + 前缀警示"这套设施，且都随正文逐章机械累积、可由落盘文件重建。
     * 新开一份账本要多一套落盘/预载/快照/续写接线，收益为零。
     *
     * 为什么不用摘要模型的语义标注：意象重复是**字面重复**（同一个词被反复用），
     * 机械扫描完全抓得到且零 token 成本；语义标注（如 abilityShowcased）留给"结构重复"那类
     * 字面抓不到的病症。两者分工见 {@code UsedPatternPolicy} 第四节的说明。
     *
     * 老数据该字段为 null（读取侧一律按空表处理）。
     */
    private Map<String, Integer> imageryWords;

}
