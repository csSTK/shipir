# Code map: frontend account

- frontend/src/features/account/{AccountPage.tsx,AccountPage.css,index.ts}: `/account`: username and role, SSO link, password change, notification preference switches
- frontend/src/features/account/{AccountPage.test.tsx,TwoFactorSection.test.tsx}: account page and 2FA section tests
- frontend/src/features/account/tokens/{api.ts,TokensSection.tsx,tokens.css}: API tokens card: list, create with one-time reveal, revoke behind a confirm dialog
- frontend/src/features/account/tokens/TokensSection.test.tsx: API tokens card tests
