package cn.novel.yonren.domain.novel.service.armory.memory;

import static org.mockito.Mockito.doThrow;
import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.adapter.repository.VectorStore;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LedgerEntry;
import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoredPoint;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryMemoryProperties;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故事记忆层测试：索引点结构（chapter/ledger/bible 幂等）、检索门控（minScore / 字符预算 / 近章窗口去重）、
 * 检索失败语义（瞬时失败有界重试；重试耗尽或确定性失败则终止作业，不静默降级）
 */
@SuppressWarnings("unchecked")
class StoryMemoryServiceTest { /*MARKER-CHECK-1*/

    private EmbeddingGateway embeddingGateway;
    private VectorStore vectorStore;
    private ChapterMemoryService chapterMemoryService;
    private StoryMemoryProperties properties;
    private StoryMemoryService service;

    @BeforeEach
    void setUp() {
        embeddingGateway = mock(EmbeddingGateway.class);
        vectorStore = mock(VectorStore.class);
        chapterMemoryService = mock(ChapterMemoryService.class);
        properties = new StoryMemoryProperties();
        service = spy(new StoryMemoryService(embeddingGateway, vectorStore, chapterMemoryService, properties));
        // 重试退避桩掉：断言重试次数而非真实等待 1s/2s
        doNothing().when(service).sleepBeforeRetry(anyLong());
        when(embeddingGateway.embed(any(), anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(1);
            return texts.stream().map(t -> new float[8]).toList();
        });
    }

    // ==================== 回滚索引对账（2026-10-11，自旧系统对齐） ====================

    private List<ChapterSummaryEntity> threeChapters() {
        return List.of(
                ChapterSummaryEntity.builder().chapterNo(1).title("一").summary("甲").build(),
                ChapterSummaryEntity.builder().chapterNo(2).title("二").summary("乙").build(),
                ChapterSummaryEntity.builder().chapterNo(3).title("三").summary("丙").build());
    }

    @Test
    void rebuildIndex_writesWholeStoryOnce_whenCollectionEmpty(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试\n世界观：架空");
        when(chapterMemoryService.buildCharacterLedger(anyList()))
                .thenReturn(List.of(new LedgerEntry("角色丙", "报名丙班", 1, 3, null, null, false)));
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        List<ChapterSummaryEntity> summaries = threeChapters();
        // 先经末章写入建立索引签名；缺签名时回滚对账会走"整集合重建"分支（那是签名变更的专用路径）
        service.indexAfterChapter(module(), storyDir, summaries, null);
        clearInvocations(vectorStore);

        service.rebuildIndex(module(), storyDir, summaries, null);

        String collection = "novel-memory-" + storyDir.getFileName();
        // 对账路径不删集合：读不到现状（空集合）等价于"全都得写"，无需先清空
        verify(vectorStore, never()).clear(anyString());
        ArgumentCaptor<List<VectorPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).upsert(eq(collection), captor.capture());
        List<VectorPoint> points = captor.getValue();
        assertEquals(3, points.stream().filter(p -> "chapter".equals(p.getPayload().get("kind")))
                .map(VectorPoint::getId).distinct().count(), "3 章各写一个章节点，且各只写一次");
        // 账本与设定在百章规模下占单次写入的绝大多数：逐章重嵌会让同一份账本被重写 N 次
        assertEquals(1, points.stream().filter(p -> "ledger".equals(p.getPayload().get("kind"))).count(),
                "账本点只能写一次");
        assertEquals(1, points.stream().filter(p -> "bible".equals(p.getPayload().get("kind"))).count(),
                "设定点只能写一次");
    }

    @Test
    void rebuildIndex_rewritesOnlyChangedPoints_andDeletesVanished(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试");
        when(chapterMemoryService.buildCharacterLedger(anyList()))
                .thenReturn(List.of(new LedgerEntry("角色丙", "报名丙班", 1, 3, null, null, false)));
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        List<ChapterSummaryEntity> summaries = threeChapters();
        service.indexAfterChapter(module(), storyDir, summaries, null);
        clearInvocations(vectorStore);

        // 第一遍：集合为空 → 捕获"应有"的完整点集，拿它当集合现状
        service.rebuildIndex(module(), storyDir, summaries, null);
        ArgumentCaptor<List<VectorPoint>> first = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(anyString(), first.capture());
        List<VectorPoint> desired = first.getValue();
        // 集合现状：第 1、2 章的点原样、第 3 章的点文本过时（模拟状态被回退）、外加一个不该存在的点
        List<StoredPoint> stored = new ArrayList<>();
        for (VectorPoint p : desired) {
            String chapterNo = p.getPayload().get("chapterNo");
            stored.add(StoredPoint.builder()
                    .id((String) p.getId())
                    .kind(p.getPayload().get("kind"))
                    .text("3".equals(chapterNo) ? "过时文本" : p.getPayload().get("text"))
                    .build());
        }
        stored.add(StoredPoint.builder()
                .id("11111111-2222-3333-4444-555555555555").kind("chapter").text("回滚掉的内容").build());
        when(vectorStore.listPoints(anyString())).thenReturn(stored);
        when(vectorStore.count(anyString())).thenReturn((long) desired.size());
        clearInvocations(vectorStore);

        service.rebuildIndex(module(), storyDir, summaries, null);

        ArgumentCaptor<List<VectorPoint>> second = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(anyString(), second.capture());
        List<VectorPoint> written = second.getValue();
        assertEquals(1, written.size(), "只有文本变了的那个点需要重嵌");
        assertEquals("3", written.get(0).getPayload().get("chapterNo"));
        ArgumentCaptor<List<String>> deleted = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).delete(anyString(), deleted.capture());
        assertEquals(List.of("11111111-2222-3333-4444-555555555555"), deleted.getValue(),
                "集合里多出来的点要删掉，否则会召回已回滚内容");
    }

    @Test
    void rebuildIndex_keepsVolumePoints_whichItDoesNotManage(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试");
        when(chapterMemoryService.buildCharacterLedger(anyList())).thenReturn(List.of());
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        List<ChapterSummaryEntity> summaries = threeChapters();
        service.indexAfterChapter(module(), storyDir, summaries, null);
        clearInvocations(vectorStore);

        // 同一集合里还有卷方向点：它是卷生成路径写的，回滚对账不该把它当"多余的点"删掉
        List<StoredPoint> stored = new ArrayList<>();
        stored.add(StoredPoint.builder().id("99999999-8888-7777-6666-555544443333")
                .kind("volume").text("第1卷《外门篇》：站稳外门").build());
        when(vectorStore.listPoints(anyString())).thenReturn(stored);
        when(vectorStore.count(anyString())).thenReturn((long) stored.size());

        service.rebuildIndex(module(), storyDir, summaries, null);

        verify(vectorStore, never()).delete(anyString(), anyList());
    }

    @Test
    void rebuildIndex_fallsBackToFullRewrite_whenStoredUnreadable(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试");
        when(chapterMemoryService.buildCharacterLedger(anyList())).thenReturn(List.of());
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        List<ChapterSummaryEntity> summaries = threeChapters();
        service.indexAfterChapter(module(), storyDir, summaries, null);
        clearInvocations(vectorStore);
        when(vectorStore.listPoints(anyString())).thenThrow(new IllegalStateException("Qdrant 抖动"));

        service.rebuildIndex(module(), storyDir, summaries, null);

        String collection = "novel-memory-" + storyDir.getFileName();
        verify(vectorStore).clear(collection);
        verify(vectorStore, times(1)).upsert(eq(collection), anyList());
    }

    private StoryVO.Module module() {
        StoryVO.Module module = new StoryVO.Module();
        module.setEmbeddingApi(new StoryVO.Module.EmbeddingApi());
        return module;
    }

    @Test
    void indexAfterChapter_writesChapterLedgerAndBiblePoints(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试\n世界观：架空");
        when(chapterMemoryService.buildCharacterLedger(anyList()))
                .thenReturn(List.of(new LedgerEntry("苏晚", "书店店主", 1, 1, null, null, false)));
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());

        ChapterSummaryEntity summary = ChapterSummaryEntity.builder()
                .chapterNo(1)
                .title("雨夜来客")
                .summary("本章摘要内容")
                .timePoint("雨夜，书店")
                .foreshadowSeeds(List.of(new ChapterSummaryEntity.SeedEntry("一只旧信封", "旧信封", 3)))
                .build();

        service.indexAfterChapter(module(), storyDir, List.of(summary), null);

        String collection = "novel-memory-" + storyDir.getFileName();
        ArgumentCaptor<List<VectorPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).ensureCollection(eq(collection), eq(8));
        verify(vectorStore).upsert(eq(collection), captor.capture());
        List<VectorPoint> points = captor.getValue();
        assertEquals(3, points.size());

        VectorPoint chapter = points.get(0);
        assertEquals("chapter", chapter.getPayload().get("kind"));
        assertEquals("1", chapter.getPayload().get("chapterNo"));
        assertTrue(chapter.getPayload().get("text").contains("伏笔埋设：一只旧信封"));
        assertEquals("ledger", points.get(1).getPayload().get("kind"));
        assertEquals("角色", points.get(1).getPayload().get("ledgerType"));
        assertEquals("bible", points.get(2).getPayload().get("kind"));
        assertTrue(points.get(2).getPayload().get("text").contains("小说名称：测试"));
    }

    @Test
    void indexAfterChapter_isIdempotentAcrossCheckpoints(@TempDir Path storyDir) throws Exception {
        when(chapterMemoryService.buildCharacterLedger(anyList())).thenReturn(List.of());
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder()
                .chapterNo(1).title("第一章").summary("摘要").build();

        service.indexAfterChapter(module(), storyDir, List.of(summary), null);
        service.indexAfterChapter(module(), storyDir, List.of(summary), null);

        ArgumentCaptor<List<VectorPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).upsert(anyString(), captor.capture());
        List<VectorPoint> first = captor.getAllValues().get(0);
        List<VectorPoint> second = captor.getAllValues().get(1);
        assertEquals(first.size(), second.size());
        assertEquals(first.get(0).getId(), second.get(0).getId());
    }

    @Test
    void retrieve_filtersByMinScoreMinChapterNoAndBudget() {
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().score(0.9).payload(Map.of("kind", "chapter", "chapterNo", "5", "text", "第5章记忆")).build(),
                ScoredVectorPoint.builder().score(0.8).payload(Map.of("kind", "ledger", "text", "角色：小唐。状态：店内")).build(),
                ScoredVectorPoint.builder().score(0.7).payload(Map.of("kind", "chapter", "chapterNo", "3", "text", "第3章记忆")).build(),
                ScoredVectorPoint.builder().score(0.6).payload(Map.of("kind", "chapter", "chapterNo", "4", "text", "第4章记忆")).build(),
                ScoredVectorPoint.builder().score(0.2).payload(Map.of("kind", "bible", "text", "故事设定")).build()));
        properties.setTopK(6);
        properties.setMinScore(0.35);
        properties.setMaxRecallChars(12);

        List<StoryMemoryService.RecallHit> hits = service.retrieve(module(), Paths.get("story"), "查询", 4, null);

        // 第5章被近章窗口去重（chapterNo>=4）、第4章同理；设定分 0.2 低于 minScore；
        // 账本命中(10字)保留，第3章命中使预算超 12 字被截断
        assertEquals(1, hits.size());
        assertEquals("角色：小唐。状态：店内", hits.get(0).text());
    }

    @Test
    void splitByBoundary_cutsAtSentenceBoundaryAndAlwaysTerminates() {
        // 回归 切块）：此前"一章一个点、整份 bible 一个点"，
        // 单点 2000~3000 字，唤醒预算绑不住、召回粒度也太粗。
        String sentences = "第一句话在这里。第二句话在这里！第三句话在这里？第四句话在这里；第五句话在这里。";
        List<String> pieces = StoryMemoryService.splitByBoundary(sentences, 12);

        assertTrue(pieces.size() > 1, "长文本必须被切开");
        for (String piece : pieces) {
            assertTrue(piece.length() <= 12, "每块不得超过上限（实际 " + piece.length() + "）");
        }
        assertEquals(sentences, String.join("", pieces), "切块不得丢字或改字");
        assertTrue(pieces.get(0).endsWith("。") || pieces.get(0).endsWith("！")
                        || pieces.get(0).endsWith("？") || pieces.get(0).endsWith("；"),
                "优先在句边界断开，而不是硬切在句中");
    }

    @Test
    void splitByBoundary_hardCutsWhenNoPunctuation() {
        // 没有标点的长文本必须靠硬切兜底——否则切块会退化成"一整块"，形同虚设
        String noPunctuation = "字".repeat(100);
        List<String> pieces = StoryMemoryService.splitByBoundary(noPunctuation, 30);

        assertEquals(4, pieces.size());
        for (String piece : pieces) {
            assertTrue(piece.length() <= 30);
        }
        assertEquals(noPunctuation, String.join("", pieces));
    }

    @Test
    void splitByBoundary_shortOrBlankTextStaysWhole() {
        assertEquals(List.of("短文本"), StoryMemoryService.splitByBoundary("短文本", 600));
        assertTrue(StoryMemoryService.splitByBoundary("   ", 600).isEmpty());
        assertTrue(StoryMemoryService.splitByBoundary(null, 600).isEmpty());
    }

    @Test
    void retrieve_truncatesOversizedFirstHitToBudget() {
        // 回归：旧实现"至少保留首条"让单条大命中（chapter/bible 记忆点实测 2000~3000 字）
        // 原样灌进前缀，600 的预算形同虚设——生产日志里「唤醒」块在 129~3166 字之间剧烈波动。
        // 现在首条也按预算截断，前缀长度才可预期、预算数值才谈得上可调。
        String oversized = "记".repeat(3000);
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().score(0.9)
                        .payload(Map.of("kind", "ledger", "text", oversized)).build(),
                ScoredVectorPoint.builder().score(0.85)
                        .payload(Map.of("kind", "ledger", "text", "角色：小唐。状态：店内")).build()));
        properties.setTopK(6);
        properties.setMinScore(0.35);
        properties.setMaxRecallChars(600);

        List<StoryMemoryService.RecallHit> hits = service.retrieve(module(), Paths.get("story"), "查询", null, null);

        assertEquals(1, hits.size(), "首条截断后预算即已用满，第二条不应再进入");
        assertTrue(hits.get(0).text().length() <= 600,
                "首条同样受预算约束（实际长度 " + hits.get(0).text().length() + "）");
        assertTrue(hits.get(0).text().startsWith("记"), "截断保留前段而非丢弃整条");
    }

    @Test
    void retrieve_embeddingFailureDegradesWithEmptyRecall() {
        when(embeddingGateway.embed(any(), anyList())).thenThrow(new RuntimeException("embedding 服务不可用"));

        // 不可重试的失败：降级为空召回继续生成 统一语义，原为终止作业），
        // 调用侧以 RECALL_DEGRADED 日志留痕，不静默
        List<StoryMemoryService.RecallHit> hits =
                service.retrieve(module(), Paths.get("story"), "查询", null, null);

        org.junit.jupiter.api.Assertions.assertTrue(hits.isEmpty());
        verify(embeddingGateway, times(1)).embed(any(), anyList());
    }

    @Test
    void retrieve_transientFailureIsRetriedThenSucceeds() {
        // 瞬时失败（限流/网络抖动）先有界重试：一次抖动不该杀死整批挂机
        when(embeddingGateway.embed(any(), anyList()))
                .thenThrow(new TransientAiException("429 too many requests"))
                .thenReturn(List.of(new float[8]));
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().score(0.9)
                        .payload(Map.of("kind", "ledger", "text", "角色：小唐。状态：店内")).build()));

        List<StoryMemoryService.RecallHit> hits = service.retrieve(module(), Paths.get("story"), "查询", null, null);

        assertEquals(1, hits.size());
        assertEquals("角色：小唐。状态：店内", hits.get(0).text());
        verify(embeddingGateway, times(2)).embed(any(), anyList());
    }

    @Test
    void retrieve_exhaustsRetriesThenDegradesWithEmptyRecall() {
        when(embeddingGateway.embed(any(), anyList()))
                .thenThrow(new ResourceAccessException("connection reset"));

        List<StoryMemoryService.RecallHit> hits =
                service.retrieve(module(), Paths.get("story"), "查询", null, null);

        org.junit.jupiter.api.Assertions.assertTrue(hits.isEmpty(), "重试耗尽后降级为空召回");
        verify(embeddingGateway, times(3)).embed(any(), anyList());
    }

    @Test
    void retrieve_deterministicFailureIsNotRetried() {
        // 欠费/鉴权/未配置属确定性失败：重试不会变好，立即降级（不浪费调用），主链路自会暴露
        when(embeddingGateway.embed(any(), anyList()))
                .thenThrow(new IllegalStateException("未配置 embedding-api（yml story.module.embedding-api），无法向量化"));

        List<StoryMemoryService.RecallHit> hits =
                service.retrieve(module(), Paths.get("story"), "查询", null, null);

        org.junit.jupiter.api.Assertions.assertTrue(hits.isEmpty());
        verify(embeddingGateway, times(1)).embed(any(), anyList());
    }

    @Test
    void retrieve_maxAttemptsOneDisablesRetry() {
        properties.setRetrieveMaxAttempts(1);
        when(embeddingGateway.embed(any(), anyList()))
                .thenThrow(new TransientAiException("503 service unavailable"));

        List<StoryMemoryService.RecallHit> hits =
                service.retrieve(module(), Paths.get("story"), "查询", null, null);

        org.junit.jupiter.api.Assertions.assertTrue(hits.isEmpty());
        verify(embeddingGateway, times(1)).embed(any(), anyList());
    }

    @Test
    void retrieve_vectorStoreTransientFailureIsRetried() {
        // Qdrant 侧失败经 .get() 抛出，异常链上带 ExecutionException → 同样按瞬时失败重试
        when(vectorStore.search(anyString(), any(), anyInt()))
                .thenThrow(new IllegalStateException("向量检索失败: UNAVAILABLE",
                        new ExecutionException(new RuntimeException("UNAVAILABLE"))))
                .thenReturn(List.of(ScoredVectorPoint.builder().score(0.9)
                        .payload(Map.of("kind", "ledger", "text", "角色：小唐。状态：店内")).build()));

        List<StoryMemoryService.RecallHit> hits = service.retrieve(module(), Paths.get("story"), "查询", null, null);

        assertEquals(1, hits.size());
        verify(vectorStore, times(2)).search(anyString(), any(), anyInt());
    }

    @Test
    void retrieve_capsSingleHitToOneChunkSoLaterCandidatesSurvive() {
        // 非切块点（卷方向）可达数千字：单条必须按一个切块封顶，否则一条就能吃掉整个唤醒预算，
        // 排名靠后的候选全部进不来
        properties.setMaxRecallChars(2000);
        String oversized = "卷".repeat(3000);
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().score(0.95)
                        .payload(Map.of("kind", "volume", "text", oversized)).build(),
                ScoredVectorPoint.builder().score(0.8)
                        .payload(Map.of("kind", "ledger", "text", "角色：小唐。状态：店内")).build(),
                ScoredVectorPoint.builder().score(0.7)
                        .payload(Map.of("kind", "chapter", "chapterNo", "3", "text", "第三章记忆块")).build()));

        List<StoryMemoryService.RecallHit> hits = service.retrieve(module(), Paths.get("story"), "查询", null, null);

        assertEquals(3, hits.size(), "单条封顶后，后续候选仍应进得来");
        assertTrue(hits.get(0).text().length() <= 600,
                "单条不得超过一个切块（实际 " + hits.get(0).text().length() + "）");
        assertTrue(hits.get(0).text().contains("已截断"), "超长单条应带截断标记");
        assertEquals("角色：小唐。状态：店内", hits.get(1).text());
    }

    @Test
    void retrieve_skipsUnfitCandidateInsteadOfCuttingOffTheRest() {
        // 装不下某条时只跳过该条（贪心填满预算），不得 break——
        // 否则一条中等命中会把它后面的小候选全部丢掉（尾部丢失）
        properties.setMaxRecallChars(1000);
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().score(0.9)
                        .payload(Map.of("kind", "chapter", "chapterNo", "3", "text", "甲".repeat(600))).build(),
                ScoredVectorPoint.builder().score(0.8)
                        .payload(Map.of("kind", "chapter", "chapterNo", "4", "text", "乙".repeat(600))).build(),
                ScoredVectorPoint.builder().score(0.7)
                        .payload(Map.of("kind", "ledger", "text", "角色：小唐。状态：店内")).build()));

        List<StoryMemoryService.RecallHit> hits = service.retrieve(module(), Paths.get("story"), "查询", null, null);

        assertEquals(2, hits.size(), "第二条装不下应被跳过，第三条小命中仍应入选");
        assertEquals(600, hits.get(0).text().length());
        assertEquals("角色：小唐。状态：店内", hits.get(1).text());
    }

    @Test
    void retrieve_singleHitNeverExceedsTinyBudget() {
        // 极端小预算（比截断标记还短）：单条长度上界仍必须成立，否则"预算可绑定"就是空话
        properties.setMaxRecallChars(12);
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().score(0.9)
                        .payload(Map.of("kind", "ledger", "text", "甲".repeat(40))).build()));

        List<StoryMemoryService.RecallHit> hits = service.retrieve(module(), Paths.get("story"), "查询", null, null);

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).text().length() <= 12,
                "单条不得超预算（实际 " + hits.get(0).text().length() + "）");
    }

    @Test
    void retrieve_returnsEmptyWhenDisabledOrMissingModule() {
        properties.setEnabled(false);
        assertTrue(service.retrieve(module(), Paths.get("story"), "查询", null, null).isEmpty());

        properties.setEnabled(true);
        assertTrue(service.retrieve(null, Paths.get("story"), "查询", null, null).isEmpty());
    }

    @Test
    void indexAfterChapter_skipsWhenDisabled() {
        properties.setEnabled(false);

        service.indexAfterChapter(module(), Paths.get("story"), List.of(ChapterSummaryEntity.builder().chapterNo(1).build()), null);

        verify(vectorStore, times(0)).upsert(anyString(), anyList());
    }

    @Test
    void indexAfterChapter_withValidWorldId_writesBibleToWorldCollection(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试\n世界观：架空");
        when(chapterMemoryService.buildCharacterLedger(anyList())).thenReturn(List.of());
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder()
                .chapterNo(1).title("第一章").summary("摘要").build();

        service.indexAfterChapter(module(), storyDir, List.of(summary), "urban-01");

        String worldCollection = "novel-world-urban-01";
        verify(vectorStore).ensureCollection(eq(worldCollection), eq(8));
        ArgumentCaptor<List<VectorPoint>> worldCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(eq(worldCollection), worldCaptor.capture());
        List<VectorPoint> worldPoints = worldCaptor.getValue();
        assertEquals(1, worldPoints.size());
        assertEquals("bible", worldPoints.get(0).getPayload().get("kind"));
        assertTrue(worldPoints.get(0).getPayload().get("text").contains("小说名称：测试"));
    }

    @Test
    void retrieve_withWorldId_mergesAndDedupsAndSharesBudget() {
        String storyCollection = "novel-memory-story";
        String worldCollection = "novel-world-urban-01";
        String sharedBibleText = "共享世界观设定文本";

        when(vectorStore.search(eq(storyCollection), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().score(0.9)
                        .payload(Map.of("kind", "bible", "text", sharedBibleText, "storyId", "story")).build(),
                ScoredVectorPoint.builder().score(0.7)
                        .payload(Map.of("kind", "chapter", "chapterNo", "2", "text", "第2章记忆内容")).build()));
        when(vectorStore.search(eq(worldCollection), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().score(0.85)
                        .payload(Map.of("kind", "bible", "text", sharedBibleText, "storyId", "story")).build(),
                ScoredVectorPoint.builder().score(0.6)
                        .payload(Map.of("kind", "bible", "text", "世界集合独有设定")).build()));
        properties.setTopK(6);
        properties.setMinScore(0.35);
        properties.setMaxRecallChars(1000);

        List<StoryMemoryService.RecallHit> hits = service.retrieve(
                module(), Paths.get("story"), "查询", null, "urban-01");

        assertEquals(3, hits.size());
        long bibleCount = hits.stream().filter(h -> h.text().equals(sharedBibleText)).count();
        assertEquals(1, bibleCount, "相同 bible 文本应去重只留一条");
        assertEquals(0.9, hits.get(0).score(), 0.001);
    }

    @Test
    void indexAfterChapter_withInvalidWorldId_skipsWorldCollection(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试");
        when(chapterMemoryService.buildCharacterLedger(anyList())).thenReturn(List.of());
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        ChapterSummaryEntity summary = ChapterSummaryEntity.builder()
                .chapterNo(1).title("第一章").summary("摘要").build();

        service.indexAfterChapter(module(), storyDir, List.of(summary), "世界1");

        verify(vectorStore, times(1)).ensureCollection(anyString(), anyInt());
        verify(vectorStore, times(1)).upsert(anyString(), anyList());
    }

    @Test
    void indexVolume_writesVolumePointWithKindAndStableId(@TempDir Path storyDir) throws Exception {
        VolumeBlueprintEntity volume = VolumeBlueprintEntity.builder()
                .volumeNo(1).title("外门篇").themeShift("站稳外门")
                .beats(List.of("承甲", "转乙"))
                .volumeExitConditions(List.of("外门公开敌对"))
                .seeds(List.of("古物"))
                .build();

        service.indexVolume(module(), storyDir, volume);

        String collection = "novel-memory-" + storyDir.getFileName();
        ArgumentCaptor<List<VectorPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(eq(collection), captor.capture());
        List<VectorPoint> points = captor.getValue();
        assertEquals(1, points.size());
        VectorPoint point = points.get(0);
        assertEquals("volume", point.getPayload().get("kind"));
        assertEquals("1", point.getPayload().get("volumeNo"));
        assertTrue(point.getPayload().get("text").contains("第1卷《外门篇》"));
        assertTrue(point.getPayload().get("text").contains("承转合：承甲；转乙"));
        assertTrue(point.getPayload().get("text").contains("卷级伏笔：古物"));
        assertTrue(point.getId() instanceof String);
    }

    @Test
    void indexVolume_skipsWhenDisabledOrNull() throws Exception {
        properties.setEnabled(false);
        service.indexVolume(module(), storyDir(), VolumeBlueprintEntity.builder().volumeNo(1).build());
        verify(vectorStore, times(0)).upsert(anyString(), anyList());

        properties.setEnabled(true);
        service.indexVolume(module(), storyDir(), null);
        verify(vectorStore, times(0)).upsert(anyString(), anyList());
    }

    @Test
    void retrieveDirections_filtersByVolumeKind() {
        when(vectorStore.search(anyString(), any(), anyInt())).thenReturn(List.of(
                ScoredVectorPoint.builder().score(0.9)
                        .payload(Map.of("kind", "volume", "text", "第一卷方向")).build(),
                ScoredVectorPoint.builder().score(0.8)
                        .payload(Map.of("kind", "chapter", "text", "章节记忆")).build()));
        properties.setTopK(6);
        properties.setMinScore(0.35);
        properties.setMaxRecallChars(1000);

        List<StoryMemoryService.RecallHit> hits = service.retrieveDirections(
                module(), storyDir(), "当前弧意图", null);

        // 只召回 kind=VOLUME，chapter 类被过滤
        assertEquals(1, hits.size());
        assertEquals("第一卷方向", hits.get(0).text());
    }

    @Test
    void retrieve_truncatesOverlongQueryBeforeEmbed() {
        String overlong = "超".repeat(5000);

        service.retrieve(module(), storyDir(), overlong, null, null);

        // 超长查询在入口统一截断（1200 字符含省略号），否则供应商按参数错误拒绝（智谱 1210），
        // 该路检索会静默失效（唤醒恒为 0）
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingGateway).embed(any(), captor.capture());
        String sent = captor.getValue().get(0);
        assertTrue(sent.length() <= 1200);
        assertTrue(sent.startsWith("超"));
    }

    @Test
    void buildPlanQuery_capsStoryContextAndTasksContributions() {
        String hugeContext = "背景".repeat(3000); // 6000 字，超出供应商单条输入上限的量级
        StageBlueprintEntity blueprint = StageBlueprintEntity.builder()
                .stageGoal("阶段目标A")
                .tasks(java.util.Arrays.asList("任务".repeat(500), "任务B"))
                .build();

        String query = service.buildPlanQuery(hugeContext, blueprint);

        // 整条查询落在安全上限内；任务拼接段保留头部、截断尾部（abbreviate 语义），
        // 阶段目标字段完整保留不被故事背景吃掉预算
        assertTrue(query.length() <= 1200);
        assertTrue(query.contains("阶段目标A"));
        assertTrue(query.contains("阶段任务："));
        assertTrue(query.contains("任务任务"));
        assertTrue(!query.contains("任务B"));
    }

    private Path storyDir() {
        return Paths.get("story");
    }



    // ==================== 移植自旧系统的闸门用例 ====================

    @Test
    void indexChapter_repairsFullyAfterFailedWrite(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试");
        when(chapterMemoryService.buildCharacterLedger(anyList())).thenReturn(List.of(
                new LedgerEntry("主角甲", "报名三班", 1, 1, null, null, false),
                new LedgerEntry("角色丁", "报名一班", 2, 2, null, null, false)));
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        List<ChapterSummaryEntity> summaries = threeChapters();
        // 第一次写入失败 → 索引被标记为脏；下一次写入必须退化为全量，把缺失的块补回来
        doThrow(new IllegalStateException("写入失败")).when(vectorStore).upsert(anyString(), anyList());
        service.indexChapter(module(), storyDir, summaries, 1, null);
        clearInvocations(vectorStore);

        service.indexChapter(module(), storyDir, summaries, 2, null);

        ArgumentCaptor<List<VectorPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).upsert(anyString(), captor.capture());
        List<VectorPoint> points = captor.getValue();
        assertEquals(2, points.stream().filter(p -> "ledger".equals(p.getPayload().get("kind"))).count(),
                "脏标记后的写入要带上全量账本，不能只写本章条目");
        assertEquals(1, points.stream().filter(p -> "bible".equals(p.getPayload().get("kind"))).count());
    }

    @Test
    void indexChapter_writesOnlyTouchedLedgerEntries_andBibleOnlyWhenChanged(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试");
        when(chapterMemoryService.buildCharacterLedger(anyList())).thenReturn(List.of(
                new LedgerEntry("主角甲", "报名三班", 1, 1, null, null, false),
                new LedgerEntry("角色丁", "报名一班", 2, 2, null, null, false)));
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        List<ChapterSummaryEntity> summaries = threeChapters();
        service.indexChapter(module(), storyDir, summaries, 1, null);
        clearInvocations(vectorStore);

        service.indexChapter(module(), storyDir, summaries, 2, null);

        ArgumentCaptor<List<VectorPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).upsert(anyString(), captor.capture());
        List<VectorPoint> points = captor.getValue();
        List<String> ledgerNames = points.stream().filter(p -> "ledger".equals(p.getPayload().get("kind")))
                .map(p -> p.getPayload().get("name")).toList();
        assertEquals(List.of("角色丁"), ledgerNames,
                "只写本章提及过的账本条目：没提及的条目其已存点仍是最新状态，无需重写");
        assertEquals(1, points.stream().filter(p -> "chapter".equals(p.getPayload().get("kind"))).count());
        assertEquals(0, points.stream().filter(p -> "bible".equals(p.getPayload().get("kind"))).count(),
                "bible 未变更则不再逐章重嵌设定点");
    }

    /**
     * 核对口径必须只数**受管的点**（2026-10-14）。
     *
     * <p>背景：实测 145 章回滚时日志报「应有受管点 453，实际 454」——差值恒为 1 个卷方向点。
     * 成因是对账用**集合总数**与"应有受管点"比对，而删除时刻意跳过卷点（那是卷生成路径维护的），
     * 两个口径必然差一个卷点数。必现的误报会训练人忽略这条 WARN。
     *
     * <p>本用例守住关键行为：**集合里有卷点不触发任何额外写删**——
     * 修正后对账不再因卷点存在而误判（旧实现在此场景会报点数不符）。
     */
    @Test
    void rebuildIndex_countIgnoresUnmanagedVolumePoints(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试");
        when(chapterMemoryService.buildCharacterLedger(anyList())).thenReturn(List.of());
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        List<ChapterSummaryEntity> summaries = threeChapters();
        // 先让集合内容与期望一致（空集合 → 全写）
        service.rebuildIndex(module(), storyDir, summaries, null);
        ArgumentCaptor<List<VectorPoint>> first = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(anyString(), first.capture());
        List<VectorPoint> desired = first.getValue();
        clearInvocations(vectorStore);

        // 集合现状 = 期望点集（文本一致）+ 一个卷方向点；总数比受管点数多 1
        List<StoredPoint> stored = new ArrayList<>();
        for (VectorPoint p : desired) {
            stored.add(StoredPoint.builder()
                    .id((String) p.getId())
                    .kind(p.getPayload().get("kind"))
                    .text(p.getPayload().get("text"))
                    .build());
        }
        stored.add(StoredPoint.builder().id("99999999-8888-7777-6666-555544443333")
                .kind("volume").text("第1卷《外门篇》：站稳外门").build());
        when(vectorStore.listPoints(anyString())).thenReturn(stored);
        when(vectorStore.count(anyString())).thenReturn((long) stored.size());

        service.rebuildIndex(module(), storyDir, summaries, null);

        // 卷点不该被删；受管点文本全一致 ⇒ 一个都不该重写
        verify(vectorStore, never()).delete(anyString(), anyList());
        verify(vectorStore, never()).upsert(anyString(), anyList());
    }

    /**
     * 回归：**改写已存在点不得被算成"新增"**（2026-10-19）。
     *
     * <p>实测 165 章回滚：日志报「应有受管点 518，实际 524」并给出"可能有漏写/漏删"的 WARN，
     * 而集合里受管点实测恰好 518（多出的 6 正是本次改写数）。旧式
     * {@code managedStored - toDelete + toWrite} 把"文本变了要重写"当成"新增了一个点"，
     * 每次回滚都虚增「改写条数」个点——必现误报会训练人忽略这条 WARN。
     *
     * <p>本用例只断言行为：改写场景下集合点数不变，因此不应产生任何"点数不符"的告警。
     * 通过 logback 捕获日志验证（不依赖返回值）。
     */
    @Test
    void rebuildIndex_rewriteOnly_doesNotInflateManagedCount(@TempDir Path storyDir) throws Exception {
        Files.writeString(storyDir.resolve("story-bible.txt"), "小说名称：测试");
        when(chapterMemoryService.buildCharacterLedger(anyList())).thenReturn(List.of());
        when(chapterMemoryService.buildLedger(anyList(), any())).thenReturn(List.of());
        List<ChapterSummaryEntity> summaries = threeChapters();
        service.indexChapter(module(), storyDir, summaries, 1, null);
        clearInvocations(vectorStore);

        // 先拿"应有"点集
        service.rebuildIndex(module(), storyDir, summaries, null);
        ArgumentCaptor<List<VectorPoint>> first = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(anyString(), first.capture());
        List<VectorPoint> desired = first.getValue();
        clearInvocations(vectorStore);

        // 集合现状：点数与期望完全相同，但**其中一个点的文本过时**（回滚导致的状态回退）
        List<StoredPoint> stored = new ArrayList<>();
        boolean marked = false;
        for (VectorPoint p : desired) {
            boolean stale = !marked;
            if (stale) {
                marked = true;
            }
            stored.add(StoredPoint.builder()
                    .id((String) p.getId())
                    .kind(p.getPayload().get("kind"))
                    .text(stale ? "过时文本" : p.getPayload().get("text"))
                    .build());
        }
        when(vectorStore.listPoints(anyString())).thenReturn(stored);
        // 集合总数 = 受管点数（无卷点）：改写不改变总数
        when(vectorStore.count(anyString())).thenReturn((long) stored.size());

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(StoryMemoryService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.rebuildIndex(module(), storyDir, summaries, null);
        } finally {
            logger.detachAppender(appender);
        }

        // 只改写了 1 个点，点数不变 ⇒ 不得出现"受管点数不符"告警
        boolean mismatchWarned = appender.list.stream()
                .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("受管点数不符"));
        assertTrue(!mismatchWarned,
                "改写已存在点不得被算成新增（否则每次回滚都误报点数不符）："
                        + appender.list.stream().map(e -> e.getFormattedMessage()).toList());
    }
}
