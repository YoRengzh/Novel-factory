package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;


/**
 * 全局兜底模型链：主模型 → 全局兜底模型（最多 2 站）。
 *
 * <p><b>为什么只有一个兜底</b>（2026-10-23 起）：兜底的前提是"原路走不通"——额度耗尽、
 * 模型下线这类失败常按供应商计量，同一供应商的另一个模型往往一起挂。所以兜底的价值
 * 全在"换一个供应商"，而不是在同一个供应商里多排几个模型名。此前按场景配 17 条链，
 * 实际填的几乎都是同样的值，维护成本远高于收益，已收敛为单个全局兜底。
 *
 * <p><b>兜底自带完整配置</b>：base-url / api-key / max-tokens / temperature 都可独立指定，
 * 缺省时回落主模型的对应字段。这与此前"只换模型名"不同——那时兜底必然同供应商，
 * 现在允许跨族，所以地址密钥与输出上限必须能跟着变。
 */
public final class ModelFallbackChain {

    /** 降级链最大长度（含主模型）：防止配置意外写成多站导致一次失败烧掉整串调用的费用 */
    public static final int MAX_CHAIN_LENGTH = 2;

    private ModelFallbackChain() {
    }

    /**
     * 构建降级链。主模型为 null（无可用配置）时返回空表——调用方据此直接失败，不做无谓尝试。
     *
     * @param module   请求级模型配置（读 fallbackModel）
     * @param scene    本次调用的场景（仅用于日志与签名兼容；单兜底不再按场景区分）
     * @param primary  该场景已解析出的主模型配置（已应用运行时覆盖）
     */
    public static List<StoryVO.Module.ChatModel> build(StoryVO.Module module, ModelScene scene,
                                                       StoryVO.Module.ChatModel primary) {
        List<StoryVO.Module.ChatModel> chain = new ArrayList<>();
        if (primary == null || StringUtils.isBlank(primary.getModel())) {
            return chain;
        }
        chain.add(primary);
        StoryVO.Module.ChatModel fallback = fallbackOf(module);
        if (fallback == null || StringUtils.isBlank(fallback.getModel())) {
            return chain;
        }
        // 兜底与主模型同名时没有降级意义（同一模型重试只会再失败一次，白烧一轮）
        if (fallback.getModel().trim().equals(primary.getModel())) {
            return chain;
        }
        if (chain.size() >= MAX_CHAIN_LENGTH) {
            return chain;
        }
        chain.add(mergeWithPrimary(fallback, primary));
        return chain;
    }

    /** 取全局兜底配置；模块或兜底未配置时返回 null */
    private static StoryVO.Module.ChatModel fallbackOf(StoryVO.Module module) {
        return module == null ? null : module.getFallbackModel();
    }

    /**
     * 兜底缺省字段回落主模型：只填了模型名的兜底配置，地址密钥沿用主模型
     * （同供应商场景仍可只写一个模型名）；显式填了的字段一律以兜底为准。
     *
     * <p>max-tokens 必须可独立指定：异族模型的上限与主模型常不同，
     * 直接沿用会因超出供应商上限而 400。
     */
    static StoryVO.Module.ChatModel mergeWithPrimary(StoryVO.Module.ChatModel fallback,
                                                     StoryVO.Module.ChatModel primary) {
        StoryVO.Module.ChatModel merged = new StoryVO.Module.ChatModel();
        merged.setModel(fallback.getModel().trim());
        merged.setMaxTokens(fallback.getMaxTokens() != null
                ? fallback.getMaxTokens() : primary.getMaxTokens());
        merged.setTemperature(fallback.getTemperature() != null
                ? fallback.getTemperature() : primary.getTemperature());
        merged.setEnableThinking(fallback.getEnableThinking() != null
                ? fallback.getEnableThinking() : primary.getEnableThinking());
        merged.setBaseUrl(StringUtils.isNotBlank(fallback.getBaseUrl())
                ? fallback.getBaseUrl() : primary.getBaseUrl());
        merged.setApiKey(StringUtils.isNotBlank(fallback.getApiKey())
                ? fallback.getApiKey() : primary.getApiKey());
        merged.setCompletionsPath(StringUtils.isNotBlank(fallback.getCompletionsPath())
                ? fallback.getCompletionsPath() : primary.getCompletionsPath());
        return merged;
    }
}
