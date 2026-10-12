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
        String lastRaw = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // maxTokens/temperature 不再硬编码，由 scene-models 的 summary 场景配置驱动
                String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                        .userPrompt(buildPrompt(item, content, globalNo, ledgerPrompt, pendingForeshadowing))
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
                    summary.setForeshadowingResolved(
                            sanitizeResolved(summary.getForeshadowingResolved(), pendingForeshadowing));
                    validateStateEvidence(summary, content);
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
        String summary = JsonRepair.extractStringField(raw, "summary");
        if (summary == null) {
            return null;
        }
        return ChapterSummaryEntity.builder()
                .chapterNo(globalNo)
                .title(title)
                .summary(summary)
                .build();
    }

    private String buildPrompt(ChapterPlanItemEntity item, String content, int globalNo,
                               String ledgerPrompt, List<String> pendingForeshadowing) {
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
                .append("如 [\"陈长安发现剑冢封印松动\", \"苏挽体内魔气即将暴走\"]；")
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
                .append("\n7. foreshadowingResolved：本章兑现的伏笔，只能从【此前埋设、尚未回收的伏笔账】中选取，")
                .append("条目必须逐字沿用账中描述，严禁改写——账本按文本核对销账。")
                .append("\n   ⚠️ **这是一项义务，不是可选项**：凡账中某条线在本章产生了它要的那个结果——")
                .append("谁因此知道了什么、谁做了那个决定、什么被确认或被公开、什么东西被交出或被销毁——")
                .append("**必须逐条登记**。漏记的后果不是「少记一笔」：该线永远关不掉账，")
                .append("会在后续每一段被反复要求回收，把同一件事重写很多遍。")
                .append("\n   判断口径：看**状态是否变化**，不是看物件是否出现。"
                        + "「格子纸又出现在桌上」不算兑现；「父亲说出我不问了、等他自己开口」算关系/认知变化。")
                .append("\n   ✅ 正确：[\"陆远山与周秀兰就2026达成不再追问的默契\"]（这是账中那条线的原话，逐字沿用）")
                .append("\n   ❌ 错误一：用自己的话复述事件经过（如「父亲把纸放进铁盒」）——账本按文本匹配，对不上就等于没登记；")
                .append("\n   ❌ 错误二：因为「这条线埋得太久了、像是老账」就留空——老账恰恰最需要登记，")
                .append("它正是被反复催收的那一批；")
                .append("\n   ❌ 错误三：把本章**新埋**的线写进这里（新埋的归 foreshadowingNew）；")
                .append("\n   确实一条都没兑现时，才输出空数组；")
                .append("\n8. timePoint：本章结束时的**时间**（只写时间不写地点），如\"外门大比当日深夜\"；")
                .append("\n9. placePoint：本章结束时主角所在的**地点**（只写地点不写时间），")
                .append("用 2-12 字的稳定地名，如\"林尘小院\"\"后山北坡第三号防御节点\"；")
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
                .append("\n结尾悬念：").append(nullToBlank(item.getEndingHook()))
                .append("\n\n【本章正文】")
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
            }
        }
        return cleaned;
    }

    /** 与账本核销同口径：互相包含即视为同一条线 */
    private static boolean matchesLedger(String resolved, List<String> pendingForeshadowing) {
        String r = resolved == null ? "" : resolved.trim();
        if (r.isEmpty()) {
            return false;
        }
        for (String pending : pendingForeshadowing) {
            if (StringUtils.isBlank(pending)) {
                continue;
            }
            String p = pending.trim();
            if (p.contains(r) || r.contains(p)) {
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
        List<String> pendingTrimmed = pendingForeshadowing.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
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

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

}
