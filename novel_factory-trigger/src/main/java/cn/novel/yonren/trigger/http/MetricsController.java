package cn.novel.yonren.trigger.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 单体运行观测：汇总 LLM 网关已落盘的 usage JSONL，读取失败时返回空统计。 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    /** usage 记账目录：网关按月轮转（llm-usage.jsonl / llm-usage-202609.jsonl），此处全量汇总 */
    private static final Path USAGE_DIR = Path.of("data", "log");

    private static final String USAGE_PREFIX = "llm-usage";
    private static final String USAGE_SUFFIX = ".jsonl";

    @GetMapping("/llm")
    public Map<String, Object> llm() {
        long calls = 0, failures = 0, promptChars = 0, responseChars = 0;
        long promptTokens = 0, completionTokens = 0, totalTokens = 0, durationMs = 0;
        // 思考用量：仅部分供应商上报（reasoning_content / reasoning_tokens），缺失记 0
        long reasoningChars = 0, reasoningTokens = 0, reasoningCalls = 0;
        Map<String, Long> byModel = new LinkedHashMap<>();
        for (Path usageLog : usageLogs()) {
            try {
                for (String line : Files.readAllLines(usageLog)) {
                    if (line == null || line.isBlank()) continue;
                    try {
                        JSONObject item = JSON.parseObject(line);
                        calls++;
                        if (!item.getBooleanValue("success")) failures++;
                        promptChars += item.getLongValue("promptChars");
                        responseChars += item.getLongValue("responseChars");
                        promptTokens += item.getLongValue("promptTokens");
                        completionTokens += item.getLongValue("completionTokens");
                        totalTokens += item.getLongValue("totalTokens");
                        durationMs += item.getLongValue("durationMs");
                        long rc = item.getLongValue("reasoningChars");
                        long rt = item.getLongValue("reasoningTokens");
                        if (rc > 0 || rt > 0) {
                            reasoningCalls++;
                            reasoningChars += rc;
                            reasoningTokens += rt;
                        }
                        String model = item.getString("model");
                        if (model != null && !model.isBlank()) byModel.merge(model, 1L, Long::sum);
                    } catch (RuntimeException ignored) {
                        // 允许末尾半行或旧格式记录，不影响其余统计
                    }
                }
            } catch (IOException ignored) {
                // 观测接口 fail-soft，不反噬写作功能
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calls", calls);
        result.put("failures", failures);
        result.put("failureRate", calls == 0 ? 0D : (double) failures / calls);
        result.put("promptChars", promptChars);
        result.put("responseChars", responseChars);
        result.put("promptTokens", promptTokens);
        result.put("completionTokens", completionTokens);
        result.put("totalTokens", totalTokens);
        result.put("durationMs", durationMs);
        result.put("averageDurationMs", calls == 0 ? 0D : (double) durationMs / calls);
        // 思考占比：只统计上报了思考量的调用；混用非思考模型时该比例才有解释力
        result.put("reasoningChars", reasoningChars);
        result.put("reasoningTokens", reasoningTokens);
        result.put("reasoningCalls", reasoningCalls);
        result.put("reasoningTokenRatio", completionTokens == 0 ? 0D
                : (double) reasoningTokens / completionTokens);
        result.put("byModel", byModel);
        return result;
    }

    /**
     * usage 记账文件清单：网关按月轮转（当月 llm-usage.jsonl，历史 llm-usage-yyyyMM.jsonl），
     * 本接口汇总全部——轮转只是防止单文件无界膨胀，观测口径仍是"全量"。
     * 目录缺失或列举失败返回空表（fail-soft）。
     */
    private static List<Path> usageLogs() {
        if (!Files.isDirectory(USAGE_DIR)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(USAGE_DIR)) {
            List<Path> logs = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(USAGE_PREFIX) && name.endsWith(USAGE_SUFFIX);
                    })
                    .sorted()
                    .forEach(logs::add);
            return logs;
        } catch (IOException e) {
            return List.of();
        }
    }
}
