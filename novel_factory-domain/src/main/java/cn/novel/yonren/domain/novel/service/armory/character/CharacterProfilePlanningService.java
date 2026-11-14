package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 蓝图前冻结人物设计；普通续写继承激活引用，不逐章读取工作副本。 */
@Slf4j
@Service
public class CharacterProfilePlanningService {
    @Resource private IStoryRepository repository;
    @Resource private CharacterDecisionDesignService designer;

    public void ensure(ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext context) throws Exception {
        var story = command.getStoryContextEntity();
        CharacterDecisionDesignService.ensureRunning(context);
        var active = context.getStoryDir() == null ? null : repository.readActiveCharacterExecution(context.getStoryDir());
        if (story.getCharacterDepthSettings() == null && active != null) {
            story.setCharacterDepthSettings(active.settings());
            story.setCharacterDepthMode(active.settings().mode());
            story.setCharacterDecisionProfiles(active.profiles());
        }
        String mode = CharacterDepthSettings.mode(story);
        if ("OFF".equals(mode)) return;
        if (context.getChapterOffset() > 0 && story.getStoryAuthoring() != null
                && story.getStoryAuthoring().characterExecution() == null && story.getCharacterDepthSettings() == null) {
            log.warn("人物设计LEGACY_APPLY：沿历史最小模式，需作者在书内明确保存新版模式");
            return;
        }
        if (story.getCharacterDepthSettings() == null) story.setCharacterDepthSettings(CharacterDepthSettings.defaults(mode));
        if ("OBSERVE".equals(mode)) {
            log.info("人物深度OBSERVE：不建卡、不改变规划与正文；策略{}", story.getCharacterDepthSettings().policyVersion());
            return;
        }
        if (story.getCharacterDecisionProfiles() != null) {
            CharacterDecisionProfileService.validatePersisted(story.getCharacterDecisionProfiles());
            log.info("人物设计沿冻结档案{}", story.getCharacterDecisionProfiles().revision());
            return;
        }
        List<String> names = coreNames(story.getProtagonist(), story.getCharacterProfiles(), story.getCharacterDepthSettings().maxNewProfilesPerPass());
        var designed = designer.design(command, context, names);
        CharacterDecisionDesignService.ensureRunning(context);
        var profiles = designed.profiles().isEmpty() ? null : CharacterDecisionProfileService.adoptAuto(
                null, designed.profiles(), context.getChapterOffset() + 1);
        story.setCharacterDecisionProfiles(profiles);
        if (context.getStoryDir() == null) context.setStoryDir(repository.createStoryDirectory());
        repository.writeStoryBible(context.getStoryDir(), command);
        repository.writeActiveCharacterExecution(context.getStoryDir(), CharacterExecutionContext.from(story));
        if (context.getJob() != null) context.getJob().setStoryDirName(context.getStoryDir().getFileName().toString());
        log.info("人物设计{}：模式{}，策略{}，档案{}，生效章{}", designed.diagnostic(), mode,
                story.getCharacterDepthSettings().policyVersion(), profiles == null ? "NO_PROFILES" : profiles.revision(), context.getChapterOffset() + 1);
    }

    static List<String> coreNames(String protagonist, String facts, int limit) {
        List<String> names = new ArrayList<>();
        if (protagonist != null) {
            var explicit = Pattern.compile("(?:姓名|名字|主角|主人公)[：:是为\\s]*([\\p{IsHan}]{2,4})(?=[，。；、（(\\s]|$)").matcher(protagonist);
            while (explicit.find() && names.size() < limit) if (!names.contains(explicit.group(1))) names.add(explicit.group(1));
            String direct = protagonist.strip();
            if (direct.matches("[\\p{IsHan}]{2,4}") && !List.of("主角", "主人公", "外门弟子", "普通弟子").contains(direct) && !names.contains(direct)) names.add(direct);
        }
        if (facts != null) {
            var named = Pattern.compile("(?:^|[\\n；;])\\s*([\\p{IsHan}]{2,4})[（(：:]").matcher(facts);
            while (named.find() && names.size() < limit) if (!names.contains(named.group(1))) names.add(named.group(1));
        }
        return List.copyOf(names.subList(0, Math.min(limit, names.size())));
    }
}
