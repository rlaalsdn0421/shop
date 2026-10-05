import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "./AuthContext";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

const KEY = "shop-auth";
const HOUR = 60 * 60 * 1000;

function Consumer() {
  const { session, ready, login } = useAuth();
  return (
    <div>
      <span data-testid="ready">{String(ready)}</span>
      <span data-testid="username">{session?.username ?? "none"}</span>
      <span data-testid="role">{session?.role ?? "none"}</span>
      <button onClick={() => login("new-token", "newuser01", "SELLER")}>login</button>
    </div>
  );
}

function renderConsumer() {
  return render(
    <AuthProvider>
      <Consumer />
    </AuthProvider>
  );
}

beforeEach(() => {
  localStorage.clear();
});

describe("AuthProvider", () => {
  it("성공: 저장된 유효한 세션(username 포함)을 복원한다", async () => {
    localStorage.setItem(
      KEY,
      JSON.stringify({ token: "t", username: "user01", role: "USER", expiresAt: Date.now() + HOUR })
    );
    renderConsumer();

    await waitFor(() => expect(screen.getByTestId("ready")).toHaveTextContent("true"));
    expect(screen.getByTestId("username")).toHaveTextContent("user01");
    expect(screen.getByTestId("role")).toHaveTextContent("USER");
  });

  it("실패: username 없이 email만 있는 이전 형식 세션은 버리고 저장소 키를 지운다", async () => {
    localStorage.setItem(
      KEY,
      JSON.stringify({ token: "t", email: "old@example.com", role: "USER", expiresAt: Date.now() + HOUR })
    );
    renderConsumer();

    await waitFor(() => expect(screen.getByTestId("ready")).toHaveTextContent("true"));
    expect(screen.getByTestId("username")).toHaveTextContent("none");
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it("실패: 만료된 세션은 버리고 저장소 키를 지운다", async () => {
    localStorage.setItem(
      KEY,
      JSON.stringify({ token: "t", username: "user01", role: "USER", expiresAt: Date.now() - 1000 })
    );
    renderConsumer();

    await waitFor(() => expect(screen.getByTestId("ready")).toHaveTextContent("true"));
    expect(screen.getByTestId("username")).toHaveTextContent("none");
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it("성공: login(token, username, role)을 호출하면 해당 username으로 세션이 저장된다", async () => {
    renderConsumer();
    await waitFor(() => expect(screen.getByTestId("ready")).toHaveTextContent("true"));

    screen.getByRole("button", { name: "login" }).click();

    await waitFor(() => expect(screen.getByTestId("username")).toHaveTextContent("newuser01"));
    const stored = JSON.parse(localStorage.getItem(KEY) ?? "{}");
    expect(stored).toMatchObject({ token: "new-token", username: "newuser01", role: "SELLER" });
    expect(stored.expiresAt).toBeGreaterThan(Date.now());
  });
});
