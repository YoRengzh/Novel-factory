package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import cn.novel.yonren.domain.novel.model.valobj.prompt.ReferenceIndexEntry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private volatile Corpus cachedCorpus;
    private volatile List<ReferenceIndexEntry> cachedIndex;
    private long cachedPolicyRevision = -1L;
    private volatile ReferenceSourcePolicy sourcePolicy = new ReferenceSourcePolicy();

    /** 可注入的用户资料目录（跨包测试隔离用；生产恒为 USER_REFERENCES_DIR） */
    private Path userReferencesDir = USER_REFERENCES_DIR;

    public synchronized void overrideUserReferencesDirForTest(Path dir) {
        this.userReferencesDir = Objects.requireNonNull(dir, "user reference path");
        refresh();
    }

    /** Spring 与管理服务共享同一策略；直接 new 的旧调用方仍保留默认策略。 */
    @Autowired
    public synchronized void setSourcePolicy(ReferenceSourcePolicy policy) {
        this.sourcePolicy = Objects.requireNonNull(policy, "reference source policy");
        refresh();
    }

    public ReferenceSourcePolicy sourcePolicy() {
        return sourcePolicy;
    }

    /** 仅返回已启用来源，按文件名排序的不可修改快照。 */
    public synchronized List<ReferenceIndexEntry> getIndex() {
        Corpus corpus = corpus();
        long revision = sourcePolicy.revision();
        if (cachedIndex == null || cachedPolicyRevision != revision) {
            cachedIndex = List.copyOf(corpus.entries().stream()
                    .filter(entry -> sourcePolicy.isEnabled(entry.getFileName())).toList());
            cachedPolicyRevision = revision;
        }
        return cachedIndex;
    }

    /** 当前存在的全部来源（含停用）；已删除文件不列入，但策略保留其停用记录。 */
    public List<ReferenceIndexEntry> getAllIndex() {
        return corpus().entries();
    }

    /** 完整有效原文 SHA-256 + 持久化启停 revision，正文不改标题也会改变版本。 */
    public synchronized String corpusVersion() {
        return corpus().fullTextHash() + "-r" + sourcePolicy.revision();
    }

    /** 资料增删改后同时逐出轻量目录和全文语料哈希，不修改启停状态。 */
    public synchronized void refresh() {
        cachedCorpus = null;
        cachedIndex = null;
        cachedPolicyRevision = -1L;
    }

    private Corpus corpus() {
        Corpus corpus = cachedCorpus;
        if (corpus == null) {
            synchronized (this) {
                if (cachedCorpus == null) {
                    cachedCorpus = scan();
                }
                corpus = cachedCorpus;
            }
        }
        return corpus;
    }

    private Corpus scan() {
        Map<String, Source> byFileName = new LinkedHashMap<>();
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
                    byFileName.put(fileName, new Source(
                            snapshotEntry(parseEntry(name, "references/" + fileName, content)), content));
                } catch (IOException e) {
                    log.warn("参考资料读取失败，已跳过：{}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.warn("参考资料目录扫描失败，索引为空", e);
        }
        int builtinCount = byFileName.size();
        scanUserDir(byFileName);
        List<Source> sources = new ArrayList<>(byFileName.values());
        sources.sort(Comparator.comparing(source -> source.entry().getFileName()));
        List<ReferenceIndexEntry> entries = List.copyOf(sources.stream().map(Source::entry).toList());
        if (entries.size() > builtinCount) {
            log.info("参考资料索引构建完成，共 {} 份（其中用户自备 {} 份）",
                    entries.size(), entries.size() - builtinCount);
        } else {
            log.info("参考资料索引构建完成，共 {} 份", entries.size());
        }
        return new Corpus(entries, fullTextHash(sources));
    }

    /** 扫描用户自备资料目录；同名覆盖内置（文件名为对外标识，覆盖即替换） */
    private void scanUserDir(Map<String, Source> byFileName) {
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
                            byFileName.put(fileName, new Source(
                            snapshotEntry(parseEntry(name, "references/" + fileName, content)), content));
                        } catch (IOException e) {
                            log.warn("用户参考资料读取失败，已跳过：{}", fileName, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("用户参考资料目录扫描失败，忽略：{}", e.getMessage());
        }
    }

    private static String fullTextHash(List<Source> sources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Source source : sources) {
                hashField(digest, source.entry().getFileName());
                hashField(digest, source.entry().getRelativePath());
                hashField(digest, source.content());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("参考资料全文摘要计算失败", e);
        }
    }

    private static void hashField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static ReferenceIndexEntry snapshotEntry(ReferenceIndexEntry entry) {
        return new ImmutableEntry(entry);
    }

    private record Source(ReferenceIndexEntry entry, String content) {
    }

    private record Corpus(List<ReferenceIndexEntry> entries, String fullTextHash) {
    }

    /** 保持 ReferenceIndexEntry API 兼容，同时防止条目 setter 污染共享快照。 */
    private static final class ImmutableEntry extends ReferenceIndexEntry {
        private ImmutableEntry(ReferenceIndexEntry entry) {
            super(entry.getFileName(), entry.getTitle(), entry.getDigest(), entry.getRelativePath(), entry.getGroup());
        }

        @Override
        public void setFileName(String value) {
            throw new UnsupportedOperationException("参考资料索引快照不可修改");
        }

        @Override
        public void setTitle(String value) {
            throw new UnsupportedOperationException("参考资料索引快照不可修改");
        }

        @Override
        public void setDigest(String value) {
            throw new UnsupportedOperationException("参考资料索引快照不可修改");
        }

        @Override
        public void setRelativePath(String value) {
            throw new UnsupportedOperationException("参考资料索引快照不可修改");
        }

        @Override
        public void setGroup(String value) {
            throw new UnsupportedOperationException("参考资料索引快照不可修改");
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
