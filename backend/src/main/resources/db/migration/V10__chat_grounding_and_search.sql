-- Answers become first-class objects: they carry how well retrieval matched, which bracketed
-- marker resolves to which page, and a title that can be refined after the fact. Conversations
-- become searchable rather than merely listable.

-- ── Grounding metadata on the assistant turn ────────────────────────────────
-- Nullable on purpose: every row written before this migration predates the scoring pipeline,
-- and a backfilled zero would be indistinguishable from a genuinely weak match.
ALTER TABLE chat_messages
    ADD COLUMN confidence     DOUBLE PRECISION,
    ADD COLUMN citations_json TEXT;

COMMENT ON COLUMN chat_messages.confidence IS
    'Retrieval confidence 0-1: how well the question matched the indexed documentation. '
    'NOT a claim that the answer is correct.';

ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_confidence_range
    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1));

-- ── Titles that can be improved without overwriting the user ────────────────
-- Existing rows default to FALSE so the async summariser can never rewrite a title a user may
-- have chosen by hand: we cannot tell them apart retrospectively, so we leave them alone.
ALTER TABLE chat_sessions
    ADD COLUMN title_generated BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN chat_sessions.title_generated IS
    'TRUE while the title is machine-derived and may be replaced by a better summary. '
    'Set to FALSE the moment a user renames the conversation.';

-- ── Full-text search over transcripts ───────────────────────────────────────
-- A functional GIN index rather than a generated tsvector column: it keeps all DDL in Flyway,
-- costs no extra row width, and plainto_tsquery matches it directly.
CREATE INDEX IF NOT EXISTS idx_chat_messages_content_fts
    ON chat_messages
    USING GIN (to_tsvector('english', content));

-- Keyset pagination reads conversations in (pinned, updated_at, id) order. Without this the
-- sidebar's first page is a sort of the user's entire history on every keystroke.
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_keyset
    ON chat_sessions (user_id, pinned DESC, updated_at DESC, id DESC);
