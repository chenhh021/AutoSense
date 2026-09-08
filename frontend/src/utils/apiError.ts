import axios from 'axios'

// 错误响应为 BaseResponse<ErrorResponse>,顶层 message 即可读原因
export function getApiErrorMessage(error: unknown, fallback: string) {
  if (axios.isAxiosError<{ message?: string }>(error)) {
    return error.response?.data?.message || fallback
  }
  if (error instanceof Error && error.message) return error.message
  return fallback
}
