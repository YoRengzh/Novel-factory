package cn.novel.yonren.domain.novel.model.valobj.revise;

import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;

/** 同一次写作的提示词与原始稿件基线，修订期间不从残稿反推事实。 */
public record RewriteContext(String writerUserPrompt, String writerSystemPrompt,
                             ChapterManuscript original, Runnable cancellationCheck, String authorFacts, Scope scope) {
    public enum Scope { LEGACY, CHAPTER, SECTIONS }

    public RewriteContext(String writerUserPrompt, String writerSystemPrompt, ChapterManuscript original,
                          Runnable cancellationCheck, String authorFacts) {
        this(writerUserPrompt, writerSystemPrompt, original, cancellationCheck, authorFacts, Scope.LEGACY);
    }

    public RewriteContext(String writerUserPrompt, String writerSystemPrompt, ChapterManuscript original) {
        this(writerUserPrompt, writerSystemPrompt, original, null);
    }

    public RewriteContext(String writerUserPrompt, String writerSystemPrompt,
                          ChapterManuscript original, Runnable cancellationCheck) {
        this(writerUserPrompt, writerSystemPrompt, original, cancellationCheck, null);
    }

    public RewriteContext forVerification(ChapterManuscript beforeRevision, String facts) {
        return new RewriteContext(writerUserPrompt, writerSystemPrompt, beforeRevision, cancellationCheck, facts, scope);
    }

    public static RewriteContext scoped(String userPrompt, String systemPrompt, ChapterManuscript original,
                                       Runnable cancellationCheck, boolean sectioned) {
        return new RewriteContext(userPrompt, systemPrompt, original, cancellationCheck, null,
                sectioned ? Scope.SECTIONS : Scope.CHAPTER);
    }

    public void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("章节修订已取消");
        if (cancellationCheck != null) cancellationCheck.run();
    }
}
