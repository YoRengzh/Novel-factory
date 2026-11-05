package cn.novel.yonren.domain.novel.service.armory.prompt;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class PromptBuilder {

    private final List<PromptRuleStrategy> strategies;

    /**
     * 收集所有命中规则的原始列表（含注入位置）。
     * 空内容的过滤责任在各策略 load 内部（见接口契约），此处直接收编
     */
    public List<PromptRule> collectRules(PromptScene scene, PromptContext ctx) {
        List<PromptRule> rules = new ArrayList<>();
        for (PromptRuleStrategy strategy : strategies) {
            try {
                if (!strategy.supports(scene, ctx)) {
                    continue;
                }
                List<PromptRule> loaded = strategy.load(scene, ctx);
                if (loaded.isEmpty() && strategy instanceof CriticalPromptRule) {
                    // 命中场景却零规则 = 关键资产缺失（加载器对缺失文件返回 null 而不抛异常）
                    throw new IllegalStateException("未加载到任何规则（规则文件或标题锚点可能被改名/未正确打包）");
                }
                rules.addAll(loaded);
            } catch (Exception e) {
                if (strategy instanceof CriticalPromptRule critical) {
                    throw new AppException(ResponseCode.UN_ERROR.getCode(),
                            "关键写作规则缺失，作业终止：" + critical.assetDescription()
                                    + "（场景 " + scene + "）：" + e.getMessage(), e);
                }
                // 普通策略：单条规则加载失败只跳过，不阻塞生成主流程
                log.warn("提示词规则加载失败，已跳过：{}", strategy.getClass().getSimpleName(), e);
            }
        }
        return rules;
    }

    /**
     * 拼接 System Message 文本（所有 SYSTEM 位置规则）
     */
    public String buildSystemMessage(PromptScene scene, PromptContext ctx) {
        return joinRules(collectRules(scene, ctx), PromptRule.InjectPosition.SYSTEM);
    }

    /**
     * 拼接 User Message 尾注（所有 USER_TAIL 位置规则）
     */
    public String buildUserTail(PromptScene scene, PromptContext ctx) {
        return joinRules(collectRules(scene, ctx), PromptRule.InjectPosition.USER_TAIL);
    }

    private String joinRules(List<PromptRule> rules, PromptRule.InjectPosition position) {
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

}