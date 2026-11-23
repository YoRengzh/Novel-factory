package cn.novel.yonren.domain.novel.model.valobj.rebase;

import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;

import java.util.List;

/**
 * 模型返回的章节重基差异提案。只表达“相对原计划的改动”，不承载完整计划。
 *
 * <p>不变量由 {@code ChapterRebaseService} 程序校验，不依赖模型自述：
 * 主线锚点、章号、标题、场景 ID 覆盖、人物名单和引用来源都不能被模型改写。
 */
public record RebaseProposal(Status status, List<Diagnosis> diagnosis, List<ScenePatch> patches,
                             List<String> remainingRisks) {

    public enum Status { PROPOSED, UNCHANGED, BLOCKED }

    /** 一条诊断：问题及其在原计划中的来源引用（引用 ID 由程序发放，不是模型编的 factId）。 */
    public record Diagnosis(String problem, String sourceRef, String sourceQuote) {
    }

    /**
     * 单个场景的完整字段替换。未出现在 patches 里的场景保持原样；
     * causality 一旦给出必须完整，避免新旧步骤并存。
     *
     * @param reason 为什么真实人物状态要求这样改；写入差异记录供人工复核
     */
    public record ScenePatch(String sceneId, String intention, String resistance, String outcome,
                             String function, SceneCausality causality, String reason) {
    }
}
