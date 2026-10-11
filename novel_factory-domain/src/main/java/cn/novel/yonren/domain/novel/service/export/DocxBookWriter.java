package cn.novel.yonren.domain.novel.service.export;

import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 手写最小 OOXML（.docx）装订器：docx 本质是「ZIP + XML」，用 java.util.zip 直接产出，
 * 不为「标题 + 段落」的纯文本装订引入 POI 十余 MB 的依赖树。
 *
 * <p>分章导出下本类只装订两种<b>独立文档</b>：章文件（章标题 = Heading1 该文件的大纲顶层，
 * 正文按行拆段、空行跳过、保留段首全角缩进）与书名页文件（BookTitle 居中大字 + 元信息段）。
 * 每个文档都是完整的最小 OOXML 包（Word/WPS 可直接打开）：{@code [Content_Types].xml}、
 * {@code _rels/.rels}、{@code word/document.xml}、{@code word/styles.xml}、
 * {@code word/_rels/document.xml.rels}——五个条目缺一不可。
 *
 * <p>健壮性：文本过 XML 转义并剔除 XML 1.0 非法控制字符（防"Word 打不开需恢复"）；
 * 包结构与可解析性由 BookExportServiceTest 解包断言锁定。
 */
final class DocxBookWriter {

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private DocxBookWriter() {
    }

    /** 独立章文件：章标题挂 Heading1（该文件的大纲顶层），正文按行拆段 */
    static byte[] writeChapter(String chapterHeading, String content) throws IOException {
        StringBuilder body = new StringBuilder(1024);
        body.append(paragraph("Heading1", chapterHeading));
        for (String line : chapterLines(content)) {
            body.append(paragraph(null, line));
        }
        return packageDocx(documentXml(body.toString()));
    }

    /** 书名页文件：BookTitle 居中大字 + 元信息普通段 */
    static byte[] writeTitlePage(String novelTitle, List<String> metaLines) throws IOException {
        StringBuilder body = new StringBuilder(512);
        body.append(paragraph("BookTitle", novelTitle));
        for (String metaLine : metaLines) {
            body.append(paragraph(null, metaLine));
        }
        return packageDocx(documentXml(body.toString()));
    }

    private static byte[] packageDocx(String documentXml) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            putEntry(zip, "[Content_Types].xml", contentTypesXml());
            putEntry(zip, "_rels/.rels", rootRelsXml());
            putEntry(zip, "word/document.xml", documentXml);
            putEntry(zip, "word/styles.xml", stylesXml());
            putEntry(zip, "word/_rels/document.xml.rels", documentRelsXml());
        }
        return buffer.toByteArray();
    }

    private static String documentXml(String bodyXml) {
        // A4 页面 + 常规页边距：不给 sectPr 时 Word 用默认纸张，写死常见值保证观感一致
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"" + W_NS + "\"><w:body>" + bodyXml
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1440\" w:right=\"1800\" w:bottom=\"1440\" w:left=\"1800\""
                + " w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>";
    }

    /** 段落：pPr 挂样式（null = Normal 默认），文本转义并保留首尾空白（段首全角缩进） */
    private static String paragraph(String styleId, String text) {
        String pPr = styleId == null ? "" : "<w:pPr><w:pStyle w:val=\"" + styleId + "\"/></w:pPr>";
        return "<w:p>" + pPr + "<w:r><w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r></w:p>";
    }

    /** 正文按行拆段：空行跳过（Word 里段距由样式 spacing 管，不靠空段） */
    private static List<String> chapterLines(String content) {
        List<String> lines = new ArrayList<>();
        if (StringUtils.isBlank(content)) {
            return lines;
        }
        for (String line : content.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String contentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>";
    }

    private static String rootRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\""
                + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.xml\"/>"
                + "</Relationships>";
    }

    private static String documentRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\""
                + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\""
                + " Target=\"styles.xml\"/>"
                + "</Relationships>";
    }

    /**
     * 样式表：Normal 默认东亚字体宋体；BookTitle 居中 22pt；Heading1 加粗 16pt 挂 outlineLvl 0
     * （name 用 "heading 1" 对齐 Word 内置样式）
     */
    private static String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:styles xmlns:w=\"" + W_NS + "\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"Times New Roman\" w:eastAsia=\"宋体\" w:hAnsi=\"Times New Roman\"/>"
                + "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/>"
                + "</w:rPr></w:rPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
                + "<w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"BookTitle\">"
                + "<w:name w:val=\"Book Title\"/><w:basedOn w:val=\"Normal\"/>"
                + "<w:pPr><w:jc w:val=\"center\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"44\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\">"
                + "<w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>"
                + "<w:pPr><w:outlineLvl w:val=\"0\"/><w:spacing w:before=\"360\" w:after=\"120\"/></w:pPr>"
                + "<w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr></w:style>"
                + "</w:styles>";
    }

    private static void putEntry(ZipOutputStream zip, String entryPath, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(entryPath));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * XML 转义 + 非法字符防御：&lt; &gt; &amp; " ' 五实体；XML 1.0 不允许的控制字符
     * （0x00-0x1F 除 \t）直接剔除——正文里混进一个控制符就是"文件损坏需恢复"，宁缺勿坏
     */
    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\t') {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
