<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { deleteSession, listSessions } from '@/api/custom'

const router = useRouter()

const sessions = ref<API.SessionListItemView[]>([])
const loading = ref(false)

const fetchSessions = async () => {
  loading.value = true
  try {
    sessions.value = await listSessions()
  } catch (e) {
    message.error('加载对话列表失败')
  } finally {
    loading.value = false
  }
}

onMounted(fetchSessions)

// 点击对话，进入对话窗口继续历史对话
const continueSession = (session: API.SessionListItemView) => {
  if (!session.sessionId) return
  router.push(`/console/chat/${session.sessionId}`)
}

const handleDelete = async (session: API.SessionListItemView) => {
  if (!session.sessionId) return
  try {
    await deleteSession(session.sessionId)
    message.success('删除成功')
    fetchSessions()
  } catch (e) {
    message.error('删除对话失败')
  }
}

// 新建对话：进入空白的对话窗口，输入第一条消息时自动创建会话
const createSession = () => {
  router.push('/console/chat')
}

const formatTime = (time?: string) => (time ? new Date(time).toLocaleString() : '')
</script>

<template>
  <div class="sessions-page">
    <div class="toolbar">
      <h2 class="page-title">对话管理</h2>
      <a-button type="primary" @click="createSession">新建对话</a-button>
    </div>

    <a-list :data-source="sessions" :loading="loading" item-layout="horizontal">
      <template #renderItem="{ item }">
        <a-list-item class="session-item" @click="continueSession(item)">
          <a-list-item-meta :description="formatTime(item.updatedAt ?? item.createdAt)">
            <template #title>
              <span>{{ item.preview || '新对话' }}</span>
              <a-tag v-if="item.status" class="status-tag">{{ item.status }}</a-tag>
            </template>
          </a-list-item-meta>
          <template #actions>
            <a-popconfirm
              title="确定删除该对话吗？"
              ok-text="删除"
              cancel-text="取消"
              @confirm="handleDelete(item)"
            >
              <a-button type="link" danger size="small" @click.stop>删除</a-button>
            </a-popconfirm>
          </template>
        </a-list-item>
      </template>
      <template #empty>
        <a-empty description="暂无对话，点击右上角新建对话" />
      </template>
    </a-list>
  </div>
</template>

<style scoped>
.sessions-page {
  padding: 24px;
  background: #fff;
  border-radius: 8px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 18px;
}

.session-item {
  cursor: pointer;
}

.session-item:hover {
  background: #fafafa;
}

.status-tag {
  margin-left: 8px;
}
</style>
