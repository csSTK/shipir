package dev.bethge.shipir.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.bethge.shipir", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "dev.bethge.shipir";

    @ArchTest
    static final ArchRule dockerKnowsNoFeature = ArchitectureRules.dockerKnowsNoFeature(ROOT);

    @ArchTest
    static final ArchRule dockerNotOnComposeOrStreaming = ArchitectureRules.dockerNotOnComposeOrStreaming(ROOT);

    @ArchTest
    static final ArchRule dockerJavaOnlyInDocker = ArchitectureRules.dockerJavaOnlyInDocker(ROOT);

    @ArchTest
    static final ArchRule noFeatureImportsAi = ArchitectureRules.noFeatureImportsAi(ROOT);

    @ArchTest
    static final ArchRule auditNotOnAi = ArchitectureRules.auditNotOnAi(ROOT);

    @ArchTest
    static final ArchRule hostNotOnStreaming = ArchitectureRules.hostNotOnStreaming(ROOT);

    @ArchTest
    static final ArchRule gitNotOnCompose = ArchitectureRules.gitNotOnCompose(ROOT);

    @ArchTest
    static final ArchRule controllersNotOnRepositories = ArchitectureRules.controllersNotOnRepositories(ROOT);

    @ArchTest
    static final ArchRule noUnpinnedConnectionManager = ArchitectureRules.noUnpinnedConnectionManager(ROOT);

    @ArchTest
    static final ArchRule noProcessBuilderOutsideComposeExecution =
            ArchitectureRules.noProcessBuilderOutsideComposeExecution(ROOT);

    @ArchTest
    static final ArchRule noRuntimeExec = ArchitectureRules.noRuntimeExec(ROOT);

    @ArchTest
    static final ArchRule rawFileIoOnlyInAllowlist = ArchitectureRules.rawFileIoOnlyInAllowlist(ROOT);

    @ArchTest
    static void rawFileIoAllowlistHasNoStaleEntries(JavaClasses classes) {
        ArchitectureRules.RAW_FILE_IO_ALLOWLIST.keySet().forEach(name -> {
            if (!classes.contain(ROOT + "." + name)) {
                throw new AssertionError("Allowlisted class no longer exists, remove it: " + name);
            }
        });
    }
}
