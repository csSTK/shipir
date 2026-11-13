# Code map: frontend ai-chat

- frontend/src/features/ai-chat/{api.ts,index.ts,toolLabel.ts}: chat session, message and confirmation hooks, pending-action count, tool name labels, exports
- frontend/src/features/ai-chat/{ChatPage.tsx,ChatPage.css,ChatPage.test.tsx,SessionList.tsx}: `/assistant[/:sessionId]` page with the own-chats list and New chat
- frontend/src/features/ai-chat/{ChatPane.tsx,ChatPane.css,ChatPaneStyles.test.ts}: one chat: rename, delete, share with admins, composer and not-configured state
- frontend/src/features/ai-chat/{MessageList.tsx,MessageList.css,ActionConfirmCard.tsx,ActionConfirmCard.test.tsx}: transcript with tool calls (also read-only) and the Confirm card listing all action arguments
- frontend/src/features/ai-chat/{ChatDockProvider.tsx,ChatDockProvider.test.tsx,useChatDock.ts}: app-wide chat state: active session and subscription, background runs, unread marks, pending count
- frontend/src/features/ai-chat/{useChatStream.ts,useChatStream.test.tsx}: STOMP subscription of one chat session turning events into streamed messages and pending confirmations
- frontend/src/features/ai-chat/{useViewContext.ts,useViewContext.test.tsx,ViewContextChip.tsx,ViewContextChip.css}: maps the current route to the assistant view context and shows the removable context chip
- frontend/src/features/ai-chat/{AssistantDock.tsx,AssistantDock.css,AssistantDock.test.tsx}: floating assistant button and panel in `AppShell` (bottom sheet on phones, hidden on `/assistant*`)
