package cn.novel.yonren.domain.novel.service.armory.edit;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterSummaryService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 章节人工编辑回流：正文被外部修改后，重跑摘要→替换记忆→重嵌入，
 * 保证记忆与正文严格一致。同步执行（单次 LLM 调用秒级），全程 catch 返回 warning 不抛出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterEditService {

    private final IStoryRepository storyRepository;
    private final ChapterSummaryService chapterSummaryService;
    private final ChapterMemoryService chapterMemoryService;
    private final StoryMemoryService storyMemoryService;
    private final StoryProperties storyProperties;

    public record ReflowResult(boolean summaryUpdated, boolean partial, String warning) {
    }

    /**
     * 编辑回流：用新正文重跑摘要并更新记忆。
     *
     * @param storyDir   已校验的故事目录
     * @param chapterNo  章节号（1-based）
     * @param newContent 用户编辑后的正文
     * @return 回流结果；任何异常转为 warning 字段，不抛出
     */
    public ReflowResult reflow(Path storyDir, int chapterNo, String newContent) {
        try {
            List<ChapterSummaryEntity> summaries = new ArrayList<>(storyRepository.readChapterSummaries(storyDir));
            if (chapterNo < 1 || chapterNo > summaries.size()) {
                return new ReflowResult(false, false,
                        "章节号 " + chapterNo + " 越界（共 " + summaries.size() + " 章）");
            }
            if (!ChapterLengthPolicy.meetsMinimum(newContent)) {
                return new ReflowResult(false, false,
                        "正文有效字符不足 " + ChapterLengthPolicy.MINIMUM_EFFECTIVE_CHARACTERS + " 字，已拒绝保存");
            }

            ChapterPlanItemEntity item = storyRepository.readChapterPlanItem(storyDir, chapterNo);
            if (item == null) {
                item = ChapterPlanItemEntity.builder().chapterNo(chapterNo).title("第" + chapterNo + "章").build();
            }

            StoryVO storyVO = storyProperties.toStoryVO();

            String ledgerPrompt = chapterMemoryService.renderLedgerPrompt(summaries);
            // 带 ID 前缀版：与生成主链路同口径，编辑回流后回收登记也能回抄 [F章号-序号]
            List<String> foreshadowing = chapterMemoryService.buildForeshadowContextListWithIds(
                    summaries, ChapterMemoryService.FORESHADOW_CONTEXT_LIMIT);

            ChapterSummaryEntity newSummary = chapterSummaryService.summarize(
                    storyVO, item, newContent, chapterNo, ledgerPrompt, foreshadowing);

            summaries.set(chapterNo - 1, newSummary);
            storyRepository.writeChapterSummaries(storyDir, summaries);

            storyMemoryService.indexChapter(
                    storyVO.getModule(), storyDir, summaries, chapterNo, null);

            return new ReflowResult(true, newSummary.isPartial(), null);
        } catch (Exception e) {
            log.warn("章节编辑回流失败（第{}章）：{}", chapterNo, e.getMessage());
            return new ReflowResult(false, false, "回流失败：" + e.getMessage());
        }
    }
}
