package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.plan.CausalVerdict;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException;
import cn.novel.yonren.domain.novel.service.armory.plan.ScenePlanPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.DeliveryReviewPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.EvidenceMatch;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

/**
 * 章节审校服务：对单章正文做七维度审校，输出结构化 issue 列表。
 * 直连 LlmGateway 小参数调用，刻意不走 PromptBuilder/动态选择器——
 * 创作规则（反 AI 味、钩子技巧）对审校模型有害无益。
 * 审校是质量门：OFF 解析/修复均失败时抛 AppException；启用交付核验则返回 UNKNOWN 与可保全的七维结论，
 * 整体首审不可用显式标记 auditUnavailable（绝不把失败当成零问题通过）。
 * BLOCKING 仅限四类硬伤且 evidence 经正文子串校验，未命中降级 MINOR——
 * 防 severity 通胀稀释修订与质量债治理链路（程度问题混入 BLOCKING 的典型症状：
 * 修订反复不达标、债务同维度每章复发且永不核销）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterAuditService {

    /** 修订后计划事件完整性的复核维度；与旧 BLOCKING 分开，避免位置错配和误归因。 */
    public static final String PLAN_INTEGRITY_DIMENSION = "plan-integrity";

    private static final BeanOutputConverter<AuditResultEntity> CONVERTER =
            new BeanOutputConverter<>(AuditResultEntity.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    /** 交付核验是授权协议：禁止重复键、尾随 JSON、类型强转及修复截断后补出的 PASS。 */
    private static final JsonMapper DELIVERY_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    // OFF 不在原协议里暗中要求新清单；本地 report 与 unavailable 也不属于模型输出。
    private record AuditIssuesOutput(List<ChapterIssueEntity> issues) { }
    private static final String LEGACY_AUDIT_FORMAT = new BeanOutputConverter<>(AuditIssuesOutput.class).getFormat();

    private final LlmGateway llmGateway;

    /**
     * 对单章正文进行审校。
     *
     * @param storyVO      模型配置
     * @param item         本章计划
     * @param content      本章正文
     * @param ledgerPrompt 三账本渲染文本（可为 null）
     * @param foreshadowing 伏笔账文本（可为 null）
     * @param styleStat    当前风格统计（可为 null）
     * @param globalNo     全局章节号（仅用于日志）
     * @return 审校结果；解析/修复均失败时抛 AppException（质量门硬失败）
     */
    public AuditResultEntity audit(StoryVO storyVO,
                                   ChapterPlanItemEntity item,
                                   String content,
                                   String ledgerPrompt,
                                   String foreshadowing,
                                   StyleStatEntity styleStat,
                                   int globalNo) {
        return audit(storyVO, item, content, ledgerPrompt, foreshadowing, styleStat, globalNo, null);
    }

    /**
     * 带机械预警 guard 的重载：guard 为质量门机械层产出的加审提示块
     * （本章禁泄清单——LLM 判变相泄露；计划覆盖预警——低覆盖事件的加审线索），可为 null
     */
    public AuditResultEntity audit(StoryVO storyVO,
                                   ChapterPlanItemEntity item,
                                   String content,
                                   String ledgerPrompt,
                                   String foreshadowing,
                                   StyleStatEntity styleStat,
                                   int globalNo,
                                   String guard) {
        return audit(storyVO, item, content, ledgerPrompt, foreshadowing, styleStat, globalNo, guard, null);
    }

    /** 同一次七维首审附带固定计划交付清单；OFF 仍保持原解析与 AppException 语义。 */
    public AuditResultEntity audit(StoryVO storyVO,
                                   ChapterPlanItemEntity item,
                                   String content,
                                   String ledgerPrompt,
                                   String foreshadowing,
                                   StyleStatEntity styleStat,
                                   int globalNo,
                                   String guard,
                                   DeliveryReviewPolicy.Request deliveryRequest) {
        if (deliveryRequest != null) {
            return auditWithDelivery(storyVO, item, content, ledgerPrompt, foreshadowing, styleStat,
                    globalNo, guard, deliveryRequest);
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            checkCancellation(null, null);
            try {
                // maxTokens/temperature 不再硬编码，由 scene-models 的 audit 场景配置驱动
                String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                        .userPrompt(buildPrompt(item, content, ledgerPrompt, foreshadowing, styleStat, globalNo, guard))
                        .label("audit-第" + globalNo + "章")
                        .scene(ModelScene.CHAPTER_AUDIT)
                        .build());
                checkCancellation(null, null);

                AuditResultEntity result = tryConvert(raw);
                if (result == null) {
                    result = JsonParseFallback.parse(raw, this::tryConvert);
                    if (result != null) {
                        log.info("章节审校经四级解析降级修复后解析成功，chapterNo: {}", globalNo);
                    }
                }
                if (result != null) {
                    int issueCount = result.getIssues() == null ? 0 : result.getIssues().size();
                    log.info("章节审校完成，chapterNo: {}, issues: {}", globalNo, issueCount);
                    verifyEvidence(result, content, globalNo);
                    return result;
                }
                log.warn("章节审校第 {}/2 次输出无法解析，chapterNo: {}", attempt, globalNo);
            } catch (Exception e) {
                checkCancellation(null, null);
                rethrowControlSignal(e);
                log.warn("章节审校第 {}/2 次尝试异常，chapterNo: {}", attempt, globalNo, e);
            }
        }

        log.warn("章节审校彻底失败，chapterNo: {}", globalNo);
        throw new AppException(ResponseCode.UN_ERROR.getCode(),
                "第 " + globalNo + " 章审校彻底失败（含修复后重解与一次重试），质量门无法给出结论，本批终止；"
                        + "此前章节已通过逐章检查点落盘，可在请求中携带 resumeStoryDir 续写补齐后续章节");
    }

    private AuditResultEntity auditWithDelivery(StoryVO storyVO, ChapterPlanItemEntity item, String content,
                                                String ledgerPrompt, String foreshadowing, StyleStatEntity styleStat,
                                                int globalNo, String guard, DeliveryReviewPolicy.Request request) {
        checkCancellation(request, null);
        if (!requestMatches(request, item, content, globalNo)) {
            AuditResultEntity unavailable = new AuditResultEntity();
            unavailable.setAuditUnavailable(true);
            unavailable.setDeliveryReview(DeliveryReviewPolicy.unavailable(request,
                    "DELIVERY_VERSION_MISMATCH: 首审正文或计划与冻结核验请求不一致"));
            return unavailable;
        }
        AuditResultEntity latestAudit = null;
        DeliveryReview review = DeliveryReviewPolicy.unavailable(request, "DELIVERY_UNAVAILABLE: 首审尚未完成");
        String diagnostic = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            // 取消/预算检查放在宽 catch 之外：一次性的取消回调也不能被降级吞掉。
            checkCancellation(request, null);
            String raw;
            try {
                raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                        .userPrompt(withRetryDiagnostic(buildPrompt(item, content, ledgerPrompt, foreshadowing,
                                styleStat, globalNo, guard, request), diagnostic))
                        .label("audit-第" + globalNo + "章")
                        .scene(ModelScene.CHAPTER_AUDIT)
                        .build());
            } catch (Exception failure) {
                checkCancellation(request, null);
                rethrowControlSignal(failure);
                diagnostic = failureDiagnostic("DELIVERY_AUDIT_UNAVAILABLE", failure);
                review = DeliveryReviewPolicy.unavailable(request, diagnostic);
                log.warn("交付首审第 {}/2 次调用异常，chapterNo: {}", attempt, globalNo, failure);
                continue;
            }
            checkCancellation(request, null);
            try {
                JsonNode root = readStrictObject(raw);
                // 只有完整 JSON 内显式且有效的 issues 才是可保全的首审结果；缺字段不能默认 []。
                AuditResultEntity parsed = new AuditResultEntity(readIssues(root, "issues"));
                verifyEvidence(parsed, content, globalNo);
                latestAudit = parsed;
                List<CausalVerdict> checks = readDeliveryChecks(root);
                parsed.setDeliveryChecks(checks);
                review = DeliveryReviewPolicy.consume(request, checks);
                diagnostic = reviewDiagnostic(review);
            } catch (Exception invalid) {
                checkCancellation(request, null);
                rethrowControlSignal(invalid);
                diagnostic = failureDiagnostic("DELIVERY_AUDIT_PROTOCOL", invalid);
                review = DeliveryReviewPolicy.unavailable(request, diagnostic);
            }
            checkCancellation(request, null);
            if (!review.unknown()) {
                latestAudit.setDeliveryReview(review);
                log.info("章节审校及交付核验完成，chapterNo: {}, issues: {}, delivery: {}",
                        globalNo, latestAudit.getIssues().size(), review.status());
                return latestAudit;
            }
            log.warn("交付首审第 {}/2 次未完成，chapterNo: {}，{}", attempt, globalNo, diagnostic);
        }
        checkCancellation(request, null);
        if (latestAudit == null) {
            latestAudit = new AuditResultEntity();
            latestAudit.setAuditUnavailable(true);
        }
        // UNKNOWN 不是空问题，更不是内容缺陷；保全最近有效七维结论，不借 JSON repair 授权通过。
        latestAudit.setDeliveryReview(review);
        return latestAudit;
    }

    private static boolean requestMatches(DeliveryReviewPolicy.Request request, ChapterPlanItemEntity item,
                                          String content, int globalNo) {
        // 稿件标题由调用方冻结，不从计划标题反推；正文按稿件落盘口径比较，不做宽松证据归一化。
        return request.manuscript().chapterNo() == globalNo
                && request.manuscript().content().equals(new ChapterManuscript(globalNo,
                        request.manuscript().title(), content).content())
                && request.contract().planHash().equals(DeliveryReviewPolicy.contract(item).planHash());
    }

    private static JsonNode readStrictObject(String raw) throws JsonProcessingException {
        JsonNode root = DELIVERY_MAPPER.readTree(unwrapFence(raw));
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("核验输出必须是一个完整 JSON 对象");
        }
        return root;
    }

    private static JsonNode requireArray(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("必须显式提供 " + field + " 数组；没有条目也必须输出 []");
        }
        return value;
    }

    private static List<ChapterIssueEntity> readIssues(JsonNode root, String field) throws JsonProcessingException {
        JsonNode values = requireArray(root, field);
        for (JsonNode value : values) {
            if (!value.isObject()) throw new IllegalArgumentException(field + " 不能包含空条目或非对象");
        }
        return new ArrayList<>(Arrays.asList(DELIVERY_MAPPER.treeToValue(values, ChapterIssueEntity[].class)));
    }

    private static List<CausalVerdict> readDeliveryChecks(JsonNode root) throws JsonProcessingException {
        // null 条目交给策略判 UNKNOWN；未知枚举/错误类型由严格读取拒绝，不能变成默认 PASS。
        return Arrays.asList(DELIVERY_MAPPER.treeToValue(requireArray(root, "deliveryChecks"), CausalVerdict[].class));
    }

    private static String unwrapFence(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastLine = value.lastIndexOf('\n');
            if (firstLine >= 0 && lastLine > firstLine) {
                String header = value.substring(0, firstLine).trim();
                if (("```".equals(header) || "```json".equalsIgnoreCase(header))
                        && "```".equals(value.substring(lastLine + 1).trim())) {
                    return value.substring(firstLine + 1, lastLine).trim();
                }
            }
        }
        return value;
    }

    private static String withRetryDiagnostic(String prompt, String diagnostic) {
        if (StringUtils.isBlank(diagnostic)) return prompt;
        return prompt + "\n\n【上次核验未完成：请修正协议或证据】\n" + diagnostic
                + "\n请重新输出全部字段和完整固定清单，不得只补单条。不能编造引文或把拿不准改成 PASS；"
                + "仍无法判断时显式返回 UNKNOWN。";
    }

    private static String reviewDiagnostic(DeliveryReview review) {
        return review.diagnostics().isEmpty() ? null : String.join("\n", review.diagnostics());
    }

    private static String failureDiagnostic(String code, Exception failure) {
        String message = failure instanceof JsonProcessingException json ? json.getOriginalMessage() : failure.getMessage();
        return code + ": " + StringUtils.abbreviate(
                StringUtils.defaultIfBlank(message, failure.getClass().getSimpleName()), 500);
    }

    private static void checkCancellation(DeliveryReviewPolicy.Request request, RewriteContext writerContext) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("章节审校已取消");
        if (request != null) request.checkCancellation();
        if (writerContext != null) writerContext.checkCancellation();
    }

    private static void rethrowControlSignal(Throwable failure) {
        LlmLocalRejectionException.rethrowIfPresent(failure);
        // 网关可能包装控制信号；不能作为可重试的供应商/协议错误处理。
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 64; depth++, cause = cause.getCause()) {
            if (cause instanceof CancellationException cancellation) throw cancellation;
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                CancellationException cancellation = new CancellationException("章节审校已中断");
                cancellation.initCause(cause);
                throw cancellation;
            }
            if (cause.getCause() == cause) break;
        }
    }

    private AuditResultEntity tryConvert(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            return null;
        }
    }

    String buildPrompt(ChapterPlanItemEntity item,
                       String content,
                       String ledgerPrompt,
                       String foreshadowing,
                       StyleStatEntity styleStat,
                       int globalNo,
                       String guard) {
        return buildPrompt(item, content, ledgerPrompt, foreshadowing, styleStat, globalNo, guard, null);
    }

    String buildPrompt(ChapterPlanItemEntity item,
                       String content,
                       String ledgerPrompt,
                       String foreshadowing,
                       StyleStatEntity styleStat,
                       int globalNo,
                       String guard,
                       DeliveryReviewPolicy.Request deliveryRequest) {
        StringBuilder sb = new StringBuilder();
        sb.append(deliveryRequest == null
                        ? "你是小说流水线审校员。阅读本章正文，从以下七个维度发现问题，只输出结构化 issue 列表。"
                        : "你是小说流水线审校员。阅读本章正文，同一次审校返回七维 issues 和固定计划交付 deliveryChecks。")
                .append("\n七维度：consistency（设定一致性）、character（人设一致性）、pacing（节奏平衡）、")
                .append("continuity（叙事连贯）、foreshadow（伏笔健康）、hook（钩子质量）、aesthetic（审美品质）。")
                .append("\n\n").append(cn.novel.yonren.domain.novel.service.armory.prompt.WritingContractPolicy.renderEditorialScope())
                .append("\n- character：配角是否只作信息转述工具、没有可信诉求；判断依据是其立场、信息与行动是否自洽，")
                .append("不是要求每章必须出现合作、被说服或救助局面。")
                .append("\n- character/pacing：人物选择是否有已知动机与后果支撑；代价类型按本书事件实际发生，")
                .append("不要求每章出现多种代价，也不把顺利成功判为缺陷。")
                .append("\n- aesthetic：心理、讲述与互动是否服务当前场景；不规定独白句数或必须以决策收尾。")
                .append("\n- pacing：暴露、揭示和悬念密度是否服务作者计划；不设每章暴露点数量上限。")
                .append("\n- character：主角是否与既定目标一致地行动；允许被动观察、等待与消化，")
                .append("只在人物无理由地失去既有目标或判断时才记问题。");
        // 年龄/能力边界需语义审校：时序锚由 guard 带进来，
        // 无锚时不输出该判据（避免让模型去猜一个不存在的锚）。
        if (StringUtils.isNotBlank(guard) && guard.contains("【时序锚】")) {
            sb.append("\n- character【能力与阶段边界·对照上方时序锚，必查】：动作、语言、书写、专注时长与精细操作")
                    .append("须符合本书已建立的年龄、伤势、训练与特殊能力。")
                    .append("本书明确允许的认知超前、特殊能力与表达媒介必须保留，不用年龄常识抹平；")
                    .append("本书未建立的新能力或新媒介不能临场发明来解题。")
                    .append("他人从行动、物证或交流中得出结论需要足够证据，但不规定必须观察几次，")
                    .append("也不要求固定的“留意→起疑→试探”顺序。")
                    // 时序锚是年龄数值的唯一依据：判罚必须逐字引用锚原文，
                    // 不得推算或使用锚中未记录的年龄，以免把合法行为误判为违规。
                    .append("**年龄数值的唯一依据是上方【时序锚】原文**：判罚涉及年龄时必须逐字引用锚中写明的数字，")
                    .append("严禁推算、改写或使用锚中不存在的年龄值（锚记\"四岁\"时，\"一岁半\"\"三岁\"等一律无效）；")
                    .append("若认为锚本身与近章剧情/账本冲突，按 MINOR 提示核对，不得按臆测的年龄记 BLOCKING；")
                    .append("同理，时间点判定也只以上方时序锚与近章摘要记载的时点为准，禁止臆造未记载的时间。")
                    .append("时间线一致性口径：**不再验收“时间是否推进到位”**——")
                    .append("本章不跨时间、全程同一天，或连续多章同一天，都属正常，**不得**以")
                    .append("“无时间流逝痕迹”“未写过渡”记违规；")
                    .append("只查**时间线是否自相矛盾**：正文若出现与【时序锚】或近章摘要已确立时点")
                    .append("相冲突的表述（如主角已上高中却写“小学三年级”、已入秋却写“盛夏蝉鸣”），")
                    .append("按事实矛盾记 BLOCKING；未声明推进时正文出现大于 1 个月的时间跳跃且无任何过渡，")
                    .append("同样按事实矛盾记 BLOCKING（防的是“无声跳月吞线”，不是“跳月本身”）。");
        }
        // 判据段走变体开关（A/B 校准骨架，docs/enhancement-plan.md A 系列）：
        // B 分支由 A2（审计目录化 + 三态锚样例）填充，当前与 A 等价以支撑骨架自检
        sb.append(AuditPromptVariant.isB() ? criteriaVariantB(deliveryRequest != null)
                : criteriaVariantA(deliveryRequest != null));

        sb.append("\n\n【本章计划】")
                .append("\n标题：").append(nullToBlank(item.getTitle()))
                .append("\n目标：").append(nullToBlank(item.getGoal()))
                .append("\n关键事件：").append(joinList(item.getKeyEvents()))
                .append("\n结尾悬念：").append(nullToBlank(item.getEndingHook()))
                .append("\n章节类型：").append(item.getChapterType() == null ? "normal" : item.getChapterType().getCode());
        if (item.getScenes() != null && !item.getScenes().isEmpty()) {
            sb.append("\n\n").append(
                    cn.novel.yonren.domain.novel.service.armory.plan.ScenePlanPolicy.render(item.getScenes()));
        }

        if (StringUtils.isNotBlank(ledgerPrompt)) {
            sb.append("\n\n【当前账本】\n").append(ledgerPrompt);
        }
        if (StringUtils.isNotBlank(foreshadowing)) {
            sb.append("\n\n【待回收伏笔】\n").append(foreshadowing);
        }

        String styleStatText = renderStyleStat(styleStat);
        if (StringUtils.isNotBlank(styleStatText)) {
            sb.append("\n\n【风格统计警示】\n").append(styleStatText);
        }

        // 机械预警 guard（禁泄清单/计划覆盖预警）：置在正文之前，判定口径见各块自带说明
        if (StringUtils.isNotBlank(guard)) {
            sb.append("\n\n").append(guard);
        }

        if (deliveryRequest != null) {
            sb.append(DeliveryReviewPolicy.prompt(deliveryRequest));
        }
        sb.append("\n\n【本章正文】\n").append(content)
                .append("\n\n请严格按照以下 JSON 格式输出：\n")
                .append(deliveryRequest == null ? LEGACY_AUDIT_FORMAT : CONVERTER.getFormat());
        if (deliveryRequest != null) {
            sb.append("\n只返回一个完整 JSON 对象，issues 与 deliveryChecks 必须分别显式提供数组，不能省略或用 null。")
                    .append("issues=[] 只表示七维没有问题，不能代替固定清单的 PASS；交付问题仅放 deliveryChecks，")
                    .append("不要混入七维 issues，也不受旧七维证据降级或 BLOCKING 数量锚点限制。")
                    .append("deliveryReview、auditUnavailable、核验状态及版本 hash 由本地决定，不要输出或自报。")
                    .append("禁止重复键、尾随说明或截断 JSON。");
        }

        return sb.toString();
    }

    /**
     * 现行判据段（变体 A，默认）：severity 标准 + 输出要求，与历史 prompt 逐字一致
     */
    private String criteriaVariantA(boolean deliveryEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nseverity 判定标准（必须严格遵守，宁可漏报不可错报）：");
        appendSeverityStandards(sb, deliveryEnabled);
        sb.append("\n\n输出要求：");
        appendOutputRequirements(sb, false);
        return sb.toString();
    }

    /**
     * 实验判据段（变体 B，{@code -Daudit.prompt.variant=B}，A2 目录化）：
     * 在 A 的 severity 标准之上，把七维度从"抽象名词"升级为编号判据目录 + 三态锚样例——
     * 把模型从"自由裁量"变成"对号入座"：MINOR 必须标注触发的判据编号，颗粒度才可对齐、
     * 噪声（AuditEvalHarnessTest 的 flip rate）才可按编号归因。样例全部一句话、borderline 钉死 MINOR。
     * 判据语义与 severity 白名单严格对齐：BLOCKING 仍仅限四类硬伤，目录不新增 BLOCKING 通道。
     */
    private String criteriaVariantB(boolean deliveryEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n七维度判据目录（逐维度对照编号扫描；MINOR 的 description 末尾必须标注触发的判据编号，")
                .append("如（C1）；无法归入任何编号的问题标（其他），默认 MINOR）：");
        sb.append("\nconsistency（设定一致性）")
                .append("\n- C1 与账本直接冲突：正文陈述与【当前账本】条目矛盾（位置/持有物/伤势/境界/生死/在场）")
                .append("\n- C2 变化无过程：账本标注了\"第N章变化：旧→新\"的状态，正文缺少对应的过程描写")
                .append("\n- C3 口径漂移：数字、称谓、组织名、地名与前文既定口径不一致")
                .append("\n  样例：通过=正文\"他把断刀插回鞘中\"且账本\"断刀：随身携带\"；")
                .append("边界=正文\"旧伤隐隐作痛\"而账本\"已痊愈\"，但同段有\"伤未真正愈合\"的铺垫 → MINOR(C1)；")
                .append("不合格=正文\"她第一次踏入北境\"而前文已两次写她驻守北境 → BLOCKING(C1)");
        sb.append("\ncharacter（人设一致性）")
                .append("\n- H1 人设越线：角色言行与既定身份/性格/能力边界冲突且无情节支撑")
                .append("\n- H2 工具人：有名字的配角只作信息转述，没有可信的自身立场、反应或判断")
                .append("\n- H3 主角单机：不成立。允许独立解决、顺利成功或整章独处；只查是否有事实/因果依据")
                .append("\n- H4 被动主角：主角无理由地失去既有目标或判断；单纯的观察、等待、消化不算")
                .append("\n  样例：通过=配角为自身立场主动隐瞒关键情报；")
                .append("边界=配角仅露面一次、反应符合立场但无主动动作 → MINOR(H2)；")
                .append("不合格=主角无任何依据地放弃既定目标并原地等待 → MINOR(H4)（强）");
        sb.append("\npacing（节奏平衡）")
                .append("\n- P1 过渡/缓冲缺失：只有紧接上一章生死危机、本章确有需要整理或消化时才核查；")
                .append("计划本身要求持续行动、或已有对白/内省消化时不算缺失")
                .append("\n- P2 暴露点密度：仅记录，不设每章次数上限；是否服务作者计划由场景功能判断")
                .append("\n- P3 代价分布：仅记录；不要求每章出现多种代价，也不把顺利成功判为缺陷")
                .append("\n  样例：通过=危机后有一段整理线索的静场；")
                .append("边界=计划明确连续行动、但段间有短暂反应 → 不记 P1；")
                .append("不合格=连续多章以死战开篇且没有任何消化，也与计划功能不符 → MINOR(P1)");
        sb.append("\ncontinuity（叙事连贯）")
                .append("\n- N1 场景跳变：时间/地点切换无过渡句，相邻段落无法建立时空连续性")
                .append(deliveryEnabled
                        ? "\n- N2 衔接偏快：必要过程已兑现但表达简短属于程度问题；计划承诺的必要过程缺失由 deliveryChecks 独立核验"
                        : "\n- N2 动作断裂：上一拍\"拔剑\"下一拍\"收剑入鞘\"，中间的交锋过程缺失")
                .append("\n- N3 指代悬空：代词/称谓的所指需要读者回翻两段以上才能确定")
                .append("\n  样例：通过=\"翌日清晨，他推开宗门大门\"衔接新场景；")
                .append("边界=场景切换只有半句过渡、需读者猜测时序 → MINOR(N1)；")
                .append(deliveryEnabled
                        ? "样例=交锋有可理解的必要过程、仅衔接偏快 → MINOR(N2)；仅报胜负而计划要求的决定性过程缺席 → deliveryChecks ISSUE，不降为 MINOR"
                        : "不合格=上段\"拔剑\"下段已\"还剑入鞘\"、交锋整段缺失 → MINOR(N2)（强）");
        sb.append("\nforeshadow（伏笔健康）")
                .append("\n- F1 变相泄底：未直接命中【本章禁泄清单】关键词，但线索组合足以让读者推出谜底")
                .append("（关键词直泄由机械层拦截为 BLOCKING，此处只判变相泄露）")
                .append("\n- F2 回收缺过程：计划标注\"回收第N章XX\"的事件，正文直接给出谜底而无连接铺垫")
                .append("\n- F3 新钩无锚：结尾抛出新谜团，但正文没有任何建立该谜团的细节")
                .append("\n  样例：通过=回收伏笔时先重现当年物证再揭谜底；")
                .append(deliveryEnabled
                        ? "边界=必要回收环节已交付，仅表达简短 → 至多 MINOR(F2)；若计划要求的必要环节缺席，不以此样例豁免 deliveryChecks；"
                        : "边界=回收过程压缩到一句带过但因果可循 → MINOR(F2)；")
                .append("不合格=第N章伏笔在本章对话中被一句闲谈无意揭穿 → MINOR(F1)（强）");
        sb.append("\nhook（钩子质量）")
                .append("\n- K1 结尾冲突/未触及：正文结尾与计划结尾悬念明显冲突或完全未触及 → BLOCKING")
                .append("\n- K2 总结式收尾：结尾只重复已知结论或空泛拔高，且与计划落点无关；")
                .append("计划本就要求闭合、感悟或余韵时不因此记问题")
                .append("\n- K3 钩子弱化：计划要求未决点，但正文没有把它交付清楚；落在心理活动本身不算弱化")
                .append("\n  样例：通过=结尾停在\"信使的手按上了城门的铁环\"；")
                .append("边界=计划要求未决、正文以心理疑问收束且信息完整 → 不记 K3；")
                .append("不合格=结尾\"从此，他踏上了新的旅程\"式空泛总结，且计划要求具体落点 → MINOR(K2)（强）");
        sb.append("\naesthetic（审美品质）")
                .append("\n- A1 定义旁白：\"这不是X，而是Y\"式定义句及其排比")
                .append("\n- A2 情绪标签空转：只反复命名情绪、不提供人物处境或判断的段落；")
                .append("有场景依据的情绪直述、内心判断和讲述可以保留")
                .append("\n- A3 独白挤压：连续独白只重复已知结论且不服务当前场景；不按句数或是否产生决策定罪")
                .append("\n- A4 术语/说教密度：与当前场景无关的设定原理复述、现代术语堆砌或说明书口吻；")
                .append("读者首次理解所需、或人物当下正在教学/解释时不算命中")
                .append("\n  样例：通过=\"他攥紧刀柄，指节泛白\"以动作呈现愤怒；")
                .append("边界=1-2 句情绪命名但有处境支撑 → 不记 A2；")
                .append("不合格=连续 5 句\"他感到恐惧，恐惧中带着愤怒，愤怒之下是委屈\" → MINOR(A3)；")
                .append("与当前场景无关的机制说明书腔 → MINOR(A4)（强）");
        sb.append("\n扫描纪律：逐维度对照编号检查后再输出；同一现象命中多条编号时取最具体的一条；宁漏勿滥。");
        sb.append("\n\nseverity 判定标准（必须严格遵守，宁可漏报不可错报）：");
        appendSeverityStandards(sb, deliveryEnabled);
        sb.append("\n\n输出要求：");
        appendOutputRequirements(sb, true);
        return sb.toString();
    }

    /** severity 标准（A/B 共享，与历史逐字一致）：BLOCKING 四类白名单 + MINOR 界定 + 降级规则 + 数量锚点 */
    private void appendSeverityStandards(StringBuilder sb, boolean deliveryEnabled) {
        sb.append("\n- BLOCKING：仅限以下四类硬伤，且必须能在正文中指出确切位置——")
                .append("\n  1) 事实矛盾：与账本/前文事实直接冲突（状态、位置、持有物、境界等前后不一致）；")
                .append("\n     账本中标注了状态变化轨迹（第N章变化：旧 → 新）的，该变化必须在正文中有对应的过程描写，")
                .append("凭空变化（伤势无端消失、位置无端转移、死者复活等）同样按事实矛盾处理；")
                .append("\n     人物在场状态：同一连续场景（同一天/同一夜/同一地点）内，角色离场后又出场、")
                .append("或出场位置与账本最后记录不符，而正文无归来/折返等过渡描写的，按事实矛盾处理——")
                .append("账本当前状态自洽不等于豁免，离场又出场必须有过场交代；")
                .append(deliveryEnabled
                        ? "\n  2) 关键交付缺失：计划承诺的事件或必要过程未兑现；在 deliveryChecks 独立报告，不重复填入七维 issues；"
                        : "\n  2) 关键事件缺失：本章计划列出的关键事件在正文中完全未出现；")
                .append("\n  3) endingHook 未呼应：正文结尾与计划的结尾悬念指向明显冲突或完全未触及；")
                .append("\n  4) 视角越权：叙事越出设定的视角约束。")
                .append("\n- MINOR：一切程度性问题——铺垫不足、钩子力度偏弱、衔接略跳、节奏偏快、表述不够精准、")
                .append("临场冲击被削弱、细节交代不充分、文风与疲劳词等。只记录，不触发修订。")
                .append(deliveryEnabled
                        ? "\n- 降级边界：必要过程与结果已兑现但表达简短/衔接偏快 = MINOR；只宣布结果、计划承诺的必要过程未兑现属于交付缺失，不能按展开不足降级。"
                        : "\n- 降级规则：关键事件出现了但展开不足/衔接偏快 = MINOR，不算缺失；")
                .append("钩子有呼应但力度或明确度不足 = MINOR，不算未呼应；")
                .append("\"-虽不算硬矛盾，但……\"式的观察一律 = MINOR。")
                .append("\n- 数量锚点：BLOCKING 通常为 0~1 条；判不准时一律降为 MINOR。")
                .append("\n- 年龄/能力越界归入第 1 类\"事实矛盾\"（不是新的第五类）：当【时序锚】或作者设定明确了")
                .append("年龄、伤势、训练与特殊能力边界，而正文让它表现出本书未建立的媒介、能力或知识时，")
                .append("才是与既成事实直接冲突，记 BLOCKING；本书明确允许的认知超前、特殊能力与表达媒介必须保留。");
        if (deliveryEnabled) {
            sb.append("\n- 上述宁漏勿滥、MINOR 降级和目录样例仅约束七维 issues，不能覆盖固定交付清单的严格判据。")
                    .append("每项按计划实际义务判断，不强制对白、动作展示或扩写；允许讲述、静场和计划许可的具体概述。")
                    .append("交付证据不足用 UNKNOWN，不能因 issues=[] 或 BLOCKING 数量锚点省略/改成 PASS。");
        }
    }

    /**
     * 输出要求（A/B 共享骨架）：catalogMode 时追加判据编号标注规则——
     * 没有编号归因的 MINOR 无法跨次对齐（A1 噪声报告按编号归因的前提）
     */
    private void appendOutputRequirements(StringBuilder sb, boolean catalogMode) {
        sb.append("\n1. 只输出 JSON，不要 Markdown，不要解释。")
                .append("\n2. 每条 issue 必须包含：dimension、severity、evidence（逐字摘自本章正文的原文引用，")
                .append("≤80 字，中间省略用……）、description、suggestion。")
                .append("\n3. 如果没有问题，issues 为空数组。")
                .append("\n4. 禁止捏造问题；给不出原文证据的问题要么不报，要么标为 MINOR。");
        if (catalogMode) {
            sb.append("\n5. MINOR 的 description 末尾必须标注触发的判据编号（如\"……（P1）\"）；")
                    .append("无法归入目录的问题标（其他），默认 MINOR。");
        }
    }

    /**
     * BLOCKING 证据校验：evidence 必须能在正文中找到（容忍空白/换行差异、引号形态、省略号截断），
     * 未命中或空证据一律降级 MINOR。只降 BLOCKING——MINOR 不触发任何链路，无需校验
     */
    void verifyEvidence(AuditResultEntity result, String content, int globalNo) {
        if (result == null || result.getIssues() == null || StringUtils.isBlank(content)) {
            return;
        }
        String normalizedContent = EvidenceMatch.normalize(content);
        for (ChapterIssueEntity issue : result.getIssues()) {
            if (issue == null || !"BLOCKING".equalsIgnoreCase(issue.getSeverity())) {
                continue;
            }
            // 用「分段多数命中」而非整串包含：引用可能替换句首主语、
            // 丢一个前置分句，或用省略号把两处原文拼起来。
            // 这些差异会破坏整串连续性，但各分段仍可锚定正文；整串判定容易把有效 BLOCKING
            // 误降为 MINOR，使其退出修订与记债通道。
            // 新口径仍拒收「纯账本摘录」「转述式引文」——它们没有可锚定的正文长段。
            if (EvidenceMatch.segmentsContained(issue.getEvidence(), normalizedContent)) {
                continue;
            }
            // 引文日志保留上限为 200 而非 50：较短截断可能落在句中，
            // 保留更多上下文便于核对降级是否有误及正文证据是否充分。
            issue.setSeverity("MINOR");
            log.info("第 {} 章 BLOCKING 证据校验未通过，已降级 MINOR（{}）：{}",
                    globalNo, issue.getDimension(), StringUtils.abbreviate(issue.getEvidence(), 200));
        }
    }

    /** 修订验证输出：verdicts 与传入的 BLOCKING 列表按 index（1 起）对齐，deliveryChecks 按固定 targetId 独立对齐。 */
    public static class FixCheckOutput {
        private List<FixVerdict> verdicts;
        private Boolean continuityChecked;
        private List<ChapterIssueEntity> introducedConflicts;
        private List<CausalVerdict> deliveryChecks;

        public List<CausalVerdict> getDeliveryChecks() { return deliveryChecks; }
        public void setDeliveryChecks(List<CausalVerdict> value) { deliveryChecks = value; }

        public Boolean getContinuityChecked() { return continuityChecked; }
        public void setContinuityChecked(Boolean value) { continuityChecked = value; }
        public List<ChapterIssueEntity> getIntroducedConflicts() { return introducedConflicts; }
        public void setIntroducedConflicts(List<ChapterIssueEntity> value) { introducedConflicts = value; }

        public List<FixVerdict> getVerdicts() {
            return verdicts;
        }

        public void setVerdicts(List<FixVerdict> verdicts) {
            this.verdicts = verdicts;
        }

        /** 普通问题 fixed 无需缺席证据；交付结论必须另外核对 deliveryChecks，不能以 fixed=true 代替。 */
        public record FixVerdict(int index, boolean fixed, String evidence) {
        }
    }

    /**
     * 修订验证结果：{@code unfixed} 是"仍未修复"的子集，{@code degraded} 表示**这次验证根本没跑成**。
     *
     * 两者必须区分：基础设施抖动（网关异常/超时）不代表稿件存在内容缺陷，
     * 不应记成质量债并回灌给写手；验证未完成与确认问题仍未修复是不同结论。
     *
     * 保守偏向不变：{@code degraded=true} 时 {@code unfixed} 仍原样带回上一轮全部 BLOCKING
     * （不放行未验证的稿），差别只在于调用方**不得把它记成质量债**。
     */
    public record FixVerification(List<ChapterIssueEntity> unfixed, boolean degraded, DeliveryReview deliveryReview) {
        public FixVerification(List<ChapterIssueEntity> unfixed, boolean degraded) {
            this(unfixed, degraded, null);
        }

        static FixVerification verified(List<ChapterIssueEntity> unfixed) {
            return new FixVerification(unfixed, false);
        }
    }

    private static final BeanOutputConverter<FixCheckOutput> FIX_CONVERTER =
            new BeanOutputConverter<>(FixCheckOutput.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());
    private record LegacyFixOutput(List<FixCheckOutput.FixVerdict> verdicts, Boolean continuityChecked,
                                   List<ChapterIssueEntity> introducedConflicts) { }
    private static final String LEGACY_FIX_FORMAT = new BeanOutputConverter<>(LegacyFixOutput.class).getFormat();

    /**
     * 修订稿复审（A3 验证化，docs/enhancement-plan.md）：不再全章重审——开放任务方差大，
     * 二轮翻案即"修订拉锯"。改为逐条验证上一轮 BLOCKING 是否已修复：任务窄、判据明，
     * 判定稳定性远高于开放式重审。
     *
     * 保守语义：未确认已修复 = 仍未修复——模型输出缺条目、验证调用解析失败
     * 均按 UNFIXED 处理并留痕（复核的失败模式应偏向"多修一轮"而非"漏放 BLOCKING"，
     * 与审校侧"宁可漏报不可错报"是同一条保守哲学在复核层的镜像）。
     * 返回仍未修复的子集（原 issue 对象原样返回）；全部修复返回空列表。
     *
     * 失败语义：**不抛异常**。两次尝试均无法解析、
     * 或调用本身异常时，返回 {@code degraded=true} 并原样带回全部 BLOCKING——内容侧仍按
     * "视为未修复"保守处理（不放行未验证的稿），但调用方必须把"未验证"与"确认未修复"分开，
     * **不得记入质量债**（记债会把基础设施抖动写成内容缺陷，再回灌给写手）。
     * 此前这里写着"与 {@link #audit} 一致抛 AppException 终止本批"，与实现不符，已按实现改正。
     */
    public FixVerification verifyFixes(StoryVO storyVO,
                                       ChapterPlanItemEntity item,
                                       String revisedContent,
                                       List<ChapterIssueEntity> blocking,
                                       String ledgerPrompt,
                                       StyleStatEntity styleStat,
                                       int globalNo) {
        return verifyFixes(storyVO, item, revisedContent, blocking, ledgerPrompt, styleStat, globalNo, null, List.of());
    }

    public FixVerification verifyFixes(StoryVO storyVO, ChapterPlanItemEntity item, String revisedContent,
                                       List<ChapterIssueEntity> blocking, String ledgerPrompt, StyleStatEntity styleStat,
                                       int globalNo, cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext writerContext) {
        return verifyFixes(storyVO, item, revisedContent, blocking, ledgerPrompt, styleStat, globalNo, writerContext, List.of());
    }

    public FixVerification verifyFixes(StoryVO storyVO, ChapterPlanItemEntity item, String revisedContent,
                                       List<ChapterIssueEntity> blocking, String ledgerPrompt, StyleStatEntity styleStat,
                                       int globalNo, cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext writerContext,
                                       List<String> reviewCandidates) {
        return verifyFixes(storyVO, item, revisedContent, blocking, ledgerPrompt, styleStat, globalNo,
                writerContext, reviewCandidates, null);
    }

    public FixVerification verifyFixes(StoryVO storyVO, ChapterPlanItemEntity item, String revisedContent,
                                       List<ChapterIssueEntity> blocking, String ledgerPrompt, StyleStatEntity styleStat,
                                       int globalNo, RewriteContext writerContext, List<String> reviewCandidates,
                                       DeliveryReviewPolicy.Request request) {
        List<String> candidates = reviewCandidates == null
                ? List.of() : reviewCandidates.stream().filter(StringUtils::isNotBlank).toList();
        // 无计划完整性候选时保持原调用形态（既有注入点与测试桩不受影响）。
        if (candidates.isEmpty()) {
            return request == null
                    ? verifyFixesInternal(storyVO, item, revisedContent, blocking, ledgerPrompt, styleStat, globalNo,
                            writerContext)
                    : verifyFixesWithDelivery(storyVO, item, revisedContent, blocking, ledgerPrompt, globalNo,
                            writerContext, request);
        }
        List<ChapterIssueEntity> checks = new ArrayList<>(blocking == null ? List.of() : blocking);
        for (String event : candidates) {
            checks.add(ChapterIssueEntity.builder().dimension(PLAN_INTEGRITY_DIMENSION).severity("BLOCKING")
                    .description("修订后计划事件完整性：该事件被保留，或有事实依据地修正而不是被直接删除：" + event)
                    .evidence(event).build());
        }
        var verificationContext = writerContext != null ? writerContext : new RewriteContext(null, null, null);
        return request == null
                ? verifyFixesInternal(storyVO, item, revisedContent, checks, ledgerPrompt, styleStat, globalNo,
                        verificationContext)
                : verifyFixesWithDelivery(storyVO, item, revisedContent, checks, ledgerPrompt, globalNo,
                        verificationContext, request);
    }

    private FixVerification verifyFixesWithDelivery(StoryVO storyVO, ChapterPlanItemEntity item, String revisedContent,
                                                    List<ChapterIssueEntity> blocking, String ledgerPrompt, int globalNo,
                                                    RewriteContext writerContext, DeliveryReviewPolicy.Request request) {
        List<ChapterIssueEntity> checks = new ArrayList<>(blocking == null ? List.of() : blocking);
        checkCancellation(request, writerContext);
        if (!requestMatches(request, item, revisedContent, globalNo)) {
            return new FixVerification(checks, true, DeliveryReviewPolicy.unavailable(request,
                    "DELIVERY_VERSION_MISMATCH: 修订稿或计划与冻结核验请求不一致"));
        }
        DeliveryReview review = DeliveryReviewPolicy.unavailable(request, "DELIVERY_UNAVAILABLE: 修订核验尚未完成");
        String diagnostic = null;
        // 即使 checks=[] 也必须核验当前修订稿的完整交付清单，不能沿用旧稿 PASS。
        for (int attempt = 1; attempt <= 2; attempt++) {
            checkCancellation(request, writerContext);
            String raw;
            try {
                raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                        .userPrompt(withRetryDiagnostic(buildFixVerifyPrompt(checks, revisedContent, ledgerPrompt,
                                item, writerContext, request), diagnostic))
                        .label("audit-verify-第" + globalNo + "章")
                        .scene(ModelScene.CHAPTER_AUDIT)
                        .build());
            } catch (Exception failure) {
                checkCancellation(request, writerContext);
                rethrowControlSignal(failure);
                diagnostic = failureDiagnostic("DELIVERY_VERIFY_UNAVAILABLE", failure);
                review = DeliveryReviewPolicy.unavailable(request, diagnostic);
                log.warn("交付复验第 {}/2 次调用异常，chapterNo: {}", attempt, globalNo, failure);
                continue;
            }
            checkCancellation(request, writerContext);
            List<ChapterIssueEntity> unfixed = null;
            try {
                FixCheckOutput output = readStrictFixOutput(readStrictObject(raw), checks.size(), writerContext != null);
                unfixed = resolveUnfixed(checks, output.getVerdicts(), revisedContent, globalNo);
                if (writerContext != null) {
                    String normalized = EvidenceMatch.normalize(revisedContent);
                    for (ChapterIssueEntity conflict : output.getIntroducedConflicts()) {
                        if (StringUtils.isBlank(conflict.getDescription())
                                || !EvidenceMatch.contained(conflict.getEvidence(), normalized)) {
                            throw new IllegalArgumentException("introducedConflicts 缺少描述或连续原文证据未命中修订稿");
                        }
                        conflict.setDimension("continuity");
                        conflict.setSeverity("BLOCKING");
                        unfixed.add(conflict);
                    }
                }
                review = DeliveryReviewPolicy.consume(request, output.getDeliveryChecks());
                diagnostic = reviewDiagnostic(review);
            } catch (Exception invalid) {
                checkCancellation(request, writerContext);
                rethrowControlSignal(invalid);
                diagnostic = failureDiagnostic("DELIVERY_VERIFY_PROTOCOL", invalid);
                review = DeliveryReviewPolicy.unavailable(request, diagnostic);
            }
            checkCancellation(request, writerContext);
            if (!review.unknown()) {
                log.info("修订验证及交付核验完成，chapterNo: {}, 未修复: {}, delivery: {}",
                        globalNo, unfixed.size(), review.status());
                // ISSUE 是有效内容结论，不是基础设施降级；旧 fixed 不为交付 PASS 提供授权。
                return new FixVerification(unfixed, false, review);
            }
            log.warn("交付复验第 {}/2 次未完成，chapterNo: {}，{}", attempt, globalNo, diagnostic);
        }
        checkCancellation(request, writerContext);
        return new FixVerification(checks, true, review);
    }

    private static FixCheckOutput readStrictFixOutput(JsonNode root, int checkCount, boolean continuityRequired)
            throws JsonProcessingException {
        JsonNode verdicts = requireArray(root, "verdicts");
        Set<Integer> indices = new HashSet<>();
        for (JsonNode verdict : verdicts) {
            JsonNode index = verdict.get("index"), fixed = verdict.get("fixed");
            if (!verdict.isObject() || index == null || !index.isIntegralNumber() || !index.canConvertToInt()
                    || index.intValue() < 1 || index.intValue() > checkCount || !indices.add(index.intValue())
                    || fixed == null || !fixed.isBoolean()) {
                throw new IllegalArgumentException("verdicts 必须逐项包含唯一合法 index 与布尔 fixed，不可省略/重复/增添索引");
            }
        }
        if (indices.size() != checkCount) {
            throw new IllegalArgumentException("verdicts 覆盖不完整：需要 " + checkCount + " 条，实际 " + indices.size());
        }
        FixCheckOutput output = new FixCheckOutput();
        output.setVerdicts(Arrays.asList(DELIVERY_MAPPER.treeToValue(verdicts, FixCheckOutput.FixVerdict[].class)));
        if (continuityRequired) {
            JsonNode checked = root.get("continuityChecked");
            if (checked == null || !checked.isBoolean() || !checked.booleanValue()) {
                throw new IllegalArgumentException("必须显式返回 continuityChecked=true 并完成修订连续性核对");
            }
            output.setContinuityChecked(true);
            output.setIntroducedConflicts(readIssues(root, "introducedConflicts"));
        }
        output.setDeliveryChecks(readDeliveryChecks(root));
        return output;
    }

    private FixVerification verifyFixesInternal(StoryVO storyVO, ChapterPlanItemEntity item, String revisedContent,
                                       List<ChapterIssueEntity> blocking, String ledgerPrompt, StyleStatEntity styleStat,
                                       int globalNo, cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext writerContext) {
        if (blocking == null || blocking.isEmpty()) {
            return FixVerification.verified(List.of());
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            checkCancellation(null, writerContext);
            try {
                // 温度/模型由 scene-models 的 audit 场景配置驱动（与首审同一模型族）
                String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                        .userPrompt(buildFixVerifyPrompt(blocking, revisedContent, ledgerPrompt, item, writerContext))
                        .label("audit-verify-第" + globalNo + "章")
                        .scene(ModelScene.CHAPTER_AUDIT)
                        .build());
                FixCheckOutput output = FIX_CONVERTER.convert(raw);
                if (output == null || output.getVerdicts() == null) {
                    output = JsonParseFallback.parse(raw, FIX_CONVERTER::convert);
                    if (output != null) {
                        log.info("修订验证经解析降级修复后成功，chapterNo: {}", globalNo);
                    }
                }
                checkCancellation(null, writerContext);
                if (output != null && output.getVerdicts() != null) {
                    List<ChapterIssueEntity> unfixed = resolveUnfixed(blocking, output.getVerdicts(), revisedContent, globalNo);
                    if (writerContext != null) {
                        if (!Boolean.TRUE.equals(output.getContinuityChecked()) || output.getIntroducedConflicts() == null) {
                            log.warn("第 {} 章修订新增连续性核对缺失，不当作验证通过", globalNo);
                            continue;
                        }
                        for (ChapterIssueEntity conflict : output.getIntroducedConflicts()) {
                            if (conflict == null || !StringUtils.isNotBlank(conflict.getEvidence())
                                    || !StringUtils.isNotBlank(conflict.getDescription())
                                    || !EvidenceMatch.contained(conflict.getEvidence(), EvidenceMatch.normalize(revisedContent))) {
                                return new FixVerification(new ArrayList<>(blocking), true);
                            }
                            conflict.setDimension("continuity");
                            conflict.setSeverity("BLOCKING");
                            unfixed.add(conflict);
                        }
                    }
                    log.info("修订验证完成，chapterNo: {}, 待验证 {} 条，未修复 {} 条",
                            globalNo, blocking.size(), unfixed.size());
                    return FixVerification.verified(unfixed);
                }
                log.warn("修订验证第 {}/2 次输出无法解析，chapterNo: {}", attempt, globalNo);
            } catch (Exception e) {
                rethrowControlSignal(e);
                checkCancellation(null, writerContext);
                log.warn("修订验证第 {}/2 次尝试异常，chapterNo: {}", attempt, globalNo, e);
            }
        }
        // 验证没跑成 ≠ 没修好：内容侧仍保守（把上一轮 BLOCKING 原样带回、不放行未验证稿），
        // 但以 degraded 标记与"确认未修复"区分开，调用方据此不落质量债。
        // 可 grep AUDIT_VERIFY_DEGRADED 定位本批哪些章是"未验证"而非"未修复"
        log.warn("AUDIT_VERIFY_DEGRADED 修订验证彻底失败，chapterNo: {}，"
                + "按保守处理带回 {} 条 BLOCKING（标记为【未验证】，不得记入质量债）",
                globalNo, blocking.size());
        return new FixVerification(new ArrayList<>(blocking), true);
    }

    /** 未确认已修复 = 仍未修复：缺条目按 UNFIXED；UNFIXED 证据校验失败不降级（会漏放 BLOCKING），仅留痕 */
    private List<ChapterIssueEntity> resolveUnfixed(List<ChapterIssueEntity> blocking,
                                                    List<FixCheckOutput.FixVerdict> verdicts,
                                                    String revisedContent, int globalNo) {
        Map<Integer, FixCheckOutput.FixVerdict> byIndex = new java.util.HashMap<>();
        for (FixCheckOutput.FixVerdict verdict : verdicts) {
            if (verdict != null && verdict.index() > 0) {
                byIndex.put(verdict.index(), verdict);
            }
        }
        List<ChapterIssueEntity> unfixed = new ArrayList<>();
        for (int i = 0; i < blocking.size(); i++) {
            ChapterIssueEntity issue = blocking.get(i);
            FixCheckOutput.FixVerdict verdict = byIndex.get(i + 1);
            if (verdict != null && verdict.fixed()) {
                log.info("第 {} 章复核确认已修复（{}）：{}", globalNo,
                        nullToBlank(issue.getDimension()),
                        StringUtils.abbreviate(StringUtils.defaultString(issue.getDescription()), 60));
                continue;
            }
            if (verdict != null && StringUtils.isNotBlank(verdict.evidence())) {
                String normalizedContent = EvidenceMatch.normalize(revisedContent);
                if (!EvidenceMatch.segmentsContained(verdict.evidence(), normalizedContent)) {
                    log.info("第 {} 章复核 UNFIXED 证据未命中修订稿（按仍未修复处理，留痕）：{}",
                            globalNo, StringUtils.abbreviate(verdict.evidence(), 100));
                }
            }
            unfixed.add(issue);
        }
        return unfixed;
    }

    private String buildFixVerifyPrompt(List<ChapterIssueEntity> blocking, String revisedContent,
                                        String ledgerPrompt, ChapterPlanItemEntity item,
                                        cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext writerContext) {
        return buildFixVerifyPrompt(blocking, revisedContent, ledgerPrompt, item, writerContext, null);
    }

    private String buildFixVerifyPrompt(List<ChapterIssueEntity> blocking, String revisedContent,
                                        String ledgerPrompt, ChapterPlanItemEntity item,
                                        RewriteContext writerContext, DeliveryReviewPolicy.Request request) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是审校复核员。修订稿声称已修复以下 ").append(blocking.size())
                .append(" 条 BLOCKING 问题。逐条核对修订稿，判定每条是否已修复。");
        for (int i = 0; i < blocking.size(); i++) {
            ChapterIssueEntity issue = blocking.get(i);
            sb.append("\n").append(i + 1).append(". 【").append(nullToBlank(issue.getDimension()))
                    .append("】").append(nullToBlank(issue.getDescription()));
            if (StringUtils.isNotBlank(issue.getEvidence())) {
                sb.append("\n   原问题证据：").append(issue.getEvidence());
            }
        }
        sb.append("\n\n判定口径：")
                .append(request == null
                        ? "\n- fixed=true：修订稿中该问题已不再成立（事实已改正/关键事件已补足/结尾悬念已呼应/视角已收拢）。修复不需要证据；拿不准一律 false。"
                        : "\n- fixed=true：普通七维问题已不再成立，不要求为问题缺席另举证；拿不准一律 false。"
                                + "plan-delivery 的修复只由当前修订稿的 deliveryChecks 判断，不能用无证据 fixed=true 作为交付证明。")
                .append(request == null
                        ? "\n- fixed=false：问题仍然成立或只被部分修复。此时 evidence 必须逐字摘自修订稿中问题仍然存在的位置（≤80 字，中间省略用……）。"
                        : "\n- fixed=false：问题仍然成立或只被部分修复，evidence 引用当前修订稿的问题位置。"
                                + "若 plan-delivery 事件完全缺席，旧条目可不填正文引文；在 deliveryChecks 用 MISSING_DELIVERY 提供计划锚、全文核对理由和修复目标，不得捏造不存在的正文。")
                .append(writerContext == null
                        ? "\n- 只验证列出的条目，不新增问题，不改变条目顺序。"
                        : "\n- 列出条目逐项验证；额外只核对修订直接引入的连续性冲突，放在 introducedConflicts，不改原条目顺序。")
                .append("\n\n【本章计划】")
                .append("\n目标：").append(nullToBlank(item.getGoal()))
                .append("\n关键事件：").append(joinList(item.getKeyEvents()))
                .append("\n结尾悬念：").append(nullToBlank(item.getEndingHook()));
        if (request != null && item.getScenes() != null && !item.getScenes().isEmpty()) {
            sb.append("\n\n").append(ScenePlanPolicy.render(item.getScenes()));
        }
        if (StringUtils.isNotBlank(ledgerPrompt)) {
            sb.append("\n\n【当前账本】\n").append(ledgerPrompt);
        }
        if (writerContext != null) {
            sb.append("\n【修订影响范围连续性核对】只核对修订涉及的时间、位置、物品身份与去向、承诺履行和保密状态，")
                    .append("以及这些改动前后的直接衔接，不做文风或全章重新评审。不以引文存在证明因果成立。")
                    .append("新增冲突须是明确的事实矛盾并引用修订稿连续原文；不是短句、少对白或没有代价。")
                    .append("\n【计划完整性核验】标记为“修订后计划事件完整性”的条目，只有事件仍以不同措辞交付、")
                    .append("或依据冻结事实作了合法修正才可判 fixed；直接删除、只留片段或拿不准均判 false。");
            if (StringUtils.isNotBlank(writerContext.writerUserPrompt())) {
                sb.append("\n【冻结写作依据】\n").append(writerContext.writerUserPrompt());
            }
            if (request != null && StringUtils.isNotBlank(writerContext.writerSystemPrompt())) {
                sb.append("\n【冻结写作约束】\n").append(writerContext.writerSystemPrompt());
            }
            if (writerContext.original() != null) {
                sb.append("\n【修订前版本】\n").append(writerContext.original().content());
            }
            sb.append("\n输出 continuityChecked=true，并提供 introducedConflicts 数组；没有新增冲突显式输出[]，不能省略。");
        }
        if (request != null) {
            sb.append(DeliveryReviewPolicy.prompt(request))
                    .append("\n交付核验只引用下方【修订稿】，冻结依据与修订前版本仅作连续性对照，不是当前交付证据。")
                    .append("必要过程与结果已兑现但简短仍属程度问题；计划承诺的必要过程未兑现不能以展开不足豁免，")
                    .append("也不强制对白、动作展示或扩写。");
        }
        sb.append("\n\n【修订稿】\n").append(revisedContent)
                .append("\n\n请严格按照以下 JSON 格式输出：\n")
                .append(request == null ? LEGACY_FIX_FORMAT : FIX_CONVERTER.getFormat());
        if (request != null) {
            sb.append("\n同一个完整 JSON 对象显式提供 verdicts 与 deliveryChecks 数组（无旧问题则 verdicts=[]）。")
                    .append("旧条目按原 index 返回，不得与 targetId 混用；不能因 fixed=true 省略固定清单。")
                    .append("只报告模型核验字段，不自报 deliveryReview/最终状态/hash；禁止重复键、截断或尾随说明。");
        }
        return sb.toString();
    }

    private String renderStyleStat(StyleStatEntity styleStat) {
        if (styleStat == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> repeated = styleStat.getRepeatedSentences();
        if (repeated != null && !repeated.isEmpty()) {
            sb.append("跨章重复句（严禁本章再次逐字使用）：\n");
            repeated.stream()
                    .skip(Math.max(0, repeated.size() - 10))
                    .forEach(s -> sb.append("- ").append(s).append("\n"));
        }
        if (styleStat.getFatigueWords() != null && !styleStat.getFatigueWords().isEmpty()) {
            List<Map.Entry<String, Integer>> overused = styleStat.getFatigueWords().entrySet().stream()
                    .filter(e -> e.getValue() >= 5)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toList());
            if (!overused.isEmpty()) {
                sb.append("疲劳词超频（本章禁用或至多出现一次）：\n");
                overused.forEach(e -> sb.append("- ").append(e.getKey()).append("(").append(e.getValue()).append("次)\n"));
            }
        }
        return sb.length() == 0 ? "" : sb.toString().trim();
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

}
