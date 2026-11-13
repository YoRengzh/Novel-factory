package cn.novel.yonren.types.enums;


public enum JobStatus {

    /** 已创建，等待执行（排队中） */
    CREATED,

    /** 执行中 */
    RUNNING,

    /** 取消已受理，当前章完成后停止 */
    CANCELLING,

    /** 执行失败 */
    FAILED,

    /** 执行完成 */
    COMPLETED,

    /** 已取消 */
    CANCELLED
}
