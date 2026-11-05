package cn.novel.yonren.infrastructure.calibration;

import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import cn.novel.yonren.domain.novel.service.job.LlmBudgetFuse;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.infrastructure.gateway.SpringAiLlmGateway;
import cn.novel.yonren.types.enums.ModelScene;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.converter.BeanOutputConverter;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 跨系统盲评（手动触发，消耗真实 LLM 配额）：
 * 对两个生成系统的同序章节稿做 A/B 盲评——每章多轮评审、每轮随机交换 A/B 位（消除位置偏好），
 * 评审走 chapter-judge 场景（DeepSeek 第二模型族；两侧参评稿默认同为 qwen 家族，评审族中立无自偏好），
 * 判据与 ChapterCandidateService.judge 同口径：只评文风与事件覆盖，严禁以篇幅长短判胜。
 * 输出逐轮胜负+理由+聚合胜率的 markdown 报告（docs/workspace/eval/）。
 *
 * 运行方式（项目根目录）：
 * mvn test -pl novel_factory-infrastructure -Dtest=CrossSystemBlindEvalTest \
 *   -Deval.blind=true \
 *   -Deval.dirA=docs/workspace/eval/round1/novel_factory \
 *   -Deval.dirB=docs/workspace/eval/round1/ai_novel_generator \
 *   -Deval.plan=docs/workspace/eval/round1/plan.txt \
 *   -Deval.rounds=3 -Deval.nameA=novel_factory -Deval.nameB=ai_novel_generator
 * （eval.plan 可选：单章计划文本，控制变量对比时提供；eval.rounds 每章评审轮数，默认 3）
 */
@EnabledIfSystemProperty(named = "eval.blind", matches = "true")
class CrossSystemBlindEvalTest {

    private static final BeanOutputConverter<Verdict> VERDICT_CONVERTER =
            new BeanOutputConverter<>(Verdict.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    /** 评审输出：winner=A|B（位次，非系统名），reason 供报告留痕 */
    public record Verdict(String winner, String reason) {
    }

    private static final Pattern CHAPTER_NO = Pattern.compile("(\\d+)");

    @Test
    void blindJudgePairedChapters() throws Exception {
        Path dirA = Paths.get(requiredProp("eval.dirA"));
        Path dirB = Paths.get(requiredProp("eval.dirB"));
        String nameA = System.getProperty("eval.nameA", "系统A");
        String nameB = System.getProperty("eval.nameB", "系统B");
        int rounds = Integer.parseInt(System.getProperty("eval.rounds", "3"));
        String planText = optionalFile(System.getProperty("eval.plan"));

        List<Path> filesA = listChapters(dirA);
        List<Path> filesB = listChapters(dirB);
        if (filesA.size() != filesB.size() || filesA.isEmpty()) {
            throw new IllegalStateException("两侧章节数不一致或为空：A=" + filesA.size() + "，B=" + filesB.size()
                    + "——请按同序成对放置稿件后重试");
        }

        StoryVO storyVO = loadStoryVO();
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(1, 20, 600, new LlmRuntimeConfig(),
                new LlmBudgetFuse(new StoryProperties(), new JobRegistry()));

        StringBuilder report = new StringBuilder("# 跨系统盲评报告\n")
                .append("生成时间：").append(LocalDateTime.now()).append('\n')
                .append("评审模型：chapter-judge 场景（DeepSeek，与参评稿家族中立）\n")
                .append("每章轮数：").append(rounds).append("（每轮随机交换 A/B 位，消除位置偏好）\n")
                .append("计划文本：").append(planText == null ? "无（纯文风对比）" : "已提供\n")
                .append("对比：").append(nameA).append(" vs ").append(nameB).append("\n\n");

        int totalA = 0, totalB = 0, totalSkip = 0;
        for (int i = 0; i < filesA.size(); i++) {
            String textSysA = Files.readString(filesA.get(i), StandardCharsets.UTF_8);
            String textSysB = Files.readString(filesB.get(i), StandardCharsets.UTF_8);
            int chapterNo = chapterNumberOf(filesA.get(i), i);

            int aWins = 0, bWins = 0, skipped = 0;
            report.append("## 第 ").append(chapterNo).append(" 章\n");
            for (int r = 1; r <= rounds; r++) {
                boolean sysAFirst = ThreadLocalRandom.current().nextBoolean();
                String posA = sysAFirst ? textSysA : textSysB;
                String posB = sysAFirst ? textSysB : textSysA;
                Verdict verdict = judge(storyVO, gateway, planText, posA, posB);
                if (verdict == null) {
                    skipped++;
                    totalSkip++;
                    report.append("- 第 ").append(r).append(" 轮：评审不可用/输出非法，跳过\n");
                    continue;
                }
                boolean aSysWins = "A".equalsIgnoreCase(verdict.winner()) == sysAFirst;
                if (aSysWins) {
                    aWins++;
                    totalA++;
                } else {
                    bWins++;
                    totalB++;
                }
                report.append("- 第 ").append(r).append(" 轮：胜者=").append(aSysWins ? nameA : nameB)
                        .append("｜理由：").append(StringUtils.defaultString(verdict.reason())).append('\n');
            }
            report.append("- 小计：").append(nameA).append(" ").append(aWins).append(" 胜 / ")
                    .append(nameB).append(" ").append(bWins).append(" 胜")
                    .append(skipped > 0 ? "（" + skipped + " 轮无效）" : "").append("\n\n");
        }

        int total = totalA + totalB;
        report.append("## 聚合\n")
                .append("- ").append(nameA).append("：").append(totalA).append(" 胜（")
                .append(total == 0 ? "-" : String.format("%.1f%%", totalA * 100.0 / total)).append("）\n")
                .append("- ").append(nameB).append("：").append(totalB).append(" 胜（")
                .append(total == 0 ? "-" : String.format("%.1f%%", totalB * 100.0 / total)).append("）\n")
                .append("- 无效轮：").append(totalSkip).append('\n')
                .append("- 判定口径：胜率显著超 50% 才有说服力；样本量 = 章数 × 轮数，5 章 × 3 轮 = 15 判定起步\n");

        Path out = Paths.get("docs/workspace/eval",
                "blind-eval-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println("盲评报告已写入: " + out.toAbsolutePath());
    }

    /**
     * 单轮盲评：两稿裸正文随机定 A/B（剥离来源），只评文风与事件覆盖，明令不评长短。
     * 评审失败/解析失败返回 null（计入无效轮，不误判）
     */
    private Verdict judge(StoryVO storyVO, SpringAiLlmGateway gateway,
                          String planText, String posA, String posB) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("你是小说评审。下面是同一章节的两稿正文（A/B），只依据以下两条标准判定哪稿更好：")
                    .append("\n1) 文风：句式多样性、信息密度、套话与模板腔多少、画面是否具体；")
                    .append("\n2) 剧情落实：关键事件是否都推进、结尾悬念是否成立。")
                    .append("\n严禁以下列任何理由判胜：篇幅长短、细节多少、谁更详尽或更完整——长度差异不构成评判依据。")
                    .append("\n只输出 JSON。");
            if (StringUtils.isNotBlank(planText)) {
                sb.append("\n\n【本章计划（评判事件覆盖的依据）】\n").append(planText);
            }
            sb.append("\n\n【候选 A】\n").append(posA)
                    .append("\n\n【候选 B】\n").append(posB)
                    .append("\n\n请严格按照以下 JSON 格式输出：\n").append(VERDICT_CONVERTER.getFormat());

            String raw = gateway.complete(storyVO.getModule(), LlmCall.builder()
                    .userPrompt(sb.toString())
                    .label("cross-system-blind-eval")
                    .scene(ModelScene.CHAPTER_JUDGE)
                    .maxTokens(1024)
                    .build());
            Verdict verdict = VERDICT_CONVERTER.convert(raw);
            if (verdict == null || verdict.winner() == null
                    || (!"A".equalsIgnoreCase(verdict.winner()) && !"B".equalsIgnoreCase(verdict.winner()))) {
                return null;
            }
            return verdict;
        } catch (Exception e) {
            System.out.println("盲评调用异常： " + e.getMessage());
            return null;
        }
    }

    /** 章节文件列表：文件名含数字按数字排序（chapter-0023.txt / 23.txt / 第23章.txt 均可），否则按文件名 */
    private List<Path> listChapters(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("目录不存在：" + dir.toAbsolutePath());
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .toList());
            files.sort((a, b) -> {
                Integer na = chapterNumberOf(a, null), nb = chapterNumberOf(b, null);
                if (na != null && nb != null) return Integer.compare(na, nb);
                return a.getFileName().compareTo(b.getFileName());
            });
            return files;
        }
    }

    private Integer chapterNumberOf(Path file, Integer fallback) {
        Matcher m = CHAPTER_NO.matcher(file.getFileName().toString());
        return m.find() ? Integer.valueOf(m.group(1)) : fallback;
    }

    private String optionalFile(String pathStr) throws IOException {
        if (StringUtils.isBlank(pathStr)) {
            return null;
        }
        Path path = Paths.get(pathStr);
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
    }

    private String requiredProp(String key) {
        String value = System.getProperty(key);
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("缺少系统属性 -D" + key);
        }
        return value;
    }

    /** 加载 novel-generation.yml：module 级 api/chat-model + scene-models（含场景级 base-url/apiKey 覆盖） */
    @SuppressWarnings("unchecked")
    private StoryVO loadStoryVO() throws IOException {
        Path yml = Paths.get("novel_factory-app/src/main/resources/novel-generation.yml");
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

        Object sceneModelsObj = module.get("scene-models");
        if (sceneModelsObj instanceof Map) {
            Map<String, StoryVO.Module.ChatModel> scenes = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) sceneModelsObj).entrySet()) {
                Map<String, Object> sm = cast(entry.getValue());
                StoryVO.Module.ChatModel cm = new StoryVO.Module.ChatModel();
                cm.setModel((String) sm.get("model"));
                if (sm.get("max-tokens") != null) {
                    cm.setMaxTokens(Long.valueOf(String.valueOf(sm.get("max-tokens"))));
                }
                if (sm.get("temperature") != null) {
                    cm.setTemperature(Double.valueOf(String.valueOf(sm.get("temperature"))));
                }
                if (sm.get("enable-thinking") != null) {
                    cm.setEnableThinking(Boolean.valueOf(String.valueOf(sm.get("enable-thinking"))));
                }
                cm.setBaseUrl((String) sm.get("base-url"));
                cm.setApiKey((String) sm.get("api-key"));
                scenes.put(entry.getKey(), cm);
            }
            storyModule.setSceneModels(scenes);
            storyModule.setUnifiedModelEnabled(Boolean.FALSE);
        }

        StoryVO storyVO = new StoryVO();
        storyVO.setModule(storyModule);
        return storyVO;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object obj) {
        return (Map<String, Object>) obj;
    }
}
