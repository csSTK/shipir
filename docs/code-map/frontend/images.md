# Code map: frontend images

- frontend/src/features/images/{api.ts,index.ts}: image list, pull, build, tag, push and update-available hooks with error texts
- frontend/src/features/images/{ImagesPage.tsx,ImagesPage.css,ImagesPage.test.tsx}: `/environments/:id/images` table with usage badges and Update available
- frontend/src/features/images/{PullImageDialog.tsx,PullImage.test.tsx,BuildImageDialog.tsx,TagImageDialog.tsx,PushImageDialog.tsx}: pull, build, tag and push dialogs
- frontend/src/features/images/{buildRules.ts,buildRules.test.ts,BuildTagPush.test.tsx}: client rules for image build input; build, tag and push tests
