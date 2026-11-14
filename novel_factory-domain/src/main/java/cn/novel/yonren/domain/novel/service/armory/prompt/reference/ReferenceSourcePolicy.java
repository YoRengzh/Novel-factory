package cn.novel.yonren.domain.novel.service.armory.prompt.reference;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 资料来源的持久化启停策略。标识使用 ReferenceIndexEntry.fileName（不带 .md）；
 * 停用记录不依赖文件是否存在，修改、删除或重新导入同名原文都不会解除停用。
 */
@Component
public class ReferenceSourcePolicy {

    private static final String NAME_PATTERN = "[a-z0-9][a-z0-9-]{0,59}";
    private final Path stateFile;
    private volatile State state;

    public ReferenceSourcePolicy() {
        this(Path.of("data", "reference-source-state.json"));
    }

    /** 测试或嵌入式调用可显式指定状态文件；构造不会创建文件。 */
    public ReferenceSourcePolicy(Path stateFile) {
        this.stateFile = Objects.requireNonNull(stateFile, "source state path").toAbsolutePath().normalize();
    }

    public boolean isEnabled(String id) {
        return !state().disabledSources().contains(validateSourceId(id));
    }

    /** 不可修改的停用标识快照，包括已删除文件的停用记录。 */
    public Set<String> disabledSources() {
        return state().disabledSources();
    }

    public long revision() {
        return state().revision();
    }

    /** 仅实际状态改变时递增 revision；原子落盘成功后才发布内存状态。 */
    public synchronized void setEnabled(String id, boolean enabled) {
        String name = validateSourceId(id);
        State current = state();
        Set<String> disabled = new TreeSet<>(current.disabledSources());
        boolean changed = enabled ? disabled.remove(name) : disabled.add(name);
        if (!changed) {
            return;
        }
        State next = new State(Set.copyOf(disabled), Math.incrementExact(current.revision()));
        persist(next);
        state = next;
    }

    /** 与 UserAssetStore 共用命名校验，不接受扩展名、路径、空白或保留 design- 前缀。 */
    public static String validateSourceId(String id) {
        if (id == null || !id.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("资料名只能用小写字母、数字与连字符（≤60 位）：" + id);
        }
        if (id.startsWith("design-")) {
            throw new IllegalArgumentException("design- 前缀为保留字（内部设计文档），请换个名字");
        }
        return id;
    }

    private State state() {
        State current = state;
        if (current == null) {
            synchronized (this) {
                if (state == null) {
                    state = load();
                }
                current = state;
            }
        }
        return current;
    }

    private State load() {
        if (Files.notExists(stateFile)) {
            return new State(Set.of(), 0L);
        }
        try {
            JSONObject root = JSON.parseObject(Files.readString(stateFile, StandardCharsets.UTF_8));
            if (root == null || !root.keySet().equals(Set.of("disabledSources", "revision"))
                    || !(root.get("disabledSources") instanceof JSONArray values)
                    || !(root.get("revision") instanceof Number revision)
                    || !(revision instanceof Integer || revision instanceof Long) || revision.longValue() < 0) {
                throw new IllegalArgumentException("来源状态必须包含 disabledSources 数组和非负整数 revision");
            }
            Set<String> disabled = new TreeSet<>();
            for (Object value : values) {
                if (!(value instanceof String id) || !disabled.add(validateSourceId(id))) {
                    throw new IllegalArgumentException("非法或重复的停用来源：" + value);
                }
            }
            return new State(Set.copyOf(disabled), revision.longValue());
        } catch (IOException | RuntimeException e) {
            // 不把损坏的停用状态当成“全部开启”。
            throw new IllegalStateException("参考资料来源状态读取失败：" + stateFile, e);
        }
    }

    private void persist(State next) {
        Path temporary = null;
        try {
            Files.createDirectories(stateFile.getParent());
            temporary = Files.createTempFile(stateFile.getParent(), stateFile.getFileName() + ".", ".tmp");
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("disabledSources", new TreeSet<>(next.disabledSources()));
            json.put("revision", next.revision());
            byte[] bytes = JSON.toJSONString(json).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("参考资料来源状态原子写入失败：" + stateFile, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 失败时保留原状态；临时文件不会被策略加载。
                }
            }
        }
    }

    private record State(Set<String> disabledSources, long revision) {
    }
}
