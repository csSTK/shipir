# Code map: frontend containers

- frontend/src/features/containers/{api.ts,index.ts}: container list and lifecycle action hooks (start, stop, restart, pause, kill, ...)
- frontend/src/features/containers/{ContainersPage.tsx,ContainersPage.css,ContainersPage.test.tsx}: `/environments/:id/containers` table with pinned row actions and `?deployment=` filter
- frontend/src/features/containers/{CreateContainerDialog.tsx,CreateContainerDialog.css,ContainerResourcesDialog.tsx,containerForm.ts,containerFixture.ts}: create and edit container forms
- frontend/src/features/containers/{containerForm.test.ts,CreateResources.test.tsx,EditContainer.test.tsx}: container form, create resources and edit container tests
- frontend/src/features/containers/details/{ContainerDetailsPage.tsx,ContainerDetailsPage.css,ContainerDetailsPage.test.tsx,api.ts}: container details tabs (overview, env, storage, labels, health, files)
- frontend/src/features/containers/logs/{ContainerLogsPage.tsx,ContainerLogsPage.css,ContainerLogsPage.test.tsx,index.ts}: container logs page and exports of the log module
- frontend/src/features/containers/logs/{logStream.ts,logStream.test.tsx,useContainerLogs.ts,useDeploymentLogs.ts}: STOMP log stream hook (batched frames, line cap, resubscribe) for container and deployment logs
- frontend/src/features/containers/logs/{LogView.tsx,LogView.css,LogView.test.tsx,Highlighted.tsx,LogOptions.tsx}: shared log viewer with search, stderr filter and options bar
- frontend/src/features/containers/logs/{logSearch.ts,logSearch.test.ts,logDownload.ts,logDownload.test.ts,ansi.ts,ansi.test.ts}: log search matching, log download and ANSI colour parsing
- frontend/src/features/containers/files/{ContainerFilesPanel.tsx,ContainerFilesPanel.css,ContainerFilesPanel.test.tsx,api.ts}: ADMIN container file browser with download and upload
- frontend/src/features/containers/exec/{ContainerConsolePage.tsx,ContainerConsolePage.css,ContainerConsolePage.test.tsx,api.ts}: container console (exec terminal) for ADMIN and permitted OPERATOR
