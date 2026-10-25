package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.api.dto.LlmConfigSaveRequestDTO;
import cn.novel.yonren.api.dto.ModelFetchRequestDTO;
import cn.novel.yonren.domain.novel.adapter.llm.ModelCatalogPort;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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

    @Resource
    private ModelCatalogPort modelCatalogPort;

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
        } catch (IllegalArgumentException e) {
            // 配置值不合法（引用不存在/温度越界等）：400 而非 500，前端提示用户改
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("LLM 运行时配置保存失败", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "配置保存失败：" + e.getMessage());
        }
    }

    /**
     * 拉取供应商模型列表（OpenAI 兼容 /v1/models）：设置页"获取模型"按钮。
     * 显式地址密钥优先于 providerId（保存前可试连）；密钥只在后端拼请求头，不进日志
     */
    @PostMapping("/models")
    public List<String> fetchModels(@RequestBody ModelFetchRequestDTO request) {
        String baseUrl = StringUtils.trimToNull(request.getBaseUrl());
        String apiKey = StringUtils.trimToNull(request.getApiKey());
        String providerId = StringUtils.trimToNull(request.getProviderId());
        if (providerId != null) {
            LlmRuntimeConfig.ProviderOverlay provider = llmRuntimeConfig.providerOf(providerId);
            if (provider == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "中转站不存在：" + providerId);
            }
            if (baseUrl == null) {
                baseUrl = provider.getBaseUrl();
            }
            if (apiKey == null) {
                apiKey = provider.getApiKey();
            }
        }
        if (baseUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 Base URL（填显式地址或指定中转站 id）");
        }
        try {
            return modelCatalogPort.fetchModels(baseUrl, apiKey);
        } catch (Exception e) {
            // 上游不可达/鉴权失败是用户可自行修复的问题：502 + 原因，密钥不在任何消息里
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型列表拉取失败：" + e.getMessage());
        }
    }
}