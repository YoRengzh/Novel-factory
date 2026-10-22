package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 预算熔断（防失控 job 烧钱）：网关在每次 usage 记账时上报本调用 token 数，
 * 按作业（jobId，一次批次）累计；达到预警线打 WARN，达到硬上限置位 job 的预算熔断信号——
 * worker 在当前章完成后停止（与协作取消同一检查点通道，逐章检查点已落盘，可 resume 续写）。
 * 阈值来自 story.budget（warn/hard 任一为 null 即关闭对应档位；两者均 null 时整体禁用）。
 * 说明：usage 流水按 jobId 归因，跨作业的"故事级累计"由每次作业各自熔断叠加保障；
 * 进程内计数，重启后计数归零（usage 明细仍完整保留在 llm-usage.jsonl 供人工对账）
 */
@Service
@Slf4j
public class LlmBudgetFuse {

    private final StoryProperties storyProperties;
    private final JobRegistry jobRegistry;

    /** jobId → 累计 token（merge 原子累加） */
    private final ConcurrentHashMap<String, Long> jobTokens = new ConcurrentHashMap<>();
    /** 已打 WARN 的作业（每作业至多一次，防日志刷屏） */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public LlmBudgetFuse(StoryProperties storyProperties, JobRegistry jobRegistry) {
        this.storyProperties = storyProperties;
        this.jobRegistry = jobRegistry;
    }

    /**
     * 网关逐调用上报（成功与失败路径均可调用）：jobId 缺失（同步调试路径无 MDC）或 token 缺失时忽略；
     * 任何异常自吞，绝不反噬调用链
     */
    public void record(String jobId, Long totalTokens) {
        try {
            if (jobId == null || totalTokens == null || totalTokens <= 0) {
                return;
            }
            // 用量观测与预算开关解耦：即使未配置熔断阈值，也必须累计真实 token。
            long total = jobTokens.merge(jobId, totalTokens, Long::sum);
            GenerationJob job = jobRegistry.get(jobId);
            if (job != null) {
                job.setTokensUsed(total);
            }
            // 作业已从注册表淘汰（终态保留窗口之外）或已完成：计数与预警标记不再需要，
            // 就地清理防 map 无界增长（自动续批链每批一个新 jobId，长驻进程会持续累积）
            if (job == null || isTerminal(job)) {
                jobTokens.remove(jobId);
                warned.remove(jobId);
            }

            StoryProperties.BudgetProperties budget = storyProperties.getBudget();
            if (budget == null
                    || (budget.getWarnTotalTokens() == null && budget.getHardTotalTokens() == null)) {
                return;
            }
            long hard = budget.getHardTotalTokens() == null ? Long.MAX_VALUE : budget.getHardTotalTokens();
            long warn = budget.getWarnTotalTokens() == null ? Long.MAX_VALUE : budget.getWarnTotalTokens();
            if (total >= hard) {
                if (job != null && job.requestBudgetExceeded()) {
                    log.error("作业 {} 预算熔断：累计 token {} 达硬上限 {}，当前章完成后停止"
                            + "（已完成章节均已通过逐章检查点落盘，可 resume 续写）",
                            jobId, total, budget.getHardTotalTokens());
                }
            } else if (total >= warn && warned.add(jobId)) {
                log.warn("作业 {} token 用量预警：累计 {} 已达预警线 {}（硬上限 {}）",
                        jobId, total, budget.getWarnTotalTokens(), budget.getHardTotalTokens());
            }
        } catch (Exception e) {
            log.warn("预算熔断记账失败（不影响生成）：{}", e.getMessage());
        }
    }

    /** 作业是否已到终态（终态后不再累计与熔断，计数可就地回收） */
    private static boolean isTerminal(GenerationJob job) {
        cn.novel.yonren.types.enums.JobStatus s = job.getStatus();
        return s == cn.novel.yonren.types.enums.JobStatus.COMPLETED
                || s == cn.novel.yonren.types.enums.JobStatus.FAILED
                || s == cn.novel.yonren.types.enums.JobStatus.CANCELLED;
    }
}
