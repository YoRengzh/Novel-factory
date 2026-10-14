package cn.novel.yonren.domain.novel.service.armory.revise;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.RevisionDecisionEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService;
import cn.novel.yonren.domain.novel.service.armory.quality.PatchIntegrityPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.PlanAdherencePolicy;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.alibaba.fastjson2.JSON;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 章节修订服务：针对 BLOCKING 问题改写整章，并通过纯代码采纳闸门决定是否保留修订稿。
 * 直连 LlmGateway，输出整章 JSON；temperature/maxTokens 由 scene-models 的 revise 场景配置驱动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterReviseService {

    private static final BeanOutputConverter<ChapterContentEntity> CONVERTER =
            new BeanOutputConverter<>(ChapterContentEntity.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    /**
     * 定向补丁输出 schema。关闭"未知字段即失败"：补丁 JSON 极小，
     * 模型常顺手带上 explanation/notes 之类的自认为有用的键，为一个多余键丢掉整份补丁不划算
     */
    private static final BeanOutputConverter<PatchPlan> PATCH_CONVERTER =
            new BeanOutputConverter<>(PatchPlan.class,
                    JsonMapper.builder()
                            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .build());

    private static final double MIN_LENGTH_RATIO = 0.8;

    /**
     * 修订轮允许丢失的关键事件条数上限（2026-10-04）：BLOCKING 违规现场常与关键事件场景重叠
     * （时序锚类违规尤其如此——"幼年写下未来年份"本身就是排期项），修复必然改写该场景。
     * 允许丢 1 条换取硬伤修复；丢 ≥2 条说明修订在跑偏，仍然拒绝。
     */
    private static final int REVISE_ALLOWED_EVENT_LOSS = 1;

    /** 整章重写形态的任务头 */
    private static final String TASK_HEADER_REWRITE =
            "你是小说流水线修订员。请根据下方的 BLOCKING 问题，对原稿进行最小化修订。"
                    + "\n修订原则："
                    + "\n1. 只修 issue 指向的问题；"
                    + "\n2. 未被 issue 覆盖的段落保持原样；"
                    + "\n3. 输出与正文同 schema 的整章 JSON。";


    private static final String TASK_HEADER_PATCH =
            "你是小说流水线修订员。请先判断下面的 BLOCKING 问题能否用**最小范围的定向补丁**修掉。"
                    + "\n\n【定向补丁是什么】你只给出「原文锚点 → 替换文本」，程序会把原稿中的该锚点替换掉。"
                    + "补丁之外的正文一个字都不会被改动——所以你不需要、也无法通过补丁做整章性调整。"
                    + "\n\n【输出规则】"
                    + "\n1. anchor 必须是**原稿中逐字存在、且只出现一次**的片段，必须原样复制（一个标点都不能改）；"
                    + "\n2. replacement 是替换后的文本；置为空字符串表示删除该片段；"
                    + "\n3. 一处问题一个补丁，补丁之间不得重叠；"
                    + "\n4. 只有当问题**确实无法用局部改动收敛**（例如需要重排整章结构、事件顺序整体错乱）时，"
                    + "才输出 {\"patches\":[]} 表示放弃。"
                    + "\n\n【补丁的典型形态（示例）】"
                    + "\n问题：账本记录某物落进了石缝，本章开场却直接拿在手里，缺少取回的过渡描写"
                    + "\n正确做法：anchor = 该物首次出现的那一句原文；replacement = 同一句 + 补上的过渡描写"
                    + "\n错误做法：在 replacement 里重写整段甚至整章——那已经不是定向补丁了。";

    /** 套话式微动作黑名单：单章每条至多 1 次；跨章累计超频由风格账疲劳词兜底（同源词表） */
    private static final List<String> MICRO_ACTION_WATCHLIST = List.of(
            "喉结滚动", "喉结滑动", "声音压低", "压低声音", "呼吸一滞", "呼吸一顿",
            "瞳孔骤缩", "瞳孔微缩", "身体一僵", "身形一滞");
    /** 微动作单章限额：超过即视为高频，修订时点名替换 */
    private static final int MICRO_ACTION_CHAPTER_LIMIT = 1;

    private final LlmGateway llmGateway;
    private final StyleStatService styleStatService;
    private final StoryProperties storyProperties;

    /**
     * 针对 BLOCKING 问题发起修订，返回修订决策与修订稿。
     *
     * @param storyVO        模型配置
     * @param item           本章计划
     * @param originalContent 原稿全文
     * @param blockingIssues 待修复的 BLOCKING 问题
     * @param ledgerPrompt   三账本渲染文本（可为 null）
     * @param foreshadowing  伏笔账文本（可为 null）
     * @param styleStat      当前风格统计（用于闸门与疲劳词禁新增名单，可为 null）
     * @param globalNo       全局章节号（仅用于日志）
     * @param previousRejectionReason 上一轮修订未采纳的原因（多轮修订时回传，首轮为 null）
     * @return 修订结果（含决策与修订稿；未采纳时修订稿为 null）
     */
    public ReviseResult revise(StoryVO storyVO,
                               ChapterPlanItemEntity item,
                               String originalContent,
                               List<ChapterIssueEntity> blockingIssues,
                               String ledgerPrompt,
                               String foreshadowing,
                               StyleStatEntity styleStat,
                               int globalNo,
                               String previousRejectionReason) {
        if (blockingIssues == null || blockingIssues.isEmpty()) {
            return new ReviseResult(
                    RevisionDecisionEntity.builder()
                            .attempted(false)
                            .accepted(false)
                            .reason("无 BLOCKING 问题，无需修订")
                            .build(),
                    null);
        }

        try {
            // 定向补丁优先：BLOCKING 若能靠"改一句"收敛，就不该付整章重写的代价——
            // 整章重写会把**所有未被问题命中的段落**也一并重出，文风回退风险与修复范围完全不成比例。
            // 未采用不是异常（模型判定不是局部问题 / 锚点不唯一 / 补丁不过闸门），一律安静回退到整章重写
            if (patchEnabled()) {
                PatchOutcome patch = tryPatch(storyVO, item, originalContent, blockingIssues,
                        ledgerPrompt, foreshadowing, styleStat, globalNo, previousRejectionReason);
                if (patch.applied()) {
                    log.info("第 {} 章定向补丁已采纳（只改局部，其余正文逐字保留）", globalNo);
                    return new ReviseResult(
                            RevisionDecisionEntity.builder()
                                    .attempted(true)
                                    .accepted(true)
                                    .reason("定向补丁通过采纳闸门")
                                    .build(),
                            patchedEntity(patch.content()));
                }
                log.info("第 {} 章定向补丁未采用（{}），回退整章重写", globalNo, patch.failureReason());
            }

            // maxTokens/temperature 不再硬编码，由 scene-models 的 revise 场景配置驱动
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .userPrompt(buildPrompt(item, originalContent, blockingIssues, ledgerPrompt,
                            foreshadowing, styleStat, globalNo, previousRejectionReason))
                    .label("revise-第" + globalNo + "章")
                    .scene(ModelScene.CHAPTER_REVISE)
                    .build());

            ChapterContentEntity revised = tryConvert(raw);
            if (revised != null) {
                // 模型输出标题常自带"第N章"前缀，与章节号重复展示，入口归一
                revised.setTitle(cn.novel.yonren.types.utils.ChapterTitleNormalizer.normalize(revised.getTitle()));
            }
            if (revised == null) {
                revised = JsonParseFallback.parse(raw, this::tryConvert);
                if (revised != null) {
                    log.info("章节修订经四级解析降级修复后解析成功，chapterNo: {}", globalNo);
                }
            }

            if (revised == null || StringUtils.isBlank(revised.getContent())) {
                return new ReviseResult(buildRejected("修订稿无法解析或 content 为空"), null);
            }

            String gateReason = runGates(originalContent, revised, item, styleStat, globalNo);
            if (gateReason != null) {
                log.warn("章节修订未通过采纳闸门，chapterNo: {}，原因：{}", globalNo, gateReason);
                return new ReviseResult(buildRejected(gateReason), null);
            }

            log.info("章节修订已采纳，chapterNo: {}", globalNo);
            return new ReviseResult(
                    RevisionDecisionEntity.builder()
                            .attempted(true)
                            .accepted(true)
                            .reason("修订稿通过采纳闸门")
                            .build(),
                    revised);
        } catch (Exception e) {
            log.warn("章节修订异常，chapterNo: {}", globalNo, e);
            return new ReviseResult(buildRejected("修订异常：" + e.getMessage()), null);
        }
    }

    // ==================== 定向补丁 ====================

    private boolean patchEnabled() {
        return storyProperties != null
                && storyProperties.getRevise() != null
                && storyProperties.getRevise().isPatchEnabled();
    }

    /**
     * 尝试用定向补丁修复：模型只产出「锚点 → 替换文本」，本方法机械套用后再走**同一套**采纳闸门。
     *
     * <p>任何一步不成立都返回"未采用"而非抛异常——回退整章重写是设计内的正常路径，不是错误：
     * 模型判定"不是局部问题"、锚点被改写过、补丁反而让文风变差，都是合理结果。
     */
    private PatchOutcome tryPatch(StoryVO storyVO,
                                  ChapterPlanItemEntity item,
                                  String originalContent,
                                  List<ChapterIssueEntity> blockingIssues,
                                  String ledgerPrompt,
                                  String foreshadowing,
                                  StyleStatEntity styleStat,
                                  int globalNo,
                                  String previousRejectionReason) {
        String raw;
        try {
            raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .userPrompt(buildPrompt(item, originalContent, blockingIssues, ledgerPrompt,
                            foreshadowing, styleStat, globalNo, previousRejectionReason, true))
                    .label("patch-第" + globalNo + "章")
                    .scene(ModelScene.CHAPTER_REVISE)
                    .build());
        } catch (Exception e) {
            return new PatchOutcome(null, "补丁调用异常：" + e.getMessage());
        }

        PatchPlan plan = JsonParseFallback.parse(raw, PATCH_CONVERTER::convert);
        if (plan == null || plan.getPatches() == null || plan.getPatches().isEmpty()) {
            // 模型主动放弃（空补丁）或输出根本无法解析——两者都说明"这问题不是局部改动能收敛的"
            return new PatchOutcome(null, "模型未给出可用补丁");
        }

        PatchOutcome outcome = applyPatches(originalContent, plan.getPatches());
        if (!outcome.applied()) {
            return outcome;
        }
        if (outcome.content().equals(originalContent)) {
            // 套用成功但等于空操作。若放过，就会把"什么也没修"当成"修完了"，还白烧一次复审
            return new PatchOutcome(null, "补丁未改变任何内容");
        }

        String gateReason = runGates(originalContent, patchedEntity(outcome.content()), item, styleStat, globalNo);
        if (gateReason != null) {
            return new PatchOutcome(null, "补丁未过采纳闸门：" + gateReason);
        }
        return new PatchOutcome(outcome.content(), null);
    }

    public static PatchOutcome applyPatches(String original, List<Patch> patches) {
        List<int[]> spans = new ArrayList<>(patches.size());
        List<String> replacements = new ArrayList<>(patches.size());
        for (Patch patch : patches) {
            String anchor = patch == null ? null : patch.getAnchor();
            if (StringUtils.isBlank(anchor)) {
                return new PatchOutcome(null, "补丁锚点为空");
            }
            int start = original.indexOf(anchor);
            if (start < 0) {
                return new PatchOutcome(null, "锚点在原稿中不存在（模型未逐字复制原文）");
            }
            if (original.indexOf(anchor, start + 1) >= 0) {
                return new PatchOutcome(null, "锚点在原稿中出现多次，无法确定该改哪一处");
            }
            spans.add(new int[]{start, start + anchor.length()});
            replacements.add(patch.getReplacement() == null ? "" : patch.getReplacement());
        }

        // 按出现位置升序套用；锚点唯一故起点不会相同，只需防重叠
        List<Integer> order = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> spans.get(i)[0]));

        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (int idx : order) {
            int[] span = spans.get(idx);
            if (span[0] < cursor) {
                return new PatchOutcome(null, "补丁锚点互相重叠，无法安全套用");
            }
            sb.append(original, cursor, span[0]).append(replacements.get(idx));
            cursor = span[1];
        }
        sb.append(original.substring(cursor));
        return new PatchOutcome(sb.toString(), null);
    }

    /** 补丁路径的返回体：**标题留空**表示"标题未变"，由 QualityGate 保留原标题（补丁不该动标题） */
    private static ChapterContentEntity patchedEntity(String content) {
        ChapterContentEntity entity = new ChapterContentEntity();
        entity.setContent(content);
        return entity;
    }

    /** 定向补丁输出：模型只需给出若干「原文锚点 → 替换文本」 */
    @Data
    public static class PatchPlan {
        private List<Patch> patches;
    }

    @Data
    public static class Patch {
        /** 原稿中逐字存在且唯一的锚点片段 */
        private String anchor;
        /** 替换文本；空字符串表示删除该锚点 */
        private String replacement;
    }

    /**
     * 补丁套用结果：content 非空 = 成功；否则 failureReason 说明为何回退（仅用于日志）。
     * 包外可见以便段落信息增量审校复用（见 {@link #applyPatches}）。
     */
    public record PatchOutcome(String content, String failureReason) {
        public boolean applied() {
            return content != null;
        }
    }

    private ChapterContentEntity tryConvert(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildPrompt(ChapterPlanItemEntity item,
                               String originalContent,
                               List<ChapterIssueEntity> blockingIssues,
                               String ledgerPrompt,
                               String foreshadowing,
                               StyleStatEntity styleStat,
                               int globalNo,
                               String previousRejectionReason) {
        return buildPrompt(item, originalContent, blockingIssues, ledgerPrompt, foreshadowing,
                styleStat, globalNo, previousRejectionReason, false);
    }

    /**
     * 组装修订 prompt：两种形态只差**任务头**与**输出 schema**，中段的业务上下文完全共用
     * （章节计划、关键事件红线、账本、伏笔、待修问题、机械违规点），避免两处各写一份而漂移
     *
     * @param patchMode true = 定向补丁形态；false = 整章重写形态
     */
    private String buildPrompt(ChapterPlanItemEntity item,
                               String originalContent,
                               List<ChapterIssueEntity> blockingIssues,
                               String ledgerPrompt,
                               String foreshadowing,
                               StyleStatEntity styleStat,
                               int globalNo,
                               String previousRejectionReason,
                               boolean patchMode) {
        StringBuilder sb = new StringBuilder();
        sb.append(patchMode ? TASK_HEADER_PATCH : TASK_HEADER_REWRITE)
                // 一致性裁决规则：治"账本 vs 正文"类债的修订横跳——改写方向唯一（以账本为准）
                .append("\n【一致性裁决规则】对\"与账本/前文事实矛盾\"类问题（位置、持有物、修为、人物状态）：")
                .append("一律以账本为准改写正文，使正文向账本对齐；位置/持有物变化必须补写过渡描写；")
                .append("严禁为迁就剧情走向而改写或无视账本事实。")
                .append(buildMicroActionBlacklist(originalContent));
        // 上一轮反馈：采纳闸门的具体拒绝原因（如疲劳词超频/缺关键事件）回传给次轮修订，避免盲改
        if (StringUtils.isNotBlank(previousRejectionReason)) {
            sb.append("\n【上一轮修订反馈】上一轮修订稿因以下原因被采纳闸门拒绝，本次必须避免引入同样问题：\n")
                    .append(previousRejectionReason);
        }
        // 疲劳词禁新增名单：与采纳闸门同源词表 + 跨章累计逼近阈值点名，让模型看见红线
        if (styleStat != null) {
            sb.append("\n").append(styleStatService.renderReviseFatigueBlacklist(styleStat));
        }
        sb.append("\n\n【本章计划】")
                .append("\n标题：").append(nullToBlank(item.getTitle()))
                .append("\n目标：").append(nullToBlank(item.getGoal()))
                .append("\n关键事件：").append(joinList(item.getKeyEvents()))
                .append("\n结尾悬念：").append(nullToBlank(item.getEndingHook()))
                .append("\n章节类型：").append(item.getChapterType() == null ? "normal" : item.getChapterType().getCode());
        // 关键事件红线：修订必须保留计划骨架，禁止为修文风/修矛盾而删事件（采纳闸门事后拦截 + 此处事前约束，双保险）
        if (item.getKeyEvents() != null && !item.getKeyEvents().isEmpty()) {
            sb.append("\n\n【关键事件红线】关键事件是本章剧情骨架，修订稿必须完整保留每一条关键事件在正文中的落点：")
                    .append("\n1. 严禁删除、合并或把关键事件降级为一句背景概述；")
                    .append("\n2. 若问题恰好发生在关键事件内部（如事件动作与账本矛盾），只能在事件内部修正具体描写，事件本身必须照常发生；")
                    .append("\n3. 采纳闸门会逐条核对关键事件是否仍被正文覆盖，缺失任何一条即拒稿。");
        }

        if (StringUtils.isNotBlank(ledgerPrompt)) {
            sb.append("\n\n【当前账本】\n").append(ledgerPrompt);
        }
        if (StringUtils.isNotBlank(foreshadowing)) {
            sb.append("\n\n【待回收伏笔】\n").append(foreshadowing);
        }

        sb.append("\n\n【待修复的 BLOCKING 问题】\n");
        int idx = 1;
        for (ChapterIssueEntity issue : blockingIssues) {
            sb.append(idx++).append(". [").append(issue.getDimension()).append("] ")
                    .append(issue.getDescription())
                    .append("\n   evidence：").append(nullToBlank(issue.getEvidence()))
                    .append("\n   suggestion：").append(nullToBlank(issue.getSuggestion()))
                    .append("\n");
        }
        // 机械扫描命中的文风违规点：evidence 是逐字定位清单（"词条×次数"），必须逐处改写，不接受"整体润色"
        List<String> mechanicalEvidence = blockingIssues.stream()
                .filter(i -> "aesthetic".equalsIgnoreCase(i.getDimension()))
                .map(ChapterIssueEntity::getEvidence)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (!mechanicalEvidence.isEmpty()) {
            sb.append("\n【机械扫描确认的文风违规点】以下命中位置由程序逐字扫描确认（非模型判断），必须逐处改写、不得遗漏：\n");
            for (String evidence : mechanicalEvidence) {
                sb.append("- ").append(evidence).append("\n");
            }
            sb.append("改写口径：删除或替换每一个命中词，不得仅调整语序或在原词前后加修饰；未命中段落保持原样。");
        }

        sb.append("\n\n【原稿全文】\n").append(originalContent);
        if (patchMode) {
            sb.append("\n\n请严格按照以下 JSON 格式输出补丁（只输出这一个 JSON，不要附带解释文字）：")
                    .append("\n{\"patches\":[{\"anchor\":\"原稿中逐字存在且唯一的片段\",\"replacement\":\"替换后的文本\"}]}")
                    .append("\n若要放弃补丁，输出 {\"patches\":[]}。")
                    .append("\n再次提醒：anchor 会被程序逐字索引，改一个字就套不上，整份补丁会被直接丢弃。");
        } else {
            sb.append("\n\n请严格按照以下 JSON 格式输出整章：")
                    .append("\n{\"chapterNo\":").append(globalNo)
                    .append(",\"title\":\"章节标题\",\"content\":\"章节正文\"}");
        }

        return sb.toString();
    }

    private String runGates(String originalContent,
                            ChapterContentEntity revised,
                            ChapterPlanItemEntity item,
                            StyleStatEntity styleStat,
                            int globalNo) {
        // 1. 字数闸（硬）：修订稿腰斩 = 内容缺失，不是风格问题
        if (revised.getContent().length() < originalContent.length() * MIN_LENGTH_RATIO) {
            return "修订稿字数不足原稿 " + (int) (MIN_LENGTH_RATIO * 100) + "%";
        }

        // 2. 风格账：只告知、不拒绝——BLOCKING 修复优先于文风瑕疵
        if (styleStat != null) {
            StyleStatEntity temp = deepCopy(styleStat);
            if (temp == null) {
                log.warn("第 {} 章修订风格账深拷贝失败，跳过风格告知（不再因此拒稿）", globalNo);
            } else {
                styleStatService.merge(temp, revised.getContent());
                if (temp.getRepeatedSentences().size() > styleStat.getRepeatedSentences().size()) {
                    log.warn("第 {} 章修订稿引入新的跨章重复句，已放行（BLOCKING 修复优先；风格由后续章节警示治理）",
                            globalNo);
                }
                for (Map.Entry<String, Integer> entry : temp.getFatigueWords().entrySet()) {
                    Integer before = styleStat.getFatigueWords().get(entry.getKey());
                    if (entry.getValue() >= StyleStatService.FATIGUE_THRESHOLD
                            && (before == null || before < StyleStatService.FATIGUE_THRESHOLD)) {
                        log.warn("第 {} 章修订稿使疲劳词 '{}' 达到超频阈值，已放行（同上）",
                                globalNo, entry.getKey());
                    }
                }
            }
        }
        List<String> keyEvents = item.getKeyEvents();
        if (keyEvents != null && !keyEvents.isEmpty()) {
            // 逐条判定（含"原稿未覆盖的不强求"这条放行规则）统一走 PatchIntegrityPolicy，
            // 与段落密度挤水路径共用同一份实现——两处各写一份正是漂移的来源
            List<String> missing = PatchIntegrityPolicy.lostEvents(originalContent, revised.getContent(), keyEvents);
            if (missing.size() > REVISE_ALLOWED_EVENT_LOSS) {
                return "修订稿缺失关键事件：" + String.join("；", missing);
            }
            if (!missing.isEmpty()) {
                log.info("第 {} 章修订丢失 {} 条关键事件，放行（BLOCKING 修复优先，修订验证与下章规划兜底）：{}",
                        globalNo, missing.size(), StringUtils.abbreviate(missing.get(0), 60));
            }
        }

        return null;
    }

    /**
     * 微动作黑名单节：逐条统计原稿中套话式微动作的出现次数，超频条目点名并责令替换。
     * 修订遵循最小化原则，因此只要求替换超频处，其余段落不动
     */
    private String buildMicroActionBlacklist(String originalContent) {
        StringBuilder sb = new StringBuilder("\n\n【微动作黑名单】套话式微动作（如喉结滚动、呼吸一滞、瞳孔骤缩）可偶尔使用但严禁高频，单章每条至多 1 次：");
        String normalized = StringUtils.trimToEmpty(originalContent);
        List<String> overused = new ArrayList<>();
        for (String phrase : MICRO_ACTION_WATCHLIST) {
            int count = countOccurrences(normalized, phrase);
            if (count > MICRO_ACTION_CHAPTER_LIMIT) {
                overused.add(phrase + "×" + count);
            }
        }
        if (overused.isEmpty()) {
            sb.append("原稿未超频，修订时同样不得新增此类套话");
        } else {
            sb.append("原稿超频：").append(String.join("、", overused))
                    .append("——将超频处替换为更具体的环境描写、心理活动或新颖比喻，其余段落保持原样");
        }
        return sb.toString();
    }

    private int countOccurrences(String text, String phrase) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(phrase, idx)) >= 0) {
            count++;
            idx += phrase.length();
        }
        return count;
    }

    private StyleStatEntity deepCopy(StyleStatEntity stat) {
        try {
            return JSON.parseObject(JSON.toJSONString(stat), StyleStatEntity.class);
        } catch (Exception e) {
            return null;
        }
    }

    private RevisionDecisionEntity buildRejected(String reason) {
        return RevisionDecisionEntity.builder()
                .attempted(true)
                .accepted(false)
                .reason(reason)
                .build();
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

    /**
     * 修订结果容器：同时携带决策与修订稿，未采纳时修订稿为 null。
     */
    public record ReviseResult(RevisionDecisionEntity decision, ChapterContentEntity revisedContent) {
    }

}
