package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.worker.ChapterWorker;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 逐章生成正文节点：委托 ChapterWorker 执行逐章循环
 * （循环体/四级降级/记忆组装/审校修订/检查点已拆至 worker 与 quality 包）。
 * 循环开始前执行计划检查点：chapter-plan 在正文生成前即落盘，中途崩溃可凭磁盘计划续写，
 * 不再依赖树尾持久化（此前计划仅在树尾写入，批中崩溃会丢失计划文件）
 */
@Service
@Slf4j
public class GenerateChapterContentNode extends AbstractArmorySupport {

    @Resource
    private ChapterWorker chapterWorker;

    @Resource
    private PersistChapterPlanNode persistChapterPlanNode;

    @Resource
    private IStoryRepository storyRepository;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return persistChapterPlanNode;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 GenerateChapterContentNode - 逐章生成正文（携带滚动记忆）");
        preparePlanCheckpoint(dynamicContext);
        chapterWorker.generateAll(requestParameter, dynamicContext);
        return router(requestParameter, dynamicContext);
    }

    /**
     * 计划检查点（包级可见供单测）：计划校验通过后、正文循环开始前落盘 chapter-plan。
     * storyId 兜底分配、故事目录/run 目录一次性创建并存入 DynamicContext——树尾持久化复用，
     * 不再重复创建（run 目录创建非幂等：作业路径冲突即抛，同步路径为序号递增）。
     * 本检查点是增强件：任一步失败仅告警，树尾持久化兜底，不阻塞正文生成
     */
    void preparePlanCheckpoint(DefaultArmoryFactory.DynamicContext dynamicContext) {
        ChapterPlanAggregate chapterPlanAggregate = dynamicContext.getChapterPlanAggregate();
        if (chapterPlanAggregate == null) {
            return;
        }
        PersistChapterPlanNode.ensureStoryId(chapterPlanAggregate);
        try {
            Path storyDir = dynamicContext.getStoryDir();
            if (storyDir == null) {
                storyDir = storyRepository.createStoryDirectory();
                dynamicContext.setStoryDir(storyDir);
            }
            // run 目录复用已存在的：createRunDirectory 非幂等，
            // 同一 jobId 二次创建会抛冲突；与树尾持久化节点「有则复用」的口径一致。
            // 审批挂起后恢复执行时本方法会被再调一次，复用是必需而非优化
            Path runDir = dynamicContext.getRunDir();
            if (runDir == null) {
                runDir = storyRepository.createRunDirectory(storyDir,
                        dynamicContext.getJob() == null ? null : dynamicContext.getJob().getJobId());
                dynamicContext.setRunDir(runDir);
            }
            if (dynamicContext.getJob() != null) {
                dynamicContext.getJob().setRunDirName(runDir.getFileName().toString());
                // 故事目录一经创建即回填作业：前端靠它定位计划与正文，
                // 不能等到链尾持久化才填（挂起根本走不到链尾）
                dynamicContext.getJob().setStoryDirName(storyDir.getFileName().toString());
            }
            storyRepository.writeChapterPlan(runDir, chapterPlanAggregate);
            log.info("章节计划检查点已落盘: {}", runDir.toAbsolutePath());
        } catch (Exception e) {
            // 计划提前落盘失败不阻塞生成，树尾持久化兜底
            log.warn("章节计划提前落盘失败（树尾持久化兜底）", e);
        }
    }

}
