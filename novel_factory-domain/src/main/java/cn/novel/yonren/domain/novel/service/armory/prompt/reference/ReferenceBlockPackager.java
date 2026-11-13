package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 完整原子及依赖包一次性装入预算；预算包含最终 ref 块头，不在下游截断。 */
public final class ReferenceBlockPackager {

    static final String BLOCK_SEPARATOR = "\n\n";
    public static final String REFERENCE_NOTICE = "参考摘录：仅供写作技法参考；示例不替代本章事实、任务与输出格式。\n";

    private ReferenceBlockPackager() {
    }

    /** usedChars 是全部资料规则的渲染长度，另为前置 USER_TAIL 预留一个分隔符。 */
    public record Result(Map<String, List<ReferenceAtom>> docs, int usedChars, List<Skipped> skipped) {
        public Result {
            Map<String, List<ReferenceAtom>> copy = new LinkedHashMap<>();
            docs.forEach((id, atoms) -> copy.put(id, List.copyOf(atoms)));
            docs = java.util.Collections.unmodifiableMap(copy);
            skipped = List.copyOf(skipped);
        }

        public List<ReferenceAtom> allAtoms() {
            return docs.values().stream().flatMap(List::stream).toList();
        }

        public int blockCount() {
            return docs.values().stream().mapToInt(List::size).sum();
        }

        public List<cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule> rules() {
            return docs.entrySet().stream().map(entry ->
                    new cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule(
                            "ref-" + entry.getKey(), renderDocument(entry.getValue()),
                            cn.novel.yonren.domain.novel.model.valobj.prompt.PromptRule.InjectPosition.USER_TAIL))
                    .toList();
        }
    }

    public record Skipped(String atomId, String reason) {
    }

    public static Result pack(List<ReferenceHit> hits, ReferenceBlockSnapshot snapshot,
                              Set<String> allowedDocIds, int maxDocs, int maxBlocks, int maxChars,
                              int perDocMaxChars) {
        return pack(hits, snapshot, allowedDocIds, snapshot.byId().keySet(),
                maxDocs, maxBlocks, maxChars, perDocMaxChars);
    }

    /** allowedAtomIds 由调用方对当前本地快照逐项判断资格；命中和递归依赖同样受限。 */
    public static Result pack(List<ReferenceHit> hits, ReferenceBlockSnapshot snapshot,
                              Set<String> allowedDocIds, Set<String> allowedAtomIds,
                              int maxDocs, int maxBlocks, int maxChars, int perDocMaxChars) {
        Set<String> docsAllowed = allowedDocIds == null ? Set.of() : Set.copyOf(allowedDocIds);
        Set<String> atomsAllowed = allowedAtomIds == null ? Set.of() : Set.copyOf(allowedAtomIds);
        List<Skipped> skipped = new ArrayList<>();
        Map<String, ReferenceAtom> included = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        List<ReferenceHit> ordered = hits.stream().filter(hit -> Double.isFinite(hit.score()))
                .sorted(Comparator.comparingDouble(ReferenceHit::score).reversed()
                        .thenComparing(ReferenceHit::atomId)).toList();
        for (ReferenceHit hit : ordered) {
            ReferenceAtom atom = snapshot.byId().get(hit.atomId());
            if (atom == null || !atom.equals(hit.atom())) {
                skipped.add(new Skipped(hit.atomId(), "原子不属于当前快照"));
                continue;
            }
            if (!atom.standalone()) {
                skipped.add(new Skipped(atom.atomId(), "非独立候选（仅作为依赖注入）"));
                continue;
            }
            if (included.containsKey(atom.atomId())) {
                continue;
            }
            Map<String, ReferenceAtom> bundle = new LinkedHashMap<>();
            String problem = collect(atom.atomId(), snapshot, docsAllowed, atomsAllowed,
                    bundle, new LinkedHashSet<>());
            if (problem != null) {
                skipped.add(new Skipped(atom.atomId(), problem));
                continue;
            }
            Map<String, ReferenceAtom> candidate = new LinkedHashMap<>(included);
            candidate.putAll(bundle);
            Map<String, List<ReferenceAtom>> docs = group(candidate.values().stream().toList(), scores);
            if (candidate.size() > Math.max(0, maxBlocks)) {
                skipped.add(new Skipped(atom.atomId(), "块数超限"));
                continue;
            }
            if (docs.size() > Math.max(0, maxDocs)) {
                skipped.add(new Skipped(atom.atomId(), "来源文档数超限"));
                continue;
            }
            String overDoc = docs.entrySet().stream()
                    .filter(entry -> perDocMaxChars > 0 && documentChars(entry.getKey(), entry.getValue()) > perDocMaxChars)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (overDoc != null) {
                skipped.add(new Skipped(atom.atomId(), "来源 " + overDoc + " 超出单文档预算"));
                continue;
            }
            if (renderedChars(docs) > Math.max(0, maxChars)) {
                skipped.add(new Skipped(atom.atomId(), "总预算不足"));
                continue;
            }
            included = candidate;
            bundle.values().forEach(value -> scores.merge(value.docId(), hit.score(), Math::max));
        }
        Map<String, List<ReferenceAtom>> docs = group(new ArrayList<>(included.values()), scores);
        return new Result(docs, renderedChars(docs), skipped);
    }

    /** 先检查递归栈再去重，避免 A→B→C→B 被误当作已处理依赖。 */
    private static String collect(String id, ReferenceBlockSnapshot snapshot, Set<String> allowed,
                                  Set<String> allowedAtoms, Map<String, ReferenceAtom> bundle, Set<String> visiting) {
        if (visiting.contains(id)) {
            return "依赖成环：" + id;
        }
        if (bundle.containsKey(id)) {
            return null;
        }
        ReferenceAtom atom = snapshot.byId().get(id);
        if (atom == null) {
            return "依赖不存在：" + id;
        }
        if (!allowed.contains(atom.docId())) {
            return "依赖来源不在允许范围：" + atom.docId();
        }
        if (!allowedAtoms.contains(id)) {
            return "原子用途不在允许范围：" + id;
        }
        visiting.add(id);
        for (String dependency : atom.requires()) {
            String problem = collect(dependency, snapshot, allowed, allowedAtoms, bundle, visiting);
            if (problem != null) {
                return problem;
            }
        }
        visiting.remove(id);
        bundle.put(id, atom);
        return null;
    }

    private static Map<String, List<ReferenceAtom>> group(List<ReferenceAtom> atoms, Map<String, Double> scores) {
        Map<String, List<ReferenceAtom>> grouped = new LinkedHashMap<>();
        atoms.forEach(atom -> grouped.computeIfAbsent(atom.docId(), key -> new ArrayList<>()).add(atom));
        Map<String, List<ReferenceAtom>> result = new LinkedHashMap<>();
        grouped.keySet().stream().sorted(Comparator.<String>comparingDouble(id -> scores.getOrDefault(id, 0D))
                .reversed().thenComparing(Comparator.naturalOrder())).forEach(id -> {
                    List<ReferenceAtom> doc = grouped.get(id);
                    doc.sort(Comparator.comparingInt(ReferenceAtom::sectionIndex).thenComparing(ReferenceAtom::atomId));
                    result.put(id, doc);
                });
        return result;
    }

    static String renderDocument(List<ReferenceAtom> atoms) {
        return REFERENCE_NOTICE + atoms.stream()
                .map(atom -> "【" + atom.sourceLabel() + "】\n" + atom.content())
                .collect(Collectors.joining(BLOCK_SEPARATOR));
    }

    static int documentChars(String docId, List<ReferenceAtom> atoms) {
        return ("【ref-" + docId + "】\n" + renderDocument(atoms)).length();
    }

    private static int renderedChars(Map<String, List<ReferenceAtom>> docs) {
        return docs.entrySet().stream().mapToInt(entry -> documentChars(entry.getKey(), entry.getValue())
                + BLOCK_SEPARATOR.length()).sum();
    }
}
