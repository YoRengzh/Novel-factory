package cn.novel.yonren.domain.novel.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 正文生成前缀总量预算：块级封顶之上的最后一道总额约束。
 * 前缀由记忆/末态红线/风格门禁/一致性索引/禁泄清单等多个块拼成，单块都有封顶，
 * 但"封顶之和"会随块数增长击穿模型输入窗口——由 PromptBudgetGuard 按优先级统一淘汰。
 */
@Data
@Component
@ConfigurationProperties(prefix = "story.prompt.budget")
public class PromptBudgetProperties {

    /**
     * 正文前缀总量上限（字符）；&lt;=0 关闭总额约束（退化为逐块直拼）。
     * 定标口径为"按各块封顶与典型体量推算"（2026-09-28 重估，见 yml 注释），非实测；
     * 真实余量以装配日志的占比字段判定——长期 ≥90% 即需回查膨胀来源而非调大本值
     */
    private int totalPrefixChars = 18000;

    /**
     * 章节计划"输入段"（故事设定 + 记忆前缀）总量上限（字符）；&lt;=0 关闭约束。
     * 规划 prompt 的固定要求与 schema 段不参与裁剪（规划契约不可丢），故此处只约束会随连载增长的输入段
     */
    private int planPrefixChars = 20000;

}