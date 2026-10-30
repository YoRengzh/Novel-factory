package cn.novel.yonren.domain.novel.adapter.repository;

import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.entity.CheckpointEntity;
import cn.novel.yonren.types.enums.CheckpointType;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;

import java.nio.file.Path;
import java.util.List;

/**
 * 故事持久化仓储端口
 */
public interface IStoryRepository {

    /**
     * 创建当天 story 目录（含 chapters/ 和 generation-records/ 子目录）。
     * 序号分配为 CREATE_NEW 语义，并发调用不会分配到同一目录
     */
    Path createStoryDirectory() throws Exception;

    /**
     * 写基础信息文件 story-bible.txt。bible 为故事级不可变信息，仅首次写入生效，
     * 已存在时跳过（续写不覆盖）
     */
    void writeStoryBible(Path storyDir, ArmoryCommandEntity command) throws Exception;

    /** 读取故事级设定与概述；文件不存在返回空串。 */
    String readStoryBible(Path storyDir) throws Exception;

    /**
     * 分配本批次的 run 目录（generation-records/run-XXXX，CREATE_NEW 语义并发安全），
     * 章节计划与生成记录按批写入各自 run 目录，续写批次互不覆盖
     */
    Path createRunDirectory(Path storyDir) throws Exception;

    /**
     * 分配本批次的 run 目录；jobId 非空时目录名为 run-job-<jobId>（runId 与 jobId 同源，
     * 冲突即异常不重试——jobId 全局唯一），供异步作业按 job 定位本批产物
     */
    Path createRunDirectory(Path storyDir, String jobId) throws Exception;

    /**
     * 写作业状态快照到故事目录根 job-status.json（原子写 + 每故事锁）。
     * 观测性文件而非记忆文件：调用方应 fail-soft，写失败不得反噬生成流程
     */
    void writeJobStatus(Path storyDir, GenerationJob job) throws Exception;

    /**
     * 读作业状态快照；文件不存在返回 null。
     * 与记忆文件不同，损坏/不可读时返回 null + warn（软失败）：
     * 该文件仅供崩溃后人工查看进度，不参与生成管线，无记忆同等硬失败的必要。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    GenerationJob readJobStatus(Path storyDir) throws Exception;

    /**
     * 写章节计划到 runDir/chapter-plan.yml
     */
    void writeChapterPlan(Path runDir, ChapterPlanAggregate chapterPlan) throws Exception;

    /**
     * 写每章正文到 chapters/chapter-XXXX.txt
     */
    void writeChapters(Path storyDir, List<ChapterContentEntity> chapterContents) throws Exception;

    /**
     * 写章节记忆摘要到 memory/summaries.json（角色/物品/势力账本与伏笔账可由摘要重建，不单独落盘）
     */
    void writeChapterSummaries(Path storyDir, List<ChapterSummaryEntity> summaries) throws Exception;

    /** 写跨章一致性索引到 memory/consistency-index.json，不替代三账本。 */
    void writeConsistencyIndex(Path storyDir, ConsistencyIndexEntity index) throws Exception;

    /** 读取跨章一致性索引；文件不存在返回空索引。 */
    ConsistencyIndexEntity readConsistencyIndex(Path storyDir) throws Exception;

    /**
     * 读取已有故事目录的记忆摘要（续写时预载，滚动账本由此重建）；文件不存在返回空列表；
     * 文件存在但损坏/不可读时抛 AppException（硬失败，禁止静默丢弃历史记忆）。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象，仓储读写统一使用同一对象
     */
    List<ChapterSummaryEntity> readChapterSummaries(Path storyDir) throws Exception;

    /**
     * 写质量债清单到 memory/quality-debts.json（审校确认且未修复的 BLOCKING 问题）
     */
    void writeQualityDebts(Path storyDir, List<QualityDebtEntity> debts) throws Exception;

    /**
     * 读取已有故事目录的质量债清单（续写时预载）；文件不存在返回空列表；
     * 文件存在但损坏/不可读时抛 AppException（硬失败）。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    List<QualityDebtEntity> readQualityDebts(Path storyDir) throws Exception;

    /**
     * 落盘卷末清账结算到 memory/foreshadow-settlements.json（阶段出口未填伏笔裁决的审计台账，幂等覆盖写）
     */
    void writeForeshadowSettlements(Path storyDir, List<ForeshadowSettlementEntity> settlements) throws Exception;

    /**
     * 写伏笔兑现排期表（P2b，单开文件 {@code memory/foreshadow-schedule.json}）。
     * 空列表不写（与结算台账一致：避免把已有台账清成空文件）。
     */
    void writeForeshadowSchedule(Path storyDir, List<ForeshadowScheduleEntity> schedules) throws Exception;

    /**
     * 读取已有故事目录的卷末清账结算（续写时预载，VOID 条目据此对齐账本）；文件不存在返回空列表；
     * 文件存在但损坏/不可读时抛 AppException（硬失败）。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    List<ForeshadowSettlementEntity> readForeshadowSettlements(Path storyDir) throws Exception;

    /** 读伏笔兑现排期表；文件不存在返回空列表（老故事续写天然兼容） */
    List<ForeshadowScheduleEntity> readForeshadowSchedule(Path storyDir) throws Exception;

    /**
     * 写风格统计到 memory/style-stats.json（跨章重复句 + 疲劳词计数）
     */
    void writeStyleStat(Path storyDir, StyleStatEntity styleStat) throws Exception;

    /**
     * 读取已有故事目录的风格统计（续写时预载）；文件不存在返回 null；
     * 文件存在但损坏/不可读时抛 AppException（硬失败）。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    StyleStatEntity readStyleStat(Path storyDir) throws Exception;

    /**
     * 写阶段蓝图链到 memory/rolling-outline.json（滚动大纲，全链保存供漂移审计，幂等覆盖写）
     */
    void writeStageBlueprints(Path storyDir, List<StageBlueprintEntity> blueprints) throws Exception;

    /**
     * 读取已有故事目录的阶段蓝图链（续写时预载）；文件不存在返回空列表；
     * 文件存在但损坏/不可读时抛 AppException（硬失败）。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    List<StageBlueprintEntity> readStageBlueprints(Path storyDir) throws Exception;

    /**
     * 写卷蓝图链到 memory/volume-blueprint.json（全书长期锚，全链保存供方向检索，幂等覆盖写）
     */
    void writeVolumes(Path storyDir, List<VolumeBlueprintEntity> volumes) throws Exception;

    /**
     * 读取已有故事目录的卷蓝图链（续写时预载）；文件不存在返回空列表；
     * 文件存在但损坏/不可读时抛 AppException（硬失败）。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    List<VolumeBlueprintEntity> readVolumes(Path storyDir) throws Exception;

    /**
     * 解析续写目录并做越界防护：目录名仅接受 yyyyMMdd-story-XXXX 格式（免疫 ../ 与绝对路径），
     * 真实路径（解析符号链接）校验位于 workspace 根下；通过后返回 Path 并确保 memory/ 子目录存在。
     * 格式非法/目录不存在/越界均抛 AppException（硬失败）。仓储所有按目录名读写共用此校验入口
     */
    Path resolveStoryDirectory(String storyDirName) throws Exception;

    /**
     * 读取指定故事目录下编号最大的章节正文全文（供续写衔接取上一章结尾）；无章节文件返回 null。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    String readLatestChapterContent(Path storyDir) throws Exception;

    /**
     * 读取指定故事目录的章节文件编号（升序列表，无章节文件返回空列表），
     * 供续写锁步校验：正文文件与记忆摘要的最大编号必须一致且编号连续。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    List<Integer> readChapterNumbers(Path storyDir) throws Exception;

    /**
     * 写生成记录到 runDir：input.json + 每章一个 output-XXXX.json
     */
    void writeGenerationRecord(Path runDir, ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception;

    /**
     * 追加一行审校失败样本（JSON 行）到 memory/audit-runtime-samples.jsonl（观测文件，追加语义，
     * 供校准 harness 复检）。调用方应 fail-soft：写失败不得反噬生成流程。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    void appendAuditSample(Path storyDir, String jsonlLine) throws Exception;

    /**
     * 追加一行候选选优样本（JSON 行）到 memory/candidate-samples.jsonl（观测文件，追加语义，
     * 供人工盲选胜率复盘与评审 prompt 校准）。调用方应 fail-soft：写失败不得反噬生成流程。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    void appendCandidateSample(Path storyDir, String jsonlLine) throws Exception;

    /**
     * 追加一行写手训练样本（JSON 行）到 memory/writer-samples.jsonl（观测文件，追加语义，
     * 供后训练自有写手模型：SFT 范文对 / 与 candidate-samples 关联出 DPO 偏好对 / GRPO 规则奖励）。
     * 调用方应 fail-soft：写失败不得反噬生成流程。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    void appendWriterSample(Path storyDir, String jsonlLine) throws Exception;

    /**
     * 追加一行全书质量评分到 memory/quality-trend.jsonl（观测文件，追加语义）。
     * 每行是一个评分窗口的汇总：rubric 版本号 + 抽样各章维度分 + 窗口机械指标。
     * 调用方应 fail-soft：评分或落盘失败都不得反噬生成流程——度量工具坏了不该拖垮生产线。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    void appendQualityTrend(Path storyDir, String jsonlLine) throws Exception;

    /**
     * 读回 {@code memory/quality-trend.jsonl} 的全部行（**最新一窗在末位**）。
     *
     * <p>补这个读入口的原因：此前该文件是**只写不读**的——每 10 章花一次
     * 强模型抽 3 章打分，分数落盘后没有任何一处消费，等于信息买回来了直接扔掉。
     * 文件缺失返回空表（新故事尚未触发评分）；单行损坏跳过（观测文件允许尾部半行）。
     */
    List<String> readQualityTrend(Path storyDir) throws Exception;

    /**
     * 追加一条机械校验事件到 memory/mechanical-events.jsonl（观测文件，追加语义）。
     * 事件为单行 JSON（必带 type 字段）；调用方应 fail-soft：写失败不得反噬生成流程。
     */
    void appendMechanicalEvent(Path storyDir, String jsonlLine) throws Exception;

    /**
     * 读候选选优样本的 **outcome 清单**（观测层统计触发率/采纳率用，不回传正文避免大对象）。
     * 文件缺失返回空表；单行损坏跳过（观测性文件，允许尾部半行）。
     */
    List<String> readCandidateOutcomes(Path storyDir) throws Exception;

    /**
     * 读候选选优样本的**原始 JSONL 行**（2026-10-03 新增）：与 {@link #readCandidateOutcomes} 同源同序，
     * 但保留 {@code chapterNo}——重跑批次时同一章会追加多行，统计层必须按章去重取**每章最后一行**，
     * 否则旧批次的行会把触发率推到 100% 以上。文件缺失返回空表；单行损坏跳过。
     */
    List<String> readCandidateSampleLines(Path storyDir) throws Exception;

    /**
     * 写文风指纹库到 memory/style-fingerprints.json（审校全票通过章节的高信息密度句，滚动上限 50）
     */
    void writeStyleFingerprints(Path storyDir, List<String> sentences) throws Exception;

    /**
     * 读取已有故事目录的文风指纹库（续写时预载）；文件不存在返回 null；
     * 文件存在但损坏/不可读时抛 AppException（硬失败）。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    List<String> readStyleFingerprints(Path storyDir) throws Exception;

    /**
     * 列出 workspace 下所有故事目录的摘要信息（六期故事库列表）。
     * 标题取 bible 首行 "小说名称: xxx"，章数取 chapters/ 下文件数，lastModifiedMs 取最新文件时间
     */
    List<StorySummary> listStories() throws Exception;

    /**
     * 读取指定章号正文（六期阅读/编辑）。按首行 "^第(\d+)章\s?(.*)$" 拆标题/正文；
     * 文件不存在返回 null
     */
    ChapterContentEntity readChapter(Path storyDir, int chapterNo) throws Exception;

    /**
     * 读取指定章号的计划条目（六期编辑回流用）。扫 generation-records 下所有 run 子目录的
     * output-XXXX.json，多命中取目录名字典序最大者（最新批次优先）；无命中返回 null
     */
    ChapterPlanItemEntity readChapterPlanItem(Path storyDir, int chapterNo) throws Exception;

    /**
     * 写完结上限元数据（story-meta.json，sticky cap）。幂等：文件已存在时不覆盖——
     * 完结上限在故事首次确立后不可被后续请求抬高，续写共享同一本就不具备推翻能力
     */
    void writeStoryMeta(Path storyDir, StoryMeta meta) throws Exception;

    /**
     * 读取完结上限元数据；文件不存在返回 null；存在但损坏/不可读时抛 AppException（硬失败）。
     * storyDir 为 resolveStoryDirectory 校验后的路径对象
     */
    StoryMeta readStoryMeta(Path storyDir) throws Exception;

    /** 故事库列表条目 */
    record StorySummary(String storyDirName, String novelTitle, int chapterCount, long lastModifiedMs) {
    }

    /** 完结上限元数据：maxChapterCount 为全书总章数硬上限（>0 时生效） */
    record StoryMeta(int maxChapterCount) {
    }

    /**
     * 列出故事全部检查点（按 versionNo 升序）。缺目录返回空列表；
     * 存在但个别损坏时抛 AppException（硬失败，与记忆文件同等语义）
     */
    List<CheckpointEntity> listCheckpoints(Path storyDir) throws Exception;

    /**
     * 读取单个检查点元数据；不存在返回 null；损坏抛 AppException（硬失败）
     */
    CheckpointEntity readCheckpoint(Path storyDir, String checkpointId) throws Exception;

    /**
     * 创建检查点：在章边界对 story 目录可变资产（正文/摘要/蓝图/质量债/伏笔结算/风格/bible）
     * 做快照，写 meta.json，并同锁内按环形保留回收最旧。
     * type=MANUAL 时 name 必填；整体包在每故事锁内，写失败抛 Exception
     */
    String snapshotCheckpoint(Path storyDir, CheckpointType type, String name) throws Exception;

    /**
     * 回滚到指定检查点：还原 manifest 文件 + 裁剪超出检查点章号的正文；
     * 恢复后 chapters 与 summaries 对齐为 1..chapterCount，续写锁步校验天然通过。
     * 全程持每故事锁，不产生半一致状态；检查点不存在抛 AppException（硬失败）
     */
    void restoreCheckpoint(Path storyDir, String checkpointId) throws Exception;

    /**
     * 环形保留回收：按 versionNo 升序保留最近 N 个，删除更旧的检查点目录。
     * 供 snapshotCheckpoint 内部调用，也可供运维显式触发；写语义
     */
    void pruneCheckpoints(Path storyDir) throws Exception;

    /**
     * **删除整个故事目录**（故事库的删除操作，不可恢复）。
     *
     * <p>写语义：持该故事的**每故事锁**执行——删除是最重的写操作，不持锁会与正在落盘的
     * 章节写入竞争（半个目录被删、写操作继续创建新文件）。调用方负责在删除前确认该故事
     * **没有运行中的作业**（{@code JobRegistry.isActiveForStory}），仓储层看不到作业状态。
     *
     * <p>storyDir 为 resolveStoryDirectory 校验后的路径对象（校验已保证它位于 workspace 下且
     * 是直接子目录，不会误删 workspace 之外的路径）
     */
    void deleteStory(Path storyDir) throws Exception;

}
