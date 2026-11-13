package cn.novel.yonren.domain.novel.model.valobj.work;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;

import java.util.List;

/**
 * 一章提交时随之生效的完整记忆状态，与正文一起写入恢复日志。
 *
 * <p>拆成整包而不是多次独立写盘，是为了让"正文、摘要、账本、蓝图"在同一次恢复里一起前移；
 * 分步写会让崩溃点落在中间，续写读到的账本与正文对不上。
 */
public record ChapterCommitState(List<ChapterSummaryEntity> summaries, ConsistencyIndexEntity consistency,
                                 List<QualityDebtEntity> debts, StyleStatEntity style, List<String> fingerprints,
                                 List<ForeshadowScheduleEntity> schedules, List<ForeshadowSettlementEntity> settlements,
                                 List<StageBlueprintEntity> stages, List<VolumeBlueprintEntity> volumes) {
}
