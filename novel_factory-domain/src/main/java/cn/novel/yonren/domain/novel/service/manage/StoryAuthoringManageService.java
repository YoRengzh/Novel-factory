package cn.novel.yonren.domain.novel.service.manage;

import cn.novel.yonren.api.dto.StoryAuthoringEntryDTO;
import cn.novel.yonren.api.dto.StoryAuthoringRequestDTO;
import cn.novel.yonren.api.dto.StoryAuthoringResponseDTO;
import cn.novel.yonren.api.dto.StoryAuthoringSnapshotDTO;
import cn.novel.yonren.api.dto.StoryAuthoringSourceDTO;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.valobj.authoring.StoryAuthoringPatch;
import cn.novel.yonren.domain.novel.model.valobj.authoring.StoryAuthoringPreview;
import cn.novel.yonren.domain.novel.model.valobj.authoring.StoryAuthoringSnapshot;
import cn.novel.yonren.domain.novel.model.valobj.authoring.StoryAuthoringView;
import cn.novel.yonren.domain.novel.service.job.JobRegistry;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 书级创作维护门面：只做 DTO 拆装与互斥，业务规则在 {@code StoryAuthoringService}。
 *
 * <p>维护是纯本地操作——不调用模型、不生成正文、不回流记忆。保存必须带预览哈希，
 * 因为候选版本是在预览时按当时的正文依据算出来的，直接写会让"作者看到的"和"落盘的"分叉。
 */
@Slf4j
@Service
public class StoryAuthoringManageService {

    @Resource
    private IStoryRepository storyRepository;

    @Resource
    private JobRegistry jobRegistry;

    /** 只读视图：不创建目录、不恢复未完成提交。 */
    public StoryAuthoringResponseDTO view(String storyDirName) {
        Path storyDir = resolveReadOnly(storyDirName);
        StoryAuthoringView view = repo(() -> storyRepository.readStoryAuthoringView(storyDir));
        return response(view, null, null, null);
    }

    /** 预览：按当前依据算出候选与预览哈希，不落盘。 */
    public StoryAuthoringResponseDTO preview(String storyDirName, StoryAuthoringRequestDTO request) {
        Path storyDir = resolveReadOnly(storyDirName);
        StoryAuthoringPatch patch = patch(request);
        StoryAuthoringPreview preview = repo(() -> storyRepository.previewStoryAuthoring(storyDir, patch));
        return response(preview.view(), preview.candidate(), preview.previewHash(), null);
    }

    /**
     * 保存：必须携带预览哈希。与作业互斥——维护期间不得启动同书生成，
     * 生成期间也不得开始维护，否则两侧各持一份陈旧依据写回同一目录。
     */
    public StoryAuthoringResponseDTO save(String storyDirName, StoryAuthoringRequestDTO request) {
        if (request == null || request.getPreviewHash() == null || request.getPreviewHash().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "请先预览并提供 previewHash");
        }
        if (!jobRegistry.tryBeginStoryOperation(storyDirName)) {
            throw new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(),
                    "该故事有活动作业或维护操作，请完成后重新预览保存");
        }
        try {
            Path storyDir = resolveReadOnly(storyDirName);
            repo(() -> { storyRepository.recoverStoryAuthoringCommit(storyDir); return null; });
            StoryAuthoringPatch patch = patch(request);
            StoryAuthoringView saved = repo(() -> storyRepository.safeApplyStoryAuthoringPreview(
                    storyDir, patch, request.getPreviewHash()));
            return response(saved, null, null, true);
        } finally {
            jobRegistry.endStoryOperation(storyDirName);
        }
    }

    private Path resolveReadOnly(String storyDirName) {
        return repo(() -> storyRepository.resolveStoryDirectoryReadOnly(storyDirName));
    }

    private <T> T repo(RepoCall<T> call) {
        try {
            return call.get();
        } catch (AppException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            throw new AppException(ResponseCode.ACTIVE_JOB_CONFLICT.getCode(), e.getMessage());
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "作者维护操作失败：" + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface RepoCall<T> {
        T get() throws Exception;
    }

    private static StoryAuthoringPatch patch(StoryAuthoringRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("必须提供作者维护请求");
        }
        return new StoryAuthoringPatch(request.getExpectedRevision(), request.getExpectedBasis(),
                request.getSettings(),
                request.getEntries() == null ? null
                        : request.getEntries().stream().map(StoryAuthoringManageService::entry).toList(),
                request.getCharacterDepthMode(), depthSettings(request.getCharacterDepthSettings()),
                request.getCharacterProfiles() == null ? null
                        : request.getCharacterProfiles().stream().map(StoryAuthoringManageService::profile).toList());
    }

    private static StoryAuthoringSnapshot.Entry entry(StoryAuthoringEntryDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("entries 不能包含 null");
        }
        StoryAuthoringSnapshot.Kind kind;
        StoryAuthoringSnapshot.Operation operation;
        try {
            kind = StoryAuthoringSnapshot.Kind.valueOf(dto.getKind());
            operation = StoryAuthoringSnapshot.Operation.valueOf(dto.getOperation());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException("条目 kind/operation 非法");
        }
        return new StoryAuthoringSnapshot.Entry(null, kind, dto.getEntityName(), operation, dto.getBefore(),
                dto.getAfter(), dto.getReason(), dto.getSourceChapter(), dto.getSourceHash(), dto.getEvidence(),
                dto.getEffectiveFromChapter() == null ? 0 : dto.getEffectiveFromChapter(), null);
    }

    private static StoryAuthoringResponseDTO response(StoryAuthoringView view, StoryAuthoringSnapshot candidate,
                                                      String previewHash, Boolean saved) {
        Map<String, String> settings = new LinkedHashMap<>(view.settings());
        if (candidate != null) {
            settings.putAll(candidate.settingsOverrides());
        }
        return new StoryAuthoringResponseDTO(settings, view.revision(), view.basis(),
                snapshot(candidate == null ? view.snapshot() : candidate), snapshot(view.activeSnapshot()),
                view.sources().stream().map(source -> new StoryAuthoringSourceDTO(source.id(),
                        source.kind().name(), source.entityName(), source.currentState(), source.sourceChapter(),
                        source.sourceHash(), source.evidence(), source.verified())).toList(),
                view.effectiveFromChapter(), previewHash, saved,
                executionDTO(candidate == null ? view.characterExecution() : candidate.characterExecution()));
    }

    private static StoryAuthoringSnapshotDTO snapshot(StoryAuthoringSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new StoryAuthoringSnapshotDTO(snapshot.schemaVersion(), snapshot.revision(), snapshot.hash(),
                snapshot.effectiveFromChapter(), snapshot.settingsOverrides(),
                snapshot.entries().stream().map(value -> {
                    StoryAuthoringEntryDTO dto = new StoryAuthoringEntryDTO();
                    dto.setId(value.id());
                    dto.setKind(value.kind().name());
                    dto.setEntityName(value.entityName());
                    dto.setOperation(value.operation().name());
                    dto.setBefore(value.before());
                    dto.setAfter(value.after());
                    dto.setReason(value.reason());
                    dto.setSourceChapter(value.sourceChapter());
                    dto.setSourceHash(value.sourceHash());
                    dto.setEvidence(value.evidence());
                    dto.setEffectiveFromChapter(value.effectiveFromChapter());
                    dto.setStatus(value.status() == null ? null : value.status().name());
                    return dto;
                }).toList(), snapshot.characterDepthMode(),
                settingsDTO(snapshot.characterExecution() == null ? null : snapshot.characterExecution().settings()),
                snapshot.characterExecution() == null || snapshot.characterExecution().profiles() == null ? null
                        : snapshot.characterExecution().profiles().profiles().stream().map(StoryAuthoringManageService::profileDTO).toList(),
                executionDTO(snapshot.characterExecution()));
    }

    private static cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthSettings depthSettings(
            cn.novel.yonren.api.dto.CharacterDepthSettingsDTO dto) {
        if (dto == null) return null;
        var defaults = cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthSettings.defaults(dto.getMode());
        return new cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthSettings(dto.getMode(),
                dto.getContextMaxChars() == null ? defaults.contextMaxChars() : dto.getContextMaxChars(),
                dto.getFeedbackMaxChars() == null ? defaults.feedbackMaxChars() : dto.getFeedbackMaxChars(),
                dto.getMaxNewProfilesPerPass() == null ? defaults.maxNewProfilesPerPass() : dto.getMaxNewProfilesPerPass(),
                dto.getPolicyVersion() == null ? defaults.policyVersion() : dto.getPolicyVersion());
    }

    private static cn.novel.yonren.domain.novel.model.valobj.character.CharacterDecisionProfile profile(
            cn.novel.yonren.api.dto.CharacterDecisionProfileDTO dto) {
        if (dto == null) throw new IllegalArgumentException("characterProfiles不能包含null");
        // 请求里的身份、来源和采纳状态不是授权，永远由adoptAuthor重新绑定。
        return new cn.novel.yonren.domain.novel.model.valobj.character.CharacterDecisionProfile(dto.getDisplayName(),
                dto.getLocked(), dto.getWant(), dto.getNeed(), dto.getFearAndDefense(), dto.getValuePriorities(),
                dto.getBoundaries(), dto.getIndependentConcerns(), dto.getDecisionPattern());
    }

    private static cn.novel.yonren.api.dto.CharacterDecisionProfileDTO profileDTO(
            cn.novel.yonren.domain.novel.model.valobj.character.CharacterDecisionProfile value) {
        var dto = new cn.novel.yonren.api.dto.CharacterDecisionProfileDTO();
        dto.setCharacterId(value.characterId()); dto.setDisplayName(value.displayName()); dto.setLocked(value.locked());
        dto.setWant(value.want()); dto.setNeed(value.need()); dto.setFearAndDefense(value.fearAndDefense());
        dto.setValuePriorities(value.valuePriorities()); dto.setBoundaries(value.boundaries());
        dto.setIndependentConcerns(value.independentConcerns()); dto.setDecisionPattern(value.decisionPattern());
        dto.setSource(value.source().name()); dto.setAdoption(value.adoption().name());
        return dto;
    }

    private static cn.novel.yonren.api.dto.CharacterDepthSettingsDTO settingsDTO(
            cn.novel.yonren.domain.novel.model.valobj.character.CharacterDepthSettings value) {
        if (value == null) return null;
        var dto = new cn.novel.yonren.api.dto.CharacterDepthSettingsDTO();
        dto.setMode(value.mode()); dto.setContextMaxChars(value.contextMaxChars());
        dto.setFeedbackMaxChars(value.feedbackMaxChars()); dto.setMaxNewProfilesPerPass(value.maxNewProfilesPerPass());
        dto.setPolicyVersion(value.policyVersion());
        return dto;
    }

    private static cn.novel.yonren.api.dto.CharacterExecutionContextDTO executionDTO(
            cn.novel.yonren.domain.novel.model.valobj.character.CharacterExecutionContext value) {
        if (value == null) return null;
        var set = value.profiles();
        return new cn.novel.yonren.api.dto.CharacterExecutionContextDTO(value.basisVersion(), settingsDTO(value.settings()),
                set == null ? null : new cn.novel.yonren.api.dto.CharacterExecutionContextDTO.ProfileSet(set.schemaVersion(),
                        set.revision(), set.hash(), set.effectiveFromChapter(),
                        set.profiles().stream().map(StoryAuthoringManageService::profileDTO).toList()));
    }
}
