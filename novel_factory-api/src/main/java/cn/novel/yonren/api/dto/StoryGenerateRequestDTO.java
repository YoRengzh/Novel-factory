package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.Map;

@Data
public class StoryGenerateRequestDTO {
    //小说名字 "灵气复苏后的外卖员"
    private String novel_title;
    //小说类型 "都市异能"
    private String theme;
    //小说格调 "逆袭打脸爽文"
    private String style;
    //世界观 "现代都市，少数人觉醒异能"
    private String worldSetting;
    //视角 "第三人称视角"
    private String perspective;
    //目标人群 "男频"
    private String targetAudience;
    //基调
    private String tone;
    //主人公 "林川，23岁，外卖员，性格隐忍但有底线"
    private String protagonist;
    //概述 "主角送外卖时卷入异能者冲突，意外觉醒能力"
    private String outline;
    //章节数量
    private Integer chapterCount;
    //章节概括 "第一章，主角遭遇事件并觉醒能力"
    private String chapterGoal;
    // 续写目录名（可选）：传入时预载该目录记忆摘要，章节号全局接续
    private String resumeStoryDir;
    // 系列/共享世界观 ID（可选）：同系列的 story-bible 写入共享向量集合 novel-world-{worldId}，
    // 写新章时与故事记忆合并检索唤醒；仅允许字母数字开头、1-63 位字母数字/下划线/连字符
    private String worldId;
    // 全书总章数上限（可选）：传入时覆盖 yml 的 constraints.max-chapter-count，
    // 续写偏移达到该值即视为完结、拒绝继续生成；本批超出部分自动截断
    private Integer maxChapterCount;
    // 故事设定明确存在金手指/系统时传 true；false/null 时不启用三章频率规则
    private Boolean hasCheatMechanism;

    /** 金手指/系统名称（如"签到系统"）：一致性索引据此登记机制账本，缺省记"未命名金手指" */
    private String cheatMechanismName;

    /** 金手指使用/有意义提及的最大间隔章数（默认 3、最小 1）：超限触发审校提醒 */
    private Integer cheatUsageInterval;

    /** 本批创作要点（导演通道）：自由文本，注入卷/阶段/计划三个规划 prompt 顶部 */
    private String creativeNotes;

    /** 可选人物深度模式；缺省/OFF不新增卡片、上下文或模型调用。 */
    private String characterDepthMode;
}


