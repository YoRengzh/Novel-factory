package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.BatchHealthDTO;
import cn.novel.yonren.api.dto.ChapterContentDTO;
import cn.novel.yonren.api.dto.ChapterMetaDTO;
import cn.novel.yonren.api.dto.ChapterSaveRequestDTO;
import cn.novel.yonren.api.dto.ChapterSaveResponseDTO;
import cn.novel.yonren.api.dto.CheckpointCreateRequestDTO;
import cn.novel.yonren.api.dto.CheckpointDTO;
import cn.novel.yonren.api.dto.DeleteStoryResponseDTO;
import cn.novel.yonren.api.dto.FactTimelineDTO;
import cn.novel.yonren.api.dto.PendingFactDTO;
import cn.novel.yonren.api.dto.SegmentEditRequestDTO;
import cn.novel.yonren.api.dto.SegmentEditResponseDTO;
import cn.novel.yonren.api.dto.StoryResumeDTO;
import cn.novel.yonren.api.dto.StorySummaryDTO;
import cn.novel.yonren.api.dto.StoryWorkbenchDTO;
import cn.novel.yonren.domain.novel.service.manage.StoryManageService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 故事库端点：列表 / 章节浏览 / 章节编辑（编辑触发摘要回流）。无鉴权（localhost 个人使用）。
 *
 * 本类只做两件事：HTTP 语义（路由/参数/响应头）与
 * {@link AppException} → HTTP 状态的映射；业务逻辑与实体触碰全部在
 * {@link StoryManageService}（domain）。
 *
 * 映射注解仍显式写参数名（如 {@code @PathVariable("storyDirName")}）：
 * Maven 构建已由 Boot 父级管理编译插件并启用 {@code -parameters}，
 * 显式名字保留 HTTP 契约，不依赖源码参数名或其它构建入口的编译设置。
 */
@Slf4j
@RestController
@RequestMapping("/api/stories")
public class StoryManageController {

    /** 业务异常码 → HTTP 状态：此处集中映射，服务层不感知 HTTP */
    /**
     * 业务异常码 → HTTP 状态：只映射有明确 HTTP 语义的码，其余一律 500。
     * 注意：仓储/检查点的受检异常已被服务层包成 UN_ERROR，必须落 500（原 controller 对这类异常
     * 就是 500）；若默认映射 400 会把服务端故障误报成用户输入错误。
     */
    private static HttpStatus statusOf(AppException e) {
        if (ResponseCode.CHAPTER_NOT_FOUND.getCode().equals(e.getCode())
                || ResponseCode.NO_STORY_DATA.getCode().equals(e.getCode())) {
            return HttpStatus.NOT_FOUND;
        }
        if (ResponseCode.ACTIVE_JOB_CONFLICT.getCode().equals(e.getCode())) {
            return HttpStatus.CONFLICT;
        }
        if (ResponseCode.ILLEGAL_PARAMETER.getCode().equals(e.getCode())
                || ResponseCode.NULL_EXCEPTION.getCode().equals(e.getCode())
                || ResponseCode.PARAM_REPETITION.getCode().equals(e.getCode())) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @Resource
    private StoryManageService storyManageService;

    @GetMapping
    public List<StorySummaryDTO> listStories() {
        try {
            return storyManageService.listStories();
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("故事列表查询失败", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "故事列表查询失败：" + e.getMessage());
        }
    }

    @GetMapping("/{storyDirName}/resume")
    public StoryResumeDTO resume(@PathVariable("storyDirName") String storyDirName) {
        try {
            return storyManageService.resume(storyDirName);
        } catch (AppException e) {
            // 可修正的入参错误交全局 advice 输出结构化诊断，不能被默认错误页过滤。
            if (ResponseCode.ILLEGAL_PARAMETER.getCode().equals(e.getCode())) {
                throw e;
            }
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("续写信息读取失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "续写信息读取失败：" + e.getMessage());
        }
    }

    @GetMapping("/{storyDirName}/chapters")
    public List<ChapterMetaDTO> listChapters(@PathVariable("storyDirName") String storyDirName) {
        try {
            return storyManageService.listChapters(storyDirName);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("章节列表查询失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "章节列表查询失败：" + e.getMessage());
        }
    }

    @GetMapping("/{storyDirName}/workbench")
    public StoryWorkbenchDTO workbench(@PathVariable("storyDirName") String storyDirName) {
        try {
            return storyManageService.workbench(storyDirName);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("工作台概览查询失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "工作台概览查询失败：" + e.getMessage());
        }
    }

    @GetMapping("/{storyDirName}/checkpoints")
    public List<CheckpointDTO> listCheckpoints(@PathVariable("storyDirName") String storyDirName) {
        try {
            return storyManageService.listCheckpoints(storyDirName);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("检查点列表查询失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "检查点列表查询失败：" + e.getMessage());
        }
    }

    /** 手动命名快照；name 必填 */
    @PostMapping("/{storyDirName}/checkpoints")
    public CheckpointDTO createCheckpoint(@PathVariable("storyDirName") String storyDirName,
                                          @RequestBody CheckpointCreateRequestDTO request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手动快照必须提供命名词（name）");
        }
        try {
            return storyManageService.createCheckpoint(storyDirName, request.getName());
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("手动快照失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "手动快照失败：" + e.getMessage());
        }
    }

    /**
     * 回滚到指定检查点：章节/摘要/蓝图/账本原子还原 + 向量索引重建。
     * 返回还原后的检查点（current）——**必须带 JSON 体**：前端 fetchJson 统一按 Content-Type 判别响应，
     * void + 空体会被误报为「服务暂不可用（200）」。
     */
    @PostMapping("/{storyDirName}/checkpoints/{checkpointId}/restore")
    public CheckpointDTO restoreCheckpoint(@PathVariable("storyDirName") String storyDirName,
                                          @PathVariable("checkpointId") String checkpointId) {
        try {
            return storyManageService.restoreCheckpoint(storyDirName, checkpointId);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("回滚失败：{} 检查点 {}", storyDirName, checkpointId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "回滚失败：" + e.getMessage());
        }
    }

    @GetMapping("/{storyDirName}/chapters/{chapterNo}")
    public ChapterContentDTO readChapter(@PathVariable("storyDirName") String storyDirName,
                                         @PathVariable("chapterNo") int chapterNo) {
        try {
            return storyManageService.readChapter(storyDirName, chapterNo);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("章节读取失败：{} 第{}章", storyDirName, chapterNo, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "章节读取失败：" + e.getMessage());
        }
    }

    @PutMapping("/{storyDirName}/chapters/{chapterNo}")
    public ChapterSaveResponseDTO saveChapter(@PathVariable("storyDirName") String storyDirName,
                                              @PathVariable("chapterNo") int chapterNo,
                                              @RequestBody ChapterSaveRequestDTO request) {
        try {
            return storyManageService.saveChapter(storyDirName, chapterNo, request);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("正文保存失败：{} 第{}章", storyDirName, chapterNo, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "正文保存失败：" + e.getMessage());
        }
    }

    /** 段落级文本编辑：选中一段正文，让 AI 只改这一段（润色 / 扩写 / 续写），不落盘 */
    @PostMapping("/{storyDirName}/chapters/{chapterNo}/segment-edit")
    public SegmentEditResponseDTO segmentEdit(
            @PathVariable("storyDirName") String storyDirName,
            @PathVariable("chapterNo") int chapterNo,
            @RequestBody SegmentEditRequestDTO request) {
        try {
            return storyManageService.segmentEdit(storyDirName, chapterNo, request);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("段落编辑失败：{} 第{}章", storyDirName, chapterNo, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "段落编辑失败：" + e.getMessage());
        }
    }

    /**
     * 批末体检（只读、零 LLM 成本）：把阶段一~四建立的机械指标横着看一遍，输出可分级、可行动的
     * 健康清单（账本完整度 / 每章新地点率 / 过渡章占比 / 低密度章占比 / 未核销债 /
     * 出口条件达成率 / 放宽档入账占比）。与批末日志是同一份结论，供人工按需复算。
     */
    @GetMapping("/{storyDirName}/health")
    public BatchHealthDTO health(@PathVariable("storyDirName") String storyDirName) {
        try {
            return storyManageService.health(storyDirName);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("批末体检失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "批末体检失败：" + e.getMessage());
        }
    }

    /**
     * 导出成书：分章 ZIP 下载——每章一个独立文件（TXT / Markdown / Word），解压即「书名文件夹 +
     * 分章文件」。只读，不加「作业运行中」守卫——章节文件是原子写（tmp+move），
     * 导出的是发起时刻的快照；响应头用 ContentDisposition 的 UTF-8 filename*（RFC 5987），中文书名不乱码。
     */
    @GetMapping("/{storyDirName}/export")
    public ResponseEntity<byte[]> exportBook(@PathVariable("storyDirName") String storyDirName,
                                             @RequestParam(name = "format", defaultValue = "txt") String format) {
        cn.novel.yonren.api.dto.ExportFilePayloadDTO book;
        try {
            book = storyManageService.exportBook(storyDirName, format);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("导出成书失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "导出成书失败：" + e.getMessage());
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(book.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(book.fileBytes());
    }

    /**
     * 待人工确认事实查询：摘要证据校验未通过的状态事实（疑似编造/漂移，不入账本），
     * 按章节聚合透出供人工裁决。数据源为 stories/<dir>/memory/summaries.json 的 pendingFacts
     */
    @GetMapping("/{storyDirName}/pending-facts")
    public List<PendingFactDTO> pendingFacts(@PathVariable("storyDirName") String storyDirName) {
        try {
            return storyManageService.pendingFacts(storyDirName);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("待确认事实查询失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "待确认事实查询失败：" + e.getMessage());
        }
    }

    /**
     * 实体事实时间线追溯：按实体名检索其在全书各章的状态轨迹（章节 + 账本类型 + 状态 + 证据原文）。
     * 排障用途——模型产出错误状态时，一次调用定位"该状态出自第几章、哪段正文"。
     * 精确匹配无结果时回退包含匹配；证据引用为摘要侧机械校验通过的原句片段
     */
    @GetMapping("/{storyDirName}/facts/{name}")
    public List<FactTimelineDTO> factTimeline(@PathVariable("storyDirName") String storyDirName,
                                              @PathVariable("name") String name) {
        try {
            return storyManageService.factTimeline(storyDirName, name);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("事实时间线查询失败：{} 实体 {}", storyDirName, name, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "事实时间线查询失败：" + e.getMessage());
        }
    }

    /**
     * **删除故事**（故事库的删除操作，不可恢复）：删故事目录 + 清该故事独占的记忆向量集合。
     * 有活动作业时 409；共享的世界观集合不动——同世界观下可能有其它故事在引用。
     */
    @PostMapping("/{storyDirName}/delete")
    public DeleteStoryResponseDTO deleteStory(@PathVariable("storyDirName") String storyDirName) {
        try {
            return storyManageService.deleteStory(storyDirName);
        } catch (AppException e) {
            throw new ResponseStatusException(statusOf(e), e.getInfo());
        } catch (Exception e) {
            log.error("删除故事失败：{}", storyDirName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除故事失败：" + e.getMessage());
        }
    }
}
