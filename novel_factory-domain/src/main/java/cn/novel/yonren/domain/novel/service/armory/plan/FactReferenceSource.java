package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** 有界正式事实引用供给；仅复制现有版本与证据，不调用模型生成依据。 */
@Slf4j
public final class FactReferenceSource {
    private static final int MAX_SOURCE_CHAPTERS = 24;

    private FactReferenceSource() {}

    public static List<FactReference> select(List<ChapterSummaryEntity> history, int beforeChapter,
                                             IStoryRepository repository, Path storyDir, int maxEntries) {
        if (history == null || history.isEmpty() || repository == null || storyDir == null || maxEntries <= 0) {
            return List.of();
        }
        return readVerified(latestReferences(history, beforeChapter).values(), repository, storyDir, maxEntries);
    }

    /** 写作只核验当前场景引用到的事实。旧引用、裸描述或无可信来源的内容不能成为写作事实。 */
    public static Set<FactReference> verifyForWriting(ChapterPlanItemEntity item, List<ChapterSummaryEntity> history,
                                                      int beforeChapter, IStoryRepository repository, Path storyDir) {
        if (item == null || !Integer.valueOf(2).equals(item.getSchemaVersion()) || item.getScenes() == null
                || history == null || history.isEmpty() || repository == null || storyDir == null) {
            return Set.of();
        }
        Set<FactReference> requested = new LinkedHashSet<>();
        for (var scene : item.getScenes()) {
            if (scene == null) continue;
            requested.addAll(scene.entryStates());
            if (scene.causality() != null && scene.causality().constraints() != null) {
                requested.addAll(scene.causality().constraints());
            }
        }
        if (requested.isEmpty()) return Set.of();
        List<FactReference> candidates = latestReferences(history, beforeChapter).values().stream()
                .filter(requested::contains)
                .toList();
        return Set.copyOf(readVerified(candidates, repository, storyDir, candidates.size()));
    }

    private static Map<String, FactReference> latestReferences(List<ChapterSummaryEntity> history, int beforeChapter) {
        Map<String, FactReference> latest = new LinkedHashMap<>();
        history.stream().filter(summary -> summary != null && summary.getChapterNo() != null
                        && summary.getChapterNo() > 0 && summary.getChapterNo() < beforeChapter)
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo).reversed())
                .forEach(summary -> {
                    add(latest, summary, "CHARACTER", summary.getCharacterStates());
                    add(latest, summary, "ITEM", summary.getItemStates());
                    add(latest, summary, "FACTION", summary.getFactionStates());
                });
        return latest;
    }

    private static List<FactReference> readVerified(Iterable<FactReference> references, IStoryRepository repository,
                                                    Path storyDir, int maxEntries) {
        Map<Integer, ChapterManuscript> manuscripts = new LinkedHashMap<>();
        List<FactReference> selected = new ArrayList<>();
        for (FactReference reference : references) {
            if (StringUtils.isBlank(reference.manuscriptHash()) || StringUtils.isBlank(reference.evidence())) continue;
            if (!manuscripts.containsKey(reference.chapterNo())) {
                if (manuscripts.size() >= MAX_SOURCE_CHAPTERS) continue;
                manuscripts.put(reference.chapterNo(), readManuscript(repository, storyDir, reference.chapterNo()));
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

    private static ChapterManuscript readManuscript(IStoryRepository repository, Path storyDir, int chapterNo) {
        try {
            var chapter = repository.readChapter(storyDir, chapterNo);
            return chapter == null ? null : ChapterManuscript.of(chapter);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("读取事实引用来源时中断");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception unavailable) {
            log.warn("第 {} 章事实引用来源读取失败，跳过该章引用", chapterNo, unavailable);
            return null;
        }
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

    public static String render(List<FactReference> references) {
        if (references == null || references.isEmpty()) return "";
        List<FactReference> rendered = references.stream().filter(Objects::nonNull).toList();
        if (rendered.isEmpty()) return "";
        return "【可引用的正式事实】仅在当前场景需要时，将下方对象的六个字段原样复制到 entryStates 或 constraints。"
                + "这些是已有来源，可以不引用。\n"
                + JSON.toJSONString(rendered);
    }
}
