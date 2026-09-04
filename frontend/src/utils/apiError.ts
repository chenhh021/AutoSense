import axios from 'axios'

export function getApiErrorMessage(error: unknown, fallback: string) {
  if (axios.isAxiosError<API.ErrorResponse>(error)) {
    return error.response?.data?.message || fallback
  }
  if (error instanceof Error && error.message) return error.message
  return fallback
}
