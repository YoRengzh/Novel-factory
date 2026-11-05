package cn.novel.yonren.domain.novel.service.armory.candidate;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 候选链路统计测试：重点钉住**按章去重**口径——* 重跑批次时同一章会追加多行，统计必须取每章最后一行，
 * 否则旧批次行会把触发率推到 100% 以上（老书重跑实测 105%/133%）。
 */
@ExtendWith(MockitoExtension.class)
class CandidateSampleServiceTest {

    @Mock
    private IStoryRepository storyRepository;

    @InjectMocks
    private CandidateSampleService service;

    @Test
    @DisplayName("readStats：重跑产生的同章多行，以每章最后一行为准")
    void readStats_dedupesByChapterKeepingLatest() throws Exception {
        when(storyRepository.readCandidateSampleLines(any())).thenReturn(List.of(
                "{\"chapterNo\":27,\"outcome\":\"incumbent-kept\"}",      // 旧批次
                "{\"chapterNo\":27,\"outcome\":\"challenger-adopted\"}",  // 重跑后最新 ⇒ 采纳
                "{\"chapterNo\":28,\"outcome\":\"challenger-adopted\"}",
                "{\"chapterNo\":29,\"outcome\":\"error\"}",               // 旧批次失败
                "{\"chapterNo\":29,\"outcome\":\"incumbent-kept\"}"       // 重跑成功 ⇒ 覆盖 error
        ));

        CandidateSampleService.CandidateStats stats = service.readStats(java.nio.file.Path.of("s"));

        assertEquals(3, stats.triggered(), "3 个章节各自只计最新一行，旧批次行不得重复计入");
        assertEquals(2, stats.adopted());
    }

    @Test
    @DisplayName("readStats：损坏行/空行跳过；仓储异常返回 null 不反噬观测层")
    void readStats_toleratesCorruptLinesAndRepositoryFailure() throws Exception {
        when(storyRepository.readCandidateSampleLines(any())).thenReturn(List.of(
                "",
                "这不是JSON",
                "{\"chapterNo\":30,\"outcome\":\"incumbent-kept\"}"
        ));
        assertEquals(1, service.readStats(java.nio.file.Path.of("s")).triggered());

        when(storyRepository.readCandidateSampleLines(any()))
                .thenThrow(new RuntimeException("io"));
        assertNull(service.readStats(java.nio.file.Path.of("s")));
    }
}
