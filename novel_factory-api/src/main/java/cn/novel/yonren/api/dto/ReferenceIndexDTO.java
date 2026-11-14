package cn.novel.yonren.api.dto;

import java.util.List;
import java.util.Map;

/** 资料索引管理视图，不返回向量数组或供应商凭据。 */
public record ReferenceIndexDTO(String backend, String version, boolean busy, String busyReason,
                                List<CollectionView> collections, List<SourceView> sources, TaskView task) {

    public record Owner(String storyDirName, String novelTitle) {
    }

    public record CollectionView(String id, String name, String kind, String state, Integer dimensions,
                                 long pointCount, String error, boolean canClear, boolean canDelete,
                                 boolean canRebuild, List<Owner> owners) {
        public CollectionView {
            owners = owners == null ? List.of() : List.copyOf(owners);
        }

        public CollectionView(String id, String name, String kind, String state, Integer dimensions,
                              long pointCount, String error, boolean canClear, boolean canDelete) {
            this(id, name, kind, state, dimensions, pointCount, error, canClear, canDelete, false, List.of());
        }
    }

    public record SourceView(String id, String title, String group, boolean builtin, boolean enabled,
                             int blockCount, List<String> collections) {
    }

    public record PointView(String id, Map<String, String> payload) {
    }

    public record PointPage(List<PointView> points, String nextCursor) {
    }

    public record CollectionResult(String id, String name, String status, String message) {
    }

    public record TaskView(String id, String status, String message, List<CollectionResult> results) {
        public TaskView {
            results = results == null ? List.of() : List.copyOf(results);
        }

        public TaskView(String id, String status, String message) {
            this(id, status, message, List.of());
        }
    }

    public record Preview(String token, String action, String title, String message, String version,
                           List<CollectionView> collections, List<SourceView> sources,
                           long embeddingChars, int embeddingBatches) {
    }

    public record OperationResult(boolean completed, String taskId, String message, List<CollectionResult> results) {
        public OperationResult {
            results = results == null ? List.of() : List.copyOf(results);
        }

        public OperationResult(boolean completed, String taskId, String message) {
            this(completed, taskId, message, List.of());
        }
    }
}
