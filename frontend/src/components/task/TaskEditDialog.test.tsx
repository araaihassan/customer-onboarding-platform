import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { Task } from "@/lib/api/tasks";
import { TaskEditDialog } from "./TaskEditDialog";

afterEach(cleanup);

const existing: Task = {
  id: "t-1",
  caseId: "case-1",
  milestoneId: "m-1",
  title: "Collect signed agreement",
  description: "Confirm banking details are on file.",
  status: "PENDING",
  priority: "HIGH",
  dueDate: "2026-12-01",
  assigneeId: "u-9",
};

const milestones = [
  { id: "m-1", name: "Kickoff" },
  { id: "m-2", name: "Delivery" },
];

describe("TaskEditDialog", () => {
  /**
   * The whole point of this test: a PUT is a full replace, so a field the
   * user never touched must still round-trip its CURRENT value, not vanish.
   * Only `title` is edited here; every other field must arrive unchanged.
   */
  it("submits every field the request type accepts, not only the changed one", () => {
    const onSubmit = vi.fn();
    render(
      <TaskEditDialog task={existing} milestones={milestones} onSubmit={onSubmit} onClose={vi.fn()} />,
    );

    fireEvent.change(screen.getByLabelText("Title"), { target: { value: "Renamed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Renamed",
        description: existing.description,
        priority: existing.priority,
        dueDate: existing.dueDate,
        milestoneId: existing.milestoneId,
        assigneeId: existing.assigneeId,
      }),
    );
  });

  it("refuses to submit a blank title", () => {
    const onSubmit = vi.fn();
    render(
      <TaskEditDialog task={existing} milestones={milestones} onSubmit={onSubmit} onClose={vi.fn()} />,
    );

    fireEvent.change(screen.getByLabelText("Title"), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText("This field is required")).not.toBeNull();
  });

  it("calls onClose when Cancel is clicked, without submitting", () => {
    const onSubmit = vi.fn();
    const onClose = vi.fn();
    render(
      <TaskEditDialog task={existing} milestones={milestones} onSubmit={onSubmit} onClose={onClose} />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalled();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
