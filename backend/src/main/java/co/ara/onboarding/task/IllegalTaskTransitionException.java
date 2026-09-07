package co.ara.onboarding.task;

/**
 * Refused because {@code TaskService.TRANSITIONS} (design spec §5.1) draws no
 * edge from {@code from} to {@code to} -- COMPLETED and CANCELLED are
 * terminal from {@code changeStatus}'s own perspective (the only route back
 * out of COMPLETED is a milestone reopen, a completely separate mechanism),
 * and every other edge the diagram does not draw -- IN_PROGRESS back to
 * PENDING, WAITING straight to COMPLETED, PENDING straight to COMPLETED -- is
 * refused the same way, not just the terminal-state case this exception was
 * first written to catch.
 */
public class IllegalTaskTransitionException extends RuntimeException {

    public IllegalTaskTransitionException(TaskStatus from, TaskStatus to) {
        super("Cannot move a task from " + from + " to " + to);
    }
}
