package cn.novel.yonren.trigger.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** usage JSONL 读取契约：沿用旧记录默认值，坏行不能污染已接受记录的统计。 */
class MetricsControllerTest {

    @TempDir
    Path dir;

    @Test
    void missingDirectoryAndNonDirectoryReturnEmptyStatistics() throws Exception {
        assertEmptyStatistics(new MetricsController(dir.resolve("missing")).llm());
        Path file = Files.writeString(dir.resolve("not-a-directory"), "{}");
        assertEmptyStatistics(new MetricsController(file).llm());
    }

    @Test
    void emptyDirectoryAndBlankLinesReturnEmptyStatistics() throws Exception {
        assertEmptyStatistics(new MetricsController(dir).llm());
        writeLog("llm-usage.jsonl", "", " ", "\t");
        writeLog("llm-usage-202601.jsonl", "");
        assertEmptyStatistics(new MetricsController(dir).llm());
    }

    @Test
    void validRecordsAggregateLongCountersTokensAndReasoning() throws Exception {
        writeLog("llm-usage.jsonl",
                "{\"success\":true,\"model\":\"qwen\",\"promptChars\":3000000000,\"responseChars\":120,"
                        + "\"promptTokens\":90,\"completionTokens\":60,\"totalTokens\":150,\"durationMs\":300,"
                        + "\"reasoningChars\":40,\"reasoningTokens\":20}",
                "{\"success\":false,\"model\":\"qwen\",\"promptChars\":50,\"responseChars\":30,"
                        + "\"promptTokens\":15,\"completionTokens\":10,\"totalTokens\":25,\"durationMs\":100}",
                "{\"success\":true,\"model\":\"claude\",\"promptChars\":10,\"responseChars\":20,"
                        + "\"promptTokens\":5,\"completionTokens\":20,\"totalTokens\":25,\"durationMs\":200,"
                        + "\"reasoningChars\":0,\"reasoningTokens\":5}");

        Map<String, Object> stats = new MetricsController(dir).llm();
        assertEquals(3L, stats.get("calls"));
        assertEquals(1L, stats.get("failures"));
        assertEquals(1D / 3, stats.get("failureRate"));
        assertEquals(3_000_000_060L, stats.get("promptChars"));
        assertEquals(170L, stats.get("responseChars"));
        assertEquals(110L, stats.get("promptTokens"));
        assertEquals(90L, stats.get("completionTokens"));
        assertEquals(200L, stats.get("totalTokens"));
        assertEquals(600L, stats.get("durationMs"));
        assertEquals(200D, stats.get("averageDurationMs"));
        assertEquals(40L, stats.get("reasoningChars"));
        assertEquals(25L, stats.get("reasoningTokens"));
        assertEquals(2L, stats.get("reasoningCalls"));
        assertEquals(25D / 90, stats.get("reasoningTokenRatio"));
        assertEquals(Map.of("qwen", 2L, "claude", 1L), stats.get("byModel"));
    }

    @Test
    void missingAndNullValuesKeepLegacyDefaults() throws Exception {
        writeLog("llm-usage.jsonl", "{}",
                "{\"success\":null,\"model\":null,\"promptChars\":null,\"responseChars\":null,"
                        + "\"promptTokens\":null,\"completionTokens\":null,\"totalTokens\":null,"
                        + "\"durationMs\":null,\"reasoningChars\":null,\"reasoningTokens\":null}",
                "{\"success\":true}");

        Map<String, Object> stats = new MetricsController(dir).llm();
        assertEquals(3L, stats.get("calls"));
        assertEquals(2L, stats.get("failures"));
        assertEquals(2D / 3, stats.get("failureRate"));
        for (String field : List.of("promptChars", "responseChars", "promptTokens", "completionTokens",
                "totalTokens", "durationMs", "reasoningChars", "reasoningTokens", "reasoningCalls")) {
            assertEquals(0L, stats.get(field), field);
        }
        assertEquals(0D, stats.get("averageDurationMs"));
        assertEquals(0D, stats.get("reasoningTokenRatio"));
        assertEquals(Map.of(), stats.get("byModel"));
    }

    @Test
    void legacyScalarCoercionsRemainStable() throws Exception {
        writeLog("llm-usage.jsonl",
                "{\"success\":\"true\",\"promptTokens\":\"12\",\"completionTokens\":\"\",\"totalTokens\":\"null\"}",
                "{\"success\":\"yes\",\"promptTokens\":2}",
                "{\"success\":1,\"promptTokens\":1.5}",
                "{\"success\":0}");

        Map<String, Object> stats = new MetricsController(dir).llm();
        assertEquals(4L, stats.get("calls"));
        assertEquals(2L, stats.get("failures"));
        assertEquals(15L, stats.get("promptTokens"));
        assertEquals(0L, stats.get("completionTokens"));
        assertEquals(0L, stats.get("totalTokens"));
    }

    @Test
    void malformedAndRejectedRecordsNeverPartiallyContribute() throws Exception {
        writeLog("llm-usage.jsonl",
                "{\"success\":true,\"model\":\"valid\",\"promptChars\":5,\"promptTokens\":3,\"durationMs\":10}",
                "{not-json", "null", "[]",
                "{\"success\":false,\"promptChars\":1000,\"responseChars\":1000,\"promptTokens\":\"bad\"}",
                "{\"success\":false,\"promptChars\":900,\"responseChars\":800,\"promptTokens\":700,"
                        + "\"completionTokens\":600,\"totalTokens\":500,\"durationMs\":400,"
                        + "\"reasoningChars\":300,\"reasoningTokens\":\"bad\",\"model\":\"rejected\"}",
                "{\"success\":[],\"promptChars\":1000}",
                "{\"success\":false,\"model\":\"valid\",\"promptChars\":6,\"promptTokens\":4,\"durationMs\":20}",
                "{\"success\":true,\"promptChars\":");

        Map<String, Object> stats = new MetricsController(dir).llm();
        assertEquals(2L, stats.get("calls"));
        assertEquals(1L, stats.get("failures"));
        assertEquals(0.5D, stats.get("failureRate"));
        assertEquals(11L, stats.get("promptChars"));
        assertEquals(7L, stats.get("promptTokens"));
        assertEquals(30L, stats.get("durationMs"));
        assertEquals(15D, stats.get("averageDurationMs"));
        for (String field : List.of("responseChars", "completionTokens", "totalTokens",
                "reasoningChars", "reasoningTokens", "reasoningCalls")) {
            assertEquals(0L, stats.get(field), field);
        }
        assertEquals(Map.of("valid", 2L), stats.get("byModel"));
    }

    @Test
    void modelGroupingIncludesFailuresAndDoesNotTrimNames() throws Exception {
        writeLog("llm-usage.jsonl",
                "{\"success\":true,\"model\":\"model\"}",
                "{\"success\":false,\"model\":\"model\"}",
                "{\"success\":true,\"model\":null}",
                "{\"success\":true}",
                "{\"success\":true,\"model\":\" \t\"}",
                "{\"success\":true,\"model\":\" model \"}");

        Map<String, Object> stats = new MetricsController(dir).llm();
        assertEquals(6L, stats.get("calls"));
        assertEquals(1L, stats.get("failures"));
        assertEquals(Map.of("model", 2L, " model ", 1L), stats.get("byModel"));
    }

    @Test
    void rotatedLogsAggregateButUnrelatedFilesAndDirectoriesAreIgnored() throws Exception {
        writeLog("llm-usage-202601.jsonl",
                "{\"success\":true,\"model\":\"reasoner\",\"completionTokens\":100,"
                        + "\"reasoningChars\":5,\"reasoningTokens\":20}");
        writeLog("llm-usage.jsonl",
                "{\"success\":true,\"model\":\"plain\",\"completionTokens\":300}");
        writeLog("unrelated.jsonl", "{}");
        writeLog("llm-usage.jsonl.bak", "{}");
        writeLog("llm-usage.txt", "{}");
        Files.createDirectory(dir.resolve("llm-usage-directory.jsonl"));

        Map<String, Object> stats = new MetricsController(dir).llm();
        assertEquals(2L, stats.get("calls"));
        assertEquals(0L, stats.get("failures"));
        assertEquals(400L, stats.get("completionTokens"));
        assertEquals(20L, stats.get("reasoningTokens"));
        assertEquals(5L, stats.get("reasoningChars"));
        assertEquals(1L, stats.get("reasoningCalls"));
        assertEquals(20D / 400, stats.get("reasoningTokenRatio"));
        assertEquals(Map.of("reasoner", 1L, "plain", 1L), stats.get("byModel"));
    }

    @Test
    void httpEndpointReturnsStatisticsAndAnEmptyDirectoryIsStill200() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(dir)).build();
        mvc.perform(get("/api/metrics/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(0))
                .andExpect(jsonPath("$.failureRate").value(0D))
                .andExpect(jsonPath("$.averageDurationMs").value(0D))
                .andExpect(jsonPath("$.byModel").isEmpty());

        writeLog("llm-usage.jsonl",
                "{\"timestamp\":\"2026-11-14T00:00:00Z\",\"success\":true,\"model\":\"qwen\","
                        + "\"promptChars\":3000000000,\"completionTokens\":10,\"reasoningTokens\":2}");
        mvc.perform(get("/api/metrics/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(1))
                .andExpect(jsonPath("$.promptChars").value(3_000_000_000L))
                .andExpect(jsonPath("$.completionTokens").value(10))
                .andExpect(jsonPath("$.reasoningCalls").value(1))
                .andExpect(jsonPath("$.reasoningTokenRatio").value(0.2D))
                .andExpect(jsonPath("$.byModel.qwen").value(1));
    }

    private void writeLog(String name, String... lines) throws Exception {
        Files.writeString(dir.resolve(name), String.join("\n", lines));
    }

    private static void assertEmptyStatistics(Map<String, Object> stats) {
        assertEquals(15, stats.size());
        for (String field : List.of("calls", "failures", "promptChars", "responseChars", "promptTokens",
                "completionTokens", "totalTokens", "durationMs", "reasoningChars", "reasoningTokens",
                "reasoningCalls")) {
            assertEquals(0L, stats.get(field), field);
        }
        for (String field : List.of("failureRate", "averageDurationMs", "reasoningTokenRatio")) {
            assertEquals(0D, stats.get(field), field);
        }
        assertEquals(Map.of(), stats.get("byModel"));
    }
}
