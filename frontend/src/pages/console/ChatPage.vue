<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { messages as listMessages, get as getSession } from '@/api/sessionController'
import { postSSE } from '@/utils/sse'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  key?: string
  attemptId?: string
  loading?: boolean
}

const route = useRoute()
const router = useRouter()
const sessionId = ref<number | null>(null)
const messages = ref<ChatMessage[]>([])
const workflow = ref<API.WorkflowView>()
const input = ref('')
const sending = ref(false)
const loading = ref(false)
const accessDenied = ref(false)
const notice = ref('')
const simulated = ref(false)
const listRef = ref<HTMLElement>()
let generation = 0
let abort: AbortController | undefined
const terminalStates = new Set(['COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED'])
const active = computed(() => !!workflow.value?.status && !terminalStates.has(workflow.value.status))
const canSend = computed(() => !accessDenied.value && !loading.value && !sending.value && (!active.value || workflow.value?.status === 'WAITING_INPUT'))
const labels: Record<string, string> = {
  KNOWLEDGE_CONSULT: '知识咨询', DEVICE_QUERY: '设备查询', FAULT_DIAGNOSIS: '故障诊断', DEVICE_CONTROL: '设备控制',
  CREATED: '已接收', PLANNING: '正在规划', VALIDATING: '正在校验', RUNNING: '执行中', PENDING: '待执行',
  WAITING_APPROVAL: '等待确认', WAITING_INPUT: '等待补充信息', WAITING_RESUME: '等待继续', RETRYING: '超时重试中',
  COMPLETED: '已完成', SKIPPED: '条件不满足，已跳过', NOT_EXECUTED: '未执行', FAILED: '已失败', REJECTED: '已拒绝', CANCELLED: '已取消',
  setBrightness: '设置亮度', setColorTemperature: '设置色温', setPower: '设置电源', state: '读取状态', list: '查询设备列表', diagnostic_snapshot: '采集诊断信息',
}
const fieldLabels: Record<string, string> = {
  name: '设备名称', deviceNames: '设备名称', deviceRef: '设备编号', deviceRefs: '设备范围', deviceType: '设备类型', model: '型号', action: '操作',
  brightness: '亮度', colorTemperature: '色温', power: '电源', fields: '查询字段', clarification: '补充信息',
}
const failureLabels: Record<string, string> = {
  APPROVAL_REJECTED: '用户拒绝执行，后续步骤已停止。', DEVICE_RESULT_UNKNOWN: '命令执行结果尚不确定，系统不会自动重新下发。',
  CAPABILITY_NOT_AVAILABLE: '当前设备暂不支持这项操作。', EXECUTION_REFUSED: '当前账号或设备权限校验未通过。',
  REQUEST_TIMEOUT: '请求超时，已达到重试上限。', AI_SERVICE_UNAVAILABLE: '模型服务暂时不可用。',
  STEP_EXECUTION_FAILED: '步骤执行失败，请核对设备连接或服务状态。', WORKFLOW_TIMEOUT: '本次执行已超时。',
  INVALID_COMMAND_PARAMETERS: '设备操作参数无效。', COMMAND_NOT_ALLOWED: '该型号不允许此操作。', DEVICE_BUSY: '设备正由其他请求操作。',
  CANCELLED: '计划已取消，已完成结果仍然保留。', PLAN_INVALID: '无法生成有效计划，请重新描述需求。',
  WORKFLOW_CANCELLED: '计划已取消，已完成结果仍然保留。',
}
const label = (value?: string) => value ? labels[value] || value : ''
const failure = (value?: string) => value ? failureLabels[value] || `执行未完成（${value}）` : ''
const confirmationFields = computed(() => Object.entries(workflow.value?.approval?.parameters || {}).filter(([key]) => key !== 'bindingHash'))
const showValue = (value: unknown) => typeof value === 'string' ? label(value) : Array.isArray(value) ? value.join('、') : JSON.stringify(value)

onMounted(init)
watch(() => route.fullPath, init)
onBeforeUnmount(() => { generation++; abort?.abort() })

function init() {
  const id = route.params.sessionId ? Number(route.params.sessionId) : null
  if (id && sessionId.value === id) return
  const current = ++generation
  abort?.abort()
  sessionId.value = id
  messages.value = []
  workflow.value = undefined
  sending.value = false
  notice.value = ''
  accessDenied.value = false
  simulated.value = false
  const problem = typeof route.query.problem === 'string' ? route.query.problem.trim() : ''
  if (id) void refresh(current, true)
  else if (problem) void streamReply('/api/v1/sessions', { problem }, problem)
}

// Refresh is strictly read-only. It never approves or resumes a workflow.
async function refresh(current = generation, history = false) {
  const id = sessionId.value
  if (!id) return
  loading.value = true
  try {
    const detail = await getSession({ sessionId: id }) as unknown as API.SessionResponse
    if (current !== generation) return
    accessDenied.value = false
    workflow.value = detail.workflow
    if (history) {
      const rows = await listMessages({ sessionId: id }) as unknown as API.ChatMessageView[]
      if (current !== generation) return
      messages.value = (rows || []).filter(row => ['USER', 'ASSISTANT'].includes(row.role?.toUpperCase() || '')).map<ChatMessage>(row => ({
        role: row.role?.toUpperCase() === 'USER' ? 'user' : 'assistant', content: row.content || '',
      })).filter((row, index, all) => row.role !== 'assistant' || index === 0
        || all[index - 1]?.role !== 'assistant' || all[index - 1]?.content !== row.content)
      if (!messages.value.length && detail.reply) messages.value.push({ role: 'assistant', content: detail.reply })
    }
    if (!detail.workflow && detail.awaitingInput) notice.value = '这是旧版历史对话，请输入新问题开始新的处理。'
    void scrollToBottom()
  } catch (error) {
    if (current === generation) {
      const status = axios.isAxiosError(error) ? error.response?.status : undefined
      accessDenied.value = status === 403 || status === 404
      if (accessDenied.value) { messages.value = []; workflow.value = undefined }
      notice.value = status === 403 ? '无权访问此会话。' : status === 404 ? '此会话不存在。' : '暂时无法读取当前状态，请点击“刷新状态”。'
    }
  } finally { if (current === generation) loading.value = false }
}

function stepMessage(key: string): ChatMessage {
  let item = messages.value.find(value => value.key === key)
  if (!item) {
    messages.value.push({ role: 'assistant', content: '', key, loading: true })
    item = messages.value[messages.value.length - 1]!
  }
  return item
}

function applyWorkflow(event: API.WorkflowEvent) {
  const data = event.data
  if (!data?.requestId || !data.conversationId) return
  if (!sessionId.value) {
    sessionId.value = data.conversationId
    void router.replace(`/console/chat/${data.conversationId}`)
  }
  const payload = data.payload || {}
  if (workflow.value?.requestId !== data.requestId) workflow.value = { requestId: data.requestId, steps: [] }
  const view = workflow.value!
  const transient = event.type === 'TEXT' || event.type === 'TEXT_RESET'
  if (!transient && (data.version ?? 0) < (view.version ?? 0)) return
  if (!transient) {
    view.status = data.status
    view.version = data.version
    view.progress = data.progress
    view.conversationId = data.conversationId
    if (data.status !== 'WAITING_APPROVAL') view.approval = undefined
  }
  if (payload.simulated === true || event.code?.startsWith('STUB_')) simulated.value = true
  if (data.stepId && data.stepType) {
    const steps = view.steps ||= []
    let step = steps.find(item => item.stepId === data.stepId)
    if (!step) { step = { stepId: data.stepId, type: data.stepType }; steps.push(step) }
    view.currentStep = steps.indexOf(step)
    if (event.type === 'STEP_RESULT') {
      step.status = payload.failureCode ? 'FAILED' : event.code?.endsWith('SKIPPED') ? 'SKIPPED' : 'COMPLETED'
      step.result = payload
      step.failureCode = payload.failureCode
      step.resultCertainty = payload.effectCertainty
    } else if (data.status === 'WAITING_APPROVAL' || data.status === 'RETRYING' || data.status === 'RUNNING') step.status = data.status
  }
  const key = `${data.requestId}:${data.stepId || 'summary'}`
  if (event.type === 'TEXT') {
    const item = stepMessage(key)
    if (item.attemptId && item.attemptId !== payload.attemptId) item.content = ''
    item.attemptId = payload.attemptId
    item.content += typeof payload.text === 'string' ? payload.text : ''
  } else if (event.type === 'TEXT_RESET') {
    const item = messages.value.find(value => value.key === key)
    if (item && (!item.attemptId || item.attemptId === payload.attemptId)) item.content = ''
  } else if (event.type === 'STEP_RESULT') {
    const item = stepMessage(key)
    item.content = payload.failureCode ? failure(payload.failureCode) : payload.answer || payload.diagnosis || event.message || ''
    item.loading = false
  } else if (event.type === 'CONFIRM') {
    view.approval = { approvalId: payload.approvalId, stepId: payload.stepId || data.stepId, prompt: event.message,
      expiresAt: payload.expiresAt, operation: payload.operation, parameters: payload.parameters }
  } else if (event.type === 'CLARIFY' || event.type === 'AWAITING') {
    view.inputRequestId = payload.inputRequestId
    view.prompt = payload.prompt || event.message
  } else if (event.type === 'ERROR') {
    view.failureCode = payload.failureCode || event.code
    notice.value = failure(view.failureCode)
  } else if (event.type === 'CONCLUSION' && !(view.steps || []).length) {
    stepMessage(key).content = event.message || ''
  }
}

async function streamReply(url: string, body: object, content?: string) {
  if (sending.value) return
  const current = generation
  sending.value = true
  notice.value = ''
  if (content) messages.value.push({ role: 'user', content })
  abort = new AbortController()
  const seen = new Set<string>()
  let modern = false
  try {
    await postSSE(url, body, { signal: abort.signal, onEvent: event => {
      if (current !== generation) return
      let data: Record<string, any>
      try { data = JSON.parse(event.data) } catch { return }
      if (event.event === 'workflow') {
        modern = true
        const id = data.data?.eventId
        if (id && seen.has(id)) return
        if (id) seen.add(id)
        applyWorkflow(data as API.WorkflowEvent)
      } else if (!modern) {
        // Older servers have only legacy events. A modern stream must never duplicate them.
        if (data.sessionId && !sessionId.value) { sessionId.value = data.sessionId; void router.replace(`/console/chat/${data.sessionId}`) }
        const item = stepMessage('legacy')
        if (event.event === 'token') item.content += data.text || ''
        if (event.event === 'awaiting') item.content = data.prompt || ''
        if (event.event === 'conclusion') item.content = data.summary || item.content
        if (event.event === 'error') item.content = data.message || '请求失败，请刷新状态。'
      }
      void scrollToBottom()
    } })
  } catch (error) {
    if (current === generation && !abort.signal.aborted) {
      notice.value = `${error instanceof Error ? error.message : '连接中断'}；正在查询当前状态，请勿重复发送。`
    }
  } finally {
    if (current === generation) {
      messages.value.forEach(item => { item.loading = false })
      await refresh(current, true)
      sending.value = false
      void scrollToBottom()
    }
  }
}

function approve(approved: boolean) {
  const view = workflow.value
  if (sending.value || !view?.approval?.approvalId || !view.approval.stepId || view.version === undefined) return
  const body: API.WorkflowApprovalRequest = { stepId: view.approval.stepId, approvalId: view.approval.approvalId, approved, expectedVersion: view.version }
  void streamReply(`/api/v1/sessions/${sessionId.value}/workflows/${view.requestId}/approval`, body)
}
function act(action: 'resume' | 'cancel') {
  const view = workflow.value
  if (sending.value || !view?.requestId || view.version === undefined) return
  const body: API.WorkflowResumeRequest = { expectedVersion: view.version }
  void streamReply(`/api/v1/sessions/${sessionId.value}/workflows/${view.requestId}/${action}`, body)
}
function send() {
  const content = input.value.trim()
  if (!content || !canSend.value) return
  input.value = ''
  if (!sessionId.value) { void streamReply('/api/v1/sessions', { problem: content } satisfies API.CreateSessionRequest, content); return }
  const body: API.MessageRequest = { content }
  if (workflow.value?.status === 'WAITING_INPUT') {
    body.inputRequestId = workflow.value.inputRequestId
    body.expectedVersion = workflow.value.version
  }
  void streamReply(`/api/v1/sessions/${sessionId.value}/messages`, body, content)
}
async function scrollToBottom() {
  await nextTick()
  if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
}
</script>

<template>
  <div class="chat-page">
    <div v-if="workflow || sessionId" class="workflow-header">
      <div><strong>执行进度</strong> <a-tag v-if="workflow" :color="workflow.status === 'FAILED' ? 'error' : 'blue'">{{ label(workflow.status) }}</a-tag>
        <span v-if="workflow?.progress?.total">{{ workflow.progress.completed || 0 }} / {{ workflow.progress.total }} 步已完成</span>
        <span v-if="workflow?.progress?.skipped"> · 已跳过 {{ workflow.progress.skipped }} 步</span>
        <a-tag v-if="simulated" color="orange">模拟流程</a-tag>
      </div>
      <a-space>
        <a-button size="small" :loading="loading" :disabled="sending" @click="refresh(generation, true)">刷新状态</a-button>
        <a-button v-if="active" size="small" danger :disabled="sending || loading" @click="act('cancel')">取消计划</a-button>
      </a-space>
    </div>
    <div ref="listRef" class="message-list" aria-live="polite">
      <a-empty v-if="messages.length === 0 && !sending" description="输入问题，开始新的对话" />
      <div v-for="(item, index) in messages" :key="item.key || index" class="message-row" :class="item.role">
        <div class="bubble"><span class="content">{{ item.content }}</span><a-spin v-if="item.loading" size="small" /></div>
      </div>
      <a-spin v-if="sending && !messages.some(item => item.loading)" tip="正在处理" />
      <div v-if="workflow?.steps?.length" class="steps-panel">
        <details v-for="(step, index) in workflow.steps" :key="step.stepId" :open="step.status === 'FAILED'">
          <summary>步骤 {{ index + 1 }} · {{ label(step.type) }} <span>{{ label(step.status) }}</span></summary>
          <p v-if="step.failureCode">{{ failure(step.failureCode) }}</p>
          <p v-if="step.resultCertainty === 'UNKNOWN'">命令结果尚不确定，不会自动重新执行。</p>
          <p v-if="step.result?.verification === 'NOT_PERFORMED'">控制已成功，尚未复检设备状态。</p>
          <p v-if="step.result?.answer" class="content">{{ step.result.answer }}</p>
        </details>
      </div>
    </div>
    <div class="interaction-area">
      <a-alert v-if="notice" type="warning" show-icon :message="notice" />
      <a-alert v-if="workflow?.status === 'WAITING_RESUME'" type="info" show-icon message="执行已暂停，已保存的结果仍然保留。">
        <template #description><a-button type="primary" :disabled="sending || loading || !workflow.canResume" @click="act('resume')">继续执行</a-button></template>
      </a-alert>
      <section v-if="workflow?.approval && workflow.status === 'WAITING_APPROVAL'" class="approval-card" aria-label="设备步骤确认">
        <strong>确认{{ label(workflow.approval.operation) }}</strong>
        <p>{{ workflow.approval.prompt }}</p>
        <dl><template v-for="[key, value] in confirmationFields" :key="key"><dt>{{ fieldLabels[key] || key }}</dt><dd>{{ showValue(value) }}</dd></template></dl>
        <p class="muted" v-if="workflow.approval.expiresAt">确认有效期至 {{ new Date(workflow.approval.expiresAt).toLocaleString() }}</p>
        <a-space><a-button type="primary" :disabled="sending || loading" @click="approve(true)">确认执行</a-button>
          <a-button :disabled="sending || loading" @click="approve(false)">拒绝执行</a-button></a-space>
      </section>
      <a-alert v-if="workflow?.status === 'WAITING_INPUT'" type="info" :message="workflow.prompt || '请补充本步骤所需的信息。'" />
      <div class="input-area">
        <a-textarea v-model:value="input" :rows="2" :disabled="!canSend" aria-label="对话内容"
          :placeholder="active && workflow?.status !== 'WAITING_INPUT' ? '请先处理当前计划的确认或继续操作' : '输入你的问题，Enter 发送，Shift + Enter 换行'"
          @keydown.enter.exact.prevent="send" />
        <a-button type="primary" :loading="sending" :disabled="!input.trim() || !canSend" @click="send">发送</a-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-page { display: flex; flex-direction: column; height: calc(100vh - 180px); min-height: 480px; background: #fff; border: 1px solid #e8edf3; border-radius: 10px; overflow: hidden; }
.workflow-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; padding: 14px 20px; border-bottom: 1px solid #e8edf3; }
.workflow-header strong { margin-right: 12px; }
.workflow-header span { font-size: 13px; }
.message-list { flex: 1; min-height: 100px; overflow: auto; padding: 22px; }
.message-row { display: flex; margin-bottom: 16px; }
.message-row.user { justify-content: flex-end; }
.bubble { max-width: 85%; padding: 12px 16px; background: #f3f6fa; border-radius: 10px; overflow-wrap: anywhere; }
.user .bubble { background: #e6f4ff; }
.content { white-space: pre-wrap; line-height: 1.7; }
.steps-panel { border-top: 1px solid #e8edf3; margin-top: 20px; padding-top: 12px; }
details { border-bottom: 1px solid #f0f0f0; padding: 10px 0; }
summary { cursor: pointer; font-weight: 500; }
summary span { margin-left: 12px; color: #5c687a; font-size: 13px; }
details p { margin: 10px 0 0; }
.interaction-area { padding: 12px 20px 18px; border-top: 1px solid #e8edf3; display: grid; gap: 12px; max-height: 52%; overflow: auto; }
.approval-card { border: 1px solid #91caff; border-radius: 8px; padding: 14px; background: #f5faff; }
.approval-card p { margin: 8px 0; }
dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 18px; margin: 12px 0; font-size: 13px; }
dt { color: #59677a; } dd { margin: 0; overflow-wrap: anywhere; }
.muted { color: #64748b; font-size: 12px; }
.input-area { display: flex; align-items: flex-end; gap: 12px; }
@media (max-width: 640px) { .chat-page { height: calc(100dvh - 120px); } .message-list { padding: 14px; } .bubble { max-width: 95%; } .workflow-header, .interaction-area { padding: 12px; } }
</style>
