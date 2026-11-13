package cn.novel.yonren.domain.novel.service.armory.llm;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 本地确定性拒绝，不得被当作供应商失败重试或降级。 */
public class LlmLocalRejectionException extends RuntimeException {
    public LlmLocalRejectionException(String message) {
        super(message);
    }

    public static LlmLocalRejectionException findLocalRejection(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && visited.size() < 64 && visited.add(cause);
             cause = cause.getCause()) {
            if (cause instanceof LlmLocalRejectionException rejection) {
                return rejection;
            }
        }
        return null;
    }

    public static void rethrowIfPresent(Throwable error) {
        LlmLocalRejectionException rejection = findLocalRejection(error);
        if (rejection != null) {
            throw rejection;
        }
    }
}
