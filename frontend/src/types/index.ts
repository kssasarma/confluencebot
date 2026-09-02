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

export interface UserPreferences {
  theme: 'light' | 'dark' | 'system'
  language: string
  responseStyle: 'concise' | 'balanced' | 'detailed'
  showSources: boolean
  showConfidence: boolean
}

export interface ChatPreferences {
  responseStyle?: string | null
  showSources?: boolean | null
  showConfidence?: boolean | null
  customPrompt?: string | null
}

export interface ChatSession {
  chatId: string
  title: string | null
  pinned: boolean
  messages: Message[]
}

export interface Message {
  localId?: string
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
  sources?: Source[]
  confidence?: number
}

export interface Source {
  title: string
  url: string
  pageId?: string
  spaceKey?: string
  excerpt?: string
}
