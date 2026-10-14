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
     * <p>为什么要与 {@link #usedSentences} 分开：那本句集上限只有 80 句、约等于一章的量，
     * 于是"隔两章的对白重复"根本进不了视野——实测相隔两章一字不差地
     * 重复了同一句台词，两个窗口都查不到。而对白是全篇最刺眼的重复形态（读者会以为人物在复读），
     * 且每章只有二三十句、句子又短，用一个大得多的窗口单独留存成本极低。
     *
     * <p>老数据该字段为 null（读取侧一律按空表处理）。
     */
    private List<String> usedDialogueLines;

    /**
     * **重复意象全篇计数**（意象短语 → 出现次数，来源 {@code RepetitionWatchlistCatalog}）。
     *
     * <p>与 {@link #fatigueWords} 的分工：那份管**词性套话**（副词/眼神/身体反应，与题材无关）；
     * 本字段管**题材相关的具体意象**——同一件东西被反复用作情绪载体时点名
     * （实测重生年代文里某类前世锚点意象被反复用作情绪载体）。
     *
     * <p>只累加不衰减：超频判定要看**全篇累计**而非窗口——该病症是每隔几章出现一次，
     * 任何有限窗口都会漏掉。
     *
     * <p>老数据该字段为 null（读取侧一律按空表处理）。
     */
    private Map<String, Integer> imageryWords;

}
