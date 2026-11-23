package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.*;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 独立的证据观察投影，不改质量分数，也不把人物设计变成历史事实。 */
public final class CharacterDepthFeedbackProjector {
    private CharacterDepthFeedbackProjector() { }

    public static CharacterDepthReview observe(StoryContextEntity story, ChapterPlanItemEntity plan,
                                               ChapterManuscript manuscript, ChapterSummaryEntity summary,
                                               List<ChapterSummaryEntity> history) {
        if (!CharacterDepthSettings.observes(story)) return null;
        var profiles = story.getCharacterDecisionProfiles();
        String profileHash = profiles == null ? CharacterDepthReview.NO_PROFILE_HASH : profiles.hash();
        var binding = new CharacterDepthReview.Binding(CharacterDepthReview.RUBRIC_VERSION, manuscript.hash(),
                ChapterManuscript.digest(JSON.toJSONString(plan)), profileHash, ChapterManuscript.digest(JSON.toJSONString(history)));
        if (profiles == null || profiles.profiles().isEmpty()) return binding.review(CharacterDepthReview.Status.NOT_EVALUATED, List.of());
        List<CharacterDepthReview.Finding> findings = new ArrayList<>();
        for (var beat : summary.getCharacterBeats() == null ? List.<ChapterSummaryEntity.CharacterBeat>of() : summary.getCharacterBeats()) {
            if (beat == null) continue;
            var profile = profiles.profiles().stream().filter(card -> Objects.equals(card.displayName(), beat.getName())).findFirst().orElse(null);
            if (profile == null) continue;
            for (var observation : CharacterBeatEvidencePolicy.observations(summary, beat, manuscript, history)) {
                var sources = CharacterBeatEvidencePolicy.sourceRefs(summary, observation, history);
                if (sources.isEmpty()) continue;
                String text = observationText(observation);
                var finding = new CharacterDepthReview.Finding(List.of(profile.characterId()), List.of(), "CHAPTER",
                        "beat-field-v1:" + observation.field(), sources.get(0).evidence(), sources, text,
                        "仅参考本字段的观察来源；解释、发言与尝试不等于获知、送达或履行，不为成长和代价制造事件。");
                if (CharacterDepthReview.validFinding(finding)) findings.add(finding);
                if (findings.size() == CharacterDepthReview.MAX_FINDINGS) break;
            }
            if (findings.size() == CharacterDepthReview.MAX_FINDINGS) break;
        }
        return binding.review(findings.isEmpty() ? CharacterDepthReview.Status.NOT_EVALUATED : CharacterDepthReview.Status.OBSERVED, findings);
    }

    public static String renderFeedback(StoryContextEntity story, List<ChapterSummaryEntity> history) {
        if (!CharacterDepthSettings.applies(story) || story.getCharacterDecisionProfiles() == null) return "";
        int limit = CharacterDepthSettings.feedbackLimit(story);
        String header = "\n【人物后果观察·不是新事实或成长配额】";
        StringBuilder text = new StringBuilder();
        for (int i = history.size() - 1; i >= 0; i--) {
            var summary = history.get(i);
            var review = summary.getCharacterDepthReview();
            if (review == null || !CharacterDepthReview.RUBRIC_VERSION.equals(review.rubricVersion())
                    || !Objects.equals(review.profileHash(), story.getCharacterDecisionProfiles().hash())
                    || !Objects.equals(review.manuscriptHash(), summary.getManuscriptHash()) || review.status() != CharacterDepthReview.Status.OBSERVED
                    || review.findings() == null) continue;
            for (var finding : review.findings()) {
                if (!CharacterDepthReview.validFinding(finding) || finding.sourceRefs().stream().anyMatch(source ->
                        history.stream().noneMatch(item -> Objects.equals(source.chapterNo(), item.getChapterNo())
                                && Objects.equals(source.manuscriptHash(), item.getManuscriptHash())))) continue;
                // 旧版整条共享quote点评不能当作逐字段复核；新版按同一policy回读且对照原字段与refs。
                if ("choice-consequence".equals(finding.dimension())) continue;
                if (finding.dimension().startsWith("beat-field-v1:") && !matchesField(story, summary, finding, history)) continue;
                String line = "\n第" + summary.getChapterNo() + "章人物选择观察：" + finding.observation();
                if (header.length() + text.length() + line.length() > limit) continue;
                text.append(line);
            }
            if (!text.isEmpty()) break;
        }
        return text.isEmpty() ? "" : header + text;
    }

    private static String observationText(CharacterBeatEvidencePolicy.FieldObservation observation) {
        return CharacterBeatEvidencePolicy.fieldLabel(observation.field()) + "[" + observation.label() + "]：" + observation.value();
    }

    private static boolean matchesField(StoryContextEntity story, ChapterSummaryEntity summary,
                                         CharacterDepthReview.Finding finding, List<ChapterSummaryEntity> history) {
        if (summary.getCharacterBeats() == null) return false;
        for (var beat : summary.getCharacterBeats()) {
            if (beat == null) continue;
            var profile = story.getCharacterDecisionProfiles().profiles().stream()
                    .filter(card -> Objects.equals(card.displayName(), beat.getName())).findFirst().orElse(null);
            if (profile == null || !finding.characterIds().equals(List.of(profile.characterId()))) continue;
            for (var observation : CharacterBeatEvidencePolicy.observations(summary, beat, history)) {
                if (observation.support() != null && finding.dimension().equals("beat-field-v1:" + observation.field())
                        && finding.observation().equals(observationText(observation))
                        && finding.sourceRefs().equals(CharacterBeatEvidencePolicy.sourceRefs(summary, observation, history))) return true;
            }
        }
        return false;
    }
}
