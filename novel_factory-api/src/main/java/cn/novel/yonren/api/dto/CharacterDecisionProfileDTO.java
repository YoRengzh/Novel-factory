package cn.novel.yonren.api.dto;

import lombok.Data;

/** 卡片可编辑内容；characterId/source/adoption回传不授予来源权限。 */
@Data
public class CharacterDecisionProfileDTO {
    private String characterId;
    private String displayName;
    private Boolean locked;
    private String want;
    private String need;
    private String fearAndDefense;
    private String valuePriorities;
    private String boundaries;
    private String independentConcerns;
    private String decisionPattern;
    private String source;
    private String adoption;
}
