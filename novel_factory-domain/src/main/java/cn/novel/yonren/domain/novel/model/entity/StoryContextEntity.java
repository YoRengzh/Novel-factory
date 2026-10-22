package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryContextEntity {

    //小说名字
    private String novel_title;
    //小说类型
    private String theme;
    //小说格调
    private String style;
    //世界观
    private String worldSetting;
    //视角
    private String perspective;
    //目标人群
    private String targetAudience;
    //基调
    private String tone;
    //主人公
    private String protagonist;
    //概述
    private String outline;
    //章节数量
    private Integer chapterCount;
    //章节概括
    private String chapterGoal;
    // 系列/共享世界观 ID（可选，五期）：同系列故事共享世界观向量集合
    private String worldId;
    /**
     * 主要人物档案（代词与既定事实）：书写为「姓名（性别，用「他/她」）＋既定事实」的纯文本，如
     * 「角色丁（女，用「她」，志愿某校一班）；角色庚（女，用「她」，志愿另一班）」。
     *
     * <p>由 story-bible 的「主要人物」行提供、续写时后端从 bible 强制回填（bible 是唯一权威）。
     * 注入规划/正文/审计三处 prompt：前者避免安排出矛盾的剧情，中者避免写错代词，
     * 后者拿到"可对照的锚"才有资格把代词/班级写错判成事实矛盾。
     */
    private String characterProfiles;

}
