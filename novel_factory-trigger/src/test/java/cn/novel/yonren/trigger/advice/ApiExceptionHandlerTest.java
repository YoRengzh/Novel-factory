package cn.novel.yonren.trigger.advice;

import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 异常出口测试：入参类错误码 → 400，服务端错误 → 500，且错误码原样透出。
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void illegalParameter_mapsTo400() {
        assertStatus(ResponseCode.ILLEGAL_PARAMETER, HttpStatus.BAD_REQUEST);
    }

    @Test
    void nullException_mapsTo400() {
        assertStatus(ResponseCode.NULL_EXCEPTION, HttpStatus.BAD_REQUEST);
    }

    @Test
    void paramRepetition_mapsTo400() {
        assertStatus(ResponseCode.PARAM_REPETITION, HttpStatus.BAD_REQUEST);
    }

    @Test
    void serverError_mapsTo500() {
        assertStatus(ResponseCode.UN_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void body_keepsCodeAndMessage() {
        AppException e = new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "worldId 格式非法");

        ApiExceptionHandler.ApiError body = handler.handleAppException(e).getBody();

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), body.code());
        assertEquals("worldId 格式非法", body.message());
    }

    private void assertStatus(ResponseCode code, HttpStatus expected) {
        ResponseEntity<ApiExceptionHandler.ApiError> response =
                handler.handleAppException(new AppException(code.getCode(), "测试"));

        assertEquals(expected, response.getStatusCode());
        assertEquals(code.getCode(), response.getBody().code());
    }
}
