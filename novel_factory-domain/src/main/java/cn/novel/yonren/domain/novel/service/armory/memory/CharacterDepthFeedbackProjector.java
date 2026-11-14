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
            var profile = profiles.profiles().stream().filter(card -> Objects.equals(card.displayName(), beat.getName())).findFirst().orElse(null);
            String evidence = beat.getEvidence();
            if (profile == null || evidence == null || evidence.isBlank() || evidence.length() > 400 || !manuscript.content().contains(evidence)) continue;
            String observation = "目标：" + Objects.toString(beat.getGoal(), "未记录") + "；选择：" + Objects.toString(beat.getDecision(), "未记录")
                    + "；后果：" + Objects.toString(beat.getConsequence(), "未记录");
            if (observation.length() > 800) continue;
            findings.add(new CharacterDepthReview.Finding(List.of(profile.characterId()), List.of(), "CHAPTER", "choice-consequence",
                    evidence, List.of(new CharacterDepthReview.SourceRef(manuscript.chapterNo(), manuscript.hash(), evidence)), observation,
                    "后续参考已发生选择及卡片设计，核对改变决定的依据；不为人物成长或代价制造事件。"));
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
                        !Objects.equals(source.chapterNo(), summary.getChapterNo())
                                || !Objects.equals(source.manuscriptHash(), summary.getManuscriptHash()))) continue;
                String line = "\n第" + summary.getChapterNo() + "章人物选择观察：" + finding.observation();
                if (header.length() + text.length() + line.length() > limit) continue;
                text.append(line);
            }
            if (!text.isEmpty()) break;
        }
        return text.isEmpty() ? "" : header + text;
    }
}
