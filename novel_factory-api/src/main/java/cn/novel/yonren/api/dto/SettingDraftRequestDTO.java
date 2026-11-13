package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;


@Data
public class SettingDraftRequestDTO {

    /** 题材（必填）：唯一硬输入，例：都市异能 */
    private String theme;

    /** 用户已定的风格（可空）：非空时作为约束传给模型，而不是照抄 */
    private String style;

    /** 用户已定的目标人群（可空） */
    private String targetAudience;

    /** 用户已定的基调（可空） */
    private String tone;

    /** 用户已定的叙述视角（可空） */
    private String perspective;

    /** 额外要求（可空）：例如「想要双男主，不要系统流」，模型必须遵守 */
    private String extraHints;

    /** 本次要重生成的字段名（如 novelTitle / style）；null/空=全部重生成，其余字段按 previous 锁定；含未知字段名报 400 */
    private List<String> targets;

    /** 上一版草稿（字段名→值）：非目标字段锁定为该值，并作为上下文供模型参考衔接 */
    private Map<String, String> previous;

    /**
     * 用户明确保留的字段：即使整份重生成也原样回填。
     * 可与 targets 同时出现于不同字段；同一字段同时重生成和保留会被拒绝。
     */
    private List<String> lockedFields;
}
