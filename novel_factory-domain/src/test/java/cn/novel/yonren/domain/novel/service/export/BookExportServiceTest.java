package cn.novel.yonren.domain.novel.service.export;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.BookExport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 分章导出测试：ZIP 装订结构（卷子文件夹/孤章/补零文件名/书名页）与 TXT/MD 双版式、
 * 文件名清洗、宽容降级。IStoryRepository 打桩（端口方法齐备，infrastructure 零参与）
 */
class BookExportServiceTest {

    /** bible 是「标签: 值」行格式，值含 ASCII 冒号也安全（解析按第一个冒号切分） */
    private static final String BIBLE =
            "小说名称: 示例书\n小说类型: 都市异能\n小说格调: 逆袭打脸爽文\n";

    @Mock
    private IStoryRepository storyRepository;

    @InjectMocks
    private BookExportService service;

    private final Path storyDir = Paths.get("/workspace/20261011-story-9001");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private static ChapterContentEntity chapter(int no, String title, String content) {
        ChapterContentEntity entity = new ChapterContentEntity();
        entity.setChapterNo(no);
        entity.setTitle(title);
        entity.setContent(content);
        return entity;
    }

    private static VolumeBlueprintEntity volume(int no, String title, int start, int end) {
        return VolumeBlueprintEntity.builder().volumeNo(no).title(title).startChapter(start).endChapter(end).build();
    }

    private void mockBibleAndVolumes(String bible, List<VolumeBlueprintEntity> volumes) throws Exception {
        if (bible == null) {
            when(storyRepository.readStoryBible(storyDir)).thenThrow(new IOException("bible 缺失"));
        } else {
            when(storyRepository.readStoryBible(storyDir)).thenReturn(bible);
        }
        when(storyRepository.readVolumes(storyDir)).thenReturn(volumes);
    }

    /** 解压 ZIP 逐条读出文件名与内容（显式 UTF-8，与服务端写入口径一致） */
    private static Map<String, String> readZip(byte[] bytes) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        readZipBytes(bytes).forEach((name, content) -> files.put(name, new String(content, StandardCharsets.UTF_8)));
        return files;
    }

    /** 字节形态解压：docx 章文件是二进制（本身又是 ZIP），文本版 readZip 会破坏它 */
    private static Map<String, byte[]> readZipBytes(byte[] bytes) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    files.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return files;
    }

    @Test
    void exportChapters_txtBuildsZipWithVolumeFoldersAndRootStrays() throws Exception {
        // 乱序 + 孤章：卷1 覆盖 1-2、卷2 覆盖 3-4，第 5 章不落任何卷区间 → 放根目录
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(5, 3, 1, 2));
        mockBibleAndVolumes(BIBLE, List.of(volume(1, "初入旧港", 1, 2), volume(2, "沉船疑云", 3, 4)));
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(chapter(1, "雨夜来客", "正文一"));
        when(storyRepository.readChapter(storyDir, 2)).thenReturn(chapter(2, "旧档疑点", "正文二"));
        when(storyRepository.readChapter(storyDir, 3)).thenReturn(chapter(3, "码头浮尸", "正文三"));
        when(storyRepository.readChapter(storyDir, 5)).thenReturn(chapter(5, "孤章", "正文五"));

        BookExport book = service.exportChapters(storyDir, BookFormat.TXT);

        assertNotNull(book);
        assertEquals("示例书", book.getNovelTitle());
        assertEquals(4, book.getChapterCount());
        assertTrue(book.getFileName().startsWith("示例书-分章TXT-4章-") && book.getFileName().endsWith(".zip"),
                book.getFileName());
        Map<String, String> files = readZip(book.getFileBytes());
        assertEquals(5, files.size(), "书名页 + 4 章：" + files.keySet());
        assertTrue(files.containsKey("示例书/书名页.txt"), files.keySet().toString());
        assertTrue(files.containsKey("示例书/第一卷 初入旧港/第0001章 雨夜来客.txt"), files.keySet().toString());
        assertTrue(files.containsKey("示例书/第一卷 初入旧港/第0002章 旧档疑点.txt"), files.keySet().toString());
        assertTrue(files.containsKey("示例书/第二卷 沉船疑云/第0003章 码头浮尸.txt"), files.keySet().toString());
        assertTrue(files.containsKey("示例书/第0005章 孤章.txt"), "不落卷区间的孤章放根目录：" + files.keySet());
        String chapterFile = files.get("示例书/第一卷 初入旧港/第0001章 雨夜来客.txt");
        assertTrue(chapterFile.startsWith("第1章 雨夜来客\n"), "TXT 章文件用自然章号纯文本标题（补零只用于文件名）");
        assertTrue(chapterFile.contains("正文一"), chapterFile);
        String titlePage = files.get("示例书/书名页.txt");
        assertTrue(titlePage.contains("《示例书》") && titlePage.contains("共 4 章"), titlePage);
    }

    @Test
    void exportChapters_mdUsesMarkdownHeadingsAndMetaPage() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 2));
        mockBibleAndVolumes(BIBLE, List.of(volume(1, "初入旧港", 1, 2)));
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(chapter(1, "雨夜来客", "正文一"));
        when(storyRepository.readChapter(storyDir, 2)).thenReturn(chapter(2, "旧档疑点", "正文二"));

        BookExport book = service.exportChapters(storyDir, BookFormat.MD);

        assertNotNull(book);
        assertTrue(book.getFileName().startsWith("示例书-分章MD-2章-") && book.getFileName().endsWith(".zip"),
                book.getFileName());
        Map<String, String> files = readZip(book.getFileBytes());
        assertEquals(3, files.size(), "书名页 + 2 章：" + files.keySet());
        assertTrue(files.containsKey("示例书/书名页.md"), files.keySet().toString());
        assertTrue(files.containsKey("示例书/第一卷 初入旧港/第0001章 雨夜来客.md"), files.keySet().toString());
        // 分章后每章是独立文件，章标题即该文件的最高层级标题
        String chapterFile = files.get("示例书/第一卷 初入旧港/第0001章 雨夜来客.md");
        assertTrue(chapterFile.startsWith("# 第1章 雨夜来客\n\n正文一"), chapterFile);
        String titlePage = files.get("示例书/书名页.md");
        assertTrue(titlePage.startsWith("# 示例书\n"), titlePage);
        assertTrue(titlePage.contains("> 题材：都市异能　风格：逆袭打脸爽文"), titlePage);
    }

    @Test
    void exportChapters_returnsNullWhenNoChapters() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of());

        assertNull(service.exportChapters(storyDir, BookFormat.TXT));
    }

    @Test
    void exportChapters_sanitizesIllegalCharsInEntryNames() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1));
        mockBibleAndVolumes("小说名称: 书名:卷一/部*上?\n", null);
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(chapter(1, "来源:不信?", "内容"));

        BookExport book = service.exportChapters(storyDir, BookFormat.TXT);

        assertNotNull(book);
        assertTrue(book.getFileName().startsWith("书名_卷一_部_上_-分章TXT-1章-"), book.getFileName());
        Map<String, String> files = readZip(book.getFileBytes());
        assertTrue(files.containsKey("书名_卷一_部_上_/第0001章 来源_不信_.txt"), files.keySet().toString());
    }

    @Test
    void exportChapters_flatWhenNoVolumes() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 2));
        mockBibleAndVolumes(BIBLE, null);
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(chapter(1, "开端", "内容一"));
        when(storyRepository.readChapter(storyDir, 2)).thenReturn(chapter(2, "发展", "内容二"));

        BookExport book = service.exportChapters(storyDir, BookFormat.TXT);

        Map<String, String> files = readZip(book.getFileBytes());
        assertEquals(3, files.size(), "书名页 + 2 章，全部平铺根目录：" + files.keySet());
        for (String name : files.keySet()) {
            assertTrue(name.startsWith("示例书/") && name.indexOf('/', "示例书/".length()) < 0,
                    "无卷蓝图不得出现卷子文件夹：" + name);
        }
    }

    /** bible 缺失/不可读 → 书名回退故事目录名（根文件夹与附件名都跟着变），不挡导出 */
    @Test
    void exportChapters_fallsBackToDirNameWhenBibleMissing() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 2));
        mockBibleAndVolumes(null, null);
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(chapter(1, "开端", "内容一百"));
        when(storyRepository.readChapter(storyDir, 2)).thenReturn(chapter(2, "发展", "内容二百"));

        BookExport book = service.exportChapters(storyDir, BookFormat.TXT);

        assertNotNull(book);
        assertEquals("20261011-story-9001", book.getNovelTitle());
        assertEquals(8, book.getEffectiveChars());
        assertTrue(book.getFileName().startsWith("20261011-story-9001-分章TXT-2章-"), book.getFileName());
        Map<String, String> files = readZip(book.getFileBytes());
        assertTrue(files.containsKey("20261011-story-9001/书名页.txt"), files.keySet().toString());
        assertTrue(files.get("20261011-story-9001/书名页.txt").contains("《20261011-story-9001》"));
    }

    /** 单章读取失败必须带章号上抛（定位坏文件），不让整个导出变成无信息的 500 */
    @Test
    void exportChapters_wrapsChapterReadFailureWithChapterNo() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1, 2));
        mockBibleAndVolumes(BIBLE, null);
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(chapter(1, "开端", "内容一"));
        when(storyRepository.readChapter(storyDir, 2)).thenThrow(new IOException("磁盘坏块"));

        IOException e = assertThrows(IOException.class, () -> service.exportChapters(storyDir, BookFormat.TXT));

        assertTrue(e.getMessage().contains("第 2 章"), e.getMessage());
    }

    // ==================== 分章 Word（docx）导出（2026-10-11） ====================

    /** docx 是标准 ZIP 包：DOM 解析锁 XML 可解析性（转义正确、无非法控制字符） */
    private static void assertWellFormedXml(byte[] docx, String entryName) throws Exception {
        byte[] bytes = readZipBytes(docx).get(entryName);
        assertNotNull(bytes, "缺少条目 " + entryName);
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new ByteArrayInputStream(bytes)));
    }

    @Test
    void exportChapters_docxBuildsZipWithStandaloneChapterDocuments() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(3, 1, 2));
        mockBibleAndVolumes(BIBLE, List.of(volume(1, "初入旧港", 1, 2), volume(2, "沉船疑云", 3, 4)));
        when(storyRepository.readChapter(storyDir, 1)).thenReturn(chapter(1, "雨夜来客", "正文第一段\n\n正文第二段"));
        when(storyRepository.readChapter(storyDir, 2)).thenReturn(chapter(2, "旧档疑点", "正文二"));
        when(storyRepository.readChapter(storyDir, 3)).thenReturn(chapter(3, "码头浮尸", "正文三"));

        BookExport book = service.exportChapters(storyDir, BookFormat.DOCX);

        assertNotNull(book);
        assertEquals(BookFormat.DOCX, book.getFormat());
        assertTrue(book.getFileName().startsWith("示例书-分章DOCX-3章-") && book.getFileName().endsWith(".zip"),
                book.getFileName());
        // 外层 ZIP：书名页 + 3 章，文件夹结构与 TXT/MD 完全同构
        Map<String, byte[]> outer = readZipBytes(book.getFileBytes());
        assertEquals(4, outer.size(), "书名页 + 3 章：" + outer.keySet());
        assertTrue(outer.containsKey("示例书/书名页.docx"), outer.keySet().toString());
        assertTrue(outer.containsKey("示例书/第一卷 初入旧港/第0001章 雨夜来客.docx"), outer.keySet().toString());
        assertTrue(outer.containsKey("示例书/第一卷 初入旧港/第0002章 旧档疑点.docx"), outer.keySet().toString());
        assertTrue(outer.containsKey("示例书/第二卷 沉船疑云/第0003章 码头浮尸.docx"), outer.keySet().toString());

        // 每个章文件都是完整可开的最小 Word 包：五条目 + document.xml 可解析 + 章标题挂 Heading1
        byte[] chapterDocx = outer.get("示例书/第一卷 初入旧港/第0001章 雨夜来客.docx");
        Map<String, byte[]> inner = readZipBytes(chapterDocx);
        for (String required : new String[]{
                "[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml",
                "word/_rels/document.xml.rels"}) {
            assertTrue(inner.containsKey(required), "缺 docx 必需条目 " + required + "：" + inner.keySet());
        }
        assertWellFormedXml(chapterDocx, "word/document.xml");
        assertWellFormedXml(chapterDocx, "word/styles.xml");
        String doc = new String(inner.get("word/document.xml"), StandardCharsets.UTF_8);
        assertTrue(doc.contains("<w:pStyle w:val=\"Heading1\"/>") && doc.contains("第1章 雨夜来客"), doc);
        // 正文按行拆段：空行跳过，两段正文各成一个 w:p
        assertTrue(doc.contains("正文第一段") && doc.contains("正文第二段"), doc);

        // 书名页文档：BookTitle 样式 + 元信息
        byte[] titlePageDocx = outer.get("示例书/书名页.docx");
        assertWellFormedXml(titlePageDocx, "word/document.xml");
        String titlePage = new String(readZipBytes(titlePageDocx).get("word/document.xml"), StandardCharsets.UTF_8);
        assertTrue(titlePage.contains("w:val=\"BookTitle\"") && titlePage.contains("示例书"), titlePage);
        assertTrue(titlePage.contains("题材：都市异能　风格：逆袭打脸爽文"), titlePage);
    }

    /** 正文里的 XML 特殊字符必须转义，控制字符必须剔除——防"Word 打不开需恢复" */
    @Test
    void exportChapters_docxEscapesXmlAndStripsControlChars() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(List.of(1));
        mockBibleAndVolumes(BIBLE, null);
        when(storyRepository.readChapter(storyDir, 1))
                .thenReturn(chapter(1, "标题<title>&", "他写下<body>&记录，\u0007然后删掉"));

        BookExport book = service.exportChapters(storyDir, BookFormat.DOCX);

        // 章文件名走文件名清洗（< > 非法被替换，& 本身合法保留），文档内容走 XML 转义
        Map<String, byte[]> outer = readZipBytes(book.getFileBytes());
        byte[] chapterDocx = outer.get("示例书/第0001章 标题_title_&.docx");
        assertNotNull(chapterDocx, outer.keySet().toString());
        String doc = new String(readZipBytes(chapterDocx).get("word/document.xml"), StandardCharsets.UTF_8);
        assertTrue(doc.contains("标题&lt;title&gt;&amp;"), doc);
        assertTrue(doc.contains("他写下&lt;body&gt;&amp;记录，"), doc);
        assertFalse(doc.contains("\u0007"), "XML 非法控制字符必须剔除：" + doc);
        assertWellFormedXml(chapterDocx, "word/document.xml");
    }
}
