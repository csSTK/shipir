package dev.bethge.shipir.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module dependency rules of the architecture tree (CLAUDE.md). Parameterised by root package so
 * {@link ArchitectureRulesFailTest} can prove each rule fails on a deliberate violation.
 */
final class ArchitectureRules {

    /**
     * Frozen violation: these controllers read their repository directly (simple read-only
     * lookups, no service in between). New controllers must go through a service.
     */
    static final Set<String> CONTROLLERS_WITH_REPOSITORY_ACCESS = Set.of(
            "AuthController", "NotificationController", "ChatRunController", "ChatSessionController");

    private ArchitectureRules() {
    }

    static ArchRule dockerKnowsNoFeature(String root) {
        return noClasses().that().resideInAPackage(root + ".docker..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        root + ".host..", root + ".compose..", root + ".config_editor..",
                        root + ".streaming..", root + ".git..", root + ".notification..",
                        root + ".ai..", root + ".retention..", root + ".auth..",
                        root + ".permission..", root + ".audit..", root + ".registry..")
                .as("docker/ must not depend on any feature module");
    }

    static ArchRule dockerJavaOnlyInDocker(String root) {
        return noClasses().that().resideOutsideOfPackage(root + ".docker..")
                .should().dependOnClassesThat().resideInAPackage("com.github.dockerjava..")
                .as("docker-java types must not be used outside docker/");
    }

    static ArchRule noFeatureImportsAi(String root) {
        return noClasses().that().resideOutsideOfPackage(root + ".ai..")
                .should().dependOnClassesThat().resideInAPackage(root + ".ai..")
                .as("no feature may import ai/");
    }

    static ArchRule auditNotOnAi(String root) {
        return noClasses().that().resideInAPackage(root + ".audit..")
                .should().dependOnClassesThat().resideInAPackage(root + ".ai..")
                .as("audit/ must not depend on ai/");
    }

    static ArchRule hostNotOnStreaming(String root) {
        return noClasses().that().resideInAPackage(root + ".host..")
                .should().dependOnClassesThat().resideInAPackage(root + ".streaming..")
                .as("host/ must not depend on streaming/");
    }

    static ArchRule gitNotOnCompose(String root) {
        return noClasses().that().resideInAPackage(root + ".git..")
                .should().dependOnClassesThat().resideInAPackage(root + ".compose..")
                .as("git/ must not depend on compose/");
    }

    static ArchRule controllersNotOnRepositories(String root) {
        DescribedPredicate<JavaClass> repository = describe("a repository",
                c -> c.getSimpleName().endsWith("Repository") && c.isInterface()
                        && c.getPackageName().startsWith(root));
        return noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .and(describe("not a frozen controller",
                        (JavaClass c) -> !CONTROLLERS_WITH_REPOSITORY_ACCESS.contains(c.getSimpleName())))
                .should().dependOnClassesThat(repository)
                .as("controllers must not access repositories directly");
    }
}
