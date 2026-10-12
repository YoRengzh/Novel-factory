package cn.novel.yonren.domain.novel.model.entity;

import cn.novel.yonren.types.enums.ChapterTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterPlanItemEntity {
    //章节号
    private Integer chapterNo;
    //标题
    private String title;
    //实现目标
    private String goal;
    //角色
    private List<String> characters;
    //关键事件
    private List<String> keyEvents;

    /**
     * **对话承载事件的声明**（2026-10-10 新增）：把本章 keyEvents 中由 ≥2 个人物**当面对话/冲突/协作**
     * 承载的那几条**逐字复制**到这里——normal/climax/finale 章 ≥2 条、transition 章 ≥1 条。
     *
     * <p><b>为什么需要独立字段</b>：keyEvents 是自由文本，事件的性质（有戏可演 / 单人独处）
     * 不在结构里，机械校验数不出来——第 105 章 5 条关键事件 0 条对话承载（characters 里甚至写着
     * "电脑屏幕"）、第 96 章"2 条对话承载数量合规但最大事件是独白"，都是这么放行的。
     * 声明是**可机械核验**的契约：见 {@code ChapterPlanChecks.validateDialogueCarriage}——
     * 逐字命中 keyEvents、匹配事件写到 ≥2 个人物全名、单人事件不得过半；不达标驳回重规划一次。
     *
     * <p>消费方仅规划层闸门（写手/节拍/审计只见 keyEvents，声明是它的子集，无需重复下达）。
     * 老数据为 null：新计划缺声明计违规，老计划不回溯（闸门只在生成时跑）。
     */
    private List<String> dialogueScenes;
    //结尾悬念
    private String endingHook;
    // 本章时间推进声明（时序锚）：唯一合法的年龄/时间推进通道——写手据此渲染锁定锚，
    // 审计据此判"推进与声明不符"。格式自由文本（如"推进 2 周，至 XXXX 年 10 月下旬"）
    private String timeAdvance;
    //章节类型：normal 普通 / climax 高潮 / finale 卷末
    private ChapterTypeVO chapterType;

    /**
     * **本章结束时核心悬念所处的档位**（2026-09-22 新增）：必须**逐字取自**阶段蓝图的
     * {@code suspenseLadder}（由蓝图针对本书生成，见 {@code StageBlueprintEntity.suspenseLadder}）。
     *
     * <p><b>为什么要这个字段</b>：此前的计划约束是"每章至少一处关系/信息/资源的变化"，
     * 而"内心评价悄然变化"也能满足它——实测出现过整批 6 章主线零推进，每章都是
     * "发现线索 → 自我否定 → 回到原点"，规划层甚至还把它写成了要"确立"的叙事范式。
     * 档位是**有序枚举**，机械比较就能判定"整批在原地打转"，这是结构的解法而非文风劝导。
     *
     * <p>校验见 {@code ChapterPlanChecks.validateSuspenseAdvance}：不得倒退；
     * 非过渡章不得连续 3 章停留同一档。
     */
    private String suspenseBeat;

    /**
     * **本章落地的主线推进**（2026-10-02 新增）：逐字取自阶段蓝图的
     * {@code StageBlueprintEntity.mainLineByChapter} 中对应章号那条。
     *
     * <p><b>与 {@link #suspenseBeat} 的分工</b>（两个维度，不可互相替代）：
     * <ul>
     *   <li>{@code suspenseBeat}：纵向——"本章结束时核心悬念走到第几格"，须逐字取自档位表
     *       （{@code SuspenseLadderPolicy.resolveIndex} 靠前缀匹配定位），故**不宜再往里塞附加文字**；</li>
     *   <li>{@code mainLineAdvance}：横向——"这一章主线做了什么"，逐字取自蓝图的章级推进。</li>
     * </ul>
     *
     * <p>存在的意义：档位表 3-6 档覆盖 5-80 章，多章共用同一档是常态，
     * 单靠档位无法区分相邻两章；本字段让"每章各自推进了什么"成为**可机械校验**的东西
     * （缺章 / 未落地 / 相邻雷同三类违规）。老数据为 null，校验跳过。
     */
    private String mainLineAdvance;
}
