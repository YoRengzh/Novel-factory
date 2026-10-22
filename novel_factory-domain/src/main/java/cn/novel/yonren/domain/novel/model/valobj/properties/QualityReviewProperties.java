package cn.novel.yonren.domain.novel.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全书质量评分（2026-09-27）：每 N 章按固定 rubric 抽样给近期正文打分，连同机械指标落成趋势线。
 *
 * <p><b>为什么需要</b>：项目此前只有两类观测——逐章机械门禁（{@code quality/*Policy}）与批级体检
 * （{@code BatchHealthReport}），它们回答的都是"这一章有没有坏"。没有一个机制回答"这本书在变好还是变差"。
 * 缺了这层度量，任何 prompt / 模型 / 预算调整都无法归因，只能凭感觉判断，也就退化成"换模型赌一把"。
 *
 * <p><b>rubric 版本号是关键</b>：分数只有在同一把尺子下才可比。改动评分维度、权重或判据措辞后
 * 必须递增 {@code rubricVersion}，否则趋势线会把两把尺子的读数混成一条曲线——这与项目里
 * "疲劳词原稿/修订稿双尺"踩过的坑是同一类错误。
 */
@Data
@Component
@ConfigurationProperties(prefix = "story.quality-review")
public class QualityReviewProperties {

    /** 总开关：关闭后不评分、不写趋势文件（零 LLM 成本） */
    private boolean enabled = true;

    /** 评分窗口长度（章）：每完成这么多章评一次 */
    private int intervalChapters = 10;

    /**
     * 单次窗口内的抽样章数：在窗口内均匀取值，避免只评最近几章而漏掉窗口前段。
     * 达到或超过窗口长度则逐章全评（小窗口下没必要抽样）。
     */
    private int sampleSize = 3;

    /** 单章送入评分的正文字符上限（超长章按头部截断，避免把 prompt 撑爆） */
    private int maxChapterChars = 6000;

    /** 量表版本号：任何影响分数的改动都必须递增它 */
    private String rubricVersion = "v1";
}
