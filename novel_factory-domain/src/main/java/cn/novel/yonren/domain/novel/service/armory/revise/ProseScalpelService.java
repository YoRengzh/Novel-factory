package cn.novel.yonren.domain.novel.service.armory.revise;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.PatchIntegrityPolicy;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 只删不扩·主编去解释刮油刀：专在轻装写手（DIRECT）出稿后剥离残余解释句与 AI 味套话。
 * 只允许删减或极短缩句，禁止改写文风、扩写与删改人物/数字/对白事实；失败或越界静默保留原稿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProseScalpelService {

    private static final double MIN_KEEP_RATIO = 0.80;
    private static final double SCALPEL_TEMPERATURE = 0.2;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final LlmGateway llmGateway;
    private final StoryProperties storyProperties;

    public String trim(StoryVO.Module module, ChapterPlanItemEntity item, String content, int chapterNo) {
        var props = storyProperties == null ? null : storyProperties.getProseScalpel();
        if (props == null || !props.isEnabled() || StringUtils.isBlank(content)) {
            return null;
        }
        int maxPatches = Math.max(1, props.getMaxPatches());
        try {
            String raw = llmGateway.complete(module, LlmCall.builder()
                    .scene(ModelScene.PARAGRAPH_AUDIT)
                    .temperature(SCALPEL_TEMPERATURE)
                    .label("prose-scalpel-第" + chapterNo + "章")
                    .userPrompt(buildPrompt(content, maxPatches))
                    .build());
            PatchEnvelope envelope = JsonParseFallback.parse(raw, this::readEnvelope);
            if (envelope == null || envelope.getPatches() == null || envelope.getPatches().isEmpty()) {
                return null;
            }
            List<ChapterReviseService.Patch> validPatches = filterShrinkOnlyPatches(envelope.getPatches(), maxPatches);
            if (validPatches.isEmpty()) {
                return null;
            }
            List<String> anchors = buildAnchors(item, content);
            String patched = applySafely(content, validPatches, anchors, chapterNo);
            if (patched == null || patched.equals(content)) {
                return null;
            }
            patched = patched.replaceAll("\n{3,}", "\n\n").strip();
            if (!passesIntegrityAndLength(content, patched, anchors, chapterNo)) {
                return null;
            }
            log.info("第 {} 章去解释刮油刀完成：原 {} 字 → 精修后 {} 字",
                    chapterNo, ChapterLengthPolicy.effectiveCharacterCount(content),
                    ChapterLengthPolicy.effectiveCharacterCount(patched));
            return patched;
        } catch (Exception e) {
            log.warn("第 {} 章去解释刮油刀异常，保留原稿：{}", chapterNo, e.getMessage());
            return null;
        }
    }

    /** 锁 1：只删不扩锁——禁止 replacement 长于 anchor。 */
    static List<ChapterReviseService.Patch> filterShrinkOnlyPatches(List<ChapterReviseService.Patch> rawPatches, int maxPatches) {
        List<ChapterReviseService.Patch> result = new ArrayList<>();
        for (ChapterReviseService.Patch patch : rawPatches) {
            if (patch == null || StringUtils.isBlank(patch.getAnchor()) || patch.getReplacement() == null) {
                continue;
            }
            if (patch.getAnchor().equals(patch.getReplacement())
                    || patch.getReplacement().length() > patch.getAnchor().length()) {
                continue;
            }
            result.add(patch);
            if (result.size() >= maxPatches) {
                break;
            }
        }
        return result;
    }

    /** 锁 2：优先批量套用并过完整性校验；若整体校验不通过或锚点重叠，则逐条安全试套，只跳过越界补丁。 */
    private String applySafely(String content, List<ChapterReviseService.Patch> validPatches,
                               List<String> anchors, int chapterNo) {
        ChapterReviseService.PatchOutcome batch = ChapterReviseService.applyPatches(content, validPatches);
        if (batch.applied() && PatchIntegrityPolicy.check(content, batch.content(), null, anchors, 0, 0).acceptable()) {
            return batch.content();
        }
        String current = content;
        int applied = 0;
        for (ChapterReviseService.Patch patch : validPatches) {
            if (StringUtils.countMatches(current, patch.getAnchor()) != 1) {
                continue;
            }
            String candidate = StringUtils.replaceOnce(current, patch.getAnchor(), patch.getReplacement());
            PatchIntegrityPolicy.Verdict verdict = PatchIntegrityPolicy.check(current, candidate, null, anchors, 0, 0);
            if (!verdict.acceptable()) {
                log.info("第 {} 章跳过破坏实体/数字锚点的单条刮油补丁：{}", chapterNo, verdict.reason());
                continue;
            }
            current = candidate;
            applied++;
        }
        return applied > 0 ? current : null;
    }

    private static List<String> buildAnchors(ChapterPlanItemEntity item, String original) {
        List<String> anchors = new ArrayList<>(
                PatchIntegrityPolicy.characterAnchors(item == null ? null : item.getCharacters()));
        anchors.addAll(PatchIntegrityPolicy.numberAnchors(original));
        return anchors;
    }

    /** 锁 3 & 锁 4：事实完整性（人名+数字零丢失）与长度底线（≥原稿80%且≥坍缩硬底线）。 */
    private boolean passesIntegrityAndLength(String original, String patched, List<String> anchors, int chapterNo) {
        PatchIntegrityPolicy.Verdict verdict = PatchIntegrityPolicy.check(original, patched, null, anchors, 0, 0);
        if (!verdict.acceptable()) {
            log.info("第 {} 章刮油补丁被完整性锁拦截，保留原稿：{}", chapterNo, verdict.reason());
            return false;
        }
        int originalEffective = ChapterLengthPolicy.effectiveCharacterCount(original);
        int patchedEffective = ChapterLengthPolicy.effectiveCharacterCount(patched);
        int floor = Math.max(ChapterLengthPolicy.COLLAPSE_FLOOR_CHARACTERS,
                (int) (originalEffective * MIN_KEEP_RATIO));
        if (patchedEffective < floor) {
            log.info("第 {} 章刮油后有效字 {} 低于下限 {}（原 {}），回退保留原稿",
                    chapterNo, patchedEffective, floor, originalEffective);
            return false;
        }
        return true;
    }

    static String buildPrompt(String content, int maxPatches) {
        return """
                你是资深网文主编，只做一件事：在不改变剧情、不改动任何引号内人物对白、不新增任何词句的前提下，用定向删除/缩句刮掉正文里残余的“AI解释腔”。

                只处理以下五类问题（最多给出 %d 条补丁）：
                1. 引号外的“不是A，而是B / 并非A，而是B / 不再是A，而是B”：删去虚设的否定前半句，直接保留或改写成干净的 B（replacement 长度必须严格短于或等于 anchor）。
                2. 动作或对白已经演明白后画蛇添足的动机复盘、心理账本、自我复盘与预告清单（如“免了小过，后面一定有别的事等着”“把能看到的东西在心里过了一遍”）、围观心思点破或战略演讲句：整句直接删除（replacement 设为空串 ""），或只留极短过渡词。
                3. 人物（尤其是配角）刚露面时的年龄、修为、资历、性格等静态档案介绍，打断节奏的突兀背景百科科普句，以及与推动剧情无关的物件/场景图鉴式冗余细描：删除静态说明，保留现场动作与对白。
                4. 限知视角下越界切入配角内心做履历感叹、心理翻译或替配角神态/语气贴标签的解说句（如“他干了十几年……没见过”“语气已经不是质问了，更像在掂量”“不像是自己划的，的确是……”）：删除多余的内心解说与定性结论，只保留主角亲眼可见的动作、停顿、神态与对白。
                5. 中文叙述中偶发混入的孤立英文虚词或连词（如 somehow、until、actually）：直接删除或替换为更短的中文词。

                铁律：
                - 绝对不要扩写，绝对不要重写正常叙事段落，不要润色已经生动的动作、感官与对白。
                - 保留所有出场人名、关键数字、物件位置与因果事件，只删多余“解说词”。
                - anchor 必须逐字复制原稿中唯一出现的连续原文（含标点），replacement 只能是更短文本或空字符串 ""。
                - 没有明显问题时输出 {"patches":[]}。

                只输出 JSON：{"patches":[{"anchor":"原稿逐字唯一片段","replacement":"删减后文本或空串"}]}

                【待精修正文】
                """.formatted(maxPatches) + content;
    }

    private PatchEnvelope readEnvelope(String raw) {
        try {
            return JSON.readValue(raw, PatchEnvelope.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Data
    public static class PatchEnvelope {
        private List<ChapterReviseService.Patch> patches;
    }
}
