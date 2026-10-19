package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.api.dto.LlmConfigSaveRequestDTO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * LLM 运行时配置端点：前端设置面板读写 baseUrl/apiKey/model/maxTokens 全局覆盖与分场景覆盖。
 * GET 返回全局覆盖（apiKey 只回掩码）+ 场景模型矩阵（yml 静态生效值 + 运行时覆盖 + 合成生效值）；
 * PUT 保存/合并（全局 apiKey 空则保留现值；scenes 全量替换场景覆盖，resetScenes 仅清场景，reset=true 清空全部）。
 * 覆盖仅作用于聊天 LLM 调用链路，生效即时、持久化到 data/llm-config-override.json，重启后仍保留
 */
@Slf4j
@RestController
@RequestMapping("/api/config/llm")
public class LlmConfigController {

    @Resource
    private LlmRuntimeConfig llmRuntimeConfig;

    @Resource
    private StoryProperties storyProperties;

    @Resource
    private cn.novel.yonren.domain.novel.model.valobj.properties.ReferenceSelectorProperties referenceSelectorProperties;

    @GetMapping
    public LlmConfigDTO get() {
        try {
            return llmRuntimeConfig.view(storyProperties, referenceSelectorProperties.getMode());
        } catch (Exception e) {
            log.error("LLM 运行时配置读取失败", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "配置读取失败：" + e.getMessage());
        }
    }

    @PutMapping
    public LlmConfigDTO save(@RequestBody LlmConfigSaveRequestDTO request) {
        try {
            llmRuntimeConfig.save(request);
            // 保存后回全量视图（含场景矩阵），前端整页刷新当前生效状态
            return llmRuntimeConfig.view(storyProperties, referenceSelectorProperties.getMode());
        } catch (Exception e) {
            log.error("LLM 运行时配置保存失败", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "配置保存失败：" + e.getMessage());
        }
    }
}