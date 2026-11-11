import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderApp, readyDataset } from "../test/render";
import * as fixtures from "../test/fixtures";

describe("home page", () => {
  it("shows warehouse and example data from the Java API", async () => {
    renderApp("/");
    await readyDataset();

    expect(
      await screen.findByRole("heading", {
        name: /Trợ lý phân tích dữ liệu bằng ngôn ngữ tự nhiên/,
      }),
    ).toBeVisible();
    expect(
      (await screen.findAllByText("Demo POSTGRESQL")).length,
    ).toBeGreaterThan(0);
    expect(
      (await screen.findAllByText(fixtures.query.question)).length,
    ).toBeGreaterThan(0);
    expect(
      screen
        .getAllByRole("link", { name: "Trang chủ" })
        .some((link) => link.getAttribute("aria-current") === "page"),
    ).toBe(true);
  });

  it("opens Query with the selected example instead of submitting it silently", async () => {
    const user = userEvent.setup();
    renderApp("/");
    await readyDataset();

    const examples = await screen.findAllByRole("button", {
      name: new RegExp(fixtures.query.question),
    });
    await user.click(examples[0]);

    expect(
      await screen.findByRole("textbox", { name: "Câu hỏi phân tích" }),
    ).toHaveValue(fixtures.query.question);
  });
});
