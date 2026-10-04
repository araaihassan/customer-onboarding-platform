-- Sub-project 6, spec 4.3. Whether an open customer document request pauses this stage's SLA
-- clock. Existing stages -- published ones included -- read true; ADD COLUMN ... DEFAULT fires no
-- row trigger, so V12's published-version freeze is unaffected (V19's precedent).
ALTER TABLE stage ADD COLUMN pauses_on_customer boolean NOT NULL DEFAULT true;
