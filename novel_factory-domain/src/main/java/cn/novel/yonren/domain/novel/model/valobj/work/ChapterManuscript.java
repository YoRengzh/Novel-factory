package cn.novel.yonren.domain.novel.model.valobj.work;

import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.types.utils.ChapterTitleNormalizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 不可变稿件：标题与正文按落盘回读口径归一，使内存对象与磁盘文件可以逐字节比较。
 *
 * <p>哈希覆盖章节号、标题与正文三者，作为提交日志、验收记录与正式历史对账的同一身份凭据。
 * 只比较正文会让"标题被改过"的稿件冒充同一版本。
 */
public record ChapterManuscript(int chapterNo, String title, String content) {

    public ChapterManuscript {
        title = ChapterTitleNormalizer.stripDuplicatePrefix(
                Objects.toString(title, "").strip().replaceAll("\\R", " "), chapterNo);
        content = Objects.toString(content, "").replace("\\n", "\n").stripLeading();
    }

    public static ChapterManuscript of(ChapterContentEntity chapter) {
        return new ChapterManuscript(chapter.getChapterNo(), chapter.getTitle(), chapter.getContent());
    }

    public ChapterContentEntity toChapter() {
        return new ChapterContentEntity(chapterNo, title, content);
    }

    public String hash() {
        return digest(chapterNo + "\n" + title + "\n" + content);
    }

    /** SHA-256 十六进制摘要；对任意文本稳定，供依据指纹与日志完整性校验复用。 */
    public static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
