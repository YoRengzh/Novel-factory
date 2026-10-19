package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.ReferenceSelectorProperties;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicReferenceService {

    private final ReferenceIndexLoader referenceIndexLoader;
    private final LlmReferenceSelector llmReferenceSelector;
    private final VectorReferenceRetriever vectorReferenceRetriever;
    private final PromptRuleFileLoader fileLoader;
    private final ReferenceSelectorProperties selectorProperties;
    private final cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig llmRuntimeConfig;

    /** 选择结果缓存：LRU（accessOrder）+ 容量封顶；key 含索引哈希，资料更新即失效 */
    private final Map<String, List<PromptRule>> selectionCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<PromptRule>> eldest) {
                    return size() > selectorProperties.getCacheMaxEntries();
                }
            });

    /**
     * 为当前场景挑选动态参考资料并包装为规则
     *
     * @param module 请求级模型配置（选择器复用同一 api/模型，仅覆盖小参数）
     */
    public List<PromptRule> selectAsRules(StoryVO.Module module, PromptScene scene, PromptContext ctx) {
        var config = selectorProperties;
        if (!config.isEnabled()) {
            return List.of();
        }

        List<ReferenceIndexEntry> index = referenceIndexLoader.getIndex();
        String cacheKey = buildCacheKey(module, scene, ctx, index);
        if (config.isCacheEnabled()) {
            List<PromptRule> cached = selectionCache.get(cacheKey);
            if (cached != null) {
                log.info("动态资料命中缓存，key: {}, 规则数: {}", cacheKey, cached.size());
                return cached;
            }
        }

        List<String> selected;
        if (isVectorMode(config)) {
            // 快速失败：向量链路任何失败（欠费/鉴权/向量库/网络）都终止作业，不降级 LLM 选择——
            // llm 选择质量明显差于向量检索，静默降级会让作业在低质量注入下跑完
            try {
                selected = vectorReferenceRetriever.retrieve(module, scene, ctx);
            } catch (Exception e) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(),
                        "动态资料向量检索失败，不降级 LLM 选择，作业终止：" + e.getMessage(), e);
            }
        } else {
            selected = llmReferenceSelector.select(module, scene, ctx, index);
        }
        // 正文场景用收紧预算：写正文时模型需要的是本章计划而非教材，整篇资料会稀释注意力
        int recallChars = scene == PromptScene.CHAPTER_CONTENT
                ? config.getContentSceneRecallChars() : config.getMaxRecallChars();
        List<PromptRule> rules = loadRules(selected, config.getMaxSelected(), recallChars);

        if (config.isCacheEnabled()) {
            selectionCache.put(cacheKey, rules);
        }
        log.info("动态资料装配完成，key: {}, 模式: {}, 选中 {} 份，注入 {} 条规则",
                cacheKey, llmRuntimeConfig.effectiveReferenceMode(config.getMode()), selected.size(), rules.size());
        return rules;
    }

    private boolean isVectorMode(ReferenceSelectorProperties config) {
        // 模式裁决：运行时覆盖（设置页「资料检索方式」开关）优先，未设置回退 yml 静态值。
        // 只在这里判一次——检索/缓存键两处都经此方法与 effectiveMode()，改模式不散落
        String mode = llmRuntimeConfig.effectiveReferenceMode(config.getMode());
        return "vector".equalsIgnoreCase(mode);
    }

    /**
     * 缓存维度：场景 + 题材 + 风格 + 章节类型 + 章号 + 本章要点 + 模型接入（baseUrl+model）+ 选择模式 + 索引内容哈希。
     * 不同故事（题材/风格不同）与不同模型/模式不串选择结果；章节要点参与键使选择细到单章语义
     * （代价是逐章各占一条缓存），索引文件增删/改写立即失效
     */
    private String buildCacheKey(StoryVO.Module module, PromptScene scene, PromptContext ctx, List<ReferenceIndexEntry> index) {
        String baseUrl = null;
        String model = null;
        if (module != null) {
            if (module.getAiApi() != null) {
                baseUrl = module.getAiApi().getBaseUrl();
            }
            if (module.getChatModel() != null) {
                model = module.getChatModel().getModel();
            }
        }
        return scene + "|" + ctx.getTheme() + "|" + ctx.getStyle() + "|"
                + (ctx.getChapterType() == null ? "-" : ctx.getChapterType().getCode()) + "|"
                + (ctx.getChapterNo() == null ? "-" : ctx.getChapterNo()) + "|"
                + java.util.Objects.toString(ctx.getChapterBrief(), "") + "|"
                + String.valueOf(baseUrl) + "|" + String.valueOf(model) + "|"
                + llmRuntimeConfig.effectiveReferenceMode(selectorProperties.getMode()) + "|"
                + index.hashCode();
    }

    private List<PromptRule> loadRules(List<String> fileNames, int maxSelected, int maxRecallChars) {
        List<PromptRule> rules = new ArrayList<>();
        int remainingChars = Math.max(0, maxRecallChars);
        for (String fileName : fileNames) {
            if (rules.size() >= maxSelected || remainingChars <= 0) {
                break;
            }
            String content = fileLoader.load("references/" + fileName + ".md");
            if (content == null) {
                continue;
            }
            String bounded = boundContent(content, remainingChars);
            if (bounded.isBlank()) {
                break;
            }
            rules.add(new PromptRule("ref-" + fileName, bounded, PromptRule.InjectPosition.USER_TAIL));
            remainingChars -= bounded.length();
        }
        return rules;
    }

    /** 尽量在段落边界截断，且明确告知模型资料是不完整摘录。 */
    private String boundContent(String content, int budget) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.length() <= budget) {
            return normalized;
        }
        if (budget <= 32) {
            return normalized.substring(0, budget);
        }
        int cut = normalized.lastIndexOf("\n\n", budget - 16);
        if (cut < budget / 2) {
            cut = normalized.lastIndexOf('\n', budget - 16);
        }
        if (cut < budget / 2) {
            cut = budget - 16;
        }
        return normalized.substring(0, cut).trim() + "\n[资料已按上下文预算截断]";
    }

}
