# Confluence RAG Chatbot

A production-quality Retrieval-Augmented Generation (RAG) chatbot that embeds your Confluence Server space into a pgvector database and answers questions from it using a locally-served LLM.

## Architecture

```
User Query
    │
    ▼
POST /api/chat  ·  POST /api/chat/stream
    │
    ▼
ChatService
    ├── HybridSearchService            — dense (HNSW cosine) + lexical, fused and re-ranked
    ├── PreferenceService.resolve()    — per-chat overrides on top of the account defaults
    ├── ConfluencePromptBuilder        — system rules + retrieved excerpts
    ├── LlmGateway                     — bulkhead + circuit breaker + retry around the model
    └── ChatSessionService.recordTurn()— question and answer persisted together, on success only
    │
    ▼
{ "answer": "...", "sources": [...], "followUpQuestions": [...] }   or a token stream

───────────────────────────────────────────────

POST /api/ingest/space
    │
    ▼
IngestionService
    ├── ConfluenceClient.fetchAllPages()    — paginated REST API (PAT auth)
    ├── Skip unchanged pages               — version check vs. confluence_pages table
    └── Per changed page:
        ├── JsoupStorageFormatParser       — strip XHTML/macros → clean text sections
        ├── HeadingAwareChunkingStrategy   — split at headings, max 1500 chars + 150 overlap
        ├── DELETE stale chunks            — WHERE metadata->>'page_id' = ?
        ├── VectorStore.add(documents)     — embed + store in confluence_chunks
        └── Upsert confluence_pages        — version tracking row
```

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1.1 |
| AI | Spring AI 2.0 |
| Vector store | PostgreSQL 17 + pgvector (HNSW index) |
| Schema migrations | Flyway |
| HTML parsing | Jsoup 1.17.2 |
| Embedding model | `snowflake-arctic-embed-l` (1024 dimensions) |
| LLM | `llama-4-17b-maverick` (via OpenAI-compatible endpoint) |
| Confluence | Server 7.19+ — REST API v1, Storage Format |
| Auth | Personal Access Token (PAT) |

## Prerequisites

- **Docker** and **Docker Compose** (for containerised setup)
- **Java 21** and **Maven 3.9+** (for local development only)
- A **Confluence Server** instance with a generated PAT
- An **OpenAI-compatible AI server** (e.g., [Ollama](https://ollama.com), [LM Studio](https://lmstudio.ai), [vLLM](https://github.com/vllm-project/vllm)) serving both an embedding and a chat model

## Quick Start — Docker Compose

### 1. Configure environment

```bash
cp .env.example .env
```

Edit `.env` with your real values (minimum required fields):

```dotenv
CONFLUENCE_BASE_URL=http://your-confluence-server:8090
CONFLUENCE_PAT=your-personal-access-token
CONFLUENCE_SPACE_KEY=MYSPACE

AI_BASE_URL=http://host.docker.internal:11434/v1   # Ollama on the Docker host
CHAT_MODEL=openai/llama-4-17b-maverick
EMBED_MODEL=openai/snowflake-arctic
```

> **Tip — Ollama on the same machine as Docker:** use `http://host.docker.internal:11434/v1` so the container can reach Ollama running on your host.

### 2. Start everything

```bash
docker compose up -d
```

This builds the app image, starts PostgreSQL, waits for it to be healthy, then starts the app. Flyway runs the three migrations automatically on first boot.

### 3. Verify

```bash
curl http://localhost:8080/actuator/health
# → {"status":"UP",...}
```

### 4. Ingest your Confluence space

```bash
curl -X POST http://localhost:8080/api/ingest/space
```

This fetches all pages in the configured space, embeds them, and stores the vectors. For a large space (500+ pages) this can take several minutes — progress is logged to stdout.

To re-ingest a single page after it changes:

```bash
curl -X POST http://localhost:8080/api/ingest/page/98765
```

### 5. Ask a question

Sign in first — every chat endpoint is authenticated:

```bash
TOKEN=$(curl -sX POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@confluencebot.local", "password": "Admin@1234"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -X POST http://localhost:8080/api/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question": "How do I reset my password?"}'
```

Add `"chatId": "<uuid>"` to record the exchange in a conversation, or stream it token by token
from `POST /api/chat/stream` (see **API Reference**).

Sample response:

```json
{
  "answer": "To reset your password:\n\n1. Navigate to the login page and click **Forgot password**.\n2. Enter your registered email address and click **Send reset link**.\n3. Open the email and click the reset link within 24 hours.\n4. Choose a new password that meets the complexity requirements.\n\nSources:\n- Password Reset Guide — http://confluence.example.com/display/IT/Password+Reset+Guide",
  "sources": [
    {
      "pageId": "131073",
      "title": "Password Reset Guide",
      "url": "http://confluence.example.com/display/IT/Password+Reset+Guide",
      "anchorUrl": "http://confluence.example.com/display/IT/Password+Reset+Guide#Self-Service-Reset",
      "spaceKey": "IT",
      "score": 0.91
    }
  ],
  "followUpQuestions": [
    "How do I enable two-factor authentication?"
  ],
  "chatId": null,
  "title": null
}
```

### 6. Stop

```bash
docker compose down          # keeps the pgdata volume
docker compose down -v       # also removes the database volume
```

---

## Local Development (without Docker)

### 1. Start PostgreSQL only

```bash
docker compose up -d postgres
```

### 2. Configure environment

```bash
cp .env.example .env
# Edit .env — DB_URL should stay as jdbc:postgresql://localhost:5432/confluencebot
```

### 3. Export env vars and run

```bash
export $(grep -v '^#' .env | xargs)
./mvnw spring-boot:run
```

Or load the `.env` file via your IDE's run configuration.

### 4. Run tests

```bash
./mvnw test
```

---

## Configuration Reference

All variables have sensible defaults where optional. Only the three starred variables are required.

| Variable | Required | Default | Description |
|---|---|---|---|
| `CONFLUENCE_BASE_URL` | ★ | — | Base URL of your Confluence Server (no trailing slash) |
| `CONFLUENCE_PAT` | ★ | — | Personal Access Token for Confluence REST API |
| `CONFLUENCE_SPACE_KEY` | ★ | — | Default space key to ingest (e.g. `MYSPACE`) |
| `CONFLUENCE_PAGE_FETCH_LIMIT` | | `250` | Pages per paginated API request |
| `CONFLUENCE_REQUEST_TIMEOUT_SECONDS` | | `30` | HTTP timeout for Confluence API calls |
| `AI_BASE_URL` | | `http://localhost:11434/v1` | OpenAI-compatible endpoint for both embedding and chat |
| `AI_API_KEY` | | `dummy` | API key (use `dummy` for local servers that don't require one) |
| `CHAT_MODEL` | | `openai/llama-4-17b-maverick` | Chat/completion model name |
| `EMBED_MODEL` | | `openai/snowflake-arctic` | Embedding model name (must produce 1024-dim vectors) |
| `CHAT_TEMPERATURE` | | `0.1` | LLM temperature (lower = more deterministic) |
| `CHAT_MAX_TOKENS` | | `2048` | Maximum tokens in LLM response |
| `CHAT_TOP_K` | | `5` | Number of vector search results to include in context |
| `CHAT_SIMILARITY_THRESHOLD` | | `0.70` | Minimum cosine similarity score for a chunk to be included |
| `DB_URL` | | `jdbc:postgresql://localhost:5432/confluencebot` | Full JDBC URL (Docker Compose overrides this automatically) |
| `DB_NAME` | | `confluencebot` | Database name (used by Docker Compose for Postgres init) |
| `DB_USERNAME` | | `confluencebot` | Database username |
| `DB_PASSWORD` | | `confluencebot` | Database password |
| `DB_PORT` | | `5432` | Host port Docker Compose binds for Postgres |
| `APP_PORT` | | `8080` | Host port Docker Compose binds for the app |
| `DB_POOL_MAX_SIZE` | | `10` | Hikari maximum connection pool size |
| `DB_POOL_MIN_IDLE` | | `2` | Hikari minimum idle connections |
| `LOG_LEVEL_APP` | | `INFO` | Log level for `com.kssasarma.confluencebot` |
| `LOG_LEVEL_SPRING_AI` | | `WARN` | Log level for Spring AI |
| `LOG_LEVEL_FLYWAY` | | `INFO` | Log level for Flyway |

---

## API Reference

### Ingest full space

```
POST /api/ingest/space
Content-Type: application/json

{}
```

Optional body to target a different space:

```json
{ "spaceKey": "OTHERSPACE" }
```

Response:

```json
{
  "status": "SUCCESS",
  "pagesProcessed": 47,
  "chunksStored": 312,
  "pagesSkipped": 3,
  "durationMs": 18420
}
```

`pagesSkipped` counts pages whose Confluence version number has not changed since the last ingestion — they are not re-embedded.

### Re-ingest single page

```
POST /api/ingest/page/{pageId}
```

Use the numeric page ID from the Confluence URL (`?pageId=98765`). Always re-embeds regardless of version.

### Chat

All chat and user endpoints require `Authorization: Bearer <access token>` (see **Authentication**).

```
POST /api/chat
Content-Type: application/json

{
  "question": "How do I configure the authentication module?",
  "chatId": "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c"
}
```

Response:

```json
{
  "answer": "The authentication module is configured via...",
  "sources": [
    {
      "pageId": "12345",
      "title": "Authentication Setup",
      "url": "http://confluence/...",
      "anchorUrl": "http://confluence/...#Configuration",
      "spaceKey": "ENG",
      "score": 0.87
    }
  ],
  "followUpQuestions": ["How do I rotate the signing key?"],
  "chatId": "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c",
  "title": "How do I configure the authentication module?"
}
```

**Validation:** `question` must be 3–1000 characters and not blank. `chatId` is optional and must be
a UUID; supply one to have the exchange recorded in the caller's transcript. The conversation is
created by the first question, so a chat the user opens and abandons never reaches the database.

### Chat (streamed)

```
POST /api/chat/stream
Accept: text/event-stream
Content-Type: application/json

{ "question": "How do I deploy?", "chatId": "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c" }
```

The same pipeline, delivered as server-sent events so the answer appears while it is being written.
Each event carries a JSON payload; the stream ends with the literal `[DONE]`. Disconnecting cancels
generation.

```
data:{"type":"sources","sources":[{"pageId":"12345","title":"Deployment Guide", ...}]}

data:{"type":"token","delta":"Deploy by running "}

data:{"type":"done","chatId":"0f2a…","title":"How do I deploy?","followUpQuestions":["…"]}

data:[DONE]
```

A `{"type":"error","message":"…"}` payload replaces `done` when the answer cannot be produced.

> Reverse proxies must not buffer this endpoint. The bundled nginx config already sets
> `proxy_buffering off` for `/api/`.

### Conversations

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/user/chats` | List the caller's conversations, pinned first |
| `POST` | `/api/user/chats` | Create one — idempotent: an untouched, untitled conversation is reused |
| `PATCH` | `/api/user/chats/{chatId}` | Rename or pin |
| `DELETE` | `/api/user/chats/{chatId}` | Delete the conversation and its transcript |
| `GET` | `/api/user/chats/{chatId}/messages` | Read the transcript, oldest turn first |
| `GET` | `/api/user/chats/{chatId}/preferences` | Per-conversation overrides (`null` = inherited) |
| `PUT` | `/api/user/chats/{chatId}/preferences` | Replace the overrides; `null` drops one |
| `GET` | `/api/user/preferences` | Account-wide preferences |
| `PATCH` | `/api/user/preferences` | Update them; omitted fields stay unchanged |

Untitled conversations that never received a message are cleaned up once they are older than
`CHAT_ABANDONED_SESSION_TTL` (1 hour by default).

### Authentication

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/auth/login` | Exchange credentials for an access/refresh token pair |
| `POST` | `/api/auth/refresh` | Rotate the pair; the presented refresh token is revoked |
| `POST` | `/api/auth/logout` | Revoke a refresh token |
| `GET` | `/api/auth/me` | Describe the signed-in user |
| `POST` | `/api/auth/change-password` | Change the password and re-issue tokens |

Failures are ProblemDetail responses, not 200s with an error field: bad credentials and expired
refresh tokens both return `401`.

### Health check

```
GET /actuator/health
```

### Error shape (RFC 9457 ProblemDetail)

```json
{
  "type": "urn:confluencebot:error:validation",
  "title": "Validation Failed",
  "status": 400,
  "detail": "query: Query must not be blank"
}
```

Error types:

| Type URN | HTTP status | Cause |
|---|---|---|
| `urn:confluencebot:error:validation` | 400 | Request body fails validation |
| `urn:confluencebot:error:invalid-request` | 400 | A well-formed request the server cannot accept |
| `urn:confluencebot:error:authentication` | 401 | Bad credentials, or an expired/revoked refresh token |
| `urn:confluencebot:error:access-denied` | 403 | Authenticated, but not allowed |
| `urn:confluencebot:error:not-found` | 404 | Unknown resource, or one owned by somebody else |
| `urn:confluencebot:error:llm-unavailable` | 503 | The model is unreachable, or the circuit breaker is open |
| `urn:confluencebot:error:confluence` | 502 | Confluence API unreachable or returned an error |
| `urn:confluencebot:error:ingestion` | 500 | Unrecoverable ingestion pipeline failure |
| `urn:confluencebot:error:internal` | 500 | Unexpected server error |

---

## Migrating to a Different Vector Store

The entire service layer depends only on the `VectorStore` interface. Switching to Qdrant requires two file changes — no business logic changes:

**pom.xml** — swap the starter:

```xml
<!-- Remove -->
<artifactId>spring-ai-starter-vector-store-pgvector</artifactId>

<!-- Add -->
<artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
```

**application.yml** — replace the `pgvector` block:

```yaml
spring:
  ai:
    vectorstore:
      qdrant:
        host: ${QDRANT_HOST:localhost}
        port: ${QDRANT_PORT:6334}
        collection-name: confluence_chunks
```

Re-run ingestion after switching — vectors cannot be copied between stores, but the `confluence_pages` version table means only changed pages will be re-embedded.

---

## Design Patterns

| Pattern | Applied in |
|---|---|
| **Strategy** | `ChunkingStrategy` — swap chunking algorithm without touching the ingestion pipeline |
| **Template Method** | `IngestionServiceImpl.processPage()` — fixed delete→parse→chunk→embed→track steps |
| **Repository** | `ConfluencePageRepository` — decouples data access from business logic |
| **Facade** | `IngestionService`, `ChatService` interfaces — single entry point hides pipeline complexity |
| **Builder** | `SearchRequest.builder()`, `ConfluencePageEntity.newPage()` |
| **Adapter** | `SpringAiLlmGateway` — the only class that knows which model library is in use |
| **Decorator** | `ResilientLlmGateway` — wraps the gateway in the bulkhead, circuit breaker and retry policy |
| **Observer** | `ChatStreamListener` — the pipeline pushes tokens without knowing they become server-sent events |
| **DTO / Assembler** | `*Response` records — no JPA entity is ever serialized, so nothing is lazily loaded outside its transaction |
| **Dependency Injection** | Constructor injection throughout — no field `@Autowired` |

---

## Project Structure

```
src/main/java/com/kssasarma/confluencebot/
├── ConfluenceChatbotApplication.java
├── api/                        REST controllers + DTOs
├── chat/                       RAG chat pipeline + prompt builder
├── config/                     @ConfigurationProperties records + RestClient bean
├── confluence/                 Confluence REST client + Jsoup parser
├── domain/                     ConfluencePageEntity (JPA)
├── exception/                  Exception types + GlobalExceptionHandler
├── ingestion/                  Ingestion pipeline + chunking strategy
├── rag/                        Hybrid search, rank fusion, re-ranking
├── repository/                 Spring Data JPA repositories
├── security/                   JWT filter, security config, user details
└── user/                       Users, conversations, transcripts, preferences (+ dto/)

src/main/resources/
├── application.yml
└── db/migration/
    ├── V1__create_vector_extension.sql
    ├── V2__create_confluence_chunks.sql   (HNSW index, functional index on page_id)
    ├── V3__create_confluence_pages.sql    (version-tracking table)
    ├── V4__add_fulltext_index.sql         (lexical half of hybrid search)
    ├── V5__create_ingestion_jobs.sql
    ├── V6__create_users.sql               (users, refresh tokens, default admin)
    ├── V7__create_user_preferences.sql    (preferences, chat sessions)
    ├── V8__fix_admin_password_hash.sql
    └── V9__create_chat_messages.sql       (transcripts + chat_preferences FK)
```
