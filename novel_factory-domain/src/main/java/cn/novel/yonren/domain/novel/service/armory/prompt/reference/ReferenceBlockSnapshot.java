package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请求级不可变语料快照。prepare 阶段 collection 为 null；查询确定实际维度后生成索引快照，
 * 不修改原快照。缓存版本不依赖是否已经构建或上一次检索。
 */
public record ReferenceBlockSnapshot(String collection, List<ReferenceAtom> atoms,
                                     Map<String, ReferenceAtom> byId, String corpusHash,
                                     String signature, String policyVersion, int schemaVersion,
                                     Integer dimensions) {

    public ReferenceBlockSnapshot {
        atoms = atoms == null ? List.of() : List.copyOf(atoms);
        byId = byId == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(byId));
    }

    /** 兼容只做打包的调用方；运行时快照由索引器提供完整版本元数据。 */
    public ReferenceBlockSnapshot(String collection, List<ReferenceAtom> atoms,
                                  Map<String, ReferenceAtom> byId, String corpusHash, String signature) {
        this(collection, atoms, byId, corpusHash, signature, "legacy",
                ReferenceBlockIndexer.CHUNK_SCHEMA_VERSION, null);
    }

    public List<ReferenceAtom> standaloneAtoms(Set<String> allowedDocIds) {
        if (allowedDocIds == null || allowedDocIds.isEmpty()) {
            return List.of();
        }
        return atoms.stream()
                .filter(atom -> atom.standalone() && allowedDocIds.contains(atom.docId()))
                .toList();
    }

    /** 有效签名与本地完整语料、策略、切分版本在查缓存前均已确定。 */
    public String version() {
        return "v" + schemaVersion + "-" + ReferenceBlockIndexer.digestOf(
                corpusHash, signature, policyVersion, String.valueOf(schemaVersion));
    }

    ReferenceBlockSnapshot indexed(String collection, int actualDimensions) {
        return new ReferenceBlockSnapshot(collection, atoms, byId, corpusHash, signature,
                policyVersion, schemaVersion, actualDimensions);
    }
}
