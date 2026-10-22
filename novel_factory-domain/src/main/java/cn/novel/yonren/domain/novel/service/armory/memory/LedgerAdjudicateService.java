package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.quality.EvidenceMatch;
import cn.novel.yonren.types.enums.ModelScene;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerAdjudicateService {

    private static final BeanOutputConverter<AdjudicationOutput> CONVERTER = new BeanOutputConverter<>(
            AdjudicationOutput.class,
            JsonMapper.builder()
                    .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                    // 模型偶发多输出字段，忽略未知字段避免整段解析失败
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build());

    private static final String SYSTEM_PROMPT = """
            你是小说流水线的账本审计员，负责判定摘要模型给出的状态/事实结论是否真的被本章正文支持。
            摘要模型常把正文改写、压缩或改写成叙述句后当作引用，导致机械校验无法定位——你的任务是回到正文，
            为确实成立的结论找出一段逐字可核对的原文。
            你是审计者而非创作者：只依据给定正文判定，不脑补剧情；被改写过的句子不能充当引文。""";

    /** 单条引文上限：与状态证据入库口径一致（超长会破坏子串有效性） */
    private static final int QUOTE_MAX_LENGTH = EvidenceMatch.STATE_EVIDENCE_MAX_LENGTH;

    /**
     * 裁决入账的留痕档位码：写入条目 evidenceTier，供观测层统计「LLM 救回」占账本的比例。
     * 与 EvidenceMatch 的机械档位区分开——这两类条目的证据质量来源不同，需分开观测
     */
    public static final String ADJUDICATED_TIER = "adjudicated";

    private final LlmGateway llmGateway;
    private final StoryProperties storyProperties;

    /** 是否开启（yml: story.ledger-adjudicate.enabled）；关掉即零 LLM 成本 */
    public boolean enabled() {
        StoryProperties.LedgerAdjudicateProperties props = storyProperties.getLedgerAdjudicate();
        return props != null && props.isEnabled();
    }

    /**
     * 裁决本章挂起项并就地回写摘要：通过的条目按来源账本回到三账本/一致性事实，其余原样留在挂起层。
     * 必须在摘要落盘之前调用，写入 summaries 的摘要才是「裁决后」状态。
     *
     * @param storyVO 用于解析模型场景配置
     * @param summary 本章摘要（挂起项与账本均就地更新）
     * @param content 本章正文原文（引文机械校验的比对基准）
     * @return 裁决入账的条数（0 表示未开启/无可裁决项/全部未通过）
     */
    public int adjudicate(StoryVO storyVO, ChapterSummaryEntity summary, String content) {
        if (!enabled() || storyVO == null || summary == null || StringUtils.isBlank(content)) {
            return 0;
        }
        List<ChapterSummaryEntity.StateEntry> pendingStates = summary.getPendingFacts();
        List<ChapterSummaryEntity.ConsistencyFact> pendingFacts = summary.getPendingConsistencyFacts();
        List<Item> all = selectAll(pendingStates, pendingFacts);
        if (all.isEmpty()) {
            return 0;
        }
        // 超出上限的条目本章不裁、仍留在挂起层：上限只是防 prompt/输出去膨胀的安全阀，不做优先级调度
        List<Item> batch = all.size() <= maxItems() ? all : new ArrayList<>(all.subList(0, maxItems()));

        Map<Integer, Verdict> verdicts = invoke(storyVO, summary, content, batch);
        if (verdicts.isEmpty()) {
            return 0;
        }

        // 通过的条目按来源回到原账本；未通过（含引文校验失败）原样留在挂起层
        List<ChapterSummaryEntity.StateEntry> remainStates = new ArrayList<>();
        Map<ChapterSummaryEntity.AccountKind, List<ChapterSummaryEntity.StateEntry>> rescuedStates =
                new HashMap<>();
        List<ChapterSummaryEntity.ConsistencyFact> rescuedFacts = new ArrayList<>();
        List<ChapterSummaryEntity.ConsistencyFact> remainFacts = new ArrayList<>();
        int smuggled = 0;

        for (Item item : batch) {
            Verdict verdict = verdicts.get(item.index());
            boolean supported = verdict != null && Boolean.TRUE.equals(verdict.getSupported());
            String quote = supported
                    ? EvidenceMatch.truncate(verdict.getQuote(), QUOTE_MAX_LENGTH)
                    : null;
            if (supported) {
                // 空白引文 = 没有证据，必须按不支持处理。EvidenceMatch.classify 对空白的契约是
                // “此处不判定”（返回 EXACT，由调用方保证先校验非空）；这里若不拦，
                // 模型只要回 {"index":1,"supported":true} 而漏掉 quote，编造事实就会被救回正账本
                if (StringUtils.isBlank(quote)) {
                    smuggled++;
                    log.warn("账本裁决声称有据但未给出可核对引文（按不支持处理），chapterNo: {}, 条目: {}",
                            summary.getChapterNo(), item.name());
                } else {
                    EvidenceMatch.Tier tier = EvidenceMatch.classify(quote, content);
                    if (tier.isAccepted()) {
                        if (item.state() != null) {
                            // 先取来源账本再消费标记：顺序反了会让所有条目都落到角色账本（已由测试守住）
                            ChapterSummaryEntity.AccountKind kind = accountOf(item.state());
                            item.state().setEvidence(quote);
                            item.state().setEvidenceTier(ADJUDICATED_TIER);
                            item.state().setSourceAccount(null);
                            rescuedStates.computeIfAbsent(kind, k -> new ArrayList<>()).add(item.state());
                        } else {
                            item.fact().setEvidence(quote);
                            item.fact().setEvidenceTier(ADJUDICATED_TIER);
                            rescuedFacts.add(item.fact());
                        }
                        continue;
                    }
                    // 模型声称有据但引文过不了机械校验：这是「裁决层想放水后被门挡住」的信号，必须告警
                    smuggled++;
                    log.warn("账本裁决引文未通过机械校验（按不支持处理），chapterNo: {}, 条目: {}",
                            summary.getChapterNo(), item.name());
                }
            }
            if (item.state() != null) {
                remainStates.add(item.state());
            } else {
                remainFacts.add(item.fact());
            }
        }

        // 未被纳入本批（超出上限）的挂起项原样保留，不得丢失；按编号排除，不依赖顺序假设
        Set<Integer> batched = new HashSet<>();
        for (Item item : batch) {
            batched.add(item.index());
        }
        for (Item item : all) {
            if (batched.contains(item.index())) {
                continue;
            }
            if (item.state() != null) {
                remainStates.add(item.state());
            } else {
                remainFacts.add(item.fact());
            }
        }

        writeBack(summary, rescuedStates, rescuedFacts, remainStates, remainFacts);
        int rescued = rescuedStates.values().stream().mapToInt(List::size).sum() + rescuedFacts.size();
        if (rescued > 0 || smuggled > 0) {
            log.info("账本裁决完成，chapterNo: {}，裁决 {} 条 -> 入账 {} 条，引文校验未过 {} 条，挂起留存 {} 条",
                    summary.getChapterNo(), batch.size(), rescued, smuggled,
                    remainStates.size() + remainFacts.size());
        }
        return rescued;
    }

    /** 调用模型并把编号-判定结果整理成 map；任何失败返回空 map（fail-soft，挂起层原样保留） */
    private Map<Integer, Verdict> invoke(StoryVO storyVO, ChapterSummaryEntity summary,
                                         String content, List<Item> batch) {
        Map<Integer, Verdict> verdicts = new HashMap<>();
        try {
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(buildUserPrompt(summary, content, batch))
                    .label("ledger-adjudicate-第" + summary.getChapterNo() + "章")
                    .scene(ModelScene.LEDGER_ADJUDICATE)
                    .build());
            AdjudicationOutput output = JsonParseFallback.parse(raw, this::readOutput);
            if (output == null || output.getVerdicts() == null) {
                log.warn("账本裁决输出无法解析，本章挂起项 {} 条原样保留，chapterNo: {}",
                        batch.size(), summary.getChapterNo());
                return verdicts;
            }
            for (Verdict verdict : output.getVerdicts()) {
                if (verdict != null && verdict.getIndex() != null) {
                    // 同编号重复输出时以首个为准，防止后者覆盖出一个更宽松的判定
                    verdicts.putIfAbsent(verdict.getIndex(), verdict);
                }
            }
        } catch (Exception e) {
            log.warn("账本裁决调用异常，本章挂起项原样保留（fail-soft），chapterNo: {}",
                    summary.getChapterNo(), e);
            verdicts.clear();
        }
        return verdicts;
    }

    /** 全部「可裁决」条目，按挂起层原顺序编号（1 起） */
    private List<Item> selectAll(List<ChapterSummaryEntity.StateEntry> pendingStates,
                                 List<ChapterSummaryEntity.ConsistencyFact> pendingFacts) {
        List<Item> items = new ArrayList<>();
        int index = 0;
        if (pendingStates != null) {
            for (ChapterSummaryEntity.StateEntry entry : pendingStates) {
                if (entry != null && adjudicable(entry.getEvidenceTier())) {
                    items.add(new Item(++index, label(entry.getSourceAccount()), entry.getName(),
                            entry.getStatus(), entry.getEvidence(), entry.getEvidenceTier(),
                            entry, null));
                }
            }
        }
        if (pendingFacts != null) {
            for (ChapterSummaryEntity.ConsistencyFact fact : pendingFacts) {
                if (fact == null || !adjudicable(fact.getEvidenceTier())) {
                    continue;
                }
                String claim = StringUtils.defaultString(fact.getSubject())
                        + (StringUtils.isBlank(fact.getValue()) ? "" : " = " + fact.getValue());
                items.add(new Item(++index, "一致性事实", claim, fact.getType(),
                        fact.getEvidence(), fact.getEvidenceTier(), null, fact));
            }
        }
        return items;
    }

    /**
     * 是否可交裁决：短语部分命中（机械已判无可再放宽的部分），或一致性事实完全未附证据者。
     * NO_MATCH 明确排除——它是反编造门唯一保留的逐出通道
     */
    private static boolean adjudicable(String tierCode) {
        return StringUtils.isBlank(tierCode)
                || EvidenceMatch.Tier.PHRASE_PARTIAL.getCode().equals(tierCode);
    }

    private static ChapterSummaryEntity.AccountKind accountOf(ChapterSummaryEntity.StateEntry entry) {
        String code = entry.getSourceAccount();
        if (ChapterSummaryEntity.AccountKind.ITEM.getCode().equals(code)) {
            return ChapterSummaryEntity.AccountKind.ITEM;
        }
        if (ChapterSummaryEntity.AccountKind.FACTION.getCode().equals(code)) {
            return ChapterSummaryEntity.AccountKind.FACTION;
        }
        // 来源缺失（老数据/降级路径）按角色账本兜底，保证救回的事实不丢
        return ChapterSummaryEntity.AccountKind.CHARACTER;
    }

    private static String label(String accountCode) {
        if (ChapterSummaryEntity.AccountKind.ITEM.getCode().equals(accountCode)) {
            return "物品";
        }
        if (ChapterSummaryEntity.AccountKind.FACTION.getCode().equals(accountCode)) {
            return "势力";
        }
        return "角色";
    }

    /** 回写：救回条目追加到对应账本，其余原样留在挂起层（空列表写 null，保持原有落盘形态） */
    private void writeBack(ChapterSummaryEntity summary,
                           Map<ChapterSummaryEntity.AccountKind, List<ChapterSummaryEntity.StateEntry>> rescuedStates,
                           List<ChapterSummaryEntity.ConsistencyFact> rescuedFacts,
                           List<ChapterSummaryEntity.StateEntry> remainStates,
                           List<ChapterSummaryEntity.ConsistencyFact> remainFacts) {
        if (!rescuedStates.isEmpty()) {
            appendStates(summary, ChapterSummaryEntity.AccountKind.CHARACTER, rescuedStates);
            appendStates(summary, ChapterSummaryEntity.AccountKind.ITEM, rescuedStates);
            appendStates(summary, ChapterSummaryEntity.AccountKind.FACTION, rescuedStates);
        }
        if (!rescuedFacts.isEmpty()) {
            summary.setConsistencyFacts(append(summary.getConsistencyFacts(), rescuedFacts));
        }
        summary.setPendingFacts(remainStates.isEmpty() ? null : remainStates);
        if (summary.getPendingConsistencyFacts() != null) {
            summary.setPendingConsistencyFacts(remainFacts.isEmpty() ? null : remainFacts);
        }
    }

    private void appendStates(ChapterSummaryEntity summary,
                              ChapterSummaryEntity.AccountKind kind,
                              Map<ChapterSummaryEntity.AccountKind, List<ChapterSummaryEntity.StateEntry>> rescued) {
        List<ChapterSummaryEntity.StateEntry> additions = rescued.get(kind);
        if (additions == null || additions.isEmpty()) {
            return;
        }
        switch (kind) {
            case ITEM -> summary.setItemStates(append(summary.getItemStates(), additions));
            case FACTION -> summary.setFactionStates(append(summary.getFactionStates(), additions));
            default -> summary.setCharacterStates(append(summary.getCharacterStates(), additions));
        }
    }

    private static <T> List<T> append(List<T> existing, List<T> additions) {
        List<T> merged = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        merged.addAll(additions);
        return merged;
    }

    private String buildUserPrompt(ChapterSummaryEntity summary, String content, List<Item> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是第 ").append(summary.getChapterNo()).append(" 章《")
                .append(StringUtils.defaultString(summary.getTitle())).append("》的摘要模型给出的 ")
                .append(batch.size()).append(" 条状态/事实。")
                .append("它们的「原引用」无法在本章正文中逐字定位（模型多为改写或压缩引用），故需要复核。")
                .append("\n请逐条判断该结论是否真的被本章正文支持；若支持，必须从正文中摘出一段")
                .append("能直接支撑该结论的连续原文作为新引文。")
                .append("\n规则：")
                .append("\n1. verdicts 与下方条目对应，index 必须等于条目前面的编号；")
                .append("判不出的条目可以直接不输出，但严禁把 index 张冠李戴；")
                .append("\n2. quote 必须是本章正文中逐字出现的连续片段（≤")
                .append(QUOTE_MAX_LENGTH).append(" 字），严禁改写、拼接、跨段组合或概括；")
                .append("系统会做子串匹配机械校验，校验不过的条目一律按「不支持」处理；")
                .append("\n3. 结论包含多个要点时，quote 只需支撑其中最核心、最可核实的那一项；")
                .append("\n4. 正文中找不到支持该结论的原文时，supported 必须为 false 且 quote 为 null，")
                .append("不得勉强引用——错判为支持会污染后续所有章节的记忆；")
                .append("\n5. 不要输出 Markdown，不要输出解释。")
                .append("\n\n【待裁决条目】");
        for (Item item : batch) {
            sb.append("\n").append(item.index()).append(". [").append(item.label()).append("] ")
                    .append(StringUtils.defaultIfBlank(item.name(), "(未命名)"))
                    .append("：").append(StringUtils.defaultString(item.claim()))
                    .append("\n   原引用（无法逐字定位）：")
                    .append(StringUtils.defaultIfBlank(item.evidence(), "（无）"));
        }
        sb.append("\n\n【本章正文】\n").append(content);
        sb.append("\n\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat());
        sb.append("\n\n【输出示例】\n")
                .append("{\"verdicts\":[")
                .append("{\"index\":1,\"supported\":true,\"quote\":\"陆沉把黑色令牌拍在案角\",\"note\":null},")
                .append("{\"index\":2,\"supported\":false,\"quote\":null,\"note\":\"正文中无支撑该结论的原文\"}")
                .append("]}");
        return sb.toString();
    }

    private AdjudicationOutput readOutput(String raw) {
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            // 抛非受检异常交给 JsonParseFallback 按"解析失败"语义降级（JsonRepair 后重解）
            throw new IllegalArgumentException(e);
        }
    }

    private int maxItems() {
        StoryProperties.LedgerAdjudicateProperties props = storyProperties.getLedgerAdjudicate();
        if (props == null || props.getMaxItems() <= 0) {
            return Integer.MAX_VALUE;
        }
        return props.getMaxItems();
    }

    /** 待裁决条目：统一承载 StateEntry 与 ConsistencyFact，便于统一编号与回写（两者仅一个非空） */
    private record Item(int index, String label, String name, String claim, String evidence,
                        String evidenceTier,
                        ChapterSummaryEntity.StateEntry state,
                        ChapterSummaryEntity.ConsistencyFact fact) {
    }

    /** 单条判定 */
    @Data
    public static class Verdict {
        /** 对应 prompt 里的条目前缀编号（1 起） */
        private Integer index;
        /** 该结论是否被正文支持 */
        private Boolean supported;
        /** supported=true 时给出的逐字引文（≤50 字），须再经 EvidenceMatch 校验才算通过 */
        private String quote;
        /** 不支持时的简短说明 */
        private String note;
    }

    /** 裁决输出载体 */
    @Data
    public static class AdjudicationOutput {
        private List<Verdict> verdicts;
    }
}
