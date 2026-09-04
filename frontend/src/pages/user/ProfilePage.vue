<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { authState, signOut } from '@/stores/auth'

const router = useRouter()
const user = computed(() => authState.currentUser.value)
const displayName = computed(() => user.value?.userName || user.value?.userAccount || '用户')
const avatarText = computed(() => displayName.value.slice(0, 1).toUpperCase())

const handleLogout = async () => {
  try {
    await signOut()
    message.success('已安全退出登录')
    await router.replace('/')
  } catch {
    message.warning('本地登录状态已清除')
    await router.replace('/')
  }
}
</script>

<template>
  <div class="profile-page">
    <a-card class="profile-card" :bordered="false">
      <div class="profile-header">
        <a-avatar :size="72" :src="user?.userAvatar">
          {{ avatarText }}
        </a-avatar>
        <div>
          <div class="profile-name-row">
            <h1>{{ displayName }}</h1>
            <a-tag :color="user?.userRole?.toLowerCase() === 'admin' ? 'blue' : 'default'">
              {{ user?.userRole?.toLowerCase() === 'admin' ? '管理员' : '普通用户' }}
            </a-tag>
          </div>
          <p>@{{ user?.userAccount }}</p>
        </div>
      </div>

      <a-divider />

      <a-descriptions title="基本信息" :column="1" bordered>
        <a-descriptions-item label="用户 ID">{{ user?.id ?? '-' }}</a-descriptions-item>
        <a-descriptions-item label="登录账号">{{ user?.userAccount ?? '-' }}</a-descriptions-item>
        <a-descriptions-item label="用户昵称">{{ user?.userName || '未设置' }}</a-descriptions-item>
        <a-descriptions-item label="个人简介">{{ user?.userProfile || '未设置' }}</a-descriptions-item>
        <a-descriptions-item label="账号角色">
          {{ user?.userRole?.toLowerCase() === 'admin' ? '管理员（ADMIN）' : '普通用户（USER）' }}
        </a-descriptions-item>
      </a-descriptions>

      <div class="profile-actions">
        <a-button v-if="authState.isAdmin.value" @click="router.push('/admin/users')">
          用户管理
        </a-button>
        <a-button danger @click="handleLogout">退出登录</a-button>
      </div>
    </a-card>
  </div>
</template>

<style scoped>
.profile-page {
  max-width: 840px;
  margin: 0 auto;
}

.profile-card {
  border-radius: 14px;
  box-shadow: 0 8px 32px rgb(15 35 62 / 8%);
}

.profile-header {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile-name-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.profile-name-row h1 {
  margin: 0;
  font-size: 26px;
}

.profile-header p {
  margin: 6px 0 0;
  color: rgb(0 0 0 / 45%);
}

.profile-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

@media (max-width: 576px) {
  .profile-header {
    align-items: flex-start;
  }

  .profile-actions {
    flex-direction: column;
  }
}
</style>
