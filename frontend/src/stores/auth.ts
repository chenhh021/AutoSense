import { computed, readonly, ref } from 'vue'
import axios from 'axios'
import { login as loginRequest, logout as logoutRequest, me } from '@/api/userController'
import {
  clearStoredSession,
  readStoredSession,
  saveStoredSession,
} from '@/utils/authStorage'

const storedSession = readStoredSession()
const token = ref(storedSession?.token ?? '')
const currentUser = ref<API.UserView | null>(storedSession?.user ?? null)
const initialized = ref(false)
let initialization: Promise<void> | null = null

const isAuthenticated = computed(() => Boolean(token.value && currentUser.value))
const isAdmin = computed(() => currentUser.value?.userRole?.toLowerCase() === 'admin')

function persistSession(nextToken: string, user: API.UserView) {
  token.value = nextToken
  currentUser.value = user
  saveStoredSession({ token: nextToken, user })
}

export function clearAuth() {
  token.value = ''
  currentUser.value = null
  clearStoredSession()
}

export async function initializeAuth() {
  if (initialized.value) return
  if (initialization) return initialization

  initialization = (async () => {
    if (!token.value) {
      initialized.value = true
      return
    }

    try {
      const user = (await me()) as unknown as API.UserView
      persistSession(token.value, user)
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 401) clearAuth()
      else if (!currentUser.value) clearAuth()
    } finally {
      initialized.value = true
      initialization = null
    }
  })()

  return initialization
}

export async function signIn(credentials: API.LoginRequest) {
  const response = (await loginRequest(credentials)) as unknown as API.LoginResponse
  if (!response.token || !response.user) {
    throw new Error('登录响应缺少令牌或用户信息')
  }
  persistSession(response.token, response.user)
  initialized.value = true
  return response.user
}

export async function signOut() {
  try {
    if (token.value) await logoutRequest()
  } finally {
    clearAuth()
  }
}

export const authState = {
  token: readonly(token),
  currentUser: readonly(currentUser),
  initialized: readonly(initialized),
  isAuthenticated,
  isAdmin,
}
