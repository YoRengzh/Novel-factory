package cn.novel.yonren.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实体事实时间线 DTO：某角色/物品/势力在全书各章的状态轨迹，
 * 供"模型写错状态时追溯该状态出自第几章、哪段正文（证据引用）"的排障与人工核对
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FactTimelineDTO {

    /** 事实来源章节号 */
    private int chapterNo;

    /** 实体名称（模糊匹配回退时可与查询名不同，如查询短名命中更长的实体全名） */
    private String name;

    /** 账本类型：角色 / 物品 / 势力 */
    private String factType;

    /** 该章记录的状态 */
    private String status;

    /** 支持该状态的正文证据原文引用（经摘要侧机械校验；缺省路径为 null） */
    private String evidence;

}
