package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 跨章一致性索引，不替代角色/物品/势力三账本。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsistencyIndexEntity {
    @Builder.Default private List<TimelineEntry> timeline = new ArrayList<>();
    @Builder.Default private List<InjuryEntry> injuries = new ArrayList<>();
    @Builder.Default private List<TermEntry> terms = new ArrayList<>();
    @Builder.Default private List<NumberEntry> numbers = new ArrayList<>();
    @Builder.Default private List<MechanismEntry> mechanisms = new ArrayList<>();
    /** 人际关系台账（2026-09-16 新增，type=RELATION 的一致性事实投影）：当前关系态按"双方"去重，
     *  保留最近一次更新——回答"A 与 B 现在什么关系"，而不只是某章发生过一次关系变化 */
    @Builder.Default private List<RelationEntry> relations = new ArrayList<>();

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TimelineEntry { private String event; private Integer chapterNo; private String storyTime; private String evidence; }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class InjuryEntry { private String character; private String location; private String severity; private Integer firstChapter; private String lastStatus; private String evidence; }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TermEntry { private String canonical; private List<String> aliases; private Integer firstChapter; }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class NumberEntry { private String name; private String value; private Integer chapterNo; private String scope; }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MechanismEntry { private String name; private String type; private Boolean enabled; private Integer lastUsedChapter; private Integer usageInterval; }
    /** 单条关系：pair 为摘要侧给的双方标识（如「陆沉与墨璇玑」），relation 为当前关系态 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RelationEntry { private String pair; private String relation; private Integer firstChapter; private Integer lastChapter; private String evidence; }
}
