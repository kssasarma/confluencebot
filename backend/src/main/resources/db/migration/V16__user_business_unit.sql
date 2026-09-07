-- The business unit a user belongs to, set by an admin (typically at onboarding). Nullable: it is
-- optional and existing accounts predate the column. Used only for reporting — which BUs are
-- asking the most questions — never for access control.
ALTER TABLE users ADD COLUMN business_unit VARCHAR(255);
