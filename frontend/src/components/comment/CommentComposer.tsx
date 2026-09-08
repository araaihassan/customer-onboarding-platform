"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { TextareaField } from "@/components/ui/Field";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { useAddComment, type CommentResourceType } from "@/lib/api/comments";
import { t } from "@/lib/i18n";

/**
 * The comment input, mounted at the bottom of `CommentThread` (design spec
 * §8.3). Body text is what a person wrote, so Instrument Sans -- the
 * textarea inherits `TextareaField`'s own UI font.
 *
 * The one requirement the brief calls out as mattering most: a failed post
 * must NOT lose the typed draft. `body` is local state cleared ONLY inside
 * `onSuccess` -- never optimistically before the request resolves, and never
 * in a `finally`/unconditional path that would also run on failure. An empty
 * or whitespace-only body is refused before `mutate` is ever called (the
 * submit control is disabled, and `submit()` itself returns early with an
 * inline error next to the field), so the backend's own `@NotBlank` is never
 * exercised from here.
 */
export function CommentComposer({
  caseId,
  resourceType,
  resourceId,
}: {
  caseId: string;
  resourceType: CommentResourceType;
  resourceId: string;
}) {
  const addComment = useAddComment();
  const [body, setBody] = useState("");
  const [validationError, setValidationError] = useState<string>();

  function submit() {
    const trimmed = body.trim();
    if (!trimmed) {
      setValidationError(t("comment.composer.required"));
      return;
    }
    setValidationError(undefined);
    addComment.mutate(
      { caseId, resourceType, resourceId, body: trimmed },
      { onSuccess: () => setBody("") },
    );
  }

  const submitError =
    addComment.isError && addComment.error instanceof ApiError
      ? parseProblemDetail(addComment.error.message)
      : addComment.isError
        ? t("common.error")
        : undefined;

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      <TextareaField
        label={t("comment.composer.label")}
        value={body}
        error={validationError ?? submitError}
        onChange={(event) => {
          setBody(event.target.value);
          if (validationError) setValidationError(undefined);
        }}
      />
      <div className="flex justify-end">
        <Button
          type="button"
          variant="small-secondary"
          disabled={addComment.isPending || body.trim().length === 0}
          onClick={submit}
        >
          {t("comment.composer.submit")}
        </Button>
      </div>
    </div>
  );
}
