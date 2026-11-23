package cn.novel.yonren.domain.novel.service.armory.revise;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.RevisionDecisionEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService;
import cn.novel.yonren.domain.novel.service.armory.quality.PatchIntegrityPolicy;
import cn.novel.yonren.domain.novel.service.armory.plan.ScenePlanPolicy;
import cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract;
import cn.novel.yonren.domain.novel.service.armory.prompt.WritingContractPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.NarrativeStylePolicy;
import cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 章节修订服务：针对 BLOCKING 问题优先应用局部补丁，修订稿须通过采纳闸门。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterReviseService {

    private static final BeanOutputConverter<ChapterContentEntity> CONVERTER =
            new BeanOutputConverter<>(ChapterContentEntity.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());

    /** 先解析完成状态，再解析正文/补丁，避免 JSON 降级解析把明确拒绝吞成可采纳稿。 */
    private static final BeanOutputConverter<CompletionStatus> STATUS_CONVERTER =
            new BeanOutputConverter<>(CompletionStatus.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());

    private static final double MIN_LENGTH_RATIO = 0.8;

    private static final JsonMapper PATCH_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * 兼容事实硬伤修正造成的词面覆盖变化，阈值不证明真实事件可被删除。
     * 最多容忍 1 条供后续语义复核；真实未完成须通过 completed=false 返回拒绝。
     */
    private static final int REVISE_ALLOWED_EVENT_LOSS = 1;

    /** 整章重写形态的任务头 */
    private static final String TASK_HEADER_REWRITE =
            "你是小说流水线修订员。请根据下方的 BLOCKING 问题，对原稿进行最小化修订。"
                    + "\n修订原则："
                    + "\n1. 只修 issue 指向的问题；"
                    + "\n2. 未被 issue 覆盖的段落保持原样；"
                    + "\n3. 输出整章 JSON；不能同时满足可信事实与本次计划时，按完成状态规则明确拒绝。";


    private static final String TASK_HEADER_PATCH =
            "你是小说流水线修订员。请先判断下面的 BLOCKING 问题能否用**最小范围的定向补丁**修掉。"
                    + "\n\n【定向补丁是什么】你只给出「原文锚点 → 替换文本」，程序会把原稿中的该锚点替换掉。"
                    + "补丁之外的正文一个字都不会被改动——所以你不需要、也无法通过补丁做整章性调整。"
                    + "\n\n【输出规则】"
                    + "\n1. anchor 必须是**原稿中逐字存在、且只出现一次**的片段，必须原样复制（一个标点都不能改）；"
                    + "\n2. replacement 是替换后的文本；置为空字符串表示删除该片段；"
                    + "\n3. 一处问题一个补丁，补丁之间不得重叠；"
                    + "\n4. 只有当问题**确实无法用局部改动收敛**（例如需要重排整章结构、事件顺序整体错乱）时，"
                    + "才返回空 patches 和 rewriteReason，指出具体哪些场景或事件必须整体调整。"
                    + "锚点找不到、格式不确定或修改太长时，应重新定位原文并缩小改动。"
                    + "\n\n【补丁的典型形态（示例）】"
                    + "\n问题：正文首次持有某物，但可信上下文已明确记录取回，只漏了本章衔接"
                    + "\n正确做法：anchor = 该物首次出现的那一句原文；replacement = 同一句 + 有事实依据的衔接"
                    + "\n若无证据支持取回，不能编造取回过程；先核对能力、地点与物品状态，无法兼容则明确拒绝。"
                    + "\n错误做法：在 replacement 里重写整段甚至整章——那已经不是定向补丁了。";

    private static final String COMPLETION_RULE =
            "【完成状态】完成修订时可返回 completed=true；reason 说明有事实依据的修正，不把猜测当证据。"
                    + "若计划与可信冻结事实无法同时满足，返回 completed=false（未完成/拒绝），reason 指明冲突、依据及计划需调整；"
                    + "事实不足时指出缺失证据，不编造新能力、物品、材料或既成事件。"
                    + "completed=false 的正文或补丁均不采纳，也不靠扩大重写范围强行完成；不改写历史稿、不自动调整计划。";

    private final LlmGateway llmGateway;
    private final StyleStatService styleStatService;
    private final StoryProperties storyProperties;

    /**
     * 针对 BLOCKING 问题发起修订，返回修订决策与修订稿。
     *
     * @param storyVO        模型配置
     * @param item           本章计划
     * @param originalContent 原稿全文
     * @param blockingIssues 待修复的 BLOCKING 问题
     * @param ledgerPrompt   三账本渲染文本（可为 null）
     * @param foreshadowing  伏笔账文本（可为 null）
     * @param styleStat      当前风格统计（用于闸门与疲劳词禁新增名单，可为 null）
     * @param globalNo       全局章节号（仅用于日志）
     * @param previousRejectionReason 上一轮修订未采纳的原因（多轮修订时回传，首轮为 null）
     * @return 修订结果（含决策与修订稿；未采纳时修订稿为 null）
     */
    public ReviseResult revise(StoryVO storyVO,
                               ChapterPlanItemEntity item,
                               String originalContent,
                               List<ChapterIssueEntity> blockingIssues,
                               String ledgerPrompt,
                               String foreshadowing,
                               StyleStatEntity styleStat,
                               int globalNo,
                               String previousRejectionReason) {
        return revise(storyVO, item, originalContent, blockingIssues, ledgerPrompt, foreshadowing,
                styleStat, globalNo, previousRejectionReason, null);
    }

    public ReviseResult revise(StoryVO storyVO, ChapterPlanItemEntity item, String originalContent,
                               List<ChapterIssueEntity> blockingIssues, String ledgerPrompt, String foreshadowing,
                               StyleStatEntity styleStat, int globalNo, String previousRejectionReason,
                               RewriteContext writerContext) {
        if (blockingIssues == null || blockingIssues.isEmpty()) {
            return new ReviseResult(
                    RevisionDecisionEntity.builder()
                            .attempted(false)
                            .accepted(false)
                            .reason("无 BLOCKING 问题，无需修订")
                            .build(),
                    null);
        }

        if (writerContext != null) {
            writerContext.checkCancellation();
            if (ChapterLengthPolicy.isCollapsed(originalContent)) {
                return collapseRewrite(storyVO, item, originalContent, blockingIssues, ledgerPrompt, foreshadowing,
                        styleStat, globalNo, previousRejectionReason, writerContext);
            }
        }
        try {
            String rewriteReason = previousRejectionReason;
            if (patchEnabled()) {
                String diagnostic = previousRejectionReason;
                for (int attempt = 1; attempt <= 2; attempt++) {
                    PatchAttempt patch = tryPatch(storyVO, item, originalContent, blockingIssues,
                            ledgerPrompt, foreshadowing, styleStat, globalNo, diagnostic, writerContext);
                    if (patch.outcome().applied()) {
                        String patched = patch.outcome().content();
                        log.info("第 {} 章定向补丁已采纳", globalNo);
                        return new ReviseResult(
                                RevisionDecisionEntity.builder()
                                        .attempted(true)
                                        .accepted(true)
                                        .reason("定向补丁通过采纳闸门，计划完整性待复核")
                                        .reviewCandidates(missingEvents(originalContent, patched, item))
                                        .build(),
                                patchedEntity(patched));
                    }
                    if (patch.rewriteReason() != null) {
                        rewriteReason = "确需调整整章结构的依据：" + patch.rewriteReason();
                        log.info("第 {} 章补丁明确提出结构性调整，发起整章重写", globalNo);
                        break;
                    }
                    diagnostic = patch.outcome().failureReason();
                    log.info("第 {} 章第 {}/2 次定向补丁未采纳，原因：{}", globalNo, attempt, diagnostic);
                    if (attempt == 2) {
                        return new ReviseResult(buildRejected("两次定向补丁未采纳，保留原稿：" + diagnostic), null);
                    }
                }
            }

            // maxTokens/temperature 不再硬编码，由 scene-models 的 revise 场景配置驱动
            if (writerContext != null) writerContext.checkCancellation();
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .systemPrompt(writerContext == null ? null : writerContext.writerSystemPrompt())
                    .userPrompt(buildPrompt(item, originalContent, blockingIssues, ledgerPrompt,
                            foreshadowing, styleStat, globalNo, rewriteReason, false, writerContext))
                    .label("revise-第" + globalNo + "章")
                    .scene(ModelScene.CHAPTER_REVISE)
                    .build());
            if (writerContext != null) writerContext.checkCancellation();
            rejectIfIncomplete(raw);

            ChapterContentEntity revised = tryConvert(raw);
            if (revised != null) {
                // 模型输出标题常自带"第N章"前缀，与章节号重复展示，入口归一
                revised.setTitle(cn.novel.yonren.types.utils.ChapterTitleNormalizer.normalize(revised.getTitle()));
            }
            if (revised == null) {
                revised = JsonParseFallback.parse(raw, this::tryConvert);
                if (revised != null) {
                    log.info("章节修订经四级解析降级修复后解析成功，chapterNo: {}", globalNo);
                }
            }

            if (revised == null || StringUtils.isBlank(revised.getContent())) {
                return new ReviseResult(buildRejected("修订稿无法解析或 content 为空"), null);
            }

            String gateReason = runGates(originalContent, revised, item, styleStat, globalNo, blockingIssues);
            if (gateReason != null) {
                log.warn("章节修订未通过采纳闸门，chapterNo: {}，原因：{}", globalNo, gateReason);
                return new ReviseResult(buildRejected(gateReason), null);
            }

            log.info("章节修订已采纳，chapterNo: {}", globalNo);
            return new ReviseResult(
                    RevisionDecisionEntity.builder()
                            .attempted(true)
                            .accepted(true)
                            .reason("修订稿通过采纳闸门，计划完整性待复核")
                            .reviewCandidates(missingEvents(originalContent, revised.getContent(), item))
                            .build(),
                    revised);
        } catch (IncompleteRevision refusal) {
            return new ReviseResult(buildPlanAdjustmentRejected(refusal.getMessage()), null);
        } catch (Exception e) {
            LlmLocalRejectionException.rethrowIfPresent(e);
            if (writerContext != null) writerContext.checkCancellation();
            if (e instanceof java.util.concurrent.CancellationException cancellation) throw cancellation;
            log.warn("章节修订异常，chapterNo: {}", globalNo, e);
            return new ReviseResult(buildRejected("修订异常：" + e.getMessage()), null);
        }
    }

    private ReviseResult collapseRewrite(StoryVO storyVO, ChapterPlanItemEntity item, String originalContent,
                                          List<ChapterIssueEntity> blockingIssues, String ledgerPrompt, String foreshadowing,
                                          StyleStatEntity styleStat, int globalNo, String previousRejectionReason,
                                          RewriteContext writerContext) {
        if (StringUtils.isBlank(writerContext.writerUserPrompt())) {
            return new ReviseResult(buildRejected("坍缩重写缺少原始写作上下文"), null);
        }
        writerContext.checkCancellation();
        String prompt = writerContext.writerUserPrompt()
                + "\n\n【坍缩稿完整重写】此前正文未形成完整场景，不能用局部补丁补凑。按上面的原始计划、事实和人物卡重新写成完整正文。"
                + "不规定对白句数、句长或轮次，保留必要心理、环境与解释，不重复确认已经成立的条件。"
                + "不要从残稿反推历史；能力、物品、地点、承诺和保密状态必须与原始上下文一致。"
                + "保留作者原有风格、视角和语气，事实修正优先，不把不成立的计划圆成新能力。"
                + "\n" + WritingContractPolicy.renderEditorialScope()
                + renderPlanWhenMissing(item, globalNo, writerContext.writerUserPrompt())
                + "\n" + COMPLETION_RULE
                + "\n待修问题：" + JSON.toJSONString(blockingIssues)
                + (StringUtils.isBlank(previousRejectionReason) ? "" : "\n上一轮未采纳原因：" + previousRejectionReason)
                + "\n只输出JSON：完成时{\"completed\":true,\"chapterNo\":" + globalNo + ",\"title\":\"章节标题\",\"content\":\"完整正文\"}"
                + "；不能完成时{\"completed\":false,\"reason\":\"冲突及依据，计划需调整\"}";
        try {
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .systemPrompt(writerContext.writerSystemPrompt()).userPrompt(prompt)
                    .label("collapse-rewrite-第" + globalNo + "章").scene(ModelScene.CHAPTER_REVISE).build());
            writerContext.checkCancellation();
            rejectIfIncomplete(raw);
            ChapterContentEntity revised = JsonParseFallback.parse(raw, this::tryConvert);
            if (revised == null || StringUtils.isBlank(revised.getContent())) {
                return new ReviseResult(buildRejected("坍缩重写未产出可解析的正文"), null);
            }
            revised.setChapterNo(globalNo);
            revised.setTitle(cn.novel.yonren.types.utils.ChapterTitleNormalizer.normalize(revised.getTitle()));
            if (ChapterLengthPolicy.isCollapsed(revised.getContent())) {
                return new ReviseResult(buildRejected("坍缩重写仍低于 " + ChapterLengthPolicy.COLLAPSE_FLOOR_CHARACTERS + " 有效字"), null);
            }
            var events = item.getKeyEvents();
            if (events != null) {
                var missing = PatchIntegrityPolicy.lostEvents(String.join("\n", events), revised.getContent(), events);
                if (!missing.isEmpty()) return new ReviseResult(buildRejected("坍缩重写缺失计划事件：" + String.join("；", missing)), null);
            }
            return new ReviseResult(RevisionDecisionEntity.builder().attempted(true).accepted(true)
                    .reviewCandidates(missingEvents(originalContent, revised.getContent(), item))
                    .reason("完整重写通过篇幅与计划预检，仍需修订复核").build(), revised);
        } catch (IncompleteRevision refusal) {
            return new ReviseResult(buildPlanAdjustmentRejected(refusal.getMessage()), null);
        } catch (RuntimeException failure) {
            LlmLocalRejectionException.rethrowIfPresent(failure);
            writerContext.checkCancellation();
            if (isAuthorizationFailure(failure)) throw failure;
            return new ReviseResult(buildRejected("坍缩重写调用或处理失败：" + failure.getClass().getSimpleName()), null);
        }
    }

    private static boolean isAuthorizationFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.web.client.RestClientResponseException http
                    && (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 403)) return true;
            String text = java.util.Objects.toString(cause.getMessage(), "").toLowerCase(java.util.Locale.ROOT);
            if (text.contains("unauthorized") || text.contains("invalid_api_key") || text.contains("认证失败")
                    || text.matches("(?s).*\\b(?:401|403)\\b.*")) return true;
        }
        return false;
    }

    // ==================== 定向补丁 ====================

    private boolean patchEnabled() {
        return storyProperties != null
                && storyProperties.getRevise() != null
                && storyProperties.getRevise().isPatchEnabled();
    }

    /**
     * 尝试用定向补丁修复：模型只产出「锚点 → 替换文本」，本方法机械套用后再走**同一套**采纳闸门。
     *
     * 格式、锚点与采纳闸门的失败返回诊断，供一次定向重试。
     * 完整响应明确给出结构性原因时，可以进入整章重写。
     */
    private PatchAttempt tryPatch(StoryVO storyVO,
                                  ChapterPlanItemEntity item,
                                  String originalContent,
                                  List<ChapterIssueEntity> blockingIssues,
                                  String ledgerPrompt,
                                  String foreshadowing,
                                  StyleStatEntity styleStat,
                                  int globalNo,
                                  String previousRejectionReason,
                                  RewriteContext writerContext) {
        String raw;
        if (writerContext != null) writerContext.checkCancellation();
        try {
            raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .systemPrompt(writerContext == null ? null : writerContext.writerSystemPrompt())
                    .userPrompt(buildPrompt(item, originalContent, blockingIssues, ledgerPrompt,
                            foreshadowing, styleStat, globalNo, previousRejectionReason, true, writerContext))
                    .label("patch-第" + globalNo + "章")
                    .scene(ModelScene.CHAPTER_REVISE)
                    .build());
        } catch (Exception e) {
            LlmLocalRejectionException.rethrowIfPresent(e);
            if (writerContext != null) writerContext.checkCancellation();
            if (e instanceof java.util.concurrent.CancellationException cancellation) throw cancellation;
            if (isAuthorizationFailure(e)) throw e;
            return patchFailure("补丁调用异常，需重新输出补丁");
        }
        if (writerContext != null) writerContext.checkCancellation();
        // 位于解析降级之外：completed=false 不可被当成坏补丁再触发整章重写。
        rejectIfIncomplete(raw);

        String json = StringUtils.trimToEmpty(raw).replaceFirst("(?is)^```(?:json)?\\s*(.*?)\\s*```$", "$1");
        JsonNode root;
        try {
            root = PATCH_MAPPER.readTree(json);
        } catch (JsonProcessingException invalid) {
            return patchFailure("补丁 JSON 无法解析，请重新返回一个完整的 JSON 对象");
        }
        JsonNode entries = root == null ? null : root.get("patches");
        if (entries == null || !entries.isArray()) {
            return patchFailure("补丁缺少 patches 数组");
        }
        if (entries.isEmpty()) {
            String rewriteReason = StringUtils.trimToNull(root.path("rewriteReason").textValue());
            return rewriteReason == null
                    ? patchFailure("空补丁未说明需要整章调整的原因，请重新定位原文")
                    : new PatchAttempt(new PatchOutcome(null, null), rewriteReason);
        }
        List<Patch> patches = new ArrayList<>(entries.size());
        for (JsonNode entry : entries) {
            if (!entry.path("anchor").isTextual() || !entry.path("replacement").isTextual()) {
                return patchFailure("补丁须提供完整的 anchor 和 replacement，删除请显式使用空字符串");
            }
            Patch patch = new Patch();
            patch.setAnchor(entry.path("anchor").textValue());
            patch.setReplacement(entry.path("replacement").textValue());
            patches.add(patch);
        }
        PatchOutcome outcome = applyPatchesKeepingParagraphs(originalContent, patches);
        if (!outcome.applied()) {
            return new PatchAttempt(outcome, null);
        }
        if (outcome.content().equals(originalContent)) {
            // 套用成功但等于空操作。若放过，就会把"什么也没修"当成"修完了"，还白烧一次复审
            return patchFailure("补丁未改变任何内容");
        }
        String gateReason = runGates(originalContent, patchedEntity(outcome.content()), item, styleStat, globalNo, blockingIssues);
        if (gateReason != null) {
            return patchFailure("补丁未过采纳闸门：" + gateReason);
        }
        return new PatchAttempt(outcome, null);
    }

    private static PatchAttempt patchFailure(String reason) {
        return new PatchAttempt(new PatchOutcome(null, reason), null);
    }

    private record PatchAttempt(PatchOutcome outcome, String rewriteReason) { }

    public static PatchOutcome applyPatches(String original, List<Patch> patches) {
        return applyPatches(original, patches, false);
    }

    static PatchOutcome applyPatchesKeepingParagraphs(String original, List<Patch> patches) {
        return applyPatches(original, patches, true);
    }

    private static PatchOutcome applyPatches(String original, List<Patch> patches, boolean protectParagraphs) {
        List<LocatedPatch> spans = new ArrayList<>(patches.size());
        for (Patch patch : patches) {
            String anchor = patch == null ? null : patch.getAnchor();
            if (StringUtils.isBlank(anchor)) {
                return new PatchOutcome(null, "补丁锚点为空");
            }
            int start = original.indexOf(anchor);
            if (start < 0) {
                return new PatchOutcome(null, "锚点在原稿中不存在（模型未逐字复制原文）");
            }
            if (original.indexOf(anchor, start + 1) >= 0) {
                return new PatchOutcome(null, "锚点在原稿中出现多次，无法确定该改哪一处");
            }
            spans.add(new LocatedPatch(start, start + anchor.length(), StringUtils.defaultString(patch.getReplacement())));
        }

        spans.sort(Comparator.comparingInt(LocatedPatch::start));
        int previousEnd = 0;
        for (LocatedPatch span : spans) {
            if (span.start() < previousEnd) {
                return new PatchOutcome(null, "补丁锚点互相重叠，无法安全套用");
            }
            previousEnd = span.end();
        }
        if (!protectParagraphs) {
            return new PatchOutcome(renderPatches(original, 0, original.length(), spans), null);
        }
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        boolean skipped = false;
        for (int first = 0; first < spans.size();) {
            LocatedPatch initial = spans.get(first);
            int start = original.lastIndexOf('\n', initial.start() - 1) + 1;
            int end = paragraphEnd(original, initial.end());
            int next = first + 1;
            while (next < spans.size() && spans.get(next).start() <= end) {
                end = paragraphEnd(original, spans.get(next++).end());
            }
            String before = original.substring(start, end);
            String after = renderPatches(original, start, end, spans.subList(first, next));
            result.append(original, cursor, start);
            if (PatchIntegrityPolicy.introducesDanglingParagraph(before, after)) {
                result.append(before);
                skipped = true;
            } else {
                result.append(after);
            }
            cursor = end;
            first = next;
        }
        String patched = result.append(original, cursor, original.length()).toString();
        return skipped && original.equals(patched)
                ? new PatchOutcome(null, "补丁删句后留下残段")
                : new PatchOutcome(patched, null);
    }

    private static int paragraphEnd(String original, int from) {
        int newline = original.indexOf('\n', from);
        return newline < 0 ? original.length() : newline;
    }

    private static String renderPatches(String original, int start, int end, List<LocatedPatch> spans) {
        StringBuilder text = new StringBuilder();
        int cursor = start;
        for (LocatedPatch span : spans) {
            text.append(original, cursor, span.start()).append(span.replacement());
            cursor = span.end();
        }
        return text.append(original, cursor, end).toString();
    }

    private record LocatedPatch(int start, int end, String replacement) { }

    /** 补丁路径的返回体：**标题留空**表示"标题未变"，由 QualityGate 保留原标题（补丁不该动标题） */
    private static ChapterContentEntity patchedEntity(String content) {
        ChapterContentEntity entity = new ChapterContentEntity();
        entity.setContent(content);
        return entity;
    }

    @Data
    public static class Patch {
        /** 原稿中逐字存在且唯一的锚点片段 */
        private String anchor;
        /** 替换文本；空字符串表示删除该锚点 */
        private String replacement;
    }

    /**
     * 补丁套用结果：content 非空表示成功；失败时提供可用于重试的诊断。
     * 包外可见以便段落信息增量审校复用（见 {@link #applyPatches}）。
     */
    public record PatchOutcome(String content, String failureReason) {
        public boolean applied() {
            return content != null;
        }
    }

    private ChapterContentEntity tryConvert(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 组装修订 prompt：两种形态只差**任务头**与**输出 schema**，中段的业务上下文完全共用
     * （章节计划、关键事件红线、账本、伏笔、待修问题、机械违规点），避免两处各写一份而漂移
     */
    private String renderPlanWhenMissing(ChapterPlanItemEntity item, int globalNo, String writerPrompt) {
        if (StringUtils.contains(writerPrompt, "\n第 " + globalNo + " 章计划：")) {
            return "";
        }
        String scenes = ScenePlanPolicy.renderForWriting(item);
        StringBuilder plan = new StringBuilder("\n\n【本章计划】")
                .append("\n标题：").append(nullToBlank(item.getTitle()))
                .append("\n目标：").append(nullToBlank(item.getGoal()));
        if (scenes.isEmpty()) {
            plan.append("\n关键事件：").append(joinList(item.getKeyEvents()));
            scenes = ScenePlanPolicy.render(item.getScenes());
        }
        return plan.append(ChapterContract.renderEndingForWriting(item.getEndingHook(), null))
                .append("\n章节类型：").append(item.getChapterType() == null ? "normal" : item.getChapterType().getCode())
                .append(scenes)
                .append(ChapterContract.renderKnowledgeProgress(item))
                .append("\n").append(WritingContractPolicy.renderIntentGuidance())
                .toString();
    }

    private String buildPrompt(ChapterPlanItemEntity item,
                               String originalContent,
                               List<ChapterIssueEntity> blockingIssues,
                               String ledgerPrompt,
                               String foreshadowing,
                               StyleStatEntity styleStat,
                               int globalNo,
                               String previousRejectionReason,
                               boolean patchMode,
                               RewriteContext writerContext) {
        StringBuilder sb = new StringBuilder();
        boolean hasWriterContext = writerContext != null && StringUtils.isNotBlank(writerContext.writerUserPrompt());
        if (hasWriterContext) {
            sb.append("【冻结的原始写作上下文】仅作为作者范围与事实基线；本轮交付以下方修订任务和完成状态规则为准。\n")
                    .append(writerContext.writerUserPrompt()).append("\n\n");
        }
        sb.append(patchMode ? TASK_HEADER_PATCH : TASK_HEADER_REWRITE)
                .append("\n").append(WritingContractPolicy.renderEditorialScope())
                .append("\n保留作者原有风格、视角和语气，不把未命中的心理、讲述、等待或环境段落统一改成动作与口语。")
                .append("\n【一致性裁决规则】可信冻结事实与作者明确设定优先；账本、摘要和 issue 是待核对的依据，不天然绝对正确。")
                .append("事实修正优先：位置、持有物、修为与人物状态的变化须有已知事实或当前场景中可成立的因果支持；")
                .append("不编造取回过程、异地转移、能力升级或历史承诺来迁就账本及计划。")
                .append("若证据不足或冲突无法在原范围内消解，按完成状态规则拒绝，不改写历史稿。")
                .append("\n").append(COMPLETION_RULE);
        // 上一轮反馈：采纳闸门的具体拒绝原因（如疲劳词超频/缺关键事件）回传给次轮修订，避免盲改
        if (StringUtils.isNotBlank(previousRejectionReason)) {
            sb.append("\n【上一轮修订反馈】上一轮修订稿因以下原因被采纳闸门拒绝，本次必须避免引入同样问题：\n")
                    .append(previousRejectionReason);
        }
        // 风格统计是告知，不把无关微动作或疲劳词变成本轮全章清洗任务。
        if (styleStat != null) {
            sb.append("\n【风格观测边界】风格统计仅供已定位修订处参考，不据此扩大修订范围；")
                    .append("未列为本轮 BLOCKING 的表达保持原样，不为了换词而补写环境、心理或比喻。");
        }
        sb.append(renderPlanWhenMissing(item, globalNo, hasWriterContext ? writerContext.writerUserPrompt() : null));
        // 守住真实场景完整性，但不把词面预检说成事实裁决，不能为计划伪造能力或历史。
        if (item.getKeyEvents() != null && !item.getKeyEvents().isEmpty()) {
            sb.append("\n\n【关键事件红线】可信事实成立的关键事件与场景结果必须保留：")
                    .append("\n1. 严禁删除、合并或把关键事件降级为一句背景概述，以回避真实剧情交付；")
                    .append("\n2. 事实修正优先；事件内部表述可有依据地修正，但若事件本身与可信冻结事实冲突，不能强写发生，应返回 completed=false 并说明计划需调整；")
                    .append("\n3. 机械采纳预检兼容硬伤修正造成的最多 1 条词面覆盖变化，不等于允许删除真实事件；")
                    .append("有依据的修正应在 reason 中说明，真实未完成或无法证明成立时返回未完成/拒绝，不采纳其稿件。其余场景、人物边界与结果不变。");
        }

        if (StringUtils.isNotBlank(ledgerPrompt)) {
            sb.append("\n\n【当前账本】\n").append(ledgerPrompt);
        }
        if (StringUtils.isNotBlank(foreshadowing)) {
            sb.append("\n\n【待回收伏笔】\n").append(foreshadowing);
        }

        sb.append("\n\n【待修复的 BLOCKING 问题】\n");
        int idx = 1;
        for (ChapterIssueEntity issue : blockingIssues) {
            if (writerContext != null && writerContext.scope() != RewriteContext.Scope.LEGACY
                    && ("foreshadow".equalsIgnoreCase(issue.getDimension()) || StringUtils.isNotBlank(issue.getPlanEvidence()))) {
                sb.append(idx++).append(". 核对下列正文是否让人物提前知道或确认了本段材料尚未给出证据的事。")
                        .append("保留已有线索与人物当时可以作出的判断。\n");
                String evidence = StringUtils.defaultString(issue.getEvidence());
                if (StringUtils.isNotBlank(evidence) && originalContent.contains(evidence)) {
                    sb.append("   原稿位置：").append(evidence).append("\n");
                } else {
                    // 机械禁泄证据为已在稿中出现的「词×次数」，只回送实际出现的位置。
                    for (String hit : evidence.split("、")) {
                        String term = hit.replaceFirst("×\\d+$", "");
                        if (StringUtils.isNotBlank(term) && originalContent.contains(term)) {
                            sb.append("   原稿位置：").append(term).append("\n");
                        }
                    }
                }
                continue;
            }
            sb.append(idx++).append(". [").append(issue.getDimension()).append("] ")
                    .append(issue.getDescription())
                    .append("\n   evidence：").append(nullToBlank(issue.getEvidence()))
                    .append("\n   suggestion：").append(nullToBlank(issue.getSuggestion()))
                    .append("\n");
            if (StringUtils.isNotBlank(issue.getFactEvidence())) {
                sb.append("   既定事实原句：").append(issue.getFactEvidence()).append("\n");
            }
            if (StringUtils.isNotBlank(issue.getPlanEvidence())) {
                sb.append("   本章揭晓上限原句：").append(issue.getPlanEvidence()).append("\n");
                sb.append("   保留已有线索和人物判断，改回本章允许的知情程度。\n");
            }
        }
        // aesthetic 也可能来自语义审校；有 evidence 不等于程序已确认每一词都违规。
        List<String> aestheticEvidence = blockingIssues.stream()
                .filter(i -> "aesthetic".equalsIgnoreCase(i.getDimension()))
                .map(ChapterIssueEntity::getEvidence)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (!aestheticEvidence.isEmpty()) {
            sb.append("\n【本轮文风问题定位】以下 evidence 只用于核对本轮问题，须结合正文、作者契约与 issue 描述判断：\n");
            for (String evidence : aestheticEvidence) {
                sb.append("- ").append(evidence).append("\n");
            }
            sb.append("只修改 issue 指向且核实成立的局部。先看原句中哪部分带来了新信息，保留它，省去重复解释。")
                    .append("同一修辞换连接词或喻体，停顿换一个数目，仍要按原问题检查看读感是否改善。")
                    .append("删去赘句即可读通时，补丁可以只做删除。作者有意的辨析、准确意象、时限与有后续作用的盘点须保留。");
        }

        sb.append("\n\n【原稿全文】\n").append(originalContent);
        if (patchMode) {
            sb.append("\n\n请严格按照以下 JSON 格式输出补丁（只输出这一个 JSON，不要附带解释文字）：")
                    .append("\n{\"completed\":true,\"reason\":\"修正依据\",\"patches\":[{\"anchor\":\"原稿中逐字存在且唯一的片段\",\"replacement\":\"替换后的文本\"}]}")
                    .append("\n确需重排整章结构时输出 {\"patches\":[],\"rewriteReason\":\"需要整体调整的场景、顺序与原因\"}；")
                    .append("计划不成立或证据不足时，输出 {\"completed\":false,\"reason\":\"冲突及依据，计划需调整\"}。")
                    .append("\n再次提醒：anchor 会被程序逐字索引，改一个字就套不上，整份补丁会被直接丢弃。");
        } else {
            sb.append("\n\n请严格按照以下 JSON 格式输出整章：")
                    .append("\n{\"completed\":true,\"reason\":\"修正依据\",\"chapterNo\":").append(globalNo)
                    .append(",\"title\":\"章节标题\",\"content\":\"章节正文\"}")
                    .append("\n不能完成时只输出 {\"completed\":false,\"reason\":\"冲突及依据，计划需调整\"}，不输出伪造的完成稿。");
        }

        return sb.toString();
    }

    private String runGates(String originalContent,
                            ChapterContentEntity revised,
                            ChapterPlanItemEntity item,
                            StyleStatEntity styleStat,
                            int globalNo,
                            List<ChapterIssueEntity> blockingIssues) {
        // 1. 字数闸（硬）：修订稿腰斩 = 内容缺失，不是风格问题
        if (revised.getContent().length() < originalContent.length() * MIN_LENGTH_RATIO) {
            return "修订稿字数不足原稿 " + (int) (MIN_LENGTH_RATIO * 100) + "%";
        }
        String styleRegression = NarrativeStylePolicy.regressionReason(originalContent, revised.getContent());
        if (styleRegression != null) {
            if (blockingIssues.stream().allMatch(issue -> "aesthetic".equalsIgnoreCase(issue.getDimension()))) {
                return styleRegression;
            }
            log.info("第 {} 章修订含剧情或事实问题，交由复审判断，文风观测：{}", globalNo, styleRegression);
        }

        // 2. 风格账：只告知、不拒绝——BLOCKING 修复优先于文风瑕疵
        if (styleStat != null) {
            StyleStatEntity temp = deepCopy(styleStat);
            if (temp == null) {
                log.warn("第 {} 章修订风格账深拷贝失败，跳过风格告知（不再因此拒稿）", globalNo);
            } else {
                styleStatService.merge(temp, revised.getContent());
                if (temp.getRepeatedSentences().size() > styleStat.getRepeatedSentences().size()) {
                    log.warn("第 {} 章修订稿引入新的跨章重复句，已放行（BLOCKING 修复优先；风格由后续章节警示治理）",
                            globalNo);
                }
                for (Map.Entry<String, Integer> entry : temp.getFatigueWords().entrySet()) {
                    Integer before = styleStat.getFatigueWords().get(entry.getKey());
                    if (entry.getValue() >= StyleStatService.FATIGUE_THRESHOLD
                            && (before == null || before < StyleStatService.FATIGUE_THRESHOLD)) {
                        log.warn("第 {} 章修订稿使疲劳词 '{}' 达到超频阈值，已放行（同上）",
                                globalNo, entry.getKey());
                    }
                }
            }
        }
        List<String> keyEvents = item.getKeyEvents();
        if (keyEvents != null && !keyEvents.isEmpty()) {
            // 逐条判定（含"原稿未覆盖的不强求"这条放行规则）统一走 PatchIntegrityPolicy，
            // 与段落密度挤水路径共用同一份实现——两处各写一份正是漂移的来源
            List<String> missing = PatchIntegrityPolicy.lostEvents(originalContent, revised.getContent(), keyEvents);
            if (missing.size() > REVISE_ALLOWED_EVENT_LOSS) {
                return "修订稿缺失关键事件：" + String.join("；", missing);
            }
            if (!missing.isEmpty()) {
                log.info("第 {} 章修订有 {} 条关键事件词面覆盖变化，按既有硬伤修正容忍线交后续语义复核；"
                                + "不代表真实事件允许删除：{}",
                        globalNo, missing.size(), StringUtils.abbreviate(missing.get(0), 60));
            }
        }

        return null;
    }

    /** 仅本服务消费的可选状态，不扩展持久化正文或公共修订结果 schema。 */
    @Data
    private static class CompletionStatus {
        private Boolean completed;
        // 状态先行：reason 即使被模型写成结构化值，也不能导致 completed=false 解析失败后放行正文。
        private Object reason;
    }

    private static class IncompleteRevision extends RuntimeException {
        private IncompleteRevision(String reason) {
            super(reason);
        }
    }

    private static List<String> missingEvents(String originalContent, String revisedContent,
                                               ChapterPlanItemEntity item) {
        List<String> events = item == null ? List.of() : item.getKeyEvents();
        if (events == null || events.isEmpty()) return List.of();
        return PatchIntegrityPolicy.lostEvents(originalContent, revisedContent, events);
    }

    private static void rejectIfIncomplete(String raw) {
        CompletionStatus status = JsonParseFallback.parse(raw, STATUS_CONVERTER::convert);
        if (status != null && Boolean.FALSE.equals(status.getCompleted())) {
            throw new IncompleteRevision("修订未完成/拒绝：" + StringUtils.defaultIfBlank(
                    java.util.Objects.toString(status.getReason(), null),
                    "未提供冲突依据，需核实事实与计划；计划需调整后再处理"));
        }
    }

    private StyleStatEntity deepCopy(StyleStatEntity stat) {
        try {
            return JSON.parseObject(JSON.toJSONString(stat), StyleStatEntity.class);
        } catch (Exception e) {
            return null;
        }
    }

    private RevisionDecisionEntity buildPlanAdjustmentRejected(String reason) {
        return RevisionDecisionEntity.builder().attempted(true).accepted(false)
                .planAdjustmentRequired(true).reason(reason).build();
    }

    private RevisionDecisionEntity buildRejected(String reason) {
        return RevisionDecisionEntity.builder()
                .attempted(true)
                .accepted(false)
                .reason(reason)
                .build();
    }

    private String joinList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join("、", list);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * 修订结果容器：同时携带决策与修订稿，未采纳时修订稿为 null。
     */
    public record ReviseResult(RevisionDecisionEntity decision, ChapterContentEntity revisedContent) {
    }

}
