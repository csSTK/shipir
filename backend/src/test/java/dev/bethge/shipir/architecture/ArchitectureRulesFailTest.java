package dev.bethge.shipir.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

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
}
