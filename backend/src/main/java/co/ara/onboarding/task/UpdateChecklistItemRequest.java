package co.ara.onboarding.task;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The single wire shape behind {@code PUT /checklist/{itemId}} (design spec
 * §7), even though {@link ChecklistService} itself exposes three separately-
 * gated methods -- {@link ChecklistService#rename rename}/
 * {@link ChecklistService#reorder reorder} ({@code task.manage}) and
 * {@link ChecklistService#toggle toggle} ({@code task.complete}). All three
 * fields are nullable and independent: {@link TaskController} calls whichever
 * of {@code rename}/{@code reorder}/{@code toggle} the non-null fields imply,
 * so a caller changing only the label never touches {@code task.complete}'s
 * gate, and one flipping only {@code toggleDone} never touches
 * {@code task.manage}'s.
 *
 * <p><b>Why {@code toggleDone} and not a target {@code done} boolean.</b>
 * {@link ChecklistService#toggle} inverts the item's current {@code done}
 * regardless of what it currently is -- it was written that way in Task 22,
 * where {@code toggle()} takes no argument at all. Modelling this field as a
 * target state ("set done to true") would misrepresent that contract: two
 * requests each sending {@code done: true} could land the item in DIFFERENT
 * end states depending on what it already was when each request arrived,
 * which is not what a client sending an explicit target value would expect.
 * Naming the field as an action instead -- {@code toggleDone: true} means
 * "flip it now" -- makes the request honest about what the underlying
 * service can actually do without this controller re-implementing a
 * read-then-decide check {@code ChecklistService} does not expose, which
 * would either duplicate its permission gate or bypass it.
 *
 * <p>A request with all three fields null is refused by
 * {@link TaskController} as {@link IllegalArgumentException} (400) rather
 * than silently returning the item unchanged: {@code ChecklistService} has no
 * bare "read one item" method for the controller to fall back on -- every
 * read it exposes is a side effect of a write -- so a true no-op request has
 * nothing to delegate to.
 */
public record UpdateChecklistItemRequest(@Size(min = 1) String label,
                                         @PositiveOrZero Integer ordinal,
                                         Boolean toggleDone) {}
