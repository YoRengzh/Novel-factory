package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.memory.StyleStatService;
import cn.novel.yonren.domain.novel.service.armory.revise.ChapterReviseService;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.PatchIntegrityPolicy;
import cn.novel.yonren.domain.novel.service.armory.quality.StyleViolationPolicy;
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
 * <p><b>它治的是什么</b>：数量型指标（字数、对白句数、动作条数）只告诉模型"写多少"，
 * 不告诉它"这一段该写什么"，于是模型为满足数量而原地扩写——重复已建立的紧张感、
 * 反复写同一种情绪、用环境描写凑篇幅。这里改用**信息增量判据**：
 * 一个段落若不能带来新信息/目标变化/关系变化/资源或位置变化/风险升降/不可撤销的选择，
 * 就应当被挤掉或并入相邻段落。
 *
 * <p><b>为什么是"改写"而不是"删除"</b>：删除不可逆，而且会破坏指代与伏笔。
 * 因此复用 {@link ChapterReviseService#applyPatches} 的补丁机制（锚点必须逐字唯一、
 * 多补丁不得重叠），套用失败就安静保留原稿。
 *
 * <p><b>为什么不需要跨章记忆</b>：它判的是"这一段是否只重复**前文**已有的状态"，
 * 是**章内**判断，所以成本远低于需要账本基线的候选盲评。
 *
 * <p><b>fail-soft</b>：未启用 / 无问题 / 套用失败 / 改写使机械文风变差 / 调用异常，
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

    /**
     * 审校并改写。
     *
     * @return 改写后的正文；无需改写或任何环节失败时返回 null（调用方保留原稿）
     */
    public String auditAndRewrite(StoryVO.Module module, ChapterPlanItemEntity item,
                                  String content, int globalNo) {
        StoryProperties.ParagraphAuditProperties props = storyProperties.getParagraphAudit();
        if (props == null || !props.isEnabled() || StringUtils.isBlank(content)) {
            return null;
        }
        int maxPatches = Math.max(1, props.getMaxPatches());
        try {
            String raw = llmGateway.complete(module, LlmCall.builder()
                    .userPrompt(buildPrompt(item, content, maxPatches))
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
                    .filter(patch -> patch != null && StringUtils.isNotBlank(patch.getAnchor()))
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
            // 采纳闸门①（最小版）：改写不得让机械文风违规变多——挤水不能顺手把文风改坏
            int before = StyleViolationPolicy.check(content).size();
            int after = StyleViolationPolicy.check(outcome.content()).size();
            if (after > before) {
                log.info("第 {} 章段落密度审校：改写使机械文风违规由 {} 条变为 {} 条，整批回退",
                        globalNo, before, after);
                return null;
            }
            // 采纳闸门②（语义完整性，2026-10-10）：密度补丁只该挤掉重复段落，**不许丢骨架**。
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
            // 采纳闸门③（长度下限，2026-10-10）：挤水的目的是去掉重复，不是把章挤薄。
            // 实测同一段章节重跑一次，字数掉 21%（平均 1716→1360）、对白掉 30%，
            // 其中挤水补丁从 2 条涨到 9 条是贡献项之一——而这条路径此前**没有任何长度闸门**
            // （修订链路有"字数不低于原稿 80%"，这里一条都没有）。
            int originalChars = ChapterLengthPolicy.effectiveCharacterCount(content);
            int patchedChars = ChapterLengthPolicy.effectiveCharacterCount(outcome.content());
            int floor = Math.max(ChapterLengthPolicy.MINIMUM_EFFECTIVE_CHARACTERS,
                    (int) (originalChars * MIN_LENGTH_KEEP_RATIO));
            if (patchedChars < floor) {
                log.info("第 {} 章段落密度审校：改写后有效字 {} 低于下限 {}（原稿 {}），整批回退保留原稿",
                        globalNo, patchedChars, floor, originalChars);
                return null;
            }
            // 采纳闸门④（重复句零容忍，2026-10-15）：补丁不得**引入**章内整句重复。
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

    private String buildPrompt(ChapterPlanItemEntity item, String content, int maxPatches) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是小说编辑，只做一件事：找出**没有带来任何信息增量**的段落，并给出局部改写。")
                .append("\n\n【判定标准】段落满足以下任一条，即为无信息增量（应挤掉或并入相邻段落）：")
                .append("\n- 只是重复前文已经建立过的紧张感、情绪或结论；")
                .append("\n- 只是原地循环同一种情绪（反复写\"他很愤怒/他很痛苦\"，却没有新动作或新变化）；")
                .append("\n- 只是环境或心理铺陈，不改变任何状态。")
                // 2026-10-10：本判据原先写的是"只是重复前文已经建立过的紧张感、情绪或结论"，
                // 而模型会优先拿**情绪段**开刀——把"具体呈现情感"的段落也当成注水删掉。
                // 实测同一章重跑一次，情感词从 5 处掉到 1 处、字数掉 30%，其中这条措辞是贡献项。
                // 注水的定义是**抽象地重复已知状态**，不是"写了情绪"。
                .append("\n  ⚠️ 但**具体呈现情感**的段落不算无信息增量：靠动作、停顿、沉默、器物或对白"
                        + "把情绪与关系变化演出来的段落（如话说到一半把碗放下、伸手又收回去），"
                        + "是人物塑造的一部分，**一律不要动**。")
                .append("\n\n有信息增量的段落**一律不要动**——信息增量指：新信息进入读者视野、角色目标变化、")
                .append("关系或信任变化、资源或位置变化、风险上升或下降、角色做出不可撤销的选择。")
                .append("\n\n【本章契约】")
                .append("\n目标：").append(StringUtils.defaultString(item == null ? null : item.getGoal()))
                .append("\n关键事件：").append(item == null || item.getKeyEvents() == null
                        ? "" : String.join("、", item.getKeyEvents()))
                .append("\n结尾落点：").append(StringUtils.defaultString(item == null ? null : item.getEndingHook()))
                .append("\n\n【输出要求】")
                .append("\n- 每个问题段落输出**一条补丁**：anchor 为该段落在正文中**逐字存在且唯一**的一段原文")
                .append("（建议取该段开头 30-60 字），replacement 为改写后的段落；")
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
