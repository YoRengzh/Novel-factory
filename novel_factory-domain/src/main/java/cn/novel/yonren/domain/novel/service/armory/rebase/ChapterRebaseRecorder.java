package cn.novel.yonren.domain.novel.service.armory.rebase;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.valobj.rebase.RebaseRecord;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 把重基候选写入独立目录，供人工复核。
 *
 * <p>与正文主链隔离：写失败只记录告警，不影响原计划与当前章节生成；
 * 候选永远不参与正文任务，{@code adopted} 恒为 false。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterRebaseRecorder {

    private final IStoryRepository storyRepository;

    /**
     * 保存候选记录。
     *
     * @return 写入路径；目录/记录缺失或写入失败返回 null（调用方按观测件处理）
     */
    public Path record(Path storyDir, Path runDir, RebaseRecord record) {
        if (storyDir == null || runDir == null || record == null || record.status() == null) {
            return null;
        }
        try {
            String content = JSON.toJSONString(record);
            Path saved = storyRepository.writeRebaseRecord(storyDir, runDir, record.chapterNo(),
                    record.status(), content);
            log.info("第 {} 章重基候选已保存：{}（{}，采纳={}）",
                    record.chapterNo(), saved, record.status(), record.adopted());
            return saved;
        } catch (Exception e) {
            log.warn("第 {} 章重基候选保存失败，原计划不受影响：{}", record.chapterNo(), e.getMessage());
            return null;
        }
    }
}
