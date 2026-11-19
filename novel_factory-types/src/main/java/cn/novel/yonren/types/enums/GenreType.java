package cn.novel.yonren.types.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 按顺序确定主文风。正文可从同一本书明确写出的其他题材中选取当章范例。 */
public enum GenreType {

    FANTASY("fantasy", "玄幻", "修仙", "修真", "仙侠", "灵气", "东方奇幻"),
    MAGIC("magic", "奇幻", "西幻", "西方幻想", "魔法", "魔幻"),
    ABILITY("ability", "异能", "超能力", "能力觉醒", "觉醒能力", "觉醒流", "全民觉醒", "进化流", "基因进化"),
    SUSPENSE("suspense", "悬疑", "推理", "惊悚", "刑侦", "侦探", "犯罪", "灵异", "恐怖", "民俗怪谈"),
    ROMANCE("romance", "言情", "甜宠", "总裁", "豪门", "婚恋", "纯爱", "古言", "现言"),
    SCIFI("scifi", "科幻", "黑科技", "科技", "星际", "机甲", "赛博", "人工智能", "太空"),
    APOCALYPSE("apocalypse", "末世", "废土", "丧尸", "灾变", "天灾", "避难所"),
    INFINITE_FLOW("infinite-flow", "无限流", "无限恐怖", "副本流", "副本闯关", "规则怪谈", "轮回流", "轮回空间"),
    GAMING("gaming", "电竞", "电子竞技", "网游", "游戏"),
    WUXIA("wuxia", "武侠", "江湖", "武林"),
    HISTORY("history", "历史", "架空王朝", "架空朝代", "古代架空", "朝堂", "权谋", "宫斗", "宅斗", "科举"),
    WAR("war", "军事", "战争", "战地", "烽火", "战时群像"),
    SPORTS("sports", "体育", "运动竞技", "运动员", "足球", "篮球", "排球", "田径", "游泳竞技", "网球", "乒乓", "冰球"),
    FARM_FOOD("farm-food", "种田", "田园", "农家", "乡村经营", "美食", "餐饮经营", "开店经营", "经营流"),
    SCHOOL("school", "校园", "青春", "学霸", "学园", "求学"),
    PERFORMING_ARTS("performing-arts", "娱乐圈", "演艺", "文娱", "影视圈", "直播文", "主播", "戏班"),
    WORKPLACE("workplace", "职场", "职业文", "行业文", "职人", "工匠", "手艺人"),
    ADVENTURE("adventure", "探险", "探秘", "野外考察", "航海", "荒野求生", "公路旅行", "寻宝"),
    URBAN("urban", "都市", "重生", "年代", "商战", "创业", "官场", "现实", "家庭"),
    DEFAULT("default");

    // 老书也有只填一个简称的题材。长描述里的「女性觉醒」「无限可能」各有别义。
    private static final Map<String, GenreType> SHORT_LABELS = Map.of(
            "觉醒", ABILITY, "进化", ABILITY, "基因", ABILITY,
            "无限", INFINITE_FLOW, "副本", INFINITE_FLOW, "轮回", INFINITE_FLOW);

    private final String code;
    private final List<String> keywords;

    GenreType(String code, String... keywords) {
        this.code = code;
        this.keywords = List.of(keywords);
    }

    public String getCode() {
        return code;
    }

    public static GenreType match(String theme, String style) {
        return matching(theme, style).stream().findFirst().orElse(DEFAULT);
    }

    /** 只读取全书题材和风格。章内一句「打游戏」不能把作品改成网游小说。 */
    public static List<GenreType> matching(String theme, String style) {
        String bookTheme = theme == null ? "" : theme.strip().toLowerCase(Locale.ROOT);
        String bookStyle = style == null ? "" : style.strip().toLowerCase(Locale.ROOT);
        String text = bookTheme + "\n" + bookStyle;
        return Arrays.stream(values())
                .filter(genre -> genre == SHORT_LABELS.get(bookTheme) || genre == SHORT_LABELS.get(bookStyle)
                        || genre.keywords.stream().anyMatch(text::contains))
                .toList();
    }
}
