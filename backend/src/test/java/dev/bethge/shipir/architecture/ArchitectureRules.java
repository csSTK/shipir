package dev.bethge.shipir.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaConstructorReference;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Module dependency rules of the architecture tree (CLAUDE.md). Parameterised by root package so
 * {@link ArchitectureRulesFailTest} can prove each rule fails on a deliberate violation.
 */
final class ArchitectureRules {

    private static final String CONSTRUCTOR_NAME = "<init>";

    private ArchitectureRules() {
    }

    /**
     * Allowlist: classes in docker/ may depend on shipir classes only in docker/ and common/. Anything else in the
     * shipir tree (any current or future module) is a violation. Third-party packages are not affected.
     */
    static ArchRule dockerKnowsNoFeature(String root) {
        DescribedPredicate<JavaClass> shipirOutsideAllowlist = JavaClass.Predicates.resideInAPackage(root + "..")
                .and(DescribedPredicate.not(
                        JavaClass.Predicates.resideInAnyPackage(root + ".docker..", root + ".common..")));
        return noClasses().that().resideInAPackage(root + ".docker..")
                .should().dependOnClassesThat(shipirOutsideAllowlist)
                .as("docker/ may depend on shipir classes only in docker/ and common/");
    }

    /** docker/ is the lower layer: compose/ and streaming/ use it, never the other way round (CLAUDE.md). */
    static ArchRule dockerNotOnComposeOrStreaming(String root) {
        return noClasses().that().resideInAPackage(root + ".docker..")
                .should().dependOnClassesThat().resideInAnyPackage(root + ".compose..", root + ".streaming..")
                .as("docker/ must not depend on compose/ or streaming/");
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
                .should().dependOnClassesThat(repository)
                .as("controllers must not access repositories directly");
    }

    /**
     * The one-argument DockerHostConnectionManager constructor resolves host names through system DNS, bypassing the
     * egress policy's DNS pinning. It exists for tests against local daemons; production code must use the pinned one.
     * Covers direct calls and constructor references ({@code DockerHostConnectionManager::new}), which bypass a
     * call-only check.
     */
    static ArchRule noUnpinnedConnectionManager(String root) {
        String owner = root + ".docker.DockerHostConnectionManager";
        DescribedPredicate<JavaConstructorCall> unpinnedCall = describe(
                "is the unpinned DockerHostConnectionManager constructor",
                call -> call.getTargetOwner().getName().equals(owner)
                        && call.getTarget().getRawParameterTypes().size() == 1);
        DescribedPredicate<JavaAccess<?>> unpinnedReference = describe(
                "is a reference to the unpinned DockerHostConnectionManager constructor",
                (JavaAccess<?> access) -> access instanceof JavaConstructorReference reference
                        && reference.getTargetOwner().getName().equals(owner)
                        && reference.getTarget().getRawParameterTypes().size() == 1);
        return noClasses()
                .should().callConstructorWhere(unpinnedCall)
                .orShould().accessTargetWhere(unpinnedReference)
                .as("main code must not call or reference the unpinned DockerHostConnectionManager(DockerProperties) "
                        + "constructor");
    }

    /**
     * Subprocesses are spawned centrally in compose.execution (CLAUDE.md, Security). Covers constructor calls and
     * constructor references ({@code ProcessBuilder::new}), which bypass a call-only check. Docker exec through the
     * Docker API is not a subprocess and unaffected.
     */
    static ArchRule noProcessBuilderOutsideComposeExecution(String root) {
        DescribedPredicate<JavaAccess<?>> processBuilderConstructor = describe(
                "is a ProcessBuilder constructor call or reference",
                (JavaAccess<?> access) -> access.getTargetOwner().isEquivalentTo(ProcessBuilder.class)
                        && access.getTarget().getName().equals(CONSTRUCTOR_NAME));
        return noClasses().that().resideOutsideOfPackage(root + ".compose.execution..")
                .should().accessTargetWhere(processBuilderConstructor)
                .as("ProcessBuilder must only be constructed in compose.execution");
    }

    /** Every overload of {@code Runtime.exec}, called or referenced ({@code Runtime::exec}), in any package. */
    static ArchRule noRuntimeExec(String root) {
        DescribedPredicate<JavaAccess<?>> runtimeExec = describe(
                "is a Runtime.exec call or reference",
                (JavaAccess<?> access) -> access.getTargetOwner().isEquivalentTo(Runtime.class)
                        && access.getTarget().getName().equals("exec"));
        return noClasses().that().resideInAPackage(root + "..")
                .should().accessTargetWhere(runtimeExec)
                .as("Runtime.exec must not be used");
    }

    /**
     * Pure metadata queries on {@code Files}: they neither read nor change file content, so they are not restricted.
     * Every other {@code Files} method (read, write, open, create, delete, copy, move, walk, list, attributes,
     * channels, ...) counts as raw file I/O, so a method added to the JDK is restricted by default.
     */
    private static final Set<String> FILES_METADATA_QUERIES = Set.of(
            "exists", "notExists", "isDirectory", "isRegularFile", "isSymbolicLink", "isHidden", "isReadable",
            "isWritable", "isExecutable", "isSameFile");

    private static final List<Class<?>> FILE_STREAM_CLASSES = List.of(
            FileInputStream.class, FileOutputStream.class, RandomAccessFile.class, FileReader.class, FileWriter.class);

    /**
     * Classes (relative to the root package) that may do raw file I/O, each with the reason it is acceptable. A new
     * entry needs a review: the path must be derived from validated input (PathValidationUtil / SecureFiles), a
     * temp directory or configuration, never a raw user-supplied path.
     */
    static final Map<String, String> RAW_FILE_IO_ALLOWLIST = Map.ofEntries(
            Map.entry("backup.BackupService", "export spools DB tables to a private temp file and tars the stacks dir "
                    + "walking without following links; no user-supplied path"),
            Map.entry("compose.AdoptionService", "validates label paths with PathValidationUtil against the admin-configured "
                    + "adopt-allowed-dirs, then reads through SecureFiles (no-follow); only Path arithmetic remains"),
            Map.entry("compose.DeploymentRunService", "lists the names in a deployment dir from ComposeFileRepository "
                    + "to see whether only Shipir-written files exist; no content is read"),
            Map.entry("compose.execution.CliPluginDirs", "reads the docker CLI config.json of the Shipir process "
                    + "(DOCKER_CONFIG or home), never user input"),
            Map.entry("compose.execution.ComposeExecutionService", "writes TLS and registry-auth material into 0700 "
                    + "temp dirs and deletes them again; checks paths with requireInside"),
            Map.entry("compose.file.ComposeFileRepository", "the deployment file store: paths via PathValidationUtil, "
                    + "reads and writes through SecureFiles"),
            Map.entry("compose.file.ComposeGraphHasher", "fingerprints a deployment dir without following links; "
                    + "rejects links leaving the dir"),
            Map.entry("compose.validation.ComposeFilePolicy", "reads compose and include files after resolving them "
                    + "to real paths inside the deployment dir"),
            Map.entry("config_editor.ConfigFileService", "user-facing editor: PathValidationUtil, symlink rejection "
                    + "and SecureFiles for every read and write"),
            Map.entry("git.CloneGuard", "measures and deletes a clone dir the service created itself, never following "
                    + "links"),
            Map.entry("git.GitRepositoryService", "creates and checks the checkout dir derived from the validated "
                    + "deployment dir; compose file reads go through SecureFiles"),
            Map.entry("host.BuildContextArchiver", "tars a Shipir-owned git checkout, skipping links and .git"),
            Map.entry("host.BuildTempSweeper", "deletes stale Shipir-prefixed spool dirs in the configured build temp "
                    + "dir without following links"),
            Map.entry("host.DockerfileReferenceScanner", "reads an upload that was spooled to a Shipir temp dir"),
            Map.entry("host.ImageBuildService", "spools uploads into its own temp dir (random name) and removes it "
                    + "afterwards; no user-supplied path"),
            Map.entry("host.VolumeBackupService", "copies a stored backup into an owner-only temp file for the restore"),
            Map.entry("host.VolumeBackupStore", "volume backup store: PathValidationUtil plus SecureFiles for list, "
                    + "write, read, delete and prune"));

    /** Raw file I/O outside {@code common.util} only in {@link #RAW_FILE_IO_ALLOWLIST}. */
    static ArchRule rawFileIoOnlyInAllowlist(String root) {
        return rawFileIoOnlyInAllowlist(root, RAW_FILE_IO_ALLOWLIST);
    }

    /**
     * Raw file I/O ({@code java.nio.file.Files} except metadata queries, {@code FileChannel.open}, the File stream
     * constructors) is allowed in {@code common.util} (PathValidationUtil, SecureFiles are the guarded entry points)
     * and in the allowlisted classes, each named relative to {@code root}. Covers calls and method/constructor
     * references ({@code Files::readString}, {@code FileInputStream::new}), which bypass a call-only check.
     */
    static ArchRule rawFileIoOnlyInAllowlist(String root, Map<String, String> allowlist) {
        Set<String> allowedClasses = new java.util.HashSet<>();
        allowlist.keySet().forEach(name -> allowedClasses.add(root + "." + name));
        DescribedPredicate<JavaAccess<?>> rawFileIo = describe(
                "is raw file I/O (Files, FileChannel.open, File streams) call or reference",
                (JavaAccess<?> access) -> {
                    JavaClass owner = access.getTargetOwner();
                    String name = access.getTarget().getName();
                    if (owner.isEquivalentTo(Files.class)) {
                        return !FILES_METADATA_QUERIES.contains(name);
                    }
                    if (owner.isEquivalentTo(FileChannel.class)) {
                        return name.equals("open");
                    }
                    return name.equals(CONSTRUCTOR_NAME)
                            && FILE_STREAM_CLASSES.stream().anyMatch(owner::isEquivalentTo);
                });
        DescribedPredicate<JavaClass> allowlisted = describe("allowlisted",
                c -> allowedClasses.contains(c.getName()) || allowedClasses.contains(outermost(c.getName())));
        return noClasses().that().resideOutsideOfPackage(root + ".common.util..")
                .and(DescribedPredicate.not(allowlisted))
                .should().accessTargetWhere(rawFileIo)
                .as("raw file I/O must only be done in common.util or an allowlisted class");
    }

    /**
     * The top-level modules (first package below {@code root}) must not depend on each other in a cycle. Classes in
     * {@code common.config} (SecurityConfig, WebSocketConfig, ...) are the composition root that wires modules together
     * and are left out of every slice. Not frozen here so the fail test can check it directly; see
     * {@link #moduleCyclesFrozen}.
     */
    static ArchRule modulesFreeOfCycles(String root) {
        String compositionRoot = root + ".common.config";
        SliceAssignment modules = new SliceAssignment() {
            @Override
            public String getDescription() {
                return "top-level module below " + root + " (without common.config)";
            }

            @Override
            public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                String name = javaClass.getPackageName();
                if (name.equals(compositionRoot) || name.startsWith(compositionRoot + ".")
                        || !name.startsWith(root + ".")) {
                    return SliceIdentifier.ignore();
                }
                String rest = name.substring(root.length() + 1);
                int dot = rest.indexOf('.');
                return SliceIdentifier.of(dot < 0 ? rest : rest.substring(0, dot));
            }
        };
        return slices().assignedFrom(modules).should().beFreeOfCycles()
                .as("top-level modules must be free of cycles (common.config ignored)");
    }

    /**
     * {@link #modulesFreeOfCycles} with today's cycles frozen in the committed store (see archunit.properties): only
     * a cycle that is not in the baseline fails. The store is read-only in CI and not refrozen.
     */
    static ArchRule moduleCyclesFrozen(String root) {
        return FreezingArchRule.freeze(modulesFreeOfCycles(root));
    }

    private static String outermost(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }
}
