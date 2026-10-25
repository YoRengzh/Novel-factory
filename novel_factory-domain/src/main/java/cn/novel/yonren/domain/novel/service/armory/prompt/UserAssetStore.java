package cn.novel.yonren.domain.novel.service.armory.prompt;

import cn.novel.yonren.api.dto.StyleViewDTO;
import cn.novel.yonren.api.dto.UserReferenceDTO;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceIndexEntry;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceIndexLoader;
import cn.novel.yonren.types.enums.GenreTypeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 用户自备资产存取（data 目录）：参考资料（data/references）与文风覆盖（data/styles）。
 * 这是把 jar 内"焊死"的资料库变成用户可增补资产唯一入口——
 * 写入后必须逐出 PromptRuleFileLoader 内容缓存并刷新 ReferenceIndexLoader 索引，
 * 否则新内容永远进不了选择器与向量索引。
 *
 * <p>命名约束：参考资料文件名只收小写字母/数字/连字符（它是选择器输出标识与白名单键），
 * 且不得与内置资料同名（同名覆盖是 rules 层语义，资料层保持"加法"——
 * 用户想改内置资料的诉求由文风覆盖承担，资料集保持可预期的 58+新增）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAssetStore {

    /** 文件名（不含扩展名）：小写字母/数字开头，只含小写字母/数字/连字符，≤60 位 */
    private static final String NAME_PATTERN = "[a-z0-9][a-z0-9-]{0,59}";
    /** 单文件上限 10 万字符：参考资料显示注入预算只有数千字，过长只会在截断处浪费 */
    private static final int MAX_CONTENT_CHARS = 100_000;
    /** design-* 前缀被索引加载器保留跳过，用户起了这个名字等于永远不被检索 */
    private static final String DESIGN_PREFIX = "design-";

    private final PromptRuleFileLoader fileLoader;
    private final ReferenceIndexLoader referenceIndexLoader;

    // ===== 参考资料 =====

    /** 用户自备资料列表（目录不存在=空表） */
    public List<UserReferenceDTO> listReferences() {
        List<UserReferenceDTO> result = new ArrayList<>();
        if (!Files.isDirectory(referenceDir())) {
            return result;
        }
        try (var files = Files.list(referenceDir())) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            ReferenceIndexEntry entry = ReferenceIndexLoader.parseEntry(
                                    fileName.substring(0, fileName.length() - 3),
                                    "references/" + fileName, content);
                            result.add(new UserReferenceDTO(entry.getFileName(), entry.getTitle(),
                                    entry.getDigest(), entry.getGroup(),
                                    Files.size(p), p.toFile().lastModified()));
                        } catch (IOException e) {
                            log.warn("用户参考资料列表项读取失败，跳过：{}", fileName, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("用户参考资料目录读取失败：" + e.getMessage(), e);
        }
        result.sort(Comparator.comparing(UserReferenceDTO::getFileName));
        return result;
    }

    /** 读单条用户资料全文；不存在抛 IllegalArgumentException（404） */
    public String readReference(String fileName) {
        Path file = referenceFile(fileName);
        requireUserFile(file, fileName);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("资料读取失败：" + e.getMessage(), e);
        }
    }

    /** 新建/覆盖一条用户资料；校验不过抛 IllegalArgumentException（400） */
    public void writeReference(String fileName, String content) {
        String name = validatedName(fileName);
        if (builtinReferenceExists(name)) {
            throw new IllegalArgumentException("与内置资料同名：" + name
                    + "（内置资料不开放覆盖，请换个名字；想改风格请用「文风」区）");
        }
        String body = validatedContent(content, "参考资料");
        try {
            Files.createDirectories(referenceDir());
            atomicWrite(referenceDir().resolve(name + ".md"), body);
        } catch (IOException e) {
            throw new IllegalStateException("资料写入失败：" + e.getMessage(), e);
        }
        refreshReferenceAssets(name);
    }

    /** 删除一条用户资料；不存在抛 IllegalArgumentException（404） */
    public void deleteReference(String fileName) {
        String name = validatedName(fileName);
        Path file = referenceFile(name);
        requireUserFile(file, name);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("资料删除失败：" + e.getMessage(), e);
        }
        refreshReferenceAssets(name);
    }

    // ===== 文风 =====

    /** 全部题材的文风视图（内置有无 + 覆盖有无） */
    public List<StyleViewDTO> listStyles() {
        List<StyleViewDTO> result = new ArrayList<>();
        for (GenreTypeVO genre : GenreTypeVO.values()) {
            Path override = styleFile(genre.getCode());
            result.add(new StyleViewDTO(genre.getCode(),
                    builtinStyleExists(genre.getCode()),
                    Files.isRegularFile(override),
                    Files.isRegularFile(override) ? override.toFile().lastModified() : 0L));
        }
        return result;
    }

    /**
     * 读文风：返回 [生效内容, 内置原文]。无覆盖时两者相同（内置即生效）；
     * 无内置的题材（如 scifi）两者为 null——写入覆盖即为该题材首次拥有专属文风
     */
    public StyleContent readStyle(String code) {
        String name = validatedGenre(code);
        String override = readIfExists(styleFile(name));
        String builtin = readClasspath("assets/styles/" + name + ".md");
        return new StyleContent(override != null ? override : builtin, override != null, builtin);
    }

    /** 写文风覆盖（data/styles/{code}.md）；题材码必须存在于 GenreTypeVO */
    public void writeStyle(String code, String content) {
        String name = validatedGenre(code);
        String body = validatedContent(content, "文风");
        try {
            Files.createDirectories(styleFile(name).getParent());
            atomicWrite(styleFile(name), body);
        } catch (IOException e) {
            throw new IllegalStateException("文风写入失败：" + e.getMessage(), e);
        }
        fileLoader.evict("styles/" + name + ".md");
    }

    /** 删除文风覆盖（恢复内置/回落 default）；无覆盖抛 IllegalArgumentException（404） */
    public void deleteStyle(String code) {
        String name = validatedGenre(code);
        Path file = styleFile(name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("该题材没有自定义文风：" + name);
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("文风删除失败：" + e.getMessage(), e);
        }
        fileLoader.evict("styles/" + name + ".md");
    }

    /** 文风读取结果：content=当前生效，builtin=内置原文（供对照），overridden=存在用户覆盖 */
    public record StyleContent(String content, boolean overridden, String builtin) {
    }

    // ===== 校验与工具 =====

    private static String validatedName(String fileName) {
        if (fileName == null || !fileName.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("资料名只能用小写字母、数字与连字符（≤60 位）：" + fileName);
        }
        if (fileName.startsWith(DESIGN_PREFIX)) {
            throw new IllegalArgumentException("design- 前缀为保留字（内部设计文档），请换个名字");
        }
        return fileName;
    }

    private static String validatedContent(String content, String label) {
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException(label + "内容不能为空");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(label + "内容超过 " + MAX_CONTENT_CHARS + " 字符上限（当前 "
                    + content.length() + "），请拆分或精简");
        }
        return content;
    }

    private static String validatedGenre(String code) {
        if (code == null) {
            throw new IllegalArgumentException("缺少题材码");
        }
        for (GenreTypeVO genre : GenreTypeVO.values()) {
            if (genre.getCode().equals(code.trim().toLowerCase())) {
                return genre.getCode();
            }
        }
        throw new IllegalArgumentException("未知题材码：" + code + "（须为 GenreTypeVO 之一）");
    }

    private Path referenceDir() {
        return fileLoader.userAssetRoot().resolve("references");
    }

    private Path referenceFile(String fileName) {
        // validatedName 已限定字符集，resolve+normalize 是纵深防御（路径穿越直接判不存在）
        Path resolved = referenceDir().resolve(fileName + ".md").normalize();
        if (!resolved.startsWith(referenceDir())) {
            throw new IllegalArgumentException("非法资料名：" + fileName);
        }
        return resolved;
    }

    private Path styleFile(String code) {
        return fileLoader.userAssetRoot().resolve("styles").resolve(code + ".md");
    }

    private void requireUserFile(Path file, String fileName) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("资料不存在：" + fileName);
        }
    }

    private void refreshReferenceAssets(String name) {
        fileLoader.evict("references/" + name + ".md");
        referenceIndexLoader.refresh();
    }

    private static boolean builtinReferenceExists(String name) {
        return new ClassPathResource("assets/references/" + name + ".md").exists();
    }

    private static boolean builtinStyleExists(String code) {
        return new ClassPathResource("assets/styles/" + code + ".md").exists();
    }

    private static String readClasspath(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readIfExists(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** 落盘走 临时文件+原子移动：写一半断电/杀进程不会留下截断的资料文件 */
    private static void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 个别文件系统不支持原子移动：退化为普通替换，语义不变
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
