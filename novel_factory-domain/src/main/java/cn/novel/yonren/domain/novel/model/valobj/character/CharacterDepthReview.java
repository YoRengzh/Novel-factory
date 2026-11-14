package cn.novel.yonren.domain.novel.model.valobj.character;

import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 人物深度的独立观测，不是 issue、事实、修订授权或放行凭据。 */
public record CharacterDepthReview(String rubricVersion, String manuscriptHash, String planHash,
                                   String profileHash, String stateHash, Status status,
                                   List<Finding> findings) {
    public static final String RUBRIC_VERSION = "character-depth-v1";
    public static final String NO_PROFILE_HASH = ChapterManuscript.digest("character-profiles:ABSENT");
    public static final int MAX_FINDINGS = 8;

    public enum Status { OBSERVED, NOT_APPLICABLE, NOT_EVALUATED, ERROR, STALE }

    public CharacterDepthReview {
        // 保留 null 与非法元素给消费端校验；旧/残缺 JSON 不能借默认空表冒充已完成观测。
        findings = immutableOrNull(findings);
    }

    /** 版本身份只由本次实际输入计算，模型回填的版本号/hash 不参与绑定。 */
    public record Binding(String rubricVersion, String manuscriptHash, String planHash,
                          String profileHash, String stateHash) {
        public boolean complete() {
            return RUBRIC_VERSION.equals(rubricVersion) && hash(manuscriptHash) && hash(planHash)
                    && hash(profileHash) && hash(stateHash);
        }

        public Binding withManuscript(ChapterManuscript manuscript) {
            return new Binding(rubricVersion, manuscript == null ? null : manuscript.hash(),
                    planHash, profileHash, stateHash);
        }

        public CharacterDepthReview review(Status status, List<Finding> findings) {
            return new CharacterDepthReview(rubricVersion, manuscriptHash, planHash, profileHash,
                    stateHash, status, findings);
        }
    }

    /** 先核对章号与逐字引文，再从代码来源表绑定 manuscriptHash。 */
    public record SourceRef(Integer chapterNo, String manuscriptHash, String evidence) { }

    public record Finding(List<String> characterIds, List<String> sceneIds, String scope,
                          String dimension, String evidence, List<SourceRef> sourceRefs,
                          String observation, String conditionalSuggestion) {
        public Finding {
            characterIds = immutableOrNull(characterIds);
            sceneIds = immutableOrNull(sceneIds);
            sourceRefs = immutableOrNull(sourceRefs);
        }
    }

    /**
     * 同一次既有审校请求的可选输入。referenceText 只是对照资料，不是写作/修订指令。
     * sourceRefs 仅含代码核对过的历史证据；当前稿证据由解析器自行绑定。
     */
    public record Context(Binding binding, ChapterManuscript manuscript, String referenceText,
                          List<String> characterIds, List<String> sceneIds, List<SourceRef> sourceRefs) {
        public Context {
            // 当前稿身份始终现场计算，不接受外部/模型提供的正文 hash。
            binding = binding == null ? null : binding.withManuscript(manuscript);
            characterIds = immutableOrEmpty(characterIds);
            sceneIds = immutableOrEmpty(sceneIds);
            sourceRefs = immutableOrEmpty(sourceRefs);
        }

        public Context withManuscript(ChapterManuscript value) {
            return new Context(binding, value, referenceText, characterIds, sceneIds, sourceRefs);
        }

        public boolean evaluable() {
            return binding != null && binding.complete() && manuscript != null
                    && !manuscript.content().isBlank() && !characterIds.isEmpty();
        }

        public boolean matches(String content, int chapterNo) {
            return manuscript != null && manuscript.equals(new ChapterManuscript(chapterNo,
                    manuscript.title(), content));
        }

        public CharacterDepthReview review(Status status) {
            return binding == null ? new CharacterDepthReview(RUBRIC_VERSION,
                    manuscript == null ? null : manuscript.hash(), null, null, null, status, List.of())
                    : binding.review(status, List.of());
        }
    }

    public Binding binding() {
        return new Binding(rubricVersion, manuscriptHash, planHash, profileHash, stateHash);
    }

    public CharacterDepthReview withStatus(Status value) {
        return value == status ? this : new CharacterDepthReview(rubricVersion, manuscriptHash,
                planHash, profileHash, stateHash, value, findings);
    }

    /** 改稿后仅标旧点评失效，绝不把旧点评贴上终稿的新 hash。 */
    public CharacterDepthReview forManuscript(String finalHash) {
        return Objects.equals(manuscriptHash, finalHash) && hash(finalHash) ? this : withStatus(Status.STALE);
    }

    public CharacterDepthReview forBinding(Binding expected) {
        return expected != null && expected.complete() && expected.equals(binding())
                ? this : withStatus(Status.STALE);
    }

    /** 旧数据缺版本/hash/显式 findings 或未知量表，不可升级成跨批深度证据。 */
    public boolean usable(Binding expected) {
        return status == Status.OBSERVED && expected != null && expected.complete()
                && expected.equals(binding()) && findings != null && findings.size() <= MAX_FINDINGS
                && findings.stream().allMatch(CharacterDepthReview::validFinding);
    }

    public static boolean validFinding(Finding finding) {
        return finding != null && ids(finding.characterIds(), false) && ids(finding.sceneIds(), true)
                && finding.scope() != null && List.of("SCENE", "CHAPTER", "CROSS_CHAPTER").contains(finding.scope())
                && (!"SCENE".equals(finding.scope()) || !finding.sceneIds().isEmpty())
                && text(finding.dimension(), 64) && text(finding.evidence(), 400)
                && text(finding.observation(), 800)
                && (finding.conditionalSuggestion() == null || finding.conditionalSuggestion().length() <= 800)
                && finding.sourceRefs() != null && !finding.sourceRefs().isEmpty()
                && finding.sourceRefs().size() <= 8
                && finding.sourceRefs().stream().allMatch(source -> source != null && source.chapterNo() != null
                    && source.chapterNo() > 0 && hash(source.manuscriptHash()) && text(source.evidence(), 400));
    }

    private static boolean ids(List<String> values, boolean allowEmpty) {
        return values != null && (allowEmpty || !values.isEmpty()) && values.size() <= 24
                && values.stream().allMatch(value -> text(value, 160))
                && values.stream().distinct().count() == values.size();
    }

    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }

    public static boolean hash(String value) {
        return value != null && value.matches("[a-fA-F0-9]{64}");
    }

    private static <T> List<T> immutableOrNull(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <T> List<T> immutableOrEmpty(List<T> values) {
        return Collections.unmodifiableList(values == null ? new ArrayList<>() : new ArrayList<>(values));
    }
}
