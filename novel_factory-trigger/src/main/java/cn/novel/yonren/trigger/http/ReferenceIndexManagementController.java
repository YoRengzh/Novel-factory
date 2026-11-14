package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.ReferenceIndexDTO;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceIndexManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Supplier;

/** 集合名由服务端目录解析；客户端只能提交预览令牌，不直接传磁盘路径。 */
@RestController
@RequestMapping("/api/reference-index")
@RequiredArgsConstructor
public class ReferenceIndexManagementController {

    private final ReferenceIndexManagementService service;

    public record PreviewRequest(String action, String sourceId, String collectionId, java.util.List<String> collectionIds) {
        public PreviewRequest(String action, String sourceId, String collectionId) {
            this(action, sourceId, collectionId, null);
        }
    }

    public record OperationRequest(String token) {
    }

    @GetMapping
    public ReferenceIndexDTO view() {
        return invoke(service::view);
    }

    @GetMapping("/collections/{id}/points")
    public ReferenceIndexDTO.PointPage points(@PathVariable("id") String id,
                                              @RequestParam(value = "cursor", required = false) String cursor,
                                              @RequestParam(value = "limit", defaultValue = "25") int limit) {
        if (limit < 1 || limit > 100 || cursor != null && cursor.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }
        return invoke(() -> service.points(id, cursor, limit));
    }

    @PostMapping("/preview")
    public ReferenceIndexDTO.Preview preview(@RequestBody PreviewRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少操作参数");
        if (request.collectionIds() != null) {
            if (request.sourceId() != null || request.collectionId() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批量集合不能同时指定来源或单集合");
            }
            return invoke(() -> service.previewCollections(request.action(), request.collectionIds()));
        }
        return invoke(() -> service.preview(request.action(), request.sourceId(), request.collectionId()));
    }

    @PostMapping("/operations")
    public ReferenceIndexDTO.OperationResult operate(@RequestBody OperationRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少操作预览令牌");
        return invoke(() -> service.execute(request.token()));
    }

    @GetMapping("/tasks/{id}")
    public ReferenceIndexDTO.TaskView task(@PathVariable("id") String id) {
        return invoke(() -> service.task(id));
    }

    private <T> T invoke(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ReferenceIndexManagementService.ConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            // 供应商响应可能夹带请求头或凭据，不把底层异常原文送入浏览器。
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "资料索引操作失败，请检查任务状态或本机日志");
        }
    }
}
