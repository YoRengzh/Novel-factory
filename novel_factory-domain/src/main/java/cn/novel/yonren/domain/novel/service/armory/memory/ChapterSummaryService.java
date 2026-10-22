package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.quality.EvidenceMatch;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.JsonParseFallback;
import cn.novel.yonren.types.utils.JsonRepair;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 章节摘要服务：把刚生成的几千字正文压缩成结构化记忆
 * （剧情摘要 + 角色/物品/势力状态 + 伏笔新埋/回收 + 时点），
 * 同时对照当前账本做一致性软校验（continuityConflicts）。
 * 在逐章循环内每章生成后立即调用，供下一章组装记忆前缀。
 * 摘要是压缩任务，不走 PromptBuilder——风格资料/去AI味等创作规则对压缩有害无益
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterSummaryService {

    private static final BeanOutputConverter<ChapterSummaryEntity> CONVERTER = new BeanOutputConverter<>(
            ChapterSummaryEntity.class,
            JsonMapper.builder()
                    .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                    // 模型偶发在顶层多输出 evidence 等非实体字段，忽略未知字段避免整段解析失败
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build());

    /** 容错解析用：把 foreshadowingNew 误输出的对象数组转回字符串数组（详见 tryConvertWithForeshadowFix） */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 伏笔种子原文引用上限字数（与 prompt 约束一致，超长截断保住子串有效性） */
    private static final int SEED_EXCERPT_MAX_LENGTH = 80;
    /** 重述判定最短字数：双方至少达到该长度才参与包含判定，防短关键词误杀正常新伏笔 */
    private static final int RESTATED_MIN_CHARS = 8;
    // 状态证据的字数上限与命中判定统一由 EvidenceMatch 提供（三处反编造门共用同一口径）

    private final LlmGateway llmGateway;

    /**
     * 生成章节摘要，三级降级保证记忆尽可能入账：
     * ① 原文解析；② 失败则正则修复常见 JSON 病（悬空逗号/markdown 包裹）后重解；
     * ③ 仍失败重试一次 LLM 调用（格式抖动是概率性的）；全败则抢救 summary 字段单独入账（partial 标记）。
     * 抢救也失败时抛 AppException（记忆是续写唯一依据，质量门硬失败，绝不静默跳过）。
     *
     * @param globalNo     全局章节号（续写时带偏移，账本/落盘以此为准）
     * @param ledgerPrompt 当前账本文本（角色/物品/势力），作为一致性校验的比对基准，可为 null
     */
    public ChapterSummaryEntity summarize(StoryVO storyVO, ChapterPlanItemEntity item, String content,
                                          int globalNo, String ledgerPrompt) {
        return summarize(storyVO, item, content, globalNo, ledgerPrompt, null);
    }

    /**
     * 带待回收伏笔账的重载：摘要模型据此逐字沿用原文登记回收，保证账本核销匹配可靠
     *
     * @param pendingForeshadowing 此前埋设、尚未回收的伏笔内容清单，可为 null
     */
    public ChapterSummaryEntity summarize(StoryVO storyVO, ChapterPlanItemEntity item, String content,
                                          int globalNo, String ledgerPrompt, List<String> pendingForeshadowing) {
        return summarize(storyVO, item, content, globalNo, ledgerPrompt, pendingForeshadowing, null);
    }

    /**
     * 带时间锚的重载（2026-10-13）：timePoint 要求写出年份后，模型必须知道**当前故事年份**。
     *
     * <p><b>为什么必须给锚</b>：实测第 136 章正文只写"三月十七号"（年份在前章），
     * 摘要模型据此猜成"2010年3月17日"，而正典是 2011 年——该摘要一旦入库即成为下一章的
     * 时序锚，年份错位会自我固化（并且污染"进度对齐"体检的口径）。
     * 锚由调用方用历史摘要渲染（{@code ConsistencyIndexService.renderTimeAnchor}），
     * 与规划/正文/审校用的是同一根锚，三处同源。
     *
     * @param historyForAnchor 历史摘要（不含本章），用于渲染时序锚；可为 null（老调用/新书首章）
     */
    public ChapterSummaryEntity summarize(StoryVO storyVO, ChapterPlanItemEntity item, String content,
                                          int globalNo, String ledgerPrompt, List<String> pendingForeshadowing,
                                          List<ChapterSummaryEntity> historyForAnchor) {
        String lastRaw = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // maxTokens/temperature 不再硬编码，由 scene-models 的 summary 场景配置驱动
                String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                        .userPrompt(buildPrompt(item, content, globalNo, ledgerPrompt,
                                pendingForeshadowing, historyForAnchor))
                        .label("summary-第" + globalNo + "章")
                        .scene(ModelScene.CHAPTER_SUMMARY)
                        .build());
                lastRaw = raw;

                ChapterSummaryEntity summary = tryConvert(raw);
                if (summary == null) {
                    summary = JsonParseFallback.parse(raw, this::tryConvert);
                    if (summary != null) {
                        log.info("章节摘要经四级解析降级修复后解析成功，chapterNo: {}", globalNo);
                    }
                }
                if (summary == null) {
                    // 容错：模型常把 foreshadowingNew（List<String>）误输出成对象数组，此处先修正结构再解析
                    summary = tryConvertWithForeshadowFix(raw);
                    if (summary != null) {
                        log.info("章节摘要经 foreshadowingNew 结构修复后解析成功，chapterNo: {}", globalNo);
                    }
                }
                if (summary != null) {
                    summary.setChapterNo(globalNo);
                    summary.setTitle(item.getTitle());
                    validateForeshadowSeeds(summary, content);
                    filterRestatedForeshadows(summary, pendingForeshadowing);
                    // 顺序有讲究：补齐必须在重述过滤**之后**——过滤会把被判重述的条目
                    // 从 new 与 seeds 双向剔除，补齐若在前，会把刚剔掉的种子又补回 new。
                    // 补齐自身也要过一遍重述闸（模型可能把账本旧线只填进 seeds 没填 new），
                    // 否则重述条目借补齐通道绕过过滤重新入账。只补 resolvable=TRUE 且 new 缺失的种子
                    syncSeedsIntoNew(summary, pendingForeshadowing);
                    summary.setForeshadowingResolved(
                            sanitizeResolved(summary.getForeshadowingResolved(), pendingForeshadowing));
                    validateStateEvidence(summary, content);
                    // 数值事实兜底：必须在一致性证据校验**之前**——回填的事实取正文原句作证据，
                    // 走同一道 EvidenceMatch 校验，才能与模型输出的事实同等待遇
                    backfillNumericFacts(summary, content);
                    validateConsistencyEvidence(summary, content);
                    log.info("章节摘要生成完成，chapterNo: {}, 一致性偏差 {} 条", globalNo,
                            summary.getContinuityConflicts() == null ? 0 : summary.getContinuityConflicts().size());
                    return summary;
                }
                log.warn("章节摘要第 {}/2 次输出无法解析，chapterNo: {}", attempt, globalNo);
            } catch (Exception e) {
                log.warn("章节摘要第 {}/2 次尝试异常，chapterNo: {}", attempt, globalNo, e);
            }
        }

        ChapterSummaryEntity salvaged = salvageSummary(lastRaw, globalNo, item.getTitle());
        if (salvaged != null) {
            // 抢救成功：仅剧情摘要入账，账本状态/伏笔账缺失，打残缺标记供复盘与后续决策
            salvaged.setPartial(true);
            log.warn("章节摘要解析失败，已降级为仅剧情摘要入账（账本状态缺失，partial 标记），chapterNo: {}", globalNo);
            return salvaged;
        }
        throw new AppException(ResponseCode.UN_ERROR.getCode(),
                "第 " + globalNo + " 章摘要彻底失败（含修复后重解、一次重试与 summary 字段抢救），记忆无法入账，本批终止；"
                        + "此前章节已通过逐章检查点落盘，可在请求中携带 resumeStoryDir 续写补齐后续章节");
    }

    /**
     * 尝试结构化解析，失败返回 null（不抛出）
     */
    private ChapterSummaryEntity tryConvert(String raw) {
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 容错解析：模型（尤其关闭思考模式后）常把 foreshadowingNew 从字符串数组误输出成对象数组
     * （{content,excerpt,importance}，即 foreshadowSeeds 的结构），导致 Jackson 反序列化失败。
     * 此处先尝试把 foreshadowingNew 数组内每个对象元素的 content 字段提出、重建为字符串数组，再整体解析。
     * 若字段本身已是字符串数组或整体非对象结构则原样返回 null（由调用方走后续降级），绝不破坏成功路径。
     */
    private ChapterSummaryEntity tryConvertWithForeshadowFix(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String text = JsonRepair.repair(raw);
            if (text == null) {
                return null;
            }
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode foreshadowing = root.get("foreshadowingNew");
            if (foreshadowing == null || !foreshadowing.isArray()) {
                return null;
            }
            boolean anyObject = false;
            for (JsonNode item : foreshadowing) {
                if (item != null && item.isObject()) {
                    anyObject = true;
                    break;
                }
            }
            if (!anyObject) {
                return null; // 已是正常字符串数组，无需修复
            }
            ArrayNode fixed = JSON.createArrayNode();
            for (JsonNode item : foreshadowing) {
                if (item == null) {
                    continue;
                }
                if (item.isObject()) {
                    JsonNode content = item.get("content");
                    if (content != null && content.isTextual()) {
                        fixed.add(content.asText());
                    }
                } else if (item.isTextual()) {
                    fixed.add(item.asText());
                }
            }
            ((ObjectNode) root).set("foreshadowingNew", fixed);
            return CONVERTER.convert(root.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 最后兜底：从坏 JSON 中抢救 summary 字段，构造仅含剧情摘要的残缺记忆
     * （账本状态缺失，但核心剧情事实保住，避免整章记忆空洞）
     */
    private ChapterSummaryEntity salvageSummary(String raw, int globalNo, String title) {
        // 取首个非空 summary——重复键时 JSON 库会取到后一个空串，按出现顺序扫才能拿回真正文
        String summary = JsonRepair.extractStringField(raw, "summary", true);
        if (StringUtils.isBlank(summary)) {
            return null;
        }
        return ChapterSummaryEntity.builder()
                .chapterNo(globalNo)
                .title(title)
                .summary(summary)
                .build();
    }

    private String buildPrompt(ChapterPlanItemEntity item, String content, int globalNo,
                               String ledgerPrompt, List<String> pendingForeshadowing,
                               List<ChapterSummaryEntity> historyForAnchor) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是小说流水线的记忆管理员。阅读以下章节正文，产出供后续章节生成使用的结构化记忆。")
                .append("\n要求：")
                .append("\n1. summary：150-200 字概括核心剧情推进，覆盖本章结束时各角色的去向；")
                .append("只记录状态变化、决策、新信息；疼痛感受、情绪渲染、环境氛围一律不记入摘要，")
                .append("除非它造成了可核查的状态变化（如伤势加重、位置移动、关系破裂）——")
                .append("摘要里的每个字都会作为事实喂给后续章节，注水内容进摘要会自我强化；")
                .append("\n1.1 verifiableDetails：本章中被正文**明确描写**的关键动作或物证，")
                .append("逐字摘录原句片段（≤50 字），至多 3 条，没有则为空数组。")
                .append("这是为了补上核验所需的粒度——阶段退出条件常要求「某动作/某细节被文本明确描写」，")
                .append("而 summary 只记主干、不记细节，于是会出现\"正文明明写了、核验却在摘要里找不到\"的假阴性；")
                .append("只记**可观察的动作或物证**（如\"指尖在桌面上无意识地轻叩了两下\"\"切成三段：哒、哒哒、哒\"），")
                .append("**严禁**记情绪、心理活动与环境氛围；必须逐字来自正文，严禁改写、拼接或概括；")
                .append("\n2. characterStates：列出本章结束时状态发生变化（或首次登场）的每个角色及其当前状态")
                .append("（伤势/修为/位置/情绪/持有物），主角配角都要记录，状态必须与正文一致；")
                .append("若某角色位置/去留状态与下方当前账本记载相反（如账本记已离场、本章正文却仍在场，")
                .append("或账本记在场、本章离场），status 必须标注轨迹\"第N章变化：旧状态 → 新状态\"，")
                .append("且该反转必须已在本章正文中有过渡描写（归来/折返/离开）；无过渡描写时记入 continuityConflicts；")
                .append("\n2.1 characterBeats：只记录本章真正参与行动的关键配角（不含纯路人和无名群众），每条必须说明")
                .append("该配角独立于主角的个人目标、主动决定、决定后果、与主角关系变化和下一步意图；")
                .append("配角不能只写‘帮助主角/提供信息’，若本章没有自主行为则不要编造；每章至多 4 条，")
                .append("evidence 必须是支持该行为的正文连续原句（≤80 字）；")
                .append("\n3. itemStates：本章状态发生变化或首次出现的关键物品（法宝/金手指/信物等），没有则为空数组；")
                .append("\n3.1 cheatMechanismUsed：仅当故事设定明确存在金手指/系统时填写 true/false，表示本章是否实际使用或有意义提及；没有金手指时必须为 false；")
                .append("\n3.2 abilityShowcased（布尔）：本章是否出现【主角能力/早慧展示场景】——主角做出超出其阶段常规")
                .append("的能力/认知表现，**且有他人在场可观察**（独自思考、暗中盘算、无人知晓的内心活动不算）；")
                .append("判定要克制，只有构成「展示时刻」才算：旁人因该表现产生具体的注意、疑问或评价，")
                .append("或主角以动作/载体向他人暗示了信息；单纯的日常行为、与主角阶段相称的行为不算，")
                .append("本章无此类场景则为 false。")
                .append("abilityShowcased=true 时必须同时在 abilityDisplayForm 用 8-16 字概括展示形态")
                .append("（如「手指蘸水画圈引人注意」「旁人评价不像四岁」「对数字页反复专注」），")
                .append("false 时该字段留空——该字段用于跨章检测「能力展示套路化」，宁可漏判不要误判；")
                .append("\n4. factionStates：本章出现或动向变化的势力/组织，没有则为空数组；")
                .append("三账本条目总量克制：角色至多 6 条、物品至多 5 条、势力至多 3 条（优先记录对后续剧情最重要的），")
                .append("status 每条一句话概括结论，严禁长段铺陈——细节正文里有，账本只要结论；")
                .append("\n5. evidence：characterStates/itemStates/factionStates 的每条状态必须附 evidence 字段——")
                .append("从本章正文逐字摘录、直接支持该状态的连续原句片段（≤50 字），严禁改写、拼接或概括；")
                .append("无法给出原文证据的状态不要输出该条；")
                .append("\n6. foreshadowingNew：本章新埋下、尚未兑现的伏笔，写明角色与具体内容；")
                .append("【格式强制】foreshadowingNew 必须是字符串数组，每项只是一句伏笔的纯文本描述，")
                .append("如 [\"主角乙发现剑冢封印松动\", \"角色辛体内魔气即将暴走\"]；")
                .append("严禁在 foreshadowingNew 里输出对象结构；含 content/excerpt/importance 的对象只能出现在 foreshadowSeeds。")
                .append("伏笔必须是读者可感知的明确悬念或承诺——纯氛围渲染、场景点缀、无悬念指向的描写不得登记为伏笔；")
                .append("与【此前埋设、尚未回收的伏笔账】实质重复的内容（同一悬念或同一人物势头的换句重述）严禁再次登记；")
                .append("同一条内容严禁同时出现在 foreshadowingNew 与 foreshadowingResolved；")
                .append("同时为每条新埋伏笔在 foreshadowSeeds 中登记：content 与 foreshadowingNew 对应条目一致，")
                .append("excerpt 为该伏笔埋设处的正文原文引用，必须是本章正文中的连续原文（≤80 字），严禁改写或拼接；")
                .append("importance 为该伏笔对主线的重要度评分（1-5）：1=纯氛围/路人闲聊，2=小钩子/装备支线，")
                .append("3=势力/地图级布局，4=足以改变一段剧情走向的转折，5=整卷级核心谜题（一批至多 1-2 条）；")
                .append("大多数伏笔应为 2-4 分，评分影响回收调度优先级，必须克制客观，每章评 5 分的伏笔至多 1 条；")
                .append("同时必须为 seed 登记 resolvable（布尔值），判断**这条内容是否承担「兑现义务」**")
                .append("（系统据此区分「要养要收的伏笔」与「人物状态/氛围点缀」）：")
                .append("resolvable=true —— 有人**承诺/约定**了什么（「答应打银锁」「约定开春来验证」），")
                .append("或出现了**未解释的物件/信息**（「夹层里一张来源未明的纸」「那家公司没再被提起」）；")
                .append("resolvable=false —— 只是**人物的状态判断**（「父母觉得孩子邪性」）、")
                .append("**氛围描写**（「张阿婆说小孩子眼睛干净」）或**当时的事实陈述**（「存折加棺材本刚够八千」）；")
                .append("拿不准时填 true（宁可多算，不要漏掉真伏笔）。")
                .append("⚠️ resolvable=false 的条目**不应出现在 foreshadowingNew 里**（那里只登记真伏笔），")
                .append("本字段主要用于纠正历史数据与边界情况；")
                .append("同时为有明确谜底的伏笔（importance≥2）在 seed 中登记 payoffHints（3-8 条，每条 ≤20 字）：")
                .append("该伏笔的谜底/答案关键词——揭示时才会出现的人名、真相短语或关键道具名，")
                .append("如 [\"魔气源头是宗主\",\"血玉实为封印钥匙\"]；")
                .append("系统会在揭示前的所有章节机械拦截这些词，防止谜底提前泄露，因此只登记揭示后才该出现的词，")
                .append("严禁把伏笔本身的前题词（埋设章已在用的词）登记进来；纯氛围伏笔可省略；")
                .append("正确示例：\"foreshadowingNew\":[\"剑冢封印松动\"],\"foreshadowSeeds\":[{\"content\":\"剑冢封印松动\",\"excerpt\":\"…\",\"importance\":4}]；")
                .append("错误示例：\"foreshadowingNew\":[{\"content\":\"剑冢封印松动\"}]（对象不能放进 foreshadowingNew）。")
                .append("\n7. foreshadowingResolved：本章兑现的伏笔，只能从【此前埋设、尚未回收的伏笔账】中选取。")
                .append("**账中每条都带 [F章号-序号] 形式的 ID——登记时回抄该 ID（如 \"[F7-2]\"），")
                .append("ID 是最可靠的销账凭据（机械层按 ID 精确核销，不会因措辞差异漏账）。**")
                .append("账中没有 ID 的旧条目才逐字沿用账中描述——账本按文本核对销账。")
                .append("\n   ⚠️ **这是一项义务，不是可选项**：凡账中某条线在本章产生了它要的那个结果——")
                .append("谁因此知道了什么、谁做了那个决定、什么被确认或被公开、什么东西被交出或被销毁——")
                .append("**必须逐条登记**。漏记的后果不是「少记一笔」：该线永远关不掉账，")
                .append("会在后续每一段被反复要求回收，把同一件事重写很多遍。")
                .append("\n   判断口径：看**状态是否变化**，不是看物件是否出现。"
                        + "「格子纸又出现在桌上」不算兑现；「父亲说出我不问了、等他自己开口」算关系/认知变化。")
                .append("\n   ✅ 正确一（首选）：[\"[F7-2]\"]——回抄账中的 ID，最可靠；")
                .append("\n   ✅ 正确二（账中无 ID 时）：[\"角色甲与角色乙就2026达成不再追问的默契\"]（账中原话，逐字沿用）")
                .append("\n   ❌ 错误一：用自己的话复述事件经过（如「父亲把纸放进铁盒」）——无 ID 时账本按文本匹配，对不上就等于没登记；")
                .append("\n   ❌ 错误二：因为「这条线埋得太久了、像是老账」就留空——老账恰恰最需要登记，")
                .append("它正是被反复催收的那一批；")
                .append("\n   ❌ 错误三：把本章**新埋**的线写进这里（新埋的归 foreshadowingNew）；")
                .append("\n   确实一条都没兑现时，才输出空数组；")
                .append("\n8. timePoint：本章结束时的**时间**（只写时间不写地点），如\"外门大比当日深夜\"；")
                .append("⚠️ **现实年代题材（故事时间锚定在真实年份）必须写出年份**，格式\"YYYY年X月X日\"，")
                .append("如\"2010年3月1日上午\"；只写\"三月一号\"会让后续所有章节的年份锚失效——")
                .append("实测第 131-140 章摘要全部只写月份，时序锚取不到年份，规划层据此臆造出 2013 年")
                .append("（而正文实际是 2010 年），年份链整段错位。")
                .append("若本章未推进日期，写\"YYYY年X月X日（承接上章同日）\"；")
                .append("境界纪年等非现实题材不受此限，沿用原口径。")
                .append("\n9. placePoint：本章结束时主角所在的**地点**（只写地点不写时间），")
                .append("用 2-12 字的稳定地名，如\"主角甲小院\"\"后山北坡第三号防御节点\"；")
                .append("**同一地点在不同章必须用完全一致的写法**（严禁\"阵法堂\"与\"阵法堂内\"、")
                .append("\"天机阁地下密室\"与\"天机阁密室\"混用）——该字段用于统计地点轨迹，")
                .append("写法漂移会使同一地点被计成多个；场景确实迁移时才换新地名。")
                .append("\n10. continuityConflicts：对照下方当前账本，找出本章正文中与账本矛盾之处")
                .append("（位置瞬移、伤势凭空消失、修为倒退、死者复活等），逐条描述；无冲突则为空数组；")
                .append("\n11. cultivationRealm：本章结束时主角的真实境界（如\"炼气三层\"）。")
                .append("对照本章计划的关键事件：若不含突破/晋升类事件，必须与账本中主角当前境界保持一致，")
                .append("严禁拔高；若本章确有突破，境界变更必须有过程铺垫与代价。")
                .append("\n12. consistencyFacts：只提取正文明确出现且后续需要保持一致的事实，每条字段为 type/subject/value/scope/evidence。")
                .append("type 仅允许 TIMELINE、INJURY、TERM、NUMBER、RELATION；TERM 用 subject 写规范名称、value 写正文别名或空；")
                .append("NUMBER 用 subject 写数字含义、value 写数值、scope 写所属对象；")
                .append("**主角的年龄/月龄只要正文提及就必须提取为 NUMBER（subject 写成「人物名+月龄/年龄」，scope 写人物名）**——")
                .append("它是后续所有章节的年龄锚点（时序锚的唯一数据源）；正文若出现「满周岁」「一岁半」等换算表述，")
                .append("按原文措辞提取即可，不要自行换算；")
                .append("RELATION 用 subject 写「A与B」（双方名，全书顺序一致）、value 写**变化后**的当前关系态")
                .append("（如「结盟」「决裂」「师徒，已生裂痕」）——仅当本章正文中关系**发生变化或首次确立**时才提取，")
                .append("关系未变的章节严禁重复上报；")
                .append("evidence 必须是正文连续原句（≤50 字）。")
                .append("\n不要输出 Markdown，不要输出解释。");
        if (StringUtils.isNotBlank(ledgerPrompt)) {
            sb.append("\n\n【当前账本（正文创作前的事实基准）】\n").append(ledgerPrompt);
        }
        if (pendingForeshadowing != null && !pendingForeshadowing.isEmpty()) {
            sb.append("\n\n【此前埋设、尚未回收的伏笔账】\n");
            for (String foreshadow : pendingForeshadowing) {
                sb.append("- ").append(foreshadow).append("\n");
            }
        }
        sb.append("\n\n【本章计划】")
                .append("\n标题：").append(nullToBlank(item.getTitle()))
                .append("\n目标：").append(nullToBlank(item.getGoal()))
                .append("\n关键事件：").append(item.getKeyEvents() == null ? "" : String.join("、", item.getKeyEvents()))
                .append("\n结尾悬念：").append(nullToBlank(item.getEndingHook()));
        // 时间锚（2026-10-13）：timePoint 强制带年份后，模型必须知道**当前故事年份**才能写对。
        // 不给锚时它会从正文里的零散线索猜——实测第 136 章正文只写"三月十七号"，摘要便填成
        // "2010年3月17日"（正典是 2011 年）；该值入库即成为下一章的时序锚，错位会自我固化。
        if (historyForAnchor != null && !historyForAnchor.isEmpty()) {
            String anchor = ConsistencyIndexService.renderTimeAnchor(historyForAnchor);
            if (StringUtils.isNotBlank(anchor)) {
                sb.append("\n\n").append(anchor);
            }
        }
        sb.append("\n\n【本章正文】")
                .append("\n").append(nullToBlank(content))
                .append("\n\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat());
        return sb.toString();
    }

    /** 谜底关键词单条长度上限（超出视为整句而非关键词，剔除防机械误杀） */
    private static final int PAYOFF_HINT_MAX_LENGTH = 20;
    /** 谜底关键词单条最短长度（过短词会大面积误伤正常叙事） */
    private static final int PAYOFF_HINT_MIN_LENGTH = 3;
    /** 谜底关键词条数上限 */
    private static final int PAYOFF_HINT_MAX_COUNT = 8;

    /**
     * 伏笔种子防编造校验：excerpt 必须是当章正文内的一段连续原文（经 {@link EvidenceMatch} 归一化校验）
     * 且不超过 80 字，否则置空——模型引用不可信，宁缺勿滥。
     * payoffHints 同步清洗：过滤空/超长/过短条目与和伏笔描述互为包含的"前题词"（拦截它会误伤悬念本身）
     */
    private void validateForeshadowSeeds(ChapterSummaryEntity summary, String content) {
        if (summary.getForeshadowSeeds() == null) {
            return;
        }
        // 正文归一化一次（引号一律统一为半角双引号 + 去空白），供各条 excerpt 复用
        String normalizedContent = EvidenceMatch.normalize(content);
        for (ChapterSummaryEntity.SeedEntry seed : summary.getForeshadowSeeds()) {
            if (seed == null) {
                continue;
            }
            String excerpt = seed.getExcerpt();
            if (StringUtils.isNotBlank(excerpt)) {
                excerpt = EvidenceMatch.truncate(excerpt, SEED_EXCERPT_MAX_LENGTH);
                // 引用非当章正文（编造/漂移）则置空，content 保留用于账本匹配。
                // 收编：此前是裸 content.indexOf——模型换了引号形态
                // （本故事 ch3/ch4 正文通篇半角引号）就会把真实引用误清空；
                // 现走 EvidenceMatch，只放宽标点/空白差异，编造引用仍拒收。
                seed.setExcerpt(StringUtils.isNotBlank(excerpt)
                        && EvidenceMatch.contained(excerpt, normalizedContent) ? excerpt : null);
            }
            sanitizePayoffHints(seed);
        }
        summary.setForeshadowSeeds(summary.getForeshadowSeeds().stream()
                .filter(seed -> seed != null && StringUtils.isNotBlank(seed.getContent()))
                .toList());
        assignForeshadowIds(summary);
    }

    /**
     * 为本章伏笔种子分配**稳定 ID**（2026-10-13）：格式 {@code F{埋设章号}-{序号}}。
     *
     * <p><b>为什么在机械层分配</b>：ID 是记账凭据，模型的职责是语义判断（这是不是伏笔、
     * 算不算已兑现），记账归机械层——与 excerpt 子串校验、账本核销同一分工。
     * 模型只需在回收时回抄 ID，机械层做集合差集，不再依赖措辞匹配。
     *
     * <p><b>为什么格式取"章号-序号"而非全局自增</b>：它同时编码了埋设章（账本排序与滞留计算的依据）
     * 与本章内顺序，且**天然幂等**——同一章重跑摘要会得到同样的 ID，不会因重试而产生 ID 漂移。
     *
     * <p>已带 ID 的条目不重编（续写/重试场景保持稳定）；老数据无 ID 时下游全部回退文本匹配。
     */
    private void assignForeshadowIds(ChapterSummaryEntity summary) {
        if (summary.getChapterNo() == null || summary.getForeshadowSeeds() == null) {
            return;
        }
        int seq = 0;
        for (ChapterSummaryEntity.SeedEntry seed : summary.getForeshadowSeeds()) {
            if (seed == null) {
                continue;
            }
            seq++;
            if (StringUtils.isBlank(seed.getForeshadowId())) {
                seed.setForeshadowId("F" + summary.getChapterNo() + "-" + seq);
            }
        }
    }

    /** 谜底关键词清洗：长度 3-20 字、去重封顶 8 条、剔除与伏笔描述互为包含的条目（前题词） */
    private void sanitizePayoffHints(ChapterSummaryEntity.SeedEntry seed) {
        if (seed.getPayoffHints() == null || seed.getPayoffHints().isEmpty()) {
            return;
        }
        String content = StringUtils.trimToEmpty(seed.getContent());
        List<String> cleaned = seed.getPayoffHints().stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .filter(h -> h.length() >= PAYOFF_HINT_MIN_LENGTH && h.length() <= PAYOFF_HINT_MAX_LENGTH)
                .filter(h -> content.isEmpty() || (!content.contains(h) && !h.contains(content)))
                .distinct()
                .limit(PAYOFF_HINT_MAX_COUNT)
                .toList();
        seed.setPayoffHints(cleaned.isEmpty() ? null : cleaned);
    }

    /**
     * 回收清单的机械兜底：去空白、去重，并统计有多少条**在待收账本里找不到**（措辞漂移）。
     *
     * <p><b>刻意不丢弃未命中条目</b>：真实兑现的措辞可能与账本不完全一致（账本核销用的是
     * "互相包含"口径，见 {@code ChapterMemoryService.buildPendingForeshadowDetailed}），
     * 误删等于永久丢掉一次回收——而回收一旦丢失，该线就再也不会关账。
     * 未命中只作**观测信号**：它是"摘要把事件经过写成了自己的话、而不是逐字沿用账中描述"的
     * 早期迹象，与体检的「清账静默兑现」同源（那边度量的是同一问题的存量）。
     */
    private List<String> sanitizeResolved(List<String> resolved, List<String> pendingForeshadowing) {
        if (resolved == null || resolved.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = resolved.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        if (pendingForeshadowing != null && !pendingForeshadowing.isEmpty()) {
            long matched = cleaned.stream().filter(r -> matchesLedger(r, pendingForeshadowing)).count();
            if (matched == 0) {
                log.warn("本章回收清单 {} 条在待收账本里一条都没匹配上（措辞漂移？）——账本按文本核销，"
                        + "对不上就不会关账，该线会被反复要求回收：{}", cleaned.size(), cleaned);
                // 结构化留痕（2026-10-13）：这是"账本按文本核销"这条设计的漏损计量点，
                // 只写日志无法聚合统计——伏笔结构化 ID 改造的效果验证依赖这个数字
                lastLedgerMismatch = new LedgerMismatch(cleaned.size(),
                        pendingForeshadowing.size(), List.copyOf(cleaned));
            } else {
                lastLedgerMismatch = null;
            }
        } else {
            lastLedgerMismatch = null;
        }
        return cleaned;
    }

    /**
     * 最近一次 {@code sanitizeResolved} 的失配事实（null = 未发生失配）。
     *
     * <p>用字段而非返回值传递：{@code sanitizeResolved} 在 {@code summarize} 内部被调用，
     * 改动其签名会波及既有调用点；本字段只由调用方在 {@code summarize} 返回后读取一次，
     * 语义是"本次摘要是否发生了账本失配"，单线程逐章生成下无竞争。
     */
    private volatile LedgerMismatch lastLedgerMismatch;

    /** 账本失配事实：登记回收条数 + 当时账本待收条数 + 登记的原文清单 */
    public record LedgerMismatch(int resolvedCount, int pendingCount, List<String> resolved) {
    }

    /** 取出并清空最近一次失配事实（供调用方落盘后即忘） */
    public LedgerMismatch consumeLedgerMismatch() {
        LedgerMismatch mismatch = lastLedgerMismatch;
        lastLedgerMismatch = null;
        return mismatch;
    }

    /**
     * 与账本核销同口径：**ID 命中优先，文本互相包含回退**。
     *
     * <p>2026-10-13 起账本清单带 {@code [F7-2] } 前缀（见
     * {@code ChapterMemoryService.buildForeshadowContextListWithIds}），故：
     * <ul>
     *   <li>登记值是 ID 形态（{@code [F7-2]} / {@code F7-2}）时——与账本条目的 ID 前缀精确比对；</li>
     *   <li>否则剥掉账本行的 ID 前缀后走既有双向 contains。</li>
     * </ul>
     * 不剥离前缀直接比对会让**所有**文本回退路径都失配（账本行现在以 "[" 开头），
     * 把失配告警刷成噪声、并掩盖真实的措辞漂移。
     */
    private static boolean matchesLedger(String resolved, List<String> pendingForeshadowing) {
        String r = resolved == null ? "" : resolved.trim();
        if (r.isEmpty()) {
            return false;
        }
        String normalizedId = ChapterMemoryService.normalizeForeshadowIdForm(r);
        for (String pending : pendingForeshadowing) {
            if (StringUtils.isBlank(pending)) {
                continue;
            }
            String p = pending.trim();
            String pendingId = ChapterMemoryService.extractForeshadowIdPrefix(p);
            // ID 形态登记：与账本条目的 ID 精确比对（大小写不敏感）
            if (normalizedId != null) {
                if (pendingId != null && pendingId.equalsIgnoreCase(normalizedId)) {
                    return true;
                }
                continue;
            }
            String pBody = ChapterMemoryService.stripForeshadowIdPrefix(p);
            if (pBody.contains(r) || r.contains(pBody)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 重述防膨胀：与【待回收伏笔账】实质重复（任一方向包含且双方达最短字数）的新登记条目剔除——
     * 账本按内容精确匹配，同一悬念的换句重述会反复入账、未填池虚胖。
     * 机械包含匹配是兜底，主要防线是登记纪律（prompt 严禁重述登记）；宁少记不重记
     */
    private void filterRestatedForeshadows(ChapterSummaryEntity summary, List<String> pendingForeshadowing) {
        if (summary.getForeshadowingNew() == null || summary.getForeshadowingNew().isEmpty()
                || pendingForeshadowing == null || pendingForeshadowing.isEmpty()) {
            return;
        }
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        // 账本行可能带 [F7-2] ID 前缀（2026-10-13）：比对前剥离，否则包含匹配恒不成立，
        // 重述过滤会静默失效（换句重述的伏笔将反复入账、未填池虚胖）
        List<String> pendingTrimmed = pendingForeshadowing.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .map(ChapterMemoryService::stripForeshadowIdPrefix)
                .filter(StringUtils::isNotBlank)
                .toList();
        for (String item : summary.getForeshadowingNew()) {
            if (StringUtils.isBlank(item)) {
                continue;
            }
            String candidate = item.trim();
            boolean restated = pendingTrimmed.stream().anyMatch(pending ->
                    Math.min(pending.length(), candidate.length()) >= RESTATED_MIN_CHARS
                            && (pending.contains(candidate) || candidate.contains(pending)));
            if (restated) {
                dropped.add(candidate);
            } else {
                kept.add(item);
            }
        }
        if (dropped.isEmpty()) {
            return;
        }
        summary.setForeshadowingNew(kept);
        // 种子按**剔除后**的 new 同步收缩——否则种子里留着已被判为重述的条目，
        // 而 new 里没有它，两个字段对同一条线给出相反结论（种子侧还以为它是本章新埋）
        if (summary.getForeshadowSeeds() != null) {
            Set<String> keptContents = kept.stream().map(String::trim).collect(Collectors.toSet());
            summary.setForeshadowSeeds(summary.getForeshadowSeeds().stream()
                    .filter(seed -> seed == null || seed.getContent() == null
                            || keptContents.contains(seed.getContent().trim()))
                    .collect(Collectors.toList()));
        }
        log.info("章节摘要剔除重述伏笔 {} 条（与待回收账实质重复），chapterNo: {}",
                dropped.size(), summary.getChapterNo());
    }

    /**
     * **种子 → 新埋清单的无条件补齐**（2026-10-14）：把种子里声明了兑现义务
     * （{@code resolvable=TRUE}）而 {@code foreshadowingNew} 里缺失的条目补进 new。
     * 补齐前先过重述闸：与待回收账（剥离 ID 前缀后）实质重复的种子不补——
     * 那是模型换句重述的账本旧线，{@code filterRestatedForeshadows} 只挡 new 侧，
     * 不挡的话重述会借补齐通道绕过过滤重新入账。
     *
     * <p><b>为什么必须补</b>：下游账本（{@code ChapterMemoryService.buildPendingForeshadowDetailed}）
     * **只读 {@code foreshadowingNew}**，种子读得再多也只服务于原文引用/重要度/ID。
     * 实测第 1-140 章有 133 条种子在 new 里找不到对应条目——其中大部分是 VOID 出账的正常残留，
     * 但仍有真伏笔因此**对账本完全不可见**：不待回收、不注入规划、拿不到 ID、永远不会被安排回收。
     * 模型偶尔只填 seeds 忘填 new（两个字段分别输出），此前无任何校验兜住。
     *
     * <p><b>为什么不反向做</b>（把 new 里缺失的条目补进 seeds）：seed 携带 excerpt/importance/
     * resolvable 等**埋设现场事实**，机械层凭空造不出来；补出的种子只有 content，
     * 反而会污染后续校验。缺 new 是"登记遗漏"，缺 seed 是"证据不足"，两者不对称。
     *
     * <p><b>为什么只补 resolvable=TRUE</b>：prompt 明确要求 {@code resolvable=false} 的条目
     * **不应出现在 new 里**（那是氛围/状态，不承担兑现义务）；补进去会直接违反不变式。
     * {@code resolvable=null}（老数据/模型漏标）同样不补——"宁可漏判不要误判"，
     * 且未标注占比已有专门指标盯着（{@code ForeshadowSpanPolicy.unlabeledRate}）。
     *
     * <p><b>为什么不去重种子与 new 的数量差</b>：VOID 出账只清 new、不清 seeds
     * （见 {@code stripVoidedForeshadows} 的契约——种子保留埋设痕迹供复盘）。
     * 故"seeds 多于 new"是**设计内的常态**，本方法只按内容做集合补齐，不推断缺口来源。
     */
    private void syncSeedsIntoNew(ChapterSummaryEntity summary, List<String> pendingForeshadowing) {
        // 每次调用先清空：未发生补齐时必须显式置 null，否则上一章的补齐事实会被
        // 本章的 consume 读走（字段传递的前提是"每次都有明确赋值"）
        lastSeedNewSync = null;
        if (summary.getForeshadowSeeds() == null || summary.getForeshadowSeeds().isEmpty()) {
            return;
        }
        List<String> newList = summary.getForeshadowingNew() == null
                ? new ArrayList<>() : new ArrayList<>(summary.getForeshadowingNew());
        Set<String> present = newList.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .collect(Collectors.toSet());
        // 与 filterRestatedForeshadows 同款预处理：账本行可能带 [F7-2] ID 前缀，
        // 不剥离的话包含匹配恒不成立，重述种子会借补齐通道入账
        List<String> pendingTrimmed = pendingForeshadowing == null ? List.of()
                : pendingForeshadowing.stream()
                        .filter(StringUtils::isNotBlank)
                        .map(String::trim)
                        .map(ChapterMemoryService::stripForeshadowIdPrefix)
                        .filter(StringUtils::isNotBlank)
                        .toList();
        List<String> added = new ArrayList<>();
        for (ChapterSummaryEntity.SeedEntry seed : summary.getForeshadowSeeds()) {
            if (seed == null || StringUtils.isBlank(seed.getContent())
                    || !Boolean.TRUE.equals(seed.getResolvable())) {
                continue;
            }
            String content = seed.getContent().trim();
            // 与待回收账实质重复 = 重述，直接丢弃（不补、也不留痕——重述不该有兑现义务）
            boolean restated = pendingTrimmed.stream().anyMatch(pending ->
                    Math.min(pending.length(), content.length()) >= RESTATED_MIN_CHARS
                            && (pending.contains(content) || content.contains(pending)));
            if (restated) {
                continue;
            }
            // 双向包含判重（与账本口径一致）：同一悬念的措辞微差不应被当成两条登记。
            // 包含只在较短一侧 ≥ RESTATED_MIN_CHARS 时生效——纯精确相等恒判重；
            // 否则账本里一个四五字的短条目会把含它的完整种子线误判为"已存在"而整条丢掉
            boolean exists = present.stream().anyMatch(p -> p.equals(content)
                    || (Math.min(p.length(), content.length()) >= RESTATED_MIN_CHARS
                            && (p.contains(content) || content.contains(p))));
            if (exists) {
                continue;
            }
            present.add(content);
            added.add(content);
        }
        if (added.isEmpty()) {
            return;
        }
        newList.addAll(added);
        summary.setForeshadowingNew(newList);
        log.info("章节摘要补齐种子到新埋清单 {} 条（模型只填了 foreshadowSeeds），chapterNo: {}，新增：{}",
                added.size(), summary.getChapterNo(), added);
        // 结构化留痕：这是"摘要模型两个字段输出不对称"的量化点——
        // 只写日志无法聚合，无法回答"补进来的条目后来有没有被回收"（ID 改造效果验证依赖它）
        lastSeedNewSync = new SeedNewSync(summary.getChapterNo() == null ? 0 : summary.getChapterNo(),
                added.size(), List.copyOf(added));
    }

    /**
     * 最近一次 {@link #syncSeedsIntoNew} 的补齐事实（null = 未发生补齐）。
     *
     * <p>与 {@link #lastLedgerMismatch} 同款约定：字段传递、调用方在 summarize 返回后读取一次，
     * 单线程逐章生成下无竞争。
     */
    private volatile SeedNewSync lastSeedNewSync;

    /** 种子补 new 的补齐事实：章号 + 补齐条数 + 补齐内容 */
    public record SeedNewSync(int chapterNo, int addedCount, List<String> added) {
    }

    /** 取出并清空最近一次补齐事实（供调用方落盘后即忘） */
    public SeedNewSync consumeSeedNewSync() {
        SeedNewSync sync = lastSeedNewSync;
        lastSeedNewSync = null;
        return sync;
    }

    /**
     * 状态事实防编造校验（证据驱动账本的第一道门）：evidence 必须能在当章正文中被定位。
     * 判定走 EvidenceMatch 统一分档（逐字 → 归一化 → 省略号分段 → 短语覆盖），
     * 用于吸收模型惯用的「...」压缩引用与引号/空白差异；完全定位不到的仍移出三账本状态、
     * 转入 pendingFacts（待裁决，不入账本前缀，随 summaries 落盘）。
     * 未填 evidence（降级路径/模型未遵从）→ <b>同样转入 pendingFacts 待裁决</b>（2026-09-16 修正：
     * 原实现直接入账，等于"不写引文就绕过整道门"，且物品/势力条目因缺 sourceAccount 会在
     * 裁决回写时被错写到角色账本）。本方法只作用于本章新生成的摘要，不回溯历史数据，
     * 故不存在"旧摘要被二次过滤"的兼容问题
     * 宁可少记一条存疑事实，不可让编造事实污染账本
     */
    private void validateStateEvidence(ChapterSummaryEntity summary, String content) {
        List<ChapterSummaryEntity.StateEntry> pending = new ArrayList<>();
        Map<String, Integer> tierCounts = new LinkedHashMap<>();
        int missing = 0;
        missing += filterVerifiedStates(summary.getCharacterStates(), content, pending, tierCounts,
                ChapterSummaryEntity.AccountKind.CHARACTER);
        missing += filterVerifiedStates(summary.getItemStates(), content, pending, tierCounts,
                ChapterSummaryEntity.AccountKind.ITEM);
        missing += filterVerifiedStates(summary.getFactionStates(), content, pending, tierCounts,
                ChapterSummaryEntity.AccountKind.FACTION);
        summary.setPendingFacts(pending.isEmpty() ? null : pending);
        if (missing > 0) {
            log.warn("章节摘要存在未附证据的状态 {} 条（已转入挂起层待裁决，不入账本），chapterNo: {}",
                    missing, summary.getChapterNo());
        }
        if (!tierCounts.isEmpty()) {
            log.info("章节摘要状态证据分档，chapterNo: {}，入账/隔离档位分布 {}", summary.getChapterNo(), tierCounts);
        }
    }

    /**
     * 逐条校验并就地分割：evidence 可在正文定位的保留在原列表，无法定位的移入 pending，返回未附证据条数。
     * tierCounts 累计全部条目的命中档位（含入账档），供观测与复盘；accountKind 记入隔离条目的来源账本，
     * 供裁决层把救回的事实写回原账本（挂起层是三账本混合的扁平列表，不记来源就会写错账）
     */
    private int filterVerifiedStates(List<ChapterSummaryEntity.StateEntry> entries, String content,
                                     List<ChapterSummaryEntity.StateEntry> pending,
                                     Map<String, Integer> tierCounts,
                                     ChapterSummaryEntity.AccountKind accountKind) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        List<ChapterSummaryEntity.StateEntry> kept = new ArrayList<>();
        int missing = 0;
        for (ChapterSummaryEntity.StateEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (StringUtils.isBlank(entry.getEvidence())) {
                missing++;
                // 未附证据**不再直接入账** 修正）：原实现沿用旧规则直接 kept，
                // 等于留了一条后门——模型只要不写引文，角色/物品/势力状态就全部绕过反编造门，
                // 且裁决层永远看不到它们。现转入挂起层待 L1 裁决：
                //  · sourceAccount 必须记——挂起层是三账本混合的扁平列表，不记来源，
                //    裁决回写会按 CHARACTER 兜底，物品/势力状态会被错写到角色账本
                //  · evidenceTier 刻意留空——adjudicable 对空档位放行，裁决层会拟稿并经
                //    EvidenceMatch 复核，救回的仍是"可机械定位"的事实，而不是无条件放行
                // 这与"宁可少记一条存疑事实，不可让编造事实污染账本"一致：
                // 裁决关闭时它们留在挂起层（账本完整度指标会如实反映），而不是混进账本
                entry.setSourceAccount(accountKind.getCode());
                pending.add(entry);
                continue;
            }
            entry.setEvidence(EvidenceMatch.truncate(entry.getEvidence(), EvidenceMatch.STATE_EVIDENCE_MAX_LENGTH));
            EvidenceMatch.Tier tier = EvidenceMatch.classify(entry.getEvidence(), content);
            tierCounts.merge(tier.getCode(), 1, Integer::sum);
            if (tier.isAccepted()) {
                if (tier.isLoose()) {
                    // 放宽留痕档（跨章引用 / 单条短语锚定）虽入账，仍写回档位供观测层统计占比
                    entry.setEvidenceTier(tier.getCode());
                }
                kept.add(entry);
            } else {
                // 引用无法在当章正文定位 → 隔离待裁决，不入账本；档位留痕供裁决层分流
                entry.setEvidenceTier(tier.getCode());
                entry.setSourceAccount(accountKind.getCode());
                pending.add(entry);
            }
        }
        entries.clear();
        entries.addAll(kept);
        return missing;
    }

    /**
     * 一致性事实防编造校验：与状态账本同一套 EvidenceMatch 分档口径（历史上此处用裸 contains，
     * 与状态账本的 indexOf 各自为政，现统一）。空证据直接隔离——一致性事实无证据即不可采信
     */
    private void validateConsistencyEvidence(ChapterSummaryEntity summary, String content) {
        List<ChapterSummaryEntity.ConsistencyFact> facts = summary.getConsistencyFacts();
        if (facts == null || facts.isEmpty()) return;
        List<ChapterSummaryEntity.ConsistencyFact> kept = new ArrayList<>();
        List<ChapterSummaryEntity.ConsistencyFact> pending = new ArrayList<>();
        Map<String, Integer> tierCounts = new LinkedHashMap<>();
        for (ChapterSummaryEntity.ConsistencyFact fact : facts) {
            if (fact == null || StringUtils.isBlank(fact.getEvidence())) {
                pending.add(fact);
                continue;
            }
            fact.setEvidence(EvidenceMatch.truncate(fact.getEvidence(), EvidenceMatch.STATE_EVIDENCE_MAX_LENGTH));
            EvidenceMatch.Tier tier = EvidenceMatch.classify(fact.getEvidence(), content);
            tierCounts.merge(tier.getCode(), 1, Integer::sum);
            if (tier.isAccepted()) {
                if (tier.isLoose()) {
                    fact.setEvidenceTier(tier.getCode());
                }
                kept.add(fact);
            } else {
                fact.setEvidenceTier(tier.getCode());
                pending.add(fact);
            }
        }
        summary.setConsistencyFacts(kept);
        summary.setPendingConsistencyFacts(pending.isEmpty() ? null : pending);
        if (!pending.isEmpty()) {
            log.info("章节摘要一致性事实证据校验未通过 {} 条，已隔离待裁决，chapterNo: {}，档位分布 {}",
                    pending.size(), summary.getChapterNo(), tierCounts);
        }
    }

    /**
     * **数值型事实的机械兜底**：正文里出现了带经济/计量单位的数字，而本章一致性事实里
     * 没有任何 NUMBER 条目覆盖它时，按正文原句回填一条。
     *
     * <p><b>治什么</b>：摘要模型偶尔整章不输出 {@code consistencyFacts}（实测某章正文明确写了
     * 「七块二跌到三块出头」并据此做了持币决策，但该章 facts 为空）——于是这条最关键的数值锚
     * 在账本里不存在，后续章节的写手看不到它，只能自己编一个量级（实测后文把同一资产
     * 写成「十七块跌到九块」，与原文的「三块出头」差了一个量级，且此后这个错值反而成了正典）。
     *
     * <p><b>为什么只兜底"经济/计量"类数字</b>：普通叙述里的数字（年份、年龄、楼层、页码）
     * 本就大量出现，全量回填会把账本灌满噪声。限定在带单位且单位属于价格/数量/金额这一族，
     * 才能既覆盖"资产价格/持仓/金额"这类真正需要跨章一致的事实，又不误伤日常叙述。
     *
     * <p><b>为什么回填而不只是告警</b>：告警只在日志里，下一章照样看不到；回填后它会进入
     * 一致性索引与三账本，真正参与后续章节的生成。证据取正文原句，天然通过 EvidenceMatch。
     *
     * <p>只补"未覆盖"的：本章已有 NUMBER 事实时，说明模型已做登记，机械层不重复也不改写
     * （改写会覆盖模型更准确的语义归类，比如它可能已把该数字归到更贴切的 subject 下）。
     */
    private void backfillNumericFacts(ChapterSummaryEntity summary, String content) {
        if (StringUtils.isBlank(content)) {
            return;
        }
        List<ChapterSummaryEntity.ConsistencyFact> facts = summary.getConsistencyFacts() == null
                ? new ArrayList<>() : new ArrayList<>(summary.getConsistencyFacts());
        boolean hasNumberFact = facts.stream().anyMatch(f -> f != null
                && "NUMBER".equalsIgnoreCase(nullToBlank(f.getType()).trim()));
        if (hasNumberFact) {
            return;
        }
        List<String> sentences = splitSentences(content);
        List<ChapterSummaryEntity.ConsistencyFact> added = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i).trim();
            if (sentence.length() < MIN_NUMERIC_FACT_SENTENCE) {
                continue;
            }
            // matcher 每次调用都是新对象：必须持有同一个 matcher 才能 find() 后取 group()
            java.util.regex.Matcher numeric = NUMERIC_WITH_UNIT.matcher(sentence);
            if (!numeric.find()) {
                continue;
            }
            // 经济语境允许出现在**本句或相邻句**：真实对话里价格常与"这是什么"分开说
            //（实测事故原句：「七块二跌到三块出头。」角色甲把电子词典推过来——本句无经济词）。
            // 窗口取 ±1 句，既能覆盖"上一句交代行情、下一句报数字"的常见写法，又不至于把整章都算进来。
            if (!hasEconomicContextNear(sentences, i)) {
                continue;
            }
            String value = numeric.group();
            if (!seen.add(value)) {
                continue;
            }
            ChapterSummaryEntity.ConsistencyFact fact = new ChapterSummaryEntity.ConsistencyFact();
            fact.setType("NUMBER");
            fact.setSubject(economicSubjectNear(sentences, i));
            fact.setValue(value);
            fact.setScope("本章");
            fact.setEvidence(sentence.length() > EvidenceMatch.STATE_EVIDENCE_MAX_LENGTH
                    ? sentence.substring(0, EvidenceMatch.STATE_EVIDENCE_MAX_LENGTH) : sentence);
            added.add(fact);
            if (added.size() >= MAX_NUMERIC_FACTS_PER_CHAPTER) {
                break;
            }
        }
        if (added.isEmpty()) {
            return;
        }
        facts.addAll(added);
        summary.setConsistencyFacts(facts);
        log.info("章节摘要数值事实兜底回填 {} 条（模型未登记任何 NUMBER 事实，按正文原句补齐），chapterNo: {}，新增：{}",
                added.size(), summary.getChapterNo(),
                added.stream().map(f -> f.getSubject() + "=" + f.getValue()).toList());
    }

    /** 数值兜底：句长下限（太短的句子容易只有数字没有语义主体） */
    private static final int MIN_NUMERIC_FACT_SENTENCE = 8;
    /** 单章最多回填条数：防止一段行情罗列刷满账本 */
    private static final int MAX_NUMERIC_FACTS_PER_CHAPTER = 5;

    /**
     * 带单位的数字：价格/数量/金额。刻意只认这些单位——年份、年龄、楼层等日常数字不进账本。
     *
     * <p>覆盖两种口语形态：① {@code 17块 / 3.5元 / 两百枚}；② {@code 七块二 / 三块出头}
     * （中文价格常用"块"后接角位或模糊后缀）。第二类必须单独认，否则「七块二跌到三块出头」
     * 这种最典型的价格叙述会被整句漏掉——实测事故原文正是这个形态。
     */
    private static final java.util.regex.Pattern NUMERIC_WITH_UNIT = java.util.regex.Pattern.compile(
            "(?:[0-9]+(?:\\.[0-9]+)?|[一二三四五六七八九十百千两几]+(?:十|百|千)?)\\s*(?:块|元|美元|枚|股|万|亿)"
                    + "(?:[一二三四五六七八九]|出头|多|左右|上下)?");

    /**
     * 经济语义上下文：句子必须同时出现这些词之一，才认定为"需要跨章一致的数值事实"。
     * 与 {@link #NUMERIC_WITH_UNIT} 是**与**关系——单有数字（"走了三里路"）或单有经济词
     * （"他说起行情"）都不够，避免把无关叙述写进账本。
     *
     * <p>判定窗口见 {@link #hasEconomicContextNear}：允许落在相邻句，因为真实对话里
     * 价格数字与"这是什么"常常分属两句。
     */
    private static final java.util.regex.Pattern ECONOMIC_CONTEXT = java.util.regex.Pattern.compile(
            "某资产|行情|价格|币价|股价|仓位|持仓|补仓|建仓|清仓|割肉|亏损|盈利|本金|市值|估值|融资|报价|跌到|涨到");

    /** 数值句的经济语境判定窗口：本句与前后各一句 */
    private static final int ECONOMIC_CONTEXT_WINDOW = 1;

    /** 在 {@code index} 的 ±{@link #ECONOMIC_CONTEXT_WINDOW} 句范围内找经济语境 */
    private static boolean hasEconomicContextNear(List<String> sentences, int index) {
        int from = Math.max(0, index - ECONOMIC_CONTEXT_WINDOW);
        int to = Math.min(sentences.size() - 1, index + ECONOMIC_CONTEXT_WINDOW);
        for (int i = from; i <= to; i++) {
            if (ECONOMIC_CONTEXT.matcher(sentences.get(i)).find()) {
                return true;
            }
        }
        return false;
    }

    /** 取窗口内首个经济语境词作为 subject；取不到时用通用标签（已通过语境判定，必有） */
    private static String economicSubjectNear(List<String> sentences, int index) {
        int from = Math.max(0, index - ECONOMIC_CONTEXT_WINDOW);
        int to = Math.min(sentences.size() - 1, index + ECONOMIC_CONTEXT_WINDOW);
        for (int i = from; i <= to; i++) {
            java.util.regex.Matcher m = ECONOMIC_CONTEXT.matcher(sentences.get(i));
            if (m.find()) {
                return m.group();
            }
        }
        return "数值";
    }

    /** 按句末符与换行切句（与 StyleStatService 同口径，供数值兜底逐句扫描） */
    private static List<String> splitSentences(String text) {
        return java.util.Arrays.stream(text.split("[。！？…\\n]+"))
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .toList();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

}
