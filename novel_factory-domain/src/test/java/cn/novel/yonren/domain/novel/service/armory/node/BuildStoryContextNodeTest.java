package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故事上下文拼装测试：侧重「人物档案」行的出现/缺席。
 *
 * <p>背景（2026-10-10）：正文把某女性角色写成「他」连写三章，根因是**整条链路都没有一处事实**
 * 声明她的性别——账本没有、审计没有可对照的锚。人物档案由作者在 bible 声明一次，
 * 这里负责把它渲染进「故事设定」段；规划与正文两个场景都吃这一段，故渲染一次即覆盖两处。
 */
class BuildStoryContextNodeTest {

    @Mock
    private BuildVolumeBlueprintNode buildVolumeBlueprintNode;

    @InjectMocks
    private BuildStoryContextNode node;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private static ArmoryCommandEntity commandWith(String characterProfiles) {
        return ArmoryCommandEntity.builder()
                .storyContextEntity(StoryContextEntity.builder()
                        .novel_title("甲").theme("乙").style("丙").worldSetting("丁")
                        .perspective("戊").targetAudience("己").tone("庚")
                        .protagonist("角色丙").characterProfiles(characterProfiles)
                        .outline("概述").chapterCount(5).chapterGoal("章节目标")
                        .build())
                .build();
    }

    @Test
    @DisplayName("声明了人物档案：渲染进故事设定，并标注「不得改变」")
    void rendersCharacterProfilesWhenDeclared() throws Exception {
        DefaultArmoryFactory.DynamicContext ctx = new DefaultArmoryFactory.DynamicContext();

        node.doApply(commandWith("角色己（女，用「她」）；角色丙（男，用「他」）"), ctx);

        String storyContext = ctx.getStoryContext();
        assertTrue(storyContext.contains("主要人物（代词与既定事实，全书必须一致，不得改变）"),
                "人物档案必须进故事设定（规划与正文共用这一段）：" + storyContext);
        assertTrue(storyContext.contains("角色己（女，用「她」）"), storyContext);
    }

    @Test
    @DisplayName("未声明（老故事）：整行不出现——不能给模型一个空栏去猜")
    void omitsCharacterProfileLineWhenBlank() throws Exception {
        DefaultArmoryFactory.DynamicContext ctx = new DefaultArmoryFactory.DynamicContext();

        node.doApply(commandWith("   "), ctx);

        assertFalse(ctx.getStoryContext().contains("主要人物"), ctx.getStoryContext());
        assertTrue(ctx.getStoryContext().contains("主人公：角色丙"), "其余字段不受影响");
    }
}
