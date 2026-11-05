package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceSelection;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.enums.PromptScene;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 资料选择器：把参考资料索引交给模型，由模型根据当前写作任务
 * 判断需要哪些资料（对应 skill 机制的"模型根据 name/description 决定是否使用"层）。
 * 选择失败降级为空列表，不阻塞主生成流程
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmReferenceSelector {

    private final LlmGateway llmGateway;

    private static final BeanOutputConverter<ReferenceSelection> CONVERTER = new BeanOutputConverter<>(
            ReferenceSelection.class,
            JsonMapper.builder()
                    .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                    .build());

    private static final String SYSTEM_PROMPT = """
            你是小说创作流水线的资料管理员。
            每次会收到一份可选写作参考资料的索引清单和当前写作任务描述，
            你的职责是判断哪些资料对本次创作有实际帮助，返回需要注入的资料文件名列表。
            原则：
            1. 只选真正能提升本次输出质量的资料，最多 3 份，宁缺毋滥；
            2. 不要因为"可能有用"而选择，当前任务用不上的资料一律不选；
            3. 只能从索引清单中选择，输出文件名，不得编造；
            4. 如果没有任何资料对本次任务有帮助，返回空列表。
            """;

    /**
     * 从索引中选出本次任务需要的资料文件名
     *
     * @param module 请求级供应商/模型配置
     * @param scene 当前场景（大纲/正文）
     * @param ctx       装配上下文（题材/风格/章节进度）
     * @param index     参考资料索引
     * @return 选中的文件名列表（已按索引白名单过滤）；失败返回空列表
     */
    public List<String> select(StoryVO.Module module, PromptScene scene, PromptContext ctx, List<ReferenceIndexEntry> index) {
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        Set<String> whitelist = index.stream()
                .map(ReferenceIndexEntry::getFileName)
                .collect(Collectors.toSet());
        try {
            // maxTokens/temperature 不再硬编码，由 scene-models 的 ref-select 场景配置驱动
            String raw = llmGateway.complete(module, LlmCall.builder()
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(buildUserPrompt(scene, ctx, index))
                    .label("ref-select-" + scene.name().toLowerCase().replace('_', '-'))
                    .scene(ModelScene.REFERENCE_SELECT)
                    .build());
            ReferenceSelection selection = CONVERTER.convert(raw);
            List<String> selected = selection == null || selection.getReferences() == null
                    ? List.of()
                    : selection.getReferences().stream()
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .filter(whitelist::contains)
                            .distinct()
                            .collect(Collectors.toList());
            log.info("资料选择完成，选中 {} 份：{}", selected.size(), selected);
            return selected;
        } catch (Exception e) {
            log.warn("资料选择失败，本次跳过动态资料注入", e);
            return List.of();
        }
    }

    private String buildUserPrompt(PromptScene scene, PromptContext ctx, List<ReferenceIndexEntry> index) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前任务】");
        if (scene == PromptScene.CHAPTER_PLAN) {
            sb.append("规划全书章节大纲");
        } else {
            sb.append("撰写第 ").append(ctx.getChapterNo()).append("/").append(ctx.getTotalChapters()).append(" 章正文");
            if (ctx.getChapterType() != null) {
                sb.append("（章节类型：").append(ctx.getChapterType().getDesc()).append("）");
            }
        }
        sb.append("\n【题材】").append(StringUtils.defaultIfBlank(ctx.getTheme(), "未指定"));
        sb.append("\n【风格】").append(StringUtils.defaultIfBlank(ctx.getStyle(), "未指定"));
        if (StringUtils.isNotBlank(ctx.getChapterBrief())) {
            sb.append("\n【本章要点】").append(ctx.getChapterBrief());
        }
        sb.append("\n\n【参考资料索引】（格式：文件名 | 标题 | 简介）\n");
        for (ReferenceIndexEntry entry : index) {
            sb.append("- ").append(entry.getFileName()).append(" | ").append(entry.getTitle());
            if (StringUtils.isNotBlank(entry.getDigest())) {
                sb.append(" | ").append(entry.getDigest());
            }
            sb.append("\n");
        }
        sb.append("\n").append(CONVERTER.getFormat());
        return sb.toString();
    }

}
