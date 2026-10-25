package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 参考资料索引加载器：扫描 assets/references 下的 md 文件（单层，不含 genres 子目录），
 * 以及 data/references 下的用户自备资料（设置页「我的参考资料」维护），
 * 提取每个文件的开头关键要素（一级标题 + 首段简介）构建轻量索引。
 * 对应 skill 机制的第一层——只注入索引供 LLM 判断，全文按需加载。
 * 同名时用户文件覆盖内置条目（索引与全文加载同口径，见 PromptRuleFileLoader）
 */
@Slf4j
@Component
public class ReferenceIndexLoader {

    private static final String SCAN_PATTERN = "classpath*:assets/references/*.md";
    private static final int DIGEST_MAX_LENGTH = 60;
    /** design-* 是 ainovel-cli/Go 方案文档，不属于 Java 写作运行时资料。 */
    private static final String DESIGN_PREFIX = "design-";
    /** 用户自备资料目录（相对进程工作目录），与 PromptRuleFileLoader 用户根同源 */
    static final Path USER_REFERENCES_DIR = Path.of("data", "references");

    private volatile List<ReferenceIndexEntry> cachedIndex;

    /** 可注入的用户资料目录（跨包测试隔离用；生产恒为 USER_REFERENCES_DIR） */
    private Path userReferencesDir = USER_REFERENCES_DIR;

    public void overrideUserReferencesDirForTest(Path dir) {
        this.userReferencesDir = dir;
    }

    /**
     * 获取全部参考资料索引（进程级缓存，按文件名排序保证稳定）
     */
    public List<ReferenceIndexEntry> getIndex() {
        List<ReferenceIndexEntry> index = cachedIndex;
        if (index == null) {
            synchronized (this) {
                if (cachedIndex == null) {
                    cachedIndex = scan();
                }
                index = cachedIndex;
            }
        }
        return index;
    }

    /**
     * 逐出索引缓存：用户资料增删改后由 UserAssetStore 调用。
     * DynamicReferenceService 的选择缓存键含索引 hashCode，索引一变旧选择自然失效
     */
    public void refresh() {
        cachedIndex = null;
    }

    private List<ReferenceIndexEntry> scan() {
        Map<String, ReferenceIndexEntry> byFileName = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(SCAN_PATTERN);
            for (Resource resource : resources) {
                try {
                    String fileName = resource.getFilename();
                    if (fileName == null || !fileName.endsWith(".md")) {
                        continue;
                    }
                    String content;
                    try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                        content = FileCopyUtils.copyToString(reader);
                    }
                    String name = fileName.substring(0, fileName.length() - 3);
                    if (name.startsWith(DESIGN_PREFIX)) {
                        log.info("跳过非运行时设计资料：{}", fileName);
                        continue;
                    }
                    byFileName.put(fileName, parseEntry(name, "references/" + fileName, content));
                } catch (IOException e) {
                    log.warn("参考资料读取失败，已跳过：{}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.warn("参考资料目录扫描失败，索引为空", e);
        }
        int builtinCount = byFileName.size();
        scanUserDir(byFileName);
        List<ReferenceIndexEntry> entries = new ArrayList<>(byFileName.values());
        entries.sort(Comparator.comparing(ReferenceIndexEntry::getFileName));
        if (entries.size() > builtinCount) {
            log.info("参考资料索引构建完成，共 {} 份（其中用户自备 {} 份）",
                    entries.size(), entries.size() - builtinCount);
        } else {
            log.info("参考资料索引构建完成，共 {} 份", entries.size());
        }
        return entries;
    }

    /** 扫描用户自备资料目录；同名覆盖内置（文件名为对外标识，覆盖即替换） */
    private void scanUserDir(Map<String, ReferenceIndexEntry> byFileName) {
        if (!Files.isDirectory(userReferencesDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(userReferencesDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".md") && !n.startsWith(DESIGN_PREFIX);
                    })
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            String name = fileName.substring(0, fileName.length() - 3);
                            byFileName.put(fileName, parseEntry(name, "references/" + fileName, content));
                        } catch (IOException e) {
                            log.warn("用户参考资料读取失败，已跳过：{}", fileName, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("用户参考资料目录扫描失败，忽略：{}", e.getMessage());
        }
    }

    /**
     * 解析文件头部：第一个一级标题为 title；其后首个非空、非标题行起的连续段落为 digest。
     * 供 UserAssetStore 复用（列表视图与索引同口径）
     */
    public static ReferenceIndexEntry parseEntry(String fileName, String relativePath, String content) {
        String title = fileName;
        String digest = null;
        String[] lines = content.split("\n");
        int titleLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("# ")) {
                title = line.substring(2).trim();
                titleLine = i;
                break;
            }
        }
        if (titleLine >= 0) {
            StringBuilder digestBuilder = new StringBuilder();
            for (int i = titleLine + 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    if (digestBuilder.length() > 0) {
                        break;
                    }
                    continue;
                }
                if (line.startsWith("#")) {
                    // 标题后直接出现子标题，说明无简介段落（纯模板文件）
                    break;
                }
                if (digestBuilder.length() > 0) {
                    digestBuilder.append(" ");
                }
                digestBuilder.append(line);
                if (digestBuilder.length() >= DIGEST_MAX_LENGTH) {
                    break;
                }
            }
            if (digestBuilder.length() > 0) {
                String text = digestBuilder.toString();
                digest = text.length() > DIGEST_MAX_LENGTH ? text.substring(0, DIGEST_MAX_LENGTH) + "…" : text;
            }
        }
        return new ReferenceIndexEntry(fileName, title, digest, relativePath,
                ReferenceGroup.groupOf(fileName));
    }

}
