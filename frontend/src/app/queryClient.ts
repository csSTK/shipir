import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { ApiError } from "../shared/api/client.ts";

// Same value as CURRENT_USER_KEY in features/auth/api.ts (not imported to keep app/ free of a cycle through it).
const CURRENT_USER_KEY = ["auth", "me"];

/**
 * A 401 from any call other than /auth/* means the server session is gone: marking the user as signed out makes
 * AuthGate show the login page. /auth/* answers stay local (a wrong password is also a 401 and must not loop).
 */
function handleUnauthorized(error: unknown) {
  if (!(error instanceof ApiError) || error.status !== 401) return;
  if (error.path?.startsWith("/auth/")) return;
  if (queryClient.getQueryData(CURRENT_USER_KEY) === null) return;
  queryClient.setQueryData(CURRENT_USER_KEY, null);
}

export const queryClient: QueryClient = new QueryClient({
  queryCache: new QueryCache({ onError: handleUnauthorized }),
  mutationCache: new MutationCache({ onError: handleUnauthorized }),
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      retry: 1,
    },
  },
});
