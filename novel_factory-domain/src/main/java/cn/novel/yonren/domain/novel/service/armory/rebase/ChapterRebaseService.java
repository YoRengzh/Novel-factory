package cn.novel.yonren.domain.novel.service.armory.rebase;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.rebase.RebaseProposal;
import cn.novel.yonren.domain.novel.model.valobj.rebase.RebaseRecord;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.memory.CharacterStateProjector;
import cn.novel.yonren.domain.novel.service.armory.plan.CharacterFactReferences;
import cn.novel.yonren.domain.novel.service.armory.plan.ScenePlanPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.DeliveryReviewPolicy;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 观察式章节重基：只产生“候选计划 + 差异报告”，不改变正文任务，也不自动采纳。
 *
 * <p>模型拿到的不是整本书前缀，而是当前章计划 + 截至上一章的已确认人物状态 + 上一章结尾。
 * 输出只允许按 sceneId 提交字段补丁，主线锚点、章号、标题、场景 ID 和人物名单由程序锁定。
 * 校验只保证协议与来源安全，不声称机械证明语义正确。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterRebaseService {

    /** 协议版本参与记录，便于区分不同提示/校验口径的候选。 */
    public static final String REVIEW_VERSION = "rebase-patch-v2";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private final LlmGateway llmGateway;
    private final StoryProperties storyProperties;

    /** 是否启用（默认 OFF，不产生任何调用）。 */
    public boolean enabled() {
        return storyProperties.getRebase() != null
                && storyProperties.getRebase().getMode() == StoryProperties.RebaseProperties.Mode.PROPOSE_ONLY;
    }

    /**
     * 生成一次重基候选。OFF 返回 null；任何失败都降级为 INVALID 记录，不影响原计划。
     *
     * @param previousTail 上一章结尾原文（可为空）；只用于理解承接状态，不整章回灌
     */
    public RebaseRecord propose(StoryVO storyVO, ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries,
                                StoryContextEntity story, String previousTail) {
        if (!enabled() || item == null || !Integer.valueOf(2).equals(item.getSchemaVersion())
                || item.getScenes() == null || item.getScenes().isEmpty()) {
            return null;
        }
        int chapterNo = item.getChapterNo() == null ? 0 : item.getChapterNo();
        String originalHash = ChapterManuscript.digest(JSON.toJSONString(item));
        RebaseReferenceTable references = references(item, summaries, chapterNo);
        String stateHash = ChapterManuscript.digest(references.render());
        String prompt = buildPrompt(item, story, previousTail, references);
        try {
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .scene(ModelScene.CHAPTER_REBASE)
                    .label("chapter-rebase-" + chapterNo)
                    .systemPrompt("你是章节重基提案员。只提出相对原计划的字段补丁，不写正文，不修改历史与主线锚点。")
                    .userPrompt(prompt)
                    .build());
            return consume(raw, item, originalHash, stateHash, references, storyVO);
        } catch (Exception e) {
            log.warn("第 {} 章重基候选生成失败，保留原计划：{}", chapterNo, e.getMessage());
            return invalid(item, originalHash, stateHash, storyVO, "REBASE_UNAVAILABLE: " + e.getMessage());
        }
    }

    /** 解析并校验模型提案；不合格一律 INVALID，不产出“可采纳候选”。 */
    RebaseRecord consume(String raw, ChapterPlanItemEntity original, String originalHash, String stateHash,
                         RebaseReferenceTable references, StoryVO storyVO) {
        if (StringUtils.isBlank(raw)) {
            return invalid(original, originalHash, stateHash, storyVO, "REBASE_EMPTY_RESPONSE");
        }
        RebaseProposal proposal = JsonParseFallback.parse(unwrap(raw), this::read);
        if (proposal == null || proposal.status() == null) {
            return invalid(original, originalHash, stateHash, storyVO, "REBASE_PARSE");
        }
        if (proposal.status() == RebaseProposal.Status.BLOCKED) {
            return new RebaseRecord(RebaseRecord.SCHEMA, String.valueOf(original.getChapterNo()), originalHash,
                    stateHash, null, model(storyVO), REVIEW_VERSION, "BLOCKED", List.of(),
                    List.copyOf(safe(proposal.remainingRisks())), false, null);
        }
        if (proposal.status() == RebaseProposal.Status.UNCHANGED) {
            return new RebaseRecord(RebaseRecord.SCHEMA, String.valueOf(original.getChapterNo()), originalHash,
                    stateHash, originalHash, model(storyVO), REVIEW_VERSION, "UNCHANGED", List.of(),
                    List.copyOf(safe(proposal.remainingRisks())), false, null);
        }
        List<String> problems = new ArrayList<>();
        for (var diagnosis : safe(proposal.diagnosis())) {
            if (diagnosis != null && !references.has(diagnosis.sourceRef())) {
                problems.add("REBASE_UNKNOWN_REFERENCE: " + diagnosis.sourceRef());
            }
        }
        Map<String, ScenePlan> byId = new LinkedHashMap<>();
        for (ScenePlan scene : original.getScenes()) {
            if (scene != null) byId.put(scene.id(), scene);
        }
        List<RebaseRecord.Change> changes = new ArrayList<>();
        List<ScenePlan> patched = new ArrayList<>();
        Set<String> patchedIds = new LinkedHashSet<>();
        for (ScenePlan scene : original.getScenes()) {
            if (scene == null) continue;
            RebaseProposal.ScenePatch patch = patchFor(proposal, scene.id());
            if (patch == null) {
                patched.add(scene);
                continue;
            }
            if (!patchedIds.add(scene.id())) {
                problems.add("REBASE_DUPLICATE_PATCH: " + scene.id());
                continue;
            }
            if (StringUtils.isBlank(patch.intention()) && StringUtils.isBlank(patch.resistance())
                    && StringUtils.isBlank(patch.outcome()) && StringUtils.isBlank(patch.function())
                    && patch.causality() != null) {
                // 只更新人物/因果依据的补丁不能冒充新的场景落点；沿用原场景承诺。
                ScenePlan updated = new ScenePlan(scene.id(), scene.characters(), scene.intention(),
                        scene.resistance(), scene.entryStates(), scene.outcome(), scene.function(),
                        scene.milestoneRefs(), scene.location(), scene.time(), patch.causality());
                diff(scene, updated, patch, changes);
                patched.add(updated);
                continue;
            }
            if (StringUtils.isBlank(patch.intention()) || StringUtils.isBlank(patch.outcome())
                    || StringUtils.isBlank(patch.function())) {
                problems.add("REBASE_INCOMPLETE_PATCH: " + scene.id() + " 缺少 intention/outcome/function");
                continue;
            }
            ScenePlan updated = new ScenePlan(scene.id(), scene.characters(), patch.intention(),
                    StringUtils.defaultString(patch.resistance()), scene.entryStates(), patch.outcome(),
                    patch.function(), scene.milestoneRefs(), scene.location(), scene.time(), patch.causality());
            diff(scene, updated, patch, changes);
            patched.add(updated);
        }
        for (RebaseProposal.ScenePatch patch : safe(proposal.patches())) {
            if (patch != null && patch.sceneId() != null && !byId.containsKey(patch.sceneId())) {
                problems.add("REBASE_UNKNOWN_SCENE: " + patch.sceneId());
            }
        }
        ChapterPlanItemEntity candidate = copyWithScenes(original, patched);
        problems.addAll(invariantViolations(original, candidate, references));
        problems.addAll(ScenePlanPolicy.violations(candidate));
        if (!problems.isEmpty()) {
            return new RebaseRecord(RebaseRecord.SCHEMA, String.valueOf(original.getChapterNo()), originalHash,
                    stateHash, null, model(storyVO), REVIEW_VERSION, "INVALID", List.copyOf(changes),
                    List.copyOf(problems), false, null);
        }
        return new RebaseRecord(RebaseRecord.SCHEMA, String.valueOf(original.getChapterNo()), originalHash,
                stateHash, ChapterManuscript.digest(JSON.toJSONString(candidate)), model(storyVO), REVIEW_VERSION,
                "PROPOSED", List.copyOf(changes), List.of(), false,
                JSON.parseObject(JSON.toJSONString(candidate)));
    }

    /** 程序锁定的不变量：锚点、标题、人物名单、引用来源、核验义务完整性。 */
    private List<String> invariantViolations(ChapterPlanItemEntity original, ChapterPlanItemEntity candidate,
                                             RebaseReferenceTable references) {
        List<String> problems = new ArrayList<>();
        if (!Objects.equals(original.getChapterNo(), candidate.getChapterNo())) {
            problems.add("REBASE_ANCHOR_CHANGED: chapterNo");
        }
        if (!Objects.equals(original.getTitle(), candidate.getTitle())) {
            problems.add("REBASE_ANCHOR_CHANGED: title");
        }
        if (!Objects.equals(original.getMainLineAdvance(), candidate.getMainLineAdvance())) {
            problems.add("REBASE_ANCHOR_CHANGED: mainLineAdvance");
        }
        Set<String> originalCast = new HashSet<>(safe(original.getCharacters()));
        for (ScenePlan scene : candidate.getScenes()) {
            if (scene == null) continue;
            for (String character : scene.characters()) {
                if (StringUtils.isNotBlank(character) && !originalCast.contains(character)) {
                    problems.add("REBASE_NEW_CHARACTER: " + character);
                }
            }
            if (scene.causality() == null) continue;
            // 新人物也可能从 causality.actors 绕进计划，不能只看 scene.characters。
            for (var actor : safe(scene.causality().actors())) {
                if (actor != null && StringUtils.isNotBlank(actor.character())
                        && !originalCast.contains(actor.character())) {
                    problems.add("REBASE_NEW_CHARACTER: " + actor.character());
                }
            }
            for (var obligation : safe(scene.causality().verificationObligations())) {
                if (obligation == null) continue;
                if (StringUtils.isBlank(obligation.verifier()) || StringUtils.isBlank(obligation.subject())
                        || StringUtils.isBlank(obligation.item()) || StringUtils.isBlank(obligation.criterion())
                        || StringUtils.isBlank(obligation.resultUse())) {
                    problems.add("REBASE_INCOMPLETE_OBLIGATION: " + scene.id());
                    continue;
                }
                for (String participant : List.of(StringUtils.defaultString(obligation.verifier()),
                        StringUtils.defaultString(obligation.subject()))) {
                    if (StringUtils.isNotBlank(participant) && !scene.characters().contains(participant)) {
                        problems.add("REBASE_OBLIGATION_CAST: " + scene.id() + " / " + participant);
                    }
                }
                if (crossSubjectResult(scene, obligation)) {
                    problems.add("REBASE_CROSS_SUBJECT_VERDICT: " + scene.id() + " / " + obligation.subject());
                }
            }
        }
        return problems;
    }

    /** 一个对象的结果不能被用来证明另一对象自身的核验完成；作者授权路径仍是合法例外。 */
    private boolean crossSubjectResult(ScenePlan scene, SceneCausality.VerificationObligation obligation) {
        String result = StringUtils.defaultString(obligation.resultUse());
        for (String participant : safe(scene.characters())) {
            if (participant.equals(obligation.subject())) continue;
            if (result.contains(participant + "通过") || result.contains(participant + "合格")) {
                if (StringUtils.isNotBlank(obligation.allowedAlternative())
                        && obligation.allowedAlternative().contains(participant)) return false;
                return true;
            }
        }
        return false;
    }

    private RebaseProposal.ScenePatch patchFor(RebaseProposal proposal, String sceneId) {
        for (RebaseProposal.ScenePatch patch : safe(proposal.patches())) {
            if (patch != null && Objects.equals(patch.sceneId(), sceneId)) return patch;
        }
        return null;
    }

    private void diff(ScenePlan before, ScenePlan after, RebaseProposal.ScenePatch patch,
                      List<RebaseRecord.Change> changes) {
        String reason = StringUtils.defaultString(patchReason(patch));
        addChange(changes, before.id(), "intention", before.intention(), after.intention(), reason);
        addChange(changes, before.id(), "resistance", before.resistance(), after.resistance(), reason);
        addChange(changes, before.id(), "outcome", before.outcome(), after.outcome(), reason);
        addChange(changes, before.id(), "function", before.function(), after.function(), reason);
        String causalityBefore = before.causality() == null ? "" : JSON.toJSONString(before.causality());
        String causalityAfter = after.causality() == null ? "" : JSON.toJSONString(after.causality());
        addChange(changes, before.id(), "causality", causalityBefore, causalityAfter, reason);
    }

    private String patchReason(RebaseProposal.ScenePatch patch) {
        return patch == null ? "" : StringUtils.defaultString(patch.reason());
    }

    private static void addChange(List<RebaseRecord.Change> changes, String sceneId, String field,
                                  String before, String after, String reason) {
        if (Objects.equals(StringUtils.defaultString(before), StringUtils.defaultString(after))) return;
        changes.add(new RebaseRecord.Change(sceneId, field, StringUtils.defaultString(before),
                StringUtils.defaultString(after), StringUtils.defaultString(reason)));
    }

    private ChapterPlanItemEntity copyWithScenes(ChapterPlanItemEntity original, List<ScenePlan> scenes) {
        return ChapterPlanItemEntity.builder()
                .chapterNo(original.getChapterNo())
                .title(original.getTitle())
                .goal(original.getGoal())
                .characters(original.getCharacters())
                .keyEvents(original.getKeyEvents())
                .dialogueScenes(original.getDialogueScenes())
                .endingHook(original.getEndingHook())
                .timeAdvance(original.getTimeAdvance())
                .chapterType(original.getChapterType())
                .suspenseBeat(original.getSuspenseBeat())
                .mainLineAdvance(original.getMainLineAdvance())
                .schemaVersion(original.getSchemaVersion())
                .scenes(scenes)
                .build();
    }

    private RebaseReferenceTable references(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries,
                                            int chapterNo) {
        List<CharacterEffect> effects = new ArrayList<>();
        if (summaries != null && !summaries.isEmpty()) {
            var projection = CharacterStateProjector.projection(summaries, chapterNo, null,
                    CharacterStateProjector.DEFAULT_MAX_FACTS, 0);
            effects.addAll(projection.confirmedFacts().values());
        }
        return new RebaseReferenceTable(effects, item.getScenes());
    }

    private String buildPrompt(ChapterPlanItemEntity item, StoryContextEntity story, String previousTail,
                               RebaseReferenceTable references) {
        List<FactReference> facts = List.of();
        StringBuilder sb = new StringBuilder();
        sb.append("你是章节重基提案员。根据真实人物状态，判断当前章计划是否仍需按原预测走。")
                .append("只提出相对原计划的字段补丁，不写正文、不改历史。\n")
                .append("规则：\n")
                .append("1. 章号、标题、主线推进、场景 ID 与人物名单不可改；不得新增有名人物。\n")
                .append("2. 改场景承诺/落点必须给完整 intention/resistance/outcome/function；仅更新人物依据或核验义务时可只给 causality。\n")
                .append("3. 引用只用下方短 ID（P*/S*），不得编造 factId、章节 hash 或长原文；来源不足就留空。\n")
                .append("4. 核验义务必须写清核验者、被验者、项目、标准、结果用途；诊断对手不能代替被验者本人完成核验。\n")
                .append("5. 未展示收件者的传讯只记录动作，不能声称送达；未来结果不是历史事实。\n")
                .append("6. 计划本就成立时返回 UNCHANGED，不要为改而改；无法兼顾时返回 BLOCKED。\n")
                .append("输出 JSON：{\"status\":\"PROPOSED|UNCHANGED|BLOCKED\",\"diagnosis\":[{\"problem\":\"...\",")
                .append("\"sourceRef\":\"P1\",\"sourceQuote\":\"...\"}],\"patches\":[{\"sceneId\":\"...\",")
                .append("\"intention\":\"...\",\"resistance\":\"...\",\"outcome\":\"...\",\"function\":\"...\",")
                .append("\"causality\":{}}],\"remainingRisks\":[\"...\"]}。只返回该对象。\n\n")
                .append("【本书设定】\n").append(StringUtils.defaultString(story == null ? null : story.getTheme()))
                .append("｜").append(StringUtils.defaultString(story == null ? null : story.getStyle()))
                .append("\n【当前章计划】\n").append(JSON.toJSONString(item))
                .append("\n【可用引用】\n").append(references.render());
        if (StringUtils.isNotBlank(previousTail)) {
            sb.append("\n【上一章结尾原文】\n").append(previousTail);
        }
        return sb.toString();
    }

    private RebaseProposal read(String raw) {
        try {
            return MAPPER.readValue(raw, RebaseProposal.class);
        } catch (Exception invalid) {
            return null;
        }
    }

    private static String unwrap(String raw) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").strip();
        }
        return text;
    }

    private RebaseRecord invalid(ChapterPlanItemEntity item, String originalHash, String stateHash,
                                 StoryVO storyVO, String diagnostic) {
        return new RebaseRecord(RebaseRecord.SCHEMA, String.valueOf(item.getChapterNo()), originalHash, stateHash,
                null, model(storyVO), REVIEW_VERSION, "INVALID", List.of(), List.of(diagnostic), false, null);
    }

    private String model(StoryVO storyVO) {
        if (storyVO == null || storyVO.getModule() == null || storyVO.getModule().getSceneModels() == null) {
            return null;
        }
        var chat = storyVO.getModule().getSceneModels().get(ModelScene.CHAPTER_REBASE.getConfigKey());
        return chat == null ? null : chat.getModel();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
