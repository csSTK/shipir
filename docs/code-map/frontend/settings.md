# Code map: frontend settings

- frontend/src/features/settings/{api.ts,index.ts}: hooks for all settings pages (users, teams, webhooks, retention, AI, audit log, shared chats, backup, git, registries, OIDC, security)
- frontend/src/features/settings/{SettingsPage.tsx,SettingsPage.css,Pager.tsx}: shared settings layout (header and width), server-paged `Pager` and shared form classes
- frontend/src/features/settings/{UsersSettingsPage.tsx,UsersSettingsPage.css,UsersSettingsPage.test.tsx,TwoFactorAdmin.test.tsx}: ADMIN users `/settings/users`: roles, disable, reset password, SSO link, 2FA reset
- frontend/src/features/settings/{TeamsSettingsPage.tsx,TeamsSettingsPage.test.tsx}: ADMIN teams `/settings/teams`: members checklist, Access dialog, rename and delete
- frontend/src/features/settings/{UserAccessDialog.tsx,UserAccessDialog.css}: Access dialog for a user or team: View/Operate level per host and managed deployment
- frontend/src/features/settings/{WebhooksSettingsPage.tsx,WebhooksSettingsPage.test.tsx}: ADMIN webhooks `/settings/notifications`: create or edit, event checkboxes, test, delete
- frontend/src/features/settings/{RetentionSettingsPage.tsx,RetentionSettingsPage.test.tsx}: ADMIN retention form `/settings/retention` (days per data type, client range check)
- frontend/src/features/settings/{AiSettingsPage.tsx,AiSettingsPage.css,AiSettingsPage.test.tsx}: ADMIN AI provider `/settings/ai`: provider switch, base URL, model, write-only API key, test
- frontend/src/features/settings/{SharedChatSessionsPage.tsx,SharedChatSessionsPage.css,SharedChatSessionsPage.test.tsx}: ADMIN read-only shared chats `/settings/ai/shared`
- frontend/src/features/settings/{AuditLogPage.tsx,AuditLogPage.test.tsx}: ADMIN audit log `/settings/audit-log` with action, user and date filters kept in the URL
- frontend/src/features/settings/{BackupSettingsPage.tsx,BackupSettingsPage.test.tsx}: ADMIN encrypted backup download `/settings/backup` (passphrase, step-up confirmation)
- frontend/src/features/settings/{OidcSettingsPage.tsx,OidcSettingsPage.test.tsx}: ADMIN single sign-on `/settings/oidc`: issuer, client, write-only secret, role mapping, test
- frontend/src/features/settings/{GitCredentialsSettingsPage.tsx,GitCredentialsSettingsPage.test.tsx}: ADMIN git credentials `/settings/git` with write-only token and test connection
- frontend/src/features/settings/{RegistriesSettingsPage.tsx,RegistriesSettingsPage.test.tsx}: ADMIN private registry logins `/settings/registries` with write-only secret and test login
