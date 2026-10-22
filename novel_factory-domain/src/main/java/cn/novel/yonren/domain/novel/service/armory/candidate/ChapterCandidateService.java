package cn.novel.yonren.domain.novel.service.armory.candidate;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.GateResult;
import cn.novel.yonren.domain.novel.service.armory.quality.QualityGate;
import cn.novel.yonren.domain.novel.service.armory.quality.StyleViolationPolicy;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.utils.JsonRepair;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 候选选优服务（保持单稿生成，低置信通过才加注）：原稿通过机械门禁 + 审校首轮但
 * "低置信通过"（有 MINOR 残留或修订过 N 轮才闭环）时，用第二模型族
 * （ModelScene.CHAPTER_JUDGE，scene-models.chapter-judge 可配独立 base-url/api-key）
 * 重写 N 稿候选，机械排序淘汰后由第二模型族盲评二选一。
 * 挑战者胜出后必须重跑质量门复审（挑战者尚未过审校，直接采纳会旁路闸门）：
 * 复审出现未修复 BLOCKING 即回退原稿——守住"闸门拒绝率不升"。
 * 评审防偏置：A/B 顺序随机盲评、明令"只评文风与事件覆盖，不评长短详略"；
 * 第二族 API 未配置/调用异常一律 fail-soft 保留原稿
 */
@Service
@Slf4j
public class ChapterCandidateService {

    /** Jackson 默认严格模式会拒绝字符串内未转义控制字符，模型长文本输出偶发该问题（与正文转换同配置） */
    private static final BeanOutputConverter<ChapterContentEntity> CONTENT_CONVERTER =
            new BeanOutputConverter<>(ChapterContentEntity.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    private static final BeanOutputConverter<JudgeVerdict> JUDGE_CONVERTER =
            new BeanOutputConverter<>(JudgeVerdict.class);

    /** 候选并发生成线程池（daemon，不阻塞 JVM 退出；MDC 手工透传保住 usage 按 jobId 归因） */
    private static final ExecutorService CANDIDATE_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "chapter-candidate");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private LlmInvokeService llmInvokeService;

    @Resource
    private LlmGateway llmGateway;

    @Resource
    private QualityGate qualityGate;

    @Resource
    private CandidateSampleService candidateSampleService;

    @Resource
    private StoryProperties storyProperties;

    /** 评审输出：winner=A|B，reason 供样本复盘 */
    public record JudgeVerdict(String winner, String reason) {
    }

    /** 评审结果容器：verdict=null 时 lastRaw 携带最后一次原始输出（截断）供样本复盘 */
    private record JudgeAttempt(JudgeVerdict verdict, String lastRaw) {
    }

    /**
     * 低置信通过的候选选优入口：未启用/非低置信通过时原样返回 incumbentGate；
     * 挑战者胜出且通过质量门复审时原地替换 chapterContent 并返回新 GateResult，
     * 复审不过（质量债）回退原稿。全程 fail-soft：任何异常保留原稿
     *
     * @param ctx        正文生成时的装配上下文（候选复用同一 userPrompt 与规则注入）
     * @param userPrompt 已装配完成的正文 prompt（含记忆前缀/节拍/规则）
     */
    public GateResult challenge(ArmoryCommandEntity requestParameter,
                                ChapterPlanItemEntity item,
                                PromptContext ctx,
                                String userPrompt,
                                ChapterContentEntity chapterContent,
                                GateResult incumbentGate,
                                List<ChapterSummaryEntity> summaries,
                                StyleStatEntity styleStat,
                                int globalNo,
                                Path storyDir) {
        StoryProperties.CandidateProperties props = storyProperties.getCandidate();
        if (props == null || !props.isEnabled() || !triggered(incumbentGate, props)) {
            return incumbentGate;
        }
        log.info("第 {} 章低置信通过（{}，MINOR {} 条/修订 {} 轮），启动候选选优",
                globalNo, incumbentGate.grade(), incumbentGate.minorCount(), incumbentGate.reviseRounds());
        try {
            int n = Math.max(1, props.getMaxCandidates());
            // 重写沿用正文 prompt 会系统性缩水（实测 27 次机械拒绝的平均字数为原稿的 78%，
            // 其中 7 次跌破 0.6 缩水线）——模型把"重写"理解成"精简"。原稿已达标，
            // 挑战者的目标是**在同等篇幅上写得更好**，故给重写追加长度护栏；
            // 正文生成 prompt 本身不动（那段给"从零写"用，加长度禁令会干扰首稿）
            String rewritePrompt = appendRewriteLengthGuard(userPrompt,
                    ChapterLengthPolicy.effectiveCharacterCount(chapterContent.getContent()));
            List<ChapterContentEntity> challengers = generateChallengers(requestParameter, item, ctx, rewritePrompt, globalNo, n);
            if (challengers.isEmpty()) {
                log.warn("第 {} 章候选生成全部失败（第二模型族未配置或调用异常），保留原稿", globalNo);
                candidateSampleService.record(storyDir, globalNo, incumbentGate.grade().name(),
                        "challenger-unavailable", chapterContent, null, null, null, null);
                return incumbentGate;
            }
            // 机械排序：零违规者优先、有效字数次之；带违规的挑战者直接出局（原稿已过机械门禁 = 零违规）。
            // 字数下限实测）：5 次采纳的挑战者全部比原稿短（ch15 砍半、ch19 -42%）——
            // 盲评明令不评长短 ⇒ 短稿结构性占优，必须在进盲评前拦下（详见 analysis/fix-plan-candidate-length-floor.md）。
            ChapterContentEntity best = mechanicalBest(challengers,
                    ChapterLengthPolicy.effectiveCharacterCount(chapterContent.getContent()));
            if (best == null) {
                log.info("第 {} 章候选全部命中机械门禁（{} 稿），保留原稿", globalNo, challengers.size());
                candidateSampleService.record(storyDir, globalNo, incumbentGate.grade().name(),
                        "challenger-mechanical-reject", chapterContent, challengers.get(0), null, null, null);
                return incumbentGate;
            }
            // 异模型盲评二选一（空响应/解析失败自动重试一次，原始输出留痕供复盘）
            // 盲评必须看到"本章开始时的既知事实"，否则结构上不可能发现事实错误（见 buildFactBaseline 说明）
            JudgeAttempt attempt = judge(requestParameter.getStoryVO(), item,
                    chapterContent.getContent(), best.getContent(), globalNo,
                    buildFactBaseline(summaries, globalNo), renderRegisterBaseline(requestParameter));
            if (attempt.verdict() == null || !"challenger".equals(attempt.verdict().winner())) {
                log.info("第 {} 章评审保留原稿（{}）", globalNo,
                        attempt.verdict() == null ? "评审不可用/解析失败" : attempt.verdict().reason());
                candidateSampleService.record(storyDir, globalNo, incumbentGate.grade().name(),
                        attempt.verdict() == null ? "judge-unavailable" : "incumbent-kept",
                        chapterContent, best,
                        attempt.verdict() == null ? null : attempt.verdict().winner(),
                        attempt.verdict() == null ? null : attempt.verdict().reason(),
                        attempt.lastRaw());
                return incumbentGate;
            }
            JudgeVerdict verdict = attempt.verdict();
            // 挑战者胜出：原地替换 + 质量门复审（挑战者尚未过审校，直接采纳等于旁路闸门）
            // 替换前快照原稿：采纳分支的样本必须保留败者全文（复盘要的是"输的那稿"）
            ChapterContentEntity incumbentSnapshot = ChapterContentEntity.builder()
                    .chapterNo(globalNo).title(chapterContent.getTitle())
                    .content(chapterContent.getContent()).build();
            chapterContent.setTitle(best.getTitle());
            chapterContent.setContent(best.getContent());
            GateResult challengerGate = qualityGate.auditAndReviseIfEnabled(
                    requestParameter, item, chapterContent, summaries, styleStat, globalNo, storyDir);
            if (!challengerGate.unresolvedBlocking().isEmpty()) {
                chapterContent.setTitle(incumbentSnapshot.getTitle());
                chapterContent.setContent(incumbentSnapshot.getContent());
                log.warn("第 {} 章挑战者复审出现未修复 BLOCKING（{} 条），回退原稿；"
                                + "该结论属于**已废弃的候选稿**，不记为本章质量债（采纳的是原稿，原稿无此问题）",
                        globalNo, challengerGate.unresolvedBlocking().size());
                candidateSampleService.record(storyDir, globalNo, incumbentGate.grade().name(),
                        "challenger-debt-reverted", chapterContent, best, "challenger", verdict.reason(), null);
                return incumbentGate;
            }
            log.info("第 {} 章挑战者胜出并复审通过（grade {}），采纳候选稿。评审理由：{}",
                    globalNo, challengerGate.grade(), verdict.reason());
            candidateSampleService.record(storyDir, globalNo, incumbentGate.grade().name(),
                    "challenger-adopted", incumbentSnapshot, best, "challenger", verdict.reason(), null);
            return challengerGate;
        } catch (Exception e) {
            log.warn("第 {} 章候选选优流程异常，保留原稿（fail-soft）", globalNo, e);
            candidateSampleService.record(storyDir, globalNo, incumbentGate.grade().name(),
                    "error", chapterContent, null, null, StringUtils.abbreviate(e.getMessage(), 200), null);
            return incumbentGate;
        }
    }

    /**
     * 候选选优的触发判定。
     *
     * <p><b>2026-10-01 修 DEBT 漏触发</b>：此前只认 {@code MINOR_RESIDUE} 与 {@code REVISED_PASS}
     * 两种"低置信通过"，{@code DEBT}（修订耗尽仍有未修复 BLOCKING）落到 {@code default -> false}
     * 被直接跳过。而这恰恰是**最需要换一个写法**的章节——原稿已经在同一方向上改了两轮仍不收敛，
     * 说明问题不在措辞而在写法本身。
     *
     * <p>实测代价：2026-10-01 批次 5 章**全部** DEBT，候选一次都没启动，
     * 每章白跑 2 轮 revise + 2 轮 audit-verify（10 次 patch 共 53k token）后仍是同样的稿。
     *
     * <p>仍受 {@code candidate.enabled} 总开关约束（见 {@link #challenge}），
     * 未配置第二模型族时不会空转。{@code DEBT} 无独立开关——它没有"要不要触发"的合理默认，
     * 只有一个正确答案：触发。
     *
     * <p><b>2026-10-02：MINOR_RESIDUE 与 DEBT 双双关闭</b>（见 {@code CandidateProperties} 各自字段的注释）。
     * 本批实测的采纳率分布是唯一依据：
     * <pre>
     *   REVISED_PASS   66.7%   ← 唯一有效的信号
     *   MINOR_RESIDUE  42.9%
     *   DEBT            0%     ← 纯烧钱
     * </pre>
     * MINOR 关闭的理由是"条数门槛无判别力"（两批中位数均为 6，调门槛只是挪切点）；
     * DEBT 关闭的理由是"代价确定、收益 0/3"（挑战者与守卫者撞同一堵墙）。
     * 两个开关都保留，可在积累更多样本后重新打开。
     */
    private boolean triggered(GateResult gate, StoryProperties.CandidateProperties props) {
        return switch (gate.grade()) {
            // "有 MINOR" 不等于"值得整章重写"——条数无判别力（实测两批中位数均 6），故默认关闭
            case MINOR_RESIDUE -> props.isTriggerOnMinorResidue()
                    && gate.minorCount() >= Math.max(1, props.getMinorResidueThreshold());
            case REVISED_PASS -> props.isTriggerOnRevisedPass();
            // 修订耗尽仍不收敛：实测 3 次触发 0 次采纳（挑战者同一份计划下撞同一堵墙），默认关闭
            case DEBT -> props.isTriggerOnDebt();
            case CLEAN_PASS -> false;
        };
    }

    /**
     * 并发生成 N 稿候选：走 CHAPTER_JUDGE 模型场景（第二模型族）复用正文规则注入，
     * 单次 maxTokens 按配置覆盖（评审场景 yml max-tokens 为小值）。解析口径与
     * ChapterWorker.generateChapterWithFallback 一致：原文解析 → JsonRepair 重解 → 抢救 content。
     * N>1 时并发生成（MDC 透传保住 usage 按 jobId 归因）；全部失败返回空表（不抛出）
     */
    private List<ChapterContentEntity> generateChallengers(ArmoryCommandEntity requestParameter,
                                                           ChapterPlanItemEntity item,
                                                           PromptContext ctx, String userPrompt, int globalNo, int n) {
        StoryProperties.CandidateProperties props = storyProperties.getCandidate();
        Integer rewriteMaxTokens = props == null || props.getRewriteMaxTokens() == null
                ? null : props.getRewriteMaxTokens().intValue();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        List<CompletableFuture<ChapterContentEntity>> futures = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String label = "chapter-rewrite-" + (n > 1 ? i + "-" : "") + "第" + globalNo + "章";
            futures.add(CompletableFuture.supplyAsync(() -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                try {
                    return generateOne(requestParameter, item, ctx, userPrompt, globalNo, label, rewriteMaxTokens);
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            }, n > 1 ? CANDIDATE_POOL : Runnable::run));
        }
        List<ChapterContentEntity> challengers = new ArrayList<>();
        for (CompletableFuture<ChapterContentEntity> future : futures) {
            try {
                ChapterContentEntity candidate = future.join();
                if (candidate != null) {
                    challengers.add(candidate);
                }
            } catch (Exception e) {
                log.warn("第 {} 章候选生成失败，跳过该稿：{}", globalNo, e.getMessage());
            }
        }
        return challengers;
    }

    /** 单稿生成 + 解析降级；失败返回 null（调用方统计存活稿数） */
    private ChapterContentEntity generateOne(ArmoryCommandEntity requestParameter,
                                             ChapterPlanItemEntity item,
                                             PromptContext ctx, String userPrompt,
                                             int globalNo, String label, Integer rewriteMaxTokens) {
        String lastRaw = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = llmInvokeService.invokeWithScene(requestParameter.getStoryVO(),
                        PromptScene.CHAPTER_CONTENT, ctx, userPrompt, null,
                        ModelScene.CHAPTER_REWRITE, label, rewriteMaxTokens);
                lastRaw = raw;
                ChapterContentEntity chapter = tryConvert(raw);
                if (chapter == null) {
                    chapter = tryConvert(JsonRepair.repair(raw));
                }
                if (chapter != null && StringUtils.isNotBlank(chapter.getContent())) {
                    // 正文实体以全局章节号为准（落盘文件名/续写衔接均依赖），不信任模型输出的编号
                    chapter.setChapterNo(globalNo);
                    chapter.setTitle(cn.novel.yonren.types.utils.ChapterTitleNormalizer.normalize(chapter.getTitle()));
                    return chapter;
                }
                log.warn("第 {} 章候选第 {}/2 次输出无法解析", globalNo, attempt);
            } catch (Exception e) {
                log.warn("第 {} 章候选第 {}/2 次生成异常：{}", globalNo, attempt, e.getMessage());
            }
        }
        String content = JsonRepair.extractStringField(lastRaw, "content");
        if (StringUtils.isNotBlank(content)) {
            log.warn("第 {} 章候选解析失败，已抢救正文主体（标题取自章节计划）", globalNo);
            return ChapterContentEntity.builder()
                    .chapterNo(globalNo)
                    .title(cn.novel.yonren.types.utils.ChapterTitleNormalizer.normalize(item.getTitle()))
                    .content(content)
                    .build();
        }
        return null;
    }

    /** 盲评事实基线取最近几章（2 章足够覆盖"上一章结尾状态 + 当前认知边界"） */
    private static final int FACT_BASELINE_CHAPTERS = 2;

    /**
     * 候选盲评用的事实基线：最近若干章的剧情摘要 + 三账本状态串（与
     * {@code StageExitReviewService} 的可核验文本同源）。
     *
     * <p><b>为什么必须补</b>：盲评原先只拿到【章节计划 + 两稿正文】，既没有记忆也没有账本，
     * 因此<em>结构上不可能</em>发现"身份映射被改写""认知状态提前升级""已确认事实被否定"
     * 这类错误，只能比较文风——实测第 9、10 章的挑战者正是这样胜出的。
     * 补上基线后，评审才具备事实核对的依据。
     *
     * @return 基线文本；无历史章节（开篇）时返回 null，由 prompt 渲染成"无既知事实约束"
     */
    private String buildFactBaseline(List<ChapterSummaryEntity> summaries, int globalNo) {
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        List<ChapterSummaryEntity> history = new ArrayList<>();
        for (ChapterSummaryEntity summary : summaries) {
            if (summary != null && summary.getChapterNo() != null && summary.getChapterNo() < globalNo) {
                history.add(summary);
            }
        }
        if (history.isEmpty()) {
            return null;
        }
        history.sort(Comparator.comparing(ChapterSummaryEntity::getChapterNo));
        int from = Math.max(0, history.size() - FACT_BASELINE_CHAPTERS);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            ChapterSummaryEntity summary = history.get(i);
            sb.append("- 第").append(summary.getChapterNo()).append("章《")
                    .append(StringUtils.defaultString(summary.getTitle())).append("》：")
                    .append(StringUtils.defaultString(summary.getSummary()));
            appendStates(sb, summary.getCharacterStates());
            appendStates(sb, summary.getItemStates());
            appendStates(sb, summary.getFactionStates());
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 候选评审的题材/语域基准，避免只比文风而选中时代错位或人物失语的稿件。 */
    private String renderRegisterBaseline(ArmoryCommandEntity request) {
        if (request == null || request.getStoryContextEntity() == null) {
            return null;
        }
        var context = request.getStoryContextEntity();
        StringBuilder baseline = new StringBuilder()
                .append("题材：").append(StringUtils.defaultString(context.getTheme(), "未指定"))
                .append("；风格：").append(StringUtils.defaultString(context.getStyle(), "未指定"))
                .append("；世界设定：").append(StringUtils.abbreviate(
                        StringUtils.defaultString(context.getWorldSetting(), "未指定"), 300))
                .append("。人物语言必须符合年龄、身份、教育、职业与时代；")
                .append("不得无来源地使用学术报告腔、管理总结腔、互联网黑话或时代错位词。");
        return baseline.toString();
    }

    /** 账本状态串渲染：口径与记忆前缀一致（（名字：状态）） */
    private void appendStates(StringBuilder sb, List<ChapterSummaryEntity.StateEntry> states) {
        if (states == null) {
            return;
        }
        for (ChapterSummaryEntity.StateEntry state : states) {
            if (state != null && StringUtils.isNotBlank(state.getName())) {
                sb.append("（").append(state.getName()).append("：")
                        .append(StringUtils.defaultString(state.getStatus())).append("）");
            }
        }
    }

    /**
     * 机械排序：先滤掉机械门禁命中的稿，再取有效字数最高者；全部命中返回 null。
     *
     * <p>字数下限（2026-10-04）：挑战者有效字 &lt; 原稿 × {@link #CHALLENGER_MIN_LENGTH_RATIO}
     * 或（原稿未达参考线时）&lt; 原稿自身长度即出局——盲评明令"不评长短"，
     * 短稿在盲评里结构性占优（11-20 章实测 5 次缩水采纳：ch15 -50%、ch19 -42%、ch20 -34%）。
     */
    private ChapterContentEntity mechanicalBest(List<ChapterContentEntity> challengers, int incumbentChars) {
        // 下限以原稿为基准而非绝对 1500：原稿本就短时，拒绝更长的挑战者只会更糟——本闸防"缩水采纳"，
        // 不防"升级"；原稿自身的绝对不足由 QualityGate 密度信号回灌规划层治理。
        int minChars = Math.max((int) (incumbentChars * CHALLENGER_MIN_LENGTH_RATIO),
                Math.min(ChapterLengthPolicy.MINIMUM_EFFECTIVE_CHARACTERS, incumbentChars));
        return challengers.stream()
                .filter(c -> StyleViolationPolicy.check(c.getContent()).isEmpty())
                .filter(c -> ChapterLengthPolicy.effectiveCharacterCount(c.getContent()) >= minChars)
                .max(Comparator.comparingInt(c -> ChapterLengthPolicy.effectiveCharacterCount(c.getContent())))
                .orElse(null);
    }

    /** 挑战者相对原稿的最短比例：低于即判缩水出局（0.6 = 缩水 40%，结构已伤） */
    private static final double CHALLENGER_MIN_LENGTH_RATIO = 0.6;

    /**
     * 重写专用的长度护栏：把"篇幅不得少于原稿"写进 prompt 尾部。
     *
     * <p><b>为什么必须在 prompt 层说</b>：机械门禁只能**事后**拒稿——被拒的那一稿已经烧掉一次
     * 重写调用（实测 36% 的挑战稿死在字数下限，等于三分之一的钱直接烧掉）。事前给出目标篇幅，
     * 是唯一能把这部分浪费变成有效产出的做法。
     *
     * <p><b>为什么不定死数字</b>：给出"不少于原稿有效字"这个相对基准，模型才有可执行目标；
     * 同时要求"同等篇幅内提升质量"而非"扩写注水"，与去注水纪律一致。
     */
    private static String appendRewriteLengthGuard(String userPrompt, int incumbentChars) {
        if (StringUtils.isBlank(userPrompt) || incumbentChars <= 0) {
            return userPrompt;
        }
        return userPrompt
                + "\n\n【重写篇幅·硬约束】上文是本章已有稿（" + incumbentChars + " 有效字）的重写任务："
                + "请在此基础上改进质量，**篇幅不得少于原稿**（有效字 ≥ " + incumbentChars + "），"
                + "目标区间 " + incumbentChars + "–" + (int) (incumbentChars * 1.3) + " 有效字。"
                + "严禁用缩写、跳段或删减情节来压缩篇幅；信息量与情节覆盖必须**不少于原稿**。"
                + "若某处原稿写得好，保留其写法继续精修，不要为了「换一种说法」而降质。";
    }

    /**
     * 异模型盲评：两稿裸正文随机定为 A/B（剥离来源信息防自偏好），只评文风与计划事件覆盖，
     * 明令不评长短详略。空响应/解析失败自动重试一次（deepseek 等思考型模型偶发
     * "HTTP 200 但正文为空、token 全在思维链"的响应，实测 ch32 踩中）；两次均失败时
     * 返回 verdict=null 并携带最后一次原始输出（截断）供样本留痕复盘
     */
    private JudgeAttempt judge(StoryVO storyVO, ChapterPlanItemEntity item,
                               String incumbent, String challenger, int globalNo,
                               String factBaseline, String registerBaseline) {
        String lastRaw = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                boolean challengerIsA = ThreadLocalRandom.current().nextBoolean();
                String textA = challengerIsA ? challenger : incumbent;
                String textB = challengerIsA ? incumbent : challenger;

                StringBuilder sb = new StringBuilder();
                sb.append("你是小说评审。下面是同一章节计划的两稿正文（A/B）。")
                        .append("**先核对事实，再看文风**——事实错误一票否决，文风只在前几项平手时才起作用：")
                        .append("\n1) 事实与认知一致性（最高优先级）：角色身份映射是否被改写、")
                        .append("认知状态是否提前升级（把\"怀疑\"写成\"确认\"、把\"不知道\"写成\"知道\"）、")
                        .append("已确认事实是否被否定、人物位置与持有物是否无过渡跳变、")
                        .append("角色是否做出与其认知边界相矛盾的行为；")
                        .append("\n2) 事件覆盖：本章计划的关键事件是否都被剧情落实、结尾悬念是否成立；")
                        .append("\n3) 因果与场景连续性：场景切换是否有铺垫、行为是否有动机支撑、有无前后矛盾；")
                        .append("\n4) 文风与语域：句式多样性、信息密度、套话与模板腔多少、画面是否具体；")
                        .append("人物措辞和叙述是否符合故事时代、人物年龄、身份、教育、职业与认知边界，")
                        .append("无来源的学术报告腔、互联网黑话、时代错位词应判为明显劣势。")
                        .append("\n判定顺序：先比 1)，1) 平手再比 2)，再比 3)，最后才比 4)。")
                        .append("**任一稿存在第 1) 项事实错误，直接判另一稿胜；两稿都有事实错误则判更轻的那稿。**")
                        .append("\n严禁以下列任何理由判胜：篇幅长短、细节多少、谁更详尽或更完整——长度差异不构成评判依据。")
                        .append("\n只输出 JSON。")
                        .append("\n\n【本章计划】")
                        .append("\n标题：").append(StringUtils.defaultString(item.getTitle()))
                        .append("\n目标：").append(StringUtils.defaultString(item.getGoal()))
                        .append("\n关键事件：").append(item.getKeyEvents() == null ? "" : String.join("、", item.getKeyEvents()))
                        .append("\n结尾悬念：").append(StringUtils.defaultString(item.getEndingHook()))
                        .append("\n\n【本章开始时的既知事实与角色状态】")
                        .append("（事实核对的基准：正文与之冲突即为事实错误——例如把尚未确认的身份当成已确认、")
                        .append("否定已确立的账本状态、或让角色做出与其认知边界矛盾的行为）\n")
                        .append(factBaseline == null
                                ? "（无历史记忆，本章为开篇，无既知事实约束）\n" : factBaseline)
                        .append("\n【题材与人物语域基准】\n")
                        .append(StringUtils.defaultIfBlank(registerBaseline,
                                "按本章计划与正文可见设定判断，不得凭空假定时代或人物背景。"))
                        .append("\n【候选 A】\n").append(textA)
                        .append("\n\n【候选 B】\n").append(textB)
                        .append("\n\n请严格按照以下 JSON 格式输出：\n").append(JUDGE_CONVERTER.getFormat());

                String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                        .userPrompt(sb.toString())
                        .label("chapter-judge-第" + globalNo + "章")
                        .scene(ModelScene.CHAPTER_JUDGE)
                        .build());
                lastRaw = raw;
                if (StringUtils.isBlank(raw)) {
                    log.warn("第 {} 章候选评审第 {}/2 次返回空响应（token 可能全在思维链），重试", globalNo, attempt);
                    continue;
                }
                JudgeVerdict verdict = JUDGE_CONVERTER.convert(raw);
                if (verdict == null || verdict.winner() == null
                        || (!"A".equalsIgnoreCase(verdict.winner()) && !"B".equalsIgnoreCase(verdict.winner()))) {
                    log.warn("第 {} 章候选评审第 {}/2 次输出非法（输出 {} 字）：{}", globalNo,
                            StringUtils.length(raw), StringUtils.abbreviate(raw, 120));
                    continue;
                }
                boolean challengerWins = "A".equalsIgnoreCase(verdict.winner()) == challengerIsA;
                return new JudgeAttempt(
                        new JudgeVerdict(challengerWins ? "challenger" : "incumbent", verdict.reason()), lastRaw);
            } catch (Exception e) {
                log.warn("第 {} 章候选评审第 {}/2 次失败：{}", globalNo, attempt, e.getMessage());
            }
        }
        return new JudgeAttempt(null, StringUtils.abbreviate(lastRaw, 200));
    }

    private ChapterContentEntity tryConvert(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return CONTENT_CONVERTER.convert(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
