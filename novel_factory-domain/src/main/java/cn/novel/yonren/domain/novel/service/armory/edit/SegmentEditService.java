package cn.novel.yonren.domain.novel.service.armory.edit;

import cn.novel.yonren.api.dto.SegmentEditRequestDTO;
import cn.novel.yonren.api.dto.SegmentEditResponseDTO;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 段落级文本编辑：选中一段正文，让 AI 只改这一段——润色 / 扩写 / 续写。
 *
 * 与整章修订的分工：整章修订（{@code ChapterReviseService}）跑的是带账本、伏笔、
 * 风格统计的完整 prompt，一次调用等于重写一章，成本与耗时都是整章量级。本服务面向"我就想
 * 把这一句写顺"的轻量诉求：短 prompt、小输出上限、不读账本、不落盘。
 *
 * 为什么不复用 CHAPTER_REVISE 场景：该场景的 prompt 规则注入（反 AI 腔、视角纪律、
 * 硬约束）恰恰是段落编辑需要的，所以仍然走同一 {@link PromptScene} 拿规则；差异只在
 * userPrompt 更短、maxTokens 更小。这样"改写质量口径"与整章修订保持一致，不会出现
 * "整章很干净、单段很 AI"的分裂。
 *
 * 输出契约：只回传替换片段，前端做 diff 对照由用户确认。服务本身不写盘——
 * 写盘走既有 PUT 章节接口，从而复用它的摘要回流与运行中作业互斥检查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentEditService {

    /** 润色：只改表达，不改事实、不改篇幅 */
    public static final String ACTION_POLISH = "polish";
    /** 扩写：在原有事实基础上加细节，篇幅明显变长 */
    public static final String ACTION_EXPAND = "expand";
    /** 续写：沿原文语气与事件继续往下写 */
    public static final String ACTION_CONTINUE = "continue";

    /** 上下文截断长度：太多会稀释"只改这一段"的指令，太少会丢指代 */
    private static final int CONTEXT_CHARS = 600;
    /** 输出上限：段落级编辑不该产出整章篇幅，上限压小也顺带省钱 */
    private static final int MAX_TOKENS = 2048;

    private final LlmInvokeService llmInvokeService;

    /**
     * 执行一次段落编辑。任何异常都收敛为 fallback（返回原文 + 原因），绝不把 500 抛给用户——
     * 一次"没改成"不该让整个阅读页报错。
     */
    public SegmentEditResponseDTO edit(StoryVO storyVO, int chapterNo,
                                       SegmentEditRequestDTO request,
                                       PromptContext baseContext) {
        String action = StringUtils.trimToNull(request.getAction());
        String selected = request.getSelectedText();
        if (StringUtils.isBlank(selected)) {
            throw new IllegalArgumentException("选中文本为空");
        }
        if (action == null) {
            throw new IllegalArgumentException("缺少编辑动作（polish / expand / continue）");
        }
        if (!ACTION_POLISH.equals(action) && !ACTION_EXPAND.equals(action) && !ACTION_CONTINUE.equals(action)) {
            throw new IllegalArgumentException("不支持的编辑动作：" + action + "（仅 polish / expand / continue）");
        }

        PromptContext ctx = PromptContext.builder()
                .theme(baseContext == null ? null : baseContext.getTheme())
                .style(baseContext == null ? null : baseContext.getStyle())
                .chapterNo(chapterNo)
                .totalChapters(baseContext == null ? null : baseContext.getTotalChapters())
                .chapterType(baseContext == null ? null : baseContext.getChapterType())
                .chapterBrief(baseContext == null ? null : baseContext.getChapterBrief())
                .build();

        String prompt = buildPrompt(action, selected, request);
        try {
            Map<String, String> usedPrompts = new java.util.HashMap<>();
            String raw = llmInvokeService.invokeWithScene(storyVO, PromptScene.CHAPTER_REVISE, ctx,
                    prompt, usedPrompts,
                    cn.novel.yonren.types.enums.ModelScene.CHAPTER_REVISE,
                    "segment-edit-" + action + "-第" + chapterNo + "章", MAX_TOKENS);
            String cleaned = cleanOutput(raw);
            if (StringUtils.isBlank(cleaned)) {
                log.warn("段落编辑第{}章 {} 动作输出为空，按未改动返回", chapterNo, action);
                return new SegmentEditResponseDTO(selected, action, true, "模型未返回可用内容，正文未改动");
            }
            return new SegmentEditResponseDTO(cleaned, action, false, null);
        } catch (Exception e) {
            // 异常消息可能为空（如 AppException 无 info、NPE 无 message）——此时退回异常类型名，
            // 否则前端会显示"调用失败：null"，对排查毫无帮助
            String reason = StringUtils.isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("段落编辑第{}章 {} 动作失败，按未改动返回：{}", chapterNo, action, reason);
            return new SegmentEditResponseDTO(selected, action, true, "调用失败：" + reason);
        }
    }

    /**
     * 拼 prompt：三类动作共用骨架，差异只在任务描述与长度约束。
     * 明确"只输出改写后的这一段"，避免模型回显原文、加解释或输出整章。
     */
    static String buildPrompt(String action, String selected, SegmentEditRequestDTO request) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名资深小说编辑。用户在正文里选中了一段文字，请只针对这一段做「")
                .append(actionLabel(action)).append("」。\n\n");

        String before = abbreviate(request.getContextBefore(), CONTEXT_CHARS, true);
        String after = abbreviate(request.getContextAfter(), CONTEXT_CHARS, false);
        if (before != null || after != null) {
            sb.append("【上下文·仅供理解语气与指代，不要改写或输出这部分】\n");
            if (before != null) {
                sb.append("……").append(before).append("\n");
            }
            sb.append("「选中片段」\n");
            if (after != null) {
                sb.append(after).append("……\n");
            }
            sb.append("\n");
        }

        sb.append("【选中片段原文】\n").append(selected).append("\n\n");
        sb.append("【任务】\n").append(taskInstruction(action)).append("\n\n");

        String extra = StringUtils.trimToNull(request.getInstruction());
        if (extra != null) {
            sb.append("【用户附加要求（优先于上面的默认倾向）】\n").append(extra).append("\n\n");
        }

        sb.append("【输出要求】\n")
                .append(ACTION_CONTINUE.equals(action)
                        // 续写走"追加"语义：前端把返回内容接在选中片段之后，原文原样保留。
                        // 若让模型回传完整段落，前端追加会造成原文出现两次
                        ? "1. 只输出**新续写的内容**，不要重复选中的原文，不要输出上下文、不要加解释或标题；\n"
                        : "1. 只输出改写后的这一段文字本身，不要输出上下文、不要加解释或标题；\n")
                .append("2. 保持原文的人称、时态、专有名词与既成事实不变")
                .append(ACTION_CONTINUE.equals(action)
                        ? "（续写可以引入新内容，但必须与上文语气连贯、事件承接自然）；\n"
                        : "（润色与扩写都不得改变情节事实）；\n")
                .append("3. 不要使用 Markdown 标记，不要加引号包裹。\n")
                // 网关对 OpenAI 协议强制 JSON_OBJECT（治正文/摘要的截断不闭合），
                // 不显式给 schema 时模型会自创键名（实测返回 {"text": "..."} 但各模型键名不一）。
                // 明确 schema 能让解包稳定命中，也避免模型把解释塞进别的键。
                .append(ACTION_CONTINUE.equals(action)
                        ? "4. 按以下 JSON 格式输出，新续写的内容放在 text 字段：{\"text\":\"紧接上文的新内容\"}\n"
                        : "4. 按以下 JSON 格式输出，改写结果放在 text 字段：{\"text\":\"改写后的这一段文字\"}\n");
        return sb.toString();
    }

    private static String actionLabel(String action) {
        return switch (action) {
            case ACTION_POLISH -> "润色";
            case ACTION_EXPAND -> "扩写";
            default -> "续写";
        };
    }

    private static String taskInstruction(String action) {
        return switch (action) {
            case ACTION_POLISH ->
                "在不改变篇幅量级的前提下，让表达更准确、节奏更顺、去 AI 腔。\n"
                        + "具体要求：删掉空话与重复确认；把抽象评价换成可观察的动作或细节；"
                        + "调整语序让长短句交替；不新增情节事实。";
            case ACTION_EXPAND ->
                "在原有事实与情绪的基础上，把这一段写得更充分（大致 1.5–3 倍篇幅）。\n"
                        + "具体要求：补充可感知的细节（动作、感官、具体物件、身体反应），"
                        + "不要靠形容词堆砌或复述已有信息撑长度；不新增情节转折。\n"
                        // 前端是"整段替换"，必须让模型回传完整段落——只回新增部分会把原文冲掉
                        + "**输出必须包含原文的全部内容并在此基础上扩充**，返回完整的新段落，不是新增片段。";
            default ->
                "沿着这一段结尾的情绪与事件，自然往下续写一小段（一段话量级）。\n"
                        + "具体要求：承接上文的动作或话题，让下一个动作有来由；"
                        + "不要把矛盾提前解决，不要引入与上文无关的新人物或新地点。\n"
                        // 续写走"追加"语义：前端把返回内容接在选中片段之后，原文原样保留
                        + "**只输出新续写的内容**：从选中片段的结尾自然接下去，"
                        + "不要重复选中片段里的任何句子。";
        };
    }

    /**
     * 清洗模型输出：解 JSON 包裹、去代码围栏、去包裹引号、去"以下是改写结果"之类的引导语。
     * 段落编辑的输出形态很窄，机械清洗比让模型自纠更可靠。
     *
     * 为什么必须解 JSON：网关对 OpenAI 协议的所有调用都强制
     * {@code responseFormat=JSON_OBJECT}（治正文/摘要的截断不闭合），模型因此会返回
     * {@code {"text":"..."}} 而不是纯文本。不解包的话整串 JSON 会被当成改写结果塞回正文。
     */
    static String cleanOutput(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String text = raw.trim();
        // 去掉 ``` 围栏（模型对"不要 Markdown"的服从率并不高）
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.trim();
        }
        // JSON Mode 的产物：{"text": "..."} / {"content": "..."} / {"result": "..."} / {"edited": "..."}
        text = unwrapJsonText(text);
        // 去掉整体包裹的引号
        if ((text.startsWith("「") && text.endsWith("」")) || (text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("“") && text.endsWith("”"))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        // 去掉"改写后："这类前缀（只处理首行短前缀，避免误删正文）
        String firstLine = text.split("\n", 2)[0];
        if (firstLine.length() <= 12 && firstLine.endsWith("：")) {
            int nl = text.indexOf('\n');
            if (nl > 0) {
                text = text.substring(nl + 1).trim();
            }
        }
        return StringUtils.trimToNull(text);
    }

    /**
     * 从 JSON 对象里取出正文：按常见键名优先级找第一个非空字符串值。
     * 不是合法 JSON、或找不到字符串字段时原样返回（交给后续清洗继续处理）。
     *
     * 键名不限于 text——不同模型对 JSON Mode 的 schema 理解各异，实测出现过
     * content / result / edited / output 等写法，统一按候选列表依次尝试。
     */
    static String unwrapJsonText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return text;
        }
        try {
            com.alibaba.fastjson2.JSONObject obj = com.alibaba.fastjson2.JSON.parseObject(trimmed);
            if (obj == null || obj.isEmpty()) {
                return text;
            }
            for (String key : new String[]{"text", "content", "result", "edited", "output", "revised"}) {
                String value = obj.getString(key);
                if (StringUtils.isNotBlank(value)) {
                    return value.trim();
                }
            }
            // 没有约定键名时：若对象只有一个字符串值，取它（模型可能用了任意键名）
            if (obj.size() == 1) {
                Object only = obj.values().iterator().next();
                if (only instanceof String s && StringUtils.isNotBlank(s)) {
                    return s.trim();
                }
            }
        } catch (Exception ignored) {
            // 不是合法 JSON（比如正文本身以 { 开头）→ 原样返回，不吞内容
        }
        return text;
    }

    /** 上下文截断：保留靠近选中片段的一侧，另一侧用省略号示意 */
    private static String abbreviate(String text, int max, boolean keepTail) {
        String value = StringUtils.trimToNull(text);
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return keepTail ? value.substring(value.length() - max) : value.substring(0, max);
    }
}
