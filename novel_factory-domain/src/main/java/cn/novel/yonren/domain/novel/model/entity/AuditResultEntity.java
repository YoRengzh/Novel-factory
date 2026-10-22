package cn.novel.yonren.domain.novel.model.entity;

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

}
