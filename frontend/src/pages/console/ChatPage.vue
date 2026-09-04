<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { listMessages } from '@/api/sessionController'
import { postSSE } from '@/utils/sse'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  /** 是否正在流式输出中 */
  loading?: boolean
}

const route = useRoute()
const router = useRouter()

const sessionId = ref<number | null>(null)
const messages = ref<ChatMessage[]>([])
const input = ref('')
const sending = ref(false)
const listRef = ref<HTMLElement>()

onMounted(init)
watch(() => route.fullPath, init)

// 根据路由初始化：带 problem 进入 = 创建新会话；带 sessionId = 继续历史对话；否则为空白新对话
function init() {
  const paramId = route.params.sessionId ? Number(route.params.sessionId) : null
  const problem = typeof route.query.problem === 'string' ? route.query.problem.trim() : ''

  // 创建会话流程中我们自己 router.replace 带上了 sessionId，避免重复初始化
  if (paramId && sessionId.value === paramId) return

  sessionId.value = paramId
  messages.value = []
  if (problem && !paramId) {
    startNewSession(problem)
  } else if (paramId) {
    loadHistory(paramId)
  }
}

/** 加载历史消息 */
async function loadHistory(id: number) {
  try {
    const list = await listMessages({ sessionId: id })
    messages.value = ((list as unknown as API.ChatMessageView[]) ?? []).map((item) => ({
      // 后端 role 可能是 user/assistant 或 USER/ASSISTANT，统一为小写
      role: item.role?.toLowerCase() === 'user' ? 'user' : 'assistant',
      content: item.content ?? '',
    }))
    scrollToBottom()
  } catch (e) {
    message.error('加载历史消息失败')
  }
}

/** 创建新会话：初始提示词作为第一条消息，SSE 实时输出 AI 回复 */
async function startNewSession(problem: string) {
  await streamReply([{ role: 'user', content: problem }], '/api/v1/sessions', { problem })
}

/** 在已有会话中发送消息 */
async function sendMessage(content: string) {
  await streamReply(
    [{ role: 'user', content }],
    `/api/v1/sessions/${sessionId.value}/messages`,
    { content },
  )
}

/** 通用的流式回复流程：追加用户消息与占位的 AI 消息，逐段填充 AI 回复 */
async function streamReply(
  userMessages: ChatMessage[],
  url: string,
  body: Record<string, unknown>,
) {
  if (sending.value) return
  sending.value = true
  messages.value.push(...userMessages)
  const aiMessage: ChatMessage = { role: 'assistant', content: '', loading: true }
  messages.value.push(aiMessage)
  scrollToBottom()

  try {
    await postSSE(url, body, {
      onEvent: (event) => {
        const data = parseEventData(event.data)
        // 流中会携带 sessionId，拿到后同步到路由，便于刷新后继续对话
        const id = data?.sessionId
        if (typeof id === 'number' && id > 0 && !sessionId.value) {
          sessionId.value = id
          router.replace(`/console/chat/${id}`)
        }
        // 后端 SSE 事件类型：token / status / awaiting / conclusion / error
        switch (event.event) {
          case 'token':
            // 模型流式输出片段，逐段追加
            if (typeof data?.text === 'string') aiMessage.content += data.text
            break
          case 'awaiting':
            // 等待用户输入：prompt 即 AI 的追问
            if (typeof data?.prompt === 'string') aiMessage.content = data.prompt
            break
          case 'conclusion':
            aiMessage.content = formatConclusion(data) || aiMessage.content
            break
          case 'error':
            aiMessage.content =
              typeof data?.message === 'string'
                ? `出错了：${data.message}`
                : '出错了，请稍后重试'
            break
          // status 事件仅用于同步 sessionId，不渲染
        }
        scrollToBottom()
      },
    })
    if (!aiMessage.content) aiMessage.content = '（AI 本次没有回复内容）'
  } catch (e) {
    aiMessage.content = aiMessage.content || '请求失败，请稍后重试'
    message.error('对话请求失败，请稍后重试')
  } finally {
    aiMessage.loading = false
    sending.value = false
    scrollToBottom()
  }
}

/** 解析 SSE data 为 JSON 对象；解析失败返回 null */
function parseEventData(data: string): Record<string, any> | null {
  try {
    const obj = JSON.parse(data)
    return obj && typeof obj === 'object' ? obj : null
  } catch {
    return null
  }
}

/** 终态结论渲染为文本：总结 + 人工步骤 + 售后网点 */
function formatConclusion(data: Record<string, any> | null): string {
  if (!data) return ''
  const conclusion = data as API.ConclusionDto
  const parts: string[] = []
  if (conclusion.summary) parts.push(conclusion.summary)
  if (conclusion.manualSteps?.length) {
    parts.push(
      '人工处理步骤：\n' +
        conclusion.manualSteps.map((step, i) => `${i + 1}. ${step}`).join('\n'),
    )
  }
  if (conclusion.afterSales?.length) {
    parts.push(
      '推荐售后网点：\n' +
        conclusion.afterSales
          .map((item) => `${item.name}（${item.address}）`)
          .join('\n'),
    )
  }
  return parts.join('\n\n')
}

/** 发送输入框内容：无会话时走创建会话流程，否则在已有会话中发送 */
const send = () => {
  const content = input.value.trim()
  if (!content || sending.value) return
  input.value = ''
  if (sessionId.value) {
    sendMessage(content)
  } else {
    startNewSession(content)
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (listRef.value) {
    listRef.value.scrollTop = listRef.value.scrollHeight
  }
}
</script>

<template>
  <div class="chat-page">
    <div ref="listRef" class="message-list">
      <a-empty
        v-if="messages.length === 0"
        class="empty"
        description="输入问题，开始新的对话"
      />
      <div
        v-for="(msg, index) in messages"
        :key="index"
        class="message-row"
        :class="msg.role"
      >
        <div class="bubble">
          <a-spin v-if="msg.loading && !msg.content" size="small" />
          <span class="content">{{ msg.content }}</span><span v-if="msg.loading && msg.content" class="cursor">▍</span>
        </div>
      </div>
    </div>
    <div class="input-area">
      <a-textarea
        v-model:value="input"
        :rows="2"
        placeholder="输入你的问题，Enter 发送，Shift + Enter 换行"
        @keydown.enter.exact.prevent="send"
      />
      <a-button
        type="primary"
        :loading="sending"
        :disabled="!input.trim()"
        @click="send"
      >
        发送
      </a-button>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  /* 视口高度减去顶部导航、布局内边距与底部页脚的空间 */
  height: calc(100vh - 64px - 48px - 120px);
  min-height: 400px;
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.empty {
  margin-top: 80px;
}

.message-row {
  display: flex;
  margin-bottom: 16px;
}

.message-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 75%;
  padding: 10px 14px;
  border-radius: 8px;
  background: #f5f5f5;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-row.user .bubble {
  background: #1677ff;
  color: #fff;
}

.cursor {
  animation: blink 1s step-start infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.input-area {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  padding: 16px;
  border-top: 1px solid #f0f0f0;
}
</style>
