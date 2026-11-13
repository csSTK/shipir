# Code map: frontend domains

- frontend/src/features/domains/{api.ts,api.test.tsx,index.ts,index.test.ts,domainErrors.ts}: managed proxy query and setup hooks, domain error texts, exports
- frontend/src/features/domains/{DomainsPage.tsx,DomainsPage.css,DomainsPage.test.tsx,ProxySetupCard.tsx,ProxySetupCard.css}: `/environments/:id/domains` page with proxy setup card and ready state
- frontend/src/features/domains/{ProxySettingsPanel.tsx,ProxySettingsPanel.css,ProxySettingsPanel.test.tsx,advancedMode.ts,advancedMode.test.tsx}: proxy settings panel and per-user Advanced mode switch
- frontend/src/features/domains/certificates/{api.ts,certificateErrors.ts,CertificatesSection.tsx,CertificatesSection.css,CertificateDialog.tsx,CertificatesSection.test.tsx}: certificates: list, upload, renew, delete
- frontend/src/features/domains/routes/{api.ts,hostname.ts,hostname.test.ts,routeErrors.ts,RoutesList.tsx,RoutesList.css,RoutesList.test.tsx}: published domains list, status polling, hostname rules
- frontend/src/features/domains/routes/{PublishDialog.tsx,PublishDialog.css,AdvancedFields.tsx,AdvancedFields.css,advancedState.ts,advancedState.test.ts,AdvancedPublish.test.tsx}: publish dialog with advanced route fields
- frontend/src/features/domains/config/{api.ts,configErrors.ts,ProxyConfigEditor.tsx,ProxyConfigEditor.css,ProxyConfigEditor.test.tsx}: ADMIN raw proxy config editor (advanced mode only)
