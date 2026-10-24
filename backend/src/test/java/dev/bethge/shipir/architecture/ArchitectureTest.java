package dev.bethge.shipir.architecture;

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
    static final ArchRule dockerJavaOnlyInDocker = ArchitectureRules.dockerJavaOnlyInDocker(ROOT);

    @ArchTest
    static final ArchRule noFeatureImportsAi = ArchitectureRules.noFeatureImportsAi(ROOT);

    @ArchTest
    static final ArchRule auditNotOnAi = ArchitectureRules.auditNotOnAi(ROOT);

    @ArchTest
    static final ArchRule gitNotOnCompose = ArchitectureRules.gitNotOnCompose(ROOT);

    @ArchTest
    static final ArchRule controllersNotOnRepositories = ArchitectureRules.controllersNotOnRepositories(ROOT);
}
