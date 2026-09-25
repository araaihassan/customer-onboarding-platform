-- Sub-project 5, Task 2 (spec section 4.1): a SIGNATURE requirement fixes the record
-- mode (QA Q13's "template setting") and the default name of the agreement that
-- case creation instantiates for it. Typed nullable columns per kind, the
-- document_category / requires_review precedent -- never a params jsonb.
ALTER TABLE requirement_definition ADD COLUMN agreement_record_mode varchar(24);
ALTER TABLE requirement_definition ADD COLUMN agreement_name        varchar(200);
ALTER TABLE requirement_definition ADD CONSTRAINT requirement_definition_agreement_mode_ck
    CHECK (agreement_record_mode IS NULL
           OR agreement_record_mode IN ('FILE_BACKED','STRUCTURED_PLUS_FILE','STRUCTURED_ONLY'));
