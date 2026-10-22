package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.api.dto.LlmConfigSaveRequestDTO;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.types.enums.ModelScene;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Slf4j
@Component
public class LlmRuntimeConfig {

    public static final String AUTH_API_KEY = "api-key";
    public static final String AUTH_CHATGPT = "chatgpt";

    /** 覆盖文件：相对进程工作目录（与 usage 记账目录 data/log 同层） */
    private static final Path DEFAULT_FILE = Path.of("data", "llm-config-override.json");

    @Data
    public static class Overlay {
        private String authMode;
        private String baseUrl;
        private String apiKey;
        private String model;
        private Long maxTokens;
        /** 分场景覆盖：key=ModelScene.configKey；null/空表=无场景覆盖 */
        private Map<String, SceneOverlay> scenes;
        /**
         * 场景级 API 总开关：false=所有场景强制走全局 ai-api 的地址与密钥（忽略场景级 base-url/api-key）；
         * true/null=场景可自带地址密钥。null 视为"未设置"（沿用静态声明，静态亦未声明时按 true=保持旧行为，
         * 避免升级后 chapter-judge 这类第二模型族被静默拉回主族）
         */
        private Boolean sceneApiEnabled;
        /** Embedding 分区覆盖（独立供应商与账单，与聊天链路互不影响）；null=无覆盖 */
        private String embedBaseUrl;
        private String embedApiKey;
        private String embedModel;
        private Integer embedDimensions;
        /**
         * 动态资料选择器模式覆盖：llm = LLM 索引选择；vector = 向量语义检索。
         * null=未设置（回退 yml 静态 story.prompt.reference-selector.mode）。
         * 前端设置页开关读写它——安装包用户改不了 jar 内 yml，运行时覆盖是唯一路径。
         */
        private String referenceMode;

        /**
         * 功能开关覆盖：key = FeatureToggles 里的开关名（audit / paragraphAudit / revise /
         * candidate / ledgerAdjudicate / qualityReview / referenceSelector / storyMemory），
         * value = 是否开启。null 或未出现的 key 一律回退 yml 静态值。
         *
         * <p>为什么要覆盖：安装包用户改不了 jar 内的 novel-generation.yml，而这些开关决定
         * 每章要额外跑几次模型调用（审校/段落审校/修订/候选重写/账本裁决…）。默认全开是
         * 质量优先档，但单章耗时会翻数倍——必须让用户自己能在"质量/速度"之间选。
         */
        private Map<String, Boolean> toggles;

        public boolean isEmpty() {
            return !AUTH_CHATGPT.equals(authMode)
                    && StringUtils.isBlank(baseUrl) && StringUtils.isBlank(apiKey)
                    && StringUtils.isBlank(model) && maxTokens == null
                    && (scenes == null || scenes.isEmpty())
                    && sceneApiEnabled == null
                    && !hasEmbeddingOverride()
                    && StringUtils.isBlank(referenceMode)
                    && (toggles == null || toggles.isEmpty());
        }

        /** 场景级 API 开关是否打开（null 视为未设置，按 true 处理=保持旧行为） */
        public boolean sceneApiOn() {
            return !Boolean.FALSE.equals(sceneApiEnabled);
        }

        /** 是否存在嵌入覆盖（嵌入任一字段非空即视为有覆盖） */
        public boolean hasEmbeddingOverride() {
            return StringUtils.isNotBlank(embedBaseUrl) || StringUtils.isNotBlank(embedApiKey)
                    || StringUtils.isNotBlank(embedModel) || embedDimensions != null;
        }
    }

    /**
     * 资料选择器模式的生效值：运行时覆盖优先，未设置回退 yml 静态值。
     * 调用方：DynamicReferenceService 的模式裁决与缓存键——切换模式后缓存键变化，
     * 旧模式的选择结果自然失效，无需手动清缓存。
     */
    public String effectiveReferenceMode(String ymlMode) {
        Overlay o = overlay;
        if (o != null && StringUtils.isNotBlank(o.getReferenceMode())) {
            return o.getReferenceMode();
        }
        return ymlMode;
    }

    /** 单场景覆盖条目：空字段表示该字段不覆盖（回退全局覆盖/静态配置） */
    @Data
    public static class SceneOverlay {
        private String model;
        private Long maxTokens;
        private Double temperature;
        /** 场景级 base-url 覆盖；null=不覆盖（回退场景 yml / 全局） */
        private String baseUrl;
        /** 场景级 api-key 覆盖；null=保留现状，空串=显式清除（密钥不回显，空白提交必须区别于"清除"） */
        private String apiKey;

        public boolean isEmpty() {
            return StringUtils.isBlank(model) && maxTokens == null && temperature == null
                    && StringUtils.isBlank(baseUrl) && StringUtils.isBlank(apiKey);
        }
    }

    private final Path file;
    private volatile Overlay overlay;

    /**
     * 功能开关注册表（可选注入）：负责把覆盖值写回各配置 bean。
     * 为 null 时（单测/离线工具直接 new）跳过——那些场景本就不涉及运行时开关。
     */
    private FeatureToggles featureToggles;

    public LlmRuntimeConfig() {
        this(DEFAULT_FILE);
    }

    public LlmRuntimeConfig(Path file) {
        this.file = file;
        this.overlay = loadFromDisk();
    }

    /** Spring 注入：开关覆盖需要落到各配置 bean，故在构造后注入并回放一次已落盘的覆盖 */
    @Autowired(required = false)
    public void setFeatureToggles(FeatureToggles featureToggles) {
        this.featureToggles = featureToggles;
        // 重启后恢复上次保存的开关覆盖（bean 刚由 yml 绑定 = 静态值，此处叠加覆盖）
        this.featureToggles.apply(this.overlay.getToggles());
    }

    @PostConstruct
    void init() {
        if (!overlay.isEmpty()) {
            log.info("LLM 运行时覆盖已加载：{}（baseUrl={}，model={}，hasKey={}）",
                    file, overlay.getBaseUrl(), overlay.getModel(), StringUtils.isNotBlank(overlay.getApiKey()));
        }
    }

    /** 保存/合并覆盖：request 非空字段覆盖，apiKey 空则保留现值；reset=true 清空全部（含场景覆盖） */
    public synchronized LlmConfigDTO save(LlmConfigSaveRequestDTO request) {
        Overlay next = new Overlay();
        if (request.isReset()) {
            overlay = next;
            persist(next);
            log.info("LLM 运行时覆盖已清空，回退静态配置");
            return view();
        }
        Overlay current = overlay == null ? new Overlay() : overlay;
        if (request.getAuthMode() != null) {
            String requestedMode = request.getAuthMode().trim().toLowerCase();
            if (!AUTH_API_KEY.equals(requestedMode) && !AUTH_CHATGPT.equals(requestedMode)) {
                throw new IllegalArgumentException("不支持的 LLM 认证方式：" + request.getAuthMode());
            }
            next.setAuthMode(AUTH_CHATGPT.equals(requestedMode) ? AUTH_CHATGPT : null);
        } else {
            next.setAuthMode(current.getAuthMode());
        }
        if (StringUtils.isNotBlank(request.getBaseUrl())) {
            next.setBaseUrl(request.getBaseUrl().trim());
        } else {
            next.setBaseUrl(current.getBaseUrl());
        }
        if (StringUtils.isNotBlank(request.getApiKey())) {
            next.setApiKey(request.getApiKey().trim());
        } else {
            next.setApiKey(current.getApiKey());
        }
        if (StringUtils.isNotBlank(request.getModel())) {
            next.setModel(request.getModel().trim());
        } else {
            next.setModel(current.getModel());
        }
        next.setMaxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : current.getMaxTokens());
        // 场景级 API 开关：null=保持现状（全局/嵌入保存请求不带该字段，不误伤）
        next.setSceneApiEnabled(request.getSceneApiEnabled() != null
                ? request.getSceneApiEnabled() : current.getSceneApiEnabled());
        next.setScenes(mergeScenes(current, request));
        mergeEmbedding(current, next, request);
        mergeReferenceMode(current, next, request);
        mergeToggles(current, next, request);
        overlay = next;
        persist(next);
        // 开关覆盖要落到各配置 bean 上：服务层直接读 bean，这是唯一能让改动生效的路径
        if (featureToggles != null) {
            featureToggles.apply(next.getToggles());
        }
        // ⚠️ 绝不打印整个 Overlay：Lombok @Data 的 toString 会带出 apiKey / embedApiKey /
        // 每个场景的 apiKey 明文，而这条 INFO 会落到 data/log/log_info.log（保留 15 天、随排障日志外发）。
        // view() 特意做了掩码，日志这里必须同口径
        log.info("LLM 运行时覆盖已保存：baseUrl={}，model={}，maxTokens={}，hasKey={}，authMode={}；"
                        + "场景覆盖 {} 项（其中 {} 项带 apiKey）；嵌入覆盖 {}；资料选择模式 {}",
                next.getBaseUrl(), next.getModel(), next.getMaxTokens(),
                StringUtils.isNotBlank(next.getApiKey()),
                next.getAuthMode() == null ? AUTH_API_KEY : next.getAuthMode(),
                next.getScenes() == null ? 0 : next.getScenes().size(),
                countSceneApiKeys(next.getScenes()),
                next.hasEmbeddingOverride() ? "生效" : "无",
                next.getReferenceMode() == null ? "回退 yml" : next.getReferenceMode());
        return view();
    }

    /**
     * 资料选择器模式合并：request.referenceMode=null → 保持现状（其他保存请求不误伤）；
     * 空串 → 显式清除覆盖（回退 yml）；llm/vector → 写入。
     */
    private static void mergeReferenceMode(Overlay current, Overlay next, LlmConfigSaveRequestDTO request) {
        String in = request.getReferenceMode();
        if (in == null) {
            next.setReferenceMode(current.getReferenceMode());
            return;
        }
        String trimmed = StringUtils.trimToNull(in);
        if (trimmed == null) {
            next.setReferenceMode(null);                 // 空串=清除覆盖
        } else if ("llm".equalsIgnoreCase(trimmed) || "vector".equalsIgnoreCase(trimmed)) {
            next.setReferenceMode(trimmed.toLowerCase());
        } else {
            throw new IllegalArgumentException("不支持的资料选择模式：" + in + "（仅 llm / vector）");
        }
    }

    /**
     * 功能开关覆盖合并：request.toggles=null → 保持现状（其他保存请求不误伤）；
     * 空表 → 清空全部开关覆盖（回退 yml 静态）；否则全量替换（前端整表提交）。
     * 未知 key 丢弃并告警——存进去也不会被任何服务读取。
     */
    private static void mergeToggles(Overlay current, Overlay next, LlmConfigSaveRequestDTO request) {
        Map<String, Boolean> incoming = request.getToggles();
        if (incoming == null) {
            next.setToggles(current.getToggles());
            return;
        }
        Map<String, Boolean> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> e : incoming.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            if (FeatureToggles.Def.values().length > 0 && !isKnownToggle(e.getKey())) {
                log.warn("开关覆盖含未知 key: {}，已丢弃", e.getKey());
                continue;
            }
            merged.put(e.getKey(), e.getValue());
        }
        next.setToggles(merged.isEmpty() ? null : merged);
    }

    private static boolean isKnownToggle(String key) {
        for (FeatureToggles.Def def : FeatureToggles.Def.values()) {
            if (def.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 嵌入覆盖合并：resetEmbedding=true → 清空（next 字段保持 null）；
     * request.embedding=null → 保持现状（全局/场景保存请求不误伤嵌入覆盖）；
     * 否则按字段合并——null=保留现状，空串=显式清除该字段覆盖（trimToNull）。
     * 覆盖即时生效：嵌入客户端按 地址|密钥|模型 缓存，覆盖变化即换新客户端，无需重启
     */
    private static void mergeEmbedding(Overlay current, Overlay next, LlmConfigSaveRequestDTO request) {
        if (request.isResetEmbedding()) {
            return;
        }
        LlmConfigSaveRequestDTO.EmbeddingOverride in = request.getEmbedding();
        if (in == null) {
            next.setEmbedBaseUrl(current.getEmbedBaseUrl());
            next.setEmbedApiKey(current.getEmbedApiKey());
            next.setEmbedModel(current.getEmbedModel());
            next.setEmbedDimensions(current.getEmbedDimensions());
            return;
        }
        next.setEmbedBaseUrl(in.getBaseUrl() == null ? current.getEmbedBaseUrl() : StringUtils.trimToNull(in.getBaseUrl()));
        next.setEmbedApiKey(in.getApiKey() == null ? current.getEmbedApiKey() : StringUtils.trimToNull(in.getApiKey()));
        next.setEmbedModel(in.getModel() == null ? current.getEmbedModel() : StringUtils.trimToNull(in.getModel()));
        next.setEmbedDimensions(in.getDimensions() != null ? in.getDimensions() : current.getEmbedDimensions());
    }

    /**
     * 场景覆盖合并：resetScenes=true 或 request.scenes 为空表 → 清空；
     * request.scenes=null → 保持现状（全局字段保存请求不带 scenes，不误伤场景覆盖）；
     * 否则全量替换（前端矩阵整表提交，条目内空字段=清除该字段覆盖）。
     * apiKey 是全表唯一的例外（密钥不回显，前端永远拿不到原文）：null=保留该场景现有密钥，
     * 空串=显式清除——否则"改个模型顺手保存"会把已配的密钥静默清掉。
     * 未知场景 key（非 ModelScene.configKey）丢弃并告警——存进去也永远不会被路由匹配
     */
    private static Map<String, SceneOverlay> mergeScenes(Overlay current, LlmConfigSaveRequestDTO request) {
        if (request.isResetScenes()) {
            return null;
        }
        Map<String, LlmConfigSaveRequestDTO.SceneOverride> incoming = request.getScenes();
        if (incoming == null) {
            return current.getScenes();
        }
        Set<String> validKeys = Arrays.stream(ModelScene.values())
                .map(ModelScene::getConfigKey).collect(Collectors.toSet());
        Map<String, SceneOverlay> merged = new LinkedHashMap<>();
        for (Map.Entry<String, LlmConfigSaveRequestDTO.SceneOverride> entry : incoming.entrySet()) {
            if (!validKeys.contains(entry.getKey())) {
                log.warn("场景覆盖含未知场景 key: {}（非 ModelScene.configKey），已丢弃", entry.getKey());
                continue;
            }
            LlmConfigSaveRequestDTO.SceneOverride in = entry.getValue();
            if (in == null || in.isEmpty()) {
                continue;
            }
            SceneOverlay so = new SceneOverlay();
            so.setModel(StringUtils.trimToNull(in.getModel()));
            so.setMaxTokens(in.getMaxTokens());
            so.setTemperature(validTemperature(entry.getKey(), in.getTemperature()));
            so.setBaseUrl(StringUtils.trimToNull(in.getBaseUrl()));
            so.setApiKey(in.getApiKey() == null
                    ? currentApiKeyOf(current, entry.getKey())
                    : StringUtils.trimToNull(in.getApiKey()));
            // 显式清除（apiKey 空串）后可能整条变空壳：不丢弃会留下一个全 null 的占位条目，
            // 表现在矩阵上就是“已覆盖”徽标常亮但什么都不生效
            if (so.getModel() == null && so.getMaxTokens() == null && so.getTemperature() == null
                    && so.getBaseUrl() == null && so.getApiKey() == null) {
                continue;
            }
            merged.put(entry.getKey(), so);
        }
        // 不可见字段保护：apiKey 永不回显，前端“整行留空”就会省略该场景。
        // 若这条覆盖只剩一个看不见的 apiKey（model/maxTokens/temperature/baseUrl 全空），
        // 全量替换会把它连同密钥一起删掉——而占位符还写着“已覆盖（sk-***），留空保留”。
        // 只保留“纯密钥”条目：可见字段一旦有值或全部留空，仍按既有语义（整行=清除）处理
        if (current != null && current.getScenes() != null) {
            for (Map.Entry<String, SceneOverlay> entry : current.getScenes().entrySet()) {
                if (incoming.containsKey(entry.getKey())) {
                    continue;
                }
                SceneOverlay prev = entry.getValue();
                if (prev == null) {
                    continue;
                }
                boolean onlyInvisibleKey = StringUtils.isBlank(prev.getModel())
                        && prev.getMaxTokens() == null && prev.getTemperature() == null
                        && StringUtils.isBlank(prev.getBaseUrl());
                if (onlyInvisibleKey && StringUtils.isNotBlank(prev.getApiKey())) {
                    merged.put(entry.getKey(), prev);
                }
            }
        }
        return merged.isEmpty() ? null : merged;
    }

    /** 该场景现有 apiKey 覆盖（供"null=保留"合并用）；无则 null */
    private static String currentApiKeyOf(Overlay current, String sceneKey) {
        if (current == null || current.getScenes() == null) {
            return null;
        }
        SceneOverlay prev = current.getScenes().get(sceneKey);
        return prev == null ? null : prev.getApiKey();
    }

    /**
     * 采样温度合法区间 [0, 1]：本项目全部供应商（DashScope 兼容模式、DeepSeek、智谱、
     * OpenAI 兼容代理）与 scene-models 静态配置都在此区间内；Anthropic 官方 API 同样以 1.0
     * 为上限。>1 虽被部分供应商（OpenAI 原生 0-2）接受，但会显著放大采样噪声，对长篇生成
     * 只有害处——前端曾允许填 2，会让用户误以为这是可用档位，故在服务端唯一入口统一拦截
     * （前端 max 只是提示，可被绕过，校验必须在保存路径）
     */
    private static final double MIN_TEMPERATURE = 0.0;
    private static final double MAX_TEMPERATURE = 1.0;

    private static Double validTemperature(String sceneKey, Double temperature) {
        if (temperature == null) {
            return null;
        }
        if (temperature.isNaN() || temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
            throw new IllegalArgumentException("场景 " + sceneKey + " 的温度超出允许范围 ["
                    + MIN_TEMPERATURE + ", " + MAX_TEMPERATURE + "]：" + temperature
                    + "（>1 会放大采样噪声，长篇生成不适用）");
        }
        return temperature;
    }

    /** apiKey 覆盖落到 module（baseUrl + apiKey）；无覆盖原样返回 */
    public StoryVO.Module applyApi(StoryVO.Module module) {
        Overlay o = overlay;
        if (module == null || o == null || (StringUtils.isBlank(o.getBaseUrl()) && StringUtils.isBlank(o.getApiKey()))) {
            return module;
        }
        StoryVO.Module.AiApi original = module.getAiApi();
        StoryVO.Module.AiApi aiApi = new StoryVO.Module.AiApi();
        aiApi.setBaseUrl(StringUtils.isNotBlank(o.getBaseUrl()) ? o.getBaseUrl() : original == null ? null : original.getBaseUrl());
        aiApi.setApiKey(StringUtils.isNotBlank(o.getApiKey()) ? o.getApiKey() : original == null ? null : original.getApiKey());
        if (original != null) {
            aiApi.setCompletionsPath(original.getCompletionsPath());
            aiApi.setZeroDataRetention(original.getZeroDataRetention());
        }
        StoryVO.Module copy = new StoryVO.Module();
        copy.setAiApi(aiApi);
        copy.setChatModel(module.getChatModel());
        copy.setEmbeddingApi(module.getEmbeddingApi());
        copy.setUnifiedModelEnabled(module.getUnifiedModelEnabled());
        copy.setSceneModels(module.getSceneModels());
        // 场景级模型降级链必须随拷贝走——否则一旦存在运行时覆盖（前端设置面板），
        // 降级链就被静默丢弃，挂机时的"换模型救调用"能力会无声失效
        copy.setModelFallbacks(module.getModelFallbacks());
        return copy;
    }

    /**
     * Embedding 覆盖落到 module（独立分区，绝不碰聊天链路的 aiApi/chatModel/scenes）。
     * 无覆盖原样返回；嵌入客户端按 地址|密钥|模型 缓存，覆盖变化即换新客户端，保存后下次向量调用即时生效
     */
    public StoryVO.Module applyEmbedding(StoryVO.Module module) {
        Overlay o = overlay;
        if (module == null || module.getEmbeddingApi() == null || o == null || !o.hasEmbeddingOverride()) {
            return module;
        }
        StoryVO.Module.EmbeddingApi original = module.getEmbeddingApi();
        StoryVO.Module.EmbeddingApi api = new StoryVO.Module.EmbeddingApi();
        api.setBaseUrl(StringUtils.isNotBlank(o.getEmbedBaseUrl()) ? o.getEmbedBaseUrl() : original.getBaseUrl());
        api.setApiKey(StringUtils.isNotBlank(o.getEmbedApiKey()) ? o.getEmbedApiKey() : original.getApiKey());
        api.setEmbeddingsPath(original.getEmbeddingsPath());
        api.setBatchSize(original.getBatchSize());
        api.setModel(StringUtils.isNotBlank(o.getEmbedModel()) ? o.getEmbedModel() : original.getModel());
        api.setDimensions(o.getEmbedDimensions() != null ? o.getEmbedDimensions() : original.getDimensions());
        StoryVO.Module copy = new StoryVO.Module();
        copy.setAiApi(module.getAiApi());
        copy.setChatModel(module.getChatModel());
        copy.setEmbeddingApi(api);
        copy.setUnifiedModelEnabled(module.getUnifiedModelEnabled());
        copy.setSceneModels(module.getSceneModels());
        copy.setModelFallbacks(module.getModelFallbacks());
        return copy;
    }

    /** model/maxTokens 覆盖到已解析的场景模型（unified 或 scene）；无覆盖原样返回 */
    public StoryVO.Module.ChatModel applyModel(StoryVO.Module.ChatModel selected) {
        Overlay o = overlay;
        if (selected == null || o == null || (StringUtils.isBlank(o.getModel()) && o.getMaxTokens() == null)) {
            return selected;
        }
        StoryVO.Module.ChatModel copy = new StoryVO.Module.ChatModel();
        // 场景级 baseUrl/apiKey 必须原样保留（第二模型族如 chapter-judge 依赖它路由到异供应商）：
        // 覆盖只作用于 model/maxTokens，绝不吞地址与密钥，否则覆盖后评审静默回退主族
        copy.setBaseUrl(selected.getBaseUrl());
        copy.setApiKey(selected.getApiKey());
        copy.setCompletionsPath(selected.getCompletionsPath());
        copy.setModel(StringUtils.isNotBlank(o.getModel()) ? o.getModel() : selected.getModel());
        copy.setMaxTokens(o.getMaxTokens() != null ? o.getMaxTokens() : selected.getMaxTokens());
        copy.setTemperature(selected.getTemperature());
        copy.setEnableThinking(selected.getEnableThinking());
        return copy;
    }

    /**
     * 场景感知的模型覆盖：先按全局覆盖合成（applyModel(selected)），再叠加该场景的覆盖条目——
     * 按字段优先级 场景覆盖 &gt; 全局覆盖 &gt; 静态。场景未配置覆盖时与全局路径完全一致。
     *
     * <p>场景级 API 开关关闭（sceneApiEnabled=false）时，场景自带的 base-url/api-key/completionsPath
     * 一律抹掉（置 null）——网关据此回落全局 ai-api，实现"一个地址密钥打天下"。
     * 注意：只抹地址密钥，不抹模型名（开关管的是"走哪个端点"，不是"用哪个模型"）
     */
    public StoryVO.Module.ChatModel applyModel(ModelScene scene, StoryVO.Module.ChatModel selected) {
        StoryVO.Module.ChatModel merged = applyModel(selected);
        SceneOverlay so = sceneOverlay(scene);
        StoryVO.Module.ChatModel result = merged;
        if (so != null) {
            result = copyOf(merged);
            result.setModel(StringUtils.isNotBlank(so.getModel()) ? so.getModel() : merged.getModel());
            result.setMaxTokens(so.getMaxTokens() != null ? so.getMaxTokens() : merged.getMaxTokens());
            result.setTemperature(so.getTemperature() != null ? so.getTemperature() : merged.getTemperature());
            // 场景级地址密钥覆盖（仅在开关打开时生效；关闭时下面的统一抹除接管）
            if (sceneApiOn() && StringUtils.isNotBlank(so.getBaseUrl())) {
                result.setBaseUrl(so.getBaseUrl());
            }
            if (sceneApiOn() && StringUtils.isNotBlank(so.getApiKey())) {
                result.setApiKey(so.getApiKey());
            }
        }
        if (!sceneApiOn()) {
            // 强制全局端点：抹掉场景层来源的地址/密钥/补全路径，交给网关回落 module.aiApi
            result = copyOf(result);
            result.setBaseUrl(null);
            result.setApiKey(null);
            result.setCompletionsPath(null);
        }
        return result == merged ? merged : result;
    }

    private static StoryVO.Module.ChatModel copyOf(StoryVO.Module.ChatModel source) {
        StoryVO.Module.ChatModel copy = new StoryVO.Module.ChatModel();
        copy.setModel(source.getModel());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setTemperature(source.getTemperature());
        copy.setEnableThinking(source.getEnableThinking());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setApiKey(source.getApiKey());
        copy.setCompletionsPath(source.getCompletionsPath());
        return copy;
    }

    /** 场景级 API 开关是否打开（运行时覆盖优先；未设置时读 yml 声明，再缺省按 true=保持旧行为） */
    private boolean sceneApiOn() {
        Overlay o = overlay;
        return o == null || o.sceneApiOn();
    }

    /** 该场景的运行时覆盖条目；场景为 null 或未覆盖时返回 null */
    private SceneOverlay sceneOverlay(ModelScene scene) {
        Overlay o = overlay;
        if (scene == null || o == null || o.getScenes() == null) {
            return null;
        }
        SceneOverlay so = o.getScenes().get(scene.getConfigKey());
        return so == null || so.isEmpty() ? null : so;
    }

    /** 当前覆盖视图（apiKey 只给掩码） */
    public LlmConfigDTO view() {
        Overlay o = overlay;
        LlmConfigDTO dto = new LlmConfigDTO();
        dto.setAuthMode(usesChatGptAuth() ? AUTH_CHATGPT : AUTH_API_KEY);
        // 场景级 API 开关：覆盖里没设过则回 null（前端按"未设置=场景可自带 API"展示）
        dto.setSceneApiEnabled(o == null ? null : o.getSceneApiEnabled());
        // 资料选择器模式覆盖：覆盖里没设过也回 null（前端展示"未设置=按 yml 静态值"）
        dto.setReferenceMode(o == null ? null : o.getReferenceMode());
        if (o == null || o.isEmpty()) {
            dto.setConfigured(false);
            return dto;
        }
        dto.setConfigured(true);
        dto.setBaseUrl(o.getBaseUrl());
        dto.setModel(o.getModel());
        dto.setMaxTokens(o.getMaxTokens());
        if (StringUtils.isNotBlank(o.getApiKey())) {
            dto.setHasKey(true);
            dto.setApiKeyMasked(mask(o.getApiKey()));
        }
        return dto;
    }

    /** 主聊天模型是否使用 Continue with ChatGPT；Embedding 始终保持独立配置。 */
    public boolean usesChatGptAuth() {
        Overlay current = overlay;
        return current != null && AUTH_CHATGPT.equals(current.getAuthMode());
    }

    /**
     * 全量视图：全局覆盖视图 + 场景模型矩阵（静态生效值 + 运行时覆盖 + 合成生效值）。
     * 静态侧取 yml 绑定的 StoryProperties.module；unifiedModelEnabled=true 时矩阵展示
     * "全部走统一模型"的提示，但行内静态值仍按 scene-models 回落逻辑给出（供切换开关后参考）
     */
    public LlmConfigDTO view(StoryProperties statics) {
        return view(statics, null);
    }

    /**
     * 全量视图（含资料选择器静态值）：referenceSelectorStatics 供前端下拉直接显示
     * 当前生效值（覆盖优先，未覆盖显示 yml 静态值）——用户视角只面对 vector/llm 二选一。
     */
    public LlmConfigDTO view(StoryProperties statics, String referenceSelectorStatics) {
        LlmConfigDTO dto = view();
        // 生效值：运行时覆盖 > yml 静态（前端下拉据此回显，不再暴露"未覆盖"概念）
        dto.setEffectiveReferenceMode(effectiveReferenceMode(referenceSelectorStatics));
        // 功能开关：静态值 + 覆盖值 + 生效值（开关 bean 未注入时返回空表，前端不渲染该区）
        dto.setToggles(featureToggles == null ? List.of() : featureToggles.view());
        StoryVO.Module module = statics == null ? null : statics.getModule();
        if (module == null) {
            return dto;
        }
        dto.setUnifiedModelEnabled(!Boolean.FALSE.equals(module.getUnifiedModelEnabled()));
        dto.setUnified(toModelView(module.getChatModel()));
        dto.setEmbedding(toEmbeddingView(module.getEmbeddingApi()));

        List<LlmConfigDTO.SceneView> rows = new ArrayList<>();
        for (ModelScene scene : ModelScene.values()) {
            LlmConfigDTO.SceneView row = new LlmConfigDTO.SceneView();
            row.setKey(scene.getConfigKey());
            row.setLabel(scene.getLabel());
            StoryVO.Module.ChatModel staticScene = module.getSceneModels() == null
                    ? null : module.getSceneModels().get(scene.getConfigKey());
            row.setSceneConfigured(staticScene != null);
            row.setIndependentApi(staticScene != null
                    && (StringUtils.isNotBlank(staticScene.getBaseUrl())
                        || StringUtils.isNotBlank(staticScene.getApiKey())));
            StoryVO.Module.ChatModel staticBase = staticScene != null ? staticScene : module.getChatModel();
            if (staticBase != null) {
                row.setStaticModel(staticBase.getModel());
                row.setStaticMaxTokens(staticBase.getMaxTokens());
                row.setStaticTemperature(staticBase.getTemperature());
            }
            SceneOverlay so = sceneOverlay(scene);
            if (so != null) {
                row.setOverridden(true);
                row.setOverrideModel(so.getModel());
                row.setOverrideMaxTokens(so.getMaxTokens());
                row.setOverrideTemperature(so.getTemperature());
                row.setOverrideBaseUrl(StringUtils.isNotBlank(so.getBaseUrl()) ? so.getBaseUrl() : null);
                if (StringUtils.isNotBlank(so.getApiKey())) {
                    row.setOverrideHasKey(true);
                    row.setOverrideApiKeyMasked(mask(so.getApiKey()));
                }
            }
            rows.add(row);
        }
        dto.setScenes(rows);
        return dto;
    }

    private static LlmConfigDTO.ModelView toModelView(StoryVO.Module.ChatModel chatModel) {
        if (chatModel == null) {
            return null;
        }
        LlmConfigDTO.ModelView view = new LlmConfigDTO.ModelView();
        view.setModel(chatModel.getModel());
        view.setMaxTokens(chatModel.getMaxTokens());
        view.setTemperature(chatModel.getTemperature());
        view.setEnableThinking(chatModel.getEnableThinking());
        return view;
    }

    /** Embedding 分区视图：静态生效值 + 运行时覆盖（密钥只给掩码） */
    private LlmConfigDTO.EmbeddingView toEmbeddingView(StoryVO.Module.EmbeddingApi embeddingApi) {
        if (embeddingApi == null) {
            return null;
        }
        LlmConfigDTO.EmbeddingView view = new LlmConfigDTO.EmbeddingView();
        view.setConfigured(true);
        view.setModel(embeddingApi.getModel());
        view.setBaseUrl(embeddingApi.getBaseUrl());
        if (StringUtils.isNotBlank(embeddingApi.getApiKey())) {
            view.setHasKey(true);
            view.setApiKeyMasked(mask(embeddingApi.getApiKey()));
        }
        view.setDimensions(embeddingApi.getDimensions());
        Overlay o = overlay;
        if (o != null && o.hasEmbeddingOverride()) {
            view.setOverridden(true);
            view.setOverrideBaseUrl(StringUtils.isNotBlank(o.getEmbedBaseUrl()) ? o.getEmbedBaseUrl() : null);
            if (StringUtils.isNotBlank(o.getEmbedApiKey())) {
                view.setOverrideHasKey(true);
                view.setOverrideApiKeyMasked(mask(o.getEmbedApiKey()));
            }
            view.setOverrideModel(StringUtils.isNotBlank(o.getEmbedModel()) ? o.getEmbedModel() : null);
            view.setOverrideDimensions(o.getEmbedDimensions());
        }
        return view;
    }

    /** 带 apiKey 的场景覆盖数（只用于日志计数，不输出值） */
    private static int countSceneApiKeys(Map<String, SceneOverlay> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (SceneOverlay scene : scenes.values()) {
            if (scene != null && StringUtils.isNotBlank(scene.getApiKey())) {
                count++;
            }
        }
        return count;
    }

    private static String mask(String apiKey) {
        if (apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }

    private void persist(Overlay o) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(o), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("LLM 运行时覆盖落盘失败（仅影响本次设置持久化，不影响内存生效）：{}", e.getMessage());
        }
    }

    private Overlay loadFromDisk() {
        try {
            if (!Files.isRegularFile(file)) {
                return new Overlay();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(content)) {
                return new Overlay();
            }
            Overlay loaded = JSON.parseObject(content, Overlay.class);
            return loaded == null ? new Overlay() : loaded;
        } catch (Exception e) {
            log.warn("LLM 运行时覆盖加载失败，忽略（回退静态配置）：{}", e.getMessage());
            return new Overlay();
        }
    }
}
