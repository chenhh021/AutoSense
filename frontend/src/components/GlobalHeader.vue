<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { MenuProps } from 'ant-design-vue'
import logo from '@/assets/logo.png'
import { authState, signOut } from '@/stores/auth'

const router = useRouter()
const route = useRoute()

// 网站标题
const siteTitle = 'AutoSense'

// 菜单项配置，key 与路由 path 对应
const menuItems = computed<MenuProps['items']>(() => {
  const items: MenuProps['items'] = [{ key: '/', label: '首页' }]
  if (authState.isAuthenticated.value) items.push({ key: '/console', label: '控制台' })
  if (authState.isAdmin.value) items.push({ key: '/admin/users', label: '用户管理' })
  items.push({ key: '/about', label: '关于' })
  return items
})

// 当前选中的菜单项，按路由前缀匹配（如 /console/chat/123 命中 /console）
const selectedKeys = computed(() => {
  if (route.path === '/') return ['/']
  const match = menuItems.value?.find(
    (item) => String((item as any).key) !== '/' && route.path.startsWith(String((item as any).key)),
  )
  return match ? [String((match as any).key)] : [route.path]
})

// 点击菜单跳转路由
const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
  router.push(String(key))
}

const displayName = computed(() => {
  const user = authState.currentUser.value
  return user?.userName || user?.userAccount || '用户'
})

const handleUserMenu: MenuProps['onClick'] = async ({ key }) => {
  if (key === 'logout') {
    try {
      await signOut()
      message.success('已退出登录')
    } catch {
      message.warning('本地登录状态已清除')
    }
    await router.replace('/')
    return
  }
  await router.push(String(key))
}
</script>

<template>
  <a-layout-header class="header">
    <div class="header-left" @click="router.push('/')">
      <img class="logo" :src="logo" alt="logo" />
      <span class="title">{{ siteTitle }}</span>
    </div>
    <a-menu
      v-model:selectedKeys="selectedKeys"
      class="menu"
      mode="horizontal"
      :items="menuItems"
      @click="handleMenuClick"
    />
    <div class="header-right">
      <a-dropdown v-if="authState.isAuthenticated.value" placement="bottomRight">
        <button class="user-trigger" type="button">
          <a-avatar :size="32" :src="authState.currentUser.value?.userAvatar">
            {{ displayName.slice(0, 1).toUpperCase() }}
          </a-avatar>
          <span class="user-name">{{ displayName }}</span>
        </button>
        <template #overlay>
          <a-menu @click="handleUserMenu">
            <a-menu-item key="/profile">个人信息</a-menu-item>
            <a-menu-item v-if="authState.isAdmin.value" key="/admin/users">用户管理</a-menu-item>
            <a-menu-divider />
            <a-menu-item key="logout" danger>退出登录</a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
      <a-space v-else>
        <a-button @click="router.push('/register')">注册</a-button>
        <a-button type="primary" @click="router.push('/login')">登录</a-button>
      </a-space>
    </div>
  </a-layout-header>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  padding-inline: 24px;
  background: #fff;
  box-shadow: 0 1px 4px rgb(0 0 0 / 8%);
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  cursor: pointer;
}

.logo {
  width: 36px;
  height: 36px;
}

.title {
  margin-left: 12px;
  font-size: 18px;
  font-weight: 600;
  color: rgb(0 0 0 / 88%);
  white-space: nowrap;
}

.menu {
  flex: 1;
  min-width: 0;
  margin-inline: 24px;
  border-bottom: none;
}

.header-right {
  flex-shrink: 0;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  color: inherit;
  background: transparent;
  border: 0;
  border-radius: 8px;
  cursor: pointer;
}

.user-trigger:hover {
  background: #f5f5f5;
}

/* 小屏设备隐藏标题，菜单自动折叠为省略按钮，保证响应式 */
@media (max-width: 576px) {
  .header {
    padding-inline: 12px;
  }

  .title {
    display: none;
  }

  .menu {
    margin-inline: 8px;
  }

  .user-name {
    display: none;
  }
}
</style>
