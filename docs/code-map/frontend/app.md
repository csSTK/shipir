# Code map: frontend app

- frontend/src/main.tsx: entry, mounts `App`
- frontend/src/app/{App.tsx,App.test.tsx}: root: providers, router, auth gate (login, setup, loading), route table, language handling and sign out
- frontend/src/app/{providers.tsx,queryClient.ts,queryClient.test.ts}: React Query setup; a 401 from any non-auth call marks the user signed out
- frontend/src/app/layout/{AppShell.tsx,AppShell.css,AppShell.test.tsx,Sidebar.tsx,Sidebar.css,UserMenu.tsx,UserMenu.css}: app shell: top bar, sidebar or phone drawer, user menu, assistant dock mount
- frontend/src/app/layout/{EnvironmentSwitcher.tsx,EnvironmentSwitcher.css,EnvironmentSwitcher.test.tsx}: environment switcher in the top bar, grouped by first tag
- frontend/src/app/layout/{CommandPalette.tsx,CommandPalette.css,CommandPalette.test.tsx,useSearch.ts}: Cmd/Ctrl+K palette: navigation commands and debounced global search
- frontend/src/app/layout/{ErrorBoundary.tsx,ErrorBoundary.css,ErrorBoundary.test.tsx}: error boundary around the routed page with Try again and Back to start
- frontend/src/app/layout/PlaceholderPages.tsx: `PlaceholderPage` and `NotAllowedPage` (non-ADMIN on `/settings/*`)
- frontend/src/app/layout/SafeAreaStyles.test.ts: guard for the safe-area tokens and the layout padding that uses them
- frontend/src/index.css: global stylesheet entry importing the shared style layers
