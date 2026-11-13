package cn.novel.yonren.api.dto;

public record QualityIssueRecheckRequestDTO(String expectedRevision, String expectedSourceHash,
                                            Boolean confirmModelCall) { }
