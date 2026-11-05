package cn.novel.yonren.trigger;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 分层边界守护（迁移计划 Phase 5）。
 *
 * 把 完成的解耦成果变成可执行规则，防回潮：
 * 规则一旦被破坏，构建即失败并直接指出违规类与依赖行。
 *
 * 规则口径与 docs/architecture.md 的模块划分一致：
 * trigger 只认 api DTO，业务与领域对象操作归 domain 服务。
 */
class ArchBoundaryTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.novel.yonren");

    /** 规则 A：trigger 层不得访问领域模型（entity/valobj/aggregate） */
    @Test
    void triggerMustNotTouchDomainModel() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("cn.novel.yonren.trigger..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "cn.novel.yonren.domain.novel.model.entity..",
                        "cn.novel.yonren.domain.novel.model.valobj..",
                        "cn.novel.yonren.domain.novel.model.aggregate..")
                .because("trigger 只认 api DTO；领域对象读写归 domain 服务"
                        + "（2026-11-05 分层重构，见 docs/refactor-migration-plan.md Phase 4）");
        rule.check(CLASSES);
    }

    /** 规则 B：领域模型不得反向依赖服务/适配器（纯数据，无 Spring 无 IO） */
    @Test
    void domainModelMustNotDependOnServicesOrAdapters() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("cn.novel.yonren.domain.novel.model..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "cn.novel.yonren.domain.novel.service..",
                        "cn.novel.yonren.domain.novel.adapter..",
                        "cn.novel.yonren.domain.novel.config..")
                .because("model 是纯数据层；服务/端口/配置依赖模型，不可反向"
                        + "（2026-11-05 已消除 BookExport→BookFormat 与 ReferenceIndexEntry→ReferenceGroup）");
        rule.check(CLASSES);
    }

    /** 规则 C：api 模块零工程模块依赖（保持最干净的契约层） */
    @Test
    void apiMustNotDependOnOtherProjectModules() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("cn.novel.yonren.api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "cn.novel.yonren.domain..",
                        "cn.novel.yonren.trigger..",
                        "cn.novel.yonren.infrastructure..")
                .because("api 是跨层契约层，只能依赖 types（2026-11-05 实测 api 已是零工程模块依赖）");
        rule.check(CLASSES);
    }
}
