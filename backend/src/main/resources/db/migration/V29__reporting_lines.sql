-- Sub-project 6, spec 4.2. Who an escalation goes to: a user's manager, else their
-- department's head, else the administrators. Both nullable; neither is a business record of
-- its own, so no new table. Deactivation revokes nothing here -- the resolver skips inactive
-- people and falls through (spec 4.2), so deactivating someone can never break the chain.
ALTER TABLE app_user   ADD COLUMN manager_id   uuid NULL REFERENCES app_user(id);
ALTER TABLE app_user   ADD CONSTRAINT app_user_not_own_manager_ck CHECK (manager_id <> id);
ALTER TABLE department ADD COLUMN head_user_id uuid NULL REFERENCES app_user(id);
