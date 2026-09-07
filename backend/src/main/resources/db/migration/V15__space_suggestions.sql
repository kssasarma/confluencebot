-- Suggested questions shown on the empty-conversation welcome screen, generated from what was
-- actually ingested rather than hard-coded in the frontend. Regenerated wholesale every time a
-- space is (re-)ingested — see SuggestionGenerationService — so old rows for a space are replaced,
-- never accumulated.
CREATE TABLE space_suggestions (
    id         BIGSERIAL    PRIMARY KEY,
    space_key  VARCHAR(255) NOT NULL,
    question   VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_space_suggestions_space_key ON space_suggestions(space_key);
