package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.SettingDraftRequestDTO;
import cn.novel.yonren.api.dto.SettingDraftResponseDTO;
import cn.novel.yonren.domain.novel.service.armory.draft.SettingDraftService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;


@Slf4j
@RestController
@RequestMapping("/api/setting-draft")
public class SettingDraftController {

    @Resource
    private SettingDraftService settingDraftService;

    @PostMapping
    public SettingDraftResponseDTO draft(@RequestBody SettingDraftRequestDTO request) {
        SettingDraftResponseDTO dto;
        try {
            dto = settingDraftService.draftDto(request);
        } catch (AppException e) {
            // 题材为空 / targets 写了未知字段 → 400（前端能自行改正）
            // 两次尝试都没产出可用 JSON → 502（上游模型没给出可用结果，不是请求的问题）
            HttpStatus status = ResponseCode.ILLEGAL_PARAMETER.getCode().equals(e.getCode())
                    ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, e.getInfo());
        }

        log.info("设定集草稿已产出，题材: {}，重生成字段: {}", request.getTheme(), dto.getRegenerated());
        return dto;
    }
}
