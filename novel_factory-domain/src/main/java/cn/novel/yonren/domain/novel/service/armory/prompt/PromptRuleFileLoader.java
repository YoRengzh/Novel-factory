package cn.novel.yonren.domain.novel.service.armory.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则文件读取工具：classpath 加载 assets 下的 md 文件，静态缓存。
 * 参考资料（references/）与文风（styles/）支持 data 目录运行时覆盖——
 * 用户在设置页"我的资料/文风"里维护的文件优先生效（jar 内版本仍作兜底）。
 * rules 不开放覆盖：其二级标题锚点是程序切片接口，改名会让作业直接终止
 */
@Slf4j
@Component
public class PromptRuleFileLoader {

    private static final String ASSETS_BASE = "assets/";

    /** 用户覆盖根（相对进程工作目录，与 data/llm-config-override.json 同层） */
    static final Path USER_ASSET_ROOT = Path.of("data");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 可注入的用户覆盖根（同包测试隔离用；生产恒为 USER_ASSET_ROOT） */
    private Path userAssetRoot = USER_ASSET_ROOT;

    void overrideUserAssetRootForTest(Path root) {
        this.userAssetRoot = root;
    }

    /** 用户覆盖根（UserAssetStore 据此定位 data/references 与 data/styles） */
    public Path userAssetRoot() {
        return userAssetRoot;
    }

    /**
     * 读取资产：可覆盖路径先查 data 目录（覆盖语义），否则回退 classpath；缺失返回 null（调用方跳过）
     */
    public String load(String relativePath) {
        return cache.computeIfAbsent(relativePath, this::loadUncached);
    }

    private String loadUncached(String relativePath) {
        if (isUserOverridable(relativePath)) {
            Path userFile = resolveUserFile(relativePath);
            if (userFile != null) {
                try {
                    return Files.readString(userFile, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("用户资产读取失败，回退内置版本：{}（{}）", relativePath, e.getMessage());
                }
            }
        }
        try {
            ClassPathResource resource = new ClassPathResource(ASSETS_BASE + relativePath);
            if (!resource.exists()) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                return FileCopyUtils.copyToString(reader);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 逐出单条缓存：用户资产写入/删除后由 UserAssetStore 调用，否则新内容永远不生效
     */
    public void evict(String relativePath) {
        cache.remove(relativePath);
    }

    /** 逐出全部缓存（整批变更/测试用） */
    public void evictAll() {
        cache.clear();
    }

    private static boolean isUserOverridable(String relativePath) {
        return relativePath != null
                && (relativePath.startsWith("references/") || relativePath.startsWith("styles/"));
    }

    /** 解析用户覆盖文件路径；含路径穿越（../）或不存在时返回 null */
    private Path resolveUserFile(String relativePath) {
        Path resolved = userAssetRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(userAssetRoot) || !Files.isRegularFile(resolved)) {
            return null;
        }
        return resolved;
    }

    /**
     * 批量读取；任意文件缺失则整体返回 null（调用方跳过该规则）
     */
    public String loadAll(String... relativePaths) {
        List<String> contents = new ArrayList<>();
        for (String path : relativePaths) {
            String content = load(path);
            if (content == null) {
                return null;
            }
            contents.add(content);
        }
        return String.join("\n\n---\n\n", contents);
    }

    public String loadSections(String relativePath, String... headingPrefixes) {
        String content = load(relativePath);
        if (content == null || headingPrefixes == null || headingPrefixes.length == 0) {
            return null;
        }
        // 按二级标题切成「标题行 + 正文」块；split 的下标 0 是文件头（一级标题与引言），丢弃
        String[] blocks = content.split("(?m)^(?=## )");
        List<String> picked = new ArrayList<>();
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            for (String prefix : headingPrefixes) {
                if (block.startsWith(prefix)) {
                    picked.add(block.stripTrailing());
                    break;
                }
            }
        }
        return picked.isEmpty() ? null : String.join("\n\n", picked);
    }

}
