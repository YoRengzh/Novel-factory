package cn.novel.yonren.domain.novel.model.entity;

import cn.novel.yonren.domain.novel.model.jackson.CharacterNameListDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 章节场景节拍表：介于"章节计划"与"正文"之间的轻量中间层。
 * 正文生成的自由度从"整章即兴"压缩为"逐拍扩写"——模型不再自己虚构填充物，
 * AI 味与注水的重要诱因（规划供给不足 + 硬性字数要求）随之消解。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterBeatsEntity {

    /** 节拍列表（3-5 个，按叙事顺序） */
    private List<Beat> beats;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Beat {
        /** 场景地点 */
        private String location;
        /** 在场角色（容错：模型常输出 [{"角色名":"婴儿"}] 形式，取键为角色名，见 CharacterNameListDeserializer） */
        @JsonDeserialize(using = CharacterNameListDeserializer.class)
        private List<String> characters;
        /** 本拍核心冲突/动作 */
        private String conflict;
        /** 本拍必须产生的信息增量（新信息/状态变化/关系变化） */
        private String infoGain;
        /** 篇幅占比提示（如 "20%"） */
        private String weight;
    }
}
