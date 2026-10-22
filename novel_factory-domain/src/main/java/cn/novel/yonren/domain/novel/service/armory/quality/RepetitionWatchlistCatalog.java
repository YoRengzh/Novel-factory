package cn.novel.yonren.domain.novel.service.armory.quality;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **重复意象盯防清单**：{@code assets/config/repetition-watchlist.txt} 的加载器。
 *
 * <p><b>与 {@link FatiguePatternCatalog} 的分工</b>：那份管**词性套话**（副词/眼神/身体反应，
 * 与题材无关，是文风层面的病）；本表管**题材相关的具体意象**——同一件东西（物件/场景/动作）
 * 被反复用作情绪载体时点名，要求换载体。典型病症：同一个意象每隔几章出现一次，
 * 每次都承担同样的抒情功能（实测「敲钟/金锤/股权」在重生文里被反复用作前世锚点）。
 *
 * <p><b>为什么外置成文件而不是加字段/写死代码</b>：意象是**题材知识**，不是流水线逻辑。
 * 把它做成 {@code ChapterSummaryEntity} 的字段（如 pastLifeRecalled）等于把"重生"这个题材
 * 焊进所有题材共用的领域实体——玄幻/悬疑用不上，还要每章付一次模型标注成本。
 * 外置后：换题材换文件，代码零改动；增删词条即时生效。
 *
 * <p><b>按题材分组</b>：{@code [common]} 对所有题材生效，{@code [urban]}/{@code [fantasy]} 等
 * 按 {@code GenreTypeVO.code} 取用（与 {@code genres/} 素材目录同一套题材标识）。
 * 匹配口径是正文**逐字子串包含**，故词条须写得足够具体（「敲钟」能命中「上市敲钟」，
 * 而「股权」会误伤「股权结构」这类正常叙述）。
 */
@Slf4j
public final class RepetitionWatchlistCatalog {

    private static final String FILE = "assets/config/repetition-watchlist.txt";

    /** 通用组名：对所有题材生效 */
    public static final String GROUP_COMMON = "common";

    /**
     * 清单内容：题材组名 → 该组意象短语。
     * 组名小写；{@link #GROUP_COMMON} 始终参与，其余按题材 code 取用。
     */
    public record Watchlist(Map<String, List<String>> groups) {

        /**
         * 取某题材生效的全部意象：通用组 ∪ 该题材组（去重）。
         * 题材为 null/未配置该组时只返回通用组——**组缺失不是错误**，
         * 大部分题材不会有专属词条，留空即该题材不启用意象盯防（对既有题材零影响）。
         */
        public List<String> forGenre(String genreCode) {
            List<String> merged = new ArrayList<>(groups.getOrDefault(GROUP_COMMON, List.of()));
            if (genreCode != null) {
                for (String phrase : groups.getOrDefault(genreCode.toLowerCase(), List.of())) {
                    if (!merged.contains(phrase)) {
                        merged.add(phrase);
                    }
                }
            }
            return merged;
        }

        /** 全量意象（跨组去重）：供"不知道题材时"的兜底与测试 */
        public List<String> all() {
            List<String> merged = new ArrayList<>();
            for (List<String> phrases : groups.values()) {
                for (String phrase : phrases) {
                    if (!merged.contains(phrase)) {
                        merged.add(phrase);
                    }
                }
            }
            return merged;
        }

        public boolean isEmpty() {
            return all().isEmpty();
        }
    }

    /**
     * 内置兜底：**刻意为空**。
     *
     * <p>与 {@link FatiguePatternCatalog} 的兜底不同——那份兜底是有内容的词表（词性套话与题材无关，
     * 内置一份可保证门禁不完全失效）；本表是题材知识，内置任何内容都等于替用户假设题材。
     * 文件缺失/解析为空时返回空表，等价于"未启用意象盯防"，行为与引入本机制前完全一致。
     */
    private static final Watchlist EMPTY = new Watchlist(Map.of());

    private static volatile Watchlist cached;
    /** 缓存指纹：文件内容哈希，文件改动即时失效（与索引类缓存同约定） */
    private static volatile int cachedHash;

    /**
     * 测试注入的覆盖清单：非 null 时 {@link #get()} 直接返回它，不读文件。
     *
     * <p>为什么需要这个接缝：打包清单按设计**默认为空**（意象是"这本书反复用了什么"的判断，
     * 不预置任何具体作品词条），于是"累计/点名/阈值"这些机制的验证不能依赖打包文件里恰好有词条——
     * 测试需要自己给一份清单。这个接缝只影响测试进程（生产路径恒为 null）。
     */
    private static volatile Watchlist override;

    /** 测试专用：注入覆盖清单（传 null 恢复读文件）。返回旧值，便于用例 finally 还原 */
    public static Watchlist overrideForTest(Watchlist replacement) {
        Watchlist previous = override;
        override = replacement;
        return previous;
    }

    /** 取清单（进程内缓存；文件内容变化时自动重载） */
    public static Watchlist get() {
        Watchlist injected = override;
        if (injected != null) {
            return injected;
        }
        try {
            ClassPathResource resource = new ClassPathResource(FILE);
            if (!resource.exists()) {
                return EMPTY;
            }
            String text;
            try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                text = FileCopyUtils.copyToString(reader);
            }
            int hash = text.hashCode();
            Watchlist current = cached;
            if (current != null && hash == cachedHash) {
                return current;
            }
            synchronized (RepetitionWatchlistCatalog.class) {
                if (cached == null || hash != cachedHash) {
                    cached = parse(text);
                    cachedHash = hash;
                    log.info("重复意象盯防清单已加载：{} 组，共 {} 条",
                            cached.groups().size(), cached.all().size());
                }
                return cached;
            }
        } catch (Exception e) {
            log.warn("重复意象盯防清单 {} 加载失败，本次不启用意象盯防：{}", FILE, e.getMessage());
            return EMPTY;
        }
    }

    /** 解析 txt：# 注释与空行忽略，[组名] 行切换当前组，词条按组归集 */
    public static Watchlist parse(String text) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        String current = GROUP_COMMON;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).trim().toLowerCase();
                groups.computeIfAbsent(current, k -> new ArrayList<>());
                continue;
            }
            groups.computeIfAbsent(current, k -> new ArrayList<>()).add(line);
        }
        // 去掉空组：让 forGenre 的"组缺失"与"组为空"语义一致
        groups.entrySet().removeIf(e -> e.getValue().isEmpty());
        return new Watchlist(groups);
    }

    private RepetitionWatchlistCatalog() {
    }
}
