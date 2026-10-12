package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.memory.StoryMemoryService;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBudgetGuard;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterTitlePolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.DialogueRatioPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.ForeshadowSpanPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.PlaceTrajectoryPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.UsedPatternPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.RelationTrajectoryPolicy;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import cn.novel.yonren.types.enums.GenreTypeVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 章节计划 prompt 装配服务（自 BuildChapterPlanPromptNode 拆出）：
 * 基于故事上下文 + 记忆前缀 + 阶段蓝图组装规划 prompt。
 * 拆出原因：ChapterWorker 的惰性分段规划也需要同一份 prompt 装配——
 * 此前经 ObjectProvider 借用节点实例，worker→node 依赖方向反了；
 * 现在节点与 worker 都依赖本服务，依赖图无环。
 */
@Service
@Slf4j
public class ChapterPlanPromptService {

    @Resource
    private ChapterMemoryService chapterMemoryService;

    @Resource
    private RollingOutlineService rollingOutlineService;

    @Resource
    private StoryMemoryService storyMemoryService;

    /** 输入段总量守门（故事设定 + 记忆前缀）：固定要求与 schema 段不参与裁剪 */
    @Resource
    private PromptBudgetGuard promptBudgetGuard;

    /** 规划 prompt 尾部章节 schema 指令块的起始标记：分支推演须自此处剥离，避免与"只输出方向"指令打架 */
    public static final String PLAN_SCHEMA_MARKER = "请严格按照以下 JSON 格式输出";


    public static final String MAINLINE_BLOCK_MARKER = "【章级主线推进】";

    /** 8.2 要求行的前缀标记（与 {@link #MAINLINE_BLOCK_MARKER} 一同剥离，避免留下悬空的"见下方"引用） */
    public static final String MAINLINE_REQUIREMENT_MARKER = "8.2 ⚠️ mainLineAdvance";

    /** 单段规划注入的限期回收条数上限：超出部分留在账本，下次阶段出口重新裁决（自愈，不丢线索） */
    private static final int SETTLEMENT_INJECTION_LIMIT = 12;

    /** 低密度判定：有效字符低于该值视为"计划供给不足"（与全局 1500 参考线一致） */
    private static final int LOW_DENSITY_CHARS = 1500;
    /** 密度反馈回看窗口：只看最近 N 章，早期章节已不可改不回溯 */
    private static final int DENSITY_LOOKBACK = 5;
    /** 对白坍缩反馈回看窗口：与密度反馈同窗口，同样只看最近 N 章 */
    private static final int DIALOGUE_LOOKBACK = 5;

    /**
     * 组装规划 prompt（整批单段、沿用上下文默认蓝图与起始章号；包级可见供单测）
     */
    String buildPlanPrompt(DefaultArmoryFactory.DynamicContext dynamicContext) {
        int startNo = dynamicContext.getChapterOffset() + 1;
        return buildPlanPrompt(dynamicContext, startNo, null, dynamicContext.getStageBlueprint(), true, null, null);
    }

    /**
     * 组装规划 prompt：startNo 起、endNo 止（null=整批原文案），blueprint 为该段生效的阶段蓝图，
     * lastSegment 决定是否要求末章标 finale（仅批次末段有此要求）
     */
    public String buildPlanPrompt(DefaultArmoryFactory.DynamicContext dynamicContext, int startNo, Integer endNo,
                           StageBlueprintEntity blueprint, boolean lastSegment, StoryVO.Module module,
                           String worldId) {
        // 二期/三期/五期：相关性检索唤醒久远记忆（窗口外章节摘要 + 账本/设定 + 世界集合 bible）。
        // 失败语义：瞬时失败在服务内已有界重试，重试耗尽/确定性失败降级为空召回并 WARN 留痕
        //（RECALL_DEGRADED），不终止作业
        List<StoryMemoryService.RecallHit> recallHits = storyMemoryService.retrieve(
                module, dynamicContext.getStoryDir(),
                storyMemoryService.buildPlanQuery(dynamicContext.getStoryContext(), blueprint),
                recallMinChapterNo(dynamicContext.getChapterSummaries()),
                worldId);
        // 上章质量债与正文层同源注入：规划层必须看到审校确认的问题，
        // 才能在"编排那一刻"绕开已被标记的劣质模式
        // 防御性拷贝：档案块要追加进列表，不依赖 buildMemoryBlocks 返回可变集合
        List<PromptBudgetGuard.Block> memoryBlocks = new ArrayList<>(chapterMemoryService.buildMemoryBlocks(
                dynamicContext.getChapterSummaries(),
                dynamicContext.getPrevChapterTail(),
                dynamicContext.getPendingConflicts(),
                dynamicContext.getQualityDebts(),
                blueprint,
                // 卷方向锚：按段起点取所属卷（批次跨卷边界时不泄下一卷方向），卷 > 弧 > 章
                rollingOutlineService.volumeAt(dynamicContext.getVolumes(), startNo),
                recallHits,
                // 段计划一次性覆盖多章：无单章推进额度——时序锚按锁定值渲染（各章 timeAdvance 由正文层的章计划声明）
                null));
        // 新书首段（无摘要）：追加设定兜底年龄锚——否则首段计划在零年龄约束下生成，
        // 计划层会写下主角当前阶段做不到的事件（实测：幼龄主角"翻账本对数目"这类跨阶段行为）
        StoryContextEntity planStoryContext = dynamicContext.getStoryContextEntity();
        if (planStoryContext != null) {
            chapterMemoryService.prependSettingsAnchorIfNoSummaries(memoryBlocks,
                    dynamicContext.getChapterSummaries(),
                    planStoryContext.getWorldSetting(), planStoryContext.getProtagonist(),
                    planStoryContext.getOutline());
        }
        // 实体档案（E1）：规划路径（item=null）注入最久未见的休眠实体——
        // 规划层据此安排"谁重新出场"，避免配角蒸发（正文路径的按章提及过滤见 ChapterWorker）
        memoryBlocks.addAll(chapterMemoryService.buildEntityDossierBlocks(
                dynamicContext.getChapterSummaries(), null, dynamicContext.getConsistencyIndex()));

        StringBuilder sb = new StringBuilder();
        // 导演通道：作者创作要点置于 prompt 最顶部（不可裁剪，优先级高于一切规划约束）
        sb.append(cn.novel.yonren.domain.novel.service.armory.CreativeNotes
                .block(dynamicContext.getCreativeNotes()));
        // 输入段总量守门：故事设定 + 记忆前缀随连载增长，固定要求与 schema 段不裁——那是规划契约。
        // 记忆子块直入：超限时牺牲顺序由 priority 决定（尾部"上章结尾/质量债"类纠错内容不再最先被截）。
        // 故事设定取 5：沿用"前情记忆整体优先于设定段保留"的既有约定（记忆子块为 1~4，均高于它）。
        // 渲染顺序仍为"设定在前、记忆在后"（渲染按入参顺序，与淘汰次序解耦）
        List<PromptBudgetGuard.Block> planBlocks = new ArrayList<>();
        planBlocks.add(new PromptBudgetGuard.Block("故事设定", 5, true, dynamicContext.getStoryContext()));
        planBlocks.addAll(memoryBlocks);
        sb.append(promptBudgetGuard.assemblePlanInput(startNo, planBlocks));
        sb.append("\n\n请先规划完整章节计划，不要生成正文。")
                .append("\n要求：")
                .append("\n0. 【数量锚定·最高优先级，先于一切剧情】本章节数量是硬性契约：")
                .append("即使剧情在中间某章已被编排为'收束/收尾/本批末章'（如剧情设定里的'本批收束章'，但其编号远小于批次末章），")
                .append("也必须规划满全部章节——收束章之后的章节用于收束余韵、人物沉淀与下批引子，严禁中途截止输出。");
        if (endNo == null) {
            sb.append("\n1. 章节数量必须等于输入的章节数量。");
        } else {
            sb.append("\n1. 本段只规划第 ").append(startNo).append(" 章至第 ").append(endNo)
                    .append(" 章（共 ").append(endNo - startNo + 1)
                    .append(" 章），严禁超出此范围；且必须输出 ").append(endNo - startNo + 1)
                    .append(" 章一个不少，未排满即输出不完整，宁可把'收束章'提前也要把批次排满。");
        }
        sb.append("\n2. chapterNo 从 ").append(startNo).append(" 开始连续递增")
                .append("\n3. 每章只描述本章目标、关键事件和结尾悬念；endingHook 必须是具体的未决点/悬念/引爆引线，")
                .append("严禁用\"本章结束\"或总结性表述收尾，严禁无钩子结尾")
                .append(renderWorldLanguageRule(dynamicContext))
                .append("\n5. 若前情记忆带有【伏笔账·待回收】:标【硬】的条目，本章计划必须逐条显式评估——")
                .append("若本章场景/人物/地点与该伏笔相关，必须把回收列入该章关键事件并注明来源（如\"回收第N章埋设的XX\"）:")
                .append("若确实无关，在该章 goal 末尾用括号注明（第N章XX与本章场景无关：一句理由）:")
                .append("标【软】的条目，仅当某章关键事件自然涉及某条伏笔相关的人物/物品/地点/势力时,")
                .append("才把该伏笔的回收列入该章关键事件，并注明来源;")
                .append("无关伏笔严禁强行安排——宁可不回收也不注水，每章合计至多安排 1 条回收。")
                .append("\n6. 若前情记忆带有【阶段蓝图】:阶段任务仅是本阶段的方向语境（里程碑）,")
                .append("某章剧情自然推进某任务时，可体现在该章 goal/keyEvents 中;")
                .append("无需每章都推进任务，严禁为凑任务强行编造事件或提前透支后段高潮")
                .append("\n7. chapterType 标记章节类型：normal=普通推进章，transition=过渡章（蓄势/整理/关系推进），")
                .append("climax=高潮章（重大冲突/转折）,finale=全书最终收束章;")
                .append("transition 章的事件密度可低于常规，但它不是逃避推进的出口——")
                .append("全段 transition 占比 ≤ 1/3、不得连续超过 2 章，且每章至少包含一处关系/信息/资源的变化")
                .append("（纯赶路/纯寒暄不算）;")
                .append(lastSegment && blueprint != null && Boolean.TRUE.equals(blueprint.getFinalVolumeDeclared())
                        && ("RESOLUTION".equalsIgnoreCase(blueprint.getStoryPhase())
                        || "EPILOGUE".equalsIgnoreCase(blueprint.getStoryPhase()))
                        ? "只有收官卷的最后一章才标 finale；remainingFinaleBeats 必须在正文中全部完成"
                        : "本段末章不得因批次结束而标 finale；全书至少一个 climax")
                .append("\n8. 人物供给（防「主角单机」）：每章至少 1 个关键事件")
                .append("由**非主角角色主动发起或推动**——配角必须有自己的目标、信息或行动逻辑，")
                .append("不得只作为主角能力的验证器或递道具的；每章至少 1 次**双向角色互动**")
                .append("（对白/冲突/交易/协作，主角的内心推演与自我总结不算互动）；")
                .append("代价必须多样化——同一阶段内「物理损伤」类代价不得超过一半，必须穿插情感、关系、信息、机会等非物理代价；")
                .append("主角不得无代价地独立解决所有问题，每段至少安排一个必须依靠他人（被说服/被救助/被迫合作）才能推进的局面；")
                .append("**若为双主角或多主角题材，两个主角必须在章节/场景间交替主导**——")
                .append("不得让一方持续处于被动响应（被救助、被安慰、被怀疑）的位置；")
                .append("每 2-3 章至少有一章由「另一位主角」发起并推动核心事件。")
                .append("对白须写成**高频短交锋**（3-5 字一句、一来一回多轮），不要用少数长对白段凑篇幅——")
                .append("恋爱/智斗/掉马题材的张力来自往返次数，不是单段长度");
        if (ChapterPlanChecks.hasUsableLadder(blueprint)) {
            sb.append("\n8.1 ⚠️ suspenseBeat——**主线推进的机械校验字段**：每章必须回填")
                    .append("「本章结束时核心悬念所处的档位」的**原文**，逐字取自下方【悬念推进锚】的档位表。")
                    .append("规范：索引不得倒退；非 transition 章不得连续 3 章停在同一档；")
                    .append("**相邻两章的 suspenseBeat 不得逐字相同**——同一档位下也必须写出各章独有的推进"
                    + "（谁知道了什么 / 谁做了什么决定 / 什么被公开），写清楚区别所在；")
                    .append("档位表档数不足以让每章不同时，在档位原文**之后**附本章独有的推进子项，"
                    + "而不是整段复用同一句描述。")
                    .append("**严禁把'产生怀疑 → 自我否定 → 回到原点'当成推进**——那会让整段计划被机械驳回并重新规划。")
                    .append("整段必须真的往前走：宁可让悬念早一点挑明，也不要原地循环。");
        }
        // 8.2 章级主线推进：与 8.1 的两个维度——8.1 是纵向"走到第几格"，
        // 8.2 是横向"这一章主线做了什么"。档位只有 3-6 档覆盖整个阶段，多章共用同一档是常态，
        // 单靠档位无法区分相邻两章，故必须另给章级内容并要求逐字落地。
        if (blueprint != null && blueprint.getMainLineByChapter() != null
                && !blueprint.getMainLineByChapter().isEmpty()) {
            sb.append("\n8.2 ⚠️ mainLineAdvance——**章级主线推进**：每章必须回填本章在主线上的推进，")
                    .append("**逐字取自**下方【章级主线推进】块中对应章号的原文。")
                    .append("规范：不得改写、不得自行替换；相邻两章不得使用同一句描述——")
                    .append("缺章或雷同会被机械驳回并要求重新规划。")
                    .append("注意与 8.1 的分工：suspenseBeat 记「本章结束时的档位」（纵向），")
                    .append("mainLineAdvance 记「本章主线做了什么」（横向），两者都要填。");
        }
        sb.append("\n9. 不要输出 Markdown，不要输出解释文字");
        if (blueprint != null && blueprint.getEntryConstraints() != null && !blueprint.getEntryConstraints().isEmpty()) {
            // 阶段进入护栏：本阶段开始时应已成立的局面约束，规划必须先补齐过渡再推进后续剧情
            sb.append("\n\n【阶段进入护栏】本阶段开始时应已成立以下局面（事实性约束）:");
            for (String constraint : blueprint.getEntryConstraints()) {
                sb.append("\n- ").append(constraint);
            }
            sb.append("\n若当前前情尚未满足某条护栏,本段最前 1-2 章必须先安排过渡补齐,")
                    .append("严禁无视护栏直接跳进后续剧情。");
        }
        if (ChapterPlanChecks.hasUsableLadder(blueprint)) {
            sb.append("\n\n【悬念推进锚】本书核心悬念：")
                    .append(blueprint.getCoreSuspense() == null ? "（蓝图未给出）" : blueprint.getCoreSuspense());
            sb.append("\n该悬念的推进档位表（每章必须回填 suspenseBeat，**逐字取自下表**）：");
            List<String> ladder = blueprint.getSuspenseLadder();
            for (int i = 0; i < ladder.size(); i++) {
                sb.append("\n  ").append(i + 1).append(". ").append(ladder.get(i));
            }
            sb.append("\n规则：档位索引**不得倒退**；非 transition 章**不得连续 3 章停留在同一档**；")
                    .append("**相邻两章不得逐字复用同一句 suspenseBeat**（同档也要写出各章独有推进）——")
                    .append("违反会被机械驳回并要求重新规划。注意'产生怀疑又被自我否定圆回'**不算推进**。");
        }
        // 章级主线推进块：与档位锚并列。档位锚给"纵向的格子"，本块给"横向的动作"。
        // 只渲染本段用得到的那几章——蓝图可能覆盖 15 章而本段只有 5 章，多渲染纯占预算。
        if (endNo != null) {
            appendMainLineBlock(sb, blueprint, startNo, endNo);
        }
        // 卷末清账·限期回收：上一阶段出口裁决为"可自然兑现"的未填伏笔，规划必须安排回收路径
        //（以结算台账为准——裁决阶段末章 == 本段起点-1 才生效，首发/段中起点无结算不注入）
        appendSettlementRecovery(sb, dynamicContext.getForeshadowSettlements(), startNo);
        // 伏笔排期块：把"本段必须埋什么、必须收什么、哪些逾期了"摆到规划那一刻。
        // 这是 P2 唯一真正把"兑现时机"交给长视野的地方——没有它，段计划仍只能本段内自产自销。
        if (endNo != null) {
            // 与上一块去重：同一批条目可能同时挂在结算台账与排期表上（实测同一条线被两块各说一遍，
            // 措辞还不同）——规划层会收到两条"必须收"的命令却只有一条线。结算块带连接点信息、
            // 对规划更有用，故保留结算块那条，排期块让位。
            appendForeshadowScheduleBlock(sb, dynamicContext.getForeshadowSchedules(), startNo, endNo,
                    settlementListedIntents(dynamicContext.getForeshadowSettlements(), startNo));
        }
        // 密度反馈：前文有"料少字少"的章节时，责令本段计划提高事件密度（治本的反注水）
        appendDensityFeedback(sb, dynamicContext.getChapterSummaries());
        // 对白坍缩反馈：前文有"篇幅够、事件也在，但角色不开腔"的章节时，责令本段把冲突写进台词
        appendDialogueCollapseFeedback(sb, dynamicContext.getChapterSummaries());
        // 伏笔长度反馈：伏笔"埋了就收"是长篇读起来浅的直接原因；
        // 埋多长由计划决定，所以必须在规划层治，写手无权改
        appendForeshadowSpanFeedback(sb, dynamicContext.getChapterSummaries());
        // 地点轨迹：把"最近都在哪儿、哪些地点已建立"摆给规划层——观测不到就不会被修
        String placeBlock = PlaceTrajectoryPolicy.renderTrajectory(dynamicContext.getChapterSummaries());
        if (placeBlock != null) {
            sb.append(placeBlock);
        }
        // 已用章节标题：把清单摆到"起名那一刻"才是治本——检测只能事后记 MINOR 债
        String usedTitles = ChapterTitlePolicy.renderUsedTitles(dynamicContext.getChapterSummaries());
        if (usedTitles != null) {
            sb.append(usedTitles);
        }
        // 关系轨迹：把 RELATION 事实聚合的"当前关系态"摆到规划那一刻
        String relationBlock = RelationTrajectoryPolicy.renderTrajectory(dynamicContext.getChapterSummaries());
        if (relationBlock != null) {
            sb.append(relationBlock);
        }
        // 已用情节模式：重复的源头在规划层，上移到编排这一刻
        String usedPatterns = UsedPatternPolicy.render(
                dynamicContext.getChapterSummaries(), startNo, UsedPatternPolicy.PLAN_LOOKBACK);
        if (usedPatterns != null) {
            sb.append(usedPatterns);
        }
        if (dynamicContext.getChapterOffset() > 0) {
            sb.append("\n9. 这是续写批次;以上前情记忆即为已有剧情，新章必须无缝承接其人物状态与悬念,严禁另起炉灶或重讲已有剧情");
        }
        sb.append("\n\n【节奏指令】禁止每一章都安排生死危机，也禁止每一章都换新场景。")
                .append("两次危机之间必须安排至少 1-2 章的'过渡期'（如整理线索、暗中发育、角色日常交流），")
                .append("这些章请标 chapterType=transition。transition 与【密度反馈】并不冲突：")
                .append("它豁免'关键事件 ≥ 3'，但仍须落在已建立的地点里，并带来一处实质变化。");
        sb.append("\n\n【冲突编排指令】【禁止对称出场】多方势力交锋时，严禁让他们像开会一样同时到达并轮流发言")
                .append("必须制造信息差和时间差（例如：一方暗中潜伏，一方迟到，一方只派低阶试探），让冲突呈现非对称性和意外感");
        // 事件密度：keyEvents 是写手的供给清单——示例只给 2 条时，
        // 模型稳定交付 3 条/章（正文 1100-1400 字），供给不足直接变成正文注水或独白章。
        // 示例条数就是有效 schema（教训 #1），示例与要求同步提到 4 条基准。
        // 加 dialogueScenes：散文规则"至少 2 条对话承载"无人执法（第 105 章 0 条照样放行），
        // 声明 + 机械校验才是执法通道（见 ChapterPlanChecks.validateDialogueCarriage）
        sb.append("\n\n【事件密度】每章 keyEvents 3-5 条：normal 章以 **4 条**为基准（其中**至少 "
                        + ChapterPlanChecks.DIALOGUE_SCENES_MIN + " 条**由对话承载、逐字复制进 dialogueScenes，"
                        + "并写明谁对谁、围绕什么分歧），")
                .append("transition 章可 2-3 条；条数不足的章，写手只能靠注水或独白凑篇幅。")
                // 场面级要求：实测同一章重跑一次，字数掉 30%、对白掉 70%——
                // 计划供给（4 条事件、250 字）两轮逐字相同，差别只在"事件被写成一场戏还是被一句话交代"。
                // 计划的写法直接决定正文的写法：一句话的事件，正文里只会被展开成一句过渡叙述。
                .append("**每条 keyEvent 必须写到「场面」级**：在场人物 + 起因或分歧 + 落点或结果（如"
                        + "「周秀兰在灶房把日历翻到背面补第五道杠，陆远山站在门口问'又记上了'，两人就"
                        + "『孩子的事按孩子节奏来』达成一致」），**禁止**写成「谁做了什么」的一句话"
                        + "（如「周秀兰记录了日历」）——后者在正文里只能被展开成一句过渡叙述。")
                .append("每章必须声明 timeAdvance（本章结束时的时间，相对上一章推进 3-7 天；每段至少 1 章跳月），")
                .append("全段单调递增并与阶段末目标（stageEnd）一致——它是年龄/时间推进的唯一合法通道。");
        sb.append("\n\n").append(PLAN_SCHEMA_MARKER).append(":")
                .append("\n{\"chapters\":[{\"chapterNo\":").append(startNo)
                .append(",\"title\":\"章节标题\",\"goal\":\"本章目标\",\"characters\":[\"角色A\"],")
                .append("\"keyEvents\":[\"关键事件1\",\"关键事件2\",\"关键事件3\",\"关键事件4\"],")
                .append("\"dialogueScenes\":[\"对话承载事件①（逐字取自keyEvents）\",\"对话承载事件②（逐字取自keyEvents）\"],")
                .append("\"timeAdvance\":\"推进2周，至XXXX年10月下旬\",\"endingHook\":\"结尾悬念\",")
                .append("\"chapterType\":\"normal\",\"suspenseBeat\":\"档位表原文之一\"");
        if (blueprint != null && blueprint.getMainLineByChapter() != null
                && !blueprint.getMainLineByChapter().isEmpty()) {
            sb.append(",\"mainLineAdvance\":\"【章级主线推进】块中本章的原文\"");
        }
        sb.append("}]}");
        return sb.toString();
    }

    /** 按题材生成世界语言约束；只有玄幻题材才出现仙侠词表，其他题材保持中性。 */
    private String renderWorldLanguageRule(DefaultArmoryFactory.DynamicContext dynamicContext) {
        StoryContextEntity context = dynamicContext.getStoryContextEntity();
        GenreTypeVO genre = GenreTypeVO.match(context == null ? null : context.getTheme(),
                context == null ? null : context.getStyle());
        StringBuilder rule = new StringBuilder("\n4. 关键事件、目标、结尾悬念必须使用与故事题材、时代、人物身份一致的世界内语言，")
                .append("不得把作者分析语、学术报告腔或时代之外的技术黑话直接当作剧情事实；")
                .append("人物用词与判断必须符合其年龄、教育、职业和当前认知边界。");
        rule.append("若前情记忆含【时序锚】，主角能力展示必须区分两类：")
                .append("**认知超前**（理解、判断、偏好、策略性拖延、权衡取舍）是否允许超出现阶段，")
                .append("由故事设定决定——设定允许时不得抹平；")
                .append("**媒介超前**（肢体动作、语言能力、书写、专注时长、精细操作等表达载体）")
                .append("必须落在时序锚所记阶段的极限内；超媒介事件一律改写为该阶段内的合法表达")
                .append("（倾向、注视、趋避、单音节词、被协助完成——以阶段常识为准）。")
                .append("**注意**：用任何**可被他人解码出具体含义**的载体展示能力（涂鸦画出某题答案结构、")
                .append("摆物摆出警告、以特定握姿展示训练痕迹、叫出某物名字）——都是能力展示，")
                .append("不是认知超前，同禁；受限阶段的\"不寻常\"应通过偏好、注视、趋避与观察者的")
                .append("跨章累积来显形（推进单位是他人认知的变化），而不是能力本身的直接或间接展示。")
                .append("圣经章节带目标只表示剧情方向，不得据此让主角跨越尚未到达的生理阶段。")
                .append("**本规则同样约束【必须埋设】注入的排期项**：排期意图与本锚冲突时")
                .append("（如要求幼龄角色书写文字/数字、独立完成精细操作），改写为该阶段内的合法表达后照常埋设")
                .append("（保留意图指向与回收章），不得原样照搬——排期项不豁免时序锚。");
        // 能力展示节奏：锚把"注视/倾向"合法化后，模型立即收敛到
        // 成本最低的合规模板——连续多章都写成「主角微动作 → 旁人注意 → 不像孩子」。
        // 合规不等于可以每章来一次；展示的稀缺性本身就是效果的一部分。
        rule.append("\n【能力展示节奏·硬约束】本段 5 章中，**至多 2 章**可安排主角能力/早慧展示类关键事件；")
                .append("相邻两个展示章不得同类（动作暗示 / 旁人评价 / 专注偏好必须轮换或间隔空章）；")
                .append("**严禁**把「旁人注意到主角不寻常 / 不像孩子」写成每章的固定推进单位——")
                .append("没有展示任务的章，关键事件落在人际、事件、选择与两难上；")
                .append("早慧的显形走观察者跨章累积的认知变化（一条暗线多个观察者接力），")
                .append("而不是每章单发一个展示时刻。");
        // 对话承载规则：关键事件里若混入"内心梳理/制定计划"型独白事件，
        // 写手交付的对白句数会明显偏低——独白事件写不出互动。
        // 由"至少 1 个"提到"至少 2 个"：实测同一段章节、同一份计划重跑一次，
        // 对白句数从 139 掉到 97（平均从 28/章 掉到 19/章，历史 45 章稳在 31-57），
        // 而"至少 1 个"这条字面上仍然满足——一章只有一个对话事件时，
        // 其余三个关键事件都写成静态交代，全章就对白塌方。同时要求写清对谁、分歧是什么，
        // 否则计划里写"二人交谈"，写手只能产出寒暄。
        // 再补 dialogueScenes 声明 + 机械校验：散文规则只劝不罚——第 105 章 5 条关键事件
        // 0 条对话承载（characters 写着"电脑屏幕"）照样放行。阈值常量单点在 ChapterPlanChecks，
        // 此处引用拼接，防两侧口径漂移（与 hasUsableLadder 同套路）
        rule.append("\n【对话承载·硬约束】每章关键事件**至少 ").append(ChapterPlanChecks.DIALOGUE_SCENES_MIN)
                .append(" 个**由人物当面的对话/冲突/协作承载（transition 章至少 ")
                .append(ChapterPlanChecks.DIALOGUE_SCENES_MIN_TRANSITION).append(" 个），")
                .append("且每条这样的关键事件必须写明**谁对谁、围绕什么分歧**（人物写全名，代词不算）——只写「二人交谈」等于没写；")
                .append("把这 ≥").append(ChapterPlanChecks.DIALOGUE_SCENES_MIN)
                .append(" 条事件**逐字复制**进该章 dialogueScenes 字段——校验器会逐条比对：")
                .append("对不上 keyEvents、事件里不足两个人物全名、或单人事件超过一半，整段计划会被驳回重规划；")
                .append("**禁止**整章关键事件全部由\"内心梳理/制定计划/复盘总结/能力自评\"类独白事件构成——")
                .append("独白事件不产生互动，写手只能交付独白章；")
                .append("相邻两章不得连续独白主导；主角需要\"想明白一件事\"时，")
                .append("优先让这件事在对峙/合作/教学的**对话中**发生，内心独白只作决策落点不作事件本体。");
        // 时间衔接规则：计划排"夜里睡熟"而锚/账本仍停在白天时，
        // 写手照排夜景 → 审计按锚判事实矛盾，补丁无法修复（改时段=重构整章）成质量债。
        // 与年龄判据同源：锚是时间/年龄的唯一依据，计划层排事件必须先衔接时段。
        rule.append("\n【时间衔接·硬约束】关键事件必须从上方【时序锚】记载的当前时间自然延展：")
                .append("排夜间/睡眠/入夜场景而锚尚在白天时，须先安排过夜或明确的时间过渡事件（并在 goal 中注明推进到何时），")
                .append("或把该事件顺延到后续章节；")
                .append("严禁在无过渡的情况下让事件时段与锚记载直接冲突——审计将按时序锚判事实矛盾记 BLOCKING。");
        if (genre == GenreTypeVO.FANTASY) {
            rule.append("玄幻/仙侠题材可使用灵气、阵纹、符箓、灵脉、宗门、长老、法宝等既有设定词，")
                    .append("不得用握手信号、加密信号、波形数据、服务器、协议、分布式节点、灰度、缓存、日志等程序员术语替代世界内概念；")
                    .append("若主角设定允许，此类术语只可作为短促的心理类比出现在正文，不进入计划事件名词。");
        }
        return rule.toString();
    }

    static void appendDensityFeedback(StringBuilder sb, List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        List<ChapterSummaryEntity> recent = summaries.subList(
                Math.max(0, summaries.size() - DENSITY_LOOKBACK), summaries.size());
        List<String> lowDensity = recent.stream()
                .filter(s -> s != null && s.getValidChars() != null && s.getValidChars() < LOW_DENSITY_CHARS)
                .filter(s -> !ChapterTypeVO.of(s.getChapterType()).isTransition())
                .map(s -> "第" + s.getChapterNo() + "章（实际 " + s.getValidChars() + " 有效字 / "
                        + (s.getKeyEventCount() == null ? "?" : s.getKeyEventCount()) + " 个关键事件）")
                .toList();
        if (!lowDensity.isEmpty()) {
            sb.append("\n\n【密度反馈】以下章节剧情供给不足，实际产出偏薄：")
                    .append(String.join("、", lowDensity))
                    .append("。本段计划必须提高事件密度：常规章（normal）每章关键事件 ≥ 3 个，")
                    .append("climax/finale（高潮/收束）章 ≥ 4 个——这两类章信息密度最高，写薄了等于白给；")
                    .append("且每个事件必须带来冲突、信息或关系的变化；")
                    .append("若确需留白，请把该章标为 chapterType=transition（密度要求对它豁免），")
                    .append("但严禁借 transition 之名安排纯赶路/纯寒暄的无变化流程章——")
                    .append("transition 章仍须至少有一处实质变化。正文长度由事件数量自然撑起，不靠描写注水。");
        }

        // 对称的另一侧：篇幅超标。判定用参考上沿，且**不豁免过渡章**（理由见方法注释）
        List<String> bloated = recent.stream()
                .filter(s -> s != null && s.getValidChars() != null
                        && s.getValidChars() > ChapterLengthPolicy.MAXIMUM_REFERENCE_CHARACTERS)
                .map(s -> "第" + s.getChapterNo() + "章（" + s.getValidChars() + " 有效字"
                        + (s.getChapterType() == null ? "" : " / " + s.getChapterType())
                        + "，仅 " + (s.getKeyEventCount() == null ? "?" : s.getKeyEventCount()) + " 个关键事件）")
                .toList();
        if (!bloated.isEmpty()) {
            sb.append("\n\n【注水反馈】以下章节篇幅显著超出参考区间，而关键事件数并未相应增加：")
                    .append(String.join("、", bloated))
                    .append("。多出来的不是料，是被拉长的描写与对白。本段计划必须按此收紧篇幅：")
                    .append("常规章有效字数宜落在 1400–2600，climax/finale 宜 ≥ 1600（同样靠增加事件达成）；")
                    .append("推进感靠**增加事件**而不是拉长单场戏；")
                    .append("过渡章尤其不得因为没事发生就用环境描写与寒暄把篇幅填满——过渡章应当更短。")
                    .append("同一场景内若只剩反复确认已知状态的对话，请合并或直接跳到下一个事件。");
        }
    }

    /**
     * **对白坍缩反馈**：扫描最近 {@value #DIALOGUE_LOOKBACK} 章的轮次密度，发现
     * "篇幅够、关键事件也在，但角色不开腔"的章节时注入指令。
     *
     * <p><b>为什么必须在规划层治</b>：对白塌了的根因往往不在写手——计划把关键事件写成
     * "谁做了什么"的动作清单时，写手只能交付动作分解。实测第 96 章：4 条关键事件里有 2 条是
     * 对话承载，**数量合规**，但篇幅最大的那条是纯独白推导，规则只管条数、不管篇幅占比，
     * 于是全章有效对话 9 句、轮次密度 4.4/千字（劣化线 6.0，同批其它章 12-27）。
     *
     * <p>与 {@link #appendDensityFeedback}（篇幅）、{@link #appendForeshadowSpanFeedback}（伏笔跨度）
     * 完全同构：机械观测 → 回灌规划 → 治本。过渡章不计入：过渡章本就允许留白，
     * 计入会逼规划层给留白也塞对话。
     */
    static void appendDialogueCollapseFeedback(StringBuilder sb, List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        String genre = summaries.stream()
                .filter(java.util.Objects::nonNull)
                .map(ChapterSummaryEntity::getStoryGenre)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        double line = DialogueRatioPolicy.densityDegraded(genre);
        List<ChapterSummaryEntity> recent = summaries.subList(
                Math.max(0, summaries.size() - DIALOGUE_LOOKBACK), summaries.size());
        List<String> collapsed = recent.stream()
                .filter(s -> s != null && s.getDialogueUtterances() != null && s.getValidChars() != null
                        && s.getValidChars() > 0)
                .filter(s -> !ChapterTypeVO.of(s.getChapterType()).isTransition())
                .filter(s -> DialogueRatioPolicy.utteranceDensity(
                        s.getDialogueUtterances(), s.getValidChars()) < line)
                .map(s -> "第" + s.getChapterNo() + "章（" + String.format("%.1f",
                        DialogueRatioPolicy.utteranceDensity(s.getDialogueUtterances(), s.getValidChars()))
                        + " 次/千字）")
                .toList();
        if (collapsed.isEmpty()) {
            return;
        }
        sb.append("\n\n【对白坍缩反馈】以下章节篇幅不短、关键事件也落地了，但**角色不开腔**：")
                .append(String.join("、", collapsed))
                .append("（健康线 ≥ ").append(String.format("%.1f", line)).append(" 次/千字）。")
                .append("通篇动作分解与内心推演，读者读到的是一份流程说明，不是一场戏。")
                .append("\n本段计划必须按此修正：")
                .append("\n1. **对话承载事件提到 ≥2 条**——不是「至少 1 条」占位就够，"
                        + "而是让冲突与分歧在台词里发生；")
                .append("\n2. 每条对话承载事件必须写清**谁对谁、围绕什么分歧**——"
                        + "只写「二人交谈」等于没写，写手只能产出寒暄，"
                        + "并把这几条事件**逐字复制**进该章 dialogueScenes 字段；")
                .append("\n3. 人物只在动作里出场、不用台词表达意图的事件，改写为一次具体的交锋或谈判。");
    }

    static void appendForeshadowSpanFeedback(StringBuilder sb, List<ChapterSummaryEntity> summaries) {
        if (!ForeshadowSpanPolicy.shouldAdvise(summaries)) {
            return;
        }
        // 与 shouldAdvise 同口径（feedbackSpans）：否则会出现"触发了反馈却举不出例子"
        // 或"举例数与触发数对不上"的矛盾
        List<ForeshadowSpanPolicy.Span> all = ForeshadowSpanPolicy.feedbackSpans(summaries);
        int shortCount = ForeshadowSpanPolicy.shortest(summaries, Integer.MAX_VALUE).size();
        int total = all.size();
        double avg = all.stream().mapToInt(ForeshadowSpanPolicy.Span::chapters).average().orElse(0.0);
        String avgText = String.format("%.1f", avg);
        List<String> samples = ForeshadowSpanPolicy.shortest(summaries, 3).stream()
                .map(s -> "第" + s.plantChapterNo() + "章埋 → 第" + s.resolveChapterNo() + "章收（"
                        + s.chapters() + " 章）")
                .toList();

        sb.append("\n\n【伏笔长度反馈】已回收的 ").append(total).append(" 条伏笔中，")
                .append(shortCount).append(" 条在 ").append(ForeshadowSpanPolicy.SHORT_SPAN_CHAPTERS)
                .append(" 章内就兑现（平均跨度仅 ").append(avgText).append(" 章）：")
                .append(String.join("；", samples)).append("。")
                .append("\n**这不叫伏笔，叫待办事项**——埋下去立刻收，读者来不及惦记，悬念就没有重量。")
                .append("\n本段计划必须拉开跨度，具体做法：")
                .append("\n1. **分层**：每段至少安排 1 条**长线**伏笔（跨度 ≥")
                .append(ForeshadowSpanPolicy.SHORT_SPAN_CHAPTERS * 3)
                .append(" 章，本段只做铺垫与强化，明确不兑现），与短平快的支线包袱并存；")
                .append("\n2. **禁止约定式伏笔**：不要把兑现日写进埋设章的计划里（如'后天带合同登门'）。")
                .append("改为只埋**信息缺口**（一句反常的话、一个未解释的物件），让读者自己去猜，")
                .append("兑现时机由后续段落的规划决定，而不是埋设时就定死；")
                .append("\n3. **延迟满足**：若某条伏笔本可立即兑现，优先让它**先发酵一到两章**——")
                .append("中途安排一次'接近真相但被打断'的推进，再兑现。");
    }

    static void appendMainLineBlock(StringBuilder sb, StageBlueprintEntity blueprint,
                                    int startNo, int endNo) {
        if (blueprint == null || blueprint.getMainLineByChapter() == null
                || blueprint.getMainLineByChapter().isEmpty() || endNo < startNo) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (StageBlueprintEntity.MainLineBeat beat : blueprint.getMainLineByChapter()) {
            if (beat == null || beat.getChapterNo() == null || StringUtils.isBlank(beat.getAdvance())) {
                continue;
            }
            if (beat.getChapterNo() < startNo || beat.getChapterNo() > endNo) {
                continue;
            }
            lines.add("  第" + beat.getChapterNo() + "章：" + beat.getAdvance());
        }
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n\n【章级主线推进】（第 ").append(startNo).append("-").append(endNo)
                .append(" 章——每章必须回填 mainLineAdvance，**逐字取自下表**）：");
        for (String line : lines) {
            sb.append("\n").append(line);
        }
        sb.append("\n规则：**逐字照抄**，不得改写或替换；相邻两章不得雷同——")
                .append("违反会被机械驳回并要求重新规划。");
    }

    /** 排期块注入条数上限（与限期回收同款：超出部分留在排期表，后续段各自注入，避免吃掉前缀预算） */
    private static final int SCHEDULE_INJECTION_LIMIT = 8;

    /**
     * 逾期挂起阈值（章）：逾期 ≥ 该值仍未收回的线**停止自动补收**，改入「逾期挂起·待人工裁定」。
     *
     * <p><b>标定依据</b>（实测逾期分布正好两簇）：仍在推进的条目逾期 11-22 章，
     * 真正卡死的几条逾期 48-74 章——30 落在两簇之间。
     *
     * <p>为什么必须有个停手点：卡死的线跨多个排期块被反复结转、每段都带着
     * 「本段必须补收，不要再往后拖」，规划层于是每段都把它们安排一遍——这就是
     * "伏笔重复拖沓"的机制。而它们关不掉的原因多半是**已经写了、账本没认出来**，
     * 继续下令不会让它变得更真，只会让同一件事被重写。
     */
    static final int OVERDUE_SUSPEND_CHAPTERS = 30;

    /** 挂起块注入条数上限：每次只摆 3 条，避免挂起清单自己膨胀成新的噪声源 */
    private static final int SUSPEND_INJECTION_LIMIT = 3;

    /** 「长线在养」注入条数上限：只给条目名，仍然限量——它不该成为新的前缀负担 */
    private static final int PARKED_INJECTION_LIMIT = 5;

    /**
     * **伏笔排期块**：五块——必须埋 / 必须兑现 / **逾期补收** / **逾期挂起·待人工裁定** /
     * **长线在养·本段不主动安排**。
     *
     * <p><b>「本段不得兑现」这半句是把"不要埋了就收"的抽象要求
     * 落成了可执行的单条约束</b>——否则段计划看到"本段要埋 X"的第一反应就是顺手把它收掉。
     *
     * <p><b>为什么必须有"逾期补收"块</b>：只注入"payoff 落在本段内"的条目时，
     * 逾期未收的线会彻底消失——它既不在本段要收的窗口里，又没人提醒它欠着账，
     * 而阶段可长达 30-80 章。故逾期项**每段持续注入**，直到收掉或被清账裁决。
     *
     * <p>无排期表时整块不出现（老故事/未启用），与引入前行为完全一致。
     *
     * <p><b>注入前必须去重</b>：同一条线在多个阶段块里各存一份，各桶上限又按条数截断，
     * 不去重就会把名额全花在少数几条上、并让规划层反复安排同样的事。见 {@link #dedupeByIntent}。
     */
    static void appendForeshadowScheduleBlock(StringBuilder sb,
                                              List<ForeshadowScheduleEntity> schedules,
                                              int startNo, int endNo) {
        appendForeshadowScheduleBlock(sb, schedules, startNo, endNo, Set.of());
    }

    /**
     * 注入排期块，并跳过已在其它块列出过的条目（{@code alreadyListedIntents} 存归一化文本）。
     *
     * <p>去重与"谁让位"的取舍见 {@link #dedupeByIntent} 与方法注释。
     */
    static void appendForeshadowScheduleBlock(StringBuilder sb,
                                              List<ForeshadowScheduleEntity> schedules,
                                              int startNo, int endNo,
                                              Set<String> alreadyListedIntents) {
        List<ForeshadowScheduleEntity.ScheduleItem> items = schedules == null || schedules.isEmpty()
                ? List.of()
                : schedules.stream()
                        .filter(java.util.Objects::nonNull)
                        .flatMap(sc -> ForeshadowScheduleEntity.itemsOf(sc).stream())
                        .filter(java.util.Objects::nonNull)
                        .toList();
        items = dedupeByIntent(items);
        if (items.isEmpty()) {
            return;
        }

        List<String> mustPlant = new ArrayList<>();
        List<String> mustPay = new ArrayList<>();
        List<String> overdue = new ArrayList<>();
        List<String> suspended = new ArrayList<>();
        List<String> parked = new ArrayList<>();
        // MISSED 过滤：已判"到期未埋"的线不再注入【必须埋设】——判死条目重复注入
        // 会让规划层反复安排同一条线（实测会连续多段 MISSED）
        // 已列过滤：同一条线在【卷末清账·限期回收】里已说过就不再重复下令
        Set<String> alreadyListed = alreadyListedIntents == null ? Set.of() : alreadyListedIntents;
        List<ForeshadowScheduleEntity.ScheduleItem> live = items.stream()
                .filter(item -> item != null && !ForeshadowScheduleEntity.STATUS_MISSED.equals(item.getStatus()))
                .filter(item -> !alreadyListed.contains(ForeshadowSpanPolicy.normalize(item.getIntent())))
                .toList();
        for (ForeshadowScheduleEntity.ScheduleItem item : live) {
            if (item.getPlantChapter() == null || item.getPayoffChapter() == null
                    || StringUtils.isBlank(item.getIntent())) {
                continue;
            }
            if (item.parked()) {
                // 长线在养（阶段出口裁定，见 ForeshadowSettlementEntity.DECISION_PARKED）：
                // 不再要求回收，但必须**明说这是有意为之**——否则模型会"好心"自己把它安排上，
                // 而实测那正是伏笔被反复重写的通道
                parked.add("  - 《" + brief(item.getIntent()) + "》");
                continue;
            }
            if (ForeshadowScheduleEntity.STATUS_PLANNED.equals(item.getStatus())
                    && item.getPlantChapter() >= startNo && item.getPlantChapter() <= endNo) {
                mustPlant.add("  - 《" + brief(item.getIntent()) + "》（计划第" + item.getPlantChapter()
                        + "章埋 → 第" + item.getPayoffChapter() + "章收）：本段第 " + item.getPlantChapter()
                        + " 章必须埋下，**本段不得兑现**");
            } else if (ForeshadowScheduleEntity.STATUS_PLANTED.equals(item.getStatus())) {
                if (item.getPayoffChapter() >= startNo && item.getPayoffChapter() <= endNo) {
                    mustPay.add("  - 《" + brief(item.getIntent()) + "》（第" + item.getActualPlantChapter()
                            + "章埋 → 计划第" + item.getPayoffChapter() + "章收）：本段第 "
                            + item.getPayoffChapter() + " 章必须安排回收");
                } else if (item.getPayoffChapter() < startNo) {
                    int overdueBy = startNo - item.getPayoffChapter();
                    if (overdueBy >= OVERDUE_SUSPEND_CHAPTERS) {
                        // 逾期过久：继续每段下令实测毫无效果（同一条线跨多个排期块被反复结转，
                        // 规划层只好反复安排同一件事），停止自动补收，改交人工裁定
                        suspended.add("  - 《" + brief(item.getIntent()) + "》（原计划第"
                                + item.getPayoffChapter() + "章收，**已逾期 " + overdueBy + " 章**）");
                    } else {
                        overdue.add("  - 《" + brief(item.getIntent()) + "》（原计划第" + item.getPayoffChapter()
                                + "章收，**已逾期**）：本段必须补收，不要再往后拖");
                    }
                }
            }
        }
        if (mustPlant.isEmpty() && mustPay.isEmpty() && overdue.isEmpty()
                && suspended.isEmpty() && parked.isEmpty()) {
            return;
        }

        sb.append("\n\n【伏笔兑现排期】以下安排来自阶段蓝图的长视野排期，必须落地：");
        if (!mustPlant.isEmpty()) {
            sb.append("\n■ 本段内**必须埋设**（埋下后本段不得兑现）：");
            mustPlant.stream().limit(SCHEDULE_INJECTION_LIMIT).forEach(sb::append);
            sb.append("\n  ⚠️ 这几条是**跨段长线**——埋下就收等于白排期，读者来不及惦记。");
        }
        if (!mustPay.isEmpty()) {
            sb.append("\n■ 本段内**必须兑现**：");
            mustPay.stream().limit(SCHEDULE_INJECTION_LIMIT).forEach(sb::append);
        }
        if (!overdue.isEmpty()) {
            sb.append("\n■ **逾期补收**（计划回收章已过，属欠账而非闲笔）：");
            overdue.stream().limit(SCHEDULE_INJECTION_LIMIT).forEach(sb::append);
        }
        if (!suspended.isEmpty()) {
            // 停手块：逾期过久的线不再自动下令。继续下令的实测后果是规划层每段都把它安排一遍，
            // 而它之所以关不掉，多半是**已经写了、账本没认出来**
            // ——回收匹配只比对摘要短句与伏笔的抽象措辞。故出口有两个：本段显式声明已完成，
            // 或留待阶段出口裁决判 DONE（见 ForeshadowSettlementEntity.DECISION_DONE）
            sb.append("\n■ **逾期挂起·待人工裁定**（已逾期 ≥ ").append(OVERDUE_SUSPEND_CHAPTERS)
                    .append(" 章仍没收，**本段不再自动安排**——继续每段下令只会让同一件事被反复重写）：");
            suspended.stream().limit(SUSPEND_INJECTION_LIMIT).forEach(sb::append);
            sb.append("\n  处理方式（二选一）：")
                    .append("\n  ① 若本段剧情**确已自然完成**该线，在该段末章 goal 末尾注明"
                            + "「已完成：第N章已兑现」并说清兑现的是什么——阶段出口会据此关账；")
                    .append("\n  ② 否则保持不动，交人工复盘裁定兑现或弃置——"
                            + "**严禁为清账强行编排无关剧情**。");
            if (suspended.size() > SUSPEND_INJECTION_LIMIT) {
                sb.append("\n  （另有 ").append(suspended.size() - SUSPEND_INJECTION_LIMIT)
                        .append(" 条同样挂起，未在此展开）");
            }
        }
        if (!parked.isEmpty()) {
            // 在养块：唯一的作用是**明确说"这条线我们是有意不动的"**。不说的话，规划层会把它
            // 当成"被忘了的线"自发安排回收——实测老长线就是这样被反复重写的。
            sb.append("\n■ **长线在养·本段不主动安排**（以下几条是设计上就要长跑的线，"
                    + "已由阶段出口裁定不再催收——**不要**为清账给它们安排回收，除非剧情自然触及）：");
            parked.stream().limit(PARKED_INJECTION_LIMIT).forEach(sb::append);
            if (parked.size() > PARKED_INJECTION_LIMIT) {
                sb.append("\n  （另有 ").append(parked.size() - PARKED_INJECTION_LIMIT)
                        .append(" 条同样在养，未在此展开）");
            }
        }
        sb.append("\n排期是长视野决策，单段无权改写——确需调整请在计划里说明原因，不要静默忽略"
                + "（「逾期挂起」与「长线在养」那几条除外：前者已交人工裁定、后者是刻意不动的，"
                + "都不要自行安排）。");
    }

    /**
     * 按归一化 intent 去重，**保留最后一条**（同键时位置取首次出现处，结果确定）。
     *
     * <p><b>为什么必须去重</b>：排期表按阶段分块落盘，同一条线会在
     * "历史块 + 各阶段结转块"里各存一份，而各桶的注入上限是**按条数**截断的。
     * 实测一批 8 条逾期命令里 5 条逐字是同一条线——8 个名额被花光，规划层于是被反复
     * 命令去收同几样东西，这正是"伏笔重复拖沓"的直接来源。
     *
     * <p>保留最后一条与 {@code ForeshadowScheduleService} 的跨版本口径一致（那边同样是"保留最后一个"），
     * 两处若不一致，同一份数据在打标与注入上会给出相反结论。
     */
    static List<ForeshadowScheduleEntity.ScheduleItem> dedupeByIntent(
            List<ForeshadowScheduleEntity.ScheduleItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ForeshadowScheduleEntity.ScheduleItem> byIntent = new LinkedHashMap<>();
        for (ForeshadowScheduleEntity.ScheduleItem item : items) {
            String key = ForeshadowSpanPolicy.normalize(item.getIntent());
            if (key.isEmpty()) {
                // 无 intent 的条目本就在分桶时被跳过（无法渲染），此处一并丢弃以免互相覆盖
                continue;
            }
            byIntent.put(key, item);
        }
        return new ArrayList<>(byIntent.values());
    }

    /**
     * 【卷末清账·限期回收】块实际会列出的伏笔（归一化文本），供排期块去重。
     *
     * <p>口径取"精确相等"而非模糊匹配：结算台账的 {@code content} 是伏笔登记原文，
     * 排期项的 {@code intent} 是蓝图意图——两者在实测中逐字相同，但**不保证**总是相同。
     * 不相等时退化为旧行为（两块各列一遍），不会漏注入；反过来用模糊匹配则可能把
     * 本段真正该收的线误吞掉，代价不对等。
     */
    static Set<String> settlementListedIntents(List<ForeshadowSettlementEntity> settlements, int startNo) {
        return ForeshadowSettlementEntity.recoverTargets(settlements, startNo).stream()
                .filter(java.util.Objects::nonNull)
                .map(ForeshadowSettlementEntity.SettlementDecision::getContent)
                .filter(StringUtils::isNotBlank)
                .map(ForeshadowSpanPolicy::normalize)
                .collect(Collectors.toSet());
    }

    /** 注入用短文本（intent 可能较长） */
    private static String brief(String text) {
        return text == null ? "" : (text.length() <= 24 ? text : text.substring(0, 24) + "…");
    }

    /**
     * 卷末清账·限期回收块：上一阶段出口清账裁决为 RECOVER 的未填伏笔，注入本段规划 prompt。
     * 整段规划与惰性分段规划共用此入口——结算文件是唯一裁决依据，蓝图自评不参与。
     * 注入按滞留最久（埋设章号最小）优先、封顶 SETTLEMENT_INJECTION_LIMIT 条：
     * 超出部分留在池里自然发酵，下次阶段出口重新裁决（RECOVER 不出账，循环自愈）
     */
    static void appendSettlementRecovery(StringBuilder sb, List<ForeshadowSettlementEntity> settlements, int startNo) {
        List<ForeshadowSettlementEntity.SettlementDecision> targets =
                ForeshadowSettlementEntity.recoverTargets(settlements, startNo);
        if (targets.isEmpty()) {
            return;
        }
        List<ForeshadowSettlementEntity.SettlementDecision> sorted = new ArrayList<>(targets);
        sorted.sort(Comparator.comparingInt(ForeshadowSettlementEntity.SettlementDecision::getChapterNo));
        List<ForeshadowSettlementEntity.SettlementDecision> injected =
                sorted.subList(0, Math.min(SETTLEMENT_INJECTION_LIMIT, sorted.size()));

        sb.append("\n\n【卷末清账·限期回收】以下伏笔已经上一阶段（结束于第 ").append(startNo - 1)
                .append(" 章）出口清账裁决为可自然兑现，本段规划必须为其安排回收：");
        for (ForeshadowSettlementEntity.SettlementDecision decision : injected) {
            sb.append("\n- ").append(decision.getContent())
                    .append("（第").append(decision.getChapterNo()).append("章埋设");
            if (StringUtils.isNotBlank(decision.getReason())) {
                sb.append("；连接点：").append(decision.getReason());
            }
            sb.append("）");
        }
        if (sorted.size() > injected.size()) {
            sb.append("\n- 另有 ").append(sorted.size() - injected.size())
                    .append(" 条经裁决可自然兑现的未填伏笔本段暂不安排，留待后续阶段出口继续裁决");
        }
        sb.append("\n把上述伏笔的回收列入本段最前 1/3 章的关键事件，并注明来源（如\"回收第N章埋设的XX\"）；")
                .append("严禁为清账强行编排无关剧情——个别确实无法自然融入的，在该段末章 goal 末尾注明")
                .append("（无法自然回收：一句理由），由后续阶段继续承接。");
    }

    /** 故事记忆检索的下界：近 N 章窗口内章节已在摘要节直给，检索仅唤醒更早章节（账本/设定点不受限） */
    private static Integer recallMinChapterNo(List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        int latest = summaries.get(summaries.size() - 1).getChapterNo();
        return latest - ChapterMemoryService.RECENT_SUMMARY_COUNT + 1;
    }
}
