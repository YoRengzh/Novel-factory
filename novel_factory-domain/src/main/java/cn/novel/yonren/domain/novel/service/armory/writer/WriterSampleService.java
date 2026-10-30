package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.service.armory.quality.GateResult;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
public class WriterSampleService {

    @Resource
    private IStoryRepository storyRepository;

    public void record(Path storyDir, String storyId, int chapterNo,
                       String finalPrompt, String systemPrompt,
                       ChapterContentEntity chapterContent, GateResult gateResult,
                       String generationMode) {
        if (storyDir == null) {
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
