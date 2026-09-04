<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'
</script>

<template>
  <div id="basicLayout">
    <a-layout class="layout">
      <GlobalHeader />
      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
      <GlobalFooter />
    </a-layout>
  </div>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}

.content {
  padding: 24px;
  /* 给固定在底部的页脚留出空间，避免遮挡内容 */
  padding-bottom: 80px;
  background: #f5f5f5;
}
</style>
