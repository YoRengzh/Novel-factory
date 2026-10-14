package cn.novel.yonren.domain.novel.service.armory.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节带目标窗口化：只裁远章带，绝不改写带内文字；解析异常 fail-soft 返回原文。
 */
class ChapterGoalWindowPolicyTest {

    private static final String GOAL =
            "【卷一·巷子烟火与垂髫早慧】(1-90章)：1-10章 胎穿2002年；11-20章 学龄前日常；"
                    + "21-30章 拜师启蒙；31-40章 世界杯之夜；41-50章 某数学竞赛；"
                    + "51-60章 置产；61-70章 某世家变故；71-80章 越级联赛；81-90章 升入初中。"
                    + "|| 【卷二·鲜衣怒马与集训争锋】(91-180章)：91-100章 初中日常；101-110章 越级联赛；"
                    + "111-120章 冬令营；121-130章 集训队群像；"
                    + "|| 【卷三·象牙塔暗涌与量化江湖】(181-270章)：181-190章 宿舍日常；191-200章 量化系统。";

    @Test
    void windowKeepsCurrentAndUpcomingBandsDropsDistantVolumes() {
        String windowed = ChapterGoalWindowPolicy.window(GOAL, 26);

        assertTrue(windowed.contains("21-30章 拜师启蒙"), "当前带必须保留");
        assertTrue(windowed.contains("31-40章 世界杯之夜"), "前瞻窗口内的带必须保留");
        assertTrue(windowed.contains("【卷一"), "本段所属卷的卷头必须保留");
        assertFalse(windowed.contains("71-80章"), "窗口外的远带应被裁掉");
        assertFalse(windowed.contains("【卷三"), "不重叠的远卷整体裁掉");
        assertTrue(windowed.contains("窗口化"), "追加窗口化说明，避免模型误以为全书只有这些带");
    }

    @Test
    void windowKeepsOnlyOverlappingVolumeAtVolumeBoundary() {
        String windowed = ChapterGoalWindowPolicy.window(GOAL, 95);

        assertTrue(windowed.contains("【卷二"), "进入第二卷后只保留第二卷");
        assertFalse(windowed.contains("【卷一"), "已走过的卷不再注入");
    }

    @Test
    void windowReturnsOriginalWhenStructureUnrecognized() {
        String plain = "这本书讲一个孩子的成长，没有章节带结构。";
        assertEquals(plain, ChapterGoalWindowPolicy.window(plain, 26));

        assertEquals(GOAL, ChapterGoalWindowPolicy.window(GOAL, 0), "章号非法时不裁剪");
        assertNull(ChapterGoalWindowPolicy.window(null, 26));
    }
}
