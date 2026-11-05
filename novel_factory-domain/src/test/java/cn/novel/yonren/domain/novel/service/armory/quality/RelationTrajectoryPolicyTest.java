package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关系轨迹回灌测试：11 章实测暴露"配角全是工具人、主角全程单机"后新增——* 与地点问题同根（观测不到就不会被修），RELATION 只记录不回灌等于没有。
 */
class RelationTrajectoryPolicyTest {

    private static ChapterSummaryEntity chapter(int no, String type, String pair, String value) {
        ChapterSummaryEntity.ConsistencyFact f = new ChapterSummaryEntity.ConsistencyFact(
                type, pair, value, null, "正文原句");
        return ChapterSummaryEntity.builder().chapterNo(no).consistencyFacts(List.of(f)).build();
    }

    @Test
    @DisplayName("同 pair 保留最近关系态，首见章号不丢")
    void currentStatesUpsertByPair() {
        var states = RelationTrajectoryPolicy.currentStates(List.of(
                chapter(1, "RELATION", "顾妄与墨璇玑", "结盟"),
                chapter(3, "RELATION", "顾妄与墨璇玑", "决裂"),
                chapter(4, "RELATION", "顾妄与监察使", "互相利用")));

        assertEquals(2, states.size(), "同 pair 去重");
        var m = states.get(0);
        assertEquals("决裂", m.relation(), "应为最近一次的关系态");
        assertEquals(1, m.firstChapter());
        assertEquals(3, m.lastChapter());
    }

    @Test
    @DisplayName("非 RELATION 类型一律忽略；空输入返回空表")
    void ignoresNonRelationFacts() {
        assertTrue(RelationTrajectoryPolicy.currentStates(List.of(
                chapter(1, "TERM", "阵纹", "拓扑语义"))).isEmpty());
        assertTrue(RelationTrajectoryPolicy.currentStates(List.of()).isEmpty());
        assertTrue(RelationTrajectoryPolicy.currentStates(null).isEmpty());
    }

    @Test
    @DisplayName("渲染块含当前态与硬约束；冷启动返回 null 不注入")
    void renderContainsStatesAndConstraint() {
        String block = RelationTrajectoryPolicy.renderTrajectory(List.of(
                chapter(2, "RELATION", "顾妄与青铜仲裁者", "夺取裁决权限")));

        assertTrue(block.contains("【关系台账·当前态】"));
        assertTrue(block.contains("顾妄与青铜仲裁者"));
        assertTrue(block.contains("禁止凭空跳变"), "必须带硬约束：改关系要有正文事件支撑");
        assertNull(RelationTrajectoryPolicy.renderTrajectory(List.of()));
        assertNull(RelationTrajectoryPolicy.renderTrajectory(null));
    }

    @Test
    @DisplayName("按最近更新降序单次渲染：同一关系不再出现两遍")
    void renderSingleOrderedListByRecency() {
        String block = RelationTrajectoryPolicy.renderTrajectory(List.of(
                chapter(1, "RELATION", "顾妄与墨璇玑", "结盟"),
                chapter(5, "RELATION", "顾妄与墨璇玑", "决裂"),
                chapter(2, "RELATION", "顾妄与监察使", "互相利用")));

        // 去重：每条关系只出现一次（旧实现还会再列一遍「最近关系演变」top6，数据完全相同）
        assertEquals(block.indexOf("顾妄与墨璇玑"), block.lastIndexOf("顾妄与墨璇玑"));
        assertEquals(block.indexOf("顾妄与监察使"), block.lastIndexOf("顾妄与监察使"));
        assertFalse(block.contains("【最近关系演变】"));
        // 最近更新（第 5 章）排在前面：优先承接由顺序表达
        assertTrue(block.indexOf("顾妄与墨璇玑") < block.indexOf("顾妄与监察使"));
    }

    @Test
    @DisplayName("关系数超出上限：按最近更新截断并给出封存计数")
    void renderCapsAtMaxRendered() {
        List<ChapterSummaryEntity> summaries = new ArrayList<>();
        int total = RelationTrajectoryPolicy.MAX_RENDERED + 5;
        for (int i = 1; i <= total; i++) {
            summaries.add(chapter(i, "RELATION", "关系对象" + i, "状态" + i));
        }

        String block = RelationTrajectoryPolicy.renderTrajectory(summaries);

        assertTrue(block.contains("另有 5 条更早确立的关系已封存"));
        assertTrue(block.contains("关系对象" + total), "最近更新的必须保留");
        assertFalse(block.contains("关系对象1："), "最早更新的应被截断");
    }
}
