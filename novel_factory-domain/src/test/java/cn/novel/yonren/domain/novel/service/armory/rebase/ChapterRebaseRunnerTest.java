package cn.novel.yonren.domain.novel.service.armory.rebase;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.rebase.RebaseRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChapterRebaseRunnerTest {

    private static ChapterPlanItemEntity item() {
        return ChapterPlanItemEntity.builder().chapterNo(3).title("资格牌前").schemaVersion(2)
                .goal("取得资格").endingHook("拿牌")
                .mainLineAdvance("【关系】取得资格并结怨")
                .characters(List.of("苏尘", "林昭"))
                .scenes(List.of(new ScenePlan("s1", List.of("苏尘", "林昭"), "领资格", "被拦", List.of(),
                        "被要求考校", "推进", List.of(), "廊下", null)))
                .build();
    }

    private static StoryProperties props(StoryProperties.RebaseProperties.Mode mode) {
        StoryProperties properties = new StoryProperties();
        properties.getRebase().setMode(mode);
        return properties;
    }

    private static StoryVO story() {
        StoryVO vo = new StoryVO();
        vo.setModule(new StoryVO.Module());
        return vo;
    }

    @Test
    void disabledRunnerDoesNothing() {
        List<String> calls = new ArrayList<>();
        var service = new ChapterRebaseService((module, call) -> {
            calls.add("called");
            return "{}";
        }, props(StoryProperties.RebaseProperties.Mode.OFF));
        var runner = new ChapterRebaseRunner(service, Mockito.mock(ChapterRebaseRecorder.class),
                props(StoryProperties.RebaseProperties.Mode.OFF), Mockito.mock(IStoryRepository.class));

        assertFalse(runner.enabled());
        assertNull(runner.proposeOnly(story(), item(), List.of(), null, Path.of("story"), Path.of("run")));
        assertTrue(calls.isEmpty());
    }

    @Test
    void proposalIsRecordedButOriginalPlanIsReturnedToCaller() throws Exception {
        var service = new ChapterRebaseService((module, call) ->
                "{\"status\":\"UNCHANGED\",\"diagnosis\":[],\"patches\":[],\"remainingRisks\":[]}",
                props(StoryProperties.RebaseProperties.Mode.PROPOSE_ONLY));
        var recorder = Mockito.mock(ChapterRebaseRecorder.class);
        var runner = new ChapterRebaseRunner(service, recorder,
                props(StoryProperties.RebaseProperties.Mode.PROPOSE_ONLY), Mockito.mock(IStoryRepository.class));
        var original = item();

        RebaseRecord record = runner.proposeOnly(story(), original, List.of(),
                StoryContextEntity.builder().build(), Path.of("story"), Path.of("run"));

        assertEquals("UNCHANGED", record.status());
        assertFalse(record.adopted());
        assertEquals("领资格", original.getScenes().get(0).intention(), "主链计划不得被候选替换");
        Mockito.verify(recorder).record(Path.of("story"), Path.of("run"), record);
    }

    @Test
    void modelFailureIsRecordedAsInvalidAndKeepsPlan() throws Exception {
        var service = new ChapterRebaseService((module, call) -> {
            throw new IllegalStateException("timeout");
        }, props(StoryProperties.RebaseProperties.Mode.PROPOSE_ONLY));
        var recorder = Mockito.mock(ChapterRebaseRecorder.class);
        var runner = new ChapterRebaseRunner(service, recorder,
                props(StoryProperties.RebaseProperties.Mode.PROPOSE_ONLY), Mockito.mock(IStoryRepository.class));

        RebaseRecord record = runner.proposeOnly(story(), item(), List.of(), null, Path.of("story"), Path.of("run"));

        assertEquals("INVALID", record.status());
        assertNull(record.candidatePlan(), "失败不得产出可采纳候选");
        assertFalse(record.adopted());
        Mockito.verify(recorder).record(Path.of("story"), Path.of("run"), record);
    }
}
