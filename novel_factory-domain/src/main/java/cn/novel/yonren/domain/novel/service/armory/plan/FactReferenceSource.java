package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 有界正式事实引用供给；仅复制现有版本与证据，不调用模型生成依据。 */
public final class FactReferenceSource {
    private FactReferenceSource() {}

    public static List<FactReference> select(List<ChapterSummaryEntity> history, int beforeChapter,
                                             IStoryRepository repository, Path storyDir, int maxEntries) throws Exception {
        if (history == null || history.isEmpty() || repository == null || storyDir == null || maxEntries <= 0) {
            return List.of();
        }
        Map<String, FactReference> latest = new LinkedHashMap<>();
        history.stream().filter(summary -> summary != null && summary.getChapterNo() != null
                        && summary.getChapterNo() > 0 && summary.getChapterNo() < beforeChapter)
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo).reversed())
                .forEach(summary -> {
                    add(latest, summary, "CHARACTER", summary.getCharacterStates());
                    add(latest, summary, "ITEM", summary.getItemStates());
                    add(latest, summary, "FACTION", summary.getFactionStates());
                });
        Map<Integer, ChapterManuscript> manuscripts = new LinkedHashMap<>();
        List<FactReference> selected = new ArrayList<>();
        for (FactReference reference : latest.values()) {
            if (StringUtils.isBlank(reference.manuscriptHash()) || StringUtils.isBlank(reference.evidence())) continue;
            if (!manuscripts.containsKey(reference.chapterNo())) {
                var chapter = repository.readChapter(storyDir, reference.chapterNo());
                manuscripts.put(reference.chapterNo(), chapter == null ? null : ChapterManuscript.of(chapter));
            }
            var manuscript = manuscripts.get(reference.chapterNo());
            if (manuscript != null && manuscript.chapterNo() == reference.chapterNo()
                    && manuscript.hash().equals(reference.manuscriptHash())
                    && manuscript.content().contains(reference.evidence())) {
                selected.add(reference);
                if (selected.size() >= maxEntries) break;
            }
        }
        return List.copyOf(selected);
    }

    private static void add(Map<String, FactReference> latest, ChapterSummaryEntity summary, String ledger,
                            List<ChapterSummaryEntity.StateEntry> states) {
        if (states == null) return;
        for (var state : states) {
            if (state == null || StringUtils.isBlank(state.getName()) || StringUtils.isBlank(state.getStatus())) continue;
            // 最新记录即使缺 hash/证据也占据该主体，不能回退旧状态冒充当前状态。
            latest.putIfAbsent(ledger + ":" + semanticText(state.getName()),
                    new FactReference(ledger, summary.getChapterNo(), summary.getManuscriptHash(),
                            state.getName(), state.getStatus(), state.getEvidence()));
        }
    }

    /** 只消除排版噪声（全角半角与空白），不做同义改写或包含匹配。 */
    private static String semanticText(String text) {
        return java.text.Normalizer.normalize(StringUtils.defaultString(text), java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", "").trim();
    }

    /** 模型可见的引用身份：与校验侧同一算法，模型只需原样回填 factId。 */
    public static String identity(FactReference reference) {
        return reference == null ? null : cn.novel.yonren.types.utils.FactIdentity.of(reference.ledger(),
                reference.chapterNo(), reference.manuscriptHash(), reference.subject(),
                reference.status(), reference.evidence());
    }

    public static String render(List<FactReference> references) {
        if (references == null || references.isEmpty()) return "";
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (FactReference reference : references) {
            if (reference == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("factId", identity(reference));
            item.put("ledger", reference.ledger());
            item.put("chapterNo", reference.chapterNo());
            item.put("manuscriptHash", reference.manuscriptHash());
            item.put("subject", reference.subject());
            item.put("status", reference.status());
            item.put("evidence", reference.evidence());
            rendered.add(item);
        }
        return "\n\n【可引用的正式事实】以下完整 JSON 引用可按当前场景需要复制；不是新的剧情目标。"
                + "引用时原样回填 factId，不得自行编造 hash、证据或来源；入口引用可以为空。\n"
                + JSON.toJSONString(rendered);
    }
}
