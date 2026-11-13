package cn.novel.yonren.api.dto;

public record QualityIssueSaveResponseDTO(boolean saved, QualityIssueDTO issue,
                                         QualityIssueDTO.Disposition disposition) { }
