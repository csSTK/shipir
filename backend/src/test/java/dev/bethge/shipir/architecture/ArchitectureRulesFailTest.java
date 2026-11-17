package dev.bethge.shipir.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import dev.bethge.shipir.architecture.fixture.common.CommonThing;
import dev.bethge.shipir.architecture.fixture.compose.execution.GoodProcessBuilderUser;
import dev.bethge.shipir.architecture.fixture.docker.GoodDockerCommonUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves each rule fails on a deliberate violation in {@code architecture.fixture}. */
class ArchitectureRulesFailTest {

    private static final String ROOT = "dev.bethge.shipir.architecture.fixture";
    private static final JavaClasses FIXTURE = new ClassFileImporter().importPackages(ROOT);

    private static void fails(ArchRule rule, String offender) {
        assertThatThrownBy(() -> rule.check(FIXTURE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(offender);
    }

    @Test
    void dockerDependingOnFeatureFails() {
        fails(ArchitectureRules.dockerKnowsNoFeature(ROOT), "BadDockerClass");
    }

    @Test
    void dockerDependingOnProxyFails() {
        fails(ArchitectureRules.dockerKnowsNoFeature(ROOT), "BadDockerProxyUser");
    }

    @Test
    void dockerDependingOnBackupFails() {
        fails(ArchitectureRules.dockerKnowsNoFeature(ROOT), "BadDockerBackupUser");
    }

    @Test
    void dockerMayDependOnCommon() {
        // Only the allowed pair is imported, so no other fixture violation can mask the result.
        JavaClasses allowed = new ClassFileImporter().importClasses(GoodDockerCommonUser.class, CommonThing.class);
        assertThatCode(() -> ArchitectureRules.dockerKnowsNoFeature(ROOT).check(allowed))
                .doesNotThrowAnyException();
    }

    @Test
    void dockerDependingOnComposeFails() {
        fails(ArchitectureRules.dockerNotOnComposeOrStreaming(ROOT), "BadDockerComposeUser");
    }

    @Test
    void dockerDependingOnStreamingFails() {
        fails(ArchitectureRules.dockerNotOnComposeOrStreaming(ROOT), "BadDockerStreamingUser");
    }

    @Test
    void dockerJavaOutsideDockerFails() {
        fails(ArchitectureRules.dockerJavaOnlyInDocker(ROOT), "BadDockerJavaUser");
    }

    @Test
    void featureImportingAiFails() {
        fails(ArchitectureRules.noFeatureImportsAi(ROOT), "BadAiUser");
    }

    @Test
    void auditOnAiFails() {
        fails(ArchitectureRules.auditNotOnAi(ROOT), "BadAudit");
    }

    @Test
    void hostOnStreamingFails() {
        fails(ArchitectureRules.hostNotOnStreaming(ROOT), "BadStreamingUser");
    }

    @Test
    void gitOnComposeFails() {
        fails(ArchitectureRules.gitNotOnCompose(ROOT), "BadGit");
    }

    @Test
    void controllerOnRepositoryFails() {
        fails(ArchitectureRules.controllersNotOnRepositories(ROOT), "ThingController");
    }

    @Test
    void unpinnedConnectionManagerConstructorFails() {
        fails(ArchitectureRules.noUnpinnedConnectionManager(ROOT), "BadUnpinnedDockerUser");
    }

    @Test
    void unpinnedConnectionManagerConstructorReferenceFails() {
        fails(ArchitectureRules.noUnpinnedConnectionManager(ROOT), "BadUnpinnedConstructorReferenceUser");
    }

    @Test
    void pinnedConnectionManagerConstructorIsAllowed() {
        assertThatThrownBy(() -> ArchitectureRules.noUnpinnedConnectionManager(ROOT).check(FIXTURE))
                .hasMessageNotContaining("GoodPinnedDockerUser");
    }

    @Test
    void processBuilderOutsideComposeExecutionFails() {
        fails(ArchitectureRules.noProcessBuilderOutsideComposeExecution(ROOT), "BadProcessBuilderUser");
    }

    @Test
    void processBuilderConstructorReferenceOutsideComposeExecutionFails() {
        fails(ArchitectureRules.noProcessBuilderOutsideComposeExecution(ROOT), "BadProcessBuilderReferenceUser");
    }

    @Test
    void processBuilderInsideComposeExecutionIsAllowed() {
        // Only the allowed class is imported. It lives inside compose.execution, so the rule's
        // "outside compose.execution" filter matches nothing; allowEmptyShould is needed here only.
        JavaClasses allowed = new ClassFileImporter().importClasses(GoodProcessBuilderUser.class);
        ArchRule rule = ArchitectureRules.noProcessBuilderOutsideComposeExecution(ROOT).allowEmptyShould(true);
        assertThatCode(() -> rule.check(allowed)).doesNotThrowAnyException();
    }

    @Test
    void runtimeExecFails() {
        fails(ArchitectureRules.noRuntimeExec(ROOT), "BadRuntimeExecUser");
    }

    @Test
    void runtimeExecInsideComposeExecutionFails() {
        fails(ArchitectureRules.noRuntimeExec(ROOT), "BadRuntimeExecInExecutionUser");
    }

    @Test
    void runtimeExecMethodReferenceFails() {
        fails(ArchitectureRules.noRuntimeExec(ROOT), "BadRuntimeExecReferenceUser");
    }
}
