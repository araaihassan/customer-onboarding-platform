"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { TextareaField } from "@/components/ui/Field";
import { ErrorState, SkeletonRows } from "@/components/ui/States";
import { parseProblemDetail, type Participant } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import {
  useComments,
  useUpdateComment,
  type Comment,
  type CommentResourceType,
} from "@/lib/api/comments";
import { shortId } from "@/lib/api/customers";
import { useAuth } from "@/lib/auth/useAuth";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";
import { CommentComposer } from "./CommentComposer";

/**
 * The comment thread mounted on both a task (`TaskDetail`, Task 27) and the
 * journey itself (the case workspace's Journey tab) -- design spec §8.3:
 * "On a task, and on the journey itself. Author, relative time, body.
 * Editing marks the comment edited."
 *
 * `participants` is threaded down from wherever the case page already
 * fetches it (`useParticipants(caseId)`), the same discipline
 * `MilestoneRow` already established for resolving an owner id to a name --
 * not refetched here. An author who is not (or is no longer) a formal case
 * participant -- an Administrator who commented without holding a
 * `CaseParticipant` row, say -- falls back to a shortened id rather than
 * disappearing.
 *
 * Ordering is enforced client-side: `CommentService.forResource` carries no
 * `ORDER BY`, so "oldest-first" is this component's own guarantee, not an
 * assumption about server order.
 */
export function CommentThread({
  caseId,
  resourceType,
  resourceId,
  participants,
}: {
  caseId: string;
  resourceType: CommentResourceType;
  resourceId: string;
  participants: Participant[];
}) {
  const comments = useComments(caseId, resourceType, resourceId);
  const { user } = useAuth();
  const canEdit = useHasPermission("comment.create");

  // Array.isArray, not just `?? []` -- a handful of existing test fixtures
  // (this thread's own mounting points included) stub every unmatched URL
  // with a bare `{}` rather than every endpoint by name, and spreading a
  // non-array here would throw before the real guard (isLoading/isError)
  // ever gets a chance to run.
  const ordered = Array.isArray(comments.data)
    ? [...comments.data].sort((a, b) => (a.createdAt ?? "").localeCompare(b.createdAt ?? ""))
    : [];

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <h5
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
        }}
      >
        {t("comment.thread.title")}
      </h5>

      {comments.isLoading && <SkeletonRows rows={2} height={48} />}

      {comments.isError && (
        <ErrorState message={t("common.error")} onRetry={() => void comments.refetch()} />
      )}

      {comments.isSuccess && ordered.length === 0 && (
        <p
          className="text-text-faint"
          style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
        >
          {t("comment.thread.empty")}
        </p>
      )}

      {ordered.length > 0 && (
        <ul className="flex flex-col" style={{ gap: "var(--ob-space-11)", listStyle: "none" }}>
          {ordered.map((comment) => (
            <CommentRow
              key={comment.id}
              comment={comment}
              participants={participants}
              // The edit control appears only on the actor's own comment,
              // AND only when the actor still holds comment.create -- the
              // same double condition the backend enforces (gate, then an
              // independent author check on top of it). Neither alone is
              // sufficient here; this hook controls the affordance only, the
              // server is the sole authority (useHasPermission's own doc
              // comment).
              canEdit={canEdit && Boolean(user?.id) && comment.authorId === user?.id}
            />
          ))}
        </ul>
      )}

      {canEdit && (
        <CommentComposer caseId={caseId} resourceType={resourceType} resourceId={resourceId} />
      )}
    </div>
  );
}

function CommentRow({
  comment,
  participants,
  canEdit,
}: {
  comment: Comment;
  participants: Participant[];
  canEdit: boolean;
}) {
  const updateComment = useUpdateComment();
  const [editing, setEditing] = useState(false);
  const [body, setBody] = useState(comment.body ?? "");
  const [validationError, setValidationError] = useState<string>();

  const author =
    participants.find((p) => p.userId === comment.authorId)?.fullName ??
    (comment.authorId ? shortId(comment.authorId) : t("comment.author.unknown"));

  function startEditing() {
    setBody(comment.body ?? "");
    setValidationError(undefined);
    setEditing(true);
  }

  function save() {
    const trimmed = body.trim();
    if (!trimmed) {
      setValidationError(t("comment.composer.required"));
      return;
    }
    if (!comment.id) return;
    setValidationError(undefined);
    updateComment.mutate(
      { commentId: comment.id, body: trimmed },
      { onSuccess: () => setEditing(false) },
    );
  }

  const submitError =
    updateComment.isError && updateComment.error instanceof ApiError
      ? parseProblemDetail(updateComment.error.message)
      : updateComment.isError
        ? t("common.error")
        : undefined;

  return (
    <li
      className="bg-surface"
      style={{
        borderRadius: "var(--ob-card-radius)",
        border: "1px solid var(--ob-line)",
        padding: "var(--ob-space-11)",
      }}
    >
      <div className="flex items-baseline justify-between" style={{ gap: "var(--ob-space-8)" }}>
        <span
          className="text-ink"
          style={{ font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
        >
          {author}
        </span>
        <span
          className="text-text-faint"
          style={{ font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)" }}
        >
          {formatRelativeTime(comment.createdAt)}
          {comment.editedAt ? ` · ${t("comment.edited")}` : ""}
        </span>
      </div>

      {editing ? (
        <div className="flex flex-col" style={{ marginTop: "var(--ob-space-8)", gap: "var(--ob-space-8)" }}>
          <TextareaField
            label={t("comment.composer.label")}
            value={body}
            error={validationError ?? submitError}
            onChange={(event) => {
              setBody(event.target.value);
              if (validationError) setValidationError(undefined);
            }}
          />
          <div className="flex justify-end" style={{ gap: "var(--ob-space-8)" }}>
            <Button type="button" variant="small-secondary" onClick={() => setEditing(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              type="button"
              variant="small-primary"
              disabled={updateComment.isPending || body.trim().length === 0}
              onClick={save}
            >
              {t("common.save")}
            </Button>
          </div>
        </div>
      ) : (
        <p
          className="text-ink"
          style={{
            marginTop: "var(--ob-space-4)",
            font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
            whiteSpace: "pre-wrap",
          }}
        >
          {comment.body}
        </p>
      )}

      {canEdit && !editing && (
        <div className="flex justify-end" style={{ marginTop: "var(--ob-space-4)" }}>
          <Button type="button" variant="text-link" onClick={startEditing}>
            {t("comment.edit")}
          </Button>
        </div>
      )}
    </li>
  );
}

const DIVISIONS: { amount: number; unit: Intl.RelativeTimeFormatUnit }[] = [
  { amount: 60, unit: "seconds" },
  { amount: 60, unit: "minutes" },
  { amount: 24, unit: "hours" },
  { amount: 7, unit: "days" },
  { amount: 4.34524, unit: "weeks" },
  { amount: 12, unit: "months" },
  { amount: Number.POSITIVE_INFINITY, unit: "years" },
];

/** ISO timestamp -> "3 minutes ago"/"2 days ago" -- mono, per CLAUDE.md's human-text-vs-machine-value rule (a timestamp is machine-generated even once formatted for reading). */
function formatRelativeTime(iso?: string): string {
  if (!iso) return "—";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "—";

  let duration = (then - Date.now()) / 1000;
  const rtf = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  for (const division of DIVISIONS) {
    if (Math.abs(duration) < division.amount) {
      return rtf.format(Math.round(duration), division.unit);
    }
    duration /= division.amount;
  }
  return rtf.format(Math.round(duration), "years");
}
