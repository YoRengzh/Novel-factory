package cn.novel.yonren.types.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 题材类型：由 theme/style 关键词路由得出，用于风格文件与 genres 题材资料的确定性装配。
 * 每个码对应 references/genres/{code}/ 下的三件套（style-references / arc-templates / exemplars）。
 */
public enum GenreType {

    /** 玄幻/奇幻/修仙类 */
    FANTASY("fantasy"),

    /** 都市异能/超能力觉醒类（2026-10-04 从 FANTASY 拆出：修仙向与都市异能向的资料分岗） */
    ABILITY("ability"),

    /** 悬疑/推理/刑侦类 */
    SUSPENSE("suspense"),

    /** 言情/情感类 */
    ROMANCE("romance"),

    /** 科技流/黑科技/星际类（2026-10-04 新增） */
    SCIFI("scifi"),

    /** 末世/废土/天灾生存类（2026-10-04 新增） */
    APOCALYPSE("apocalypse"),

    /** 无限流/副本/规则怪谈类（2026-10-04 新增） */
    INFINITE_FLOW("infinite-flow"),

    /** 都市现实/重生/年代/商战类（2026-10-04 新增：此前都市重生落 DEFAULT，题材资料完全缺席） */
    URBAN("urban"),

    /** 未命中任何题材词表（无对应 genres 资料，仅落 styles/default.md） */
    DEFAULT("default");

    private final String code;

    GenreType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 异能/超能力系（2026-10-04 从 FANTASY 词表拆出：玄幻仍归 FANTASY，都市异能归 ABILITY） */
    private static final List<String> ABILITY_KEYWORDS = List.of("异能", "超能力", "觉醒", "进化", "基因");
    private static final List<String> FANTASY_KEYWORDS = List.of("玄幻", "奇幻", "修仙", "修真", "仙侠", "魔法", "灵气");
    private static final List<String> SUSPENSE_KEYWORDS = List.of("悬疑", "推理", "惊悚", "刑侦", "侦探", "犯罪", "灵异");
    private static final List<String> ROMANCE_KEYWORDS = List.of("言情", "甜宠", "青春", "校园", "总裁", "豪门", "婚恋", "纯爱");
    private static final List<String> SCIFI_KEYWORDS = List.of("科幻", "黑科技", "科技", "星际", "机甲", "赛博", "人工智能", "太空");
    private static final List<String> APOCALYPSE_KEYWORDS = List.of("末世", "废土", "丧尸", "灾变", "天灾", "避难所");
    private static final List<String> INFINITE_FLOW_KEYWORDS = List.of("无限流", "无限", "副本", "规则怪谈", "轮回");
    private static final List<String> URBAN_KEYWORDS = List.of("都市", "重生", "年代", "商战", "创业", "职场", "官场", "娱乐圈", "现实", "家庭");

    /**
     * 关键词路由：theme 优先，其次 style，包含匹配；未命中返回 DEFAULT。
     * 顺序即优先级：玄幻在 ABILITY 之前（"玄幻异能"归修仙资料），异能在 URBAN 之前
     * （"都市异能"归异能资料而非都市资料），末世在无限流之前（"末世无限流"归末世）。
     */
    public static GenreType match(String theme, String style) {
        if (matchesAny(theme, FANTASY_KEYWORDS) || matchesAny(style, FANTASY_KEYWORDS)) {
            return FANTASY;
        }
        if (matchesAny(theme, ABILITY_KEYWORDS) || matchesAny(style, ABILITY_KEYWORDS)) {
            return ABILITY;
        }
        if (matchesAny(theme, SUSPENSE_KEYWORDS) || matchesAny(style, SUSPENSE_KEYWORDS)) {
            return SUSPENSE;
        }
        if (matchesAny(theme, ROMANCE_KEYWORDS) || matchesAny(style, ROMANCE_KEYWORDS)) {
            return ROMANCE;
        }
        if (matchesAny(theme, SCIFI_KEYWORDS) || matchesAny(style, SCIFI_KEYWORDS)) {
            return SCIFI;
        }
        if (matchesAny(theme, APOCALYPSE_KEYWORDS) || matchesAny(style, APOCALYPSE_KEYWORDS)) {
            return APOCALYPSE;
        }
        if (matchesAny(theme, INFINITE_FLOW_KEYWORDS) || matchesAny(style, INFINITE_FLOW_KEYWORDS)) {
            return INFINITE_FLOW;
        }
        if (matchesAny(theme, URBAN_KEYWORDS) || matchesAny(style, URBAN_KEYWORDS)) {
            return URBAN;
        }
        return DEFAULT;
    }

    private static boolean matchesAny(String text, List<String> keywords) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(lower::contains);
    }

}
