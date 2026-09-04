export interface AuthUser {
  userId: number
  email: string
  role: 'ADMIN' | 'ADMIN_READ_ONLY' | 'USER'
  mustChangePassword: boolean
}

export interface AuthResponse {
  userId?: number
  email?: string
  role?: string
  token?: string
  refreshToken?: string
  mustChangePassword?: boolean
  error?: string
}

/**
 * What the sign-in screen is told about single sign-on, before anyone has authenticated.
 *
 * Read from a public endpoint on purpose: whether there is a directory to sign in through is a
 * property of the deployment, not of the build, so baking it into the bundle would mean rebuilding
 * the frontend to turn SSO on.
 */
export interface SsoConfig {
  enabled: boolean
  /** Identifies which provider a session came from. Null when SSO is off. */
  providerId: string | null
  /** What to call the provider on the button. Null when SSO is off. */
  providerName: string | null
  /** Where to send the browser to begin. Null when SSO is off. */
  authorizationUrl: string | null
  /** Where to send it after signing out, to end the provider's session too. Often null. */
  logoutUrl: string | null
}

export type ResponseStyle = 'concise' | 'balanced' | 'detailed'

export interface UserPreferences {
  theme: 'light' | 'dark' | 'system'
  language: string
  responseStyle: ResponseStyle
  showSources: boolean
  showConfidence: boolean
}

export interface ChatPreferences {
  responseStyle?: ResponseStyle | null
  showSources?: boolean | null
  showConfidence?: boolean | null
  customPrompt?: string | null
}

/** The passage that caused a conversation to match a search. */
export interface SearchMatch {
  messageId: number | null
  /** Highlights are delimited with [[HL]]…[[/HL]] so they can be rendered without parsing HTML. */
  snippet: string | null
}

/** A conversation as the server knows it. Drafts live only in the browser until first use. */
export interface ChatSession {
  chatId: string
  title: string | null
  pinned: boolean
  messageCount: number
  createdAt?: string
  updatedAt?: string
  /** True while the title is still machine-derived and may improve itself. */
  titleGenerated?: boolean
  match?: SearchMatch | null
}

export interface ChatSessionPage {
  items: ChatSession[]
  /** Null on the last page. */
  nextCursor: string | null
}

/**
 * Why an answer failed, attached to the message it belongs to.
 *
 * Modelled on the message rather than pushed as a separate bubble: an answer that streamed halfway
 * and then lost its connection is one damaged answer, not a good answer followed by a bad one.
 */
export interface MessageError {
  message: string
  retryable: boolean
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** True while tokens are still arriving. */
  streaming?: boolean
  /** The user stopped generation, so this answer was not recorded. */
  stopped?: boolean
  /** Set when the answer could not be completed. The partial content, if any, is kept. */
  error?: MessageError
  sources?: Source[]
  followUpQuestions?: string[]
  /** Resolves each [n] marker in `content` to the page it cites. */
  citations?: Citation[]
  /**
   * How well the question matched the indexed documentation, 0–1.
   *
   * Retrieval quality — deliberately not "how likely the answer is to be correct". See
   * `ConfidenceBadge`, which labels it accordingly.
   */
  confidence?: number | null
  createdAt?: string
}

export interface Citation {
  /** The number inside the brackets in the answer text; 1-based. */
  marker: number
  pageId: string
}

export interface Source {
  pageId?: string
  title: string
  url: string
  /** Deep link to the matching section; falls back to the page URL. */
  anchorUrl?: string
  spaceKey?: string
  score?: number
  sectionHeading?: string | null
  /** A short extract of the matching passage, so a citation can be judged without opening it. */
  excerpt?: string | null
}
