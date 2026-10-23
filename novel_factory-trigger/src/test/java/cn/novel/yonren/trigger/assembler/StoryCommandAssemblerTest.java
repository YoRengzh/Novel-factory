package cn.novel.yonren.trigger.assembler;

import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO→命令装配测试：worldId 校验（空放行/合法通过/非法 400）
 */
class StoryCommandAssemblerTest {

    private final StoryProperties properties = new StoryProperties();

    @Test
    void toCommand_nullWorldId_passesThrough() {
        StoryGenerateRequestDTO request = new StoryGenerateRequestDTO();
        request.setNovel_title("测试");

        ArmoryCommandEntity command = StoryCommandAssembler.toCommand(request, properties);

        assertNotNull(command);
        assertNull(command.getStoryContextEntity().getWorldId());
    }

    @Test
    void toCommand_blankWorldId_passesThrough() {
        StoryGenerateRequestDTO request = new StoryGenerateRequestDTO();
        request.setWorldId("  ");

        ArmoryCommandEntity command = StoryCommandAssembler.toCommand(request, properties);

        assertNull(command.getStoryContextEntity().getWorldId());
    }

    @Test
    void toCommand_validWorldId_copiedToContext() {
        StoryGenerateRequestDTO request = new StoryGenerateRequestDTO();
        request.setWorldId("urban-01");

        ArmoryCommandEntity command = StoryCommandAssembler.toCommand(request, properties);

        assertEquals("urban-01", command.getStoryContextEntity().getWorldId());
    }

    @Test
    void toCommand_illegalWorldId_throwsAppException() {
        StoryGenerateRequestDTO request = new StoryGenerateRequestDTO();
        request.setWorldId("世界1");

        AppException e = assertThrows(AppException.class,
                () -> StoryCommandAssembler.toCommand(request, properties));
        assertTrue(e.getInfo().contains("worldId 格式非法"));
    }

    @Test
    void toCommand_worldIdWithSpecialChars_throwsAppException() {
        StoryGenerateRequestDTO request = new StoryGenerateRequestDTO();
        request.setWorldId("a/b/c");

        assertThrows(AppException.class,
                () -> StoryCommandAssembler.toCommand(request, properties));
    }

    @Test
    void toCommand_copiesStorySpecificCheatMechanismSettings() {
        StoryGenerateRequestDTO request = new StoryGenerateRequestDTO();
        request.setHasCheatMechanism(true);
        request.setCheatMechanismName("剑意提取系统");
        request.setCheatUsageInterval(4);

        ArmoryCommandEntity command = StoryCommandAssembler.toCommand(request, properties);

        assertTrue(command.getStoryVO().getFeatures().getHasCheatMechanism());
        assertEquals("剑意提取系统", command.getStoryVO().getFeatures().getCheatMechanismName());
        assertEquals(4, command.getStoryVO().getFeatures().getCheatUsageInterval());
    }
}
