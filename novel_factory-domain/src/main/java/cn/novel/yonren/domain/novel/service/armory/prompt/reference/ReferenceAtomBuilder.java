package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注入原子构建器。默认完整 H2，长度不是语义独立性的证据。
 * 只有策略审核允许且存在安全 H3 边界时才细分；父前文作为必需依赖保留。
 * 无安全边界时保留长块，由预算层整包取舍，绝不截断原文。
 * 策略中的标题必须精确匹配，缺失、歧义或依赖环均阻止构建。
 */
@Component
public class ReferenceAtomBuilder {

    private static final String POLICY_RESOURCE = "assets/reference-block-policy.json";
    private volatile Policy policy;

    public ReferenceAtomBuilder() {
    }

    /** 包内测试入口：用与生产相同的解析和验证路径检查策略错误。 */
    ReferenceAtomBuilder(String policyJson) {
        policy = parsePolicy(policyJson);
    }

    /** 显式版本 + 策略原文哈希；策略变动也必须触发索引快照失效。 */
    public String policyVersion() {
        return policy().version;
    }

    public List<ReferenceAtom> build(String docId, String title, String group, String markdown,
                                     ReferenceChunker chunker) {
        Policy current = policy();
        DocRule rule = current.docs.get(docId);
        if (StringUtils.isBlank(markdown)) {
            if (rule != null) {
                throw invalid(docId, "策略引用的文档为空");
            }
            return List.of();
        }
        Map<String, Integer> occurrences = new HashMap<>();
        if (rule != null && rule.whole) {
            return List.of(atom(docId, title, "全文", 0, markdown.trim(), group, List.of(), true, occurrences));
        }

        List<ReferenceChunker.Section> sections = chunker.sections(markdown);
        validateSectionRules(docId, sections, rule);
        if (sections.isEmpty()) {
            List<ReferenceAtom> whole = new ArrayList<>(List.of(atom(docId, title, "全文", 0,
                    markdown.trim(), group, List.of(), true, occurrences)));
            applyDependencies(docId, whole, rule);
            return whole;
        }

        List<ReferenceAtom> atoms = new ArrayList<>();
        String front = chunker.frontMatter(markdown);
        List<String> frontDependency = List.of();
        if (StringUtils.isNotBlank(front)) {
            ReferenceAtom intro = atom(docId, title, "导言", atoms.size(), front.trim(), group,
                    List.of(), false, occurrences);
            atoms.add(intro);
            frontDependency = List.of(intro.atomId());
        }
        for (ReferenceChunker.Section section : sections) {
            String path = section.title();
            List<ReferenceChunker.Section> sub = splitByH3IfAllowed(section, rule, chunker);
            if (sub.isEmpty()) {
                atoms.add(atom(docId, title, path, atoms.size(), section.content().trim(), group,
                        frontDependency, true, occurrences));
                continue;
            }
            List<String> prerequisite = frontDependency;
            for (ReferenceChunker.Section h3 : sub) {
                if (h3.title() == null) {
                    if (StringUtils.isNotBlank(h3.body())) {
                        ReferenceAtom parent = atom(docId, title, path, atoms.size(),
                                (section.heading() + h3.body()).trim(), group, frontDependency, false, occurrences);
                        atoms.add(parent);
                        prerequisite = List.of(parent.atomId());
                    }
                    continue;
                }
                String subPath = path + " › " + h3.title();
                atoms.add(atom(docId, title, subPath, atoms.size(),
                        (section.heading() + h3.content()).trim(), group, prerequisite, true, occurrences));
            }
        }
        applyDependencies(docId, atoms, rule);
        Map<String, ReferenceAtom> byId = new LinkedHashMap<>();
        for (ReferenceAtom atom : atoms) {
            if (byId.putIfAbsent(atom.atomId(), atom) != null) {
                throw invalid(docId, "原子 ID 冲突：" + atom.atomId());
            }
        }
        List<String> problems = validateClosure(byId, Set.of(docId));
        if (!problems.isEmpty()) {
            throw invalid(docId, String.join("；", problems));
        }
        return List.copyOf(atoms);
    }

    private List<ReferenceChunker.Section> splitByH3IfAllowed(ReferenceChunker.Section section,
                                                              DocRule rule, ReferenceChunker chunker) {
        // 未经人工审核的长小节不自动拆；字数和子节数量不能证明可独立阅读。
        if (rule == null || !rule.splitTitles.contains(section.title())
                || rule.noSplitTitles.contains(section.title()) || looksLikePaired(section.body())
                || chunker.hasUnclosedFence(section.body())) {
            return List.of();
        }
        List<ReferenceChunker.Section> sub = chunker.h3Sections(section.body());
        List<ReferenceChunker.Section> named = sub.stream().filter(s -> s.title() != null).toList();
        // 不把空标题/空子节变为独立候选；解析保证边界不在围栏/表格内。
        if (named.size() < 2 || named.stream().anyMatch(s -> s.title().isBlank() || s.body().isBlank())) {
            return List.of();
        }
        return sub;
    }

    private static boolean looksLikePaired(String body) {
        return (body.contains("错误") && body.contains("正确"))
                || (body.contains("反例") && body.contains("正例"));
    }

    private void validateSectionRules(String docId, List<ReferenceChunker.Section> sections, DocRule rule) {
        if (rule == null) {
            return;
        }
        Set<String> titles = new HashSet<>();
        for (ReferenceChunker.Section section : sections) {
            titles.add(section.title());
        }
        for (String title : rule.splitTitles) {
            if (!titles.contains(title)) {
                throw invalid(docId, "splitTitles 标题不存在：" + title);
            }
        }
        for (String title : rule.noSplitTitles) {
            if (!titles.contains(title)) {
                throw invalid(docId, "noSplitTitles 标题不存在：" + title);
            }
        }
    }

    private ReferenceAtom atom(String docId, String docTitle, String path, int index, String content,
                               String group, List<String> requires, boolean standalone,
                               Map<String, Integer> occurrences) {
        int localOrdinal = occurrences.merge(path, 1, Integer::sum) - 1;
        // 原文序号仅用于展示排序，不参与身份；插入无关章节不会改变已有后节的 ID。
        String atomId = docId + "#" + hash(docId + "\u0000" + path + "\u0000" + localOrdinal);
        return new ReferenceAtom(docId, atomId, docTitle, path, index, content, hash(content), group,
                requires, standalone);
    }

    private void applyDependencies(String docId, List<ReferenceAtom> atoms, DocRule rule) {
        if (rule == null || rule.requiresByHeading.isEmpty()) {
            return;
        }
        Map<String, List<ReferenceAtom>> byPath = new LinkedHashMap<>();
        for (ReferenceAtom atom : atoms) {
            byPath.computeIfAbsent(atom.headingPath(), ignored -> new ArrayList<>()).add(atom);
        }
        Map<String, List<String>> dependencies = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : rule.requiresByHeading.entrySet()) {
            ReferenceAtom source = exactHeading(docId, byPath, entry.getKey());
            LinkedHashSet<String> required = new LinkedHashSet<>(source.requires());
            for (String heading : entry.getValue()) {
                ReferenceAtom target = exactHeading(docId, byPath, heading);
                if (source.atomId().equals(target.atomId())) {
                    throw invalid(docId, "自依赖：" + entry.getKey());
                }
                required.add(target.atomId());
            }
            dependencies.put(source.atomId(), List.copyOf(required));
        }
        for (int i = 0; i < atoms.size(); i++) {
            ReferenceAtom atom = atoms.get(i);
            List<String> required = dependencies.get(atom.atomId());
            if (required != null) {
                atoms.set(i, atom.withRequires(required));
            }
        }
    }

    private static ReferenceAtom exactHeading(String docId, Map<String, List<ReferenceAtom>> byPath, String path) {
        List<ReferenceAtom> matches = byPath.get(path);
        if (matches == null || matches.isEmpty()) {
            throw invalid(docId, "requires 标题不存在：" + path);
        }
        if (matches.size() != 1) {
            throw invalid(docId, "requires 标题有歧义：" + path);
        }
        return matches.get(0);
    }

    private Policy policy() {
        Policy current = policy;
        if (current == null) {
            synchronized (this) {
                if (policy == null) {
                    policy = loadPolicy();
                }
                current = policy;
            }
        }
        return current;
    }

    private Policy loadPolicy() {
        try (InputStreamReader reader = new InputStreamReader(
                new ClassPathResource(POLICY_RESOURCE).getInputStream(), StandardCharsets.UTF_8)) {
            return parsePolicy(FileCopyUtils.copyToString(reader));
        } catch (Exception e) {
            throw new IllegalStateException("资料块策略加载失败：" + POLICY_RESOURCE, e);
        }
    }

    private static Policy parsePolicy(String content) {
        try {
            JSONObject root = JSON.parseObject(content);
            if (root == null || !(root.get("version") instanceof String version) || version.isBlank()
                    || !(root.get("docs") instanceof JSONObject docsNode)) {
                throw new IllegalArgumentException("策略必须包含非空 version 和 docs 对象");
            }
            checkKeys(root, Set.of("version", "docs"));
            Map<String, DocRule> docs = new LinkedHashMap<>();
            for (String docId : docsNode.keySet()) {
                if (docId.isBlank() || !(docsNode.get(docId) instanceof JSONObject node)) {
                    throw new IllegalArgumentException("非法文档策略：" + docId);
                }
                checkKeys(node, Set.of("whole", "splitTitles", "noSplitTitles", "requires"));
                if (node.containsKey("whole") && !(node.get("whole") instanceof Boolean)) {
                    throw invalid(docId, "whole 必须为布尔值");
                }
                boolean whole = node.getBooleanValue("whole");
                List<String> split = strings(node.get("splitTitles"));
                List<String> noSplit = strings(node.get("noSplitTitles"));
                Map<String, List<String>> requires = requiresMap(node.get("requires"));
                if (split.stream().anyMatch(noSplit::contains)) {
                    throw invalid(docId, "拆分与不拆分标题冲突");
                }
                if (whole && (!split.isEmpty() || !noSplit.isEmpty() || !requires.isEmpty())) {
                    throw invalid(docId, "whole 不允许混用小节策略");
                }
                docs.put(docId, new DocRule(whole, split, noSplit, requires));
            }
            return new Policy(version + "-" + hash(content), Map.copyOf(docs));
        } catch (RuntimeException e) {
            throw new IllegalStateException("资料块策略解析失败", e);
        }
    }

    private static void checkKeys(JSONObject node, Set<String> allowed) {
        for (String key : node.keySet()) {
            if (!key.startsWith("_") && !allowed.contains(key)) {
                throw new IllegalArgumentException("未知策略字段：" + key);
            }
        }
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof JSONArray array)) {
            throw new IllegalArgumentException("标题配置必须为字符串数组");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object item : array) {
            if (!(item instanceof String text) || text.isBlank() || !text.equals(text.trim()) || !out.add(text)) {
                throw new IllegalArgumentException("标题必须为非空、无首尾空白且不重复的精确字符串：" + item);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, List<String>> requiresMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof JSONObject node)) {
            throw new IllegalArgumentException("requires 必须为对象");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String key : node.keySet()) {
            if (key.isBlank() || !key.equals(key.trim())) {
                throw new IllegalArgumentException("非法依赖来源标题");
            }
            List<String> targets = strings(node.get(key));
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("requires 目标不能为空：" + key);
            }
            out.put(key, targets);
        }
        return Map.copyOf(out);
    }

    static String hash(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 目标存在、所有来源可见、键与实际 ID 一致、依赖无环；空表表示合法。 */
    public List<String> validateClosure(Map<String, ReferenceAtom> atomsById, Set<String> allowedDocIds) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, ReferenceAtom> entry : atomsById.entrySet()) {
            ReferenceAtom atom = entry.getValue();
            if (!entry.getKey().equals(atom.atomId())) {
                problems.add("原子键与 ID 不一致：" + entry.getKey());
            }
            if (allowedDocIds != null && !allowedDocIds.contains(atom.docId())) {
                problems.add(atom.atomId() + " 来自不可见来源 " + atom.docId());
            }
            for (String dependency : atom.requires()) {
                ReferenceAtom target = atomsById.get(dependency);
                if (target == null) {
                    problems.add(atom.atomId() + " 依赖不存在的 " + dependency);
                } else if (allowedDocIds != null && !allowedDocIds.contains(target.docId())) {
                    problems.add(atom.atomId() + " 依赖了不可见来源 " + target.docId());
                }
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String id : atomsById.keySet()) {
            detectCycles(id, atomsById, visited, visiting, problems);
        }
        return List.copyOf(problems);
    }

    private void detectCycles(String id, Map<String, ReferenceAtom> atoms, Set<String> visited,
                              Set<String> visiting, List<String> problems) {
        if (visiting.contains(id)) {
            problems.add(id + " 处于依赖环中");
            return;
        }
        if (!visited.add(id)) {
            return;
        }
        ReferenceAtom atom = atoms.get(id);
        if (atom == null) {
            return;
        }
        visiting.add(id);
        for (String dependency : atom.requires()) {
            detectCycles(dependency, atoms, visited, visiting, problems);
        }
        visiting.remove(id);
    }

    private static IllegalStateException invalid(String docId, String problem) {
        return new IllegalStateException("资料策略错误 [" + docId + "]：" + problem);
    }

    private record DocRule(boolean whole, List<String> splitTitles, List<String> noSplitTitles,
                           Map<String, List<String>> requiresByHeading) {
    }

    private record Policy(String version, Map<String, DocRule> docs) {
    }
}
