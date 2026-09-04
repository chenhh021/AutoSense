import { BASE_URL } from '@/request'
import { AUTH_UNAUTHORIZED_EVENT, getStoredToken } from '@/utils/authStorage'

/** 一条解析后的 SSE 事件 */
export interface SseEvent {
  event?: string
  data: string
}

interface PostSseOptions {
  /** 每收到一条 SSE 事件时回调 */
  onEvent: (event: SseEvent) => void
  /** 可用于中途取消请求 */
  signal?: AbortSignal
}

/**
 * 基于 fetch ReadableStream 的 POST SSE 请求工具。
 * 后端的创建会话 / 发送消息接口返回 text/event-stream，
 * axios 对流式响应支持有限，因此这里用原生 fetch 实现。
 */
export async function postSSE(url: string, body: unknown, options: PostSseOptions): Promise<void> {
  const token = getStoredToken()
  const res = await fetch(`${BASE_URL}${url}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal: options.signal,
  })
  if (!res.ok || !res.body) {
    if (res.status === 401) {
      window.dispatchEvent(new Event(AUTH_UNAUTHORIZED_EVENT))
    }
    throw new Error(`请求失败：${res.status} ${res.statusText}`)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    // SSE 事件之间以空行分隔
    const parts = buffer.split('\n\n')
    buffer = parts.pop() ?? ''
    for (const part of parts) {
      const event = parseEvent(part)
      if (event) options.onEvent(event)
    }
  }
  // 处理流结束时缓冲区中残留的事件
  const last = parseEvent(buffer)
  if (last) options.onEvent(last)
}

/** 解析单个 SSE 事件块（event: / data: 行） */
function parseEvent(raw: string): SseEvent | null {
  const trimmed = raw.trim()
  if (!trimmed) return null
  let event: string | undefined
  const dataLines: string[] = []
  for (const line of trimmed.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart())
    }
  }
  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}
