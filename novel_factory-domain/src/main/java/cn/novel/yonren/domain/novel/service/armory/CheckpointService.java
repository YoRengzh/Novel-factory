package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.CheckpointEntity;
import cn.novel.yonren.types.enums.CheckpointType;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 检查点 + 回滚编排服务：手动命名快照、列表、回滚。
 * 回滚 = 磁盘原子还原（IStoryRepository.restore，锁内完成，恢复后可续写）→ 向量索引对账
 * （StoryMemoryService.rebuildIndex，只改回滚窗口内涉及的点，fail-soft，不阻塞）。
 * module/worldId 取法对齐 ChapterEditService.reflow。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckpointService {

    private final IStoryRepository storyRepository;
    private final StoryMemoryService storyMemoryService;
    private final StoryProperties storyProperties;

    /** 手动命名快照；name 必填 */
    public String manualSnapshot(Path storyDir, String name) throws Exception {
        return storyRepository.snapshotCheckpoint(storyDir, CheckpointType.MANUAL, name);
    }

    /** 列出全部检查点（按版本升序）；无则空列表 */
    public List<CheckpointEntity> list(Path storyDir) throws Exception {
        return storyRepository.listCheckpoints(storyDir);
    }

    /**
     * 回滚到指定检查点：先磁盘原子还原（使章节/摘要/蓝图/账本回到该点，续写锁步校验天然通过），
     * 再把该故事向量索引对账回该点（只改回滚窗口内涉及的点，不整集合重嵌）。
     * 索引对账失败仅告警（增强通道），不阻塞回滚。
     */
    public void restore(Path storyDir, String checkpointId) throws Exception {
        CheckpointEntity target = storyRepository.readCheckpoint(storyDir, checkpointId);
        if (target == null) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "检查点不存在：" + checkpointId);
        }
        storyRepository.restoreCheckpoint(storyDir, checkpointId);

        try {
            storyMemoryService.rebuildIndex(storyProperties.toStoryVO().getModule(), storyDir,
                    storyRepository.readChapterSummaries(storyDir), null);
        } catch (Exception e) {
            log.warn("回滚后向量索引对账失败，已跳过（不阻塞）：{}", e.getMessage());
        }
    }
}