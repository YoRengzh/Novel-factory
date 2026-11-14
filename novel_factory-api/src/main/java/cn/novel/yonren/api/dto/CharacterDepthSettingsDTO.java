package cn.novel.yonren.api.dto;

import lombok.Data;

/** 请求中预算可省略，服务端填冻结默认值；policyVersion不可伪造为新策略。 */
@Data
public class CharacterDepthSettingsDTO {
    private String mode;
    private Integer contextMaxChars;
    private Integer feedbackMaxChars;
    private Integer maxNewProfilesPerPass;
    private String policyVersion;
}
