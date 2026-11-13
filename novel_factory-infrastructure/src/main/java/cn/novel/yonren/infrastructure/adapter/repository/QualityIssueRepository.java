package cn.novel.yonren.infrastructure.adapter.repository;

import cn.novel.yonren.domain.novel.adapter.repository.IQualityIssueRepository;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.quality.*;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterAcceptance;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.quality.QualityIssuePolicy;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Uses StoryRepository's existing shared lock; only quality-issue-actions.json is writable here. */
@Repository
public class QualityIssueRepository implements IQualityIssueRepository {
    private static final String FILE = "quality-issue-actions.json";
    private static final long MAX_STATE_BYTES = 8L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final IStoryRepository storyRepository;

    public QualityIssueRepository(IStoryRepository storyRepository) {
        this.storyRepository = Objects.requireNonNull(storyRepository);
    }

    @Override
    public List<QualityIssue> list(Path storyDir) throws Exception {
        return StoryRepository.withSharedStoryLock(storyDir, () -> {
            var state = readState(storyDir);
            return snapshots(storyDir, state).stream().map(QualityIssueSnapshot::issue).toList();
        });
    }

    @Override
    public QualityIssue appendAction(Path storyDir, String issueId, QualityIssueDisposition.Action action,
                                    String reason, String expectedRevision, String expectedSourceHash) throws Exception {
        QualityIssuePolicy.validateExpected(issueId, expectedRevision, expectedSourceHash);
        QualityIssuePolicy.validateReason(reason);
        if (action == null || action == QualityIssueDisposition.Action.RECHECK)
            throw new IllegalArgumentException("action 仅接受 NOTE、DEFER 或 FALSE_POSITIVE");
        return StoryRepository.withSharedStoryLock(storyDir, () -> {
            checkCancelled();
            assertSafeStory(storyDir);
            storyRepository.assertStoryMaintenanceWriteBoundary(storyDir);
            var state = readState(storyDir);
            var snapshot = requireSnapshot(storyDir, state, issueId, expectedRevision, expectedSourceHash);
            var next = QualityIssuePolicy.append(state.journal(), issueId, action, reason.strip(), expectedSourceHash, null);
            writeState(storyDir, state.hash(), next);
            return withHistory(snapshot.issue(), next);
        });
    }

    @Override
    public QualityIssueSnapshot freezeForRecheck(Path storyDir, String issueId, String expectedRevision,
                                                String expectedSourceHash) throws Exception {
        QualityIssuePolicy.validateExpected(issueId, expectedRevision, expectedSourceHash);
        return StoryRepository.withSharedStoryLock(storyDir, () -> {
            checkCancelled();
            assertSafeStory(storyDir);
            storyRepository.assertStoryMaintenanceWriteBoundary(storyDir);
            return requireSnapshot(storyDir, readState(storyDir), issueId, expectedRevision, expectedSourceHash);
        });
    }

    @Override
    public QualityIssue appendRecheck(Path storyDir, QualityIssueSnapshot frozen,
                                     QualityIssueRecheck result) throws Exception {
        if (frozen == null || frozen.issue() == null || result == null
                || !Objects.equals(result.sourceHash(), frozen.issue().sourceHash())
                || !Objects.equals(result.contentHash(), frozen.issue().currentContentHash())
                || !Objects.equals(result.manuscriptHash(), frozen.issue().currentManuscriptHash())
                || !Objects.equals(result.planHash(), frozen.issue().planHash()))
            throw new IllegalStateException("复核结果未绑定冻结的问题、正文与计划");
        return StoryRepository.withSharedStoryLock(storyDir, () -> {
            checkCancelled();
            assertSafeStory(storyDir);
            storyRepository.assertStoryMaintenanceWriteBoundary(storyDir);
            var state = readState(storyDir);
            if (!Objects.equals(state.hash(), frozen.storageHash()))
                throw new IllegalStateException("复核期间处置历史发生变化，结果未保存，请重新读取");
            var source = requireSnapshot(storyDir, state, frozen.issue().id(), frozen.issue().disposition().revision(),
                    frozen.issue().sourceHash());
            var next = QualityIssuePolicy.append(state.journal(), source.issue().id(),
                    QualityIssueDisposition.Action.RECHECK, "用户明确请求复核当前稿", source.issue().sourceHash(), result);
            writeState(storyDir, state.hash(), next);
            return withHistory(source.issue(), next);
        });
    }

    private QualityIssueSnapshot requireSnapshot(Path storyDir, State state, String id,
                                                 String revision, String sourceHash) throws Exception {
        var snapshot = snapshots(storyDir, state).stream().filter(row -> row.issue().id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("问题来源已变化或不存在，请重新读取待处理问题"));
        if (!snapshot.issue().sourceHash().equals(sourceHash))
            throw new IllegalStateException("正文、计划或质量问题来源已变化，请重新读取后操作");
        if (!snapshot.issue().disposition().revision().equals(revision))
            throw new IllegalStateException("问题处置已有新版本，请重新读取后操作");
        return snapshot;
    }

    private List<QualityIssueSnapshot> snapshots(Path storyDir, State state) throws Exception {
        assertSafeStory(storyDir);
        assertRegularIfPresent(storyDir.resolve("memory/quality-debts.json"));
        assertRegularIfPresent(storyDir.resolve("memory/chapter-acceptances.json"));
        assertRegularIfPresent(storyDir.resolve("story-bible.txt"));
        var debts = storyRepository.readQualityDebts(storyDir);
        if (debts == null) throw new IllegalStateException("质量问题来源不可读");
        var origins = new TreeMap<String, Origin>();
        for (var debt : debts) {
            if (debt == null || debt.getChapterNo() == null || debt.getChapterNo() <= 0 || debt.getIssues() == null)
                throw new IllegalStateException("质量问题来源损坏，拒绝生成虚构问题");
            for (var source : debt.getIssues()) {
                if (source == null) throw new IllegalStateException("质量问题来源包含空条目");
                var issue = JSON.parseObject(JSON.toJSONString(source), ChapterIssueEntity.class);
                String id = QualityIssuePolicy.issueId(debt.getChapterNo(), issue);
                String provenance = JSON.toJSONString(List.of(debt.getChapterNo(), issue,
                        debt.isResolved(), debt.getCleanStreak()));
                var next = new Origin(debt.getChapterNo(), issue, debt.isResolved(), new ArrayList<>(List.of(provenance)));
                origins.merge(id, next, QualityIssueRepository::mergeOrigins);
            }
        }
        if (origins.isEmpty()) return List.of();
        String bible = QualityIssuePolicy.text(storyRepository.readStoryBible(storyDir));
        var chapters = new HashMap<Integer, ChapterSources>();
        var rows = new ArrayList<QualityIssueSnapshot>();
        for (var entry : origins.entrySet()) {
            var origin = entry.getValue();
            var chapter = chapters.get(origin.chapterNo());
            if (chapter == null) {
                assertChapterSources(storyDir, origin.chapterNo());
                var body = storyRepository.readChapter(storyDir, origin.chapterNo());
                if (body != null && !Objects.equals(body.getChapterNo(), origin.chapterNo()))
                    throw new IllegalStateException("章节来源编号不匹配");
                var plan = storyRepository.readChapterPlanItem(storyDir, origin.chapterNo());
                plan = plan == null ? null : JSON.parseObject(JSON.toJSONString(plan), ChapterPlanItemEntity.class);
                var acceptance = storyRepository.readChapterAcceptance(storyDir, origin.chapterNo());
                chapter = new ChapterSources(body == null ? null : ChapterManuscript.of(body), plan, acceptance);
                chapters.put(origin.chapterNo(), chapter);
            }
            String manuscriptHash = chapter.manuscript() == null ? null : chapter.manuscript().hash();
            String contentHash = chapter.manuscript() == null ? null : ChapterManuscript.digest(chapter.manuscript().content());
            String planHash = chapter.plan() == null ? null : QualityIssuePolicy.digest(chapter.plan());
            String acceptedHash = chapter.acceptance() == null ? null : chapter.acceptance().manuscriptHash();
            String sourceHash = QualityIssuePolicy.digest(Arrays.asList("quality-source-v1", entry.getKey(),
                    origin.provenance().stream().sorted().toList(), manuscriptHash, planHash,
                    chapter.acceptance(), ChapterManuscript.digest(bible)));
            var issue = origin.issue();
            var row = new QualityIssue(entry.getKey(), origin.chapterNo(), sourceHash,
                    QualityIssuePolicy.text(issue.getDimension()), QualityIssuePolicy.text(issue.getSeverity()),
                    QualityIssuePolicy.text(issue.getDescription()), QualityIssuePolicy.text(issue.getEvidence()),
                    QualityIssuePolicy.text(issue.getSuggestion()), origin.systemResolved(),
                    QualityIssuePolicy.disposition(state.journal(), entry.getKey()),
                    currentRecheck(state.journal(), entry.getKey(), sourceHash), contentHash, manuscriptHash, acceptedHash, planHash);
            rows.add(new QualityIssueSnapshot(row, chapter.manuscript(), chapter.plan(), bible, state.hash()));
        }
        rows.sort(Comparator.comparingInt((QualityIssueSnapshot row) -> row.issue().chapterNo())
                .thenComparing(row -> row.issue().id()));
        return List.copyOf(rows);
    }

    private static Origin mergeOrigins(Origin first, Origin second) {
        var provenance = new ArrayList<>(first.provenance());
        provenance.addAll(second.provenance());
        // Identical findings from repeated debt rows share identity, never an array-index suffix.
        var selected = Comparator.comparing((ChapterIssueEntity issue) -> !"BLOCKING".equalsIgnoreCase(issue.getSeverity()))
                .thenComparing(issue -> JSON.toJSONString(issue)).compare(first.issue(), second.issue()) <= 0
                ? first.issue() : second.issue();
        return new Origin(first.chapterNo(), selected, first.systemResolved() && second.systemResolved(), provenance);
    }

    private static QualityIssue withHistory(QualityIssue source, QualityIssueJournal journal) {
        return new QualityIssue(source.id(), source.chapterNo(), source.sourceHash(), source.dimension(), source.severity(),
                source.description(), source.evidence(), source.suggestion(), source.systemResolved(),
                QualityIssuePolicy.disposition(journal, source.id()), currentRecheck(journal, source.id(), source.sourceHash()),
                source.currentContentHash(), source.currentManuscriptHash(), source.acceptedManuscriptHash(), source.planHash());
    }

    private static QualityIssueRecheck currentRecheck(QualityIssueJournal journal, String id, String sourceHash) {
        var result = QualityIssuePolicy.latestRecheck(journal, id);
        return result == null ? null : result.forSource(sourceHash);
    }

    private State readState(Path storyDir) throws IOException {
        assertSafeStory(storyDir);
        Path file = storyDir.resolve("memory").resolve(FILE);
        assertRegularIfPresent(file);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
            return new State(QualityIssueJournal.empty(storyDir.getFileName().toString()), ChapterManuscript.digest("ABSENT"));
        try {
            String raw = readUtf8(file);
            QualityIssueJournal journal = MAPPER.readValue(raw, QualityIssueJournal.class);
            QualityIssuePolicy.validateJournal(journal, storyDir.getFileName().toString());
            return new State(journal, ChapterManuscript.digest(raw));
        } catch (IOException | RuntimeException corrupt) {
            throw new IllegalStateException("质量问题处置历史损坏或不可读，拒绝覆盖，请从备份恢复", corrupt);
        }
    }

    private void writeState(Path storyDir, String expectedHash, QualityIssueJournal journal) throws IOException {
        checkCancelled();
        QualityIssuePolicy.validateJournal(journal, storyDir.getFileName().toString());
        assertSafeStory(storyDir);
        byte[] bytes = MAPPER.writeValueAsBytes(journal);
        if (bytes.length > MAX_STATE_BYTES) throw new IllegalStateException("处置历史达到安全大小上限，拒绝截断历史");
        Path memory = storyDir.resolve("memory");
        if (Files.notExists(memory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(memory);
        assertDirectoryIfPresent(memory);
        Path file = memory.resolve(FILE);
        Path temporary = Files.createTempFile(memory, ".quality-issue-actions-", ".tmp");
        try {
            assertRegularIfPresent(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (!readState(storyDir).hash().equals(expectedHash))
                throw new IllegalStateException("处置历史在保存前发生变化，请重新读取");
            checkCancelled();
            assertRegularIfPresent(file);
            // No non-atomic fallback: inability to atomically replace must leave the previous history intact.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String readUtf8(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > MAX_STATE_BYTES) throw new IOException("处置历史超过安全大小上限");
            var output = new ByteArrayOutputStream();
            var buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                if ((long) output.size() + buffer.remaining() > MAX_STATE_BYTES)
                    throw new IOException("处置历史超过安全大小上限");
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString();
        }
    }

    private static void assertSafeStory(Path storyDir) throws IOException {
        if (storyDir == null || !Files.isDirectory(storyDir, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("故事目录不存在或不可安全访问");
        assertDirectoryIfPresent(storyDir);
        assertDirectoryIfPresent(storyDir.resolve("memory"));
        assertDirectoryIfPresent(storyDir.resolve("chapters"));
        assertDirectoryIfPresent(storyDir.resolve("generation-records"));
    }

    private static void assertChapterSources(Path storyDir, int chapterNo) throws IOException {
        assertRegularIfPresent(storyDir.resolve("chapters").resolve(String.format("chapter-%04d.txt", chapterNo)));
        Path records = storyDir.resolve("generation-records");
        if (!Files.exists(records, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(records)) {
            for (Path run : entries.toList()) {
                if (Files.isSymbolicLink(run)) throw new IllegalStateException("生成记录不允许符号链接");
                if (!Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS)) continue;
                assertDirectoryIfPresent(run);
                assertRegularIfPresent(run.resolve(String.format("output-%04d.json", chapterNo)));
            }
        }
    }

    private static void assertDirectoryIfPresent(Path path) throws IOException { assertPath(path, true); }
    private static void assertRegularIfPresent(Path path) throws IOException { assertPath(path, false); }
    private static void assertPath(Path path, boolean directory) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther()
                || (directory ? !attrs.isDirectory() : !attrs.isRegularFile())
                || !path.toRealPath().equals(path.toAbsolutePath().normalize()))
            throw new IllegalStateException("质量问题来源或处置文件不允许符号链接、重定向或非普通文件");
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("操作已取消，未保存质量复核结果");
    }

    private record State(QualityIssueJournal journal, String hash) { }
    private record ChapterSources(ChapterManuscript manuscript, ChapterPlanItemEntity plan, ChapterAcceptance acceptance) { }
    private record Origin(int chapterNo, ChapterIssueEntity issue, boolean systemResolved, List<String> provenance) { }
}
