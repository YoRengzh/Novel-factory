package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.service.armory.memory.CharacterStateProjector;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 确定性正文材料组装：保留当前场景的叙事事实，不经过中间模型改写。
 *
 * <p>宏观蓝图、后续章节、完整账本和审计字段不进入正文；但已经核验的入场事实、人物决策依据、
 * 物理末态、相关旧记忆与上一章结尾不能被一起裁掉。LEGACY 与 DIRECT 的差别应是"如何筛选和投影"，
 * 不是后者少知道半本书。
 */
public final class WriterContextAssembler {

    private static final int SECTION_STATE_CHARS = 1600;
    public static final int DEFAULT_SECTION_CHARS = 6000;

    private WriterContextAssembler() {
    }

    public static ChapterWritingPlan assemble(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries) {
        return assemble(item, summaries, WriterChapterContext.empty(), DEFAULT_SECTION_CHARS);
    }

    public static ChapterWritingPlan assemble(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries,
                                              int maxSectionChars) {
        return assemble(item, summaries, WriterChapterContext.empty(), maxSectionChars);
    }

    public static ChapterWritingPlan assemble(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries,
                                              WriterChapterContext context, int maxSectionChars) {
        if (item == null) {
            return new ChapterWritingPlan(List.of());
        }
        WriterChapterContext safe = context == null ? WriterChapterContext.empty() : context;
        List<ScenePlan> scenes = usableScenes(item);
        if (scenes.isEmpty()) {
            return legacySections(item, summaries, safe);
        }
        List<List<ScenePlan>> groups = packByBudget(groupByContinuity(scenes), item, summaries, safe, maxSectionChars);
        List<ChapterWritingPlan.Section> sections = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            List<ScenePlan> group = groups.get(i);
            sections.add(new ChapterWritingPlan.Section(
                    renderSection(item, summaries, safe, group, i + 1, groups.size(), i + 1 == groups.size()),
                    brief(group)));
        }
        boolean knowledgeSupplied = sections.stream().anyMatch(section ->
                section.render().contains("【本章开始前的人物状态·场内新证据可以更新它】"));
        return new ChapterWritingPlan(List.copyOf(sections), knowledgeSupplied);
    }

    private static List<ScenePlan> usableScenes(ChapterPlanItemEntity item) {
        if (!Integer.valueOf(2).equals(item.getSchemaVersion()) || item.getScenes() == null) {
            return List.of();
        }
        return item.getScenes().stream().filter(Objects::nonNull).toList();
    }

    /** 同地连续场景优先合并；预算只决定是否在天然换场处断开，不截断场景文本。 */
    private static List<List<ScenePlan>> groupByContinuity(List<ScenePlan> scenes) {
        List<List<ScenePlan>> groups = new ArrayList<>();
        List<ScenePlan> current = new ArrayList<>();
        String currentPlace = null;
        for (ScenePlan scene : scenes) {
            String place = StringUtils.trimToNull(scene.location());
            if (!current.isEmpty() && !Objects.equals(currentPlace, place)) {
                groups.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            if (current.isEmpty()) {
                currentPlace = place;
            }
            current.add(scene);
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        return groups;
    }

    private static List<List<ScenePlan>> packByBudget(List<List<ScenePlan>> groups, ChapterPlanItemEntity item,
                                                      List<ChapterSummaryEntity> summaries, WriterChapterContext context,
                                                      int maxSectionChars) {
        if (maxSectionChars <= 0) {
            return List.of(groups.stream().flatMap(List::stream).toList());
        }
        if (groups.size() <= 1) {
            return groups;
        }
        List<List<ScenePlan>> packed = new ArrayList<>();
        List<ScenePlan> current = new ArrayList<>();
        int currentChars = 0;
        for (List<ScenePlan> group : groups) {
            int groupChars = renderSection(item, summaries, context, group, 1, 1, true).length();
            if (!current.isEmpty() && currentChars + groupChars > maxSectionChars) {
                packed.add(List.copyOf(current));
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.addAll(group);
            currentChars += groupChars;
        }
        if (!current.isEmpty()) {
            packed.add(List.copyOf(current));
        }
        return packed;
    }

    private static String renderSection(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries,
                                        WriterChapterContext context, List<ScenePlan> group,
                                        int index, int total, boolean includeEnding) {
        Set<String> cast = new LinkedHashSet<>();
        for (ScenePlan scene : group) {
            scene.characters().stream().filter(StringUtils::isNotBlank).forEach(cast::add);
        }
        StringBuilder text = new StringBuilder();
        if (total > 1) {
            text.append("【本段是第 ").append(index).append(" / ").append(total).append(" 段】\n");
        }
        appendLine(text, "本章要完成的事", item.getGoal());
        // 全书层面的入场材料只给第一段；后续段以实际写出来的前文为准，避免旧状态覆盖场内变化。
        if (index == 1) {
            appendBlock(text, context.initialStoryFacts());
            appendBlock(text, context.edgeState());
            appendBlock(text, context.continuityGuidance());
            appendBlock(text, context.characterGuidance());
            appendBlock(text, context.characterFeedback());
            appendBlock(text, context.authorGuidance());
            appendBlock(text, context.recalledMemories());
            if (StringUtils.isNotBlank(context.previousChapterTail())) {
                text.append("【上一章实际结尾·只用于承接，不要复述】\n")
                        .append(context.previousChapterTail().strip()).append('\n');
            }
        }
        String state = CharacterStateProjector.renderForWriterTolerant(summaries, item.getChapterNo(), cast,
                SECTION_STATE_CHARS, requiredReferences(group));
        if (StringUtils.isNotBlank(state)) {
            text.append(state.replace("【人物当前状态·只写他确实知道的】",
                    "【本章开始前的人物状态·场内新证据可以更新它】")).append('\n');
        }
        for (ScenePlan scene : group) {
            renderScene(text, scene, context.verifiedReferences(), context.povCharacter());
        }
        // 禁泄是每一段都必须遵守的边界，不因第一段已经知道而省略。
        appendBlock(text, context.secrecyGuidance());
        if (includeEnding) {
            appendLine(text, "本章结尾备选落点（仅交付作者视角允许呈现的部分）", item.getEndingHook());
        }
        return text.toString();
    }

    private static java.util.Map<String, List<String>> requiredReferences(List<ScenePlan> scenes) {
        java.util.Map<String, List<String>> references = new java.util.LinkedHashMap<>();
        for (ScenePlan scene : scenes) {
            var causality = scene.causality();
            if (causality == null) continue;
            if (causality.actors() != null) {
                for (var actor : causality.actors()) {
                    if (actor != null && actor.knowledgeRefs() != null) {
                        references.computeIfAbsent(actor.character(), key -> new ArrayList<>()).addAll(actor.knowledgeRefs());
                    }
                }
            }
            if (causality.steps() != null) {
                for (var step : causality.steps()) {
                    if (step != null && step.requires() != null) {
                        references.computeIfAbsent(step.actor(), key -> new ArrayList<>()).addAll(step.requires());
                    }
                }
            }
        }
        return references;
    }

    private static void renderScene(StringBuilder text, ScenePlan scene, Set<FactReference> verified, String povCharacter) {
        text.append("【场景】");
        String where = StringUtils.trimToNull(scene.location());
        String when = StringUtils.trimToNull(scene.time());
        if (where != null || when != null) {
            text.append(StringUtils.join(new String[]{when, where}, " · ")).append("　");
        }
        text.append("在场：").append(String.join("、", scene.characters())).append('\n');
        appendLine(text, "人物想做什么", scene.intention());
        appendLine(text, "眼前的阻碍", scene.resistance());
        appendLine(text, "这一场结束时的落点", StringUtils.defaultIfBlank(scene.outcome(), scene.function()));
        appendFacts(text, "已核实的入场事实", scene.entryStates(), verified);
        appendCausality(text, scene, verified, povCharacter);
    }

    /** 只投影已回查正式正文的事实；未核验引用和 hash/evidence 不进写手材料。 */
    private static void appendFacts(StringBuilder text, String label, List<FactReference> requested,
                                    Set<FactReference> verified) {
        if (requested == null || verified == null || verified.isEmpty()) {
            return;
        }
        String facts = requested.stream().filter(Objects::nonNull).filter(verified::contains)
                .map(f -> f.subject().trim() + "：" + f.status().trim()).distinct().reduce((a, b) -> a + "；" + b)
                .orElse("");
        appendLine(text, label, facts);
    }

    private static void appendCausality(StringBuilder text, ScenePlan scene, Set<FactReference> verified,
                                        String povCharacter) {
        SceneCausality causality = scene.causality();
        if (causality == null) {
            return;
        }
        if (causality.actors() != null) {
            boolean limitToPov = StringUtils.isNotBlank(povCharacter);
            String trimmedPov = limitToPov ? povCharacter.strip() : null;
            for (var actor : causality.actors()) {
                if (actor == null) continue;
                String name = StringUtils.defaultIfBlank(actor.character(), "人物");
                boolean isPov = !limitToPov || name.strip().equals(trimmedPov);
                if (isPov) {
                    appendLine(text, name + "想达到", actor.want());
                    appendLine(text, name + "承受的压力", actor.pressure());
                    appendLine(text, name + "可用资源", actor.resources() == null || actor.resources().isEmpty()
                            ? null : String.join("、", actor.resources()));
                    appendLine(text, name + "此刻以为", actor.belief());
                } else {
                    appendLine(text, name + "外在意图（借动作、眼神与话锋体现）", actor.want());
                    appendLine(text, name + "可用资源", actor.resources() == null || actor.resources().isEmpty()
                            ? null : String.join("、", actor.resources()));
                    List<String> subtextParts = new ArrayList<>(2);
                    if (StringUtils.isNotBlank(actor.pressure())) {
                        subtextParts.add("压力：" + actor.pressure().trim());
                    }
                    if (StringUtils.isNotBlank(actor.belief())) {
                        subtextParts.add("此刻以为：" + actor.belief().trim());
                    }
                    if (!subtextParts.isEmpty()) {
                        appendLine(text, name + "戏底暗线（供拿捏潜台词、停顿与话中带刺；限知视角下通过外在言行演出来，不写成全知内心独白或静态简历）",
                                String.join("；", subtextParts));
                    }
                }
                if (StringUtils.isNotBlank(actor.voiceAndTics())) {
                    appendLine(text, name + "本场腔调或小动作（写作参考）", actor.voiceAndTics());
                }
                if (StringUtils.isNotBlank(actor.tactic())) {
                    appendLine(text, name + "当场说话或应对方式（写作参考）", actor.tactic());
                }
            }
        }
        if (causality.steps() != null) {
            for (var step : causality.steps()) {
                if (step == null) continue;
                String actor = StringUtils.defaultIfBlank(step.actor(), "人物");
                if (StringUtils.isNotBlank(step.action())) {
                    appendLine(text, "关键动作", actor + "：" + step.action().trim());
                }
                if (StringUtils.isNotBlank(step.response())) {
                    String responder = StringUtils.defaultIfBlank(step.responder(), "对方");
                    appendLine(text, "对方反应", responder + "：" + step.response().trim());
                }
            }
        }
        var terms = causality.terms();
        if (terms != null) {
            appendLine(text, StringUtils.trimToEmpty(terms.offerer()) + "向"
                    + StringUtils.trimToEmpty(terms.receiver()) + "提供", terms.consideration());
            appendLine(text, "回报", terms.payment());
            appendLine(text, "验收方式", terms.verificationBy());
            appendLine(text, "期限", terms.deadline());
            appendLine(text, "未达约定时", terms.onFailure());
        }
        if (causality.verificationObligations() != null) {
            for (var obligation : causality.verificationObligations()) {
                if (obligation == null) continue;
                appendLine(text, "核验者", obligation.verifier());
                appendLine(text, "被核验对象", obligation.subject());
                appendLine(text, "须实际核验的项目", obligation.item());
                appendLine(text, "判定标准", obligation.criterion());
                appendLine(text, "核验结果的用途", obligation.resultUse());
                appendLine(text, "明确允许的替代及条件", obligation.allowedAlternative());
            }
        }
        appendFacts(text, "已核实的行动限制", causality.constraints(), verified);
    }

    /** 资料检索只需任务语义，不包含人物秘密、审计信息和衔接原文。 */
    private static String brief(List<ScenePlan> scenes) {
        return scenes.stream().map(scene -> {
            StringBuilder line = new StringBuilder(StringUtils.defaultString(scene.intention()));
            if (StringUtils.isNotBlank(scene.resistance())) line.append("；阻碍：").append(scene.resistance());
            if (StringUtils.isNotBlank(scene.outcome())) line.append("；落点：").append(scene.outcome());
            return line.toString();
        }).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static ChapterWritingPlan legacySections(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries,
                                                      WriterChapterContext context) {
        List<String> events = item.getKeyEvents() == null ? List.of() : item.getKeyEvents().stream()
                .filter(StringUtils::isNotBlank).toList();
        if (events.isEmpty()) {
            return new ChapterWritingPlan(List.of());
        }
        StringBuilder text = new StringBuilder();
        appendLine(text, "本章要完成的事", item.getGoal());
        appendBlock(text, context.initialStoryFacts());
        appendBlock(text, context.edgeState());
        appendBlock(text, context.continuityGuidance());
        appendBlock(text, context.characterGuidance());
        appendBlock(text, context.characterFeedback());
        appendBlock(text, context.authorGuidance());
        appendBlock(text, context.recalledMemories());
        String state = CharacterStateProjector.renderForWriter(summaries, item.getChapterNo(),
                item.getCharacters(), SECTION_STATE_CHARS);
        appendBlock(text, state);
        text.append("【本章要发生的事】按顺序写出人物行动、回应和结果：\n");
        int index = 1;
        for (String event : events) {
            text.append("- ").append(index++).append("、").append(event.trim()).append('\n');
        }
        appendBlock(text, context.secrecyGuidance());
        appendLine(text, "本章结尾备选落点（仅交付作者视角允许呈现的部分）", item.getEndingHook());
        if (StringUtils.isNotBlank(context.previousChapterTail())) {
            text.append("【上一章实际结尾·只用于承接，不要复述】\n")
                    .append(context.previousChapterTail().strip()).append('\n');
        }
        return new ChapterWritingPlan(List.of(new ChapterWritingPlan.Section(text.toString(),
                String.join("\n", events))), StringUtils.isNotBlank(state));
    }

    private static void appendLine(StringBuilder text, String label, String value) {
        if (StringUtils.isNotBlank(value)) {
            text.append("- ").append(label).append("：").append(value.trim()).append('\n');
        }
    }

    private static void appendBlock(StringBuilder text, String value) {
        if (StringUtils.isNotBlank(value)) {
            text.append(value.strip()).append("\n\n");
        }
    }
}
