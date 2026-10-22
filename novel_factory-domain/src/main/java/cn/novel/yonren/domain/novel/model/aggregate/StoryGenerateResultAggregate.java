package cn.novel.yonren.domain.novel.model.aggregate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 故事生成结果聚合根
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryGenerateResultAggregate {

    private ChapterPlanAggregate chapterPlanAggregate;

    private Map<String, String> usedPromptMap;

    // 本次落盘的故事目录名（docs/workspace/stories 下），续写请求将其填入 resumeStoryDir
    private String storyDirName;

}
