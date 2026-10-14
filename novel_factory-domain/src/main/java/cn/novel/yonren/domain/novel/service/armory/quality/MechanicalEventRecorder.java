package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;


@Service
@Slf4j
public class MechanicalEventRecorder {

    /** 事件类别：伏笔回收清单与账本一条都没匹配上（措辞漂移） */
    public static final String TYPE_LEDGER_MISMATCH = "ledger-mismatch";

    /** 事件类别：卷末清账裁决被降级（DONE 缺章号 / PARKED 缺理由 / 非法决策值） */
    public static final String TYPE_SETTLEMENT_DEGRADED = "settlement-degraded";

    /** 事件类别：模型输出需经降级解析修复才可用 */
    public static final String TYPE_PARSE_DEGRADED = "parse-degraded";

    /** 事件类别：摘要模型只填了 foreshadowSeeds、漏填 foreshadowingNew，由机械层补齐 */
    public static final String TYPE_SEED_NEW_SYNC = "seed-new-sync";

    @Resource
    private IStoryRepository storyRepository;

    /**
     * 记录一条机械事件。
     *
     * @param storyDir 故事目录；null 时跳过（无落点）
     * @param type     事件类别，取本类常量
     * @param fields   事件明细（键值对），会与 ts/type 合并成一行 JSON
     */
    public void record(Path storyDir, String type, Map<String, Object> fields) {
        if (storyDir == null || type == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("ts", LocalDateTime.now().toString());
            event.put("type", type);
            if (fields != null) {
                event.putAll(fields);
            }
            storyRepository.appendMechanicalEvent(storyDir, JSON.toJSONString(event));
        } catch (Exception e) {
            log.warn("机械事件落盘失败（不影响生成）：type={}, {}", type, e.getMessage());
        }
    }

    /**
     * 便捷入口：伏笔回收清单与账本完全失配。
     *
     * @param resolvedCount 本章登记为"已回收"的条数
     * @param pendingCount  当时账本上的待回收条数
     * @param resolved     本章登记的回收条目（原文，供离线看措辞漂移形态）
     */
    public void recordLedgerMismatch(Path storyDir, int chapterNo, int resolvedCount, int pendingCount,
                                     java.util.List<String> resolved) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chapterNo", chapterNo);
        fields.put("resolvedCount", resolvedCount);
        fields.put("pendingCount", pendingCount);
        fields.put("resolved", resolved);
        record(storyDir, TYPE_LEDGER_MISMATCH, fields);
    }

    /**
     * 便捷入口：摘要模型只填种子、漏填新埋清单，机械层补齐。
     *
     * <p>这是"两个字段输出不对称"的量化点——补齐的条目是否真被后续回收、
     * 与 ID 改造的效果验证同源，只写日志无法聚合。
     *
     * @param addedCount 补齐条数
     * @param added      补齐的条目内容（供离线看模型漏填的形态）
     */
    public void recordSeedNewSync(Path storyDir, int chapterNo, int addedCount, java.util.List<String> added) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chapterNo", chapterNo);
        fields.put("addedCount", addedCount);
        fields.put("added", added);
        record(storyDir, TYPE_SEED_NEW_SYNC, fields);
    }
}
