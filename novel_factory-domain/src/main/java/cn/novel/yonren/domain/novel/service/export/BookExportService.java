package cn.novel.yonren.domain.novel.service.export;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.BookExport;
import cn.novel.yonren.domain.novel.service.armory.quality.ChapterLengthPolicy;
import cn.novel.yonren.types.utils.StoryBibleParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


@Slf4j
@Service
public class BookExportService {

    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter EXPORT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 附件文件名主体长度上限，防超长文件名被文件系统拒绝 */
    private static final int FILE_NAME_MAX_CHARS = 80;

    private static final String TXT_RULE = "=".repeat(64);

    private final IStoryRepository storyRepository;

    public BookExportService(IStoryRepository storyRepository) {
        this.storyRepository = storyRepository;
    }

    /**
     * 分章导出。故事下没有任何可读章节时返回 null（控制器转 404）。
     */
    public BookExport exportChapters(Path storyDir, BookFormat format) throws IOException {
        List<ChapterContentEntity> chapters = loadChapters(storyDir);
        if (chapters.isEmpty()) {
            return null;
        }
        String storyDirName = storyDir.getFileName() == null ? "untitled" : storyDir.getFileName().toString();
        StoryBibleParser.Snapshot bible = readBibleQuietly(storyDir);
        String novelTitle = firstNonBlank(bible == null ? null : bible.novelTitle(), storyDirName);
        String theme = bible == null ? null : bible.theme();
        String style = bible == null ? null : bible.style();
        List<VolumeBlueprintEntity> volumes = readVolumesQuietly(storyDir);
        long effectiveChars = effectiveCharsOf(chapters);

        String root = sanitizeFileName(novelTitle);
        byte[] zipBytes = buildChapterZip(root, format, novelTitle, theme, style, chapters, effectiveChars, volumes);
        return BookExport.builder()
                .fileName(root + "-分章" + format.name() + "-" + chapters.size() + "章-"
                        + LocalDateTime.now().format(EXPORT_DATE) + ".zip")
                .fileBytes(zipBytes)
                .format(format)
                .chapterCount(chapters.size())
                .effectiveChars(effectiveChars)
                .novelTitle(novelTitle)
                .build();
    }

    // ==================== 装载与统计 ====================

    /**
     * 顺序读全章节：端口契约 readChapterNumbers 返回升序，但装订顺序不容赌——
     * 防御性排序，仓储契约漂移时书序也不能乱。单章读取失败带章号上抛（定位坏文件）；
     * 列表与读取之间文件消失（并发删除等极端竞态）跳过不中断
     */
    private List<ChapterContentEntity> loadChapters(Path storyDir) throws IOException {
        List<Integer> chapterNumbers;
        try {
            chapterNumbers = storyRepository.readChapterNumbers(storyDir);
        } catch (Exception e) {
            throw new IOException("导出中止：读取章节列表失败（" + e.getMessage() + "）", e);
        }
        if (chapterNumbers == null || chapterNumbers.isEmpty()) {
            return List.of();
        }
        List<Integer> ordered = chapterNumbers.stream().sorted().toList();
        List<ChapterContentEntity> chapters = new ArrayList<>(ordered.size());
        for (int no : ordered) {
            ChapterContentEntity chapter;
            try {
                chapter = storyRepository.readChapter(storyDir, no);
            } catch (Exception e) {
                throw new IOException("导出中止：读取第 " + no + " 章失败（" + e.getMessage() + "）", e);
            }
            if (chapter != null) {
                chapters.add(chapter);
            }
        }
        return chapters;
    }

    private long effectiveCharsOf(List<ChapterContentEntity> chapters) {
        return chapters.stream()
                .mapToLong(chapter -> ChapterLengthPolicy.effectiveCharacterCount(chapter.getContent()))
                .sum();
    }


    private byte[] buildChapterZip(String root, BookFormat format, String novelTitle, String theme, String style,
                                   List<ChapterContentEntity> chapters, long effectiveChars,
                                   List<VolumeBlueprintEntity> volumes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            putEntry(zip, root + "/" + metaFileName(format),
                    metaFileBytes(format, novelTitle, theme, style, chapters.size(), effectiveChars));
            for (ChapterContentEntity chapter : chapters) {
                VolumeBlueprintEntity volume = volumeOf(chapter.getChapterNo(), volumes);
                String dir = volume == null ? root : root + "/" + sanitizeFileName(volumeHeading(volume));
                putEntry(zip, dir + "/" + chapterFileName(chapter) + "." + format.getExt(),
                        chapterFileBytes(format, chapter));
            }
        }
        return buffer.toByteArray();
    }

    private void putEntry(ZipOutputStream zip, String entryPath, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(entryPath));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** 书名页文件字节：随格式走 TXT 分隔线版 / MD 引用块版 / DOCX 独立 Word 文档 */
    private byte[] metaFileBytes(BookFormat format, String novelTitle, String theme, String style,
                                 int chapterCount, long effectiveChars) throws IOException {
        return switch (format) {
            case TXT -> titlePageText(novelTitle, theme, style, chapterCount, effectiveChars)
                    .getBytes(StandardCharsets.UTF_8);
            case MD -> titlePageMarkdown(novelTitle, theme, style, chapterCount, effectiveChars)
                    .getBytes(StandardCharsets.UTF_8);
            case DOCX -> DocxBookWriter.writeTitlePage(novelTitle,
                    metaLines(theme, style, chapterCount, effectiveChars));
        };
    }

    /** 章文件字节：TXT/MD 是文本（与书名页同源版式），DOCX 是独立 Word 文档 */
    private byte[] chapterFileBytes(BookFormat format, ChapterContentEntity chapter) throws IOException {
        return switch (format) {
            case TXT, MD -> chapterFileText(format, chapter).getBytes(StandardCharsets.UTF_8);
            case DOCX -> DocxBookWriter.writeChapter(chapterHeading(chapter), chapter.getContent());
        };
    }

    /** 根目录元信息文件名：随格式走「书名页.txt / 书名页.md / 书名页.docx」 */
    private String metaFileName(BookFormat format) {
        return "书名页." + format.getExt();
    }

    /**
     * 章文本文件内容（TXT/MD）：TXT 用纯文本标题行，MD 用一级标题——
     * DOCX 不走这里（章文件由 DocxBookWriter 装订为独立 Word 文档）
     */
    private String chapterFileText(BookFormat format, ChapterContentEntity chapter) {
        String heading = chapterHeading(chapter);
        String body = StringUtils.trimToEmpty(chapter.getContent());
        return format == BookFormat.MD
                ? "# " + heading + "\n\n" + body + "\n"
                : heading + "\n\n" + body + "\n";
    }

    // ==================== 版式片段 ====================

    /** 书名页元信息行：题材/风格缺失则该部分不出现（导出是给读者的书，不打 null 占位） */
    private List<String> metaLines(String theme, String style, int chapterCount, long effectiveChars) {
        List<String> lines = new ArrayList<>(3);
        List<String> parts = new ArrayList<>(2);
        if (StringUtils.isNotBlank(theme)) {
            parts.add("题材：" + theme.trim());
        }
        if (StringUtils.isNotBlank(style)) {
            parts.add("风格：" + style.trim());
        }
        if (!parts.isEmpty()) {
            lines.add(String.join("　", parts));
        }
        lines.add("共 " + chapterCount + " 章　全书有效字数 "
                + String.format(Locale.CHINA, "%,d", effectiveChars) + " 字");
        lines.add("导出时间：" + LocalDateTime.now().format(EXPORT_TIME));
        return lines;
    }

    /** TXT 书名页：分隔线 + 书名 + 元信息 + 分隔线（不含结尾换行） */
    private String titlePageText(String novelTitle, String theme, String style,
                                 int chapterCount, long effectiveChars) {
        StringBuilder sb = new StringBuilder();
        sb.append(TXT_RULE).append('\n');
        sb.append("《").append(novelTitle).append("》\n");
        for (String metaLine : metaLines(theme, style, chapterCount, effectiveChars)) {
            sb.append(metaLine).append('\n');
        }
        sb.append(TXT_RULE);
        return sb.toString();
    }

    /** MD 书名页：一级标题 + 引用块元信息 */
    private String titlePageMarkdown(String novelTitle, String theme, String style,
                                     int chapterCount, long effectiveChars) {
        StringBuilder sb = new StringBuilder("# ").append(novelTitle).append("\n\n");
        for (String metaLine : metaLines(theme, style, chapterCount, effectiveChars)) {
            sb.append("> ").append(metaLine).append('\n');
        }
        return sb.toString();
    }

    /** 卷子文件夹名：「第一卷 初入雾港」；卷名缺失时只剩卷号部分（分章文件夹与 Word 大纲共用） */
    static String volumeHeading(VolumeBlueprintEntity volume) {
        int no = volume.getVolumeNo() == null ? 0 : volume.getVolumeNo();
        String title = StringUtils.isBlank(volume.getTitle()) ? "" : " " + volume.getTitle().trim();
        return "第" + ChineseNumberConverter.toChinese(no) + "卷" + title;
    }

    /**
     * 章号所属卷：按列表序取第一个区间命中的卷（无区间信息/不落任何区间 → null，平铺根目录）
     */
    static VolumeBlueprintEntity volumeOf(Integer chapterNo, List<VolumeBlueprintEntity> volumes) {
        if (chapterNo == null) {
            return null;
        }
        for (VolumeBlueprintEntity volume : volumes) {
            Integer start = volume.getStartChapter();
            Integer end = volume.getEndChapter();
            if (start != null && end != null && chapterNo >= start && chapterNo <= end) {
                return volume;
            }
        }
        return null;
    }

    /** 章标题行：「第1章 雾夜来客」；标题缺失时只剩章号部分（分章文件与 Word 大纲共用） */
    static String chapterHeading(ChapterContentEntity chapter) {
        String no = chapter.getChapterNo() == null ? "" : String.valueOf(chapter.getChapterNo());
        String title = StringUtils.isBlank(chapter.getTitle()) ? "" : " " + chapter.getTitle().trim();
        return "第" + no + "章" + title;
    }

    /** 分章文件名：「第0001章 雾夜来客」——章号补零保证排序；标题缺失时只剩章号部分 */
    private String chapterFileName(ChapterContentEntity chapter) {
        int no = chapter.getChapterNo() == null ? 0 : chapter.getChapterNo();
        String title = StringUtils.isBlank(chapter.getTitle())
                ? "" : " " + sanitizeFileName(chapter.getTitle().trim());
        return String.format("第%04d章", no) + title;
    }

    // ==================== 降级与清洗 ====================

    private StoryBibleParser.Snapshot readBibleQuietly(Path storyDir) {
        try {
            return StoryBibleParser.parse(storyRepository.readStoryBible(storyDir));
        } catch (Exception e) {
            log.warn("导出成书：story-bible 读取/解析失败，书名回退故事目录名（{}）", e.getMessage());
            return null;
        }
    }

    private List<VolumeBlueprintEntity> readVolumesQuietly(Path storyDir) {
        try {
            List<VolumeBlueprintEntity> volumes = storyRepository.readVolumes(storyDir);
            return volumes == null ? List.of() : volumes;
        } catch (Exception e) {
            log.warn("导出成书：卷蓝图读取失败，全部章平铺根目录（{}）", e.getMessage());
            return List.of();
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "book";
    }

    /**
     * 文件名清洗：路径非法字符（\\ / : * ? " < > |）与控制符替换为 _；
     * 先清洗后截断，截断按码点计（防半个代理对）
     */
    private String sanitizeFileName(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (cleaned.isEmpty()) {
            cleaned = "book";
        }
        if (cleaned.codePointCount(0, cleaned.length()) <= FILE_NAME_MAX_CHARS) {
            return cleaned;
        }
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, FILE_NAME_MAX_CHARS));
    }
}
