package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmErrorClassifier;
import cn.novel.yonren.domain.novel.service.armory.llm.ModelFallbackChain;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import cn.novel.yonren.domain.novel.service.job.LlmBudgetFuse;
import cn.novel.yonren.types.enums.LlmProtocol;
import cn.novel.yonren.types.enums.ModelScene;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM 网关的 Spring AI 适配实现：客户端构建、消息组装、参数覆盖等供应商细节全部收敛于此，
 * 领域层只依赖 LlmGateway 端口。按 ai-api.protocol 分派三套传输实现：
 * openai（Chat Completions，默认）/ anthropic（Messages）/ responses（Responses API），
 * 烧穿判定、降级链、usage 记账对三协议通用（全部构建在 ChatResponse/Usage 抽象上）。
 * 模型客户端按供应商配置缓存复用（单例化），usage 以 JSONL 记账（覆盖全部调用方）。
 */
@Component
public class SpringAiLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmGateway.class);

    /** usage 记账文件，与 logback 的 CWD 相对日志目录约定一致 */
    private static final Path USAGE_LOG_PATH = Path.of("data", "log", "llm-usage.jsonl");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object USAGE_LOCK = new Object();

    /**
     * Anthropic 协议 max_tokens 缺省值：Messages API 必填该参数，配置留空时兜底。
     * 8192 是 Claude 3.5 Sonnet 及以后模型的通用安全输出上限
     */
    private static final int ANTHROPIC_DEFAULT_MAX_TOKENS = 8192;

    /** 传输层最大尝试次数（默认 1，即不重试；5xx 重试收权到业务层，避免 token 白烧） */
    private final int maxAttempts;
    private final RetryTemplate retryTemplate;

    /** 模型客户端缓存：protocol|baseUrl|apiKey|model|maxTokens -> ChatModel（三协议共用同一缓存） */
    private final ConcurrentHashMap<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    /**
     * 出网请求工厂（连接/读取超时）：RestClient.builder() 与 JDK HttpClient 默认均<b>不设超时</b>，
     * 供应商静默不回包时调用线程会永久阻塞（表现为作业卡死且无任何异常/重试）。
     * 这里统一注入超时，把"永久挂住"转成"超时异常 → 走既有瞬时失败重试"
     */
    private final ClientHttpRequestFactory requestFactory;

    /**
     * 思考烧穿的处置策略（novel.llm.on-budget-burn）：
     * skip（默认）= 记失败账后沿降级链换站，链尾也没得换时把空串交调用方 fail-soft；
     * fail = 烧穿即抛异常终止本批——烧穿一次 10-30 分钟，链上剩下的站大概率也是思考型，
     * 与其让作业再挂半小时不如快速失败让人换配置（resume 不丢已落盘章节）。
     * fail 模式下黑名单仍生效：把链上其他烧穿站点也滤掉，只对未试过的站做最后一搏。
     */
    private final boolean failOnBudgetBurn;

    /** 前端设置面板写入的运行时覆盖（api 地址/key/model/maxTokens），未设置时原样透传 */
    private final LlmRuntimeConfig llmRuntimeConfig;

    /** 作业注册表：烧穿黑名单按 jobId 取作业实例；无 MDC（离线工具/同步调试）时为 null 不启用 */
    private final JobRegistry jobRegistry;

    /** 预算熔断：每次 usage 记账顺路上报 token，超阈值置位 job 停机信号（不反噬调用链） */
    private final LlmBudgetFuse budgetFuse;

    /** Continue with ChatGPT：封装 OAuth 凭据与 Responses API，主网关不接触令牌。 */
    private final ChatGptGateway chatGptGateway;

    /** 保留离线校准工具的旧构造签名；这些工具只走 API Key 模式（无作业语境，黑名单自然失效）。 */
    public SpringAiLlmGateway(int maxAttempts, int connectTimeoutSeconds, int readTimeoutSeconds,
                              LlmRuntimeConfig llmRuntimeConfig, LlmBudgetFuse budgetFuse) {
        this(maxAttempts, connectTimeoutSeconds, readTimeoutSeconds, false, llmRuntimeConfig, budgetFuse,
                new UnavailableChatGptGateway(), new JobRegistry());
    }

    /** 网关单测的旧构造签名。 */
    public SpringAiLlmGateway(int maxAttempts, int connectTimeoutSeconds, int readTimeoutSeconds,
                              LlmRuntimeConfig llmRuntimeConfig, LlmBudgetFuse budgetFuse,
                              ChatGptGateway chatGptGateway) {
        this(maxAttempts, connectTimeoutSeconds, readTimeoutSeconds, false, llmRuntimeConfig, budgetFuse,
                chatGptGateway, new JobRegistry());
    }

    @Autowired
    public SpringAiLlmGateway(@Value("${spring.ai.retry.max-attempts:1}") int maxAttempts,
                              @Value("${novel.llm.connect-timeout-seconds:20}") int connectTimeoutSeconds,
                              @Value("${novel.llm.read-timeout-seconds:600}") int readTimeoutSeconds,
                              @Value("${novel.llm.on-budget-burn:skip}") String onBudgetBurn,
                              LlmRuntimeConfig llmRuntimeConfig,
                              LlmBudgetFuse budgetFuse,
                              ChatGptGateway chatGptGateway,
                              JobRegistry jobRegistry) {
        this(maxAttempts, connectTimeoutSeconds, readTimeoutSeconds, "fail".equalsIgnoreCase(onBudgetBurn),
                llmRuntimeConfig, budgetFuse, chatGptGateway, jobRegistry);
    }

    /** 全参数构造：上两组旧签名经此收口；onBudgetBurnFail 由 Spring 构造器从 yml 解析 */
    public SpringAiLlmGateway(int maxAttempts, int connectTimeoutSeconds, int readTimeoutSeconds,
                              boolean onBudgetBurnFail, LlmRuntimeConfig llmRuntimeConfig,
                              LlmBudgetFuse budgetFuse, ChatGptGateway chatGptGateway,
                              JobRegistry jobRegistry) {
        this.maxAttempts = maxAttempts;
        this.failOnBudgetBurn = onBudgetBurnFail;
        this.llmRuntimeConfig = llmRuntimeConfig;
        this.budgetFuse = budgetFuse;
        this.chatGptGateway = chatGptGateway;
        this.jobRegistry = jobRegistry;
        // 超时是"卡死"的唯一刹车：无超时则供应商静默不回包会永久阻塞 worker 线程
        this.requestFactory = HttpTimeouts.requestFactory(connectTimeoutSeconds, readTimeoutSeconds);
        this.retryTemplate = RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .retryOn(java.util.List.of(TransientAiException.class, ResourceAccessException.class,
                        java.util.concurrent.CancellationException.class,
                        java.net.http.HttpTimeoutException.class))
                .build();
        log.info("LLM 网关初始化：传输层最大尝试次数 = {}，连接超时 = {}s，读取超时 = {}s，思考烧穿处置 = {}",
                maxAttempts, connectTimeoutSeconds, readTimeoutSeconds, failOnBudgetBurn ? "fail" : "skip");
    }

    private static final class UnavailableChatGptGateway implements ChatGptGateway {
        private IllegalStateException unavailable() { return new IllegalStateException("Continue with ChatGPT 未装配"); }
        public AuthStatus status() { return new AuthStatus(false, null, null); }
        public Authorization beginAuthorization(String redirectUri) { throw unavailable(); }
        public AuthStatus finishAuthorization(String code, String state, String clientId, String redirectUri) { throw unavailable(); }
        public void logout() { }
        public List<ModelOption> models() { return List.of(); }
        public Completion complete(String model, String instructions, String input) { throw unavailable(); }
    }

    @Override
    public String complete(StoryVO.Module module, LlmCall call) {
        // 思考捕获用 ThreadLocal 传递：无论成功、失败还是降级换站，出方法前必须清，
        // 否则线程池复用会把本次调用的思考用量带给下一章
        try {
            return completeInner(module, call);
        } finally {
            ReasoningCapture.clearAll();
        }
    }

    private String completeInner(StoryVO.Module module, LlmCall call) {
        // 兜底模型覆盖先落到 module：它是"所有场景共用的一份"，与场景解析无关，
        // 必须在 ModelFallbackChain.build 之前生效，否则运行时配置的兜底模型读不到
        StoryVO.Module effective = llmRuntimeConfig.applyFallback(llmRuntimeConfig.applyApi(module));
        // 场景感知覆盖：场景覆盖 > 全局覆盖 > yml 静态（场景条目缺项已由 resolveChatModel 回落统一模型）
        StoryVO.Module.ChatModel primary = llmRuntimeConfig.applyModel(
                call.getScene(), resolveChatModel(effective, call.getScene()));
        if (llmRuntimeConfig.usesChatGptAuth()) {
            return completeWithChatGpt(effective, primary, call);
        }
        // 场景级降级链：主模型 → 该场景备选 → default 备选（阶段六，见 ModelFallbackChain）
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(effective, call.getScene(), primary);
        if (chain.isEmpty()) {
            IllegalStateException missing = new IllegalStateException(
                    "LLM 调用缺少可用模型配置：scene=" + call.getScene());
            recordUsage(effective, null, call, null, 0L, null, missing);
            throw missing;
        }
        // 作业内烧穿黑名单：思考烧穿是确定性故障（与采样无关），同一模型在同一场景重试只会再烧
        // 一次 10-30 分钟。链中已烧穿的站直接滤掉；全部烧穿则快速失败——黑名单内的模型必然复发，
        // 再调一次只是把同样的钱再烧一遍（实测连烧 3 次 96k token、44 分钟零产出的事故即此形态）
        String sceneKey = call.getScene() == null ? null : call.getScene().getConfigKey();
        String jobId = jobId();
        GenerationJob job = jobId == null ? null : jobRegistry.get(jobId);
        List<StoryVO.Module.ChatModel> live = filterBurned(chain, job, sceneKey);
        if (live.size() < chain.size()) {
            log.warn("场景 {} 降级链含本作业已烧穿的模型，已滤除后执行：{} → {}",
                    call.getScene(),
                    chain.stream().map(StoryVO.Module.ChatModel::getModel).toList(),
                    live.stream().map(StoryVO.Module.ChatModel::getModel).toList());
        }
        for (int i = 0; i < live.size(); i++) {
            StoryVO.Module.ChatModel selected = live.get(i);
            long start = System.currentTimeMillis();
            // 每站开跑前清掉上一站残留的思考快照：否则降级换站后，新站的账会记上旧站的思考用量
            ReasoningCapture.clearForRecord();
            try {
                ChatResponse response = callOnce(effective, selected, call);
                String content = response.getResult().getOutput().getText();
                // 思考捕获（拦截器在响应侧写入）：chars=思考正文字符数，tokens=供应商报的思考 token
                ReasoningCapture.Snapshot reasoning = ReasoningCapture.take();
                // 部分中转站把思考以 <think>...</think> 混在 content 里返回：剥离后再判定与返回，
                // 否则思考文本会被当成正文交给调用方（JSON 场景直接解析失败）
                content = stripThinkingMarkup(content);
                // 空回复/思考吃穿输出预算视为失败，换链上备选重试：思考型模型的思考 token 从输出预算
                // 中扣除，思考失控时会把预算全烧在思维链上，content 只剩空串或不可解析的残片，却以
                // HTTP 200 记账成功——调用方拿残片按"解析失败"静默降级，备选链因"无异常"从未触发。
                // 判据以 usage 为主、不依赖 finish_reason（预算烧穿时它可能仍报正常结束），四判据见下。
                // 实测案例：glm-5.3 烧穿 32000/16384 全空（判定①）；deepseek 备选 16383/16384 撞上限
                // 只吐 672 字符残 JSON——旧判据"正文 < 500 字符"放过了它，在解析层翻车后整链又烧一轮。
                // 反例保护：撞上限但 JSON 完整（26524 token/4296 字符的合法计划）判成功放行。
                //
                // ⚠️ 失败账须带真实 usage：烧掉的 token 是真实消耗，必须进预算熔断的账，
                // 否则反复烧穿对熔断不可见。此处记失败账后直接换下一站，不走 catch 的 null usage 路径。
                Usage usage = response.getMetadata().getUsage();
                long completionTokens = usage == null || usage.getCompletionTokens() == null
                        ? 0L : usage.getCompletionTokens();
                // 上限可能为空（场景显式留空 = 不下发 max_tokens，由供应商按模型上限裁决）。
                // 判据①②④都以它为基准，为空时全部失效，只剩判据③（正文为空）可判——
                // 这是"不设上限"的代价，故日志与文档都引导用户优先填具体值。
                Long effectiveMaxTokens = call.getMaxTokens() != null
                        ? call.getMaxTokens().longValue() : selected.getMaxTokens();
                // 烧穿判据（满足其一即判"烧穿式失败"）：
                // ① completionTokens ≥ 90% 上限 且 正文为空——思考 token 把预算全吃光，正文一个字没吐；
                // ② completionTokens ≥ 90% 上限 且 JSON 判残缺——撞上限本身只是截断（正常长输出也会），
                //    但思考挤占下输出常死在 JSON 结构中途（responseFormat 并非所有供应商都守约），
                //    must 真解析判定（see looksTruncatedJson：事故输出死在中文引号中间，尾字符启发式判不出）；
                // ③ 正文为空——无论用量如何，空串对调用方没有意义，能换站就换（用量缺失的供应商
                //    思考场景只是其子集，不再单列判据）；
                // ④ 思考 token 占预算 ≥ 80%——不必等正文变空：思考已吃掉大部分预算，剩下那点余量
                //    写不出合格长文本。这条在 glm 型故障里能提前一次调用止损（判定①要等全文烧完）。
                boolean nearCeiling = effectiveMaxTokens != null && effectiveMaxTokens > 0
                        && completionTokens * 10 >= effectiveMaxTokens * 9;
                long contentChars = StringUtils.length(content);
                boolean reasonDominates = effectiveMaxTokens != null && effectiveMaxTokens > 0
                        && reasoning != null && reasoning.tokens() != null
                        && reasoning.tokens() * 10 >= effectiveMaxTokens * 8;
                boolean burnedBudget = reasonDominates
                        || (nearCeiling && (contentChars == 0 || looksTruncatedJson(content)));
                boolean burnLike = burnedBudget || StringUtils.isBlank(content);
                if (burnLike && i + 1 < live.size()) {
                    if (burnedBudget && job != null) {
                        // 烧穿是确定性故障：记入作业黑名单，本作业内该场景不再重试此模型
                        job.recordBurnedModel(sceneKey, selected.getModel());
                    }
                    recordUsage(effective, selected, call, null, System.currentTimeMillis() - start,
                            usage, new IllegalStateException("模型返回空内容或思考吃穿输出预算：scene="
                                    + call.getScene() + ", model=" + selected.getModel()
                                    + ", completionTokens=" + completionTokens + "/" + effectiveMaxTokens
                                    + ", contentChars=" + contentChars));
                    log.warn("场景 {} 主模型 {} 思考吃穿输出预算（completionTokens={}/{}，contentChars={}，"
                                    + "finishReason={}），已记失败账{}，降级到备选模型 {}",
                            call.getScene(), selected.getModel(), completionTokens, effectiveMaxTokens,
                            contentChars,
                            response.getResult().getMetadata() == null ? "-"
                                    : String.valueOf(response.getResult().getMetadata().getFinishReason()),
                            burnedBudget && job != null ? "并拉黑（本作业内该场景不再重试此模型）" : "",
                            live.get(i + 1).getModel());
                    continue;
                }
                if (burnLike && failOnBudgetBurn) {
                    // fail 模式：烧穿即快速失败终止本批，不再把空串交给调用方静默降级——
                    // 那会触发上游又一层重试循环，把同样的 10-30 分钟再挂一遍。
                    // BudgetBurnException 在下方 catch 中原样穿透（否则被自家 catch 当普通失败
                    // 再记一次账、再沿链降级，fail 语义失效）
                    if (burnedBudget && job != null) {
                        job.recordBurnedModel(sceneKey, selected.getModel());
                    }
                    BudgetBurnException burned = new BudgetBurnException(call.getScene(),
                            selected.getModel(), completionTokens, effectiveMaxTokens, contentChars);
                    recordUsage(effective, selected, call, null, System.currentTimeMillis() - start,
                            usage, burned);
                    throw burned;
                }
                if (burnLike) {
                    // skip 模式链尾：无备选可换，保持旧行为返回空串交调用方 fail-soft
                    // （各调用点只处理过"空串"这一类软失败，不引入它们没处理过的异常）。
                    //
                    // 但记账必须记失败：空正文/烧穿对调用方毫无价值，记 success=true 会让
                    // 预算熔断看不见真实空烧，也让排障者以为模型"成功返回了空"。
                    // 2026-10-26 实测：chapter-content 两次 responseChars=0、completionTokens=16384
                    // 全被记成 success=true，单作业烧掉 32.7 万 token 而账面无一条失败。
                    // 返回值不变（仍是 content 空串），只修记账口径——success 字段无消费方。
                    recordUsage(effective, selected, call, content, System.currentTimeMillis() - start,
                            response.getMetadata().getUsage(),
                            new IllegalStateException("模型返回空内容或思考吃穿输出预算（链尾无可降级）：scene="
                                    + call.getScene() + ", model=" + selected.getModel()
                                    + ", completionTokens=" + completionTokens + "/" + effectiveMaxTokens
                                    + ", contentChars=" + contentChars));
                    return content;
                }
                recordUsage(effective, selected, call, content, System.currentTimeMillis() - start,
                        response.getMetadata().getUsage(), null);
                return content;
            } catch (BudgetBurnException e) {
                throw e;
            } catch (Exception e) {
                recordUsage(effective, selected, call, null, System.currentTimeMillis() - start, null, e);
                boolean hasNext = i + 1 < live.size();
                if (!hasNext || !shouldFallback(e)) {
                    throw e;
                }
                log.warn("场景 {} 主模型 {} 调用失败（{}），降级到备选模型 {}；异常消息：{}；供应商响应：{}",
                        call.getScene(), selected.getModel(),
                        LlmErrorClassifier.classify(e).getDesc(), live.get(i + 1).getModel(),
                        StringUtils.abbreviate(String.valueOf(e.getMessage()), 300),
                        responseBodyOf(e));
            }
        }
        // 循环内必然 return 或 throw，此处不可达；保留以满足编译器
        throw new IllegalStateException("降级链耗尽但未捕获到异常：scene=" + call.getScene());
    }

    private String completeWithChatGpt(StoryVO.Module module, StoryVO.Module.ChatModel selected, LlmCall call) {
        if (selected == null || StringUtils.isBlank(selected.getModel())) {
            IllegalStateException missing = new IllegalStateException("ChatGPT 模式缺少模型配置");
            recordUsage(module, selected, call, null, 0L, null, missing);
            throw missing;
        }
        long start = System.currentTimeMillis();
        try {
            ChatGptGateway.Completion response = chatGptGateway.complete(
                    selected.getModel(), call.getSystemPrompt(), call.getUserPrompt());
            recordUsageValues(selected, call, response.content(), System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(), response.totalTokens(), null);
            return response.content();
        } catch (Exception e) {
            recordUsage(module, selected, call, null, System.currentTimeMillis() - start, null, e);
            throw e;
        }
    }

    /** 单次真实调用：消息组装 + 每调用级 runtime options 合并（按协议选择 options 类型） */
    private ChatResponse callOnce(StoryVO.Module effective, StoryVO.Module.ChatModel selected, LlmCall call) {
        ChatModel chatModel = chatModel(effective, selected);
        List<Message> messages = new ArrayList<>();
        if (StringUtils.isNotBlank(call.getSystemPrompt())) {
            messages.add(new SystemMessage(call.getSystemPrompt()));
        }
        messages.add(new UserMessage(call.getUserPrompt()));
        // 每调用级覆盖项走 Prompt 的 runtime options，与模型默认参数合并。
        // Responses 客户端按 ChatOptions 接口读取，复用 OpenAiChatOptions 载体即可
        return chatModel.call(new Prompt(messages, callOptions(effective, selected, call)));
    }

    /**
     * 按场景解析本次调用实际使用的模型配置：
     * 总开关开（unifiedModelEnabled=true）、场景为 null、或 scene-models 未配置该场景 → 回退统一 chatModel；
     * 否则使用 scene-models 中对应场景的独立配置。
     */
    private StoryVO.Module.ChatModel resolveChatModel(StoryVO.Module module, ModelScene scene) {
        if (module == null || module.getChatModel() == null) {
            return null;
        }
        if (Boolean.FALSE.equals(module.getUnifiedModelEnabled())
                && scene != null && module.getSceneModels() != null) {
            StoryVO.Module.ChatModel sceneModel = module.getSceneModels().get(scene.getConfigKey());
            if (sceneModel != null) {
                return sceneModel;
            }
        }
        return module.getChatModel();
    }

    /** 异常记账路径的模型解析已并入降级链循环（selected 恒非空），此处不再需要单独兜底 */

    private ChatModel chatModel(StoryVO.Module module, StoryVO.Module.ChatModel selected) {
        StoryVO.Module.AiApi api = effectiveApi(module, selected);
        return chatModelCache.computeIfAbsent(chatModelCacheKey(api, selected), k -> buildChatModel(api, selected));
    }


    static String chatModelCacheKey(StoryVO.Module.AiApi api, StoryVO.Module.ChatModel selected) {
        // protocol 已在 api 上（effectiveApi 合成后即含场景协议），缓存键带上它才能区分
        // "同地址同模型、不同协议"的两个客户端
        return api.getProtocol() + "|" + api.getBaseUrl() + "|" + api.getApiKey() + "|" + api.getZeroDataRetention()
                + "|" + selected.getModel() + "|" + selected.getMaxTokens()
                + "|" + selected.getEnableThinking() + "|" + selected.getTemperature();
    }

    private static StoryVO.Module.AiApi effectiveApi(StoryVO.Module module, StoryVO.Module.ChatModel selected) {
        StoryVO.Module.AiApi fallback = module.getAiApi();
        boolean hasSceneApi = selected != null
                && (StringUtils.isNotBlank(selected.getBaseUrl()) || StringUtils.isNotBlank(selected.getApiKey()));
        boolean hasScenePath = selected != null && StringUtils.isNotBlank(selected.getCompletionsPath());
        boolean hasSceneProtocol = selected != null && StringUtils.isNotBlank(selected.getProtocol());
        if (fallback == null || (!hasSceneApi && !hasScenePath && !hasSceneProtocol)) {
            return fallback;
        }
        StoryVO.Module.AiApi api = new StoryVO.Module.AiApi();
        api.setBaseUrl(hasSceneApi && StringUtils.isNotBlank(selected.getBaseUrl())
                ? selected.getBaseUrl() : fallback.getBaseUrl());
        api.setApiKey(hasSceneApi && StringUtils.isNotBlank(selected.getApiKey())
                ? selected.getApiKey() : fallback.getApiKey());
        api.setCompletionsPath(hasScenePath ? selected.getCompletionsPath() : fallback.getCompletionsPath());
        api.setZeroDataRetention(fallback.getZeroDataRetention());
        // 协议：场景显式声明时以场景为准（选中转站即随站切协议），否则继承全局。
        // 全局 OpenAI + 评审场景指向 Anthropic 站时，两边必须各用各的客户端与 options 载体
        api.setProtocol(hasSceneProtocol ? selected.getProtocol() : fallback.getProtocol());
        return api;
    }

    /**
     * 按协议构建模型客户端。三协议共用网关的烧穿判定/降级链/记账——它们只依赖
     * ChatResponse/Usage 抽象，协议差异全部隔离在各 build 方法内部。
     */
    private ChatModel buildChatModel(StoryVO.Module.AiApi api, StoryVO.Module.ChatModel selected) {
        return switch (LlmProtocol.parse(api.getProtocol())) {
            case ANTHROPIC -> buildAnthropicChatModel(api, selected);
            case RESPONSES -> new ResponsesApiChatModel(api, selected, requestFactory, retryTemplate);
            case OPENAI -> buildOpenAiChatModel(api, selected);
        };
    }

    private OpenAiChatModel buildOpenAiChatModel(StoryVO.Module.AiApi api, StoryVO.Module.ChatModel selected) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(api.getBaseUrl())
                .apiKey(api.getApiKey());
        // 供应商路径不同：智谱无 /v1 前缀，标准 OpenAI 兼容代理留空即默认 /v1/chat/completions
        if (StringUtils.isNotBlank(api.getCompletionsPath())) {
            apiBuilder.completionsPath(api.getCompletionsPath());
        }
        boolean dashscopeEndpoint = StringUtils.contains(
                StringUtils.defaultString(selected.getBaseUrl(), api.getBaseUrl()), "dashscope");
        if (Boolean.FALSE.equals(selected.getEnableThinking()) && dashscopeEndpoint) {
            apiBuilder.restClientBuilder(chatRestClientBuilder(api, true));
        } else {
            // 未关闭思考的场景也必须挂超时，避免无限等待拖死 worker
            apiBuilder.restClientBuilder(chatRestClientBuilder(api, false));
        }
        OpenAiChatOptions.Builder optionBuilder = OpenAiChatOptions.builder()
                .model(selected.getModel())
                // JSON Mode：强制模型输出合法 JSON，治正文/摘要等场景的"输出截断/不闭合"格式翻车（零 token 成本）
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        // 输出上限留空 = 不显式下发 max_tokens，由供应商按模型自身上限裁决（旧代码直接 intValue()，
        // 留空即 NPE，所以"留空=不限"这条路径从来没真正可用）。不设上限**不等于无限输出**：
        // 每个模型都有硬性 max-output（glm-5.3-flash 131072、deepseek-v4-pro 128000），到点必被截断。
        // 注意代价：没有显式上限时烧穿判据①（撞 90% 上限）与④（思考占 80%）都失去基准，
        // 只剩"正文为空"能识别——所以优先给场景配一个接近天花板的具体值，留空只作最后手段。
        if (selected.getMaxTokens() != null) {
            optionBuilder.maxTokens(selected.getMaxTokens().intValue());
        }
        // 采样温度：配置显式给定时写入默认选项；null 则走供应商默认
        if (selected.getTemperature() != null) {
            optionBuilder.temperature(selected.getTemperature());
        }
        OpenAiChatOptions options = optionBuilder.build();
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(options)
                .retryTemplate(retryTemplate)
                .build();
    }

    /**
     * Anthropic Messages 协议（/v1/messages）客户端。与 OpenAI 路径的协议差异：
     * <ul>
     *   <li>max_tokens 是<b>必填</b>参数——配置留空时给 {@link #ANTHROPIC_DEFAULT_MAX_TOKENS}，
     *       没法像 OpenAI 那样"留空=不下发"；</li>
     *   <li>无 response_format JSON mode（JSON 场景靠 prompt 约束，本就是项目的基线手段）；</li>
     *   <li>enable_thinking / x-cmd-zdr / DashScope 注入是 OpenAI 路径专属，此处不适用。
     *       思考改由原生 ThinkingConfig 表达，且<b>必须在每次调用时裁决</b>（见
     *       {@link #anthropicCallOptions}）：Spring AI 的 runtime options 合并规则是
     *       "非空字段覆盖默认"，若思考固化在 defaultOptions，带小 maxTokens 的调用
     *       （选择器/摘要）合并后会出现 max_tokens &lt; budget_tokens 的非法组合被 400；</li>
     *   <li>温度上限 1.0 的全局校验与 Anthropic 官方一致，无需特判。</li>
     * </ul>
     */
    private AnthropicChatModel buildAnthropicChatModel(StoryVO.Module.AiApi api, StoryVO.Module.ChatModel selected) {
        String baseUrl = StringUtils.defaultString(api.getBaseUrl());
        // apiKey 空串兜底：Builder.build() 断言非 null（本地无鉴权中转允许不填 key，
        // 官方端点空 key 会得到清晰的 401 而不是构建期异常）
        AnthropicApi.Builder apiBuilder = AnthropicApi.builder()
                .baseUrl(baseUrl)
                .apiKey(StringUtils.defaultString(api.getApiKey()));
        // completionsPath 是 OpenAI 语义的字段（如智谱用户 yml 里的 /chat/completions）：
        // 切到 anthropic 协议后若原样沿用会拼出错误端点、404 难排查——只接受含 messages 的路径
        String customPath = StringUtils.trimToNull(api.getCompletionsPath());
        if (customPath != null) {
            if (customPath.toLowerCase().contains("messages")) {
                apiBuilder.completionsPath(customPath.startsWith("/") ? customPath : "/" + customPath);
            } else {
                log.warn("completionsPath={} 不像 Anthropic Messages 路径，已忽略（anthropic 协议按默认 /v1/messages 拼接）",
                        customPath);
            }
        } else if (baseUrl.endsWith("/v1")) {
            // AnthropicApi 是 baseUrl+completionsPath 直接拼接：base 带 /v1 会拼出 /v1/v1/messages。
            // 与模型列表/Responses 端点"带不带 /v1 均可"的适配承诺保持同款行为
            apiBuilder.completionsPath("/messages");
        }
        // 超时必须注入（与 OpenAI 路径同款）；思考提取拦截器按 /messages 响应形态解析
        apiBuilder.restClientBuilder(anthropicRestClientBuilder());
        AnthropicChatOptions.Builder optionBuilder = AnthropicChatOptions.builder()
                .model(selected.getModel());
        // defaultOptions 只固化 model + maxTokens 兜底：思考/温度随每次调用生效上限动态裁决
        optionBuilder.maxTokens(selected.getMaxTokens() != null
                ? selected.getMaxTokens().intValue() : ANTHROPIC_DEFAULT_MAX_TOKENS);
        if (Boolean.TRUE.equals(selected.getEnableThinking()) && (selected.getMaxTokens() == null
                || selected.getMaxTokens() < 2048)) {
            log.warn("Anthropic 协议 maxTokens={} 不足以为思考分配预算（需 ≥2048），思考仅在预算充足的具体调用上开启：model={}",
                    selected.getMaxTokens(), selected.getModel());
        }
        return AnthropicChatModel.builder()
                .anthropicApi(apiBuilder.build())
                .defaultOptions(optionBuilder.build())
                .retryTemplate(retryTemplate)
                .build();
    }

    private static boolean shouldFallback(Throwable e) {
        return LlmErrorClassifier.classify(e) != LlmErrorClassifier.Kind.CONTENT_POLICY;
    }

    /**
     * 剥离混进正文的思考标记。部分中转站不把思考放进 reasoning_content，
     * 而是用 &lt;think&gt;…&lt;/think&gt;（或 &lt;thinking&gt;）包在 content 里返回。
     * 不剥离的后果：正文里出现大段推理文字，JSON 场景直接解析失败、正文场景出现"模型自述"。
     * 只剥标记块，不碰正常文本；无标记时零开销（indexOf 判断先走）。
     */
    static String stripThinkingMarkup(String content) {
        if (StringUtils.isBlank(content)) {
            return content;
        }
        // 两种形态：① <think>…</think> 标签块；② ```reasoning/think/thinking 围栏块。
        // 正常正文既无 < 也无反引号，两个分支都不进，零正则开销
        String cleaned = content;
        if (cleaned.indexOf('<') >= 0) {
            cleaned = cleaned.replaceAll(
                    "(?is)<think(?:ing)?\\b[^>]*>.*?</think(?:ing)?>", "");
        }
        if (cleaned.indexOf("`") >= 0) {
            cleaned = cleaned.replaceAll(
                    "(?is)```(?:reasoning|think|thinking)[^\\n]*\\n.*?```", "");
        }
        // 只剩空白时说明整段都是思考：返回空串让上层按"空回复"处理（换站/失败），
        // 而不是把一段空白当正文交出去
        return cleaned.isBlank() ? "" : cleaned.stripLeading();
    }

    /**
     * 过滤降级链中本作业已烧穿的站点。全部烧穿时分流：fail 模式直接抛
     * {@link BudgetBurnException}（黑名单内的模型重试必然复发，再调一次只是把同样的
     * token 再烧一遍）；skip 模式回退原链走既有出口（烧穿换站 → 链尾 fail-soft）。
     * 无作业语境（离线工具/无 MDC）原样返回整链
     */
    List<StoryVO.Module.ChatModel> filterBurned(List<StoryVO.Module.ChatModel> chain,
                                                GenerationJob job, String sceneKey) {
        if (job == null) {
            return chain;
        }
        List<StoryVO.Module.ChatModel> live = chain.stream()
                .filter(m -> !job.isModelBurned(sceneKey, m.getModel()))
                .toList();
        if (live.isEmpty()) {
            if (failOnBudgetBurn) {
                throw new BudgetBurnException("本场景全部候选模型均在本作业内烧穿过输出预算"
                        + "（确定性故障，重试必然复发）：scene=" + sceneKey
                        + ", models=" + chain.stream().map(StoryVO.Module.ChatModel::getModel).toList()
                        + "。请把该场景切换到非思考型模型后重试（已生成章节均已落盘，可 resume）");
            }
            return chain;
        }
        return live;
    }

    /** 当前作业 id（logback MDC 的 trace-id）；无作业语境（离线工具/同步调试）返回 null */
    private static String jobId() {
        return MDC.get("trace-id");
    }

    /** fail 模式下的烧穿终止异常：越过降级链直抵作业层，携带归因所需的全部量化证据 */
    static final class BudgetBurnException extends IllegalStateException {
        BudgetBurnException(ModelScene scene, String model, long completionTokens,
                            Long maxTokens, long contentChars) {
            super("思考烧穿输出预算且 novel.llm.on-budget-burn=fail：scene=" + scene
                    + ", model=" + model
                    + ", completionTokens=" + completionTokens + "/" + maxTokens
                    + ", contentChars=" + contentChars
                    + "。请把该场景切换到非思考型模型后重试（已生成章节均已落盘，可 resume）");
        }

        /** 全链烧穿变体：消息已完整自述，直接以文本构造 */
        BudgetBurnException(String message) {
            super(message);
        }
    }

    /**
     * JSON 截断粗判：剥掉闭合的 markdown 代码围栏后尝试完整解析，失败即判残。
     * 证据驱动：deepseek 备选那次翻车输出死在中文引号中间（…李默辩解：「我），
     * 尾字符是引号本身，"尾字符是否闭合"的启发式判不出来，必须真解析。
     * 仅在撞输出上限时调用（此时截断概率最高），解析开销可忽略
     */
    static boolean looksTruncatedJson(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        try {
            JSON.readTree(text);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** 从异常链提取 HTTP 4xx/5xx 的响应体（供应商原始报错），供确定性失败的日志归因；提取不到返回 "-" */
    private static String responseBodyOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.web.client.RestClientResponseException rest) {
                return StringUtils.abbreviate(rest.getResponseBodyAsString(), 500);
            }
            // Spring AI 自家异常：HTTP 4xx/5xx 的响应体在 message 里（RestClientResponseException 之外的通道）
            if (t instanceof org.springframework.ai.retry.NonTransientAiException nonTransient) {
                return StringUtils.abbreviate(String.valueOf(nonTransient.getMessage()), 500);
            }
            if (t instanceof org.springframework.web.client.HttpStatusCodeException http) {
                return StringUtils.abbreviate(http.getResponseBodyAsString(), 500);
            }
        }
        return "-";
    }


    private RestClient.Builder chatRestClientBuilder(StoryVO.Module.AiApi api, boolean disableThinking) {
        // 本模型实例是否已确认拒绝 enable_thinking（强制思考模型如 glm-5.3/kimi-k3：注入即 400）。
        // 记住后本实例后续请求直接跳过注入，省一次 400 往返；chatModel 缓存按配置建实例，状态随实例存活
        AtomicBoolean injectionRejected = new AtomicBoolean(false);
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            if (Boolean.TRUE.equals(api.getZeroDataRetention())) {
                request.getHeaders().set("x-cmd-zdr", "1");
            }
            String path = request.getURI() == null ? null : request.getURI().getPath();
            boolean chatPath = path != null && path.endsWith("/chat/completions");
            boolean injectable = disableThinking && !injectionRejected.get() && chatPath;

            byte[] payload = body;
            if (injectable && body != null && body.length > 0) {
                try {
                    ObjectNode node = (ObjectNode) JSON.readTree(body);
                    node.put("enable_thinking", false);
                    payload = JSON.writeValueAsBytes(node);
                } catch (Exception e) {
                    log.warn("注入 enable_thinking=false 失败，按原请求发送：{}", e.getMessage());
                }
            }

            ClientHttpResponse response = execution.execute(request, payload);
            // 自愈：强制思考模型对 enable_thinking=false 返回 4xx——去掉该参数
            // 原样重试一次，并记住本实例后续跳过注入。重试走的是余下执行链，不会重新进入本拦截器成环
            if (injectable && response.getStatusCode().is4xxClientError()) {
                injectionRejected.set(true);
                log.warn("enable_thinking 注入被供应商拒绝（HTTP {}），已去除该参数重试一次，"
                        + "本模型实例后续请求不再注入（强制思考模型按供应商默认开启思考）",
                        response.getStatusCode().value());
                response.close();
                ObjectNode stripped = (ObjectNode) JSON.readTree(payload);
                stripped.remove("enable_thinking");
                response = execution.execute(request, JSON.writeValueAsBytes(stripped));
            }

            // 响应侧：提取思考内容并剥离，交给 Spring AI 的永远是干净正文。
            // 只处理 chat/completions（其余路径如 embeddings 无此字段，且不必缓冲）
            if (!chatPath) {
                return response;
            }
            return bufferAndExtractReasoning(response, false);
        };
        return baseRestClientBuilder().requestInterceptor(interceptor);
    }

    /**
     * 把响应体读进内存、提取思考内容，再以可重复读取的包装返回。
     *
     * <p><b>为什么必须在这里做</b>：Spring AI 1.0.0 的 {@code OpenAiApi.ChatCompletionMessage}
     * 没有 {@code reasoning_content} 字段，供应商返回的思考内容会被 Jackson 静默丢弃——正文不受污染，
     * 但调用方也拿不到任何思考信号（长度、占用 token），烧穿判定只能靠 usage 反推。
     * 在这一层提取，既补上观测信号，又能剥掉混进 content 的思考标记。
     * anthropicShape=true 时按 /messages 的 content[] thinking 块解析（见 extractAnthropicReasoning）。
     *
     * <p><b>代价</b>：响应体全量读入内存。项目全部调用都是非流式（chatModel.call），
     * 单章正文量级几十 KB，可接受；若将来引入流式必须改成增量扫描。
     */
    private ClientHttpResponse bufferAndExtractReasoning(ClientHttpResponse response, boolean anthropicShape) {
        byte[] body;
        try (java.io.InputStream in = response.getBody()) {
            body = in.readAllBytes();
        } catch (Exception e) {
            log.warn("响应体缓冲失败，跳过思考内容提取：{}", e.getMessage());
            return response;
        }
        try {
            if (anthropicShape) {
                body = stripAnthropicThinking(body);
            } else {
                extractReasoning(body);
            }
        } catch (Exception e) {
            // 提取是增强能力，失败绝不影响正常解析
            log.debug("思考内容提取失败（不影响调用）：{}", e.getMessage());
        }
        return new BufferedClientHttpResponse(response, body);
    }

    /** 从响应 JSON 里读出思考长度与思考 token，写入线程级捕获槽 */
    private void extractReasoning(byte[] body) throws Exception {
        if (body.length == 0) {
            return;
        }
        var root = JSON.readTree(body);
        if (root == null || !root.has("choices")) {
            return;
        }
        var choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }
        var message = choices.get(0).get("message");
        long chars = 0;
        if (message != null) {
            // 主流命名：DashScope / DeepSeek / Kimi 用 reasoning_content；部分中转站用 reasoning
            for (String field : new String[]{"reasoning_content", "reasoning"}) {
                var node = message.get(field);
                if (node != null && !node.isNull()) {
                    chars += node.asText("").length();
                }
            }
        }
        Long reasoningTokens = null;
        var usage = root.get("usage");
        if (usage != null) {
            // OpenAI / DashScope：usage.completion_tokens_details.reasoning_tokens
            var details = usage.get("completion_tokens_details");
            if (details != null && details.has("reasoning_tokens")) {
                reasoningTokens = details.get("reasoning_tokens").asLong();
            } else if (usage.has("reasoning_tokens")) {
                reasoningTokens = usage.get("reasoning_tokens").asLong();
            }
        }
        if (chars > 0 || reasoningTokens != null) {
            ReasoningCapture.set(chars, reasoningTokens);
        }
    }

    /**
     * Anthropic /messages 响应的思考剥离与提取（真机联调实证的必要性，2026-10-26）：
     * Spring AI 1.0.0 的 {@code AnthropicChatModel.toChatResponse} 会把 content[] 里的
     * thinking 块<b>也包装成一个 Generation</b>（AssistantMessage 的正文取 thinking 字段），
     * 且思考块排在首位——网关 {@code response.getResult()} 取第一个结果，拿到的将是
     * <b>思考文本而非正文</b>（实测 deepseek-v4.1-flash 经 anthropic 中转：正文=思考自言自语，
     * responseChars 与 reasoningChars 完全相等）。因此在拦截器里重写 content：
     * <ul>
     *   <li>丢弃 thinking / redacted_thinking 块（思考字符数先记入 ReasoningCapture 供观测与记账）；</li>
     *   <li>多个 text 块合并为单块（Spring AI 每块一个 Generation，网关只取首个，多块会静默丢正文）；</li>
     *   <li>纯思考响应（无 text 块）→ content 置空数组，交给网关按"空回复"走换站/fail-soft。</li>
     * </ul>
     * 与 OpenAI 路径同款哲学："交给 Spring AI 的永远是干净正文"。
     * Anthropic 的 usage 只报 input/output tokens，不单独上报思考 token——判据④（思考占比）
     * 对该协议天然缺失，烧穿判定退回判据①③（撞上限/空正文）仍可用
     */
    static byte[] stripAnthropicThinking(byte[] body) throws Exception {
        if (body == null || body.length == 0) {
            return body;
        }
        var root = JSON.readTree(body);
        var content = root.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            return body;
        }
        long thinkingChars = 0;
        StringBuilder text = new StringBuilder();
        boolean hasThinking = false;
        for (var block : content) {
            if (block == null) {
                continue;
            }
            String type = block.path("type").asText();
            if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                hasThinking = true;
                thinkingChars += block.path("thinking").asText("").length();
            } else if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            }
        }
        if (thinkingChars > 0) {
            ReasoningCapture.set(thinkingChars, null);
        }
        if (!hasThinking) {
            // 无思考块的响应（大多数调用）零成本原样返回
            return body;
        }
        com.fasterxml.jackson.databind.node.ArrayNode replaced = JSON.createArrayNode();
        if (text.length() > 0) {
            ObjectNode textBlock = replaced.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", text.toString());
        }
        ((ObjectNode) root).set("content", replaced);
        return JSON.writeValueAsBytes(root);
    }

    /** 线程级思考捕获：拦截器写入，complete() 取用后即清（同一 worker 线程同步调用） */
    static final class ReasoningCapture {
        record Snapshot(long chars, Long tokens) {
        }

        private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();
        /** 本次调用已消费的快照：供紧随其后的 recordUsage 记账用，调用结束清除 */
        private static final ThreadLocal<Snapshot> FOR_RECORD = new ThreadLocal<>();

        static void set(long chars, Long tokens) {
            HOLDER.set(new Snapshot(chars, tokens));
        }

        /**
         * 取走并清空：每次调用结束后必须调，避免线程复用把上一章的数据带给下一章。
         * 同时留在 FOR_RECORD，让随后的 recordUsage 能把这两个数写进账本
         */
        static Snapshot take() {
            Snapshot snapshot = HOLDER.get();
            HOLDER.remove();
            FOR_RECORD.set(snapshot);
            return snapshot;
        }

        /** 记账用：只读不清（一次调用可能记多次账：成功/失败/降级） */
        static Snapshot currentForRecord() {
            return FOR_RECORD.get();
        }

        /** 换站前清除上一站残留，避免新站记上旧站的思考用量 */
        static void clearForRecord() {
            FOR_RECORD.remove();
        }

        /** 整个调用（含降级链）结束时清理，避免跨调用泄漏 */
        static void clearAll() {
            HOLDER.remove();
            FOR_RECORD.remove();
        }
    }

    /** 可重复读取的响应包装：body 已在内存，Spring AI 仍可正常解析 */
    private static final class BufferedClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public org.springframework.http.HttpStatusCode getStatusCode() throws java.io.IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws java.io.IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public java.io.InputStream getBody() {
            return new java.io.ByteArrayInputStream(body);
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }

    private RestClient.Builder baseRestClientBuilder() {
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * Anthropic 路径的出网拦截器：只做超时（基座自带）与 /messages 响应的思考提取。
     * enable_thinking 注入 / x-cmd-zdr / 4xx 自愈都是 OpenAI 路径专属，此处不挂
     */
    private RestClient.Builder anthropicRestClientBuilder() {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            String path = request.getURI() == null ? null : request.getURI().getPath();
            if (path == null || !path.endsWith("/messages")) {
                return response;
            }
            return bufferAndExtractReasoning(response, true);
        };
        return baseRestClientBuilder().requestInterceptor(interceptor);
    }

    /** LlmCall 中非空字段作为每调用级覆盖（选择器/摘要等小调用用小参数）；按协议选择 options 载体 */
    private ChatOptions callOptions(StoryVO.Module effective, StoryVO.Module.ChatModel selected, LlmCall call) {
        // 协议判定必须与 buildChatModel 同源：都走 effectiveApi 合成后的协议。
        // 只看 module.aiApi 会把"场景指向 Anthropic 站"的调用错发成 OpenAI options 载体
        LlmProtocol protocol = LlmProtocol.parse(effectiveApi(effective, selected).getProtocol());
        if (protocol == LlmProtocol.ANTHROPIC) {
            return anthropicCallOptions(selected, call);
        }
        // openai 与 responses 共用 OpenAiChatOptions 载体（responses 客户端按 ChatOptions 接口读取）
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (call.getMaxTokens() != null) {
            builder.maxTokens(call.getMaxTokens());
        }
        if (call.getTemperature() != null) {
            builder.temperature(call.getTemperature());
        }
        return builder.build();
    }

    /**
     * Anthropic 的每调用级 options：思考与温度必须按<b>本次生效的上限</b>整体裁决，
     * 不能拆开覆盖——Spring AI 合并规则是"runtime 非空字段压过 defaultOptions"，
     * 任何只覆盖一半的组合都可能拼出 API 拒绝的请求：
     * <ul>
     *   <li>thinking budget ≥1024 且必须 &lt; max_tokens：budget 取本次生效上限的一半，
     *       上限不足 2048 的调用（选择器/摘要）直接不开思考；</li>
     *   <li>思考开启时 temperature 只允许 1.0：显式写 1.0 压过配置温度，
     *       否则 defaultOptions 里的温度（若固化）与思考组合必 400；</li>
     *   <li>maxTokens 每次都带：Messages API 必填，且 budget 依赖它保持一致。</li>
     * </ul>
     */
    static AnthropicChatOptions anthropicCallOptions(StoryVO.Module.ChatModel selected, LlmCall call) {
        int effectiveMax = call != null && call.getMaxTokens() != null ? call.getMaxTokens()
                : selected.getMaxTokens() != null ? selected.getMaxTokens().intValue() : ANTHROPIC_DEFAULT_MAX_TOKENS;
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                .maxTokens(effectiveMax);
        boolean thinkingWanted = Boolean.TRUE.equals(selected.getEnableThinking());
        if (thinkingWanted && effectiveMax >= 2048) {
            builder.thinking(new AnthropicApi.ChatCompletionRequest.ThinkingConfig(
                    AnthropicApi.ThinkingType.ENABLED, Math.max(1024, effectiveMax / 2)));
            // 思考态唯一允许的温度值：显式 1.0 压过调用级/配置级温度（0.x + thinking 会被 400）
            builder.temperature(1.0);
        } else {
            Double temperature = call != null && call.getTemperature() != null
                    ? call.getTemperature() : selected.getTemperature();
            if (temperature != null) {
                builder.temperature(temperature);
            }
        }
        return builder.build();
    }

    private void recordUsage(StoryVO.Module module, StoryVO.Module.ChatModel selected, LlmCall call,
                             String content, long durationMs, Usage usage, Exception error) {
        recordUsageValues(selected, call, content, durationMs,
                usage == null ? null : Long.valueOf(usage.getPromptTokens()),
                usage == null ? null : Long.valueOf(usage.getCompletionTokens()),
                usage == null ? null : Long.valueOf(usage.getTotalTokens()), error);
    }

    private void recordUsageValues(StoryVO.Module.ChatModel selected, LlmCall call,
                                   String content, long durationMs, Long promptTokens,
                                   Long completionTokens, Long totalTokens, Exception error) {
        try {
            ObjectNode line = JSON.createObjectNode();
            line.put("ts", Instant.now().toString());
            if (call != null) {
                line.put("label", call.getLabel());
            }
            String jobId = MDC.get("trace-id");
            if (jobId != null) {
                line.put("jobId", jobId);
            }
            line.put("model", selected != null ? selected.getModel() : null);
            line.put("promptChars", call == null ? 0
                    : StringUtils.length(call.getSystemPrompt()) + StringUtils.length(call.getUserPrompt()));
            line.put("responseChars", content == null ? 0 : content.length());
            line.put("durationMs", durationMs);
            line.put("success", error == null);
            if (totalTokens != null) {
                line.put("promptTokens", promptTokens);
                line.put("completionTokens", completionTokens);
                line.put("totalTokens", totalTokens);
            }
            // 思考用量（供应商上报时才有）：量化日志据此显示"思考占比"，
            // 也是判据④（思考 token 占预算 ≥80%）的事后证据。
            // 由 complete() 在本次调用开始时写入线程级暂存，记账后随调用结束清除
            ReasoningCapture.Snapshot reasoning = ReasoningCapture.currentForRecord();
            if (reasoning != null) {
                if (reasoning.chars() > 0) {
                    line.put("reasoningChars", reasoning.chars());
                }
                if (reasoning.tokens() != null) {
                    line.put("reasoningTokens", reasoning.tokens());
                }
            }
            if (error != null) {
                String message = error.getClass().getSimpleName() + ": " + error.getMessage();
                line.put("error", StringUtils.abbreviate(message, 500));
            }
            synchronized (USAGE_LOCK) {
                Files.createDirectories(USAGE_LOG_PATH.getParent());
                Files.writeString(USAGE_LOG_PATH, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            // 预算熔断上报：usage 缺失（部分供应商异常响应）时记 0 不影响累计语义；
            // fuse 内部自吞异常，绝不反噬调用链
            budgetFuse.record(jobId, totalTokens);
        } catch (Exception e) {
            log.warn("usage 记账失败（不影响生成）：{}", e.getMessage());
        }
    }

}
