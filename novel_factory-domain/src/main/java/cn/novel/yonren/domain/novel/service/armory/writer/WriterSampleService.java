package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.audit.ParagraphDensityAuditService.Suggestion;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class WriterSampleService {

    @Resource
    private IStoryRepository storyRepository;

    public void recordDraft(Path storyDir, Path runDir, ChapterManuscript draft) {
        recordDraftReview(storyDir, runDir, draft, "draft", draft);
    }

    public void recordSuggestions(Path storyDir, Path runDir, ChapterManuscript draft, List<Suggestion> suggestions) {
        if (suggestions != null && !suggestions.isEmpty()) {
            recordDraftReview(storyDir, runDir, draft, "suggestions", suggestions);
        }
    }

    private void recordDraftReview(Path storyDir, Path runDir, ChapterManuscript draft, String type, Object data) {
        if (storyDir == null) {
            return;
        }
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("ts", LocalDateTime.now().toString());
            record.put("runId", runDir == null ? null : runDir.getFileName().toString());
            record.put("chapterNo", draft.chapterNo());
            record.put("manuscriptHash", draft.hash());
            record.put("type", type);
            record.put("data", data);
            storyRepository.appendDraftReview(storyDir, JSON.toJSONString(record));
        } catch (Exception e) {
            log.warn("第 {} 章原稿或段落建议记录失败", draft.chapterNo(), e);
        }
    }

    public void record(Path storyDir, String storyId, int chapterNo,
                       String finalPrompt, String systemPrompt,
                       ChapterContentEntity chapterContent, GateResult gateResult,
                       String generationMode) {
        if (storyDir == null || gateResult == null) {
            return;
        }
        DeliveryReview deliveryReview = gateResult.deliveryReview();
        // 与 Worker 双防御：历史样本允许普通 DEBT，不把“未验证”或严格交付失败写成范文。
        // OFF 的 NOT_CHECKED 仍按旧行为记录；OBSERVE 的已确认 ISSUE 只随元数据留痕。
        if (gateResult.grade() == GateResult.PassGrade.UNVERIFIED
                || (deliveryReview != null && (deliveryReview.unknown() || deliveryReview.blocksCommit()))) {
            log.info("第 {} 章写手样本跳过（grade={}，delivery={}）", chapterNo, gateResult.grade(),
                    deliveryReview == null ? null : deliveryReview.status());
            return;
        }
        try {
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("grade", gateResult.grade() == null ? null : gateResult.grade().name());
            gate.put("minorCount", gateResult.minorCount());
            gate.put("reviseRounds", gateResult.reviseRounds());
            gate.put("blockingCount", gateResult.unresolvedBlocking() == null ? 0 : gateResult.unresolvedBlocking().size());
            gate.put("mechanicalMinorCount", gateResult.mechanicalMinor() == null ? 0 : gateResult.mechanicalMinor().size());
            gate.put("semanticMinorCount", gateResult.semanticMinors() == null ? 0 : gateResult.semanticMinors().size());
            gate.put("auditVerifyDegraded", gateResult.auditVerifyDegraded());
            gate.put("semanticFeedbackHash", gateResult.semanticFeedbackHash());
            gate.put("planAdjustmentReason", gateResult.planAdjustmentReason());
            gate.put("deliveryReview", deliveryReview);

            String content = chapterContent == null ? null : chapterContent.getContent();
            Map<String, Object> sample = new HashMap<>();
            sample.put("ts", LocalDateTime.now().toString());
            sample.put("storyId", storyId);
            sample.put("chapterNo", chapterNo);
            sample.put("generationMode", generationMode);
            sample.put("systemPrompt", systemPrompt);
            sample.put("prompt", finalPrompt);
            sample.put("title", chapterContent == null ? null : chapterContent.getTitle());
            sample.put("output", content);
            sample.put("outputChars", content == null ? 0 : content.length());
            sample.put("gate", gate);
            storyRepository.appendWriterSample(storyDir, JSON.toJSONString(sample));
            log.info("第 {} 章写手样本已落盘（grade={}，promptChars={}，outputChars={}）", chapterNo,
                    gate.get("grade"),
                    finalPrompt == null ? 0 : finalPrompt.length(),
                    content == null ? 0 : content.length());
        } catch (Exception e) {
            log.warn("第 {} 章写手样本落盘失败，忽略", chapterNo, e);
        }
    }
}
