package cn.novel.yonren.infrastructure.calibration;

import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.LedgerEntry;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.domain.novel.service.armory.audit.AuditPromptVariant;
import cn.novel.yonren.domain.novel.service.armory.audit.ChapterAuditService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowPriorityService;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import cn.novel.yonren.domain.novel.service.job.LlmBudgetFuse;
import cn.novel.yonren.infrastructure.gateway.SpringAiLlmGateway;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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
 * 审计评测骨架（docs/enhancement-plan.md A1 噪声重放 + E2 变体 A/B；手动触发，消耗真实 LLM 配额）。
 *
 * 三种模式（{@code -Deval.mode}）：
 *
 *   noise（默认，A1）：同一 prompt 对每章跑 k 次 → 按维度翻转率报告
 *       （issue 条数 CV / BLOCKING 出现率 / 集合 Jaccard / 分维度 CV），落 audit-noise-report.md；
 *   ab（E2）：当前变体（{@code -Daudit.prompt.variant}）对语料各跑 k 次，
 *       结果落 target/audit-ab-<variant>.jsonl——A、B 各跑一次本模式；
 *   ab-report（E2）：合并两份 jsonl → 同表对照稳定性指标，落 audit-ab-report.md。
 *
 *
 * 语料：故事目录的 output-XXXX.json 计划 + chapters 正文 + summaries 账本
 * （与 ChapterAuditCalibrationTest 同源）。B 分支未落地（A2 之前与 A 等价）时，
 * ab-report 应显示零差异——这本身是骨架自检：管线无偏，之后测出的任何差异都归因于判据本身。
 *
 * 运行方式（项目根目录）：
 *
 * mvn test -pl novel_factory-infrastructure -Dtest=AuditEvalHarnessTest -Daudit.eval=true \
 *   -Deval.mode=noise -Deval.story=20260905-story-0002 -Deval.k=3 -Deval.limit=5
 *
 */
@EnabledIfSystemProperty(named = "audit.eval", matches = "true")
class AuditEvalHarnessTest {

    private static final String DEFAULT_STORY_DIR = "20260905-story-0002";
    private static final int DEFAULT_K = 3;
    private static final int DEFAULT_LIMIT = 5;
    private static final ChapterMemoryService MEMORY_SERVICE = new ChapterMemoryService(new ForeshadowPriorityService());

    private record Sample(int chapterNo, ChapterPlanItemEntity item, String content,
                          String ledgerPrompt, String foreshadowing, StyleStatEntity styleStat) {
    }

    @Test
    void run() throws Exception {
        String mode = System.getProperty("eval.mode", "noise");
        switch (mode) {
            case "ab-report" -> abReport();
            case "ab" -> ab();
            default -> noise();
        }
    }

    // ---- A1：噪声重放 ----

    private void noise() throws Exception {
        Path storyDir = locate("docs/workspace/stories/" + storyDirName());
        List<Sample> samples = loadCorpus(storyDir, limit());
        ChapterAuditService auditService = newAuditService();
        StoryVO storyVO = loadStoryVO();
        int k = kRuns();

        StringBuilder report = new StringBuilder("\n\n# 审计噪声重放报告（A1）\n")
                .append("生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n变体：").append(AuditPromptVariant.active())
                .append("，k=").append(k).append("，章节数=").append(samples.size())
                .append("\n\n| 章节 | 条数(k次) | 条数CV | BLOCKING率 | 稳定 | 集合Jaccard | 最抖维度(CV) |\n")
                .append("|------|-----------|--------|-----------|------|-------------|-------------|\n");

        List<AuditEvalMetrics.SampleNoise> all = new ArrayList<>();
        for (Sample sample : samples) {
            List<List<AuditEvalMetrics.IssueDigest>> runs = runAuditKTimes(auditService, storyVO, sample, k);
            AuditEvalMetrics.SampleNoise noise = AuditEvalMetrics.evaluate(sample.chapterNo(), runs);
            all.add(noise);
            report.append("| ").append(sample.chapterNo())
                    .append(" | ").append(noise.issueCounts())
                    .append(" | ").append(fmt(noise.issueCountCv()))
                    .append(" | ").append(fmt(noise.blockingAppearanceRate()))
                    .append(" | ").append(noise.blockingStable() ? "是" : "**否**")
                    .append(" | ").append(fmt(noise.setJaccardMean()))
                    .append(" | ").append(worstDimension(noise))
                    .append(" |\n");
        }
        report.append("\n汇总：BLOCKING 出现率漂移章节 ").append(all.stream().filter(n -> !n.blockingStable()).count())
                .append("/").append(all.size())
                .append("；平均集合 Jaccard ").append(fmt(all.stream().mapToDouble(AuditEvalMetrics.SampleNoise::setJaccardMean).average().orElse(0)))
                .append("；平均条数 CV ").append(fmt(all.stream().mapToDouble(AuditEvalMetrics.SampleNoise::issueCountCv).average().orElse(0)))
                .append("\n（CV 与 Jaccard 的判读口径见 AuditEvalMetrics 类注释；分维度明细见本报告各章小节）\n");
        appendPerDimensionDetails(report, all);
        writeReport(storyDir.resolve("audit-noise-report.md"), report);
    }

    // ---- E2：变体 A/B ----

    private void ab() throws Exception {
        String variant = AuditPromptVariant.active();
        Path storyDir = locate("docs/workspace/stories/" + storyDirName());
        List<Sample> samples = loadCorpus(storyDir, limit());
        ChapterAuditService auditService = newAuditService();
        StoryVO storyVO = loadStoryVO();
        int k = kRuns();

        Path outFile = Paths.get("target", "audit-ab-" + variant + ".jsonl");
        List<String> lines = new ArrayList<>();
        for (Sample sample : samples) {
            List<List<AuditEvalMetrics.IssueDigest>> runs = runAuditKTimes(auditService, storyVO, sample, k);
            lines.add(JSON.toJSONString(Map.of(
                    "chapterNo", sample.chapterNo(),
                    "variant", variant,
                    "runs", runs)));
            System.out.println("变体 " + variant + " 第 " + sample.chapterNo() + " 章完成（" + k + " 次）");
        }
        Files.createDirectories(outFile.getParent());
        Files.write(outFile, lines);
        System.out.println("A/B 结果已写出: " + outFile.toAbsolutePath() + "（两变体各跑一次后用 -Deval.mode=ab-report 汇总）");
    }

    @SuppressWarnings("unchecked")
    private void abReport() throws Exception {
        Path storyDir = locate("docs/workspace/stories/" + storyDirName());
        Path aFile = Paths.get("target", "audit-ab-A.jsonl");
        Path bFile = Paths.get("target", "audit-ab-B.jsonl");
        if (!Files.exists(aFile) || !Files.exists(bFile)) {
            System.out.println("缺少 target/audit-ab-A.jsonl 或 audit-ab-B.jsonl——请先用 -Deval.mode=ab -Daudit.prompt.variant=A|B 各跑一次");
            return;
        }
        Map<Integer, List<List<AuditEvalMetrics.IssueDigest>>> runsA = readAbFile(aFile);
        Map<Integer, List<List<AuditEvalMetrics.IssueDigest>>> runsB = readAbFile(bFile);

        StringBuilder report = new StringBuilder("# 审计变体 A/B 对照报告（E2）\n")
                .append("生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n判读：B 相对 A 应看到——BLOCKING 出现率更稳定（贴近 0 或 1）、集合 Jaccard 更高（更可复现）、\n")
                .append("条数 CV 更低；条数均值显著上升需人工核对是\"抓到了原本漏掉的问题\"还是\"放宽了判据\"。\n\n")
                .append("| 章节 | A:BLOCKING率 | B:BLOCKING率 | A:Jaccard | B:Jaccard | A:条数CV | B:条数CV | A:均条数 | B:均条数 |\n")
                .append("|------|-------------|-------------|-----------|-----------|----------|----------|----------|----------|\n");

        double[] sums = new double[8];
        int rows = 0;
        for (Integer chapterNo : runsA.keySet()) {
            List<List<AuditEvalMetrics.IssueDigest>> ra = runsA.get(chapterNo);
            List<List<AuditEvalMetrics.IssueDigest>> rb = runsB.get(chapterNo);
            if (rb == null) {
                report.append("| ").append(chapterNo).append(" | （B 未覆盖该章） |\n");
                continue;
            }
            AuditEvalMetrics.SampleNoise na = AuditEvalMetrics.evaluate(chapterNo, ra);
            AuditEvalMetrics.SampleNoise nb = AuditEvalMetrics.evaluate(chapterNo, rb);
            report.append("| ").append(chapterNo)
                    .append(" | ").append(fmt(na.blockingAppearanceRate()))
                    .append(" | ").append(fmt(nb.blockingAppearanceRate()))
                    .append(" | ").append(fmt(na.setJaccardMean()))
                    .append(" | ").append(fmt(nb.setJaccardMean()))
                    .append(" | ").append(fmt(na.issueCountCv()))
                    .append(" | ").append(fmt(nb.issueCountCv()))
                    .append(" | ").append(fmt(AuditEvalMetrics.mean(na.issueCounts())))
                    .append(" | ").append(fmt(AuditEvalMetrics.mean(nb.issueCounts())))
                    .append(" |\n");
            sums[0] += na.blockingAppearanceRate();
            sums[1] += nb.blockingAppearanceRate();
            sums[2] += na.setJaccardMean();
            sums[3] += nb.setJaccardMean();
            sums[4] += na.issueCountCv();
            sums[5] += nb.issueCountCv();
            sums[6] += AuditEvalMetrics.mean(na.issueCounts());
            sums[7] += AuditEvalMetrics.mean(nb.issueCounts());
            rows++;
        }
        if (rows > 0) {
            report.append("| **均值** ")
                    .append(" | ").append(fmt(sums[0] / rows))
                    .append(" | ").append(fmt(sums[1] / rows))
                    .append(" | ").append(fmt(sums[2] / rows))
                    .append(" | ").append(fmt(sums[3] / rows))
                    .append(" | ").append(fmt(sums[4] / rows))
                    .append(" | ").append(fmt(sums[5] / rows))
                    .append(" | ").append(fmt(sums[6] / rows))
                    .append(" | ").append(fmt(sums[7] / rows))
                    .append(" |\n");
        }
        writeReport(storyDir.resolve("audit-ab-report.md"), report);
    }

    // ---- 公共执行件 ----

    private List<List<AuditEvalMetrics.IssueDigest>> runAuditKTimes(ChapterAuditService auditService, StoryVO storyVO,
                                                                    Sample sample, int k) {
        List<List<AuditEvalMetrics.IssueDigest>> runs = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            try {
                AuditResultEntity result = auditService.audit(storyVO, sample.item(), sample.content(),
                        sample.ledgerPrompt(), sample.foreshadowing(), sample.styleStat(), sample.chapterNo());
                List<ChapterIssueEntity> issues = result == null || result.getIssues() == null
                        ? List.of() : result.getIssues();
                runs.add(issues.stream().map(AuditEvalMetrics::digest).toList());
            } catch (Exception e) {
                System.out.println("第 " + sample.chapterNo() + " 章第 " + (i + 1) + " 次审计调用失败（按空结果计入）："
                        + e.getMessage());
                runs.add(List.of());
            }
        }
        return runs;
    }

    private List<Sample> loadCorpus(Path storyDir, int limit) throws IOException {
        List<ChapterSummaryEntity> summaries = readSummaries(storyDir);
        StyleStatEntity styleStat = readStyleStat(storyDir);
        List<Sample> samples = new ArrayList<>();
        for (Path record : listChapterRecords(storyDir)) {
            ChapterPlanItemEntity item = JSON.parseObject(Files.readString(record), ChapterPlanItemEntity.class);
            int globalNo = item.getChapterNo();
            Path chapterFile = storyDir.resolve("chapters/chapter-" + String.format("%04d", globalNo) + ".txt");
            if (!Files.exists(chapterFile)) {
                continue;
            }
            List<ChapterSummaryEntity> history = summaries.stream()
                    .filter(s -> s.getChapterNo() != null && s.getChapterNo() < globalNo)
                    .toList();
            String ledgerPrompt = renderLedger(history);
            String foreshadowing = String.join("\n", MEMORY_SERVICE.buildPendingForeshadowing(history));
            samples.add(new Sample(globalNo, item, readChapterBody(chapterFile),
                    ledgerPrompt, foreshadowing, styleStat));
            if (samples.size() >= limit) {
                break;
            }
        }
        return samples;
    }

    private ChapterAuditService newAuditService() {
        return new ChapterAuditService(new SpringAiLlmGateway(1, 20, 600, new LlmRuntimeConfig(),
                new LlmBudgetFuse(
                        new StoryProperties(),
                        new JobRegistry())));
    }

    private String worstDimension(AuditEvalMetrics.SampleNoise noise) {
        return noise.dimensions().stream()
                .filter(d -> d.meanCount() > 0)
                .max((a, b) -> Double.compare(a.cv(), b.cv()))
                .map(d -> d.dimension() + "(" + fmt(d.cv()) + ")")
                .orElse("-");
    }

    private void appendPerDimensionDetails(StringBuilder report, List<AuditEvalMetrics.SampleNoise> all) {
        report.append("\n## 分维度明细\n");
        for (AuditEvalMetrics.SampleNoise noise : all) {
            report.append("\n### 第 ").append(noise.chapterNo()).append(" 章\n");
            report.append("| 维度 | 均条数 | CV |\n|------|--------|----|\n");
            for (AuditEvalMetrics.DimensionNoise dim : noise.dimensions()) {
                report.append("| ").append(dim.dimension())
                        .append(" | ").append(fmt(dim.meanCount()))
                        .append(" | ").append(fmt(dim.cv()))
                        .append(" |\n");
            }
        }
    }

    private Map<Integer, List<List<AuditEvalMetrics.IssueDigest>>> readAbFile(Path file) throws IOException {
        Map<Integer, List<List<AuditEvalMetrics.IssueDigest>>> byChapter = new java.util.LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            com.alibaba.fastjson2.JSONObject obj = JSON.parseObject(line);
            int chapterNo = obj.getIntValue("chapterNo");
            List<List<AuditEvalMetrics.IssueDigest>> runs = new ArrayList<>();
            for (Object runObj : obj.getJSONArray("runs")) {
                List<ChapterIssueEntity> issues = JSON.parseArray(
                        JSON.toJSONString(runObj), ChapterIssueEntity.class);
                runs.add(issues.stream().map(AuditEvalMetrics::digest).toList());
            }
            byChapter.put(chapterNo, runs);
        }
        return byChapter;
    }

    private String storyDirName() {
        return System.getProperty("eval.story", DEFAULT_STORY_DIR);
    }

    private int kRuns() {
        return Integer.parseInt(System.getProperty("eval.k", String.valueOf(DEFAULT_K)));
    }

    private int limit() {
        return Integer.parseInt(System.getProperty("eval.limit", String.valueOf(DEFAULT_LIMIT)));
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }

    private void writeReport(Path reportFile, StringBuilder report) throws IOException {
        String existing = Files.exists(reportFile) ? Files.readString(reportFile) : "";
        Files.writeString(reportFile, existing + report.toString());
        System.out.println("评测报告已写出: " + reportFile.toAbsolutePath());
    }

    // ---- 语料读取（与 ChapterAuditCalibrationTest 同源） ----

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

    private StoryVO loadStoryVO() throws IOException {
        Path yml = locate("novel_factory-app/src/main/resources/novel-generation.yml");
        Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(Files.readString(yml));
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
        // 保真度关键（A1 基线的测量对象必须是生产审计链路）：
        // 生产 unified-model-enabled=false，审校走 scene-models.audit（deepseek-v4.1-flash, temperature 0.1），
        // 不加载 scene-models 会静默回退统一 chat-model——测出的噪声与生产无关
        storyModule.setUnifiedModelEnabled(Boolean.valueOf(String.valueOf(story.getOrDefault("unified-model-enabled", "false"))));
        Map<String, Object> sceneConfigs = cast(module.get("scene-models"));
        Map<String, StoryVO.Module.ChatModel> sceneModels = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : sceneConfigs.entrySet()) {
            Map<String, Object> cfg = cast(entry.getValue());
            StoryVO.Module.ChatModel sceneModel = new StoryVO.Module.ChatModel();
            sceneModel.setModel((String) cfg.get("model"));
            if (cfg.get("max-tokens") != null) {
                sceneModel.setMaxTokens(Long.valueOf(String.valueOf(cfg.get("max-tokens"))));
            }
            if (cfg.get("temperature") != null) {
                sceneModel.setTemperature(Double.valueOf(String.valueOf(cfg.get("temperature"))));
            }
            if (cfg.get("enable-thinking") != null) {
                sceneModel.setEnableThinking(Boolean.valueOf(String.valueOf(cfg.get("enable-thinking"))));
            }
            sceneModel.setBaseUrl((String) cfg.get("base-url"));
            sceneModel.setApiKey((String) cfg.get("api-key"));
            sceneModel.setCompletionsPath((String) cfg.get("completions-path"));
            sceneModels.put(entry.getKey(), sceneModel);
        }
        storyModule.setSceneModels(sceneModels);

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
            return StyleStatEntity.builder().build();
        }
        StyleStatEntity stat = JSON.parseObject(Files.readString(statFile), StyleStatEntity.class);
        return stat == null ? StyleStatEntity.builder().build() : stat;
    }

    private List<Path> listChapterRecords(Path storyDir) throws IOException {
        Path recordDir = storyDir.resolve("generation-records");
        // scanFlatRecords 对空/缺失目录返回不可变空表，后续 addAll 需要可变集合
        List<Path> records = new ArrayList<>(scanFlatRecords(recordDir));
        if (records.isEmpty()) {
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

    /** 落盘文件首行为"第X章 标题"页眉，审校对象剔除后与原始正文实体一致 */
    private String readChapterBody(Path chapterFile) throws IOException {
        String text = Files.readString(chapterFile);
        int firstBreak = text.indexOf('\n');
        return firstBreak < 0 ? text : text.substring(firstBreak + 1).trim();
    }

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
}
