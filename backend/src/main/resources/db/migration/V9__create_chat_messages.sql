-- Chat turns are persisted so a conversation survives a page reload and so the sidebar can
-- tell an empty draft apart from a real conversation.

CREATE TABLE chat_messages (
    id              BIGSERIAL    PRIMARY KEY,
    chat_session_id BIGINT       NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    sequence_no     INTEGER      NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT         NOT NULL,
    sources_json    TEXT,
    follow_ups_json TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chat_messages_sequence UNIQUE (chat_session_id, sequence_no)
);

CREATE INDEX idx_chat_messages_session ON chat_messages(chat_session_id, sequence_no);

-- Per-chat preferences were orphaned whenever a chat session was deleted: chat_id carried no
-- foreign key. Drop the strays, then let the database enforce the relationship.
DELETE FROM chat_preferences p
 WHERE NOT EXISTS (SELECT 1 FROM chat_sessions s WHERE s.chat_id = p.chat_id);

ALTER TABLE chat_preferences
    ADD CONSTRAINT fk_chat_preferences_session
    FOREIGN KEY (chat_id) REFERENCES chat_sessions (chat_id) ON DELETE CASCADE;
