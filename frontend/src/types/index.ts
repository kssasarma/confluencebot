export interface AuthUser {
  userId: number
  email: string
  role: 'ADMIN' | 'USER'
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

/** A conversation as the server knows it. Drafts live only in the browser until first use. */
export interface ChatSession {
  chatId: string
  title: string | null
  pinned: boolean
  messageCount: number
  createdAt?: string
  updatedAt?: string
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** True while tokens are still arriving. */
  streaming?: boolean
  /** The user stopped generation, so this answer was not recorded. */
  stopped?: boolean
  /** The answer could not be produced; content holds the reason. */
  failed?: boolean
  sources?: Source[]
  followUpQuestions?: string[]
  createdAt?: string
}

export interface Source {
  pageId?: string
  title: string
  url: string
  /** Deep link to the matching section; falls back to the page URL. */
  anchorUrl?: string
  spaceKey?: string
  score?: number
}
