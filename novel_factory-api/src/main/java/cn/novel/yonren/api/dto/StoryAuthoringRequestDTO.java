package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET 的版本/依据回传；PUT 还必须回传同一内容的 previewHash。null patch 分量不改。 */
@Data
public class StoryAuthoringRequestDTO {
    private String expectedRevision;
    private String expectedBasis;
    private Map<String, String> settings;
    private List<StoryAuthoringEntryDTO> entries;
    private String characterDepthMode;
    private CharacterDepthSettingsDTO characterDepthSettings;
    private List<CharacterDecisionProfileDTO> characterProfiles;
    private String previewHash;

    /**
     * 设定值必须是字符串或 null：数字/布尔被静默转成文本后，写回 bible 会改变原意。
     * 本模块不引入 Jackson 注解，反序列化器按 JavaBean 约定调用本方法。
     */
    public void setSettings(Map<String, ?> values) {
        if (values == null) { settings = null; return; }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null && !(value instanceof String)) throw new IllegalArgumentException("设定值必须是字符串或 null");
            result.put(key, (String) value);
        });
        settings = result;
    }
}
