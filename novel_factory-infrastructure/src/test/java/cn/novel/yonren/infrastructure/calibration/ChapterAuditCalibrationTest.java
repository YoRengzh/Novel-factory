package cn.novel.yonren.infrastructure.calibration;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.LedgerEntry;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.audit.ChapterAuditService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowPriorityService;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.infrastructure.gateway.SpringAiLlmGateway;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 审校精确率离线校准（手动触发，消耗真实 LLM 配额）：
 * 对已有成品逐章跑 audit，输出报告供人工核对 issue 真伪——真问题占比即审校精确率。
 * 校准方向"宁漏勿滥"：乱报会驱动 revise 无差别重写，severity 判据预计迭代 2~3 轮。
 *
 * 运行方式（项目根目录）：
 * mvn test -pl novel_factory-infrastructure -Dtest=ChapterAuditCalibrationTest -Daudit.calibration=true -Daudit.calibration.story=20260830-story-0001
 */
@EnabledIfSystemProperty(named = "audit.calibration", matches = "true")
class ChapterAuditCalibrationTest {

    private static final String DEFAULT_STORY_DIR = "20260905-story-0002";
    private static final ChapterMemoryService MEMORY_SERVICE = new ChapterMemoryService(new ForeshadowPriorityService());

    @Test
    void auditEveryChapterOfExistingStory() throws Exception {
        String storyDirName = System.getProperty("audit.calibration.story", DEFAULT_STORY_DIR);
        Path storyDir = locate("docs/workspace/stories/" + storyDirName);
        StoryVO storyVO = loadStoryVO();

        ChapterAuditService auditService = new ChapterAuditService(new SpringAiLlmGateway(1, 20, 600, new LlmRuntimeConfig(), new cn.novel.yonren.domain.novel.service.job.LlmBudgetFuse(
                        new cn.novel.yonren.domain.novel.config.StoryProperties(),
                        new cn.novel.yonren.domain.novel.service.job.JobRegistry())));
        List<ChapterSummaryEntity> summaries = readSummaries(storyDir);
        StyleStatEntity styleStat = readStyleStat(storyDir);

        StringBuilder report = new StringBuilder("# 审校精确率离线校准报告\n")
                .append("生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n故事目录：").append(storyDirName)
                .append("\n核对方式：逐条标注 issue 为 真问题 / 误报 / 存疑，统计 BLOCKING 真问题占比\n");

        for (Path record : listChapterRecords(storyDir)) {
            ChapterPlanItemEntity item = JSON.parseObject(Files.readString(record), ChapterPlanItemEntity.class);
            int globalNo = item.getChapterNo();
            Path chapterFile = storyDir.resolve("chapters/chapter-" + String.format("%04d", globalNo) + ".txt");
            if (!Files.exists(chapterFile)) {
                report.append("\n## 第 ").append(globalNo).append(" 章\n（正文文件缺失，跳过）\n");
                continue;
            }

            List<ChapterSummaryEntity> history = summaries.stream()
                    .filter(s -> s.getChapterNo() != null && s.getChapterNo() < globalNo)
                    .toList();
            String ledgerPrompt = renderLedger(history);
            String foreshadowing = String.join("\n", MEMORY_SERVICE.buildPendingForeshadowing(history));

            AuditResultEntity result = auditService.audit(storyVO, item, readChapterBody(chapterFile),
                    ledgerPrompt, foreshadowing, styleStat, globalNo);

            report.append("\n## 第 ").append(globalNo).append(" 章《").append(item.getTitle()).append("》\n");
            if (result == null || result.getIssues() == null || result.getIssues().isEmpty()) {
                report.append("（审校失败或无 issue）\n");
                continue;
            }
            List<ChapterIssueEntity> issues = result.getIssues();
            long blocking = issues.stream().filter(i -> "BLOCKING".equalsIgnoreCase(i.getSeverity())).count();
            report.append("issue 共 ").append(issues.size()).append(" 条，其中 BLOCKING ").append(blocking).append(" 条\n");
            for (ChapterIssueEntity issue : issues) {
                report.append("- 【").append(nullToBlank(issue.getSeverity())).append("】【").append(nullToBlank(issue.getDimension())).append("】")
                        .append(nullToBlank(issue.getDescription()))
                        .append("\n  - 证据：").append(nullToBlank(issue.getEvidence()))
                        .append("\n  - 建议：").append(nullToBlank(issue.getSuggestion()))
                        .append("\n  - 人工判定：（真问题 / 误报 / 存疑）\n");
            }
        }

        Path reportFile = storyDir.resolve("audit-calibration-report.md");
        Files.writeString(reportFile, report.toString());
        System.out.println("校准报告已写出: " + reportFile.toAbsolutePath());
    }

    /**
     * 从当前目录逐级向上定位相对路径（兼容从模块目录或项目根目录启动测试）
     */
    private Path locate(String relative) {
        Path cwd = Paths.get("").toAbsolutePath();
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

    /**
     * 模块配置从 app 模块的 novel-generation.yml 读取，独立于 Spring 容器
     */
    private StoryVO loadStoryVO() throws IOException {
        Path yml = locate("novel_factory-app/src/main/resources/novel-generation.yml");
        Map<String, Object> root = new Yaml().load(Files.readString(yml));
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

    private List<ChapterSummaryEntity> readSummaries(Path storyDir) throws IOException {
        Path summariesFile = storyDir.resolve("memory/summaries.json");
        if (!Files.exists(summariesFile)) {
            return List.of();
        }
        List<ChapterSummaryEntity> summaries = JSON.parseArray(Files.readString(summariesFile), ChapterSummaryEntity.class);
        return summaries == null ? List.of() : summaries;
    }

    private StyleStatEntity readStyleStat(Path storyDir) throws IOException {
        Path statFile = storyDir.resolve("memory/style-stats.json");
        if (!Files.exists(statFile)) {
            return null;
        }
        return JSON.parseObject(Files.readString(statFile), StyleStatEntity.class);
    }

    private List<Path> listChapterRecords(Path storyDir) throws IOException {
        Path recordDir = storyDir.resolve("generation-records");
        List<Path> records = scanFlatRecords(recordDir);
        if (records.isEmpty()) {
            // 异步作业布局：generation-records/run-<jobId>/output-XXXX.json
            try (Stream<Path> runs = Files.list(recordDir)) {
                List<Path> runDirs = runs.filter(Files::isDirectory).toList();
                for (Path runDir : runDirs) {
                    records.addAll(scanFlatRecords(runDir));
                }
            }
        }
        return new ArrayList<>(records.stream()
                .sorted(Comparator.comparing(f -> fileNameNo(f)))
                .toList());
    }

    private List<Path> scanFlatRecords(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(f -> f.getFileName().toString().matches("output-\\d{4}\\.json"))
                    .toList();
        }
    }

    private int fileNameNo(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring("output-".length(), name.length() - ".json".length()));
    }

    /**
     * 落盘文件首行为"第X章 标题"页眉，审校对象剔除后与原始正文实体一致
     */
    private String readChapterBody(Path chapterFile) throws IOException {
        String text = Files.readString(chapterFile);
        int firstBreak = text.indexOf('\n');
        return firstBreak < 0 ? text : text.substring(firstBreak + 1).trim();
    }

    /**
     * 与 GenerateChapterContentNode.buildLedgerPrompt 同格式的账本渲染
     */
    private String renderLedger(List<ChapterSummaryEntity> history) {
        StringBuilder sb = new StringBuilder();
        appendLedger(sb, "角色", MEMORY_SERVICE.buildCharacterLedger(history));
        appendLedger(sb, "物品", MEMORY_SERVICE.buildLedger(history, ChapterSummaryEntity::getItemStates));
        appendLedger(sb, "势力", MEMORY_SERVICE.buildLedger(history, ChapterSummaryEntity::getFactionStates));
        return sb.length() == 0 ? null : sb.toString();
    }

    private void appendLedger(StringBuilder sb, String kind, List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        sb.append("【").append(kind).append("账本】\n");
        for (LedgerEntry entry : entries) {
            sb.append("- ").append(entry.getName()).append("：").append(entry.getStatus()).append("\n");
        }
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
