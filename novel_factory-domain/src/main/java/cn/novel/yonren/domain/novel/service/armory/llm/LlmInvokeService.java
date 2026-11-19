package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptBuilder;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.DynamicReferenceService;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import jakarta.annotation.Resource;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用服务：统一封装动态规则注入与模型调用编排。
 * 节点只负责组装业务 prompt，不再关心模型怎么建、规则怎么加载；
 * 模型访问经 LlmGateway 端口完成，供应商细节由 infrastructure 承担。
 * 规则来源两路：确定性策略（PromptRuleStrategy 体系）+ skill 式动态资料（按需选择）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmInvokeService {

    private final PromptBuilder promptBuilder;
    private final LlmGateway llmGateway;
    private final DynamicReferenceService dynamicReferenceService;

    @Resource
    private JobRegistry jobRegistry;

    /**
     * 调用大模型：按场景动态注入规则
     *
     * @param storyVO      yml 配置（api/模型参数）
     * @param scene        场景（大纲/正文）
     * @param ctx          装配上下文（风格关键词、章节进度等）
     * @param userPrompt   节点组装好的业务 prompt
     * @param usedPromptMap 本次调用实际使用的 prompt 汇总（含 system + user + 注入规则），可为 null
     * @return 模型返回的文本
     */
    public String invoke(StoryVO storyVO, PromptScene scene, PromptContext ctx, String userPrompt, Map<String, String> usedPromptMap) {
        return invokeWithScene(storyVO, scene, ctx, userPrompt, usedPromptMap, toModelScene(scene), null, null);
    }

    /**
     * 显式指定模型路由的重载：复用同一 PromptScene 的全部规则注入与 prompt 装配，
     * 仅替换模型场景/label/单次 maxTokens——供"同 prompt 异模型"场景使用
     * （候选选优：正文规则注入不变，路由到第二模型族 chapter-judge 重写候选）
     *
     * @param modelScene       模型路由场景；null 走统一 chat-model
     * @param labelOverride    usage 记账 label；null 时按 scene 自动生成
     * @param maxTokensOverride 单次输出上限覆盖；null 用场景 yml 配置
     */
    public String invokeWithScene(StoryVO storyVO, PromptScene scene, PromptContext ctx, String userPrompt,
                                  Map<String, String> usedPromptMap, ModelScene modelScene,
                                  String labelOverride, Integer maxTokensOverride) {
        if (jobRegistry != null && !jobRegistry.tryBeginReferenceUse()) {
            throw new IllegalStateException("资料索引正在维护，请稍后再调用模型");
        }
        try {
            return invokePrepared(storyVO, scene, ctx, userPrompt, usedPromptMap, modelScene,
                    labelOverride, maxTokensOverride);
        } finally {
            if (jobRegistry != null) jobRegistry.endReferenceUse();
        }
    }

    private String invokePrepared(StoryVO storyVO, PromptScene scene, PromptContext ctx, String userPrompt,
                                  Map<String, String> usedPromptMap, ModelScene modelScene,
                                  String labelOverride, Integer maxTokensOverride) {
        StoryVO.Module module = storyVO.getModule();

        // 动态规则装配：确定性策略 + skill 式动态资料选择
        List<PromptRule> rules = new ArrayList<>(promptBuilder.collectRules(scene, ctx));
        rules.addAll(withoutAlreadyInjected(rules, dynamicReferenceService.selectAsRules(module, scene, ctx)));

        String systemText = joinPosition(rules, PromptRule.InjectPosition.SYSTEM);
        String userTail = joinPosition(rules, PromptRule.InjectPosition.USER_TAIL);

        String finalUserPrompt = userPrompt;
        if (!userTail.isBlank()) {
            finalUserPrompt = userPrompt + "\n\n==== 交付与事实约束优先；技法、风格和参考资料按场景取用，不新增剧情任务或数量配额 ====\n" + userTail;
        }

        String label = labelOverride != null ? labelOverride
                : scene.name().toLowerCase().replace('_', '-')
                + (ctx.getChapterNo() != null ? "-第" + ctx.getChapterNo() + "章" : "");
        String content = llmGateway.complete(module, LlmCall.builder()
                .systemPrompt(systemText.isBlank() ? null : systemText)
                .userPrompt(finalUserPrompt)
                .label(label)
                .scene(modelScene)
                .maxTokens(maxTokensOverride)
                .build());
        log.info("LLM 调用完成，scene: {}, 模型场景: {}, 注入规则数: {}", scene, modelScene, rules.size());

        // 记录实际使用的 prompt（复盘用）
        if (usedPromptMap != null) {
            String prefix = scene == PromptScene.CHAPTER_CONTENT && ctx.getChapterNo() != null
                    ? "chapter-" + ctx.getChapterNo() + ":" : "";
            if (!systemText.isBlank()) {
                usedPromptMap.put(prefix + "system", systemText);
            }
            usedPromptMap.put(prefix + "user", finalUserPrompt);
            for (PromptRule rule : rules) {
                usedPromptMap.put(prefix + "rule:" + rule.getName(), "已注入（" + rule.getInjectPosition() + "）");
            }
        }

        return content;
    }

    /** 将提示词场景映射到模型路由场景，null 表示使用全局模型。 */
    private ModelScene toModelScene(PromptScene scene) {
        if (scene == null) {
            return null;
        }
        return switch (scene) {
            case STAGE_BLUEPRINT -> ModelScene.STAGE_BLUEPRINT;
            case VOLUME_BLUEPRINT -> ModelScene.STAGE_BLUEPRINT;
            case CHAPTER_PLAN -> ModelScene.CHAPTER_PLAN;
            case CHAPTER_CONTENT -> ModelScene.CHAPTER_CONTENT;
            case CHAPTER_AUDIT -> ModelScene.CHAPTER_AUDIT;
            case CHAPTER_REVISE -> ModelScene.CHAPTER_REVISE;
            case SETTING_DRAFT -> null;
        };
    }

    private String joinPosition(List<PromptRule> rules, PromptRule.InjectPosition position) {
        StringBuilder sb = new StringBuilder();
        for (PromptRule rule : rules) {
            if (rule.getInjectPosition() != position) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append("【").append(rule.getName()).append("】\n").append(rule.getContent());
        }
        return sb.toString();
    }

    /**
     * 固定资料已覆盖同一资源时，丢弃没有新增内容的动态副本。
     *
     * 只在动态内容整体包含于已注入的固定规则时跳过；固定资料只抽了小节、
     * 动态副本仍含其他有效内容时保留，避免按文件名模糊去重误删。
     */
    private static List<PromptRule> withoutAlreadyInjected(List<PromptRule> fixed, List<PromptRule> dynamic) {
        if (fixed == null || fixed.isEmpty() || dynamic == null || dynamic.isEmpty()) {
            return dynamic == null ? List.of() : dynamic;
        }
        String fixedText = fixed.stream().filter(java.util.Objects::nonNull)
                .map(PromptRule::getContent).filter(java.util.Objects::nonNull)
                .map(LlmInvokeService::normalizeForOverlap)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (fixedText.isEmpty()) {
            return dynamic;
        }
        List<PromptRule> kept = new ArrayList<>();
        for (PromptRule rule : dynamic) {
            if (rule == null || rule.getContent() == null) {
                continue;
            }
            String referenceBody = referenceBody(rule.getContent());
            String body = normalizeForOverlap(referenceBody);
            if (!body.isEmpty() && fixedText.contains(body)) {
                log.debug("动态资料与已注入固定资料内容相同，跳过：{}", rule.getName());
                continue;
            }
            kept.add(rule);
        }
        return kept;
    }

    /**
     * 块检索会在正文外附加“参考摘录”通知与来源标签；去重针对被引用的资料正文，
     * 不要求整个包装串也出现在固定资料里，否则同一资料的块副本永远不会被识别。
     */
    private static String referenceBody(String content) {
        String body = content;
        String notice = cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceBlockPackager.REFERENCE_NOTICE;
        if (body.startsWith(notice)) body = body.substring(notice.length());
        return body.replaceAll("(?m)^【[^】\\r\\n]+】\\s*$", "");
    }

    private static String normalizeForOverlap(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

}
