package cn.novel.yonren.domain.novel.service.armory.character;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.valobj.character.*;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        boolean newApply = context.getChapterOffset() == 0 && active == null && story.getStoryAuthoring() == null;
        if (story.getCharacterDecisionProfiles() != null) {
            CharacterDecisionProfileService.validatePersisted(story.getCharacterDecisionProfiles());
            if (newApply) requireAppliedProfile(story.getCharacterDecisionProfiles(),
                    protagonistName(story.getProtagonist(), story.getOutline()));
            log.info("人物设计沿冻结档案{}", story.getCharacterDecisionProfiles().revision());
            return;
        }
        // 历史冻结的无卡 APPLY 也是原有执行依据，不因再次经过节点而补建或改版。
        if (!newApply) return;
        String protagonist = protagonistName(story.getProtagonist(), story.getOutline());
        if (protagonist == null) throw new IllegalArgumentException("新建 APPLY 需要唯一明确的主角姓名，请在主人公中写“姓名：…”");
        List<String> names = coreNames(story.getProtagonist(), story.getCharacterProfiles(), story.getOutline(),
                story.getCharacterDepthSettings().maxNewProfilesPerPass());
        var designed = designer.design(command, context, names);
        CharacterDecisionDesignService.ensureRunning(context);
        var drafts = CharacterDecisionProfileService.draft(designed.profiles());
        if (drafts.isEmpty() || drafts.stream().anyMatch(profile -> !names.contains(profile.displayName())
                || CharacterDecisionProfileService.fieldEntries(profile).isEmpty())
                || drafts.stream().noneMatch(profile -> protagonist.equals(profile.displayName()))) {
            throw new IllegalStateException("新建 APPLY 人物建档失败：未生成主角的有效决策卡（" + designed.diagnostic() + "），已停止蓝图与正文生成");
        }
        var profiles = CharacterDecisionProfileService.adoptAuto(null, drafts, context.getChapterOffset() + 1);
        requireAppliedProfile(profiles, protagonist);
        story.setCharacterDecisionProfiles(profiles);
        if (context.getStoryDir() == null) context.setStoryDir(repository.createStoryDirectory());
        repository.writeStoryBible(context.getStoryDir(), command);
        repository.writeActiveCharacterExecution(context.getStoryDir(), CharacterExecutionContext.from(story));
        if (context.getJob() != null) context.getJob().setStoryDirName(context.getStoryDir().getFileName().toString());
        log.info("人物设计{}：模式{}，策略{}，档案{}，生效章{}", designed.diagnostic(), mode,
                story.getCharacterDepthSettings().policyVersion(), profiles == null ? "NO_PROFILES" : profiles.revision(), context.getChapterOffset() + 1);
    }

    private static final Set<String> NON_NAMES = Set.of("主角", "主人公", "男主", "女主", "男主角", "女主角",
            "姓名", "名字", "身份", "能力", "性格", "年龄", "背景", "职业", "设定", "人物", "事实", "宗门", "修为",
            "外门弟子", "普通弟子", "普通人", "年轻人", "一个人", "某人", "未知", "未定", "待定", "暂无", "无", "没有",
            "name", "identity", "role", "age", "personality", "ability", "background", "occupation", "protagonist", "null");
    private static final Pattern NAME_LABEL = Pattern.compile("^(?:姓名|名字|主人公|主角|男主角|女主角|男主|女主|(?i:name|protagonist|main\\s+character|hero|heroine))\\s*[：:]\\s*(.*)$");
    private static final List<Pattern> OUTLINE_NAMES = List.of(
            Pattern.compile("(?:^|[\\r\\n。！？!?；;])\\s*(?:主角|主人公|男主角|女主角|男主|女主)\\s*[：:]\\s*([\\p{IsHan}]{2,6}?)(?=\\s*(?:是|为|[，,。；;：:（(\\r\\n]|$))"),
            Pattern.compile("(?:^|[\\r\\n。！？!?；;])\\s*(?:主角|主人公|男主角|女主角|男主|女主)\\s*([\\p{IsHan}]{2,6}?)\\s*(?:是|为)"),
            Pattern.compile("(?:^|[\\r\\n.!?;])\\s*(?i:(?:the\\s+)?(?:protagonist|main\\s+character|hero|heroine))\\s*:\\s*([\\p{L}][\\p{L}\\p{M} .·’'-]{0,79}?)(?=\\s+(?i:is)\\b|\\s*[,.;:(\\r\\n]|\\s*$)"),
            Pattern.compile("(?:^|[\\r\\n.!?;])\\s*(?i:(?:the\\s+)?(?:protagonist|main\\s+character|hero|heroine))\\s+([\\p{L}][\\p{L}\\p{M} .·’'-]{0,79}?)\\s+(?i:is)\\b"));

    static List<String> coreNames(String protagonist, String facts, int limit) {
        return coreNames(protagonist, facts, null, limit);
    }

    static List<String> coreNames(String protagonist, String facts, String outline, int limit) {
        Set<String> names = new LinkedHashSet<>();
        String main = protagonistName(protagonist, outline);
        if (main != null) names.add(main);
        addDeclaredNames(names, facts, false);
        return names.stream().limit(Math.max(0, limit)).toList();
    }

    private static String protagonistName(String protagonist, String outline) {
        Set<String> declared = new LinkedHashSet<>();
        addDeclaredNames(declared, protagonist, true);
        Set<String> explicit = new LinkedHashSet<>();
        if (outline != null) for (Pattern pattern : OUTLINE_NAMES) {
            var matcher = pattern.matcher(outline);
            while (matcher.find()) if (isName(matcher.group(1).strip(), true)) explicit.add(matcher.group(1).strip());
        }
        if (declared.size() > 1 || explicit.size() > 1 || !declared.isEmpty() && !explicit.isEmpty() && !declared.equals(explicit)) {
            throw new IllegalArgumentException("主角姓名存在歧义或与大纲冲突，请明确唯一姓名");
        }
        Set<String> selected = declared.isEmpty() ? explicit : declared;
        return selected.isEmpty() ? null : selected.iterator().next();
    }

    private static void addDeclaredNames(Set<String> names, String text, boolean protagonist) {
        if (text == null || text.isBlank()) return;
        boolean first = true;
        for (String part : text.split("[\\r\\n；;]+")) {
            String declaration = part.strip().replaceFirst("^(?:[-*•]|\\d+[.、)])\\s*", "");
            var label = NAME_LABEL.matcher(declaration);
            boolean labeled = label.matches();
            if (protagonist && !first && !labeled) continue;
            first = false;
            if (labeled) declaration = label.group(1);
            String prefix = declaration.split("[（(：:，,。.!！?？]", 2)[0].strip();
            boolean boundary = labeled || !prefix.equals(declaration);
            for (String name : prefix.split("、")) if (isName(name.strip(), boundary)) names.add(name.strip());
        }
    }

    private static boolean isName(String name, boolean explicit) {
        if (NON_NAMES.contains(name.toLowerCase(java.util.Locale.ROOT))) return false;
        return name.matches(explicit ? "[\\p{IsHan}]{2,6}" : "[\\p{IsHan}]{2,4}")
                || !name.matches(".*[\\p{IsHan}].*") && (explicit
                ? name.matches("[\\p{L}][\\p{L}\\p{M} .·’'-]{0,79}")
                : name.matches("\\p{Lu}[\\p{L}\\p{M}’'-]*(?:[ .·-]\\p{Lu}[\\p{L}\\p{M}’'-]*){0,5}"));
    }

    private static void requireAppliedProfile(CharacterDecisionProfileSet set, String protagonist) {
        if (protagonist == null) throw new IllegalArgumentException("新建 APPLY 需要唯一明确的主角姓名，请在主人公中写“姓名：…”");
        if (set.profiles().isEmpty() || set.profiles().stream().anyMatch(profile -> profile.adoption() == CharacterDecisionProfile.Adoption.DRAFT
                || CharacterDecisionProfileService.fieldEntries(profile).isEmpty())
                || set.profiles().stream().noneMatch(profile -> protagonist.equals(profile.displayName()))) {
            throw new IllegalStateException("新建 APPLY 人物建档失败：需要已采纳且含有效字段的主角决策卡，已停止蓝图与正文生成");
        }
    }
}
