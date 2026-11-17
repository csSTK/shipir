package dev.bethge.shipir.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import dev.bethge.shipir.architecture.fixture.common.CommonThing;
import dev.bethge.shipir.architecture.fixture.compose.execution.GoodProcessBuilderUser;
import dev.bethge.shipir.architecture.fixture.common.util.UnrestrictedFileUser;
import dev.bethge.shipir.architecture.fixture.docker.GoodDockerCommonUser;
import dev.bethge.shipir.architecture.fixture.host.GoodAllowlistedFileUser;
import dev.bethge.shipir.architecture.fixture.host.GoodFilesMetadataUser;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

    private static final Map<String, String> FIXTURE_ALLOWLIST =
            Map.of("host.GoodAllowlistedFileUser", "fixture: stands for a reviewed class");

    @Test
    void filesReadOutsideAllowlistFails() {
        fails(ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT, FIXTURE_ALLOWLIST), "BadFilesReadUser");
    }

    @Test
    void filesMethodReferenceOutsideAllowlistFails() {
        fails(ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT, FIXTURE_ALLOWLIST), "BadFilesReferenceUser");
    }

    @Test
    void fileInputStreamConstructorOutsideAllowlistFails() {
        fails(ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT, FIXTURE_ALLOWLIST), "BadFileInputStreamUser");
    }

    @Test
    void fileInputStreamConstructorReferenceOutsideAllowlistFails() {
        fails(ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT, FIXTURE_ALLOWLIST), "BadFileInputStreamReferenceUser");
    }

    @Test
    void fileChannelOpenOutsideAllowlistFails() {
        fails(ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT, FIXTURE_ALLOWLIST), "BadFileChannelUser");
    }

    @Test
    void allowlistedClassIsNotReported() {
        assertThatThrownBy(() -> ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT, FIXTURE_ALLOWLIST).check(FIXTURE))
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("GoodAllowlistedFileUser");
    }

    @Test
    void allowlistedClassPasses() {
        // The allowlisted class is filtered out of the rule's subject, so it matches nothing; allowEmptyShould for that.
        JavaClasses allowed = new ClassFileImporter().importClasses(GoodAllowlistedFileUser.class);
        ArchRule rule = ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT, FIXTURE_ALLOWLIST).allowEmptyShould(true);
        assertThatCode(() -> rule.check(allowed)).doesNotThrowAnyException();
    }

    @Test
    void filesMetadataQueriesPass() {
        JavaClasses metadata = new ClassFileImporter().importClasses(GoodFilesMetadataUser.class);
        ArchRule rule = ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT, Map.of());
        assertThatCode(() -> rule.check(metadata)).doesNotThrowAnyException();
    }

    @Test
    void rawFileIoInCommonUtilIsUnrestricted() {
        // The class lives in common.util, so the rule's package filter matches nothing; allowEmptyShould only for that.
        JavaClasses util = new ClassFileImporter().importClasses(UnrestrictedFileUser.class);
        ArchRule rule = ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT, Map.of()).allowEmptyShould(true);
        assertThatCode(() -> rule.check(util)).doesNotThrowAnyException();
    }

    @Test
    void everyAllowlistEntryHasAReason() {
        ArchitectureRules.RAW_FILE_IO_ALLOWLIST.forEach((name, reason) ->
                org.assertj.core.api.Assertions.assertThat(reason).as(name).isNotBlank());
    }
}
