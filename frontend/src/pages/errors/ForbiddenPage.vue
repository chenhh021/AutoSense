<script setup lang="ts">
import { useRouter } from 'vue-router'
import { authState } from '@/stores/auth'

const router = useRouter()
</script>

<template>
  <div class="forbidden-page">
    <a-result
      status="403"
      title="403"
      sub-title="你没有访问此管理页面的权限。"
    >
      <template #extra>
        <a-space>
          <a-button @click="router.back()">返回上一页</a-button>
          <a-button type="primary" @click="router.replace(authState.isAuthenticated.value ? '/profile' : '/')">
            返回安全页面
          </a-button>
        </a-space>
      </template>
    </a-result>
  </div>
</template>

<style scoped>
.forbidden-page {
  display: grid;
  place-items: center;
  min-height: calc(100vh - 64px - 128px);
  background: #fff;
  border-radius: 14px;
}
</style>
