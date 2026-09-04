<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const problem = ref('')

// 提交后跳转到对话窗口，由对话页创建会话并通过 SSE 实时输出 AI 回复
const startChat = () => {
  const value = problem.value.trim()
  if (!value) return
  router.push({
    path: '/console/chat',
    query: { problem: value },
  })
}
</script>

<template>
  <div class="home-page">
    <h1 class="title">AutoSense</h1>
    <p class="subtitle">AI 驱动的 IoT 设备智能诊断与维修助手</p>
    <div class="input-card">
      <a-textarea
        v-model:value="problem"
        :rows="4"
        placeholder="描述你的设备问题，例如：家里的路由器频繁掉线怎么办？"
        @keydown.enter.exact.prevent="startChat"
      />
      <a-button
        class="submit-btn"
        type="primary"
        size="large"
        :disabled="!problem.trim()"
        @click="startChat"
      >
        进入
      </a-button>
    </div>
  </div>
</template>

<style scoped>
.home-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 50vh;
  padding: 48px 16px;
}

.title {
  font-size: 40px;
  font-weight: 700;
  margin-bottom: 8px;
}

.subtitle {
  color: rgb(0 0 0 / 45%);
  margin-bottom: 32px;
}

.input-card {
  width: 100%;
  max-width: 640px;
  padding: 16px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgb(0 0 0 / 8%);
}

.submit-btn {
  margin-top: 12px;
  float: right;
}
</style>
