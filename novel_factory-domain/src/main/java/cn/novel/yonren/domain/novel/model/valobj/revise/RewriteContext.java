package cn.novel.yonren.domain.novel.model.valobj.revise;

import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;

/** 同一次写作的提示词与原始稿件基线，修订期间不从残稿反推事实。 */
public record RewriteContext(String writerUserPrompt, String writerSystemPrompt,
                             ChapterManuscript original, Runnable cancellationCheck) {
    public RewriteContext(String writerUserPrompt, String writerSystemPrompt, ChapterManuscript original) {
        this(writerUserPrompt, writerSystemPrompt, original, null);
    }

    public void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("章节修订已取消");
        if (cancellationCheck != null) cancellationCheck.run();
    }
}
