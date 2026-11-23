package cn.novel.yonren.domain.novel.service.armory.writer;

import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;

import java.util.List;
import java.util.Set;

/**
 * DIRECT 正文材料所需的确定性输入，不包含全量规划前缀。
 *
 * <p>所有文本都应是可交给正文写手的语义内容，不含审计 hash 和未来章节。事实引用单独传递，
 * 由组装器按场景过滤，避免其他场景的物品状态混入当前段。
 */
public record WriterChapterContext(
        Set<FactReference> verifiedReferences,
        String characterGuidance,
        String characterFeedback,
        String edgeState,
        String continuityGuidance,
        String secrecyGuidance,
        String authorGuidance,
        String recalledMemories,
        String previousChapterTail,
        String initialStoryFacts,
        String povCharacter) {

    public WriterChapterContext(
            Set<FactReference> verifiedReferences,
            String characterGuidance,
            String characterFeedback,
            String edgeState,
            String continuityGuidance,
            String secrecyGuidance,
            String authorGuidance,
            String recalledMemories,
            String previousChapterTail,
            String initialStoryFacts) {
        this(verifiedReferences, characterGuidance, characterFeedback, edgeState,
                continuityGuidance, secrecyGuidance, authorGuidance,
                recalledMemories, previousChapterTail, initialStoryFacts, null);
    }

    public static WriterChapterContext empty() {
        return new WriterChapterContext(Set.of(), "", "", "", "", "", "", "", "", "", null);
    }
}
