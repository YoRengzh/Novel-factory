package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.AssetContentDTO;
import cn.novel.yonren.domain.novel.service.armory.prompt.UserAssetStore;
import cn.novel.yonren.domain.novel.service.armory.prompt.reference.ReferenceIndexManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserAssetReferenceMaintenanceTest {

    private UserAssetStore assets;
    private ReferenceIndexManagementService maintenance;
    private UserAssetController controller;

    @BeforeEach
    void setUp() {
        assets = mock(UserAssetStore.class);
        maintenance = mock(ReferenceIndexManagementService.class);
        controller = new UserAssetController();
        ReflectionTestUtils.setField(controller, "userAssetStore", assets);
        ReflectionTestUtils.setField(controller, "referenceIndexManagement", maintenance);
        when(assets.listReferences()).thenReturn(List.of());
    }

    @Test
    void existingSaveAndDeleteGoThroughReferenceMaintenance() {
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(maintenance).mutateReference(any());
        controller.writeReference("user-file", new AssetContentDTO("new body", null, null));
        controller.deleteReference("user-file");
        verify(assets).writeReference("user-file", "new body");
        verify(assets).deleteReference("user-file");
        verify(maintenance, times(2)).mutateReference(any());
    }

    @Test
    void activeGenerationRejectsMutationWithoutChangingSource() {
        doThrow(new ReferenceIndexManagementService.ConflictException("任务仍在进行"))
                .when(maintenance).mutateReference(any());
        var error = assertThrows(ResponseStatusException.class, () -> controller.deleteReference("user-file"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(assets, never()).deleteReference(any());
    }
}
