package cn.novel.yonren.domain.novel.service.armory.beats;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterBeatsEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 场景节拍服务：章节计划 → 正文之间的轻量中间层（每次正文生成前调用一次，小输出任务）。
 * 把本章计划拆解为 3-5 个有序节拍（地点/在场角色/核心冲突/对白/信息增量/篇幅占比），
 * 注入正文 prompt 要求逐拍扩写——治"计划太粗导致模型即兴注水"的根因。
 *
 * <p>{@code dialogue} 与 {@code infoGain} 一样是**显式供给项**：只给"冲突/动作"时，
 * "他把信折好塞进信封"是合法的一拍，写手据此就能交出一整章动作分解（实测有效对话个位数句，
 * 同批其它章 12-27 句）。对白必须成为节拍表的一项，写手才有可执行的东西。
 *
 * 失败 fail-soft：任何异常/解析失败返回 null，调用方降级为无节拍直写，不阻塞生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterBeatsService {

    private static final BeanOutputConverter<ChapterBeatsEntity> CONVERTER =
            new BeanOutputConverter<>(ChapterBeatsEntity.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    /** 节拍数下限/上限：过少失去约束意义，过多则束缚创作 */
    private static final int MIN_BEATS = 2;
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
            // 信息增量机械检查：节拍 prompt 里早有"每拍必须兑现信息增量"的纪律，
            // 但此前**没有任何机械校验**——无状态变化的拍会诱导模型"原地扩写"凑篇幅。
            // 判据与 renderBeatsPrompt 的纪律同源（infoGain 承载 新信息/状态变化/关系变化）。
            long stagnant = beats.getBeats().stream().filter(b -> !hasStateChange(b)).count();
            if (stagnant > 0) {
                log.warn("第 {} 章有 {}/{} 拍未声明信息增量（无状态变化），已在正文 prompt 中标注"
                                + "「并入相邻拍、严禁原地扩写」", globalNo, stagnant, beats.getBeats().size());
            }
            // 对白供给机械检查（2026-10-09）：与 infoGain 同款但**判据必须不同**——
            // infoGain 是必填字段，模型总会填点什么，那条检查实测全程从未触发（等于空转）；
            // dialogue 是允许留空的字段，留空才携带信息：全章留空＝这一章只有流程、没有戏。
            long silent = beats.getBeats().stream().filter(b -> !hasDialogue(b)).count();
            if (silent == beats.getBeats().size()) {
                log.warn("第 {} 章 {} 拍全部未提供对白，已在正文 prompt 中标注「至少 1 拍由对白承载」",
                        globalNo, silent);
            }
            return beats;
        } catch (Exception e) {
            log.warn("第 {} 章场景节拍生成异常，降级为无节拍直写：{}", globalNo, e.getMessage());
            return null;
        }
    }

    /**
     * 节拍是否带来状态变化——信息增量判据。
     *
     * <p>口径与 {@link #renderBeatsPrompt} 的扩写纪律同源：{@code infoGain} 的语义本就是
     * 「新信息 / 状态变化 / 关系变化」（见 {@code ChapterBeatsEntity.Beat#infoGain} 注释），
     * 因此非空即视为本拍有增量。**没有增量的拍应当并入相邻拍，而不是原地扩写凑篇幅。**
     *
     * <p>⚠️ 这里**不新增字段**：{@code stateChange} 的语义已由 {@code infoGain} 承载，
     * 真正缺的一直是<em>机械校验</em>——此前只在 prompt 里要求过，代码里没有任何检查
     * （典型的"指标已有、只是没动作"）。
     */
    public static boolean hasStateChange(ChapterBeatsEntity.Beat beat) {
        return beat != null && StringUtils.isNotBlank(beat.getInfoGain());
    }

    /**
     * 本拍是否提供了对白供给（见 {@link ChapterBeatsEntity.Beat#getDialogue()}）。
     *
     * <p>与 {@link #hasStateChange} 的差别值得记一笔：{@code infoGain} 是必填字段，
     * 模型总会填点什么，那条机械检查实测从未触发；{@code dialogue} 是**允许留空**的字段，
     * 留空才携带信息——全章留空说明这一章只有流程、没有戏。
     */
    public static boolean hasDialogue(ChapterBeatsEntity.Beat beat) {
        return beat != null && StringUtils.isNotBlank(beat.getDialogue());
    }

    /**
     * 把节拍表渲染进正文 prompt：逐拍扩写 + 每拍必须兑现信息增量。
     * 这是正文侧唯一的"防注水供给"——模型按拍执行，没有自由虚构填充的空间
     */
    public String renderBeatsPrompt(ChapterBeatsEntity beats) {
        return renderBeatsPrompt(beats, true);
    }

    /**
     * 渲染节拍块，{@code enforceOrder} 决定节拍是"必须逐拍照做"还是"仅建议顺序"。
     *
     * <p><b>为什么要这个开关</b>（2026-10-09）：{@code GenerationMode.enforcesBeats()} 早就写好了
     * 也写了单测，但**生产代码从未调用**——FREE（契约完整 + 节拍可用）本意是"节拍只作建议顺序，
     * 把场景划分与对白比例的自由还给模型"，实际拼出来的却是"以下节拍为建议顺序"紧跟
     * "请逐拍扩写，不要跳过或合并"（后者更靠后、更具体，实际生效）。于是"自由写作"名不副实，
     * 写手被锁死在逐拍扩写上——第 96 章那种把每个事件展开成动作分解的写法正源于此。
     */
    public String renderBeatsPrompt(ChapterBeatsEntity beats, boolean enforceOrder) {
        if (beats == null || beats.getBeats() == null || beats.getBeats().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(enforceOrder
                ? "\n\n【本章节拍表】本章已拆为以下有序节拍，请逐拍扩写，不要跳过或合并："
                : "\n\n【本章节拍表】以下节拍为**建议顺序**：在保证因果链与【本章完成条件】的前提下，"
                        + "可自行调整、合并或重组场景划分与对白比例（顺序变了不算违规，漏掉信息增量才算）：");
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
            if (StringUtils.isNotBlank(beat.getDialogue())) {
                sb.append("｜对白：").append(beat.getDialogue());
            } else {
                sb.append("｜对白：（本拍无对白）");
            }
            if (StringUtils.isNotBlank(beat.getInfoGain())) {
                sb.append("｜信息增量：").append(beat.getInfoGain());
            } else {
                // 机械检查发现的"无状态变化拍"：标注为可合并，堵住"原地扩写凑篇幅"这条路
                sb.append("｜⚠️本拍未声明信息增量——若无法产生新信息/关系变化/资源变化/风险变化/"
                        + "不可逆决定，请并入相邻拍，严禁原地扩写凑篇幅");
            }
            if (StringUtils.isNotBlank(beat.getWeight())) {
                sb.append("｜篇幅占比：约").append(beat.getWeight());
            }
        }
        // 全章零对白拍：兜底告知。根因在节拍生成侧（要求 + 字段已补），这里只补一句硬要求，
        // 不留质量债、不降级——节拍本就是 fail-soft 的轻量层，不该为它打断生成
        if (beats.getBeats().stream().noneMatch(ChapterBeatsService::hasDialogue)) {
            sb.append("\n⚠️ 【对白承载·兜底】本章节拍表没有提供任何对白——请至少安排 1 场由对白承载的关键事件"
                    + "（谁对谁、围绕什么分歧、一来一回多轮），不要整章只用动作分解交代流程。"
                    + "动作分解能写满篇幅却零互动，那是流程说明，不是戏。");
        }
        sb.append("\n扩写纪律：每一拍必须兑现其【信息增量】（新信息/状态变化/关系变化至少其一）；")
                .append("禁止在一拍内部原地循环同一情绪或同一动作；上一拍未完成的动作必须在下一拍延续或交代中断原因。");
        return sb.toString();
    }

    /**
     * 组装节拍 prompt。
     *
     * <p>⚠️ {@code contextText}（前情上下文）**必须给**：要求 3 早就写着"第一拍必须无缝承接上一章结尾"，
     * 但此前 prompt 里根本没有上一章的任何内容——**指令承诺了模型做不到的事**，
     * 于是它只能凭空想象上一章发生了什么（或干脆忽略这条要求）。
     *
     * @param contextText 紧邻本章之前的章节摘要（由调用方裁剪，避免把整段记忆前缀塞进来）
     */
    private String buildPrompt(ChapterPlanItemEntity item, int globalNo, String contextText) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是小说场景调度师。把以下章节计划拆解为 3-5 个有序的场景节拍，供写手逐拍扩写。")
                .append("\n要求：")
                .append("\n1. 每拍五要素：location（地点）、characters（在场角色）、conflict（本拍核心冲突或关键动作）、")
                .append("dialogue（本拍由谁对谁开口、说了什么：一句话交锋要点或关键台词；本拍确实无对话时留空）、")
                .append("infoGain（本拍必须产生的信息增量：新信息/状态变化/关系变化至少其一）、weight（篇幅占比，合计 100%）；")
                .append("\n   ⚠️ characters 必须是**纯字符串数组**，元素就是角色名本身，"
                        + "不得写成对象、不得夹带身份说明——身份信息不属于本字段。")
                .append("✅ [\"角色A\",\"角色B\"]　❌ [{\"角色A\":\"婴儿\"}]　❌ [\"角色A（婴儿）\"]")
                .append("\n2. 节拍按叙事顺序排列，合起来必须完整覆盖本章目标与全部关键事件；")
                .append("\n3. 第一拍必须无缝承接上一章结尾的现场/悬念，最后一拍必须落在本章计划的结尾悬念上；")
                .append("\n4. 每拍的 conflict 必须具体（谁对谁的什么行动/阻碍），严禁‘推进剧情’‘发展关系’这类空话；")
                .append("\n4.1 ⚠️ **对白供给**：全章至少 1 拍的 dialogue 必须写出具体的交锋（谁对谁、说的是什么），"
                        + "让这一章有戏可看；**禁止**全章所有拍都是‘谁做了什么’的动作分解——"
                        + "实测教训：全程动作分解的章能把篇幅写满，有效对话却只有个位数句（同批其它章在 12-27 句），"
                        + "读者读到的是一份流程说明而不是一场戏。角色只在动作里出场、不用台词表达意图，"
                        + "写手会如实扩写成流水账；")
                .append("\n   ⚠️ dialogue 里若写台词，一律用成对中文双引号“”包裹，不得用括号——"
                        + "括号包裹的句子不计入对白统计，会让对白量虚报达标。")
                .append("\n5. 不输出 Markdown，不输出解释。")
                .append(StringUtils.isBlank(contextText) ? "" : "\n\n【前情上下文】以下是紧邻本章之前的章节摘要——"
                        + "**要求 3 的「承接上一章结尾」只能依据这里，不要凭空想象上一章发生了什么**：\n"
                        + contextText)
                .append("\n\n【本章计划】")
                .append("\n标题：").append(item.getTitle() == null ? "" : item.getTitle())
                .append("\n目标：").append(item.getGoal() == null ? "" : item.getGoal())
                .append("\n关键事件：")
                .append(item.getKeyEvents() == null ? "" : String.join("、", item.getKeyEvents()))
                .append("\n结尾悬念：").append(item.getEndingHook() == null ? "" : item.getEndingHook())
                .append("\n\n请严格按照以下 JSON 格式输出：")
                .append("\n{\"beats\":[{\"location\":\"地点\",\"characters\":[\"角色A\"],")
                .append("\"conflict\":\"核心冲突\",\"dialogue\":\"角色A对角色B说的那句话（确无对话时留空字符串）\",")
                .append("\"infoGain\":\"信息增量\",\"weight\":\"30%\"}]}");
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
     * <p>只在首个非空白字符是 {@code '['} 时生效；剥离 markdown 围栏后判断，
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
