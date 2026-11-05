package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptContext;
import cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;

import java.util.List;

/**
 * 提示词规则策略：每种规则来源一个实现，由 PromptBuilder 统一收集。
 * load 返回多条规则——一个策略可按题材/场景装配一组相关资料（如题材资料包）
 */
public interface PromptRuleStrategy {

    /**
     * 判断当前场景+上下文是否命中该策略
     */
    boolean supports(PromptScene scene, PromptContext ctx);

    /**
     * 加载规则内容（实现类内部做缓存）。
     * 契约：返回的每条规则非 null 且内容非空白（由实现自行过滤），无命中时返回空列表；
     * 收集器据此不做二次校验，直接收编
     */
    List<PromptRule> load(PromptScene scene, PromptContext ctx);

}
