import { describe, it, expect, vi, beforeEach } from "vitest";
import { StrictMode } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ChatWidget } from "./ChatWidget";

const GREETING = { answer: "안녕하세요! 무엇을 도와드릴까요?", suggestions: ["배송 문의", "반품 문의"] };

function mockFetch(...responses: Array<{ status: number; body: unknown } | "network-error">) {
  const fn = vi.fn();
  for (const r of responses) {
    if (r === "network-error") fn.mockRejectedValueOnce(new TypeError("fail"));
    else
      fn.mockResolvedValueOnce({
        ok: r.status >= 200 && r.status < 300,
        status: r.status,
        json: async () => r.body,
      });
  }
  vi.stubGlobal("fetch", fn);
  return fn;
}

const sentMessages = (fn: ReturnType<typeof vi.fn>) =>
  fn.mock.calls.map(([, init]) => JSON.parse(init.body).message);

beforeEach(() => {
  vi.unstubAllGlobals();
});

describe("ChatWidget", () => {
  it("성공: 버튼으로 열고 닫으며 Escape로도 닫힌다", async () => {
    mockFetch({ status: 200, body: GREETING });
    const user = userEvent.setup();
    render(<ChatWidget />);
    expect(screen.queryByText("무엇이든 물어보세요")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "챗봇" }));
    expect(screen.getByText("무엇이든 물어보세요")).toBeInTheDocument();
    expect(screen.getByLabelText("질문 입력")).toHaveFocus();

    await user.click(screen.getByRole("button", { name: "닫기" }));
    expect(screen.queryByText("무엇이든 물어보세요")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await user.keyboard("{Escape}");
    expect(screen.queryByText("무엇이든 물어보세요")).not.toBeInTheDocument();
  });

  it("성공: 처음 열 때만 도움말을 조용히 요청하고 답변과 추천만 보여준다", async () => {
    const fn = mockFetch({ status: 200, body: GREETING });
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    expect(await screen.findByText(GREETING.answer)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "배송 문의" })).toBeInTheDocument();
    expect(screen.queryByText("도움말")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "닫기" }));
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    expect(fn).toHaveBeenCalledTimes(1);
    expect(sentMessages(fn)).toEqual(["도움말"]);
  });

  it("성공: 메시지를 보내면 사용자 말풍선과 봇 답변이 모두 보인다", async () => {
    const fn = mockFetch(
      { status: 200, body: GREETING },
      { status: 200, body: { answer: "2~3일 걸려요.", suggestions: [] } }
    );
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(GREETING.answer);

    await user.type(screen.getByLabelText("질문 입력"), "배송 얼마나 걸려요?{Enter}");
    expect(screen.getByText("배송 얼마나 걸려요?")).toBeInTheDocument();
    expect(await screen.findByText("2~3일 걸려요.")).toBeInTheDocument();
    expect(sentMessages(fn)).toEqual(["도움말", "배송 얼마나 걸려요?"]);
    expect(screen.getByLabelText("질문 입력")).toHaveValue("");
  });

  it("성공: 추천 칩을 누르면 그 텍스트가 사용자 메시지로 전송된다", async () => {
    const fn = mockFetch(
      { status: 200, body: GREETING },
      { status: 200, body: { answer: "반품은 7일 이내예요.", suggestions: [] } }
    );
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await user.click(await screen.findByRole("button", { name: "반품 문의" }));

    expect(screen.getByText("반품 문의")).toBeInTheDocument();
    expect(await screen.findByText("반품은 7일 이내예요.")).toBeInTheDocument();
    expect(sentMessages(fn)).toEqual(["도움말", "반품 문의"]);
  });

  it("실패: 서버 오류면 안내 말풍선을 보여주고 입력은 계속 쓸 수 있다", async () => {
    mockFetch(
      { status: 200, body: GREETING },
      { status: 500, body: { error: "서버 오류" } }
    );
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(GREETING.answer);

    await user.type(screen.getByLabelText("질문 입력"), "질문{Enter}");
    expect(
      await screen.findByText("지금은 답변할 수 없어요. 잠시 후 다시 시도해 주세요.")
    ).toBeInTheDocument();
    expect(screen.getByLabelText("질문 입력")).toHaveValue("질문");
    await user.type(screen.getByLabelText("질문 입력"), "다시");
    expect(screen.getByLabelText("질문 입력")).toHaveValue("질문다시");
    expect(screen.getByRole("button", { name: "전송" })).toBeEnabled();
  });

  it("실패: 네트워크 오류여도 안내 말풍선이 보이고, 다시 열면 인사를 재시도한다", async () => {
    const fn = mockFetch("network-error", { status: 200, body: GREETING });
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    expect(
      await screen.findByText("지금은 답변할 수 없어요. 잠시 후 다시 시도해 주세요.")
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "닫기" }));
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    expect(await screen.findByText(GREETING.answer)).toBeInTheDocument();
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it("실패: 입력이 비어 있거나 공백뿐이면 전송 버튼이 비활성화된다", async () => {
    mockFetch({ status: 200, body: GREETING });
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(GREETING.answer);

    const send = screen.getByRole("button", { name: "전송" });
    expect(send).toBeDisabled();
    await user.type(screen.getByLabelText("질문 입력"), "   ");
    expect(send).toBeDisabled();
    await user.type(screen.getByLabelText("질문 입력"), "a");
    expect(send).toBeEnabled();
  });
});

const ERROR_TEXT = "지금은 답변할 수 없어요. 잠시 후 다시 시도해 주세요.";

function deferredFetch(first: unknown) {
  let resolve!: (v: unknown) => void;
  const fn = vi
    .fn()
    .mockResolvedValueOnce({ ok: true, status: 200, json: async () => first })
    .mockReturnValueOnce(new Promise((res) => (resolve = res)));
  vi.stubGlobal("fetch", fn);
  return {
    fn,
    finish: (body: unknown) => resolve({ ok: true, status: 200, json: async () => body }),
  };
}

describe("ChatWidget 보강", () => {
  it("성공: 응답 대기 중에는 표시만 바뀌고 두 번째 요청이 나가지 않는다", async () => {
    const { fn, finish } = deferredFetch(GREETING);
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(GREETING.answer);

    await user.type(screen.getByLabelText("질문 입력"), "첫 질문{Enter}");
    expect(screen.getByText("답변 중...")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "배송 문의" })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("질문 입력"), "둘째{Enter}");
    expect(screen.getByRole("button", { name: "전송" })).toBeDisabled();
    expect(fn).toHaveBeenCalledTimes(2);

    finish({ answer: "답이에요.", suggestions: [] });
    expect(await screen.findByText("답이에요.")).toBeInTheDocument();
    expect(screen.queryByText("답변 중...")).not.toBeInTheDocument();
  });

  it("성공: StrictMode에서도 도움말 요청은 한 번만 나간다", async () => {
    const fn = mockFetch({ status: 200, body: GREETING });
    const user = userEvent.setup();
    render(
      <StrictMode>
        <ChatWidget />
      </StrictMode>
    );
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(GREETING.answer);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it("성공: 답변의 HTML은 글자 그대로 표시된다", async () => {
    mockFetch({ status: 200, body: { answer: "<img src=x onerror=alert(1)>", suggestions: [] } });
    const user = userEvent.setup();
    const { container } = render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    expect(await screen.findByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it("성공: 응답 대기 중 닫았다가 다시 열어도 상태가 유지되고 인사를 다시 요청하지 않는다", async () => {
    const { fn, finish } = deferredFetch(GREETING);
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(GREETING.answer);
    await user.type(screen.getByLabelText("질문 입력"), "질문{Enter}");

    await user.click(screen.getByRole("button", { name: "닫기" }));
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    expect(screen.getByText("질문")).toBeInTheDocument();
    expect(screen.getByText("답변 중...")).toBeInTheDocument();
    finish({ answer: "늦은 답", suggestions: [] });
    expect(await screen.findByText("늦은 답")).toBeInTheDocument();
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it("성공: 닫으면 토글 버튼으로, 칩을 누르면 입력창으로 포커스가 간다", async () => {
    mockFetch(
      { status: 200, body: GREETING },
      { status: 200, body: { answer: "답", suggestions: [] } }
    );
    const user = userEvent.setup();
    render(<ChatWidget />);
    const toggle = screen.getByRole("button", { name: "챗봇" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(toggle).toHaveAttribute("aria-controls", "chat-panel");

    await user.click(await screen.findByRole("button", { name: "배송 문의" }));
    expect(screen.getByLabelText("질문 입력")).toHaveFocus();

    await user.keyboard("{Escape}");
    expect(toggle).toHaveFocus();
    await user.click(toggle);
    await user.click(screen.getByRole("button", { name: "닫기" }));
    expect(toggle).toHaveFocus();
  });

  it("실패: 질문이 실패하면 입력을 복원하고 칩을 유지하며 다시 열어도 인사를 재요청하지 않는다", async () => {
    const fn = mockFetch(
      { status: 200, body: GREETING },
      { status: 500, body: { error: "서버 오류" } }
    );
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(GREETING.answer);

    await user.type(screen.getByLabelText("질문 입력"), "실패할 질문{Enter}");
    expect(await screen.findByText(ERROR_TEXT)).toBeInTheDocument();
    expect(screen.getByLabelText("질문 입력")).toHaveValue("실패할 질문");
    expect(screen.getByRole("button", { name: "배송 문의" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "닫기" }));
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it("실패: 인사 재시도 때는 이전 오류 말풍선이 사라진다", async () => {
    mockFetch("network-error", { status: 200, body: GREETING });
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(ERROR_TEXT);
    await user.click(screen.getByRole("button", { name: "닫기" }));
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(GREETING.answer);
    expect(screen.queryByText(ERROR_TEXT)).not.toBeInTheDocument();
  });

  it("실패: 입력은 200자로 제한되고 공백만 Enter하면 아무것도 보내지 않는다", async () => {
    const fn = mockFetch({ status: 200, body: GREETING });
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    await screen.findByText(GREETING.answer);

    const input = screen.getByLabelText("질문 입력");
    expect(input).toHaveAttribute("maxlength", "200");
    await user.type(input, "   {Enter}");
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it("실패: 응답 본문이 비어 있으면 빈 말풍선 대신 오류 말풍선을 보여준다", async () => {
    mockFetch({ status: 200, body: { answer: "", suggestions: [] } });
    const user = userEvent.setup();
    render(<ChatWidget />);
    await user.click(screen.getByRole("button", { name: "챗봇" }));
    expect(await screen.findByText(ERROR_TEXT)).toBeInTheDocument();
  });
});
