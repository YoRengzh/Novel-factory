package cn.novel.yonren.domain.novel.model.entity;

import cn.novel.yonren.types.utils.CharacterNameListDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 章节计划与正文之间的场景组织建议。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterBeatsEntity {

    /** 按叙事顺序排列的节拍 */
    private List<Beat> beats;

    /** 原结尾末尾可省的策划说明，必须逐字来自计划，供写作提示核对。 */
    private String redundantEndingSuffix;

    public ChapterBeatsEntity(List<Beat> beats) {
        this.beats = beats;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Beat {
        /** 场景地点 */
        private String location;
        /** 在场角色（容错：模型常输出 [{"主角甲":"婴儿"}] 形式，取键为角色名，见 CharacterNameListDeserializer） */
        @JsonDeserialize(using = CharacterNameListDeserializer.class)
        private List<String> characters;
        /** 本拍核心冲突/动作 */
        private String conflict;
        /** 本拍是否需要展开计划中已有的人物交流。 */
        private Boolean exchange;
        /** 本拍已有的信息或状态变化，可留空 */
        private String infoGain;
        /** 篇幅占比提示（如 "20%"） */
        private String weight;
    }
}
