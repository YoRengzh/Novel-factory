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
        /** 在场角色（容错：模型常输出 [{"主角甲":"婴儿"}] 形式，取键为角色名，见 CharacterNameListDeserializer） */
        @JsonDeserialize(using = CharacterNameListDeserializer.class)
        private List<String> characters;
        /** 本拍核心冲突/动作 */
        private String conflict;
        /**
         * 本拍由谁对谁开口、说了什么（一句话交锋要点或关键台词）；本拍无对白时留空。
         *
         * <p><b>为什么单独一个字段</b>（2026-10-09）：只有 conflict 时，"他把信折好塞进信封"
         * 是合法的一拍——实测第 96 章就是这样被逐拍展开成动作分解的，全章有效对话仅 9 句、
         * 轮次密度 4.4/千字（劣化线 6.0），而同批其它章在 12-27。动作分解能写满篇幅却零互动，
         * 对白必须成为节拍表的一项**显式供给**，写手才有可执行的东西。
         */
        private String dialogue;
        /** 本拍必须产生的信息增量（新信息/状态变化/关系变化） */
        private String infoGain;
        /** 篇幅占比提示（如 "20%"） */
        private String weight;
    }
}
