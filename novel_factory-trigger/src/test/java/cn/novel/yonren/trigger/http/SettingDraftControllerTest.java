package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.SettingDraftRequestDTO;
import cn.novel.yonren.api.dto.SettingDraftResponseDTO;
import cn.novel.yonren.domain.novel.service.armory.draft.SettingDraftService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设定集草稿端点 HTTP 层测试（分层重构后）：
 * 字段映射与透传断言已迁移至 domain 的 SettingDraftServiceTest.draftDto 系列，
 * 本类只验证异常到 HTTP 状态的映射——参数错是 400、模型没给出可用结果是 502，
 * 混成一个码会让前端无法判断"该改输入还是该重试"。
 */
class SettingDraftControllerTest {

    @Mock
    private SettingDraftService settingDraftService;

    @InjectMocks
    private SettingDraftController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void draft_returnsDtoFromFacade() {
        SettingDraftResponseDTO dto = new SettingDraftResponseDTO();
        dto.setTheme("都市异能");
        dto.setNovelTitle("示例书");
        dto.setRegenerated(List.of("outline"));
        when(settingDraftService.draftDto(any())).thenReturn(dto);

        SettingDraftRequestDTO request = new SettingDraftRequestDTO();
        request.setTheme("都市异能");

        SettingDraftResponseDTO response = controller.draft(request);

        assertEquals("都市异能", response.getTheme());
        assertEquals("示例书", response.getNovelTitle());
        verify(settingDraftService).draftDto(request);
    }

    @Test
    void draft_mapsIllegalParameterToBadRequest() {
        when(settingDraftService.draftDto(any()))
                .thenThrow(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "一键生成设定集至少需要题材（theme）"));
        SettingDraftRequestDTO request = new SettingDraftRequestDTO();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.draft(request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("一键生成设定集至少需要题材（theme）", ex.getReason());
    }

    @Test
    void draft_mapsModelFailureToBadGateway() {
        when(settingDraftService.draftDto(any()))
                .thenThrow(new AppException(ResponseCode.UN_ERROR.getCode(), "设定集生成失败"));
        SettingDraftRequestDTO request = new SettingDraftRequestDTO();
        request.setTheme("都市异能");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.draft(request));

        // 上游模型没给出可用结果：不是请求的问题，不该报 400 让用户去改输入
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }
}
