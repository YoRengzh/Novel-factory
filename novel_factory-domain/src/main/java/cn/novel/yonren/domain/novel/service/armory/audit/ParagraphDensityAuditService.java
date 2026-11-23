package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService;
import cn.novel.yonren.domain.novel.service.armory.revise.ChapterReviseService;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.PatchIntegrityPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.StyleViolationPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.NarrativeStylePolicy;
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
import java.util.List;

/**
 * 段落信息增量审校：找正文里"没有带来任何状态变化"的段落，产出**局部改写补丁**。
 *
 * 它治的是什么：数量型指标（字数、对白句数、动作条数）只告诉模型"写多少"，
 * 不告诉它"这一段该写什么"，于是模型为满足数量而原地扩写——重复已建立的紧张感、
 * 反复写同一种情绪、用环境描写凑篇幅。这里改用**信息增量判据**：
 * 一个段落若不能带来新信息/目标变化/关系变化/资源或位置变化/风险升降/不可撤销的选择，
 * 就应当被挤掉或并入相邻段落。
 *
 * 为什么是"改写"而不是"删除"：删除不可逆，而且会破坏指代与伏笔。
 * 因此复用 {@link ChapterReviseService#applyPatches} 的补丁机制（锚点必须逐字唯一、
 * 多补丁不得重叠），套用失败就安静保留原稿。
 *
 * 为什么不需要跨章记忆：它判的是"这一段是否只重复**前文**已有的状态"，
 * 是**章内**判断，所以成本远低于需要账本基线的候选盲评。
 *
 * fail-soft：未启用 / 无问题 / 套用失败 / 改写使机械文风变差 / 调用异常，
 * 一律返回 null，调用方保留原稿——本服务永不阻断生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParagraphDensityAuditService {

    private static final BeanOutputConverter<PatchEnvelope> CONVERTER = new BeanOutputConverter<>(
            PatchEnvelope.class,
            JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    private final LlmGateway llmGateway;
    private final StoryProperties storyProperties;

    public record Suggestion(String anchor, String replacement, String evidence, String reason) {}

    private record Suggestions(List<Suggestion> suggestions) {}

    private static final JsonMapper SUGGESTION_JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build();

    public List<Suggestion> suggest(StoryVO.Module module, String content, int chapterNo, int priorBreathTimings) {
        var props = storyProperties.getParagraphAudit();
        if (props == null || !props.isEnabled() || StringUtils.isBlank(content)) {
            return List.of();
        }
        int limit = Math.max(1, props.getMaxPatches());
        String prompt = """
                你是小说编辑。通读原稿，给作者少量可以逐处比较的段落建议。
                对旁白重复解释，逐字指出本段之前哪里已经交代同一层意思；引用不出来就留原句。
                发现新的推断、人物第一次知情、立场和语气变化、具体时限或有用意象时保留。
                对显眼的重复句式或比喻扎堆，也可引用前面另一处重复写法说明原因。句式和词频只用于定位。
                删一句就能读通时可以只删。改写时保留每个物件、位置、状态、证据和说话口气，不猜新增细节，
                不将后文的发现提前，也不把人物的怀疑改成事实。anchor 是逐字、唯一的原稿片段，evidence 是说明
                重复或扎堆的前文原句。没有合适的建议就返回空数组。
                只返回 JSON：{"suggestions":[{"anchor":"原句","replacement":"改句，可为空","evidence":"前文原句","reason":"改动依据"}]}
                """ + "最多 " + limit + " 条。近两章用“息”计时 " + priorBreathTimings
                + " 次，仅供通读时参考。\n【原稿】\n" + content;
        try {
            String raw = llmGateway.complete(module, LlmCall.builder().userPrompt(prompt)
                    .label("paragraph-suggestions-第" + chapterNo + "章").scene(ModelScene.PARAGRAPH_AUDIT).build());
            Suggestions parsed = JsonParseFallback.parse(raw, this::readSuggestions);
            if (parsed == null || parsed.suggestions() == null) {
                return List.of();
            }
            return parsed.suggestions().stream()
                    .filter(s -> s != null && StringUtils.isNotBlank(s.anchor()) && s.replacement() != null
                            && !s.anchor().equals(s.replacement()) && StringUtils.isNotBlank(s.reason())
                            && StringUtils.isNotBlank(s.evidence()) && !s.anchor().contains(s.evidence())
                            && StringUtils.countMatches(content, s.anchor()) == 1
                            && precedes(content, s.evidence(), s.anchor()))
                    .limit(limit).toList();
        } catch (Exception e) {
            log.warn("第 {} 章段落建议未生成：{}", chapterNo, e.getMessage());
            return List.of();
        }
    }

    private Suggestions readSuggestions(String json) {
        try {
            return SUGGESTION_JSON.readValue(json, Suggestions.class);
        } catch (Exception invalid) {
            return null;
        }
    }

    private static boolean precedes(String content, String evidence, String anchor) {
        int at = content.indexOf(evidence);
        return at >= 0 && at + evidence.length() <= content.indexOf(anchor);
    }

    /**
     * 审校并改写。
     *
     * @return 改写后的正文；无需改写或任何环节失败时返回 null（调用方保留原稿）
     */
    public String auditAndRewrite(StoryVO.Module module, ChapterPlanItemEntity item,
                                  String content, int globalNo) {
        return auditAndRewrite(module, item, content, globalNo, 0);
    }

    public String auditAndRewrite(StoryVO.Module module, ChapterPlanItemEntity item,
                                  String content, int globalNo, int priorBreathTimings) {
        StoryProperties.ParagraphAuditProperties props = storyProperties.getParagraphAudit();
        if (props == null || !props.isEnabled() || StringUtils.isBlank(content)) {
            return null;
        }
        int maxPatches = Math.max(1, props.getMaxPatches());
        try {
            String raw = llmGateway.complete(module, LlmCall.builder()
                    .userPrompt(buildPrompt(item, content, maxPatches, priorBreathTimings))
                    // 独立场景 从 audit 拆出）：机械补丁任务此前跟随 audit 的强制思考模型，
                    // 思维链 token 占单次调用 ~2/3（4.8k 字输入 / 700 字输出实付 7-9.5k token）。
                    // ModelScene.PARAGRAPH_AUDIT 在 yml scene-models 配非思考免费档（deepseek-v4-flash）。
                    .label("paragraph-audit-第" + globalNo + "章")
                    .scene(ModelScene.PARAGRAPH_AUDIT)
                    .build());

            PatchEnvelope envelope = JsonParseFallback.parse(raw, this::readOutput);
            if (envelope == null || envelope.getPatches() == null || envelope.getPatches().isEmpty()) {
                return null;
            }
            List<ChapterReviseService.Patch> patches = envelope.getPatches().stream()
                    .filter(patch -> patch != null && StringUtils.isNotBlank(patch.getAnchor())
                            && patch.getReplacement() != null)
                    .limit(maxPatches)
                    .toList();
            if (patches.isEmpty()) {
                return null;
            }
            ChapterReviseService.PatchOutcome outcome = ChapterReviseService.applyPatches(content, patches);
            if (!outcome.applied()) {
                log.info("第 {} 章段落密度审校：补丁未套用（{}），保留原稿", globalNo, outcome.failureReason());
                return null;
            }
            String regression = NarrativeStylePolicy.regressionReason(content, outcome.content());
            if (regression != null) {
                log.info("第 {} 章段落补丁未采纳：{}", globalNo, regression);
                return null;
            }
            // 采纳闸门①（最小版）：改写不得让机械文风违规变多——挤水不能顺手把文风改坏
            int before = StyleViolationPolicy.check(content).size();
            int after = StyleViolationPolicy.check(outcome.content()).size();
            if (after > before) {
                log.info("第 {} 章段落密度审校：改写使机械文风违规由 {} 条变为 {} 条，整批回退",
                        globalNo, before, after);
                return null;
            }
            // 采纳闸门②（语义完整性）：密度补丁只该挤掉重复段落，**不许丢骨架**。
            // 两条都零容忍——这条路径没有"改事实"的合法任务，任何关键事件/实体名/数字的消失都是错的；
            // 而此前它只查文风违规条数，一次套用就能把只出现一次的实体或数字删掉，且不会在任何指标上现形。
            // 零容忍的前提是判据足够精确：锚只算"原稿有、补丁后全章再无"，不比较出现次数
            //（挤水的正当目的就是删段落，比次数会把正确的改写也一起拒掉）。
            List<String> anchors = new ArrayList<>(
                    PatchIntegrityPolicy.characterAnchors(item == null ? null : item.getCharacters()));
            anchors.addAll(PatchIntegrityPolicy.numberAnchors(content));
            PatchIntegrityPolicy.Verdict integrity = PatchIntegrityPolicy.check(
                    content, outcome.content(), item == null ? null : item.getKeyEvents(), anchors, 0, 0);
            if (!integrity.acceptable()) {
                log.info("第 {} 章段落密度审校：{}，整批回退保留原稿", globalNo, integrity.reason());
                return null;
            }
            // 采纳闸门③（长度下限）：挤水的目的是去掉重复，不是把章挤薄。
            // 与修订链路一样，需要长度保持闸门，避免局部补丁累积后损害成篇完整性。
            int originalChars = ChapterLengthPolicy.effectiveCharacterCount(content);
            int patchedChars = ChapterLengthPolicy.effectiveCharacterCount(outcome.content());
            int floor = Math.max(ChapterLengthPolicy.MINIMUM_EFFECTIVE_CHARACTERS,
                    (int) (originalChars * MIN_LENGTH_KEEP_RATIO));
            if (patchedChars < floor) {
                log.info("第 {} 章段落密度审校：改写后有效字 {} 低于下限 {}（原稿 {}），整批回退保留原稿",
                        globalNo, patchedChars, floor, originalChars);
                return null;
            }
            // 采纳闸门④（重复句零容忍）：补丁不得**引入**章内整句重复。
            // 治的是一种具体误写：模型把"删除该段"执行成"保留原句再接一遍原句"——
            // 挤水的目的本就是去掉重复，改写后重复句变多与该路径的语义直接相反。
            // 只查"变多"不查存量：原稿已有的重复由质量门的 BLOCKING 去管，
            // 本闸门只管这次补丁有没有让情况变坏（与闸门①的"不得变多"同款思路）。
            int dupBefore = StyleStatService.intraChapterDuplicateSentences(content).size();
            int dupAfter = StyleStatService.intraChapterDuplicateSentences(outcome.content()).size();
            if (dupAfter > dupBefore) {
                log.info("第 {} 章段落密度审校：改写引入章内整句重复（{} → {} 句），整批回退保留原稿",
                        globalNo, dupBefore, dupAfter);
                return null;
            }
            log.info("第 {} 章段落密度审校：套用 {} 条改写补丁（机械违规 {} → {}，有效字 {} → {}，语义完整性通过）",
                    globalNo, patches.size(), before, after, originalChars, patchedChars);
            return outcome.content();
        } catch (Exception e) {
            log.warn("第 {} 章段落密度审校异常，保留原稿：{}", globalNo, e.getMessage());
            return null;
        }
    }

    /** 补丁后必须保留的有效字比例：低于「原稿 85%」或全局下限即回退（挤水不得挤薄章） */
    private static final double MIN_LENGTH_KEEP_RATIO = 0.85;

    private String buildPrompt(ChapterPlanItemEntity item, String content, int maxPatches, int priorBreathTimings) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是小说编辑，只做一件事：找出重复且没有当前阅读功能的段落，并给出局部改写。")
                .append("\n\n【判定标准】只有同时满足“内容重复或空转”且“删改后不损失当前场景功能”时才处理：")
                .append("\n- 反复陈述前文已经建立、且没有带来新理解的情绪、结论或处境；")
                .append("\n- 同一情绪、判断或动作在原地循环，既不推进人物关系，也不改变读者理解；")
                .append("\n- 段中或段末，旁白把刚写过的行动再盘成得失清单，或替读者重述已经清楚的用意，可只删多余的那句。")
                .append("保留实际盘点得出的新信息，例如清点后才发现物资短缺，以及改变人物下一步行动的判断。")
                .append("\n- 环境、心理或概述段落只是填充篇幅，删去后不损失空间定位、氛围、人物状态或后果消化。")
                .append("\n\n").append(cn.novel.yonren.domain.novel.service.armory.prompt.WritingContractPolicy.renderEditorialScope())
                .append("\n心理、讲述、等待、环境、余韵和后果消化都可以独立承担阅读功能，不能仅因不改变外部状态而删除。")
                .append("段落里有需要保留的行动、人物声音或情绪时，保留这些原句。")
                .append("\n\n【本章契约】")
                .append("\n目标：").append(StringUtils.defaultString(item == null ? null : item.getGoal()))
                .append("\n关键事件：").append(item == null || item.getKeyEvents() == null
                        ? "" : String.join("、", item.getKeyEvents()))
                .append("\n结尾落点：").append(StringUtils.defaultString(item == null ? null : item.getEndingHook()));
        if (item != null && item.getScenes() != null && !item.getScenes().isEmpty()) {
            sb.append("\n").append(
                    cn.novel.yonren.domain.novel.service.armory.plan.ScenePlanPolicy.render(item.getScenes()));
        }
        var observations = NarrativeStylePolicy.inspect(content);
        var styleIssues = observations.issues(ChapterLengthPolicy.effectiveCharacterCount(content));
        if (!styleIssues.isEmpty()) {
            sb.append("\n\n【本稿重复表达线索】不同段落也可能借同一句型来写不同的事。")
                    .append("即使事实各不相同，也可局部调整反复出现的写法。没有人持有被否定的判断时，可以直接写观察结果。")
                    .append("保留确有作用的辨析、形象和时限，原文已经交代的事实不用再解释一遍。");
            for (var issue : styleIssues) {
                sb.append("\n- ").append(issue.getDescription()).append(" 原文：").append(issue.getEvidence());
            }
            if (observations.closestSimiles().size() >= 3) {
                sb.append("\n邻近的比喻请连起来读。若同一眼扫过几件东西，每件都另配一个喻体，优先留下与人物感受或动作有关的形象。")
                        .append("其他物件可以直写。逐项核对原物、位置与状态，勿为了少写一个比喻把物件一起删掉。");
            }
        }
        int currentBreaths = observations.breathTimings().size();
        if (priorBreathTimings > 0 && currentBreaths > 0 && priorBreathTimings + currentBreaths >= 6) {
            sb.append("\n近两章叙述用“息”计时 ").append(priorBreathTimings).append(" 次，本稿 ")
                    .append(currentBreaths).append(" 次。核对普通停顿是否反复报时；保留具体时限、倒计时和影响人物选择的等待。");
        }
        sb.append("\n\n【输出要求】")
                .append("\n- 每个问题段落最多一条补丁，anchor 逐字复制全部待替换的原文并确保只出现一次。")
                .append("程序仅替换 anchor。只删一句时，引用该句并将 replacement 设为空字符串；改整段时，引用完整原段。")
                .append("\n- 改写要**保留必要信息**，把水分挤掉即可，不要改变剧情走向、不要新增情节；")
                .append("\n- 最多输出 ").append(maxPatches).append(" 条，优先处理最明显的注水段落；")
                .append("\n- 没有需要改的段落时输出 {\"patches\":[]}；")
                .append("\n- anchor 必须逐字来自正文，任何改写过的 anchor 都会导致整批作废。")
                .append("\n\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat())
                .append("\n\n【正文】\n").append(content);
        return sb.toString();
    }

    private PatchEnvelope readOutput(String raw) {
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** 补丁信封：与修订链路的 JSON 形状一致，便于复用同一套机械套用规则 */
    @Data
    public static class PatchEnvelope {
        private List<ChapterReviseService.Patch> patches;
    }
}
