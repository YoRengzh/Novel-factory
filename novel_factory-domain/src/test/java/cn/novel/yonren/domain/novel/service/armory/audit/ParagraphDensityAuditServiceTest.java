package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 段落信息增量审校测试。
 *
 * <p>要点：只产出**局部改写补丁**（不是整章重写），且任何环节失败都必须保留原稿——
 * 它是一个增强件，不得阻断生成。默认关闭。
 */
class ParagraphDensityAuditServiceTest {

    /** 中间那段含一个只在全章出现一次的实体名与一个唯一数字；同样垫足篇幅 */
    private static final String DATED_CONTENT =
            filler() + "管理员把补充名单放在桌上。\n\n"
                    + "角色丁的名字在第二个，考场编号 17。\n\n"
                    + "他把名单压进笔记本底下。\n\n" + filler();

    /** 中间那段是典型的"原地循环同一种情绪"，无任何状态变化；两端垫足篇幅以过采纳闸门的长度下限 */
    private static final String CONTENT =
            filler() + "角色癸站在窗前，外面在下雨。\n\n"
                    + "他感到很愤怒。他真的很愤怒。\n\n"
                    + "他打开电脑，看到了角色壬发来的消息。\n\n" + filler();

    private LlmGateway llmGateway;
    private StoryProperties storyProperties;
    private ParagraphDensityAuditService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        storyProperties = new StoryProperties();
        service = new ParagraphDensityAuditService(llmGateway, storyProperties);
    }

    @Test
    void disabledByDefault_neverCallsLlm() {
        assertFalse(storyProperties.getParagraphAudit().isEnabled(), "新链路默认关闭");

        assertNull(service.auditAndRewrite(module(), item(), CONTENT, 1));
        verifyNoInteractions(llmGateway);
    }

    @Test
    void emptyPatches_keepsOriginal() {
        enable();
        stub("{\"patches\":[]}");

        assertNull(service.auditAndRewrite(module(), item(), CONTENT, 1));
    }

    @Test
    void validPatch_isAppliedLocally() {
        enable();
        stub("{\"patches\":[{\"anchor\":\"他感到很愤怒。他真的很愤怒。\","
                + "\"replacement\":\"他攥紧了鼠标，指节泛白。\"}]}");

        String result = service.auditAndRewrite(module(), item(), CONTENT, 1);

        assertNotNull(result);
        assertTrue(result.contains("他攥紧了鼠标，指节泛白。"), "应套用改写");
        assertFalse(result.contains("他真的很愤怒。"), "注水句应被替换掉");
        // 局部改写：其余段落原样保留
        assertTrue(result.contains("角色癸站在窗前，外面在下雨。"));
        assertTrue(result.contains("他打开电脑，看到了角色壬发来的消息。"));
    }

    @Test
    void anchorNotFoundInContent_keepsOriginal() {
        enable();
        // 锚点必须在正文中逐字存在且唯一——找不到即整批作废（宁可少改，不可改错位置）
        stub("{\"patches\":[{\"anchor\":\"这句话正文里根本没有\",\"replacement\":\"x\"}]}");

        assertNull(service.auditAndRewrite(module(), item(), CONTENT, 1));
    }

    @Test
    void maxPatchesIsEnforced() {
        enable();
        storyProperties.getParagraphAudit().setMaxPatches(1);
        // 给出两条锚点唯一的补丁，但上限为 1 → 只套用第一条
        stub("{\"patches\":["
                + "{\"anchor\":\"他感到很愤怒。他真的很愤怒。\",\"replacement\":\"他攥紧了鼠标。\"},"
                + "{\"anchor\":\"角色癸站在窗前，外面在下雨。\",\"replacement\":\"雨敲在窗玻璃上。\"}]}");

        String result = service.auditAndRewrite(module(), item(), CONTENT, 1);

        assertNotNull(result);
        assertTrue(result.contains("他攥紧了鼠标。"));
        assertTrue(result.contains("角色癸站在窗前，外面在下雨。"), "超出上限的补丁不得被套用");
    }

    @Test
    void llmFailure_keepsOriginal() {
        enable();
        when(llmGateway.complete(any(), any())).thenThrow(new RuntimeException("boom"));

        assertNull(service.auditAndRewrite(module(), item(), CONTENT, 1));
    }

    @Test
    void promptCarriesIncrementalCriteriaAndSafetyRules() {
        enable();
        stub("{\"patches\":[]}");

        service.auditAndRewrite(module(), item(), CONTENT, 7);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();

        assertTrue(prompt.contains("没有带来任何信息增量"), "必须给出信息增量的判定标准");
        assertTrue(prompt.contains("信息增量指"), "必须说明什么算增量（否则模型会把有变化的段落也改掉）");
        assertTrue(prompt.contains("不要改变剧情走向") && prompt.contains("不要新增情节"),
                "改写必须限定在挤水，不得改剧情");
        assertTrue(prompt.contains("anchor 必须逐字来自正文"), "必须强调锚点逐字性");
        assertTrue(prompt.contains("第") || prompt.contains("正文"), "必须带上正文");
        assertEquals("paragraph-audit-第7章", captor.getValue().getLabel());
    }

    private void enable() {
        storyProperties.getParagraphAudit().setEnabled(true);
    }

    private void stub(String response) {
        when(llmGateway.complete(any(), any())).thenReturn(response);
    }

    private StoryVO.Module module() {
        return new StoryVO.Module();
    }

    private ChapterPlanItemEntity item() {
        return ChapterPlanItemEntity.builder()
                .chapterNo(7).title("第七章").goal("角色癸掩饰身份")
                .keyEvents(List.of("收到消息")).endingHook("他停下手里的动作")
                .build();
    }


    // ==================== 移植自旧系统的闸门用例 ====================

    /**
     * 删掉只出现一次的计划角色名 → 整批回退。
     *
     * <p>此前这条路径只查"机械文风违规不增加"：一次套用就能把实体删干净，
     * 而这类损失不会出现在任何指标上。
     */
    @Test
    void patchDroppingOnlyMentionOfCharacter_isRejected() {
        enable();
        stub("{\"patches\":[{\"anchor\":\"角色庚的名字在第二个，考场编号 17。\","
                + "\"replacement\":\"名字在第二个。\"}]}");

        assertNull(service.auditAndRewrite(module(), entityItem(), DATED_CONTENT, 7),
                "角色庚是计划角色且全章只此一处，被删即回退保留原稿");
    }

    /** 只删数字也一样拦：年代文里考场号/年份/金额是最硬的事实 */
    @Test
    void patchDroppingOnlyMentionOfNumber_isRejected() {
        enable();
        stub("{\"patches\":[{\"anchor\":\"角色庚的名字在第二个，考场编号 17。\","
                + "\"replacement\":\"角色庚的名字在第二个。\"}]}");

        assertNull(service.auditAndRewrite(module(), entityItem(), DATED_CONTENT, 7),
                "17 是全章唯一一次出现，删掉即回退");
    }

    /** 锚都保留时照常套用——闸门不能把正确的挤水一起拒掉 */
    @Test
    void patchKeepingAnchors_isApplied() {
        enable();
        stub("{\"patches\":[{\"anchor\":\"他把名单压进笔记本底下。\","
                + "\"replacement\":\"名单被他压进笔记本底下，指腹按了两下。\"}]}");

        String result = service.auditAndRewrite(module(), entityItem(), DATED_CONTENT, 7);

        assertNotNull(result, "锚（角色名与数字）未受影响，应正常套用");
        assertTrue(result.contains("指腹按了两下"), result);
        assertTrue(result.contains("角色丁的名字在第二个，考场编号 17。"), "未触及的段落逐字保留");
    }

    /**
     * 长度下限闸门（2026-10-10）：挤水的目的是去掉重复，不是把章挤薄。
     *
     * <p>实测同一段章节重跑一次，字数掉 21%（平均 1716→1360）、对白掉 30%，其中挤水补丁
     * 从 2 条涨到 9 条是贡献项之一——而这条路径此前**没有任何长度闸门**
     * （修订链路至少有"字数不低于原稿 80%"，这里一条都没有）。
     */
    @Test
    void patchShrinkingBelowFloor_isRejected() {
        enable();
        // 两段唯一长段（整段重复同一句，故在正文中只此一处，可作唯一锚点）
        String uniqueA = "角色甲蹲在灶前把煤球摞成三层，火门对得极准。".repeat(12);
        String uniqueB = "角色乙把晾在檐下的衣裳一件件收进来，领口袖口分开叠。".repeat(12);
        String longContent = filler() + uniqueA + "\n\n" + uniqueB + "\n\n";
        // 删掉两段 ≈ 减 24%，低于原稿 85% 与 1500 参考线
        stub("{\"patches\":["
                + "{\"anchor\":\"" + uniqueA + "\",\"replacement\":\"\"},"
                + "{\"anchor\":\"" + uniqueB + "\",\"replacement\":\"\"}]}");

        assertNull(service.auditAndRewrite(module(), entityItem(), longContent, 3),
                "补丁把章挤到参考线以下即整批回退");
    }

    /** 反向：只是把重复段换短一点、整体仍在参考线之上时照常套用（闸门不能把正常挤水一起拒掉） */
    @Test
    void patchShrinkingWithinFloor_isApplied() {
        enable();
        stub("{\"patches\":[{\"anchor\":\"他感到很愤怒。他真的很愤怒。\","
                + "\"replacement\":\"他把鼠标攥紧了一下。\"}]}");

        String result = service.auditAndRewrite(module(), item(), CONTENT, 1);

        assertNotNull(result, "小幅挤水应当放行");
        assertTrue(result.contains("他把鼠标攥紧了一下"), result);
        assertFalse(result.contains("他真的很愤怒。"), result);
    }


    // ---- 移植用例的辅助构造 ----

    private ChapterPlanItemEntity entityItem() {
        return ChapterPlanItemEntity.builder()
                .chapterNo(7).title("第七章").goal("马老师查看补充名单")
                .characters(List.of("角色庚"))
                .keyEvents(List.of("马老师查看补充名单"))
                .endingHook("名单被压进笔记本底下")
                .build();
    }

    /** 中性的年代日常叙述填充（不含任何角色名/数字，不会与任何判据相撞）；约 1650 有效字 */
    private static String filler() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("灶上的水汽把窗玻璃糊住一层，屋檐下滴水落在青石板上，声响细而匀。")
                    .append("院里的绳子上挂着刚洗过的衣裳，风一过就轻轻晃两下。\n\n");
        }
        return sb.toString();
    }
}
