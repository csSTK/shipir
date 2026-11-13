# Code map: backend registry

- backend/src/main/java/dev/bethge/shipir/registry/{RegistryCredential,RegistryCredentialRepository,RegistryCredentialService,RegistryCredentialController,RegistryException,RegistryHost,RegistryAuth,RegistryLoginChecker,RegistryProperties}.java, backend/src/main/java/dev/bethge/shipir/registry/dto/*: ADMIN /api/registries CRUD and login test (Registry v2, https only, egress-guarded); secret encrypted, hint only
- backend/src/main/java/dev/bethge/shipir/registry/{RemoteDigestChecker,BearerChallenge}.java: remote manifest digest lookup for image update detection (anonymous or stored login, https only, no redirects, egress-guarded)
- backend/src/main/java/dev/bethge/shipir/registry/RegistryAuthSourceAdapter.java: adapter giving compose/ the decrypted registry logins (missing key gives none plus a warning)
- backend/src/test/java/dev/bethge/shipir/registry/{RegistryTestBase,RegistryCredentialApiTest,RegistryCredentialConnectionTest,RegistryCredentialWithoutKeyTest,RegistryLoginCheckerTest,RegistryHostTest}.java: roles, CSRF, codes, hint only, audit, test endpoint against a stub
- backend/src/test/java/dev/bethge/shipir/registry/{RemoteDigestCheckerTest,RegistryAuthSourceAdapterTest,RegistryAuthSourceAdapterWithoutKeyTest}.java: digest lookup and auth adapter
- backend/src/main/resources/db/migration/V19__create_registry_credentials.sql: Flyway migration for registry credentials
