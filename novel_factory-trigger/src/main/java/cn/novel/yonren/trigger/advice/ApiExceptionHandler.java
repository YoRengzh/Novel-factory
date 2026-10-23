package cn.novel.yonren.trigger.advice;

import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Set;

/**
 * 触发层统一异常出口：把 {@link AppException} 的异常码翻译成 HTTP 状态码，一处定义。
 *
 * <p>判断标准只有一条——**这个错误是不是请求本身的错**：
 * {@code 0002 非法参数 / 0003 参数为空 / 0004 参数重复} 属于入参类，前端改了就能重发，映射 400；
 * 其余（{@code 0001 未知失败}、队列满等）归服务端，映射 500。
 * 状态码 409/502/503 这类**端点特有语义**（同一故事已有作业、上游模型没给出可用结果）
 * 仍由各端点自行决定，本类不猜。
 *
 * <p>刻意只接管 {@code AppException}：{@code ResponseStatusException} 与其它运行时异常
 * 继续交给 Spring 的默认解析，保持响应体形状与历史行为一致。
 * 新增端点不应再手写 {@code catch (AppException e) -> 400} 样板，直接抛给本类即可。
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 入参类异常码：请求本身有问题，前端可自行改正 */
    private static final Set<String> INPUT_ERROR_CODES = Set.of(
            ResponseCode.ILLEGAL_PARAMETER.getCode(),
            ResponseCode.NULL_EXCEPTION.getCode(),
            ResponseCode.PARAM_REPETITION.getCode());

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiError> handleAppException(AppException e) {
        boolean inputError = INPUT_ERROR_CODES.contains(e.getCode());
        HttpStatus status = inputError ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;

        if (inputError) {
            // 入参被拒是预期内的用户错误，记 WARN 且不打堆栈，避免刷屏
            log.warn("入参被拒绝：code={}, info={}", e.getCode(), e.getInfo());
        } else {
            log.error("业务异常：code={}, info={}", e.getCode(), e.getInfo(), e);
        }
        return ResponseEntity.status(status).body(new ApiError(e.getCode(), e.getInfo()));
    }

    /** 错误响应体：保留 code 供前端按异常码分支，message 供直接展示 */
    public record ApiError(String code, String message) {
    }

}
