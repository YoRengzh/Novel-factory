package cn.novel.yonren.infrastructure.adapter.repository;

import java.time.format.DateTimeFormatter;

/**
 * 落盘布局常量：故事产物的目录结构与文件名契约。
 * 仓储私有细节，不下沉到共享层；包内可见，供仓储及后续导出/清理类工具复用
 */
class StorageKeys {

    static final String WORKSPACE_DIR = "docs/workspace/stories";
    static final String CHAPTER_DIR = "chapters";
    static final String RECORD = "generation-records";
    static final String MEMORY_DIR = "memory";
    static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    static final String STORY_BIBLE_FILE = "story-bible.txt";
    static final String CHAPTER_PLAN_FILE = "chapter-plan.yml";
    static final String INPUT_FILE = "input.json";
    static final String OUTPUT_FILE_PREFIX = "output-";
    static final String OUTPUT_FILE_SUFFIX = ".json";
    static final String CHAPTER_FILE_PREFIX = "chapter-";
    static final String CHAPTER_FILE_SUFFIX = ".txt";
    static final String SUMMARIES_FILE = "summaries.json";
    static final String CONSISTENCY_INDEX_FILE = "consistency-index.json";
    static final String QUALITY_DEBT_FILE = "quality-debts.json";
    static final String FORESHADOW_SETTLEMENT_FILE = "foreshadow-settlements.json";

    /**
     * 伏笔兑现排期表（P2b）：与结算台账同为**单开文件**，不动 summaries.json 的顶层数组格式。
     * ⚠️ 必须同时加入 {@code StoryRepository#collectSnapshotSources}——否则审批挂起/崩溃恢复后排期丢失。
     */
    static final String FORESHADOW_SCHEDULE_FILE = "foreshadow-schedule.json";
    static final String STYLE_STAT_FILE = "style-stats.json";
    static final String ROLLING_OUTLINE_FILE = "rolling-outline.json";
    static final String VOLUME_FILE = "volume-blueprint.json";
    static final String JOB_STATUS_FILE = "job-status.json";
    static final String CHECKPOINT_DIR = "checkpoints";
    static final String CHECKPOINT_META_FILE = "meta.json";
    static final String CHECKPOINT_SNAPSHOT_DIR = "snapshot";
    static final String JOB_RUN_PREFIX = "run-job-";
    static final String AUDIT_SAMPLES_FILE = "audit-runtime-samples.jsonl";
    static final String CANDIDATE_SAMPLES_FILE = "candidate-samples.jsonl";

    /**
     * 写手训练样本（水龙头）：每章追加一行 {最终prompt, 最终采纳正文, 门禁结论}。
     * 供后训练自有写手模型用（SFT/DPO/GRPO 原料），见 WriterSampleService。
     */
    static final String WRITER_SAMPLES_FILE = "writer-samples.jsonl";

    /** 全书质量评分趋势（每 N 章追加一行 JSONL；rubric 版本号随行落盘，防止两把尺子的读数混成一条曲线） */
    static final String QUALITY_TREND_FILE = "quality-trend.jsonl";
    /** 机械校验事件流水（伏笔账本失配/种子补齐/降级解析等；单行 JSON、追加写） */
    static final String MECHANICAL_EVENTS_FILE = "mechanical-events.jsonl";
    static final String STYLE_FINGERPRINT_FILE = "style-fingerprints.json";
    /** 完结上限元数据（sticky cap）：首次确立后不可被后续请求抬高 */
    static final String STORY_META_FILE = "story-meta.json";

    private StorageKeys() {
    }

}
