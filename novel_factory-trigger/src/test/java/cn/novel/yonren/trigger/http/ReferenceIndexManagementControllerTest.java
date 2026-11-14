package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.ReferenceIndexDTO;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceIndexManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferenceIndexManagementControllerTest {

    private ReferenceIndexManagementService service;
    private ReferenceIndexManagementController controller;

    @BeforeEach
    void setUp() {
        service = mock(ReferenceIndexManagementService.class);
        controller = new ReferenceIndexManagementController(service);
    }

    @Test
    void listReturnsTypedReadOnlyCatalog() {
        var view = new ReferenceIndexDTO("embedded", "version", false, "", List.of(), List.of(), null);
        when(service.view()).thenReturn(view);
        assertSame(view, controller.view());
    }

    @Test
    void destructiveOperationRequiresPreviewToken() {
        var result = new ReferenceIndexDTO.OperationResult(true, null, "完成");
        when(service.execute("confirmed")).thenReturn(result);
        assertSame(result, controller.operate(new ReferenceIndexManagementController.OperationRequest("confirmed")));
        verify(service).execute("confirmed");
        assertThrows(ResponseStatusException.class, () -> controller.operate(null));
    }

    @Test
    void busyIsConflictAndPaginationRejectsInvalidLimits() {
        when(service.preview("clear", null, "id")).thenThrow(
                new ReferenceIndexManagementService.ConflictException("生成仍在进行"));
        var error = assertThrows(ResponseStatusException.class, () -> controller.preview(
                new ReferenceIndexManagementController.PreviewRequest("clear", null, "id")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> controller.points("id", null, 101));
        assertThrows(ResponseStatusException.class, () -> controller.points("id", "x".repeat(513), 25));
        verify(service, never()).points(any(), any(), anyInt());
    }

    @Test
    void collectionBatchUsesLogicalIdsAndRejectsMixedSelectors() {
        var ids = List.of("collection-a", "collection-b");
        controller.preview(new ReferenceIndexManagementController.PreviewRequest("delete", null, null, ids));
        verify(service).previewCollections("delete", ids);
        assertThrows(ResponseStatusException.class, () -> controller.preview(
                new ReferenceIndexManagementController.PreviewRequest("delete", "source", null, ids)));
        verify(service, never()).preview("delete", "source", null);
    }

    @Test
    void underlyingSecretsAreNotReturnedAsHttpError() {
        when(service.view()).thenThrow(new IllegalStateException("Authorization: Bearer actual-secret"));
        var error = assertThrows(ResponseStatusException.class, controller::view);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatusCode());
        assertFalse(error.getReason().contains("actual-secret"));
    }
}
