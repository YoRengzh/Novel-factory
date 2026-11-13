package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.exception.PlanApprovalSuspendedException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 章节计划审批门（human-in-the-loop）。
 *
 * 位置：{@code ValidateChapterPlanNode} 与 {@code GenerateChapterContentNode} 之间——
 * 此时计划已解析并通过结构校验、正文一章未写，是唯一"拦得住"的时点。
 *
 * 开启时：先把本章计划落盘（复用正文节点的检查点逻辑），再把作业转为
 * {@code AWAITING_APPROVAL} 并抛出 {@link PlanApprovalSuspendedException} —— 这是控制信号而非错误，
 * worker 捕获后不置失败、直接归还线程，等人工裁决或超时后再重新入队续跑剩余阶段。
 *
 * 为什么不是真阻塞：{@code jobExecutor} 是单线程池（避免 LLM 并发花费与故事目录竞争），
 * 在工作线程里 {@code await} 十分钟会把全部排队作业（含自续批子作业）一起冻住。
 *
 * 关闭时（默认）：{@code get()} 直连正文生成节点，节点链与历史行为逐字节一致。
 *
 * 局限：跨阶段批次的章节计划是"惰性分段"的——本节点只拦到首段计划，
 * 后续段在正文推进到段边界时才由 worker 即时规划，因此那部分天然无法预先裁决。
 * 单段批次（批大小 ≤ 阶段跨度）则是完整计划，这也是本门价值最大的场景。
 */
@Service
@Slf4j
public class PlanApprovalGateNode extends AbstractArmorySupport {

    @Resource
    private StoryProperties storyProperties;

    @Resource
    private GenerateChapterContentNode generateChapterContentNode;

    /**
     * 无论放行还是挂起，下一跳都是正文生成节点：挂起走的是 doApply 内的提前抛出，
     * 不会走到这里；因此本方法保持恒定，让"开启=直连"与"关闭=直连"共用同一条边
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return generateChapterContentNode;
    }

    @Override
    protected StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        if (shouldSuspend(requestParameter, dynamicContext) && suspendForApproval(dynamicContext)) {
            GenerationJob job = dynamicContext.getJob();
            throw new PlanApprovalSuspendedException("章节计划审批门挂起：作业 " + job.getJobId()
                    + " 等待人工裁决，截止 " + job.getPlanApprovalDeadlineMs());
        }
        return router(requestParameter, dynamicContext);
    }

    /**
     * 是否应该挂起：（1）门已开启；（2）本批计划尚未裁决过；（3）异步作业路径；（4）批次在作用范围内。
     * 同步调试端点（无 job）刻意不支持——它会阻塞到全批结束，挂起必然撞 HTTP/网关超时
     */
    /** 兼容重载：无请求上下文时按配置判定（旧调用点/单测） */
    boolean shouldSuspend(DefaultArmoryFactory.DynamicContext dynamicContext) {
        return shouldSuspend(null, dynamicContext);
    }

    /**
     * 是否挂起等待人工裁决。
     *
     * 请求级开关优先：{@code autoApprovePlan=true} 时本批直接透传、不挂起。
     * 语义为"**只能放宽不能收紧**"——它无法让 yml 关闭的门重新生效，避免请求参数
     * 悄悄改掉配置侧"要审"的意图。跳过会打日志，观测层据此区分"人主动跳过"与"配置就没开"
     */
    boolean shouldSuspend(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        StoryProperties.PlanApprovalProperties cfg = storyProperties == null ? null : storyProperties.getPlanApproval();
        if (cfg == null || !cfg.isEnabled()) {
            return false;
        }
        if (requestParameter != null && Boolean.TRUE.equals(requestParameter.getAutoApprovePlan())) {
            log.info("章节计划审批门已被请求级开关跳过（autoApprovePlan=true，本批不挂起）");
            return false;
        }
        if (dynamicContext.isPlanApproved()) {
            return false;
        }
        GenerationJob job = dynamicContext.getJob();
        if (job == null) {
            log.warn("章节计划审批门已开启，但当前为同步调试路径（无作业载体），本批不挂起。"
                    + "需要人工裁决请改用 POST /api/jobs 异步提交");
            return false;
        }
        if (!inScope(cfg, job)) {
            log.info("章节计划审批门跳过：scope={}，本作业为第 {} 批",
                    cfg.getScope(), job.getBatchRound());
            return false;
        }
        return true;
    }

    /**
     * 作用范围判定：委托 {@link StoryProperties.PlanApprovalProperties#coversBatch}
     * （口径唯一实现，规划节点的"整批提前规划"判定共用同一份）。
     * EVERY_BATCH = 每批都裁决；
     * FIRST_BATCH_ONLY（默认）= 只裁决首批——首批定下全书调性与叙事口径，后续批次是延续。
     * 该默认值同时是与自续批（"只问体检不问人"）并存的隔离手段：否则每批都会停下来等人
     */
    private boolean inScope(StoryProperties.PlanApprovalProperties cfg, GenerationJob job) {
        return cfg.coversBatch(job.getBatchRound());
    }

    /**
     * 执行挂起。返回 false 表示状态迁移失败（多为并发取消已把作业推向 CANCELLING/CANCELLED），
     * 此时不挂起、直接放行——用户已经取消，不该再弹审批。
     *
     * 落盘先于状态迁移：挂起期间前端与人工都要能在 runDir 读到计划；
     * 检查点自身 fail-soft（写失败仅告警），与正文节点的既有语义一致
     */
    private boolean suspendForApproval(DefaultArmoryFactory.DynamicContext dynamicContext) {
        GenerationJob job = dynamicContext.getJob();
        generateChapterContentNode.preparePlanCheckpoint(dynamicContext);

        StoryProperties.PlanApprovalProperties cfg = storyProperties.getPlanApproval();
        long timeoutSeconds = cfg.getTimeoutSeconds() > 0 ? cfg.getTimeoutSeconds() : 7200L;
        long deadlineMs = System.currentTimeMillis() + timeoutSeconds * 1000L;

        // 计划随状态一次性交接（不变式：状态可见 ⇒ 计划可读）：避免"状态已挂起、服务层记录未登记"的微窗口
        if (!job.markAwaitingApproval(deadlineMs, dynamicContext.getChapterPlanAggregate())) {
            log.warn("作业 {} 未能进入审批挂起态（当前 {}），按放行处理（可能已被并发取消）",
                    job.getJobId(), job.getStatus());
            return false;
        }
        log.info("章节计划已挂起等待人工裁决：作业 {}，第 {} 批，计划 {} 章，等待 {} 秒；"
                        + "裁决端点 POST /api/jobs/{}/chapter-plan/approve",
                job.getJobId(), job.getBatchRound(),
                dynamicContext.getChapterPlanAggregate() == null || dynamicContext.getChapterPlanAggregate().getChapters() == null
                        ? 0 : dynamicContext.getChapterPlanAggregate().getChapters().size(),
                timeoutSeconds, job.getJobId());
        return true;
    }
}
