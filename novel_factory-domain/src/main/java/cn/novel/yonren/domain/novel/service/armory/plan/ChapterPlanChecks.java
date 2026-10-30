package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.service.armory.quality.ForeshadowSpanPolicy;
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
     * 规划执行器的降级链与 ParseChapterPlanNode 的纵深兜底共用这一个入口。
     *
     * <p><b>必须关闭未知字段即失败</b>：模型常在同一 JSON 里顺手多输出 notes/tips 之类的键，
     * 严格模式会让整份计划解析失败（四级文本修复救不了语义错误），本批直接终止；
     * 未知枚举读成 null 而不是抛异常，才能让 ParseChapterPlanNode 的
     * “未知 chapterType 归一化为 NORMAL” 兜底真正执行。与 ChapterSummaryService /
     * ChapterReviseService 的转换器保持同一口径
     */
    private static final BeanOutputConverter<ChapterPlanAggregate> CHAPTER_PLAN_CONVERTER =
            new BeanOutputConverter<>(ChapterPlanAggregate.class,
                    com.fasterxml.jackson.databind.json.JsonMapper.builder()
                            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                            .enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .enable(com.fasterxml.jackson.databind.DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
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

    /** 归一化：去空白与引号（与项目其它文本比对口径一致；实现复用仓库统一入口，勿另写正则） */
    private static String normalizeAdvance(String value) {
        return ForeshadowSpanPolicy.normalize(value);
    }

    /** 违规信息里截断以便阅读 */
    private static String brief(String value) {
        return value == null ? "" : (value.length() <= 30 ? value : value.substring(0, 30) + "…");
    }


    /**
 * timeAdvance 完整性检查 由「必填」改为「选填」。
     *
     * <p><b>为什么不再必填</b>：原实现要求每章都声明时间推进，缺失即驳回。这等于把"时间推进"
     * 列为每章的交付项——事件只占一个下午的章也必须交出一段时间，模型只能拿"第二天""过了几日"
     * 这类无信息过渡去凑。时间应当是情节的背景，不是每章的配额。
     *
     * <p>现在空值本身合法：表示本章不跨时间，写手按同日连续处理。
     */
    public static String validateTimeAdvance(List<ChapterPlanItemEntity> chapters) {
        // 选填语义下无完整性要求，保留方法签名供调用方沿用。
        return null;
    }

    public static String timeAdvanceFeedback(String issue) {
        // 选填后不再因缺失而驳回；保留文案供需要提示的调用点使用。
        return "\n\n【时间声明提示】以下章节的时间表述无法解析：" + issue
                + "\n\ntimeAdvance 是**选填**的：本章若都在同一天，留空即可，不必为凑数编一个日期。"
                + "需要跨时间时再写（如“次日清晨”“三日后”），写实到哪天就到哪天。";
    }

    /**
     * **跳月不得吞线**：某章 timeAdvance 一次跨越 ≥1 个月时，该章（或紧随其后的一章）必须显式
     * 收束上一段铺开的待收线索，不能一句"日子被试卷切碎"就把整段过程带过。
     *
     * <p><b>治什么</b>：实测某段五章里安排两次跳月（+2 月、+2.5 月），把前后九章铺开的
     * 「苏家观察员」「Vossner 座谈」两条线在跳月叙述里整段吞掉——观察员线前面埋了 9 章，
     * 收尾只有一句"把记录件塞回枕下"；座谈座位表刚排出来就再没出现。读者视角是"刚铺就断"。
     *
     * <p><b>判据为什么用"待收线索关键词"而不是语义理解</b>：机械层没有语义能力，能做的是
     * 逐字比对——把待收线索的描述切成关键词，看跳月章的关键事件里有没有命中。
     * 命中即认为"这条线在本章被交代过"。宁可漏报（关键词切不准就不报）也不误报，
     * 与其它规划闸门同款保守口径。
     *
     * <p><b>跳月判定</b>：从 timeAdvance 文本里抽"推进约 N 个月 / 跳月 / N 个月后 / 跨月"等表述，
     * 或年份发生变化（跨年必然跨月）。只认显式写法——模型若写"推进到九月十五日"而上一章是
     * 七月，文本里没有"月"字但日期跨度大，这类由 {@code timeAdvance} 的单调性负责，本闸门不猜。
     *
     * @param chapters   本段计划
     * @param openThreads 上段遗产：尚未回收的伏笔内容（可为 null/空，此时不校验）
     * @return null = 通过或无跳月；非 null = 违规说明（调用方带反馈重规划）
     */
    public static String validateJumpDoesNotSwallowThreads(List<ChapterPlanItemEntity> chapters,
                                                          List<String> openThreads) {
        if (chapters == null || chapters.isEmpty() || openThreads == null || openThreads.isEmpty()) {
            return null;
        }
        List<String> swallowed = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            ChapterPlanItemEntity chapter = chapters.get(i);
            if (chapter == null || StringUtils.isBlank(chapter.getTimeAdvance())
                    || !isMonthJump(chapter.getTimeAdvance())) {
                continue;
            }
            // 跳月章的"交代窗口"= 本章 + 下一章（跳月常在一章末尾发生，收束写在下一章开头）
            String window = eventsText(chapter)
                    + (i + 1 < chapters.size() ? eventsText(chapters.get(i + 1)) : "");
            List<String> missed = unmatchedThreads(openThreads, window);
            if (!missed.isEmpty()) {
                swallowed.add("第" + chapter.getChapterNo() + "章跳月（" + brief(chapter.getTimeAdvance())
                        + "）期间未交代 " + missed.size() + " 条已铺开的线：" + String.join("；", missed));
            }
        }
        return swallowed.isEmpty() ? null : String.join("｜", swallowed);
    }

    /** 跳月违规的修正指令（与其它规划闸门同款：点名 + 可执行改法） */
    public static String jumpSwallowFeedback(String issue) {
        return "\n\n【上一版计划的时间跳跃吞掉了已铺开的线索】以下问题必须修正：\n- " + issue
                + "\n\n修正要求（逐条执行，**不得只换措辞**）："
                + "\n1. 跳月那章（或其下一章）必须**显式落地**上面点名的每条线："
                + "在关键事件里写出它在本段时间内发生了什么结果——不必展开全过程，"
                + "但必须有可核验的落点（谁做了什么决定、什么被确认、什么被公开）；"
                + "\n2. 严禁用「这期间一切如常」「日子被试卷切碎」这类概述替代具体落点；"
                + "\n3. 若某条线确实要在跳月后才推进，就不许在本段跳月——把跳月挪到该线收束之后；"
                + "\n4. 重新输出**完整**计划（章数不变）。";
    }

    /** timeAdvance 是否表示"一次跨越 ≥1 个月"（只认显式写法，不做日期推算） */
    static boolean isMonthJump(String timeAdvance) {
        if (StringUtils.isBlank(timeAdvance)) {
            return false;
        }
        String text = timeAdvance.trim();
        // 显式"月"表述：跳月 / 跨月 / N 个月 / 两个半月 / 数月
        if (text.matches(".*(跳月|跨月|几个月|数月|半[个]?月).*")) {
            return true;
        }
        if (text.matches(".*[0-9一二三四五六七八九十两]+\\s*个半月.*")) {
            return true;
        }
        if (text.matches(".*[0-9一二三四五六七八九十两]+\\s*个月.*")) {
            return true;
        }
        // 跨年（必然跨月）：出现"次年/第二年/N 年后/跨年"
        return text.matches(".*(次年|第二年|跨年|[0-9一二三四五六七八九十]+\\s*年后).*");
    }

    /** 章节关键事件的拼接文本（归一化，供关键词命中判定） */
    private static String eventsText(ChapterPlanItemEntity chapter) {
        if (chapter == null || chapter.getKeyEvents() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String event : chapter.getKeyEvents()) {
            if (StringUtils.isNotBlank(event)) {
                sb.append(normalizeAdvance(event));
            }
        }
        return sb.toString();
    }

    /**
     * 待收线索里**未被窗口文本命中**的那些。
     *
     * <p>判据取"命中任一段落级关键词即算交代"：机械层没有分词器，一条伏笔描述切出来的
     * 片段质量参差（"随营观察"能命中，"的表现"命中不了）。要求过半命中会把
     * "只写了主体、没写修饰"的正常交代也判成未交代——那正是误报。
     * 反过来只要求命中一段，意味着模型只要提到这条线的核心名词就算过关，
     * 宁可漏报（写得含糊也算交代）也不误伤，与其它规划闸门同款保守口径。
     */
    private static List<String> unmatchedThreads(List<String> openThreads, String window) {
        List<String> missed = new ArrayList<>();
        for (String thread : openThreads) {
            if (StringUtils.isBlank(thread)) {
                continue;
            }
            List<String> keywords = threadKeywords(thread);
            if (keywords.isEmpty()) {
                continue;
            }
            boolean hit = keywords.stream().anyMatch(window::contains);
            if (!hit) {
                missed.add(brief(thread));
            }
        }
        return missed;
    }

    /**
     * 把一条伏笔描述切成关键词锚点。
     *
     * <p><b>为什么用滑窗而不是整段</b>：中文没有词分隔，按标点切出来的往往是整句
     * （"苏家旁支观察员随营观察"），而正文里写的是"苏家旁支观察员完成首次记录"——
     * 整句比对永远命中不了。故对每个标点分段再做 3 字滑窗，任一窗口命中即算提到过这条线。
     *
     * <p><b>为什么是 3 字</b>：2 字窗口（"观察""座谈"）太泛，几乎任何正文都能撞上；
     * 4 字以上切不出足够的窗口。3 字是"能命中又不至于过宽"的折中。
     * 同一分段内窗口去重，避免长句产生大量重复锚点。
     */
    private static List<String> threadKeywords(String thread) {
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (String raw : thread.split("[\\s，。；：、（）()「」《》【】,.。:;!?！？]+")) {
            String segment = normalizeAdvance(raw);
            if (segment.length() < KEYWORD_ANCHOR_LENGTH) {
                continue;
            }
            for (int i = 0; i + KEYWORD_ANCHOR_LENGTH <= segment.length(); i++) {
                tokens.add(segment.substring(i, i + KEYWORD_ANCHOR_LENGTH));
            }
        }
        return new ArrayList<>(tokens);
    }

    /** 关键词锚点长度：3 字（见 {@link #threadKeywords} 的取舍说明） */
    private static final int KEYWORD_ANCHOR_LENGTH = 3;


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

    // ==================== 跨章场景重演校验（2026-10-09） ====================

    /**
     * 判"同一场景/同一动作被写了两遍"的判定线：归一化后最长公共连续片段 ≥ 该字数。
     *
     * <p><b>标定样本（第 96-100 章真实计划实测）</b>：
     * <ul>
     *   <li>真重复：第 96-99 章关键事件共享 15 字（「翻到主角甲算术本最后一页，指尖」，
     *       两章连正文都写了同一个场景，第 99 章原文还用"再次"衔接）；第 96-98 章共享 11 字
     *       （「主角甲在房间里把角色丙…」，同一个人在同一房间处理同一批信）；
     *       第 97-100 章共享 19 字（同一条伏笔的回收被安排了两遍）；</li>
     *   <li>跨字段：第 98-99 章的 suspenseBeat/mainLineAdvance 共享 <b>56 字</b>——
     *       两章逐字用了同一句档位原文，只因第 99 章在**后面追加**了一句本章子项，
     *       就绕过了现有的"相邻逐字相同"检查；</li>
     *   <li>误报基线：本批其余两两组合最长只有 4-7 字（人名、地点、标点串）。</li>
     * </ul>
     * 取 10：落在误报基线（≤7）与真重复（≥11）之间且留有余量。
     */
    static final int SCENE_REPLAY_MIN_MATCH = 10;

    /**
     * **跨章场景重演校验**：段内任意两章（**不限于相邻**）的关键事件若共享一段较长的连续文本，
     * 判为同一场景/同一动作被安排了两遍；悬念档位与章级推进字段之间同样比对。
     *
     * <p><b>为什么必须新增</b>：现有去重只看**相邻**两章——{@code duplicateBeatViolations}
     * 与 {@link #validateMainLineAdvance} 都只比对"当前 vs 上一章"，且都要求**逐字相同**。
     * 于是两种逃逸同时存在：①相隔两章的同一场景（第 96 与第 99 章）；②在共用原文后**追加一句**
     * 本章子项（第 98 与第 99 章）——后者恰是 prompt 自己建议的写法（"档位原文之后附本章独有的
     * 推进子项"），等于给出了绕过检查的标准姿势。
     *
     * <p><b>为什么用归一化 LCS 而不是相似度</b>：{@code UsedPatternPolicy} 记着这条教训——
     * 同一套路的 3-gram Jaccard 只有 0.02-0.09，抓不到；结构化标签或归一化 LCS 才行。
     *
     * <p>软闸门：与其它推进校验同款——带反馈重规划一次，二次仍不过则告警放行
     * （只有追进度跳接是 fail-closed，理由不适用这里）。
     */
    public static String validateSceneReplay(List<ChapterPlanItemEntity> chapters) {
        return validateSceneReplay(chapters, null);
    }

    /** 带档位表的重载：调用方持有蓝图时传入，消除"同档位+各自独有推进"的误报 */
    public static String validateSceneReplay(List<ChapterPlanItemEntity> chapters, List<String> suspenseLadder) {
        if (chapters == null || chapters.size() < 2) {
            return null;
        }
        // 推进指纹按章只算一次：剥离已命中的档位原文后，两两比对"独有推进"
        List<String> advances = new ArrayList<>(chapters.size());
        for (ChapterPlanItemEntity chapter : chapters) {
            advances.add(advanceFingerprint(chapter, suspenseLadder));
        }
        List<String> issues = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            ChapterPlanItemEntity a = chapters.get(i);
            if (a == null) {
                continue;
            }
            for (int j = i + 1; j < chapters.size(); j++) {
                ChapterPlanItemEntity b = chapters.get(j);
                if (b == null) {
                    continue;
                }
                // 同一对章至多报一条：先报事件重演（更常见、更好改），命中就不再报推进重合
                String sharedEvents = sharedSpan(sceneFingerprint(a), sceneFingerprint(b));
                if (sharedEvents != null) {
                    issues.add("第 " + a.getChapterNo() + " 与第 " + b.getChapterNo()
                            + " 章的关键事件高度重合（共享「" + sharedEvents + "」）——同一场景/同一动作"
                            + "不得在段内两章重演，请合并到一章，或改写其中一章的事件与推进");
                    continue;
                }
                String sharedAdvance = sharedSpan(advances.get(i), advances.get(j));
                if (sharedAdvance != null) {
                    issues.add("第 " + a.getChapterNo() + " 与第 " + b.getChapterNo()
                            + " 章的悬念档位/主线推进描述重合（共享「" + brief(sharedAdvance) + "」）——"
                            + "同一件事被排给了两章：请让其中一章改为推进别的事，"
                            + "或把两章合成一章（在共用原文后追加一句话不算换了一件事）");
                }
            }
        }
        return issues.isEmpty() ? null : String.join("；", issues);
    }

    /**
     * 事件指纹：关键事件逐条归一化后相连。
     *
     * <p>**刻意不含 characters**：人物名相连会拼出长片段（5 个角色名就有 15 字），
     * 同一批配角反复出场是正常的，把它算进来会让"同一批人在场"被误判成"同一场景"。
     * 实测人物名单独出现时把误报基线从 4-6 抬到 6-7，收益不足、风险实在。
     */
    private static String sceneFingerprint(ChapterPlanItemEntity item) {
        StringBuilder sb = new StringBuilder();
        if (item.getKeyEvents() != null) {
            for (String event : item.getKeyEvents()) {
                if (StringUtils.isNotBlank(event)) {
                    sb.append(normalizeAdvance(stripForeshadowTemplate(event)));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 剥离关键事件里的伏笔登记模板前缀（2026-10-12）。
     *
     * <p><b>治什么</b>：规划层要求每章末尾登记一条伏笔，格式固定为
     * {@code （伏笔埋设）埋设第N章计划中的《…》：<实际内容>}。该模板在多章之间**逐字相同**，
     * 归一化后共享片段「。（伏笔埋设）埋设第13」就有 12 字，越过判定线 10 字
     * ⇒ 段校验误报"关键事件高度重合"，白白触发一轮重规划（实测第 131-135 章段：
     * 132 与 133/134 章两两被误报，二次重规划仍未过、被迫告警放行）。
     *
     * <p>模板之后的内容才是本章独有的伏笔设计——**只剥前缀、不剥内容**，
     * 真重演（两章排了同一条伏笔的同一动作）仍会被抓到。
     */
    private static String stripForeshadowTemplate(String event) {
        if (event == null) {
            return null;
        }
        String text = event.trim();
        if (!text.startsWith("（伏笔埋设）") && !text.startsWith("（伏笔回收）")
                && !text.startsWith("(伏笔埋设)") && !text.startsWith("(伏笔回收)")) {
            return text;
        }
        // 模板形如「（伏笔埋设）埋设第131章计划中的《…》：实际内容」——取首个全角/半角冒号之后的部分；
        // 无冒号（模型未按格式写）时退化为整条保留，不做过度剥离
        int colon = text.indexOf('：');
        if (colon < 0) {
            colon = text.indexOf(':');
        }
        return colon >= 0 && colon + 1 < text.length() ? text.substring(colon + 1).trim() : text;
    }

    /**
     * 推进指纹：悬念档位 + 章级主线推进（两者合看，任一被复述都算"同一件事被排给两章"）。
     *
     * <p><b>已命中的档位表原文先剥离</b>（2026-10-12）：prompt 侧要求"suspenseBeat 以档位表原文
     * 开头，再附本章独有的推进子项"——两章落在同一档位时，共享的档位原文（50+ 字）远超判定线
     * 10 字，会把"同档位+各自独有推进"这个 prompt 自己要求的正常形态误判成重合（实测第 121-125
     * 章段：121-123 同档位4、124-125 同档位5，全部被本项误报，与档位序校验的空格误报叠加后
     * 反馈被噪声淹没，二次重规划仍未过，被迫告警放行）。剥离只对能**唯一定位**的章生效
     * （{@code resolveIndex >= 0}）；<b>关键事件侧不豁免</b>——同档位下两章排同一件关键事件
     * （实测 121/125 章都排了"日历背面补写短杠"）是真重演，必须保留执法。
     */
    private static String advanceFingerprint(ChapterPlanItemEntity item, List<String> suspenseLadder) {
        String beat = normalizeAdvance(item.getSuspenseBeat());
        int ladderIndex = SuspenseLadderPolicy.resolveIndex(item.getSuspenseBeat(), suspenseLadder);
        if (ladderIndex >= 0) {
            String entry = normalizeAdvance(suspenseLadder.get(ladderIndex));
            if (!entry.isEmpty() && beat.startsWith(entry)) {
                beat = beat.substring(entry.length());
            }
        }
        return beat + normalizeAdvance(item.getMainLineAdvance());
    }

    /** 共享连续片段达到判定线时返回该片段，否则 null */
    private static String sharedSpan(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return null;
        }
        String span = ForeshadowSpanPolicy.longestCommonSubstringText(a, b);
        return span.length() >= SCENE_REPLAY_MIN_MATCH ? span : null;
    }

    public static String sceneReplayFeedback(String issue) {
        return "\n\n【上一版计划的跨章场景重演被机械驳回】以下问题必须修正：\n- " + issue
                + "\n\n修正要求（逐条执行，**不得只换措辞**）："
                + "\n1. 同一场景或同一物理动作（取信/折信/塞信/收进本子这类流程）只允许占用段内**一章**："
                + "已在前章做过的动作，在后章不得再完整做一遍——要么合并到前章，要么让后章从"
                + "「动作已经完成」的状态直接进戏；"
                + "\n2. 同一件主线推进不得排给两章：两章的 mainLineAdvance/suspenseBeat 若指向同一件事，"
                + "保留其中一章，另一章改为推进别的事；"
                + "\n3. 在档位原文之后**追加一句话**并不能让两章变成不同的推进——"
                + "共用的原文部分必须真正不同（谁知道了什么 / 谁做了什么决定 / 什么被公开）；"
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

    // ==================== 事件密度基准（2026-10-13） ====================

    /**
     * 常规章关键事件数基准：常态供给块与密度反馈块**共用此一处**。
     *
     * <p><b>为什么要单点</b>：此前常态块写"以 4 条为基准"、密度反馈块写"每章关键事件 ≥ 3 个"——
     * 同一 prompt 内两条供给基准不一致，而反馈块是**纠偏**用的，要求反比常态更低，
     * 等于让模型取低线即合规，反馈块效力被常态块稀释。与 {@link #DIALOGUE_SCENES_MIN} 同套路。
     */
    static final int KEY_EVENTS_BASELINE_NORMAL = 4;

    /** 高潮/收束章的关键事件下限：信息密度最高的章写薄了等于白给 */
    static final int KEY_EVENTS_BASELINE_CLIMAX = 4;

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
