package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LedgerEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard;
import cn.novel.yonren.domain.novel.service.armory.quality.ForeshadowIds;
import cn.novel.yonren.domain.novel.service.armory.quality.ForeshadowSpanPolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 记忆组装服务：把章节摘要列表装配成本章生成的记忆前缀。
 * 分层思想：顶层设定（story-bible）由 BuildStoryContextNode 负责，此处只装配底层 ——
 * 近章摘要 + 角色/物品/势力三账本 + 伏笔账 + 上章偏差警示 + 上章质量债 + 上一章结尾。
 * 各账本均由摘要列表确定性重建，本服务无状态
 */

@Service
@Slf4j
public class ChapterMemoryService {

    /** 近程记忆保留的摘要章数 */
    public static final int RECENT_SUMMARY_COUNT = 5;
    /**
     * 上一章结尾原文的近似字数。2026-09-30 由 500 提到 2000（杠杆三）：
     * 写第 N 章时模型只凭"摘要 + 500 字结尾"续写，等于每次都在"按梗概重写"而非"接着原文写"——
     * 文风断裂与 AI 味的一大来源。加厚到 2000 字（约最后 3-5 个自然段）让模型接住前章的语感与节奏。
     * 代价：每章正文 prompt 增加约 1.5k 字符（前缀总额 18000 内，优先级 3 高于账本/疲劳词，不挤关键块）。
     */
    public static final int DEFAULT_PREV_TAIL_LENGTH = 2000;
    /** 活跃窗口：最近 N 章内有状态更新的账本条目进入前缀，其余沉入沉淀名单 */
    public static final int ACTIVE_WINDOW = 10;
    /** 新埋区（上一章刚埋）带原文展示的条数上限 */
    public static final int MAX_FRESH_LINES = 3;
    /** 硬区（计划必须评估）带原文展示的条数上限 */
    public static final int MAX_HARD_LINES = 5;
    /** 软区（推荐顺手回收）带原文展示的条数上限 */
    public static final int MAX_SOFT_LINES = 5;
    /** 背景区内容行的条数上限：按分数取 top N，防前缀随账本线性膨胀 */
    public static final int MAX_FORESHADOW_BACKGROUND_LINES = 10;
    /** 沉寂名单展示的条目上限：按最近更新章号取前 N 个，其余封存（防百章后名单线性膨胀） */
    public static final int MAX_DORMANT_NAMES = 20;
    /** 伏笔上下文清单（摘要登记/审校比对）的条数上限：按埋设章号新→旧取前 N 条 */
    public static final int FORESHADOW_CONTEXT_LIMIT = 20;
    /** 质量债回灌的最近章数上限（1~2 章） */
    public static final int MAX_DEBT_CHAPTERS = 2;
    /** 质量债回灌的 issue 条数上限 */
    public static final int MAX_DEBT_ISSUES = 5;
    /** 蓝图生成的伏笔输入条数上限（硬区优先，其次软区，最后未填） */
    public static final int BLUEPRINT_FORESHADOW_LIMIT = 20;
    /** 阶段蓝图结转任务的渲染上限：只保留"进行中"的前 N 条（已完成/放弃不进写手前缀） */
    public static final int MAX_BLUEPRINT_CARRIED_TASKS = 6;
    /** 结转任务进展说明（note）的截断长度：note 常是规划层审计语言，写手只需知道缺口本身 */
    public static final int MAX_CARRIED_NOTE_CHARS = 60;

    /** 记忆子块间分隔符（与 PromptBudgetGuard 的块间分隔符同口径） */
    private static final String SECTION_SEPARATOR = "\n\n";

    private final ForeshadowPriorityService priorityService;

    public ChapterMemoryService(ForeshadowPriorityService priorityService) {
        this.priorityService = priorityService;
    }

    /**
     * 待回收伏笔条目：content=伏笔描述，excerpt=埋设处原文引用（摘要种子，可为 null），
     * importance=埋设时评估的主线重要度 1-5（老数据为 null），
     * foreshadowId=机械层分配的稳定 ID（{@code F章号-序号}，老数据为 null）。
     *
     * <p>ID 供 prompt 注入与回收回抄——模型在 {@code foreshadowingResolved} 里回抄 ID 远比
     * 复述措辞可靠，机械层据此做集合差集即可核销，不再依赖文本匹配（见 {@code SeedEntry#foreshadowId}）。
     */
    public record PendingForeshadow(int chapterNo, String content, String excerpt, Integer importance,
                                    String foreshadowId) {

        /** 兼容构造（无 ID）：老数据与测试用；下游按 ID 缺失回退文本匹配 */
        public PendingForeshadow(int chapterNo, String content, String excerpt, Integer importance) {
            this(chapterNo, content, excerpt, importance, null);
        }
    }

    /** 空禁泄清单（无未揭伏笔带谜底关键词/全豁免时共用） */
    public static final SecrecyGuard NO_SECRECY = new SecrecyGuard(List.of(), null);

    /**
     * 本章禁泄清单（伏笔保密边界）：keywords 供机械层逐字扫描（命中即 BLOCKING），
     * promptBlock 供正文/审校/修订 prompt 注入（LLM 判"变相泄露"）。三者同源同豁免口径
     */
    public record SecrecyGuard(List<String> keywords, String promptBlock) {

        public boolean isEmpty() {
            return keywords == null || keywords.isEmpty();
        }
    }

    /** 揭示章豁免的标记词：本章计划关键事件含该伏笔描述且带以下任一标记，视为揭示/回收章 */
    private static final List<String> REVEAL_MARKERS =
            List.of("回收", "揭示", "揭晓", "揭秘", "真相大白", "兑现", "印证", "揭晓谜底", "解开");
    /** 推进章豁免的标记词：本章明确要"处理/了结"这条伏笔（摊牌、对质、骗局败露）——
     *  该伏笔的**主体词**必然出现在正文里，禁泄反而与本章目标直接冲突 */
    private static final List<String> ADVANCE_MARKERS =
            List.of("摊牌", "对质", "败露", "揭穿", "拆穿", "翻脸", "闹翻", "上门", "对峙", "戳穿", "识破");
    /** 揭示章豁免的词面匹配下限：关键事件与伏笔描述的最长公共子串达到该长度即认定指向同一伏笔
     * （计划层常只引用伏笔核心短语而非全文，如"回收第N章埋设的剑冢封印松动"） */
    private static final int REVEAL_MATCH_MIN_CHARS = 6;
    /** 禁泄关键词总量封顶：防百章后清单线性膨胀 */
    private static final int SECRECY_KEYWORD_LIMIT = 12;

    /**
     * 过短的关键词不得进机械禁泄扫描：这类词常是伏笔的**主体**而非**谜底**，
     * 逐字扫描会把正常叙事判成泄露。
     *
     * <p><b>2026-10-01 实测反例</b>：第 1 章伏笔「主角甲听到'表哥'二字时意识一紧」的
     * payoffHints 里混进了字面词 {@code 表哥}。但它不是谜底——谜底是"表哥是谁、与前世何关"，
     * 而"表哥"只是**这条疑问指向的那个人**。第 4 章标题《表哥的投资经》正是该伏笔的推进章，
     * 正文必然出现"表哥"，于是被逐字扫描判成 BLOCKING 泄露。
     *
     * <p>真正的谜底词（"萝卜章""集资骗局""签到系统"）都 ≥3 字，2 字词几乎必是主体词，
     * 故以 3 字为门槛。这会让少量真·二字谜底漏过机械层——由 LLM 审校兜底（同"未登记
     * payoffHints 的存量伏笔自动跳过"的既有口径）。
     */
    private static final int SECRECY_MIN_KEYWORD_CHARS = 3;

    /**
     * 禁泄词的最短保密跨度：埋设后不足该章数的伏笔**不进禁泄表**（2026-10-01 新增）。
     *
     * <p><b>为什么需要</b>：本机制隐含假设"伏笔有一段较长的保密窗口"，但实测写作节奏是
     * <b>下一章就兑现</b>——第 10 章埋的伏笔（老顾论斤卖书、角色乙买书钱）在第 11 章就逐字写出谜底，
     * 跨度仅 1 章。这类伏笔的关键词几乎**必然**命中扫描，于是禁泄表持续产出 BLOCKING，
     * 写手被迫把本该说清的信息改成含糊表述，信息传递效率反而下降。
     *
     * <p><b>豁免的是扫描，不是观测</b>：伏笔仍照常进伏笔账、照常参与卷末清账与揭示判定，
     * 只是它的关键词不再进逐字扫描表。真正的问题（伏笔寿命过短）由规划层反馈治本
     * ——见 {@code ChapterPlanPromptService.appendForeshadowSpanFeedback}。
     * 机械扫描在这里只负责"别误拦正常兑现"，不负责评价伏笔写得好不好。
     *
     * <p>取值 2：跨度 1（本章埋、下章用）几乎都是正常推进，豁免；跨度 ≥2 仍进表。
     * 若日后发现跨度 2 也普遍误拦，再上调——但**不要**为了少误拦而把真泄底一起放行。
     */
    private static final int SECRECY_MIN_SPAN_CHAPTERS = 2;

    /**
     * 构建本章禁泄清单：收集"已埋未揭"且登记了 payoffHints（谜底关键词）的伏笔；
     * 本章计划关键事件匹配到回收/揭示意图的伏笔（揭示章豁免）不进清单。
     * 未登记 payoffHints 的存量伏笔自动跳过（不误伤），纯靠 LLM 审校兜底。
     *
     * <p><b>行数封顶 + 风险排序</b>（2026-10-12）：清单行数与关键词共用
     * {@link #SECRECY_KEYWORD_LIMIT} 上限——候选取"与本章关键事件文本交叠最多"的线优先
     * （写手正在写它，最可能顺手泄底），其余按埋设章号新→旧。老线百章未泄属低风险，
     * 交由逐字扫描兜底的口径不变：被截掉的线**仍参与扫描**吗？不——扫描与清单同源，
     * 被截掉的线连关键词一起不进扫描。这是有意的取舍：老线的谜底若真被写出，LLM 审校
     * 兜底判"变相泄露"；换来的是清单块从 2.3k 字符（58 条在途时）回落到可控体量，
     * 不再被前缀预算整块丢弃。
     */
    public SecrecyGuard buildSecrecyGuard(List<ChapterSummaryEntity> summaries, ChapterPlanItemEntity item) {
        if (summaries == null || summaries.isEmpty()) {
            return NO_SECRECY;
        }
        List<PendingForeshadow> pending = buildPendingForeshadowDetailed(summaries);
        if (pending.isEmpty()) {
            return NO_SECRECY;
        }
        Map<String, ChapterSummaryEntity.SeedEntry> seedIndex = new LinkedHashMap<>();
        for (ChapterSummaryEntity summary : ordered(summaries)) {
            seedsOf(summary).forEach(seedIndex::putIfAbsent);
        }
        List<String> keyEvents = item == null || item.getKeyEvents() == null ? List.of()
                : item.getKeyEvents().stream()
                        .filter(StringUtils::isNotBlank)
                        .map(k -> k.replaceAll("[\\s“”\"「」『』]", ""))
                        .toList();

        int currentNo = item == null || item.getChapterNo() == null ? 0 : item.getChapterNo();
        // 先收集全部候选（豁免后），再按"本章泄露风险"排序取前 K——行数与关键词共用同一份选择，
        // 保持"扫描与清单同源同口径"（2026-10-12 前行数无上限，58 条在途伏笔时块体 2.3k 字符，
        // 是正文前缀预算饱和（100%）被整块丢弃的常客；丢弃后写手失去保密边界）
        record Candidate(PendingForeshadow foreshadow, List<String> hints, int affinity) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (PendingForeshadow p : pending) {
            ChapterSummaryEntity.SeedEntry seed = matchSeed(seedIndex, p.content());
            if (seed == null || seed.getPayoffHints() == null || seed.getPayoffHints().isEmpty()) {
                continue;
            }
            List<String> hints = seed.getPayoffHints().stream()
                    .filter(StringUtils::isNotBlank)
                    .map(String::trim)
                    .toList();
            if (hints.isEmpty()) {
                continue;
            }
            // 最短跨度豁免：刚埋下的伏笔本章就要用，禁泄表拦它只会逼写手含糊其辞。
            // 扫描与清单块**同时**排除，避免"清单说禁、扫描不查"的口径分裂
            if (currentNo > 0 && currentNo - p.chapterNo() < SECRECY_MIN_SPAN_CHAPTERS) {
                continue;
            }
            if (isRevealChapter(keyEvents, p.content()) || isAdvanceChapter(keyEvents, p.content())) {
                continue;
            }
            candidates.add(new Candidate(p, hints, keyEventAffinity(keyEvents, p.content())));
        }
        // 风险排序：①与本章关键事件有文本交叠的排最前——写手本章大概率要写到这条线，
        // 最可能顺手写出谜底（第 124 章"禁泄命中 1 条"正是近线）；②其余按埋设章号新→旧——
        // 刚埋的线活跃在当前叙事空间，老线百章未泄属低风险（旧实现按埋设顺序取最老，排序恰好相反）
        candidates.sort(Comparator.comparingInt(Candidate::affinity).reversed()
                .thenComparing((Candidate c) -> c.foreshadow().chapterNo(), Comparator.reverseOrder()));
        List<Candidate> selected = candidates.size() > SECRECY_KEYWORD_LIMIT
                ? candidates.subList(0, SECRECY_KEYWORD_LIMIT) : candidates;

        List<String> keywords = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        for (Candidate c : selected) {
            for (String hint : c.hints()) {
                if (keywords.size() >= SECRECY_KEYWORD_LIMIT) {
                    break;
                }
                // 过短的关键词多是伏笔主体而非谜底，逐字扫描会误伤正常叙事（见常量注释）
                if (hint.length() < SECRECY_MIN_KEYWORD_CHARS) {
                    continue;
                }
                if (!keywords.contains(hint)) {
                    keywords.add(hint);
                }
            }
            lines.append("- ").append(c.foreshadow().content())
                    .append("（第").append(c.foreshadow().chapterNo()).append("章埋）→ 禁泄：")
                    .append(String.join("、", c.hints())).append("\n");
        }
        if (keywords.isEmpty()) {
            return NO_SECRECY;
        }
        String block = "【本章禁泄清单】以下伏笔已埋设但尚未揭示，其谜底关键词严禁在本章正文出现"
                + "（程序会逐字扫描，命中即打回修订）：\n" + lines
                + "本章只允许继续强化悬念本身，严禁写出、暗示或以改述方式变相透露谜底；"
                + "若本章计划的关键事件正是该伏笔的揭示/回收，按计划正常揭示（该条已豁免）。";
        return new SecrecyGuard(keywords, block);
    }

    /** 揭示章判定：本章计划关键事件带回收/揭示类标记词，且与该伏笔描述有 ≥6 字公共子串（指向同一伏笔） */
    private boolean isRevealChapter(List<String> normalizedKeyEvents, String foreshadowContent) {
        if (normalizedKeyEvents.isEmpty() || StringUtils.isBlank(foreshadowContent)) {
            return false;
        }
        String foreshadow = foreshadowContent.replaceAll("[\\s“”\"「」『』]", "");
        return normalizedKeyEvents.stream().anyMatch(event ->
                REVEAL_MARKERS.stream().anyMatch(event::contains)
                        && cn.novel.yonren.domain.novel.service.armory.quality.PlanAdherencePolicy
                        .longestCommonSubstring(event, foreshadow) >= REVEAL_MATCH_MIN_CHARS);
    }

    /**
     * 推进章判定：本章计划要"处理/了结"这条伏笔，但**还不揭示谜底**（摊牌、对质、骗局败露、上门闹事）。
     *
     * <p>与 {@link #isRevealChapter} 的区别：揭示章要求谜底被说出，故整条伏笔全部豁免；
     * 推进章只说"这条线本章要动"，谜底仍未揭晓——理论上仍该禁泄谜底。
     * 但**伏笔的主体词**（"表哥"这个人）必然出现在正文里，机械逐字扫描无法区分
     * "主体词"与"谜底词"，所以整条豁免，改由 LLM 审校判"有没有顺手把谜底说破"。
     *
     * <p>2026-10-01 实测：第 4 章计划"亲戚骗局登场"，关键事件含"摊牌/对质"，
     * 而第 1 章的"表哥"伏笔被逐字判成泄露——正是缺了这条豁免。
     */
    private boolean isAdvanceChapter(List<String> normalizedKeyEvents, String foreshadowContent) {
        if (normalizedKeyEvents.isEmpty() || StringUtils.isBlank(foreshadowContent)) {
            return false;
        }
        String foreshadow = foreshadowContent.replaceAll("[\\s“”\"「」『』]", "");
        return normalizedKeyEvents.stream().anyMatch(event ->
                ADVANCE_MARKERS.stream().anyMatch(event::contains)
                        && cn.novel.yonren.domain.novel.service.armory.quality.PlanAdherencePolicy
                        .longestCommonSubstring(event, foreshadow) >= REVEAL_MATCH_MIN_CHARS);
    }

    /**
     * 本章关键事件与伏笔描述的最长公共子串（禁泄候选用，无标记词语义）：
     * >0 说明本章叙事大概率触到这条线——写手正在写它，谜底随手带出的风险最高。
     * 与 {@link #isRevealChapter}/{@link #isAdvanceChapter} 同一匹配口径
     * （{@code PlanAdherencePolicy.longestCommonSubstring}），只是不带标记词门槛。
     */
    private static int keyEventAffinity(List<String> normalizedKeyEvents, String foreshadowContent) {
        if (normalizedKeyEvents.isEmpty() || StringUtils.isBlank(foreshadowContent)) {
            return 0;
        }
        String foreshadow = foreshadowContent.replaceAll("[\\s“”\"「」『』]", "");
        int best = 0;
        for (String event : normalizedKeyEvents) {
            best = Math.max(best, cn.novel.yonren.domain.novel.service.armory.quality.PlanAdherencePolicy
                    .longestCommonSubstring(event, foreshadow));
        }
        return best;
    }

    /**
     * 组装本章生成的记忆前缀；无任何摘要时返回空串（第 1 章或摘要全部失败，退化为纯 bible+计划）
     *
     * @param summaries        此前各章摘要（含续写时预载的历史摘要，调用方按章序累积）
     * @param prevChapterTail  上一章正文结尾原文，可为 null（第 1 章）
     * @param pendingConflicts 上一章摘要发现的一致性偏差，可为 null（无则不渲染警示节）
     */
    public String buildMemoryPrefix(List<ChapterSummaryEntity> summaries, String prevChapterTail, List<String> pendingConflicts) {
        return buildMemoryPrefix(summaries, prevChapterTail, pendingConflicts, null);
    }

    /**
     * 带质量债的重载：qualityDebts 非空时渲染【上章质量债】节（位置与口吻同上章偏差警示）
     *
     * @param qualityDebts 质量债清单（含续写预载与批内滚动追加），可为 null
     */
    public String buildMemoryPrefix(List<ChapterSummaryEntity> summaries, String prevChapterTail,
                                    List<String> pendingConflicts, List<QualityDebtEntity> qualityDebts) {
        return buildMemoryPrefix(summaries, prevChapterTail, pendingConflicts, qualityDebts, null);
    }

    /**
     * 带阶段蓝图的重载：stageBlueprint 非空时在境界锁定与摘要之间渲染【阶段蓝图】节
     * （方向先于细节——规划层先看阶段方向再看局部连续性）
     *
     * @param stageBlueprint 当前生效的阶段蓝图（滚动大纲），可为 null（无蓝图模式）
     */
    public String buildMemoryPrefix(List<ChapterSummaryEntity> summaries, String prevChapterTail,
                                    List<String> pendingConflicts, List<QualityDebtEntity> qualityDebts,
                                    StageBlueprintEntity stageBlueprint) {
        return buildMemoryPrefix(summaries, prevChapterTail, pendingConflicts, qualityDebts, stageBlueprint, null);
    }

    /**
     * 带相关性唤醒的重载：recallHits 为故事记忆层（二期/三期）检索命中，
     * 非空时在摘要之后渲染【久远记忆·相关性唤醒】节（已过 minScore 门控与字符预算）。
     * 无卷调用：退化为不含卷方向锚的旧行为
     *
     * @param recallHits 久远记忆检索命中（分数降序），可为 null/空（无唤醒）
     */
    public String buildMemoryPrefix(List<ChapterSummaryEntity> summaries, String prevChapterTail,
                                    List<String> pendingConflicts, List<QualityDebtEntity> qualityDebts,
                                    StageBlueprintEntity stageBlueprint, List<StoryMemoryService.RecallHit> recallHits) {
        return buildMemoryPrefix(summaries, prevChapterTail, pendingConflicts, qualityDebts,
                stageBlueprint, null, recallHits);
    }

    /**
     * 整串视图（章节计划输入段等仍按单块消费）：等价于 {@link #buildMemoryBlocks} 以块间分隔符连接。
     * 正文路径应改用 buildMemoryBlocks 分块消费——单块交给总额守门只能整体截尾
     */
    public String buildMemoryPrefix(List<ChapterSummaryEntity> summaries, String prevChapterTail,
                                    List<String> pendingConflicts, List<QualityDebtEntity> qualityDebts,
                                    StageBlueprintEntity stageBlueprint, VolumeBlueprintEntity volume,
                                    List<StoryMemoryService.RecallHit> recallHits) {
        // 无单章计划（该重载供规划/复核路径使用）：timeAdvance 为 null——时序锚按锁定值渲染
        return joinMemoryBlocks(buildMemoryBlocks(summaries, prevChapterTail, pendingConflicts,
                qualityDebts, stageBlueprint, volume, recallHits, null));
    }

    /**
     * 记忆前缀的分块视图（2026-09-28）：各节独立成块交由 PromptBudgetGuard 装配，不再整块交给总额守门——
     * 单块只能"整体截尾"，而尾部恰是最近/纠错类内容（上章结尾 → 质量债 → 偏差警示 先被牺牲），
     * 中部三账本/唤醒却与末态红线部分重复地更早保住，牺牲顺序与价值顺序相反。
     * 拆分后牺牲顺序由 priority 决定（刻度见 PromptBudgetGuard 类注释），渲染顺序仍为旧版拼接顺序
     * （唯一例外：三账本/伏笔账在正文前缀中被 {@code ChapterWorker} 抽出挪至末尾，见其装配处注释）
     *
     * @param volume 当前章所属卷（调用方用 RollingOutlineService.volumeAt 按章号选取），可为 null
     */
    public List<PromptBudgetGuard.Block> buildMemoryBlocks(List<ChapterSummaryEntity> summaries,
                                                           String prevChapterTail,
                                                           List<String> pendingConflicts,
                                                           List<QualityDebtEntity> qualityDebts,
                                                           StageBlueprintEntity stageBlueprint,
                                                           VolumeBlueprintEntity volume,
                                                           List<StoryMemoryService.RecallHit> recallHits,
                                                           String timeAdvance) {
        if (summaries == null || summaries.isEmpty()) {
            // 摘要为空 = 新书首段：**不能整块清空**——方向类块不依赖摘要，仍须注入。
            // 实测事故：首段计划输入仅剩[故事设定]，阶段蓝图/卷方向锚全部丢失，
            // 计划层据此编出超龄事件（写手只忠实执行契约），阶段出口条件达成率仅 2/5。
            // 年龄约束由调用方经 prependSettingsAnchorIfNoSummaries 追加（摘要锚此时无从产生）。
            List<PromptBudgetGuard.Block> blocks = new ArrayList<>();
            StringBuilder direction = new StringBuilder();
            renderVolumeDirection(direction, volume, stageBlueprint);
            if (direction.length() > 0) {
                blocks.add(section("卷方向锚", MEMORY_PRIORITY_DIRECTION, true, direction));
            }
            StringBuilder blueprintBody = new StringBuilder();
            renderStageBlueprint(blueprintBody, stageBlueprint);
            if (blueprintBody.length() > 0) {
                blocks.add(section("阶段蓝图", MEMORY_PRIORITY_DIRECTION, true, blueprintBody));
            }
            return blocks;
        }
        List<ChapterSummaryEntity> ordered = ordered(summaries);
        int latestNo = ordered.get(ordered.size() - 1).getChapterNo();

        List<PromptBudgetGuard.Block> blocks = new ArrayList<>();

        // 境界锁定+头部：账本驱动的数值连续性硬约束，置于最顶部；极小且截半即误导，故不可截断
        StringBuilder head = new StringBuilder("==== 以下为前情记忆，正文必须与其保持一致，不得出现与之矛盾的事实 ====\n");
        String realm = latestCultivationRealm(ordered);
        if (realm != null) {
            head.append("【主角境界锁定】主角当前真实境界：").append(realm)
                    .append("。此为系统强制设定，严禁在正文中擅自提升、降低或改变；")
                    .append("仅当本章计划的关键事件明确包含突破/晋升时才允许变化，且变化必须有过程铺垫与代价。\n\n");
        }
        blocks.add(section("境界锁定", MEMORY_PRIORITY_REALM_LOCK, false, head));

        // 时序锚：主角当前年龄与故事时间。与境界锁定同级、同样不可截断——
        // 26-30 章批次因计划 prompt 里"当前月龄出现 0 次"，把圣经 band"21-30 章步入小学／自学高阶数学"
        // 直接落到了十一个月大的婴儿身上（写数论证明、列乘法竖式）。年龄必须像境界一样每路显式锁定：
        // 认知可超前、媒介不可超前。无年龄事实时**整块不加入**（不编造、也不留空标签占位）。
        String timeAnchor = ConsistencyIndexService.renderTimeAnchor(ordered);
        if (StringUtils.isNotBlank(timeAnchor)) {
            // 时序锚 v2境界式）：年龄/时间升级为锁定值——禁止擅自增减，
            // 唯一合法推进通道是计划声明的 timeAdvance（与【主角境界锁定】同一数据流）。
            // 45 章时间冻结（2002 年 7 月走了 2-3 周）的根因就是旧锚只"描述推算"、无推进通道
            String locked = timeAnchor + "\n【时间锁】年龄/故事时间以上述值为准，禁止在正文中擅自增长、倒退或跳跃；"
                    + (StringUtils.isNotBlank(timeAdvance)
                            ? "本章计划允许推进至：" + timeAdvance + "（唯一合法推进通道，须有场景交代过夜/生日/学期跨越）。"
                            : "本章无推进额度，故事时间保持不变。");
            blocks.add(section("时序锚", MEMORY_PRIORITY_REALM_LOCK, false, new StringBuilder(locked)));
        }

        // 卷方向锚：全书长期锚（卷主旨/承转合/卷级伏笔/卷内弧清单），渲染于阶段蓝图之前
        StringBuilder direction = new StringBuilder();
        renderVolumeDirection(direction, volume, stageBlueprint);
        blocks.add(section("卷方向锚", MEMORY_PRIORITY_DIRECTION, true, direction));

        // 阶段蓝图：滚动大纲的方向锚定（方向先于细节），渲染于摘要之前
        StringBuilder blueprintBody = new StringBuilder();
        renderStageBlueprint(blueprintBody, stageBlueprint);
        blocks.add(section("阶段蓝图", MEMORY_PRIORITY_DIRECTION, true, blueprintBody));

        // 近 N 章剧情摘要。**倒序渲染（最新在前）**：本块可截断，而 PromptBudgetGuard 的截断从
        // **尾部**切（truncate 取 substring(0, cut)）——旧→新渲染会让"最新一章的摘要"最先被切掉，
        // 而它恰是衔接本章最要紧的一条。与伏笔账"尾部放最低分条目抗截"是同一条纪律：
        // 块内条目按重要性降序排，截断只伤最不要紧的那端。
        StringBuilder recent = new StringBuilder("【前章剧情摘要】\n");
        // 最新时点紧随表头：它是本章时间连续性的硬锚（只一行），排尾会在截断时先丢
        if (ordered.get(ordered.size() - 1).getTimePoint() != null) {
            recent.append("最新时点：").append(ordered.get(ordered.size() - 1).getTimePoint()).append("\n");
        }
        List<ChapterSummaryEntity> recentWindow = ordered.subList(
                Math.max(0, ordered.size() - RECENT_SUMMARY_COUNT), ordered.size());
        for (int i = recentWindow.size() - 1; i >= 0; i--) {
            ChapterSummaryEntity s = recentWindow.get(i);
            recent.append("- 第").append(s.getChapterNo()).append("章《")
                    .append(nullToBlank(s.getTitle())).append("》：")
                    .append(nullToBlank(s.getSummary())).append("\n");
        }
        // 配角轨迹垫底：块内重要性最低，承担截断
        appendCharacterBeats(recent, ordered);
        blocks.add(section("近章摘要", MEMORY_PRIORITY_RECENT, true, recent));

        // 久远记忆·相关性唤醒：向量检索注入（窗口外章节 + 账本/设定），分数降序，尾部的命中最低分
        StringBuilder recall = new StringBuilder();
        renderRecallHits(recall, recallHits);
        blocks.add(section("久远唤醒", MEMORY_PRIORITY_RECALL, true, recall));

        // 三账本：角色 / 物品 / 势力（活跃条目进前缀，沉寂条目并入沉淀名单防膨胀）
        StringBuilder ledger = new StringBuilder();
        appendLedger(ledger, "角色账本·当前状态", ordered, ChapterSummaryEntity::getCharacterStates, latestNo, true);
        appendLedger(ledger, "物品账本·当前状态", ordered, ChapterSummaryEntity::getItemStates, latestNo, false);
        appendLedger(ledger, "势力账本·当前状态", ordered, ChapterSummaryEntity::getFactionStates, latestNo, false);
        blocks.add(section(LEDGER_BLOCK_LABEL, MEMORY_PRIORITY_LEDGER, true, ledger));

        // 伏笔账：新埋减去已回收；动态权重分级渲染（新埋/硬/软/背景/未填），尾部为最低分背景条目
        StringBuilder foreshadow = new StringBuilder();
        renderForeshadowing(foreshadow, ordered);
        blocks.add(section(FORESHADOW_BLOCK_LABEL, MEMORY_PRIORITY_LEDGER, true, foreshadow));

        // 上一章一致性偏差警示（软校验结果回灌，本章以账本为准修正）
        StringBuilder conflicts = new StringBuilder();
        if (pendingConflicts != null && !pendingConflicts.isEmpty()) {
            conflicts.append("【上章偏差警示】上一章正文存在与账本冲突的描述，本章必须以账本为准，不得延续错误：\n");
            for (String conflict : pendingConflicts) {
                conflicts.append("- ").append(conflict).append("\n");
            }
        }
        blocks.add(section("偏差警示", MEMORY_PRIORITY_RECENT, true, conflicts));

        // 上章质量债：审校确认未修复的 BLOCKING 问题，防同类问题复发
        StringBuilder debts = new StringBuilder();
        renderQualityDebts(debts, qualityDebts);
        blocks.add(section("质量债", MEMORY_PRIORITY_RECENT, true, debts));

        // 上一章结尾原文（衔接文风与悬念语气）——明确标注为禁止重复，防止LLM原样复述
        StringBuilder tail = new StringBuilder();
        if (StringUtils.isNotBlank(prevChapterTail)) {
            tail.append("【上一章结尾原文·禁止重复】以下内容仅用于衔接参考，严禁在本章正文中原样复述或大段重复（与上文文字重复不得超过30字）。本章必须从上文结束的时间点之后继续推进，写后续结果/反应/下一句对话，不得重复上文已写的动作/场景/对话：\n")
                    .append(prevChapterTail.trim()).append("\n");
        }
        blocks.add(section("上章结尾", MEMORY_PRIORITY_DIRECTION, true, tail));

        logMemorySections(latestNo, blocks);
        return blocks;
    }

    /**
     * 新书首段兜底：摘要为空时，把"设定兜底年龄锚"作为记忆首块（时序锚，priority 与境界锁定同级、
     * 不可截断）追加进记忆块列表。
     *
     * <p><b>为什么需要</b>：{@link #buildMemoryBlocks} 以摘要为输入，无摘要时产不出年龄锚——
     * 首段计划与第一章会在"零年龄约束"下生成（2026-10-03 实测：4 岁主角首段做出超龄行为，
     * 且审校年龄判据因 guard 无锚而不触发）。年龄从故事设定保守提取，命中从严（见
     * {@link ConsistencyIndexService#renderSettingsAgeAnchor}）。
     *
     * <p><b>有摘要时不追加</b>：以摘要锚为准，防止把故事起始年龄钉死在中期。
     */
    public void prependSettingsAnchorIfNoSummaries(List<PromptBudgetGuard.Block> blocks,
                                                   List<ChapterSummaryEntity> summaries,
                                                   String worldSetting, String protagonist, String outline) {
        if (blocks == null || (summaries != null && !summaries.isEmpty())) {
            return;
        }
        String anchor = ConsistencyIndexService.renderSettingsAgeAnchor(worldSetting, protagonist, outline);
        if (StringUtils.isBlank(anchor)) {
            return;
        }
        blocks.add(0, section("时序锚", MEMORY_PRIORITY_REALM_LOCK, false, new StringBuilder(anchor)));
    }

    /** 记忆子块在正文前缀中的优先级（刻度见 PromptBudgetGuard 类注释：数值小者先保住） */
    private static final int MEMORY_PRIORITY_REALM_LOCK = 1;
    private static final int MEMORY_PRIORITY_RECENT = 2;
    private static final int MEMORY_PRIORITY_DIRECTION = 3;
    /**
     * 三账本/伏笔账：由 4 上调至 <b>3</b>（2026-10-19 实测）。
     *
     * <p><b>为什么必须上调</b>：连续多章（实测 169-175）出现
     * {@code 截断[三账本, 伏笔账]}——**整块被丢弃**，写手完全看不到角色/物品/势力状态与
     * 在途伏笔。后果不是"文风变差"而是**事实编造**：同一资产的价格在 163 章是「三块出头」，
     * 而三账本被截断后的 166/172 章写成了「十几美元」「十七块跌到九块」，
     * 且错值随后被摘要登记进账本、反而成了正典。
     *
     * <p>三账本不是"背景补完"而是**事实基准**：与禁泄清单、阶段蓝图同属"缺了就写错"的一类。
     * 排在"久远唤醒"(5)之后被优先牺牲，方向本身就是反的——远期回忆缺一章只损失氛围，
     * 账本缺一章直接产出与设定矛盾的正文。
     */
    private static final int MEMORY_PRIORITY_LEDGER = 3;

    /**
     * 两个账本块的 label（公开供 ChapterWorker 按 label 抽取）：正文前缀中这两个块被挪到
     * 门禁类块之后——它们是第 4 档的主要消耗方（常合计 5k+ 字），排末尾才能让同档的
     * 情节模式黑名单/审校反馈两个小纠正通道先入场、由账本承担截断（见 ChapterWorker 装配处注释）
     */
    public static final String LEDGER_BLOCK_LABEL = "三账本";
    public static final String FORESHADOW_BLOCK_LABEL = "伏笔账";

    /**
     * 久远回唤的优先级：2026-09-29 由 6 提到 4，2026-10-01 又由 4 降至 <b>5</b>。
     *
     * <p><b>提到 4 的理由（仍然成立）</b>：三账本只覆盖最近 {@link #ACTIVE_WINDOW} 章的活跃窗口，
     * 超过窗口的历史除了被裁掉的近章摘要，**唯一通路就是回唤**——把它排在账本之后丢弃，
     * 等于"预算一紧就先忘记远期、保留近期"，与长篇连载对长程一致性的需求方向相反。
     *
     * <p><b>为什么又降回 5（2026-10-01 实测）</b>：第 8/12/13 章连续出现
     * 「丢弃[疲劳词红线、审校反馈、风格警示、情节模式黑名单]」——降回 5 是为了给这三块**行为指导**让位。
     * 两者的代价不对等：行为指导块每章都被写手消费，缺一块就直接导致同类问题**每章重犯**
     * （同批超长章与禁泄违例连续三批反复发作，高度怀疑与此相关）；
     * 而回唤只是背景补完，缺一两章不破坏连贯。回唤仍**高于**文风指纹(7)与情节模式黑名单(8)，
     * 并未退回最早的"最先被牺牲"位置。
     */
    private static final int MEMORY_PRIORITY_RECALL = 5;

    /** 记忆节 → 装配块：内容两端空白归一（块间距由装配器分隔符统一负责）；空白节由装配器过滤 */
    private static PromptBudgetGuard.Block section(String label, int priority, boolean truncatable, StringBuilder body) {
        return new PromptBudgetGuard.Block(label, priority, truncatable, body.toString().strip());
    }

    /** 实体档案封顶：单次最多唤醒的休眠实体数 */
    static final int DOSSIER_LIMIT = 3;
    /** 单个实体档案的字符封顶（超出截断，档案是增益件不挤占记忆预算） */
    static final int DOSSIER_CHARS = 260;

    /**
     * 实体档案块（docs/enhancement-plan.md E1）：休眠超过 {@link #ACTIVE_WINDOW} 章的实体，
     * 其早期设定与当前状态既不在近章摘要窗口内，语义检索也未必按名词精确命中——
     * 本块把休眠实体的 首见章/上次出现/当前状态/关联关系/早期事实 组装成档案注入。
     *
     * <p>两种模式：{@code item} 非空（正文路径）=只注入本章计划涉及的实体
     * （characters 精确命中 + goal/keyEvents/endingHook 文本包含 + 别名词典命中）；
     * {@code item} 为空（规划路径）=注入最久未见的休眠实体——规划层据此安排"谁重新出场"，
     * 避免配角蒸发。封顶 {@link #DOSSIER_LIMIT} 个，按最久未见优先。
     * 休眠判定复用 {@code buildLedger} 的 lastChapterNo 语义（每次提及刷新）。
     * 三账本为空或无休眠实体时返回空列表（首发/冷启动不注入）。
     */
    public List<PromptBudgetGuard.Block> buildEntityDossierBlocks(List<ChapterSummaryEntity> summaries,
                                                                  ChapterPlanItemEntity item,
                                                                  ConsistencyIndexEntity index) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<ChapterSummaryEntity> ordered = ordered(summaries);
        int latestNo = ordered.get(ordered.size() - 1).getChapterNo();
        List<LedgerEntry> entries = new ArrayList<>(buildCharacterLedger(summaries));
        entries.addAll(buildLedger(summaries, ChapterSummaryEntity::getItemStates));
        entries.addAll(buildLedger(summaries, ChapterSummaryEntity::getFactionStates));
        List<LedgerEntry> dormant = entries.stream()
                .filter(e -> e != null && e.getName() != null && e.getLastChapterNo() != null
                        && e.getLastChapterNo() <= latestNo - ACTIVE_WINDOW)
                .sorted(Comparator.comparingInt(e -> e.getLastChapterNo()))
                .toList();
        if (dormant.isEmpty()) {
            return List.of();
        }
        String mentionText = item == null ? null : itemMentionText(item);
        // 所有实体合并进同一个「实体档案」块：预算守卫要求场景内 label 唯一，
        // 多实体拆多块会同 label 触发"前缀装配块 label 重复" 实测炸过规划路径）
        StringBuilder dossierBody = new StringBuilder();
        int injected = 0;
        for (LedgerEntry entry : dormant) {
            if (injected >= DOSSIER_LIMIT) {
                break;
            }
            if (item != null && !mentioned(entry.getName(), item, mentionText, index)) {
                continue;
            }
            if (injected > 0) {
                dossierBody.append("\n\n");
            }
            dossierBody.append(renderDossier(entry, ordered, index));
            injected++;
        }
        if (injected == 0) {
            return List.of();
        }
        return List.of(section("实体档案", MEMORY_PRIORITY_LEDGER, true, dossierBody));
    }

    /** 本章计划的提及文本：goal + keyEvents + endingHook 拼接（characters 走精确命中，不进文本匹配） */
    private String itemMentionText(ChapterPlanItemEntity item) {
        StringBuilder text = new StringBuilder();
        if (StringUtils.isNotBlank(item.getGoal())) {
            text.append(item.getGoal()).append('；');
        }
        if (item.getKeyEvents() != null) {
            text.append(String.join("；", item.getKeyEvents())).append('；');
        }
        if (StringUtils.isNotBlank(item.getEndingHook())) {
            text.append(item.getEndingHook());
        }
        return text.toString();
    }

    /**
     * 实体是否被本章计划提及：characters 精确命中 → 计划文本包含 → 别名词典命中
     * （TermEntry.canonical 的别名出现在计划文本里，档案按 canonical 出）
     */
    private boolean mentioned(String name, ChapterPlanItemEntity item, String mentionText,
                              ConsistencyIndexEntity index) {
        if (item.getCharacters() != null && item.getCharacters().contains(name)) {
            return true;
        }
        if (mentionText != null && mentionText.contains(name)) {
            return true;
        }
        if (index != null && index.getTerms() != null) {
            for (ConsistencyIndexEntity.TermEntry term : index.getTerms()) {
                if (term == null || !name.equals(term.getCanonical()) || term.getAliases() == null) {
                    continue;
                }
                for (String alias : term.getAliases()) {
                    if (StringUtils.isBlank(alias)) {
                        continue;
                    }
                    if ((mentionText != null && mentionText.contains(alias))
                            || (item.getCharacters() != null && item.getCharacters().contains(alias))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 单实体档案：首见/上次出现/当前状态 + 机制 + 关联关系（最近 2 条）+ 最早 2 条原始事实 */
    private StringBuilder renderDossier(LedgerEntry entry, List<ChapterSummaryEntity> ordered,
                                        ConsistencyIndexEntity index) {
        StringBuilder sb = new StringBuilder("【实体档案·").append(entry.getName()).append("】")
                .append("首见第").append(entry.getFirstChapterNo()).append("章，上次出现第")
                .append(entry.getLastChapterNo()).append("章");
        if (StringUtils.isNotBlank(entry.getStatus())) {
            sb.append("；当前状态：").append(entry.getStatus());
        }
        if (entry.isReversalSuspect()) {
            sb.append("；【复活存疑·待人工确认】");
        }
        if (index != null) {
            if (index.getMechanisms() != null) {
                index.getMechanisms().stream()
                        .filter(m -> m != null && entry.getName().equals(m.getName()))
                        .findFirst()
                        .ifPresent(m -> sb.append("；机制：").append(m.getName())
                                .append(m.getUsageInterval() == null ? ""
                                        : "（使用间隔 " + m.getUsageInterval() + " 章）"));
            }
            if (index.getRelations() != null) {
                index.getRelations().stream()
                        .filter(r -> r != null && r.getPair() != null && r.getPair().contains(entry.getName()))
                        .sorted(Comparator.comparing(r -> r.getLastChapter() == null ? 0 : r.getLastChapter(),
                                Comparator.reverseOrder()))
                        .limit(2)
                        .forEach(r -> sb.append("；关系：").append(r.getPair()).append("——").append(r.getRelation())
                                .append(r.getLastChapter() == null ? "" : "（第" + r.getLastChapter() + "章）"));
            }
        }
        int earlyFacts = 0;
        for (ChapterSummaryEntity summary : ordered) {
            if (earlyFacts >= 2) {
                break;
            }
            if (summary.getConsistencyFacts() == null) {
                continue;
            }
            for (ChapterSummaryEntity.ConsistencyFact fact : summary.getConsistencyFacts()) {
                if (earlyFacts >= 2) {
                    break;
                }
                if (fact == null || "RELATION".equalsIgnoreCase(fact.getType())
                        || StringUtils.isBlank(fact.getValue())
                        || fact.getSubject() == null || !fact.getSubject().contains(entry.getName())) {
                    continue;
                }
                sb.append("；第").append(summary.getChapterNo()).append("章：")
                        .append(StringUtils.abbreviate(fact.getValue(), 60));
                earlyFacts++;
            }
        }
        if (sb.length() > DOSSIER_CHARS) {
            sb.setLength(DOSSIER_CHARS);
            sb.append("……");
        }
        return sb;
    }

    /** 分块视图回退为整串（章节计划输入段等仍按单块消费）：分隔符与装配器口径一致 */
    private static String joinMemoryBlocks(List<PromptBudgetGuard.Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (PromptBudgetGuard.Block block : blocks) {
            if (StringUtils.isBlank(block.content())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SECTION_SEPARATOR);
            }
            sb.append(block.content());
        }
        return sb.toString();
    }

    /**
     * 各节字数一行落盘（grep「前缀预算」得增长曲线）：分段取自块本身，不再维护平行长度变量——
     * 旧结构里头部分段在卷方向/蓝图渲染之后才取值，"头部/境界"实际含后两者（各段之和大于合计）
     */
    private static void logMemorySections(int latestNo, List<PromptBudgetGuard.Block> blocks) {
        StringBuilder detail = new StringBuilder();
        int total = 0;
        int shown = 0;
        for (PromptBudgetGuard.Block block : blocks) {
            if (StringUtils.isBlank(block.content())) {
                continue;
            }
            if (detail.length() > 0) {
                detail.append("｜");
            }
            detail.append(block.label()).append(block.content().length());
            total += block.content().length();
            shown++;
        }
        log.info("前缀预算[前情记忆] 第{}章 合计{}字 = {}",
                latestNo, total + Math.max(0, shown - 1) * SECTION_SEPARATOR.length(), detail);
    }

    private void appendCharacterBeats(StringBuilder sb, List<ChapterSummaryEntity> summaries) {
        List<String> lines = new ArrayList<>();
        for (ChapterSummaryEntity summary : summaries) {
            if (summary.getCharacterBeats() == null) continue;
            for (ChapterSummaryEntity.CharacterBeat beat : summary.getCharacterBeats()) {
                if (beat == null || StringUtils.isBlank(beat.getName())) continue;
                StringBuilder line = new StringBuilder("- 第").append(summary.getChapterNo()).append("章 ")
                        .append(beat.getName());
                if (StringUtils.isNotBlank(beat.getGoal())) line.append("｜目标：").append(beat.getGoal());
                if (StringUtils.isNotBlank(beat.getDecision())) line.append("｜决定：").append(beat.getDecision());
                if (StringUtils.isNotBlank(beat.getConsequence())) line.append("｜后果：").append(beat.getConsequence());
                if (StringUtils.isNotBlank(beat.getRelationshipChange())) line.append("｜关系变化：").append(beat.getRelationshipChange());
                if (StringUtils.isNotBlank(beat.getNextIntent())) line.append("｜后续意图：").append(beat.getNextIntent());
                lines.add(line.toString());
            }
        }
        if (!lines.isEmpty()) {
            sb.append("\n【配角独立行动轨迹】\n");
            int from = Math.max(0, lines.size() - 12);
            lines.subList(from, lines.size()).forEach(line -> sb.append(line).append("\n"));
        }
    }

    private void renderRecallHits(StringBuilder sb, List<StoryMemoryService.RecallHit> recallHits) {
        if (recallHits == null || recallHits.isEmpty()) {
            return;
        }
        sb.append("\n【久远记忆·相关性唤醒】（经相关性检索从较早章节/设定中唤醒，仅供衔接参考；若与本章场景无关可忽略）\n");
        for (StoryMemoryService.RecallHit hit : recallHits) {
            sb.append("- ").append(hit.text().replace('\n', '；')).append("\n");
        }
    }

    /**
     * 从摘要列表重建角色账本（保留方法名以示领域语义）。
     * 角色账本启用"死亡→复活反转"存疑标记（物品/势力无此语义）
     */
    public List<LedgerEntry> buildCharacterLedger(List<ChapterSummaryEntity> summaries) {
        return buildLedger(summaries, ChapterSummaryEntity::getCharacterStates, true);
    }

    /**
     * **角色名册（仅姓名）**：规划层「对话承载」机械校验的人名来源——
     * "事件里是否出现两个已知人物"的判定基准。只认账本里真实登场过的角色，
     * 不含物品/势力（同名条目在三类账本间互不混用）。空摘要（新书首段）安全返回空表，
     * 调用方据此降级为人名核验跳过（见 validateDialogueCarriage 的池不足口径）。
     */
    public List<String> characterRoster(List<ChapterSummaryEntity> summaries) {
        return buildLedger(summaries, ChapterSummaryEntity::getCharacterStates, true).stream()
                .map(LedgerEntry::getName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    /**
     * 渲染当前三账本（角色/物品/势力）文本，作为摘要一致性校验与审校账本比对基准
     */
    public String renderLedgerPrompt(List<ChapterSummaryEntity> summaries) {
        StringBuilder sb = new StringBuilder();
        appendLedgerPromptLines(sb, "角色", buildCharacterLedger(summaries));
        appendLedgerPromptLines(sb, "物品", buildLedger(summaries, ChapterSummaryEntity::getItemStates));
        appendLedgerPromptLines(sb, "势力", buildLedger(summaries, ChapterSummaryEntity::getFactionStates));
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 末态红线携带的持有物条数上限（按最近更新章号取最新） */
    private static final int EDGE_STATE_ITEM_LIMIT = 5;

    /**
     * 账本末态红线：从账本提炼"截至上一章结束时"的主角位置/境界/持有关键物，一行式硬约束。
     * 治"正文开头承接上一幕、账本记录章末状态"的衔接类矛盾——连续 9 章 consistency 债的
     * 复发根因是这三要素被几十行账本淹没，单独置顶让正文与修订都有明确的对齐锚点。
     * 无任何可提炼事实时返回空串（第 1 章前）
     */
    public String renderLedgerEdgeState(List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return "";
        }
        List<ChapterSummaryEntity> ordered = ordered(summaries);
        ChapterSummaryEntity latest = ordered.get(ordered.size() - 1);
        StringBuilder sb = new StringBuilder(
                "==== 以下为账本末态红线（截至上一章结束时的既成事实），正文开场的人物位置、持有物、修为必须与其一致；位置/持有物变化必须有过渡描写，严禁无过渡跳变 ====\n");
        int facts = 0;
        if (StringUtils.isNotBlank(latest.getTimePoint())) {
            sb.append("- 时间与地点：").append(latest.getTimePoint()).append("\n");
            facts++;
        }
        String realm = latestCultivationRealm(ordered);
        if (StringUtils.isNotBlank(realm)) {
            sb.append("- 主角境界：").append(realm).append("\n");
            facts++;
        }
        List<LedgerEntry> items = new ArrayList<>(buildLedger(ordered, ChapterSummaryEntity::getItemStates));
        items.sort(Comparator.comparing(LedgerEntry::getLastChapterNo,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<LedgerEntry> topItems = items.subList(0, Math.min(EDGE_STATE_ITEM_LIMIT, items.size()));
        if (!topItems.isEmpty()) {
            sb.append("- 持有关键物：");
            for (LedgerEntry item : topItems) {
                sb.append(item.getName()).append("（").append(item.getStatus()).append("）");
            }
            sb.append("\n");
            facts++;
        }
        return facts == 0 ? "" : sb.toString().trim();
    }

    private void appendLedgerPromptLines(StringBuilder sb, String kind, List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        sb.append("【").append(kind).append("账本】\n");
        for (LedgerEntry entry : entries) {
            String status = entry.isReversalSuspect()
                    ? "【复活存疑·待人工确认】" + entry.getStatus() : entry.getStatus();
            String trajectory = trajectorySuffix(entry);
            sb.append("- ").append(entry.getName()).append("：").append(status).append(trajectory);
            // 来源章追溯：变化条目的章号已在轨迹后缀中，稳定条目在此标明状态出自哪一章
            if (trajectory.isEmpty() && entry.getLastChapterNo() != null) {
                sb.append("（来源：第").append(entry.getLastChapterNo()).append("章）");
            }
            // 存疑条目附证据原文供人工核对原文（正常条目不渲染，控制前缀体积）
            if (entry.isReversalSuspect() && StringUtils.isNotBlank(entry.getEvidence())) {
                sb.append("〔证据：").append(entry.getEvidence()).append("〕");
            }
            sb.append("\n");
        }
    }

    /**
     * 通用账本合并：按章序滚动，后章覆盖同名条目状态，记录首现章与最近更新章；
     * 仅在状态真正变化时记录前一状态（prevStatus），供渲染"旧 → 新"变化轨迹
     *
     * @param extractor 从摘要提取对应状态条目列表的字段访问器
     */
    public List<LedgerEntry> buildLedger(List<ChapterSummaryEntity> summaries,
                                         Function<ChapterSummaryEntity, List<ChapterSummaryEntity.StateEntry>> extractor) {
        return buildLedger(summaries, extractor, false);
    }

    /**
     * @param deathReversalCheck 启用"死亡→非死亡且无明确复活描写"反转存疑标记（角色账本语义）
     */
    public List<LedgerEntry> buildLedger(List<ChapterSummaryEntity> summaries,
                                         Function<ChapterSummaryEntity, List<ChapterSummaryEntity.StateEntry>> extractor,
                                         boolean deathReversalCheck) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        Map<String, LedgerEntry> ledger = new LinkedHashMap<>();
        for (ChapterSummaryEntity summary : ordered(summaries)) {
            List<ChapterSummaryEntity.StateEntry> states = extractor.apply(summary);
            if (states == null) {
                continue;
            }
            for (ChapterSummaryEntity.StateEntry state : states) {
                if (state == null || StringUtils.isBlank(state.getName()) || StringUtils.isBlank(state.getStatus())) {
                    continue;
                }
                String name = state.getName().trim();
                String newStatus = state.getStatus().trim();
                String evidence = StringUtils.trimToNull(state.getEvidence());
                LedgerEntry existing = ledger.get(name);
                if (existing == null) {
                    ledger.put(name, new LedgerEntry(name, newStatus, summary.getChapterNo(),
                            summary.getChapterNo(), null, evidence, false));
                } else {
                    if (!existing.getStatus().equals(newStatus)) {
                        existing.setPrevStatus(existing.getStatus());
                        existing.setStatus(newStatus);
                        // 状态变化即换用新章证据（旧证据支持的是旧状态）
                        existing.setEvidence(evidence);
                        if (deathReversalCheck && isSuspiciousDeathReversal(existing.getPrevStatus(), newStatus)) {
                            existing.setReversalSuspect(true);
                        }
                    }
                    existing.setLastChapterNo(summary.getChapterNo());
                }
            }
        }
        return new ArrayList<>(ledger.values());
    }

    /** 死亡标记词表：上一状态命中即视为"曾死亡" */
    private static final List<String> DEATH_MARKERS =
            List.of("死亡", "陨落", "身亡", "毙命", "战死", "殒命", "尸");
    /** 明确复活描写词表：新状态命中则视为合法反转剧情，不标记存疑 */
    private static final List<String> RESURRECTION_MARKERS =
            List.of("复活", "还魂", "起死回生", "重生归来");

    /**
     * 死亡→复活反转存疑规则（事实变更规则表之一）：上一状态含死亡标记、新状态不含死亡标记
     * 且无明确复活描写 → 存疑（账本渲染强制人工确认标记）。有明确复活描写的合法反转不标记
     */
    static boolean isSuspiciousDeathReversal(String prevStatus, String newStatus) {
        if (StringUtils.isBlank(prevStatus) || StringUtils.isBlank(newStatus)) {
            return false;
        }
        boolean wasDead = DEATH_MARKERS.stream().anyMatch(prevStatus::contains);
        boolean stillDead = DEATH_MARKERS.stream().anyMatch(newStatus::contains);
        boolean explicitlyResurrected = RESURRECTION_MARKERS.stream().anyMatch(newStatus::contains);
        return wasDead && !stillDead && !explicitlyResurrected;
    }

    /**
     * as-of 事实快照：仅用 chapterNo 之前的摘要重建账本，把账本的时序语义显式成 API。
     * 逐章循环中审校与摘要比对天然消费 as-of(当前章-1) 状态（当章摘要尚未入账）；
     * 未来如需任意章号回溯（回忆章/多视角比对），复用此入口，无须独立事实存储——
     * 摘要列表本身就是时间线
     */
    public List<LedgerEntry> factsAsOf(List<ChapterSummaryEntity> summaries, int chapterNo,
                                       Function<ChapterSummaryEntity, List<ChapterSummaryEntity.StateEntry>> extractor) {
        List<ChapterSummaryEntity> truncated = summaries.stream()
                .filter(s -> s.getChapterNo() != null && s.getChapterNo() < chapterNo)
                .toList();
        return buildLedger(truncated, extractor);
    }

    /**
     * 状态变化轨迹后缀：仅最近一次真实状态变化才产生轨迹（同值重复更新不算变化）。
     * 供记忆前缀与审校账本基准共用——让"位置瞬移/死者复活/伤势无端消失"类跳变
     * 在渲染层显形，审校据此要求变化过程必须在正文中有描写
     */
    public static String trajectorySuffix(LedgerEntry entry) {
        if (entry.getPrevStatus() == null || entry.getPrevStatus().equals(entry.getStatus())) {
            return "";
        }
        return "（第" + entry.getLastChapterNo() + "章变化：" + entry.getPrevStatus()
                + " → " + entry.getStatus() + "）";
    }

    /**
     * 伏笔账：全部新埋伏笔减去已回收项，按埋设顺序
     */
    public List<String> buildPendingForeshadowing(List<ChapterSummaryEntity> summaries) {
        return buildPendingForeshadowDetailed(summaries).stream()
                .map(PendingForeshadow::content)
                .toList();
    }

    /**
     * 待回收伏笔登记清单：**逾期项优先占位**，其余按埋设章号新→旧填满，总量封顶 limit。
     * 供摘要登记使用——未填（冻结）条目保留在内，剧情自然触达时仍可逐字登记核销出账；
     * 全量清单随章节数线性膨胀，此处统一封顶。
     *
     * <p><b>为什么要给逾期项留位</b>（2026-10-10）：此前是纯"最新 N 条"，于是**埋得越早的线越看不见**。
     * 而摘要 prompt 要求回收条目"逐字沿用账中描述"——一条掉出清单的线，模型根本无从逐字登记，
     * 于是它永远关不掉账、被反复要求回收。实测「2026」那条埋在第 0 章，早就掉出了最新的 20 条之外；
     * 体检同时报出「清账静默兑现 80 条（占弃置 66%）」，两者是同一个缺口的两面。
     *
     * <p>逾期 = 已过计划回收章（{@code scheduledPayoffChapter}），与"漏收伏笔数"指标同一口径——
     * 那个指标度量的问题，这里正是它的成因之一。
     */
    public List<String> buildForeshadowContextList(List<ChapterSummaryEntity> summaries, int limit) {
        List<PendingForeshadow> pending = buildPendingForeshadowDetailed(summaries);
        if (pending.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<ChapterSummaryEntity> ordered = ordered(summaries);
        int latestNo = ordered.get(ordered.size() - 1) == null
                ? 0 : (ordered.get(ordered.size() - 1).getChapterNo() == null
                ? 0 : ordered.get(ordered.size() - 1).getChapterNo());
        Set<String> overdueContents = ForeshadowSpanPolicy.pending(summaries, latestNo, List.of()).stream()
                .filter(p -> p.scheduledPayoffChapter() != null && latestNo > p.scheduledPayoffChapter())
                .map(ForeshadowSpanPolicy.Pending::content)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        // ① 逾期项先占位，滞留最久（埋设章号最小）的靠前
        pending.stream()
                .filter(p -> overdueContents.contains(p.content()))
                .sorted(Comparator.comparingInt(PendingForeshadow::chapterNo))
                .map(PendingForeshadow::content)
                .forEach(merged::add);
        // ② 再用最新若干填满（保持原有行为）
        pending.stream()
                .sorted(Comparator.comparingInt(PendingForeshadow::chapterNo).reversed())
                .map(PendingForeshadow::content)
                .forEach(merged::add);
        return merged.stream().limit(limit).toList();
    }

    /**
     * 带 **ID 前缀**的待回收清单（2026-10-13）：供**摘要模型**消费，格式 {@code [F7-2] 伏笔描述}。
     *
     * <p>与 {@link #buildForeshadowContextList} 的区别：那个返回纯文本描述（供文本匹配与既有 prompt
     * 路径使用，保持零改动），本方法额外附上机械层分配的稳定 ID，让模型能**回抄 ID** 而不是复述措辞。
     *
     * <p>排序口径与纯文本版一致（逾期项先占位、滞留最久靠前，再按最新填充），
     * 保证两个口径的清单在"哪些条目可见"上完全对齐——否则模型看得到的 ID 与实际账本对不上。
     * 无 ID 的老数据不带前缀，行为与纯文本版逐字相同。
     */
    public List<String> buildForeshadowContextListWithIds(List<ChapterSummaryEntity> summaries, int limit) {
        List<PendingForeshadow> pending = buildPendingForeshadowDetailed(summaries);
        if (pending.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<ChapterSummaryEntity> ordered = ordered(summaries);
        int latestNo = ordered.get(ordered.size() - 1) == null
                ? 0 : (ordered.get(ordered.size() - 1).getChapterNo() == null
                ? 0 : (ordered.get(ordered.size() - 1).getChapterNo()));
        Set<String> overdueContents = ForeshadowSpanPolicy.pending(summaries, latestNo, List.of()).stream()
                .filter(p -> p.scheduledPayoffChapter() != null && latestNo > p.scheduledPayoffChapter())
                .map(ForeshadowSpanPolicy.Pending::content)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

        LinkedHashSet<PendingForeshadow> merged = new LinkedHashSet<>();
        pending.stream()
                .filter(p -> overdueContents.contains(p.content()))
                .sorted(Comparator.comparingInt(PendingForeshadow::chapterNo))
                .forEach(merged::add);
        pending.stream()
                .sorted(Comparator.comparingInt(PendingForeshadow::chapterNo).reversed())
                .forEach(merged::add);
        return merged.stream().limit(limit).map(ChapterMemoryService::renderWithId).toList();
    }

    /** 渲染为 {@code [F7-2] 描述}；无 ID 时退化为纯描述（老数据） */
    private static String renderWithId(PendingForeshadow pending) {
        return StringUtils.isBlank(pending.foreshadowId())
                ? pending.content()
                : "[" + pending.foreshadowId() + "] " + pending.content();
    }

    // ==================== 伏笔 ID 形态工具（委托唯一实现 {@link ForeshadowIds}） ====================
    //
    // 形态定义已抽到 quality 包的 ForeshadowIds（2026-10-14）：消费端从两处涨到三处——
    // 本类的账本核销、ChapterSummaryService 的失配检测、ForeshadowSpanPolicy 的寿命/在途配对——
    // 三份实现稍有差异就会让"核销成功"与"仍在途"对同一条线给出相反结论。
    // 这里保留同名静态方法仅为兼容既有调用点，逻辑一律委托。

    /** 归一化 ID 形态：{@code [F7-2]} / {@code F7-2} / {@code （F7-2）} → {@code F7-2}；非 ID 返回 null */
    static String normalizeForeshadowIdForm(String value) {
        return ForeshadowIds.normalize(value);
    }

    /** 提取账本行的 ID 前缀（{@code [F7-2] 描述} → {@code F7-2}）；无前缀返回 null */
    static String extractForeshadowIdPrefix(String pendingLine) {
        return ForeshadowIds.extractPrefix(pendingLine);
    }

    /** 剥离账本行的 ID 前缀（{@code [F7-2] 描述} → {@code 描述}）；无前缀原样返回 */
    static String stripForeshadowIdPrefix(String pendingLine) {
        return ForeshadowIds.stripPrefix(pendingLine);
    }

    /**
     * 审校比对清单：登记清单剔除未填后的版本——未填已冻结、不再构成审校的回收义务，
     * 避免熔断条目被审校反复标记"未回收"造成 severity 回潮
     */
    public List<String> buildAuditForeshadowList(List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<ChapterSummaryEntity> ordered = ordered(summaries);
        int latestNo = ordered.get(ordered.size() - 1).getChapterNo() == null
                ? 0 : ordered.get(ordered.size() - 1).getChapterNo();
        return priorityService.score(buildPendingForeshadowDetailed(ordered), latestNo).stream()
                .filter(s -> s.tier() != ForeshadowPriorityService.Tier.BREAKER)
                .map(s -> s.item().content())
                .limit(FORESHADOW_CONTEXT_LIMIT)
                .toList();
    }

    /**
     * 阶段蓝图生成的伏笔输入：硬/软/未填三档逐条带分级标记（背景与新埋条目不构成阶段级编排压力）。
     * 未填保留在内——蓝图是阶段级编排者，冻结条目仅在能被剧情自然触达时可编排兑现，严禁为清账强行回收
     */
    public List<String> buildBlueprintForeshadowList(List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<ChapterSummaryEntity> ordered = ordered(summaries);
        int latestNo = ordered.get(ordered.size() - 1).getChapterNo() == null
                ? 0 : ordered.get(ordered.size() - 1).getChapterNo();
        List<ForeshadowPriorityService.ScoredForeshadow> scored =
                priorityService.score(buildPendingForeshadowDetailed(ordered), latestNo);
        List<String> lines = new ArrayList<>();
        appendBlueprintTier(lines, scored, ForeshadowPriorityService.Tier.HARD, "【硬】");
        appendBlueprintTier(lines, scored, ForeshadowPriorityService.Tier.SOFT, "【软】");
        appendBlueprintTier(lines, scored, ForeshadowPriorityService.Tier.BREAKER, "【未填·冻结】");
        return lines.size() > BLUEPRINT_FORESHADOW_LIMIT ? lines.subList(0, BLUEPRINT_FORESHADOW_LIMIT) : lines;
    }

    private void appendBlueprintTier(List<String> lines, List<ForeshadowPriorityService.ScoredForeshadow> scored,
                                     ForeshadowPriorityService.Tier tier, String tag) {
        for (ForeshadowPriorityService.ScoredForeshadow s : scored) {
            if (s.tier() != tier) {
                continue;
            }
            lines.add(tag + s.item().content() + "（第" + s.item().chapterNo() + "章埋）");
        }
    }

    /**
     * 长期未填伏笔清单（冻结档）：待回收伏笔打分后过滤熔断档（带埋设章号/重要度/原文引用），
     * 供阶段出口卷末清账裁决使用；latestNo 由调用方给定（阶段末章）
     */
    public List<ForeshadowPriorityService.ScoredForeshadow> buildBreakerForeshadows(List<ChapterSummaryEntity> summaries, int latestNo) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        return priorityService.score(buildPendingForeshadowDetailed(ordered(summaries)), latestNo).stream()
                .filter(s -> s.tier() == ForeshadowPriorityService.Tier.BREAKER)
                .toList();
    }

    /**
     * 卷末清账出账：把裁决弃置（VOID）的伏笔从各章摘要的新埋清单中剔除——
     * 按登记原文精确匹配（结算条目 content 映射自账本条目原文，无需模糊匹配）。
     * 直接修改传入摘要对象，调用方负责随后落盘；出账后未填计数/蓝图输入/登记清单随之收敛
     */
    public void stripVoidedForeshadows(List<ChapterSummaryEntity> summaries, Collection<String> voidedContents) {
        if (summaries == null || summaries.isEmpty() || voidedContents == null || voidedContents.isEmpty()) {
            return;
        }
        Set<String> voided = voidedContents.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .collect(Collectors.toSet());
        if (voided.isEmpty()) {
            return;
        }
        for (ChapterSummaryEntity summary : summaries) {
            if (summary == null || summary.getForeshadowingNew() == null || summary.getForeshadowingNew().isEmpty()) {
                continue;
            }
            summary.setForeshadowingNew(summary.getForeshadowingNew().stream()
                    .filter(item -> item == null || !voided.contains(item.trim()))
                    .collect(Collectors.toCollection(ArrayList::new)));
        }
    }

    /**
     * 待回收伏笔明细：带埋设章号与种子原文引用（模型输出且通过正文子串校验的才有 excerpt）
     */
    public List<PendingForeshadow> buildPendingForeshadowDetailed(List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<PendingForeshadow> pending = new ArrayList<>();
        for (ChapterSummaryEntity summary : ordered(summaries)) {
            Map<String, ChapterSummaryEntity.SeedEntry> seeds = seedsOf(summary);
            int chapterNo = summary.getChapterNo() == null ? 0 : summary.getChapterNo();
            if (summary.getForeshadowingNew() != null) {
                summary.getForeshadowingNew().stream()
                        .filter(StringUtils::isNotBlank)
                        .forEach(item -> {
                            String content = item.trim();
                            if (pending.stream().noneMatch(p -> p.content().equals(content))) {
                                ChapterSummaryEntity.SeedEntry seed = matchSeed(seeds, content);
                                pending.add(new PendingForeshadow(chapterNo, content,
                                        seed == null ? null : seed.getExcerpt(),
                                        seed == null ? null : seed.getImportance(),
                                        // ID 随种子带出：老数据为 null，下游回退文本匹配
                                        seed == null ? null : seed.getForeshadowId()));
                            }
                        });
            }
            if (summary.getForeshadowingResolved() != null) {
                summary.getForeshadowingResolved().stream()
                        .filter(StringUtils::isNotBlank)
                        .map(String::trim)
                        // **ID 优先**（2026-10-13）：模型回抄 ID（如 F7-2）时按 ID 精确核销——
                        // 这是解决"措辞漂移导致账永远关不掉"的正解（「2026」那条线第 93/95 章
                        // 其实已兑现，却因摘要措辞与账本原文对不上而到第 100 章仍被催收）。
                        // 回退：非 ID 形态（老数据/模型仍复述文本）走既有双向 contains。
                        .forEach(resolved -> pending.removeIf(p -> isResolved(p, resolved)));
            }
        }
        return pending;
    }

    /**
     * 单条回收登记是否核销该待收条目：**ID 精确匹配优先，文本包含回退**。
     *
     * <p>ID 匹配取全等（{@code F7-2}），不做模糊——ID 是机械分配的结构化凭据，
     * 拼错即视为未命中并回退文本路径，绝不"猜一个相近的 ID"（误关会永久丢掉一条真伏笔）。
     * 模型可能带方括号回抄（{@code [F7-2]}），故先归一化 ID 形态再比对。
     */
    private static boolean isResolved(PendingForeshadow pending, String resolved) {
        if (StringUtils.isNotBlank(pending.foreshadowId())) {
            String registered = normalizeForeshadowIdForm(resolved);
            if (registered != null && pending.foreshadowId().equalsIgnoreCase(registered)) {
                return true;
            }
        }
        return pending.content().contains(resolved) || resolved.contains(pending.content());
    }

    /**
     * 截取正文结尾约 maxChars 字，起点对齐段落边界（避免半句开头）
     */
    public String tailByParagraph(String content, int maxChars) {
        if (StringUtils.isBlank(content)) {
            return null;
        }
        // 正文实体中的换行可能残留 JSON 转义形态，统一归一（与落盘处理一致）
        String normalized = content.replace("\\n", "\n").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        String tail = normalized.substring(normalized.length() - maxChars);
        int firstBreak = tail.indexOf('\n');
        if (firstBreak >= 0 && firstBreak < tail.length() - 1) {
            tail = tail.substring(firstBreak + 1);
        }
        return tail.trim();
    }

    /**
     * 渲染卷方向锚节：卷号/区间 + 卷主旨 + 卷承转合 + 卷级伏笔 + 卷内弧清单（标记本弧位置）。
     * 卷级承诺（seeds）本就要求滚向卷尾回收——规划层看不到，就会一路漏到卷末清账才补救
     */
    private void renderVolumeDirection(StringBuilder sb, VolumeBlueprintEntity volume, StageBlueprintEntity blueprint) {
        if (volume == null) {
            return;
        }
        sb.append("\n【当前卷方向锚】第").append(volume.getVolumeNo()).append("卷《")
                .append(nullToBlank(volume.getTitle())).append("》（第")
                .append(volume.getStartChapter()).append("-").append(volume.getEndChapter()).append("章）\n");
        if (StringUtils.isNotBlank(volume.getThemeShift())) {
            sb.append("卷主旨：").append(volume.getThemeShift()).append("\n");
        }
        if (volume.getBeats() != null && !volume.getBeats().isEmpty()) {
            sb.append("卷承转合：").append(String.join("；", volume.getBeats())).append("\n");
        }
        if (volume.getSeeds() != null && !volume.getSeeds().isEmpty()) {
            sb.append("卷级伏笔（滚向卷尾回收）：").append(String.join("；", volume.getSeeds())).append("\n");
        }
        if (volume.getArcPlan() != null && !volume.getArcPlan().isEmpty()) {
            Integer currentArcNo = blueprint == null ? null : blueprint.getArcNo();
            sb.append("卷内弧清单（本弧已标出）：");
            for (VolumeBlueprintEntity.ArcPlan arc : volume.getArcPlan()) {
                if (arc == null || StringUtils.isBlank(arc.getOneLineGoal())) {
                    continue;
                }
                sb.append("【").append(arc.getArcNo());
                if (Objects.equals(currentArcNo, arc.getArcNo())) {
                    sb.append("·本弧");
                }
                sb.append("】").append(arc.getOneLineGoal()).append("；");
            }
            sb.append("\n本弧只推进所在卷的当前阶段，不得提前透支后续弧的高潮；")
                    .append("卷级伏笔由章节计划按剧情相关性安排，滚向卷尾回收。\n");
        }
    }

    /**
     * 渲染阶段蓝图节：滚动大纲的方向锚定（仅当前版入前缀，历史链只落盘供审计）。
     * 里程碑任务明确"方向语境"定位——规划层按剧情自然推进，严禁为凑任务注水
     *
     * <p><b>结转任务裁剪</b>（2026-10-12）：①已完成/放弃的结转不再渲染——它们没有可执行动作
     * （已完成项的事实由三账本承载，放弃裁决属规划层）；②"进行中"条目封顶
     * {@link #MAX_BLUEPRINT_CARRIED_TASKS} 条；③note 截断到 {@link #MAX_CARRIED_NOTE_CHARS}。
     * 依据：结转任务携带的"机械结转并已注入本阶段退出条件（阶段末重验一次）：复合条件 2 个
     * 分句中有 2 个未达成…"是给**规划/核验层**看的审计语言，对**写手**是纯噪声——第 13 阶段
     * 实测 17 条结转 ≈1.9k 字符，是【阶段蓝图】块膨胀到 2.9k、把三账本/伏笔账挤出 18000
     * 前缀预算的主力之一。这与 {@link #renderForeshadowing} 的"写手视图无元信息"是同一条纪律。
     */
    private void renderStageBlueprint(StringBuilder sb, StageBlueprintEntity blueprint) {
        if (blueprint == null) {
            return;
        }
        sb.append("\n【阶段蓝图】第").append(blueprint.getStageNo()).append("阶段（第")
                .append(blueprint.getStartChapter()).append("-").append(blueprint.getEndChapter()).append("章）\n");
        if (StringUtils.isNotBlank(blueprint.getStageGoal())) {
            sb.append("阶段目标：").append(blueprint.getStageGoal()).append("\n");
        }
        if (blueprint.getTasks() != null && !blueprint.getTasks().isEmpty()) {
            sb.append("阶段任务（里程碑，按剧情自然推进，无需每章推进，严禁为凑任务注水）：\n");
            for (String task : blueprint.getTasks()) {
                sb.append("- ").append(task).append("\n");
            }
        }
        if (blueprint.getCarriedTasks() != null && !blueprint.getCarriedTasks().isEmpty()) {
            List<StageBlueprintEntity.CarriedTaskEntity> carried = new ArrayList<>();
            for (StageBlueprintEntity.CarriedTaskEntity task : blueprint.getCarriedTasks()) {
                if (task == null || StringUtils.isBlank(task.getContent())) {
                    continue;
                }
                // status 为空的老数据保守保留（无法判定已完成/放弃）
                if (StringUtils.isNotBlank(task.getStatus()) && !"进行中".equals(task.getStatus().trim())) {
                    continue;
                }
                carried.add(task);
            }
            if (!carried.isEmpty()) {
                sb.append("上一阶段遗留：\n");
                carried.stream().limit(MAX_BLUEPRINT_CARRIED_TASKS).forEach(task -> {
                    sb.append("- ").append(task.getContent()).append("（")
                            .append(nullToBlank(task.getStatus()).trim());
                    if (StringUtils.isNotBlank(task.getNote())) {
                        String note = task.getNote().trim();
                        sb.append("，").append(note.length() > MAX_CARRIED_NOTE_CHARS
                                ? note.substring(0, MAX_CARRIED_NOTE_CHARS) + "…" : note);
                    }
                    sb.append("）\n");
                });
            }
        }
    }

    /**
     * 渲染伏笔账节：按动态权重分级——
     * 新埋区（上一章刚埋，始终带原文保衔接）→ 硬区（优先承接）→ 软区（推荐顺手回收）
     * → 内容行池（硬/软溢出+背景，共用封顶）→ 未填计数（冻结）。回收决策归规划层按剧情相关性安排，
     * 写手仅在自然触及时兑现——为清账强行回收会导致章节注水
     *
     * <p><b>写手视图无元信息（2026-10-04，新书 26-30 章实测）</b>：本节供**正文写手**消费，
     * 严禁携带"（第N章埋）""滞留N章""本章计划必须评估"等规划层语言——实测写手把
     * "（第15章埋）"逐字抄进正文（章节编号元信息泄露，读者立刻出戏），并把"必须评估回收"
     * 当行动指令去回收弹珠线、违反物品账本的已埋状态。章号/滞留/评估指令只属于计划层视图。
     */
    private void renderForeshadowing(StringBuilder sb, List<ChapterSummaryEntity> ordered) {
        List<PendingForeshadow> pending = buildPendingForeshadowDetailed(ordered);
        sb.append("\n【伏笔账·待回收】\n");
        if (pending.isEmpty()) {
            sb.append("（暂无）\n");
            return;
        }
        int latestNo = ordered.get(ordered.size() - 1).getChapterNo() == null
                ? 0 : ordered.get(ordered.size() - 1).getChapterNo();
        List<ForeshadowPriorityService.ScoredForeshadow> scored = priorityService.score(pending, latestNo);

        List<ForeshadowPriorityService.ScoredForeshadow> fresh = new ArrayList<>();
        List<ForeshadowPriorityService.ScoredForeshadow> hard = new ArrayList<>();
        List<ForeshadowPriorityService.ScoredForeshadow> soft = new ArrayList<>();
        List<ForeshadowPriorityService.ScoredForeshadow> background = new ArrayList<>();
        int breaker = 0;
        for (ForeshadowPriorityService.ScoredForeshadow s : scored) {
            switch (s.tier()) {
                case HARD -> hard.add(s);
                case SOFT -> soft.add(s);
                case BREAKER -> breaker++;
                case BACKGROUND -> {
                    // 上一章刚埋的条目进入新埋区（分数必然低于软阈值，不会与硬/软重复）
                    if (s.item().chapterNo() == latestNo) {
                        fresh.add(s);
                    } else {
                        background.add(s);
                    }
                }
            }
        }

        int freshShown = Math.min(MAX_FRESH_LINES, fresh.size());
        for (int i = 0; i < freshShown; i++) {
            appendForeshadowLine(sb, fresh.get(i), true, "（上章新埋）", null);
        }
        for (int i = freshShown; i < fresh.size(); i++) {
            appendForeshadowLine(sb, fresh.get(i), false, "（上章新埋）", null);
        }

        int hardShown = Math.min(MAX_HARD_LINES, hard.size());
        for (int i = 0; i < hardShown; i++) {
            ForeshadowPriorityService.ScoredForeshadow s = hard.get(i);
            String note = "已悬置多章：剧情自然触及则顺势承接，未触及不得强行提起；"
                    + "涉及具体物品时先对照上方物品账本的当前状态，严禁状态冲突"
                    + "（已藏起/埋起/交出的物品不得凭空回到场景中）";
            appendForeshadowLine(sb, s, true, "【硬】", note);
        }

        int softShown = Math.min(MAX_SOFT_LINES, soft.size());
        for (int i = 0; i < softShown; i++) {
            ForeshadowPriorityService.ScoredForeshadow s = soft.get(i);
            String note = "剧情自然触及的场合可顺手承接，不做要求";
            appendForeshadowLine(sb, s, true, "【软】", note);
        }

        // 硬/软超限的溢出条目回落到内容行池（保持可见，仅省去原文与说明），与背景共用一个封顶
        List<ForeshadowPriorityService.ScoredForeshadow> contentLines = new ArrayList<>();
        for (int i = hardShown; i < hard.size(); i++) {
            contentLines.add(hard.get(i));
        }
        for (int i = softShown; i < soft.size(); i++) {
            contentLines.add(soft.get(i));
        }
        contentLines.addAll(background);

        int bgShown = Math.min(MAX_FORESHADOW_BACKGROUND_LINES, contentLines.size());
        for (int i = 0; i < bgShown; i++) {
            appendForeshadowLine(sb, contentLines.get(i), false, "（更早埋设）", null);
        }
        int bgSunk = contentLines.size() - bgShown;
        if (bgSunk > 0) {
            sb.append("- 另有 ").append(bgSunk).append(" 条伏笔暂未逐条展示，剧情需要时可依前情摘要唤醒\n");
        }
        if (breaker > 0) {
            sb.append("- 另有 ").append(breaker).append(" 条伏笔长期未填（剧情长期未触达），已冻结暂停自动安排，留待阶段出口裁决兑现或弃置\n");
        }
        sb.append("（伏笔回收由章节计划按剧情相关性安排，仅在自然触及时兑现，严禁为清账强行回收）\n");
        log.info("第{}章伏笔分级: 新埋{} 硬{} 软{} 背景{} 未填{}",
                latestNo, fresh.size(), hard.size(), soft.size(), background.size(), breaker);
    }

    /**
     * 单条伏笔渲染：content 必带，prefix 携带分级与埋设章号，note 为分级语义说明；
     * excerpt 仅新埋/硬/软区携带
     */
    private void appendForeshadowLine(StringBuilder sb, ForeshadowPriorityService.ScoredForeshadow s,
                                      boolean withExcerpt, String prefix, String note) {
        sb.append("- ").append(prefix).append(s.item().content());
        if (note != null) {
            sb.append("（").append(note).append("）");
        }
        sb.append("\n");
        if (withExcerpt && StringUtils.isNotBlank(s.item().excerpt())) {
            sb.append("  原文引用：").append(s.item().excerpt()).append("\n");
        }
    }

    /**
     * 渲染质量债节：只回灌最近 1~2 章的未修复债、最多 5 条，防前缀膨胀。
     * 槽位优先给 BLOCKING，剩余才给降档的 MINOR（见方法内说明）。
     */
    private void renderQualityDebts(StringBuilder sb, List<QualityDebtEntity> debts) {
        if (debts == null || debts.isEmpty()) {
            return;
        }
        List<QualityDebtEntity> unresolved = debts.stream()
                .filter(d -> d.getChapterNo() != null && !d.isResolved())
                .sorted(Comparator.comparing(QualityDebtEntity::getChapterNo).reversed())
                .toList();
        if (unresolved.isEmpty()) {
            return;
        }
        int boundary = unresolved.stream()
                .map(QualityDebtEntity::getChapterNo)
                .distinct()
                .limit(MAX_DEBT_CHAPTERS)
                .min(Integer::compareTo)
                .orElse(Integer.MIN_VALUE);

        StringBuilder body = new StringBuilder();
        int count = 0;
        // 槽位分配：BLOCKING 先占满，再填降档的 MINOR 提示（严重度分层须在消费端也生效）。
        // 前缀只回灌 MAX_DEBT_ISSUES 条，若按章节顺序混排，机械文风 MINOR 会把真硬伤挤出前缀——
        // 那正是「降档改革」最容易踩的意外副作用
        for (boolean blockingPass : new boolean[]{true, false}) {
            for (QualityDebtEntity debt : unresolved) {
                if (debt.getChapterNo() < boundary || count >= MAX_DEBT_ISSUES) {
                    break;
                }
                if (debt.getIssues() == null) {
                    continue;
                }
                for (ChapterIssueEntity issue : debt.getIssues()) {
                    if (count >= MAX_DEBT_ISSUES) {
                        break;
                    }
                    if ("BLOCKING".equalsIgnoreCase(issue.getSeverity()) != blockingPass) {
                        continue;
                    }
                    body.append("- 第").append(debt.getChapterNo()).append("章");
                    if (StringUtils.isNotBlank(issue.getDimension())) {
                        body.append("【").append(issue.getDimension()).append("】");
                    }
                    body.append(nullToBlank(issue.getDescription()));
                    if (StringUtils.isNotBlank(issue.getSuggestion())) {
                        body.append("（建议：").append(issue.getSuggestion()).append("）");
                    }
                    body.append("\n");
                    count++;
                }
            }
        }
        if (count > 0) {
            sb.append("\n【上章质量债】以下问题审校已确认，本章写作避免同类问题：\n").append(body);
        }
    }

    /**
     * 渲染单个账本节：活跃窗口内的条目逐条列出，沉寂条目并入沉淀名单只留名字。
     * deathReversalCheck 启用时（角色账本），死亡→非死亡反转条目渲染存疑标记与证据引用
     */
    /**
     * 是否只被提到过一次：{@code buildLedger} 合并时 lastChapterNo **每次提及都会刷新**（不限于状态变化），
     * 因此"首现章 == 最近提及章"等价于该条目全篇只在一章里出现过。
     * 空值视为"无法判定"，按"曾多次出现"处理（宁可多渲染，不可误降级）。
     */
    private static boolean mentionedOnce(LedgerEntry entry) {
        return entry.getFirstChapterNo() != null && entry.getFirstChapterNo().equals(entry.getLastChapterNo());
    }

    private void appendLedger(StringBuilder sb, String title, List<ChapterSummaryEntity> ordered,
                              Function<ChapterSummaryEntity, List<ChapterSummaryEntity.StateEntry>> extractor,
                              int latestNo, boolean deathReversalCheck) {
        List<LedgerEntry> entries = buildLedger(ordered, extractor, deathReversalCheck);
        if (entries.isEmpty()) {
            return;
        }
        int activeFrom = latestNo - ACTIVE_WINDOW + 1;
        List<LedgerEntry> active = new ArrayList<>();
        List<LedgerEntry> dormant = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.getLastChapterNo() != null && entry.getLastChapterNo() >= activeFrom) {
                active.add(entry);
            } else {
                dormant.add(entry);
            }
        }
        sb.append("\n【").append(title).append("】\n");
        // 只出现一次的条目只列名、不带状态。
        // 判定依据：合并时 lastChapterNo **每次提及都会刷新**（见 buildLedger），
        // 故"首现章 == 最近提及章" ⟺ 只出现过一次。
        // 动因：实测本书第 16 章物品账本活跃 26 条 1737 字中，有 14 条（888 字，占一半）只出现过一次，
        // 且清一色是年代布景道具（英雄牌蓝黑墨水／绿豆汤／油纸包桃酥／洛阳轴承厂纸盒…）——
        // 它们不会再被引用，保留状态没有连续性价值，却持续吃前缀预算并挤掉其他块。
        // 留名仍能提示"该名词在本书出现过"，避免后文生造出冲突的同名物。
        // 真正需要完整状态的恰是"被复用的东西状态变了"（集资合同×5、黑白电视×2），
        // 本规则天然把它们划入完整渲染一侧。
        // **该降级是自愈的**：条目一旦被再次提及，lastChapterNo 即刷新、与首现章不再相等，
        // 下一章自动恢复完整状态渲染——所以"先一次性登场、后变成关键物"的条目不会永久丢失状态，
        // 这也是本规则可以放心使用的前提。
        List<LedgerEntry> oneOff = new ArrayList<>();
        List<LedgerEntry> recurring = new ArrayList<>();
        for (LedgerEntry entry : active) {
            if (mentionedOnce(entry)) {
                oneOff.add(entry);
            } else {
                recurring.add(entry);
            }
        }
        for (LedgerEntry entry : recurring) {
            String trajectory = trajectorySuffix(entry);
            String status = entry.isReversalSuspect()
                    ? "【复活存疑·待人工确认】" + nullToBlank(entry.getStatus()) : nullToBlank(entry.getStatus());
            sb.append("- ").append(entry.getName())
                    .append("（第").append(entry.getFirstChapterNo()).append("章登场）：")
                    .append(status)
                    .append(trajectory);
            // 来源章追溯：变化条目的章号已在轨迹后缀中，稳定条目在此标明状态出自哪一章
            if (trajectory.isEmpty() && entry.getLastChapterNo() != null) {
                sb.append("（来源：第").append(entry.getLastChapterNo()).append("章）");
            }
            // 存疑条目附证据原文供人工核对（正常条目不渲染，控制前缀体积）
            if (entry.isReversalSuspect() && StringUtils.isNotBlank(entry.getEvidence())) {
                sb.append("〔证据：").append(entry.getEvidence()).append("〕");
            }
            sb.append("\n");
        }
        if (!oneOff.isEmpty()) {
            // 与沉寂名单同为"降级展示"，但语义不同：沉寂是"很久没动"，这里是"只动过一次"
            sb.append("- 仅出现过一次的条目（低相关，仅列名）：");
            for (LedgerEntry entry : oneOff) {
                sb.append(entry.getName()).append(" ");
            }
            sb.append("\n");
        }
        if (!dormant.isEmpty()) {
            // 沉寂名单封顶：按最近更新章号取前 N 个，其余封存——防百章后名单线性膨胀成纯噪声
            List<LedgerEntry> listed = dormant.stream()
                    .sorted(Comparator.comparing(LedgerEntry::getLastChapterNo,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(MAX_DORMANT_NAMES)
                    .toList();
            sb.append("- 早期沉寂条目：");
            for (LedgerEntry entry : listed) {
                sb.append(entry.getName()).append("(第").append(entry.getFirstChapterNo()).append("章) ");
            }
            int sealed = dormant.size() - listed.size();
            if (sealed > 0) {
                sb.append("…（另有 ").append(sealed).append(" 个更早条目已封存，复现时依前情摘要）");
            }
            sb.append("——如需再次登场，遵循其此前设定，状态按剧情合理推进\n");
        }
    }

    /**
     * 摘要的伏笔种子映射：content → SeedEntry（含 excerpt 与 importance，重复 content 取首条）
     */
    private Map<String, ChapterSummaryEntity.SeedEntry> seedsOf(ChapterSummaryEntity summary) {
        if (summary.getForeshadowSeeds() == null) {
            return Map.of();
        }
        Map<String, ChapterSummaryEntity.SeedEntry> seeds = new LinkedHashMap<>();
        for (ChapterSummaryEntity.SeedEntry seed : summary.getForeshadowSeeds()) {
            if (seed == null || StringUtils.isBlank(seed.getContent())) {
                continue;
            }
            seeds.putIfAbsent(seed.getContent().trim(), seed);
        }
        return seeds;
    }

    /**
     * 种子匹配：content 精确相等优先，其次互为包含（模型登记 content 与伏笔描述偶有出入）
     */
    private ChapterSummaryEntity.SeedEntry matchSeed(Map<String, ChapterSummaryEntity.SeedEntry> seeds, String content) {
        if (seeds.containsKey(content)) {
            return seeds.get(content);
        }
        for (Map.Entry<String, ChapterSummaryEntity.SeedEntry> entry : seeds.entrySet()) {
            if (entry.getKey().contains(content) || content.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private List<ChapterSummaryEntity> ordered(List<ChapterSummaryEntity> summaries) {
        List<ChapterSummaryEntity> ordered = new ArrayList<>(summaries);
        ordered.sort(Comparator.comparing(ChapterSummaryEntity::getChapterNo));
        return ordered;
    }

    /**
     * 主角当前境界：取最近一条记录了 cultivationRealm 的摘要（后章覆盖前章）
     */
    private String latestCultivationRealm(List<ChapterSummaryEntity> ordered) {
        for (int i = ordered.size() - 1; i >= 0; i--) {
            if (StringUtils.isNotBlank(ordered.get(i).getCultivationRealm())) {
                return ordered.get(i).getCultivationRealm().trim();
            }
        }
        return null;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

}
