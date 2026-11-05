package cn.novel.yonren.infrastructure.calibration;

import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.audit.ChapterAuditService;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.infrastructure.gateway.SpringAiLlmGateway;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审校失败样本离线复检（四期闭环自愈的校准闭环；手动触发，消耗真实 LLM 配额）：
 * 逐行读 memory/audit-runtime-samples.jsonl（修订循环未闭环时由 AuditSampleService 落盘），
 * 用行内记录的 计划/最终正文/账本/伏笔清单 重跑 audit，对比复发维度并校验 evidence 子串，
 * 结果追加到 audit-calibration-report.md 的"运行时失败样本复检"节，供收紧 revise/audit prompt 迭代。
 *
 * 运行方式（项目根目录）：
 * mvn test -pl novel_factory-infrastructure -Dtest=ChapterAuditRuntimeSampleTest -Daudit.calibration=true
 * 可加 -Daudit.calibration.story=20260905-story-0004 指定样本所在故事
 */
@EnabledIfSystemProperty(named = "audit.calibration", matches = "true")
class ChapterAuditRuntimeSampleTest {

    private static final String DEFAULT_STORY_DIR = "20260905-story-0002";

    @Test
    void reAuditRuntimeFailureSamples() throws Exception {
        String storyDirName = System.getProperty("audit.calibration.story", DEFAULT_STORY_DIR);
        Path storyDir = locate("docs/workspace/stories/" + storyDirName);
        Path samplesFile = storyDir.resolve("memory/audit-runtime-samples.jsonl");

        StringBuilder report = new StringBuilder("\n## 运行时失败样本复检\n")
                .append("生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n样本文件：").append(samplesFile).append("\n");

        if (!Files.exists(samplesFile)) {
            report.append("（该故事无运行时失败样本——修订循环未发生未闭环场景，无需复检）\n");
            appendReport(storyDir, report);
            return;
        }

        ChapterAuditService auditService = new ChapterAuditService(new SpringAiLlmGateway(1, 20, 600, new LlmRuntimeConfig(), new cn.novel.yonren.domain.novel.service.job.LlmBudgetFuse(
                        new cn.novel.yonren.domain.novel.config.StoryProperties(),
                        new cn.novel.yonren.domain.novel.service.job.JobRegistry())));
        StoryVO storyVO = loadStoryVO();
        int totalBlocking = 0;
        int recurred = 0;
        int evidenceMismatch = 0;
        int lineNo = 0;

        for (String line : Files.readAllLines(samplesFile)) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            JSONObject sample;
            try {
                sample = JSON.parseObject(line);
            } catch (RuntimeException e) {
                report.append("\n### 样本行 ").append(lineNo).append(" 解析失败（跳过）\n");
                continue;
            }

            int chapterNo = sample.getIntValue("chapterNo");
            int attempts = sample.getIntValue("attempts");
            ChapterPlanItemEntity item = JSON.parseObject(
                    JSON.toJSONString(sample.getJSONObject("plan")), ChapterPlanItemEntity.class);
            String content = sample.getString("content");
            String ledgerPrompt = sample.getString("ledgerPrompt");
            String foreshadowing = sample.getString("foreshadowing");
            List<ChapterIssueEntity> recorded = JSON.parseArray(
                    JSON.toJSONString(sample.getJSONArray("issues")), ChapterIssueEntity.class);

            report.append("\n### 样本行 ").append(lineNo).append("：第 ").append(chapterNo)
                    .append(" 章（修订 ").append(attempts).append(" 轮未闭环）\n");

            AuditResultEntity result;
            try {
                result = auditService.audit(storyVO, item, content, ledgerPrompt, foreshadowing,
                        StyleStatEntity.builder().build(), chapterNo);
            } catch (Exception e) {
                report.append("（复检审校调用失败：").append(e.getMessage()).append("，跳过）\n");
                continue;
            }

            List<ChapterIssueEntity> reIssues = result == null || result.getIssues() == null
                    ? List.of() : result.getIssues();
            long blocking = reIssues.stream().filter(i -> "BLOCKING".equalsIgnoreCase(i.getSeverity())).count();
            totalBlocking += blocking;

            for (ChapterIssueEntity issue : recorded) {
                String evidence = issue.getEvidence();
                boolean evidenceHolds = evidence == null || evidence.isBlank()
                        || normalize(content).contains(normalize(evidence));
                if (!evidenceHolds) {
                    evidenceMismatch++;
                }
                boolean dimensionRecurred = reIssues.stream()
                        .anyMatch(r -> sameDimension(r, issue));
                if (dimensionRecurred) {
                    recurred++;
                }
                report.append("- 原样：【").append(nullToBlank(issue.getSeverity())).append("】【")
                        .append(nullToBlank(issue.getDimension())).append("】")
                        .append(nullToBlank(issue.getDescription()))
                        .append(evidenceHolds ? "" : "（⚠ evidence 已不在最终正文中）")
                        .append(dimensionRecurred ? " → 复检同维度复发" : " → 复检未复发")
                        .append("\n");
            }
            report.append("复检 BLOCKING ").append(blocking).append(" 条\n");
        }

        report.append("\n汇总：复检 BLOCKING 共 ").append(totalBlocking)
                .append(" 条；记录 issue 中同维度复发 ").append(recurred)
                .append(" 条；evidence 失效 ").append(evidenceMismatch).append(" 条\n");
        appendReport(storyDir, report);
    }

    private boolean sameDimension(ChapterIssueEntity reIssue, ChapterIssueEntity recorded) {
        if (reIssue.getDimension() == null || recorded.getDimension() == null) {
            return false;
        }
        return reIssue.getDimension().equals(recorded.getDimension());
    }

    /** 去空白归一化（与审校 evidence 校验同思路）：换行/空格不构成证据失效 */
    private String normalize(String text) {
        return text.replaceAll("\\s+", "");
    }

    private void appendReport(Path storyDir, StringBuilder section) throws IOException {
        Path reportFile = storyDir.resolve("audit-calibration-report.md");
        String existing = Files.exists(reportFile) ? Files.readString(reportFile) : "# 审校校准报告\n";
        Files.writeString(reportFile, existing + section.toString());
        System.out.println("运行时失败样本复检已追加到: " + reportFile.toAbsolutePath());
    }

    private Path locate(String relative) {
        Path cwd = Path.of("").toAbsolutePath();
        Path current = cwd;
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到 " + relative + "（从 " + cwd + " 向上查找失败），请确认项目根目录");
    }

    private StoryVO loadStoryVO() throws IOException {
        Path yml = locate("novel_factory-app/src/main/resources/novel-generation.yml");
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        Map<String, Object> root = yaml.load(Files.readString(yml));
        Map<String, Object> story = cast(root.get("story"));
        Map<String, Object> module = cast(story.get("module"));
        Map<String, Object> aiApi = cast(module.get("ai-api"));
        Map<String, Object> chatModel = cast(module.get("chat-model"));

        StoryVO.Module.AiApi ai = new StoryVO.Module.AiApi();
        ai.setBaseUrl((String) aiApi.get("base-url"));
        ai.setApiKey((String) aiApi.get("api-key"));
        StoryVO.Module.ChatModel model = new StoryVO.Module.ChatModel();
        model.setModel((String) chatModel.get("model"));
        model.setMaxTokens(Long.valueOf(String.valueOf(chatModel.get("max-tokens"))));

        StoryVO.Module storyModule = new StoryVO.Module();
        storyModule.setAiApi(ai);
        storyModule.setChatModel(model);

        StoryVO storyVO = new StoryVO();
        storyVO.setModule(storyModule);
        return storyVO;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        return (Map<String, Object>) Optional.ofNullable(value).orElseThrow(
                () -> new IllegalStateException("novel-generation.yml 缺少 story.module 配置"));
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
