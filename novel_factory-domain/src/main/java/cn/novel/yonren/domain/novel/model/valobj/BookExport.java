package cn.novel.yonren.domain.novel.model.valobj;

import cn.novel.yonren.domain.novel.service.export.BookFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 导出成书结果：zipBytes/fileBytes 为分章 ZIP 归档字节（每章一个独立文件，
 * 解压即「书名文件夹 + 分章文件」），其余字段供书名页渲染与附件响应头使用。
 *
 * <p>纯只读产物——不含任何章型/伏笔状态/计划字段等元信息：元信息物理隔离在 memory/、
 * record/ 目录，导出只读 chapters/，存储分层天然保证，无需清洗逻辑。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookExport {

    /** 附件文件名（已清洗非法字符并截断），如「雾港拾骨-分章TXT-65章-20261011.zip」（三种格式均为 .zip） */
    private String fileName;

    /** 分章 ZIP 归档字节（响应正文，三种格式同形态） */
    private byte[] fileBytes;

    /** 导出格式（决定响应形态与包内章文件版式） */
    private BookFormat format;

    /** 收入的章数 */
    private int chapterCount;

    /** 全书有效字数（各章 ChapterLengthPolicy.effectiveCharacterCount 之和，书名页展示用） */
    private long effectiveChars;

    /** 书名（bible 缺失/不可读时回退故事目录名） */
    private String novelTitle;
}
