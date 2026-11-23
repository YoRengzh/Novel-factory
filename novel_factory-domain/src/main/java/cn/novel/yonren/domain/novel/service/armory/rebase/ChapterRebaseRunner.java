package cn.novel.yonren.domain.novel.service.armory.rebase;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.rebase.RebaseRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 观察式重基的执行入口：开关、落盘与主链隔离都收在这里。
 *
 * <p>只在 {@code story.rebase.mode=PROPOSE_ONLY} 时产生候选并写入 runDir/rebase/；
 * 无论候选结果如何，都返回原计划供调用方继续使用——本入口绝不切换正文任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterRebaseRunner {

    private final ChapterRebaseService rebaseService;
    private final ChapterRebaseRecorder recorder;
    private final StoryProperties storyProperties;
    private final IStoryRepository storyRepository;

    /** 是否启用（供调用方在装配前短路，避免不必要的工作）。 */
    public boolean enabled() {
        return rebaseService.enabled();
    }

    /**
     * 生成候选并落盘，返回原计划（永不返回候选作为执行计划）。
     *
     * @return 本次候选记录；未启用、无计划或落盘失败时为 null
     */
    public RebaseRecord proposeOnly(StoryVO storyVO, ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries,
                                    StoryContextEntity story, Path storyDir, Path runDir) {
        if (!enabled() || item == null) {
            return null;
        }
        String previousTail = null;
        if (summaries != null && !summaries.isEmpty()) {
            ChapterSummaryEntity latest = summaries.get(summaries.size() - 1);
            previousTail = latest == null ? null : latest.getSummary();
        }
        RebaseRecord record;
        try {
            record = rebaseService.propose(storyVO, item, summaries, story, previousTail);
        } catch (Exception e) {
            log.warn("第 {} 章重基候选异常，保留原计划：{}", item.getChapterNo(), e.getMessage());
            return null;
        }
        if (record != null && storyDir != null && runDir != null) {
            recorder.record(storyDir, runDir, record);
        }
        return record;
    }
}
