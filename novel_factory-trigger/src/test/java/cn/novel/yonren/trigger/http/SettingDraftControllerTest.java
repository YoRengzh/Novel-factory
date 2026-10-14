package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.SettingDraftRequestDTO;
import cn.novel.yonren.api.dto.SettingDraftResponseDTO;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.draft.SettingDraftService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

/**
 * 设定集草稿端点测试：DTO 双向适配（各字段逐一映射）与异常到 HTTP 状态的映射。
 * 状态码映射是这里最容易写错的部分——参数错是 400、模型没给出可用结果是 502，
 * 混成一个码会让前端无法判断"该改输入还是该重试"。
 */
class SettingDraftControllerTest {

    private static final StoryVO STORY_VO = new StoryVO();

    @Mock
    private SettingDraftService settingDraftService;

    @Mock
    private StoryProperties storyProperties;

    @InjectMocks
    private SettingDraftController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(storyProperties.toStoryVO()).thenReturn(STORY_VO);
    }

    @Test
    void draft_mapsEveryDomainFieldIntoResponse() {
        when(settingDraftService.draft(any(), any())).thenReturn(result());
        SettingDraftRequestDTO request = new SettingDraftRequestDTO();
        request.setTheme("都市异能");

        SettingDraftResponseDTO response = controller.draft(request);

        assertEquals("都市异能", response.getTheme());
        assertEquals("示例书", response.getNovelTitle());
        assertEquals("冷硬悬疑", response.getStyle());
        assertEquals("世界观", response.getWorldSetting());
        assertEquals("第三人称", response.getPerspective());
        assertEquals("男频", response.getTargetAudience());
        assertEquals("压抑", response.getTone());
        assertEquals("主人公", response.getProtagonist());
        assertEquals("故事概述", response.getOutline());
        assertEquals("章节目标", response.getChapterGoal());
        assertEquals(180, response.getTotalChapters());
        assertEquals("取舍说明", response.getRationale());
        assertEquals(List.of("outline"), response.getRegenerated());
    }

    @Test
    void draft_passesTargetsAndPreviousThroughVerbatim() {
        when(settingDraftService.draft(any(), any())).thenReturn(result());
        SettingDraftRequestDTO request = new SettingDraftRequestDTO();
        request.setTheme("都市异能");
        request.setExtraHints("不要系统流");
        request.setTargets(List.of("outline"));
        request.setPrevious(java.util.Map.of("worldSetting", "既有世界观"));

        controller.draft(request);

        ArgumentCaptor<SettingDraftService.DraftRequest> captor =
                ArgumentCaptor.forClass(SettingDraftService.DraftRequest.class);
        org.mockito.Mockito.verify(settingDraftService).draft(same(STORY_VO), captor.capture());
        SettingDraftService.DraftRequest forwarded = captor.getValue();
        assertEquals("都市异能", forwarded.theme());
        assertEquals("不要系统流", forwarded.extraHints());
        assertEquals(List.of("outline"), forwarded.targets());
        assertEquals("既有世界观", forwarded.previous().get("worldSetting"));
    }

    @Test
    void draft_carriesNullTargetsAndPreviousWithoutInventingDefaults() {
        when(settingDraftService.draft(any(), any())).thenReturn(result());
        SettingDraftRequestDTO request = new SettingDraftRequestDTO();
        request.setTheme("悬疑推理");

        controller.draft(request);

        ArgumentCaptor<SettingDraftService.DraftRequest> captor =
                ArgumentCaptor.forClass(SettingDraftService.DraftRequest.class);
        org.mockito.Mockito.verify(settingDraftService).draft(any(), captor.capture());
        // "全部重生成"要用 null 表达，不能在触发层擅自补一个全字段列表——
        // 那会把"哪些字段是目标"的知识从领域层复制到触发层
        assertNull(captor.getValue().targets());
        assertNull(captor.getValue().previous());
    }

    @Test
    void draft_mapsIllegalParameterToBadRequest() {
        when(settingDraftService.draft(any(), any()))
                .thenThrow(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "一键生成设定集至少需要题材（theme）"));
        SettingDraftRequestDTO request = new SettingDraftRequestDTO();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.draft(request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("一键生成设定集至少需要题材（theme）", ex.getReason());
    }

    @Test
    void draft_mapsModelFailureToBadGateway() {
        when(settingDraftService.draft(any(), any()))
                .thenThrow(new AppException(ResponseCode.UN_ERROR.getCode(), "设定集生成失败"));
        SettingDraftRequestDTO request = new SettingDraftRequestDTO();
        request.setTheme("都市异能");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.draft(request));

        // 上游模型没给出可用结果：不是请求的问题，不该报 400 让用户去改输入
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    private static SettingDraftService.Result result() {
        SettingDraftService.DraftOutput fields = new SettingDraftService.DraftOutput();
        fields.setNovelTitle("示例书");
        fields.setStyle("冷硬悬疑");
        fields.setWorldSetting("世界观");
        fields.setPerspective("第三人称");
        fields.setTargetAudience("男频");
        fields.setTone("压抑");
        fields.setProtagonist("主人公");
        fields.setOutline("故事概述");
        fields.setChapterGoal("章节目标");
        fields.setTotalChapters(180);
        fields.setRationale("取舍说明");
        return new SettingDraftService.Result(fields, List.of("outline"));
    }
}
