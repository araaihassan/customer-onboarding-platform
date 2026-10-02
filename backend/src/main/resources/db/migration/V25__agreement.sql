-- Sub-project 5, Task 3 (spec section 4): the agreement module's five tables.
-- One live agreement per SIGNATURE requirement (cancel-and-replace keeps it so);
-- versions, reviews and signatures are append-only evidence.

CREATE TABLE agreement (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid NOT NULL REFERENCES tenant(id),
    case_id               uuid NOT NULL REFERENCES onboarding_case(id),
    requirement_id        uuid NOT NULL REFERENCES requirement(id),
    -- Denormalised from the case (a case never changes customer) so the portal
    -- audience predicate needs no join -- the document.customer_id precedent.
    customer_id           uuid NOT NULL REFERENCES customer(id),
    name                  varchar(200) NOT NULL,
    record_mode           varchar(24) NOT NULL,
    status                varchar(24) NOT NULL,
    effective_date        date,
    expires_at            date,
    renewal_date          date,
    notice_period_days    int,
    owner_user_id         uuid NOT NULL REFERENCES app_user(id),
    document_id           uuid REFERENCES document(id),
    last_edited_by        uuid NOT NULL REFERENCES app_user(id),
    replaces_agreement_id uuid REFERENCES agreement(id),
    cancel_reason         text,
    signed_at             timestamptz,
    signature_provider    varchar(16) NOT NULL,
    provider_envelope_id  text,
    lock_version          bigint NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT agreement_mode_ck CHECK (record_mode IN ('FILE_BACKED','STRUCTURED_PLUS_FILE','STRUCTURED_ONLY')),
    -- EXPIRED is deliberately absent: it is derived on read (spec section 5.7).
    CONSTRAINT agreement_status_ck CHECK (status IN
        ('DRAFT','UNDER_REVIEW','APPROVED','SENT','AWAITING_SIGNATURE','SIGNED','CANCELLED')),
    CONSTRAINT agreement_cancel_reason_ck CHECK (status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason) <> '')),
    CONSTRAINT agreement_signed_at_ck CHECK ((status = 'SIGNED') = (signed_at IS NOT NULL)),
    CONSTRAINT agreement_notice_ck CHECK (notice_period_days IS NULL OR notice_period_days >= 0),
    CONSTRAINT agreement_provider_ck CHECK (signature_provider IN ('MANUAL'))
);
CREATE UNIQUE INDEX agreement_live_per_requirement_uq ON agreement (requirement_id) WHERE status <> 'CANCELLED';
CREATE INDEX agreement_tenant_case_idx     ON agreement (tenant_id, case_id);
CREATE INDEX agreement_tenant_customer_idx ON agreement (tenant_id, customer_id, status);
CREATE INDEX agreement_tenant_expiry_idx   ON agreement (tenant_id, expires_at) WHERE status = 'SIGNED' AND expires_at IS NOT NULL;

CREATE TABLE agreement_signatory (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    agreement_id uuid NOT NULL REFERENCES agreement(id),
    kind         varchar(16) NOT NULL,
    contact_id   uuid REFERENCES customer_contact(id),
    user_id      uuid REFERENCES app_user(id),
    display_role varchar(120) NOT NULL,
    sort_order   int NOT NULL,
    CONSTRAINT agreement_signatory_kind_ck CHECK (kind IN ('CONTACT','INTERNAL')),
    CONSTRAINT agreement_signatory_party_ck CHECK (
        (kind = 'CONTACT'  AND contact_id IS NOT NULL AND user_id IS NULL) OR
        (kind = 'INTERNAL' AND user_id IS NOT NULL AND contact_id IS NULL))
);
CREATE UNIQUE INDEX agreement_signatory_contact_uq ON agreement_signatory (agreement_id, contact_id) WHERE contact_id IS NOT NULL;
CREATE UNIQUE INDEX agreement_signatory_user_uq    ON agreement_signatory (agreement_id, user_id)    WHERE user_id IS NOT NULL;

CREATE TABLE agreement_version (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    agreement_id        uuid NOT NULL REFERENCES agreement(id),
    version_number      int  NOT NULL,
    -- Copied so the file CHECK below needs no join and the row never needs an UPDATE.
    record_mode         varchar(24) NOT NULL,
    submitted_by        uuid NOT NULL REFERENCES app_user(id),
    submitted_at        timestamptz NOT NULL,
    last_edited_by      uuid NOT NULL REFERENCES app_user(id),
    structured_snapshot jsonb NOT NULL,
    document_version_id uuid REFERENCES document_version(id),
    document_sha256     char(64),
    content_sha256      char(64) NOT NULL,
    UNIQUE (agreement_id, version_number),
    CONSTRAINT agreement_version_file_ck CHECK (
        (record_mode = 'STRUCTURED_ONLY' AND document_version_id IS NULL AND document_sha256 IS NULL) OR
        (record_mode <> 'STRUCTURED_ONLY' AND document_version_id IS NOT NULL AND document_sha256 IS NOT NULL))
);

CREATE TABLE agreement_version_review (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    agreement_version_id uuid NOT NULL UNIQUE REFERENCES agreement_version(id),
    decision             varchar(8) NOT NULL,
    reviewer_id          uuid NOT NULL REFERENCES app_user(id),
    reviewed_at          timestamptz NOT NULL,
    reason               text,
    CONSTRAINT agreement_review_decision_ck CHECK (decision IN ('APPROVE','REJECT')),
    CONSTRAINT agreement_review_reason_ck CHECK (decision <> 'REJECT' OR (reason IS NOT NULL AND btrim(reason) <> ''))
);

CREATE TABLE agreement_signature (
    id                                uuid PRIMARY KEY,
    tenant_id                         uuid NOT NULL REFERENCES tenant(id),
    agreement_id                      uuid NOT NULL REFERENCES agreement(id),
    signatory_id                      uuid NOT NULL REFERENCES agreement_signatory(id),
    agreement_version_id              uuid NOT NULL REFERENCES agreement_version(id),
    signed_content_sha256             char(64) NOT NULL,
    signed_on                         date NOT NULL,
    method                            varchar(200) NOT NULL,
    recorded_by                       uuid NOT NULL REFERENCES app_user(id),
    recorded_at                       timestamptz NOT NULL,
    countersigned_document_version_id uuid REFERENCES document_version(id),
    UNIQUE (agreement_id, signatory_id)
);

SELECT enable_tenant_rls('agreement');
SELECT enable_tenant_rls('agreement_signatory');
SELECT enable_tenant_rls('agreement_version');
SELECT enable_tenant_rls('agreement_version_review');
SELECT enable_tenant_rls('agreement_signature');

GRANT SELECT, INSERT, UPDATE ON agreement           TO onboarding_app;
-- DELETE granted deliberately: PUT .../signatories replaces a DRAFT agreement's
-- list, and a signatory row is not a business record -- the frozen copy a
-- signature is proven against lives in agreement_version.structured_snapshot.
-- AgreementService only deletes signatories of an agreement in DRAFT.
GRANT SELECT, INSERT, UPDATE, DELETE ON agreement_signatory TO onboarding_app;
-- Append-only evidence: the plan_revision_item / audit_event shape.
GRANT SELECT, INSERT ON agreement_version        TO onboarding_app;
GRANT SELECT, INSERT ON agreement_version_review TO onboarding_app;
GRANT SELECT, INSERT ON agreement_signature      TO onboarding_app;
