-- Sub-project 5, final whole-branch review (Importants 2 and 3): two facts the
-- document module must own about an agreement's file, without ever naming an
-- agreement type (ModuleBoundaryTest.noDocumentDependencyOnAgreement).
--
-- agreement_owned: the file belongs to an agreement and is mutable ONLY through
-- document.AgreementFiles (spec section 7). The general document write paths --
-- DocumentService.addVersion/patch/retire, DocumentSharingService.share/link --
-- refuse it. The category alone cannot mark this: AGREEMENT was already an
-- ordinary category users could pick before sub-project 5, and such documents
-- must stay fully editable, hence DEFAULT false.
--
-- portal_min_version_no: once set, a PORTAL actor may open only versions numbered
-- at or above it. AgreementFiles.retier sets it to the document's current version
-- at the moment it is shared with the customer (send), so the rejected and
-- superseded internal drafts before the sent version are never served to the
-- portal (decision 5), while a countersigned copy appended later stays visible.
-- NULL means "no restriction" -- every ordinary document.
--
-- document already has tenant_id, its RLS policy and FORCE ROW LEVEL SECURITY
-- (V23); additive columns need nothing more. No trigger on document_version is
-- touched.
ALTER TABLE document
    ADD COLUMN agreement_owned boolean NOT NULL DEFAULT false,
    ADD COLUMN portal_min_version_no integer;

ALTER TABLE document
    ADD CONSTRAINT document_portal_min_version_no_ck
        CHECK (portal_min_version_no IS NULL OR portal_min_version_no >= 1);

-- Backfill: every document an agreement already points at was created by
-- AgreementFiles.createOwnedDocument (the only writer of agreement.document_id).
UPDATE document d
SET agreement_owned = true
FROM agreement a
WHERE a.document_id = d.id;

-- An already-sent agreement's file (a database that ran V25 before this one)
-- keeps the restriction the send would now apply: the sent version onward.
UPDATE document d
SET portal_min_version_no = v.document_version_no
FROM (
    SELECT a.document_id, dv.version_no AS document_version_no
    FROM agreement a
    JOIN LATERAL (
        SELECT av.document_version_id
        FROM agreement_version av
        WHERE av.agreement_id = a.id
        ORDER BY av.version_number DESC
        LIMIT 1
    ) latest ON true
    JOIN document_version dv ON dv.id = latest.document_version_id
    WHERE a.document_id IS NOT NULL
      AND a.status IN ('SENT', 'AWAITING_SIGNATURE', 'SIGNED')
) v
WHERE d.id = v.document_id;
