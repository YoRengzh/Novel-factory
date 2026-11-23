package cn.novel.yonren.domain.novel.service.armory.writer;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/** 从完整计划中整理出的正文材料。每次写作只能拿到自己的段。 */
public record ChapterWritingPlan(List<Section> sections, boolean knowledgeBoundarySupplied) {
    public ChapterWritingPlan(List<Section> sections) {
        this(sections, false);
    }
    /**
     * 一段写作材料。
     *
     * @param rendered 非空时直接作为本段材料文本，不再由 viewpoint/entry/scenes 拼装——
     *                 确定性直通路径用它承载未经 LLM 改写的原文，避免再被压成任务摘要。
     * @param brief    给动态参考资料选择器的紧凑任务描述。DIRECT 的 scenes 为空，不能从它反推 brief。
     */
    public record Section(String viewpoint, String entry, List<Scene> scenes, String rendered, String brief) {
        /** 旧三参入口保持可用：rendered 为空，行为与引入前一致。 */
        public Section(String viewpoint, String entry, List<Scene> scenes) {
            this(viewpoint, entry, scenes, null, null);
        }

        /** 确定性直通入口：必须同时提供写作材料和资料检索 Brief。 */
        public Section(String rendered, String brief) {
            this(null, null, List.of(), rendered, brief);
        }

        public String brief() {
            if (StringUtils.isNotBlank(brief)) {
                return brief;
            }
            return String.join("\n", scenes.stream().map(Scene::events).toList());
        }

        public String render() {
            if (StringUtils.isNotBlank(rendered)) {
                return rendered;
            }
            StringBuilder text = new StringBuilder("【本段视角】").append(viewpoint)
                    .append("\n【入场时已知、可见或须维持的状态】").append(StringUtils.defaultString(entry));
            for (Scene scene : scenes) {
                text.append("\n【场景 ").append(scene.number()).append("】").append(scene.events());
            }
            return text.toString();
        }
    }

    public record Scene(int number, String events) {}
}
