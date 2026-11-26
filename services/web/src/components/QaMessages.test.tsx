import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QaMessages } from "./QaMessages";

afterEach(cleanup);

const msgs = [
  { seq: 0, question: "Năm nay công việc ra sao?", answer: "Đoạn một.\n\nĐoạn hai.", created_at: "2026-11-25T03:00:00Z" },
  { seq: 1, question: "Còn tiền bạc?", answer: "Tiền bạc ổn.", created_at: "2026-11-25T04:00:00Z" },
];

describe("QaMessages", () => {
  it("shows each question, its answer as paragraphs, the seal once, the disclaimer and the AI label", () => {
    const { container } = render(<QaMessages messages={msgs} status="ANSWERED" notice={null} disclaimer="Chỉ để tham khảo." />);
    expect(Array.from(container.querySelectorAll(".ask")).map((e) => e.textContent)).toEqual([msgs[0].question, msgs[1].question]);
    expect(screen.getByText("Đoạn một.").tagName).toBe("P");
    expect(screen.getByText("Đoạn hai.").tagName).toBe("P");
    expect(screen.getAllByRole("img", { name: "Ấn son" })).toHaveLength(1);
    expect(container.querySelector(".w")).toBeNull(); // static: no ink animation
    expect(screen.getByText("Chỉ để tham khảo.")).toBeTruthy();
    expect(screen.getByText("Nội dung do AI biên soạn từ kho tư liệu.")).toBeTruthy();
  });

  it("has no seal until answered, and shows the notice of a queued or refunded reading", () => {
    render(
      <QaMessages
        messages={[{ ...msgs[0], answer: null }]}
        status="RETRYING"
        notice="Thầy đang bận."
        disclaimer="Chỉ để tham khảo."
      />,
    );
    expect(screen.queryByRole("img", { name: "Ấn son" })).toBeNull();
    expect(screen.getByRole("status").textContent).toBe("Thầy đang bận.");
  });
});
