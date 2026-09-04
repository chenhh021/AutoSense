import { createApp } from 'vue'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import App from './App.vue'
import router from './router'
import { clearAuth } from '@/stores/auth'
import { AUTH_UNAUTHORIZED_EVENT } from '@/utils/authStorage'

const app = createApp(App)

app.use(Antd)
app.use(router)

window.addEventListener(AUTH_UNAUTHORIZED_EVENT, () => {
  clearAuth()
  const route = router.currentRoute.value
  if (route.meta.requiresAuth && route.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: route.fullPath } })
  }
})

app.mount('#app')
