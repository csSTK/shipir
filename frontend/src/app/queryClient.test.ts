import { beforeEach, describe, expect, it } from "vitest";
import { ApiError } from "../shared/api/client.ts";
import { queryClient } from "./queryClient.ts";

const ME = ["auth", "me"];
const admin = { username: "admin", role: "ADMIN", setupRequired: false };

beforeEach(() => {
  queryClient.clear();
  queryClient.setQueryData(ME, admin);
});

describe("global 401 handling", () => {
  it("signs the user out when a query fails with 401", async () => {
    await queryClient
      .fetchQuery({
        queryKey: ["hosts"],
        queryFn: () =>
          Promise.reject(
            new ApiError(
              401,
              "x",
              undefined,
              undefined,
              undefined,
              undefined,
              undefined,
              "/hosts",
            ),
          ),
        retry: false,
      })
      .catch(() => {});
    expect(queryClient.getQueryData(ME)).toBeNull();
  });

  it("signs the user out when a mutation fails with 401", async () => {
    await queryClient
      .getMutationCache()
      .build(queryClient, {
        mutationFn: () =>
          Promise.reject(
            new ApiError(
              401,
              "x",
              undefined,
              undefined,
              undefined,
              undefined,
              undefined,
              "/hosts",
            ),
          ),
      })
      .execute(undefined)
      .catch(() => {});
    expect(queryClient.getQueryData(ME)).toBeNull();
  });

  it("ignores a 401 from an /auth/ call such as a wrong password", async () => {
    await queryClient
      .getMutationCache()
      .build(queryClient, {
        mutationFn: () =>
          Promise.reject(
            new ApiError(
              401,
              "x",
              "INVALID_CREDENTIALS",
              undefined,
              undefined,
              undefined,
              undefined,
              "/auth/login",
            ),
          ),
      })
      .execute(undefined)
      .catch(() => {});
    expect(queryClient.getQueryData(ME)).toEqual(admin);
  });

  it("ignores other error statuses", async () => {
    await queryClient
      .fetchQuery({
        queryKey: ["hosts"],
        queryFn: () =>
          Promise.reject(
            new ApiError(
              500,
              "x",
              undefined,
              undefined,
              undefined,
              undefined,
              undefined,
              "/hosts",
            ),
          ),
        retry: false,
      })
      .catch(() => {});
    expect(queryClient.getQueryData(ME)).toEqual(admin);
  });
});
