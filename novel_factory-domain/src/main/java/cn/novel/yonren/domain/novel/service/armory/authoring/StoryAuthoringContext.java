package cn.novel.yonren.domain.novel.service.armory.authoring;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.authoring.StoryAuthoringSnapshot;

import java.nio.file.Path;
import java.util.Objects;

/** 普通续写采用已激活维护版，断点恢复只允许原工作版本。 */
public final class StoryAuthoringContext {
    private StoryAuthoringContext() { }

    public static void prepare(StoryContextEntity requested, IStoryRepository repository, Path storyDir,
                               StoryContextEntity pendingStory) throws Exception {
        if (requested == null) return;
        if (storyDir == null) {
            requested.setStoryAuthoring(null);
            return;
        }
        StoryAuthoringSnapshot active = repository.readActiveStoryAuthoring(storyDir);
        if (pendingStory != null && !Objects.equals(active, pendingStory.getStoryAuthoring())) {
            throw new IllegalStateException("RECOVERY_AUTHORING_CONTEXT_MISMATCH");
        }
        requested.setStoryAuthoring(pendingStory == null ? active : pendingStory.getStoryAuthoring());
        if (requested.getStoryAuthoring() != null) {
            StoryAuthoringService.applySettings(requested, requested.getStoryAuthoring());
        }
    }
}
