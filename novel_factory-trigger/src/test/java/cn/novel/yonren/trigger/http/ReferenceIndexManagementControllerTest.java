package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.ReferenceIndexDTO;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceIndexManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReferenceIndexManagementControllerTest {

    private static final String COLLECTION_ID = "collection-b9129c994863a8efe442bd12";
    private static final String POINTS_URL = "/api/reference-index/collections/" + COLLECTION_ID + "/points";

    private ReferenceIndexManagementService service;
    private ReferenceIndexManagementController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ReferenceIndexManagementService.class);
        controller = new ReferenceIndexManagementController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void firstPageNormalizesMissingOrBlankCursorThroughSpringMvc(String cursor) throws Exception {
        var page = new ReferenceIndexDTO.PointPage(List.of(new ReferenceIndexDTO.PointView(
                "point-1", Map.of("text", "分块正文"))), "embedded-v1-next");
        when(service.points(eq(COLLECTION_ID), any(), eq(25))).thenAnswer(invocation -> {
            if (invocation.getArgument(1) != null) {
                throw new IllegalArgumentException("向量分页游标非法");
            }
            return page;
        });
        var request = get(POINTS_URL);
        if (cursor != null) request.queryParam("cursor", cursor);

        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].payload.text").value("分块正文"))
                .andExpect(jsonPath("$.nextCursor").value("embedded-v1-next"));

        verify(service).points(COLLECTION_ID, null, 25);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded-v1-next", "qdrant-v1-next", "opaque/?&下一页"})
    void nextPagePreservesNonBlankCursorThroughSpringMvc(String cursor) throws Exception {
        var page = new ReferenceIndexDTO.PointPage(List.of(), null);
        when(service.points(COLLECTION_ID, cursor, 25)).thenReturn(page);

        mvc.perform(get(POINTS_URL).queryParam("cursor", cursor))
                .andExpect(status().isOk());

        verify(service).points(COLLECTION_ID, cursor, 25);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", " embedded-v1-next", "qdrant-v1-next "})
    void invalidCursorIsStillRejectedThroughSpringMvc(String cursor) throws Exception {
        when(service.points(COLLECTION_ID, cursor, 25)).thenThrow(
                new IllegalArgumentException("向量分页游标非法"));

        mvc.perform(get(POINTS_URL).queryParam("cursor", cursor))
                .andExpect(status().isBadRequest());

        verify(service).points(COLLECTION_ID, cursor, 25);
    }

    @Test
    void oversizedCursorAndInvalidLimitsAreRejectedBeforeServiceThroughSpringMvc() throws Exception {
        mvc.perform(get(POINTS_URL).queryParam("cursor", " ".repeat(513)))
                .andExpect(status().isBadRequest());
        for (String limit : List.of("0", "101", "invalid")) {
            mvc.perform(get(POINTS_URL).queryParam("limit", limit))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(service);
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
