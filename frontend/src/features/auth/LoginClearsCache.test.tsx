import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { queryClient } from "../../app/queryClient.ts";
import { apiFetch } from "../../shared/api/client.ts";
import { LoginPage } from "./LoginPage.tsx";
import { CURRENT_USER_KEY } from "./api.ts";

vi.mock("../../shared/api/client.ts", async (importActual) => ({
  ...(await importActual<typeof import("../../shared/api/client.ts")>()),
  apiFetch: vi.fn(),
}));
const apiFetchMock = vi.mocked(apiFetch);

const user = {
  username: "admin",
  role: "ADMIN",
  setupRequired: false,
  setupStep: null,
};
const SESSIONS = ["ai", "sessions"];

beforeEach(() => {
  queryClient.clear();
  queryClient.setQueryData(SESSIONS, [{ id: "previous-users-chat" }]);
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

async function submitCredentials(answer: unknown) {
  render(
    <QueryClientProvider client={queryClient}>
      <LoginPage />
    </QueryClientProvider>,
  );
  const username = await screen.findByLabelText("Username");
  apiFetchMock.mockResolvedValueOnce(answer);
  fireEvent.change(username, { target: { value: "admin" } });
  fireEvent.change(screen.getByLabelText("Password"), {
    target: { value: "pw" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
}

describe("sign-in drops the previous user cache", () => {
  it("after a password login", async () => {
    await submitCredentials(user);
    await waitFor(() =>
      expect(queryClient.getQueryData(CURRENT_USER_KEY)).toEqual(user),
    );
    expect(queryClient.getQueryData(SESSIONS)).toBeUndefined();
  });

  it("after the 2FA verification", async () => {
    await submitCredentials({ mfaRequired: true });
    const code = await screen.findByLabelText("Authentication code");
    expect(queryClient.getQueryData(SESSIONS)).toBeDefined();
    apiFetchMock.mockResolvedValueOnce(user);
    fireEvent.change(code, { target: { value: "123456" } });
    fireEvent.click(screen.getByRole("button", { name: "Verify" }));
    await waitFor(() =>
      expect(queryClient.getQueryData(CURRENT_USER_KEY)).toEqual(user),
    );
    expect(queryClient.getQueryData(SESSIONS)).toBeUndefined();
  });
});
