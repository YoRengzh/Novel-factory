package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException;
import cn.novel.yonren.types.enums.ModelScene;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/** 显式APPLY的有界设计调用；模型输出只授予设计来源，不授予事实或锁定权。 */
@Service
public class CharacterDecisionDesignService {
    @Resource private LlmGateway gateway;
    public record Result(List<CharacterDecisionProfile> profiles, String diagnostic) { }

    public Result design(ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext context, List<String> names) {
        if (names.isEmpty()) return new Result(List.of(), "NO_KNOWN_CORE");
        String prompt = "为明确人物生成决策设计，不能改写设定或把设计当成已发生事实。只输出JSON："
                + "{\"profiles\":[{\"displayName\":\"名单中的原名\",\"want\":\"欲望\",\"need\":\"内在需要\","
                + "\"fearAndDefense\":\"恐惧与防御\",\"valuePriorities\":\"价值排序\",\"boundaries\":\"行为底线\","
                + "\"independentConcerns\":\"独立牵挂\",\"decisionPattern\":\"如何选择\"}]}。"
                + "不强制全员成长、代价、秘密或人际冲突。姓名名单：" + JSON.toJSONString(names)
                + "\n故事设定：" + context.getStoryContext();
        for (int attempt = 0; attempt < 2; attempt++) {
            ensureRunning(context);
            String raw;
            try {
                raw = gateway.complete(command.getStoryVO().getModule(), LlmCall.builder()
                        .systemPrompt("人物设计与历史事实分离。元数据由程序绑定，输出不提供source/adoption/locked。")
                        .userPrompt(prompt).scene(ModelScene.CHAPTER_PLAN).label("character-design-" + (attempt + 1))
                        .maxTokens(5000).build());
            } catch (RuntimeException failure) {
                LlmLocalRejectionException.rethrowIfPresent(failure);
                ensureRunning(context);
                if (mustPropagate(failure)) throw failure;
                return new Result(List.of(), "DESIGN_CALL_FAILED:" + failure.getClass().getSimpleName());
            }
            ensureRunning(context);
            try {
                String content = raw == null ? "" : raw.strip();
                if (content.startsWith("```")) content = content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                if (content.length() > 32768) throw new IllegalArgumentException("设计输出过长");
                var object = JSON.parseObject(content);
                var profiles = object == null ? null : object.getList("profiles", CharacterDecisionProfile.class);
                if (profiles == null) throw new IllegalArgumentException("缺少profiles数组");
                var drafts = CharacterDecisionProfileService.draft(profiles);
                if (drafts.isEmpty() || drafts.stream().anyMatch(profile -> !names.contains(profile.displayName())
                        || CharacterDecisionProfileService.fieldEntries(profile).isEmpty())) {
                    throw new IllegalArgumentException("未生成授权人物的有效设计字段");
                }
                return new Result(drafts, "DESIGNED");
            } catch (RuntimeException malformed) {
                if (attempt == 1) return new Result(List.of(), "DESIGN_FORMAT_FAILED");
                prompt += "\n上一输出格式不符合契约；只重输出所列姓名的profiles JSON，不增加其他人物或元数据。";
            }
        }
        return new Result(List.of(), "DESIGN_FALLBACK");
    }

    private static boolean mustPropagate(Throwable failure) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.size() < 32 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.CancellationException) return true;
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
            if (cause instanceof org.springframework.web.client.RestClientResponseException http
                    && (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 403)) return true;
            String message = java.util.Objects.toString(cause.getMessage(), "").toLowerCase(java.util.Locale.ROOT);
            if (message.matches("(?s).*\\b(?:401|403)\\b.*") || message.contains("unauthorized")
                    || message.contains("forbidden") || message.contains("invalid_api_key")
                    || message.contains("认证失败") || message.contains("鉴权")) return true;
        }
        return Thread.currentThread().isInterrupted();
    }

    static void ensureRunning(DefaultArmoryFactory.DynamicContext context) {
        var job = context.getJob();
        if (Thread.currentThread().isInterrupted() || job != null && (job.isCancelRequested() || job.isBudgetExceeded())) {
            throw new IllegalStateException("人物设计已取消或触发预算停止");
        }
    }
}
