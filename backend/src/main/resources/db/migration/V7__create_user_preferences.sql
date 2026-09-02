CREATE TABLE user_preferences (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE UNIQUE,
    theme           VARCHAR(20)  NOT NULL DEFAULT 'system',
    language        VARCHAR(10)  NOT NULL DEFAULT 'en',
    response_style  VARCHAR(20)  NOT NULL DEFAULT 'balanced',
    show_sources    BOOLEAN      NOT NULL DEFAULT TRUE,
    show_confidence BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE chat_preferences (
    id              BIGSERIAL PRIMARY KEY,
    chat_id         VARCHAR(255) NOT NULL UNIQUE,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    response_style  VARCHAR(20),
    show_sources    BOOLEAN,
    show_confidence BOOLEAN,
    custom_prompt   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE chat_sessions (
    id          BIGSERIAL    PRIMARY KEY,
    chat_id     VARCHAR(255) NOT NULL UNIQUE,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       VARCHAR(500),
    pinned      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_sessions_user ON chat_sessions(user_id);
CREATE INDEX idx_chat_preferences_user ON chat_preferences(user_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
