-- Sub-project 4, Task 8: the document substrate. Documents are scoped per journey
-- (PRD section 10), never per account; document_case_link is the explicit
-- cross-journey share that rule requires. Visibility is TWO axes -- tier (how
-- broadly) and targeting (which group) -- per QA Q9 and its 2026-08-29 amendment.

CREATE TABLE document (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    case_id              uuid NOT NULL REFERENCES onboarding_case(id),
    -- Denormalised from the case. Safe because a case never changes customer, and
    -- it is what lets the portal audience predicate avoid a join on every read.
    customer_id          uuid NOT NULL REFERENCES customer(id),
    name                 text NOT NULL,
    category             text NOT NULL,
    visibility_tier      text NOT NULL,
    target_department_id uuid     NULL REFERENCES department(id),
    target_contact_label text     NULL,
    owner_contact_id     uuid     NULL REFERENCES customer_contact(id),
    expires_at           timestamptz,
    status               text NOT NULL,
    current_version_id   uuid     NULL,
    uploaded_by          uuid NOT NULL REFERENCES app_user(id),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT document_category_ck CHECK (category IN
        ('CONTRACT','AGREEMENT','NDA','COMPANY_REGISTRATION','TAX','KYC',
         'TECHNICAL','CERTIFICATE','INVOICE','OTHER')),
    CONSTRAINT document_tier_ck CHECK (visibility_tier IN
        ('COMPANY_SHARED','CONTACT_ONLY','SENSITIVE')),
    CONSTRAINT document_status_ck CHECK (status IN ('ACTIVE','RETIRED')),
    -- A CONTACT_ONLY document with no owner is visible to no contact at all,
    -- which is a SENSITIVE document wearing the wrong label. Refuse it here
    -- rather than let the audience predicate silently return nothing.
    CONSTRAINT document_owner_ck CHECK (
        visibility_tier <> 'CONTACT_ONLY' OR owner_contact_id IS NOT NULL)
);
CREATE INDEX document_tenant_case_idx     ON document (tenant_id, case_id);
CREATE INDEX document_tenant_customer_idx ON document (tenant_id, customer_id);
CREATE INDEX document_tenant_expiry_idx   ON document (tenant_id, expires_at)
    WHERE expires_at IS NOT NULL AND status = 'ACTIVE';

CREATE TABLE document_version (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    document_id   uuid NOT NULL REFERENCES document(id),
    version_no    int  NOT NULL,
    storage_key   text NOT NULL,
    size_bytes    bigint NOT NULL,
    content_type  text NOT NULL,
    -- Task 7's hardening ruling (spec §2.3/§7.6): the hex SHA-256 of the uploaded
    -- bytes, computed alongside the same stream at upload time. Integrity,
    -- duplicate detection, and provable version identity for sub-project 5.
    sha256        char(64) NOT NULL,
    review_status text NOT NULL,
    reviewed_by   uuid     NULL REFERENCES app_user(id),
    reviewed_at   timestamptz,
    review_note   text,
    uploaded_by   uuid NOT NULL REFERENCES app_user(id),
    uploaded_at   timestamptz NOT NULL,
    CONSTRAINT document_version_review_ck CHECK (review_status IN
        ('PENDING','APPROVED','REJECTED')),
    -- Two clients racing a new version resolve as a 409 rather than needing a row
    -- lock: appending a version derives no state, unlike CaseEngine.reconcile.
    CONSTRAINT document_version_no_uq UNIQUE (document_id, version_no)
);

-- Versions are immutable in their CONTENT, mutable in their REVIEW OUTCOME.
-- A blanket UPDATE revoke would block review, so immutability is a trigger.
CREATE OR REPLACE FUNCTION document_version_immutable() RETURNS trigger AS $$
BEGIN
    IF NEW.document_id  IS DISTINCT FROM OLD.document_id
    OR NEW.version_no   IS DISTINCT FROM OLD.version_no
    OR NEW.storage_key  IS DISTINCT FROM OLD.storage_key
    OR NEW.size_bytes   IS DISTINCT FROM OLD.size_bytes
    OR NEW.content_type IS DISTINCT FROM OLD.content_type
    OR NEW.sha256       IS DISTINCT FROM OLD.sha256
    OR NEW.uploaded_by  IS DISTINCT FROM OLD.uploaded_by
    OR NEW.uploaded_at  IS DISTINCT FROM OLD.uploaded_at THEN
        RAISE EXCEPTION 'document_version content is immutable once written';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_version_immutable_trg
    BEFORE UPDATE ON document_version
    FOR EACH ROW EXECUTE FUNCTION document_version_immutable();

CREATE TABLE document_share (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    document_id    uuid NOT NULL REFERENCES document(id),
    principal_type text NOT NULL,
    principal_id   uuid NOT NULL,
    granted_by     uuid NOT NULL REFERENCES app_user(id),
    granted_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    CONSTRAINT document_share_principal_ck CHECK (principal_type IN
        ('CONTACT','USER','DEPARTMENT'))
);
-- Revocation is a column, not a DELETE: who could once see a document is part of
-- the record, and DELETE is denied at the database anyway.
CREATE UNIQUE INDEX document_share_live_uq
    ON document_share (document_id, principal_type, principal_id)
    WHERE revoked_at IS NULL;

CREATE TABLE document_case_link (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    document_id uuid NOT NULL REFERENCES document(id),
    case_id     uuid NOT NULL REFERENCES onboarding_case(id),
    linked_by   uuid NOT NULL REFERENCES app_user(id),
    linked_at   timestamptz NOT NULL,
    revoked_at  timestamptz
);
CREATE UNIQUE INDEX document_case_link_live_uq
    ON document_case_link (document_id, case_id) WHERE revoked_at IS NULL;

CREATE TABLE document_request (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    case_id                 uuid NOT NULL REFERENCES onboarding_case(id),
    -- NULL is ad-hoc, non-null is requirement-instantiated. Exactly task's shape.
    requirement_id          uuid     NULL REFERENCES requirement(id),
    requested_of_contact_id uuid     NULL REFERENCES customer_contact(id),
    category                text NOT NULL,
    description             text,
    due_at                  timestamptz,
    requires_review         boolean NOT NULL DEFAULT false,
    status                  text NOT NULL,
    fulfilled_document_id   uuid     NULL REFERENCES document(id),
    requested_by            uuid NOT NULL REFERENCES app_user(id),
    requested_at            timestamptz NOT NULL,
    CONSTRAINT document_request_status_ck CHECK (status IN
        ('OPEN','FULFILLED','WITHDRAWN')),
    CONSTRAINT document_request_fulfilled_ck CHECK (
        status <> 'FULFILLED' OR fulfilled_document_id IS NOT NULL)
);
-- A requirement is satisfied by at most one document request, mirroring
-- task_requirement_uq.
CREATE UNIQUE INDEX document_request_requirement_uq
    ON document_request (requirement_id) WHERE requirement_id IS NOT NULL;
CREATE INDEX document_request_tenant_case_idx ON document_request (tenant_id, case_id, status);

-- QA Q9's amendment: customer-side targeting labels, set by internal staff.
ALTER TABLE customer_contact ADD COLUMN label text;

-- Design spec section 5.3: whether fulfilling this requirement needs review
-- before it satisfies. Nullable; NULL reads as false, so every existing frozen
-- row keeps its current meaning.
ALTER TABLE requirement_definition ADD COLUMN requires_review boolean;

SELECT enable_tenant_rls('document');
SELECT enable_tenant_rls('document_version');
SELECT enable_tenant_rls('document_share');
SELECT enable_tenant_rls('document_case_link');
SELECT enable_tenant_rls('document_request');

GRANT SELECT, INSERT, UPDATE ON document            TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON document_version    TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON document_share      TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON document_case_link  TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON document_request    TO onboarding_app;
