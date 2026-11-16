package cn.novel.yonren.domain.novel.model.entity;

import cn.novel.yonren.domain.novel.model.valobj.plan.CausalVerdict;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节审校结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditResultEntity {

    @Builder.Default
    private List<ChapterIssueEntity> issues = new ArrayList<>();

    /** 启用交付核验时必须显式返回；缺省不能按空问题清单通过。 */
    private List<CausalVerdict> deliveryChecks;

    /** 仅由本地策略生成，模型不能自报核验状态或稿件身份。 */
    @JsonIgnore
    private DeliveryReview deliveryReview;

    @JsonIgnore
    private boolean auditUnavailable;

    public AuditResultEntity(List<ChapterIssueEntity> issues) {
        this.issues = issues;
    }

}
