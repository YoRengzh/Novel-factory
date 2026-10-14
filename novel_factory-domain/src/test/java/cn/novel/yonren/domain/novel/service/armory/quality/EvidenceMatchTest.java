package cn.novel.yonren.domain.novel.service.armory.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据命中判定测试。
 *
 * <p>单元用例的素材取自 20260913-story-0001 的真实挂起数据（模型的省略号压缩引用、
 * 引号形态差异、语序重组等），不是臆造样本。末尾的干跑回归用同一份数据重放
 * 全部历史隔离条目，断言账本完整度回到 94% 以上（门分层 + SPREAD/ANCHORED 留痕档），
 * 且「全库无据」条目一条都不被放行——放行与守门这两件事同时是本改造的验收判据。
 */
class EvidenceMatchTest {

    // ------------------------------------------------------------ 档位判定

    @Test
    @DisplayName("逐字包含 → EXACT（保持原 indexOf 语义）")
    void exactTier() {
        String content = "陆沉把黑色令牌拍在案角，\"从现在起，灵剑山进入战时状态。\"";
        assertEquals(EvidenceMatch.Tier.EXACT, EvidenceMatch.classify("陆沉把黑色令牌拍在案角", content));
    }

    @Test
    @DisplayName("引号形态/空白差异 → NORMALIZED")
    void normalizedTier() {
        // 正文用中文引号，模型引用时写成英文引号并混入多余空格
        String content = "陆沉打断他：“掌门给了我临时全权。现在听我分工。”";
        String evidence = "\"掌门 给了我 临时全权。 现在听我分工。\"";
        assertEquals(EvidenceMatch.Tier.NORMALIZED, EvidenceMatch.classify(evidence, content));

        // 仅空白差异：正文有换行，引用压成一行
        String contentWithBreak = "陆沉打断他：\n“掌门给了我临时全权。\n现在听我分工。”\n";
        String flattened = "陆沉打断他：“掌门给了我临时全权。现在听我分工。”";
        assertEquals(EvidenceMatch.Tier.NORMALIZED, EvidenceMatch.classify(flattened, contentWithBreak));
    }

    @Test
    @DisplayName("省略号压缩引用且集中 → FRAGMENTED（本改造要救回的主要类型）")
    void fragmentedTier() {
        // 取自真实数据：模型把本章三个关键片段用「...」压成一条状态证据，中间省略正文原句。
        // 正文按真实章节长度构造（约 2000 字），引用只占其中一小段——比例条件才算得准
        String head = "一块锋利的石屑划破了陆沉的脸颊，血珠顺着下颌滴落。";
        String middle = "四周的碎石簌簌落下，灵脉的嗡鸣声渐渐低沉。".repeat(10);
        String tail = "陆沉握着那块冰凉的玉简，终于看清了那些字符。他看到了。";
        String after = "风声从断裂的穹顶灌进来，卷起满地尘土。".repeat(100);
        String content = head + middle + tail + after;
        String evidence = "一块锋利的石屑划破了陆沉的脸颊...陆沉握着那块冰凉的玉简...他看到了。";
        assertEquals(EvidenceMatch.Tier.FRAGMENTED, EvidenceMatch.classify(evidence, content));
    }

    @Test
    @DisplayName("语序被模型重排，但引用仍集中于同一段 → 仍判 FRAGMENTED")
    void fragmentedTierWithReorderedFragments() {
        // 真实数据里模型常把对话内两句话调换顺序。语序不影响可信度，跨度才影响，
        // 且短正文下绝对跨度条件兜住了比例阈值过严的问题
        String content = "陆沉向前迈了一步，压低声音，只有两人能听见：\"长老这枚清心玉，似乎出了点问题。\"";
        String evidence = "长老这枚清心玉，似乎出了点问题...陆沉向前迈了一步，压低声音";
        assertEquals(EvidenceMatch.Tier.FRAGMENTED, EvidenceMatch.classify(evidence, content));
    }

    @Test
    @DisplayName("短章里引用跨度不大 → 绝对跨度条件兜住比例阈值")
    void shortChapterUsesAbsoluteSpan() {
        // 短章正文仅约 700 字，若只按比例判定会被误判为散布
        String content = "短章开头的叙述性文字。".repeat(20)
                + "陆沉推开房门，脸色苍白。" + "中段的过渡描写。".repeat(20);
        String evidence = "短章开头的叙述性文字...陆沉推开房门，脸色苍白。";
        assertEquals(EvidenceMatch.Tier.FRAGMENTED, EvidenceMatch.classify(evidence, content));
    }

    @Test
    @DisplayName("引用横跨大半章 → 仍判 SPREAD，但已属放行档（留痕，不再逐出）")
    void spreadTierAcceptedButTraced() {
        // 首句与末句被拼成一条状态，中间隔了整章内容。但两段都是正文逐字原文——
        // 「跨度大」只说明这是一条复合状态（位置 + 伤势/持有物），与证据真实性无关：
        // 离线重放实测该档 52 条全部为真，且「全库无据」条目分段必有段命中不到、与本档零交集
        StringBuilder content = new StringBuilder("陆沉从讲堂出来，沿着石径走向藏经阁。");
        content.append("中间正文".repeat(1200));
        content.append("但手腕处的黑色锁链纹身烫得像烙铁。");
        String evidence = "陆沉从讲堂出来，沿着石径走向藏经阁……手腕处的黑色锁链纹身烫得像烙铁";
        EvidenceMatch.Tier tier = EvidenceMatch.classify(evidence, content.toString());
        assertEquals(EvidenceMatch.Tier.SPREAD, tier);
        assertTrue(tier.isAccepted(), "SPREAD 已转为留痕放行档，不应再逐出");
        assertTrue(tier.isLoose(), "SPREAD 属放宽留痕档，须写回 evidenceTier 供观测层统计");
    }

    @Test
    @DisplayName("只剩单条短语但锚定足够长 → ANCHORED（条数少不等于证据弱）")
    void anchoredTierForSingleLongPhrase() {
        // 取自真实数据第53章：模型把引用切成多段，切完后只剩一条 ≥8 字的短语可定位。
        // 旧实现因 len(phrases) >= 2 的数量门槛，把它直接漏进了最弱的隔离档
        String content = "方启明躺在医疗堂的床榻上，嘴里不停地念叨着两个词。";
        String evidence = "方启明……方师兄醒了！……嘴里不停地念叨着两个词。他说……‘眼睛’……";
        EvidenceMatch.Tier tier = EvidenceMatch.classify(evidence, content);
        assertEquals(EvidenceMatch.Tier.ANCHORED, tier);
        assertTrue(tier.isAccepted(), "单条 ≥8 字短语的锚定强度足够，应放行");
        assertTrue(tier.isLoose(), "ANCHORED 属放宽留痕档，须写回 evidenceTier 供观测层统计");
    }

    @Test
    @DisplayName("单条短语过短（7 字短锚）→ 仍判 PHRASE_PARTIAL 转裁决")
    void shortSinglePhraseStillIsolated() {
        // 短锚与「关键词碰巧命中」量级接近，不足以自证，仍交裁决层
        String content = "方启明躺在床榻上，嘴里不停地念叨。";
        String evidence = "方启明……嘴里不停地念叨。他说……‘眼睛’……";
        assertEquals(EvidenceMatch.Tier.PHRASE_PARTIAL, EvidenceMatch.classify(evidence, content));
    }

    @Test
    @DisplayName("无省略号但各语义单元都在正文 → PHRASE_COVERED")
    void phraseCoveredTier() {
        // 模型改写了连接处措辞（用逗号连成一句），但两个语义单元都是正文原文且位置相近
        String content = "周伯通负责协调灵石与人力。断臂绷带透着暗红血迹。";
        String evidence = "周伯通负责协调灵石与人力，断臂绷带透着暗红血迹";
        assertEquals(EvidenceMatch.Tier.PHRASE_COVERED, EvidenceMatch.classify(evidence, content));
    }

    @Test
    @DisplayName("仅部分短语可定位 → PHRASE_PARTIAL（转裁决）")
    void phrasePartialTierIsolated() {
        String content = "断臂绷带透着暗红血迹。";
        String evidence = "周伯通负责协调灵石与人力，断臂绷带透着暗红血迹";
        assertEquals(EvidenceMatch.Tier.PHRASE_PARTIAL, EvidenceMatch.classify(evidence, content));
    }

    @Test
    @DisplayName("编造引用（正文无据）→ NO_MATCH，反编造门不放弃")
    void noMatchTierForFabricatedEvidence() {
        String content = "陆沉从讲堂出来，沿着石径走向藏经阁。";
        String evidence = "玉佩上浮现出第三道裂痕，青光暴涨三尺";
        assertEquals(EvidenceMatch.Tier.NO_MATCH, EvidenceMatch.classify(evidence, content));
    }

    @Test
    @DisplayName("空证据不属本类判定范围，安全返回 EXACT")
    void blankEvidenceIsNotJudgedHere() {
        assertEquals(EvidenceMatch.Tier.EXACT, EvidenceMatch.classify(null, "任意正文"));
        assertEquals(EvidenceMatch.Tier.EXACT, EvidenceMatch.classify("   ", "任意正文"));
    }

    @Test
    @DisplayName("超长证据按上限截断后再判定（与 prompt 字数约束一致）")
    void evidenceTruncatedBeforeJudge() {
        // 证据是正文 + 大量追加内容；截断到 50 字后仍应能在正文中定位
        String content = "陆沉站在阵眼中央，手腕上的锁链纹身微微发烫。".repeat(3);
        String longEvidence = content + "，但他没有停下脚步。".repeat(20);
        assertEquals(EvidenceMatch.Tier.EXACT, EvidenceMatch.classify(longEvidence, content));
        assertEquals(50, EvidenceMatch.truncate(longEvidence, EvidenceMatch.STATE_EVIDENCE_MAX_LENGTH).length());
    }

    // ------------------------------------------------------------ 审校侧口径

    @Test
    @DisplayName("contained：归一化整段或省略号分段命中（审校 BLOCKING 证据校验口径）")
    void containedForAuditSide() {
        String normalized = EvidenceMatch.normalize("陆沉把黑色令牌拍在案角，“从现在起，灵剑山进入战时状态。”");
        assertTrue(EvidenceMatch.contained("陆沉把黑色令牌拍在案角", normalized));
        assertTrue(EvidenceMatch.contained("陆沉把黑色令牌...进入战时状态", normalized));
        assertFalse(EvidenceMatch.contained("苏清平推开房门，面色苍白", normalized));
        assertFalse(EvidenceMatch.contained(null, normalized));
        assertFalse(EvidenceMatch.contained("", normalized));
    }

    @Test
    @DisplayName("normalize：去空白/统一引号/剥首尾引号")
    void normalizeRules() {
        assertEquals("这是个测试", EvidenceMatch.normalize(" 这 是 个\n测试 "));
        // 中文引号先统一成英文引号，位于首尾时再被剥除
        assertEquals("引号", EvidenceMatch.normalize("“引号”"));
        // 尾部引号一律剥除（沿用审校侧既有口径：即使它不是配对的开引号，也一并在尾端剥掉）
        assertEquals("他说\"你好", EvidenceMatch.normalize("他说“你好”"));
    }

    @Test
    @DisplayName("normalize：全角/半角引号一律统一为半角双引号（2026-09-18 起）")
    void normalizeUnifiesAllQuoteForms() {
        // 只做"全角→同类型半角"不足以免疫：记忆原文用半角单引号包裹 ID（线上角色'无月'），
        // 模型复述时换成全角或半角双引号，旧口径下就整条对不上。
        // 引号是装饰性标点、不承载语义，故所有形态一律归一到半角双引号。
        String expected = "他说\"你好";
        assertEquals(expected, EvidenceMatch.normalize("他说“你好”"));
        assertEquals(expected, EvidenceMatch.normalize("他说‘你好’"));
        assertEquals(expected, EvidenceMatch.normalize("他说'你好'"));
        assertEquals(expected, EvidenceMatch.normalize("他说\"你好\""));
        // 嵌在句中、不触首尾剥离的形态
        assertEquals("线上角色\"无月\"与软软绑定", EvidenceMatch.normalize("线上角色'无月'与软软绑定"));
    }

    @Test
    @DisplayName("contained：正文与证据的引号形态不同仍能命中")
    void containedIsImmuneToQuoteFormDifference() {
        // 取自真实产出：ch6 三账本条目「线上角色'无月'与软软绑定为固定搭档」
        String content = EvidenceMatch.normalize("线上角色'无月'与软软绑定为固定搭档；");
        assertTrue(EvidenceMatch.contained("线上角色“无月”与软软绑定为固定搭档", content));
        assertTrue(EvidenceMatch.contained("线上角色\"无月\"与软软绑定为固定搭档", content));
        assertTrue(EvidenceMatch.contained("线上角色'无月'与软软绑定为固定搭档", content));
        // 文字不同仍必须拒收（放宽的只是标点形态）
        assertFalse(EvidenceMatch.contained("线上角色“无月”与软软解除搭档关系", content));
    }

    // ------------------------------------------------------------ 干跑回归

    @Test
    @DisplayName("干跑回归：历史隔离条目重放后，账本完整度应回到 90% 以上")
    void replayHistoricalPendingFacts() throws Exception {
        Path storyDir = locateStoryDir();
        Assumptions.assumeTrue(storyDir != null, "本地故事数据不存在，跳过干跑回归");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode summaries = mapper.readTree(storyDir.resolve("memory").resolve("summaries.json").toFile());
        assertTrue(summaries.isArray() && summaries.size() > 0, "summaries.json 应为非空数组");

        Map<Integer, String> contents = new HashMap<>();
        for (JsonNode s : summaries) {
            int no = s.path("chapterNo").asInt();
            Path p = storyDir.resolve("chapters").resolve(String.format("chapter-%04d.txt", no));
            if (Files.isRegularFile(p)) {
                contents.put(no, new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
        }
        Assumptions.assumeTrue(!contents.isEmpty(), "章节正文不存在，跳过干跑回归");

        int kept = 0;
        int pending = 0;
        int accepted = 0;
        int spread = 0;
        int anchored = 0;
        int partial = 0;
        int noMatch = 0;
        for (JsonNode s : summaries) {
            String content = contents.get(s.path("chapterNo").asInt());
            for (String key : new String[]{"characterStates", "itemStates", "factionStates", "consistencyFacts"}) {
                JsonNode arr = s.path(key);
                if (arr.isArray()) {
                    kept += arr.size();
                }
            }
            for (String key : new String[]{"pendingFacts", "pendingConsistencyFacts"}) {
                JsonNode arr = s.path(key);
                if (!arr.isArray()) {
                    continue;
                }
                for (JsonNode item : arr) {
                    pending++;
                    EvidenceMatch.Tier tier = EvidenceMatch.classify(item.path("evidence").asText(null), content);
                    if (tier.isAccepted()) {
                        accepted++;
                        if (tier == EvidenceMatch.Tier.SPREAD) {
                            spread++;
                        } else if (tier == EvidenceMatch.Tier.ANCHORED) {
                            anchored++;
                        }
                    } else if (tier == EvidenceMatch.Tier.PHRASE_PARTIAL) {
                        partial++;
                    } else {
                        noMatch++;
                    }
                }
            }
        }

        double before = 100.0 * kept / (kept + pending);
        double after = 100.0 * (kept + accepted) / (kept + pending);
        System.out.printf("干跑回归：已入账 %d / 历史隔离 %d；重放后救回 %d 条"
                        + "（其中 spread 留痕 %d、anchored 留痕 %d、phrase-partial 待裁决 %d、no-match 保持逐出 %d）%n"
                        + "账本完整度 %.1f%% -> %.1f%%%n",
                kept, pending, accepted, spread, anchored, partial, noMatch, before, after);

        assertTrue(accepted >= 500,
                "重放应救回 500 条以上（含留痕档；离线归因预期 509 条），实际 " + accepted);
        assertTrue(anchored > 0,
                "ANCHORED 档应有命中（离线归因预期 14 条），实际 " + anchored);
        assertTrue(noMatch <= 60,
                "保持逐出的条目应控制在 60 条以内（离线归因预期 19 条），实际 " + noMatch);
        assertTrue(after >= 94.0,
                "账本完整度应达到 94% 以上（门分层 + 留痕档；离线归因预期 94.4%），实际 "
                        + String.format("%.1f%%", after));
    }

    /** 干跑依赖本地故事数据：优先按模块目录相对定位，缺失则跳过而非失败 */
    private static Path locateStoryDir() {
        String[] candidates = {
                "../docs/workspace/stories/20260913-story-0001",
                "docs/workspace/stories/20260913-story-0001",
        };
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path.resolve("memory")) && Files.isDirectory(path.resolve("chapters"))) {
                return path;
            }
        }
        return null;
    }
}
