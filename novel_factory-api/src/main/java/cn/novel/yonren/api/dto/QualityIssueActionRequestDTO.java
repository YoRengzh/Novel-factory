package cn.novel.yonren.api.dto;

public record QualityIssueActionRequestDTO(String action, String reason, String expectedRevision,
                                           String expectedSourceHash) { }
