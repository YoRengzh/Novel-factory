package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.domain.framework.AbstractMultiThreadStrategyRouter;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 规则树节点基类。
 * 使用内联于 {@code cn.novel.yonren.domain.framework} 的树形策略路由：
 * 每个节点的 get() 是路由决策点，
 * 可根据上下文返回不同分支，后期扩展多条路径时只需在 get() 中增加分支判断。
 */
public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> {

    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

}
