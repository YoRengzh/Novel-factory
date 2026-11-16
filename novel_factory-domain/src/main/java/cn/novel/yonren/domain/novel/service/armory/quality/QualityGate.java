package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.config.StoryProperties.CausalityProperties.Mode;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.model.valobj.revise.RewriteContext;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.audit.AuditSampleService;
import cn.novel.yonren.domain.novel.service.armory.audit.ChapterAuditService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ConsistencyIndexService;
import cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService;
import cn.novel.yonren.domain.novel.service.armory.revise.ChapterReviseService;
import cn.novel.yonren.domain.novel.service.armory.prompt.WritingContractPolicy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 单章质量门：审校 → 修订的决策链。
 * 审校是质量门：彻底失败抛异常终止本批（绝不等价于零问题）；
 * 修订失败则保留原稿并把 BLOCKING 问题作为质量债返回（审校结论不因修订失败而丢失）。
 * 从 GenerateChapterContentNode 自然拆出（D8），逐行等价
 */
@Service
@Slf4j
public class QualityGate {

    @Resource
    private ChapterAuditService chapterAuditService;

    @Resource
    private ChapterReviseService chapterReviseService;

    @Resource
    private ChapterMemoryService chapterMemoryService;

    @Resource
    private StoryProperties storyProperties;

    @Resource
    private AuditSampleService auditSampleService;

    @Resource
    private ConsistencyIndexService consistencyIndexService;

    /** 跨章对白逐字重复检测（机械）：对白句索引与查重口径都在那里，此处只消费 */
    @Resource
    private cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService styleStatService;

    public boolean auditEnabled() {
        return storyProperties != null && storyProperties.getAudit().isEnabled();
    }

    /**
     * 审校 → 修订（若启用）→ 修订稿复审的闭环自愈链（四期）：生成正文后、风格统计合并前调用。
     * 首审发现 BLOCKING 且修订启用时，逐轮"修订 → 采纳闸门 → 复审"，复审干净即闭环；
     * 轮次（story.revise.max-attempts）耗尽仍未闭环时，把最终 BLOCKING 记为质量债并落失败样本。
     * 审校/复审硬失败照旧抛 AppException 终止本批；修订调用异常保留审校结论（不重试省 token）。
     * 六期扩展：伏笔保密边界（禁泄关键词机械兜底 + 审校判变相泄露）、大纲偏离检测
     * （关键事件覆盖率过低时给审校注入加审预警），并通过 GateResult 暴露通过质量分级
     * （低置信通过供候选选优触发、CLEAN_PASS 供文风指纹库入库）。
     * 七期扩展：对白数量/轮次密度仅观测，不作为数量准入或补稿要求。
     * 场景兑现由既有计划与语义审校核验，词面未命中不直接证明违约。
     * 九期扩展：篇幅坍缩硬底线——有效字低于
     * {@link ChapterLengthPolicy#COLLAPSE_FLOOR_CHARACTERS} 的"提纲式废稿"记 BLOCKING；
     * 且**修订稿也要重新过线**：首审告警发生在重写前，不能代表重写后的篇幅仍然合格。

     *
     * @return 质量门结论：未修复 BLOCKING 清单 + 通过质量分级 + MINOR 残留/修订轮次
     */
    public GateResult auditAndReviseIfEnabled(ArmoryCommandEntity requestParameter,
                                              ChapterPlanItemEntity item,
                                              ChapterContentEntity chapterContent,
                                              List<ChapterSummaryEntity> summaries,
                                              StyleStatEntity styleStat,
                                              int globalNo,
                                              Path storyDir) {
        return auditAndReviseIfEnabled(requestParameter, item, chapterContent, summaries, styleStat, globalNo, storyDir, null);
    }

    public GateResult auditAndReviseIfEnabled(ArmoryCommandEntity requestParameter, ChapterPlanItemEntity item,
                                              ChapterContentEntity chapterContent, List<ChapterSummaryEntity> summaries,
                                              StyleStatEntity styleStat, int globalNo, Path storyDir,
                                              RewriteContext writerContext) {
        if (writerContext != null) writerContext.checkCancellation();
        DeliveryReviewPolicy.assertConfiguration(storyProperties);
        Mode deliveryMode = DeliveryReviewPolicy.mode(storyProperties);
        DeliveryReviewPolicy.Contract deliveryContract = deliveryMode == Mode.OFF ? null
                : DeliveryReviewPolicy.contract(item);
        DeliveryReview deliveryReview = DeliveryReview.notChecked();
        // 段落结构兜底：整章若没有换行，应在任何按行统计之前重排，
        // 否则对白行占比会被误算为 1.0，段落密度审校也无法定位段落。
        // ratioOf = 含引号行/非空行，整章仅 1 行且含引号 ⇒ 恒 1.0。
        // 重排是纯机械动作、不改一个字；异常信号另行记 MINOR 回灌规划层。
        List<ChapterIssueEntity> structureIssues = List.of();
        if (chapterContent != null) {
            String raw = chapterContent.getContent();
            structureIssues = ParagraphStructurePolicy.checkStructureCollapse(
                    raw, ParagraphStructurePolicy.paragraphLimit(raw));
            String reparagraphed = ParagraphStructurePolicy.reparagraph(raw);
            if (reparagraphed != raw) {
                log.warn("第 {} 章段落结构坍缩，已机械重排：{} 字 / {} 段 → {} 段（不改动任何文字）",
                        globalNo, raw.length(), raw.split("\n").length,
                        reparagraphed.split("\n\n", -1).length);
                chapterContent.setContent(reparagraphed);
            }
        }
        // 字数不足不再是 BLOCKING：拒收短章只会逼模型注水；改为仅告警，
        // 密度信号由 ChapterWorker 机械记入摘要（validChars/keyEventCount），回灌规划层治本
        if (storyProperties.getAudit().isEnforceMinimumChapterLength()
                && chapterContent != null && !ChapterLengthPolicy.meetsMinimum(chapterContent.getContent())) {
            log.warn("第 {} 章未达到 1500 字参考线，实际有效字数 {}（不阻塞，密度信号将回灌规划层）", globalNo,
                    ChapterLengthPolicy.effectiveCharacterCount(chapterContent.getContent()));
        }
        // 篇幅坍缩硬底线：低于 800 有效字 = 提纲式废稿，BLOCKING 进修订闭环。
        // 与上面 1500 线告警的分工见 ChapterLengthPolicy#COLLAPSE_FLOOR_CHARACTERS 的类注释：
        // 1500 答"计划给的料够不够"（回灌规划层），本线答"这一稿是不是根本不成篇"（当场返工）。
        // 告警不阻塞生成，不能替代用于返工的篇幅硬底线。
        List<ChapterIssueEntity> lengthIssues = List.of();
        // 独立开关：不与 1500 参考线告警共用——共用会让"关掉告警"静默连硬底线一起关掉
        if (storyProperties.getAudit().isEnforceCollapseFloor()
                && chapterContent != null && ChapterLengthPolicy.isCollapsed(chapterContent.getContent())) {
            int effective = ChapterLengthPolicy.effectiveCharacterCount(chapterContent.getContent());
            log.warn("第 {} 章篇幅坍缩：有效字 {} 低于硬底线 {}，记 BLOCKING 进修订闭环", globalNo,
                    effective, ChapterLengthPolicy.COLLAPSE_FLOOR_CHARACTERS);
            ChapterIssueEntity collapsed = new ChapterIssueEntity();
            collapsed.setDimension("aesthetic");
            collapsed.setSeverity("BLOCKING");
            collapsed.setEvidence("全章有效字 " + effective + "，低于硬底线 " + ChapterLengthPolicy.COLLAPSE_FLOOR_CHARACTERS);
            collapsed.setDescription("篇幅坍缩：本章有效字 " + effective + " 低于硬底线 "
                    + ChapterLengthPolicy.COLLAPSE_FLOOR_CHARACTERS + "，已塌成提纲——场景没有铺展、"
                    + "对白与动作被压成概述。这不是'写得短'，是这一稿根本不成篇，必须返工重写。");
            collapsed.setSuggestion("按本章原始写作上下文与完整场景计划重写，交代关键行动、必要交流及后果。"
                    + "不规定对白句数、句长或轮次，不从残稿反推计划，不为达到篇幅而重复确认条款。");
            lengthIssues = List.of(collapsed);
        }
        // 上沿同口径告警：关键事件数量不变而篇幅显著增加时，可能存在信息密度下降。
        // 与下沿一样只告警不阻塞，信号回灌规划层（见 ChapterPlanPromptService 的注水反馈）。
        // 刻意**不豁免过渡章**：下沿豁免过渡章是因为它本就该短；写得比常规章还长的过渡章
        // 恰恰是最典型的注水形态。
        if (chapterContent != null && ChapterLengthPolicy.exceedsReference(chapterContent.getContent())) {
            log.warn("第 {} 章超出 {} 字参考上沿，实际有效字数 {}（不阻塞，注水信号将回灌规划层）", globalNo,
                    ChapterLengthPolicy.MAXIMUM_REFERENCE_CHARACTERS,
                    ChapterLengthPolicy.effectiveCharacterCount(chapterContent.getContent()));
        }
        // 机械文风门禁（零 LLM 调用）：万能副词密度/眼神套话/身体套话/章末升华已降为 MINOR
        //（只记录 + 回灌规划层，不触发修订），章节编号元信息泄露仍为 BLOCKING 进修订闭环。
        // 分层依据见 StyleViolationPolicy 类注释：程度类文风问题不以机械命中直接驱动整章修订。
        List<ChapterIssueEntity> styleIssues = chapterContent == null ? List.of()
                : StyleViolationPolicy.check(chapterContent.getContent());
        if (!styleIssues.isEmpty()) {
            log.warn("第 {} 章机械文风门禁命中 {} 条（BLOCKING {} / MINOR {}）：{}", globalNo,
                    styleIssues.size(), blockingOf(styleIssues).size(), nonBlockingOf(styleIssues).size(),
                    styleIssues.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        // 伏笔保密边界（机械兜底）：已埋未揭伏笔的谜底关键词逐字命中即 BLOCKING，
        // 揭示章（计划关键事件带回收/揭示意图）在清单构建侧豁免；prompt 块交审校判"变相泄露"
        ChapterMemoryService.SecrecyGuard secrecyGuard = chapterMemoryService.buildSecrecyGuard(summaries, item);
        List<ChapterIssueEntity> secrecyIssues = (chapterContent == null || secrecyGuard.isEmpty()) ? List.of()
                : SecrecyViolationPolicy.check(chapterContent.getContent(), secrecyGuard.keywords());
        if (!secrecyIssues.isEmpty()) {
            log.warn("第 {} 章禁泄清单命中 {} 条：{}", globalNo, secrecyIssues.size(),
                    secrecyIssues.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        // 大纲偏离检测（机械预检）：关键事件词面覆盖率过低时给审校注入加审预警（WARN 语义，不直接 BLOCKING）
        String adherenceHint = chapterContent == null ? null
                : PlanAdherencePolicy.renderLowCoverageHint(item.getKeyEvents(), chapterContent.getContent());
        // 对白数量仅作观测，不进审稿条件或修订指令。
        int dialogueCount = chapterContent == null ? 0 : ContentDensityPolicy.countDialogue(chapterContent.getContent());
        log.info("第 {} 章对白数量观测：{}（不作为数量准入或补稿要求）", globalNo, dialogueCount);
        // 章节编号元信息泄露检测（机械预检）：正文中出现"第X章""本章/上一章"等作者层面元信息时给审校注入加审预警
        // 同时 StyleViolationPolicy.check 会将其标记为 BLOCKING issue 进入修订闭环，双重保险
        String chapterRefEvidence = chapterContent == null ? null
                : StyleViolationPolicy.checkChapterReference(chapterContent.getContent());
        String chapterRefHint = StyleViolationPolicy.renderChapterRefAuditHint(chapterRefEvidence);
        if (chapterRefEvidence != null) {
            log.warn("第 {} 章章节编号元信息泄露预警：检测到 {}，审校注入加审预警并进入修订闭环",
                    globalNo, chapterRefEvidence);
        }

        // 金手指机制门禁（机械兜底，由"只记债不阻塞"改为真 BLOCKING 进修订闭环）：
        // 超期未使用 / 机制原理重复描述。两条都是 (摘要历史 + 本章正文) 的纯函数 ⇒ 修订稿可重算，
        // 修订真的清掉问题即闭环，不会永远挂债。
        // 必须在摘要落盘之前调用：本章是否"已使用"靠回读正文判定（mentionsMechanism），否则会误判
        List<ChapterIssueEntity> mechanismIssues = chapterContent == null ? List.of()
                : consistencyIndexService.mechanismIssues(
                        requestParameter.getStoryVO(), summaries, globalNo, chapterContent.getContent());
        if (!mechanismIssues.isEmpty()) {
            log.warn("第 {} 章机制门禁命中 {} 条：{}", globalNo, mechanismIssues.size(),
                    mechanismIssues.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        // 章节标题唯一性（机械，MINOR）：长篇里"新的开始""风暴前夕"这类模板标题会反复出现。
        // 刻意不进 BLOCKING——修订是整章重写，为改一个标题重写整章既不成比例也不保证会改名；
        // 根治在规划层（ChapterTitlePolicy.renderUsedTitles 已把已用标题注入规划 prompt）
        List<ChapterIssueEntity> titleIssues = (chapterContent == null
                || StringUtils.isBlank(chapterContent.getTitle()))
                ? List.of() : ChapterTitlePolicy.check(chapterContent.getTitle(), summaries);
        if (!titleIssues.isEmpty()) {
            log.warn("第 {} 章标题与既有章节重复：{}", globalNo, chapterContent.getTitle());
        }
        List<ChapterIssueEntity> mechanicalIssues = new java.util.ArrayList<>(styleIssues);
        mechanicalIssues.addAll(secrecyIssues);
        mechanicalIssues.addAll(mechanismIssues);
        mechanicalIssues.addAll(titleIssues);
        // 篇幅坍缩（机械 BLOCKING）：与文风/标题等程度类问题不同，
        // 它是"这一稿不成篇"的形态判断，必须在首审就进修订闭环
        mechanicalIssues.addAll(lengthIssues);
        // 段落结构坍缩（机械，）：正文被写成一个巨型文本块。注意必须用**重排前**的原文
        // 做判定，重排后段落数已被补齐，再判就永远不命中。MINOR 落债回灌，不进修订
        // （根因在写手侧，为分段重写整章不成比例；兜底重排已在方法入口完成）
        if (!structureIssues.isEmpty()) {
            log.warn("第 {} 章段落结构坍缩：{}", globalNo,
                    structureIssues.stream().map(ChapterIssueEntity::getEvidence).toList());
        }
        mechanicalIssues.addAll(structureIssues);
        // 对白格式坍缩检测（机械，P1）：整章零对白引号=读者无法区分叙述与发言（典型成因：
        // 写手把"纯文本禁令"过度泛化到对白，阅读实测整批丢引号）。MINOR 落债回灌，
        // 不进修订——根因已在生成 prompt 侧修复（对白引号豁免），此处是兜底观测与回灌
        List<ChapterIssueEntity> dialogueIssues = chapterContent == null ? List.of()
                : DialogueRatioPolicy.checkFormatCollapse(chapterContent.getContent(),
                        item.getChapterType() == null ? null : item.getChapterType().getCode());
        if (!dialogueIssues.isEmpty()) {
            log.warn("第 {} 章对白格式坍缩：{}", globalNo,
                    dialogueIssues.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        mechanicalIssues.addAll(dialogueIssues);
        // 括号包对话检测（机械，P1，）：台词写成（……）而非「……」。
        // 它躲得过上面的坍缩检测（文里仍有其它引号），却会让对白占比与轮次密度双双虚高——
        // 被括号包裹的句子两个统计都不计入，等于把对话坍缩伪装成达标。MINOR 落债回灌
        List<ChapterIssueEntity> bracketIssues = chapterContent == null ? List.of()
                : DialogueRatioPolicy.checkBracketDialogue(chapterContent.getContent(),
                        item.getChapterType() == null ? null : item.getChapterType().getCode());
        if (!bracketIssues.isEmpty()) {
            log.warn("第 {} 章疑似括号包对话：{}", globalNo,
                    bracketIssues.stream().map(ChapterIssueEntity::getEvidence).toList());
        }
        mechanicalIssues.addAll(bracketIssues);
        // 引号风格漂移检测（机械，MINOR）：与本书主流引号不同的写法记入观测与质量债。
        // 两种引号本身都合法，漂移只提示同书排版不一致，不因此触发修订。
        List<ChapterIssueEntity> quoteStyleIssues = chapterContent == null ? List.of()
                : DialogueRatioPolicy.checkQuoteStyleDrift(chapterContent.getContent(),
                        item.getChapterType() == null ? null : item.getChapterType().getCode(),
                        mainstreamCurlyQuotes(styleStat));
        if (!quoteStyleIssues.isEmpty()) {
            log.warn("第 {} 章引号风格漂移：{}", globalNo,
                    quoteStyleIssues.stream().map(ChapterIssueEntity::getEvidence).toList());
        }
        mechanicalIssues.addAll(quoteStyleIssues);
        // 对白轮次仅观测，不以低密度推断场景质量或强制补对白。
        List<ChapterIssueEntity> densityCollapse = dialogueDensityCollapse(item, chapterContent, summaries);
        if (!densityCollapse.isEmpty()) {
            log.info("第 {} 章对白轮次观测：{}", globalNo,
                    densityCollapse.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        mechanicalIssues.addAll(densityCollapse);
        // 跨章对白逐字重复（机械）：完整台词与历史一字不差时走 BLOCKING 定向修订。
        // 近重复仍交由软警示处理，避免将正常呼应一并拦截。
        List<ChapterIssueEntity> dialogueRepeat = dialogueRepeatIssues(chapterContent, styleStat);
        if (!dialogueRepeat.isEmpty()) {
            log.warn("第 {} 章跨章对白逐字重复 {} 句：{}", globalNo, dialogueRepeat.size(),
                    dialogueRepeat.stream().map(ChapterIssueEntity::getEvidence).toList());
        }
        mechanicalIssues.addAll(dialogueRepeat);
        // 章内相邻整句重复（机械）：同一句在本章内部重复时记 BLOCKING。
        // 与跨章查重互补，也覆盖局部补丁新引入的重复。
        List<ChapterIssueEntity> intraRepeats = intraChapterRepeatIssues(chapterContent);
        if (!intraRepeats.isEmpty()) {
            log.warn("第 {} 章章内整句重复 {} 句：{}", globalNo, intraRepeats.size(),
                    intraRepeats.stream().map(ChapterIssueEntity::getEvidence).toList());
        }
        mechanicalIssues.addAll(intraRepeats);
        // 严重度分层：机械问题按档分流——BLOCKING 进修订闭环，
        // MINOR（文风程度问题）只记录、不触发修订也不触发候选，见 GateResult 类注释
        List<ChapterIssueEntity> mechanicalMinor = nonBlockingOf(mechanicalIssues);
        List<ChapterIssueEntity> mechanicalBlocking = blockingOf(mechanicalIssues);
        if (!auditEnabled() && deliveryContract != null) {
            deliveryReview = DeliveryReviewPolicy.unavailable(deliveryRequest(deliveryContract, chapterContent,
                    deliveryMode, writerContext), "DELIVERY_AUDIT_DISABLED: 审校已关闭，交付未核验");
        }
        if (!auditEnabled() && (mechanicalBlocking.isEmpty() || deliveryReview.unknown())) {
            return GateResult.of(mechanicalBlocking, 0, mechanicalMinor, 0).withDeliveryReview(deliveryReview);
        }

        String ledgerPrompt = chapterMemoryService.renderLedgerPrompt(summaries);
        // 账本末态红线置顶：位置/持有物/修为的对齐锚点，审校比对与修订改写方向共用
        //（治连续 9 章 consistency 债复发的"正文开头 vs 账本末态"衔接错位）
        String edgeState = chapterMemoryService.renderLedgerEdgeState(summaries);
        if (StringUtils.isNotBlank(edgeState)) {
            ledgerPrompt = edgeState + (ledgerPrompt == null ? "" : "\n\n" + ledgerPrompt);
        }
        // 审校比对清单：剔除已熔断冻结的未填伏笔——未填不再构成审校的回收义务，且封顶防百章后线性膨胀
        String foreshadowing = String.join("\n", chapterMemoryService.buildAuditForeshadowList(summaries));
        // 审校输入 guard：禁泄清单（LLM 判变相泄露）+ 计划覆盖预警 + 内容密度预警 + 章节编号元信息预警（加审）
        // + 时序锚：把主角年龄摆进审校视野——否则"婴儿写数论证明"这类
        // 能力越界在审校侧完全不可见（此前 26 章 10 条 issue 全是位置/持有物类琐碎项）
        String registerContract = renderRegisterContract(requestParameter);
        // 时序锚：摘要锚优先；新书首段（无摘要）回退设定兜底锚——否则审校年龄判据因 guard 无锚而不触发
        String timeAnchor = ConsistencyIndexService.renderTimeAnchor(summaries);
        if (StringUtils.isBlank(timeAnchor)) {
            var storyCtx = requestParameter == null ? null : requestParameter.getStoryContextEntity();
            if (storyCtx != null) {
                timeAnchor = ConsistencyIndexService.renderSettingsAgeAnchorIfNoSummaries(summaries,
                        storyCtx.getWorldSetting(), storyCtx.getProtagonist(), storyCtx.getOutline());
            }
        }
        String auditGuard = joinBlocks(
                joinBlocks(joinBlocks(secrecyGuard.promptBlock(), adherenceHint), chapterRefHint),
                joinBlocks(joinBlocks(registerContract, timeAnchor), characterProfileHint(requestParameter)));
        // 修订输入保留禁泄清单与语域契约（防修订环节修好事实、却引入时代错位或人物失语）
        String reviseForeshadowing = joinBlocks(joinBlocks(foreshadowing, secrecyGuard.promptBlock()), registerContract);

        // 审校调用在 try 之外：解析/重试均失败时 AppException 直接终止本批
        List<ChapterIssueEntity> currentBlocking = new java.util.ArrayList<>(mechanicalBlocking);
        int minorCount = 0;
        List<ChapterIssueEntity> semanticMinors = List.of();
        String semanticFeedbackHash = null;
        int reviseRounds = 0;
        // 修订验证是否"根本没跑成"：为真时 currentBlocking 的语义是
        // 【未验证】而非【确认未修复】——内容侧偏向不变（仍算未通过、不放行未验证的稿），
        // 但上层不得把它记入质量债，观测侧单独计数（见 FixVerification / BatchHealthService）
        boolean auditVerifyDegraded = false;
        String planAdjustmentReason = null;
        if (auditEnabled()) {
            semanticFeedbackHash = ChapterManuscript.of(chapterContent).hash();
            AuditResultEntity auditResult = deliveryContract == null ? chapterAuditService.audit(
                    requestParameter.getStoryVO(), item, chapterContent.getContent(),
                    ledgerPrompt, foreshadowing, styleStat, globalNo, auditGuard)
                    : chapterAuditService.audit(requestParameter.getStoryVO(), item, chapterContent.getContent(),
                    ledgerPrompt, foreshadowing, styleStat, globalNo, auditGuard,
                    deliveryRequest(deliveryContract, chapterContent, deliveryMode, writerContext));
            if (deliveryContract != null) {
                deliveryReview = DeliveryReviewPolicy.ensureBound(auditResult == null ? null : auditResult.getDeliveryReview(),
                        item, ChapterManuscript.of(chapterContent), deliveryMode == Mode.ENFORCE);
                if (auditResult == null || auditResult.isAuditUnavailable()) {
                    deliveryReview = DeliveryReviewPolicy.unavailable(deliveryRequest(deliveryContract, chapterContent,
                            deliveryMode, writerContext), "DELIVERY_AUDIT_UNAVAILABLE: 首审没有完成，不能把空问题清单当通过");
                    auditVerifyDegraded = true;
                }
            }
            currentBlocking.addAll(blockingOf(auditResult));
            currentBlocking.addAll(DeliveryReviewPolicy.blockingIssues(deliveryReview));
            minorCount = minorOf(auditResult);
            // P2 审校反馈回灌：语义 MINOR 清单随 GateResult 传出（验证化复审不产出新 MINOR，以首审为准）
            semanticMinors = semanticMinorsOf(auditResult);
        }
        // UNKNOWN 不驱动盲目重写；严格模式由 Worker 保全当前稿后阻止提交。
        if (deliveryReview.unknown()) {
            return GateResult.of(currentBlocking, minorCount, mechanicalMinor, 0, semanticMinors, auditVerifyDegraded)
                    .withSemanticFeedbackHash(semanticFeedbackHash).withDeliveryReview(deliveryReview);
        }
        if (currentBlocking.isEmpty()) {
            log.info("第 {} 章审校完成，仅 MINOR（叙事 {} 条 / 文风 {} 条）或不含 BLOCKING 问题，不触发修订",
                    globalNo, minorCount, mechanicalMinor.size());
            return GateResult.of(List.of(), minorCount, mechanicalMinor, 0, semanticMinors, auditVerifyDegraded)
                    .withSemanticFeedbackHash(semanticFeedbackHash).withDeliveryReview(deliveryReview);
        }

        log.warn("第 {} 章审校发现 BLOCKING 问题 {} 条，准备修订", globalNo, currentBlocking.size());

        if (storyProperties.getRevise() == null || !storyProperties.getRevise().isEnabled()) {
            log.info("第 {} 章修订未启用，保留原稿", globalNo);
            auditSampleService.record(storyDir, globalNo, item, chapterContent.getContent(),
                    currentBlocking, 0, ledgerPrompt, foreshadowing);
            return GateResult.of(currentBlocking, minorCount, mechanicalMinor, 0, semanticMinors, auditVerifyDegraded)
                    .withSemanticFeedbackHash(semanticFeedbackHash).withDeliveryReview(deliveryReview);
        }

        int maxAttempts = Math.max(1, storyProperties.getRevise().getMaxAttempts());
        // 上一轮采纳闸门拒绝原因（方案2回传）：让次轮修订知道为何被拒，定向避免而非盲改
        String previousRejectionReason = null;
        // 无进展检测：使用问题描述归一化后的多重集指纹。
        // 仅条数相同但问题已变化，仍说明修订有推进，不能误判无进展。
        String previousBlockingFingerprint = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            reviseRounds = attempt;
            ChapterReviseService.ReviseResult reviseResult;
            try {
                reviseResult = writerContext == null ? chapterReviseService.revise(
                        requestParameter.getStoryVO(), item, chapterContent.getContent(),
                        currentBlocking, ledgerPrompt, reviseForeshadowing, styleStat, globalNo, previousRejectionReason)
                        : chapterReviseService.revise(requestParameter.getStoryVO(), item, chapterContent.getContent(),
                        currentBlocking, ledgerPrompt, reviseForeshadowing, styleStat, globalNo,
                        previousRejectionReason, writerContext);
                if (writerContext != null) writerContext.checkCancellation();
            } catch (Exception e) {
                cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException.rethrowIfPresent(e);
                if (writerContext != null) writerContext.checkCancellation();
                // 修订失败保留审校结论：BLOCKING 记为质量债，随后续章节记忆回灌
                log.warn("第 {} 章第 {}/{} 轮修订流程异常，保留当前稿并将 BLOCKING 问题记为质量债",
                        globalNo, attempt, maxAttempts, e);
                if (deliveryContract == null || (!deliveryReview.unknown() && !auditVerifyDegraded)) {
                    auditSampleService.record(storyDir, globalNo, item, chapterContent.getContent(),
                            currentBlocking, attempt, ledgerPrompt, foreshadowing);
                }
                return GateResult.of(currentBlocking, minorCount, mechanicalMinor, attempt, semanticMinors, auditVerifyDegraded)
                    .withSemanticFeedbackHash(semanticFeedbackHash).withDeliveryReview(deliveryReview);
            }

            if (reviseResult.decision().isPlanAdjustmentRequired()) {
                // 计划与可信事实冲突：不再用同一计划重试，保留当前稿并记录待调整原因。
                planAdjustmentReason = reviseResult.decision().getReason();
                if (deliveryContract != null) {
                    deliveryReview = DeliveryReviewPolicy.unavailable(deliveryRequest(deliveryContract,
                            chapterContent, deliveryMode, writerContext),
                            "DELIVERY_PLAN_ADJUSTMENT_REQUIRED: " + planAdjustmentReason);
                }
                log.warn("第 {} 章修订暂停：计划与可信事实冲突，保留当前稿等待调整计划：{}",
                        globalNo, planAdjustmentReason);
                break;
            }

            if (reviseResult.revisedContent() != null && reviseResult.decision().isAccepted()) {
                ChapterContentEntity revised = reviseResult.revisedContent();
                String contentBeforeRevision = chapterContent.getContent();
                String titleBeforeRevision = chapterContent.getTitle();
                List<ChapterIssueEntity> minorBeforeRevision = mechanicalMinor;
                DeliveryReview deliveryBeforeRevision = deliveryReview;
                boolean degradedBeforeRevision = auditVerifyDegraded;
                // 标题非空才覆盖：定向补丁刻意不带标题（它只动正文局部），
                // 无条件赋值会把原本完好的标题清成 null
                if (StringUtils.isNotBlank(revised.getTitle())) {
                    chapterContent.setTitle(revised.getTitle());
                }
                chapterContent.setContent(revised.getContent());
                log.info("第 {} 章第 {}/{} 轮修订已采纳，原因：{}", globalNo, attempt, maxAttempts,
                        reviseResult.decision().getReason());

                // 修订稿复审：本方法不吞异常，但 verifyFixes 自身对"验证没跑成"做降级标记而非抛出，
                // 故此处只需接住结果（原注释"硬失败照旧 AppException 终止本批"与实现不符，已修正）
                // 机械门禁重扫：修订稿带套话/引入泄底都不算闭环（字数不再进 BLOCKING，见首审处说明）
                List<ChapterIssueEntity> rescan = new java.util.ArrayList<>(
                        StyleViolationPolicy.check(chapterContent.getContent()));
                if (!secrecyGuard.isEmpty()) {
                    rescan.addAll(SecrecyViolationPolicy.check(chapterContent.getContent(), secrecyGuard.keywords()));
                }
                // 机制门禁重算：修订稿若真的用上了金手指 / 删掉了原理段，本次即闭环
                rescan.addAll(consistencyIndexService.mechanismIssues(
                        requestParameter.getStoryVO(), summaries, globalNo, chapterContent.getContent()));
                // 标题唯一性重算：修订可能改了标题，留旧结论会误导（改了名即闭环）
                if (StringUtils.isNotBlank(chapterContent.getTitle())) {
                    rescan.addAll(ChapterTitlePolicy.check(chapterContent.getTitle(), summaries));
                }
                // 最终稿重算对白观测，不因低密度或词面差异制造新的 BLOCKING。
                rescan.addAll(dialogueDensityCollapse(item, chapterContent, summaries));
                // 重复类硬伤重算：整章重写是“以现有 BLOCKING 为靶点”的，可能**新引入**重复台词/
                // 重复整句；verifyFixes 只验证旧问题是否修好，不重算这两项的话，
                // 带重复硬伤的终稿会以“newBlocking 为空”闭环放行
                rescan.addAll(dialogueRepeatIssues(chapterContent, styleStat));
                rescan.addAll(intraChapterRepeatIssues(chapterContent));
                // 篇幅坍缩重算：修复旧 BLOCKING 不代表终稿成篇，重写后仍须通过硬底线。
                // 开关必须与首审（见上方 800 字硬底线处）同源：1500 参考线告警与 800 硬底线
                // 是两个独立开关，共用会让“关掉告警”静默连返工一起关掉
                if (storyProperties.getAudit().isEnforceCollapseFloor()
                        && ChapterLengthPolicy.isCollapsed(chapterContent.getContent())) {
                    int effective = ChapterLengthPolicy.effectiveCharacterCount(chapterContent.getContent());
                    ChapterIssueEntity stillCollapsed = new ChapterIssueEntity();
                    stillCollapsed.setDimension("aesthetic");
                    stillCollapsed.setSeverity("BLOCKING");
                    stillCollapsed.setEvidence("修订稿全章有效字 " + effective + "，仍低于硬底线 "
                            + ChapterLengthPolicy.COLLAPSE_FLOOR_CHARACTERS);
                    stillCollapsed.setDescription("篇幅坍缩（修订稿仍未达标）：有效字 " + effective
                            + " 低于硬底线 " + ChapterLengthPolicy.COLLAPSE_FLOOR_CHARACTERS
                            + "，仍是提纲形态。修订必须把被概述掉的过程铺展开，而不是在短稿上做局部替换。");
                    stillCollapsed.setSuggestion("依据完整计划与冻结事实重写关键过程，不规定对白句数或轮次；"
                            + "保留有效心理与环境，避免用重复确认充篇幅。");
                    rescan.add(stillCollapsed);
                    log.warn("第 {} 章第 {}/{} 轮修订稿篇幅仍坍缩：有效字 {} 低于硬底线 {}，继续修订",
                            globalNo, attempt, maxAttempts, effective,
                            ChapterLengthPolicy.COLLAPSE_FLOOR_CHARACTERS);
                }
                // 降档项以最终稿重扫结果为准：修订可能清掉或改变文风问题，留旧版记录会误导观测层
                mechanicalMinor = nonBlockingOf(rescan);
                List<ChapterIssueEntity> newBlocking = new java.util.ArrayList<>(blockingOf(rescan));
                List<ChapterIssueEntity> blockingBeforeRound = new java.util.ArrayList<>(currentBlocking);
                boolean planCandidateUnfixed = false;
                if (auditEnabled()) {
                    // A3 复审验证化（docs/enhancement-plan.md）：复审不再全章重审——开放任务方差大，
                    // 二轮翻案即"修订拉锯"；改为逐条验证上一轮 BLOCKING 是否已修复（未确认已修复=仍未修复）。
                    // 机械门禁重扫（上方 rescan）照旧兜住修订稿新引入的硬伤。
                    // 观测口径变化：叙事 MINOR 计数保留首审值——验证任务不做全章扫描，不产出新 MINOR
                    // 没有计划完整性候选时走原调用形态，保持既有复验入口不变。
                    List<String> candidates = reviseResult.decision() == null
                            ? List.of() : reviseResult.decision().getReviewCandidates();
                    boolean hasCandidates = candidates != null && !candidates.isEmpty();
                    ChapterAuditService.FixVerification verification = deliveryContract != null
                            ? chapterAuditService.verifyFixes(requestParameter.getStoryVO(), item,
                                    chapterContent.getContent(), currentBlocking, ledgerPrompt, styleStat, globalNo,
                                    writerContext, candidates == null ? List.of() : candidates,
                                    deliveryRequest(deliveryContract, chapterContent, deliveryMode, writerContext))
                            : !hasCandidates
                            ? (writerContext == null ? chapterAuditService.verifyFixes(
                                    requestParameter.getStoryVO(), item, chapterContent.getContent(),
                                    currentBlocking, ledgerPrompt, styleStat, globalNo)
                                    : chapterAuditService.verifyFixes(requestParameter.getStoryVO(), item,
                                    chapterContent.getContent(), currentBlocking, ledgerPrompt, styleStat, globalNo,
                                    writerContext))
                            : chapterAuditService.verifyFixes(requestParameter.getStoryVO(), item,
                                    chapterContent.getContent(), currentBlocking, ledgerPrompt, styleStat, globalNo,
                                    writerContext, candidates);
                    // 验证没跑成 ≠ 没修好：逐项交付结论不能由旧 fixed=true 或词面命中替代。
                    auditVerifyDegraded = verification == null || verification.degraded();
                    if (deliveryContract != null) {
                        deliveryReview = DeliveryReviewPolicy.ensureBound(
                                verification == null ? null : verification.deliveryReview(), item,
                                ChapterManuscript.of(chapterContent), deliveryMode == Mode.ENFORCE);
                        if (auditVerifyDegraded) {
                            deliveryReview = DeliveryReviewPolicy.unavailable(deliveryRequest(deliveryContract,
                                    chapterContent, deliveryMode, writerContext),
                                    "DELIVERY_VERIFY_UNAVAILABLE: 修订复验未完成，不能确认当前稿交付");
                        }
                    }
                    // 完整性候选独立维度；新的交付问题只从同稿报告重算，不能沿用旧稿的问题证据。
                    List<ChapterIssueEntity> unfixed = verification == null ? currentBlocking : verification.unfixed();
                    for (ChapterIssueEntity issue : unfixed) {
                        if (ChapterAuditService.PLAN_INTEGRITY_DIMENSION.equals(issue.getDimension())) {
                            planCandidateUnfixed = true;
                        } else if (deliveryContract == null || !DeliveryReviewPolicy.isDeliveryIssue(issue)) {
                            newBlocking.add(issue);
                        }
                    }
                    newBlocking.addAll(DeliveryReviewPolicy.blockingIssues(deliveryReview));
                }
                // 完整性候选未通过：修订稿改了原稿，但仍有计划事件未获复核确认，回退本次采纳。
                // 必须放在“全部修好即通过”之前——旧 BLOCKING 全清但事件被删，同样不能闭环。
                if (planCandidateUnfixed) {
                    chapterContent.setContent(contentBeforeRevision);
                    chapterContent.setTitle(titleBeforeRevision);
                    currentBlocking = blockingBeforeRound;
                    mechanicalMinor = minorBeforeRevision;
                    deliveryReview = deliveryBeforeRevision;
                    auditVerifyDegraded = degradedBeforeRevision;
                    log.warn("第 {} 章第 {}/{} 轮修订未通过计划完整性复核，回退本次采纳", globalNo, attempt, maxAttempts);
                    break;
                }
                if (deliveryReview.unknown()) {
                    // 未验证稿保留为草稿，不把“上一版的问题”当成新稿的内容结论，更不继续盲目重写。
                    return GateResult.of(newBlocking, minorCount, mechanicalMinor, attempt, semanticMinors, auditVerifyDegraded)
                            .withSemanticFeedbackHash(semanticFeedbackHash).withDeliveryReview(deliveryReview);
                }
                if (newBlocking.isEmpty()) {
                    log.info("第 {} 章第 {}/{} 轮复审通过，闭环完成", globalNo, attempt, maxAttempts);
                    return GateResult.of(List.of(), minorCount, mechanicalMinor, attempt, semanticMinors, auditVerifyDegraded)
                    .withSemanticFeedbackHash(semanticFeedbackHash).withDeliveryReview(deliveryReview);
                }
                currentBlocking = newBlocking;
                log.warn("第 {} 章第 {}/{} 轮复审仍发现 BLOCKING 问题 {} 条，继续修订",
                        globalNo, attempt, maxAttempts, currentBlocking.size());
                previousRejectionReason = "修订稿已通过采纳闸门，但复审仍发现 BLOCKING 问题："
                        + reviseResult.decision().getReason();
            } else {
                log.info("第 {} 章第 {}/{} 轮修订未采纳，保留当前稿，原因：{}", globalNo, attempt, maxAttempts,
                        reviseResult.decision().getReason());
                previousRejectionReason = reviseResult.decision().getReason();
            }
            // 未修复多重集与上轮相同即结束无效轮次，不改变耗尽后的放行判定。
            // 问题条数相同但内容变化仍视为有推进。
            String fingerprint = blockingFingerprint(currentBlocking);
            if (attempt < maxAttempts && fingerprint.equals(previousBlockingFingerprint)) {
                log.warn("第 {} 章修订第 {}/{} 轮未修复清单与上一轮相同（无进展），"
                                + "提前结束修订循环省掉无效轮次——BLOCKING 问题照旧记债",
                        globalNo, attempt, maxAttempts);
                break;
            }
            previousBlockingFingerprint = fingerprint;
        }

        log.warn("第 {} 章修订 {} 轮后仍有 BLOCKING 问题 {} 条（结论交由上层处置：主路径记为质量债，"
                        + "候选挑战者路径会连同候选稿一起回退丢弃）",
                globalNo, reviseRounds, currentBlocking.size());
        if (deliveryContract == null || (!deliveryReview.unknown() && !auditVerifyDegraded)) {
            auditSampleService.record(storyDir, globalNo, item, chapterContent.getContent(),
                    currentBlocking, reviseRounds, ledgerPrompt, foreshadowing);
        }
        GateResult result = GateResult.of(currentBlocking, minorCount, mechanicalMinor, reviseRounds,
                        semanticMinors, auditVerifyDegraded)
                .withSemanticFeedbackHash(semanticFeedbackHash).withDeliveryReview(deliveryReview);
        return planAdjustmentReason == null ? result : result.withPlanAdjustmentRequired(planAdjustmentReason);
    }

    private static DeliveryReviewPolicy.Request deliveryRequest(DeliveryReviewPolicy.Contract contract,
                                                                 ChapterContentEntity chapter, Mode mode,
                                                                 RewriteContext writerContext) {
        return new DeliveryReviewPolicy.Request(contract, ChapterManuscript.of(chapter), mode == Mode.ENFORCE,
                writerContext == null ? null : writerContext::checkCancellation);
    }

    /**
     * 本书台词引号的主流风格是否为弯引号，供 {@link DialogueRatioPolicy#checkQuoteStyleDrift} 使用。
     *
     * 数据源是 {@link StyleStatEntity#getUsedDialogueLines()}——历史章节的对白句（含引号）。
     * 取不到足够样本时返回 {@code null}，检测整体跳过：新书前几章还没有历史，
     * 此时"主流"尚未形成，报漂移只会误伤。
     *
 *  规范由「」改为“”后的口径调整：prompt 层（hard-constraints.md /
     * plan-targets.md / ChapterBeatsService / ChapterWorker）已统一要求双引号，但历史章节
     * 全是「」。纯历史统计会把主流永远钉在旧写法上，导致**遵守新规的每一章都被判"漂移"**——
     * 那等于用质检去对抗规则本身。故此处直接返回 {@code true}（主流=双引号）：
     * 新规下仍在写「」的章才是漂移，与 prompt 方向一致。历史统计逻辑保留为
     * {@link #mainstreamCurlyQuotesFromHistory(StyleStatEntity)}，供需要历史基线的场景复用。
     */
    private static Boolean mainstreamCurlyQuotes(StyleStatEntity styleStat) {
        // 规则层已锁定双引号，主流判定跟随规则而非历史存量。
        return Boolean.TRUE;
    }

    /** 历史对白的引号主流统计（原 mainstreamCurlyQuotes 实现，保留作历史基线参考）。 */
    private static Boolean mainstreamCurlyQuotesFromHistory(StyleStatEntity styleStat) {
        if (styleStat == null || styleStat.getUsedDialogueLines() == null
                || styleStat.getUsedDialogueLines().size() < 10) {
            return null;
        }
        int corner = 0;
        int curly = 0;
        for (String line : styleStat.getUsedDialogueLines()) {
            if (line == null) {
                continue;
            }
            if (line.indexOf('「') >= 0) {
                corner++;
            }
            if (line.indexOf('“') >= 0) {
                curly++;
            }
        }
        int total = corner + curly;
        if (total < 10) {
            return null;
        }
        return curly * 2 > total;
    }

    /** 与写手同源的作者语域/编辑范围；旧检查标题保留给已有提示词定位。 */
    private static String renderRegisterContract(ArmoryCommandEntity request) {
        StoryContextEntity context = request == null ? null : request.getStoryContextEntity();
        return "【语言与时代语域检查】\n" + WritingContractPolicy.renderRegister(context)
                + "\n" + WritingContractPolicy.renderEditorialScope();
    }

    /** 拼接机械预警块（忽略空段，非空段以空行分隔）；全空返回 null */
    private static String joinBlocks(String first, String second) {
        boolean hasFirst = StringUtils.isNotBlank(first);
        boolean hasSecond = StringUtils.isNotBlank(second);
        if (!hasFirst && !hasSecond) {
            return null;
        }
        if (hasFirst && hasSecond) {
            return first + "\n\n" + second;
        }
        return hasFirst ? first : second;
    }

    /** 语义 MINOR 清单（P2 审校反馈回灌的数据源）：与 minorOf 同口径，但保留完整 issue 供渲染 */
    private List<ChapterIssueEntity> semanticMinorsOf(AuditResultEntity auditResult) {
        if (auditResult == null || auditResult.getIssues() == null) {
            return List.of();
        }
        return auditResult.getIssues().stream()
                .filter(issue -> issue != null && "MINOR".equalsIgnoreCase(issue.getSeverity()))
                .toList();
    }

    private int minorOf(AuditResultEntity auditResult) {
        if (auditResult == null || auditResult.getIssues() == null) {
            return 0;
        }
        return (int) auditResult.getIssues().stream()
                .filter(issue -> issue != null && "MINOR".equalsIgnoreCase(issue.getSeverity()))
                .count();
    }

    private List<ChapterIssueEntity> blockingOf(AuditResultEntity auditResult) {
        if (auditResult == null || auditResult.getIssues() == null || auditResult.getIssues().isEmpty()) {
            return List.of();
        }
        return auditResult.getIssues().stream()
                .filter(issue -> "BLOCKING".equalsIgnoreCase(issue.getSeverity()))
                .toList();
    }

    /**
     * 人物档案锚（代词与既定事实），注入审校 guard。
     *
     * 审校需要作者声明的人物事实作为对照，才能核对代词、学段与亲属等身份信息。
     * 没有事实锚时不能凭叙事印象判定身份冲突。
     */
    private static String characterProfileHint(ArmoryCommandEntity requestParameter) {
        StoryContextEntity ctx = requestParameter == null ? null : requestParameter.getStoryContextEntity();
        String profiles = ctx == null ? null : ctx.getCharacterProfiles();
        if (StringUtils.isBlank(profiles)) {
            return null;
        }
        return "【人物档案·代词与既定事实】（作者声明的事实锚，判定时以此为准）\n" + profiles
                + "\n与档案冲突的写法属**事实矛盾**：代词用错（把女角色写成「他」）、"
                + "班级/年级/志愿写错、亲属关系写错、已声明的事实被改写——"
                + "这类错误读者一眼能看出，必须记 BLOCKING 并给出正文中的确切位置。";
    }

    /**
     * 跨章对白逐字重复（机械）：本章有台词与早期章节一字不差 → 每句一条 BLOCKING。
     *
     * 口径只认精确相等（去空白后）：这条要进 BLOCKING 驱动修订，误报的代价是白改一稿；
     * 而"同一句台词隔几章一字不差地又出现"本身就是硬伤，放宽口径会把正常呼应也扫进来。
     * 近重复（改一两个字）仍走风格警示那条软通道，不进这里。
     *
     * {@code styleStat} 在本章 merge 之前传进来（ChapterWorker 的调用顺序），所以索引里
     * 只有历史章节的对白——本章自己不会跟自己撞。
     */
    private List<ChapterIssueEntity> dialogueRepeatIssues(ChapterContentEntity chapterContent,
                                                          StyleStatEntity styleStat) {
        if (chapterContent == null || StringUtils.isBlank(chapterContent.getContent())) {
            return List.of();
        }
        List<String> repeats = styleStatService.crossChapterDialogueRepeats(styleStat, chapterContent.getContent());
        if (repeats.isEmpty()) {
            return List.of();
        }
        List<ChapterIssueEntity> issues = new java.util.ArrayList<>(repeats.size());
        for (String line : repeats) {
            ChapterIssueEntity issue = new ChapterIssueEntity();
            issue.setDimension("aesthetic");
            issue.setSeverity("BLOCKING");
            issue.setEvidence(line);
            issue.setDescription("跨章对白逐字重复：本章这句台词与早期章节一字不差（" + line
                    + "）——读者会认为人物在复读同一句话。");
            issue.setSuggestion("只改这一句：换一个问法或说法，或把这次交流改成动作/神态等非对白表达；"
                    + "不要只改标点、增减语气词或换成同义句来糊过去。其余正文不要改动。");
            issues.add(issue);
        }
        return issues;
    }

    /**
     * 章内整句重复（机械）：同一句在本章内部一字不差地出现两次以上 → 每句一条 BLOCKING。
     *
     * 为什么进 BLOCKING 而不是 MINOR：相邻整句复制是读者一眼可见的硬伤
     * （"这段写了两遍"），修法极其机械（删掉重复的那一遍），定向修订的成本远低于留在成稿里的代价。
     * 与跨章对白重复同一处置逻辑（{@link #dialogueRepeatIssues}）。
     *
     * 口径只认精确相等（去空白后），短句不判——近重复与短句复用走风格警示的软通道。
     */
    private List<ChapterIssueEntity> intraChapterRepeatIssues(ChapterContentEntity chapterContent) {
        if (chapterContent == null || StringUtils.isBlank(chapterContent.getContent())) {
            return List.of();
        }
        List<String> repeats = StyleStatService.intraChapterDuplicateSentences(chapterContent.getContent());
        if (repeats.isEmpty()) {
            return List.of();
        }
        List<ChapterIssueEntity> issues = new java.util.ArrayList<>(repeats.size());
        for (String sentence : repeats) {
            ChapterIssueEntity issue = new ChapterIssueEntity();
            issue.setDimension("aesthetic");
            issue.setSeverity("BLOCKING");
            issue.setEvidence(sentence);
            issue.setDescription("章内整句重复：本章这句话一字不差地出现了两次以上（" + sentence
                    + "）——读者会立刻看出这一段被写了两遍。");
            issue.setSuggestion("删掉重复的那一遍，只保留语义位置最合适的一处；"
                    + "若两处都想保留，把其中一处改写成不同的表达（动作/神态/别的说法），"
                    + "不要只改标点或增减语气词。其余正文不要改动。");
            issues.add(issue);
        }
        return issues;
    }

    /**
     * 对白轮次仅作机械观测，不用密度证明戏剧性或场景遗漏。
     * 声明台词的词面未匹配也可能是等价表达，不能升级 BLOCKING 或自动下发补量指令；
     * 真正遗漏继续由既有场景/计划语义审校及修订复核处理。
     * 题材阈值、过渡章与短样本豁免保留，便于与统计口径对照。
     */
    private static List<ChapterIssueEntity> dialogueDensityCollapse(ChapterPlanItemEntity item,
                                                                   ChapterContentEntity chapterContent,
                                                                   List<ChapterSummaryEntity> summaries) {
        if (chapterContent == null || StringUtils.isBlank(chapterContent.getContent())) {
            return List.of();
        }
        if (item.getChapterType() != null && item.getChapterType().isTransition()) {
            return List.of();
        }
        String content = chapterContent.getContent();
        int effectiveChars = ChapterLengthPolicy.effectiveCharacterCount(content);
        if (effectiveChars < DialogueRatioPolicy.FORMAT_COLLAPSE_MIN_CHARS) {
            return List.of();
        }
        String genre = summaries == null ? null : summaries.stream()
                .filter(java.util.Objects::nonNull)
                .map(ChapterSummaryEntity::getStoryGenre)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        int utterances = DialogueRatioPolicy.utteranceCount(content);
        double density = DialogueRatioPolicy.utteranceDensity(utterances, effectiveChars);
        double line = DialogueRatioPolicy.densityDegraded(genre);
        if (density >= line) {
            return List.of();
        }
        List<String> declaredScenes = item.getDialogueScenes();
        List<String> missing = DialoguePresencePolicy.missingScenes(declaredScenes, content);
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        issue.setSeverity("MINOR");
        issue.setEvidence(String.format("全章 %d 有效字，对白轮次 %d 次，密度 %.1f 次/千字；声明台词词面未匹配 %d 条",
                effectiveChars, utterances, density, missing.size()));
        issue.setDescription(String.format("对白轮次观测：密度 %.1f 次/千字，低于题材参考线 %.1f。"
                + "仅记录表达分布，不据此判定场景缺失、人物失语或不成戏；心理、讲述、等待与后果消化同样可以承载场景。",
                density, line));
        issue.setSuggestion("仅作观测，不要求补量，不新增对白、谈判或事件任务。"
                + "声明台词的词面未匹配不是事实裁决；如怀疑真正遗漏，应结合完整场景计划、既有 presence/plan-adherence 线索做语义核验，"
                + "只有确认未兑现的场景内容才按原作者范围修复，已兑现的简短交流不扩写。"
                + (missing.isEmpty() ? "" : "词面待核验声明：" + String.join("；", missing)));
        return List.of(issue);
    }

    /**
     * 按严重度筛选：BLOCKING 档（进修订闭环）。
     * 供审校结论与机械结论共用——机械门禁已分层，旧口径「一律 BLOCKING」不再成立。
     */
    private static List<ChapterIssueEntity> blockingOf(List<ChapterIssueEntity> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        return issues.stream()
                .filter(issue -> issue != null && "BLOCKING".equalsIgnoreCase(issue.getSeverity()))
                .toList();
    }

    /**
     * 未修复 BLOCKING 清单的**内容指纹**：供修订循环做"无进展检测"。
     *
     * 取描述文本（trim + 去空白）排序后拼接——多重集语义：
     * 条数相同但换了问题（说明修订有推进）不会被误判为无进展；
     * 而同一条问题在措辞上仅差空白时仍算同一问题。
     * 描述为空时退用 dimension+evidence 兜底（机械类问题可能只有 evidence）。
     */
    private static String blockingFingerprint(List<ChapterIssueEntity> blocking) {
        if (blocking == null || blocking.isEmpty()) {
            return "";
        }
        return blocking.stream()
                .filter(java.util.Objects::nonNull)
                .map(issue -> {
                    String text = issue.getDescription();
                    if (StringUtils.isBlank(text)) {
                        text = StringUtils.defaultString(issue.getDimension())
                                + "|" + StringUtils.defaultString(issue.getEvidence());
                    }
                    return text.replaceAll("\\s+", "");
                })
                .sorted()
                .collect(java.util.stream.Collectors.joining("\u0001"));
    }

    /**
     * 按严重度筛选：非 BLOCKING 档（机械文风程度问题）。
     * 这些只落质量债供观测统计，不进修订闭环、不计入 grade、不触发候选选优。
     */
    private static List<ChapterIssueEntity> nonBlockingOf(List<ChapterIssueEntity> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        return issues.stream()
                .filter(issue -> issue != null && !"BLOCKING".equalsIgnoreCase(issue.getSeverity()))
                .toList();
    }
}
