package cn.novel.yonren.domain.novel.service.armory.beats;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterBeatsEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.List;

/** 场景节拍只展开已有契约；失败时回退直接写作，不追加对白、冲突或篇幅任务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterBeatsService {

    private static final BeanOutputConverter<ChapterBeatsEntity> CONVERTER =
            new BeanOutputConverter<>(ChapterBeatsEntity.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());

    /** 仅保留非空与输出规模防护；单场、单拍同样可以完成任务。 */
    private static final int MIN_BEATS = 1;
    private static final int MAX_BEATS = 6;

    private final LlmGateway llmGateway;

    /**
     * 为本章生成场景节拍表
     *
     * @return 节拍表；生成失败/解析失败/节拍数越界时返回 null（调用方降级为无节拍直写）
     */
    public ChapterBeatsEntity buildBeats(StoryVO storyVO, ChapterPlanItemEntity item, int globalNo,
                                         String contextText) {
        try {
            // 独立 chapter-beats 场景：每章一次的高频调用留在 flash 控成本，不跟随 chapter-plan 升 max
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .userPrompt(buildPrompt(item, globalNo, contextText))
                    .label("beats-第" + globalNo + "章")
                    .scene(ModelScene.CHAPTER_BEATS)
                    .build());

            ChapterBeatsEntity beats = tryConvert(raw);
            if (beats == null) {
                beats = JsonParseFallback.parse(raw, this::tryConvert);
            }
            if (beats == null || beats.getBeats() == null
                    || beats.getBeats().size() < MIN_BEATS || beats.getBeats().size() > MAX_BEATS) {
                log.warn("第 {} 章场景节拍生成失败或节拍数越界，降级为无节拍直写", globalNo);
                return null;
            }
            log.info("第 {} 章场景节拍生成完成，共 {} 拍", globalNo, beats.getBeats().size());
            log.debug("第 {} 章节拍观测：{} 拍未声明状态增量", globalNo,
                    beats.getBeats().stream().filter(b -> !hasStateChange(b)).count());
            return beats;
        } catch (Exception e) {
            log.warn("第 {} 章场景节拍生成异常，降级为无节拍直写：{}", globalNo, e.getMessage());
            return null;
        }
    }

    /** 节拍是否声明了信息或状态变化。 */
    public static boolean hasStateChange(ChapterBeatsEntity.Beat beat) {
        return beat != null && StringUtils.isNotBlank(beat.getInfoGain());
    }

    /** 渲染正文使用的节拍表。 */
    public String renderBeatsPrompt(ChapterBeatsEntity beats) {
        return renderBeatsPrompt(beats, true);
    }

    /**
     * 渲染节拍块，{@code enforceOrder} 决定节拍是"必须逐拍照做"还是"仅建议顺序"。
     *
     * {@code GenerationMode.enforcesBeats()} 决定是否强制执行顺序。
     * FREE 模式下允许重组场景与对白比例，不能同时渲染与建议顺序矛盾的逐拍强制指令。
     */
    public String renderBeatsPrompt(ChapterBeatsEntity beats, boolean enforceOrder) {
        if (beats == null || beats.getBeats() == null || beats.getBeats().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(enforceOrder
                ? "\n\n【本章节拍表】本章已拆为以下有序节拍，请逐拍扩写，不要跳过或合并："
                : "\n\n【本章节拍表】以下节拍为**建议顺序**：在保证因果链与【本章完成条件】的前提下，"
                        + "可自行调整、合并或重组场景划分与对白比例，交付以场景契约为准：");
        int idx = 1;
        for (ChapterBeatsEntity.Beat beat : beats.getBeats()) {
            sb.append("\n节拍 ").append(idx++);
            if (StringUtils.isNotBlank(beat.getLocation())) {
                sb.append("｜地点：").append(beat.getLocation());
            }
            if (beat.getCharacters() != null && !beat.getCharacters().isEmpty()) {
                sb.append("｜在场：").append(String.join("、", beat.getCharacters()));
            }
            if (StringUtils.isNotBlank(beat.getConflict())) {
                sb.append("｜冲突：").append(beat.getConflict());
            }
            if (Boolean.TRUE.equals(beat.getExchange())) {
                sb.append("｜交流任务：按场景契约展开已有的交流目的、必要信息和回应，")
                        .append("发言依据各角色当时掌握的信息，具体措辞由正文决定");
            }
            if (StringUtils.isNotBlank(beat.getInfoGain())) {
                sb.append("｜信息增量：").append(beat.getInfoGain());
            } else {
                // 未声明状态增量不代表没有阅读功能。
                sb.append("｜阅读功能：按原场景的等待、消化或余韵处理，不为状态增量制造事件");
            }
            if (StringUtils.isNotBlank(beat.getWeight())) {
                sb.append("｜篇幅占比：约").append(beat.getWeight());
            }
        }
        sb.append("\n扩写纪律：节拍只展开本章场景契约，不新增人物、事实、结果、对白或代价。")
                .append("等待、心理、环境与后果消化可以独立承担阅读功能，不要求每拍有状态变化。")
                .append("若节拍与权威场景或已确认事实冲突，以后者为准；已承诺的行动和结果须连贯完成。");
        return sb.toString();
    }

    /**
     * 组装节拍 prompt。
     *
     * ⚠️ {@code contextText}（前情上下文）**必须给**：要求 3 早就写着"第一拍必须无缝承接上一章结尾"，
     * 但此前 prompt 里根本没有上一章的任何内容——**指令承诺了模型做不到的事**，
     * 于是它只能凭空想象上一章发生了什么（或干脆忽略这条要求）。
     *
     * @param contextText 紧邻本章之前的章节摘要（由调用方裁剪，避免把整段记忆前缀塞进来）
     */
    private String buildPrompt(ChapterPlanItemEntity item, int globalNo, String contextText) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是小说场景调度师。把以下章节计划拆成供写手展开的节拍，数量服从场景本身，")
                .append("单场、单拍或安静场景同样可以成立。")
                .append("\n要求：")
                .append("\n1. 每拍字段：location（地点）、characters（在场角色）、conflict（本拍要发生的事或阻力，无外部阻力可写人物的等待、观察或消化）、")
                .append("exchange（布尔值：本拍是否需要展开计划已安排的人物交流）、")
                .append("infoGain（本拍带来的新信息/状态变化/关系变化；安静场可留空，不强制状态增量）、weight（篇幅占比，合计 100%）；")
                .append("\n   ⚠️ characters 必须是**纯字符串数组**，元素就是角色名本身，"
                        + "不得写成对象、不得夹带身份说明——身份信息不属于本字段。")
                .append("✅ [\"角色A\",\"角色B\"]　❌ [{\"角色A\":\"婴儿\"}]　❌ [\"角色A（婴儿）\"]")
                .append("\n2. 节拍按叙事顺序排列，合起来覆盖本章目标与场景契约；不新增计划外人物、事件、结果或冲突。")
                .append("\n3. 第一拍承接上一章结尾的现场/状态，最后一拍落在本章计划的结尾落点。")
                .append("\n4. 每拍的 conflict 必须具体，严禁‘推进剧情’‘发展关系’这类空话。")
                .append("\n4.1 有计划内交流时标记 exchange=true，动作或静场可标记 false。"
                        + "任何字段都不得写对白原句或编造交流内容；具体措辞由写手依据场景契约创作。")
                .append("\n5. 不输出 Markdown，不输出解释。")
                .append(StringUtils.isBlank(contextText) ? "" : "\n\n【前情上下文】以下是紧邻本章之前的章节摘要——"
                        + "**要求 3 的「承接上一章结尾」只能依据这里，不要凭空想象上一章发生了什么**：\n"
                        + contextText)
                .append("\n\n【本章计划】")
                .append("\n标题：").append(item.getTitle() == null ? "" : item.getTitle())
                .append("\n目标：").append(item.getGoal() == null ? "" : item.getGoal())
                .append("\n关键事件：")
                .append(item.getKeyEvents() == null ? "" : String.join("、", item.getKeyEvents()))
                .append("\n结尾悬念：").append(item.getEndingHook() == null ? "" : item.getEndingHook());
        if (item.getScenes() != null && !item.getScenes().isEmpty()) {
            sb.append("\n\n").append(cn.novel.yonren.domain.novel.service.armory.plan.ScenePlanPolicy.render(item.getScenes()));
        }
        sb.append("\n\n请严格按照以下 JSON 格式输出：")
                .append("\n{\"beats\":[{\"location\":\"地点\",\"characters\":[\"角色A\"],")
                .append("\"conflict\":\"本拍发生的事\",\"exchange\":false,")
                .append("\"infoGain\":\"本拍的信息或状态变化（安静场可留空）\",\"weight\":\"30%\"}]}");
        return sb.toString();
    }

    private ChapterBeatsEntity tryConvert(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            // 裸数组兜底：prompt 末尾明确给了 {"beats":[...]} 包装格式，实测 qwen3.8-flash
            // 仍会直接返回顶层数组 [{...},{...}]（第 131/133/135 章连续三次）。JsonParseFallback
            // 只治 JSON 语法病（引号/逗号/围栏），治不了"形状不符"——内容其实完整，被形状判死后
            // 整章降级骨架直写。这里按 JSON 语法补上包装层再解析一次，不进 prompt、不加调用成本。
            return convertWrappedArray(raw);
        }
    }

    /**
     * 把顶层裸数组包成 {@code {"beats":[...]}} 后重试解析。
     *
     * 只在首个非空白字符是 {@code '['} 时生效；剥离 markdown 围栏后判断，
     * 避免 ```json 开头的裸数组漏网。仍失败返回 null（调用方 fail-soft 降级）。
     */
    private ChapterBeatsEntity convertWrappedArray(String raw) {
        String text = raw.trim();
        // 剥围栏（JsonRepair 同款口径）：```json ... ``` / ``` ... ```
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        if (!text.startsWith("[")) {
            return null;
        }
        try {
            return CONVERTER.convert("{\"beats\":" + text + "}");
        } catch (Exception e) {
            return null;
        }
    }
}
