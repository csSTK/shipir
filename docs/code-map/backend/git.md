# Code map: backend git

- backend/src/main/java/dev/bethge/shipir/git/{GitCredential,GitCredentialRepository,GitCredentialService,GitCredentialController,GitException}.java, backend/src/main/java/dev/bethge/shipir/git/dto/*: ADMIN /api/git/credentials CRUD and test; PAT encrypted, bound to one host, audited
- backend/src/main/java/dev/bethge/shipir/git/{GitRemoteChecker,GitProperties}.java: JGit ls-remote and URL validation, timeouts and size limits; every JGit connection goes through the egress guard
- backend/src/main/java/dev/bethge/shipir/git/GitHostBinding.java, backend/src/main/java/dev/bethge/shipir/git/HostBoundCredentialsProvider.java: credential host binding (exact host match) enforced wherever a token is decrypted
- backend/src/main/java/dev/bethge/shipir/git/{EgressGuardedConnectionFactory,PinnedHttpConnection}.java: JGit connection factory that pins every connection to an address validated by EgressPolicy, no redirects, TLS never relaxed
- backend/src/main/java/dev/bethge/shipir/git/{GitRepositoryService,GitCheckout,GitOperationException,CloneGuard}.java: API for compose/: shallow fetch and reset, ref validation, compose file read via SecureFiles, clone timeout and size cap
- backend/src/main/java/dev/bethge/shipir/git/{GitPollingScheduler,AutoUpdateDeployments,AutoUpdateFailedException}.java: scheduled ls-remote poll over the AutoUpdateDeployments port (implemented by compose/), redeploy only on a new commit
- backend/src/test/java/dev/bethge/shipir/git/{GitTestBase,GitCredentialApiTest,GitCredentialConnectionTest,GitCredentialWithoutKeyTest,GitCredentialHostBindingTest}.java: credential API against a JDK HttpServer smart-HTTP stub, hint only, host binding
- backend/src/test/java/dev/bethge/shipir/git/{GitHostBindingTest,HostBoundCredentialsProviderTest,GitRemoteCheckerTest,GitEgressTest,PinnedHttpConnectionTest,GitSyncLimitsTest,GitComposeFileReadTest,GitPollingSchedulerTest}.java: binding rules, egress and rebinding, clone limits, compose file read, scheduler
- backend/src/main/resources/db/migration/V{12,31}__*.sql: Flyway migrations for git credentials and their host binding
