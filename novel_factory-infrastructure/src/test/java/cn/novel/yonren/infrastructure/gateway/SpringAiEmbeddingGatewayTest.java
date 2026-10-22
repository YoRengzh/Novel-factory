package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Embedding 网关测试：瞬时/确定性失败分类、瞬时失败有界重试退避后成功、
 * 确定性失败不浪费重试快速失败、重试耗尽抛最后一次异常
 */
class SpringAiEmbeddingGatewayTest {

    @Test
    void transientClassification() {
        assertTrue(SpringAiEmbeddingGateway.isTransientEmbeddingFailure(
                new TransientAiException("429 too many requests")));
        assertTrue(SpringAiEmbeddingGateway.isTransientEmbeddingFailure(
                new ResourceAccessException("connection reset")));
        assertFalse(SpringAiEmbeddingGateway.isTransientEmbeddingFailure(
                new NonTransientAiException("400 - {\"code\":\"1210\"}")));
        assertFalse(SpringAiEmbeddingGateway.isTransientEmbeddingFailure(
                new IllegalStateException("未配置 embedding-api")));
    }

    @Test
    void embed_retriesTransientFailureThenSucceeds() {
        SpringAiEmbeddingGateway gateway = Mockito.spy(new SpringAiEmbeddingGateway(3, 20, 600));
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        StoryVO.Module module = module();
        doReturn(model).when(gateway).embeddingModel(module);
        doNothing().when(gateway).sleepBeforeRetry(anyLong());
        EmbeddingResponse ok = new EmbeddingResponse(List.of(new Embedding(new float[8], 0)));
        doThrow(new ResourceAccessException("connection reset"))
                .doReturn(ok)
                .when(gateway).callModel(eq(model), eq(module), anyList());

        List<float[]> vectors = gateway.embed(module, List.of("文本"));

        assertEquals(1, vectors.size());
        verify(gateway, times(1)).sleepBeforeRetry(anyLong());
    }

    @Test
    void embed_deterministicFailureFailsFastWithoutRetry() {
        SpringAiEmbeddingGateway gateway = Mockito.spy(new SpringAiEmbeddingGateway(3, 20, 600));
        StoryVO.Module module = module();
        doReturn(mock(OpenAiEmbeddingModel.class)).when(gateway).embeddingModel(module);
        NonTransientAiException rejected = new NonTransientAiException("400 - 参数有误");
        doThrow(rejected).when(gateway).callModel(any(), any(), anyList());

        // 4xx 确定性拒绝（欠费/鉴权/参数）重试无意义：一次都不重试、不打退避等待
        NonTransientAiException thrown = assertThrows(NonTransientAiException.class,
                () -> gateway.embed(module, List.of("文本")));

        assertSame(rejected, thrown);
        verify(gateway, times(0)).sleepBeforeRetry(anyLong());
    }

    @Test
    void embed_retryExhaustedThrowsLastFailure() {
        SpringAiEmbeddingGateway gateway = Mockito.spy(new SpringAiEmbeddingGateway(2, 20, 600));
        StoryVO.Module module = module();
        doReturn(mock(OpenAiEmbeddingModel.class)).when(gateway).embeddingModel(module);
        doNothing().when(gateway).sleepBeforeRetry(anyLong());
        doThrow(new ResourceAccessException("read timeout"))
                .when(gateway).callModel(any(), any(), anyList());

        assertThrows(ResourceAccessException.class, () -> gateway.embed(module, List.of("文本")));

        verify(gateway, times(2)).callModel(any(), any(), anyList());
        verify(gateway, times(1)).sleepBeforeRetry(anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void embed_capsOverlongInputBeforeCallingModel() {
        // 智谱 embedding-3 单条限约 3072 tokens：超限整批 400-1210，入模前逐条截断（payload 原文不受影响）
        SpringAiEmbeddingGateway gateway = Mockito.spy(new SpringAiEmbeddingGateway(1, 20, 600));
        StoryVO.Module module = module();
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        doReturn(model).when(gateway).embeddingModel(module);
        // 条数必须与请求一一对应（网关会校验）：2 条输入 → 2 条结果
        EmbeddingResponse ok = new EmbeddingResponse(List.of(
                new Embedding(new float[8], 0), new Embedding(new float[8], 1)));
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        doReturn(ok).when(gateway).callModel(eq(model), eq(module), captor.capture());

        String shortText = "短文本";
        // 构造**真正超限**的输入：原用例写死 3000 字却期望被截到 2000，
        // 而 MAX_EMBED_INPUT_CHARS 在 已调到 8000 —— 断言早已过时（该测试长期没被跑到）
        String overlongText = "长".repeat(SpringAiEmbeddingGateway.MAX_EMBED_INPUT_CHARS + 1000);
        gateway.embed(module, List.of(shortText, overlongText));

        List<String> sent = captor.getValue();
        assertEquals(shortText, sent.get(0), "未超限的输入原样送入");
        assertEquals(SpringAiEmbeddingGateway.MAX_EMBED_INPUT_CHARS, sent.get(1).length(),
                "超过安全上限的输入必须被截断（防的是把整本书一次性塞进来的编程错误）");
    }

    @Test
    void resolveBatchSize_defaultsWhenUnsetOrIllegal() {
        // 换 text-embedding-v3 时暴露：单次请求条数上限为 10，超限直接 400 并终止作业
        StoryVO.Module module = module();
        assertEquals(SpringAiEmbeddingGateway.DEFAULT_EMBED_BATCH_SIZE,
                SpringAiEmbeddingGateway.resolveBatchSize(module), "未配置时用默认值");

        module.getEmbeddingApi().setBatchSize(0);
        assertEquals(SpringAiEmbeddingGateway.DEFAULT_EMBED_BATCH_SIZE,
                SpringAiEmbeddingGateway.resolveBatchSize(module), "0 是非法值，退回默认");

        module.getEmbeddingApi().setBatchSize(-3);
        assertEquals(SpringAiEmbeddingGateway.DEFAULT_EMBED_BATCH_SIZE,
                SpringAiEmbeddingGateway.resolveBatchSize(module), "负数非法，退回默认");

        module.getEmbeddingApi().setBatchSize(5);
        assertEquals(5, SpringAiEmbeddingGateway.resolveBatchSize(module), "合法配置按其执行");
    }

    @Test
    void resolveBatchSize_handlesMissingEmbeddingApi() {
        assertEquals(SpringAiEmbeddingGateway.DEFAULT_EMBED_BATCH_SIZE,
                SpringAiEmbeddingGateway.resolveBatchSize(new StoryVO.Module()));
    }

    private StoryVO.Module module() {
        StoryVO.Module module = new StoryVO.Module();
        StoryVO.Module.EmbeddingApi api = new StoryVO.Module.EmbeddingApi();
        api.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        api.setApiKey("test-key");
        api.setModel("embedding-3");
        module.setEmbeddingApi(api);
        return module;
    }
}
