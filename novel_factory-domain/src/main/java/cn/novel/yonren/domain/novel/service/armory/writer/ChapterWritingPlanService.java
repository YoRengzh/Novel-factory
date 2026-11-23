package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.contract.ChapterContract;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException;
import cn.novel.yonren.domain.novel.service.armory.plan.ScenePlanPolicy;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterWritingPlanService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LlmGateway llmGateway;

    public ChapterWritingPlan prepare(StoryVO story, ChapterPlanItemEntity item, int chapterNo,
                                      String planningContext, List<String> unrevealedKeywords) {
        String prompt = buildPrompt(item, chapterNo, planningContext);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = llmGateway.complete(story.getModule(), LlmCall.builder()
                        .label("writing-plan-第" + chapterNo + "章").scene(ModelScene.CHAPTER_BEATS)
                        .userPrompt(prompt + (attempt == 1 ? "" : "\n请核对 JSON、场景编号和未到揭晓时机的信息。"))
                        .build());
                ChapterWritingPlan plan = JsonParseFallback.parse(raw, this::read);
                if (valid(plan, sceneCount(item), unrevealedKeywords)) {
                    return plan;
                }
                log.warn("第 {} 章正文材料第 {}/2 次未通过场景覆盖或禁泄检查", chapterNo, attempt);
            } catch (Exception e) {
                LlmLocalRejectionException.rethrowIfPresent(e);
                log.warn("第 {} 章正文材料第 {}/2 次整理失败", chapterNo, attempt, e);
            }
        }
        throw new AppException(ResponseCode.UN_ERROR.getCode(),
                "第 " + chapterNo + " 章正文材料整理失败。此前已保存的章节可继续续写。");
    }

    private ChapterWritingPlan read(String raw) {
        try {
            return JSON.readValue(raw, ChapterWritingPlan.class);
        } catch (Exception invalid) {
            return null;
        }
    }

    static boolean valid(ChapterWritingPlan plan, int sceneCount, List<String> forbidden) {
        if (plan == null || plan.sections() == null || plan.sections().isEmpty()) {
            return false;
        }
        int next = 1;
        for (var section : plan.sections()) {
            if (section == null || StringUtils.isBlank(section.viewpoint())
                    || section.scenes() == null || section.scenes().isEmpty()) {
                return false;
            }
            for (var scene : section.scenes()) {
                if (scene == null || scene.number() != next++ || StringUtils.isBlank(scene.events())) {
                    return false;
                }
            }
            String text = section.render();
            if (forbidden != null && forbidden.stream().filter(StringUtils::isNotBlank).anyMatch(text::contains)) {
                return false;
            }
        }
        return next == sceneCount + 1;
    }

    private static int sceneCount(ChapterPlanItemEntity item) {
        if (Integer.valueOf(2).equals(item.getSchemaVersion()) && item.getScenes() != null) {
            return Math.max(1, (int) item.getScenes().stream().filter(Objects::nonNull).count());
        }
        return Math.max(1, item.getKeyEvents() == null ? 0 : item.getKeyEvents().size());
    }

    String buildPrompt(ChapterPlanItemEntity item, int chapterNo, String planningContext) {
        StringBuilder prompt = new StringBuilder("""
                你负责整理小说第 %d 章交给正文写手的材料。你能看完整计划，写手每次只能看一个 section。
                1. 保留本章真实要写的行动、对方可见的回应、首次得到的证据、实际结果和结尾落点。优先写具体来意、处境和选择。
                2. viewpoint 按作者视角指定本段跟谁看。entry 简洁记录衔接状态、这个人已亲历或听说的前情、已有判断及确信程度，以及本段确实用到的世界规则或称呼。把猜测和听闻标清。无依据的旧事和人物内心留空。保留原计划的人物称呼、物件、地点、数字和关键证据。只有原文已经证实两种称呼指向同一人才能合并，职位相近、动作相似或同处一地都不能作为依据。缺少明确来源的制度、身份和关系留空。
                3. events 只写当前视角能经历的事件。其他人的私下理由用计划中已有的可见言行承载。只保留作者已安排的线索，别为后续秘密添暗示。尚未确认精确规则时留可观察的现象，隐藏的数值和原因等有证据再交给写手。计划功能、未来方向、保密答案和跨章目标都留在这里。场景引用只采纳「核验过的场景引用」中的条目。
                4. 普通章给一个 section。若本章中途才揭开会影响前面写法的答案，从能得到证据的那个场景起开新 section。主视角实质变化也可开启新段。前段连后段的答案和总结都不能收到。后段 entry 只给本章开场前已成立且仍必要的材料，本章内已经写出的事由写手从实际前文接续。作者明确允许全知、切镜或读者先知时，按原叙事范围提供材料，人物仍按自己的所知行动。
                5. 本章有 %d 项。原顺序每项恰好写一个 scene，编号从 1 开始连续。普通对话只交代要谈的事和结果，出入口、连续交锋或推理有必要时写清因果顺序。保留原计划的事实与具体选择，写手自己措辞和安排节奏。不要写示范正文、比喻、固定动作或事后总结。

                只输出 JSON，格式为 {"sections":[{"viewpoint":"视角与范围","entry":"入场状态","scenes":[{"number":1,"events":"本场发生的事"}]}]}。
                """.formatted(chapterNo, sceneCount(item)));
        prompt.append("\n【仅供规划的背景】\n").append(StringUtils.defaultString(planningContext))
                .append("\n【本章计划】\n").append(StringUtils.defaultString(item.getGoal()));
        if (Integer.valueOf(2).equals(item.getSchemaVersion()) && item.getScenes() != null && !item.getScenes().isEmpty()) {
            prompt.append(ScenePlanPolicy.render(item.getScenes()));
        } else if (item.getKeyEvents() != null) {
            for (int i = 0; i < item.getKeyEvents().size(); i++) {
                prompt.append("\n事件 ").append(i + 1).append("：").append(item.getKeyEvents().get(i));
            }
        }
        return prompt.append("\n结尾：").append(StringUtils.defaultString(item.getEndingHook()))
                .append("\n本章人物：").append(item.getCharacters())
                .append(ChapterContract.renderKnowledgeProgress(item)).toString();
    }
}
