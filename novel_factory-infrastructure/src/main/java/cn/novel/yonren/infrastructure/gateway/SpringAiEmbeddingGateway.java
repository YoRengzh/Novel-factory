package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


@Component
public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingGateway.class);

    /** 瞬时失败重试退避基数：第 n 次失败后等待 n * 1s 再试（1s、2s） */
    private static final long RETRY_BACKOFF_MS = 1000L;

    static final int MAX_EMBED_INPUT_CHARS = 8000;
    private final ConcurrentHashMap<String, OpenAiEmbeddingModel> modelCache = new ConcurrentHashMap<>();

    /** 瞬时失败最大尝试次数（含首次）；spring.ai.retry.embedding-max-attempts 可调，1=关闭重试 */
    private final int maxAttempts;

    /** 出网请求工厂（连接/读取超时）：避免供应商静默不回包时向量化调用永久阻塞作业 */
    private final ClientHttpRequestFactory requestFactory;

    /** 运行时覆盖源（嵌入分区）：字段注入不动既有构造器签名；单测直构时为 null，embed 内已判空 */
    @Resource
    private LlmRuntimeConfig llmRuntimeConfig;

    public SpringAiEmbeddingGateway(@Value("${spring.ai.retry.embedding-max-attempts:3}") int maxAttempts,
                                    @Value("${novel.llm.connect-timeout-seconds:20}") int connectTimeoutSeconds,
                                    @Value("${novel.llm.read-timeout-seconds:600}") int readTimeoutSeconds) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.requestFactory = HttpTimeouts.requestFactory(connectTimeoutSeconds, readTimeoutSeconds);
    }

    @Override
    public List<float[]> embed(StoryVO.Module module, List<String> texts) {
        if (module == null || module.getEmbeddingApi() == null) {
            throw new IllegalStateException("未配置 embedding-api（yml story.module.embedding-api），无法向量化");
        }
        // 运行时嵌入覆盖（前端「向量检索分区」表单）：客户端按 地址|密钥|模型 缓存，
        // 覆盖变化即换新客户端，保存后下次向量调用即时生效。llmRuntimeConfig 允许为 null（单测直构）
        if (llmRuntimeConfig != null) {
            module = llmRuntimeConfig.applyEmbedding(module);
        }
        OpenAiEmbeddingModel model = embeddingModel(module);
        List<String> capped = capInputLength(texts);
        int batchSize = resolveBatchSize(module);
        List<float[]> vectors = new ArrayList<>(capped.size());
        for (int start = 0; start < capped.size(); start += batchSize) {
            int end = Math.min(capped.size(), start + batchSize);
            List<String> batch = capped.subList(start, end);
            EmbeddingResponse response = callWithRetry(model, module, batch);
            List<Embedding> results = response == null ? null : response.getResults();
            // 条数必须与请求一一对应：少一条会让其后所有文本错配到别人的向量上
            // （调用方按 index 配对，长度差看不出来，只表现为“记忆召回莫名其妙”）
            if (results == null || results.size() != batch.size()) {
                throw new IllegalStateException("embedding 返回条数与请求不一致：请求 " + batch.size()
                        + " 条，返回 " + (results == null ? 0 : results.size())
                        + " 条（批量起点 " + start + "，模型 " + module.getEmbeddingApi().getModel()
                        + "）。为防止向量错位，已终止本次向量化");
            }
            for (Embedding embedding : results) {
                float[] vector = embedding == null ? null : embedding.getOutput();
                if (vector == null || vector.length == 0) {
                    throw new IllegalStateException("embedding 返回空向量（批量起点 " + start
                            + "，模型 " + module.getEmbeddingApi().getModel() + "）");
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }

    /** 单次请求输入条数的兜底上限：主流供应商都支持 ≤10，故取 10 作默认（可被 yml 覆盖） */
    static final int DEFAULT_EMBED_BATCH_SIZE = 10;

    /** 批量大小：优先取 yml 的 embedding-api.batch-size，非法值（null/≤0）退回默认（包级可见以便测试） */
    static int resolveBatchSize(StoryVO.Module module) {
        Integer configured = module.getEmbeddingApi() == null
                ? null : module.getEmbeddingApi().getBatchSize();
        return configured == null || configured <= 0 ? DEFAULT_EMBED_BATCH_SIZE : configured;
    }

    private static List<String> capInputLength(List<String> texts) {
        List<String> capped = new ArrayList<>(texts.size());
        for (String text : texts) {
            capped.add(text != null && text.length() > MAX_EMBED_INPUT_CHARS
                    ? text.substring(0, MAX_EMBED_INPUT_CHARS) : text);
        }
        return capped;
    }

    private EmbeddingResponse callWithRetry(OpenAiEmbeddingModel model, StoryVO.Module module, List<String> texts) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callModel(model, module, texts);
            } catch (RuntimeException e) {
                last = e;
                if (!isTransientEmbeddingFailure(e) || attempt == maxAttempts) {
                    throw e;
                }
                long backoff = RETRY_BACKOFF_MS * attempt;
                log.warn("embedding 调用瞬时失败（第 {}/{} 次尝试），{}ms 后重试：{}",
                        attempt, maxAttempts, backoff, e.getMessage());
                sleepBeforeRetry(backoff);
            }
        }
        throw last;
    }

    /** 单次模型调用（包级可见供单测 spy 桩替换） */
    EmbeddingResponse callModel(OpenAiEmbeddingModel model, StoryVO.Module module, List<String> texts) {
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder()
                .model(module.getEmbeddingApi().getModel());
        // 维度显式钉死：供应商默认值漂移会让 Qdrant 集合维度静默不匹配（见 EmbeddingApi.dimensions）
        if (module.getEmbeddingApi().getDimensions() != null) {
            options.dimensions(module.getEmbeddingApi().getDimensions());
        }
        return model.call(new EmbeddingRequest(texts, options.build()));
    }

    /** 瞬时/确定性失败分类（包级可见供单测） */
    static boolean isTransientEmbeddingFailure(RuntimeException e) {
        return e instanceof TransientAiException || e instanceof ResourceAccessException;
    }

    void sleepBeforeRetry(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding 重试等待被中断", ie);
        }
    }

    OpenAiEmbeddingModel embeddingModel(StoryVO.Module module) {
        StoryVO.Module.EmbeddingApi api = module.getEmbeddingApi();
        String key = api.getBaseUrl() + "|" + api.getApiKey() + "|" + api.getModel();
        return modelCache.computeIfAbsent(key, k -> {
            OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                    .baseUrl(api.getBaseUrl())
                    .apiKey(api.getApiKey());
            if (StringUtils.isNotBlank(api.getEmbeddingsPath())) {
                apiBuilder.embeddingsPath(api.getEmbeddingsPath());
            }
            log.info("Embedding 网关构建模型客户端：baseUrl={}, model={}", api.getBaseUrl(), api.getModel());
            // 1.0.0 无 builder：模型名等默认参数走每次调用的 EmbeddingRequest options
            return new OpenAiEmbeddingModel(apiBuilder
                    .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                    .build());
        });
    }

}
