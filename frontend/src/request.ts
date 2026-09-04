import axios from 'axios'
import {
  AUTH_UNAUTHORIZED_EVENT,
  clearStoredSession,
  getStoredToken,
} from '@/utils/authStorage'

// 后端服务地址，可通过 .env 中的 VITE_API_BASE_URL 覆盖
// 导出供 SSE 等 fetch 原生请求复用
export const BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8180'

const request = axios.create({
  baseURL: BASE_URL,
  timeout: 60000,
})

request.interceptors.request.use((config) => {
  const token = getStoredToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 响应拦截器：直接返回响应数据，统一处理错误
request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      clearStoredSession()
      window.dispatchEvent(new Event(AUTH_UNAUTHORIZED_EVENT))
    }
    return Promise.reject(error)
  },
)

export default request
