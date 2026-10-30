package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.api.dto.LlmConfigSaveRequestDTO;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.types.enums.LlmProtocol;
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
        /**
         * 手动模式接口协议覆盖（LlmProtocol.code：openai/anthropic/responses）：
         * null=不覆盖（回退 yml 静态 ai-api.protocol）；全局选中中转站时以该站协议优先
         */
        private String protocol;
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

        /**
         * 全局兜底模型覆盖（2026-10-23）：所有场景共用这一个兜底，字段级合并
         * （null=沿用 yml 静态值）。支持独立 base-url / api-key / max-tokens——
         * 兜底的意义就在于"换一个供应商"，地址密钥必须能跟着变。
         */
        private SceneOverlay fallback;

        /**
         * 中转站注册表（OpenAI 兼容供应商）：key=id（前端生成），值含显示名/地址/密钥。
         * 注册表本身不直接生效——生效路径是全局 providerId 引用（见 applyApi）
         */
        private Map<String, ProviderOverlay> providers;

        /** 全局选中的中转站 id；null/空=手动模式（沿用 baseUrl/apiKey 覆盖字段与 yml 静态值） */
        private String providerId;

        public boolean isEmpty() {
            return !AUTH_CHATGPT.equals(authMode)
                    && StringUtils.isBlank(baseUrl) && StringUtils.isBlank(protocol)
                    && StringUtils.isBlank(apiKey)
                    && StringUtils.isBlank(model) && maxTokens == null
                    && (scenes == null || scenes.isEmpty())
                    && fallback == null
                    && sceneApiEnabled == null
                    && !hasEmbeddingOverride()
                    && StringUtils.isBlank(referenceMode)
                    && (toggles == null || toggles.isEmpty())
                    && (providers == null || providers.isEmpty());
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
        /**
         * 场景级中转站引用（推荐配法）：指向 providers 里的 id。生效时该站的
         * baseUrl/apiKey/protocol 一并接管本场景——场景要指向异族评审模型只需选站，
         * 不必手填地址密钥。null=不引用（走 baseUrl/apiKey 手动字段或回退全局）
         */
        private String providerId;
        /** 场景级 base-url 覆盖；null=不覆盖（回退场景 yml / 全局） */
        private String baseUrl;
        /** 场景级 api-key 覆盖；null=保留现状，空串=显式清除（密钥不回显，空白提交必须区别于"清除"） */
        private String apiKey;

        public boolean isEmpty() {
            return StringUtils.isBlank(model) && maxTokens == null && temperature == null
                    && StringUtils.isBlank(providerId)
                    && StringUtils.isBlank(baseUrl) && StringUtils.isBlank(apiKey);
        }
    }

    /** 中转站条目：密钥与全局覆盖同款"null=保留现状"合并语义（见 mergeProviders） */
    @Data
    public static class ProviderOverlay {
        /** 显示名（前端展示用） */
        private String name;
        /** OpenAI 兼容 Base URL（可带可不带 /v1，取模型列表时自动适配路径） */
        private String baseUrl;
        /** 接口协议（LlmProtocol.code）：null/blank = openai；决定网关走哪套传输实现 */
        private String protocol;
        private String apiKey;
        /**
         * 最近一次「获取模型」拉到的模型名（服务端缓存）：场景矩阵据此反查
         * 「这个模型在哪家中转站可用」。前端保存注册表不带它 → 合并时沿用旧值
         */
        private List<String> models;
    }

    private final Path file;
    private volatile Overlay overlay;
    /** 同名兜底告警只发一次：applyFallback 在每次 LLM 调用都执行，不设防会逐条刷 WARN */
    private volatile boolean sameNameFallbackWarned;

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
        // 协议覆盖：null=保持现状（其他保存请求不带该字段，不误伤）；空串=清除回 openai；
        // 值本身交给 LlmProtocol.parse 校验（未知值 400 给前端，不静默落到错误协议）
        if (request.getProtocol() != null) {
            String trimmed = StringUtils.trimToNull(request.getProtocol());
            next.setProtocol(trimmed == null ? null : LlmProtocol.parse(trimmed).getCode());
        } else {
            next.setProtocol(current.getProtocol());
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
        next.setFallback(mergeFallback(current, request));
        next.setProviders(mergeProviders(current, request));
        next.setProviderId(mergeProviderId(current, next, request));
        // 删除站点走定点解除：只清该站引用，绝不重放整张场景覆盖表（否则其它场景会被误清）
        detachProviderReferences(next, request.getDetachProviderId());
        // 场景/兜底的中转站引用必须存在：悬空引用会被 providerOf 静默忽略，
        // 于是界面显示"已选站"、实际却走另一套地址（删除站点后尤其容易留下）
        validateSceneProviders(next);
        mergeEmbedding(current, next, request);
        mergeReferenceMode(current, next, request);
        mergeToggles(current, next, request);
        overlay = next;
        // 新一轮保存后重置同名兜底告警：再次配错时还能提醒一次
        sameNameFallbackWarned = false;
        persist(next);
        // 开关覆盖要落到各配置 bean 上：服务层直接读 bean，这是唯一能让改动生效的路径
        if (featureToggles != null) {
            featureToggles.apply(next.getToggles());
        }
        // ⚠️ 绝不打印整个 Overlay：Lombok @Data 的 toString 会带出 apiKey / embedApiKey /
        // 每个场景的 apiKey 明文，而这条 INFO 会落到 data/log/log_info.log（保留 15 天、随排障日志外发）。
        // view() 特意做了掩码，日志这里必须同口径
        log.info("LLM 运行时覆盖已保存：baseUrl={}，model={}，maxTokens={}，hasKey={}，authMode={}；"
                        + "场景覆盖 {} 项（其中 {} 项带 apiKey）；中转站 {} 个（全局引用 {}）；"
                        + "嵌入覆盖 {}；资料选择模式 {}",
                next.getBaseUrl(), next.getModel(), next.getMaxTokens(),
                StringUtils.isNotBlank(next.getApiKey()),
                next.getAuthMode() == null ? AUTH_API_KEY : next.getAuthMode(),
                next.getScenes() == null ? 0 : next.getScenes().size(),
                countSceneApiKeys(next.getScenes()),
                next.getProviders() == null ? 0 : next.getProviders().size(),
                StringUtils.isBlank(next.getProviderId()) ? "手动模式" : next.getProviderId(),
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
     * 全局兜底模型覆盖合并（整表替换，与场景矩阵同款语义）：
     * request.fallback=null → 保持现状（其他保存请求不误伤）；
     * resetFallback=true → 清空；否则全量替换——**缺席字段=清除该字段覆盖**
     * （前端兜底表单总是整表提交，若用 null=保留语义，用户清空输入框保存后覆盖不会消失，
     * 形成界面上看不见的残留状态）。
     * apiKey 是唯一例外（密钥不回显）：null=保留现有密钥，空串=显式清除。
     */
    private static SceneOverlay mergeFallback(Overlay current, LlmConfigSaveRequestDTO request) {
        if (request.isResetFallback()) {
            return null;
        }
        LlmConfigSaveRequestDTO.SceneOverride in = request.getFallback();
        if (in == null) {
            return current.getFallback();
        }
        SceneOverlay prev = current.getFallback();
        SceneOverlay next = new SceneOverlay();
        next.setModel(StringUtils.trimToNull(in.getModel()));
        next.setMaxTokens(in.getMaxTokens());
        next.setTemperature(in.getTemperature() == null ? null
                : validTemperature("fallback", in.getTemperature()));
        String providerId = StringUtils.trimToNull(in.getProviderId());
        next.setProviderId(providerId);
        next.setBaseUrl(StringUtils.trimToNull(in.getBaseUrl()));
        next.setApiKey(in.getApiKey() == null
                ? (prev == null ? null : prev.getApiKey()) : StringUtils.trimToNull(in.getApiKey()));
        // 兜底选中转站：清掉手动地址密钥，避免旧值在生效链路里反压站配置
        // （applyFallback 按"站 > 手动 > 静态"取首个非空，残留旧值本身不会压过站，
        //  但界面上看不到的残留会让"切回跟随主模型"后突然用上旧地址）
        if (providerId != null) {
            next.setBaseUrl(null);
            next.setApiKey(null);
        }
        return next.isEmpty() ? null : next;
    }

    /**
     * 删除站点时定点解除引用：清掉全局/场景/兜底里指向该站的 providerId 及旧地址密钥，
     * 其余字段原样保留。为避免保存失败时污染旧 overlay，涉及变更的条目都新建对象，
     * 不在 current 共享的 Map/SceneOverlay 上原地修改。
     */
    private static void detachProviderReferences(Overlay next, String detachProviderId) {
        String id = StringUtils.trimToNull(detachProviderId);
        if (id == null) {
            return;
        }
        if (id.equals(next.getProviderId())) {
            next.setProviderId(null);
        }
        if (next.getScenes() != null) {
            Map<String, SceneOverlay> updated = new LinkedHashMap<>();
            for (Map.Entry<String, SceneOverlay> entry : next.getScenes().entrySet()) {
                SceneOverlay so = entry.getValue();
                if (so != null && id.equals(so.getProviderId())) {
                    SceneOverlay detached = new SceneOverlay();
                    detached.setModel(so.getModel());
                    detached.setMaxTokens(so.getMaxTokens());
                    detached.setTemperature(so.getTemperature());
                    // providerId/baseUrl/apiKey 全部清除：解除后必须回退全局，
                    // 而不是让旧的场景级手动地址突然重新生效
                    if (!detached.isEmpty()) {
                        updated.put(entry.getKey(), detached);
                    }
                } else {
                    updated.put(entry.getKey(), so);
                }
            }
            next.setScenes(updated.isEmpty() ? null : updated);
        }
        SceneOverlay fallback = next.getFallback();
        if (fallback != null && id.equals(fallback.getProviderId())) {
            SceneOverlay detached = new SceneOverlay();
            detached.setModel(fallback.getModel());
            detached.setMaxTokens(fallback.getMaxTokens());
            detached.setTemperature(fallback.getTemperature());
            next.setFallback(detached.isEmpty() ? null : detached);
        }
    }

    /**
     * 校验场景/兜底的中转站引用都存在于本次保存后的注册表。
     *
     * <p>为什么不静默丢弃：丢弃后该场景会静默回落到全局端点，而界面仍显示"已选站"——
     * 用户看到的是 A、实际走 B，属于最难排查的一类配置错位。保存时直接拒绝，
     * 前端能立刻提示"该站不存在"。全局 providerId 的 {@link #mergeProviderId} 同款处理。
     *
     * <p>删除中转站的路径：前端删除时会把引用一并清掉（见 deleteProvider），
     * 因此正常 UI 操作不会撞到这条；它兜住的是直接调 API / 并发保存等异常路径。
     */
    private static void validateSceneProviders(Overlay next) {
        Map<String, ProviderOverlay> providers = next.getProviders();
        if (next.getScenes() != null) {
            for (Map.Entry<String, SceneOverlay> entry : next.getScenes().entrySet()) {
                String providerId = entry.getValue() == null ? null : entry.getValue().getProviderId();
                if (StringUtils.isNotBlank(providerId)
                        && (providers == null || !providers.containsKey(providerId))) {
                    throw new IllegalArgumentException("场景 " + entry.getKey() + " 引用的中转站不存在："
                            + providerId + "（请先在注册表中保存它，或清除该场景的选站）");
                }
            }
        }
        SceneOverlay fallback = next.getFallback();
        if (fallback != null && StringUtils.isNotBlank(fallback.getProviderId())
                && (providers == null || !providers.containsKey(fallback.getProviderId()))) {
            throw new IllegalArgumentException("兜底模型引用的中转站不存在："
                    + fallback.getProviderId() + "（请先在注册表中保存它，或清除兜底的选站）");
        }
    }

    /**
     * 中转站注册表合并（全量替换，与场景矩阵同款语义）：request.providers=null → 保持现状
     * （其它保存请求不误伤）；resetProviders=true 或空表 → 清空；否则全量替换。
     * apiKey 同款不可见字段保护：null=保留现有密钥，空串=显式清除。
     * id 只收 [a-z0-9-]（前端生成 slug）：它是 JSON 键与 providerId 引用的锚点，
     * 放宽命名会连带把引用校验、URL 安全问题引进来
     */
    private static Map<String, ProviderOverlay> mergeProviders(Overlay current, LlmConfigSaveRequestDTO request) {
        if (request.isResetProviders()) {
            return null;
        }
        Map<String, LlmConfigSaveRequestDTO.ProviderSave> incoming = request.getProviders();
        if (incoming == null) {
            return current.getProviders();
        }
        Map<String, ProviderOverlay> merged = new LinkedHashMap<>();
        for (Map.Entry<String, LlmConfigSaveRequestDTO.ProviderSave> entry : incoming.entrySet()) {
            String id = entry.getKey() == null ? null : entry.getKey().trim().toLowerCase();
            if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,63}")) {
                log.warn("中转站 id 不合法（仅小写字母/数字/连字符）：{}，已丢弃", entry.getKey());
                continue;
            }
            LlmConfigSaveRequestDTO.ProviderSave in = entry.getValue();
            if (in == null || StringUtils.isBlank(in.getBaseUrl())) {
                log.warn("中转站 {} 缺少 Base URL，已丢弃", id);
                continue;
            }
            // 协议未知 = 丢弃该条而非炸整次保存：注册表是整表提交，一条坏数据殃及
            // 全表会让用户其他字段的修改一起丢失（对比：全局 protocol 覆盖是单字段输入，
            // 抛异常给出精确反馈才合理）。丢弃语义与"id 不合法已丢弃"同款
            if (in.getProtocol() != null && LlmProtocol.parseOrNull(in.getProtocol().trim()) == null) {
                log.warn("中转站 {} 的协议值不合法（{}，仅 openai/anthropic/responses），已丢弃该条",
                        id, in.getProtocol());
                continue;
            }
            ProviderOverlay prev = current.getProviders() == null ? null : current.getProviders().get(id);
            ProviderOverlay po = new ProviderOverlay();
            po.setName(StringUtils.trimToNull(in.getName()));
            po.setBaseUrl(in.getBaseUrl().trim());
            po.setProtocol(in.getProtocol() == null ? null
                    : LlmProtocol.parse(in.getProtocol().trim()).getCode());
            // 密钥不回显：null=沿用该 id 现有密钥，空串=显式清除
            po.setApiKey(in.getApiKey() == null
                    ? (prev == null ? null : prev.getApiKey())
                    : StringUtils.trimToNull(in.getApiKey()));
            // 模型缓存与密钥同款保留语义：前端提交不带 models，丢掉会让场景矩阵的
            // 「站名·模型」标注与下拉选项在每次保存注册表后消失，还得重新获取
            po.setModels(prev == null ? null : prev.getModels());
            merged.put(id, po);
        }
        return merged.isEmpty() ? null : merged;
    }

    /**
     * 全局中转站引用合并：null=保持现状；空串=清除（回手动模式）；值必须存在于合并后的
     * 注册表（不存在=引用悬空，等于没选中转站却看不到——直接拒绝比静默清除诚实）
     */
    private static String mergeProviderId(Overlay current, Overlay next, LlmConfigSaveRequestDTO request) {
        String in = request.getProviderId();
        String candidate;
        if (in == null) {
            candidate = current.getProviderId();
        } else {
            candidate = StringUtils.trimToNull(in);
        }
        if (candidate == null) {
            return null;
        }
        // resetProviders 把注册表清空后引用必然悬空：按"手动模式"收敛而不是报错
        boolean known = next.getProviders() != null && next.getProviders().containsKey(candidate);
        if (!known) {
            if (in != null && !in.isBlank() && next.getProviders() != null) {
                throw new IllegalArgumentException("全局中转站引用不存在：" + candidate + "（请先在注册表中保存它）");
            }
            return null;
        }
        return candidate;
    }

    /**
     * 中转站接入点（供模型列表拉取）：返回该 id 的地址与密钥；不存在返回 null。
     * 密钥仅在进程内传递，绝不进日志与任何 DTO
     */
    public ProviderOverlay providerOf(String id) {
        Overlay o = overlay;
        if (o == null || o.getProviders() == null || StringUtils.isBlank(id)) {
            return null;
        }
        return o.getProviders().get(id.trim());
    }

    /**
     * 更新中转站的模型名缓存（「获取模型」成功后由 controller 回写）并落盘。
     * 只认已存在的 id——拉取端点在调用前已用 providerOf 校验过，此处防御静默注册
     */
    public synchronized void cacheProviderModels(String id, List<String> models) {
        Overlay o = overlay;
        if (o == null || o.getProviders() == null || StringUtils.isBlank(id)
                || !o.getProviders().containsKey(id) || models == null || models.isEmpty()) {
            return;
        }
        Overlay next = cloneOverlay(o);
        ProviderOverlay po = next.getProviders().get(id);
        po.setModels(new ArrayList<>(models));
        overlay = next;
        persist(next);
    }

    /** 浅拷贝覆盖（providers 条目逐个复制，杜绝 cache 与并发 save 共享可变条目） */
    private static Overlay cloneOverlay(Overlay source) {
        Overlay copy = new Overlay();
        copy.setAuthMode(source.getAuthMode());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setProtocol(source.getProtocol());
        copy.setApiKey(source.getApiKey());
        copy.setModel(source.getModel());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setSceneApiEnabled(source.getSceneApiEnabled());
        copy.setEmbedBaseUrl(source.getEmbedBaseUrl());
        copy.setEmbedApiKey(source.getEmbedApiKey());
        copy.setEmbedModel(source.getEmbedModel());
        copy.setEmbedDimensions(source.getEmbedDimensions());
        copy.setReferenceMode(source.getReferenceMode());
        copy.setToggles(source.getToggles() == null ? null : new LinkedHashMap<>(source.getToggles()));
        copy.setScenes(source.getScenes() == null ? null
                : source.getScenes().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (a, b) -> a, LinkedHashMap::new)));
        if (source.getFallback() != null) {
            SceneOverlay fb = new SceneOverlay();
            fb.setModel(source.getFallback().getModel());
            fb.setMaxTokens(source.getFallback().getMaxTokens());
            fb.setTemperature(source.getFallback().getTemperature());
            fb.setProviderId(source.getFallback().getProviderId());
            fb.setBaseUrl(source.getFallback().getBaseUrl());
            fb.setApiKey(source.getFallback().getApiKey());
            copy.setFallback(fb);
        }
        if (source.getProviders() != null) {
            Map<String, ProviderOverlay> providers = new LinkedHashMap<>();
            for (Map.Entry<String, ProviderOverlay> e : source.getProviders().entrySet()) {
                ProviderOverlay p = new ProviderOverlay();
                if (e.getValue() != null) {
                    p.setName(e.getValue().getName());
                    p.setBaseUrl(e.getValue().getBaseUrl());
                    p.setProtocol(e.getValue().getProtocol());
                    p.setApiKey(e.getValue().getApiKey());
                    p.setModels(e.getValue().getModels());
                }
                providers.put(e.getKey(), p);
            }
            copy.setProviders(providers);
        }
        copy.setProviderId(source.getProviderId());
        return copy;
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
            String providerId = StringUtils.trimToNull(in.getProviderId());
            so.setProviderId(providerId);
            // baseUrl 与 apiKey 同款"null=保留"语义：旧版前端只提交 model 时，
            // 若按空值覆盖会把已配的地址静默清掉（实测：只改模型 → baseUrl 变 null，
            // 留下一个"密钥还在、地址丢了"的半残覆盖，下次调用必失败）。
            // 显式清除仍可用空串表达（trimToNull 后为 null 的分支由 in.getBaseUrl()==null 区分）
            so.setBaseUrl(in.getBaseUrl() == null
                    ? currentBaseUrlOf(current, entry.getKey())
                    : StringUtils.trimToNull(in.getBaseUrl()));
            so.setApiKey(in.getApiKey() == null
                    ? currentApiKeyOf(current, entry.getKey())
                    : StringUtils.trimToNull(in.getApiKey()));
            // 选中转站 = 该站的地址密钥协议接管：同时清掉旧版手动字段，否则手动字段会在
            // 生效链路里反压站配置（见 applyModel 注释），界面上也说不清到底走哪套
            if (providerId != null) {
                so.setBaseUrl(null);
                so.setApiKey(null);
            }
            // 显式清除（apiKey 空串）后可能整条变空壳：不丢弃会留下一个全 null 的占位条目，
            // 表现在矩阵上就是“已覆盖”徽标常亮但什么都不生效
            if (so.getModel() == null && so.getMaxTokens() == null && so.getTemperature() == null
                    && so.getProviderId() == null
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

    /** 该场景现有 baseUrl 覆盖（供"null=保留"合并用）；无则 null */
    private static String currentBaseUrlOf(Overlay current, String sceneKey) {
        if (current == null || current.getScenes() == null) {
            return null;
        }
        SceneOverlay prev = current.getScenes().get(sceneKey);
        return prev == null ? null : prev.getBaseUrl();
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

    /**
     * apiKey 覆盖落到 module（baseUrl + apiKey + protocol）；无覆盖原样返回。
     * 字段优先级：全局中转站 &gt; 手动覆盖（baseUrl/protocol/apiKey 字段）&gt; yml 静态——
     * 选中中转站后地址密钥协议立即跟着走；手动字段保留为"中转站没配某字段时的补位"高级逃生口
     */
    public StoryVO.Module applyApi(StoryVO.Module module) {
        Overlay o = overlay;
        ProviderOverlay provider = providerOf(o == null ? null : o.getProviderId());
        boolean providerHasApi = provider != null
                && (StringUtils.isNotBlank(provider.getBaseUrl()) || StringUtils.isNotBlank(provider.getApiKey()));
        if (module == null || o == null
                || (!providerHasApi && StringUtils.isBlank(o.getBaseUrl()) && StringUtils.isBlank(o.getApiKey())
                    && StringUtils.isBlank(o.getProtocol()))) {
            return module;
        }
        StoryVO.Module.AiApi original = module.getAiApi();
        StoryVO.Module.AiApi aiApi = new StoryVO.Module.AiApi();
        aiApi.setBaseUrl(firstNonBlank(provider == null ? null : provider.getBaseUrl(), o.getBaseUrl(),
                original == null ? null : original.getBaseUrl()));
        aiApi.setApiKey(firstNonBlank(provider == null ? null : provider.getApiKey(), o.getApiKey(),
                original == null ? null : original.getApiKey()));
        aiApi.setProtocol(firstNonBlank(provider == null ? null : provider.getProtocol(), o.getProtocol(),
                original == null ? null : original.getProtocol()));
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
        // 全局兜底模型必须随拷贝走——否则一旦存在运行时覆盖（前端设置面板），
        // 兜底配置就被静默丢弃，挂机时的"换模型救调用"能力会无声失效
        copy.setFallbackModel(module.getFallbackModel());
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
        copy.setFallbackModel(module.getFallbackModel());
        return copy;
    }

    /**
     * model/maxTokens 覆盖到已解析的场景模型（unified 或 scene）；无覆盖原样返回。
     *
     * <p><b>maxTokens 是兜底而非覆盖</b>（2026-10-23）：selected 已带值（场景 yml 的
     * max-tokens，或回落的 chat-model.max-tokens）时一律尊重它，全局值只在 selected
     * 没有上限时补位。此前是全局无条件覆盖，代价实测可见：全局填 16384 会把
     * chapter-plan 的 32768 腰斩，强制思考模型必然烧穿（completionTokens=16384/16384、
     * 正文为空 → 判烧穿 → 拉黑 → 降级）；同时把 chapter-judge 的 1024 抬高 16 倍。
     * 各场景上限是按其输出形态调过的（短判定 vs 长计划），一个数字统一压掉本就是错的。
     */
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
        copy.setProtocol(selected.getProtocol());
        copy.setCompletionsPath(selected.getCompletionsPath());
        copy.setModel(StringUtils.isNotBlank(o.getModel()) ? o.getModel() : selected.getModel());
        // 兜底语义：selected 已有上限就尊重它，全局值只补空
        copy.setMaxTokens(selected.getMaxTokens() != null ? selected.getMaxTokens() : o.getMaxTokens());
        copy.setTemperature(selected.getTemperature());
        copy.setEnableThinking(selected.getEnableThinking());
        return copy;
    }

    /**
     * 全局兜底模型覆盖落到 module（独立分区，与场景覆盖互不影响）。
     *
     * <p>为什么单独一个方法而不是并进 applyModel：兜底是"所有场景共用的一份配置"，
     * 与"本次调用是哪个场景"无关——并进场景感知路径会让每个场景各拿一份，
     * 也就退回了被收敛掉的按场景配置。无覆盖时原样返回。
     */
    public StoryVO.Module applyFallback(StoryVO.Module module) {
        Overlay o = overlay;
        if (module == null || o == null || o.getFallback() == null || o.getFallback().isEmpty()) {
            return module;
        }
        SceneOverlay fb = o.getFallback();
        StoryVO.Module.ChatModel staticFallback = module.getFallbackModel();
        StoryVO.Module.ChatModel merged = new StoryVO.Module.ChatModel();
        // 字段优先级：运行时覆盖 > yml 静态兜底
        String overrideModel = StringUtils.isNotBlank(fb.getModel()) ? fb.getModel() : null;
        // 兜底与全局主模型同名 = 无效兜底（ModelFallbackChain 见同名直接返回单元素链，
        // 烧穿保护进不了降级分支，空正文被记成 success，故障最终伪装成"解析失败"）。
        // 2026-10-25 实测：设置页把兜底填成与主模型相同的 glm-5.3-flash，chapter-plan
        // 连烧两次空正文，报错却指向章数校验。这里整条覆盖作废、回落 yml 静态兜底（异族模型）。
        // 注意只兜住"与全局覆盖主模型同名"这一种形态：无全局覆盖时各场景主模型不同，
        // 静态兜底恰好与某场景同名属正常配置（ModelFallbackChain 本就按场景判同名）。
        String resolvedModel = overrideModel;
        boolean droppedSameName = false;
        if (sameNameFallbackDropped(fb, staticFallback)) {
            droppedSameName = true;
            resolvedModel = null;
            if (!sameNameFallbackWarned) {
                sameNameFallbackWarned = true;
                log.warn("兜底模型与全局主模型同名（{}），降级链会塌缩为单站、烧穿保护失效；"
                        + "已改用 yml 静态兜底 {}。请在设置页把兜底换成异族模型", overrideModel,
                        staticFallback.getModel());
            }
        }
        merged.setModel(resolvedModel != null ? resolvedModel
                : staticFallback == null ? null : staticFallback.getModel());
        // 上限仍按字段级覆盖走：只有守卫触发（整条覆盖作废）才回落静态值；
        // "只填上限不填模型名"的覆盖必须继续生效，不能因为模型名为空被吞掉
        Long maxTokensOverride = droppedSameName ? null : fb.getMaxTokens();
        merged.setMaxTokens(maxTokensOverride != null ? maxTokensOverride
                : staticFallback == null ? null : staticFallback.getMaxTokens());
        merged.setTemperature(fb.getTemperature() != null ? fb.getTemperature()
                : staticFallback == null ? null : staticFallback.getTemperature());
        merged.setEnableThinking(staticFallback == null ? null : staticFallback.getEnableThinking());
        // 兜底选中转站：该站地址密钥优先于手动字段（兜底的意义就是换供应商，
        // 而"换供应商"现在只需选站，不必再手抄地址与密钥）
        ProviderOverlay fbProvider = providerOf(fb.getProviderId());
        String providerBaseUrl = fbProvider == null ? null : fbProvider.getBaseUrl();
        String providerApiKey = fbProvider == null ? null : fbProvider.getApiKey();
        merged.setBaseUrl(firstNonBlank(providerBaseUrl, fb.getBaseUrl(),
                staticFallback == null ? null : staticFallback.getBaseUrl()));
        merged.setApiKey(firstNonBlank(providerApiKey, fb.getApiKey(),
                staticFallback == null ? null : staticFallback.getApiKey()));
        // 兜底协议同理：选中转站即随站切协议（否则 Anthropic 兜底站会按全局 OpenAI 协议请求）
        String providerProtocol = fbProvider == null ? null : fbProvider.getProtocol();
        merged.setProtocol(firstNonBlank(providerProtocol,
                staticFallback == null ? null : staticFallback.getProtocol()));
        merged.setCompletionsPath(staticFallback == null ? null : staticFallback.getCompletionsPath());

        StoryVO.Module copy = new StoryVO.Module();
        copy.setAiApi(module.getAiApi());
        copy.setChatModel(module.getChatModel());
        copy.setEmbeddingApi(module.getEmbeddingApi());
        copy.setUnifiedModelEnabled(module.getUnifiedModelEnabled());
        copy.setSceneModels(module.getSceneModels());
        copy.setFallbackModel(merged);
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
            // 场景选中转站：该站地址密钥接管本场景（优先级高于场景手动字段）。
            // 这是"场景指向异族评审模型"的主路径——选站即可，协议也随之切换，
            // 避免上一版"场景独立 API 只能沿用全局协议"导致跨协议供应商必然 401
            ProviderOverlay sceneProvider = providerOf(so.getProviderId());
            if (sceneApiOn() && sceneProvider != null) {
                if (StringUtils.isNotBlank(sceneProvider.getBaseUrl())) {
                    result.setBaseUrl(sceneProvider.getBaseUrl());
                }
                if (StringUtils.isNotBlank(sceneProvider.getApiKey())) {
                    result.setApiKey(sceneProvider.getApiKey());
                }
                // 协议随站切换：全局 OpenAI + 评审站 Anthropic 时，两边各用各的客户端。
                // 网关 effectiveApi 只在场景未声明协议时才继承全局
                if (StringUtils.isNotBlank(sceneProvider.getProtocol())) {
                    result.setProtocol(sceneProvider.getProtocol());
                }
            }
            // 场景级地址密钥手动字段（仅在开关打开时生效；关闭时下面的统一抹除接管）。
            // 顺序必须在站点之后：站是主路径，手动字段只是它没配某字段时的补位——
            // 反过来写会让残留的旧手动地址反压站配置（实测：选站后仍请求 OLD 地址）
            if (sceneApiOn() && StringUtils.isNotBlank(so.getBaseUrl())
                    && (sceneProvider == null || StringUtils.isBlank(sceneProvider.getBaseUrl()))) {
                result.setBaseUrl(so.getBaseUrl());
            }
            if (sceneApiOn() && StringUtils.isNotBlank(so.getApiKey())
                    && (sceneProvider == null || StringUtils.isBlank(sceneProvider.getApiKey()))) {
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
        copy.setProtocol(source.getProtocol());
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
        dto.setProtocol(o.getProtocol());
        dto.setModel(o.getModel());
        dto.setMaxTokens(o.getMaxTokens());
        dto.setProviderId(StringUtils.trimToNull(o.getProviderId()));
        if (o.getProviders() != null && !o.getProviders().isEmpty()) {
            List<LlmConfigDTO.ProviderView> providers = new ArrayList<>();
            for (Map.Entry<String, ProviderOverlay> entry : o.getProviders().entrySet()) {
                LlmConfigDTO.ProviderView pv = new LlmConfigDTO.ProviderView();
                pv.setId(entry.getKey());
                pv.setName(entry.getValue() == null ? entry.getKey() : entry.getValue().getName());
                pv.setBaseUrl(entry.getValue() == null ? null : entry.getValue().getBaseUrl());
                pv.setProtocol(entry.getValue() == null ? null : entry.getValue().getProtocol());
                if (entry.getValue() != null && StringUtils.isNotBlank(entry.getValue().getApiKey())) {
                    pv.setHasKey(true);
                    pv.setApiKeyMasked(mask(entry.getValue().getApiKey()));
                }
                pv.setModels(entry.getValue() == null || entry.getValue().getModels() == null
                        ? List.of() : entry.getValue().getModels());
                providers.add(pv);
            }
            dto.setProviders(providers);
        }
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
                row.setOverrideProviderId(StringUtils.trimToNull(so.getProviderId()));
                row.setOverrideBaseUrl(StringUtils.isNotBlank(so.getBaseUrl()) ? so.getBaseUrl() : null);
                if (StringUtils.isNotBlank(so.getApiKey())) {
                    row.setOverrideHasKey(true);
                    row.setOverrideApiKeyMasked(mask(so.getApiKey()));
                }
            }
            rows.add(row);
        }
        dto.setScenes(rows);
        dto.setFallback(toFallbackView(module));
        return dto;
    }

    /** 全局兜底模型视图：yml 静态值 + 运行时覆盖 + 生效模型名 */
    private LlmConfigDTO.FallbackView toFallbackView(StoryVO.Module module) {
        LlmConfigDTO.FallbackView view = new LlmConfigDTO.FallbackView();
        StoryVO.Module.ChatModel staticFallback = module.getFallbackModel();
        if (staticFallback != null) {
            view.setStaticModel(staticFallback.getModel());
            view.setStaticMaxTokens(staticFallback.getMaxTokens());
            view.setStaticBaseUrl(staticFallback.getBaseUrl());
            if (StringUtils.isNotBlank(staticFallback.getApiKey())) {
                view.setStaticHasKey(true);
                view.setStaticApiKeyMasked(mask(staticFallback.getApiKey()));
            }
        }
        Overlay o = overlay;
        SceneOverlay fb = o == null ? null : o.getFallback();
        if (fb != null && !fb.isEmpty()) {
            view.setOverridden(true);
            view.setOverrideModel(fb.getModel());
            view.setOverrideMaxTokens(fb.getMaxTokens());
            view.setOverrideTemperature(fb.getTemperature());
            view.setOverrideProviderId(StringUtils.trimToNull(fb.getProviderId()));
            view.setOverrideBaseUrl(StringUtils.isNotBlank(fb.getBaseUrl()) ? fb.getBaseUrl() : null);
            if (StringUtils.isNotBlank(fb.getApiKey())) {
                view.setOverrideHasKey(true);
                view.setOverrideApiKeyMasked(mask(fb.getApiKey()));
            }
        }
        // 生效值：运行时覆盖 > yml 静态；与 applyFallback 同口径——同名兜底会被守卫作废，
        // 这里必须显示真实生效的模型，否则设置页显示"已覆盖 glm-5.3-flash"而实际跑的是静态兜底
        String effective = sameNameFallbackDropped(fb, staticFallback)
                ? (staticFallback == null ? null : staticFallback.getModel())
                : fb != null && StringUtils.isNotBlank(fb.getModel())
                        ? fb.getModel() : (staticFallback == null ? null : staticFallback.getModel());
        view.setEffectiveModel(effective);
        return view;
    }

    /**
     * 运行时兜底是否为"与全局主模型同名"的无效配置（applyFallback 与 toFallbackView 共用）。
     *
     * <p>同名会让 ModelFallbackChain 直接返回单元素链，烧穿保护失去降级目标；同时静态兜底
     * 存在且是异族模型时才有回落价值，否则不如保持原样（避免把兜底清空）。
     *
     * <p><b>同名但不同供应商不算无效</b>（2026-10-27 实测）：兜底选了另一个中转站时，
     * 即使模型名相同，实际也是另一路端点/账号——拦掉它会把模型名换成静态兜底，
     * 却仍保留用户选的中转站地址密钥协议，拼出"静态模型 + 异站供应商"的错配请求。
     * 因此只有"同名且同端点"才判无效。
     */
    private boolean sameNameFallbackDropped(SceneOverlay fb, StoryVO.Module.ChatModel staticFallback) {
        if (fb == null || StringUtils.isBlank(fb.getModel())) {
            return false;
        }
        Overlay o = overlay;
        if (o == null
                || !fb.getModel().trim().equals(StringUtils.trimToEmpty(o.getModel()))
                || staticFallback == null || StringUtils.isBlank(staticFallback.getModel())
                || staticFallback.getModel().equals(fb.getModel())) {
            return false;
        }
        // 兜底引用了中转站 = 明确换了端点：即便模型同名，也不能按"无效兜底"整条作废
        if (StringUtils.isNotBlank(fb.getProviderId()) && providerOf(fb.getProviderId()) != null) {
            return false;
        }
        // 手动地址与全局地址不同，同理视为另一路端点
        String fallbackBaseUrl = StringUtils.trimToNull(fb.getBaseUrl());
        String globalBaseUrl = StringUtils.trimToNull(o.getBaseUrl());
        if (fallbackBaseUrl != null && globalBaseUrl != null && !fallbackBaseUrl.equals(globalBaseUrl)) {
            return false;
        }
        return true;
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
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
