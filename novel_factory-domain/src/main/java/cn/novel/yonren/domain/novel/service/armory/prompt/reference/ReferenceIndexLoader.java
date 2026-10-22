package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 参考资料索引加载器：扫描 assets/references 下的 md 文件（单层，不含 genres 子目录），
 * 提取每个文件的开头关键要素（一级标题 + 首段简介）构建轻量索引。
 * 对应 skill 机制的第一层——只注入索引供 LLM 判断，全文按需加载
 */
@Slf4j
@Component
public class ReferenceIndexLoader {

    private static final String SCAN_PATTERN = "classpath*:assets/references/*.md";
    private static final int DIGEST_MAX_LENGTH = 60;
    /** design-* 是 ainovel-cli/Go 方案文档，不属于 Java 写作运行时资料。 */
    private static final String DESIGN_PREFIX = "design-";

    private volatile List<ReferenceIndexEntry> cachedIndex;

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

    private List<ReferenceIndexEntry> scan() {
        List<ReferenceIndexEntry> entries = new ArrayList<>();
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
                    entries.add(parse(name, "references/" + fileName, content));
                } catch (IOException e) {
                    log.warn("参考资料读取失败，已跳过：{}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.warn("参考资料目录扫描失败，索引为空", e);
        }
        entries.sort(Comparator.comparing(ReferenceIndexEntry::getFileName));
        log.info("参考资料索引构建完成，共 {} 份", entries.size());
        return entries;
    }

    /**
     * 解析文件头部：第一个一级标题为 title；其后首个非空、非标题行起的连续段落为 digest
     */
    private ReferenceIndexEntry parse(String fileName, String relativePath, String content) {
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
