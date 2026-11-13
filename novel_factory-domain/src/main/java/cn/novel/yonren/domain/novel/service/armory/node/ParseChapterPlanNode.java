package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanChecks;
import cn.novel.yonren.domain.novel.service.armory.plan.ScenePlanPolicy;
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
        // v2 场景契约：先按场景刷新派生视图，再做结构校验。
        // 顺序不能反——派生视图本身就是校验对象，先校验会对着模型给的旧事件清单判违规。
        applySceneContract(chapterPlanAggregate);
        dynamicContext.setChapterPlanAggregate(chapterPlanAggregate);

        return router(requestParameter, dynamicContext);
    }

    /**
     * v2 场景计划：刷新 characters/keyEvents/dialogueScenes 派生视图并检查场景结构。
     * 旧计划（schemaVersion 为 null）完全跳过，保持原事件清单语义。
     *
     * <p>场景违规只记录不抛出：带反馈重规划在上游规划器已完成，此处再抛会让整批直接失败；
     * 结构缺失仍由后续校验按既有语义报错。
     */
    private void applySceneContract(ChapterPlanAggregate aggregate) {
        if (aggregate == null || aggregate.getChapters() == null) {
            return;
        }
        for (var item : aggregate.getChapters()) {
            if (item == null || !Integer.valueOf(2).equals(item.getSchemaVersion())) {
                continue;
            }
            ScenePlanPolicy.normalize(item);
            var violations = ScenePlanPolicy.violations(item);
            if (!violations.isEmpty()) {
                log.warn("第 {} 章场景契约存在结构问题：{}", item.getChapterNo(), violations);
            }
        }
    }
}
