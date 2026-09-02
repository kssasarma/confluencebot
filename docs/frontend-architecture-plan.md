# ConfluenceBot Frontend — Architecture & Delivery Plan

> Benchmarked against `doc-ai-system/frontend`. Every claim below was verified against the
> source of both repositories; file:line references are to the current `master` of each.

---

## 0. Verdict up front

| | doc-ai-system | confluencebot |
|---|---|---|
| Frontend LOC (`src/**/*.{ts,tsx,css}`) | **17,003** | **2,697** |
| Components | 82 | 20 |
| UI primitives (`components/ui/`) | 22 | 3 |
| Service modules | 30 | 6 |
| Accessibility attributes in the whole app | widespread (`label` required at type level on every icon button) | **2** (`role="switch"`, `aria-checked`, both on one toggle) |
| Error boundaries | 2 (global + per-conversation) | 0 |
| Test tooling | vitest + testing-library + v8 coverage | none |
| Routing | react-router, 12 lazy routes | none — all state in `App.tsx` `useState` |

The gap is **not** "we need more components". It is four missing *systems*, and all ten of your
observations fall out of one of them:

1. **A design system** — semantic tokens, elevation, motion, z-index, focus. (Items 4, 5)
2. **A primitive layer** — one `Modal`, one `Menu`, one `Toast`, one `Button`. (Item 5)
3. **Stream & state discipline** — how tokens, completion, and failure are modelled. (Items 6, 9)
4. **An answer-presentation model** — what an answer *is*: prose + citations + confidence +
   next steps, not just a markdown blob. (Items 7, 8, 9, 10)

**The single most important finding for sequencing:** five of your ten items
(2, 7, 8, 9, 10) cannot be fixed in the frontend alone. The backend does not currently emit a
confidence value, does not emit machine-linkable citation markers, and derives chat titles by
string-clipping rather than summarising. A frontend-only plan stalls at Phase 3. The backend
work is scoped in §6 and is small — roughly 400 lines of Java.

---

## 1. Reference inventory — what doc-ai-system actually does

### 1.1 Design system (`index.css`, `tailwind.config.js`)

- **Semantic token pairs**, not raw colours: `background / surface / surface-hover / border /
  foreground / muted / muted-foreground`, plus `primary`, `accent`, and four status colours
  (`success`, `warning`, `danger`, `info`) — **each with its own `-foreground`**, so text on a
  status chip is never guessed.
- `color-scheme: light|dark` on `<html>` so native form controls, scrollbars and
  `caret-color` follow the theme.
- **A global `:focus-visible` ring** (`ring-2 ring-primary ring-offset-2 ring-offset-background`) —
  one rule, every focusable element in the app.
- Theme-aware scrollbars (`scrollbar-color` + `::-webkit-scrollbar-thumb` with `background-clip:
  content-box` for the inset look).
- `@media (prefers-reduced-motion: reduce)` collapsing durations to `0.01ms` — deliberately *not*
  `animation: none`, so `onAnimationEnd` callbacks still fire and exit-then-unmount doesn't hang.
- A `.hljs` code theme wired to the same tokens, so code blocks are theme-correct without importing
  a second stylesheet.
- Elevation scale: `shadow-soft`, `shadow-elevated`, `shadow-elevated-dark`.
- Motion scale: 6 keyframes + `cubic-bezier(0.16, 1, 0.3, 1)` as a named `expo-out` easing.

### 1.2 Primitive layer (`components/ui/`)

`Modal` (Headless UI `Dialog` — real focus trap, Escape, click-outside, `aria-modal`,
`DialogTitle` wiring, sticky header, `ModalBody`/`ModalFooter`), `ConfirmDialog` (a **promise-based**
`confirm()` replacing `window.confirm`), `Toast` (context + `AnimatePresence` + auto-dismiss),
`Menu` (Headless UI, keyboard-navigable, `placement="top"` for bottom-pinned triggers),
`Button`/`IconButton` (`cva` variants; **`label` is required at the type level on `IconButton`**, so
an icon-only button without an accessible name will not compile), `Badge`, `Tooltip`, `Skeleton`
(+`Text`/`Card`/`Row`), `EmptyState`, `Select`, `Combobox`, `Input`, `Textarea`, `PasswordInput`,
`PageHeader`, `ThemeToggle`, `AccountMenu`, `DateRangePicker`, `ChartTooltip`.

### 1.3 Chat surface

- **`MessageItem` (560 lines)** — confidence badge (3 buckets), expandable sources panel with
  excerpt + relevance % + product badge + open-document + annotations, multi-hop reasoning chain,
  related questions, copy, upvote (→ "Team Verified" at ≥3), bookmark, add-to-collection,
  escalate-to-human (shown only when confidence < 0.6), regenerate with a style menu, thumbs
  feedback, edit-and-resend, timestamp.
- **`MarkdownContent`** — GFM + syntax highlighting via a **hand-written `rehypeHighlightSubset`**
  built on `lowlight` directly, specifically because `rehype-highlight` references its
  `common` (~35-language) bundle at module scope and so cannot be tree-shaken. Per-block copy button.
- **`MessageList`** — virtualised with dynamic measurement; autoscroll fires **only when the reader
  is already within 96px of the bottom**; a "Jump to latest" pill otherwise.
- **`MessageInput`** — per-chat draft persistence in `sessionStorage`, `/` to focus from anywhere,
  `ArrowUp` in an empty composer to edit the last message, `Escape` to stop generation, character
  counter that appears at 90% of the limit.
- **`ChatArea`** — inline title editing, export (md/json), share, an `ErrorBoundary` **keyed by
  `chatId`** so a crash in one conversation doesn't persist after switching away.

### 1.4 Streaming discipline (`App.tsx:105-190`) — the part worth studying closest

Two non-obvious correctness details:

1. **rAF coalescing.** Tokens accumulate into a local `accumulated` string; a `requestAnimationFrame`
   flush pushes at most one `setState` per frame. A raw per-token `setState` repaints far more often
   than the eye resolves and janks long answers.
2. **The `streamEnded` guard.** The final token delta and the `done` event routinely land in the
   *same* synchronous SSE read, so the rAF scheduled for that last flush is still pending when
   `onDone` runs. Without the guard it fires on the next paint and sets `isStreaming: true` again —
   and nothing ever flips it back. The symptom is a permanently blinking cursor on a finished
   answer. **ConfluenceBot will hit this exact bug the moment it adds rAF coalescing, so implement
   the guard at the same time.**

---

## 2. Where ConfluenceBot is already *better* — do not regress these

This matters as much as the gap list. Four of these are places where copying doc-ai-system would be
a downgrade.

| # | ConfluenceBot | doc-ai-system | Why ConfluenceBot wins |
|---|---|---|---|
| 1 | One `apiFetch` with **single-flight** 401 refresh + replay (`services/http.ts`) | Monkey-patches `window.fetch` globally (`lib/httpInterceptors.ts`) | The global patch fires `notifyAuthExpired()` on *any* 401 — including a wrong-password login attempt. It also mutates a global, which breaks under test and double-wraps under HMR. |
| 2 | **Draft-chat model** — the conversation is created by the first *question* | `createSession()` on every "New Chat" click | Clicking "New Chat" ten times in doc-ai leaves ten empty sessions. ConfluenceBot's `isOnEmptyDraft` guard is the right design. |
| 3 | SSE → plain-JSON **fallback** when a proxy won't pass `text/event-stream` (`chatService.ts:83-90`) | No fallback | Real deployments sit behind proxies that buffer or reject event streams. |
| 4 | Proactive token renewal *before* expiry (`AuthContext.tsx:73-88`) | Reactive only | An idle tab doesn't need a failed request to notice. |
| 5 | `messagesByChat: Record<chatId, Message[]>` | `sessions[]` array holding messages, cloned on **every token** | doc-ai's `setSessions(prev => prev.map(...))` per token clones the whole session list per token. |
| 6 | **Tri-state** per-chat overrides (Default / On / Off) | Flat booleans | Genuinely better UX — `null` meaning "inherit my account default" is modelled explicitly. |
| 7 | Throw-based `ApiError` carrying HTTP status + RFC 9457 `detail` | `{ success, data, error: string }` envelope | doc-ai's envelope discards the status code and forces every call site to branch. ConfluenceBot's shape drops straight into react-query. |

**Rule for the whole programme: adopt doc-ai-system's *presentation* layer; keep ConfluenceBot's
*transport* layer.**

---

## 3. Your ten items — diagnosis and fix

### 3.1 Finding chat history

**Symptom.** No way to find an old conversation.

**Root cause.** `Layout/Sidebar.tsx` renders `sessions` as two flat lists (Pinned / Recent) with no
search field, no date grouping, and no pagination. `fetchSessions()` (`chatService.ts:7`) returns
every conversation in one call. There is no way to search *inside* transcripts at all — the backend
has no such endpoint.

**Fix.**
- *Frontend:* a filter input at the top of the sidebar (client-side, matches title); date grouping
  (Today / Yesterday / Previous 7 days / Previous 30 days / older, by `updatedAt`); a `⌘K`/`Ctrl+K`
  command palette (`cmdk`) listing recent chats, actions and — once the backend supports it —
  full-text hits with the matching snippet; virtualised list once the count passes ~150.
- *Backend:* `GET /api/user/chats?q=&cursor=&limit=` — cursor pagination plus a Postgres full-text
  search over `chat_message.content` returning `chatId`, `messageId`, and a `ts_headline` snippet.

**Owner:** FE + BE · **Phase:** 4

---

### 3.2 Summarising the chat title

**Symptom.** Titles are the raw question, truncated.

**Root cause.** `ChatSessionServiceImpl.deriveTitle()` (line 208) is
`question.strip().lines().findFirst()` clipped to `TITLE_MAX_LENGTH` + `…`. It is a substring, not a
summary. *(Note: doc-ai-system is no better — `useChatSessions.ts` does
`message.content.slice(0, 50)`. This is a place to **exceed** the reference, not match it.)*

**Fix.**
- *Backend:* after the first turn completes, fire an async, cheap LLM call ("Summarise this exchange
  as a 3–6 word title, no punctuation, no quotes") and `PATCH` the session title. Keep the clipped
  question as the immediate optimistic value so the sidebar is never blank; the summary replaces it
  a second later. Guard with a timeout and fall back silently. Never block the answer stream on it.
- *Frontend:* the `done` event already carries `title`; add a `title` push so a late summary can
  update a sidebar row that is already on screen. Animate the swap (`framer-motion` `layout`) so it
  reads as a refinement rather than a glitch. Inline rename in the chat header, not only in the
  sidebar hover menu.

**Owner:** BE (primary) + FE · **Phase:** 5

---

### 3.3 Resizing components

**Symptom.** Nothing can be resized.

**Root cause.** `Sidebar.tsx:70` is a binary `isCollapsed ? 'w-14' : 'w-64'`. The composer's
auto-grow is capped at a hard-coded `200px` (`ChatInput.tsx:31`). There is no mobile layout at all —
the 256px sidebar is always present, so on a 375px phone the chat column is 119px wide.

*doc-ai-system does not solve this either* (it animates 64↔320px). **This is a second place to
exceed the reference.**

**Fix.**
- A `<ResizableSidebar>` with a drag handle: `pointerdown`/`pointermove` with pointer capture,
  `min 220px / max 480px`, width persisted to `localStorage`, **double-click to reset**, and
  `role="separator"` + `aria-valuenow`/`aria-valuemin`/`aria-valuemax` with `ArrowLeft`/`ArrowRight`
  moving it in 16px steps (keyboard-accessible resizing is the part everyone skips).
- Respect `prefers-reduced-motion` — no width transition while dragging.
- Below `md`: the sidebar becomes an overlay drawer with a scrim and a floating hamburger.
- A resizable right-hand **Sources panel** (see §3.8), same mechanism, same persistence.
- Composer: grow to `min(40vh, 320px)`, then scroll.

**Owner:** FE · **Phase:** 3

---

### 3.4 Colour contrast

**Symptom.** "Colour contrasts."

**Root cause — measured, not assumed.** I computed WCAG 2.1 ratios for the full palette. The result
is more specific than "the palette is bad": most of it is fine, and **four pairs actually fail**.

| Pair | ConfluenceBot | doc-ai-system | Verdict |
|---|---|---|---|
| `success` on `surface` (light) | **3.18** | 5.02 | ❌ fails AA (4.5) |
| `primary` on `surface` (dark) | **3.85** | 4.77 | ❌ fails AA — this is link and accent text |
| `danger` on `surface` (dark) | **4.47** | 6.34 | ❌ fails AA by a hair — used for failed answers |
| `muted-foreground` on `muted` (both) | **3.92 / 3.88** | 4.39 / 5.43 | ❌ fails AA — the active sidebar row's metadata line |
| `foreground` on `background` | 15.45 / 15.15 | 17.19 / 16.27 | ✅ fine |
| white on `primary` (user bubble, dark) | 4.31 | 3.68 | ⚠️ ConfluenceBot is *better* here |

**The larger problem is not any single ratio — it is that there is no global focus indicator.**
doc-ai-system has one `:focus-visible` rule in `index.css` covering the entire app. ConfluenceBot has
`focus:ring-2` on `Button` and `Input` only; the sidebar rows, source chips, follow-up buttons,
modal close buttons and theme selectors have **no visible focus state at all**. For a
keyboard user that is a harder failure than any of the four ratios above.

**Fix.**
- Retune the four failing values (darken light-mode `success`, lighten dark-mode `primary`/`danger`,
  raise `muted-foreground`), then lock them in with a contrast test that runs in CI.
- Add the global `:focus-visible` ring with `ring-offset-background`.
- Add `color-scheme` to `<html>` so native controls follow the theme.
- Add `accent`, `warning`, `info` and a `-foreground` for every status colour — today a status chip
  has to guess its text colour, which is how these drifts start.
- Retire `text-[9px]` / `text-[10px]` (`MessageBubble.tsx:100`, `Sidebar.tsx:262`); floor at 11px.
- **Do not** claim `border` vs `surface` (1.33) is a failure: WCAG's 3:1 non-text rule targets
  meaningful boundaries and focus indicators, not decorative dividers — doc-ai-system sits at the
  same 1.24/1.35. What *does* need fixing is that ConfluenceBot uses that border as the **only**
  affordance on interactive chips; pair it with a background or elevation change.

**Owner:** FE · **Phase:** 1

---

### 3.5 Pop-ups to overlay all of those

**Symptom.** Overlays don't behave like overlays.

**Root cause.** There is no `Modal` primitive. Three separate components hand-roll
`<div className="fixed inset-0 z-50 …">`:
`Admin/AdminPage.tsx:20`, `Settings/ChatPreferencesPanel.tsx:37`,
`Settings/UserPreferencesPage.tsx:40`. Consequently, across the entire app:

- **No `createPortal`** — modals render inside the flex layout and inherit stacking contexts and
  `overflow` from ancestors.
- **No focus trap.** Tab walks straight out of the dialog into the page behind it.
- **No Escape-to-close, no click-outside-to-close.**
- **No `aria-modal`, no `role="dialog"`, no labelled title, no focus restore** on close.
- **No scroll lock** — the page scrolls behind the overlay.
- **All three are `z-50`**, so stacking is decided by DOM order. `App.tsx` can render
  `UserPreferencesPage` and `ChatPreferencesPanel` simultaneously; which one wins is accidental.

**Fix.** One `Modal` primitive on Headless UI `Dialog` (focus trap, Escape, `aria-modal`, portal and
focus restore come free), with `ModalBody`/`ModalFooter`, plus a **documented z-index scale** as
tokens: `--z-dropdown: 30 / --z-drawer: 40 / --z-modal: 50 / --z-popover: 60 / --z-toast: 100 /
--z-palette: 200`. Add promise-based `useConfirm()` and a `Toast` provider on the same layer. Route
Admin and Settings as real pages rather than overlays (§4).

**Owner:** FE · **Phase:** 2

---

### 3.6 Seeing a network error *after* responding with data

**Symptom.** A complete, correct answer renders — then a red error bubble appears beneath it.

**Root cause — confirmed, and it is a genuine bug.** Two defects compound:

1. `consumeEventStream` (`chatService.ts:127`) keeps reading after the `done` event is dispatched
   (`:164`). The backend sends `done`, then the `[DONE]` sentinel, then completes. If the transport
   dies in that window — proxy idle timeout, load-balancer cap, laptop sleep, tab throttling —
   `reader.read()` rejects and the error propagates up through `streamChatMessage` into
   `useChatController.sendMessage`'s catch (`:184`).
2. `reportFailure` (`:121`) sees that partial content already exists and therefore **appends a
   second, `failed: true` assistant message** rather than annotating the existing one.

The result is the worst possible framing: the answer is complete, it was persisted server-side
(`ChatServiceImpl.completeQuietly` already ran), reloading the page shows it perfectly — and the UI
is telling the user it failed. **The UI is lying.**

**Fix.**
- Track completion in the stream consumer: once `done` has been dispatched, mark the stream
  *settled* and **swallow subsequent transport errors** — the answer is already whole. Return
  cleanly instead of throwing.
- Cancel the reader in a `finally` so a thrown event handler doesn't leak the body stream
  (`handleEvent` currently throws from inside the read loop at `:171` with no `reader.cancel()`).
- Change the failure model: errors attach to the message they belong to (`message.error`) and render
  as an inline strip **under** the partial answer with a **Retry** button, never as a new bubble.
- Distinguish the cases in the copy: `offline` (`navigator.onLine`) → "You're offline";
  `AbortError` → "Stopped"; `503` → "The assistant is busy"; `after-done` → nothing at all.
- Add a global offline banner and re-arm on `window.online`.

**Owner:** FE · **Phase:** 2 — *this is the highest-severity item on your list and the cheapest to
fix.*

---

### 3.7 The message has references, but not hyperlinks

**Symptom.** Answers say `[Password Reset Guide]` in plain text.

**Root cause.** The prompt asks for exactly that. `ConfluencePromptBuilder.systemMessage()` rule 4:

> "Cite the source page title in square brackets when stating a specific fact."

`[Password Reset Guide]` is not markdown link syntax — markdown needs `[text](url)` — so
`react-markdown` renders it as literal text. The frontend has the URL (it's in `sources[].anchorUrl`)
and never joins the two.

Matching by *title* would also be fragile: titles contain brackets, colons and duplicates.

**Fix — change both ends.**
- *Backend:* the user message **already numbers the excerpts** `[1]`, `[2]`, … in
  `ConfluencePromptBuilder.userMessage()`. Change rule 4 to *"cite the excerpt number in square
  brackets, e.g. `[2]`"*. Numeric markers are unambiguous, stable, and trivially mappable.
  Emit a `citations` array on the `done` event mapping marker index → `pageId` so the join is
  explicit rather than positional.
- *Frontend:* a small `remark` plugin that rewrites `[n]` text nodes into a `<Citation n={n}>`
  node resolved against the message's `sources`. Renders as a superscript pill that links to
  `anchorUrl`, shows a hover card (page title › section, space key, relevance, excerpt), and
  scroll-highlights the matching row in the sources panel on click. Unresolvable markers fall back
  to plain text — never a dead link.

**Owner:** BE + FE · **Phase:** 5

---

### 3.8 Citations are there, but not up to the mark

**Symptom.** The source chips are weak.

**Root cause.** `MessageBubble.SourcesList` (`:94-119`) is a flat row of pills: title truncated at
`max-w-[140px]`, relevance hidden inside the `title` attribute (invisible on touch, unreadable by
screen readers), no excerpt, no section, no space key, no ordering signal, no way to expand. Compare
doc-ai-system's `MessageItem.tsx:390-455`: a collapsible panel with excerpt, relevance %, product
badge, open-document and per-chunk annotations.

The backend already returns everything needed and it is being thrown away: `SourceReference` carries
`pageId`, `title`, `url`, `anchorUrl`, `spaceKey` and `score`, and `ChatServiceImpl.extractSources()`
already dedupes to the best chunk per page in re-ranked order.

**Fix.**
- A **`n sources` disclosure button** in the message meta row, expanding to a panel: rank, title,
  `space › section` breadcrumb, excerpt (3-line clamp), a relevance bar with the numeral, and
  "Open in Confluence" → `anchorUrl`.
- Inline citation pills (§3.7) cross-highlight the panel row.
- An optional **resizable right-hand sources rail** for wide viewports, pinned across the
  conversation (see §3.3).
- *Backend:* add `excerpt` (the matching chunk text, ~240 chars) and `sectionHeading` to
  `SourceReference` — currently the section is baked into `anchorUrl` and cannot be displayed
  separately.
- Respect `showSources` — see §4.

**Owner:** FE + small BE · **Phase:** 5

---

### 3.9 No follow-up questions

**Symptom.** Follow-ups rarely appear.

**Root cause — three separate causes, which is why it looks like the feature is missing.**

1. **Last-message-only.** `ChatArea.tsx:31-32` computes
   `[...messages].reverse().find(m => m.role === 'assistant')` and renders follow-ups **once**, at
   the bottom of the transcript. Every historical turn's follow-ups are fetched, stored, and never
   shown.
2. **Fragile parsing.** The model must emit the literal marker `---FOLLOW-UP-QUESTIONS---`.
   `StreamingAnswerAssembler` handles the marker straddling chunk boundaries correctly (that part is
   good work), but if the model reformats it at all — bolds it, changes the dashes, wraps it in a
   code fence — the marker is missed, the block is rendered *as part of the answer*, and follow-ups
   come back empty.
3. **They're outside the message.** Rendered as a sibling in `ChatArea`, so they don't move with the
   message, don't survive scrolling context, and vanish the instant the next question is sent.

**Fix.**
- Render follow-ups **per assistant message**, inside `MessageBubble`, like doc-ai-system's
  `relatedQuestions` block (`MessageItem.tsx:498-518`).
- Make parsing tolerant: match `/^\s*[-*#\s]*FOLLOW[- ]?UP[- ]?QUESTIONS[-\s:]*$/im` and strip
  surrounding fences/bold, so a reformatted marker still parses. Keep the streaming hold-back logic.
- Belt and braces: ask for **structured output** (a JSON tail or a tool call) rather than a text
  marker, where the configured model supports it. This is the durable fix.
- Add a "regenerate suggestions" affordance when the list is empty.

**Owner:** BE (parsing) + FE (placement) · **Phase:** 4

---

### 3.10 No confidence score

**Symptom.** There is no confidence anywhere.

**Root cause.** The backend **computes** it and then throws it away.
`ChatServiceImpl.prepare()` calculates `maxSimilarity` across retrieved chunks and derives
`boolean lowConfidence = maxSimilarity < minSimilarityThreshold` (default `0.4`) — but that boolean
is only used to *nudge the prompt*. Neither the number nor the flag appears in `ChatApiResponse`,
in `ChatStreamEvent.Done`, or in `ChatMessageResponse`. The frontend `Message` type has no
`confidence` field, and `MessageBubble` renders nothing.

Meanwhile `UserPreferences.showConfidence` exists, is persisted, and is offered in **two** settings
screens (`UserPreferencesPage.tsx:111`, `ChatPreferencesPanel.tsx:79`) — a toggle for a feature that
does not exist.

**Fix.**
- *Backend:* add `confidence` (0–1) to `ChatApiResponse`, `ChatStreamEvent.Done`, `ChatMessage`
  (persisted) and `ChatMessageResponse`. Compute it from more than one signal — top similarity,
  the mean of the top-k, how many distinct pages agreed, and whether the answer actually cited
  anything — rather than `maxSimilarity` alone.
- *Frontend:* a three-bucket `ConfidenceBadge` (≥0.75 High / ≥0.5 Medium / else Low) in the message
  meta row, **gated on the effective `showConfidence` preference**, with a tooltip explaining the
  inputs.

**⚠️ One thing I'd push back on.** doc-ai-system labels this "High confidence" / "Low confidence",
which reads as *"the assistant is confident this answer is correct."* It isn't measuring that. It is
measuring **retrieval quality** — how well the question matched the indexed documentation. An answer
can be confidently retrieved and still wrong, and a user who trusts a green "High confidence" badge
on a hallucination has been actively misled by the UI.

I'd label it honestly — **"Strong source match" / "Partial match" / "Weak match"** — with a tooltip
reading *"How well your question matched the indexed Confluence pages. It does not verify the
answer."* Same badge, same colours, same code; it just stops making a claim the system cannot
support. For an internal documentation assistant that people will act on, this is worth the
five-word difference.

**Owner:** BE + FE · **Phase:** 5

---

## 4. What else is broken — not on your list

Ranked by severity × cheapness.

| # | Issue | Evidence | Impact |
|---|---|---|---|
| 1 | **The display preferences are write-only.** `showSources` / `showConfidence` are saved by two settings screens and read by **nothing**. `grep` finds them only inside the two forms — never in `MessageBubble` or any renderer. | `UserPreferencesPage.tsx:105-112`, `ChatPreferencesPanel.tsx:74-80` | Two settings screens that do nothing. Erodes trust in every other setting. |
| 2 | **No error boundary anywhere.** | 0 occurrences | One malformed markdown table or bad payload white-screens the whole SPA with no recovery but a manual refresh. |
| 3 | **Full re-render on every streamed token.** `appendToken` (`useChatController.ts:71`) does a `setState` per SSE token, re-rendering every message in the conversation. | `:171` | Long answers jank; gets worse the longer the conversation. Needs rAF coalescing + `React.memo` — and the `streamEnded` guard from §1.4. |
| 4 | **Autoscroll fights the reader.** `bottomRef.scrollIntoView({behavior:'smooth'})` runs on every `messages` change — i.e. every token — with no "am I at the bottom?" check. | `ChatArea.tsx:27-29` | Scroll up to reread and you are yanked back to the bottom, smoothly, dozens of times per second. |
| 5 | **Invalid DOM nesting in code blocks.** The `code` component override returns a `<pre>`; react-markdown has already wrapped it in a `<pre>`. Result: `<pre><pre><code>`. | `MessageBubble.tsx:43-45` | Invalid HTML, React warnings, broken styling. Override `pre`, not `code` (as doc-ai-system does). |
| 6 | **No routing.** Everything is `useState` in `App.tsx`. | `App.tsx:31-36` | No deep links to a conversation, refresh loses your place, browser Back does nothing, Admin/Settings are modals rather than pages. |
| 7 | **Errors are silently swallowed.** Four `catch {}` / `.catch(() => {})` on preference load and save; `fetchSessions().catch(() => {})` makes an outage look identical to "no chats yet". | `UserPreferencesPage.tsx:20,35`, `ChatPreferencesPanel.tsx:19,30`, `useChatController.ts:60` | Optimistic rename/pin/delete never roll back and never report. The UI lies (again). |
| 8 | **No toast/notification layer.** | — | Nothing can report a background failure. Prerequisite for fixing #7. |
| 9 | **No mobile layout.** 256px sidebar always present. | `Sidebar.tsx:70` | On a 375px phone the chat column is ~119px. |
| 10 | **No code highlighting, no copy button on code.** | `MessageBubble.tsx:43` | This is a *documentation* assistant — answers are full of config and commands. |
| 11 | **Streaming code-fence flicker.** No `closeUnterminatedCodeFence` equivalent. | — | Mid-fence content renders as inline code, then snaps into a block. Visible flicker on every code answer. |
| 12 | **No draft persistence.** | `App.tsx:32` (`draft` is component state) | Type a long question, switch chats, lose it. |
| 13 | **No message actions at all** — no copy, regenerate, feedback, export, share. | — | No feedback signal reaches the backend; no way to improve retrieval. |
| 14 | **No `aria-live` on the streaming answer.** | — | A screen-reader user gets no indication an answer is arriving. |
| 15 | **No loading skeletons**, no empty/error states beyond a bare `<p>`. | `Sidebar.tsx:127` | — |
| 16 | **No tests, no test tooling.** | `package.json` | doc-ai-system ships vitest + testing-library + v8 coverage. |
| 17 | **Nothing is lazy-loaded.** | `App.tsx:1-11` | Admin (371 lines) ships to every user including those who can't open it. |
| 18 | **Inconsistent suggestion behaviour.** Home suggestions *fill* the composer (`onSelectPrompt={onDraftChange}`); follow-ups *send immediately* (`onAsk`). | `ChatArea.tsx:42,48` | Two identical-looking affordances behave differently. |

---

## 5. Target architecture

```
src/
├── app/            router, providers, shell, route guards
├── design/         tokens.css · theme.ts · motion.ts · z-index scale
├── components/
│   ├── ui/         Button IconButton Badge Modal ConfirmDialog Toast Menu Tooltip
│   │               Skeleton EmptyState Input Textarea Select Switch Popover
│   │               ResizeHandle ErrorBoundary
│   ├── chat/       ChatView MessageList MessageBubble MessageActions
│   │               MarkdownContent Citation CitationHoverCard SourcesPanel
│   │               ConfidenceBadge FollowUps Composer StreamCursor
│   ├── sidebar/    Sidebar ResizableSidebar SessionSearch SessionGroup SessionItem
│   ├── palette/    CommandPalette (⌘K)
│   ├── settings/   PreferencesPage ChatPreferencesPanel ThemeSection
│   └── admin/      AdminLayout UsersTab IngestionTab JobsTab
├── hooks/          useChatController useSessions useResizable usePersistentState
│                   useHotkeys useOnlineStatus useAutoScroll
├── services/       http.ts (KEEP) chatService authService preferenceService adminService
├── lib/            cn markdown/remarkCitations streamParser errors format
└── test/           setup.ts + colocated *.test.tsx
```

**Key decisions**

- **Keep `services/http.ts` exactly as it is.** It is the best module in the codebase.
- **Keep `useChatController`'s `messagesByChat` shape.** Add rAF coalescing and the settled-stream
  guard; do not restructure.
- **Add `react-router`** — `/`, `/chat/:chatId`, `/settings`, `/admin/*`. Lazy-load `/admin`.
- **Add `@tanstack/react-query`** for sessions/preferences/admin only. **Not** for the SSE stream —
  that stays imperative in `useChatController`.
- **One animation system.** doc-ai-system runs three concurrently (framer-motion + Headless UI
  transitions + Tailwind keyframes). Pick framer-motion for components, Tailwind keyframes for
  incidental CSS, and accept Headless UI's own transitions only inside `Modal`/`Menu` (fighting
  `AnimatePresence` against `Dialog`'s portal unmount is a known bug source — doc-ai-system's own
  `Modal.tsx` comment says exactly this, and it's right).

---

## 6. Backend work required (Spring Boot)

Small, and it unblocks half the list. Roughly 400 lines.

| # | Change | Files | Unblocks |
|---|---|---|---|
| B1 | Add `confidence` (0–1) to `ChatApiResponse`, `ChatStreamEvent.Done`, `ChatMessage` (persisted), `ChatMessageResponse`. Compute from top similarity + top-k mean + distinct-page agreement + citation presence. | `ChatServiceImpl`, `ChatApiResponse`, `ChatStreamEvent`, `ChatMessage`, `ChatMessagePayloadCodec`, + Flyway migration | 3.10 |
| B2 | Switch citation rule to **numeric markers** (`[1]`, `[2]`) matching the excerpt numbering already emitted in `userMessage()`; add a `citations` array to `Done` mapping marker → `pageId`. | `ConfluencePromptBuilder`, `ChatStreamEvent` | 3.7 |
| B3 | Add `excerpt` (~240 chars of the matching chunk) and `sectionHeading` to `SourceReference`. | `SourceReference`, `ChatServiceImpl.extractSources` | 3.8 |
| B4 | Async LLM title summarisation after the first turn; `PATCH` the session; fall back to the current clipped question. | `ChatSessionServiceImpl`, `LlmGateway` | 3.2 |
| B5 | Tolerant follow-up marker parsing; prefer structured output where the model supports it. | `StreamingAnswerAssembler`, `ConfluencePromptBuilder` | 3.9 |
| B6 | `GET /api/user/chats?q=&cursor=&limit=` — cursor pagination + Postgres FTS over `chat_message.content` with `ts_headline` snippets. | `UserController`, `ChatSessionService`, `ChatMessageRepository`, + GIN index migration | 3.1 |
| B7 | Send an SSE **heartbeat comment** (`:ping`) every ~15s so proxies don't idle-close a slow generation — the other half of the §3.6 problem. | `SseChatStreamAdapter` | 3.6 |

**Sequencing note:** B1–B3 are additive and backward-compatible (new optional fields). They can ship
before any frontend work and be consumed later. Do these first so the frontend is never blocked.

---

## 7. Delivery plan

Each phase is independently shippable and leaves `master` green.

### Phase 0 — Scaffolding *(0.5 day)*
Vitest + testing-library + jsdom + v8 coverage; `test`/`test:watch` scripts; a CI job running
`lint`, `tsc -b`, `test`, `build`. **Acceptance:** one passing smoke test; CI green.

### Phase 1 — Design system *(1.5 days)* → items **4**
Expand tokens (`accent`, `warning`, `info`, `-foreground` pairs, elevation, motion, z-index scale);
retune the four failing contrast pairs; global `:focus-visible`; `color-scheme`; theme-aware
scrollbars; `prefers-reduced-motion`; `@tailwindcss/typography`; hljs theme bound to tokens.
**Acceptance:** an automated contrast test asserts every documented token pair ≥ 4.5:1 (text) /
3:1 (UI) in both themes and **fails CI** otherwise. Every interactive element shows a visible focus
ring under keyboard navigation.

### Phase 2 — Primitives, overlays, error handling *(3 days)* → items **5, 6**
`Modal` (Headless UI) + `ConfirmDialog` + `Toast` + `Menu` + `Tooltip` + `Skeleton` + `EmptyState` +
`IconButton` (label required at type level) + `ErrorBoundary` (global + per-conversation). Migrate
the three hand-rolled overlays. **Fix the post-completion stream error** (§3.6): settle-on-`done`,
`reader.cancel()` in `finally`, inline per-message error with Retry, offline detection. Replace every
swallowed `catch` with a toast.
**Acceptance:** killing the network *after* the `done` event produces **no error UI**; killing it
*before* produces an inline error with a working Retry. Tab-cycling inside a modal never escapes it;
Escape closes it; focus returns to the trigger.

### Phase 3 — Layout, routing, resizing *(2.5 days)* → item **3**
`react-router` (`/`, `/chat/:chatId`, `/settings`, `/admin/*`), lazy routes, `AppShell`,
`ResizableSidebar` with persisted width + keyboard resize, mobile drawer + scrim, `useDocumentTitle`.
**Acceptance:** a conversation URL is shareable and survives reload; the sidebar resizes by drag
*and* by arrow keys with correct `aria-valuenow`; the app is usable at 375px.

### Phase 4 — Chat surface *(4 days)* → items **1, 9**
`MarkdownContent` (GFM + curated-subset highlighting + per-block copy + unterminated-fence handling);
`MessageBubble` rewrite (fix the `<pre><pre>` nesting) with a meta row: copy, regenerate, feedback,
timestamp; **per-message follow-ups**; rAF-coalesced streaming + `streamEnded` guard + `React.memo`;
at-bottom-aware autoscroll + "Jump to latest"; `aria-live="polite"` on the streaming answer;
composer upgrades (per-chat draft persistence, `/` focus, `ArrowUp` to edit last, `Escape` to stop,
character counter); sidebar search + date grouping; `⌘K` command palette.
**Acceptance:** a 300-message conversation scrolls at 60fps while streaming; scrolling up during a
stream is never overridden; follow-ups appear under **every** assistant message.

### Phase 5 — Answers as first-class objects *(3 days, needs B1–B4)* → items **2, 7, 8, 10**
`remarkCitations` plugin → clickable `[n]` pills with hover cards; `SourcesPanel` (rank, breadcrumb,
excerpt, relevance bar, open-in-Confluence) + optional resizable rail; `ConfidenceBadge` with honest
labelling (§3.10); **wire `showSources` / `showConfidence` to the renderer**; live title-summary
update with an animated swap.
**Acceptance:** every `[n]` in an answer is a working link to the right anchor; both display
preferences visibly change the transcript; a title refines itself within ~2s of the first answer.

### Phase 6 — Polish & hardening *(2 days)*
Skeletons everywhere; empty/error states; `AccountMenu`; `ThemeToggle`; onboarding hint; bundle
budget check; axe-core accessibility sweep; visual QA in both themes at 375 / 768 / 1440.
**Acceptance:** zero axe violations on the chat route; initial JS bundle ≤ 250KB gzipped.

**Total ≈ 16.5 engineering days**, ordered so that severity-first (Phase 2) lands in week one.

---

## 8. Where I would *not* copy doc-ai-system

You asked me to think twice rather than agree. Four places where "just like doc-ai-system" is the
wrong instruction:

1. **Don't virtualise the message list yet.** doc-ai-system's `MessageList` combines
   `@tanstack/react-virtual` dynamic measurement, `framer-motion` `layout` animations, streaming
   content that changes height every frame, and expandable source panels. That is four things
   fighting over the same scroll offset, and it's a well-known source of scroll jumps. With
   `estimateSize: () => 160` against messages that can be 1,000px tall, the measurement pass thrashes.
   Start with `React.memo` + `content-visibility: auto`; virtualise only if real transcripts exceed
   ~200 messages, and then only *behind the same test suite*.
2. **Don't adopt the `{ success, data, error }` envelope.** It discards HTTP status, forces every
   call site to branch, and doesn't compose with react-query. ConfluenceBot's throwing `ApiError`
   is better. Keep it.
3. **Don't monkey-patch `window.fetch`.** See §2.1 — it logs the user out on a failed login attempt.
   ConfluenceBot's `apiFetch` already does this correctly.
4. **Don't ship doc-ai-system's dependency list.** It carries `recharts` and `pptxgenjs` for admin
   charts and a PowerPoint export that ConfluenceBot has no equivalent of. Budget deliberately (§9).

And one framing correction, restated because it matters most: **"confidence" as implemented in
doc-ai-system measures retrieval quality, not answer correctness.** Copying the label copies a claim
the system cannot back. See §3.10.

---

## 9. Dependency budget

| Package | Why | Verdict |
|---|---|---|
| `@headlessui/react` | Modal/Menu focus trap, a11y, Escape, portal | ✅ Add — the alternative is hand-rolling a focus trap |
| `framer-motion` | Shared motion language; `MotionConfig reducedMotion="user"` | ✅ Add |
| `@tanstack/react-query` | Sessions, preferences, admin caching | ✅ Add |
| `cmdk` | ⌘K palette | ✅ Add |
| `class-variance-authority` | Typed variants on primitives | ✅ Add |
| `@tailwindcss/typography` | Prose styling for answers | ✅ Add |
| `lowlight` + curated `highlight.js` languages | Code highlighting — **register a subset directly**, do not use `rehype-highlight` (it references its ~35-language `common` bundle at module scope and cannot be tree-shaken; doc-ai-system's `rehypeHighlightSubset.ts` is the workaround and is worth copying verbatim) | ✅ Add |
| `vitest` + `@testing-library/*` + `jsdom` | Tests | ✅ Add (dev) |
| `@tanstack/react-virtual` | Virtualisation | ⏸ Defer — see §8.1 |
| `recharts`, `pptxgenjs` | doc-ai admin charts / PPTX export | ❌ Skip — no equivalent feature |

Target: **≤ 250KB gzipped** initial JS with `/admin` and the palette lazy-loaded.

---

## 10. Testing & acceptance

- **Unit** — `streamParser` (including *the post-`done` transport failure*), `remarkCitations`
  marker resolution, tolerant follow-up parsing, `relativeTime`, `useResizable` clamping.
- **Component** — `MessageBubble` (streaming / stopped / failed / with citations / with sources),
  `Modal` (focus trap, Escape, restore), `Toast`, `SourcesPanel`, `ConfidenceBadge` gated on
  preference.
- **Contract** — a fixture set of SSE transcripts (happy, no-context, error-mid-stream,
  **error-after-done**, aborted, non-streaming fallback) replayed against the real consumer.
- **Contrast** — the automated token-pair assertion from Phase 1, run in CI.
- **Accessibility** — `axe-core` on the chat and settings routes; a manual keyboard-only pass
  (send a message, open and dismiss a modal, resize the sidebar, open the palette) each phase.

**Definition of done for the programme:** every one of your ten items has a named test that fails
against today's `master` and passes after the change.
