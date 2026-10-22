package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ConsistencyIndexEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将已通过证据校验的章节摘要投影为跨章一致性索引，不替代三账本。 */
@Service
public class ConsistencyIndexService {

    /**
     * 每节渲染条数上限（2026-10-12 由 20 收紧到 12）：索引随连载线性增长，125 章时整块 2.9k
     * 字符，是正文前缀预算饱和（章章 99-100%）挤出三账本/伏笔账的主力之一。上限只影响
     * {@link #renderPrompt} 渲染视图，不截断底层数据（审计与排期仍看全量 index）。
     */
    private static final int MAX_RENDER_ENTRIES = 12;

    /**
     * 时间线按**章号**窗口：只渲染最近 W 章的条目（2026-10-12）。
     * 时间线是"每章一条"的心跳数据，老条目与近章摘要（覆盖近 10 章）和三账本重复，
     * 且随连载线性膨胀。按章号而非插入序截取：TIMELINE 事实与每章 timePoint 混排时不依赖插入序。
     */
    private static final int TIMELINE_WINDOW_CHAPTERS = 12;

    /** 年龄类关键数字的识别词：subject 命中即视为年龄事实（如"主角甲月龄""主角年龄"） */
    private static final String[] AGE_SUBJECT_KEYWORDS = {"月龄", "年龄", "岁数"};
    public ConsistencyIndexEntity rebuild(List<ChapterSummaryEntity> summaries, StoryVO storyVO) {
        ConsistencyIndexEntity index = ConsistencyIndexEntity.builder().build();
        if (summaries == null) return index;
        Map<String, ConsistencyIndexEntity.InjuryEntry> injuries = new LinkedHashMap<>();
        Map<String, ConsistencyIndexEntity.MechanismEntry> mechanisms = new LinkedHashMap<>();
        for (ChapterSummaryEntity summary : summaries) {
            if (summary == null || summary.getChapterNo() == null) continue;
            Integer chapterNo = summary.getChapterNo();
            if (!blank(summary.getTimePoint())) index.getTimeline().add(new ConsistencyIndexEntity.TimelineEntry(summary.getSummary(), chapterNo, summary.getTimePoint(), null));
            if (Boolean.TRUE.equals(summary.getCheatMechanismUsed()) && cheatRuleEnabled(storyVO)) {
                String name = storyVO.getFeatures().getCheatMechanismName();
                if (blank(name)) name = "未命名金手指";
                mechanisms.put(name, new ConsistencyIndexEntity.MechanismEntry(name, "故事设定机制", true, chapterNo, usageInterval(storyVO)));
            }
            for (ChapterSummaryEntity.StateEntry state : safe(summary.getCharacterStates())) {
                if (state == null || blank(state.getName()) || blank(state.getStatus()) || !containsInjury(state.getStatus())) continue;
                String key = state.getName() + "#" + injuryLocation(state.getStatus());
                ConsistencyIndexEntity.InjuryEntry prior = injuries.get(key);
                injuries.put(key, new ConsistencyIndexEntity.InjuryEntry(state.getName(), injuryLocation(state.getStatus()), injurySeverity(state.getStatus()), prior == null ? chapterNo : prior.getFirstChapter(), state.getStatus(), state.getEvidence()));
            }
            for (ChapterSummaryEntity.StateEntry state : safe(summary.getItemStates())) {
                if (state == null || blank(state.getName())) continue;
                String lower = (state.getName() + " " + nullToBlank(state.getStatus())).toLowerCase(Locale.ROOT);
                if (isMechanism(lower) && cheatRuleEnabled(storyVO)) mechanisms.put(state.getName(), new ConsistencyIndexEntity.MechanismEntry(state.getName(), "摘要物品状态", true, chapterNo, usageInterval(storyVO)));
            }
            appendConsistencyFacts(index, injuries, summary, chapterNo);
        }
        index.setInjuries(new ArrayList<>(injuries.values()));
        index.setMechanisms(new ArrayList<>(mechanisms.values()));
        return index;
    }

    public boolean cheatRuleEnabled(StoryVO storyVO) { return storyVO != null && storyVO.getFeatures() != null && Boolean.TRUE.equals(storyVO.getFeatures().getHasCheatMechanism()); }

    /** 全篇允许的机制原理大段描述次数上限（首次建立 + 关键升级），超出即为违规 */
    public static final int MAX_MECHANISM_DESCRIPTIONS = 2;

    /** 机制原理描述关键词（段落同时含机制名与其中任一关键词，即计一次"原理描述"） */
    private static final String[] MECHANISM_DESCRIPTION_KEYWORDS = {
            "原理", "本质", "运作", "转换", "视界", "代码流", "拓扑", "底层逻辑",
            "运作机制", "的本质是", "的原理是", "不再是", "分解成", "变成了"};

    /** 未显式配置机制名时的兜底名表（与 rebuild 的 isMechanism 口径一致） */
    private static final String[] DEFAULT_MECHANISM_NAMES = {
            "代码视界", "金手指", "系统", "面板", "剑意提取"};

    /** 显式配置的机制名；未配置返回 null（调用方回退默认名表） */
    private String mechanismName(StoryVO storyVO) {
        return storyVO == null || storyVO.getFeatures() == null
                ? null : storyVO.getFeatures().getCheatMechanismName();
    }

    /**
     * 本章正文是否"有意义提及"了金手指（机制全名或保守拆分别名命中）。
     *
     * <p>用途：本组检查已改为在**质量门内、摘要落盘之前**执行（要能触发修订），
     * 而摘要还没生成 ⇒ 必须回读正文，否则"本章明明用了金手指、只因摘要滞后"会被判超期未使用，
     * 白白烧一轮整章修订。
     */
    public boolean mentionsMechanism(String content, StoryVO storyVO) {
        if (blank(content)) return false;
        String name = mechanismName(storyVO);
        return blank(name) ? containsAny(content, DEFAULT_MECHANISM_NAMES)
                : containsAny(content, mechanismTerms(name));
    }

    /**
     * 本章正文是否出现"机制名 + 原理解释关键词"的大段描述（段落 ≥ 50 字，每章最多计一次）。
     * 与 {@link #mentionsMechanism} 的区别：本方法要求<em>大段解释原理</em>，只是提到名字不算。
     * 机械统计结果由 ChapterWorker 写入摘要 {@code mechanismDescribed}，供跨批累计。
     */
    public boolean describesMechanism(String content, StoryVO storyVO) {
        if (blank(content)) return false;
        String name = mechanismName(storyVO);
        for (String paragraph : content.split("\\n+")) {
            if (paragraph.length() < 50) continue;
            boolean hasMechanism = blank(name)
                    ? containsAny(paragraph, DEFAULT_MECHANISM_NAMES)
                    : paragraph.contains(name);
            if (hasMechanism && containsAny(paragraph, MECHANISM_DESCRIPTION_KEYWORDS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 金手指超期未使用检测：每个完整间隔只报一次；没有明确金手指设定时始终返回空。
     *
     * <p><b>interval &lt;= 1 直接放行</b>（2026-10-01 补）：间隔为 1 意味着"每章都必须用"，
     * 此时 {@code gap % interval} 恒为 0 ⇒ 只要某章没用，其后的**每一章**都会命中。
     * 实测该配置下 5 章 5 条 BLOCKING、章章修订两轮耗尽仍不收敛——因为写手根本无法
     * 在"必须用金手指"与"金手指没额度"之间同时满足，这是一种自我不可能的要求。
     * 间隔 1 本身是把"每章都得用"写成了配置，但那条要求应当由规划层表达，机械门禁只做兜底。
     *
     * @param currentContent 本章正文，可为 null。非空且本章已提及机制时直接放行——本检查在质量门内
     *                       于摘要落盘前执行，只有回读正文才能避免"本章用了但摘要还没生成"的误判
     */
    public List<ChapterIssueEntity> mechanismUsageIssues(List<ChapterSummaryEntity> summaries, StoryVO storyVO,
                                                         int chapterNo, String currentContent) {
        if (!cheatRuleEnabled(storyVO)) return List.of();
        if (mentionsMechanism(currentContent, storyVO)) return List.of();
        return mechanismUsageIssues(summaries, storyVO, chapterNo);
    }

    /** 兼容重载：不提供本章正文，仅按摘要判定（摘要已落盘时的口径） */
    public List<ChapterIssueEntity> mechanismUsageIssues(List<ChapterSummaryEntity> summaries, StoryVO storyVO, int chapterNo) {
        if (!cheatRuleEnabled(storyVO)) return List.of();
        int interval = usageInterval(storyVO);
        if (interval <= 1) return List.of();
        int lastUsed = summaries == null ? 0 : summaries.stream()
                .filter(summary -> summary != null && summary.getChapterNo() != null
                        && summary.getChapterNo() <= chapterNo && Boolean.TRUE.equals(summary.getCheatMechanismUsed()))
                .mapToInt(ChapterSummaryEntity::getChapterNo).max().orElse(0);
        int gap = chapterNo - lastUsed;
        if (gap < interval || gap % interval != 0) return List.of();
        String name = storyVO.getFeatures().getCheatMechanismName();
        if (blank(name)) name = "已设定的金手指/系统";
        return List.of(ChapterIssueEntity.builder()
                .dimension("mechanism")
                .severity("BLOCKING")
                .evidence("截至第 " + chapterNo + " 章，连续 " + gap + " 章未记录使用或有意义提及")
                .description(name + "超过 " + interval + " 章未使用或有意义提及")
                .suggestion("本章在既有能力边界内自然使用或交代暂时不可用的原因，不得新增能力")
                .build());
    }

    /**
     * 金手指/核心能力运作机制跨章重复描述检测（**增量口径**）。
     *
     * <p>2026-09-16 重写，修掉两处缺陷：
     * <ol>
     *   <li><b>计数器不归零</b>：原实现每章都用全量 contents 重算累计值，一旦第 3 个描述章出现，
     *       其后<em>每一章</em>累计值都 &gt; 2 因而章章报 BLOCKING——162 章样本实测会连报
     *       <b>135 章（83.3%）</b>；若直接当 BLOCKING 进修订闭环，等于从第 28 章起每章都要修订。
     *       现改为：<b>只有"本章新引入描述 且 此前额度已用满"的那一章才报</b>（同一样本只报 3 章）</li>
     *   <li><b>跨批丢历史</b>：原实现依赖 {@code chapterContents}，而它每个批次都从空列表开始 ⇒
     *       "全篇至多 2 次"的上限每批都会重置，多批续写下形同虚设。现改为从<b>摘要</b>取历史
     *       （{@code mechanismDescribed} 由 ChapterWorker 机械写入，摘要跨批预载）</li>
     * </ol>
     *
     * @param currentDescribed 本章正文是否引入机制描述（{@link #describesMechanism}）
     */
    public List<ChapterIssueEntity> mechanismDescriptionIssues(List<ChapterSummaryEntity> summaries, StoryVO storyVO,
                                                              int chapterNo, boolean currentDescribed) {
        if (!cheatRuleEnabled(storyVO)) return List.of();
        if (!currentDescribed) return List.of();
        List<Integer> priorDescribed = new ArrayList<>();
        if (summaries != null) {
            for (ChapterSummaryEntity summary : summaries) {
                if (summary == null || summary.getChapterNo() == null) continue;
                if (summary.getChapterNo() < chapterNo && Boolean.TRUE.equals(summary.getMechanismDescribed())) {
                    priorDescribed.add(summary.getChapterNo());
                }
            }
        }
        if (priorDescribed.size() < MAX_MECHANISM_DESCRIPTIONS) return List.of();
        String name = mechanismName(storyVO);
        if (blank(name)) name = "已设定的金手指/核心能力";
        List<Integer> all = new ArrayList<>(priorDescribed);
        all.add(chapterNo);
        return List.of(ChapterIssueEntity.builder()
                .dimension("mechanism")
                .severity("BLOCKING")
                .evidence("第 " + chapterNo + " 章再次大段描述机制原理；额度已于第 " + priorDescribed + " 章用满")
                .description(name + "运作机制跨章重复描述超过上限（全篇至多 " + MAX_MECHANISM_DESCRIPTIONS
                        + " 次：首次建立+关键升级），本样本累计 " + all)
                .suggestion("本章直接用行动展示操作过程和结果，删掉原理性解释段落；如需提醒机制存在，用角色的一个习惯性动作或一句话带过")
                .build());
    }

    /**
     * 机制类门禁的合并入口：供质量门在选定的正文上**重算**（修订稿复审也走这里）。
     * 两条检查都是 (summaries 历史 + 本章正文) 的纯函数，故每次改稿后都能重跑——
     * 修订真的清掉问题即闭环，不会永远挂在债上。
     */
    public List<ChapterIssueEntity> mechanismIssues(StoryVO storyVO, List<ChapterSummaryEntity> summaries,
                                                    int chapterNo, String currentContent) {
        List<ChapterIssueEntity> issues = new ArrayList<>(
                mechanismUsageIssues(summaries, storyVO, chapterNo, currentContent));
        issues.addAll(mechanismDescriptionIssues(summaries, storyVO, chapterNo,
                describesMechanism(currentContent, storyVO)));
        return issues;
    }

    /**
     * 显式机制名的保守别名集：保留全名，并按中文连接词拆分出长度至少 4 的语义片段。
     * 例："前世记忆与幼儿大脑黄金期" 可由正文里的 "前世记忆" 或 "幼儿大脑黄金期" 命中；
     * 不启用任意二字子串或通用词，避免把普通叙事误判为金手指使用。
     */
    private String[] mechanismTerms(String name) {
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        String normalized = name == null ? "" : name.trim();
        if (!normalized.isEmpty()) {
            terms.add(normalized);
            for (String part : normalized.split("(?:以及|及|与|和|、|/|\\+|＆|&)+")) {
                String term = part.trim();
                if (term.length() >= 4) {
                    terms.add(term);
                }
            }
        }
        return terms.toArray(String[]::new);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (!blank(keyword) && text.contains(keyword)) return true;
        }
        return false;
    }

    /** 渲染有限长度的事实索引；仅注入已确认摘要字段，避免替代三账本。 */
    public String renderPrompt(ConsistencyIndexEntity index) {
        if (index == null) return "";
        StringBuilder sb = new StringBuilder("【跨章一致性索引】\n");
        append(sb, "时间线", windowTimeline(index.getTimeline()));
        append(sb, "伤情", index.getInjuries());
        append(sb, "术语", index.getTerms());
        append(sb, "关键数字", index.getNumbers());
        append(sb, "机制", index.getMechanisms());
        append(sb, "关系台账（当前态）", index.getRelations());
        return sb.length() == "【跨章一致性索引】\n".length() ? "" : sb.toString();
    }

    /**
     * 时间线窗口：只保留最近 {@link #TIMELINE_WINDOW_CHAPTERS} 章的条目。
     * 按条目自带章号判定（时间线是每章一条的心跳数据，老条目与近章摘要/三账本重复，
     * 随连载线性膨胀——125 章时仅此一节约 1.2k 字符）。只影响渲染视图，不动底层数据。
     */
    private static List<?> windowTimeline(List<?> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return timeline;
        }
        int latest = -1;
        for (Object o : timeline) {
            if (o instanceof ConsistencyIndexEntity.TimelineEntry t && t.getChapterNo() != null) {
                latest = Math.max(latest, t.getChapterNo());
            }
        }
        if (latest < 0) {
            return timeline;
        }
        int floor = latest - TIMELINE_WINDOW_CHAPTERS + 1;
        List<Object> windowed = new ArrayList<>();
        for (Object o : timeline) {
            if (o instanceof ConsistencyIndexEntity.TimelineEntry t) {
                if (t.getChapterNo() != null && t.getChapterNo() >= floor) {
                    windowed.add(o);
                }
            } else {
                // 非时间线条目（理论不存在）保守保留，不做静默丢弃
                windowed.add(o);
            }
        }
        return windowed;
    }

    /**
     * 时序锚（2026-10-03）：把「当前故事时间 + 主角当前年龄」渲染成一段自包含的硬锚文本。
     *
     * <p><b>为什么需要它</b>：26-30 章批次出现"十一个月大的婴儿写数论证明、列乘法竖式"的能力失控。
     * 根因不在写手——计划 prompt 里<b>当前月龄出现 0 次</b>，只有圣经 band"21-30 章步入小学／
     * 自学高阶数学"，模型无从知道人物仍是婴儿，于是把"这一带应该读小学"直接落到了襁褓婴儿身上。
     * 年龄必须像修为境界一样，在每一路 prompt 里被显式锁定。
     *
     * <p><b>为什么不编造</b>：只从摘要里已记录、且经证据校验入账的年龄类关键数字取材；
     * 无年龄事实时返回空串，由装配器过滤该块，绝不猜一个年龄塞进去。
     *
     * <p><b>推算语义</b>：年龄值记于其来源章号（如第16章"十一个月"）；调用方（计划/正文/蓝图）
     * 应当按当前故事时间往前推算，因此文本显式提示"按故事时间推算至本章"，而非把旧值当成本章值。
     *
     * @return 无年龄事实时返回空串
     */
    public static String renderTimeAnchor(List<ChapterSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return "";
        }
        String timePoint = null;
        Integer timeChapterNo = null;
        String ageValue = null;
        Integer ageChapterNo = null;
        String ageSubject = null;
        for (ChapterSummaryEntity summary : summaries) {
            if (summary == null || summary.getChapterNo() == null) {
                continue;
            }
            if (!blank(summary.getTimePoint())) {
                timePoint = summary.getTimePoint();
                timeChapterNo = summary.getChapterNo();
            }
            for (ChapterSummaryEntity.ConsistencyFact fact : safe(summary.getConsistencyFacts())) {
                if (fact == null || blank(fact.getSubject()) || blank(fact.getValue())) {
                    continue;
                }
                if (!"NUMBER".equalsIgnoreCase(nullToBlank(fact.getType()).trim())) {
                    continue;
                }
                if (!containsAny(fact.getSubject(), AGE_SUBJECT_KEYWORDS)) {
                    continue;
                }
                // 取章号最大者（摘要列表通常按章序累积，仍显式取最新，防乱序输入）
                if (ageChapterNo == null || summary.getChapterNo() >= ageChapterNo) {
                    ageValue = fact.getValue();
                    ageChapterNo = summary.getChapterNo();
                    ageSubject = blank(fact.getScope()) ? stripAgeSuffix(fact.getSubject()) : fact.getScope();
                }
            }
        }
        if (blank(ageValue)) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【时序锚】");
        if (!blank(timePoint)) {
            sb.append("当前故事时间：").append(timePoint)
                    .append(timeChapterNo == null ? "" : "（第" + timeChapterNo + "章）").append("；");
        }
        sb.append(blank(ageSubject) ? "主角" : ageSubject).append("年龄：").append(ageValue)
                .append("（第").append(ageChapterNo).append("章摘要记录；请按故事时间推算至本章，不得倒推变小）。\n")
                .append(anchorConstraints());
        return sb.toString();
    }

    /**
     * 设定兜底年龄锚（2026-10-03）：新书首段还没有任何摘要 → 摘要驱动的 {@link #renderTimeAnchor} 为空，
     * 第一章与首段计划会在"零年龄约束"下生成（实测：4 岁主角在首段做出超龄行为，
     * 且审校年龄判据因 guard 无锚而不触发）。此处从故事设定保守提取年龄。
     *
     * <p><b>命中从严</b>：只在年龄明确绑定幼龄阶段词（"以4岁幼童的身份""4岁的孩童"）或"年仅/年方N岁"
     * 时才命中——"前世38岁""他38岁时"这类成人年龄一律不锚，宁可无锚也不误锚。
     *
     * <p><b>只允许无摘要时兜底</b>（见 {@link #renderSettingsAgeAnchorIfNoSummaries}）：
     * 有摘要后以摘要锚为准，否则会把故事起始年龄钉死在中期。
     *
     * @return 锚文本；未命中返回空串（不编造）
     */
    public static String renderSettingsAgeAnchor(String worldSetting, String protagonist, String outline) {
        String text = nullToBlank(worldSetting) + "\n" + nullToBlank(outline);
        Matcher matcher = SETTINGS_CHILD_AGE.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        Integer age = parseAgeNumber(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        if (age == null || age < 1 || age > 18) {
            return "";
        }
        String subject = leadingName(protagonist);
        return "【时序锚】" + (subject == null ? "主角" : subject) + "起始年龄：" + age
                + "岁（来自故事设定；这是故事开篇的年龄，请按故事时间推算至本章，不得倒推变小）。\n"
                + anchorConstraints();
    }

    /** 便捷入口：仅"无摘要"时回退设定锚；有摘要返回空串（摘要锚优先，防起始年龄被钉死在中期） */
    public static String renderSettingsAgeAnchorIfNoSummaries(List<ChapterSummaryEntity> summaries,
                                                              String worldSetting, String protagonist, String outline) {
        if (summaries != null && !summaries.isEmpty()) {
            return "";
        }
        return renderSettingsAgeAnchor(worldSetting, protagonist, outline);
    }

    /** 锚的硬约束正文：摘要时序锚与设定兜底锚共用同一份措辞，避免两处漂移 */
    private static String anchorConstraints() {
        return "硬约束：认知可以超前（是否允许由故事设定决定），媒介不能超前——"
                + "该阶段角色的动作、语言、书写、专注时长与精细操作必须落在其生理/状态极限内；"
                + "超出的\"能力展示\"必须改写为阶段内的合法表达（倾向、注视、趋避、选择、被协助完成——"
                + "以该年龄常识为准）。**不允许**用任何**可被他人在事后解读出具体含义**的载体传递信息——"
                + "涂鸦、摆物、敲击、手势、节奏、器物位置都一样；这不叫认知超前，这叫能力展示。"
                + "观察者可以觉得\"不寻常\"，但不得从单次观察就**确认**他掌握了某种具体知识、"
                + "认出了某物、算出了某题或在传递某个警告；对行为的解读若指向具体知识或事件，"
                + "必须建立在多次重复之上，且本章必须仍保留\"不确定\"的口吻。";
    }

    /** 设定兜底锚的命中式：年龄必须绑定幼龄阶段词或"年仅/年方"，否则不锚（防误锚成人年龄） */
    private static final Pattern SETTINGS_CHILD_AGE = Pattern.compile(
            "(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*岁\\s*的?\\s*(?:幼童|孩童|儿童|孩子|幼儿|婴儿|少年)"
                    + "|(?:年仅|年方)\\s*(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*岁");

    /** 年龄数字解析：阿拉伯数字直读；中文数字支持 ≤ 九十九；解析失败返回 null */
    private static Integer parseAgeNumber(String raw) {
        if (blank(raw)) {
            return null;
        }
        String text = raw.trim();
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        int tenIndex = text.indexOf('十');
        if (tenIndex < 0) {
            return chineseDigit(text);
        }
        Integer tens = tenIndex == 0 ? 1 : chineseDigit(text.substring(0, tenIndex));
        Integer ones = tenIndex == text.length() - 1 ? 0 : chineseDigit(text.substring(tenIndex + 1));
        return tens == null || ones == null ? null : tens * 10 + ones;
    }

    private static Integer chineseDigit(String text) {
        if (text == null || text.length() != 1) {
            return null;
        }
        int index = "零一二三四五六七八九".indexOf(text.charAt(0));
        return index < 0 ? null : index;
    }

    /** 主人公字段的头部人名（"主角甲，灵魂是……"→"主角甲"）；解析不出返回 null（锚里回退"主角"） */
    private static String leadingName(String protagonist) {
        if (blank(protagonist)) {
            return null;
        }
        String first = protagonist.split("[，,。；;、\\s]", 2)[0].trim();
        return first.length() >= 2 && first.length() <= 6 ? first : null;
    }

    /** 去掉 subject 里的年龄后缀，得到人物名（"主角甲月龄"→"主角甲"）；scope 缺失时的兜底 */
    private static String stripAgeSuffix(String subject) {
        String stripped = subject;
        for (String keyword : AGE_SUBJECT_KEYWORDS) {
            stripped = stripped.replace(keyword, "");
        }
        return stripped.trim();
    }

    private void append(StringBuilder sb, String title, List<?> values) {
        if (values == null || values.isEmpty()) return;
        // 只渲染最近的 MAX_RENDER_ENTRIES 条：list 按章节序累积，此前 limit 取头部会
        // 永远停在开篇旧事实上 实测 22 条 timeline 只渲染了第 1-20 条）
        List<?> recent = values.size() > MAX_RENDER_ENTRIES
                ? values.subList(values.size() - MAX_RENDER_ENTRIES, values.size()) : values;
        sb.append(title).append("：");
        recent.forEach(value -> sb.append(compact(value)).append("；"));
        sb.append('\n');
    }

    /**
     * 紧凑渲染（2026-10-01）：此前直接拼接 lombok toString，全字段名（event=/chapterNo=/evidence=…）
     * 塞满 prompt——timeline 单条实测 313 字符、整块近 7000 字符，是前缀预算饱和（97-100%）
     * 挤出审校反馈/风格警示块的头号元凶。紧凑后单条约 40-70 字符，同一信息量省 60% 以上。
     */
    private static String compact(Object value) {
        if (value instanceof ConsistencyIndexEntity.TimelineEntry t) {
            // storyTime（故事内历法时间）是写手的对齐锚点，一并保留
            return "第" + t.getChapterNo() + "章 " + abbreviate(t.getEvent(), 60)
                    + (t.getStoryTime() == null ? "" : "〔" + t.getStoryTime() + "〕");
        }
        if (value instanceof ConsistencyIndexEntity.InjuryEntry i) {
            return i.getCharacter() + "：" + abbreviate(i.getLastStatus(), 40)
                    + (i.getFirstChapter() == null ? "" : "（首见第" + i.getFirstChapter() + "章）");
        }
        if (value instanceof ConsistencyIndexEntity.TermEntry t) {
            return t.getCanonical() + (t.getAliases() == null || t.getAliases().isEmpty() ? ""
                    : "（别名:" + String.join("/", t.getAliases()) + "）");
        }
        if (value instanceof ConsistencyIndexEntity.NumberEntry n) {
            return n.getName() + "=" + abbreviate(String.valueOf(n.getValue()), 30) + "（第" + n.getChapterNo() + "章）";
        }
        if (value instanceof ConsistencyIndexEntity.MechanismEntry m) {
            return m.getName() + "（间隔" + m.getUsageInterval() + "章，上次第" + m.getLastUsedChapter() + "章）";
        }
        if (value instanceof ConsistencyIndexEntity.RelationEntry r) {
            return r.getPair() + "——" + abbreviate(String.valueOf(r.getRelation()), 40)
                    + (r.getLastChapter() == null ? "" : "（至第" + r.getLastChapter() + "章）");
        }
        return String.valueOf(value);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + '…';
    }

    private int usageInterval(StoryVO storyVO) { return storyVO == null || storyVO.getFeatures() == null || storyVO.getFeatures().getCheatUsageInterval() == null ? 3 : Math.max(1, storyVO.getFeatures().getCheatUsageInterval()); }
    private boolean containsInjury(String status) { String s = status.toLowerCase(Locale.ROOT); return s.contains("伤") || s.contains("创") || s.contains("骨折") || s.contains("中毒") || s.contains("残疾"); }
    private String injuryLocation(String status) { for (String part : status.split("[，,；;。 ]")) if (part.contains("肩") || part.contains("臂") || part.contains("腿") || part.contains("胸") || part.contains("腹") || part.contains("头") || part.contains("背")) return part; return "未明确部位"; }
    private String injurySeverity(String status) { if (status.contains("重伤") || status.contains("濒死") || status.contains("致命")) return "重"; if (status.contains("轻伤")) return "轻"; return "未评级"; }
    private boolean isMechanism(String text) { return text.contains("系统") || text.contains("金手指") || text.contains("外挂") || text.contains("面板") || text.contains("剑意提取"); }
    private void appendConsistencyFacts(ConsistencyIndexEntity index, Map<String, ConsistencyIndexEntity.InjuryEntry> injuries,
                                        ChapterSummaryEntity summary, Integer chapterNo) {
        for (ChapterSummaryEntity.ConsistencyFact fact : safe(summary.getConsistencyFacts())) {
            if (fact == null || blank(fact.getType()) || blank(fact.getSubject())) continue;
            switch (fact.getType().trim().toUpperCase(Locale.ROOT)) {
                case "TIMELINE" -> index.getTimeline().add(new ConsistencyIndexEntity.TimelineEntry(fact.getSubject(), chapterNo, fact.getValue(), fact.getEvidence()));
                case "INJURY" -> injuries.put(fact.getSubject() + "#" + nullToBlank(fact.getScope()), new ConsistencyIndexEntity.InjuryEntry(fact.getSubject(), fact.getScope(), injurySeverity(nullToBlank(fact.getValue())), chapterNo, fact.getValue(), fact.getEvidence()));
                case "TERM" -> index.getTerms().add(new ConsistencyIndexEntity.TermEntry(fact.getSubject(), blank(fact.getValue()) ? List.of() : List.of(fact.getValue()), chapterNo));
                case "NUMBER" -> index.getNumbers().add(new ConsistencyIndexEntity.NumberEntry(fact.getSubject(), fact.getValue(), chapterNo, fact.getScope()));
                // 人际关系 新增）：此前关系变化只存于 characterBeats 的自由文本，
                // 是唯一"不可机械核验"的主要维度；RELATION 事实让它进入与伤情/术语同一套证据链
                case "RELATION" -> upsertRelation(index, fact.getSubject(), fact.getValue(), chapterNo, fact.getEvidence());
                default -> { }
            }
        }
    }

    /**
     * 关系按「双方」去重并保留最近一次更新：台账要回答的是"**现在**什么关系"，
     * 而不是"哪一章变过"（变化过程由 characterBeats 承载，台账只存当前态）。
     * 同一对双方再次出现 = 关系演变，覆盖旧值并更新 lastChapter，首见章号保留在 firstChapter
     */
    private void upsertRelation(ConsistencyIndexEntity index, String pair, String relation,
                                Integer chapterNo, String evidence) {
        if (blank(pair)) return;
        for (ConsistencyIndexEntity.RelationEntry existing : index.getRelations()) {
            if (pair.equals(existing.getPair())) {
                existing.setRelation(nullToBlank(relation));
                existing.setLastChapter(chapterNo);
                existing.setEvidence(evidence);
                return;
            }
        }
        index.getRelations().add(new ConsistencyIndexEntity.RelationEntry(
                pair, nullToBlank(relation), chapterNo, chapterNo, evidence));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullToBlank(String value) { return value == null ? "" : value; }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
}
