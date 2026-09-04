<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MenuProps } from 'ant-design-vue'

const router = useRouter()
const route = useRoute()

// 侧边功能标签页配置，key 与路由 path 对应
const menuItems: MenuProps['items'] = [
  { key: '/console/chat', label: '对话窗口' },
  { key: '/console/sessions', label: '对话管理' },
  { key: '/console/devices', label: '设备管理' },
]

// 当前选中的标签页，按路由前缀匹配（如 /console/chat/123 命中 /console/chat）
const selectedKeys = computed(() => {
  const match = menuItems?.find((item) => route.path.startsWith(String((item as any).key)))
  return match ? [String((match as any).key)] : []
})

const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
  router.push(String(key))
}
</script>

<template>
  <div class="console-page">
    <a-layout class="console-layout">
      <!-- 侧面功能标签页，小屏自动折叠 -->
      <a-layout-sider
        theme="light"
        :width="200"
        breakpoint="lg"
        collapsed-width="0"
        class="sider"
      >
        <a-menu
          mode="inline"
          :selected-keys="selectedKeys"
          :items="menuItems"
          class="sider-menu"
          @click="handleMenuClick"
        />
      </a-layout-sider>
      <a-layout-content class="console-content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </div>
</template>

<style scoped>
.console-layout {
  background: transparent;
}

.sider {
  background: #fff;
  border-radius: 8px;
  margin-right: 16px;
  overflow: hidden;
}

.sider-menu {
  border-inline-end: none;
}

.console-content {
  min-width: 0;
}
</style>
