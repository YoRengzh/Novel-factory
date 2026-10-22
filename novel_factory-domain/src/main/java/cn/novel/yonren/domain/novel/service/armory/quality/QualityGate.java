package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.audit.AuditSampleService;
import cn.novel.yonren.domain.novel.service.armory.audit.ChapterAuditService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ConsistencyIndexService;
import cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService;
import cn.novel.yonren.domain.novel.service.armory.revise.ChapterReviseService;
import cn.novel.yonren.types.enums.GenreTypeVO;
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
     * 七期扩展：内容密度检测（有效对话句数<25时给审校注入加审预警，WARN 语义不直接 BLOCKING）。
     * 八期扩展：正文级对话承载对账——计划声明了 dialogueScenes 的章，轮次密度坍缩从 MINOR
     * 升级 BLOCKING 进修订闭环（计划承诺的对话被写成动作/沉默是写手违约），缺失场景由
     * {@link DialoguePresencePolicy} 识别并作为修订靶点下发，修订采纳后机械重扫闭环。
     * 九期扩展（2026-10-13）：篇幅坍缩硬底线——有效字低于
     * {@link ChapterLengthPolicy#COLLAPSE_FLOOR_CHARACTERS} 的"提纲式废稿"记 BLOCKING；
     * 且**修订稿也要重新过线**（首审告警发生在重写前，重写后不复测就是 136 章废稿出厂的直接原因）。

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
        // 段落结构兜底：写手偶尔把整章当成单个字符串吐出（实测 4/10 章换行数为 0，
        // 单章 3000~4600 字挤成一段）。必须在任何按行统计之前重排，否则对白行占比会被误算为 1.0
        // （ratioOf = 含引号行/非空行，整章仅 1 行且含引号 ⇒ 恒 1.0），段落密度审校也无从下手。
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
        // 篇幅坍缩硬底线（2026-10-13）：低于 800 有效字 = 提纲式废稿，BLOCKING 进修订闭环。
        // 与上面 1500 线告警的分工见 ChapterLengthPolicy#COLLAPSE_FLOOR_CHARACTERS 的类注释：
        // 1500 答"计划给的料够不够"（回灌规划层），本线答"这一稿是不是根本不成篇"（当场返工）。
        // 实测第 136 章终稿 674 字、无场景铺展，正是缺了这条线才出厂。
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
            collapsed.setSuggestion("按计划的关键事件逐条铺展成场面：每个事件至少给出一处可观察的场景、"
                    + "两轮以上有来有回的对话、以及人物的动作与反应；不要靠环境描写或心理独白凑字数。"
                    + "不要压缩既有情节，只把被概述掉的过程写出来。");
            lengthIssues = List.of(collapsed);
        }
        // 上沿同口径告警：字数超标不是"写得多"，而是**关键事件数没变、水变多了**——
        // 实测第 20 章 4170 字 / 5 个关键事件，对照第 19 章 2205 字 / 5 个关键事件。
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
        // 分层依据见 StyleViolationPolicy 类注释（162 章实测：原口径 53 条 BLOCKING 人工核验后 0 条成立）
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
        // 内容密度检测（机械预检）：有效对话句数<25时给审校注入加审预警（WARN 语义，不直接 BLOCKING）
        int dialogueCount = chapterContent == null ? 0 : ContentDensityPolicy.countDialogue(chapterContent.getContent());
        String densityHint = ContentDensityPolicy.renderLowDensityHint(dialogueCount);
        if (dialogueCount < ContentDensityPolicy.MIN_DIALOGUE_COUNT) {
            log.warn("第 {} 章内容密度预警：有效对话仅 {} 句，低于最低要求 {} 句", globalNo,
                    dialogueCount, ContentDensityPolicy.MIN_DIALOGUE_COUNT);
        }
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
        // 篇幅坍缩（机械 BLOCKING，2026-10-13）：与文风/标题等程度类问题不同，
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
        // 引号风格漂移检测（机械，MINOR，2026-10-13）：本章台词几乎全用弯引号而本书主流是直角引号
        // （或反之）。两种写法都合法、现有检测都不报，但同书混用是读者一眼可见的排版不一致——
        // 实测第 136 章重写稿 8 组弯引号，其余 139 章全是「」。MINOR 落债回灌，不触发修订。
        List<ChapterIssueEntity> quoteStyleIssues = chapterContent == null ? List.of()
                : DialogueRatioPolicy.checkQuoteStyleDrift(chapterContent.getContent(),
                        item.getChapterType() == null ? null : item.getChapterType().getCode(),
                        mainstreamCurlyQuotes(styleStat));
        if (!quoteStyleIssues.isEmpty()) {
            log.warn("第 {} 章引号风格漂移：{}", globalNo,
                    quoteStyleIssues.stream().map(ChapterIssueEntity::getEvidence).toList());
        }
        mechanicalIssues.addAll(quoteStyleIssues);
        // 对白轮次坍缩检测（机械，P1，2026-10-09）：篇幅够、关键事件也落地了，但轮次密度低于劣化线——
        // 典型形态是通篇动作分解与内心推演（实测第 96 章 4.4 次/千字，劣化线 6.0，同批其它章 12-27）。
        // 与上面两条的分工：那两条判**格式**（引号有没有、是不是括号），这条判**有没有戏**。
        // 为什么必须落债：已有的 ContentDensityPolicy 预警（<25 句）只进审校 prompt，而审校规则里
        // BLOCKING 仅限四类硬伤、MINOR 明写"只记录不触发修订"——它在设计上就不会产生任何动作，
        // 实测第 96 章报了预警后原稿照样放行。落成质量债才会随记忆回灌到规划层与下一章 prompt。
        List<ChapterIssueEntity> densityCollapse = dialogueDensityCollapse(item, chapterContent, summaries);
        if (!densityCollapse.isEmpty()) {
            log.warn("第 {} 章对白轮次坍缩：{}", globalNo,
                    densityCollapse.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        mechanicalIssues.addAll(densityCollapse);
        // 跨章对白逐字重复（机械，2026-10-10）：同一句台词与早期章节一字不差 → BLOCKING，走定向修订。
        // 此前这类重复只进「风格警示」（下一章的软提示）与 MINOR 记账，于是第 100 章与第 102 章
        // 一字不差地问了同一句「三班还等得到吗？」——两句都留在了成稿里。对白重复是读者最容易
        // 察觉的硬伤（人物像在复读），修法又机械（改掉这一句），所以放进 BLOCKING 让它真的被改。
        List<ChapterIssueEntity> dialogueRepeat = dialogueRepeatIssues(chapterContent, styleStat);
        if (!dialogueRepeat.isEmpty()) {
            log.warn("第 {} 章跨章对白逐字重复 {} 句：{}", globalNo, dialogueRepeat.size(),
                    dialogueRepeat.stream().map(ChapterIssueEntity::getEvidence).toList());
        }
        mechanicalIssues.addAll(dialogueRepeat);
        // 章内相邻整句重复（机械，2026-10-15）：同一句在本章内部一字不差地出现两次 → BLOCKING。
        // 与上一块的跨章查重互补——那个只比"本章 vs 历史"，本章自己内部撞车不在射程内。
        // 实测成因：段落密度审校的改写补丁把"删除该段"误写成"保留原句再接一遍"，
        // 而那条路径的采纳闸门只查机械文风违规与语义锚，重复句两条都不碰，于是漏进成稿。
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
        if (!auditEnabled() && mechanicalBlocking.isEmpty()) {
            return GateResult.of(List.of(), 0, mechanicalMinor, 0);
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
                joinBlocks(joinBlocks(joinBlocks(secrecyGuard.promptBlock(), adherenceHint), densityHint), chapterRefHint),
                joinBlocks(joinBlocks(registerContract, timeAnchor), characterProfileHint(requestParameter)));
        // 修订输入保留禁泄清单与语域契约（防修订环节修好事实、却引入时代错位或人物失语）
        String reviseForeshadowing = joinBlocks(joinBlocks(foreshadowing, secrecyGuard.promptBlock()), registerContract);

        // 审校调用在 try 之外：解析/重试均失败时 AppException 直接终止本批
        List<ChapterIssueEntity> currentBlocking = new java.util.ArrayList<>(mechanicalBlocking);
        int minorCount = 0;
        List<ChapterIssueEntity> semanticMinors = List.of();
        int reviseRounds = 0;
        // 修订验证是否"根本没跑成"：为真时 currentBlocking 的语义是
        // 【未验证】而非【确认未修复】——内容侧偏向不变（仍算未通过、不放行未验证的稿），
        // 但上层不得把它记入质量债，观测侧单独计数（见 FixVerification / BatchHealthService）
        boolean auditVerifyDegraded = false;
        if (auditEnabled()) {
            AuditResultEntity auditResult = chapterAuditService.audit(
                    requestParameter.getStoryVO(), item, chapterContent.getContent(),
                    ledgerPrompt, foreshadowing, styleStat, globalNo, auditGuard);
            currentBlocking.addAll(blockingOf(auditResult));
            minorCount = minorOf(auditResult);
            // P2 审校反馈回灌：语义 MINOR 清单随 GateResult 传出（验证化复审不产出新 MINOR，以首审为准）
            semanticMinors = semanticMinorsOf(auditResult);
        }
        if (currentBlocking.isEmpty()) {
            log.info("第 {} 章审校完成，仅 MINOR（叙事 {} 条 / 文风 {} 条）或不含 BLOCKING 问题，不触发修订",
                    globalNo, minorCount, mechanicalMinor.size());
            return GateResult.of(List.of(), minorCount, mechanicalMinor, 0, semanticMinors, auditVerifyDegraded);
        }

        log.warn("第 {} 章审校发现 BLOCKING 问题 {} 条，准备修订", globalNo, currentBlocking.size());

        if (storyProperties.getRevise() == null || !storyProperties.getRevise().isEnabled()) {
            log.info("第 {} 章修订未启用，保留原稿", globalNo);
            auditSampleService.record(storyDir, globalNo, item, chapterContent.getContent(),
                    currentBlocking, 0, ledgerPrompt, foreshadowing);
            return GateResult.of(currentBlocking, minorCount, mechanicalMinor, 0, semanticMinors, auditVerifyDegraded);
        }

        int maxAttempts = Math.max(1, storyProperties.getRevise().getMaxAttempts());
        // 上一轮采纳闸门拒绝原因（方案2回传）：让次轮修订知道为何被拒，定向避免而非盲改
        String previousRejectionReason = null;
        // 无进展检测（2026-10-13）：上一轮结束时的 BLOCKING 清单指纹。
        // 实测（候选服务注释记录的样本）：两轮 revise + 两轮 audit-verify 共 10 次调用 53k token
        // 之后，未修复清单与首轮逐字相同——纯损耗。判据用"问题描述归一化后的多重集"，
        // 而非条数：条数相同但换了问题不算无进展（那说明修订有推进）。
        String previousBlockingFingerprint = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            reviseRounds = attempt;
            ChapterReviseService.ReviseResult reviseResult;
            try {
                reviseResult = chapterReviseService.revise(
                        requestParameter.getStoryVO(), item, chapterContent.getContent(),
                        currentBlocking, ledgerPrompt, reviseForeshadowing, styleStat, globalNo,
                        previousRejectionReason);
            } catch (Exception e) {
                // 修订失败保留审校结论：BLOCKING 记为质量债，随后续章节记忆回灌
                log.warn("第 {} 章第 {}/{} 轮修订流程异常，保留当前稿并将 BLOCKING 问题记为质量债",
                        globalNo, attempt, maxAttempts, e);
                auditSampleService.record(storyDir, globalNo, item, chapterContent.getContent(),
                        currentBlocking, attempt, ledgerPrompt, foreshadowing);
                return GateResult.of(currentBlocking, minorCount, mechanicalMinor, attempt, semanticMinors, auditVerifyDegraded);
            }

            if (reviseResult.revisedContent() != null && reviseResult.decision().isAccepted()) {
                ChapterContentEntity revised = reviseResult.revisedContent();
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
                // 对白轮次坍缩重算（2026-10-11）：声明了对话场景的章它是 BLOCKING——修订真的把
                // 对话补进正文即机械闭环；未声明的章是 MINOR。降档项以最终稿重算，口径与上面一致
                rescan.addAll(dialogueDensityCollapse(item, chapterContent, summaries));
                // 重复类硬伤重算：整章重写是“以现有 BLOCKING 为靶点”的，可能**新引入**重复台词/
                // 重复整句；verifyFixes 只验证旧问题是否修好，不重算这两项的话，
                // 带重复硬伤的终稿会以“newBlocking 为空”闭环放行
                rescan.addAll(dialogueRepeatIssues(chapterContent, styleStat));
                rescan.addAll(intraChapterRepeatIssues(chapterContent));
                // 篇幅坍缩重算（2026-10-13，本次修复的核心）：首审的字数告警发生在重写**之前**，
                // 而整章重写是"以现有 BLOCKING 为靶点"的——它可能把章写得更短却修好了那几条，
                // 于是废稿照样闭环。实测第 136 章：首审 87 字 → 整章重写 → 终稿 674 字
                // 却因"原 BLOCKING 已修复"而放行。修订稿必须重新过篇幅硬底线，才算真闭环。
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
                    stillCollapsed.setSuggestion("按计划关键事件逐条铺展成场面：每个事件至少一处可观察场景、"
                            + "两轮以上有来有回的对话、人物的动作与反应；不要靠环境描写或心理独白凑字数。");
                    rescan.add(stillCollapsed);
                    log.warn("第 {} 章第 {}/{} 轮修订稿篇幅仍坍缩：有效字 {} 低于硬底线 {}，继续修订",
                            globalNo, attempt, maxAttempts, effective,
                            ChapterLengthPolicy.COLLAPSE_FLOOR_CHARACTERS);
                }
                // 降档项以最终稿重扫结果为准：修订可能清掉或改变文风问题，留旧版记录会误导观测层
                mechanicalMinor = nonBlockingOf(rescan);
                List<ChapterIssueEntity> newBlocking = new java.util.ArrayList<>(blockingOf(rescan));
                if (auditEnabled()) {
                    // A3 复审验证化（docs/enhancement-plan.md）：复审不再全章重审——开放任务方差大，
                    // 二轮翻案即"修订拉锯"；改为逐条验证上一轮 BLOCKING 是否已修复（未确认已修复=仍未修复）。
                    // 机械门禁重扫（上方 rescan）照旧兜住修订稿新引入的硬伤。
                    // 观测口径变化：叙事 MINOR 计数保留首审值——验证任务不做全章扫描，不产出新 MINOR
                    ChapterAuditService.FixVerification verification = chapterAuditService.verifyFixes(
                            requestParameter.getStoryVO(), item, chapterContent.getContent(),
                            currentBlocking, ledgerPrompt, styleStat, globalNo);
                    // 验证没跑成 ≠ 没修好：照旧保守对待（unfixed 原样带回），但标记为【未验证】，
                    // 由上层决定不落债、观测侧单独计数
                    auditVerifyDegraded = verification.degraded();
                    newBlocking.addAll(verification.unfixed());
                }
                if (newBlocking.isEmpty()) {
                    log.info("第 {} 章第 {}/{} 轮复审通过，闭环完成", globalNo, attempt, maxAttempts);
                    return GateResult.of(List.of(), minorCount, mechanicalMinor, attempt, semanticMinors, auditVerifyDegraded);
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
            // 无进展检测（2026-10-13）：本轮结束的未修复清单若与上轮指纹一致，下一轮必然同样无效。
            // 实测样本（候选服务注释）：两轮 revise + 两轮 audit-verify 共 10 次调用 53k token 后，
            // 未修复清单与首轮逐字相同——纯损耗。**不改变放行判定**（仍走"轮次耗尽"路径记债），
            // 只省掉确定无效的重复劳动。判据用"描述归一化后的多重集"而非条数：
            // 条数相同但换了问题说明修订有推进，不该误判。
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
                globalNo, maxAttempts, currentBlocking.size());
        auditSampleService.record(storyDir, globalNo, item, chapterContent.getContent(),
                currentBlocking, maxAttempts, ledgerPrompt, foreshadowing);
        return GateResult.of(currentBlocking, minorCount, mechanicalMinor, maxAttempts, semanticMinors, auditVerifyDegraded);
    }

    /**
     * 本书台词引号的主流风格是否为弯引号（2026-10-13，供 {@link DialogueRatioPolicy#checkQuoteStyleDrift}）。
     *
     * <p>数据源是 {@link StyleStatEntity#getUsedDialogueLines()}——历史章节的对白句（含引号）。
     * 取不到足够样本时返回 {@code null}，检测整体跳过：新书前几章还没有历史，
     * 此时"主流"尚未形成，报漂移只会误伤。
     */
    private static Boolean mainstreamCurlyQuotes(StyleStatEntity styleStat) {
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
            if (line.indexOf('\u201C') >= 0) {
                curly++;
            }
        }
        int total = corner + curly;
        if (total < 10) {
            return null;
        }
        return curly * 2 > total;
    }

    /** 审校与修订共用的语域检查契约；只给 fantasy 附加仙侠词表，其他题材保持中性。 */
    private static String renderRegisterContract(ArmoryCommandEntity request) {
        if (request == null || request.getStoryContextEntity() == null) {
            return null;
        }
        var context = request.getStoryContextEntity();
        GenreTypeVO genre = GenreTypeVO.match(context.getTheme(), context.getStyle());
        StringBuilder contract = new StringBuilder("【语言与时代语域检查】检查叙述和对白是否符合故事时代、世界设定及人物的年龄、身份、教育、职业与认知边界；")
                .append("把无来源的学术报告腔、管理总结腔、互联网黑话、时代错位技术词或儿童不可能掌握的成熟抽象表达列为 aesthetic/character 问题。")
                .append("修订时必须改写为角色可观察的动作、物件、生活经验和口语，不得只替换同义词。");
        if (genre == GenreTypeVO.FANTASY) {
            contract.append("本题材为玄幻/仙侠：客观世界优先使用既有设定词；程序员术语只能在角色设定明确支持时作短促心理类比，不得充当世界事实。");
        }
        return contract.toString();
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
        if (auditResult.getIssues() == null || auditResult.getIssues().isEmpty()) {
            return List.of();
        }
        return auditResult.getIssues().stream()
                .filter(issue -> "BLOCKING".equalsIgnoreCase(issue.getSeverity()))
                .toList();
    }

    /**
     * 人物档案锚（代词与既定事实），注入审校 guard。
     *
     * <p><b>为什么必须给审校</b>（2026-10-10）：正文把女主角色庚写成「他」连写了三章，而审校
     * 只有 MINOR 叙事问题、一条 BLOCKING 都没报——不是它没看见，而是**没有可对照的锚**：
     * 账本里既没有"角色庚是女"也没有"她报另一班"，判"事实矛盾"无从下嘴。
     * 给了锚之后，代词写错、班级/亲属写错就落在审校规则既有的「事实矛盾」类里，可判 BLOCKING。
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
     * 跨章对白逐字重复（机械，2026-10-10）：本章有台词与早期章节一字不差 → 每句一条 BLOCKING。
     *
     * <p><b>口径只认精确相等</b>（去空白后）：这条要进 BLOCKING 驱动修订，误报的代价是白改一稿；
     * 而"同一句台词隔几章一字不差地又出现"本身就是硬伤，放宽口径会把正常呼应也扫进来。
     * 近重复（改一两个字）仍走风格警示那条软通道，不进这里。
     *
     * <p>{@code styleStat} 在本章 merge 之前传进来（ChapterWorker 的调用顺序），所以索引里
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
     * <p><b>为什么进 BLOCKING 而不是 MINOR</b>：相邻整句复制是读者一眼可见的硬伤
     * （"这段写了两遍"），修法极其机械（删掉重复的那一遍），定向修订的成本远低于留在成稿里的代价。
     * 与跨章对白重复同一处置逻辑（{@link #dialogueRepeatIssues}）。
     *
     * <p>口径只认精确相等（去空白后），短句不判——近重复与短句复用走风格警示的软通道。
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
     * 对白轮次坍缩（机械，P1）：轮次密度低于题材劣化线时记一条问题——严重度按「计划是否
     * 声明过对话承载」分流（2026-10-11）：
     *
     * <ul>
     *   <li><b>声明了 dialogueScenes</b>：规划层已经承诺"这些事件以对话承载"（对话承载闸门
     *       放行），正文仍开不了腔就是<b>写手违约</b>——记 BLOCKING 进修订闭环，缺失清单由
     *       {@link DialoguePresencePolicy#missingScenes} 识别并作为修订靶点下发（实测第 110 章
     *       形态：声明两句、成文一句，「嗯」被降级成点头——计划层闸门管不到正文兑现）。</li>
     *   <li><b>未声明</b>：根因在规划层没给料，照旧记 MINOR 落债回灌。</li>
     * </ul>
     *
     * <p><b>三处必须同源</b>：体检的 {@code dialogueCollapseShare}、规划层的【对白坍缩反馈】、
     * 这里的质量债，都用 {@link DialogueRatioPolicy#densityDegraded} 这同一根线。口径一旦分叉，
     * 就会出现"体检说崩了、债里没记、规划层没收到"的分裂——本项目在"两份 LCS 阈值不一致"
     * 上已经吃过同类亏。
     *
     * <p>过渡章豁免、短样本豁免（与 {@code DialogueRatioPolicy} 的其它检测同款：宁可漏报不误伤）。
     * 题材取自摘要的机械字段 storyGenre，与体检口径一致。
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
        boolean carriagePromised = declaredScenes != null && !declaredScenes.isEmpty();
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        if (carriagePromised) {
            List<String> missing = DialoguePresencePolicy.missingScenes(declaredScenes, content);
            issue.setSeverity("BLOCKING");
            issue.setEvidence(String.format(
                    "全章 %d 有效字，对白轮次 %d 次，密度 %.1f 次/千字；计划声明对话场景 %d 条，台词未落进正文 %d 条",
                    effectiveChars, utterances, density, declaredScenes.size(), missing.size()));
            issue.setDescription(String.format(
                    "对白轮次坍缩（对话承载违约）：计划已声明 %d 条对话承载场景（对话承载闸门放行的承诺），"
                            + "正文轮次密度仍只有 %.1f 次/千字，低于劣化线 %.1f（健康线 %.1f）——"
                            + "写手把承诺的对话写成了动作、神态或沉默。计划层闸门只校验声明存在，"
                            + "管不到正文兑现，此处即「声明→正文」的机械对账。",
                    declaredScenes.size(), density, line, DialogueRatioPolicy.densityOk(genre)));
            issue.setSuggestion(restorationSuggestion(declaredScenes, missing));
        } else {
            issue.setSeverity("MINOR");
            issue.setEvidence(String.format("全章 %d 有效字，对白轮次 %d 次，密度 %.1f 次/千字", effectiveChars, utterances, density));
            issue.setDescription(String.format("对白轮次坍缩：轮次密度 %.1f 次/千字，低于劣化线 %.1f"
                    + "（健康线 %.1f）。篇幅与事件可能都够，但角色几乎不开腔——通篇动作分解与内心推演，"
                    + "读者读到的是一份流程说明而不是戏。", density, line,
                    DialogueRatioPolicy.densityOk(genre)));
            issue.setSuggestion("规划层把「对话承载事件」提到 ≥2 条，并写明谁对谁、围绕什么分歧；"
                    + "节拍表的 dialogue 供给要写出具体交锋，不要只给动作分解。"
                    + "正文层不要靠增加环境描写或心理独白凑篇幅。");
        }
        return List.of(issue);
    }

    /**
     * 修订靶点（BLOCKING 档专用）：缺失场景逐条列出（允许等价改写，不得整场改成动作或沉默）；
     * 声明台词全部已落地时改为要求把场景扩写成有来有回的交锋——此时坍缩的根因是声明本身太薄
     * （两句短语撑不起一场戏），补对话仍是对的修法，但不能怪写手丢了台词。
     */
    private static String restorationSuggestion(List<String> declaredScenes, List<String> missing) {
        StringBuilder sb = new StringBuilder("把计划声明的对话场景落成引号对白：只补对话与必要的动作衔接，其余正文不要改动。");
        if (missing.isEmpty()) {
            sb.append("声明的台词均已在正文，但轮次总量仍不足——把这些场景扩写成有来有回的交锋，不要靠环境描写或心理独白凑数。");
            return sb.toString();
        }
        sb.append("以下场景的台词未在正文出现，必须补回（允许等价改写，不得只改成动作或神态）：");
        for (int i = 0; i < missing.size(); i++) {
            String scene = missing.get(i);
            sb.append('\n').append(i + 1).append(". ").append(scene.length() <= 60 ? scene : scene.substring(0, 60) + "…");
        }
        return sb.toString();
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
     * <p>取描述文本（trim + 去空白）排序后拼接——多重集语义：
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
