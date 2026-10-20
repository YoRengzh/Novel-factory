package cn.novel.yonren.domain.novel.service.armory.draft;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service
@Slf4j
@RequiredArgsConstructor
public class SettingDraftService {

    /**
     * 设定集 schema 复杂（10 个字段，含自由长文本与数字），格式抖动概率不低，与章节计划同档：重试 1 次
     */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * Jackson 默认严格模式会拒绝字符串内未转义的控制字符（模型长文本里偶发真实换行），与章节计划同口径放宽；
     * 另外关掉"未知字段即失败"——模型爱多输出几个自认为有用的键（如 summary/notes），
     * 为一个多余键丢掉整份草稿不划算
     */
    private static final BeanOutputConverter<DraftOutput> CONVERTER =
            new BeanOutputConverter<>(DraftOutput.class,
                    JsonMapper.builder()
                            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .build());

    /**
     * 字段描述表：一处声明同时驱动 ①请求/响应的键集合 ②prompt 里的中文名 ③"哪些字段已有值"
     * ④锁定字段的回填。避免在五处写五套 switch（项目里 `exitConditions` 改成对象列表时
     * 连带影响 10 处的教训）
     */
    private static final List<Field> FIELDS = List.of(
            new Field("novelTitle", "书名", DraftOutput::getNovelTitle, DraftOutput::setNovelTitle),
            new Field("style", "风格", DraftOutput::getStyle, DraftOutput::setStyle),
            new Field("worldSetting", "世界观", DraftOutput::getWorldSetting, DraftOutput::setWorldSetting),
            new Field("perspective", "视角", DraftOutput::getPerspective, DraftOutput::setPerspective),
            new Field("targetAudience", "目标人群", DraftOutput::getTargetAudience, DraftOutput::setTargetAudience),
            new Field("tone", "基调", DraftOutput::getTone, DraftOutput::setTone),
            new Field("protagonist", "主人公", DraftOutput::getProtagonist, DraftOutput::setProtagonist),
            new Field("outline", "故事概述", DraftOutput::getOutline, DraftOutput::setOutline),
            new Field("chapterGoal", "章节目标", DraftOutput::getChapterGoal, DraftOutput::setChapterGoal),
            new Field("totalChapters", "建议总章数",
                    d -> d.getTotalChapters() == null ? null : String.valueOf(d.getTotalChapters()),
                    (d, v) -> d.setTotalChapters(parseIntOrNull(v))));

    private final LlmInvokeService llmInvokeService;

    /**
     * 生成/重生成设定集草稿。
     *
     * @param storyVO yml 模型配置（走 scene-models.setting-draft；未配置时回落统一 chat-model）
     * @param request 题材 + 可选偏好 + 目标字段 + 上一版内容
     * @throws AppException 题材为空（非法参数）或两次尝试均无法产出齐备的草稿
     */
    public Result draft(StoryVO storyVO, DraftRequest request) {
        if (request == null || StringUtils.isBlank(request.theme())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "一键生成设定集至少需要题材（theme）");
        }
        List<String> targets = normalizeTargets(request.targets());
        Map<String, String> previous = blankSafeMap(request.previous());
        // 无任何旧值时，"只重生成某字段"等价于全量生成：没有可锁定的东西，prompt 也不该提"保持既有设定"
        boolean hasPrevious = previous.values().stream().anyMatch(StringUtils::isNotBlank);

        String prompt = buildPrompt(request, targets, previous, hasPrevious);
        PromptContext ctx = PromptContext.builder()
                .theme(request.theme())
                .style(request.style())
                .build();

        // 重试纠错要把"上轮到底怎么了"分清：JSON 根本没解析出来 vs 解析出来了但某些字段空着，
        // 两者给模型的指令完全不同（笼统说"输出不合法"会让模型去改格式而漏掉真缺的内容）
        boolean parseFailed = false;
        List<String> missing = List.of();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String callPrompt = attempt == 1 ? prompt : prompt + retryHint(parseFailed, missing);
            String raw = llmInvokeService.invoke(storyVO, PromptScene.SETTING_DRAFT, ctx, callPrompt, null);

            DraftOutput parsed = JsonParseFallback.parse(raw, CONVERTER::convert);
            parseFailed = parsed == null;
            missing = parseFailed ? List.of() : missingFields(parsed, targets);
            if (!parseFailed && missing.isEmpty()) {
                lockNonTargetFields(parsed, previous, targets);
                log.info("设定集草稿生成成功，题材: {}，重生成字段: {}，锁定字段: {}",
                        request.theme(), targets, previous.isEmpty() ? 0 : FIELDS.size() - targets.size());
                return new Result(parsed, targets);
            }
            log.warn("设定集草稿第 {}/{} 次输出不可用（{}）{}", attempt, MAX_ATTEMPTS,
                    parseFailed ? "JSON 解析失败" : "缺失字段 " + missing,
                    attempt < MAX_ATTEMPTS ? "，将重试" : "");
        }

        throw new AppException(ResponseCode.UN_ERROR.getCode(),
                "设定集生成失败（含修复后重解与一次重试）。可换个题材措辞或补充「额外要求」后重试");
    }

    // ==================== prompt 组装 ====================

    private String buildPrompt(DraftRequest request, List<String> targets,
                               Map<String, String> previous, boolean hasPrevious) {
        boolean full = targets.size() == FIELDS.size();

        StringBuilder sb = new StringBuilder();
        sb.append("你是资深网文编辑兼策划。请根据下面的输入，产出一份可以直接开工的小说设定集。\n\n");
        sb.append("【题材】").append(request.theme().trim()).append('\n');
        appendLine(sb, "用户已定的风格倾向", request.style());
        appendLine(sb, "用户已定的目标人群", request.targetAudience());
        appendLine(sb, "用户已定的基调", request.tone());
        appendLine(sb, "用户已定的叙述视角", request.perspective());
        appendLine(sb, "额外要求（必须遵守）", request.extraHints());

        if (hasPrevious && !full) {
            sb.append("\n【必须保持不变的既有设定（严禁改写）】\n");
            for (Field field : FIELDS) {
                if (targets.contains(field.key())) {
                    continue;
                }
                String value = previous.get(field.key());
                if (StringUtils.isNotBlank(value)) {
                    sb.append("- ").append(field.label()).append("：").append(value).append('\n');
                }
            }
            sb.append("\n【本次需要重写的字段】")
                    .append(targets.stream().map(this::labelOf).collect(Collectors.joining("、")))
                    .append("。重写的内容必须与上面列出的既有设定完全自洽——不得引入与它们冲突的新设定。\n");
        }

        if (hasPrevious) {
            List<String> rewritten = targets.stream()
                    .filter(t -> StringUtils.isNotBlank(previous.get(t)))
                    .toList();
            if (!rewritten.isEmpty()) {
                sb.append("\n【与上一版的区别（重要）】上一版内容如下，本次必须换一个明显不同的构思方向");
                if (!full) {
                    sb.append("（在既有设定不变的前提下，换核心冲突的组织方式、换对手、换切入场景）");
                }
                sb.append("，不得只做同义改写或调换措辞：\n");
                for (String key : rewritten) {
                    sb.append("- ").append(labelOf(key)).append("（上一版）：").append(previous.get(key)).append('\n');
                }
            }
        }

        sb.append("\n【输出要求】\n")
                .append("1. 全部使用中文；不得出现「待定」「略」「同上」之类占位内容。\n")
                .append("2. 书名：8-14 字，有题材辨识度，读起来像真实的网文标题。\n")
                .append("3. 风格：一句话定位，说明写法与节奏倾向（例：逆袭打脸爽文，节奏快、爽点密）。\n")
                .append("4. 世界观：200-400 字，写清力量/规则体系、社会结构、以及最关键的**限制与代价**。\n")
                .append("5. 视角：一句话（例：第三人称限知视角，主跟主角）。\n")
                .append("6. 目标人群：一句话。\n")
                .append("7. 基调：一句话。\n")
                .append("8. 主人公：150-300 字，写清身份、性格、初始处境、内在欲望与致命弱点。\n")
                .append("9. 故事概述：350-600 字，**按时间顺序叙述**，必须覆盖①主角初始处境 ②核心冲突 "
                + "③主要对手或障碍 ④至少两个关键转折 ⑤结局方向。不要分点罗列，不要写成章节清单。\n")
                .append("10. 章节目标：一句话，说明这本书要让读者获得什么体验。\n")
                .append("11. 建议总章数：60-300 之间的整数，按题材体量与上面故事的容量给一个合理值。\n")
                .append("12. rationale：1-2 句，说明本次构思的取舍（供策划者判断是否需要重新生成）。\n")
                .append("\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat());
        return sb.toString();
    }

    private void appendLine(StringBuilder sb, String label, String value) {
        if (StringUtils.isNotBlank(value)) {
            sb.append("【").append(label).append("】").append(value.trim()).append('\n');
        }
    }

    private String labelOf(String key) {
        for (Field field : FIELDS) {
            if (field.key().equals(key)) {
                return field.label();
            }
        }
        return key;
    }

    /**
     * 字段缺失时的纠错指令：把"缺哪些字段"或"格式不合法"明确钉给模型，
     * 比笼统让他"重试"有效得多
     */
    private static String retryHint(boolean parseFailed, List<String> missing) {
        String problem = parseFailed
                ? "（JSON 格式不合法，无法解析）"
                : "：这些字段缺失或为空——" + String.join("、", missing);
        return "\n\n【上轮输出的问题】你上一次的输出无法使用" + problem
                + "。请重新输出**完整且合法**的 JSON，所有字段都要有真实内容。";
    }

    // ==================== 机械处理 ====================

    /**
     * targets 归一：null/空 = 全部字段；含未知字段名直接报错（本项目前端是唯一调用方，
     * 悄悄忽略拼错的字段名只会让"点了重生却什么都没变"变成难查的问题）
     */
    private static List<String> normalizeTargets(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return FIELDS.stream().map(Field::key).toList();
        }
        LinkedHashMap<String, Boolean> normalized = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String key : raw) {
            if (StringUtils.isBlank(key)) {
                continue;
            }
            String trimmed = key.trim();
            if (FIELDS.stream().noneMatch(f -> f.key().equals(trimmed))) {
                unknown.add(trimmed);
                continue;
            }
            normalized.put(trimmed, Boolean.TRUE);
        }
        if (!unknown.isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "targets 含未知字段：" + String.join("、", unknown)
                            + "；可选值为 " + FIELDS.stream().map(Field::key).collect(Collectors.joining("、")));
        }
        return normalized.isEmpty() ? FIELDS.stream().map(Field::key).toList() : List.copyOf(normalized.keySet());
    }

    private static List<String> missingFields(DraftOutput parsed, List<String> targets) {
        if (parsed == null) {
            return targets;
        }
        return targets.stream().filter(key -> StringUtils.isBlank(readField(parsed, key))).toList();
    }

    private static String readField(DraftOutput output, String key) {
        for (Field field : FIELDS) {
            if (field.key().equals(key)) {
                return field.read().apply(output);
            }
        }
        return null;
    }

    /**
     * 锁定字段机械回填：模型仍按完整 schema 输出，但非目标字段一律以调用方给的值为准。
     * 这就是"只改大纲"不会顺手改掉世界观的原因
     */
    private static void lockNonTargetFields(DraftOutput output, Map<String, String> previous, List<String> targets) {
        if (previous.isEmpty()) {
            return;
        }
        for (Field field : FIELDS) {
            if (targets.contains(field.key())) {
                continue;
            }
            String locked = previous.get(field.key());
            if (StringUtils.isNotBlank(locked)) {
                field.write().accept(output, locked);
            }
        }
    }

    private static Map<String, String> blankSafeMap(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> cleaned = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null) {
                cleaned.put(k, v);
            }
        });
        return cleaned;
    }

    private static Integer parseIntOrNull(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        // 模型偶尔给"180章"这种带单位的数字，剥掉非数字字符再解析；仍失败则保留模型原值
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 入参 / 出参 ====================

    /**
     * 草稿请求。
     *
     * @param theme         题材（必填，唯一硬输入）
     * @param style         用户已定的风格（可空；非空时作为约束而非直接采用）
     * @param targetAudience 用户已定的目标人群（可空）
     * @param tone          用户已定的基调（可空）
     * @param perspective   用户已定的视角（可空）
     * @param extraHints    任意额外要求（可空）
     * @param targets       本次要重生成的字段；空 = 全部重生成。其余字段原样锁定
     * @param previous      当前已有内容：既作为锁定字段的取值来源，也作为"本次必须有所区别"的对照
     */
    public record DraftRequest(String theme, String style, String targetAudience, String tone,
                               String perspective, String extraHints, List<String> targets,
                               Map<String, String> previous) {
    }

    /**
     * 草稿结果。
     *
     * @param fields      字段值（锁定字段已回填为调用方给的原值）
     * @param regenerated 本次实际重生成的字段名（空 targets 归一为全部）
     */
    public record Result(DraftOutput fields, List<String> regenerated) {
    }

    /**
     * 模型输出 schema：字段名同时是前端回填表单的键。用 @Data 而非 record ——
     * 锁定字段需要 setter 回填，且与项目其他结构化输出（ChapterSummaryEntity 等）保持同一写法
     */
    @Data
    public static class DraftOutput {
        /** 书名 */
        private String novelTitle;
        /** 风格定位 */
        private String style;
        /** 世界观 */
        private String worldSetting;
        /** 叙述视角 */
        private String perspective;
        /** 目标人群 */
        private String targetAudience;
        /** 基调 */
        private String tone;
        /** 主人公 */
        private String protagonist;
        /** 故事概述：全书走向，覆盖初始处境/核心冲突/障碍/关键转折/结局方向 */
        private String outline;
        /** 全书写作目标 */
        private String chapterGoal;
        /** 建议总章数（前端填入 maxChapterCount；必须在本批提交前定下，该值会被 sticky 固化） */
        private Integer totalChapters;
        /** 构思取舍说明（1-2 句），供判断是否需要重生成 */
        private String rationale;
    }

    /** 字段描述：中文名 + 读写访问器 */
    private record Field(String key, String label,
                         Function<DraftOutput, String> read,
                         BiConsumer<DraftOutput, String> write) {
    }

}
