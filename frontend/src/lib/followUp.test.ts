import { describe, expect, it } from "vitest";
import {
  composeFollowUpQuestion,
  followUpQuestionLimit,
  limitCodePoints,
} from "./followUp";

describe("follow-up question context", () => {
  it("composes both questions without including result rows", () => {
    expect(
      composeFollowUpQuestion("Doanh thu theo tháng?", "Chỉ hiển thị tháng 9"),
    ).toBe(
      "Previous question context: Doanh thu theo tháng?\nFollow-up question: Chỉ hiển thị tháng 9",
    );
  });

  it("uses Unicode code points for the 1000-character request limit", () => {
    const previous = "Doanh thu?";
    const limit = followUpQuestionLimit(previous);
    expect(
      Array.from(composeFollowUpQuestion(previous, "é".repeat(limit)) ?? ""),
    ).toHaveLength(1000);
    expect(composeFollowUpQuestion(previous, "é".repeat(limit + 1))).toBeNull();
  });

  it("rejects empty previous and follow-up questions", () => {
    expect(composeFollowUpQuestion("", "continue")).toBeNull();
    expect(composeFollowUpQuestion("previous", "  ")).toBeNull();
  });

  it("limits supplementary Unicode characters as single code points", () => {
    expect(limitCodePoints("🎧🎼🎹", 2)).toBe("🎧🎼");
  });
});
