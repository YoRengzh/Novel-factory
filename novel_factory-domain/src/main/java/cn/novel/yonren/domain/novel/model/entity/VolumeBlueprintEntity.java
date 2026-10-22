package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 卷蓝图：全书长期锚（顶部一次性规划）。仅在卷边界生成一版、落盘后不再随进度滚动微调，
 * 与弧/章的滚动解耦——这是治长线漂移的"北极星"下一级固化层。
 * 卷 = 一次剧情移动（承转合），由若干"弧"组成；弧即现有 StageBlueprintEntity（30-80 章窗口）。
 * 与弧对应，卷终点也由模型按剧情移动自定、机械钳制在 MIN/MAX_VOLUME_LENGTH 章之间；
 * 弧终点触到卷底（endChapter）意味着本卷剧情移动落定，需生成下一卷。
 * 生成注入"硬性完结上限"时，距上限不足一个卷规模即强制本卷为末卷、承转合按收官收敛排布。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolumeBlueprintEntity {

    /** 卷号，1 起 */
    private Integer volumeNo;

    /** 卷名 */
    private String title;

    /** 覆盖起始章 */
    private Integer startChapter;

    /** 覆盖终点：一次剧情移动（承转合）落定的收束点，模型自定 + 机械钳制 */
    private Integer endChapter;

    /** 卷主旨：卷结束时主角/世界应到达的状态转变（1-2 句） */
    private String themeShift;

    /** 卷级"承转合"大节拍（2-4 步，每步一句）；弧据此标注自己在卷中哪一步 */
    private List<String> beats;

    /** 卷内弧清单（arcNo + 一句目标） */
    private List<ArcPlan> arcPlan;

    /** 卷结束条件（机械可核验谓词，卷尾核验） */
    private List<String> volumeExitConditions;

    /** 卷出口核验结果（随落盘审计） */
    private List<ExitConditionResult> volumeExitResults;

    /** 卷级长线伏笔种子（卷尾统一回收） */
    private List<String> seeds;

    /** 卷内单弧规划 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArcPlan {
        private Integer arcNo;
        private String oneLineGoal;
        /**
         * 弧预计章数（2026-09-16 #5 补齐）：让"本弧还剩几章"可以被机械回答，
         * 阶段窗口与弧预算才能对齐。各弧之和应约等于卷章数（生成期仅告警，不做硬约束）
         */
        private Integer estimatedChapters;
        /** 弧转折点（一句话，可选）：阶段规划与出口条件可对照它安排中段升级 */
        private String turningPoint;
    }

    /** 卷出口核验结果（对标阶段蓝图同构结果） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExitConditionResult {
        private String condition;
        private Boolean met;
        private String evidence;
        private String note;
    }
}