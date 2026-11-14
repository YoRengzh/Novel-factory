package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.model.valobj.VectorCollectionInfo;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointPage;
import cn.novel.yonren.domain.novel.model.valobj.VectorPointView;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.CollectionStatus;
import io.qdrant.client.grpc.Collections.OptimizerStatus;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.DeletePoints;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.UpsertPoints;
import io.qdrant.client.grpc.Points.VectorOutput;
import io.qdrant.client.grpc.Points.VectorsOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 只注入 QdrantClient mock；不启动 Spring、不构造 gRPC channel、不接触真实向量服务。 */
class QdrantVectorStoreTest {

    private static final String UUID_ID = "123e4567-e89b-12d3-a456-426614174000";
    private QdrantClient client;
    private QdrantVectorStore store;

    @BeforeEach
    void setUp() {
        client = mock(QdrantClient.class);
        store = new QdrantVectorStore(client);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void injectedConstructorAndBackendNameNeverConnectToService() {
        assertEquals("qdrant", store.backendName());
        verifyNoInteractions(client);
    }

    @Test
    void listCollectionsOnlyReturnsManagedNamesWithDimensionsCountsAndIsolatedErrors() {
        String block = "novel-reference-blocks-v2-abcd-d2";
        String broken = "novel-reference-blocks-broken";
        String story = "novel-memory-中文书名";
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("other-app", "novel-worldish",
                "novel-referencesOther", "novel-memory-", "novel-memory-../outside", "C:\\outside",
                "novel-references", block, broken, story, "novel-world-shared", "novel-references")));
        when(client.getCollectionInfoAsync("novel-references")).thenReturn(Futures.immediateFuture(info(2, 7)));
        when(client.getCollectionInfoAsync(block)).thenReturn(Futures.immediateFuture(info(2, 3)));
        when(client.getCollectionInfoAsync(story)).thenReturn(Futures.immediateFuture(info(4, 5)));
        when(client.getCollectionInfoAsync("novel-world-shared")).thenReturn(Futures.immediateFuture(info(8, 9)));
        when(client.getCollectionInfoAsync(broken)).thenReturn(Futures.immediateFailedFuture(new IllegalStateException("damaged collection")));

        List<VectorCollectionInfo> result = store.listCollections();
        assertEquals(List.of(block, broken, story, "novel-references", "novel-world-shared").stream().sorted().toList(),
                result.stream().map(VectorCollectionInfo::name).toList());
        VectorCollectionInfo reference = result.stream().filter(it -> it.name().equals("novel-references")).findFirst().orElseThrow();
        assertEquals(2, reference.dimensions());
        assertEquals(7, reference.pointCount());
        assertEquals("ready", reference.status());
        assertNull(reference.error());
        VectorCollectionInfo bad = result.stream().filter(it -> it.name().equals(broken)).findFirst().orElseThrow();
        assertEquals("error", bad.status());
        assertEquals(-1, bad.pointCount(), "读取失败集合点数未知，不能冒充空集合");
        assertNull(bad.dimensions());
        assertTrue(bad.error().contains("damaged collection"));
        verify(client, never()).getCollectionInfoAsync("other-app");
        verify(client, never()).getCollectionInfoAsync("novel-referencesOther");
        verify(client, times(1)).getCollectionInfoAsync("novel-references");
    }

    @Test
    void listCollectionsUsesConfiguredStoryPrefixAndRetainsReferencesAndWorld() {
        store = new QdrantVectorStore(client, "custom-memory");
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("custom-memory-中文故事",
                "novel-memory-other", "novel-references", "novel-world-shared")));
        when(client.getCollectionInfoAsync(anyString())).thenReturn(Futures.immediateFuture(info(2, 0)));
        assertEquals(List.of("custom-memory-中文故事", "novel-references", "novel-world-shared"),
                store.listCollections().stream().map(VectorCollectionInfo::name).toList());
        verify(client, never()).getCollectionInfoAsync("novel-memory-other");
    }

    @Test
    void redCollectionAndOptimizerFailuresAreErrorsButYellowCanRemainReady() {
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("novel-references-red",
                "novel-reference-blocks-optimizer", "novel-world-yellow")));
        when(client.getCollectionInfoAsync("novel-references-red")).thenReturn(Futures.immediateFuture(
                info(2, 1).toBuilder().setStatus(CollectionStatus.Red).build()));
        when(client.getCollectionInfoAsync("novel-reference-blocks-optimizer")).thenReturn(Futures.immediateFuture(
                info(2, 1).toBuilder().setOptimizerStatus(OptimizerStatus.newBuilder().setError("optimizer error")).build()));
        when(client.getCollectionInfoAsync("novel-world-yellow")).thenReturn(Futures.immediateFuture(
                info(2, 1).toBuilder().setStatus(CollectionStatus.Yellow).build()));
        Map<String, String> statuses = store.listCollections().stream().collect(java.util.stream.Collectors.toMap(
                VectorCollectionInfo::name, VectorCollectionInfo::status));
        assertEquals("error", statuses.get("novel-references-red"));
        assertEquals("error", statuses.get("novel-reference-blocks-optimizer"));
        assertEquals("ready", statuses.get("novel-world-yellow"));
    }

    @Test
    void scrollReturnsFullStringPayloadButNeverVectorsAndPreservesNumericOffset() {
        String collection = "novel-references";
        PointId numeric = PointId.newBuilder().setNum(Long.parseUnsignedLong("18446744073709551615")).build();
        Map<String, JsonWithInt.Value> payload = Map.of("kind", value("ref"), "text", value("全文"),
                "docId", value("doc-1"), "fileName", value("资料.txt"), "group", value("自定义"),
                "ignored-number", JsonWithInt.Value.newBuilder().setIntegerValue(42).build());
        RetrievedPoint pointWithVector = RetrievedPoint.newBuilder().setId(PointId.newBuilder().setUuid(UUID_ID))
                .putAllPayload(payload).setVectors(VectorsOutput.newBuilder()
                        .setVector(VectorOutput.newBuilder().addData(0.1f).addData(0.9f))).build();
        when(client.collectionExistsAsync(collection)).thenReturn(Futures.immediateFuture(true));
        when(client.scrollAsync(any(ScrollPoints.class))).thenReturn(Futures.immediateFuture(
                        ScrollResponse.newBuilder().addResult(pointWithVector).setNextPageOffset(numeric).build()),
                Futures.immediateFuture(ScrollResponse.newBuilder().addResult(RetrievedPoint.newBuilder().setId(numeric)).build()));

        VectorPointPage first = store.pagePoints(collection, null, 1);
        assertEquals(UUID_ID, first.points().get(0).id());
        assertEquals(Map.of("kind", "ref", "text", "全文", "docId", "doc-1", "fileName", "资料.txt", "group", "自定义"),
                first.points().get(0).payload());
        assertNotNull(first.nextCursor());
        assertFalse(first.nextCursor().equals("18446744073709551615"));
        assertEquals(List.of("id", "payload"), Arrays.stream(VectorPointView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertThrows(UnsupportedOperationException.class, () -> first.points().get(0).payload().clear());
        VectorPointPage second = store.pagePoints(collection, first.nextCursor(), 100);
        assertEquals("18446744073709551615", second.points().get(0).id());
        assertNull(second.nextCursor());

        ArgumentCaptor<ScrollPoints> request = ArgumentCaptor.forClass(ScrollPoints.class);
        verify(client, times(2)).scrollAsync(request.capture());
        assertFalse(request.getAllValues().get(0).hasOffset());
        assertEquals(numeric, request.getAllValues().get(1).getOffset());
        assertEquals(1, request.getAllValues().get(0).getLimit());
        assertEquals(100, request.getAllValues().get(1).getLimit());
        for (ScrollPoints scroll : request.getAllValues()) {
            assertEquals(collection, scroll.getCollectionName());
            assertTrue(scroll.getWithPayload().getEnable());
            assertTrue(scroll.hasWithVectors());
            assertFalse(scroll.getWithVectors().getEnable());
        }
    }

    @Test
    void uuidAndZeroNumericScrollOffsetsRemainOpaqueAndRoundTripWithoutIncrement() {
        String collection = "novel-references";
        when(client.collectionExistsAsync(collection)).thenReturn(Futures.immediateFuture(true));
        for (PointId offset : List.of(PointId.newBuilder().setUuid(UUID_ID).build(), PointId.newBuilder().setNum(0).build())) {
            when(client.scrollAsync(any(ScrollPoints.class))).thenReturn(Futures.immediateFuture(
                            ScrollResponse.newBuilder().setNextPageOffset(offset).build()),
                    Futures.immediateFuture(ScrollResponse.getDefaultInstance()));
            String cursor = store.pagePoints(collection, null, 1).nextCursor();
            assertNotNull(cursor);
            store.pagePoints(collection, cursor, 1);
        }
        ArgumentCaptor<ScrollPoints> requests = ArgumentCaptor.forClass(ScrollPoints.class);
        verify(client, times(4)).scrollAsync(requests.capture());
        assertEquals(PointId.newBuilder().setUuid(UUID_ID).build(), requests.getAllValues().get(1).getOffset());
        assertEquals(PointId.newBuilder().setNum(0).build(), requests.getAllValues().get(3).getOffset());
    }

    @Test
    void malformedLimitsCursorsAndNamesFailBeforeAnyClientOperation() {
        for (int limit : new int[]{-1, 0, 101, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> store.pagePoints("novel-references", null, limit));
        }
        for (String cursor : List.of("", "../outside", "C:\\outside", UUID_ID, "42", "qdrant-v1-@@", "qdrant-v1-AA")) {
            assertThrows(IllegalArgumentException.class, () -> store.pagePoints("novel-references", cursor, 1));
        }
        for (String collection : List.of("../outside", "novel-memory-../outside", "novel-memory-", "C:\\outside",
                "C:outside", "/absolute", "\\\\server\\share", "novel-references-a?b")) {
            assertThrows(IllegalArgumentException.class, () -> store.pagePoints(collection, null, 1));
            assertThrows(IllegalArgumentException.class, () -> store.dropCollection(collection));
            assertThrows(IllegalArgumentException.class, () -> store.delete(collection, List.of()));
            assertThrows(IllegalArgumentException.class, () -> store.count(collection));
        }
        verifyNoInteractions(client);
    }

    @Test
    void cursorCannotBeReusedForAnotherCollection() {
        when(client.collectionExistsAsync("novel-references")).thenReturn(Futures.immediateFuture(true));
        when(client.scrollAsync(any(ScrollPoints.class))).thenReturn(Futures.immediateFuture(ScrollResponse.newBuilder()
                .setNextPageOffset(PointId.newBuilder().setNum(42)).build()));
        String cursor = store.pagePoints("novel-references", null, 1).nextCursor();
        assertThrows(IllegalArgumentException.class, () -> store.pagePoints("novel-world-shared", cursor, 1));
        verify(client, never()).collectionExistsAsync("novel-world-shared");
    }

    @Test
    void absentCollectionHasEmptyPageZeroCountAndIdempotentDrop() {
        when(client.collectionExistsAsync("novel-references")).thenReturn(Futures.immediateFuture(false));
        assertEquals(new VectorPointPage(List.of(), null), store.pagePoints("novel-references", null, 100));
        assertEquals(0, store.count("novel-references"));
        assertDoesNotThrow(() -> store.dropCollection("novel-references"));
        assertDoesNotThrow(() -> store.dropCollection("novel-references"));
        verify(client, never()).scrollAsync(any(ScrollPoints.class));
        verify(client, never()).countAsync(anyString());
        verify(client, never()).deleteCollectionAsync(anyString());
        verify(client, never()).deleteAsync(any(DeletePoints.class));
    }

    @Test
    void dropAndClearDeleteOnlyTheRequestedCollectionRatherThanDeletingPoints() {
        when(client.collectionExistsAsync(anyString())).thenReturn(Futures.immediateFuture(true));
        when(client.deleteCollectionAsync(anyString())).thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        store.dropCollection("novel-reference-blocks-v2-hash-d2");
        store.clear("novel-world-shared");
        verify(client).deleteCollectionAsync("novel-reference-blocks-v2-hash-d2");
        verify(client).deleteCollectionAsync("novel-world-shared");
        verify(client, times(2)).deleteCollectionAsync(anyString());
        verify(client, never()).deleteAsync(any(DeletePoints.class));
    }

    @Test
    void pointDeletionPreservesUuidAndUnsignedNumericTypesWaitAndCollectionSelector() {
        when(client.deleteAsync(any(DeletePoints.class))).thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        store.delete("novel-references", List.of(UUID_ID, "42", "18446744073709551615"));
        ArgumentCaptor<DeletePoints> request = ArgumentCaptor.forClass(DeletePoints.class);
        verify(client).deleteAsync(request.capture());
        assertEquals("novel-references", request.getValue().getCollectionName());
        assertTrue(request.getValue().getWait());
        assertTrue(request.getValue().getPoints().hasPoints());
        assertFalse(request.getValue().getPoints().hasFilter());
        assertEquals(List.of(PointId.newBuilder().setUuid(UUID_ID).build(), PointId.newBuilder().setNum(42).build(),
                PointId.newBuilder().setNum(-1L).build()), request.getValue().getPoints().getPoints().getIdsList());
        verify(client, never()).deleteCollectionAsync(anyString());
    }

    @Test
    void invalidPointIdsAndEmptyDeletionDoNotSendDeleteRequest() {
        store.delete("novel-references", List.of());
        store.delete("novel-references", null);
        for (String id : List.of("../outside", "-1", "18446744073709551616", "1-1-1-1-1")) {
            assertThrows(IllegalArgumentException.class, () -> store.delete("novel-references", List.of(id)));
        }
        verifyNoInteractions(client);
    }

    @Test
    void numericUpsertUsesSamePointIdTypeAsScrollAndDelete() {
        when(client.upsertAsync(any(UpsertPoints.class))).thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        store.upsert("novel-references", List.of(VectorPoint.builder().id("42").vector(new float[]{1, 0})
                .payload(Map.of("text", "numeric")).build()));
        ArgumentCaptor<UpsertPoints> request = ArgumentCaptor.forClass(UpsertPoints.class);
        verify(client).upsertAsync(request.capture());
        assertTrue(request.getValue().getPoints(0).getId().hasNum());
        assertEquals(42, request.getValue().getPoints(0).getId().getNum());
    }

    @Test
    void legacyReconciliationScrollStillPreservesNumericIdsAndDisablesVectors() {
        when(client.collectionExistsAsync("novel-references")).thenReturn(Futures.immediateFuture(true));
        when(client.scrollAsync(any(ScrollPoints.class))).thenReturn(Futures.immediateFuture(ScrollResponse.newBuilder()
                .addResult(RetrievedPoint.newBuilder().setId(PointId.newBuilder().setNum(-1L))
                        .putAllPayload(Map.of("kind", value("ref"), "text", value("正文")))).build()));
        assertEquals("18446744073709551615", store.listPoints("novel-references").get(0).getId());
        ArgumentCaptor<ScrollPoints> request = ArgumentCaptor.forClass(ScrollPoints.class);
        verify(client).scrollAsync(request.capture());
        assertFalse(request.getValue().getWithVectors().getEnable());
    }

    @Test
    void listAndPagePreserveInterruptFlag() throws Exception {
        ListenableFuture<List<String>> listing = interruptingFuture();
        when(client.listCollectionsAsync()).thenReturn(listing);
        assertThrows(IllegalStateException.class, store::listCollections);
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
        when(client.collectionExistsAsync("novel-references")).thenReturn(Futures.immediateFuture(true));
        ListenableFuture<ScrollResponse> scroll = interruptingFuture();
        when(client.scrollAsync(any(ScrollPoints.class))).thenReturn(scroll);
        assertThrows(IllegalStateException.class, () -> store.pagePoints("novel-references", null, 1));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void singleCollectionInfoInterruptAbortsListingAndPreservesFlag() throws Exception {
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("novel-references")));
        ListenableFuture<CollectionInfo> info = interruptingFuture();
        when(client.getCollectionInfoAsync("novel-references")).thenReturn(info);
        assertThrows(IllegalStateException.class, store::listCollections);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void dropPreservesInterruptAndConnectionFailuresKeepExistingCircuitStyle() throws Exception {
        ListenableFuture<Boolean> exists = interruptingFuture();
        when(client.collectionExistsAsync("novel-references")).thenReturn(exists);
        assertThrows(IllegalStateException.class, () -> store.dropCollection("novel-references"));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
        when(client.collectionExistsAsync("novel-references")).thenReturn(Futures.immediateFuture(true));
        when(client.deleteCollectionAsync("novel-references")).thenReturn(Futures.immediateFailedFuture(
                new IllegalStateException("UNAVAILABLE: Connection refused")));
        assertThrows(IllegalStateException.class, () -> store.dropCollection("novel-references"));
        IllegalStateException circuit = assertThrows(IllegalStateException.class, () -> store.count("novel-references"));
        assertTrue(circuit.getMessage().contains("熔断"));
        verify(client, never()).countAsync(anyString());
    }

    private static CollectionInfo info(int dimensions, long pointCount) {
        return CollectionInfo.newBuilder().setStatus(CollectionStatus.Green).setPointsCount(pointCount)
                .setConfig(CollectionConfig.newBuilder().setParams(CollectionParams.newBuilder()
                        .setVectorsConfig(VectorsConfig.newBuilder().setParams(VectorParams.newBuilder().setSize(dimensions)))))
                .build();
    }

    private static JsonWithInt.Value value(String value) {
        return JsonWithInt.Value.newBuilder().setStringValue(value).build();
    }

    @SuppressWarnings("unchecked")
    private static <T> ListenableFuture<T> interruptingFuture() throws Exception {
        ListenableFuture<T> future = mock(ListenableFuture.class);
        when(future.get(30, TimeUnit.SECONDS)).thenThrow(new InterruptedException("test interruption"));
        return future;
    }
}
