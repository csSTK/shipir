# Code map: frontend hosts

- frontend/src/features/hosts/{api.ts,api.test.tsx,index.ts}: host and overview types and hooks (list, create, test connection, rename, delete, tags), exports
- frontend/src/features/hosts/{EnvironmentForm.tsx,EnvironmentForm.css,EnvironmentForm.test.tsx}: Local or Remote (TCP+TLS) environment form
- frontend/src/features/hosts/{EnvironmentsOverview.tsx,EnvironmentsOverview.css,EnvironmentsOverview.test.tsx}: home screen: environment cards, search, tag filter, ADMIN add
- frontend/src/features/hosts/{EnvironmentActions.tsx,EnvironmentActions.css,EnvironmentActions.test.tsx}: ADMIN overflow menu: rename, edit tags, delete with deployments warning
- frontend/src/features/hosts/{tags.ts,tags.test.ts,TagInput.tsx,TagInput.css,TagInput.test.tsx,TagChips.tsx,TagChips.css,TagChips.test.tsx,EditTagsDialog.tsx}: tag rules, chip input, read-only chips, edit-tags dialog
- frontend/src/features/hosts/{EnvironmentDashboard.tsx,EnvironmentDashboard.css,EnvironmentDashboard.test.tsx}: `/environments/:id` dashboard: status, resource tiles, usage history, disk usage, clean up
- frontend/src/features/hosts/{PruneDialog.tsx,PruneDialog.css,PruneDialog.test.tsx,prune.ts}: prune dialog with live preview and result summary
- frontend/src/features/hosts/{DiskUsageCard.tsx,DiskUsageCard.css,DiskUsageCard.test.tsx,diskUsage.ts}: disk usage card: stacked bar per category, largest items, refresh
- frontend/src/features/hosts/{createResource.ts,deleteResource.ts,deleteResource.test.ts,deleteRowActions.tsx}: create and delete mutations for containers, networks, volumes, images and the row delete menu
- frontend/src/features/hosts/{DeleteResourceDialog.tsx,DeleteResourceDialog.css,DeleteResources.test.tsx}: delete confirmation with force option and managed-resource warning
- frontend/src/features/hosts/{resources.ts,format.ts,format.test.ts}: `ENV_RESOURCES` for sidebar and tiles, size formatting and count plurals
