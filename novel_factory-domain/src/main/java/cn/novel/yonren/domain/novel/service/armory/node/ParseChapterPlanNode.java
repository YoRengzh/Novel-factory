package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanChecks;
import cn.novel.yonren.types.enums.ChapterType;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 解析章节计划节点：消费规划器的解析结果，
 * 做纵深兜底（上游 aggregate 缺失时按 rawResult 再解一次）、chapterType 归一化与续写编号校正
 */
@Service
@Slf4j
public class ParseChapterPlanNode extends AbstractArmorySupport {

    @Resource
    private ValidateChapterPlanNode validateChapterPlanNode;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return validateChapterPlanNode;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 ParseChapterPlanNode - 解析章节计划输出");

        ChapterPlanAggregate chapterPlanAggregate = dynamicContext.getChapterPlanAggregate();
        if (chapterPlanAggregate == null) {
            // 纵深兜底：上游规划器已三级降级，理论不会走到这里
            chapterPlanAggregate = ChapterPlanChecks.parseLenient(dynamicContext.getRawResult());
        }
        if (chapterPlanAggregate == null) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "章节计划解析失败：上游降级与兜底解析均未成功");
        }

        // 容错：模型输出未知/缺失 chapterType 时统一降级为 NORMAL，避免 NPE 与脏值
        if (chapterPlanAggregate.getChapters() != null) {
            for (var item : chapterPlanAggregate.getChapters()) {
                if (item != null) {
                    item.setChapterType(ChapterType.of(item.getChapterType() == null ? null : item.getChapterType().getCode()));
                }
            }
        }
        // 续写编号校正：模型惯性输出批内编号（从 1 开始）时全量平移为全局编号
        ChapterPlanChecks.normalizeChapterNumbers(chapterPlanAggregate, dynamicContext.getChapterOffset());
        dynamicContext.setChapterPlanAggregate(chapterPlanAggregate);

        return router(requestParameter, dynamicContext);
    }
}
