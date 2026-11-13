package cn.novel.yonren.domain.novel.model.entity;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则树入参实体：聚合用户输入 + yml 配置的 StoryVO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArmoryCommandEntity {

    //上下文
    private StoryContextEntity storyContextEntity;

    // yml 配置装配进来的 StoryVO
    private StoryVO storyVO;

    // 续写目录名（docs/workspace/stories 下的目录）；非空时预载该目录的记忆摘要
    private String resumeStoryDir;

    // 全书总章数上限（可选，来自请求）：覆盖 yml 的 constraints.max-chapter-count，达到后视为完结、拒绝续写
    private Integer maxChapterCount;

    /** 本批创作要点（导演通道）：自由文本，注入三个规划 prompt 顶部；自续批原样传递 */
    private String creativeNotes;
}
