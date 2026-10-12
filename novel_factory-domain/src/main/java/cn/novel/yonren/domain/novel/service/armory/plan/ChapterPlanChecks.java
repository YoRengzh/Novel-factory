package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.service.armory.quality.SuspenseLadderPolicy;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import cn.novel.yonren.types.utils.JsonParseFallback;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class ChapterPlanChecks {

    private ChapterPlanChecks() {
    }

    /**
     * 计划输出宽松解析器：Jackson 默认严格模式会拒绝字符串内未转义的控制字符（如真实换行），
     * 模型长文本输出偶发该问题；枚举按小写 code 大小写不敏感匹配，未知值由调用方归一化。
     * 规划执行器的降级链与 ParseChapterPlanNode 的纵深兜底共用这一个入口
     */
    private static final BeanOutputConverter<ChapterPlanAggregate> CHAPTER_PLAN_CONVERTER =
            new BeanOutputConverter<>(ChapterPlanAggregate.class,
                    com.fasterxml.jackson.databind.json.JsonMapper.builder()
                            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                            .enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                            .build());

    public static ChapterPlanAggregate parseLenient(String raw) {
        return JsonParseFallback.parse(raw, CHAPTER_PLAN_CONVERTER::convert);
    }

    /**
     * 续写编号校正防线：offset>0 且首章编号为 1（批内编号惯性）时，
     * 全量 +offset 平移为全局编号；模型已输出全局编号则不动。
     * 注意平移是幂等安全的——平移后首章不再为 1，重复调用不会再平移。
     */
    public static void normalizeChapterNumbers(ChapterPlanAggregate aggregate, int offset) {
        if (aggregate == null || aggregate.getChapters() == null || aggregate.getChapters().isEmpty() || offset <= 0) {
            return;
        }
        ChapterPlanItemEntity first = aggregate.getChapters().get(0);
        if (first == null || first.getChapterNo() == null || first.getChapterNo() != 1) {
            return;
        }
        for (ChapterPlanItemEntity item : aggregate.getChapters()) {
            if (item != null && item.getChapterNo() != null) {
                item.setChapterNo(item.getChapterNo() + offset);
            }
        }
    }

    /**
     * 段级结构校验：段章数、起始章号连续性、必填字段、编号唯一。
     * 失败信息区分原因（章数/字段/编号），避免"这个参数不能为空"式模糊报错掩盖真实故障
     */
    public static void validateSegmentStructure(List<ChapterPlanItemEntity> chapters,
                                                int expectedChapterCount, int expectedStart) {
        if (chapters == null) {
            throw new AppException(ResponseCode.NULL_EXCEPTION.getCode(),
                    "章节计划输出为空（预期 " + expectedChapterCount + " 章，自第 " + expectedStart + " 章起）");
        }
        if (chapters.size() != expectedChapterCount) {
            throw new AppException(ResponseCode.NULL_EXCEPTION.getCode(),
                    "章节计划章数不符：预期 " + expectedChapterCount + " 章（第 " + expectedStart + "-"
                            + (expectedStart + expectedChapterCount - 1) + " 章），实际 " + chapters.size() + " 章");
        }

        Set<Integer> chapterNoSet = new HashSet<>();
        for (int i = 0; i < chapters.size(); i++) {
            ChapterPlanItemEntity item = chapters.get(i);
            if (item == null) {
                throw new AppException(ResponseCode.NULL_EXCEPTION.getCode(),
                        "章节计划第 " + (expectedStart + i) + " 章（位置 " + (i + 1) + "）为空对象");
            }
            if (item.getChapterNo() == null || item.getChapterNo() != expectedStart + i) {
                throw new AppException(ResponseCode.NULL_EXCEPTION.getCode(),
                        "章节计划编号不连续：第 " + (i + 1) + " 章应编号 " + (expectedStart + i)
                                + "，实际编号 " + item.getChapterNo());
            }
            String missing = missingFields(item);
            if (missing != null) {
                throw new AppException(ResponseCode.NULL_EXCEPTION.getCode(),
                        "章节计划第 " + item.getChapterNo() + " 章字段缺失：" + missing);
            }
            if (!chapterNoSet.add(item.getChapterNo())) {
                throw new AppException(ResponseCode.PARAM_REPETITION.getCode(),
                        "章节计划编号重复：" + item.getChapterNo());
            }
        }
    }

    public static String validateSuspenseAdvance(List<ChapterPlanItemEntity> chapters, List<String> ladder) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        List<SuspenseLadderPolicy.Beat> beats = new ArrayList<>(chapters.size());
        for (int i = 0; i < chapters.size(); i++) {
            ChapterPlanItemEntity item = chapters.get(i);
            if (item == null) {
                continue;
            }
            // 章号可能缺失（上游未规范化）：退回"列表内序号"定位，
            // 避免违规信息里出现"第 0 章"这种无法定位的告警
            int displayNo = item.getChapterNo() == null ? i + 1 : item.getChapterNo();
            beats.add(new SuspenseLadderPolicy.Beat(displayNo, item.getSuspenseBeat(),
                    item.getChapterType() == cn.novel.yonren.types.enums.ChapterTypeVO.TRANSITION));
        }
        List<String> issues = new ArrayList<>();
        // 描述去重**不依赖档位表**，所以放在 usable 判断之外——无蓝图模式同样要拦；
        // 实测：多章回填同一 suspenseBeat 逐字相同时，只做档位下标比较会报过一次就沉默
        issues.addAll(SuspenseLadderPolicy.duplicateBeatViolations(beats));
        if (SuspenseLadderPolicy.usable(ladder)) {
            issues.addAll(SuspenseLadderPolicy.violations(beats, ladder));
        }
        return issues.isEmpty() ? null : String.join("；", issues);
    }

    public static String suspenseFeedback(String issue) {
        return "\n\n【上一版计划被机械驳回】以下问题必须修正（其余部分可保留）：\n- " + issue
                + "\n\n修正要求（逐条执行，**不得只换措辞**）："
                + "\n1. 相邻两章的 suspenseBeat **不得逐字相同**——即使落在同一档位，也必须写出两章各自推进了什么，"
                + "使人一眼能看出差异；"
                + "\n2. 若档位表档数不足以让每章不同（如只有 3 档而本段有 5 章），"
                + "**保留原档位表的档位归类**，另外为每章补充一条「本章独有的推进子项」（谁知道了什么 / 谁做了什么决定 / "
                + "什么被公开），并把它写进该章 keyEvents 与 suspenseBeat 中；"
                + "\n3. 档位索引仍**不得倒退**；非 transition 章仍不得连续 3 章停留同一档；"
                + "\n4. 重新输出**完整**计划（章数不变），suspenseBeat 必须以档位表原文**开头**，"
                + "再附本章独有的推进子项。";
    }

    /**
     * 蓝图是否给出**可用**的悬念档位表（至少 2 档）。
     * "可用"的定义只有一处，prompt 侧与校验侧共用，免得两边对不上
     * （那会让闸门看似存在实则空转）。
     */
    public static boolean hasUsableLadder(StageBlueprintEntity blueprint) {
        return blueprint != null && SuspenseLadderPolicy.usable(blueprint.getSuspenseLadder());
    }

    private static String missingFields(ChapterPlanItemEntity item) {
        if (StringUtils.isBlank(item.getTitle()) || StringUtils.isBlank(item.getGoal())
                || item.getKeyEvents() == null || item.getKeyEvents().isEmpty()) {
            List<String> missing = new ArrayList<>();
            if (StringUtils.isBlank(item.getTitle())) {
                missing.add("title");
            }
            if (StringUtils.isBlank(item.getGoal())) {
                missing.add("goal");
            }
            if (item.getKeyEvents() == null || item.getKeyEvents().isEmpty()) {
                missing.add("keyEvents");
            }
            return String.join("/", missing);
        }
        return null;
    }

    // ==================== 章级主线推进校验 ====================


    public static String validateMainLineAdvance(List<ChapterPlanItemEntity> chapters,
                                                 List<StageBlueprintEntity.MainLineBeat> planned) {
        if (chapters == null || chapters.isEmpty() || planned == null || planned.isEmpty()) {
            return null;
        }
        Map<Integer, String> expected = new HashMap<>();
        for (StageBlueprintEntity.MainLineBeat beat : planned) {
            if (beat != null && beat.getChapterNo() != null && StringUtils.isNotBlank(beat.getAdvance())) {
                expected.put(beat.getChapterNo(), normalizeAdvance(beat.getAdvance()));
            }
        }
        if (expected.isEmpty()) {
            return null;
        }

        List<String> issues = new ArrayList<>();
        String prev = null;
        int prevNo = 0;
        for (ChapterPlanItemEntity item : chapters) {
            if (item == null || item.getChapterNo() == null) {
                continue;
            }
            int no = item.getChapterNo();
            String want = expected.get(no);
            String got = normalizeAdvance(item.getMainLineAdvance());

            if (StringUtils.isBlank(got)) {
                issues.add("第 " + no + " 章未回填 mainLineAdvance"
                        + (want == null ? "" : "（应逐字取自蓝图：" + brief(want) + "）"));
                prev = null;
                continue;
            }
            // 未落地：蓝图对本窗内的章给了内容，就必须对得上。**容错方向取"宽容"**——
            // 模型常会在原文之外补一句解释，故允许"前缀命中 / 互为包含"，只拦真正跑偏的。
            if (want != null && !got.equals(want) && !got.startsWith(want) && !want.startsWith(got)) {
                issues.add("第 " + no + " 章的 mainLineAdvance 与蓝图不符：应为「" + brief(want)
                        + "」，实际「" + brief(got) + "」——必须逐字取自蓝图");
            }
            if (prev != null && got.equals(prev)) {
                issues.add("第 " + prevNo + "–" + no + " 章的 mainLineAdvance **逐字相同**（「" + brief(got)
                        + "」）——相邻两章必须各自推进不同的事");
            }
            prev = got;
            prevNo = no;
        }
        return issues.isEmpty() ? null : String.join("；", issues);
    }

    /** 归一化：去空白与引号（与项目其它文本比对口径一致） */
    private static String normalizeAdvance(String value) {
        return value == null ? "" : value.replaceAll("[\\s\u3000“”\"「」『』]", "");
    }

    /** 违规信息里截断以便阅读 */
    private static String brief(String value) {
        return value == null ? "" : (value.length() <= 30 ? value : value.substring(0, 30) + "…");
    }


    public static String validateTimeAdvance(List<ChapterPlanItemEntity> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        for (ChapterPlanItemEntity c : chapters) {
            if (c == null) {
                continue;
            }
            if (StringUtils.isBlank(c.getTimeAdvance())) {
                missing.add("第" + c.getChapterNo() + "章");
            }
        }
        return missing.isEmpty() ? null : "未声明 timeAdvance：" + String.join("、", missing);
    }

    public static String timeAdvanceFeedback(String issue) {
        return "\n\n【上一版计划的时间推进声明被机械驳回】以下章节缺 timeAdvance：" + issue
                + "\n\n修正要求（逐章执行）："
                + "\n1. 为每一章补声明 timeAdvance（本章结束时的时间：相对上一章推进 3-7 天；"
                + "允许跳月/跳季但必须显式写出推进到何时）；"
                + "\n2. 全段 timeAdvance 必须**单调递增**、不得倒退，并与阶段末目标（stageEndYear/stageEndAge）一致；"
                + "\n3. 这是年龄/故事时间推进的唯一合法通道——不声明，时间锁就永远不会放行。";
    }

    /**
     * 追进度跳接校验：跳接段（蓝图挂 pacing 且滞后 ≥1 年）的末章 timeAdvance 必须显式推进到
     * 预算年份（或其次年——段预算常写作两年区间）。只做包含性检查：timeAdvance 是
     * 自由文本，能写出目标年份即代表跳跃真实发生；缺声明的情形由 {@link #validateTimeAdvance} 负责
     */
    public static String validateTimeAdvancePacing(List<ChapterPlanItemEntity> chapters, Integer budgetStartYear) {
        if (budgetStartYear == null || chapters == null || chapters.isEmpty()) {
            return null;
        }
        ChapterPlanItemEntity last = chapters.get(chapters.size() - 1);
        if (last == null || StringUtils.isBlank(last.getTimeAdvance())) {
            return null;
        }
        for (int y : new int[]{budgetStartYear, budgetStartYear + 1}) {
            if (last.getTimeAdvance().contains(String.valueOf(y))) {
                return null;
            }
        }
        return "末章（第" + last.getChapterNo() + "章）timeAdvance 未推进到预算年份 " + budgetStartYear
                + "（当前声明：" + brief(last.getTimeAdvance()) + "）";
    }

    public static String timeAdvancePacingFeedback(Integer budgetStartYear) {
        return "\n\n【上一版计划的时间跳跃被机械驳回】本段是追进度跳接段：故事时间必须从当前锚年直接"
                + "跳跃到大纲预算年 " + budgetStartYear + " 年。\n修正要求（逐条执行）："
                + "\n1. 第一章开篇即写时间跳跃（「N 年后」式过渡，可配合蒙太奇交代跨越期内的关键变化）；"
                + "\n2. 末章 timeAdvance 必须显式包含年份「" + budgetStartYear + " 年」（如：推进到 "
                + budgetStartYear + " 年秋）；"
                + "\n3. 结转任务一律在跳跃后的时间线下执行或一句带过清偿，不得再展开跳跃前的日常场景。";
    }

    public static String mainLineFeedback(String issue) {
        return "\n\n【上一版计划的章级主线推进被机械驳回】以下问题必须修正：\n- " + issue
                + "\n\n修正要求（逐条执行）："
                + "\n1. 每一章的 mainLineAdvance 必须**逐字取自**上方【章级主线推进】块中该章的原文，"
                + "不得改写、不得自行替换；"
                + "\n2. 若你判断蓝图的安排不合理，**仍要先逐字落地**——蓝图是长视野决策，"
                + "单章落地时无权改写它；确需调整请在该章 keyEvents 里补充说明，而不是改 mainLineAdvance；"
                + "\n3. 相邻两章不得使用同一句推进描述；"
                + "\n4. 重新输出**完整**计划（章数不变）。";
    }

    // ==================== 对话承载校验（2026-10-10） ====================

    /**
     * 对话承载事件下限：normal/climax/finale 章 ≥{@value DIALOGUE_SCENES_MIN}，transition 章 ≥
     * {@link #DIALOGUE_SCENES_MIN_TRANSITION}。**口径只有这一处**——
     * prompt 侧（ChapterPlanPromptService）引用同一常量拼装要求文本，
     * 与 {@link #hasUsableLadder} 同套路，防"两边对不上让闸门看似存在实则空转"。
     */
    static final int DIALOGUE_SCENES_MIN = 2;

    /** transition 章的对话承载事件下限（过渡章允许留白，但仍须一场当面戏） */
    static final int DIALOGUE_SCENES_MIN_TRANSITION = 1;

    /** 声明被认作"逐字复制"的最短归一化长度：低于此长的"声明"无法定位到具体事件，视为未声明 */
    private static final int DIALOGUE_DECLARATION_MIN_CHARS = 8;

    /**
     * **对话承载校验**：把「有没有戏可演」从散文规则变成可机械核验的结构契约。
     *
     * <p><b>为什么必须新增</b>：keyEvents 是自由文本，事件的性质（双人当面戏 / 单人独处）
     * 不在结构里——【对话承载·硬约束】写了"至少 2 个"却无人执法，第 105 章 5 条关键事件
     * 全是"整理私信/写笔记/看屏幕"的单人特写照样放行（characters 里甚至写着"电脑屏幕"）。
     * 声明（dialogueScenes）+ 逐字比对 + 人名计数，就是这条规则的执法通道。
     *
     * <p>逐章核验五条：
     * <ol>
     *   <li><b>存在</b>：dialogueScenes 缺失/空 = 没契约（字段不进输出等于不存在）；</li>
     *   <li><b>数量</b>：有效声明（逐字命中 keyEvents 的**不同**事件数）达标；</li>
     *   <li><b>诚实</b>：声明必须逐字取自本章 keyEvents（归一化后互为包含），防占位虚报；</li>
     *   <li><b>双人</b>：匹配到的 keyEvent 须写到 ≥2 个人名——"谁对谁"的全名落点，代词不算；</li>
     *   <li><b>占比</b>：非 transition 章"单人事件"（人名 &lt;2）不得超过一半——治第 96 章的
     *       "2 条合规但其余全是独白"逃逸形态。</li>
     * </ol>
     *
     * <p><b>人名来源</b>：角色账本名册（rosterNames，调用方传入）∪ 本章 characters——
     * 并集容忍"本章首次登场、账本还没有的新角色"；防"电脑屏幕"这类伪角色靠的是
     * 它必须先被列进 characters 这道显式动作，同时被占比检查与体检观测兜住——
     * 软闸门不为跟模型军备竞赛。
     *
     * <p><b>降级</b>：人名池不足 2 个（新书首段、characters 只列一人）时，人名与占比两条
     * 跳过（无人可数，强查只会全章误报），存在/数量/诚实三条仍执法。
     *
     * <p>软闸门：与其它推进校验同款——带反馈重规划一次，二次仍不过则告警放行。
     */
    public static String validateDialogueCarriage(List<ChapterPlanItemEntity> chapters,
                                                  List<String> rosterNames) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        List<String> issues = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            ChapterPlanItemEntity item = chapters.get(i);
            if (item == null) {
                continue;
            }
            // 章号可能缺失（上游未规范化）：退回"列表内序号"定位（与 validateSuspenseAdvance 同口径）
            int no = item.getChapterNo() == null ? i + 1 : item.getChapterNo();
            boolean transition = item.getChapterType() != null && item.getChapterType().isTransition();
            int required = transition ? DIALOGUE_SCENES_MIN_TRANSITION : DIALOGUE_SCENES_MIN;
            List<String> events = item.getKeyEvents();
            if (events == null || events.isEmpty()) {
                // 结构性缺失由 validateSegmentStructure 负责（缺 keyEvents 在那里已判死），不重复报
                continue;
            }
            List<String> scenes = item.getDialogueScenes();
            if (scenes == null || scenes.isEmpty()) {
                issues.add("第 " + no + " 章未声明 dialogueScenes：本章需 ≥" + required
                        + " 条对话承载事件（逐字取自 keyEvents 复制进该字段）");
                continue;
            }
            // 声明诚实性 + 有效数量：逐字命中 keyEvents 的不同事件才算数（同一条重复声明只计一次）
            Set<Integer> matched = new LinkedHashSet<>();
            for (String scene : scenes) {
                if (StringUtils.isBlank(scene)) {
                    continue;
                }
                int idx = matchEventIndex(events, scene);
                if (idx < 0) {
                    issues.add("第 " + no + " 章的 dialogueScenes「" + brief(scene)
                            + "」与本章关键事件对不上——必须逐字复制，不得改写或另写");
                } else {
                    matched.add(idx);
                }
            }
            if (matched.size() < required) {
                issues.add("第 " + no + " 章有效对话承载事件仅 " + matched.size() + " 条（要求 ≥" + required
                        + "）——把更多关键事件写成双人当面戏");
            }
            // 人名与占比：池不足 2 人时降级（无人可数，强查只会全章误报）
            List<String> pool = namePool(rosterNames, item.getCharacters());
            if (pool.size() < 2) {
                continue;
            }
            for (int idx : matched) {
                String event = events.get(idx);
                int names = countNames(event, pool);
                if (names < 2) {
                    issues.add("第 " + no + " 章对话承载事件「" + brief(event) + "」只写到 " + names
                            + " 个人物全名——必须写明谁对谁（代词不算）");
                }
            }
            if (!transition) {
                long solo = events.stream().filter(e -> countNames(e, pool) < 2).count();
                if (solo * 2 > events.size()) {
                    issues.add("第 " + no + " 章单人事件 " + solo + "/" + events.size()
                            + " 过半——独处/内心梳理推进需改为双人当面戏");
                }
            }
        }
        return issues.isEmpty() ? null : String.join("；", issues);
    }

    /** 违规信息回注规划层的差异化修正指令：核心是解开"蓝图逐字锁"与"要有戏"的死结 */
    public static String dialogueCarriageFeedback(String issue) {
        return "\n\n【上一版计划的对话承载被机械驳回】以下问题必须修正（其余部分可保留）：\n- " + issue
                + "\n\n修正要求（逐条执行，**不得只换措辞**）："
                + "\n1. mainLineAdvance **照旧逐字取自蓝图**——蓝图定的主线里程碑是本章的**落点**，"
                + "不是**事件本体**：里程碑不许改，但必须由 ≥2 场双人对峙/盘问/合作/拒绝承载"
                + "（如「把发现记录在蓝皮本」应演成「被同伴撞见锁抽屉，两人就『你还在查什么』对峙，"
                + "他在拒答中完成记录」——同一信息落点，戏是补出来的，不是改蓝图）；"
                + "\n2. 每条对话承载事件写明**谁对谁、围绕什么分歧**，人物一律用**全名**（代词不计入校验）；"
                + "\n3. 把这 ≥2 条事件（transition 章 ≥1 条）**逐字复制**进对应章的 dialogueScenes 字段；"
                + "\n4. 非 transition 章单人事件不得过半——主角需要「想明白一件事」时，让它在对话中发生；"
                + "\n5. 严禁用寒暄与注水凑对话事件——单场景内反复确认已知状态的对话会被【注水反馈】驳回；"
                + "\n6. 重新输出**完整**计划（章数不变）。";
    }

    /** 声明 → 事件定位：归一化后互为包含（容忍前后缀差），返回首个命中的事件下标，无命中 -1 */
    private static int matchEventIndex(List<String> events, String declared) {
        String d = normalizeAdvance(declared);
        if (d.length() < DIALOGUE_DECLARATION_MIN_CHARS) {
            return -1;
        }
        for (int i = 0; i < events.size(); i++) {
            String e = normalizeAdvance(events.get(i));
            if (e.length() < 4) {
                continue;
            }
            if (e.contains(d) || d.contains(e)) {
                return i;
            }
        }
        return -1;
    }

    /** 人名池：账本名册 ∪ 本章 characters，按归一化去重（两处来源任一为 null 安全） */
    private static List<String> namePool(List<String> rosterNames, List<String> characters) {
        Set<String> seen = new LinkedHashSet<>();
        appendNames(seen, rosterNames);
        appendNames(seen, characters);
        return new ArrayList<>(seen);
    }

    private static void appendNames(Set<String> seen, List<String> names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (StringUtils.isNotBlank(name)) {
                seen.add(normalizeAdvance(name));
            }
        }
    }

    /** 事件文本中出现的不同人名个数（归一化包含判定；空名已在上游过滤） */
    private static int countNames(String text, List<String> pool) {
        String t = normalizeAdvance(text);
        int count = 0;
        for (String name : pool) {
            if (!name.isEmpty() && t.contains(name)) {
                count++;
            }
        }
        return count;
    }
}
