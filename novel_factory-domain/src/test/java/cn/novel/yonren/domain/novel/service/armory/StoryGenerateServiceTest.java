package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ConsistencyIndexService;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 续写预载测试：目录解析硬失败、记忆/偏移/上章结尾/偏差警示填充、首发不预载
 */
class StoryGenerateServiceTest {

    @Mock
    private DefaultArmoryFactory armoryFactory;

    @Mock
    private IStoryRepository storyRepository;

    @Mock
    private ChapterMemoryService chapterMemoryService;

    /**
     * 新增：此前该依赖未被 mock，测试里恒为 null，于是
     * "yml 兜底值参与续写上限比较"这个缺陷在单测里始终不可见。
     * Mockito 的默认返回值是 null，其他用例的语义不受影响。
     */
    @Mock
    private StoryProperties storyProperties;

    @InjectMocks
    private StoryGenerateService storyGenerateService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void generate_preloadsResumeContextIntoDynamicContext() throws Exception {
        ArmoryCommandEntity command = ArmoryCommandEntity.builder().resumeStoryDir("20260831-story-0001").build();
        Path storyDir = Paths.get("docs/workspace/stories/20260831-story-0001");
        List<ChapterSummaryEntity> history = List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build(),
                ChapterSummaryEntity.builder().chapterNo(2).continuityConflicts(List.of("时间线冲突")).build());
        StyleStatEntity styleStat = StyleStatEntity.builder().build();

        when(storyRepository.resolveStoryDirectory("20260831-story-0001")).thenReturn(storyDir);
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(history);
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 2));
        when(storyRepository.readStyleStat(storyDir)).thenReturn(styleStat);
        when(storyRepository.readLatestChapterContent(storyDir)).thenReturn("第2章结尾全文");
        when(chapterMemoryService.tailByParagraph("第2章结尾全文", ChapterMemoryService.DEFAULT_PREV_TAIL_LENGTH))
                .thenReturn("结尾片段");
        stubStrategyReturn();

        storyGenerateService.generate(command);

        ArgumentCaptor<DefaultArmoryFactory.DynamicContext> captor =
                ArgumentCaptor.forClass(DefaultArmoryFactory.DynamicContext.class);
        verify(armoryFactory.armoryStrategyHandler()).apply(eq(command), captor.capture());

        DefaultArmoryFactory.DynamicContext ctx = captor.getValue();
        assertEquals(storyDir, ctx.getStoryDir());
        assertEquals(history, ctx.getChapterSummaries());
        assertEquals(styleStat, ctx.getStyleStat());
        assertEquals(2, ctx.getChapterOffset());
        assertEquals("结尾片段", ctx.getPrevChapterTail());
        assertEquals(List.of("时间线冲突"), ctx.getPendingConflicts());
    }

    @Test
    void generate_hardFailsWhenResumeDirMissing() throws Exception {
        ArmoryCommandEntity command = ArmoryCommandEntity.builder().resumeStoryDir("not-exist-dir").build();
        when(storyRepository.resolveStoryDirectory("not-exist-dir"))
                .thenThrow(new AppException("续写失败：故事目录不存在"));

        assertThrows(AppException.class, () -> storyGenerateService.generate(command));

        verify(storyRepository, never()).readChapterSummaries(any());
        verify(armoryFactory, never()).armoryStrategyHandler();
    }

    @Test
    void generate_hardFailsWhenChapterAndSummaryMaxMismatch() throws Exception {
        ArmoryCommandEntity command = ArmoryCommandEntity.builder().resumeStoryDir("20260831-story-0002").build();
        Path storyDir = Paths.get("docs/workspace/stories/20260831-story-0002");
        when(storyRepository.resolveStoryDirectory("20260831-story-0002")).thenReturn(storyDir);
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build(),
                ChapterSummaryEntity.builder().chapterNo(2).build()));
        // 正文只有 1 章，与摘要最大值 2 不一致 → 锁步硬失败
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1));

        AppException e = assertThrows(AppException.class, () -> storyGenerateService.generate(command));

        assertTrue(e.getInfo().contains("不一致"));
        verify(armoryFactory, never()).armoryStrategyHandler();
    }

    @Test
    void validateResumeLockStep_consistent_passes() {
        List<ChapterSummaryEntity> history = List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build(),
                ChapterSummaryEntity.builder().chapterNo(2).build());

        assertDoesNotThrow(() -> StoryGenerateService.validateResumeLockStep("dir", history, List.of(1, 2)));
        // 空故事（无正文无摘要）也合法
        assertDoesNotThrow(() -> StoryGenerateService.validateResumeLockStep("dir", List.of(), List.of()));
    }

    @Test
    void validateResumeLockStep_gapOrDuplicate_hardFails() {
        List<ChapterSummaryEntity> history = List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build(),
                ChapterSummaryEntity.builder().chapterNo(2).build(),
                ChapterSummaryEntity.builder().chapterNo(3).build());

        // 正文缺第 2 章
        AppException e = assertThrows(AppException.class,
                () -> StoryGenerateService.validateResumeLockStep("dir", history, List.of(1, 3)));
        assertTrue(e.getInfo().contains("正文"));
        // 摘要缺第 2 章
        List<ChapterSummaryEntity> gappedHistory = List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build(),
                ChapterSummaryEntity.builder().chapterNo(3).build());
        e = assertThrows(AppException.class,
                () -> StoryGenerateService.validateResumeLockStep("dir", gappedHistory, List.of(1, 2, 3)));
        assertTrue(e.getInfo().contains("摘要"));
        // 编号重复（等价缺号）
        e = assertThrows(AppException.class,
                () -> StoryGenerateService.validateResumeLockStep("dir", history, List.of(1, 1, 3)));
        assertTrue(e.getInfo().contains("正文"));
    }

    @Test
    void generate_skipsPreloadOnFirstRun() throws Exception {
        ArmoryCommandEntity command = ArmoryCommandEntity.builder().build();
        stubStrategyReturn();

        storyGenerateService.generate(command);

        ArgumentCaptor<DefaultArmoryFactory.DynamicContext> captor =
                ArgumentCaptor.forClass(DefaultArmoryFactory.DynamicContext.class);
        verify(armoryFactory.armoryStrategyHandler()).apply(eq(command), captor.capture());

        DefaultArmoryFactory.DynamicContext ctx = captor.getValue();
        assertNull(ctx.getStoryDir());
        assertNull(ctx.getChapterSummaries());
        assertEquals(0, ctx.getChapterOffset());
        assertNull(ctx.getPrevChapterTail());
        assertNull(ctx.getPendingConflicts());
        verify(storyRepository, never()).readChapterSummaries(any());
        verify(storyRepository, never()).resolveStoryDirectory(anyString());
    }

    @Test
    void generate_resumeRefusesWhenPersistedCapReached() throws Exception {
        // 固化上限 2、已生成 2 章 → 无论请求给多高的 max 都硬拒绝（sticky cap 不可抬高）
        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .resumeStoryDir("20260831-story-0001").maxChapterCount(100).build();
        Path storyDir = Paths.get("docs/workspace/stories/20260831-story-0001");
        List<ChapterSummaryEntity> history = List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build(),
                ChapterSummaryEntity.builder().chapterNo(2).build());

        when(storyRepository.resolveStoryDirectory("20260831-story-0001")).thenReturn(storyDir);
        when(storyRepository.readStoryMeta(storyDir)).thenReturn(new IStoryRepository.StoryMeta(2));
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(history);
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 2));
        when(storyRepository.readStyleStat(storyDir)).thenReturn(StyleStatEntity.builder().build());
        when(storyRepository.readLatestChapterContent(storyDir)).thenReturn("结尾");
        when(chapterMemoryService.tailByParagraph(anyString(), eq(ChapterMemoryService.DEFAULT_PREV_TAIL_LENGTH)))
                .thenReturn("结尾片段");

        AppException e = assertThrows(AppException.class, () -> storyGenerateService.generate(command));

        assertTrue(e.getInfo().contains("上限"));
        assertTrue(e.getInfo().contains("已完结"));
        verify(storyRepository, never()).writeStoryMeta(any(), any());
        verify(armoryFactory, never()).armoryStrategyHandler();
    }

    @Test
    void generate_resumeTruncatesToPersistedCapAndExposesIt() throws Exception {
        // 固化上限 5、已生成 2 章、本批请求 5 章 → 截断为 3 章，maxChapterCount 暴露为 5
        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .resumeStoryDir("20260831-story-0001")
                .storyContextEntity(StoryContextEntity.builder().chapterCount(5).build())
                .build();
        Path storyDir = Paths.get("docs/workspace/stories/20260831-story-0001");
        List<ChapterSummaryEntity> history = List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build(),
                ChapterSummaryEntity.builder().chapterNo(2).build());

        when(storyRepository.resolveStoryDirectory("20260831-story-0001")).thenReturn(storyDir);
        when(storyRepository.readStoryMeta(storyDir)).thenReturn(new IStoryRepository.StoryMeta(5));
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(history);
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 2));
        when(storyRepository.readStyleStat(storyDir)).thenReturn(StyleStatEntity.builder().build());
        when(storyRepository.readLatestChapterContent(storyDir)).thenReturn("结尾");
        when(chapterMemoryService.tailByParagraph(anyString(), eq(ChapterMemoryService.DEFAULT_PREV_TAIL_LENGTH)))
                .thenReturn("结尾片段");
        stubStrategyReturn();

        storyGenerateService.generate(command);

        ArgumentCaptor<DefaultArmoryFactory.DynamicContext> captor =
                ArgumentCaptor.forClass(DefaultArmoryFactory.DynamicContext.class);
        verify(armoryFactory.armoryStrategyHandler()).apply(eq(command), captor.capture());
        DefaultArmoryFactory.DynamicContext ctx = captor.getValue();
        assertEquals(5, ctx.getMaxChapterCount());
        assertEquals(3, command.getStoryContextEntity().getChapterCount());
    }

    /**
     * 回归：续写请求**不带** maxChapterCount 时，不得回落到 yml 的全局默认上限。
     * 夹具中固化上限高于 yml 默认上限；默认兜底值不得被当成显式请求上限，
     * 否则会提前判完结并破坏固化值不可下调的语义。
     */
    @Test
    void generate_resumeWithoutRequestCap_ignoresYmlDefault() throws Exception {
        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .resumeStoryDir("20260927-story-0001")
                .storyContextEntity(StoryContextEntity.builder().chapterCount(5).build())
                .build();
        Path storyDir = Paths.get("docs/workspace/stories/20260927-story-0001");

        StoryVO.Constraints constraints = new StoryVO.Constraints();
        constraints.setEnforceChapterLimit(true);
        constraints.setMaxChapterCount(188);
        when(storyProperties.getConstraints()).thenReturn(constraints);
        stubResumePreload(storyDir, 320);
        stubStrategyReturn();

        storyGenerateService.generate(command);

        ArgumentCaptor<DefaultArmoryFactory.DynamicContext> captor =
                ArgumentCaptor.forClass(DefaultArmoryFactory.DynamicContext.class);
        verify(armoryFactory.armoryStrategyHandler()).apply(eq(command), captor.capture());
        assertEquals(320, captor.getValue().getMaxChapterCount(),
                "续写未显式给上限时必须以上固化值为准——yml 默认值无权下调它");
        assertEquals(5, command.getStoryContextEntity().getChapterCount(),
                "320 上限下本批 5 章不应被截断");
    }

    /** 显式请求仍保留下调能力（min 语义保留），改动只排除了 yml 默认值的冒充 */
    @Test
    void generate_resumeExplicitLowerCap_stillApplies() throws Exception {
        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .resumeStoryDir("20260927-story-0001").maxChapterCount(100)
                .storyContextEntity(StoryContextEntity.builder().chapterCount(5).build())
                .build();
        Path storyDir = Paths.get("docs/workspace/stories/20260927-story-0001");
        stubResumePreload(storyDir, 320);
        stubStrategyReturn();

        storyGenerateService.generate(command);

        ArgumentCaptor<DefaultArmoryFactory.DynamicContext> captor =
                ArgumentCaptor.forClass(DefaultArmoryFactory.DynamicContext.class);
        verify(armoryFactory.armoryStrategyHandler()).apply(eq(command), captor.capture());
        assertEquals(100, captor.getValue().getMaxChapterCount(),
                "请求里显式给出的 100 < 固化 320，按下调后的 100 执行");
    }

    /** 续写预载的公共打桩：目录解析、固化上限、历史摘要、章节号、文风统计、上章结尾 */
    private void stubResumePreload(Path storyDir, int persistedCap) throws Exception {
        List<ChapterSummaryEntity> history = List.of(
                ChapterSummaryEntity.builder().chapterNo(1).build(),
                ChapterSummaryEntity.builder().chapterNo(2).build());
        when(storyRepository.resolveStoryDirectory(storyDir.getFileName().toString())).thenReturn(storyDir);
        when(storyRepository.readStoryMeta(storyDir)).thenReturn(new IStoryRepository.StoryMeta(persistedCap));
        when(storyRepository.readChapterSummaries(storyDir)).thenReturn(history);
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 2));
        when(storyRepository.readStyleStat(storyDir)).thenReturn(StyleStatEntity.builder().build());
        when(storyRepository.readLatestChapterContent(storyDir)).thenReturn("结尾");
        when(chapterMemoryService.tailByParagraph(anyString(), eq(ChapterMemoryService.DEFAULT_PREV_TAIL_LENGTH)))
                .thenReturn("结尾片段");
    }

    @Test
    void generate_firstRunAppliesRequestedCapInMemory() throws Exception {
        // 首发无目录：完结上限仅内存生效（由 PersistChapterPlanNode 落盘），本批不截断
        ArmoryCommandEntity command = ArmoryCommandEntity.builder().maxChapterCount(10).build();
        stubStrategyReturn();

        storyGenerateService.generate(command);

        ArgumentCaptor<DefaultArmoryFactory.DynamicContext> captor =
                ArgumentCaptor.forClass(DefaultArmoryFactory.DynamicContext.class);
        verify(armoryFactory.armoryStrategyHandler()).apply(eq(command), captor.capture());
        assertEquals(10, captor.getValue().getMaxChapterCount());
        verify(storyRepository, never()).readStoryMeta(any());
        verify(storyRepository, never()).writeStoryMeta(any(), any());
    }

    // ---- 审批续跑入口守卫（R4）：树腰入口的前提是挂起现场原样保留，残缺时快速失败 ----

    @Test
    void resumeAfterPlanApproval_failsFastWhenContextMissingPlan() {
        DefaultArmoryFactory.DynamicContext ctx = new DefaultArmoryFactory.DynamicContext();

        AppException e = assertThrows(AppException.class, () -> storyGenerateService.resumeAfterPlanApproval(
                new ArmoryCommandEntity(), new GenerationJob("job-1"), ctx));

        assertTrue(e.getInfo().contains("挂起现场已失效"), e.getInfo());
    }

    @Test
    void resumeAfterPlanApproval_failsFastWhenJobMissing() {
        DefaultArmoryFactory.DynamicContext ctx = new DefaultArmoryFactory.DynamicContext();
        ctx.setChapterPlanAggregate(ChapterPlanAggregate.builder().build());

        AppException e = assertThrows(AppException.class, () -> storyGenerateService.resumeAfterPlanApproval(
                new ArmoryCommandEntity(), null, ctx));

        assertTrue(e.getInfo().contains("异步作业路径"), e.getInfo());
    }

    @SuppressWarnings("unchecked")
    private void stubStrategyReturn() throws Exception {
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> handler =
                mock(StrategyHandler.class);
        when(armoryFactory.armoryStrategyHandler()).thenReturn(handler);
        when(handler.apply(any(ArmoryCommandEntity.class), any(DefaultArmoryFactory.DynamicContext.class)))
                .thenReturn(StoryGenerateResultAggregate.builder().build());
    }

    /**
     * 续写时人物档案必须由 bible **强制回填**。
     *
     * 性别、志愿这类跨章不变的设定由作者在 bible 中声明。
     * 后端必须回填这些事实锚，不能依赖前端携带字段，否则漏传会静默丢失约束。
     */
    @Test
    void generate_resumeRejectsAmbiguousBibleBeforeStrategy() throws Exception {
        String roster = "角色己（女，用「她」）";
        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .resumeStoryDir("20260831-story-0001")
                .storyContextEntity(StoryContextEntity.builder().novel_title("甲").build())
                .storyVO(new StoryVO())
                .build();
        Path storyDir = Paths.get("docs/workspace/stories/20260831-story-0001");
        when(storyRepository.resolveStoryDirectory("20260831-story-0001")).thenReturn(storyDir);
        when(storyRepository.readStoryBible(storyDir)).thenReturn(String.join("\n",
                "小说名称: 甲",
                "主要人物: " + roster,
                "主要人物: " + roster,
                "存在金手指: true",
                "存在金手指: true",
                "金手指名称: 回溯",
                "金手指使用间隔: 3"));

        AppException exception = assertThrows(AppException.class, () -> storyGenerateService.generate(command));

        assertEquals(cn.novel.yonren.types.enums.ResponseCode.ILLEGAL_PARAMETER.getCode(), exception.getCode());
        assertTrue(exception.getInfo().contains("STORY_BIBLE_AMBIGUOUS"));
        assertTrue(exception.getInfo().contains("主要人物"));
        assertFalse(exception.getInfo().contains(roster), "异常不能回显设定正文");
        verify(armoryFactory, never()).armoryStrategyHandler();
        verify(storyRepository, never()).readChapterSummaries(any());
    }

    @Test
    void generate_resumeRestoresMultilineMechanismIdentifierForExistingChecks() throws Exception {
        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .resumeStoryDir("20260831-story-0001")
                .storyContextEntity(StoryContextEntity.builder().novel_title("甲").build())
                .storyVO(new StoryVO())
                .build();
        Path storyDir = Paths.get("docs/workspace/stories/20260831-story-0001");
        when(storyRepository.resolveStoryDirectory("20260831-story-0001")).thenReturn(storyDir);
        when(storyRepository.readStoryBible(storyDir)).thenReturn(String.join("\n",
                "存在金手指: true",
                "金手指名称: 回声之钥",
                "每夜仅能启用一次",
                "金手指使用间隔: 3"));
        stubResumePreload(storyDir, 50);
        stubStrategyReturn();

        storyGenerateService.generate(command);

        StoryVO.StoryFeatures features = command.getStoryVO().getFeatures();
        assertNotNull(features);
        assertEquals("回声之钥", features.getCheatMechanismName(),
                "多行说明不能并入机制检测标识");
        assertEquals(3, features.getCheatUsageInterval());
        assertTrue(new ConsistencyIndexService().mentionsMechanism(
                "角色甲转动回声之钥，锁舌弹开。", command.getStoryVO()),
                "首行名称仍能命中既有使用检测");
        assertTrue(new ConsistencyIndexService().describesMechanism(
                "回声之钥的原理依靠既有结构传递信号并留下痕迹。" + "装置记录每次启用。".repeat(5),
                command.getStoryVO()), "含换行的原始说明不能破坏原理检测");
    }
}
