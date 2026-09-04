const AUTH_STORAGE_KEY = 'autosense.auth'

export const AUTH_UNAUTHORIZED_EVENT = 'autosense:unauthorized'

export interface StoredAuthSession {
  token: string
  user: API.UserView
}

export function readStoredSession(): StoredAuthSession | null {
  const raw = window.localStorage.getItem(AUTH_STORAGE_KEY)
  if (!raw) return null

  try {
    const session = JSON.parse(raw) as Partial<StoredAuthSession>
    if (typeof session.token !== 'string' || !session.token || !session.user) {
      clearStoredSession()
      return null
    }
    return session as StoredAuthSession
  } catch {
    clearStoredSession()
    return null
  }
}

export function getStoredToken() {
  return readStoredSession()?.token ?? ''
}

export function saveStoredSession(session: StoredAuthSession) {
  window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(session))
}

export function clearStoredSession() {
  window.localStorage.removeItem(AUTH_STORAGE_KEY)
}
