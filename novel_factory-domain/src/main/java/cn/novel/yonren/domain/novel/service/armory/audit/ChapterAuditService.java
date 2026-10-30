package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.quality.EvidenceMatch;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 章节审校服务：对单章正文做七维度审校，输出结构化 issue 列表。
 * 直连 LlmGateway 小参数调用，刻意不走 PromptBuilder/动态选择器——
 * 创作规则（反 AI 味、钩子技巧）对审校模型有害无益。
 * 审校是质量门：解析/修复均失败时抛 AppException 终止本批（绝不返回 null 等价零问题）。
 * BLOCKING 仅限四类硬伤且 evidence 经正文子串校验，未命中降级 MINOR——
 * 防 severity 通胀稀释修订与质量债治理链路（程度问题混入 BLOCKING 的典型症状：
 * 修订反复不达标、债务同维度每章复发且永不核销）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterAuditService {

    private static final BeanOutputConverter<AuditResultEntity> CONVERTER =
            new BeanOutputConverter<>(AuditResultEntity.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    private final LlmGateway llmGateway;

    /**
     * 对单章正文进行审校。
     *
     * @param storyVO      模型配置
     * @param item         本章计划
     * @param content      本章正文
     * @param ledgerPrompt 三账本渲染文本（可为 null）
     * @param foreshadowing 伏笔账文本（可为 null）
     * @param styleStat    当前风格统计（可为 null）
     * @param globalNo     全局章节号（仅用于日志）
     * @return 审校结果；解析/修复均失败时抛 AppException（质量门硬失败）
     */
    public AuditResultEntity audit(StoryVO storyVO,
                                   ChapterPlanItemEntity item,
                                   String content,
                                   String ledgerPrompt,
                                   String foreshadowing,
                                   StyleStatEntity styleStat,
                                   int globalNo) {
        return audit(storyVO, item, content, ledgerPrompt, foreshadowing, styleStat, globalNo, null);
    }

    /**
     * 带机械预警 guard 的重载：guard 为质量门机械层产出的加审提示块
     * （本章禁泄清单——LLM 判变相泄露；计划覆盖预警——低覆盖事件的加审线索），可为 null
     */
    public AuditResultEntity audit(StoryVO storyVO,
                                   ChapterPlanItemEntity item,
                                   String content,
                                   String ledgerPrompt,
                                   String foreshadowing,
                                   StyleStatEntity styleStat,
                                   int globalNo,
                                   String guard) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // maxTokens/temperature 不再硬编码，由 scene-models 的 audit 场景配置驱动
                String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                        .userPrompt(buildPrompt(item, content, ledgerPrompt, foreshadowing, styleStat, globalNo, guard))
                        .label("audit-第" + globalNo + "章")
                        .scene(ModelScene.CHAPTER_AUDIT)
                        .build());

                AuditResultEntity result = tryConvert(raw);
                if (result == null) {
                    result = JsonParseFallback.parse(raw, this::tryConvert);
                    if (result != null) {
                        log.info("章节审校经四级解析降级修复后解析成功，chapterNo: {}", globalNo);
                    }
                }
                if (result != null) {
                    int issueCount = result.getIssues() == null ? 0 : result.getIssues().size();
                    log.info("章节审校完成，chapterNo: {}, issues: {}", globalNo, issueCount);
                    verifyEvidence(result, content, globalNo);
                    return result;
                }
                log.warn("章节审校第 {}/2 次输出无法解析，chapterNo: {}", attempt, globalNo);
            } catch (Exception e) {
                log.warn("章节审校第 {}/2 次尝试异常，chapterNo: {}", attempt, globalNo, e);
            }
        }

        log.warn("章节审校彻底失败，chapterNo: {}", globalNo);
        throw new AppException(ResponseCode.UN_ERROR.getCode(),
                "第 " + globalNo + " 章审校彻底失败（含修复后重解与一次重试），质量门无法给出结论，本批终止；"
                        + "此前章节已通过逐章检查点落盘，可在请求中携带 resumeStoryDir 续写补齐后续章节");
    }

    private AuditResultEntity tryConvert(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            return null;
        }
    }

    String buildPrompt(ChapterPlanItemEntity item,
                       String content,
                       String ledgerPrompt,
                       String foreshadowing,
                       StyleStatEntity styleStat,
                       int globalNo,
                       String guard) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是小说流水线审校员。阅读本章正文，从以下七个维度发现问题，只输出结构化 issue 列表。")
                .append("\n七维度：consistency（设定一致性）、character（人设一致性）、pacing（节奏平衡）、")
                .append("continuity（叙事连贯）、foreshadow（伏笔健康）、hook（钩子质量）、aesthetic（审美品质）。")
                .append("\n\n人物与代价对照（2026-09-16 补，机械层测不了语义，靠你逐项检查）：")
                .append("\n- character：配角是否沦为工具人——只等主角触发、没有独立目标/反应/自身判断；")
                .append("主角是否无代价地独立解决一切（没有需要被说服/救助/合作的局面）")
                .append("\n- character/pacing：本章代价是否单一——全程只有物理损伤而无情感、关系、信息、机会代价")
                .append("\n- aesthetic：内心独白/自我推演是否挤压了实际互动场景（独白只在产生新决策时保留）")
                .append("\n- pacing（身份隐藏类题材）：近暴露点是否过密——身份/秘密的「差点被发现」每章超过 1 个即为过密，")
                .append("会把「还没掉马」的长程张力提前耗光；这类题材的资产正是拉锯本身")
                .append("\n- character：主角是否持续被动响应（无独立主动目标，只被他人/事件推着走）");
        // 年龄/能力边界：机械层测不了"婴儿写数论证明"。时序锚由 guard 带进来，
        // 无锚时不输出该判据（避免让模型去猜一个不存在的锚）。
        if (StringUtils.isNotBlank(guard) && guard.contains("【时序锚】")) {
            sb.append("\n- character【能力与阶段边界·对照上方时序锚，必查】：主角在本章展示的能力若超出锚记录的阶段")
                    .append("极限——动作、语言、书写、专注时长、精细操作，凡超出该阶段生理/状态常识的——按\"事实矛盾\"记 BLOCKING；")
                    .append("认知超前（理解、判断、偏好、策略性拖延）不算越界（是否允许由故事设定决定），")
                    .append("只判\"媒介\"（表达载体）是否越过阶段极限。")
                    .append("以下两类**间接展示变体同样按事实矛盾记 BLOCKING**：")
                    .append("① 主角用任何**可被他人在事后解读出具体含义**的载体传递信息——涂鸦、摆物、敲击、")
                    .append("手势、节奏、器物位置都算（如画出某题答案、摆出警告、以特定动作\"认出\"某物）")
                    .append("——载体换了，能力展示的本质没变；")
                    .append("② 观察者在**单次**观察后就从该角色行为中**确认**其掌握了具体知识或具体意图")
                    .append("（\"他算出了这道题\"\"他在警告我们\"\"他认出了那件事物\"）——允许\"觉得不寻常\"，")
                    .append("禁止当章坐实为具体结论；解读指向具体知识/事件时，须建立在多次重复之上且保留不确定口吻，否则越界。")
                    // 时序锚唯一依据实测）：审计（强制思考模型）曾幻觉出
                    // 锚中不存在的"一岁半"，把正确的四岁行为逐章判成违规——年龄判据必须逐字引用锚原文。
                    .append("**年龄数值的唯一依据是上方【时序锚】原文**：判罚涉及年龄时必须逐字引用锚中写明的数字，")
                    .append("严禁推算、改写或使用锚中不存在的年龄值（锚记\"四岁\"时，\"一岁半\"\"三岁\"等一律无效）；")
                    .append("若认为锚本身与近章剧情/账本冲突，按 MINOR 提示核对，不得按臆测的年龄记 BLOCKING；")
                    .append("同理，时间点判定也只以上方时序锚与近章摘要记载的时点为准，禁止臆造未记载的时间。")
 .append("时间线一致性 口径）：**不再验收“时间是否推进到位”**——")
                    .append("本章不跨时间、全程同一天，或连续多章同一天，都属正常，**不得**以")
                    .append("“无时间流逝痕迹”“未写过渡”记违规；")
                    .append("只查**时间线是否自相矛盾**：正文若出现与【时序锚】或近章摘要已确立时点")
                    .append("相冲突的表述（如主角已上高中却写“小学三年级”、已入秋却写“盛夏蝉鸣”），")
                    .append("按事实矛盾记 BLOCKING；未声明推进时正文出现大于 1 个月的时间跳跃且无任何过渡，")
                    .append("同样按事实矛盾记 BLOCKING（防的是“无声跳月吞线”，不是“跳月本身”）。");
        }
        // 判据段走变体开关（A/B 校准骨架，docs/enhancement-plan.md A 系列）：
        // B 分支由 A2（审计目录化 + 三态锚样例）填充，当前与 A 等价以支撑骨架自检
        sb.append(AuditPromptVariant.isB() ? criteriaVariantB() : criteriaVariantA());

        sb.append("\n\n【本章计划】")
                .append("\n标题：").append(nullToBlank(item.getTitle()))
                .append("\n目标：").append(nullToBlank(item.getGoal()))
                .append("\n关键事件：").append(joinList(item.getKeyEvents()))
                .append("\n结尾悬念：").append(nullToBlank(item.getEndingHook()))
                .append("\n章节类型：").append(item.getChapterType() == null ? "normal" : item.getChapterType().getCode());

        if (StringUtils.isNotBlank(ledgerPrompt)) {
            sb.append("\n\n【当前账本】\n").append(ledgerPrompt);
        }
        if (StringUtils.isNotBlank(foreshadowing)) {
            sb.append("\n\n【待回收伏笔】\n").append(foreshadowing);
        }

        String styleStatText = renderStyleStat(styleStat);
        if (StringUtils.isNotBlank(styleStatText)) {
            sb.append("\n\n【风格统计警示】\n").append(styleStatText);
        }

        // 机械预警 guard（禁泄清单/计划覆盖预警）：置在正文之前，判定口径见各块自带说明
        if (StringUtils.isNotBlank(guard)) {
            sb.append("\n\n").append(guard);
        }

        sb.append("\n\n【本章正文】\n").append(content)
                .append("\n\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat());

        return sb.toString();
    }

    /**
     * 现行判据段（变体 A，默认）：severity 标准 + 输出要求，与历史 prompt 逐字一致
     */
    private String criteriaVariantA() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nseverity 判定标准（必须严格遵守，宁可漏报不可错报）：");
        appendSeverityStandards(sb);
        sb.append("\n\n输出要求：");
        appendOutputRequirements(sb, false);
        return sb.toString();
    }

    /**
     * 实验判据段（变体 B，{@code -Daudit.prompt.variant=B}，A2 目录化）：
     * 在 A 的 severity 标准之上，把七维度从"抽象名词"升级为<b>编号判据目录 + 三态锚样例</b>——
     * 把模型从"自由裁量"变成"对号入座"：MINOR 必须标注触发的判据编号，颗粒度才可对齐、
     * 噪声（AuditEvalHarnessTest 的 flip rate）才可按编号归因。样例全部一句话、borderline 钉死 MINOR。
     * 判据语义与 severity 白名单严格对齐：BLOCKING 仍仅限四类硬伤，目录不新增 BLOCKING 通道。
     */
    private String criteriaVariantB() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n七维度判据目录（逐维度对照编号扫描；MINOR 的 description 末尾必须标注触发的判据编号，")
                .append("如（C1）；无法归入任何编号的问题标（其他），默认 MINOR）：");
        sb.append("\nconsistency（设定一致性）")
                .append("\n- C1 与账本直接冲突：正文陈述与【当前账本】条目矛盾（位置/持有物/伤势/境界/生死/在场）")
                .append("\n- C2 变化无过程：账本标注了\"第N章变化：旧→新\"的状态，正文缺少对应的过程描写")
                .append("\n- C3 口径漂移：数字、称谓、组织名、地名与前文既定口径不一致")
                .append("\n  样例：通过=正文\"他把断刀插回鞘中\"且账本\"断刀：随身携带\"；")
                .append("边界=正文\"旧伤隐隐作痛\"而账本\"已痊愈\"，但同段有\"伤未真正愈合\"的铺垫 → MINOR(C1)；")
                .append("不合格=正文\"她第一次踏入北境\"而前文已两次写她驻守北境 → BLOCKING(C1)");
        sb.append("\ncharacter（人设一致性）")
                .append("\n- H1 人设越线：角色言行与既定身份/性格/能力边界冲突且无情节支撑")
                .append("\n- H2 工具人：有名字的配角整章只响应主角，无独立目标/反应/自身判断")
                .append("\n- H3 主角单机：主角无代价地独立解决全部问题，全章无一个必须依靠他人的局面")
                .append("\n- H4 被动主角：主角整章只被事件或他人推动，无主动目标")
                .append("\n  样例：通过=配角为自身立场主动隐瞒关键情报；")
                .append("边界=配角仅露面一次、反应符合立场但无主动动作 → MINOR(H2)；")
                .append("不合格=主角被围杀整章只等待救援、无任何自救尝试 → MINOR(H4)（强）");
        sb.append("\npacing（节奏平衡）")
                .append("\n- P1 过渡缺失：紧接上一章生死危机，本章无整理/缓冲段落（对照计划 chapterType）")
                .append("\n- P2 暴露点过密（身份隐藏类）：本章\"差点被发现\"超过 1 次")
                .append("\n- P3 代价单一：本章全部代价都是物理损伤，无情感/关系/信息/机会类")
                .append("\n  样例：通过=危机后有一段整理线索的静场；")
                .append("边界=全程紧绷但保留一段缓冲对话 → MINOR(P1)；")
                .append("不合格=连续第 3 章以死战开篇且无任何缓冲 → MINOR(P1)（强）");
        sb.append("\ncontinuity（叙事连贯）")
                .append("\n- N1 场景跳变：时间/地点切换无过渡句，相邻段落无法建立时空连续性")
                .append("\n- N2 动作断裂：上一拍\"拔剑\"下一拍\"收剑入鞘\"，中间的交锋过程缺失")
                .append("\n- N3 指代悬空：代词/称谓的所指需要读者回翻两段以上才能确定")
                .append("\n  样例：通过=\"翌日清晨，他推开宗门大门\"衔接新场景；")
                .append("边界=场景切换只有半句过渡、需读者猜测时序 → MINOR(N1)；")
                .append("不合格=上段\"拔剑\"下段已\"还剑入鞘\"、交锋整段缺失 → MINOR(N2)（强）");
        sb.append("\nforeshadow（伏笔健康）")
                .append("\n- F1 变相泄底：未直接命中【本章禁泄清单】关键词，但线索组合足以让读者推出谜底")
                .append("（关键词直泄由机械层拦截为 BLOCKING，此处只判变相泄露）")
                .append("\n- F2 回收缺过程：计划标注\"回收第N章XX\"的事件，正文直接给出谜底而无连接铺垫")
                .append("\n- F3 新钩无锚：结尾抛出新谜团，但正文没有任何建立该谜团的细节")
                .append("\n  样例：通过=回收伏笔时先重现当年物证再揭谜底；")
                .append("边界=回收过程压缩到一句带过但因果可循 → MINOR(F2)；")
                .append("不合格=第N章伏笔在本章对话中被一句闲谈无意揭穿 → MINOR(F1)（强）");
        sb.append("\nhook（钩子质量）")
                .append("\n- K1 结尾冲突/未触及：正文结尾与计划结尾悬念明显冲突或完全未触及 → BLOCKING")
                .append("\n- K2 总结式收尾：结尾停在感悟/总结/金句，而非画面或动作")
                .append("\n- K3 钩子弱化：有未决点但力度不足（\"他想了很久\"\"一切尚未结束\"式）")
                .append("\n  样例：通过=结尾停在\"信使的手按上了城门的铁环\"；")
                .append("边界=结尾有未决点但落在心理活动上 → MINOR(K3)；")
                .append("不合格=结尾\"从此，他踏上了新的旅程\"式总结 → MINOR(K2)（强）");
        sb.append("\naesthetic（审美品质）")
                .append("\n- A1 定义旁白：\"这不是X，而是Y\"式定义句及其排比")
                .append("\n- A2 情绪直述：用\"感到愤怒/悲伤/震惊\"命名情绪，而非动作呈现")
                .append("\n- A3 独白挤压：连续超过 3 句内心独白且不产生新决策或新信息")
                .append("\n- A4 术语/说教密度：连续 2 句以上的设定原理复述、现代术语堆砌或说明书口吻讲机制")
                .append("（对照【当前账本】机制条目：金手指机制全篇至多 2 次原理描述，重复解释即命中）")
                .append("\n  样例：通过=\"他攥紧刀柄，指节泛白\"以动作呈现愤怒；")
                .append("边界=1-2 句情绪命名但紧接动作补救 → MINOR(A2)；")
                .append("不合格=连续 5 句\"他感到恐惧，恐惧中带着愤怒，愤怒之下是委屈\" → MINOR(A3)；")
                .append("说明书腔复述金手指原理 → MINOR(A4)（强）");
        sb.append("\n扫描纪律：逐维度对照编号检查后再输出；同一现象命中多条编号时取最具体的一条；宁漏勿滥。");
        sb.append("\n\nseverity 判定标准（必须严格遵守，宁可漏报不可错报）：");
        appendSeverityStandards(sb);
        sb.append("\n\n输出要求：");
        appendOutputRequirements(sb, true);
        return sb.toString();
    }

    /** severity 标准（A/B 共享，与历史逐字一致）：BLOCKING 四类白名单 + MINOR 界定 + 降级规则 + 数量锚点 */
    private void appendSeverityStandards(StringBuilder sb) {
        sb.append("\n- BLOCKING：仅限以下四类硬伤，且必须能在正文中指出确切位置——")
                .append("\n  1) 事实矛盾：与账本/前文事实直接冲突（状态、位置、持有物、境界等前后不一致）；")
                .append("\n     账本中标注了状态变化轨迹（第N章变化：旧 → 新）的，该变化必须在正文中有对应的过程描写，")
                .append("凭空变化（伤势无端消失、位置无端转移、死者复活等）同样按事实矛盾处理；")
                .append("\n     人物在场状态：同一连续场景（同一天/同一夜/同一地点）内，角色离场后又出场、")
                .append("或出场位置与账本最后记录不符，而正文无归来/折返等过渡描写的，按事实矛盾处理——")
                .append("账本当前状态自洽不等于豁免，离场又出场必须有过场交代；")
                .append("\n  2) 关键事件缺失：本章计划列出的关键事件在正文中完全未出现；")
                .append("\n  3) endingHook 未呼应：正文结尾与计划的结尾悬念指向明显冲突或完全未触及；")
                .append("\n  4) 视角越权：叙事越出设定的视角约束。")
                .append("\n- MINOR：一切程度性问题——铺垫不足、钩子力度偏弱、衔接略跳、节奏偏快、表述不够精准、")
                .append("临场冲击被削弱、细节交代不充分、文风与疲劳词等。只记录，不触发修订。")
                .append("\n- 降级规则：关键事件出现了但展开不足/衔接偏快 = MINOR，不算缺失；")
                .append("钩子有呼应但力度或明确度不足 = MINOR，不算未呼应；")
                .append("\"-虽不算硬矛盾，但……\"式的观察一律 = MINOR。")
                .append("\n- 数量锚点：BLOCKING 通常为 0~1 条；判不准时一律降为 MINOR。")
                .append("\n- 年龄/能力越界归入第 1 类\"事实矛盾\"（不是新的第五类）：当【时序锚】明确了主角年龄/阶段，")
                .append("而正文让他展示超出该阶段生理/状态极限的表达（动作、语言、书写、专注时长），")
                .append("或用可被他人在事后解码出具体含义的载体间接传递信息时，都是与既成事实直接冲突，记 BLOCKING；认知超前不算。");
    }

    /**
     * 输出要求（A/B 共享骨架）：catalogMode 时追加判据编号标注规则——
     * 没有编号归因的 MINOR 无法跨次对齐（A1 噪声报告按编号归因的前提）
     */
    private void appendOutputRequirements(StringBuilder sb, boolean catalogMode) {
        sb.append("\n1. 只输出 JSON，不要 Markdown，不要解释。")
                .append("\n2. 每条 issue 必须包含：dimension、severity、evidence（逐字摘自本章正文的原文引用，")
                .append("≤80 字，中间省略用……）、description、suggestion。")
                .append("\n3. 如果没有问题，issues 为空数组。")
                .append("\n4. 禁止捏造问题；给不出原文证据的问题要么不报，要么标为 MINOR。");
        if (catalogMode) {
            sb.append("\n5. MINOR 的 description 末尾必须标注触发的判据编号（如\"……（P1）\"）；")
                    .append("无法归入目录的问题标（其他），默认 MINOR。");
        }
    }

    /**
     * BLOCKING 证据校验：evidence 必须能在正文中找到（容忍空白/换行差异、引号形态、省略号截断），
     * 未命中或空证据一律降级 MINOR。只降 BLOCKING——MINOR 不触发任何链路，无需校验
     */
    void verifyEvidence(AuditResultEntity result, String content, int globalNo) {
        if (result == null || result.getIssues() == null || StringUtils.isBlank(content)) {
            return;
        }
        String normalizedContent = EvidenceMatch.normalize(content);
        for (ChapterIssueEntity issue : result.getIssues()) {
            if (issue == null || !"BLOCKING".equalsIgnoreCase(issue.getSeverity())) {
                continue;
            }
            // 用「分段多数命中」而非整串包含 改）：模型的引用习惯——句首补主语
            //（正文「他将…」→引文「陈默将…」）、丢一个前置分句、或用省略号把两处原文拼起来——
            // 会破坏"整串连续"，但那些引文仍是正文里真实存在的片段。整串判定会把**整条 BLOCKING
            // 降级成 MINOR**（不进修订、不落债）：实测第 6-17 章有 8 条因此被压掉。
            // 新口径仍拒收「纯账本摘录」「转述式引文」——它们没有可锚定的正文长段。
            if (EvidenceMatch.segmentsContained(issue.getEvidence(), normalizedContent)) {
                continue;
            }
            // 200 而非 50：50 字会把引文截在句中，事后无法核对"这条降级究竟是误伤还是真没证据"——
            // 复盘第 6-17 章时正因为截断而无法判断
            issue.setSeverity("MINOR");
            log.info("第 {} 章 BLOCKING 证据校验未通过，已降级 MINOR（{}）：{}",
                    globalNo, issue.getDimension(), StringUtils.abbreviate(issue.getEvidence(), 200));
        }
    }

    /** 修订验证输出：verdicts 与传入的 BLOCKING 列表按 index（1 起）对齐 */
    public static class FixCheckOutput {
        private List<FixVerdict> verdicts;

        public List<FixVerdict> getVerdicts() {
            return verdicts;
        }

        public void setVerdicts(List<FixVerdict> verdicts) {
            this.verdicts = verdicts;
        }

        /** 单条验证结论：fixed 判定不需要证据（修复=问题不再出现，是缺席证明）；UNFIXED 的 evidence 引修订稿中问题仍在的位置 */
        public record FixVerdict(int index, boolean fixed, String evidence) {
        }
    }

    /**
     * 修订验证结果：{@code unfixed} 是"仍未修复"的子集，{@code degraded} 表示**这次验证根本没跑成**。
     *
     * <p>把两者分开是必须的（2026-09-30）：原先"验证失败"被并入"未修复"，
     * 于是**基础设施抖动（网关异常/超时）会被记成质量债**，而质量债会回灌给写手当作
     * "你上一章犯的错"——那是给模型下错误指令。验证没跑成 ≠ 没修好。
     *
     * <p>保守偏向不变：{@code degraded=true} 时 {@code unfixed} 仍原样带回上一轮全部 BLOCKING
     * （不放行未验证的稿），差别只在于调用方**不得把它记成质量债**。
     */
    public record FixVerification(List<ChapterIssueEntity> unfixed, boolean degraded) {
        static FixVerification verified(List<ChapterIssueEntity> unfixed) {
            return new FixVerification(unfixed, false);
        }
    }

    private static final BeanOutputConverter<FixCheckOutput> FIX_CONVERTER =
            new BeanOutputConverter<>(FixCheckOutput.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    /**
     * 修订稿复审（A3 验证化，docs/enhancement-plan.md）：不再全章重审——开放任务方差大，
     * 二轮翻案即"修订拉锯"。改为逐条验证上一轮 BLOCKING 是否已修复：任务窄、判据明，
     * 判定稳定性远高于开放式重审。
     *
     * <p>保守语义：<b>未确认已修复 = 仍未修复</b>——模型输出缺条目、验证调用解析失败
     * 均按 UNFIXED 处理并留痕（复核的失败模式应偏向"多修一轮"而非"漏放 BLOCKING"，
     * 与审校侧"宁可漏报不可错报"是同一条保守哲学在复核层的镜像）。
     * 返回仍未修复的子集（原 issue 对象原样返回）；全部修复返回空列表。
     *
     * <p>失败语义（2026-09-30 修正，此前文档与实现相反）：**不抛异常**。两次尝试均无法解析、
     * 或调用本身异常时，返回 {@code degraded=true} 并原样带回全部 BLOCKING——内容侧仍按
     * "视为未修复"保守处理（不放行未验证的稿），但调用方必须把"未验证"与"确认未修复"分开，
     * **不得记入质量债**（记债会把基础设施抖动写成内容缺陷，再回灌给写手）。
     * 此前这里写着"与 {@link #audit} 一致抛 AppException 终止本批"，与实现不符，已按实现改正。
     */
    public FixVerification verifyFixes(StoryVO storyVO,
                                       ChapterPlanItemEntity item,
                                       String revisedContent,
                                       List<ChapterIssueEntity> blocking,
                                       String ledgerPrompt,
                                       StyleStatEntity styleStat,
                                       int globalNo) {
        if (blocking == null || blocking.isEmpty()) {
            return FixVerification.verified(List.of());
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // 温度/模型由 scene-models 的 audit 场景配置驱动（与首审同一模型族）
                String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                        .userPrompt(buildFixVerifyPrompt(blocking, revisedContent, ledgerPrompt, item))
                        .label("audit-verify-第" + globalNo + "章")
                        .scene(ModelScene.CHAPTER_AUDIT)
                        .build());
                FixCheckOutput output = FIX_CONVERTER.convert(raw);
                if (output == null || output.getVerdicts() == null) {
                    output = JsonParseFallback.parse(raw, FIX_CONVERTER::convert);
                    if (output != null) {
                        log.info("修订验证经解析降级修复后成功，chapterNo: {}", globalNo);
                    }
                }
                if (output != null && output.getVerdicts() != null) {
                    List<ChapterIssueEntity> unfixed = resolveUnfixed(blocking, output.getVerdicts(), revisedContent, globalNo);
                    log.info("修订验证完成，chapterNo: {}, 待验证 {} 条，未修复 {} 条",
                            globalNo, blocking.size(), unfixed.size());
                    return FixVerification.verified(unfixed);
                }
                log.warn("修订验证第 {}/2 次输出无法解析，chapterNo: {}", attempt, globalNo);
            } catch (Exception e) {
                log.warn("修订验证第 {}/2 次尝试异常，chapterNo: {}", attempt, globalNo, e);
            }
        }
        // 验证没跑成 ≠ 没修好：内容侧仍保守（把上一轮 BLOCKING 原样带回、不放行未验证稿），
        // 但以 degraded 标记与"确认未修复"区分开，调用方据此不落质量债。
        // 可 grep AUDIT_VERIFY_DEGRADED 定位本批哪些章是"未验证"而非"未修复"
        log.warn("AUDIT_VERIFY_DEGRADED 修订验证彻底失败，chapterNo: {}，"
                + "按保守处理带回 {} 条 BLOCKING（标记为【未验证】，不得记入质量债）",
                globalNo, blocking.size());
        return new FixVerification(new ArrayList<>(blocking), true);
    }

    /** 未确认已修复 = 仍未修复：缺条目按 UNFIXED；UNFIXED 证据校验失败不降级（会漏放 BLOCKING），仅留痕 */
    private List<ChapterIssueEntity> resolveUnfixed(List<ChapterIssueEntity> blocking,
                                                    List<FixCheckOutput.FixVerdict> verdicts,
                                                    String revisedContent, int globalNo) {
        Map<Integer, FixCheckOutput.FixVerdict> byIndex = new java.util.HashMap<>();
        for (FixCheckOutput.FixVerdict verdict : verdicts) {
            if (verdict != null && verdict.index() > 0) {
                byIndex.put(verdict.index(), verdict);
            }
        }
        List<ChapterIssueEntity> unfixed = new ArrayList<>();
        for (int i = 0; i < blocking.size(); i++) {
            ChapterIssueEntity issue = blocking.get(i);
            FixCheckOutput.FixVerdict verdict = byIndex.get(i + 1);
            if (verdict != null && verdict.fixed()) {
                log.info("第 {} 章复核确认已修复（{}）：{}", globalNo,
                        nullToBlank(issue.getDimension()),
                        StringUtils.abbreviate(StringUtils.defaultString(issue.getDescription()), 60));
                continue;
            }
            if (verdict != null && StringUtils.isNotBlank(verdict.evidence())) {
                String normalizedContent = EvidenceMatch.normalize(revisedContent);
                if (!EvidenceMatch.segmentsContained(verdict.evidence(), normalizedContent)) {
                    log.info("第 {} 章复核 UNFIXED 证据未命中修订稿（按仍未修复处理，留痕）：{}",
                            globalNo, StringUtils.abbreviate(verdict.evidence(), 100));
                }
            }
            unfixed.add(issue);
        }
        return unfixed;
    }

    private String buildFixVerifyPrompt(List<ChapterIssueEntity> blocking, String revisedContent,
                                        String ledgerPrompt, ChapterPlanItemEntity item) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是审校复核员。修订稿声称已修复以下 ").append(blocking.size())
                .append(" 条 BLOCKING 问题。逐条核对修订稿，判定每条是否已修复。");
        for (int i = 0; i < blocking.size(); i++) {
            ChapterIssueEntity issue = blocking.get(i);
            sb.append("\n").append(i + 1).append(". 【").append(nullToBlank(issue.getDimension()))
                    .append("】").append(nullToBlank(issue.getDescription()));
            if (StringUtils.isNotBlank(issue.getEvidence())) {
                sb.append("\n   原问题证据：").append(issue.getEvidence());
            }
        }
        sb.append("\n\n判定口径：")
                .append("\n- fixed=true：修订稿中该问题已不再成立（事实已改正/关键事件已补足/结尾悬念已呼应/视角已收拢）。修复不需要证据；拿不准一律 false。")
                .append("\n- fixed=false：问题仍然成立或只被部分修复。此时 evidence 必须逐字摘自修订稿中问题仍然存在的位置（≤80 字，中间省略用……）。")
                .append("\n- 只验证列出的条目，不要检查其他内容，不要新增问题，不要改变条目顺序。")
                .append("\n\n【本章计划】")
                .append("\n目标：").append(nullToBlank(item.getGoal()))
                .append("\n关键事件：").append(joinList(item.getKeyEvents()))
                .append("\n结尾悬念：").append(nullToBlank(item.getEndingHook()));
        if (StringUtils.isNotBlank(ledgerPrompt)) {
            sb.append("\n\n【当前账本】\n").append(ledgerPrompt);
        }
        sb.append("\n\n【修订稿】\n").append(revisedContent)
                .append("\n\n请严格按照以下 JSON 格式输出：\n").append(FIX_CONVERTER.getFormat());
        return sb.toString();
    }

    private String renderStyleStat(StyleStatEntity styleStat) {
        if (styleStat == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> repeated = styleStat.getRepeatedSentences();
        if (repeated != null && !repeated.isEmpty()) {
            sb.append("跨章重复句（严禁本章再次逐字使用）：\n");
            repeated.stream()
                    .skip(Math.max(0, repeated.size() - 10))
                    .forEach(s -> sb.append("- ").append(s).append("\n"));
        }
        if (styleStat.getFatigueWords() != null && !styleStat.getFatigueWords().isEmpty()) {
            List<Map.Entry<String, Integer>> overused = styleStat.getFatigueWords().entrySet().stream()
                    .filter(e -> e.getValue() >= 5)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toList());
            if (!overused.isEmpty()) {
                sb.append("疲劳词超频（本章禁用或至多出现一次）：\n");
                overused.forEach(e -> sb.append("- ").append(e.getKey()).append("(").append(e.getValue()).append("次)\n"));
            }
        }
        return sb.length() == 0 ? "" : sb.toString().trim();
    }

    private String joinList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join("、", list);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

}
