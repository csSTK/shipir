# Code map: frontend auth

- frontend/src/features/auth/{api.ts,index.ts}: auth hooks (current user, login, logout, password change/reset, OIDC config, step-up status, two-factor setup/confirm/disable) and exports
- frontend/src/features/auth/{LoginPage.tsx,LoginPage.css,AuthBrand.tsx}: login form with forgot-password view, 2FA code step and SSO button; brand mark and shared auth card styles
- frontend/src/features/auth/{LoginOidc.test.tsx,LoginOidcError.test.tsx,LoginClearsCache.test.tsx,LoginFieldErrors.test.tsx,LoginThrottle.test.tsx}: login page tests (SSO, errors, cache reset, field errors, 429)
- frontend/src/features/auth/{PasswordResetPage.tsx,PasswordResetPage.css,PasswordResetPage.test.tsx}: password reset flow: username, 6-digit code, new password with live rules
- frontend/src/features/auth/{SetupPage.tsx,SetupPage.css,SetupPage.test.tsx}: first-start setup wizard (password, optional two-factor, first environment)
- frontend/src/features/auth/{passwordPolicy.ts,passwordPolicy.test.ts}: client mirror of the backend password rules
- frontend/src/features/auth/{fieldError.ts,throttleErrors.ts,throttleErrors.test.ts,twoFactorErrors.ts}: `aria-describedby`/`aria-invalid` helper for inline errors; 429 and 2FA error texts
- frontend/src/features/auth/{stepUp.ts,StepUpDialog.tsx,StepUpDialog.test.tsx}: step-up confirmation dialog: current password plus code for local users, SSO re-authentication for SSO users
- frontend/src/features/auth/{QrCode.tsx,TwoFactorEnrollment.tsx,TwoFactorEnrollment.css,TwoFactorEnrollment.test.tsx,TwoFactorLogin.test.tsx}: local SVG QR code; 2FA enrolment flow with recovery codes; tests
