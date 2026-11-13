package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.service.armory.llm.LlmErrorClassifier.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 失败分类测试。
 *
 * 覆盖代表性供应商错误报文及归因：
 * 403 {@code AllocationQuota.FreeTierOnly}（额度用尽且账号仅允许免费额度）、
 * 403 {@code content_policy_violation}（内容审计命中）、
 * 400 {@code invalid_request_error}（参数被拒）、404 {@code model_not_found}（模型不存在）。
 */
class LlmErrorClassifierTest {

    private static RuntimeException withMessage(String message) {
        return new RuntimeException(message);
    }

    @Test
    @DisplayName("额度耗尽：FreeTierOnly / AllocationQuota —— 不可重试但可换模型")
    void quotaExhausted() {
        RuntimeException e = withMessage(
                "403 Forbidden: {\"code\":\"AllocationQuota.FreeTierOnly\","
                        + "\"message\":\"You have exhausted your free tier quota\"}");

        Kind kind = LlmErrorClassifier.classify(e);

        assertEquals(Kind.QUOTA_EXHAUSTED, kind);
        assertFalse(kind.isRetryable(), "同 key 重试没有意义");
        assertTrue(kind.isModelLevel(), "免费额度按模型分别计量 ⇒ 换模型有效");
        assertTrue(LlmErrorClassifier.shouldFallbackToAnotherModel(e));
    }

    @Test
    @DisplayName("模型不存在：404 model_not_found —— 必须换模型，重试永远失败")
    void modelNotFound() {
        Kind kind = LlmErrorClassifier.classify(withMessage(
                "404 Not Found: {\"error\":{\"code\":\"model_not_found\",\"message\":\"The model `deepseek-flash` does not exist\"}}"));

        assertEquals(Kind.MODEL_NOT_FOUND, kind);
        assertFalse(kind.isRetryable());
        assertTrue(kind.isModelLevel());
    }

    @Test
    @DisplayName("参数被拒：400 invalid_request_error —— 换模型或改参数，重试无意义")
    void invalidRequest() {
        Kind kind = LlmErrorClassifier.classify(withMessage(
                "400 Bad Request: {\"error\":{\"code\":\"invalid_request_error\","
                        + "\"message\":\"InvalidParameter: enable_thinking is not supported by this model\"}}"));

        assertEquals(Kind.INVALID_REQUEST, kind);
        assertFalse(kind.isRetryable());
        assertTrue(kind.isModelLevel());
    }

    @Test
    @DisplayName("内容审计：换模型也可能不过，不算模型层错误 —— 不该让整批陪葬，也不该白走降级链")
    void contentPolicy() {
        Kind kind = LlmErrorClassifier.classify(withMessage(
                "403 Forbidden: {\"code\":\"content_policy_violation\",\"message\":\"内容审计命中风险规则\"}"));

        assertEquals(Kind.CONTENT_POLICY, kind);
        assertFalse(kind.isRetryable());
        assertFalse(kind.isModelLevel(), "内容问题与模型无关");
    }

    @Test
    @DisplayName("内容审计必须先于额度判定 —— 两个都是 403，顺序错了会把内容问题误当额度问题降级")
    void contentPolicyWinsOverQuotaOrdering() {
        // 报文同时含 403 与 "quota" 字样时，具体错误码必须优先
        Kind kind = LlmErrorClassifier.classify(withMessage(
                "403 content_policy_violation: request rejected by quota-safe content filter"));

        assertEquals(Kind.CONTENT_POLICY, kind);
    }

    @Test
    @DisplayName("瞬时故障：超时/5xx/限流 —— 原样重试有效，换模型无意义")
    void transientFailures() {
        assertEquals(Kind.TRANSIENT, LlmErrorClassifier.classify(withMessage("Read timed out")));
        assertEquals(Kind.TRANSIENT, LlmErrorClassifier.classify(withMessage("503 Service Unavailable")));
        assertEquals(Kind.TRANSIENT, LlmErrorClassifier.classify(withMessage("429 Too Many Requests")));
        assertEquals(Kind.TRANSIENT, LlmErrorClassifier.classify(new HttpTimeoutException("request timed out")));
        assertEquals(Kind.TRANSIENT, LlmErrorClassifier.classify(new CancellationException("cancelled")));

        Kind transientKind = Kind.TRANSIENT;
        assertTrue(transientKind.isRetryable());
        assertFalse(transientKind.isModelLevel());
    }

    @Test
    @DisplayName("供应商内部故障：500 / engine abort —— 换模型比原样重试更有效")
    void upstreamInternal() {
        // engine abort 属于供应商内部故障，应允许更换模型
        Kind engineAbort = LlmErrorClassifier.classify(withMessage(
                "500 InternalError.Algo: engine abort, request=... model=kimi-k3"));
        assertEquals(Kind.UPSTREAM_INTERNAL, engineAbort);
        assertTrue(engineAbort.isModelLevel(), "换一个端点重跑通常就过了，而原样重试大概率再撞一次");

        // 通用 500 与 Spring AI 的 server_error 文案
        assertEquals(Kind.UPSTREAM_INTERNAL,
                LlmErrorClassifier.classify(withMessage("500 Internal Server Error")));
        assertEquals(Kind.UPSTREAM_INTERNAL,
                LlmErrorClassifier.classify(withMessage(
                        "OpenAI API returned an error: {\"error\":{\"type\":\"server_error\"}}")));

        // 5xx 里 502/503/504 仍是瞬时（网关侧抖动，原样重试足够），不能被 500 规则吃掉
        assertEquals(Kind.TRANSIENT, LlmErrorClassifier.classify(withMessage("502 Bad Gateway")));
        assertEquals(Kind.TRANSIENT, LlmErrorClassifier.classify(withMessage("504 Gateway Timeout")));
    }

    @Test
    @DisplayName("内容审计优先于供应商故障判定 —— 审计报文的通用 500 包装不该变成「换模型」")
    void contentPolicyWinsOverUpstreamInternalOrdering() {
        Kind kind = LlmErrorClassifier.classify(withMessage(
                "500 Internal Server Error: {\"code\":\"content_policy_violation\",\"message\":\"内容审计命中风险规则\"}"));

        assertEquals(Kind.CONTENT_POLICY, kind, "具体错误码必须先于宽泛的 500 判断");
    }

    @Test
    @DisplayName("异常链下钻：HTTP 错误码埋在内层 cause 时仍能分类")
    void digsIntoCauseChain() {
        RuntimeException inner = withMessage("model_not_found: no such model");
        RuntimeException middle = new RuntimeException("chat call failed", inner);
        RuntimeException outer = new IllegalStateException("LLM 调用失败", middle);

        assertEquals(Kind.MODEL_NOT_FOUND, LlmErrorClassifier.classify(outer));
    }

    @Test
    @DisplayName("无法归类：保守按可重试处理（交给传输层重试兜底），但不消耗降级链")
    void unknownIsConservative() {
        Kind kind = LlmErrorClassifier.classify(withMessage("something inexplicable happened"));

        assertEquals(Kind.UNKNOWN, kind);
        assertTrue(kind.isRetryable());
        assertFalse(kind.isModelLevel(), "归不了类就不要乱换模型，避免白烧调用");
        assertEquals(Kind.UNKNOWN, LlmErrorClassifier.classify(null));
    }

    @Test
    @DisplayName("大小写不敏感：供应商大小写写法不同不影响分类")
    void caseInsensitive() {
        assertEquals(Kind.QUOTA_EXHAUSTED, LlmErrorClassifier.classify(withMessage("FREETIERONLY")));
        assertEquals(Kind.MODEL_NOT_FOUND, LlmErrorClassifier.classify(withMessage("Model_Not_Found")));
    }

    @Test
    @DisplayName("不同类别给出不同处置说明，便于日志直接读出该做什么")
    void kindsCarryActionableDescription() {
        assertTrue(Kind.QUOTA_EXHAUSTED.getDesc().contains("换模型"));
        assertTrue(Kind.MODEL_NOT_FOUND.getDesc().contains("必须换模型"));
        assertTrue(Kind.TRANSIENT.getDesc().contains("重试"));
        assertTrue(Kind.CONTENT_POLICY.getDesc().contains("人工"));
        assertTrue(Kind.UPSTREAM_INTERNAL.getDesc().contains("换模型"));
    }

    @Test
    @DisplayName("降级门：只有内容审计不换模型，其余类别一律允许（与网关 shouldFallback 同口径）")
    void onlyContentPolicyBlocksModelSwitch() {
        // 网关唯一的分流判据是"是不是内容审计"，其余一律换模型——这里钉住该口径的意图，
        // 避免有人日后"顺手"把它改回 isModelLevel()（那会让 500/超时重新变成整批终止）
        assertFalse(Kind.CONTENT_POLICY.isModelLevel());
        assertTrue(Kind.UPSTREAM_INTERNAL.isModelLevel());
        assertTrue(Kind.QUOTA_EXHAUSTED.isModelLevel());
        assertTrue(Kind.MODEL_NOT_FOUND.isModelLevel());
        assertTrue(Kind.INVALID_REQUEST.isModelLevel());
    }
}
