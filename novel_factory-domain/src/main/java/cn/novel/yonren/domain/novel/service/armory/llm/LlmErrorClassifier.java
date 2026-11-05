package cn.novel.yonren.domain.novel.service.armory.llm;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;


public final class LlmErrorClassifier {

    /** 判定"该不该重试 / 该不该换模型"的失败类别 */
    public enum Kind {

        /** 瞬时故障：超时、网络中断、5xx、线程取消——原样重试即可，换模型无意义 */
        TRANSIENT("瞬时故障（可原样重试）", true, false),

        /** 额度耗尽 / 仅免费额度：同 key 重试无意义，但额度按模型分别计量 ⇒ 换模型有效 */
        QUOTA_EXHAUSTED("额度耗尽（同模型重试无效，可换模型）", false, true),

        /** 模型不存在（名字写错或该账号无权）：重试永远失败，必须换模型 */
        MODEL_NOT_FOUND("模型不存在（必须换模型）", false, true),

        /** 请求参数被拒（如强制思考模型被要求关思考）：重试永远失败，须换模型或改参数 */
        INVALID_REQUEST("请求参数被拒（须换模型或改参数）", false, true),

        /**
         * 供应商侧内部故障（500 / engine abort / InternalError.Algo）：
         * 重试同一模型大概率再撞一次（长时间思考跑到一半被上游掐断），换模型更有效。
         *
         * 2026-09-30 新增：此前 500 落到 {@link #UNKNOWN}（modelLevel=false），
         * 于是"思考模型被上游 abort"永远不触发降级链——而这恰恰是思考模型最常见的失败。
         */
        UPSTREAM_INTERNAL("供应商内部故障（换模型比原样重试更有效）", true, true),

        /** 内容审计命中：与模型无关，换模型也可能不过；属内容问题，不该让整批陪葬 */
        CONTENT_POLICY("内容审计命中（模型无关，需人工/跳过）", false, false),

        /** 无法归类：保守按"可重试"处理，交给传输层重试策略兜底 */
        UNKNOWN("无法归类（保守按可重试处理）", true, false);

        private final String desc;
        private final boolean retryable;
        private final boolean modelLevel;

        Kind(String desc, boolean retryable, boolean modelLevel) {
            this.desc = desc;
            this.retryable = retryable;
            this.modelLevel = modelLevel;
        }

        public String getDesc() {
            return desc;
        }

        /** 原样重试（同一模型）是否有意义 */
        public boolean isRetryable() {
            return retryable;
        }

        /** 是否属"模型层"错误——换模型比原样重试更有意义（场景级降级链的适用条件） */
        public boolean isModelLevel() {
            return modelLevel;
        }
    }

    /**
     * 匹配规则：按顺序命中即返回。
     * 顺序敏感——先判更具体的，例如 {@code content_policy_violation} 必须先于任何宽泛的 403 判断，
     * 否则会被误归为额度问题而白走一轮降级。
     */
    private static final List<Rule> RULES = List.of(
            // 内容审计：百炼报 content_policy_violation / data_inspection_failed，OpenAI 报 content_filter
            new Rule(Kind.CONTENT_POLICY, "content_policy_violation", "content_policy", "content_filter",
                    "data_inspection", "内容审计", "风险规则"),
            // 额度：百炼报 AllocationQuota.FreeTierOnly / insufficient_quota
            new Rule(Kind.QUOTA_EXHAUSTED, "freetieronly", "allocationquota", "insufficient_quota",
                    "exceeded your current quota", "quota"),
            // 模型不存在
            new Rule(Kind.MODEL_NOT_FOUND, "model_not_found", "model not found", "no such model",
                    "does not exist", "unknown model"),
            // 请求非法
            new Rule(Kind.INVALID_REQUEST, "invalid_request_error", "invalidparameter", "invalid_parameter",
                    "invalid_value", "invalid value", "unsupported value"),
            // 供应商侧内部故障 补）：思考模型跑久了上游会直接 abort，
            // 回报 500 InternalError.Algo / engine abort / 引擎异常 —— 这类错误**换模型有效**
            //（同一份请求换一个供应商端点重跑通常就过了），而原样重试同一模型大概率再撞一次。
            // 实测：kimi-k3 跑卷蓝图 795s 后报 500 engine abort，若不归此类则落到 UNKNOWN
            //（modelLevel=false，不换模型）+ 传输层把 500 当服务端错误重试 → 白烧两轮长调用后整批终止。
            new Rule(Kind.UPSTREAM_INTERNAL, "internalerror.algo", "internal_error.algo", "engine abort",
                    "engine_abort", "engineerror", "internal server error", "server_error",
                    "service internal error", " 500", "上游异常", "引擎异常"),
            // 瞬时故障
            new Rule(Kind.TRANSIENT, "timeout", "timed out", "connection reset", "connection refused",
                    "temporarily unavailable", "service unavailable", "bad gateway", "gateway timeout",
                    " 502", " 503", " 504", "rate limit", "too many requests", "overloaded")
    );

    private LlmErrorClassifier() {
    }

    /**
     * 分类一次失败。遍历 cause 链取全部 message 片段做匹配（HTTP 错误码常埋在内层 cause）。
     * 入参为 null 返回 {@link Kind#UNKNOWN}。
     */
    public static Kind classify(Throwable error) {
        if (error == null) {
            return Kind.UNKNOWN;
        }
        String text = collectMessages(error).toLowerCase(Locale.ROOT);
        // 异常类型优先：Spring AI 的瞬时异常是被设计用来重试的，且它的 message 可能不含关键词
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof CancellationException) {
                return Kind.TRANSIENT;
            }
        }
        for (Rule rule : RULES) {
            for (String token : rule.tokens) {
                if (text.contains(token)) {
                    return rule.kind;
                }
            }
        }
        return Kind.UNKNOWN;
    }

    /** 收集异常链上的全部 message（上限 8 层，防自引用死循环） */
    private static String collectMessages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            sb.append(current.getClass().getSimpleName()).append(' ');
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return sb.toString();
    }

    public static boolean shouldFallbackToAnotherModel(Throwable error) {
        return classify(error).isModelLevel();
    }

    /** 规则载体：命中任一 token 即归入该类 */
    private record Rule(Kind kind, String... tokens) {
    }
}
