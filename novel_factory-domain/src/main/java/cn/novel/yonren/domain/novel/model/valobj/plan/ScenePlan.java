package cn.novel.yonren.domain.novel.model.valobj.plan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 章节内的权威场景计划；事实引用不是未来结果，里程碑引用不是完成证明。
 * resistance 可声明外部阻力、内在犹疑或认知不确定性；function 可用过渡/消化/余韵等前缀带说明。
 * causality 是可选补充，不提升 v2 版本，也不把角色相信的内容当作世界事实。
 */
public record ScenePlan(String id, List<String> characters, String intention, String resistance,
                        List<FactReference> entryStates, String outcome, String function,
                        List<String> milestoneRefs, String location, String time,
                        @JsonInclude(JsonInclude.Include.NON_NULL) SceneCausality causality) {

    /** 旧十参入口保持可用；null 不补为空对象，以免旧 JSON 与基于 JSON 的 hash 改变。 */
    public ScenePlan(String id, List<String> characters, String intention, String resistance,
                     List<FactReference> entryStates, String outcome, String function,
                     List<String> milestoneRefs, String location, String time) {
        this(id, characters, intention, resistance, entryStates, outcome, function,
                milestoneRefs, location, time, null);
    }

    public ScenePlan {
        characters = safeList(characters);
        entryStates = safeList(entryStates);
        milestoneRefs = safeList(milestoneRefs);
    }

    private static <T> List<T> safeList(List<T> values) {
        // 保留非法 null 元素给 policy 精确报错，不在 JSON 读取时绕过校验。
        // 各字段持有独立列表，兼容序列化器的引用检测不能把两个空数组写成共享对象引用。
        return Collections.unmodifiableList(values == null ? new ArrayList<>() : new ArrayList<>(values));
    }
}
