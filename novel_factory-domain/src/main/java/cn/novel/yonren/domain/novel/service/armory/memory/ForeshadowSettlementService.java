package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.service.armory.quality.ForeshadowSpanPolicy;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 卷末清账服务：阶段末章完成后，对本阶段出口长期未填的伏笔逐条 LLM 裁决——
 * RECOVER（能自然融入后续剧情，结转下一阶段前段限期回收）或 VOID（剧情已走远，弃置出账不再追踪）。
 * 未填伏笔的语义是"剧情走远的闲笔"，裁决默认弃置：想不出自然连接点一律 VOID，严禁为清账强行回收。
 * 清账是增强件：解析失败/条数不齐/异常整体返回 null，调用方保持未填冻结（fail-soft），不阻断生成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForeshadowSettlementService {

    private static final BeanOutputConverter<SettlementOutput> CONVERTER = new BeanOutputConverter<>(
            SettlementOutput.class,
            JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    private static final String SETTLE_SYSTEM_PROMPT = """
            你是小说流水线的卷末清账审计员，负责对阶段出口仍冻结的未填伏笔（埋下后长期未被剧情触达的旧坑）逐条裁决。
            你是审计不是创作者：只依据给定的章节记忆判定，不脑补剧情外的可能性。
            未填伏笔的语义是"剧情已经走远的闲笔"——对**没有逾期标注**的条目，裁决默认弃置（VOID）；
            只有当能明确说出该伏笔与后续剧情的自然连接点时，才判限期回收（RECOVER）。
            存疑一律 VOID。严禁为了清账而强行回收——强行回收会导致章节注水。

            ⚠️ 两种**不是闲笔**的特殊情况，必须区别对待：

            一、**已兑现但漏记账**：若该伏笔其实已在某一章被剧情消化（文中已给出答案、承诺已履行、
            冲突已了结），只是回收清单没记上——**首选判 DONE 并回填 actualPayoffChapter**：
            账要平，排期表也要据此关账，否则它下一阶段还会被当成欠账重新要求回收。
            若你认为该线确实已不值得追踪而判 VOID，则 reason **必须以「【已兑现】」开头**并写明第几章兑现——
            这类条目是**记账缺口的证据**，系统会据此统计摘要层漏记回收的规模，请勿省略该标记。

            二、**按计划在途**：若条目带有「已过计划回收章，已逾期 N 章」标注，说明它当初被明确排进了
            兑现计划，只是到期未收——它**是欠账，不是闲笔**，必须逐条在 DONE / PARKED / RECOVER 中做出判断，
            不得直接弃置，判据看那个 N：
              · 已兑现 → DONE（回填章号）；
              · N ≥ 30 且它就是设计上要长跑的线（身世/身份秘密/最终目标类）、又指不出一条具体的回收路径
                → PARKED（保留追踪、不再每段催收；reason 写明它指向哪个跨阶段悬念）；
              · 其余（刚逾期几章到二十几章，或能找到自然连接点）→ RECOVER，reason 写明如何补收；
              · 确实无法补收的才判 VOID，且 reason 必须写明「排期落空」以便复盘。

            PARKED 不是"躲开难题"的出口：它只覆盖逾期几十章、明显在长跑的那几条。
            把逾期不久的线判成 PARKED，等于让它失去回收压力——那正是这套机制要避免的。""";

    /** 单章剧情记忆在 prompt 中的截断字数（清账只判断剧情走向，不需全文） */
    private static final int STAGE_MEMORY_CHAPTER_CHARS = 300;

    private final LlmGateway llmGateway;

    private final ChapterMemoryService chapterMemoryService;

    public List<ForeshadowSettlementEntity.SettlementDecision> settleStageBreakers(StoryVO.Module module,
                                                                                   StageBlueprintEntity stage,
                                                                                   String chapterGoal,
                                                                                   List<ChapterSummaryEntity> summaries,
                                                                                   List<ForeshadowSettlementEntity> settlementsInOut,
                                                                                   Map<String, String> promptSink) {
        return settleStageBreakers(module, stage, chapterGoal, summaries, settlementsInOut, promptSink, null);
    }


    public List<ForeshadowSettlementEntity.SettlementDecision> settleStageBreakers(StoryVO.Module module,
                                                                                   StageBlueprintEntity stage,
                                                                                   String chapterGoal,
                                                                                   List<ChapterSummaryEntity> summaries,
                                                                                   List<ForeshadowSettlementEntity> settlementsInOut,
                                                                                   Map<String, String> promptSink,
                                                                                   List<ForeshadowScheduleEntity> schedules) {
        int stageEndNo = stage.getEndChapter() == null ? 0 : stage.getEndChapter();
        List<ForeshadowPriorityService.ScoredForeshadow> breakers =
                chapterMemoryService.buildBreakerForeshadows(summaries, stageEndNo);
        Map<String, Integer> overdueByIntent = overdueIntents(schedules, stageEndNo);
        List<ForeshadowPriorityService.ScoredForeshadow> candidates =
                withOverdueScheduled(breakers, overdueByIntent.keySet(), stageEndNo);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ForeshadowSettlementEntity.SettlementDecision> decisions =
                settle(module, stage, chapterGoal, candidates, summaries, promptSink, overdueByIntent);
        if (decisions == null) {
            return null;
        }
        // VOID 出账：登记原文精确剔除，随调用方的检查点/预载对齐落盘
        List<String> voided = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_VOID.equals(d.getDecision()))
                .map(ForeshadowSettlementEntity.SettlementDecision::getContent)
                .filter(StringUtils::isNotBlank)
                .toList();
        chapterMemoryService.stripVoidedForeshadows(summaries, voided);
        settlementsInOut.add(ForeshadowSettlementEntity.builder()
                .stageNo(stage.getStageNo())
                .stageEndChapter(stageEndNo)
                .decisions(decisions)
                .build());
        return decisions;
    }

    /**
     * 逐条裁决长期未填的伏笔；输出与未填清单一一位置对齐。
     *
     * @param promptSink 实际使用的 system/user prompt 回写容器（供复盘），可为 null
     * @return 裁决结果；无条件缺失/解析失败/条数不齐/异常时返回 null（fail-soft，未填保持冻结）
     */
    /** 兼容重载：不标注任何"欠账"条目（调用方没有排期表时用） */
    public List<ForeshadowSettlementEntity.SettlementDecision> settle(StoryVO.Module module,
                                                                      StageBlueprintEntity stage,
                                                                      String chapterGoal,
                                                                      List<ForeshadowPriorityService.ScoredForeshadow> breakerItems,
                                                                      List<ChapterSummaryEntity> summaries,
                                                                      Map<String, String> promptSink) {
        return settle(module, stage, chapterGoal, breakerItems, summaries, promptSink, Map.of());
    }

    public List<ForeshadowSettlementEntity.SettlementDecision> settle(StoryVO.Module module,
                                                                      StageBlueprintEntity stage,
                                                                      String chapterGoal,
                                                                      List<ForeshadowPriorityService.ScoredForeshadow> breakerItems,
                                                                      List<ChapterSummaryEntity> summaries,
                                                                      Map<String, String> promptSink,
                                                                      Map<String, Integer> overdueIntents) {
        if (module == null || stage == null || breakerItems == null || breakerItems.isEmpty()) {
            return null;
        }
        try {
            if (promptSink != null) {
                promptSink.put("system", SETTLE_SYSTEM_PROMPT);
                promptSink.put("user", buildUserPrompt(stage, chapterGoal, breakerItems, summaries, overdueIntents));
            }
            // maxTokens/temperature 不再硬编码，由 scene-models 的 foreshadow-settlement 场景配置驱动
            String raw = llmGateway.complete(module, LlmCall.builder()
                    .systemPrompt(SETTLE_SYSTEM_PROMPT)
                    .userPrompt(buildUserPrompt(stage, chapterGoal, breakerItems, summaries, overdueIntents))
                    .label("foreshadow-settlement-第" + stage.getStageNo() + "阶段")
                    .scene(ModelScene.FORESHADOW_SETTLEMENT)
                    .build());

            SettlementOutput output = JsonParseFallback.parse(raw, this::readOutput);
            if (output == null || output.getResults() == null || output.getResults().size() != breakerItems.size()) {
                log.warn("卷末清账输出无法解析或条数不齐（期望 {}），未填伏笔保持冻结", breakerItems.size());
                return null;
            }
            return alignAndNormalize(breakerItems, output.getResults());
        } catch (Exception e) {
            log.warn("卷末清账异常，未填伏笔保持冻结（fail-soft），stage: {}", stage.getStageNo(), e);
            return null;
        }
    }

    private String buildUserPrompt(StageBlueprintEntity stage, String chapterGoal,
                                   List<ForeshadowPriorityService.ScoredForeshadow> breakerItems,
                                   List<ChapterSummaryEntity> summaries,
                                   Map<String, Integer> overdueIntents) {
        int stageEnd = stage.getEndChapter() == null ? 0 : stage.getEndChapter();
        StringBuilder sb = new StringBuilder();
        sb.append("对以下长期未填的伏笔逐条裁决。results 数组与未填清单一一位置对齐，条数必须一致。");
        sb.append("\n\n【上一阶段】第").append(stage.getStageNo()).append("阶段（第")
                .append(stage.getStartChapter()).append("-").append(stageEnd).append("章）");
        if (StringUtils.isNotBlank(stage.getStageGoal())) {
            sb.append("，阶段目标：").append(stage.getStageGoal());
        }
        if (StringUtils.isNotBlank(chapterGoal)) {
            sb.append("\n\n【全书后续方向（原始章节目标）】").append(chapterGoal);
        }

        sb.append("\n\n【长期未填伏笔清单】");
        for (int i = 0; i < breakerItems.size(); i++) {
            ChapterMemoryService.PendingForeshadow item = breakerItems.get(i).item();
            sb.append("\n").append(i + 1).append(". （埋设第").append(item.chapterNo()).append("章")
                    .append("，滞留").append(Math.max(0, stageEnd - item.chapterNo())).append("章")
                    .append("，重要度").append(item.importance() == null ? "未评" : item.importance()).append("）");
            // 逾期排期线明确标注：它不是"走远的闲笔"，而是**欠账**——裁决取向完全不同。
            // 带出逾期章数：判"被写忘了"还是"有意养的长线"要靠这个数字（实测逾期两簇：
            // 仍在推进的 11-22 章、真正卡死的 48-74 章）
            Integer overdueBy = overdueIntents == null
                    ? null : overdueIntents.get(ForeshadowSpanPolicy.normalize(item.content()));
            if (overdueBy != null) {
                sb.append("【⚠️ 已过计划回收章，已逾期 ").append(overdueBy).append(" 章——欠账，不是闲笔】");
            }
            sb.append(item.content());
            if (StringUtils.isNotBlank(item.excerpt())) {
                sb.append("（埋设处原文：").append(item.excerpt()).append("）");
            }
        }

        sb.append("\n\n【上一阶段章节记忆】\n");
        for (ChapterSummaryEntity summary : stageSummaries(stage, summaries)) {
            String text = StringUtils.defaultString(summary.getSummary());
            if (text.length() > STAGE_MEMORY_CHAPTER_CHARS) {
                text = text.substring(0, STAGE_MEMORY_CHAPTER_CHARS);
            }
            sb.append("- 第").append(summary.getChapterNo()).append("章《")
                    .append(StringUtils.defaultString(summary.getTitle())).append("》：").append(text).append("\n");
        }

        sb.append("\n裁决要求：")
                .append("\n1. decision 只允许四选一：")
                .append("\n   · DONE——这条线**其实已经在已写章节里兑现了**（在上面的章节记忆里能找到它落地的那一章）：")
                .append("此时必须回填 actualPayoffChapter=该章的章号。这是**首选**结果：")
                .append("一条已经被写出来的线不该再被要求回收一次，否则规划层会反复安排同一件事；")
                .append("\n   · RECOVER——确实还没兑现，但能说出与后续剧情的自然连接点（结转下一阶段前段限期回收）；")
                .append("\n   · PARKED——**有意慢慢养的长线**：这条线本身就是设计上要长跑的（身世/身份秘密/最终目标"
                        + "这类），短期内不可能兑现，而它**没有**明确的自然连接点可以指定回收章。")
                .append("判 PARKED 的效果是：保留追踪，但**不再每段要求它回收**——")
                .append("反复催一条设计上就该慢的线，只会逼规划层反复重写同一件事"
                        + "（实测「记号纸」被催出了一连串'又一次被折好收起'的静态动作）。")
                .append("判 PARKED 必须写明 reason：**为什么它是长线**（它指向的是哪一个跨阶段的悬念）；")
                .append("\n   · VOID——剧情已走远，弃置不再追踪；")
                .append("\n1.1 ⚠️ 凡标了【已过计划回收章，已逾期 N 章——欠账】的条目，")
                .append("**必须逐条先在 DONE / PARKED / RECOVER 三者中做出判断**。")
                .append("回收章早就过了却一直关不掉账，最常见的原因不是「没写成」，而是**已经写了、但账本没认出来**")
                .append("（回收匹配只比对摘要里的短句与伏笔的抽象措辞，措辞对不上就认不出）。")
                .append("判断依据就是上面的章节记忆：若其中某章已经出现了这条线要的那个结果——")
                .append("谁因此知道了什么、谁做了那个决定、什么被确认或被公开——就判 DONE 并给出章号；")
                .append("若它确实还没发生，且**逾期已 ≥30 章**、又说不出一条具体的回收路径，")
                .append("那它大概率是设计上的长线，判 PARKED；只有确实能找到自然连接点时才判 RECOVER。")
                .append("\n    ⚠️ 不得判 DONE 的情形：只是「又提到了一次」「那个物件又出现了一次」，")
                .append("但没有产生这条线要的状态变化——那仍是未兑现。")
                .append("\n    ⚠️ 不得判 PARKED 的情形：**逾期不久的线**（刚过回收章几章到二十几章）。")
                .append("那些属于「该收没收」，应当判 RECOVER 并要求本阶段安排，")
                .append("PARKED 只留给逾期几十章、明显在长跑的那几条——它不能拿来当「躲开难题」的出口。")
                .append("\n2. 存疑一律 VOID；判 RECOVER 时 reason 必须写明自然连接点")
                .append("（后续哪个阶段目标/哪条人物线能自然接住它）；判 DONE 时 reason 写明在哪一章兑现了什么；")
                .append("\n3. reason 一句话，必填；")
                .append("\n4. 不要输出 Markdown，不要输出解释。")
                .append("\n\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat());
        return sb.toString();
    }

    /**
     * 逾期排期线：归一 intent → **逾期章数**（PLANTED、回收章已过阶段末章、且不在"长线在养"）。
     *
     * <p>带距离而不是只带一个集合：裁决要判"这是被写忘了的线、还是有意慢慢养的长线"，
     * 而主要依据就是逾期了多久（实测逾期分布正好两簇：仍在推进的条目 11-22 章、
     * 真正卡死的 48-74 章）。只给"已逾期"三个字，模型没有可判定的数字。
     *
     * <p>在养条目排除在外：否则它们每阶段又被送回裁决，可能在 RECOVER 与 PARKED 之间反复横跳。
     */
    private static Map<String, Integer> overdueIntents(List<ForeshadowScheduleEntity> schedules, int stageEndNo) {
        if (schedules == null || schedules.isEmpty() || stageEndNo <= 0) {
            return Map.of();
        }
        Map<String, Integer> byIntent = new HashMap<>();
        for (ForeshadowScheduleEntity schedule : schedules) {
            for (ForeshadowScheduleEntity.ScheduleItem item : ForeshadowScheduleEntity.itemsOf(schedule)) {
                if (item != null
                        && ForeshadowScheduleEntity.STATUS_PLANTED.equals(item.getStatus())
                        && !item.parked()
                        && item.getPayoffChapter() != null && item.getPayoffChapter() <= stageEndNo
                        && StringUtils.isNotBlank(item.getIntent())) {
                    // 同一 intent 常有多份副本（跨阶段块各存一份）：取最大逾期，即"最该收的那次计划"
                    byIntent.merge(ForeshadowSpanPolicy.normalize(item.getIntent()),
                            stageEndNo - item.getPayoffChapter(), Math::max);
                }
            }
        }
        return byIntent;
    }

    /**
     * 候选并集：BREAKER 档 ∪ 逾期排期线。
     *
     * <p>逾期排期线即使没到 BREAKER 档也必须送审——它的"该收未收"是**计划事实**，
     * 不该由滞留时长决定要不要审。已在 BREAKER 里出现过的（按归一文本比对）不重复加。
     */
    private static List<ForeshadowPriorityService.ScoredForeshadow> withOverdueScheduled(
            List<ForeshadowPriorityService.ScoredForeshadow> breakers, Set<String> overdueIntents,
            int stageEndNo) {
        List<ForeshadowPriorityService.ScoredForeshadow> all = new ArrayList<>(breakers);
        if (overdueIntents.isEmpty()) {
            return all;
        }
        Set<String> covered = breakers.stream()
                .map(b -> ForeshadowSpanPolicy.normalize(b.item().content()))
                .collect(Collectors.toSet());
        for (String intent : overdueIntents) {
            if (covered.contains(intent)) {
                continue;
            }
            all.add(new ForeshadowPriorityService.ScoredForeshadow(
                    new ChapterMemoryService.PendingForeshadow(0, intent, null, null),
                    Integer.MAX_VALUE, ForeshadowPriorityService.Tier.BREAKER));
        }
        return all;
    }

    /** 阶段区间内的剧情摘要，按章号排序 */
    private List<ChapterSummaryEntity> stageSummaries(StageBlueprintEntity stage, List<ChapterSummaryEntity> summaries) {
        if (summaries == null) {
            return List.of();
        }
        return summaries.stream()
                .filter(s -> s.getChapterNo() != null
                        && s.getChapterNo() >= stage.getStartChapter() && s.getChapterNo() <= stage.getEndChapter())
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo))
                .toList();
    }

    /**
     * 对齐与归一：按位置映射回账本条目原文（不信任模型回抄的 content）；决策值非法按保守语义归为
     * 弃置（宁弃置不强收），理由缺失补默认值
     */
    private List<ForeshadowSettlementEntity.SettlementDecision> alignAndNormalize(
            List<ForeshadowPriorityService.ScoredForeshadow> breakerItems, List<DecisionOutput> modelResults) {
        List<ForeshadowSettlementEntity.SettlementDecision> decisions = new ArrayList<>();
        for (int i = 0; i < breakerItems.size(); i++) {
            DecisionOutput model = modelResults.get(i);
            String rawDecision = model == null ? "" : StringUtils.trimToEmpty(model.getDecision());
            boolean recover = ForeshadowSettlementEntity.DECISION_RECOVER.equalsIgnoreCase(rawDecision);
            boolean done = ForeshadowSettlementEntity.DECISION_DONE.equalsIgnoreCase(rawDecision);
            boolean parked = ForeshadowSettlementEntity.DECISION_PARKED.equalsIgnoreCase(rawDecision);
            Integer payoffChapter = model == null ? null : model.getActualPayoffChapter();
            String reason = model == null ? "" : StringUtils.trimToEmpty(model.getReason());
            // DONE 必须带章号才可用：没有章号的"已完成"无法核验，也无法回写排期表——
            // 降级回 RECOVER（继续追踪）而不是直接关账，因为误关会永久丢掉一条真伏笔
            if (done && (payoffChapter == null || payoffChapter <= 0)) {
                done = false;
                recover = true;
                reason = "判 DONE 但未给出有效的 actualPayoffChapter，降级为限期回收" +
                        (reason.isBlank() ? "" : "：" + reason);
            }
            // PARKED 必须说明"为什么它是长线"：没有理由的在养与"被写忘了的线"无法区分，
            // 而两者的处置正好相反（前者不催、后者要催），故降级回 RECOVER 继续追踪
            if (parked && StringUtils.isBlank(reason)) {
                parked = false;
                recover = true;
                reason = "判长线在养但未说明理由，降级为限期回收";
            }
            if (!recover && !done && !parked
                    && !ForeshadowSettlementEntity.DECISION_VOID.equalsIgnoreCase(rawDecision)) {
                reason = "非法决策值归为弃置：" + rawDecision;
            }
            if (StringUtils.isBlank(reason)) {
                reason = recover ? "未给出连接点" : "剧情已走远（未给出理由）";
            }
            ChapterMemoryService.PendingForeshadow item = breakerItems.get(i).item();
            decisions.add(ForeshadowSettlementEntity.SettlementDecision.builder()
                    .content(item.content())
                    .chapterNo(item.chapterNo())
                    .decision(done ? ForeshadowSettlementEntity.DECISION_DONE
                            : (parked ? ForeshadowSettlementEntity.DECISION_PARKED
                            : (recover ? ForeshadowSettlementEntity.DECISION_RECOVER
                            : ForeshadowSettlementEntity.DECISION_VOID)))
                    .reason(reason)
                    .actualPayoffChapter(done ? payoffChapter : null)
                    .build());
        }
        long recoverCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_RECOVER.equals(d.getDecision())).count();
        long doneCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_DONE.equals(d.getDecision())).count();
        long parkedCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_PARKED.equals(d.getDecision())).count();
        log.info("卷末清账裁决完成：{} 条未填 = 已完成 {} + 长线在养 {} + 弃置 {} + 限期回收 {}",
                decisions.size(), doneCount, parkedCount,
                decisions.size() - recoverCount - doneCount - parkedCount, recoverCount);
        return decisions;
    }

    private SettlementOutput readOutput(String raw) {
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            // 抛非受检异常交给 JsonParseFallback 按"解析失败"语义降级（JsonRepair 后重解）
            throw new IllegalArgumentException(e);
        }
    }

    /** 裁决输出载体：results 与未填清单一一位置对齐 */
    @Data
    public static class SettlementOutput {
        private List<DecisionOutput> results;
    }

    @Data
    public static class DecisionOutput {
        private Integer index;
        private String decision;
        private String reason;
        /** 判 DONE 时必填：该线实际兑现的章号（缺省视为无效，降级为 RECOVER） */
        private Integer actualPayoffChapter;
    }
}
