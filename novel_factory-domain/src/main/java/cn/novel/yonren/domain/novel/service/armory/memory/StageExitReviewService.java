package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.quality.EvidenceMatch;
import cn.novel.yonren.domain.novel.service.armory.quality.ExitConditionPolicy;
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


@Slf4j
@Service
@RequiredArgsConstructor
public class StageExitReviewService {

    private static final BeanOutputConverter<ExitReviewOutput> CONVERTER = new BeanOutputConverter<>(
            ExitReviewOutput.class,
            JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    private static final String REVIEW_SYSTEM_PROMPT = """
            你是小说流水线的质量审计员，负责对照章节记忆核验阶段退出条件是否达成。
            你是外部审计，不是创作者：只依据给定的章节记忆判定，不脑补剧情外的可能性。
            判定宁严勿松：存疑一律视为未达成。""";

    /** 证据原文引用上限字数（与 prompt 约束一致，超长截断保住子串有效性） */
    private static final int REVIEW_EVIDENCE_MAX_LENGTH = 80;

    private final LlmGateway llmGateway;

    public List<StageBlueprintEntity.ExitConditionResult> review(StoryVO.Module module,
                                                                 StageBlueprintEntity stage,
                                                                 List<ChapterSummaryEntity> summaries,
                                                                 Map<String, String> promptSink) {
        if (module == null || stage == null || stage.getExitConditions() == null || stage.getExitConditions().isEmpty()) {
            return null;
        }
        List<ChapterSummaryEntity> stageSummaries = stageSummaries(stage, summaries);
        if (stageSummaries.isEmpty()) {
            log.warn("阶段退出条件核验跳过：阶段区间内无章节摘要，stage: {}", stage.getStageNo());
            return null;
        }
        AtomPlan plan = planAtoms(stage.getExitConditions());

        // 可核验文本 = 剧情摘要 + 三账本状态串（模型引用与机械校验使用同一文本源）
        Map<Integer, String> textByChapter = new HashMap<>();
        for (ChapterSummaryEntity summary : stageSummaries) {
            textByChapter.put(summary.getChapterNo(), chapterMemoryText(summary));
        }

        try {
            String userPrompt = buildUserPrompt(stage, stageSummaries, plan);
            if (promptSink != null) {
                promptSink.put("system", REVIEW_SYSTEM_PROMPT);
                promptSink.put("user", userPrompt);
            }
            // maxTokens/temperature 不再硬编码，由 scene-models 的 exit-review 场景配置驱动
            String raw = llmGateway.complete(module, LlmCall.builder()
                    .systemPrompt(REVIEW_SYSTEM_PROMPT)
                    .userPrompt(userPrompt)
                    .label("exit-review-第" + stage.getStageNo() + "阶段")
                    .scene(ModelScene.STAGE_EXIT_REVIEW)
                    .build());

            ExitReviewOutput output = JsonParseFallback.parse(raw, this::readOutput);
            if (output == null || output.getResults() == null
                    || output.getResults().size() != plan.atoms.size()) {
                log.warn("阶段退出条件核验输出无法解析或条数不齐（期望原子数 {}），回退模型自评结转",
                        plan.atoms.size());
                return null;
            }
            List<StageBlueprintEntity.ExitConditionResult> results =
                    alignAndValidate(stage.getExitConditions(), plan, output.getResults(), textByChapter);
            long metCount = results.stream().filter(r -> Boolean.TRUE.equals(r.getMet())).count();
            log.info("阶段退出条件核验完成：第{}阶段，条件达成 {}/{}（原子 {} 个）",
                    stage.getStageNo(), metCount, results.size(), plan.atoms.size());
            return results;
        } catch (Exception e) {
            log.warn("阶段退出条件核验异常，回退模型自评结转（fail-soft），stage: {}", stage.getStageNo(), e);
            return null;
        }
    }

    /** 原子展开表：按条件顺序把各条件的原子子句拼成一张扁平表，并记录每个条件占用的下标区间 */
    private AtomPlan planAtoms(List<String> conditions) {
        AtomPlan plan = new AtomPlan();
        for (String condition : conditions) {
            List<String> atoms = ExitConditionPolicy.splitAtoms(condition);
            if (atoms.isEmpty()) {
                // 拆不出原子的（空条件）也要占一个槽位，否则下标聚合会错位
                atoms = List.of(StringUtils.defaultString(condition));
            }
            int start = plan.atoms.size();
            plan.atoms.addAll(atoms);
            plan.ranges.add(new int[]{start, plan.atoms.size() - 1});
        }
        return plan;
    }

    /** 阶段区间内的摘要，按章号排序 */
    private List<ChapterSummaryEntity> stageSummaries(StageBlueprintEntity stage, List<ChapterSummaryEntity> summaries) {
        if (summaries == null) {
            return List.of();
        }
        return summaries.stream()
                .filter(s -> s.getChapterNo() != null && s.getChapterNo() >= stage.getStartChapter()
                        && s.getChapterNo() <= stage.getEndChapter())
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo))
                .toList();
    }

    /**
     * 单章可核验文本：剧情摘要 + 三账本状态串 + **可核验细节**。
     * 实现已收编到 {@link ChapterMemoryText}（此前与 {@code FinaleReviewService} 各持一份逐字相同的副本）。
     */
    private String chapterMemoryText(ChapterSummaryEntity summary) {
        return ChapterMemoryText.of(summary);
    }

    private String buildUserPrompt(StageBlueprintEntity stage, List<ChapterSummaryEntity> stageSummaries,
                                   AtomPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("对照以下章节记忆，逐条核验上一阶段（第 ")
                .append(stage.getStartChapter()).append("-").append(stage.getEndChapter())
                .append(" 章，阶段目标：").append(StringUtils.defaultString(stage.getStageGoal()))
                .append("）的退出条件是否达成。")
                .append("\n规则：")
                .append("\n1. results 数组与下方【待核验条目】逐条一一对应，逐条输出，条数必须等于 ")
                .append(plan.atoms.size()).append("；")
                .append("\n2. met=true 必须给出证据：chapterNo 为该事实发生/确立的章节号，")
                .append("evidence 为**该章记忆文本内的一段连续原文**（≤80 字，逐字摘录，严禁改写；引号与标点允许与原文不同）；")
                .append("\n3. 证据必须能在**你所填章号那一章**的记忆文本中逐字找到，找不到视为未达成；")
                .append("\n4. 若支撑事实分散在多章：**只引用最主要的那一章的连续原文**，其余证据所在章节写进 note")
                .append("（note 不参与机械校验，仅作人工复核线索）；")
                .append("**严禁把不同章节、或同章内相距较远的句子用省略号拼成一条 evidence**——")
                .append("拼接出来的引文无法在任何单章内定位，会与「编造引用」一样被判为未达成；")
                .append("\n5. 判定存疑时一律 met=false，宁严勿松；")
                .append("\n6. 复合条件已拆成多个原子分句，请**逐分句独立判定**，不要把整条条件一起判——")
                .append("某分句已落地就照实判 met=true 并给出该分句的证据，系统会自行聚合（全部原子达成才算整条达成）；")
                .append("\n7. 不要输出 Markdown，不要输出解释；")
                .append("\n8. 示例仅为 JSON 格式示范，其内容与条件无关，不代表真实判定。")
                .append("\n\n【待核验条目】（共 ").append(plan.atoms.size()).append(" 条）");
        int no = 0;
        for (int ci = 0; ci < stage.getExitConditions().size(); ci++) {
            String condition = stage.getExitConditions().get(ci);
            int[] range = plan.ranges.get(ci);
            int total = range[1] - range[0] + 1;
            for (int i = range[0]; i <= range[1]; i++) {
                no++;
                sb.append("\n").append(no).append(". ").append(plan.atoms.get(i));
                if (total > 1) {
                    sb.append("　←　任务「").append(condition).append("」的第 ")
                            .append(i - range[0] + 1).append("/").append(total).append(" 个分句");
                }
            }
        }
        sb.append("\n\n【章节记忆（第 ").append(stage.getStartChapter()).append("-")
                .append(stage.getEndChapter()).append(" 章）】\n");
        for (ChapterSummaryEntity summary : stageSummaries) {
            sb.append("- 第").append(summary.getChapterNo()).append("章《").append(StringUtils.defaultString(summary.getTitle()))
                    .append("》：").append(chapterMemoryText(summary)).append("\n");
        }
        // 示例数据（确保模型照数据输出，不复读格式模板）
        sb.append("\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat());
        sb.append("\n\n【输出示例】\n")
                .append("{\"results\":[")
                .append("{\"chapterNo\":3,\"condition\":\"示例条件一\",\"evidence\":\"陆沉在阵眼处逐步推演阵纹走向\",\"met\":true,\"note\":null},")
                .append("{\"chapterNo\":10,\"condition\":\"示例条件二\",\"evidence\":null,\"met\":false,\"note\":\"对应章节记忆中没有支撑该结论的原文\"}")
                .append("]}");
        return sb.toString();
    }


    private void appendStates(StringBuilder sb, List<ChapterSummaryEntity.StateEntry> states) {
        if (states == null) {
            return;
        }
        for (ChapterSummaryEntity.StateEntry state : states) {
            if (state != null && StringUtils.isNotBlank(state.getName())) {
                sb.append("（").append(state.getName()).append("：").append(StringUtils.defaultString(state.getStatus())).append("）");
            }
        }
    }

    /**
     * 对齐与机械校验（**原子粒度**）：先逐原子校验证据（条件原文机械回填；证据章节号越界/
     * 该章摘要缺失/引用非该章记忆原文一律判未达成并注记，宁严勿松），
     * 再把原子结果**聚合回整条条件**——全部原子达成才算整条达成，并记录 metAtoms/totalAtoms
     * 与"还差哪些分句"的注记。
     */
    private List<StageBlueprintEntity.ExitConditionResult> alignAndValidate(
            List<String> conditions, AtomPlan plan,
            List<StageBlueprintEntity.ExitConditionResult> modelResults,
            Map<Integer, String> textByChapter) {

        int atomCount = plan.atoms.size();
        boolean[] atomMet = new boolean[atomCount];
        Integer[] atomChapter = new Integer[atomCount];
        String[] atomEvidence = new String[atomCount];
        String[] atomNote = new String[atomCount];

        // 校验用文本预先归一化一次（引号统一为半角双引号 + 去空白）；
        // ⚠️ 只用于校验——喂给模型的 prompt 仍是原文（见 buildUserPrompt），模型必须看到真实形态。
        Map<Integer, String> normalizedByChapter = new HashMap<>();
        for (Map.Entry<Integer, String> entry : textByChapter.entrySet()) {
            normalizedByChapter.put(entry.getKey(), EvidenceMatch.normalize(entry.getValue()));
        }

        for (int i = 0; i < atomCount; i++) {
            StageBlueprintEntity.ExitConditionResult model = modelResults.get(i);
            boolean met = model != null && Boolean.TRUE.equals(model.getMet());
            Integer chapterNo = model == null ? null : model.getChapterNo();
            String evidence = model == null ? null : model.getEvidence();
            String note = model == null ? "模型未输出该条判定" : model.getNote();
            if (met) {
                if (chapterNo == null || !textByChapter.containsKey(chapterNo)) {
                    met = false;
                    note = "证据校验失败：章节号越界或该章摘要缺失";
                } else {
                    // 截断到 prompt 约束的字数上限，保住"前缀仍是原文子串"的有效性
                    evidence = EvidenceMatch.truncate(StringUtils.trimToEmpty(evidence),
                            REVIEW_EVIDENCE_MAX_LENGTH);
                    // 统一走 EvidenceMatch 收编，此前是裸 indexOf）：
                    // 归一化后整串包含，或按省略号分段后**各段全命中**。
                    // 治的是"模型引文与原文只差标点/空白/引号形态"，以及"同章内相距较远的两段被拼成一条"；
                    // 且**不放宽"证据必须能定位"**——跨章拼接的引文仍会被逐段校验挡住。
                    if (evidence.isEmpty()
                            || !EvidenceMatch.contained(evidence, normalizedByChapter.get(chapterNo))) {
                        met = false;
                        evidence = null;
                        note = "证据校验失败：引用非该章记忆原文";
                    }
                }
            }
            if (!met && StringUtils.isBlank(note)) {
                note = "未达成";
            }
            atomMet[i] = met;
            atomChapter[i] = chapterNo;
            atomEvidence[i] = met ? evidence : null;
            atomNote[i] = note;
        }

        List<StageBlueprintEntity.ExitConditionResult> results = new ArrayList<>();
        for (int ci = 0; ci < conditions.size(); ci++) {
            int[] range = plan.ranges.get(ci);
            int total = range[1] - range[0] + 1;
            int metCount = 0;
            Integer chapterNo = null;
            String evidence = null;
            String firstFailureNote = null;
            List<String> unmetAtoms = new ArrayList<>();
            for (int i = range[0]; i <= range[1]; i++) {
                if (atomMet[i]) {
                    metCount++;
                    if (chapterNo == null) {
                        chapterNo = atomChapter[i];
                        evidence = atomEvidence[i];
                    }
                } else {
                    unmetAtoms.add(plan.atoms.get(i));
                    if (firstFailureNote == null) {
                        firstFailureNote = atomNote[i];
                    }
                }
            }
            StageBlueprintEntity.ExitConditionResult result = new StageBlueprintEntity.ExitConditionResult();
            result.setCondition(conditions.get(ci));
            result.setMetAtoms(metCount);
            result.setTotalAtoms(total);
            result.setMet(metCount == total);
            if (metCount == total) {
                result.setChapterNo(chapterNo);
                result.setEvidence(evidence);
                result.setNote(null);
            } else {
                // 未达成的整条不保留引用（证据不完整即不足以支撑整条结论），只保留缺口注记
                result.setChapterNo(null);
                result.setEvidence(null);
                String gapNote = total == 1
                        ? firstFailureNote
                        : "复合条件 " + total + " 个分句中有 " + unmetAtoms.size() + " 个未达成："
                                + ExitConditionPolicy.renderAtoms(unmetAtoms)
                                + "（原因：" + firstFailureNote + "）";
                result.setNote(withMultiPointHint(conditions.get(ci), gapNote));
            }
            results.add(result);
        }
        return results;
    }

    /**
     * 未达成注记补一句「结构性提示」：若该条件属多点取证形态
     * （对比/并列、二选一、跨章演化），其证据天然分散在多章，而核验只接受「单章一段连续原文」
     * ——失败与写作质量无关，属条件的**写法**问题。
     *
     * <p>⚠️ <b>只对"引文校验失败"追加</b>（即模型给了 {@code met=true} 却被机械拒收）。
     * 模型**自评 false** 时说明它确实没在给定文本里找到，那是内容或摘要粒度的问题，
     * 打上"结构缺口"标签属于**误报**（实测：某条"通关已达成、仅未确认长期组队"的部分达成条件，
     * 被错标成"二选一型需多点取证"）。
     *
     * <p>判据是**判定路径之外**的归因辅助，不影响任何达成结论。
     */
    private String withMultiPointHint(String condition, String note) {
        if (note == null || !note.contains("证据校验失败")) {
            return note;
        }
        String form = ExitConditionPolicy.multiPointForm(condition);
        if (form == null) {
            return note;
        }
        return note + "【疑似结构性缺口：" + form + "条件需多点取证，而核验只接受单章内一段连续原文；"
                + "建议下一阶段把它拆成若干单要素条件】";
    }

    /** 原子展开表：atoms 为按条件顺序拼接的扁平原子表，ranges[i] 为条件 i 占用的下标区间（闭区间） */
    private static final class AtomPlan {
        private final List<String> atoms = new ArrayList<>();
        private final List<int[]> ranges = new ArrayList<>();
    }

    private static final String RECHECK_SYSTEM_PROMPT = """
            你是小说流水线的质量审计员，负责**二次核验**阶段退出条件。
            本次给你的是**正文**——第一次核验只给了章节摘要，而摘要只记主干、可能遗漏正文里的细节描写。
            你是外部审计，不是创作者：只依据给定的正文判定，不脑补剧情外的可能性。
            引用必须是正文中逐字存在的连续原文；找不到就判未达成，严禁编造或用自己的话概括。""";

    /**
     * 二阶段：对**未达成**的条件用**正文**复核（2026-09-22）。
     *
     * <p><b>为什么必须补这一层</b>：一阶段的可核验文本是「剧情摘要 + 三账本状态串」，
     * 而摘要只记剧情主干、不记动作细节。实测出现过这种情况——正文里明明写着
     * 「指尖在桌面上无意识地轻叩了两下——一下，两下」，但六章的摘要里没有任何相关字样，
     * 于是"角色壬食指轻叩桌面两下"这条条件被判未达成。**证据在正文里，核验却看不到正文。**
     *
     * <p><b>门槛不变</b>：evidence 仍必须是<em>所填章号那一章的正文中逐字存在的连续原文</em>，
     * 用的是同一套 {@link EvidenceMatch} 归一化比对。放宽的只是"去哪找证据"，不是"要不要证据"。
     *
     * <p><b>只对未达成的条件复核</b>（达成的无需重验），一次批量调用。
     * 任何异常/输出不齐一律保留一阶段结论（fail-soft）。
     *
     * @param chapterTextByNo 章号 → 正文（复核文本源）
     * @param promptSink      实际使用的 prompt 回写容器（供复盘），可为 null
     * @return 原地更新后的结果列表（未改判时与入参相同）
     */
    public List<StageBlueprintEntity.ExitConditionResult> recheckAgainstChapterText(
            StoryVO.Module module,
            StageBlueprintEntity stage,
            List<StageBlueprintEntity.ExitConditionResult> firstPass,
            Map<Integer, String> chapterTextByNo,
            Map<String, String> promptSink) {
        if (firstPass == null || firstPass.isEmpty()
                || chapterTextByNo == null || chapterTextByNo.isEmpty() || stage == null) {
            return firstPass;
        }
        List<StageBlueprintEntity.ExitConditionResult> unmet = new ArrayList<>();
        for (StageBlueprintEntity.ExitConditionResult result : firstPass) {
            if (result != null && !Boolean.TRUE.equals(result.getMet())) {
                unmet.add(result);
            }
        }
        if (unmet.isEmpty()) {
            return firstPass;
        }
        try {
            String userPrompt = buildRecheckPrompt(unmet, chapterTextByNo);
            if (promptSink != null) {
                promptSink.put("recheck-system", RECHECK_SYSTEM_PROMPT);
                promptSink.put("recheck-user", userPrompt);
            }
            String raw = llmGateway.complete(module, LlmCall.builder()
                    .systemPrompt(RECHECK_SYSTEM_PROMPT)
                    .userPrompt(userPrompt)
                    .label("exit-recheck-第" + stage.getStageNo() + "阶段")
                    .scene(ModelScene.STAGE_EXIT_REVIEW)
                    .build());
            ExitReviewOutput output = JsonParseFallback.parse(raw, this::readOutput);
            if (output == null || output.getResults() == null
                    || output.getResults().size() != unmet.size()) {
                log.warn("阶段{}正文复核输出无法解析或条数不齐（期望 {} 条），保留一阶段结论",
                        stage.getStageNo(), unmet.size());
                return firstPass;
            }
            // 正文按章预归一化一次，与一阶段同口径（单章内连续原文）
            Map<Integer, String> normalizedByChapter = new HashMap<>();
            for (Map.Entry<Integer, String> entry : chapterTextByNo.entrySet()) {
                normalizedByChapter.put(entry.getKey(), EvidenceMatch.normalize(entry.getValue()));
            }
            int recovered = 0;
            for (int i = 0; i < unmet.size(); i++) {
                StageBlueprintEntity.ExitConditionResult model = output.getResults().get(i);
                if (model == null || !Boolean.TRUE.equals(model.getMet())) {
                    continue;
                }
                Integer chapterNo = model.getChapterNo();
                if (chapterNo == null || !normalizedByChapter.containsKey(chapterNo)) {
                    continue;
                }
                String evidence = EvidenceMatch.truncate(
                        StringUtils.trimToEmpty(model.getEvidence()), REVIEW_EVIDENCE_MAX_LENGTH);
                if (evidence.isEmpty()
                        || !EvidenceMatch.contained(evidence, normalizedByChapter.get(chapterNo))) {
                    continue;
                }
                // 改判：条件原文以机械回填的为准（不信任模型回显），只取它的判定与证据
                StageBlueprintEntity.ExitConditionResult target = unmet.get(i);
                target.setMet(true);
                target.setChapterNo(chapterNo);
                target.setEvidence(evidence);
                target.setNote("一阶段（摘要）判未达成，**正文复核通过**："
                        + "摘要是正文的压缩，未覆盖该细节，证据在正文中");
                target.setMetAtoms(target.getTotalAtoms());
                recovered++;
            }
            if (recovered > 0) {
                log.info("阶段{}正文复核：{} / {} 条从「未达成」改判为达成（摘要粒度不足，证据在正文）",
                        stage.getStageNo(), recovered, unmet.size());
            } else {
                log.info("阶段{}正文复核：{} 条仍全部未达成（内容确实没写到，非核验口径问题）",
                        stage.getStageNo(), unmet.size());
            }
            return firstPass;
        } catch (Exception e) {
            log.warn("阶段{}正文复核异常，保留一阶段结论：{}", stage.getStageNo(), e.getMessage());
            return firstPass;
        }
    }

    private String buildRecheckPrompt(List<StageBlueprintEntity.ExitConditionResult> unmet,
                                      Map<Integer, String> chapterTextByNo) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下条件在第一次核验（依据**章节摘要**）中被判为未达成。")
                .append("但摘要只记录剧情主干、可能遗漏正文里的细节描写，所以现在给你**正文**重新判定。")
                .append("\n\n【待复核条件】");
        for (int i = 0; i < unmet.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(unmet.get(i).getCondition());
        }
        sb.append("\n\n【判定要求】")
                .append("\n1. 逐条判定，**顺序与上面完全一致**，条数必须等于 ").append(unmet.size()).append("；")
                .append("\n2. met=true 时必须给出证据：chapterNo 填证据所在章号，")
                .append("evidence 为该章**正文中逐字存在的一段连续原文**（≤80 字；引号与标点允许与原文不同）；")
                .append("\n3. **找不到逐字证据就如实判 met=false**——严禁编造引文，")
                .append("严禁用自己的话概括正文（概括出来的文字在正文里找不到，会被机械校验拒收）；")
                .append("\n4. note 简述判定依据；")
                .append("\n5. 只依据下方正文判定，不要依据常识或推测补足情节。")
                .append("\n\n【正文】");
        for (Map.Entry<Integer, String> entry : chapterTextByNo.entrySet()) {
            sb.append("\n\n=== 第 ").append(entry.getKey()).append(" 章 ===\n").append(entry.getValue());
        }
        sb.append("\n\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat());
        return sb.toString();
    }

    private ExitReviewOutput readOutput(String raw) {
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            // 抛非受检异常交给 JsonParseFallback 按"解析失败"语义降级（JsonRepair 后重解）
            throw new IllegalArgumentException(e);
        }
    }

    /** 核验输出载体：results 与退出条件逐一下标对齐 */
    @Data
    public static class ExitReviewOutput {
        private List<StageBlueprintEntity.ExitConditionResult> results;
    }
}
