package cn.novel.yonren.types.exception;

/**
 * 章节计划审批门的"挂起"控制信号——**不是错误**。
 *
 * 语义：章节计划已生成、已落盘，作业需要在正文生成前交人工裁决，因此主动中断规则树的本次执行。
 * worker 捕获本信号后**不置失败**，而是把作业转为 {@code AWAITING_APPROVAL} 并归还线程，
 * 待审批/超时后再用同一作业继续执行剩余阶段。
 *
 * 故意不继承 {@link AppException}：AppException 是业务异常，会被 worker 归入
 * FAILED 分支；本类承载的是正常控制流。捕获顺序上必须先于 {@code Throwable} 兜底。
 *
 * 不携带业务载荷：恢复所需的一切（DynamicContext / 已批计划 / 命令）由 worker 侧持有，
 * 保持本类为纯信号，避免把可变上下文挂在异常对象上。
 */
public class PlanApprovalSuspendedException extends RuntimeException {

    private static final long serialVersionUID = 8721937465021837465L;

    public PlanApprovalSuspendedException(String message) {
        super(message);
    }
}
