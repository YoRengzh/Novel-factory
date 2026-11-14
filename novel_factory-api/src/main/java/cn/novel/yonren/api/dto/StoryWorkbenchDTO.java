package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单体工作台的只读聚合视图。字段保持为现有文件数据的投影，避免引入新的存储模型。
 */
@Data
public class StoryWorkbenchDTO {

    /** 故事目录名（stories/ 下的目录名） */
    private String storyDirName;

    /** 书名（故事库列表解析值；取不到时回退目录名） */
    private String novelTitle;

    /** 故事概述（取自 story-bible 的"故事概述"行） */
    private String storyOverview;

    /** 已落盘章节数 */
    private int chapterCount;

    /** 当前故事阶段（最新阶段蓝图的 phase：NORMAL/PREPARATION/ESCALATION/WAR/RESOLUTION/EPILOGUE；旧数据缺省 NORMAL） */
    private String storyPhase;

    /** 是否已声明收官卷（一旦为 true，后续蓝图不得回退） */
    private boolean finalVolumeDeclared;

    /** 预估全书总章数（蓝图估算值，非完结条件；旧故事回退 bible 的"章节数量"） */
    private Integer estimatedTotalChapters;

    /** 预估剩余章数下限（蓝图未给时按总章数 ±15% 估算） */
    private Integer estimatedRemainingChaptersMin;

    /** 预估剩余章数上限（同上） */
    private Integer estimatedRemainingChaptersMax;

    /** 收官卷尚未完成的剧情节点 */
    private List<String> remainingFinaleBeats = new ArrayList<>();

    /** 收官卷已完成的剧情节点 */
    private List<String> completedFinaleBeats = new ArrayList<>();

    /** 是否有运行中的生成作业占用该故事（true=生成中，编辑/回滚会被 409 拒绝） */
    private boolean activeJob;

    /** 最新章号（已落盘章节的最大章号）；尚无章节为 null */
    private Integer latestChapterNo;

    /** 最新章正文字符数 */
    private int latestChapterLength;

    /** 角色账本（由各章摘要确定性重建，保证展示与生成上下文一致） */
    private List<LedgerDTO> characters = new ArrayList<>();

    /** 物品账本（同上） */
    private List<LedgerDTO> items = new ArrayList<>();

    /** 势力账本（同上） */
    private List<LedgerDTO> factions = new ArrayList<>();

    /** 未回收伏笔清单（供回收决策与优先级排序） */
    private List<ForeshadowDTO> foreshadowing = new ArrayList<>();

    /** 待人工确认事实（疑似编造/漂移，未入账本，供人工裁决） */
    private List<PendingFactDTO> pendingFacts = new ArrayList<>();

    /** 质量债清单（审校确认且未修复的问题） */
    private List<QualityDebtDTO> qualityDebts = new ArrayList<>();

    /** 工作台统计指标（字数/账本健康度的聚合数） */
    private WorkbenchMetrics metrics = new WorkbenchMetrics();

    private List<CharacterObservationDTO> characterObservations = new ArrayList<>();
    private String causalityPlanMode;
    private String causalityAuditMode;

    public record CharacterObservationDTO(Integer chapterNo, String status, String manuscriptHash, String profileHash,
                                          List<String> observations) { }

    @Data
    public static class ForeshadowDTO {
        /** 伏笔埋设章号 */
        private int chapterNo;
        /** 伏笔内容 */
        private String content;
        /** 回收状态（工作台当前固定为"待回收"） */
        private String status;
        /** 埋设时评估的主线重要度 1-5（老数据为 null） */
        private Integer importance;
    }

    @Data
    public static class LedgerDTO {
        /** 实体名称（角色名/物品名/势力名） */
        private String name;
        /** 当前状态（伤势/修为/位置/持有物/动向等） */
        private String status;
        /** 首次出现章节号 */
        private Integer firstChapterNo;
        /** 最近一次被提及的章节号（每次提及都会刷新；与 firstChapterNo 相等即全篇只出现一次） */
        private Integer lastChapterNo;
        /** 最近一次真实状态变化前的状态（同值重复更新不记录；首次登场为 null） */
        private String prevStatus;
        /** 当前状态最新一次的正文证据原文引用（摘要侧已过机械校验；缺省路径为 null） */
        private String evidence;
        /** 死亡→非死亡且无明确复活描写的反转存疑标记（前端据此追加"复活存疑"提示） */
        private boolean reversalSuspect;
    }

    @Data
    public static class QualityDebtDTO {
        /** 产生该债务的章号（全局编号） */
        private Integer chapterNo;
        /** 审校确认且未修复的问题（维度/证据/描述/建议） */
        private Object issues;
        /** 是否已修复（验证式自动核销，或人工标记） */
        private boolean resolved;
        /** 连续无同维度复发的审校章数（达到阈值自动核销） */
        private int cleanStreak;
    }

    @Data
    public static class WorkbenchMetrics {
        /** 全书总字数（字符口径；当前赋值与 totalWords 同值） */
        private int totalCharacters;
        /** 全书总字数 */
        private int totalWords;
        /** 平均每章字数 */
        private int averageChapterWords;
        /** 一致性冲突条数（各章"与当前账本冲突的正文偏差"累计） */
        private int continuityConflictCount;
        /** 待人工确认事实数 */
        private int pendingFactCount;
        /** 未解决质量债数 */
        private int unresolvedQualityDebtCount;
        /** 未回收伏笔数 */
        private int unresolvedForeshadowCount;
    }
}
