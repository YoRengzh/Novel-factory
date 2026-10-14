package cn.novel.yonren.domain.novel.service.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import cn.novel.yonren.types.enums.JobStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存作业注册表（单进程个人使用，无 DB 队列）。
 * 进程重启后注册表清空：job-status.json 仅供人工查看崩溃前进度，不回填、不自动恢复执行
 */
@Service
@Slf4j
public class JobRegistry {

    /**
     * 保留的终态作业数上限：自动续批链每个子作业都会留在表里，
     * 长驻进程 + 数百批后是无界增长（每个作业还带着 chapterDurations 与终态体检报告）。
     * 保留最近 50 个即可满足前端轮询与"回看上一批"的需求；运行中的作业永不淘汰。
     */
    static final int TERMINAL_RETENTION = 50;

    private final ConcurrentHashMap<String, GenerationJob> jobs = new ConcurrentHashMap<>();

    public void register(GenerationJob job) {
        jobs.put(job.getJobId(), job);
        evictTerminalOverflow();
    }

    /**
     * 淘汰超额的终态作业（保留最近 {@value #TERMINAL_RETENTION} 个）。
     *
     * <p>只淘汰终态（COMPLETED/FAILED/CANCELLED）——运行中、排队中与审批挂起的作业
     * 仍在被 worker/前端引用，淘汰会造成"作业凭空消失"。
     * 按 startedAtMs 排序保留最新：自动续批链的子作业按时间递增，天然保住最近的一批。
     */
    private void evictTerminalOverflow() {
        List<GenerationJob> terminal = new ArrayList<>();
        for (GenerationJob job : jobs.values()) {
            if (isTerminal(job.getStatus())) {
                terminal.add(job);
            }
        }
        int overflow = terminal.size() - TERMINAL_RETENTION;
        if (overflow <= 0) {
            return;
        }
        terminal.sort(Comparator.comparingLong(j -> j.getStartedAtMs() == null ? 0L : j.getStartedAtMs()));
        for (int i = 0; i < overflow; i++) {
            GenerationJob victim = terminal.get(i);
            // 二次确认状态：排序到淘汰之间作业可能被复用（当前实现不复用终态作业，此处为防御）
            if (isTerminal(victim.getStatus())) {
                jobs.remove(victim.getJobId(), victim);
            }
        }
        log.debug("作业注册表淘汰 {} 个过期终态作业（保留最近 {} 个）", overflow, TERMINAL_RETENTION);
    }

    private static boolean isTerminal(JobStatus status) {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
    }

    /** 未知 jobId 返回 null */
    public GenerationJob get(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * 指定故事是否有运行中的作业（CREATED/RUNNING/CANCELLING/AWAITING_APPROVAL）：
     * 编辑端点据此拒绝修改正在生成的故事，防止正文与记忆不一致。
     * AWAITING_APPROVAL 必须计入——审批门挂起的作业尚未定稿本批，此时改正文会与即将写入的计划冲突
     */
    public boolean isActiveForStory(String storyDirName) {
        if (storyDirName == null) {
            return false;
        }
        for (GenerationJob job : jobs.values()) {
            JobStatus s = job.getStatus();
            if ((s == JobStatus.CREATED || s == JobStatus.RUNNING || s == JobStatus.CANCELLING
                    || s == JobStatus.AWAITING_APPROVAL)
                    && storyDirName.equals(job.getStoryDirName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 协作取消：CREATED → 直接 CANCELLED（未启动）；RUNNING → CANCELLING（当前章完成后停）；
     * AWAITING_APPROVAL → 直接 CANCELLED（审批门已释放线程，无在途工作，无需等待当前章）；
     * CANCELLING 幂等；终态不动。未知 jobId 返回 null
     */
    public GenerationJob cancel(String jobId) {
        GenerationJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        if (!job.requestCancel()) {
            return job;
        }
        if (job.getStatus() == JobStatus.CREATED || job.getStatus() == JobStatus.AWAITING_APPROVAL) {
            job.markCancelled();
        } else if (job.getStatus() == JobStatus.RUNNING) {
            job.markCancelling();
        }
        return job;
    }
}
